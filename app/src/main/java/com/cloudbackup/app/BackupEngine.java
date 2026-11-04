package com.cloudbackup.app;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.os.Build;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.ContactsContract;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
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
    private long bytesOriginal, bytesSent;
    private static final int MAX_EDGE = 4096;   // longest side after downscale — above virtually every phone camera's output, so real photos are never downscaled
    private static final int WEBP_QUALITY = 92;  // visually indistinguishable from the original at this setting, per WebP's own quality guidance

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
            if (st.messages() && !cancelled) {
                try {
                    int sent = Outbox.flush(ctx);
                    if (sent > 0) Status.log("Uploaded " + sent + " captured messages");
                } catch (Exception ex) {
                    Status.log("Messages not uploaded yet: " + ex.getMessage());
                }
            }
            if (st.photos() && !cancelled) backupMedia(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "photos");
            if (st.videos() && !cancelled) backupMedia(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "videos");
            if (st.filesEnabled() && !cancelled) backupFilesAndFolders();
        } catch (Exception e) {
            Status.log("ERROR: " + e.getMessage());
            failed++;
        } finally {
            Status.running = false;
        }
        st.addUploaded(uploaded);
        st.setLastBackupAt(System.currentTimeMillis());
        String r = (cancelled ? "Stopped. " : "Done. ") + uploaded + " uploaded, " + failed + " failed."
                + (bytesOriginal > 0 ? String.format(Locale.US, " Sent %.1f MB instead of %.1f MB.", bytesSent / 1e6, bytesOriginal / 1e6) : "");
        Status.summary = r;
        Status.log(r);
        return failed == 0 && !cancelled;
    }

    /**
     * Runs from MediaWatchJobService whenever MediaStore reports a change: uploads only files that
     * belong to a chat app's own album (WhatsApp, Telegram, or anything saved from Instagram/Facebook/
     * Messenger) — never the rest of the camera roll — so new sent/received media gets backed up within
     * roughly a minute, without waiting for the nightly schedule or a full "Back up now" sweep.
     */
    boolean backupNewChatMedia() {
        boolean ok = true;
        try {
            loadDone();
            s3.ensureSession();
            backupMedia(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "photos", true);
            backupMedia(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "videos", true);
        } catch (Exception e) {
            Status.log("Chat media watch: " + e.getMessage());
            ok = false;
        }
        if (uploaded > 0) {
            st.addUploaded(uploaded);
            st.setLastBackupAt(System.currentTimeMillis());
            String msg = "Auto-backed up " + uploaded + " new chat photo" + (uploaded == 1 ? "" : "s") + "/video.";
            Status.summary = msg;
            Status.log(msg);
        }
        return ok && failed == 0;
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
    private void backupMedia(Uri collection, String folder) { backupMedia(collection, folder, false); }

    /**
     * @param chatOnly if true, skips everything except files MediaStore has filed under a chat app's own
     *                 album (WhatsApp, Telegram, or anything saved from Instagram/Facebook/Messenger) —
     *                 used by the prompt "new chat media" watcher so it never sweeps the whole camera roll.
     */
    private void backupMedia(Uri collection, String folder, boolean chatOnly) {
        String[] cols = {MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.BUCKET_DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE};
        int total = 0, n = 0;
        try (Cursor c = cr.query(collection, cols, null, null, MediaStore.MediaColumns.DATE_ADDED + " ASC")) {
            if (c == null) { if (!chatOnly) Status.log("Cannot read " + folder + " (permission?)"); return; }
            total = c.getCount();
            if (!chatOnly) Status.log("Scanning " + total + " " + folder + "…");
            while (c.moveToNext() && !cancelled) {
                long id = c.getLong(0);
                String name = c.getString(1) == null ? "file" : c.getString(1);
                long size = c.getLong(2), mod = c.getLong(3);
                String album = c.getString(4) == null ? "Other" : c.getString(4);
                String mime = c.getString(5) == null ? "" : c.getString(5);
                if (chatOnly && !ChatMediaBuckets.matches(album)) continue;
                String ident = folder + ":" + id + ":" + size + ":" + mod;
                n++;
                if (done.contains(ident)) continue;
                String key = folder + "/" + clean(album) + "/" + id + "_" + clean(name);
                Uri uri = ContentUris.withAppendedId(collection, id);
                boolean squeeze = folder.equals("photos") && st.compress()
                        && (mime.equals("image/jpeg") || mime.equals("image/png") || mime.equals("image/heic")
                            || mime.equals("image/heif"));
                File tmp = null;
                try {
                    Status.summary = chatOnly ? ("Uploading new " + folder + ": " + name) : ("Uploading " + folder + " " + n + "/" + total);
                    if (squeeze) tmp = compress(uri);
                    if (tmp != null && tmp.length() < size) {
                        int dot = key.lastIndexOf('.');
                        String wkey = (dot > key.lastIndexOf('/') ? key.substring(0, dot) : key) + ".webp";
                        try (InputStream in = new FileInputStream(tmp)) {
                            s3.put(wkey, in, tmp.length(), "image/webp");
                        }
                        bytesSent += tmp.length();
                    } else {
                        try (ParcelFileDescriptor pfd = cr.openFileDescriptor(uri, "r");
                             InputStream in = new FileInputStream(pfd.getFileDescriptor())) {
                            s3.put(key, in, pfd.getStatSize());
                            bytesSent += pfd.getStatSize();
                        }
                    }
                    bytesOriginal += size;
                    markDone(ident);
                    uploaded++;
                } catch (Exception e) {
                    failed++;
                    Status.log("Failed " + name + ": " + e.getMessage());
                } finally {
                    if (tmp != null) tmp.delete();
                }
            }
        } catch (SecurityException e) {
            Status.log("No permission to read " + folder);
            failed++;
        }
    }

    /** Decodes, downsizes (longest side MAX_EDGE), fixes rotation and writes WebP. Null if not possible. */
    private File compress(Uri uri) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = cr.openInputStream(uri)) { BitmapFactory.decodeStream(in, null, o); }
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= MAX_EDGE) sample *= 2;
            o.inJustDecodeBounds = false;
            o.inSampleSize = sample;
            Bitmap bmp;
            try (InputStream in = cr.openInputStream(uri)) { bmp = BitmapFactory.decodeStream(in, null, o); }
            if (bmp == null) return null;

            int w = bmp.getWidth(), h = bmp.getHeight();
            float scale = Math.min(1f, (float) MAX_EDGE / Math.max(w, h));
            Matrix m = new Matrix();
            if (scale < 1f) m.postScale(scale, scale);
            int rot = rotation(uri);
            if (rot != 0) m.postRotate(rot);
            if (!m.isIdentity()) {
                Bitmap t = Bitmap.createBitmap(bmp, 0, 0, w, h, m, true);
                if (t != bmp) { bmp.recycle(); bmp = t; }
            }
            boolean lossless = st.losslessWebp();
            if (lossless && Build.VERSION.SDK_INT < 30) { bmp.recycle(); return null; } // WEBP_LOSSLESS needs API 30: upload the original instead of pretending to
            Bitmap.CompressFormat fmt;
            int quality;
            if (lossless) {
                fmt = Bitmap.CompressFormat.WEBP_LOSSLESS;
                quality = 100; // ignored by the lossless encoder, but a value is required
            } else if (Build.VERSION.SDK_INT >= 30) {
                fmt = Bitmap.CompressFormat.WEBP_LOSSY;
                quality = WEBP_QUALITY;
            } else {
                fmt = Bitmap.CompressFormat.WEBP; // deprecated alias, same as WEBP_LOSSY pre-API 30
                quality = WEBP_QUALITY;
            }
            File f = File.createTempFile("img", ".webp", ctx.getCacheDir());
            try (FileOutputStream out = new FileOutputStream(f)) {
                bmp.compress(fmt, quality, out);
            }
            bmp.recycle();
            copyExif(uri, f); // Bitmap.compress drops all metadata (capture time, GPS, camera model); put back what we can
            return f;
        } catch (Exception | OutOfMemoryError e) {
            return null; // fall back to uploading the original
        }
    }

    /** WebP can carry EXIF too, but Bitmap.compress() never writes any — copy the original's tags across by hand. */
    private void copyExif(Uri original, File webp) {
        try {
            ExifInterface src;
            try (InputStream in = cr.openInputStream(original)) {
                if (in == null) return;
                src = new ExifInterface(in);
            }
            ExifInterface dst = new ExifInterface(webp.getPath());
            boolean any = false;
            for (String tag : EXIF_TAGS) {
                String v = src.getAttribute(tag);
                if (v != null) { dst.setAttribute(tag, v); any = true; }
            }
            // rotation was already baked into the pixels above, so the copy must not also carry an orientation tag
            dst.setAttribute(ExifInterface.TAG_ORIENTATION, String.valueOf(ExifInterface.ORIENTATION_NORMAL));
            if (any) dst.saveAttributes();
        } catch (Exception ignored) { } // metadata is a bonus; a failure here must never fail the whole upload
    }

    private static final String[] EXIF_TAGS = {
            ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF, ExifInterface.TAG_GPS_TIMESTAMP,
            ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_F_NUMBER,
            ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_ISO_SPEED_RATINGS, ExifInterface.TAG_FOCAL_LENGTH,
    };

    private int rotation(Uri uri) {
        try (InputStream in = cr.openInputStream(uri)) {
            int o = new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL);
            switch (o) {
                case ExifInterface.ORIENTATION_ROTATE_90: return 90;
                case ExifInterface.ORIENTATION_ROTATE_180: return 180;
                case ExifInterface.ORIENTATION_ROTATE_270: return 270;
                default: return 0;
            }
        } catch (Exception e) {
            return 0;
        }
    }

    private static String clean(String s) {
        return s.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
    }

    // ---------------------------------------------------------------- chosen files & folders
    private void backupFilesAndFolders() {
        for (String s : new HashSet<>(st.pickedTrees())) {
            if (cancelled) return;
            Uri tree = Uri.parse(s);
            try {
                String rootDocId = DocumentsContract.getTreeDocumentId(tree);
                String rootName = clean(docDisplayName(tree, rootDocId, "Folder"));
                Status.log("Scanning folder " + rootName + "…");
                walkTree(tree, rootDocId, "files/" + rootName);
            } catch (SecurityException e) {
                failed++;
                Status.log("Lost access to a folder — add it again in the app");
            } catch (Exception e) {
                failed++;
                Status.log("Folder failed: " + e.getMessage());
            }
        }
        for (String s : new HashSet<>(st.pickedFiles())) {
            if (cancelled) return;
            try {
                backupSingleFile(Uri.parse(s));
            } catch (Exception e) {
                failed++;
                Status.log("File failed: " + e.getMessage());
            }
        }
    }

    private void walkTree(Uri treeUri, String parentDocId, String keyPrefix) {
        if (cancelled) return;
        Uri childrenUri;
        try {
            childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId);
        } catch (Exception e) {
            return;
        }
        try (Cursor c = cr.query(childrenUri, new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED}, null, null, null)) {
            if (c == null) { Status.log("Could not list a folder (permission?)"); return; }
            while (c.moveToNext() && !cancelled) {
                String docId = c.getString(0);
                String name = c.getString(1) == null ? "unnamed" : c.getString(1);
                String mime = c.getString(2);
                long size = c.isNull(3) ? 0 : c.getLong(3);
                long mod = c.isNull(4) ? 0 : c.getLong(4);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    walkTree(treeUri, docId, keyPrefix + "/" + clean(name));
                    continue;
                }
                String ident = "tree:" + treeUri + ":" + docId + ":" + size + ":" + mod;
                if (done.contains(ident)) continue;
                Uri fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
                String key = keyPrefix + "/" + clean(name);
                try (ParcelFileDescriptor pfd = cr.openFileDescriptor(fileUri, "r");
                     InputStream in = new FileInputStream(pfd.getFileDescriptor())) {
                    Status.summary = "Uploading " + name;
                    s3.put(key, in, pfd.getStatSize(), mime == null ? "application/octet-stream" : mime);
                    bytesSent += pfd.getStatSize();
                    bytesOriginal += pfd.getStatSize();
                    markDone(ident);
                    uploaded++;
                } catch (Exception e) {
                    failed++;
                    Status.log("Failed " + name + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            Status.log("Could not list a folder: " + e.getMessage());
        }
    }

    private void backupSingleFile(Uri uri) {
        String name = "file";
        long size = 0, mod = 0;
        try (Cursor c = cr.query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int si = c.getColumnIndex(OpenableColumns.SIZE);
                int li = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED);
                if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni);
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si);
                if (li >= 0 && !c.isNull(li)) mod = c.getLong(li);
            }
        } catch (Exception ignored) { }
        String ident = "file:" + uri + ":" + size + ":" + mod;
        if (done.contains(ident)) return;
        String mime = cr.getType(uri);
        String key = "files/" + shortId(uri.toString()) + "_" + clean(name);
        try (ParcelFileDescriptor pfd = cr.openFileDescriptor(uri, "r");
             InputStream in = new FileInputStream(pfd.getFileDescriptor())) {
            Status.summary = "Uploading " + name;
            s3.put(key, in, pfd.getStatSize(), mime == null ? "application/octet-stream" : mime);
            bytesSent += pfd.getStatSize();
            bytesOriginal += pfd.getStatSize();
            markDone(ident);
            uploaded++;
        } catch (SecurityException e) {
            failed++;
            Status.log("Lost access to " + name + " — add it again in the app");
        } catch (Exception e) {
            failed++;
            Status.log("Failed " + name + ": " + e.getMessage());
        }
    }

    private String docDisplayName(Uri treeUri, String docId, String fallback) {
        try (Cursor c = cr.query(DocumentsContract.buildDocumentUriUsingTree(treeUri, docId),
                new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0) && !c.getString(0).isEmpty()) return c.getString(0);
        } catch (Exception ignored) { }
        return fallback;
    }

    /** Short, stable id derived from a content URI, used so same-named files from different folders don't collide. */
    private static String shortId(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return hex(d).substring(0, 8);
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
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
