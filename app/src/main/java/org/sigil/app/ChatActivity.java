package org.sigil.app;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.bouncycastle.openpgp.PGPPublicKey;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ChatActivity extends AppCompatActivity {
    private static final int POLL_MS = 3000;

    private static final int[] CHAT_VALUES = {0, 10, 60, 3600, 86400, 604800};
    private static final String[] CHAT_LABELS = {"Off", "10 seconds", "1 minute", "1 hour", "1 day", "1 week"};

    private static final int[] NEXT_VALUES = {-1, 0, 10, 60, 3600, 86400};
    private static final String[] NEXT_LABELS = {"Chat timer default", "Off for this message", "10 seconds", "1 minute", "1 hour", "1 day"};

    private String peer, token;
    private Pgp.Identity identity;
    private PGPPublicKey peerKey, selfKey;
    private int chatTtl = 0;
    private int nextTtl = -1;
    private boolean polling = false;
    private boolean visible = false;
    private boolean blockedPeer = false;

    // Decrypted text lives only in memory and is cleared when the screen stops.
    private final Map<String, String> cache = new HashMap<>();
    private final List<MessageAdapter.Msg> items = new ArrayList<>();

    private MessageAdapter adapter;
    private RecyclerView list;
    private EditText input;
    private Button sendBtn;
    private MaterialButton chatTimerBtn, nextTimerBtn;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            poll();
            handler.postDelayed(this, POLL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_chat);
        EdgeToEdge.apply(this);
        findViewById(R.id.back).setOnClickListener(v -> finish());

        identity = Session.identity();
        peer = getIntent().getStringExtra("user");
        token = Session.token(this);
        if (identity == null || peer == null || token == null) {
            finish();
            return;
        }

        ((TextView) findViewById(R.id.headerTitle)).setText("@" + peer);
        list = findViewById(R.id.messages);
        input = findViewById(R.id.input);
        sendBtn = findViewById(R.id.send);
        chatTimerBtn = findViewById(R.id.chatTimer);
        nextTimerBtn = findViewById(R.id.nextTimer);
        findViewById(R.id.keysBtn).setOnClickListener(v ->
                startActivity(new Intent(this, KeysActivity.class).putExtra("user", peer)));

        adapter = new MessageAdapter(items, this::onMyMessageLongPress);
        LinearLayoutManager lm = new LinearLayoutManager(this);
        lm.setStackFromEnd(true);
        list.setLayoutManager(lm);
        list.setAdapter(adapter);
        // Keep the newest message visible when the keyboard opens or the list resizes.
        list.addOnLayoutChangeListener((v, l, t, r, bt, ol, ot, orr, ob) -> {
            if (bt < ob && !items.isEmpty()) list.post(() -> list.scrollToPosition(items.size() - 1));
        });

        selfKey = identity.selfEncryptionKey();
        sendBtn.setEnabled(false);
        sendBtn.setOnClickListener(v -> send());
        chatTimerBtn.setOnClickListener(v -> pickChatTimer());
        nextTimerBtn.setOnClickListener(v -> pickNextTimer());

        loadPeer();
        loadBlockState();
        findViewById(R.id.callBtn).setOnClickListener(v -> startCall(false));
        findViewById(R.id.headerTitle).setOnClickListener(v -> peerMenu());
        loadTimer();
        updateChips();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Session.identity() == null) {
            // Locked while away: never keep the old key reference around.
            finish();
            return;
        }
        visible = true;
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
    }

    @Override
    protected void onStop() {
        super.onStop();
        visible = false;
        items.clear();
        cache.clear();
        adapter.notifyDataSetChanged();
    }

    private void loadPeer() {
        Async.run(() -> Pgp.encryptionKeyFrom(Api.publicKey(token, peer).getString("publicKey")), (key, err) -> {
            if (err != null) {
                Toast.makeText(this, Async.message(err), Toast.LENGTH_LONG).show();
                input.setEnabled(false);
                return;
            }
            peerKey = key;
            sendBtn.setEnabled(true);
        });
    }

    private void loadTimer() {
        Async.run(() -> Api.getTimer(token, peer), (json, err) -> {
            if (err == null && json != null) chatTtl = json.optInt("ttlSeconds", 0);
            updateChips();
        });
    }

    private void poll() {
        if (polling || !visible) return;
        polling = true;
        Async.run(this::fetchMessages, (fresh, err) -> {
            polling = false;
            if (err != null || !visible) return;
            items.clear();
            items.addAll(fresh);
            adapter.notifyDataSetChanged();
            if (!items.isEmpty()) list.scrollToPosition(items.size() - 1);
        });
    }

    /** Runs on a background thread. The server returns ciphertext; this phone decrypts it. */
    private List<MessageAdapter.Msg> fetchMessages() throws Exception {
        JSONObject json = Api.messages(token, peer);
        JSONArray arr = json.optJSONArray("messages");
        List<MessageAdapter.Msg> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject m = arr.optJSONObject(i);
            if (m == null) continue;
            String mid = m.optString("id");
            String body = m.optString("body");
            // The cache key includes the body, so an edited message is decrypted again.
            String key = mid + "|" + body.hashCode();
            String text = cache.get(key);
            if (text == null) {
                try {
                    text = Pgp.decrypt(body, identity);
                } catch (Exception e) {
                    text = "Could not decrypt this message";
                }
                cache.put(key, text);
            }
            boolean expiring = m.has("expiresAt") && !m.isNull("expiresAt");
            out.add(new MessageAdapter.Msg(mid, m.optBoolean("mine"), text, expiring, m.optBoolean("edited")));
        }
        return out;
    }

    private void onMyMessageLongPress(MessageAdapter.Msg m) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Your message")
                .setItems(new String[]{"Edit", "Unsend", "Cancel"}, (d, which) -> {
                    if (which == 0) showEditDialog(m);
                    else if (which == 1) confirmUnsend(m);
                })
                .show();
    }

    private void showEditDialog(MessageAdapter.Msg m) {
        final EditText et = new EditText(this);
        et.setText(m.text);
        et.setSelection(et.getText().length());
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        et.setPadding(pad, pad, pad, pad);
        new MaterialAlertDialogBuilder(this)
                .setTitle("Edit message")
                .setView(et)
                .setPositiveButton("Save", (d, w) -> {
                    final String text = et.getText().toString().trim();
                    if (text.isEmpty() || peerKey == null) return;
                    Async.run(() -> {
                        String armored = Pgp.encrypt(text, Arrays.asList(peerKey, selfKey));
                        Api.editMessage(token, m.id, armored);
                        return Boolean.TRUE;
                    }, (ok, err) -> {
                        if (err != null) Toast.makeText(this, Async.message(err), Toast.LENGTH_LONG).show();
                        else poll();
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmUnsend(MessageAdapter.Msg m) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Unsend message?")
                .setMessage("It will be removed from the server for both of you.")
                .setPositiveButton("Unsend", (d, w) -> Async.run(() -> Api.unsend(token, m.id), (ok, err) -> {
                    if (err != null) Toast.makeText(this, Async.message(err), Toast.LENGTH_LONG).show();
                    else poll();
                }))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void loadBlockState() {
        Async.run(() -> Api.getBlocks(token), (json, err) -> {
            if (err != null || json == null) return;
            JSONArray arr = json.optJSONArray("blocked");
            blockedPeer = false;
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    if (peer.equals(arr.optString(i))) blockedPeer = true;
                }
            }
            ((TextView) findViewById(R.id.headerTitle)).setText("@" + peer + (blockedPeer ? "  (blocked)" : ""));
        });
    }

    private void startCall(boolean video) {
        if (blockedPeer) {
            Toast.makeText(this, "Unblock @" + peer + " to call", Toast.LENGTH_SHORT).show();
            return;
        }
        startActivity(new Intent(this, CallActivity.class)
                .putExtra("peer", peer)
                .putExtra("outgoing", true)
                .putExtra("video", video));
    }

    private void peerMenu() {
        String[] items = {"Video call", blockedPeer ? "Unblock @" + peer : "Block @" + peer, "Keys"};
        new MaterialAlertDialogBuilder(this)
                .setTitle("@" + peer)
                .setItems(items, (d, which) -> {
                    if (which == 0) startCall(true);
                    else if (which == 1) setBlocked(!blockedPeer);
                    else startActivity(new Intent(this, KeysActivity.class).putExtra("user", peer));
                })
                .show();
    }

    private void setBlocked(boolean block) {
        Async.run(() -> block ? Api.block(token, peer) : Api.unblock(token, peer), (json, err) -> {
            if (err != null) {
                Toast.makeText(this, Async.message(err), Toast.LENGTH_LONG).show();
                return;
            }
            blockedPeer = block;
            ((TextView) findViewById(R.id.headerTitle)).setText("@" + peer + (block ? "  (blocked)" : ""));
            Toast.makeText(this, block ? "Blocked. They cannot message or call you." : "Unblocked", Toast.LENGTH_SHORT).show();
        });
    }

    private void send() {
        final String text = input.getText().toString().trim();
        if (text.isEmpty() || peerKey == null) return;

        sendBtn.setEnabled(false);
        final Integer ttl = nextTtl >= 0 ? Integer.valueOf(nextTtl) : null;

        Async.run(() -> {
            // Encrypt to the recipient AND to ourselves, so we can still read what we sent.
            String armored = Pgp.encrypt(text, Arrays.asList(peerKey, selfKey));
            Api.send(token, peer, armored, ttl, identity.fingerprint());
            return Boolean.TRUE;
        }, (ok, err) -> {
            sendBtn.setEnabled(true);
            if (err != null) {
                Toast.makeText(this, Async.message(err), Toast.LENGTH_LONG).show();
                return;
            }
            input.setText("");
            nextTtl = -1;
            updateChips();
            poll();
        });
    }

    private void pickChatTimer() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Chat timer")
                .setMessage("Applies only to messages sent from now on. Older messages keep their own rules.")
                .setItems(CHAT_LABELS, (d, which) -> setChatTimer(CHAT_VALUES[which]))
                .show();
    }

    private void setChatTimer(int seconds) {
        Async.run(() -> Api.setTimer(token, peer, seconds), (json, err) -> {
            if (err != null) {
                Toast.makeText(this, Async.message(err), Toast.LENGTH_LONG).show();
                return;
            }
            chatTtl = seconds;
            updateChips();
        });
    }

    private void pickNextTimer() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Next message only")
                .setItems(NEXT_LABELS, (d, which) -> {
                    nextTtl = NEXT_VALUES[which];
                    updateChips();
                })
                .show();
    }

    private void updateChips() {
        chatTimerBtn.setText("Timer: " + label(chatTtl));
        nextTimerBtn.setText(nextTtl < 0 ? "Next message: chat default" : "Next message: " + label(nextTtl));
    }

    static String label(int s) {
        if (s <= 0) return "off";
        if (s < 60) return s + "s";
        if (s < 3600) return (s / 60) + "m";
        if (s < 86400) return (s / 3600) + "h";
        if (s < 604800) return (s / 86400) + "d";
        return (s / 604800) + "w";
    }
}
