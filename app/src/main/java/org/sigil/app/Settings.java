package org.sigil.app;

import android.content.Context;

/** User preferences. Nothing here is secret, so plain preferences are fine. */
public final class Settings {
    private static final String PREFS = "sigil_settings";

    private Settings() {}

    private static android.content.SharedPreferences p(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean autoLock(Context c) {
        return p(c).getBoolean("autoLock", true);
    }

    public static void setAutoLock(Context c, boolean on) {
        p(c).edit().putBoolean("autoLock", on).apply();
    }

    /** The ringtone URI the user picked, or null for the phone's default ringtone. */
    public static String ringtone(Context c) {
        return p(c).getString("ringtone", null);
    }

    public static void setRingtone(Context c, String uri) {
        p(c).edit().putString("ringtone", uri).apply();
    }

    public static boolean vibrate(Context c) {
        return p(c).getBoolean("vibrate", true);
    }

    public static void setVibrate(Context c, boolean on) {
        p(c).edit().putBoolean("vibrate", on).apply();
    }

    /**
     * When on, calls always go through the TURN relay, even on the same network.
     * The other person then never learns your IP address.
     */
    public static boolean relayOnly(Context c) {
        return p(c).getBoolean("relayOnly", true);
    }

    public static void setRelayOnly(Context c, boolean on) {
        p(c).edit().putBoolean("relayOnly", on).apply();
    }
}
