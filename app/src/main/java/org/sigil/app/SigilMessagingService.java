package org.sigil.app;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.util.Map;

/** Receives incoming-call pushes. The payload holds only the caller, call id and video flag. */
public class SigilMessagingService extends FirebaseMessagingService {

    @Override
    public void onNewToken(String token) {
        Push.saveToken(this, token);
        Push.upload(this);
    }

    @Override
    public void onMessageReceived(RemoteMessage message) {
        Map<String, String> d = message.getData();
        if ("test".equals(d.get("type"))) {
            Notifier.showTest(this, d.get("text"));
            return;
        }
        if (!"call".equals(d.get("type"))) return;
        String from = d.get("from");
        String callId = d.get("callId");
        if (from == null || callId == null) return;
        Notifier.showIncomingCall(this, from, callId, "1".equals(d.get("video")));
    }
}
