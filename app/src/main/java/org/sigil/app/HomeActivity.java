package org.sigil.app;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class HomeActivity extends AppCompatActivity {
    private EditText search;
    private TextView empty, who;
    private RecyclerView list;
    private UserAdapter adapter;
    private final List<UserAdapter.Row> rows = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable runSearch = this::doSearch;
    private String token;

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
        if (Session.identity() == null) {
            if (Session.hasKeys(this)) {
                setContentView(R.layout.activity_home);
                bindViews();
                promptUnlock();
            } else {
                startActivity(new Intent(this, SetupKeysActivity.class));
                finish();
            }
            return;
        }
        setContentView(R.layout.activity_home);
        bindViews();
    }

    private void bindViews() {
        search = findViewById(R.id.search);
        empty = findViewById(R.id.empty);
        who = findViewById(R.id.who);
        list = findViewById(R.id.list);
        Button lockBtn = findViewById(R.id.lockBtn);
        Button signOutBtn = findViewById(R.id.signOutBtn);

        who.setText("Signed in as @" + Session.username(this));
        empty.setText("Search for a username to start a private chat");

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
                handler.removeCallbacks(runSearch);
                handler.postDelayed(runSearch, 300);
            }
            @Override public void afterTextChanged(Editable e) {}
        });

        lockBtn.setOnClickListener(v -> {
            Session.lock();
            promptUnlock();
        });
        signOutBtn.setOnClickListener(v -> confirmSignOut());
    }

    private void doSearch() {
        String q = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        if (q.length() < 2) {
            rows.clear();
            adapter.notifyDataSetChanged();
            empty.setText("Search for a username to start a private chat");
            empty.setVisibility(android.view.View.VISIBLE);
            return;
        }
        Async.run(() -> Api.search(token, q), (json, err) -> {
            rows.clear();
            if (err != null) {
                empty.setText(Async.message(err));
                empty.setVisibility(android.view.View.VISIBLE);
                adapter.notifyDataSetChanged();
                return;
            }
            JSONArray arr = json.optJSONArray("results");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o != null) rows.add(new UserAdapter.Row(o.optString("username"), o.optBoolean("hasPublicKey")));
                }
            }
            adapter.notifyDataSetChanged();
            boolean none = rows.isEmpty();
            empty.setText(none ? "No users found" : "");
            empty.setVisibility(none ? android.view.View.VISIBLE : android.view.View.GONE);
        });
    }

    private void promptUnlock() {
        final Context app = getApplicationContext();
        final EditText et = new EditText(this);
        et.setHint("Key passphrase");
        et.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
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

        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            final char[] pass = et.getText().toString().toCharArray();
            Async.run(() -> Session.unlock(app, pass), (ident, err) -> {
                if (err != null) {
                    et.setError("Wrong passphrase");
                    return;
                }
                dialog.dismiss();
            });
        }));
        dialog.show();
    }

    private void confirmSignOut() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Sign out?")
                .setMessage("This deletes your private key from this phone. Without a backup of your key you will lose access to your messages.")
                .setPositiveButton("Delete and sign out", (d, w) -> {
                    Session.clear(getApplicationContext());
                    Intent i = new Intent(this, LoginActivity.class);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(i);
                    finish();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
