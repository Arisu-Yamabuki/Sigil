package org.sigil.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Local session state. Keys are stored per account, so each username has its own key files.
 * The server token lives in preferences. The unlocked identity exists only in memory.
 */
public final class Session {
    private static final String PREFS = "sigil_session";

    private static volatile Pgp.Identity identity;

    private Session() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static void saveLogin(Context c, String token, String username) {
        prefs(c).edit().putString("token", token).putString("username", username).apply();
    }

    public static String token(Context c) {
        return prefs(c).getString("token", null);
    }

    public static String username(Context c) {
        return prefs(c).getString("username", "");
    }

    /** File names include the account, so one device can hold a key for each account. */
    private static String secretName(Context c) {
        return "secret_" + username(c) + ".asc";
    }

    private static String publicName(Context c) {
        return "public_" + username(c) + ".asc";
    }

    public static boolean hasKeys(Context c) {
        if (username(c).isEmpty()) return false;
        return new File(c.getFilesDir(), secretName(c)).exists()
                && new File(c.getFilesDir(), publicName(c)).exists();
    }

    public static void saveKeys(Context c, String secretArmor, String publicArmor) throws IOException {
        write(c, secretName(c), secretArmor);
        write(c, publicName(c), publicArmor);
    }

    public static Pgp.Identity identity() {
        return identity;
    }

    public static void setIdentity(Pgp.Identity id) {
        identity = id;
    }

    public static Pgp.Identity unlock(Context c, char[] passphrase) throws Exception {
        Pgp.Identity id = Pgp.load(read(c, secretName(c)), read(c, publicName(c)), passphrase);
        identity = id;
        return id;
    }

    /** Forget the unlocked key from memory. The key stays on disk, protected by the passphrase. */
    public static void lock() {
        identity = null;
    }

    /** Signs out but keeps this account's encrypted key on the phone. */
    public static void signOut(Context c) {
        identity = null;
        prefs(c).edit().remove("token").remove("username").apply();
    }

    /** Erases this account's private key from the phone. */
    public static void wipeKeys(Context c) {
        identity = null;
        new File(c.getFilesDir(), secretName(c)).delete();
        new File(c.getFilesDir(), publicName(c)).delete();
    }

    private static void write(Context c, String name, String text) throws IOException {
        try (OutputStream o = new FileOutputStream(new File(c.getFilesDir(), name))) {
            o.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String read(Context c, String name) throws IOException {
        try (InputStream in = new FileInputStream(new File(c.getFilesDir(), name));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }
}
