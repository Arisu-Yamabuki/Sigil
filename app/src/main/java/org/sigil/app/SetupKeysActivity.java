package org.sigil.app;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class SetupKeysActivity extends AppCompatActivity {
    private EditText keyName, passphrase, confirm;
    private Button createKey, importKey;
    private String pendingSecret, pendingPublic;

    private final ActivityResultLauncher<String[]> pickPublic = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) return;
                pendingPublic = readText(uri);
                if (pendingSecret == null || pendingPublic == null) {
                    Toast.makeText(this, "Could not read those key files", Toast.LENGTH_LONG).show();
                    pendingSecret = null;
                    pendingPublic = null;
                    return;
                }
                doImport();
            });

    private final ActivityResultLauncher<String[]> pickSecret = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) return;
                pendingSecret = readText(uri);
                Toast.makeText(this, "Now choose your public key file", Toast.LENGTH_SHORT).show();
                pickPublic.launch(new String[]{"*/*"});
            });

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_setup_keys);

        keyName = findViewById(R.id.keyName);
        passphrase = findViewById(R.id.passphrase);
        confirm = findViewById(R.id.confirm);
        createKey = findViewById(R.id.createKey);
        importKey = findViewById(R.id.importKey);

        keyName.setText(Session.username(this));
        createKey.setOnClickListener(v -> createKeys());
        importKey.setOnClickListener(v -> {
            if (checkPassphrase(false)) pickSecret.launch(new String[]{"*/*"});
        });
    }

    private boolean checkPassphrase(boolean confirmToo) {
        String p = passphrase.getText().toString();
        if (p.length() < 8) {
            Toast.makeText(this, "Passphrase must be at least 8 characters", Toast.LENGTH_SHORT).show();
            return false;
        }
        if (confirmToo && !p.equals(confirm.getText().toString())) {
            Toast.makeText(this, "Passphrases do not match", Toast.LENGTH_SHORT).show();
            return false;
        }
        return true;
    }

    private void setBusy(boolean busy) {
        createKey.setEnabled(!busy);
        importKey.setEnabled(!busy);
    }

    private void createKeys() {
        String name = keyName.getText().toString().trim();
        if (name.isEmpty() || name.length() > 40) {
            Toast.makeText(this, "Enter a key name (1-40 characters)", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!checkPassphrase(true)) return;

        setBusy(true);
        Toast.makeText(this, "Generating your key. This can take a few seconds.", Toast.LENGTH_SHORT).show();
        final char[] pass = passphrase.getText().toString().toCharArray();
        final String token = Session.token(this);
        final Context app = getApplicationContext();

        Async.run(() -> {
            Pgp.Identity ident = Pgp.generate(name, pass);
            Session.saveKeys(app, ident.secretArmor(), ident.publicArmor());
            Api.uploadKey(token, ident.publicArmor(), ident.fingerprint());
            Session.setIdentity(ident);
            return ident;
        }, (ident, err) -> {
            setBusy(false);
            if (err != null) {
                Toast.makeText(this, Async.message(err), Toast.LENGTH_LONG).show();
                return;
            }
            openHome();
        });
    }

    private void doImport() {
        if (!checkPassphrase(false)) {
            pendingSecret = null;
            pendingPublic = null;
            return;
        }
        setBusy(true);
        final char[] pass = passphrase.getText().toString().toCharArray();
        final String sec = pendingSecret, pub = pendingPublic;
        pendingSecret = null;
        pendingPublic = null;
        final String token = Session.token(this);
        final Context app = getApplicationContext();

        Async.run(() -> {
            Pgp.Identity ident = Pgp.load(sec, pub, pass);
            Session.saveKeys(app, ident.secretArmor(), ident.publicArmor());
            Api.uploadKey(token, ident.publicArmor(), ident.fingerprint());
            Session.setIdentity(ident);
            return ident;
        }, (ident, err) -> {
            setBusy(false);
            if (err != null) {
                Toast.makeText(this, "Import failed: " + Async.message(err), Toast.LENGTH_LONG).show();
                return;
            }
            openHome();
        });
    }

    private void openHome() {
        startActivity(new Intent(this, HomeActivity.class));
        finish();
    }

    private String readText(Uri uri) {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } catch (IOException e) {
            return null;
        }
    }
}
