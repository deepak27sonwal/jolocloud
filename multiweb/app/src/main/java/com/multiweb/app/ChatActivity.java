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
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.Menu;
import android.view.View;
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
    private static final int REQ_FILE = 1, REQ_PERM = 2;

    private WebView web;
    private ProgressBar bar;
    private ValueCallback<Uri[]> chooser;
    private PermissionRequest pendingPerm;
    private String account;
    private int lastUnread = 0;
    private FrameLayout root;
    private android.view.View splash;
    private int accent = 0xFF00897B;
    private int mode = 0;               // 0 = Phone (one pane at a time), 1 = Desktop (zoomed out), 2 = Original
    private String mwScript;
    private android.widget.LinearLayout navBar;
    private final android.view.View[] navPill = new android.view.View[4];
    private final android.widget.ImageView[] navIcon = new android.widget.ImageView[4];
    private final android.widget.TextView[] navLabel = new android.widget.TextView[4];
    private android.widget.TextView chatsBadge;
    private int activeTab = 0;
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
        mode = ui.getInt("mode", 0);
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
        android.widget.LinearLayout outer = new android.widget.LinearLayout(this);
        outer.setOrientation(android.widget.LinearLayout.VERTICAL);
        outer.addView(root, new android.widget.LinearLayout.LayoutParams(-1, 0, 1));
        navBar = buildNav();
        navBar.setVisibility(android.view.View.GONE);   // shown by the page script once the chat list is up
        outer.addView(navBar, new android.widget.LinearLayout.LayoutParams(-1, -2));
        setContentView(outer);

        applySettings();
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
        s.setUseWideViewPort(mode == 1);                 // compact (phone-width) layout unless toggled
        s.setLoadWithOverviewMode(mode == 1);
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
        @Override public void onPageFinished(WebView v, String u) { bar.setVisibility(android.view.View.GONE); hideSplash(); injectPhoneLayout(); }
    }

    private class Chrome extends WebChromeClient {
        @Override public void onProgressChanged(WebView v, int p) { bar.setProgress(p); }

        @Override public void onReceivedTitle(WebView v, String title) {
            Matcher m = Pattern.compile("^\\((\\d+)\\)").matcher(title == null ? "" : title);
            int n = m.find() ? Integer.parseInt(m.group(1)) : 0;
            setBadge(n);
            if (n > lastUnread) notifyUnread(n);
            lastUnread = n;
        }

        @Override public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
            if (chooser != null) chooser.onReceiveValue(null);
            chooser = cb;
            try { startActivityForResult(p.createIntent(), REQ_FILE); }
            catch (ActivityNotFoundException e) { chooser = null; return false; }
            return true;
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

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (code != REQ_PERM || pendingPerm == null) return;
        boolean ok = res.length > 0;
        for (int r : res) ok &= r == PackageManager.PERMISSION_GRANTED;
        if (ok) pendingPerm.grant(pendingPerm.getResources()); else pendingPerm.deny();
        pendingPerm = null;
    }

    @Override protected void onActivityResult(int code, int result, Intent data) {
        if (code == REQ_FILE && chooser != null) {
            chooser.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result, data));
            chooser = null;
        }
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
        @JavascriptInterface public void view(String v) {
            runOnUiThread(() -> {
                navBar.setVisibility(mode == 0 && "list".equals(v) ? android.view.View.VISIBLE : android.view.View.GONE);
                // inside a conversation WhatsApp's own header is used, like the phone app: hide ours
                if (getActionBar() != null) {
                    if (mode == 0 && "chat".equals(v)) getActionBar().hide(); else getActionBar().show();
                }
            });
        }

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


    // ------------------------------------------------------------ WhatsApp-style bottom bar
    private static final String[] TABS = {"chats", "updates", "communities", "calls"};
    private static final String[] TAB_LABELS = {"Chats", "Updates", "Communities", "Calls"};
    private static final int[] TAB_ICONS = {R.drawable.ic_chat, R.drawable.ic_updates, R.drawable.ic_people, R.drawable.ic_call};

    private android.widget.LinearLayout buildNav() {
        float d = getResources().getDisplayMetrics().density;
        android.widget.LinearLayout bar = new android.widget.LinearLayout(this);
        bar.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(getColor(R.color.surface));
        bar.setElevation(8 * d);
        bar.setPadding(0, (int) (8 * d), 0, (int) (8 * d));
        for (int i = 0; i < TABS.length; i++) {
            final int idx = i;
            android.widget.LinearLayout item = new android.widget.LinearLayout(this);
            item.setOrientation(android.widget.LinearLayout.VERTICAL);
            item.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
            android.widget.FrameLayout top = new android.widget.FrameLayout(this);
            View pill = new View(this);
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
            g.setCornerRadius(16 * d);
            g.setColor(getColor(R.color.pill));
            pill.setBackground(g);
            top.addView(pill, new android.widget.FrameLayout.LayoutParams((int) (56 * d), (int) (32 * d),
                    android.view.Gravity.CENTER));
            android.widget.ImageView icon = new android.widget.ImageView(this);
            icon.setImageResource(TAB_ICONS[i]);
            top.addView(icon, new android.widget.FrameLayout.LayoutParams((int) (24 * d), (int) (24 * d),
                    android.view.Gravity.CENTER));
            if (i == 0) {
                chatsBadge = new android.widget.TextView(this);
                chatsBadge.setTextColor(android.graphics.Color.WHITE);
                chatsBadge.setTextSize(10);
                chatsBadge.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                chatsBadge.setGravity(android.view.Gravity.CENTER);
                chatsBadge.setPadding((int) (5 * d), 0, (int) (5 * d), 0);
                android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
                bg.setCornerRadius(10 * d);
                bg.setColor(getColor(R.color.badge));
                chatsBadge.setBackground(bg);
                chatsBadge.setVisibility(android.view.View.GONE);
                android.widget.FrameLayout.LayoutParams bp = new android.widget.FrameLayout.LayoutParams(-2,
                        (int) (18 * d), android.view.Gravity.CENTER);
                bp.setMargins((int) (26 * d), (int) (-22 * d), 0, 0);
                top.addView(chatsBadge, bp);
            }
            item.addView(top, new android.widget.LinearLayout.LayoutParams(-1, (int) (32 * d)));
            android.widget.TextView label = new android.widget.TextView(this);
            label.setText(TAB_LABELS[i]);
            label.setTextSize(12);
            label.setPadding(0, (int) (4 * d), 0, 0);
            item.addView(label);
            navPill[i] = pill; navIcon[i] = icon; navLabel[i] = label;
            item.setOnClickListener(v -> {
                activeTab = idx;
                paintNav();
                web.evaluateJavascript("window.__mw&&window.__mw.nav('" + TABS[idx] + "')", null);
            });
            bar.addView(item, new android.widget.LinearLayout.LayoutParams(0, -2, 1));
        }
        paintNav();
        return bar;
    }

    private void paintNav() {
        for (int i = 0; i < TABS.length; i++) {
            boolean on = i == activeTab;
            navPill[i].setVisibility(on ? android.view.View.VISIBLE : android.view.View.INVISIBLE);
            int col = getColor(on ? R.color.text : R.color.subtext);
            navIcon[i].setColorFilter(col);
            navLabel[i].setTextColor(col);
            navLabel[i].setTypeface(on ? android.graphics.Typeface.DEFAULT_BOLD : android.graphics.Typeface.DEFAULT);
        }
    }

    private void setBadge(int n) {
        if (chatsBadge == null) return;
        chatsBadge.setText(n > 99 ? "99+" : String.valueOf(n));
        chatsBadge.setVisibility(n > 0 ? android.view.View.VISIBLE : android.view.View.GONE);
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

    // ------------------------------------------------------------ menu / lifecycle
    @Override public boolean onCreateOptionsMenu(Menu m) {
        m.add(0, 1, 0, "Reload");
        m.add(0, 2, 1, "Layout: " + new String[]{"Phone", "Desktop (zoomed out)", "Original"}[mode] + " (tap to change)");
        m.add(0, 6, 1, "Show chat list");
        m.add(0, 8, 4, "WhatsApp settings");
        m.add(0, 7, 5, "Layout diagnostics");
        m.add(0, 4, 2, "Text size +");
        m.add(0, 5, 3, "Text size -");
        m.add(0, 3, 4, "Log out & clear this account");
        return true;
    }

    @Override public boolean onOptionsItemSelected(MenuItem i) {
        switch (i.getItemId()) {
            case 1: web.reload(); return true;
            case 2:
                mode = (mode + 1) % 3;
                ui.edit().putInt("mode", mode).apply();
                Toast.makeText(this, new String[]{"Phone layout", "Desktop layout", "Original layout"}[mode],
                        Toast.LENGTH_SHORT).show();
                applySettings();
                web.reload();
                invalidateOptionsMenu();
                return true;
            case 8:
                web.evaluateJavascript("window.__mw&&window.__mw.nav('settings')", null);
                return true;
            case 7:
                web.evaluateJavascript("window.__mw?window.__mw.debug():'Phone-mode script is not running on this page'", r -> {
                    String t;
                    try { t = String.valueOf(new org.json.JSONTokener(r).nextValue()); } catch (Exception e) { t = r; }
                    android.widget.TextView tv = new android.widget.TextView(this);
                    tv.setText(t);
                    tv.setTypeface(android.graphics.Typeface.MONOSPACE);
                    tv.setTextSize(12);
                    tv.setTextIsSelectable(true);
                    tv.setPadding(40, 30, 40, 10);
                    new AlertDialog.Builder(this).setTitle("Layout diagnostics").setView(tv)
                            .setPositiveButton("Close", null).show();
                });
                return true;
            case 6:
                web.evaluateJavascript("window.__mw&&window.__mw.back()", null);
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
        if (mode == 0) {   // in a conversation, Back returns to the chat list first
            web.evaluateJavascript("(window.__mw&&window.__mw.back())===true", r -> {
                if (!"true".equals(r)) goBackOrHide();
            });
        } else {
            goBackOrHide();
        }
    }

    private void goBackOrHide() {
        if (web.canGoBack()) web.goBack(); else moveTaskToBack(true);
    }

    /** Phone mode: reshapes WhatsApp Web's two-pane desktop layout into one pane at a time. */
    private void injectPhoneLayout() {
        if (mode != 0) return;
        try {
            if (mwScript == null) {
                try (java.io.InputStream in = getAssets().open("mw.js")) {
                    mwScript = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                }
            }
            web.evaluateJavascript(mwScript, null);
        } catch (java.io.IOException ignored) { }
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
