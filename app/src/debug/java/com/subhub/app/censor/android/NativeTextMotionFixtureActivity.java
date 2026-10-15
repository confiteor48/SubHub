package com.subhub.app.censor.android;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import androidx.appcompat.widget.AppCompatTextView;
import java.util.concurrent.atomic.AtomicLong;

/** Ordinary synthetic TextViews and an explicitly owned paint parent; debug APKs only. */
public final class NativeTextMotionFixtureActivity extends Activity {
    public static final String CAPTION = "send nudes\n\nporn";
    public final AtomicLong contentGeneration = new AtomicLong();
    public AppCompatTextView moving, fixed;
    public ScrollView scroll;
    public Surface surface;
    public SnapshotCensorView probe;
    public volatile long commits, recordings;
    private FrameLayout content;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); getWindow().setWindowAnimations(0); overridePendingTransition(0, 0);
        if (getActionBar() != null) getActionBar().hide();
        surface = new Surface(); surface.setBackgroundColor(Color.WHITE);
        content = new FrameLayout(this); content.setMinimumHeight(3200);
        moving = caption(android.R.id.text1); content.addView(moving, captionParams(600));
        scroll = new ScrollView(this); scroll.setId(android.R.id.list);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER); scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(content, new ScrollView.LayoutParams(-1, 3200));
        surface.addView(scroll, new FrameLayout.LayoutParams(-1, 2000));
        fixed = caption(android.R.id.text2); surface.addView(fixed, captionParams(1400));
        setContentView(surface); getWindow().getDecorView().setClipToOutline(false); demand();
    }
    private AppCompatTextView caption(int id) {
        // No node-info delegate, setUniqueId call, custom extra-data provider or identity override.
        AppCompatTextView view = new AppCompatTextView(this); view.setId(id); view.setText(CAPTION);
        view.setTextSize(20); view.setTextColor(Color.BLACK); view.setPadding(0, 0, 0, 0);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES); return view;
    }
    private static FrameLayout.LayoutParams captionParams(int top) {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(800, -2); params.leftMargin = 32; params.topMargin = top;
        return params;
    }
    public void scrollTo(int position) { scroll.scrollTo(0, position); demand(); }
    public void replaceMoving() {
        contentGeneration.incrementAndGet(); content.removeView(moving); moving = caption(android.R.id.text1);
        content.addView(moving, captionParams(600)); demand();
    }
    public void safeContent() {
        contentGeneration.incrementAndGet(); moving.setText("ordinary safe caption"); fixed.setText("ordinary safe caption"); demand();
    }
    public void install(SnapshotCensorView view) {
        probe = view; view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        surface.addView(view, new FrameLayout.LayoutParams(-1, -1)); demand();
    }
    public void demand() {
        if (android.os.Build.VERSION.SDK_INT >= 29)
            surface.getViewTreeObserver().registerFrameCommitCallback(() -> commits++);
        surface.invalidate();
    }
    public final class Surface extends FrameLayout {
        Surface() { super(NativeTextMotionFixtureActivity.this); setClipToOutline(false); }
        @Override protected boolean drawChild(Canvas canvas, View child, long time) {
            if (child != probe) return super.drawChild(canvas, child, time);
            int saved = canvas.save();
            try {
                canvas.translate(child.getLeft(), child.getTop()); canvas.clipRect(0, 0, child.getWidth(), child.getHeight());
                probe.recordOwnedSurface(canvas); recordings++;
            } finally { canvas.restoreToCount(saved); }
            return true;
        }
    }
}
