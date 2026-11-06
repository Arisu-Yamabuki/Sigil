package org.sigil.app;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.VibrationEffect;
import android.os.Vibrator;

/** Plays the incoming-call ringtone and vibration. Used by the call screen and the Settings tests. */
public final class Ringer {
    private static final long[] PATTERN = {0, 800, 600};

    private Ringer() {}

    /** Loops the chosen ringtone. Falls back to the phone's default ringtone if that fails. */
    public static MediaPlayer startLoop(Context c) {
        MediaPlayer p = play(c, Notifier.ringtone(c));
        if (p == null) p = play(c, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE));
        return p;
    }

    public static void stop(MediaPlayer p) {
        if (p == null) return;
        try {
            p.stop();
        } catch (IllegalStateException ignored) {
            // Already stopped.
        }
        p.release();
    }

    /** Repeats the vibration until stopVibration is called. Respects the Settings switch. */
    public static Vibrator startVibration(Context c) {
        if (!Settings.vibrate(c)) return null;
        Vibrator v = c.getSystemService(Vibrator.class);
        if (v != null) v.vibrate(VibrationEffect.createWaveform(PATTERN, 0));
        return v;
    }

    public static void stopVibration(Vibrator v) {
        if (v != null) v.cancel();
    }

    private static MediaPlayer play(Context c, Uri uri) {
        MediaPlayer p = null;
        try {
            p = new MediaPlayer();
            p.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            p.setDataSource(c, uri);
            p.setLooping(true);
            p.setVolume(1f, 1f);
            p.prepare();
            p.start();
            return p;
        } catch (Exception e) {
            if (p != null) p.release();
            return null;
        }
    }
}
