package com.cloudbackup.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
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
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(pad, pad, pad, pad);
        setContentView(root);

        TextView title = new TextView(this);
        title.setText("Cloud Backup is locked");
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Enter your PIN to continue");
        sub.setPadding(0, 8, 0, 32);
        sub.setGravity(Gravity.CENTER);
        root.addView(sub);

        pinField = new EditText(this);
        pinField.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pinField.setGravity(Gravity.CENTER);
        pinField.setTextSize(24);
        root.addView(pinField, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        Button unlock = new Button(this);
        unlock.setText("Unlock");
        unlock.setOnClickListener(v -> tryUnlock());
        root.addView(unlock, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
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
