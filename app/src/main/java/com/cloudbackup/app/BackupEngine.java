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
import android.provider.MediaStore;
import android.provider.Telephony;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.Writer;
import java.security.DigestOutputStream;
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
    private static final int MAX_EDGE = 2048;   // longest side after downscale
    private static final int WEBP_QUALITY = 80;

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
            if (st.messages() && !cancelled) backupMessages();
            if (st.photos() && !cancelled) backupMedia(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "photos");
            if (st.videos() && !cancelled) backupMedia(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "videos");
        } catch (Exception e) {
            Status.log("ERROR: " + e.getMessage());
            failed++;
        } finally {
            Status.running = false;
        }
        String r = (cancelled ? "Stopped. " : "Done. ") + uploaded + " uploaded, " + failed + " failed."
                + (bytesOriginal > 0 ? String.format(Locale.US, " Sent %.1f MB instead of %.1f MB.", bytesSent / 1e6, bytesOriginal / 1e6) : "");
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
                MediaStore.MediaColumns.BUCKET_DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE};
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
                String mime = c.getString(5) == null ? "" : c.getString(5);
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
                    Status.summary = "Uploading " + folder + " " + n + "/" + total;
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
            File f = File.createTempFile("img", ".webp", ctx.getCacheDir());
            Bitmap.CompressFormat fmt = Build.VERSION.SDK_INT >= 30
                    ? Bitmap.CompressFormat.WEBP_LOSSY : Bitmap.CompressFormat.WEBP;
            try (FileOutputStream out = new FileOutputStream(f)) {
                bmp.compress(fmt, WEBP_QUALITY, out);
            }
            bmp.recycle();
            return f;
        } catch (Exception | OutOfMemoryError e) {
            return null; // fall back to uploading the original
        }
    }

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

    // ---------------------------------------------------------------- SMS
    /** Exports all SMS in the "SMS Backup & Restore" XML format; uploads only if changed. */
    private void backupMessages() {
        Status.summary = "Exporting messages";
        File f = null;
        try (Cursor c = cr.query(Telephony.Sms.CONTENT_URI, new String[]{
                Telephony.Sms.ADDRESS, Telephony.Sms.DATE, Telephony.Sms.DATE_SENT, Telephony.Sms.TYPE,
                Telephony.Sms.BODY, Telephony.Sms.READ, Telephony.Sms.PROTOCOL,
                Telephony.Sms.SERVICE_CENTER, Telephony.Sms.STATUS}, null, null, "date ASC")) {
            if (c == null) { Status.log("Cannot read messages"); failed++; return; }
            f = File.createTempFile("sms", ".xml", ctx.getCacheDir());
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            int count = 0;
            try (Writer w = new OutputStreamWriter(new DigestOutputStream(new FileOutputStream(f), md),
                    StandardCharsets.UTF_8)) {
                w.write("<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>\n<smses count=\""
                        + c.getCount() + "\">\n");
                while (c.moveToNext() && !cancelled) {
                    w.write("  <sms protocol=\"" + esc(c.getString(6)) + "\" address=\"" + esc(c.getString(0))
                            + "\" date=\"" + c.getLong(1) + "\" type=\"" + c.getInt(3)
                            + "\" subject=\"null\" body=\"" + esc(c.getString(4))
                            + "\" toa=\"null\" sc_toa=\"null\" service_center=\"" + esc(c.getString(7))
                            + "\" read=\"" + c.getInt(5) + "\" status=\"" + c.getInt(8)
                            + "\" locked=\"0\" date_sent=\"" + c.getLong(2)
                            + "\" readable_date=\"\" contact_name=\"(Unknown)\" />\n");
                    count++;
                }
                w.write("</smses>\n");
            }
            if (cancelled) return;
            String hash = hex(md.digest());
            SharedPreferences p = ctx.getSharedPreferences("state", Context.MODE_PRIVATE);
            if (hash.equals(p.getString("smsHash", ""))) {
                Status.log("Messages unchanged (" + count + ")");
                return;
            }
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
            try (InputStream in = new FileInputStream(f)) {
                s3.put("messages/sms-" + stamp + ".xml", in, f.length(), "application/xml");
            }
            p.edit().putString("smsHash", hash).apply();
            uploaded++;
            Status.log("Backed up " + count + " text messages");
        } catch (SecurityException e) {
            Status.log("No permission to read messages (see note in app info: allow restricted settings)");
            failed++;
        } catch (Exception e) {
            Status.log("Messages failed: " + e.getMessage());
            failed++;
        } finally {
            if (f != null) f.delete();
        }
    }

    private static String esc(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                case '\'': sb.append("&apos;"); break;
                case '\n': sb.append("&#10;"); break;
                case '\r': sb.append("&#13;"); break;
                case '\t': sb.append("&#9;"); break;
                default:
                    // drop control chars that are illegal in XML 1.0
                    if (ch >= 0x20 || ch == 0) { if (ch != 0) sb.append(ch); }
            }
        }
        return sb.toString();
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
