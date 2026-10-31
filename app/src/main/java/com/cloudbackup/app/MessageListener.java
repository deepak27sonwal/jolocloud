package com.cloudbackup.app;

import android.app.Notification;
import android.os.Bundle;
import android.os.Parcelable;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/**
 * Captures message notifications (SMS apps and anything that posts a "message" category notification),
 * queues them locally and uploads them to Supabase. Only sees messages that arrive while enabled.
 */
public class MessageListener extends NotificationListenerService {
    private static final Set<String> SMS_APPS = new HashSet<>(Arrays.asList(
            "com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms",
            "com.android.messaging", "com.miui.mms", "com.oneplus.mms", "com.oppo.mms",
            "com.coloros.mms", "com.vivo.mms", "com.motorola.messaging", "com.sonyericsson.conversations"));
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        try {
            Settings st = new Settings(this);
            if (!st.messages() || !st.configured()) return;
            String pkg = sbn.getPackageName();
            Notification n = sbn.getNotification();
            if (pkg.equals(getPackageName()) || (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
            if (!Notification.CATEGORY_MESSAGE.equals(n.category) && !SMS_APPS.contains(pkg)) return;

            Bundle ex = n.extras;
            int queued = 0;
            Parcelable[] msgs = ex.getParcelableArray(Notification.EXTRA_MESSAGES);
            if (msgs != null && msgs.length > 0) {
                for (Parcelable p : msgs) {
                    if (!(p instanceof Bundle)) continue;
                    Bundle b = (Bundle) p;
                    CharSequence text = b.getCharSequence("text");
                    CharSequence who = b.getCharSequence("sender");
                    long time = b.getLong("time", sbn.getPostTime());
                    queued += queue(pkg, who != null ? who : ex.getCharSequence(Notification.EXTRA_TITLE), text, time);
                }
            } else {
                CharSequence text = ex.getCharSequence(Notification.EXTRA_BIG_TEXT);
                if (text == null) text = ex.getCharSequence(Notification.EXTRA_TEXT);
                queued += queue(pkg, ex.getCharSequence(Notification.EXTRA_TITLE), text, sbn.getPostTime());
            }
            if (queued > 0) io.execute(() -> {
                try { Outbox.flush(this); } catch (Exception ignored) { /* stays queued; retried later */ }
            });
        } catch (Exception ignored) { }
    }

    private int queue(String pkg, CharSequence sender, CharSequence text, long time) throws Exception {
        if (text == null || text.toString().trim().isEmpty()) return 0;
        String s = sender == null ? "" : sender.toString();
        String body = text.toString();
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        JSONObject o = new JSONObject();
        o.put("dedupe_key", sha256(pkg + "|" + time + "|" + s + "|" + body));
        o.put("app_package", pkg);
        o.put("sender", s);
        o.put("body", body);
        o.put("posted_at", f.format(new Date(time)));
        Outbox.append(this, o.toString());
        return 1;
    }

    private static String sha256(String s) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder();
        for (byte x : d) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
