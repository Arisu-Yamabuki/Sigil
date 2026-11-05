package org.sigil.app;

import android.Manifest;
import android.content.Intent;
import android.os.Build;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class HomeActivity extends AppCompatActivity {
    private static final long INBOX_MS = 5000;
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());

    private EditText search;
    private TextView empty, who;
    private RecyclerView list;
    private UserAdapter adapter;
    private final List<UserAdapter.Row> rows = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable searchTask = this::doSearch;
    private final Runnable inboxTick = new Runnable() {
        @Override
        public void run() {
            loadInbox();
            handler.postDelayed(this, INBOX_MS);
        }
    };
    private String token;
    private boolean inboxBusy = false;
    private boolean keyChecked = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);

        token = Session.token(this);
        if (token == null) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }
        if (Session.identity() == null && !Session.hasKeys(this)) {
            startActivity(new Intent(this, SetupKeysActivity.class));
            finish();
            return;
        }
        setContentView(R.layout.activity_home);
        EdgeToEdge.apply(this);
        bindViews();
        askNotifications();
        Push.register(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(inboxTick);
        Signal.connect(token);
        Signal.setListener(homeListener);
        ensureUnlocked();
        verifyServerKey();
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(inboxTick);
    }

    /** Incoming calls arrive here while Home is on screen. */
    private final Signal.Listener homeListener = m -> {
        if (!"call".equals(m.optString("type")) || !"offer".equals(m.optString("kind"))) return;
        if (Session.identity() == null) return;
        startActivity(new Intent(this, CallActivity.class)
                .putExtra("peer", m.optString("from"))
                .putExtra("callId", m.optString("callId"))
                .putExtra("video", m.optBoolean("video"))
                .putExtra("offer", m.optString("body")));
    };

    private void bindViews() {
        search = findViewById(R.id.search);
        empty = findViewById(R.id.empty);
        who = findViewById(R.id.who);
        list = findViewById(R.id.list);

        who.setText("Signed in as @" + Session.username(this));

        adapter = new UserAdapter(rows, row -> {
            Intent i = new Intent(this, ChatActivity.class);
            i.putExtra("user", row.username);
            startActivity(i);
        });
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int c, int d) {}
            @Override public void onTextChanged(CharSequence s, int a, int c, int d) {
                handler.removeCallbacks(searchTask);
                handler.postDelayed(searchTask, 300);
            }
            @Override public void afterTextChanged(Editable e) {}
        });

        findViewById(R.id.settingsBtn).setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        findViewById(R.id.aboutBtn).setOnClickListener(v -> startActivity(new Intent(this, AboutActivity.class)));
        findViewById(R.id.keysBtn).setOnClickListener(v -> startActivity(new Intent(this, KeysActivity.class)));
        findViewById(R.id.lockBtn).setOnClickListener(v -> {
            Session.lock();
            keyChecked = false;
            rows.clear();
            adapter.notifyDataSetChanged();
            promptUnlock();
        });
        findViewById(R.id.signOutBtn).setOnClickListener(v -> confirmSignOut());
    }

    private String query() {
        return search.getText().toString().trim().toLowerCase(Locale.ROOT);
    }

    /** Inbox: conversations with the latest message, decrypted on this phone. */
    private void loadInbox() {
        if (!query().isEmpty() || inboxBusy || token == null) return;
        inboxBusy = true;
        final Pgp.Identity id = Session.identity();
        Async.run(() -> buildInbox(id), (fresh, err) -> {
            inboxBusy = false;
            if (err != null || !query().isEmpty()) return;
            rows.clear();
            rows.addAll(fresh);
            adapter.notifyDataSetChanged();
            boolean none = rows.isEmpty();
            empty.setText(none ? "No conversations yet.\nSearch a username above to start a private chat." : "");
            empty.setVisibility(none ? View.VISIBLE : View.GONE);
        });
    }

    private List<UserAdapter.Row> buildInbox(Pgp.Identity id) throws Exception {
        JSONArray arr = Api.inbox(token).optJSONArray("conversations");
        List<UserAdapter.Row> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject c = arr.optJSONObject(i);
            if (c == null) continue;
            String preview;
            if (id == null) {
                preview = "Unlock Sigil to read";
            } else {
                try {
                    preview = Pgp.decrypt(c.optString("lastBody"), id);
                } catch (Exception e) {
                    preview = "Encrypted message";
                }
            }
            if (c.optBoolean("lastFromMe")) preview = "You: " + preview;
            out.add(new UserAdapter.Row(c.optString("username"), preview, formatTime(c.optString("lastAt")), true));
        }
        return out;
    }

    private void doSearch() {
        final String q = query();
        if (q.length() < 2) {
            loadInbox();
            return;
        }
        Async.run(() -> Api.search(token, q), (json, err) -> {
            if (!q.equals(query())) return;
            rows.clear();
            if (err != null) {
                empty.setText(Async.message(err));
                empty.setVisibility(View.VISIBLE);
                adapter.notifyDataSetChanged();
                return;
            }
            JSONArray arr = json.optJSONArray("results");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    boolean hasKey = o.optBoolean("hasPublicKey");
                    rows.add(new UserAdapter.Row(o.optString("username"),
                            hasKey ? "Encryption key ready" : "No key yet", "", hasKey));
                }
            }
            adapter.notifyDataSetChanged();
            boolean none = rows.isEmpty();
            empty.setText(none ? "No users found" : "");
            empty.setVisibility(none ? View.VISIBLE : View.GONE);
        });
    }

    private static String formatTime(String iso) {
        try {
            return TIME.format(Instant.parse(iso));
        } catch (Exception e) {
            return "";
        }
    }

    /** Warns when the server's key for this account differs from the key on this phone. */
    private void verifyServerKey() {
        final Pgp.Identity id = Session.identity();
        if (id == null || keyChecked) return;
        final String me = Session.username(this);
        Async.run(() -> Api.publicKey(token, me).optString("fingerprint"), (serverFpr, err) -> {
            if (err != null || serverFpr == null || serverFpr.isEmpty()) return;
            keyChecked = true;
            if (serverFpr.equalsIgnoreCase(id.fingerprint())) return;
            new MaterialAlertDialogBuilder(this)
                    .setTitle("Key mismatch")
                    .setMessage("The server has a different public key for @" + me + " than this phone. "
                            + "Messages sent to the server's key can only be read on the phone that owns it.\n\n"
                            + "Upload this phone's key so new messages reach you here?")
                    .setPositiveButton("Upload this phone's key", (d, w) -> Async.run(
                            () -> Api.uploadKey(token, id.publicArmor(), id.fingerprint()),
                            (r, e2) -> Toast.makeText(this,
                                    e2 == null ? "Key updated" : Async.message(e2), Toast.LENGTH_LONG).show()))
                    .setNegativeButton("Later", null)
                    .show();
        });
    }

    private boolean unlockShowing = false;

    private final ActivityResultLauncher<String> notifPermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {});

    /** Android 13+ needs permission before any call notification can ring. */
    private void askNotifications() {
        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS);
        }
    }

    private void ensureUnlocked() {
        if (Session.identity() == null && Session.hasKeys(this)) promptUnlock();
    }

    private void promptUnlock() {
        if (unlockShowing) return;
        unlockShowing = true;
        final EditText et = new EditText(this);
        et.setHint("Key passphrase");
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        et.setPadding(pad, pad, pad, pad);

        final AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle("Unlock Sigil")
                .setMessage("Enter your key passphrase. It stays only in memory and is never stored.")
                .setView(et)
                .setCancelable(false)
                .setPositiveButton("Unlock", null)
                .setNegativeButton("Sign out", (d, w) -> confirmSignOut())
                .create();

        final android.content.Context app = getApplicationContext();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            final char[] pass = et.getText().toString().toCharArray();
            Async.run(() -> Session.unlock(app, pass), (ident, err) -> {
                if (err != null) {
                    et.setError("Wrong passphrase");
                    return;
                }
                dialog.dismiss();
                loadInbox();
                verifyServerKey();
            });
        }));
        dialog.setOnDismissListener(d -> unlockShowing = false);
        dialog.show();
    }

    private void confirmSignOut() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Sign out?")
                .setMessage("Your encrypted key stays on this phone, so signing back in restores your messages. "
                        + "To erase the key, use Keys, then Delete keys.")
                .setPositiveButton("Sign out", (d, w) -> {
                    Session.signOut(getApplicationContext());
                    Intent i = new Intent(this, LoginActivity.class);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(i);
                    finish();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
