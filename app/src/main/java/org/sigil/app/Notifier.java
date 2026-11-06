package org.sigil.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

/**
 * Incoming-call notifications. Channels cannot change their sound after creation, so the
 * channel ID includes the ringtone and vibration setting. Changing either creates a new channel.
 */
public final class Notifier {
    private static final String PREFIX = "calls_";
    private static final int RING_MS = 45_000;

    private Notifier() {}

    public static Uri ringtone(Context c) {
        String s = Settings.ringtone(c);
        return s == null ? RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE) : Uri.parse(s);
    }

    private static String channelId(Context c) {
        return PREFIX + Math.abs(ringtone(c).toString().hashCode()) + (Settings.vibrate(c) ? "v" : "q");
    }

    public static void ensureChannel(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        String id = channelId(c);
        for (NotificationChannel ch : nm.getNotificationChannels()) {
            if (ch.getId().startsWith(PREFIX) && !ch.getId().equals(id)) nm.deleteNotificationChannel(ch.getId());
        }
        if (nm.getNotificationChannel(id) != null) return;

        NotificationChannel ch = new NotificationChannel(id, "Incoming calls", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Rings when someone calls you");
        ch.setSound(ringtone(c), new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build());
        ch.enableVibration(Settings.vibrate(c));
        if (Settings.vibrate(c)) ch.setVibrationPattern(new long[]{0, 800, 600, 800});
        ch.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        nm.createNotificationChannel(ch);
    }

    public static void showIncomingCall(Context c, String from, String callId, boolean video) {
        ensureChannel(c);
        String kind = video ? "video" : "voice";

        Intent open = new Intent(c, CallActivity.class)
                .putExtra("peer", from)
                .putExtra("callId", callId)
                .putExtra("video", video)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent openPi = PendingIntent.getActivity(c, notifId(callId), open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent decline = new Intent(c, CallActivity.class)
                .putExtra("peer", from)
                .putExtra("callId", callId)
                .putExtra("decline", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent declinePi = PendingIntent.getActivity(c, notifId(callId) + 1, decline,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification n = new NotificationCompat.Builder(c, channelId(c))
                .setSmallIcon(android.R.drawable.sym_call_incoming)
                .setContentTitle("Incoming " + kind + " call")
                .setContentText("@" + from + " is calling")
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setOngoing(true)
                .setAutoCancel(false)
                .setTimeoutAfter(RING_MS)
                .setFullScreenIntent(openPi, true)
                .setContentIntent(openPi)
                .addAction(0, "Decline", declinePi)
                .addAction(0, "Answer", openPi)
                .build();

        try {
            NotificationManagerCompat.from(c).notify(notifId(callId), n);
        } catch (SecurityException ignored) {
            // Notification permission was denied. The call can still be answered from the app.
        }
    }

    /** Shows a visible notification for the server's push test. */
    public static void showTest(Context c, String text) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        String id = "push_tests";
        if (nm.getNotificationChannel(id) == null) {
            nm.createNotificationChannel(new NotificationChannel(id, "Push tests", NotificationManager.IMPORTANCE_DEFAULT));
        }
        Notification n = new NotificationCompat.Builder(c, id)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Sigil push test")
                .setContentText(text == null ? "Firebase push works" : text)
                .setAutoCancel(true)
                .build();
        try {
            NotificationManagerCompat.from(c).notify(777, n);
        } catch (SecurityException ignored) {
            // Notification permission is not granted.
        }
    }

    public static void cancel(Context c, String callId) {
        if (callId == null) return;
        NotificationManagerCompat.from(c).cancel(notifId(callId));
    }

    private static int notifId(String callId) {
        return callId == null ? 0 : Math.abs(callId.hashCode());
    }
}
