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
 * Local session state. The server token is stored in preferences. The keys are stored as
 * passphrase-protected files. The unlocked identity exists only in memory.
 */
public final class Session {
    private static final String PREFS = "sigil_session";
    private static final String SECRET_FILE = "secret.asc";
    private static final String PUBLIC_FILE = "public.asc";

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

    public static boolean hasKeys(Context c) {
        return new File(c.getFilesDir(), SECRET_FILE).exists() && new File(c.getFilesDir(), PUBLIC_FILE).exists();
    }

    public static void saveKeys(Context c, String secretArmor, String publicArmor) throws IOException {
        write(c, SECRET_FILE, secretArmor);
        write(c, PUBLIC_FILE, publicArmor);
    }

    public static Pgp.Identity identity() {
        return identity;
    }

    public static void setIdentity(Pgp.Identity id) {
        identity = id;
    }

    public static Pgp.Identity unlock(Context c, char[] passphrase) throws Exception {
        Pgp.Identity id = Pgp.load(read(c, SECRET_FILE), read(c, PUBLIC_FILE), passphrase);
        identity = id;
        return id;
    }

    /** Forget the unlocked key from memory. The key stays on disk, protected by the passphrase. */
    public static void lock() {
        identity = null;
    }

    /** Full sign-out: deletes the token, the keys and everything cached on this phone. */
    public static void clear(Context c) {
        identity = null;
        new File(c.getFilesDir(), SECRET_FILE).delete();
        new File(c.getFilesDir(), PUBLIC_FILE).delete();
        prefs(c).edit().clear().apply();
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
