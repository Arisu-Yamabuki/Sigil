package org.sigil.app;

import android.app.Application;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.security.Security;

public class SigilApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        // Android ships a stripped "BC" provider. Replace it with the full Bouncy Castle one.
        Security.removeProvider("BC");
        Security.insertProviderAt(new BouncyCastleProvider(), 1);
    }
}
