package com.cloudbackup.app;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Supabase Auth (email/password) + Storage over plain HTTPS. Files go to <bucket>/<user-id>/<key>. */
final class SupabaseClient {
    private final Settings st;
    private final String base, anon;

    SupabaseClient(Settings s) {
        st = s;
        String u = s.url().trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        base = u;
        anon = s.anonKey().trim();
    }

    // ------------------------------------------------------------ auth
    void signIn(String email, String password) throws IOException {
        JSONObject r = postJson("/auth/v1/token?grant_type=password",
                json("email", email, "password", password));
        saveSession(r, email);
    }

    void signUp(String email, String password) throws IOException {
        JSONObject r = postJson("/auth/v1/signup", json("email", email, "password", password));
        if (!r.has("access_token")) {
            throw new IOException("Account created. Confirm the email we sent, then tap Sign in.");
        }
        saveSession(r, email);
    }

    /** Refreshes the access token when it is about to expire. */
    void ensureSession() throws IOException {
        if (st.refresh().isEmpty()) throw new IOException("Not signed in");
        if (st.expiresAt() - 60 > System.currentTimeMillis() / 1000) return;
        JSONObject r = postJson("/auth/v1/token?grant_type=refresh_token",
                json("refresh_token", st.refresh()));
        saveSession(r, st.email());
    }

    private void saveSession(JSONObject r, String email) throws IOException {
        try {
            st.p.edit()
                    .putString("access", r.getString("access_token"))
                    .putString("refresh", r.getString("refresh_token"))
                    .putLong("exp", System.currentTimeMillis() / 1000 + r.optLong("expires_in", 3600))
                    .putString("uid", r.getJSONObject("user").getString("id"))
                    .putString("email", email)
                    .apply();
        } catch (Exception e) {
            throw new IOException("Unexpected auth response");
        }
    }

    // ------------------------------------------------------------ storage
    /** Uploads length bytes to <uid>/<key>. A file that already exists counts as success. */
    void put(String key, InputStream in, long length) throws IOException {
        put(key, in, length, null);
    }

    void put(String key, InputStream in, long length, String contentType) throws IOException {
        ensureSession();
        StringBuilder path = new StringBuilder("/storage/v1/object/").append(enc(st.bucket()))
                .append('/').append(enc(st.uid()));
        for (String part : key.split("/", -1)) path.append('/').append(enc(part));

        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        try {
            c.setRequestMethod("POST");
            c.setConnectTimeout(20000);
            c.setReadTimeout(120000);
            c.setRequestProperty("apikey", anon);
            c.setRequestProperty("Authorization", "Bearer " + st.access());
            c.setRequestProperty("Content-Type", contentType == null ? "application/octet-stream" : contentType);
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(length);
            try (OutputStream out = c.getOutputStream()) {
                byte[] buf = new byte[64 * 1024];
                long left = length;
                while (left > 0) {
                    int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                    if (n < 0) throw new IOException("File shrank during upload");
                    out.write(buf, 0, n);
                    left -= n;
                }
            }
            int code = c.getResponseCode();
            if (code == 409) return; // already uploaded (Duplicate)
            if (code / 100 != 2) throw new IOException("HTTP " + code + " " + errorBody(c));
        } finally {
            c.disconnect();
        }
    }

    /** Inserts rows into a table through PostgREST; duplicates (same user_id+dedupe_key) are ignored. */
    void insertRows(String table, String jsonArray) throws IOException {
        ensureSession();
        HttpURLConnection c = (HttpURLConnection) new URL(
                base + "/rest/v1/" + table + "?on_conflict=user_id,dedupe_key").openConnection();
        try {
            c.setRequestMethod("POST");
            c.setConnectTimeout(20000);
            c.setReadTimeout(30000);
            c.setRequestProperty("apikey", anon);
            c.setRequestProperty("Authorization", "Bearer " + st.access());
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Prefer", "resolution=ignore-duplicates,return=minimal");
            c.setDoOutput(true);
            try (OutputStream o = c.getOutputStream()) { o.write(jsonArray.getBytes(StandardCharsets.UTF_8)); }
            int code = c.getResponseCode();
            if (code / 100 != 2) throw new IOException("HTTP " + code + " " + errorBody(c));
        } finally {
            c.disconnect();
        }
    }

    // ------------------------------------------------------------ helpers
    private JSONObject postJson(String path, String body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        try {
            c.setRequestMethod("POST");
            c.setConnectTimeout(20000);
            c.setReadTimeout(30000);
            c.setRequestProperty("apikey", anon);
            c.setRequestProperty("Content-Type", "application/json");
            c.setDoOutput(true);
            try (OutputStream o = c.getOutputStream()) { o.write(body.getBytes(StandardCharsets.UTF_8)); }
            int code = c.getResponseCode();
            if (code / 100 != 2) throw new IOException(errorBody(c));
            try (InputStream is = c.getInputStream()) {
                return new JSONObject(new String(is.readAllBytes(), StandardCharsets.UTF_8));
            }
        } catch (org.json.JSONException e) {
            throw new IOException("Bad response from server");
        } finally {
            c.disconnect();
        }
    }

    private static String errorBody(HttpURLConnection c) {
        try (InputStream es = c.getErrorStream()) {
            if (es == null) return "";
            String s = new String(es.readNBytes(500), StandardCharsets.UTF_8);
            try {
                JSONObject j = new JSONObject(s);
                for (String k : new String[]{"msg", "error_description", "message", "error"}) {
                    if (j.has(k)) return j.getString(k);
                }
            } catch (Exception ignored) { }
            return s.replaceAll("\\s+", " ");
        } catch (IOException e) {
            return "";
        }
    }

    private static String json(String... kv) {
        JSONObject o = new JSONObject();
        try { for (int i = 0; i < kv.length; i += 2) o.put(kv[i], kv[i + 1]); }
        catch (Exception ignored) { }
        return o.toString();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8)
                .replace("+", "%20").replace("*", "%2A").replace("%7E", "~");
    }
}
