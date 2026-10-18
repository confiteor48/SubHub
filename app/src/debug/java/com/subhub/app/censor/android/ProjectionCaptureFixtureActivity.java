package com.subhub.app.censor.android;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;

/** Explicit, fresh system consent for the debug source fixture, never product capture settings. */
public final class ProjectionCaptureFixtureActivity extends Activity {
    private static final int CONSENT = 1;
    static volatile ProjectionCaptureFixtureActivity current;
    String runId;
    volatile boolean denied;
    private boolean requested;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        runId = getIntent().getStringExtra("runId");
        if (android.os.Build.VERSION.SDK_INT < 34 || runId == null || saved != null) {
            denied = true;
            finish();
            return;
        }
        showMarkers(this, false);
        current = this;
        // The grant belongs only to the service below; never pass the recorder's consumed grant.
        requested = true;
        startActivityForResult(getSystemService(MediaProjectionManager.class).createScreenCaptureIntent(
                MediaProjectionConfig.createConfigForDefaultDisplay()), CONSENT);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != CONSENT || !requested) return;
        requested = false;
        if (result != RESULT_OK || data == null) { denied = true; return; }
        startForegroundService(new Intent(this, ProjectionCaptureFixtureService.class)
                .putExtra("runId", runId).putExtra("resultCode", result).putExtra("grant", data));
    }

    @Override protected void onDestroy() {
        if (current == this) current = null;
        super.onDestroy();
    }

    /** Full-window, opaque synthetic witness. Other-UID test activity uses the other palette. */
    @androidx.annotation.RequiresApi(30)
    public static void showMarkers(Activity activity, boolean alternate) {
        activity.getWindow().setDecorFitsSystemWindows(false);
        WindowManager.LayoutParams params = activity.getWindow().getAttributes();
        params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        activity.getWindow().setAttributes(params);
        activity.setContentView(new MarkerView(activity, alternate));
    }

    public static final class MarkerView extends View {
        private final Paint paint = new Paint();
        private final boolean alternate;
        private int centerColor;
        public MarkerView(Context context, boolean alternate) {
            super(context); this.alternate = alternate; centerColor = alternate ? Color.MAGENTA : Color.GREEN;
            setContentDescription("Projection initial source");
        }
        public void centerColor(int color) {
            if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) throw new IllegalStateException("main marker mutation");
            centerColor = color; invalidate();
        }
        @Override protected void onDraw(Canvas canvas) {
            canvas.drawColor(Color.BLACK);
            int[] parts = {1,3,2};
            int[] colors = alternate ? new int[]{Color.CYAN,Color.YELLOW,Color.MAGENTA}
                    : new int[]{Color.RED,Color.BLUE,Color.GREEN};
            colors[2] = centerColor;
            for (int i = 0; i < parts.length; i++) {
                int x = getWidth() * parts[i] / 4, y = getHeight() * parts[i] / 4;
                paint.setColor(colors[i]); canvas.drawRect(x - 8, y - 8, x + 8, y + 8, paint);
            }
        }
    }
}
