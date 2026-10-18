package com.subhub.app.censor.android;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.ResultReceiver;
import android.view.View;
import android.view.WindowManager;

/** Standalone other-UID witness; does not load target-app classes or own projection permission. */
@androidx.test.filters.SdkSuppress(minSdkVersion = 34)
public final class ProjectionTargetFixtureActivity extends Activity {
    private View marker;
    private boolean pulse;
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setDecorFitsSystemWindows(false);
        WindowManager.LayoutParams params = getWindow().getAttributes();
        params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        getWindow().setAttributes(params);
        marker = new View(this) {
            private final Paint paint = new Paint();
            @Override protected void onDraw(Canvas canvas) {
                canvas.drawColor(Color.BLACK);
                int[] parts = {1,3,2};
                int[] colors = {Color.CYAN,Color.YELLOW,pulse ? Color.WHITE : Color.MAGENTA};
                for (int i = 0; i < parts.length; i++) {
                    int x = getWidth() * parts[i] / 4, y = getHeight() * parts[i] / 4;
                    paint.setColor(colors[i]); canvas.drawRect(x - 8, y - 8, x + 8, y + 8, paint);
                }
            }
        };
        marker.setContentDescription("Projection alternate source");
        setContentView(marker);
        command(getIntent());
    }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); command(intent); }
    private void command(Intent intent) {
        if (intent.getBooleanExtra("finish", false)) finish();
        else if (intent.getBooleanExtra("landscape", false)) setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        else if (intent.getBooleanExtra("pulse", false)) { pulse = !pulse; marker.invalidate(); }
        else if (intent.getBooleanExtra("redraw", false)) {
            ResultReceiver reply = intent.getParcelableExtra("commit", ResultReceiver.class);
            // onNewIntent can precede first attachment; View.post follows that actual readiness.
            marker.post(() -> {
                if (reply != null && marker.isHardwareAccelerated()) {
                    marker.getViewTreeObserver().registerFrameCommitCallback(() -> marker.post(() -> {
                        int[] location = new int[2]; marker.getLocationOnScreen(location);
                        Bundle bounds = new Bundle(); bounds.putInt("uid", android.os.Process.myUid());
                        bounds.putInt("left", location[0]); bounds.putInt("top", location[1]);
                        bounds.putInt("right", location[0] + marker.getWidth()); bounds.putInt("bottom", location[1] + marker.getHeight());
                        reply.send(1, bounds);
                    }));
                } else if (reply != null) reply.send(0, Bundle.EMPTY);
                marker.invalidate();
            });
        }
    }
}
