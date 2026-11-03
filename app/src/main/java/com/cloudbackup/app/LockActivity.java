package com.cloudbackup.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** The app's launcher activity. Asks for the PIN (if one is set) before handing off to MainActivity. */
public class LockActivity extends Activity {
    private Settings st;
    private EditText pinField;
    private int attempts = 0;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        st = new Settings(this);
        if (!st.pinSet() || LockState.isUnlocked()) { proceed(); return; }
        buildUi();
    }

    @Override protected void onResume() {
        super.onResume();
        if (!st.pinSet() || LockState.isUnlocked()) proceed();
    }

    private void buildUi() {
        float d = getResources().getDisplayMetrics().density;
        int pad = (int) (24 * d);

        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{getColor(R.color.primary_dark), getColor(R.color.accent)});
        outer.setBackground(bg);
        setContentView(outer);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setPadding(pad, pad, pad, pad);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setCornerRadius(22 * d);
        cardBg.setColor(getColor(R.color.surface));
        card.setBackground(cardBg);
        outer.addView(card, new LinearLayout.LayoutParams((int) (320 * d), LinearLayout.LayoutParams.WRAP_CONTENT));

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_lock);
        icon.setColorFilter(getColor(R.color.primary));
        card.addView(icon, new LinearLayout.LayoutParams((int) (40 * d), (int) (40 * d)));

        TextView title = new TextView(this);
        title.setText("Cloud Backup is locked");
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(getColor(R.color.text));
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, (int) (12 * d), 0, 0);
        card.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Enter your PIN to continue");
        sub.setTextColor(getColor(R.color.subtext));
        sub.setPadding(0, 6, 0, (int) (24 * d));
        sub.setGravity(Gravity.CENTER);
        card.addView(sub);

        pinField = new EditText(this);
        pinField.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pinField.setGravity(Gravity.CENTER);
        pinField.setTextSize(24);
        pinField.setTextColor(getColor(R.color.text));
        GradientDrawable fieldBg = new GradientDrawable();
        fieldBg.setCornerRadius(10 * d);
        fieldBg.setColor(getColor(R.color.bg));
        fieldBg.setStroke((int) d, getColor(R.color.stroke));
        pinField.setBackground(fieldBg);
        pinField.setPadding(0, (int) (10 * d), 0, (int) (10 * d));
        card.addView(pinField, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        Button unlock = new Button(this);
        unlock.setText("Unlock");
        unlock.setAllCaps(false);
        unlock.setTextColor(Color.WHITE);
        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setCornerRadius(24 * d);
        btnBg.setColor(getColor(R.color.primary));
        unlock.setBackground(btnBg);
        unlock.setOnClickListener(v -> tryUnlock());
        LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (int) (48 * d));
        ulp.setMargins(0, (int) (16 * d), 0, 0);
        card.addView(unlock, ulp);
        pinField.setOnEditorActionListener((v, actionId, e) -> { tryUnlock(); return true; });
    }

    private void tryUnlock() {
        String pin = pinField.getText().toString();
        if (PinUtil.matches(pin, st.pinSalt(), st.pinHash())) {
            attempts = 0;
            LockState.setUnlocked(true);
            proceed();
        } else {
            attempts++;
            pinField.setText("");
            Toast.makeText(this, "Wrong PIN", Toast.LENGTH_SHORT).show();
        }
    }

    private void proceed() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    @Override public void onBackPressed() {
        moveTaskToBack(true); // leaving the lock screen with Back exits to the home screen, not into the app
    }
}
