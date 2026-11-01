package com.multiweb.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.Menu;
import android.view.MenuItem;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.Toast;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** One WhatsApp Web session. Subclassed per slot; each runs in its own process. */
public class ChatActivity extends Activity {
    private static final String HOME = "https://web.whatsapp.com/";
    private static final String DESKTOP_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    private static final int REQ_FILE = 1, REQ_PERM = 2, REQ_MEDIA = 4;

    private WebView web;
    private ProgressBar bar;
    private ValueCallback<Uri[]> chooser;
    private PermissionRequest pendingPerm;
    private Intent pendingPicker;          // file picker waiting for the media permission answer
    private boolean mediaAsked = false;
    private String account;
    private int lastUnread = 0;
    private FrameLayout root;
    private android.view.View splash;
    private int accent = 0xFF00897B;
    private boolean wide = false;      // false = Auto (page adapts to the real screen width)
    private int textZoom = 100;
    private android.content.SharedPreferences ui;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        account = getIntent().getStringExtra("name");
        if (account == null) account = "WhatsApp";
        setTitle(account);
        accent = getIntent().getIntExtra("color", accent);
        if (getActionBar() != null) getActionBar().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(accent));
        getWindow().setStatusBarColor(darken(accent));
        ui = getSharedPreferences("ui_" + getClass().getSimpleName(), MODE_PRIVATE);
        wide = ui.getBoolean("desktop", false);
        textZoom = ui.getInt("zoom", 100);

        root = new FrameLayout(this);
        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        root.addView(bar, new FrameLayout.LayoutParams(-1, 8));
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE));
        splash = buildSplash();
        root.addView(splash, new FrameLayout.LayoutParams(-1, -1));
        web.postDelayed(this::hideSplash, 9000); // never leave the loading screen up forever
        setContentView(root);

        applySettings();
        if (!hasMediaPermission() && !mediaAsked) { mediaAsked = true; requestPermissions(mediaPermissions(), REQ_MEDIA); }
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebViewClient(new Client());
        web.setWebChromeClient(new Chrome());
        web.setDownloadListener((url, ua, cd, mime, len) -> download(url, cd, mime));
        if (saved != null) web.restoreState(saved); else web.loadUrl(HOME);
    }

    private void applySettings() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setUserAgentString(DESKTOP_UA);           // WhatsApp Web refuses mobile user agents
        s.setUseWideViewPort(wide);                 // compact (phone-width) layout unless toggled
        s.setLoadWithOverviewMode(wide);
        s.setTextZoom(textZoom);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
    }

    // ------------------------------------------------------------ navigation
    private class Client extends WebViewClient {
        @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
            String host = r.getUrl().getHost();
            if (host != null && (host.endsWith("whatsapp.com") || host.endsWith("whatsapp.net"))) return false;
            try { startActivity(new Intent(Intent.ACTION_VIEW, r.getUrl())); } catch (ActivityNotFoundException ignored) { }
            return true; // links to other sites open in the normal browser
        }
        @Override public void onPageStarted(WebView v, String u, Bitmap f) { bar.setVisibility(android.view.View.VISIBLE); }
        @Override public void onPageFinished(WebView v, String u) { bar.setVisibility(android.view.View.GONE); hideSplash(); injectAttachFix(); }
    }

    private class Chrome extends WebChromeClient {
        @Override public void onProgressChanged(WebView v, int p) { bar.setProgress(p); }

        @Override public void onReceivedTitle(WebView v, String title) {
            Matcher m = Pattern.compile("^\\((\\d+)\\)").matcher(title == null ? "" : title);
            int n = m.find() ? Integer.parseInt(m.group(1)) : 0;
            if (n > lastUnread) notifyUnread(n);
            lastUnread = n;
        }

        @Override public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
            if (chooser != null) chooser.onReceiveValue(null);
            chooser = cb;
            Intent i;
            try {
                i = p.createIntent();
            } catch (Exception e) {
                i = new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
            }
            if (p.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            if (!hasMediaPermission() && !mediaAsked) {      // first attach without access: ask, then open the picker
                mediaAsked = true;
                pendingPicker = i;
                requestPermissions(mediaPermissions(), REQ_MEDIA);
                return true;
            }
            return launchPicker(i);
        }

        @Override public void onPermissionRequest(PermissionRequest r) {
            runOnUiThread(() -> {
                if (!HOME.startsWith(r.getOrigin().getScheme() + "://" + r.getOrigin().getHost())) { r.deny(); return; }
                List<String> need = new ArrayList<>();
                for (String res : r.getResources()) {
                    if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(res)
                            && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
                        need.add(Manifest.permission.CAMERA);
                    if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(res)
                            && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                        need.add(Manifest.permission.RECORD_AUDIO);
                }
                if (need.isEmpty()) { r.grant(r.getResources()); return; }
                pendingPerm = r;
                requestPermissions(need.toArray(new String[0]), REQ_PERM);
            });
        }
    }

    // ------------------------------------------------------------ media access (photos, videos, audio)
    private static String[] mediaPermissions() {
        if (Build.VERSION.SDK_INT >= 34) return new String[]{Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED};
        if (Build.VERSION.SDK_INT >= 33) return new String[]{Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO};
        return new String[]{Manifest.permission.READ_EXTERNAL_STORAGE};
    }

    private boolean hasMediaPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            return checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
                    || (Build.VERSION.SDK_INT >= 34
                        && checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED);
        }
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean launchPicker(Intent i) {
        try {
            startActivityForResult(i, REQ_FILE);
            return true;
        } catch (ActivityNotFoundException e) {
            try {
                startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT)
                        .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_FILE);
                return true;
            } catch (ActivityNotFoundException e2) {
                if (chooser != null) { chooser.onReceiveValue(null); chooser = null; }
                Toast.makeText(this, "No file picker available", Toast.LENGTH_LONG).show();
                return false;
            }
        }
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (code == REQ_MEDIA) {
            // whatever the answer, the picker still opens (it also works with partial access)
            Intent i = pendingPicker;
            pendingPicker = null;
            if (i != null && chooser != null) launchPicker(i);
            return;
        }
        if (code != REQ_PERM || pendingPerm == null) return;
        boolean ok = res.length > 0;
        for (int r : res) ok &= r == PackageManager.PERMISSION_GRANTED;
        if (ok) pendingPerm.grant(pendingPerm.getResources()); else pendingPerm.deny();
        pendingPerm = null;
    }

    @Override protected void onActivityResult(int code, int result, Intent data) {
        if (code != REQ_FILE || chooser == null) return;
        Uri[] files = null;
        if (result == RESULT_OK && data != null) {
            files = WebChromeClient.FileChooserParams.parseResult(result, data);
            if (files == null && data.getData() != null) files = new Uri[]{data.getData()};
        }
        chooser.onReceiveValue(files);
        chooser = null;
    }

    // ------------------------------------------------------------ downloads (WhatsApp uses blob: URLs)
    private void download(String url, String cd, String mime) {
        if (!url.startsWith("blob:")) {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception ignored) { }
            return;
        }
        String name = URLUtil.guessFileName(url, cd, mime).replace("'", "");
        String type = mime == null ? "application/octet-stream" : mime.replace("'", "");
        web.evaluateJavascript("(function(){var x=new XMLHttpRequest();x.open('GET','" + url
                + "',true);x.responseType='blob';x.onload=function(){var r=new FileReader();"
                + "r.onloadend=function(){Android.save(r.result.split(',')[1],'" + type + "','" + name
                + "');};r.readAsDataURL(x.response);};x.send();})()", null);
    }

    private class Bridge {
        @JavascriptInterface public void save(String b64, String mime, String name) {
            try {
                byte[] data = Base64.decode(b64, Base64.DEFAULT);
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, name);
                v.put(MediaStore.Downloads.MIME_TYPE, mime);
                v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri u = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                try (OutputStream o = getContentResolver().openOutputStream(u)) { o.write(data); }
                runOnUiThread(() -> Toast.makeText(ChatActivity.this, "Saved to Downloads: " + name,
                        Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(ChatActivity.this, "Download failed", Toast.LENGTH_LONG).show());
            }
        }
    }

    // ------------------------------------------------------------ unread notification (from "(3) WhatsApp" title)
    private void notifyUnread(int n) {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 3);
            return;
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("unread", "Unread chats",
                NotificationManager.IMPORTANCE_DEFAULT));
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, getClass()).putExtra("name", account).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        nm.notify(getClass().getSimpleName().hashCode(), new Notification.Builder(this, "unread")
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(account).setContentText(n + " unread chat" + (n == 1 ? "" : "s"))
                .setContentIntent(pi).setAutoCancel(true).build());
    }

    // ------------------------------------------------------------ loading screen
    private android.view.View buildSplash() {
        float d = getResources().getDisplayMetrics().density;
        android.widget.LinearLayout l = new android.widget.LinearLayout(this);
        l.setOrientation(android.widget.LinearLayout.VERTICAL);
        l.setGravity(android.view.Gravity.CENTER);
        l.setBackgroundColor(accent);
        l.setClickable(true); // swallow touches while loading
        android.widget.TextView av = new android.widget.TextView(this);
        av.setText(account.substring(0, 1).toUpperCase());
        av.setTextColor(accent);
        av.setTextSize(40);
        av.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        av.setGravity(android.view.Gravity.CENTER);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        g.setColor(android.graphics.Color.WHITE);
        av.setBackground(g);
        l.addView(av, new android.widget.LinearLayout.LayoutParams((int) (96 * d), (int) (96 * d)));
        android.widget.TextView n = new android.widget.TextView(this);
        n.setText(account);
        n.setTextColor(android.graphics.Color.WHITE);
        n.setTextSize(22);
        n.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        n.setPadding(0, (int) (16 * d), 0, (int) (4 * d));
        l.addView(n);
        android.widget.TextView s = new android.widget.TextView(this);
        s.setText("Connecting to WhatsApp Web…");
        s.setTextColor(0xCCFFFFFF);
        s.setTextSize(14);
        l.addView(s);
        // gentle pulse so it feels alive
        av.animate().scaleX(1.08f).scaleY(1.08f).setDuration(700).withEndAction(new Runnable() {
            @Override public void run() {
                if (splash == null) return;
                float t = av.getScaleX() > 1.04f ? 1f : 1.08f;
                av.animate().scaleX(t).scaleY(t).setDuration(700).withEndAction(this).start();
            }
        }).start();
        return l;
    }

    private void hideSplash() {
        final android.view.View s = splash;
        if (s == null) return;
        splash = null;
        s.animate().alpha(0f).setDuration(300).withEndAction(() -> root.removeView(s)).start();
    }

    private static int darken(int c) {
        float[] hsv = new float[3];
        android.graphics.Color.colorToHSV(c, hsv);
        hsv[2] *= 0.75f;
        return android.graphics.Color.HSVToColor(hsv);
    }

    // ------------------------------------------------------------ attach menu placement
    private String attachScript;

    /** Keeps WhatsApp's attach menu inside the visible screen (right side, above the message bar). */
    private void injectAttachFix() {
        try {
            if (attachScript == null) {
                try (java.io.InputStream in = getAssets().open("attach.js")) {
                    attachScript = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                }
            }
            web.evaluateJavascript(attachScript, null);
        } catch (java.io.IOException ignored) { }
    }

    // ------------------------------------------------------------ menu / lifecycle
    @Override public boolean onCreateOptionsMenu(Menu m) {
        m.add(0, 1, 0, "Reload");
        m.add(0, 2, 1, wide ? "Layout: Desktop (tap for Auto)" : "Layout: Auto (tap for Desktop)");
        m.add(0, 4, 2, "Text size +");
        m.add(0, 5, 3, "Text size -");
        m.add(0, 3, 4, "Log out & clear this account");
        return true;
    }

    @Override public boolean onOptionsItemSelected(MenuItem i) {
        switch (i.getItemId()) {
            case 1: web.reload(); return true;
            case 2:
                wide = !wide;
                ui.edit().putBoolean("desktop", wide).apply();
                applySettings();
                web.reload();
                invalidateOptionsMenu();
                return true;
            case 4:
            case 5:
                textZoom = Math.max(70, Math.min(200, textZoom + (i.getItemId() == 4 ? 10 : -10)));
                ui.edit().putInt("zoom", textZoom).apply();
                web.getSettings().setTextZoom(textZoom);
                Toast.makeText(this, "Text size " + textZoom + "%", Toast.LENGTH_SHORT).show();
                return true;
            case 3:
                new AlertDialog.Builder(this).setTitle("Clear " + account + "?")
                        .setMessage("This logs the account out of this app and deletes its saved session.")
                        .setPositiveButton("Clear", (d, w) -> {
                            CookieManager.getInstance().removeAllCookies(null);
                            WebStorage.getInstance().deleteAllData();
                            web.clearCache(true);
                            web.clearHistory();
                            web.loadUrl(HOME);
                        }).setNegativeButton("Cancel", null).show();
                return true;
        }
        return false;
    }

    @Override public void onConfigurationChanged(android.content.res.Configuration cfg) {
        super.onConfigurationChanged(cfg);
        // Tell the page the window size changed so it switches between one-pane and two-pane layouts.
        web.postDelayed(() -> web.evaluateJavascript("window.dispatchEvent(new Event('resize'))", null), 250);
    }

    @Override protected void onSaveInstanceState(Bundle out) { super.onSaveInstanceState(out); web.saveState(out); }

    @Override public void onBackPressed() {
        if (web.canGoBack()) web.goBack(); else moveTaskToBack(true);
    }

    @Override protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush(); // persist the session; WebView itself is left running
    }

    @Override protected void onDestroy() {
        ((ViewGroup) web.getParent()).removeView(web);
        web.destroy();
        super.onDestroy();
    }
}
