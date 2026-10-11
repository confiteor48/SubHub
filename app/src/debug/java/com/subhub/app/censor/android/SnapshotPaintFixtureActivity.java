package com.subhub.app.censor.android;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Build;
import android.view.View;
import android.widget.FrameLayout;

import com.subhub.app.censor.engine.CensorEngine;
import com.subhub.app.settings.CensorAppearance;

/** Unexported, synthetic attached-hardware lifetime fixture; never an Accessibility owner. */
public final class SnapshotPaintFixtureActivity extends Activity {
    public Probe probe;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        FrameLayout root = new FrameLayout(this); root.setBackgroundColor(Color.BLACK);
        probe = new Probe(); root.addView(probe, new FrameLayout.LayoutParams(320, 320)); setContentView(root);
    }
    public final class Probe extends View {
        public CensorEngine<PixelFrame> engine;
        public PreparedCensorPaint prepared;
        public CensorAppearance appearance;
        public long generation;
        public volatile int draws, painted, commits;
        public volatile boolean hardware;
        public final SnapshotCensorPainter painter = new SnapshotCensorPainter(1);
        Probe() { super(SnapshotPaintFixtureActivity.this); setWillNotDraw(false); }
        @Override protected void onDraw(Canvas canvas) {
            draws++; hardware = canvas.isHardwareAccelerated(); canvas.drawColor(Color.BLACK);
            if (engine == null || appearance == null) return;
            try (CensorEngine<PixelFrame>.RenderView view = engine.acquireRenderView()) {
                if (view != null) { painter.drawRectangularClip(canvas, view, prepared, appearance, generation, 0); painted++; }
            }
        }
        public void install(CensorEngine<PixelFrame> engine, PreparedCensorPaint prepared, CensorAppearance appearance, long generation) {
            this.engine = engine; this.prepared = prepared; this.appearance = appearance; this.generation = generation;
            if (Build.VERSION.SDK_INT < 29) throw new IllegalStateException("Hardware frame fixture requires Android 10");
            getViewTreeObserver().registerFrameCommitCallback(() -> commits++); invalidate();
        }
    }
}
