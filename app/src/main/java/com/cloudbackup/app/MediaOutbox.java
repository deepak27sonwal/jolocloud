package com.cloudbackup.app;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Durable queue of binary files (notification-embedded picture previews) waiting to be uploaded to
 * Supabase Storage, parallel to {@link Outbox} which queues text rows for the database instead.
 */
final class MediaOutbox {
    private static final AtomicBoolean FLUSHING = new AtomicBoolean(false);

    private static File dir(Context c) {
        File d = new File(c.getFilesDir(), "pending-media");
        d.mkdirs();
        return d;
    }

    /** Saves bytes locally along with the storage key they should upload under. */
    static boolean append(Context c, String key, byte[] data) {
        try {
            File f = new File(dir(c), UUID.randomUUID() + ".bin");
            try (FileOutputStream out = new FileOutputStream(f)) { out.write(data); }
            try (FileWriter w = new FileWriter(f.getPath() + ".key")) { w.write(key); }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    static int pending(Context c) {
        File[] fs = dir(c).listFiles((d, n) -> n.endsWith(".bin"));
        return fs == null ? 0 : fs.length;
    }

    /** Uploads everything queued. Returns the number sent. Safe to call from any thread. */
    static int flush(Context c) {
        if (!FLUSHING.compareAndSet(false, true)) return 0;
        int sent = 0;
        try {
            Settings st = new Settings(c);
            if (!st.configured()) return 0; // not signed in yet: stays queued
            SupabaseClient client = new SupabaseClient(st);
            File[] fs = dir(c).listFiles((d, n) -> n.endsWith(".bin"));
            if (fs == null) return 0;
            for (File f : fs) {
                File meta = new File(f.getPath() + ".key");
                if (!meta.exists()) { f.delete(); continue; }
                String key;
                try {
                    key = new String(Files.readAllBytes(meta.toPath()), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    f.delete();
                    meta.delete();
                    continue;
                }
                try (InputStream in = new FileInputStream(f)) {
                    client.put(key, in, f.length(), "image/jpeg");
                    f.delete();
                    meta.delete();
                    sent++;
                } catch (Exception e) {
                    // left in place; a later flush (next notification, or the periodic job) retries it
                }
            }
        } finally {
            FLUSHING.set(false);
        }
        return sent;
    }
}
