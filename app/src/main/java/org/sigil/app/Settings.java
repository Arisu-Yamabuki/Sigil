package org.sigil.app;

import android.content.Context;

/** User preferences. Nothing here is secret, so plain preferences are fine. */
public final class Settings {
    private static final String PREFS = "sigil_settings";

    private Settings() {}

    public static boolean autoLock(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("autoLock", true);
    }

    public static void setAutoLock(Context c, boolean on) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("autoLock", on).apply();
    }
}
