package com.subhub.app.censor.android;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.FrameLayout;
import android.widget.TextView;

/** Synthetic real foreground target in the separate test package, not the protected product process. */
public final class ProductTargetFixtureActivity extends Activity {
    public static final int UNDERLYING = 0xff18ac58;
    private TextView label;
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); getWindow().setWindowAnimations(0); overridePendingTransition(0, 0);
        FrameLayout root = new FrameLayout(this); root.setBackgroundColor(UNDERLYING);
        label = new TextView(this); label.setTextColor(0xff000000); label.setTextSize(20);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-2, -2); params.topMargin = 120; params.leftMargin = 48;
        root.addView(label, params); setContentView(root); apply(getIntent());
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(this::finish, 60000);
    }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); apply(intent); }
    private void apply(Intent intent) {
        if (intent.getBooleanExtra("finish", false)) { finish(); return; }
        if (intent.getBooleanExtra("insetWindow", false)) {
            android.view.WindowManager.LayoutParams layout = getWindow().getAttributes();
            android.util.DisplayMetrics display = new android.util.DisplayMetrics();
            getWindowManager().getDefaultDisplay().getRealMetrics(display);
            layout.gravity = android.view.Gravity.TOP | android.view.Gravity.LEFT;
            layout.x = 40; layout.y = 80;
            layout.width = display.widthPixels - 80; layout.height = display.heightPixels - 160;
            getWindow().setAttributes(layout);
        }
        label.setText(intent.getStringExtra("label") == null ? "Target" : intent.getStringExtra("label"));
    }
}
