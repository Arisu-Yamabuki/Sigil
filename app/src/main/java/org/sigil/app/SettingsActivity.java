package org.sigil.app;

import android.app.Activity;
import android.content.Intent;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;

import org.json.JSONArray;

public class SettingsActivity extends AppCompatActivity {
    private LinearLayout blockedList;
    private TextView blockedEmpty, ringtoneLabel;
    private MaterialButton ringtoneBtn;
    private String token;

    private final ActivityResultLauncher<Intent> ringtonePicker = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), r -> {
                if (r.getResultCode() != Activity.RESULT_OK || r.getData() == null) return;
                Uri picked = r.getData().getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI);
                Settings.setRingtone(this, picked == null ? null : picked.toString());
                Notifier.ensureChannel(this);
                refreshRingtone();
            });

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

        MaterialSwitch vibrate = findViewById(R.id.vibrateSwitch);
        vibrate.setChecked(Settings.vibrate(this));
        vibrate.setOnCheckedChangeListener((btn, on) -> {
            Settings.setVibrate(getApplicationContext(), on);
            Notifier.ensureChannel(this);
        });

        ringtoneBtn = findViewById(R.id.ringtoneBtn);
        ringtoneLabel = findViewById(R.id.ringtoneLabel);
        ringtoneBtn.setOnClickListener(v -> pickRingtone());
        refreshRingtone();

        blockedList = findViewById(R.id.blockedList);
        blockedEmpty = findViewById(R.id.blockedEmpty);

        findViewById(R.id.back).setOnClickListener(v -> finish());
        findViewById(R.id.keysBtn).setOnClickListener(v -> startActivity(new Intent(this, KeysActivity.class)));
        findViewById(R.id.aboutBtn).setOnClickListener(v -> startActivity(new Intent(this, AboutActivity.class)));

        loadBlocked();
    }

    private void pickRingtone() {
        Intent i = new Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_RINGTONE)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Incoming call sound")
                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Notifier.ringtone(this));
        ringtonePicker.launch(i);
    }

    private void refreshRingtone() {
        Uri uri = Notifier.ringtone(this);
        String name = "Default";
        try {
            String t = RingtoneManager.getRingtone(this, uri).getTitle(this);
            if (t != null) name = t;
        } catch (RuntimeException ignored) {
            // The tone is no longer available. Keep the default label.
        }
        ringtoneLabel.setText(name);
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
