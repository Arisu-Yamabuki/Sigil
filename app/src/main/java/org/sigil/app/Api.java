package org.sigil.app;

import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Thin client for the Sigil server. Every payload is either JSON metadata or PGP ciphertext. */
public final class Api {
    private static final MediaType JSON_TYPE = MediaType.get("application/json; charset=utf-8");
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build();

    private Api() {}

    private static JSONObject call(String method, String path, String token, JSONObject body) throws Exception {
        Request.Builder rb = new Request.Builder().url(BuildConfig.API_BASE + path);
        if (token != null) rb.header("Authorization", "Bearer " + token);

        RequestBody rbody = null;
        if (body != null) {
            rbody = RequestBody.create(body.toString(), JSON_TYPE);
        } else if (!method.equals("GET") && !method.equals("DELETE")) {
            rbody = RequestBody.create("{}", JSON_TYPE);
        }
        rb.method(method, rbody);

        try (Response r = HTTP.newCall(rb.build()).execute()) {
            String text = r.body() != null ? r.body().string() : "";
            JSONObject json = text.isEmpty() ? new JSONObject() : new JSONObject(text);
            if (!r.isSuccessful()) {
                throw new Exception(json.optString("error", "Server error " + r.code()));
            }
            return json;
        }
    }

    public static JSONObject register(String username, String password) throws Exception {
        JSONObject b = new JSONObject().put("username", username).put("password", password);
        return call("POST", "/api/auth/register", null, b);
    }

    public static JSONObject login(String username, String password) throws Exception {
        JSONObject b = new JSONObject().put("username", username).put("password", password);
        return call("POST", "/api/auth/login", null, b);
    }

    public static JSONObject search(String token, String q) throws Exception {
        return call("GET", "/api/users/search?q=" + q, token, null);
    }

    public static JSONObject publicKey(String token, String username) throws Exception {
        return call("GET", "/api/users/" + username + "/public-key", token, null);
    }

    public static JSONObject uploadKey(String token, String armoredPublic, String fingerprint) throws Exception {
        JSONObject b = new JSONObject().put("publicKey", armoredPublic).put("fingerprint", fingerprint);
        return call("PUT", "/api/users/me/public-key", token, b);
    }

    public static JSONObject inbox(String token) throws Exception {
        return call("GET", "/api/messages/inbox", token, null);
    }

    public static JSONObject editMessage(String token, String id, String armoredBody) throws Exception {
        JSONObject b = new JSONObject().put("body", armoredBody);
        return call("PUT", "/api/messages/" + id, token, b);
    }

    public static JSONObject unsend(String token, String id) throws Exception {
        return call("DELETE", "/api/messages/" + id, token, null);
    }

    public static JSONObject getBlocks(String token) throws Exception {
        return call("GET", "/api/blocks", token, null);
    }

    public static JSONObject block(String token, String username) throws Exception {
        return call("POST", "/api/blocks/" + username, token, null);
    }

    public static JSONObject unblock(String token, String username) throws Exception {
        return call("DELETE", "/api/blocks/" + username, token, null);
    }

    public static JSONObject ice(String token) throws Exception {
        return call("GET", "/api/calls/ice", token, null);
    }

    public static JSONObject messages(String token, String username) throws Exception {
        return call("GET", "/api/messages/with/" + username, token, null);
    }

    public static JSONObject send(String token, String to, String armoredBody, Integer ttl, String fingerprint) throws Exception {
        JSONObject b = new JSONObject().put("to", to).put("body", armoredBody);
        if (fingerprint != null) b.put("recipientFingerprint", fingerprint);
        if (ttl != null) b.put("messageTtlSeconds", ttl.intValue());
        return call("POST", "/api/messages", token, b);
    }

    public static JSONObject getTimer(String token, String username) throws Exception {
        return call("GET", "/api/messages/conversation/" + username + "/timer", token, null);
    }

    public static JSONObject setTimer(String token, String username, int seconds) throws Exception {
        JSONObject b = new JSONObject().put("ttlSeconds", seconds);
        return call("PUT", "/api/messages/conversation/" + username + "/timer", token, b);
    }
}
