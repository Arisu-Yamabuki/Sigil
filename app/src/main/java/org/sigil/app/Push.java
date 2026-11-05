package org.sigil.app;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.firebase.messaging.FirebaseMessaging;

/** Firebase device token handling. If Firebase is not configured, push simply stays off. */
public final class Push {
    private static final String PREFS = "sigil_push";

    private Push() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static void saveToken(Context c, String token) {
        prefs(c).edit().putString("token", token).apply();
    }

    /** Fetches the current device token and uploads it for the signed-in account. */
    public static void register(Context c) {
        try {
            FirebaseMessaging.getInstance().getToken().addOnSuccessListener(t -> {
                saveToken(c, t);
                upload(c);
            });
        } catch (RuntimeException ignored) {
            // google-services.json is missing or Firebase is not set up. Calls still work in foreground.
        }
    }

    public static void upload(Context c) {
        String token = prefs(c).getString("token", null);
        String auth = Session.token(c);
        if (token == null || auth == null) return;
        Async.run(() -> Api.pushToken(auth, token), (r, e) -> {
        });
    }
}
