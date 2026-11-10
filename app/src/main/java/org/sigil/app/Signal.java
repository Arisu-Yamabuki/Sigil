package org.sigil.app;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * One WebSocket to the server for call setup. It reconnects by itself, and sendWithRetry waits
 * for the connection before giving up, so a call never fails silently after a dropped connection.
 */
public final class Signal {

    public interface Listener {
        void onCallMessage(JSONObject m);
    }

    private static final long STALE_MS = 10_000;
    private static final long RETRY_DELAY_MS = 3_000;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .pingInterval(25, TimeUnit.SECONDS)
            .build();
    private static final Runnable RECONNECT = Signal::ensureConnected;

    private static volatile Listener listener;
    private static volatile boolean ready = false;
    private static volatile boolean wantConnected = false;
    private static volatile String token;
    private static volatile WebSocket socket;
    private static volatile long openedAt;

    private Signal() {}

    public static void connect(String tok) {
        token = tok;
        wantConnected = true;
        ensureConnected();
    }

    public static void disconnect() {
        wantConnected = false;
        MAIN.removeCallbacks(RECONNECT);
        WebSocket s = socket;
        socket = null;
        ready = false;
        if (s != null) s.close(1000, "bye");
        token = null;
    }

    public static void setListener(Listener l) {
        listener = l;
    }

    /** Clears the listener only if it is still the one that set it. */
    public static void releaseListener(Listener l) {
        if (listener == l) listener = null;
    }

    public static boolean send(JSONObject o) {
        WebSocket s = socket;
        return ready && s != null && s.send(o.toString());
    }

    /** Reconnects if needed, then sends. Gives up after six seconds. */
    public static boolean sendWithRetry(JSONObject o) throws InterruptedException {
        long end = System.currentTimeMillis() + 6000;
        while (true) {
            ensureConnected();
            if (send(o)) return true;
            if (System.currentTimeMillis() > end) return false;
            Thread.sleep(250);
        }
    }

    /** Opens a connection when there is none, or when the last attempt has been stuck too long. */
    public static synchronized void ensureConnected() {
        if (!wantConnected || token == null || ready) return;
        boolean stale = socket == null || System.currentTimeMillis() - openedAt > STALE_MS;
        if (!stale) return;
        WebSocket old = socket;
        socket = null;
        if (old != null) old.cancel();
        open();
    }

    private static void open() {
        openedAt = System.currentTimeMillis();
        String url = BuildConfig.API_BASE.replaceFirst("^https", "wss") + "/ws";
        WebSocket created = HTTP.newWebSocket(new Request.Builder().url(url).build(), new WebSocketListener() {
            @Override
            public void onOpen(WebSocket ws, Response response) {
                if (ws != socket) return;
                try {
                    ws.send(new JSONObject().put("type", "auth").put("token", token).toString());
                } catch (Exception e) {
                    ws.cancel();
                }
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                if (ws != socket) return;
                try {
                    JSONObject m = new JSONObject(text);
                    String type = m.optString("type");
                    if ("ready".equals(type)) {
                        ready = true;
                        return;
                    }
                    if ("call".equals(type) || "error".equals(type)) {
                        Listener l = listener;
                        if (l != null) MAIN.post(() -> l.onCallMessage(m));
                    }
                } catch (Exception ignored) {
                    // Malformed frames are dropped.
                }
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                if (ws != socket) return;
                drop();
            }

            @Override
            public void onClosed(WebSocket ws, int code, String reason) {
                if (ws != socket) return;
                drop();
            }
        });
        socket = created;
    }

    private static void drop() {
        ready = false;
        socket = null;
        if (wantConnected) MAIN.postDelayed(RECONNECT, RETRY_DELAY_MS);
    }
}
