package com.subhub.app.censor.android;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.widget.FrameLayout;

/** Unexported synthetic surface controller. No capture, model or Accessibility permission. */
public final class SnapshotViewFixtureActivity extends Activity {
    public Surface surface;
    public SnapshotCensorView probe;
    public volatile int commits, recordings;
    public volatile boolean hardware;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state); surface = new Surface(); surface.setBackgroundColor(Color.BLACK); setContentView(surface);
        // This synthetic controller explicitly owns a rectangular chain, not the theme's outline.
        getWindow().getDecorView().setClipToOutline(false);
    }
    public void install(SnapshotCensorView view) {
        probe = view;
        FrameLayout.LayoutParams layout = new FrameLayout.LayoutParams(320, 320);
        layout.leftMargin = 40; layout.topMargin = 50; surface.addView(view, layout); demand();
    }
    public void demand() {
        if (Build.VERSION.SDK_INT < 29) throw new IllegalStateException("Hardware fixture requires Android 10");
        surface.getViewTreeObserver().registerFrameCommitCallback(() -> commits++); surface.invalidate();
    }
    public final class Surface extends FrameLayout {
        Surface() { super(SnapshotViewFixtureActivity.this); }
        @Override protected void dispatchDraw(Canvas canvas) {
            hardware = canvas.isHardwareAccelerated();
            if (probe == null || probe.getParent() != this || probe.getVisibility() != VISIBLE) return;
            int saved = canvas.save();
            try {
                // Actual owned-parent dispatch; the child cannot infer this from a hardware flag.
                // Framework recording already accounts for this parent's scroll offset.
                canvas.translate(probe.getLeft(), probe.getTop());
                canvas.clipRect(0, 0, probe.getWidth(), probe.getHeight());
                probe.recordOwnedSurface(canvas); recordings++;
            } finally { canvas.restoreToCount(saved); }
        }
    }
}
