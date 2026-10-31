package com.cloudbackup.app;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.ContactsContract;
import android.provider.MediaStore;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Scanner;
import java.util.Set;

/** Uploads new photos, videos and a contacts vCard export. Each file is uploaded once. */
final class BackupEngine {
    private final Context ctx;
    private final Settings st;
    private final SupabaseClient s3;
    private final ContentResolver cr;
    private final File doneFile;
    private final Set<String> done = new HashSet<>();
    private volatile boolean cancelled;
    private int uploaded, failed;

    BackupEngine(Context c) {
        ctx = c.getApplicationContext();
        st = new Settings(ctx);
        s3 = new SupabaseClient(st);
        cr = ctx.getContentResolver();
        doneFile = new File(ctx.getFilesDir(), "uploaded.txt");
    }

    void cancel() { cancelled = true; }

    /** @return true if everything requested finished without errors. */
    boolean run() {
        Status.running = true;
        try {
            loadDone();
            Status.log("Signing in…");
            s3.ensureSession();
            if (st.contacts() && !cancelled) backupContacts();
            if (st.photos() && !cancelled) backupMedia(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "photos");
            if (st.videos() && !cancelled) backupMedia(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "videos");
        } catch (Exception e) {
            Status.log("ERROR: " + e.getMessage());
            failed++;
        } finally {
            Status.running = false;
        }
        String r = (cancelled ? "Stopped. " : "Done. ") + uploaded + " uploaded, " + failed + " failed.";
        Status.summary = r;
        Status.log(r);
        return failed == 0 && !cancelled;
    }

    private void loadDone() throws IOException {
        done.clear();
        if (!doneFile.exists()) return;
        try (Scanner sc = new Scanner(doneFile, "UTF-8")) {
            while (sc.hasNextLine()) done.add(sc.nextLine());
        }
    }

    private void markDone(String id) {
        done.add(id);
        try (PrintWriter w = new PrintWriter(new FileWriter(doneFile, true))) {
            w.println(id);
        } catch (IOException ignored) { }
    }

    // ---------------------------------------------------------------- media
    private void backupMedia(Uri collection, String folder) {
        String[] cols = {MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.BUCKET_DISPLAY_NAME};
        int total = 0, n = 0;
        try (Cursor c = cr.query(collection, cols, null, null, MediaStore.MediaColumns.DATE_ADDED + " ASC")) {
            if (c == null) { Status.log("Cannot read " + folder + " (permission?)"); return; }
            total = c.getCount();
            Status.log("Scanning " + total + " " + folder + "…");
            while (c.moveToNext() && !cancelled) {
                long id = c.getLong(0);
                String name = c.getString(1) == null ? "file" : c.getString(1);
                long size = c.getLong(2), mod = c.getLong(3);
                String album = c.getString(4) == null ? "Other" : c.getString(4);
                String ident = folder + ":" + id + ":" + size + ":" + mod;
                n++;
                if (done.contains(ident)) continue;
                String key = folder + "/" + clean(album) + "/" + id + "_" + clean(name);
                Uri uri = ContentUris.withAppendedId(collection, id);
                try (ParcelFileDescriptor pfd = cr.openFileDescriptor(uri, "r");
                     InputStream in = new FileInputStream(pfd.getFileDescriptor())) {
                    Status.summary = "Uploading " + folder + " " + n + "/" + total;
                    s3.put(key, in, pfd.getStatSize());
                    markDone(ident);
                    uploaded++;
                } catch (Exception e) {
                    failed++;
                    Status.log("Failed " + name + ": " + e.getMessage());
                }
            }
        } catch (SecurityException e) {
            Status.log("No permission to read " + folder);
            failed++;
        }
    }

    private static String clean(String s) {
        return s.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
    }

    // ---------------------------------------------------------------- contacts
    private void backupContacts() {
        Status.summary = "Exporting contacts";
        try (Cursor c = cr.query(ContactsContract.Contacts.CONTENT_URI,
                new String[]{ContactsContract.Contacts.LOOKUP_KEY}, null, null, null)) {
            if (c == null) { Status.log("Cannot read contacts"); failed++; return; }
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            int count = 0;
            while (c.moveToNext() && !cancelled) {
                Uri u = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_VCARD_URI, c.getString(0));
                try (InputStream in = cr.openInputStream(u)) {
                    if (in != null) { in.transferTo(all); count++; }
                } catch (Exception e) {
                    Status.log("Skipped one contact: " + e.getMessage());
                }
            }
            byte[] data = all.toByteArray();
            String hash = hex(MessageDigest.getInstance("SHA-256").digest(data));
            SharedPreferences p = ctx.getSharedPreferences("state", Context.MODE_PRIVATE);
            if (hash.equals(p.getString("contactsHash", ""))) {
                Status.log("Contacts unchanged (" + count + ")");
                return;
            }
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
            s3.put("contacts/contacts-" + stamp + ".vcf", new ByteArrayInputStream(data), data.length);
            p.edit().putString("contactsHash", hash).apply();
            uploaded++;
            Status.log("Backed up " + count + " contacts");
        } catch (SecurityException e) {
            Status.log("No permission to read contacts");
            failed++;
        } catch (Exception e) {
            Status.log("Contacts failed: " + e.getMessage());
            failed++;
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
