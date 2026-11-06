package org.sigil.app;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.ProcessLifecycleOwner;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.security.Security;

public class SigilApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        // Android ships a stripped "BC" provider. Replace it with the full Bouncy Castle one.
        Security.removeProvider("BC");
        Security.insertProviderAt(new BouncyCastleProvider(), 1);
        // Only WebRTC errors reach logcat; its routine messages are switched off.
        org.webrtc.Logging.enableLogToDebugOutput(org.webrtc.Logging.Severity.LS_ERROR);

        // Lock when the whole app goes to the background, unless a call is in progress.
        ProcessLifecycleOwner.get().getLifecycle().addObserver(new DefaultLifecycleObserver() {
            @Override
            public void onStop(@NonNull LifecycleOwner owner) {
                if (Settings.autoLock(SigilApp.this) && !CallActivity.active) Session.lock();
            }
        });
    }
}
