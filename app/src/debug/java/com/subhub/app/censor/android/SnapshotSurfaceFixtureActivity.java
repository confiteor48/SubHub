package com.subhub.app.censor.android;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;

/** Unexported actual-holder fixture over contrasting synthetic content; no live capture or models. */
public final class SnapshotSurfaceFixtureActivity extends Activity {
    public FrameLayout root;
    public SnapshotCensorSurface probe;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); root = new FrameLayout(this);
        root.addView(new Checker(), new FrameLayout.LayoutParams(-1, -1)); setContentView(root);
        getWindow().getDecorView().setClipToOutline(false);
    }
    public void install(SnapshotCensorSurface surface) {
        probe = surface; FrameLayout.LayoutParams layout = new FrameLayout.LayoutParams(320, 320);
        layout.leftMargin = 40; layout.topMargin = 50; root.addView(surface, layout);
    }
    public static int checkerAt(int x, int y) { return (((x / 24) + (y / 24)) & 1) == 0 ? Color.YELLOW : Color.CYAN; }
    private final class Checker extends View {
        private final Paint paint = new Paint();
        Checker() { super(SnapshotSurfaceFixtureActivity.this); }
        @Override protected void onDraw(Canvas canvas) {
            for (int y = 0; y < getHeight(); y += 24) for (int x = 0; x < getWidth(); x += 24) {
                paint.setColor(checkerAt(x, y)); canvas.drawRect(x, y, x + 24, y + 24, paint);
            }
        }
    }
}
