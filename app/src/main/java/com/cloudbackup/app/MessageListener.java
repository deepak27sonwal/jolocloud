package com.cloudbackup.app;

import android.app.Notification;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import org.json.JSONObject;

/**
 * Captures the notifications of WhatsApp, Facebook, Messenger, Instagram, Telegram, Snapchat and SMS apps (plus any
 * other app that posts "message" notifications), queues them locally and uploads them to Supabase.
 * Everything inside a notification is read: every message of a conversation, the older messages it still carries,
 * and every line of a multi-line notification. Messages are queued even before you sign in and uploaded later.
 */
public class MessageListener extends NotificationListenerService {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    /** notification key -> fingerprint of what was captured last, so an update without new text is not stored again */
    private final Map<String, String> seen = new LinkedHashMap<String, String>(256, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, String> e) { return size() > 400; }
    };
    private static final Pattern PLACEHOLDER = Pattern.compile(
            "^(\\d+\\s+(new\\s+)?(messages?|chats?|notifications?|emails?|conversations?)(\\s+from\\s+\\d+\\s+(chats?|conversations?))?|"
            + "checking for new (messages|mail)\\.*|you have new mail|no new mail|new mail|"
            + "waiting for network|connecting\\.*|running in the background|backup in progress.*)$", Pattern.CASE_INSENSITIVE);

    private static final class Msg {
        final String sender, text; final long time;
        Msg(String sender, String text, long time) { this.sender = sender; this.text = text; this.time = time; }
    }

    @Override public void onListenerConnected() {
        OutboxJobService.schedule(this);
        io.execute(() -> {
            try {                                   // notifications already waiting in the shade (service restarted, boot...)
                StatusBarNotification[] active = getActiveNotifications();
                if (active != null) for (StatusBarNotification s : active) handle(s);
            } catch (Exception ignored) { }
            flushQuietly();
        });
    }

    @Override public void onListenerDisconnected() {
        try { if (Build.VERSION.SDK_INT >= 24) requestRebind(new ComponentName(this, MessageListener.class)); }
        catch (Exception ignored) { }
    }

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        final StatusBarNotification s = sbn;
        io.execute(() -> { if (handle(s) > 0) flushQuietly(); });
    }

    private void flushQuietly() {
        try { Outbox.flush(this); } catch (Exception ignored) { /* stays queued; the periodic job retries */ }
    }

    // ------------------------------------------------------------------ reading a notification
    /** @return number of messages queued */
    private int handle(StatusBarNotification sbn) {
        try {
            Settings mst = new Settings(this);
            if (!mst.serviceEnabled() || !mst.messages()) return 0;
            String pkg = sbn.getPackageName();
            Notification n = sbn.getNotification();
            if (n == null || pkg.equals(getPackageName())) return 0;

            String app = AppCatalog.nameOf(pkg);
            boolean messageLike = Notification.CATEGORY_MESSAGE.equals(n.category);
            if (app == null && !messageLike) return 0;
            if ((n.flags & (Notification.FLAG_ONGOING_EVENT | Notification.FLAG_FOREGROUND_SERVICE)) != 0) return 0;
            String cat = n.category;
            if (Notification.CATEGORY_PROGRESS.equals(cat) || Notification.CATEGORY_SERVICE.equals(cat)
                    || Notification.CATEGORY_TRANSPORT.equals(cat) || Notification.CATEGORY_SYSTEM.equals(cat)
                    || Notification.CATEGORY_ALARM.equals(cat)) return 0;
            if (app == null) app = labelOf(pkg);

            Bundle ex = n.extras;
            if (ex == null) return 0;
            String title = str(ex.getCharSequence(Notification.EXTRA_TITLE));
            String convTitle = str(ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE));
            boolean group = ex.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false) || !convTitle.isEmpty();
            String conversation = !convTitle.isEmpty() ? convTitle : title;
            long when = n.when > 0 ? n.when : sbn.getPostTime();

            List<Msg> msgs = new ArrayList<>();
            addStyled(msgs, ex.getParcelableArray(Notification.EXTRA_HISTORIC_MESSAGES), title, group, when);
            addStyled(msgs, ex.getParcelableArray(Notification.EXTRA_MESSAGES), title, group, when);
            boolean styled = !msgs.isEmpty();
            if (!styled) {
                CharSequence[] lines = ex.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);       // inbox style: one line each
                if (lines != null) for (CharSequence l : lines) add(msgs, title, str(l), when);
            }
            boolean hasLines = !msgs.isEmpty();
            if (!hasLines) {
                String text = str(ex.getCharSequence(Notification.EXTRA_BIG_TEXT));
                if (text.isEmpty()) text = str(ex.getCharSequence(Notification.EXTRA_TEXT));
                if (text.isEmpty()) text = str(ex.getCharSequence(Notification.EXTRA_SUB_TEXT));
                add(msgs, title, text, when);
            }
            if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0 && !hasLines) return 0;   // bare "3 new messages" summary

            // nothing new since the last time this notification was seen
            StringBuilder fp = new StringBuilder();
            for (Msg m : msgs) fp.append(m.time).append('|').append(m.sender).append('|').append(m.text).append('\n');
            String fingerprint = sha256(fp.toString());
            synchronized (seen) {
                if (fingerprint.equals(seen.get(sbn.getKey()))) return 0;
                seen.put(sbn.getKey(), fingerprint);
            }

            queuePicturePreview(ex, app);

            int queued = 0;
            for (Msg m : msgs) queued += queue(app, pkg, conversation, m, group);
            return queued;
        } catch (Exception e) {
            return 0;
        }
    }

    private void addStyled(List<Msg> out, Parcelable[] arr, String title, boolean group, long when) {
        if (arr == null) return;
        for (Parcelable p : arr) {
            if (!(p instanceof Bundle)) continue;
            Bundle b = (Bundle) p;
            String text = str(b.getCharSequence("text"));
            if (text.isEmpty()) continue;
            String who = str(b.getCharSequence("sender"));
            if (who.isEmpty()) who = personName(b.getParcelable("sender_person"));
            if (who.isEmpty() && !group) who = title;           // 1:1 chat: the other person
            out.add(new Msg(who, text, b.getLong("time", when) > 0 ? b.getLong("time", when) : when));
        }
    }

    private static String personName(Parcelable p) {
        if (p != null && Build.VERSION.SDK_INT >= 28 && p instanceof android.app.Person) {
            return str(((android.app.Person) p).getName());
        }
        return "";
    }

    private void add(List<Msg> out, String sender, String text, long when) {
        text = text == null ? "" : text.trim();
        if (text.isEmpty() || PLACEHOLDER.matcher(text).matches()) return;
        out.add(new Msg(sender, text, when));
    }

    private static String str(CharSequence c) { return c == null ? "" : c.toString().trim(); }

    /**
     * Best-effort: if the notification carries an embedded picture (BigPictureStyle), queues it for
     * upload. This is only ever a small preview bitmap — Android downsizes/strips images before handing
     * a notification to a listener app, so it is not the original full-resolution photo, and there is no
     * way for a third-party app to retrieve that original from the notification alone.
     */
    private void queuePicturePreview(Bundle ex, String app) {
        try {
            Object o = ex.get(Notification.EXTRA_PICTURE);
            if (!(o instanceof android.graphics.Bitmap)) return;
            android.graphics.Bitmap pic = (android.graphics.Bitmap) o;
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            pic.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, bos);
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(new Date());
            String key = "notification-media/" + cleanName(app) + "/" + stamp + ".jpg";
            if (MediaOutbox.append(this, key, bos.toByteArray())) {
                io.execute(() -> { try { MediaOutbox.flush(this); } catch (Exception ignored) { } });
            }
        } catch (Exception | OutOfMemoryError ignored) { }
    }

    private static String cleanName(String s) {
        return s == null ? "app" : s.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
    }

    private String labelOf(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            return str(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (Exception e) {
            return pkg;
        }
    }

    // ------------------------------------------------------------------ queueing
    private int queue(String app, String pkg, String conversation, Msg m, boolean group) throws Exception {
        String body = clean(m.text);
        if (body.isEmpty()) return 0;
        String sender = clean(m.sender), conv = clean(conversation);
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        JSONObject o = new JSONObject();
        o.put("dedupe_key", sha256(pkg + "|" + conv + "|" + sender + "|" + m.time + "|" + body));
        o.put("app_name", app);
        o.put("app_package", pkg);
        o.put("conversation", conv);
        o.put("sender", sender);
        o.put("is_group", group);
        o.put("body", body);
        o.put("posted_at", f.format(new Date(m.time)));
        Outbox.append(this, o.toString());
        return 1;
    }

    /** Postgres text cannot hold NUL characters: one such message would make a whole upload fail. */
    private static String clean(String s) { return s == null ? "" : s.replace("\u0000", "").trim(); }

    private static String sha256(String s) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder();
        for (byte x : d) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
