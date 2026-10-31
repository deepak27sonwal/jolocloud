package com.cloudbackup.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private EditText url, anon, email, password;
    private CheckBox photos, videos, contacts, auto, compress, messages;
    private TextView summary, log;
    private Settings st;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            summary.setText(Status.running ? Status.summary : (Status.summary));
            log.setText(Status.text());
            ui.postDelayed(this, 1000);
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        st = new Settings(this);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);

        label(root, "Supabase project");
        url = field(root, "Project URL, e.g. https://xxxx.supabase.co", st.url(), false);
        anon = field(root, "Publishable / anon key", st.anonKey(), false);
        label(root, "Account (your files are private to it)");
        email = field(root, "Email", st.email(), false);
        email.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        password = field(root, "Password (min 6 characters)", "", true);
        Button signIn = button(root, st.configured() ? "Sign in again" : "Sign in");
        signIn.setOnClickListener(v -> auth(false));
        Button signUp = button(root, "Create account");
        signUp.setOnClickListener(v -> auth(true));

        label(root, "What to back up");
        photos = check(root, "Photos", st.photos());
        compress = check(root, "Compress photos to WebP (smaller, faster uploads)", st.compress());
        videos = check(root, "Videos", st.videos());
        contacts = check(root, "Contacts", st.contacts());
        messages = check(root, "Text messages (SMS)", st.messages());
        auto = check(root, "Automatic daily backup (Wi-Fi + charging)", st.auto());

        Button start = button(root, "Back up now");
        start.setOnClickListener(v -> startBackup());
        Button stop = button(root, "Stop");
        stop.setOnClickListener(v -> startService(new Intent(this, BackupService.class)
                .setAction(BackupService.ACTION_STOP)));

        summary = new TextView(this);
        summary.setTextSize(16);
        summary.setPadding(0, pad, 0, 0);
        root.addView(summary);
        log = new TextView(this);
        log.setTextSize(12);
        root.addView(log);
    }

    @Override protected void onResume() { super.onResume(); ui.post(refresh); }
    @Override protected void onPause() { super.onPause(); ui.removeCallbacks(refresh); }

    private void auth(boolean create) {
        saveConn();
        String e = email.getText().toString().trim(), p = password.getText().toString();
        if (st.url().isEmpty() || st.anonKey().isEmpty() || e.isEmpty() || p.length() < 6) {
            Toast.makeText(this, "Fill in URL, key, email and a 6+ char password", Toast.LENGTH_LONG).show();
            return;
        }
        new Thread(() -> {
            String msg;
            try {
                SupabaseClient c = new SupabaseClient(st);
                if (create) c.signUp(e, p); else c.signIn(e, p);
                msg = "Signed in as " + e;
            } catch (Exception ex) {
                msg = ex.getMessage();
            }
            String m = msg;
            ui.post(() -> Toast.makeText(this, m, Toast.LENGTH_LONG).show());
        }).start();
    }

    private void saveConn() {
        String u = url.getText().toString().trim();
        if (!u.isEmpty() && !u.startsWith("https://")) u = "https://" + u;
        st.p.edit().putString("url", u)
                .putString("anon", anon.getText().toString().trim())
                .putString("email", email.getText().toString().trim()).apply();
    }

    private void save() {
        saveConn();
        st.p.edit()
                .putBoolean("photos", photos.isChecked())
                .putBoolean("compress", compress.isChecked())
                .putBoolean("videos", videos.isChecked())
                .putBoolean("contacts", contacts.isChecked())
                .putBoolean("messages", messages.isChecked())
                .putBoolean("auto", auto.isChecked())
                .apply();
        BackupJobService.schedule(this, auto.isChecked());
    }

    private void startBackup() {
        save();
        if (!st.configured()) {
            Toast.makeText(this, "Sign in to your Supabase account first", Toast.LENGTH_LONG).show();
            return;
        }
        List<String> need = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33) {
            if (photos.isChecked()) need.add(Manifest.permission.READ_MEDIA_IMAGES);
            if (videos.isChecked()) need.add(Manifest.permission.READ_MEDIA_VIDEO);
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        } else if (photos.isChecked() || videos.isChecked()) {
            need.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }
        if (contacts.isChecked()) need.add(Manifest.permission.READ_CONTACTS);
        if (messages.isChecked()) need.add(Manifest.permission.READ_SMS);
        List<String> missing = new ArrayList<>();
        for (String p : need) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), 1);
        } else {
            launch();
        }
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        launch(); // run with whatever was granted; the engine reports anything unreadable
    }

    private void launch() {
        Status.summary = "Starting…";
        startForegroundService(new Intent(this, BackupService.class));
    }

    // ---- tiny UI helpers
    private void label(LinearLayout p, String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(14);
        v.setPadding(0, 24, 0, 4);
        p.addView(v);
    }

    private EditText field(LinearLayout p, String hint, String val, boolean secret) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(val);
        e.setSingleLine();
        e.setInputType(secret ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                              : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        p.addView(e);
        return e;
    }

    private CheckBox check(LinearLayout p, String t, boolean on) {
        CheckBox c = new CheckBox(this);
        c.setText(t);
        c.setChecked(on);
        p.addView(c);
        return c;
    }

    private Button button(LinearLayout p, String t) {
        Button b = new Button(this);
        b.setText(t);
        p.addView(b);
        return b;
    }
}
