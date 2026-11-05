package org.sigil.app;

import android.Manifest;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;

import org.bouncycastle.openpgp.PGPPublicKey;
import org.json.JSONArray;
import org.json.JSONObject;
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.Camera2Enumerator;
import org.webrtc.DataChannel;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.MediaStreamTrack;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpTransceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * One call, audio or video, peer to peer over WebRTC. Media is encrypted by WebRTC (DTLS-SRTP).
 * Setup messages go through the server as PGP ciphertext made for the other person's key.
 */
public class CallActivity extends AppCompatActivity implements Signal.Listener {

    /** True while a call screen is open, so auto-lock does not interrupt a call. */
    public static volatile boolean active = false;

    private static final long RING_TIMEOUT_MS = 45_000;

    private String peer, token, callId;
    private String pendingOffer;
    private boolean video, outgoing;
    private boolean ended = false;
    private boolean released = false;
    private boolean remoteSet = false;
    private boolean muted = false;
    private boolean cameraOff = false;

    private Pgp.Identity identity;
    private PGPPublicKey peerKey;

    private EglBase egl;
    private PeerConnectionFactory factory;
    private PeerConnection pc;
    private AudioSource audioSource;
    private AudioTrack audioTrack;
    private VideoCapturer capturer;
    private SurfaceTextureHelper texHelper;
    private VideoSource videoSource;
    private VideoTrack localVideo;

