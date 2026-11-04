package org.sigil.app;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.Locale;

public class LoginActivity extends AppCompatActivity {
    private boolean registerMode = false;
    private EditText userInput, passInput;
    private Button action;
    private TextView toggle;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (Session.token(this) != null) {
            route();
            return;
        }
        setContentView(R.layout.activity_login);
        EdgeToEdge.apply(this);
        userInput = findViewById(R.id.username);
        passInput = findViewById(R.id.password);
        action = findViewById(R.id.actionButton);
        toggle = findViewById(R.id.toggleMode);

        action.setOnClickListener(v -> submit());
        toggle.setOnClickListener(v -> {
            registerMode = !registerMode;
            render();
        });
        render();
    }

    private void render() {
        action.setText(registerMode ? "Create account" : "Log in");
        toggle.setText(registerMode ? "Already have an account? Log in" : "New here? Create an account");
    }

    private void submit() {
        final String u = userInput.getText().toString().trim().toLowerCase(Locale.ROOT);
        final String p = passInput.getText().toString();
        if (!u.matches("[a-z0-9_]{3,24}")) {
            Toast.makeText(this, "Username: 3-24 characters, a-z, 0-9, underscore", Toast.LENGTH_SHORT).show();
            return;
        }
        if (p.length() < 8) {
            Toast.makeText(this, "Password must be at least 8 characters", Toast.LENGTH_SHORT).show();
            return;
        }
        action.setEnabled(false);
        final boolean reg = registerMode;
        final Context app = getApplicationContext();

        Async.run(() -> reg ? Api.register(u, p) : Api.login(u, p), (json, err) -> {
            action.setEnabled(true);
            if (err != null) {
                Toast.makeText(this, Async.message(err), Toast.LENGTH_LONG).show();
                return;
            }
            Session.saveLogin(app, json.optString("token"), u);
            route();
        });
    }

    private void route() {
        startActivity(new Intent(this, Session.hasKeys(this) ? HomeActivity.class : SetupKeysActivity.class));
        finish();
    }
}
