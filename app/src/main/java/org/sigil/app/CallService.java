package org.sigil.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

/**
 * Keeps a call alive in the background. Android requires a visible notification while the
 * microphone or camera is in use by a foreground service.
 */
public class CallService extends Service {
    private static final String CHANNEL = "ongoing_calls";
    private static final int NOTIF = 4242;

    public static void start(Context c, String peer, boolean video) {
        try {
            ContextCompat.startForegroundService(c,
                    new Intent(c, CallService.class).putExtra("peer", peer).putExtra("video", video));
        } catch (RuntimeException ignored) {
            // Starting a foreground service can be refused in rare cases. The call still runs in the app.
        }
    }

    public static void stop(Context c) {
        c.stopService(new Intent(c, CallService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String peer = intent == null ? "" : String.valueOf(intent.getStringExtra("peer"));
        boolean video = intent != null && intent.getBooleanExtra("video", false);

        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Ongoing calls", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }

        Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                launch == null ? new Intent() : launch, PendingIntent.FLAG_IMMUTABLE);

        Notification n = new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.sym_call_outgoing)
                .setContentTitle("Call in progress")
                .setContentText("@" + peer)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setOngoing(true)
                .setContentIntent(pi)
                .build();

        if (Build.VERSION.SDK_INT >= 29) {
            int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            if (video) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
            startForeground(NOTIF, n, type);
        } else {
            startForeground(NOTIF, n);
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
