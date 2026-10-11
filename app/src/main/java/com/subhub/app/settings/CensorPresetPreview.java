package com.subhub.app.settings;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import com.subhub.app.capture.CensorRenderer;
import com.subhub.app.detection.BBox;
import com.subhub.app.detection.Detection;
import java.util.Collections;

/** One cached synthetic sample per visible preset. Never loads personal custom-image assets. */
public final class CensorPresetPreview extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF destination = new RectF();
    private final CensorAppearance appearance;
    private Bitmap rendered;

    public CensorPresetPreview(Context context, CensorAppearance appearance) {
        super(context);
        this.appearance = appearance;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (getWidth() <= 0 || getHeight() <= 0) return;
        if (rendered == null) {
            Bitmap source = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888);
            source.eraseColor(Color.rgb(61, 43, 75));
            Canvas sample = new Canvas(source);
            for (int y = 0; y < 180; y += 20) for (int x = 0; x < 320; x += 20) {
                paint.setColor((x + y) % 40 == 0 ? 0xff906b9f : 0xff5d426d);
                sample.drawRect(x, y, x + 20, y + 20, paint);
            }
            rendered = source.copy(Bitmap.Config.ARGB_8888, true);
            try (CensorRenderer renderer = new CensorRenderer(getContext(), Collections.emptyList())) {
                renderer.drawAtTime(rendered, source, Collections.singletonList(
                        new Detection("preset", "preset", 1f, new BBox(30, 30, 260, 120), false, false)),
                        appearance, 1800);
            } finally { source.recycle(); }
        }
        destination.set(0, 0, getWidth(), getHeight());
        canvas.drawBitmap(rendered, null, destination, paint);
    }

    @Override protected void onDetachedFromWindow() {
        if (rendered != null) { rendered.recycle(); rendered = null; }
        super.onDetachedFromWindow();
    }
}
