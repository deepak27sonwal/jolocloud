package com.multiweb.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {
    static final Class<?>[] SLOTS = {Slot1.class, Slot2.class, Slot3.class, Slot4.class, Slot5.class, Slot6.class};
    /** One accent colour per account, reused for its avatar, chat title bar and loading screen. */
    static final int[] COLORS = {0xFF00897B, 0xFF3949AB, 0xFFF4511E, 0xFF8E24AA, 0xFF1E88E5, 0xFFD81B60};

    private SharedPreferences prefs;
    private LinearLayout list;
    private float d;
    private boolean animated = false;

    private int dp(int v) { return Math.round(v * d); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        d = getResources().getDisplayMetrics().density;
        prefs = getSharedPreferences("accounts", MODE_PRIVATE);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(getColor(R.color.bg));
        sv.setFillViewport(true);
        FrameLayout holder = new FrameLayout(this);
        int maxW = Math.min(dp(600), getResources().getDisplayMetrics().widthPixels);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        holder.addView(col, new FrameLayout.LayoutParams(maxW, -2, Gravity.CENTER_HORIZONTAL));
        sv.addView(holder);
        setContentView(sv);

        // ---- header
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(24), dp(28), dp(24), dp(32));
        GradientDrawable hg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{getColor(R.color.primary_dark), 0xFF26A69A});
        hg.setCornerRadii(new float[]{0, 0, 0, 0, dp(28), dp(28), dp(28), dp(28)});
        header.setBackground(hg);
        header.addView(text("MultiWeb", 30, Color.WHITE, true));
        TextView sub = text("Run all your WhatsApp Web accounts side by side", 15, 0xCCFFFFFF, false);
        sub.setPadding(0, dp(4), 0, 0);
        header.addView(sub);
        col.addView(header, new LinearLayout.LayoutParams(-1, -2));

        // ---- how-to card
        LinearLayout how = card();
        how.setOrientation(LinearLayout.VERTICAL);
        how.addView(text("Link an account in 3 steps", 16, getColor(R.color.text), true));
        how.addView(step("1", "Tap an account below"));
        how.addView(step("2", "On your phone: WhatsApp → Linked devices → Link a device"));
        how.addView(step("3", "Scan the QR code. You stay logged in"));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2);
        hp.setMargins(dp(16), dp(-16), dp(16), 0);
        col.addView(how, hp);

        TextView label = text("YOUR ACCOUNTS", 12, getColor(R.color.subtext), true);
        label.setLetterSpacing(0.1f);
        label.setPadding(dp(24), dp(24), dp(24), dp(4));
        col.addView(label);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(16), 0, dp(16), dp(24));
        col.addView(list, new LinearLayout.LayoutParams(-1, -2));
    }

    @Override protected void onResume() { super.onResume(); render(); }

    private String name(int i) { return prefs.getString("name" + i, "Account " + (i + 1)); }

    private void render() {
        list.removeAllViews();
        for (int i = 0; i < SLOTS.length; i++) {
            final int slot = i;
            LinearLayout row = card();
            row.setGravity(Gravity.CENTER_VERTICAL);

            String nm = name(i);
            TextView avatar = new TextView(this);
            avatar.setText(nm.substring(0, 1).toUpperCase());
            avatar.setTextColor(Color.WHITE);
            avatar.setTextSize(20);
            avatar.setTypeface(Typeface.DEFAULT_BOLD);
            avatar.setGravity(Gravity.CENTER);
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setColor(COLORS[i]);
            avatar.setBackground(circle);
            row.addView(avatar, new LinearLayout.LayoutParams(dp(48), dp(48)));

            LinearLayout mid = new LinearLayout(this);
            mid.setOrientation(LinearLayout.VERTICAL);
            mid.setPadding(dp(14), 0, dp(8), 0);
            mid.addView(text(nm, 17, getColor(R.color.text), true));
            long last = prefs.getLong("last" + i, 0);
            mid.addView(text(last == 0 ? "Tap to link a number"
                    : "Last opened " + DateUtils.getRelativeTimeSpanString(last, System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS), 13, getColor(R.color.subtext), false));
            row.addView(mid, new LinearLayout.LayoutParams(0, -2, 1));

            TextView edit = text("✎", 20, getColor(R.color.subtext), false);
            edit.setPadding(dp(12), dp(8), dp(12), dp(8));
            edit.setOnClickListener(v -> rename(slot));
            row.addView(edit);

            TextView open = text("Open", 14, Color.WHITE, true);
            open.setPadding(dp(18), dp(9), dp(18), dp(9));
            GradientDrawable pill = new GradientDrawable();
            pill.setCornerRadius(dp(20));
            pill.setColor(COLORS[i]);
            open.setBackground(pill);
            row.addView(open);

            View.OnClickListener go = v -> launch(slot);
            row.setOnClickListener(go);
            open.setOnClickListener(go);
            row.setOnLongClickListener(v -> { rename(slot); return true; });
            row.setOnTouchListener((v, e) -> {
                if (e.getAction() == MotionEvent.ACTION_DOWN) v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(80).start();
                else if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL)
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                return false;
            });

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.setMargins(0, dp(10), 0, 0);
            list.addView(row, lp);

            if (!animated) {               // staggered entrance, first time only
                row.setAlpha(0f);
                row.setTranslationY(dp(24));
                row.animate().alpha(1f).translationY(0).setStartDelay(120L + i * 70L).setDuration(350).start();
            }
        }
        animated = true;
    }

    private void launch(int slot) {
        prefs.edit().putLong("last" + slot, System.currentTimeMillis()).apply();
        startActivity(new Intent(this, SLOTS[slot]).putExtra("name", name(slot))
                .putExtra("color", COLORS[slot]).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private void rename(int slot) {
        EditText e = new EditText(this);
        e.setText(name(slot));
        e.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this).setTitle("Name this account").setView(e)
                .setPositiveButton("Save", (dlg, w) -> {
                    String n = e.getText().toString().trim();
                    prefs.edit().putString("name" + slot, n.isEmpty() ? "Account " + (slot + 1) : n).apply();
                    render();
                }).setNegativeButton("Cancel", null).show();
    }

    // ---- small view helpers
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
        c.setOrientation(LinearLayout.HORIZONTAL);
        c.setPadding(dp(16), dp(14), dp(14), dp(14));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(18));
        g.setColor(getColor(R.color.surface));
        g.setStroke(dp(1), getColor(R.color.stroke));
        c.setBackground(g);
        c.setElevation(dp(3));
        return c;
    }

    private LinearLayout step(String n, String s) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(10), 0, 0);
        TextView num = text(n, 12, Color.WHITE, true);
        num.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(getColor(R.color.primary));
        num.setBackground(g);
        r.addView(num, new LinearLayout.LayoutParams(dp(22), dp(22)));
        TextView t = text(s, 14, getColor(R.color.subtext), false);
        t.setPadding(dp(10), 0, 0, 0);
        r.addView(t);
        return r;
    }
}
