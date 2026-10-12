package com.subhub.app.censor.android;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.concurrent.atomic.AtomicInteger;

/** Synthetic native/Canvas content for real bound Accessibility qualification; debug source only. */
public final class BoundCensorFixtureActivity extends Activity {
    public static final int UNDERLYING = 0xff18ac58;
    public final AtomicInteger touches = new AtomicInteger();
    public View artwork;
    public TextView nativeText;
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setWindowAnimations(0);
        overridePendingTransition(0, 0);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);
        artwork = new View(this) {
            final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                canvas.drawColor(UNDERLYING);
                paint.setColor(Color.BLACK); paint.setTextSize(48);
                String text = "send nudes";
                if (paint.measureText(text) > getWidth() - 96) throw new IllegalStateException("Canvas fixture text overflow");
                canvas.drawText(text, 48, 150, paint);
            }
        };
        artwork.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        root.addView(artwork, new FrameLayout.LayoutParams(-1, -1));
        nativeText = new TextView(this);
        nativeText.setText("touch yourself"); nativeText.setTextColor(Color.BLACK); nativeText.setTextSize(24);
        nativeText.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        FrameLayout.LayoutParams text = new FrameLayout.LayoutParams(-2, -2);
        text.leftMargin = 48; text.topMargin = 280;
        root.addView(nativeText, text);
        root.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_UP) { touches.incrementAndGet(); view.performClick(); }
            return true;
        });
        setContentView(root);
    }
}
