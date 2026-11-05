package org.sigil.app;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;

import org.json.JSONArray;

public class SettingsActivity extends AppCompatActivity {
    private LinearLayout blockedList;
    private TextView blockedEmpty;
    private String token;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_settings);
        EdgeToEdge.apply(this);

        token = Session.token(this);
        ((TextView) findViewById(R.id.username)).setText("@" + Session.username(this));

        MaterialSwitch autoLock = findViewById(R.id.autoLock);
        autoLock.setChecked(Settings.autoLock(this));
        autoLock.setOnCheckedChangeListener((btn, on) -> Settings.setAutoLock(getApplicationContext(), on));

        blockedList = findViewById(R.id.blockedList);
        blockedEmpty = findViewById(R.id.blockedEmpty);

        findViewById(R.id.back).setOnClickListener(v -> finish());
        findViewById(R.id.keysBtn).setOnClickListener(v -> startActivity(new Intent(this, KeysActivity.class)));
        findViewById(R.id.aboutBtn).setOnClickListener(v -> startActivity(new Intent(this, AboutActivity.class)));

        loadBlocked();
    }

    private void loadBlocked() {
        Async.run(() -> Api.getBlocks(token), (json, err) -> {
            blockedList.removeAllViews();
            if (err != null) {
                blockedEmpty.setText(Async.message(err));
                blockedEmpty.setVisibility(View.VISIBLE);
                return;
            }
            JSONArray arr = json.optJSONArray("blocked");
            int n = arr == null ? 0 : arr.length();
            blockedEmpty.setText("No blocked users");
            blockedEmpty.setVisibility(n == 0 ? View.VISIBLE : View.GONE);
            for (int i = 0; i < n; i++) {
                final String user = arr.optString(i);
                MaterialButton btn = new MaterialButton(this, null,
                        com.google.android.material.R.attr.materialButtonOutlinedStyle);
                btn.setText("Unblock @" + user);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.topMargin = (int) (8 * getResources().getDisplayMetrics().density);
                btn.setLayoutParams(lp);
                btn.setOnClickListener(v -> Async.run(() -> Api.unblock(token, user), (r, e2) -> {
                    if (e2 != null) Toast.makeText(this, Async.message(e2), Toast.LENGTH_SHORT).show();
                    loadBlocked();
                }));
                blockedList.addView(btn);
            }
        });
    }
}
