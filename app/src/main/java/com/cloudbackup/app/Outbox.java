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

    /** Uploads everything queued. Returns number of messages sent. Safe to call from any thread. */
    static int flush(Context c) throws IOException {
        if (!FLUSHING.compareAndSet(false, true)) return 0;
        try {
            Settings st = new Settings(c);
            if (!st.configured()) return 0;
            SupabaseClient client = new SupabaseClient(st);
            int sent = 0;
            List<String> batch;
            while (!(batch = read(c, BATCH)).isEmpty()) {
                client.insertRows("notification_messages", "[" + String.join(",", batch) + "]");
                dropFirst(c, batch.size());
                sent += batch.size();
            }
            return sent;
        } finally {
            FLUSHING.set(false);
        }
    }
}
