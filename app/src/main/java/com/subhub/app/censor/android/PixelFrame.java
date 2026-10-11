package com.subhub.app.censor.android;

import android.graphics.Bitmap;

import com.subhub.app.censor.engine.PixelGeometry;

import java.util.Objects;

/** Borrowed readable pixels and immutable capture metadata; only the runtime disposes pixels. */
public final class PixelFrame {
    public enum Kind { ACCESSIBILITY_WINDOW, ACCESSIBILITY_DISPLAY }

    public final Bitmap bitmap;
    public final PixelGeometry geometry;
    public final Kind kind;
    /** Platform API stamp, explicitly not an independently verified pixel-capture time. */
    public final long reportedUptimeMillis;
    /** Arrival on the callback executor, not the API-reported time or source-pixel clock. */
    public final long callbackUptimeMillis;
    public final long preparationNanos;

    public PixelFrame(Bitmap bitmap, PixelGeometry geometry, Kind kind,
            long reportedUptimeMillis, long callbackUptimeMillis, long preparationNanos) {
        this.bitmap = Objects.requireNonNull(bitmap);
        this.geometry = Objects.requireNonNull(geometry);
        this.kind = Objects.requireNonNull(kind);
        if (bitmap.isRecycled() || bitmap.getConfig() == Bitmap.Config.HARDWARE
                || bitmap.getWidth() != geometry.width || bitmap.getHeight() != geometry.height
                || reportedUptimeMillis < -1 || callbackUptimeMillis < 0 || preparationNanos < 0) throw new IllegalArgumentException("pixel frame");
        this.reportedUptimeMillis = reportedUptimeMillis;
        this.callbackUptimeMillis = callbackUptimeMillis;
        this.preparationNanos = preparationNanos;
    }
}
