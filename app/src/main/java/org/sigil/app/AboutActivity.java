package org.sigil.app;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class AboutActivity extends AppCompatActivity {
    // Edit these before publishing.
    static final String GITHUB = "https://github.com/Arisu-Yamabuki/Sigil";
    static final String INSTAGRAM = "https://instagram.com/adnanisagoodboy";
    static final String EMAIL = "mail@adnanD.com";
    static final String SERVER = "https://sigil.adnand.com";

    static final String PRIVACY =
            "Privacy Policy\n"
            + "Effective date: November 4, 2026\n\n"
            + "Sigil (\"we\", \"our\", \"us\") runs the Sigil Android app and the relay server at https://sigil.adnand.com. "
            + "Sigil is open source and built so that we cannot read your messages or access your private keys.\n\n"
            + "1. What the server stores\n"
            + "- Account data: your username and a bcrypt hash of your password. We never see your plaintext password.\n"
            + "- Public keys: your public GPG key, so others can encrypt to you. Anyone can look it up by username.\n"
            + "- Ciphertext: encrypted messages, which the server cannot read.\n"
            + "- Metadata: timestamps, sender and recipient usernames, and your timer settings, used to delete expired messages.\n\n"
            + "2. What the server never receives\n"
            + "- Message contents, private keys, or key passphrases.\n"
            + "- Profile pictures, avatars, or device identifiers. Sigil has no such feature.\n\n"
            + "3. What stays on your phone\n"
            + "- Your private key is saved in app-private storage, protected by your key passphrase. It is never uploaded.\n"
            + "- Sign-out keeps this encrypted key on the phone. Delete keys in the Keys screen erases it.\n"
            + "- Decrypted messages are kept only in memory and are cleared when you leave a chat.\n"
            + "- Android cloud backups are disabled, so keys and messages are not copied off the device.\n\n"
            + "4. Retention and deletion\n"
            + "- Messages with a timer are deleted from the server automatically when the timer expires.\n"
            + "- You can delete messages you sent. Deleting a message removes its ciphertext from the server.\n"
            + "- Deleting your account and all server data can be requested through the contact details below.\n\n"
            + "5. Sharing\n"
            + "We do not sell, rent, or share user data with third parties, advertisers, or analytics providers. "
            + "The server holds no readable message content to share.\n\n"
            + "6. Security notes\n"
            + "- Screens use FLAG_SECURE, which blocks screenshots and recent-apps previews.\n"
            + "- The server only sees metadata (who messages whom and when). End-to-end encryption protects content, not metadata.\n"
            + "- If you lose your private key and have no backup, past messages cannot be recovered.\n\n"
            + "7. Changes\n"
            + "We may update this policy as the app changes. The current version ships with the app and is published on GitHub.\n\n"
            + "Contact: " + EMAIL;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_about);
        EdgeToEdge.apply(this);

        ((TextView) findViewById(R.id.version)).setText(
                "Version " + BuildConfig.VERSION_NAME + " (build " + BuildConfig.VERSION_CODE + ")");
        ((TextView) findViewById(R.id.server)).setText("Server: " + SERVER);
        ((TextView) findViewById(R.id.policy)).setText(PRIVACY);

        findViewById(R.id.back).setOnClickListener(v -> finish());
        findViewById(R.id.github).setOnClickListener(v -> open(GITHUB));
        findViewById(R.id.instagram).setOnClickListener(v -> open(INSTAGRAM));
        findViewById(R.id.email).setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + EMAIL)));
            } catch (ActivityNotFoundException e) {
                Toast.makeText(this, EMAIL, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void open(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, url, Toast.LENGTH_LONG).show();
        }
    }
}
