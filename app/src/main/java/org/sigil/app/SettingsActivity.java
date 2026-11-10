package org.sigil.app;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
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
    /** Replace with the real APKPure page once Sigil is published there. */
    static final String APKPURE_URL = "https://apkpure.com/sigil/org.sigil.app";

    private LinearLayout blockedList;
    private TextView blockedEmpty, ringtoneLabel;
    private String token;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final ActivityResultLauncher<Intent> ringtonePicker = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), r -> {
                if (r.getResultCode() != RESULT_OK || r.getData() == null) return;
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
        String name = Session.username(this);
        ((TextView) findViewById(R.id.username)).setText("@" + name);
        ((TextView) findViewById(R.id.avatar)).setText(name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase());

        MaterialSwitch autoLock = findViewById(R.id.autoLock);
        autoLock.setChecked(Settings.autoLock(this));
        autoLock.setOnCheckedChangeListener((btn, on) -> Settings.setAutoLock(getApplicationContext(), on));

        MaterialSwitch relay = findViewById(R.id.relaySwitch);
        relay.setChecked(Settings.relayOnly(this));
        relay.setOnCheckedChangeListener((btn, on) -> Settings.setRelayOnly(getApplicationContext(), on));

        MaterialSwitch vibrate = findViewById(R.id.vibrateSwitch);
        vibrate.setChecked(Settings.vibrate(this));
        vibrate.setOnCheckedChangeListener((btn, on) -> {
            Settings.setVibrate(getApplicationContext(), on);
            Notifier.ensureChannel(this);
        });

        ringtoneLabel = findViewById(R.id.ringtoneLabel);
        findViewById(R.id.ringtoneBtn).setOnClickListener(v -> pickRingtone());
        refreshRingtone();

        findViewById(R.id.testRingtoneBtn).setOnClickListener(v -> testRingtone());
        findViewById(R.id.testVibrateBtn).setOnClickListener(v -> testVibration());
        findViewById(R.id.batteryBtn).setOnClickListener(v -> allowBackground());
        findViewById(R.id.apkpureBtn).setOnClickListener(v -> openUrl(APKPURE_URL));
        findViewById(R.id.keysBtn).setOnClickListener(v -> startActivity(new Intent(this, KeysActivity.class)));
        findViewById(R.id.aboutBtn).setOnClickListener(v -> startActivity(new Intent(this, AboutActivity.class)));
        findViewById(R.id.back).setOnClickListener(v -> finish());

        blockedList = findViewById(R.id.blockedList);
        blockedEmpty = findViewById(R.id.blockedEmpty);
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
        String name = "Default";
        try {
            String t = RingtoneManager.getRingtone(this, Notifier.ringtone(this)).getTitle(this);
            if (t != null) name = t;
        } catch (RuntimeException ignored) {
            // The tone is no longer available, so keep the default label.
        }
        ringtoneLabel.setText(name);
    }

    private void testRingtone() {
        final MediaPlayer p = Ringer.startLoop(this);
        if (p == null) {
            Toast.makeText(this, "Could not play a ringtone on this phone", Toast.LENGTH_LONG).show();
            return;
        }
        handler.postDelayed(() -> Ringer.stop(p), 3000);
    }

    private void testVibration() {
        Vibrator v = getSystemService(Vibrator.class);
        if (v == null) {
            Toast.makeText(this, "This phone has no vibration motor", Toast.LENGTH_LONG).show();
            return;
        }
        v.vibrate(VibrationEffect.createWaveform(new long[]{0, 600, 300, 600}, -1));
    }

    /** Asks Android to let Sigil run in the background, so incoming calls are not delayed. */
    private void allowBackground() {
        Intent i = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + getPackageName()));
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Open Settings > Battery and set Sigil to unrestricted", Toast.LENGTH_LONG).show();
        }
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, url, Toast.LENGTH_LONG).show();
        }
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
                btn.setSingleLine(true);
                btn.setEllipsize(android.text.TextUtils.TruncateAt.END);
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
