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
 * Keeps one WebSocket to the server for call setup. The socket carries only routing data and
 * ciphertext. Authentication happens with the first frame, not in the URL.
 */
public final class Signal {

    public interface Listener {
        void onCallMessage(JSONObject m);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .pingInterval(25, TimeUnit.SECONDS)
            .build();
    private static final Runnable RECONNECT = Signal::open;

    private static volatile Listener listener;
    private static volatile boolean ready = false;
    private static volatile boolean wantConnected = false;
    private static volatile String token;
    private static WebSocket socket;

    private Signal() {}

    public static void connect(String tok) {
        token = tok;
        wantConnected = true;
        if (socket == null) open();
    }

    public static void disconnect() {
        wantConnected = false;
        MAIN.removeCallbacks(RECONNECT);
        if (socket != null) socket.close(1000, "bye");
        socket = null;
        ready = false;
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

    private static void open() {
        if (!wantConnected || token == null) return;
        String url = BuildConfig.API_BASE.replaceFirst("^https", "wss") + "/ws";
        socket = HTTP.newWebSocket(new Request.Builder().url(url).build(), new WebSocketListener() {
            @Override
            public void onOpen(WebSocket ws, Response response) {
                try {
                    ws.send(new JSONObject().put("type", "auth").put("token", token).toString());
                } catch (Exception e) {
                    ws.cancel();
                }
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
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
                ready = false;
                socket = null;
                scheduleReconnect();
            }

            @Override
            public void onClosed(WebSocket ws, int code, String reason) {
                ready = false;
                socket = null;
                if (code != 1000) scheduleReconnect();
            }
        });
    }

    private static void scheduleReconnect() {
        if (wantConnected) MAIN.postDelayed(RECONNECT, 5000);
    }
}
