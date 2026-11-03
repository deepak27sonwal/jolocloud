package com.cloudbackup.app;

import android.content.Context;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** Durable queue (one JSON object per line) of captured messages waiting to be uploaded. */
final class Outbox {
    private static final Object LOCK = new Object();
    private static final AtomicBoolean FLUSHING = new AtomicBoolean(false);
    private static final int BATCH = 200;

    private static File file(Context c) { return new File(c.getFilesDir(), "outbox.jsonl"); }

    static void append(Context c, String jsonLine) {
        synchronized (LOCK) {
            try (PrintWriter w = new PrintWriter(new FileWriter(file(c), true))) { w.println(jsonLine); }
            catch (IOException ignored) { }
        }
    }

    private static List<String> read(Context c, int max) throws IOException {
        synchronized (LOCK) {
            File f = file(c);
            if (!f.exists()) return new ArrayList<>();
            List<String> all = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
            return new ArrayList<>(all.subList(0, Math.min(max, all.size())));
        }
    }

    private static void dropFirst(Context c, int n) throws IOException {
        synchronized (LOCK) {
            File f = file(c);
            List<String> all = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
            List<String> rest = all.subList(Math.min(n, all.size()), all.size());
            Files.write(f.toPath(), rest, StandardCharsets.UTF_8);
        }
    }

    /** Number of captured messages still waiting to be uploaded. */
    static int pending(Context c) {
        synchronized (LOCK) {
            File f = file(c);
            if (!f.exists()) return 0;
            try { return Files.readAllLines(f.toPath(), StandardCharsets.UTF_8).size(); } catch (IOException e) { return 0; }
        }
    }

    private static boolean clientError(IOException e) {      // the server rejected the DATA (not the login / network)
        String m = String.valueOf(e.getMessage());
        return m.startsWith("HTTP 400") || m.startsWith("HTTP 409") || m.startsWith("HTTP 413") || m.startsWith("HTTP 422");
    }

    private static List<String> withoutNewColumns(List<String> rows) {
        List<String> out = new ArrayList<>();
        for (String r : rows) {
            try {
                JSONObject j = new JSONObject(r);
                j.remove("app_name"); j.remove("conversation"); j.remove("is_group");
                out.add(j.toString());
            } catch (Exception e) { out.add(r); }
        }
        return out;
    }

    private static String arr(List<String> rows) { return "[" + String.join(",", rows) + "]"; }

    /**
     * Sends one batch. If the server rejects the data, the rows are sent one by one so a single bad message can never
     * block the others; a row that is rejected on its own is moved to outbox-rejected.jsonl.
     */
    private static void sendBatch(Context c, SupabaseClient client, List<String> batch) throws IOException {
        try {
            client.insertRows("notification_messages", arr(batch));
            return;
        } catch (IOException e) {
            if (!clientError(e)) throw e;
        }
        for (String row : batch) {
            List<String> one = new ArrayList<>();
            one.add(row);
            try {
                try { client.insertRows("notification_messages", arr(one)); }
                catch (IOException e) {
                    if (!clientError(e)) throw e;
                    // maybe the table does not have the newer columns yet: try without them
                    client.insertRows("notification_messages", arr(withoutNewColumns(one)));
                }
            } catch (IOException e) {
                if (!clientError(e)) throw e;
                try (PrintWriter w = new PrintWriter(new FileWriter(new File(c.getFilesDir(), "outbox-rejected.jsonl"), true))) {
                    w.println(row);
                } catch (IOException ignored) { }
            }
        }
    }

    /** Uploads everything queued. Returns number of messages sent. Safe to call from any thread. */
    static int flush(Context c) throws IOException {
        if (!FLUSHING.compareAndSet(false, true)) return 0;
        try {
            Settings st = new Settings(c);
            if (!st.configured()) return 0;                     // not signed in yet: messages stay queued
            SupabaseClient client = new SupabaseClient(st);
            int sent = 0;
            List<String> batch;
            while (!(batch = read(c, BATCH)).isEmpty()) {
                sendBatch(c, client, batch);
                dropFirst(c, batch.size());
                sent += batch.size();
            }
            return sent;
        } finally {
            FLUSHING.set(false);
        }
    }
}
