package com.multiweb.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {
    static final Class<?>[] SLOTS = {Slot1.class, Slot2.class, Slot3.class, Slot4.class, Slot5.class, Slot6.class};
    private SharedPreferences prefs;
    private LinearLayout list;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("accounts", MODE_PRIVATE);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);

        TextView help = new TextView(this);
        help.setText("Each account below is a separate, isolated web session.\n\nTap Open, then on the phone that "
                + "owns that number: WhatsApp → Linked devices → Link a device, and scan the QR code. "
                + "Sessions stay logged in.");
        help.setTextSize(14);
        root.addView(help);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list);
        render();
    }

    private String name(int i) { return prefs.getString("name" + i, "Account " + (i + 1)); }

    private void render() {
        list.removeAllViews();
        for (int i = 0; i < SLOTS.length; i++) {
            final int slot = i;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, 24, 0, 8);
            TextView t = new TextView(this);
            t.setText(name(i));
            t.setTextSize(18);
            t.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            Button open = new Button(this);
            open.setText("Open");
            open.setOnClickListener(v -> startActivity(new Intent(this, SLOTS[slot])
                    .putExtra("name", name(slot)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
            Button rename = new Button(this);
            rename.setText("Rename");
            rename.setOnClickListener(v -> rename(slot));
            row.addView(t);
            row.addView(open);
            row.addView(rename);
            list.addView(row);
        }
    }

    private void rename(int slot) {
        EditText e = new EditText(this);
        e.setText(name(slot));
        new AlertDialog.Builder(this).setTitle("Account name").setView(e)
                .setPositiveButton("Save", (d, w) -> {
                    String n = e.getText().toString().trim();
                    prefs.edit().putString("name" + slot, n.isEmpty() ? "Account " + (slot + 1) : n).apply();
                    render();
                }).setNegativeButton("Cancel", null).show();
    }
}