    private SurfaceViewRenderer localView, remoteView;
    private TextView title, status;
    private View incomingRow, controls;
    private MaterialButton muteBtn, cameraBtn;
    private final List<String> queuedIce = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final ActivityResultLauncher<String[]> permissions = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                boolean audioOk = Boolean.TRUE.equals(result.get(Manifest.permission.RECORD_AUDIO));
                boolean cameraOk = !video || Boolean.TRUE.equals(result.get(Manifest.permission.CAMERA));
                if (audioOk && cameraOk) begin();
                else endCall("Microphone or camera permission is needed", true);
            });

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        // Calls must not leak into screenshots or recents, and the screen stays on during a call.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_call);
        EdgeToEdge.apply(this);

        identity = Session.identity();
        token = Session.token(this);
        peer = getIntent().getStringExtra("peer");
        video = getIntent().getBooleanExtra("video", false);
        outgoing = getIntent().getBooleanExtra("outgoing", false);
        pendingOffer = getIntent().getStringExtra("offer");
        callId = getIntent().getStringExtra("callId");

        if (identity == null || token == null || peer == null) {
            Toast.makeText(this, "Unlock Sigil to make calls", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        if (outgoing) callId = UUID.randomUUID().toString();
        if (callId == null || pendingOffer == null && !outgoing) {
            finish();
            return;
        }

        active = true;
        title = findViewById(R.id.title);
        status = findViewById(R.id.status);
        localView = findViewById(R.id.local);
        remoteView = findViewById(R.id.remote);
        incomingRow = findViewById(R.id.incomingRow);
        controls = findViewById(R.id.controls);
        muteBtn = findViewById(R.id.muteBtn);
        cameraBtn = findViewById(R.id.cameraBtn);

        title.setText("@" + peer);
        setupRenderers();
        Signal.setListener(this);

        findViewById(R.id.endBtn).setOnClickListener(v -> endCall("Call ended", true));
        muteBtn.setOnClickListener(v -> toggleMute());
        cameraBtn.setOnClickListener(v -> toggleCamera());
        findViewById(R.id.acceptBtn).setOnClickListener(v -> requestMediaThenBegin());
        findViewById(R.id.declineBtn).setOnClickListener(v -> endCall("Call declined", true));

        if (outgoing) {
            status.setText("Calling...");
            controls.setVisibility(View.VISIBLE);
            requestMediaThenBegin();
            handler.postDelayed(() -> {
                if (!remoteSet) endCall("No answer", true);
            }, RING_TIMEOUT_MS);
        } else {
            status.setText("Incoming " + (video ? "video" : "voice") + " call");
            incomingRow.setVisibility(View.VISIBLE);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        active = false;
        Signal.releaseListener(this);
        handler.removeCallbacksAndMessages(null);
        release();
    }

    // ---- Media setup ----------------------------------------------------------------------

    private void setupRenderers() {
        egl = EglBase.create();
        localView.init(egl.getEglBaseContext(), null);
        localView.setMirror(true);
        localView.setEnableHardwareScaler(true);
        remoteView.init(egl.getEglBaseContext(), null);
        remoteView.setEnableHardwareScaler(true);
        localView.setVisibility(video ? View.VISIBLE : View.GONE);
    }

    private void requestMediaThenBegin() {
        incomingRow.setVisibility(View.GONE);
        controls.setVisibility(View.VISIBLE);
        if (video) {
            permissions.launch(new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA});
        } else {
            permissions.launch(new String[]{Manifest.permission.RECORD_AUDIO});
        }
    }

    /** Creates the peer connection after fetching TURN credentials and the other person's key. */
    private void begin() {
        if (ended || released) return;
        status.setText("Connecting...");
        Async.run(() -> {
            peerKey = Pgp.encryptionKeyFrom(Api.publicKey(token, peer).getString("publicKey"));
            return Api.ice(token);
        }, (ice, err) -> {
            if (err != null) {
                endCall(Async.message(err), false);
                return;
            }
            try {
                initMedia();
                createPeer(ice);
                if (outgoing) makeOffer();
                else acceptOffer();
            } catch (Exception e) {
                endCall("Could not start the call: " + Async.message(e), true);
            }
        });
    }

    private void initMedia() {
        PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(getApplicationContext()).createInitializationOptions());
        factory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(new DefaultVideoEncoderFactory(egl.getEglBaseContext(), true, true))
                .setVideoDecoderFactory(new DefaultVideoDecoderFactory(egl.getEglBaseContext()))
                .createPeerConnectionFactory();

        audioSource = factory.createAudioSource(new MediaConstraints());
        audioTrack = factory.createAudioTrack("audio0", audioSource);

        if (video) {
            Camera2Enumerator enumerator = new Camera2Enumerator(this);
            String name = null;
            for (String n : enumerator.getDeviceNames()) {
                if (enumerator.isFrontFacing(n)) {
                    name = n;
                    break;
                }
            }
            if (name == null && enumerator.getDeviceNames().length > 0) name = enumerator.getDeviceNames()[0];
            if (name == null) throw new IllegalStateException("No camera found");

            capturer = enumerator.createCapturer(name, null);
            texHelper = SurfaceTextureHelper.create("CaptureThread", egl.getEglBaseContext());
            videoSource = factory.createVideoSource(capturer.isScreencast());
            capturer.initialize(texHelper, getApplicationContext(), videoSource.getCapturerObserver());
            capturer.startCapture(640, 480, 30);
            localVideo = factory.createVideoTrack("video0", videoSource);
            localVideo.addSink(localView);
        }
    }

    private void createPeer(JSONObject ice) {
        PeerConnection.RTCConfiguration cfg = new PeerConnection.RTCConfiguration(parseIce(ice));
        cfg.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        cfg.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE;
        cfg.rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE;
        pc = factory.createPeerConnection(cfg, observer);
        if (pc == null) throw new IllegalStateException("Could not create the connection");
        pc.addTrack(audioTrack, Collections.singletonList("sigil"));
        if (localVideo != null) pc.addTrack(localVideo, Collections.singletonList("sigil"));
    }

    private static List<PeerConnection.IceServer> parseIce(JSONObject ice) {
        List<PeerConnection.IceServer> out = new ArrayList<>();
        JSONArray arr = ice.optJSONArray("iceServers");
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject s = arr.optJSONObject(i);
            if (s == null) continue;
            List<String> urls = new ArrayList<>();
            Object u = s.opt("urls");
            if (u instanceof JSONArray) {
                JSONArray ua = (JSONArray) u;
                for (int j = 0; j < ua.length(); j++) urls.add(ua.optString(j));
            } else {
                urls.add(String.valueOf(u));
            }
            PeerConnection.IceServer.Builder b = PeerConnection.IceServer.builder(urls);
            if (s.has("username")) b.setUsername(s.optString("username"));
            if (s.has("credential")) b.setPassword(s.optString("credential"));
            out.add(b.createIceServer());
        }
        return out;
    }

    private final PeerConnection.Observer observer = new PeerConnection.Observer() {
        @Override public void onSignalingChange(PeerConnection.SignalingState state) {}

        @Override
        public void onIceConnectionChange(PeerConnection.IceConnectionState state) {
            runOnUiThread(() -> {
                if (state == PeerConnection.IceConnectionState.CONNECTED
                        || state == PeerConnection.IceConnectionState.COMPLETED) {
                    status.setText("Connected");
                } else if (state == PeerConnection.IceConnectionState.DISCONNECTED
                        || state == PeerConnection.IceConnectionState.FAILED) {
                    endCall("Connection lost", true);
                }
            });
        }

        @Override public void onIceConnectionReceivingChange(boolean receiving) {}
        @Override public void onIceGatheringChange(PeerConnection.IceGatheringState state) {}

        @Override
        public void onIceCandidate(IceCandidate c) {
            try {
                JSONObject j = new JSONObject()
                        .put("sdpMid", c.sdpMid)
                        .put("sdpMLineIndex", c.sdpMLineIndex)
                        .put("candidate", c.sdp);
                sendSignal("ice", j.toString());
            } catch (Exception ignored) {
                // A candidate that cannot be encoded is simply not sent.
            }
        }

        @Override public void onIceCandidatesRemoved(IceCandidate[] candidates) {}
        @Override public void onAddStream(MediaStream stream) {}
        @Override public void onRemoveStream(MediaStream stream) {}
        @Override public void onDataChannel(DataChannel channel) {}
        @Override public void onRenegotiationNeeded() {}

        @Override
        public void onTrack(RtpTransceiver transceiver) {
            MediaStreamTrack track = transceiver.getReceiver().track();
            if (track instanceof VideoTrack) {
                runOnUiThread(() -> ((VideoTrack) track).addSink(remoteView));
            }
        }
    };

    // ---- Offer and answer -----------------------------------------------------------------

    private void makeOffer() {
        pc.createOffer(new SdpAdapter() {
            @Override
            public void onCreateSuccess(SessionDescription desc) {
                pc.setLocalDescription(new SdpAdapter() {
                    @Override
                    public void onSetSuccess() {
                        sendSignal("offer", sdpJson(desc));
                    }

                    @Override
                    public void onSetFailure(String error) {
                        endCall("Setup failed", true);
                    }
                }, desc);
            }

            @Override
            public void onCreateFailure(String error) {
                endCall("Setup failed", true);
            }
        }, offerConstraints());
    }

    private void acceptOffer() {
        Async.run(() -> Pgp.decrypt(pendingOffer, identity), (plain, err) -> {
            if (err != null) {
                endCall("Could not read the call", true);
                return;
            }
            try {
                JSONObject j = new JSONObject(plain);
                SessionDescription offer = new SessionDescription(SessionDescription.Type.OFFER, j.optString("sdp"));
                pc.setRemoteDescription(new SdpAdapter() {
                    @Override
                    public void onSetSuccess() {
                        remoteSet = true;
                        flushQueuedIce();
                        pc.createAnswer(new SdpAdapter() {
                            @Override
                            public void onCreateSuccess(SessionDescription answer) {
                                pc.setLocalDescription(new SdpAdapter() {
                                    @Override
                                    public void onSetSuccess() {
                                        sendSignal("answer", sdpJson(answer));
                                    }
                                }, answer);
                            }

                            @Override
                            public void onCreateFailure(String error) {
                                endCall("Setup failed", true);
                            }
                        }, new MediaConstraints());
                    }

                    @Override
                    public void onSetFailure(String error) {
                        endCall("Setup failed", true);
                    }
                }, offer);
            } catch (Exception e) {
                endCall("Setup failed", true);
            }
        });
    }

    private void applyAnswer(String plain) {
        try {
            JSONObject j = new JSONObject(plain);
            SessionDescription answer = new SessionDescription(SessionDescription.Type.ANSWER, j.optString("sdp"));
            pc.setRemoteDescription(new SdpAdapter() {
                @Override
                public void onSetSuccess() {
                    remoteSet = true;
                    flushQueuedIce();
                }

                @Override
                public void onSetFailure(String error) {
                    endCall("Setup failed", true);
                }
            }, answer);
        } catch (Exception e) {
            endCall("Setup failed", true);
        }
    }

    private void addIce(String plain) {
        if (!remoteSet || pc == null) {
            queuedIce.add(plain);
            return;
        }
        try {
            JSONObject j = new JSONObject(plain);
            pc.addIceCandidate(new IceCandidate(
                    j.optString("sdpMid"), j.optInt("sdpMLineIndex"), j.optString("candidate")));
        } catch (Exception ignored) {
            // A malformed candidate is dropped.
        }
    }

    private void flushQueuedIce() {
        for (String c : queuedIce) addIce(c);
        queuedIce.clear();
    }

    private static MediaConstraints offerConstraints() {
        MediaConstraints c = new MediaConstraints();
        c.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
        c.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"));
        return c;
    }

    private static String sdpJson(SessionDescription d) {
        try {
            return new JSONObject().put("type", d.type.canonicalForm()).put("sdp", d.description).toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    // ---- Signaling ------------------------------------------------------------------------

    /** Encrypts the plaintext for the other person and sends it. Empty body for control messages. */
    private void sendSignal(String kind, String plain) {
        Async.run(() -> {
            String body = (plain == null || peerKey == null) ? "" : Pgp.encrypt(plain, Collections.singletonList(peerKey));
            JSONObject m = new JSONObject()
                    .put("type", "call")
                    .put("kind", kind)
                    .put("to", peer)
                    .put("callId", callId)
                    .put("video", video)
                    .put("body", body);
            return Signal.send(m);
        }, (ok, err) -> {
            if (err != null) endCall(Async.message(err), false);
        });
    }

    @Override
    public void onCallMessage(JSONObject m) {
        if ("error".equals(m.optString("type"))) {
            if (callId != null && callId.equals(m.optString("callId"))) {
                endCall("@" + peer + " is not available", false);
            }
            return;
        }

        String kind = m.optString("kind");
        String cid = m.optString("callId");
        if (!callId.equals(cid)) {
            // Already in a call: tell the other caller we are busy.
            if ("offer".equals(kind)) sendBusy(m.optString("from"), cid);
            return;
        }

        String body = m.optString("body");
        switch (kind) {
            case "answer":
                decryptThen(body, this::applyAnswer);
                break;
            case "ice":
                decryptThen(body, this::addIce);
                break;
            case "hangup":
            case "busy":
                endCall("Call ended", false);
                break;
            default:
                break;
        }
    }

    private void decryptThen(String body, Consumer<String> then) {
        Async.run(() -> Pgp.decrypt(body, identity), (plain, err) -> {
            if (err == null && plain != null) then.accept(plain);
        });
    }

    private void sendBusy(String to, String cid) {
        Async.run(() -> Signal.send(new JSONObject()
                .put("type", "call")
                .put("kind", "busy")
                .put("to", to)
                .put("callId", cid)
                .put("video", false)
                .put("body", "")), (ok, err) -> {
        });
    }

    // ---- Controls -------------------------------------------------------------------------

    private void toggleMute() {
        muted = !muted;
        if (audioTrack != null) audioTrack.setEnabled(!muted);
        muteBtn.setText(muted ? "Unmute" : "Mute");
    }

    private void toggleCamera() {
        cameraOff = !cameraOff;
        if (localVideo != null) localVideo.setEnabled(!cameraOff);
        cameraBtn.setText(cameraOff ? "Camera on" : "Camera off");
    }

    // ---- Teardown -------------------------------------------------------------------------

    private void endCall(String message, boolean notifyPeer) {
        if (ended) return;
        ended = true;
        if (notifyPeer && peerKey != null && callId != null) sendSignal("hangup", null);
        status.setText(message);
        release();
        handler.postDelayed(this::finish, 1200);
    }

    private void release() {
        if (released) return;
        released = true;
        try {
            if (capturer != null) {
                capturer.stopCapture();
                capturer.dispose();
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        if (videoSource != null) videoSource.dispose();
        if (texHelper != null) texHelper.dispose();
        if (audioTrack != null) audioTrack.dispose();
        if (audioSource != null) audioSource.dispose();
        if (pc != null) {
            pc.close();
            pc.dispose();
        }
        if (factory != null) factory.dispose();
        if (localView != null) localView.release();
        if (remoteView != null) remoteView.release();
        if (egl != null) egl.release();
    }

    /** Callbacks that do nothing on their own. Subclasses override only what they need. */
    private static class SdpAdapter implements SdpObserver {
        @Override public void onCreateSuccess(SessionDescription d) {}
        @Override public void onSetSuccess() {}
        @Override public void onCreateFailure(String error) {}
        @Override public void onSetFailure(String error) {}
    }
}
