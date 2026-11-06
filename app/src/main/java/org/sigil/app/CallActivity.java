package org.sigil.app;

import android.Manifest;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;
import android.os.VibrationEffect;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

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
 * One call, audio or video, peer to peer over WebRTC. WebRTC encrypts the media itself (DTLS-SRTP).
 * Setup messages travel through the server as PGP ciphertext made for the other person's key.
 *
 * The offer and answer are sent only after ICE gathering completes, so every network candidate
 * is inside the SDP. That makes setup work even when the callee wakes from a push later.
 */
public class CallActivity extends AppCompatActivity implements Signal.Listener {

    /** True while a call screen is open, so auto-lock does not interrupt a call. */
    public static volatile boolean active = false;

    private static final long RING_TIMEOUT_MS = 45_000;
    private static final long CONNECT_TIMEOUT_MS = 45_000;
    private static final long GATHER_FALLBACK_MS = 4_000;
    private static final String TAG = "SigilCall";

    private String peer, token, callId, pendingOffer;
    private boolean video, outgoing, declineOnly;
    private boolean ended, released, remoteSet, muted, cameraOff;
    private boolean gatheringDone, descSent;
    private SessionDescription localDesc;
    private boolean connected = false;
    private MediaPlayer ringer;
    private Vibrator vibrator;
    /** Kinds of network paths found so far: host (local), srflx (public address), relay (TURN). */
    private final java.util.Set<String> routes = new java.util.TreeSet<>();
    private boolean fallbackScheduled = false;

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
    private MaterialButton acceptBtn, muteBtn, cameraBtn;
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
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 27) {
            // Ring over the lock screen, like a phone call.
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }
        setContentView(R.layout.activity_call);
        EdgeToEdge.apply(this);

        peer = getIntent().getStringExtra("peer");
        video = getIntent().getBooleanExtra("video", false);
        outgoing = getIntent().getBooleanExtra("outgoing", false);
        declineOnly = getIntent().getBooleanExtra("decline", false);
        pendingOffer = getIntent().getStringExtra("offer");
        callId = getIntent().getStringExtra("callId");
        token = Session.token(this);

        title = findViewById(R.id.title);
        status = findViewById(R.id.status);
        localView = findViewById(R.id.local);
        remoteView = findViewById(R.id.remote);
        incomingRow = findViewById(R.id.incomingRow);
        controls = findViewById(R.id.controls);
        acceptBtn = findViewById(R.id.acceptBtn);
        muteBtn = findViewById(R.id.muteBtn);
        cameraBtn = findViewById(R.id.cameraBtn);

        if (peer == null || token == null) {
            finish();
            return;
        }
        if (outgoing) callId = UUID.randomUUID().toString();
        if (callId == null) {
            finish();
            return;
        }
        title.setText("@" + peer);

        findViewById(R.id.endBtn).setOnClickListener(v -> endCall("Call ended", true));
        findViewById(R.id.declineBtn).setOnClickListener(v -> endCall("Call declined", true));
        acceptBtn.setOnClickListener(v -> requestMediaThenBegin());
        muteBtn.setOnClickListener(v -> toggleMute());
        cameraBtn.setOnClickListener(v -> toggleCamera());

        Signal.connect(token);
        Signal.setListener(this);

        if (!outgoing && !declineOnly) startRinging();

        if (declineOnly) {
            // Opened from the Decline button on the notification: tell the caller and leave.
            Notifier.cancel(this, callId);
            incomingRow.setVisibility(View.GONE);
            status.setText("Declining...");
            handler.postDelayed(() -> endCall("Call declined", true), 1500);
            return;
        }

        active = true;
        setupRenderers();

        if (Session.identity() == null) {
            if (Session.hasKeys(this)) promptUnlock();
            else {
                Toast.makeText(this, "Set up your keys first", Toast.LENGTH_SHORT).show();
                finish();
            }
            return;
        }
        start();
    }

    /** Rings and vibrates until the call is answered, declined, or ends. */
    private void startRinging() {
        ringer = Ringer.startLoop(this);
        vibrator = Ringer.startVibration(this);
    }

    private void stopRinging() {
        Ringer.stop(ringer);
        ringer = null;
        Ringer.stopVibration(vibrator);
        vibrator = null;
    }

    private void promptUnlock() {
        final EditText et = new EditText(this);
        et.setHint("Key passphrase");
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        et.setPadding(pad, pad, pad, pad);
        final android.content.Context app = getApplicationContext();
        new MaterialAlertDialogBuilder(this)
                .setTitle("Unlock to answer")
                .setMessage("Enter your key passphrase to read this call.")
                .setView(et)
                .setCancelable(false)
                .setPositiveButton("Unlock", (d, w) -> Async.run(
                        () -> Session.unlock(app, et.getText().toString().toCharArray()),
                        (ident, err) -> {
                            if (err != null) {
                                Toast.makeText(this, "Wrong passphrase", Toast.LENGTH_SHORT).show();
                                promptUnlock();
                            } else {
                                start();
                            }
                        }))
                .setNegativeButton("Decline", (d, w) -> endCall("Call declined", true))
                .show();
    }

    private void start() {
        identity = Session.identity();
        if (identity == null) return;
        Notifier.cancel(this, callId);
        if (outgoing) {
            status.setText("Calling...");
            controls.setVisibility(View.VISIBLE);
            requestMediaThenBegin();
            handler.postDelayed(() -> {
                if (!remoteSet && !ended) endCall("No answer", true);
            }, RING_TIMEOUT_MS);
        } else {
            incomingRow.setVisibility(View.VISIBLE);
            acceptBtn.setEnabled(pendingOffer != null);
            status.setText(pendingOffer != null
                    ? "Incoming " + (video ? "video" : "voice") + " call"
                    : "Connecting...");
        }
    }

    /** Diagnostics are turned off. Events are not recorded or shown. */
    private void log(String msg) {
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        active = false;
        stopRinging();
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
        stopRinging();
        if (!outgoing && pendingOffer == null) return;
        incomingRow.setVisibility(View.GONE);
        controls.setVisibility(View.VISIBLE);
        Notifier.cancel(this, callId);
        if (video) permissions.launch(new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA});
        else permissions.launch(new String[]{Manifest.permission.RECORD_AUDIO});
    }

    /** Fetches the other person's key and TURN credentials, then builds the connection. */
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
                CallService.start(this, peer, video);
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
        List<PeerConnection.IceServer> servers = parseIce(ice);
        log("ICE servers configured: " + servers.size());
        PeerConnection.RTCConfiguration cfg = new PeerConnection.RTCConfiguration(servers);
        cfg.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        cfg.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE;
        cfg.rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE;
        // Relay-only: every media path goes through the TURN server, hiding both IP addresses.
        cfg.iceTransportsType = Settings.relayOnly(this)
                ? PeerConnection.IceTransportsType.RELAY
                : PeerConnection.IceTransportsType.ALL;
        log("relay only: " + Settings.relayOnly(this));
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
            log("ICE state: " + state);
            runOnUiThread(() -> {
                switch (state) {
                    case CHECKING:
                        status.setText("Finding a route...");
                        break;
                    case CONNECTED:
                    case COMPLETED:
                        connected = true;
                        status.setText("Connected");
                        break;
                    case DISCONNECTED:
                        if (connected) status.setText("Reconnecting...");
                        break;
                    case FAILED:
                        endCall("Could not connect. " + routeSummary(), true);
                        break;
                    default:
                        break;
                }
            });
        }

        @Override public void onIceConnectionReceivingChange(boolean receiving) {}

        @Override
        public void onIceGatheringChange(PeerConnection.IceGatheringState state) {
            if (state == PeerConnection.IceGatheringState.COMPLETE) {
                runOnUiThread(() -> {
                    gatheringDone = true;
                    maybeSendLocal();
                });
            }
        }

        // Candidates travel inside the SDP and are not sent one by one.
        // Here we only record which kinds of path were found, for diagnosis.
        @Override
        public void onIceCandidate(IceCandidate c) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(" typ (\\w+)").matcher(c.sdp);
            String type = m.find() ? m.group(1) : "unknown";
            log("candidate found: " + type);
            runOnUiThread(() -> routes.add(type));
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

    /**
     * Sends our description once every candidate is inside it. If the gathering-complete callback
     * never arrives, send anyway after a short wait, so setup cannot stall.
     */
    private void maybeSendLocal() {
        if (localDesc == null || descSent || pc == null) return;
        if (!gatheringDone) {
            if (!fallbackScheduled) {
                fallbackScheduled = true;
                handler.postDelayed(() -> {
                    gatheringDone = true;
                    maybeSendLocal();
                }, GATHER_FALLBACK_MS);
            }
            return;
        }
        // Relay-only with no relay path: the connection cannot succeed, so say so now.
        if (Settings.relayOnly(this) && !routes.contains("relay")) {
            endCall("No relay path could be created. The relay server rejected the login or is unreachable.", true);
            return;
        }
        // The description returned by createOffer/createAnswer has NO network addresses yet.
        // Send the one WebRTC holds after gathering, which contains every address.
        SessionDescription full = pc.getLocalDescription();
        if (full == null) return;
        localDesc = full;
        descSent = true;
        boolean isOffer = localDesc.type == SessionDescription.Type.OFFER;
        int addresses = localDesc.description.split("a=candidate", -1).length - 1;
        log("sending " + (isOffer ? "offer" : "answer") + " with " + addresses + " addresses");
        sendSignal(isOffer ? "offer" : "answer", sdpJson(localDesc));
    }

    private void setupFailed(String reason) {
        log("setup failed: " + reason);
        endCall("Setup failed: " + reason, true);
    }

    private void connectTimeout() {
        if (!connected && !ended) {
            endCall("Could not connect in time. " + routeSummary(), true);
        }
    }

    /** Explains which paths were found, so the screen says what is missing. */
    private String routeSummary() {
        log("routes found: " + routes);
        if (routes.isEmpty()) return "No network paths were found. Check this phone's internet connection.";
        if (!routes.contains("relay")) {
            return "Paths found: " + routes + ". No relay (TURN) path, so the relay server is unreachable or rejected the login.";
        }
        return "Paths found: " + routes + ". The relay exists but the two phones could not reach each other.";
    }

    private void makeOffer() {
        pc.createOffer(new SdpAdapter() {
            @Override
            public void onCreateSuccess(SessionDescription desc) {
                pc.setLocalDescription(new SdpAdapter() {
                    @Override
                    public void onSetSuccess() {
                        runOnUiThread(() -> {
                            localDesc = desc;
                            maybeSendLocal();
                        });
                    }

                    @Override
                    public void onSetFailure(String error) {
                        setupFailed(error);
                    }
                }, desc);
            }

            @Override
            public void onCreateFailure(String error) {
                        setupFailed(error);
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
                        handler.postDelayed(CallActivity.this::connectTimeout, CONNECT_TIMEOUT_MS);
                        pc.createAnswer(new SdpAdapter() {
                            @Override
                            public void onCreateSuccess(SessionDescription answer) {
                                pc.setLocalDescription(new SdpAdapter() {
                                    @Override
                                    public void onSetSuccess() {
                                        runOnUiThread(() -> {
                                            localDesc = answer;
                                            maybeSendLocal();
                                        });
                                    }

                                    @Override
                                    public void onSetFailure(String error) {
                        setupFailed(error);
                                    }
                                }, answer);
                            }

                            @Override
                            public void onCreateFailure(String error) {
                        setupFailed(error);
                            }
                        }, new MediaConstraints());
                    }

                    @Override
                    public void onSetFailure(String error) {
                        setupFailed(error);
                    }
                }, offer);
            } catch (Exception e) {
                setupFailed(String.valueOf(e.getMessage()));
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
                    handler.postDelayed(CallActivity.this::connectTimeout, CONNECT_TIMEOUT_MS);
                }

                @Override
                public void onSetFailure(String error) {
                        setupFailed(error);
                }
            }, answer);
        } catch (Exception e) {
                setupFailed(String.valueOf(e.getMessage()));
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

    /** Encrypts for the other person and sends. Control messages have an empty body. */
    private void sendSignal(String kind, String plain) {
        Async.run(() -> {
            String body = (plain == null || peerKey == null)
                    ? ""
                    : Pgp.encrypt(plain, Collections.singletonList(peerKey));
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
            // Already on a call: tell the other caller we are busy.
            if ("offer".equals(kind)) sendBusy(m.optString("from"), cid);
            return;
        }

        String body = m.optString("body");
        log("received " + kind);
        switch (kind) {
            case "offer":
                // Arrived after the notification, from the queue on the server.
                if (!outgoing && pendingOffer == null && !body.isEmpty()) {
                    pendingOffer = body;
                    acceptBtn.setEnabled(true);
                    status.setText("Incoming " + (video ? "video" : "voice") + " call");
                }
                break;
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
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread(() -> endCall(message, notifyPeer));
            return;
        }
        if (ended) return;
        ended = true;
        stopRinging();
        log("ending: " + message);
        if (notifyPeer && callId != null) sendSignal("hangup", null);
        if (status != null) status.setText(message);
        Notifier.cancel(this, callId);
        CallService.stop(this);
        release();
        handler.postDelayed(this::finish, 1200);
    }

    private void release() {
        if (released) return;
        released = true;
        CallService.stop(this);
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

    /** Callbacks that do nothing by default. Subclasses override only what they need. */
    private static class SdpAdapter implements SdpObserver {
        @Override public void onCreateSuccess(SessionDescription d) {}
        @Override public void onSetSuccess() {}
        @Override public void onCreateFailure(String error) {}
        @Override public void onSetFailure(String error) {}
    }
}
