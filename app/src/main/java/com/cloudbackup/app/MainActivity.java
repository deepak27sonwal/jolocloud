package com.cloudbackup.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQ_PICK_FOLDER = 10, REQ_PICK_FILES = 11;
    private EditText url, anon, email, password;
    private Switch photos, videos, contacts, auto, compress, messages, files;
    private TextView summary, log, listenerState, statsText;
    private Button pinButton, removePinButton, timeButton, addFolderBtn, addFilesBtn;
    private LinearLayout filesListContainer;
    private Switch serviceSwitch;
    private View[] gatedByService;
    private Settings st;
    private float d;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            summary.setText(Status.running ? Status.summary : (Status.summary));
            log.setText(Status.text());
            updateCaptureState();
            updateStats();
            ui.postDelayed(this, 1000);
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        st = new Settings(this);
        d = getResources().getDisplayMetrics().density;

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(getColor(R.color.bg));
        sv.setFillViewport(true);
        FrameLayout holder = new FrameLayout(this);
        int maxW = Math.min(dp(640), getResources().getDisplayMetrics().widthPixels);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        holder.addView(col, new FrameLayout.LayoutParams(maxW, -2, Gravity.CENTER_HORIZONTAL));
        sv.addView(holder);
        setContentView(sv);

        // ---------------------------------------------------------------- header
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(24), dp(28), dp(24), dp(28));
        GradientDrawable hg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{getColor(R.color.primary_dark), getColor(R.color.accent)});
        hg.setCornerRadii(new float[]{0, 0, 0, 0, dp(26), dp(26), dp(26), dp(26)});
        header.setBackground(hg);
        ImageView cloudIcon = new ImageView(this);
        cloudIcon.setImageResource(R.drawable.ic_cloud);
        cloudIcon.setColorFilter(Color.WHITE);
        header.addView(cloudIcon, new LinearLayout.LayoutParams(dp(34), dp(34)));
        LinearLayout headerText = new LinearLayout(this);
        headerText.setOrientation(LinearLayout.VERTICAL);
        headerText.setPadding(dp(14), 0, 0, 0);
        TextView title = text("Cloud Backup", 22, Color.WHITE, true);
        headerText.addView(title);
        TextView subtitle = text(st.configured() ? "Signed in as " + st.email() : "Not signed in", 13, 0xD9FFFFFF, false);
        headerText.addView(subtitle);
        header.addView(headerText, new LinearLayout.LayoutParams(0, -2, 1));
        col.addView(header, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(16), dp(16), dp(24));
        col.addView(body, new LinearLayout.LayoutParams(-1, -2));

        // ---------------------------------------------------------------- dashboard card
        LinearLayout dash = card();
        LinearLayout serviceRow = new LinearLayout(this);
        serviceRow.setOrientation(LinearLayout.HORIZONTAL);
        serviceRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView serviceLabel = text("Backup service", 15, getColor(R.color.text), true);
        serviceRow.addView(serviceLabel, new LinearLayout.LayoutParams(0, -2, 1));
        serviceSwitch = new Switch(this);
        serviceSwitch.setChecked(st.serviceEnabled());
        serviceRow.addView(serviceSwitch);
        dash.addView(serviceRow);
        TextView serviceHint = text("Turns automatic backup and message capture on or off. \"Back up now\" still works while this is off.",
                12, getColor(R.color.subtext), false);
        serviceHint.setPadding(0, dp(2), 0, dp(14));
        dash.addView(serviceHint);

        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        Button start = pillButton(actionRow, "☁  Back up now", true);
        LinearLayout.LayoutParams startLp = new LinearLayout.LayoutParams(0, dp(48), 1);
        actionRow.getChildAt(0).setLayoutParams(startLp);
        start.setOnClickListener(v -> startBackup());
        Button stop = pillButton(actionRow, "Stop", false);
        LinearLayout.LayoutParams stopLp = new LinearLayout.LayoutParams(dp(90), dp(48));
        stopLp.setMargins(dp(8), 0, 0, 0);
        stop.setLayoutParams(stopLp);
        stop.setOnClickListener(v -> startService(new Intent(this, BackupService.class)
                .setAction(BackupService.ACTION_STOP)));
        dash.addView(actionRow, new LinearLayout.LayoutParams(-1, -2));

        summary = text("Ready to back up.", 15, getColor(R.color.text), true);
        summary.setPadding(0, dp(14), 0, dp(4));
        dash.addView(summary);

        statsText = text("", 12, getColor(R.color.subtext), false);
        dash.addView(statsText);

        FrameLayout logFrame = new FrameLayout(this);
        GradientDrawable logBg = new GradientDrawable();
        logBg.setCornerRadius(dp(10));
        logBg.setColor(getColor(R.color.bg));
        logBg.setStroke(dp(1), getColor(R.color.stroke));
        logFrame.setBackground(logBg);
        ScrollView logScroll = new ScrollView(this);
        log = text("", 11, getColor(R.color.subtext), false);
        log.setTypeface(Typeface.MONOSPACE);
        log.setPadding(dp(10), dp(8), dp(10), dp(8));
        logScroll.addView(log);
        logFrame.addView(logScroll);
        LinearLayout.LayoutParams logLp = new LinearLayout.LayoutParams(-1, dp(130));
        logLp.setMargins(0, dp(10), 0, 0);
        dash.addView(logFrame, logLp);
        addCard(body, dash);

        // ---------------------------------------------------------------- social & messaging backup (one switch)
        LinearLayout social = card();
        LinearLayout socialRow = new LinearLayout(this);
        socialRow.setOrientation(LinearLayout.HORIZONTAL);
        socialRow.setGravity(Gravity.CENTER_VERTICAL);
        ImageView socialIcon = new ImageView(this);
        socialIcon.setImageResource(R.drawable.ic_message);
        socialIcon.setColorFilter(getColor(R.color.primary));
        socialRow.addView(socialIcon, new LinearLayout.LayoutParams(dp(20), dp(20)));
        TextView socialTitle = text("Social & messaging auto-backup", 15, getColor(R.color.text), true);
        LinearLayout.LayoutParams socialTitleLp = new LinearLayout.LayoutParams(0, -2, 1);
        socialTitleLp.setMargins(dp(10), 0, dp(8), 0);
        socialRow.addView(socialTitle, socialTitleLp);
        Switch socialSwitch = new Switch(this);
        boolean socialOn = st.messages() && st.auto() && st.filesEnabled() && st.serviceEnabled();
        socialSwitch.setChecked(socialOn);
        socialRow.addView(socialSwitch);
        social.addView(socialRow);
        TextView socialHint = text("One switch for message text (WhatsApp, Facebook, Facebook Lite, Messenger, "
                + "Messenger Lite, Instagram, Telegram, Snapchat) plus the WhatsApp/Telegram media folders below, "
                + "backed up automatically every day. Turns on the backup service, daily schedule, message capture "
                + "and files/folders together.", 12, getColor(R.color.subtext), false);
        socialHint.setPadding(0, dp(4), 0, 0);
        social.addView(socialHint);
        addCard(body, social);

        // ---------------------------------------------------------------- account card
        LinearLayout acct = card();
        sectionHeader(acct, R.drawable.ic_person, "Account");
        email = inputField(acct, "Email", st.email(), false);
        email.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        password = inputField(acct, "Password (min 6 characters)", "", true);
        LinearLayout authRow = new LinearLayout(this);
        authRow.setOrientation(LinearLayout.HORIZONTAL);
        Button signIn = pillButton(authRow, st.configured() ? "Sign in again" : "Sign in", true);
        authRow.getChildAt(0).setLayoutParams(new LinearLayout.LayoutParams(0, dp(44), 1));
        signIn.setOnClickListener(v -> auth(false));
        Button signUp = pillButton(authRow, "Create account", false);
        LinearLayout.LayoutParams signUpLp = new LinearLayout.LayoutParams(0, dp(44), 1);
        signUpLp.setMargins(dp(8), 0, 0, 0);
        signUp.setLayoutParams(signUpLp);
        signUp.setOnClickListener(v -> auth(true));
        LinearLayout.LayoutParams authLp = new LinearLayout.LayoutParams(-1, -2);
        authLp.setMargins(0, dp(10), 0, 0);
        acct.addView(authRow, authLp);

        // collapsible advanced (Supabase project) section
        LinearLayout advToggle = new LinearLayout(this);
        advToggle.setOrientation(LinearLayout.HORIZONTAL);
        advToggle.setGravity(Gravity.CENTER_VERTICAL);
        advToggle.setPadding(0, dp(14), 0, 0);
        ImageView chevron = new ImageView(this);
        chevron.setImageResource(R.drawable.ic_chevron_down);
        chevron.setColorFilter(getColor(R.color.subtext));
        advToggle.addView(chevron, new LinearLayout.LayoutParams(dp(18), dp(18)));
        TextView advLabel = text("Advanced: Supabase project", 12, getColor(R.color.subtext), false);
        advLabel.setPadding(dp(6), 0, 0, 0);
        advToggle.addView(advLabel);
        acct.addView(advToggle);
        LinearLayout advBody = new LinearLayout(this);
        advBody.setOrientation(LinearLayout.VERTICAL);
        advBody.setVisibility(View.GONE);
        url = inputField(advBody, "Project URL, e.g. https://xxxx.supabase.co", st.url(), false);
        anon = inputField(advBody, "Publishable / anon key", st.anonKey(), false);
        acct.addView(advBody);
        advToggle.setOnClickListener(v -> {
            boolean show = advBody.getVisibility() != View.VISIBLE;
            advBody.setVisibility(show ? View.VISIBLE : View.GONE);
            chevron.setRotation(show ? 180f : 0f);
        });
        addCard(body, acct);

        // ---------------------------------------------------------------- what to back up card
        LinearLayout what = card();
        sectionHeader(what, R.drawable.ic_check_circle, "What to back up");

        photos = toggleRow(what, R.drawable.ic_photo, "Photos", st.photos());
        LinearLayout compressRow = new LinearLayout(this);
        compressRow.setOrientation(LinearLayout.HORIZONTAL);
        compressRow.setGravity(Gravity.CENTER_VERTICAL);
        compressRow.setPadding(dp(30), 0, 0, dp(8));
        compress = new Switch(this);
        compress.setChecked(st.compress());
        TextView compressLabel = text("Compress to WebP (smaller, faster uploads)", 12, getColor(R.color.subtext), false);
        compressRow.addView(compressLabel, new LinearLayout.LayoutParams(0, -2, 1));
        compressRow.addView(compress);
        what.addView(compressRow);

        videos = toggleRow(what, R.drawable.ic_video, "Videos", st.videos());
        contacts = toggleRow(what, R.drawable.ic_person, "Contacts", st.contacts());

        messages = toggleRow(what, R.drawable.ic_message, "Messages (captured from notifications)", st.messages());
        LinearLayout msgSub = new LinearLayout(this);
        msgSub.setOrientation(LinearLayout.VERTICAL);
        msgSub.setPadding(dp(30), 0, 0, dp(10));
        Button access = outlineButton(msgSub, "Enable notification access");
        access.setOnClickListener(v -> startActivity(
                new Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        listenerState = text("", 12, getColor(R.color.subtext), false);
        listenerState.setPadding(0, dp(8), 0, 0);
        msgSub.addView(listenerState);
        TextView apps = text("Captures: " + AppCatalog.SUMMARY + ", and any other app that posts message notifications.",
                11, getColor(R.color.subtext), false);
        apps.setPadding(0, dp(6), 0, dp(6));
        msgSub.addView(apps);
        Button battery = outlineButton(msgSub, "Keep running in the background");
        battery.setOnClickListener(v -> {
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                startActivity(new Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            }
        });
        what.addView(msgSub);

        auto = toggleRow(what, R.drawable.ic_settings, "Automatic daily backup (Wi-Fi + charging)", st.auto());
        LinearLayout timeRow = new LinearLayout(this);
        timeRow.setOrientation(LinearLayout.HORIZONTAL);
        timeRow.setPadding(dp(30), 0, 0, dp(10));
        timeButton = chipButton(timeRow, "⏰ Backup time: " + timeLabel());
        timeButton.setOnClickListener(v -> pickTime());
        what.addView(timeRow);
        addCard(body, what);

        // ---------------------------------------------------------------- files & folders card
        LinearLayout filesCard = card();
        files = toggleRow(filesCard, R.drawable.ic_folder, "Back up chosen files and folders", st.filesEnabled());
        LinearLayout filesBtnRow = new LinearLayout(this);
        filesBtnRow.setOrientation(LinearLayout.HORIZONTAL);
        filesBtnRow.setPadding(dp(30), 0, 0, dp(8));
        addFolderBtn = outlineButton(filesBtnRow, "+ Add folder");
        addFolderBtn.setOnClickListener(v -> startActivityForResult(
                new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_PICK_FOLDER));
        ((LinearLayout.LayoutParams) addFolderBtn.getLayoutParams()).setMargins(0, 0, dp(8), 0);
        addFilesBtn = outlineButton(filesBtnRow, "+ Add files");
        addFilesBtn.setOnClickListener(v -> startActivityForResult(
                new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), REQ_PICK_FILES));
        filesCard.addView(filesBtnRow);

        LinearLayout waRow = new LinearLayout(this);
        waRow.setOrientation(LinearLayout.HORIZONTAL);
        waRow.setPadding(dp(30), 0, 0, dp(8));
        Button waBtn = outlineButton(waRow, "📷 WhatsApp photos");
        waBtn.setOnClickListener(v -> pickAppMediaFolder("com.whatsapp", "WhatsApp", "WhatsApp Images"));
        ((LinearLayout.LayoutParams) waBtn.getLayoutParams()).setMargins(0, 0, dp(8), 0);
        Button waBizBtn = outlineButton(waRow, "WhatsApp Business photos");
        waBizBtn.setOnClickListener(v -> pickAppMediaFolder("com.whatsapp.w4b", "WhatsApp Business", "WhatsApp Business Images"));
        filesCard.addView(waRow);

        LinearLayout waVidRow = new LinearLayout(this);
        waVidRow.setOrientation(LinearLayout.HORIZONTAL);
        waVidRow.setPadding(dp(30), 0, 0, dp(8));
        Button waVidBtn = outlineButton(waVidRow, "🎥 WhatsApp videos");
        waVidBtn.setOnClickListener(v -> pickAppMediaFolder("com.whatsapp", "WhatsApp", "WhatsApp Video"));
        ((LinearLayout.LayoutParams) waVidBtn.getLayoutParams()).setMargins(0, 0, dp(8), 0);
        Button waBizVidBtn = outlineButton(waVidRow, "WhatsApp Business videos");
        waBizVidBtn.setOnClickListener(v -> pickAppMediaFolder("com.whatsapp.w4b", "WhatsApp Business", "WhatsApp Business Video"));
        filesCard.addView(waVidRow);
        TextView waHint = text("Covers both sent and received — WhatsApp keeps them in one folder either way. "
                + "Tap “Use this folder” / “Allow” on the screen that opens; if it doesn’t land in the "
                + "right place, browse to Internal storage → Android → media → com.whatsapp → WhatsApp → "
                + "Media → (the matching folder).", 11, getColor(R.color.subtext), false);
        waHint.setPadding(dp(30), 0, 0, dp(10));
        filesCard.addView(waHint);

        LinearLayout tgRow = new LinearLayout(this);
        tgRow.setOrientation(LinearLayout.HORIZONTAL);
        tgRow.setPadding(dp(30), 0, 0, dp(8));
        Button tgBtn = outlineButton(tgRow, "✈️ Telegram media (photos, video, docs)");
        tgBtn.setOnClickListener(v -> pickAppFolder("org.telegram.messenger", "Telegram"));
        filesCard.addView(tgRow);
        TextView tgHint = text("Picks Telegram's whole media folder, which already keeps Images, Video and "
                + "Documents separately — this one covers all of them at once.", 11, getColor(R.color.subtext), false);
        tgHint.setPadding(dp(30), 0, 0, dp(10));
        filesCard.addView(tgHint);

        TextView mediaLimits = text("Instagram, Facebook, Facebook Lite, Messenger, Messenger Lite and Snapchat don't save "
                + "chat photos/videos to a folder any backup app can reach — Snapchat in particular is built not to keep "
                + "received content at all. Their message text is still captured above.",
                11, getColor(R.color.warn), false);
        mediaLimits.setPadding(dp(30), dp(4), 0, dp(10));
        filesCard.addView(mediaLimits);

        filesListContainer = new LinearLayout(this);
        filesListContainer.setOrientation(LinearLayout.VERTICAL);
        filesListContainer.setPadding(dp(30), 0, 0, 0);
        filesCard.addView(filesListContainer);
        renderFilesList();
        addCard(body, filesCard);

        gatedByService = new View[]{photos, compress, videos, contacts, messages, access, battery, auto, timeButton,
                files, addFolderBtn, addFilesBtn, waBtn, waBizBtn, waVidBtn, waBizVidBtn, tgBtn};
        setGatedEnabled(st.serviceEnabled());
        serviceSwitch.setOnCheckedChangeListener((v, on) -> {
            st.p.edit().putBoolean("serviceEnabled", on).apply();
            setGatedEnabled(on);
            save();
            Toast.makeText(this, on ? "Backup service is ON" : "Backup service is OFF", Toast.LENGTH_SHORT).show();
        });
        socialSwitch.setOnCheckedChangeListener((v, on) -> {
            messages.setChecked(on);
            files.setChecked(on);
            auto.setChecked(on);
            if (on && !serviceSwitch.isChecked()) serviceSwitch.setChecked(true); // make sure it can actually run
            save();
            setGatedEnabled(serviceSwitch.isChecked());
            Toast.makeText(this, on ? "Social & messaging backup is ON" : "Social & messaging backup is OFF",
                    Toast.LENGTH_SHORT).show();
        });

        // ---------------------------------------------------------------- app lock card
        LinearLayout lock = card();
        sectionHeader(lock, R.drawable.ic_lock, "App lock");
        LinearLayout lockRow = new LinearLayout(this);
        lockRow.setOrientation(LinearLayout.HORIZONTAL);
        lockRow.setPadding(0, dp(10), 0, 0);
        pinButton = outlineButton(lockRow, st.pinSet() ? "Change PIN" : "Set a PIN to open this app");
        pinButton.setOnClickListener(v -> promptSetPin());
        ((LinearLayout.LayoutParams) pinButton.getLayoutParams()).setMargins(0, 0, dp(8), 0);
        removePinButton = outlineButton(lockRow, "Remove PIN");
        removePinButton.setVisibility(st.pinSet() ? View.VISIBLE : View.GONE);
        removePinButton.setOnClickListener(v -> promptRemovePin());
        lock.addView(lockRow);
        addCard(body, lock);
    }

    @Override protected void onResume() {
        super.onResume();
        updateCaptureState();
        updateStats();
        if (st.messages() && st.serviceEnabled()) OutboxJobService.schedule(this);
        ui.post(refresh);
    }
    @Override protected void onPause() { super.onPause(); ui.removeCallbacks(refresh); }

    private void updateCaptureState() {
        String on = android.provider.Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        boolean enabled = on != null && on.contains(getPackageName());
        int waiting = Outbox.pending(this);
        StringBuilder t = new StringBuilder(enabled
                ? "Message capture is ON. New messages are saved as they arrive."
                : "Message capture is OFF. Tap the button above and allow Cloud Backup.");
        t.append("\nWaiting to upload: ").append(waiting);
        if (waiting > 0 && !st.configured()) t.append("  (sign in above to upload them)");
        listenerState.setText(t.toString());
    }

    private void updateStats() {
        long total = st.totalUploaded();
        long last = st.lastBackupAt();
        String lastStr = last == 0 ? "never" : DateUtils.getRelativeTimeSpanString(last, System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS).toString();
        statsText.setText(total + " files backed up in total  •  Last backup: " + lastStr);
    }

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
                .putBoolean("filesEnabled", files.isChecked())
                .apply();
        BackupJobService.schedule(this, auto.isChecked());
        if (messages.isChecked()) OutboxJobService.schedule(this); else OutboxJobService.cancel(this);
    }

    private void setGatedEnabled(boolean on) {
        for (View v : gatedByService) v.setEnabled(on);
    }

    private String timeLabel() {
        return String.format(Locale.US, "%02d:%02d", st.backupHour(), st.backupMinute());
    }

    private void pickTime() {
        new android.app.TimePickerDialog(this, (TimePicker tp, int hour, int minute) -> {
            st.p.edit().putInt("backupHour", hour).putInt("backupMinute", minute).apply();
            timeButton.setText("⏰ Backup time: " + timeLabel());
            if (auto.isChecked() && serviceSwitch.isChecked()) BackupJobService.schedule(this, true);
        }, st.backupHour(), st.backupMinute(), true).show();
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

    // ---- files & folders (Storage Access Framework)
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        if (requestCode == REQ_PICK_FOLDER) {
            Uri tree = data.getData();
            if (tree == null) return;
            takePersisted(tree);
            st.addPickedTree(tree.toString());
            renderFilesList();
            files.setChecked(true);
            save();
        } else if (requestCode == REQ_PICK_FILES) {
            List<Uri> picked = new ArrayList<>();
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) picked.add(data.getClipData().getItemAt(i).getUri());
            } else if (data.getData() != null) {
                picked.add(data.getData());
            }
            for (Uri u : picked) {
                takePersisted(u);
                st.addPickedFile(u.toString());
            }
            if (!picked.isEmpty()) {
                renderFilesList();
                files.setChecked(true);
                save();
            }
        }
    }

    /**
     * Opens the folder picker pre-navigated (best-effort) to another app's media folder under
     * Android/media/&lt;pkg&gt;, so adding it to "Files & folders" normally needs only one tap
     * ("Use this folder"). The hint is ignored harmlessly on pickers that don't support it — the
     * user then just browses there manually, same as the plain "Add folder" button.
     */
    private void pickAppMediaFolder(String pkg, String appFolderName, String mediaSubfolder) {
        pickAppFolder(pkg, appFolderName + "/Media/" + mediaSubfolder);
    }

    /** Same pre-navigation trick, but with full control over the path after Android/media/&lt;pkg&gt;/ —
     *  not every app nests its media folders the same way WhatsApp does. */
    private void pickAppFolder(String pkg, String relativePath) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        try {
            String docId = "primary:Android/media/" + pkg + "/" + relativePath;
            Uri hint = DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", docId);
            i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, hint);
        } catch (Exception ignored) { } // no hint: the picker just opens at its usual starting point
        startActivityForResult(i, REQ_PICK_FOLDER);
    }

    private void takePersisted(Uri uri) {
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) { } // some providers (e.g. plain file pickers) do not support this; we still try to read it later
    }

    private void renderFilesList() {
        filesListContainer.removeAllViews();
        for (String s : st.pickedTrees()) addPickedRow(R.drawable.ic_folder, treeName(Uri.parse(s)), () -> {
            st.removePickedTree(s);
            try { getContentResolver().releasePersistableUriPermission(Uri.parse(s), Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) { }
            renderFilesList();
        });
        for (String s : st.pickedFiles()) addPickedRow(R.drawable.ic_check_circle, fileName(Uri.parse(s)), () -> {
            st.removePickedFile(s);
            try { getContentResolver().releasePersistableUriPermission(Uri.parse(s), Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) { }
            renderFilesList();
        });
        if (st.pickedTrees().isEmpty() && st.pickedFiles().isEmpty()) {
            TextView none = text("Nothing chosen yet.", 12, getColor(R.color.subtext), false);
            none.setPadding(0, dp(6), 0, dp(6));
            filesListContainer.addView(none);
        }
    }

    private interface Removal { void run(); }

    private void addPickedRow(int iconRes, String label, Removal onRemove) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));
        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setColorFilter(getColor(R.color.subtext));
        row.addView(icon, new LinearLayout.LayoutParams(dp(16), dp(16)));
        TextView t = text(label, 13, getColor(R.color.text), false);
        t.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        t.setSingleLine();
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
        lp.setMargins(dp(8), 0, 0, 0);
        row.addView(t, lp);
        ImageView remove = new ImageView(this);
        remove.setImageResource(R.drawable.ic_x);
        remove.setColorFilter(getColor(R.color.subtext));
        remove.setPadding(dp(6), dp(6), dp(6), dp(6));
        remove.setOnClickListener(v -> onRemove.run());
        row.addView(remove, new LinearLayout.LayoutParams(dp(28), dp(28)));
        filesListContainer.addView(row);
    }

    private String treeName(Uri treeUri) {
        try {
            String docId = DocumentsContract.getTreeDocumentId(treeUri);
            Uri docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
            try (Cursor c = getContentResolver().query(docUri,
                    new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    String n = c.getString(0);
                    if (n != null && !n.isEmpty()) return n;
                }
            }
        } catch (Exception ignored) { }
        return treeUri.getLastPathSegment() != null ? treeUri.getLastPathSegment() : "folder";
    }

    private String fileName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null && !n.isEmpty()) return n;
            }
        } catch (Exception ignored) { }
        return uri.getLastPathSegment() != null ? uri.getLastPathSegment() : "file";
    }

    private void launch() {
        Status.summary = "Starting…";
        startForegroundService(new Intent(this, BackupService.class));
    }

    // ---------------------------------------------------------------- UI helpers
    private int dp(int v) { return Math.round(v * d); }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(16), dp(16), dp(16));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(16));
        g.setColor(getColor(R.color.surface));
        g.setStroke(dp(1), getColor(R.color.stroke));
        c.setBackground(g);
        c.setElevation(dp(2));
        return c;
    }

    private void addCard(LinearLayout root, LinearLayout cardView) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(12), 0, 0);
        root.addView(cardView, lp);
    }

    private void sectionHeader(LinearLayout card, int iconRes, String title) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setColorFilter(getColor(R.color.primary));
        row.addView(icon, new LinearLayout.LayoutParams(dp(20), dp(20)));
        TextView t = text(title, 15, getColor(R.color.text), true);
        t.setPadding(dp(10), 0, 0, 0);
        row.addView(t);
        card.addView(row);
    }

    /** A labelled row with a leading icon and a trailing Switch; used for every "back up X" toggle. */
    private Switch toggleRow(LinearLayout card, int iconRes, String title, boolean checked) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, dp(4));
        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setColorFilter(getColor(R.color.subtext));
        row.addView(icon, new LinearLayout.LayoutParams(dp(20), dp(20)));
        TextView t = text(title, 14, getColor(R.color.text), false);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1);
        tlp.setMargins(dp(10), 0, dp(8), 0);
        row.addView(t, tlp);
        Switch sw = new Switch(this);
        sw.setChecked(checked);
        row.addView(sw);
        card.addView(row);
        return sw;
    }

    private EditText inputField(LinearLayout p, String hint, String val, boolean secret) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(val);
        e.setSingleLine();
        e.setTextColor(getColor(R.color.text));
        e.setHintTextColor(getColor(R.color.subtext));
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(10));
        g.setColor(getColor(R.color.bg));
        g.setStroke(dp(1), getColor(R.color.stroke));
        e.setBackground(g);
        e.setInputType(secret ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                              : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(8), 0, 0);
        p.addView(e, lp);
        return e;
    }

    /** A filled pill button (primary) when filled==true, otherwise a lighter tinted pill. */
    private Button pillButton(LinearLayout p, String t, boolean filled) {
        Button b = new Button(this);
        b.setText(t);
        b.setAllCaps(false);
        b.setTextColor(filled ? getColor(R.color.on_primary) : getColor(R.color.primary));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(24));
        g.setColor(filled ? getColor(R.color.primary) : getColor(R.color.chip_bg));
        b.setBackground(g);
        p.addView(b, new LinearLayout.LayoutParams(-2, -2));
        return b;
    }

    /** A smaller outlined button for secondary actions inside a card. */
    private Button outlineButton(LinearLayout p, String t) {
        Button b = new Button(this);
        b.setText(t);
        b.setAllCaps(false);
        b.setTextSize(12);
        b.setTextColor(getColor(R.color.primary));
        b.setMinHeight(dp(36));
        b.setPadding(dp(14), dp(2), dp(14), dp(2));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(18));
        g.setColor(Color.TRANSPARENT);
        g.setStroke(dp(1), getColor(R.color.primary));
        b.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(0, dp(6), 0, dp(6));
        p.addView(b, lp);
        return b;
    }

    /** A compact tappable chip, used for the backup-time picker. */
    private Button chipButton(LinearLayout p, String t) {
        Button b = new Button(this);
        b.setText(t);
        b.setAllCaps(false);
        b.setTextSize(12);
        b.setTextColor(getColor(R.color.primary));
        b.setMinHeight(dp(32));
        b.setPadding(dp(14), 0, dp(14), 0);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(16));
        g.setColor(getColor(R.color.chip_bg));
        b.setBackground(g);
        p.addView(b, new LinearLayout.LayoutParams(-2, dp(32)));
        return b;
    }

    // ---- app PIN lock
    private void promptSetPin() {
        if (st.pinSet()) {
            EditText current = pinField("Current PIN");
            new android.app.AlertDialog.Builder(this).setTitle("Enter current PIN").setView(current)
                    .setPositiveButton("Next", (dlg, w) -> {
                        if (PinUtil.matches(current.getText().toString(), st.pinSalt(), st.pinHash())) {
                            promptNewPin();
                        } else {
                            Toast.makeText(this, "Wrong PIN", Toast.LENGTH_SHORT).show();
                        }
                    }).setNegativeButton("Cancel", null).show();
        } else {
            promptNewPin();
        }
    }

    private void promptNewPin() {
        EditText pin1 = pinField("New PIN (4+ digits)");
        new android.app.AlertDialog.Builder(this).setTitle("Set a PIN").setView(pin1)
                .setPositiveButton("Next", (dlg, w) -> {
                    String p1 = pin1.getText().toString();
                    if (p1.length() < 4) {
                        Toast.makeText(this, "PIN must be at least 4 digits", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    EditText pin2 = pinField("Confirm PIN");
                    new android.app.AlertDialog.Builder(this).setTitle("Confirm PIN").setView(pin2)
                            .setPositiveButton("Set PIN", (d2, w2) -> {
                                if (!p1.equals(pin2.getText().toString())) {
                                    Toast.makeText(this, "PINs did not match", Toast.LENGTH_SHORT).show();
                                    return;
                                }
                                String salt = PinUtil.newSalt();
                                st.p.edit().putString("pinSalt", salt).putString("pinHash", PinUtil.hash(p1, salt)).apply();
                                LockState.setUnlocked(true); // avoid immediately re-locking yourself out this run
                                pinButton.setText("Change PIN");
                                removePinButton.setVisibility(View.VISIBLE);
                                Toast.makeText(this, "PIN set", Toast.LENGTH_SHORT).show();
                            }).setNegativeButton("Cancel", null).show();
                }).setNegativeButton("Cancel", null).show();
    }

    private void promptRemovePin() {
        if (!st.pinSet()) return;
        EditText current = pinField("Current PIN");
        new android.app.AlertDialog.Builder(this).setTitle("Remove PIN").setView(current)
                .setPositiveButton("Remove", (dlg, w) -> {
                    if (PinUtil.matches(current.getText().toString(), st.pinSalt(), st.pinHash())) {
                        st.p.edit().remove("pinSalt").remove("pinHash").apply();
                        pinButton.setText("Set a PIN to open this app");
                        removePinButton.setVisibility(View.GONE);
                        Toast.makeText(this, "PIN removed", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "Wrong PIN", Toast.LENGTH_SHORT).show();
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    private EditText pinField(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        int pad = dp(16);
        e.setPadding(pad, pad, pad, pad);
        return e;
    }
}
