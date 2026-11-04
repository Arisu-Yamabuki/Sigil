package org.sigil.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Shows a public key (yours or a contact's) with copy. Also backs up or erases your own key. */
public class KeysActivity extends AppCompatActivity {
    private String peer, token;
    private Pgp.Identity self;
    private String armored = "";
    private TextView fpr, key;
    private View exportBtn, wipeBtn;

    private final ActivityResultLauncher<String> exportLauncher = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("application/pgp-keys"), uri -> {
                if (uri == null || self == null) return;
                try (OutputStream o = getContentResolver().openOutputStream(uri)) {
                    if (o == null) throw new IOException("Cannot open the file");
                    o.write(self.secretArmor().getBytes(StandardCharsets.UTF_8));
                    Toast.makeText(this, "Backup saved. Keep it somewhere safe.", Toast.LENGTH_LONG).show();
                } catch (Exception e) {
                    Toast.makeText(this, "Backup failed: " + Async.message(e), Toast.LENGTH_LONG).show();
                }
            });

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_keys);
        EdgeToEdge.apply(this);

        peer = getIntent().getStringExtra("user");
        token = Session.token(this);
        self = Session.identity();

        TextView title = findViewById(R.id.title);
        fpr = findViewById(R.id.fpr);
        key = findViewById(R.id.armored);
        exportBtn = findViewById(R.id.exportBtn);
        wipeBtn = findViewById(R.id.wipeBtn);

        findViewById(R.id.back).setOnClickListener(v -> finish());
        findViewById(R.id.copyBtn).setOnClickListener(v -> copy());

        if (peer == null) {
            if (self == null) {
                Toast.makeText(this, "Unlock Sigil first", Toast.LENGTH_SHORT).show();
                finish();
                return;
            }
            title.setText("My keys");
            exportBtn.setVisibility(View.VISIBLE);
            wipeBtn.setVisibility(View.VISIBLE);
            try {
                armored = self.publicArmor();
            } catch (IOException e) {
                armored = "Could not read your key";
            }
            fpr.setText(fmt(self.fingerprint()));
            key.setText(armored);
            exportBtn.setOnClickListener(v ->
                    exportLauncher.launch("sigil-backup-" + Session.username(this) + ".asc"));
            wipeBtn.setOnClickListener(v -> confirmWipe());
        } else {
            title.setText("@" + peer + " keys");
            key.setText("Loading...");
            Async.run(() -> Api.publicKey(token, peer), (json, err) -> {
                if (err != null) {
                    key.setText(Async.message(err));
                    return;
                }
                armored = json.optString("publicKey");
                fpr.setText(fmt(json.optString("fingerprint")));
                key.setText(armored);
            });
        }
    }

    private void copy() {
        if (armored.isEmpty()) return;
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("Sigil public key", armored));
        Toast.makeText(this, "Public key copied", Toast.LENGTH_SHORT).show();
    }

    private void confirmWipe() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Delete keys from this phone?")
                .setMessage("Your private key will be erased. Messages encrypted to it will be unreadable on this phone. Back up first if you may need them.")
                .setPositiveButton("Delete keys", (d, w) -> {
                    Session.wipeKeys(getApplicationContext());
                    Intent i = new Intent(this, SetupKeysActivity.class);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(i);
                    finish();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static String fmt(String hex) {
        if (hex == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hex.length(); i++) {
            if (i > 0 && i % 4 == 0) sb.append(' ');
            sb.append(hex.charAt(i));
        }
        return sb.toString();
    }
}
