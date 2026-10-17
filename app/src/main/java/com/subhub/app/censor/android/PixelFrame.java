package com.subhub.app.censor.android;

import android.graphics.Bitmap;

import com.subhub.app.censor.engine.PixelGeometry;

import java.util.Objects;

/** Borrowed readable pixels and immutable capture metadata; only the runtime disposes pixels. */
public final class PixelFrame {
    public enum Kind { ACCESSIBILITY_WINDOW, ACCESSIBILITY_DISPLAY, PROJECTION_DISPLAY }

    public final Bitmap bitmap;
    public final PixelGeometry geometry;
    public final Kind kind;
    /** Platform API stamp, explicitly not an independently verified pixel-capture time. */
    public final long reportedUptimeMillis;
    /** Arrival on the callback executor, not the API-reported time or source-pixel clock. */
    public final long callbackUptimeMillis;
    public final long preparationNanos;
    /** Projection-only source metadata. Neither value establishes a pixel clock or pixel novelty. */
    public final long acquisitionSequence;
    public final long reportedTimestampNanos;

    public PixelFrame(Bitmap bitmap, PixelGeometry geometry, Kind kind,
            long reportedUptimeMillis, long callbackUptimeMillis, long preparationNanos) {
        this(bitmap, geometry, kind, reportedUptimeMillis, callbackUptimeMillis, preparationNanos, -1, -1);
    }

    public static PixelFrame projection(Bitmap bitmap, PixelGeometry geometry, long sequence,
            long reportedTimestampNanos, long arrivalUptimeMillis, long preparationNanos) {
        return new PixelFrame(bitmap, geometry, Kind.PROJECTION_DISPLAY, -1, arrivalUptimeMillis,
                preparationNanos, sequence, reportedTimestampNanos);
    }

    private PixelFrame(Bitmap bitmap, PixelGeometry geometry, Kind kind, long reportedUptimeMillis,
            long callbackUptimeMillis, long preparationNanos, long acquisitionSequence, long reportedTimestampNanos) {
        this.bitmap = Objects.requireNonNull(bitmap);
        this.geometry = Objects.requireNonNull(geometry);
        this.kind = Objects.requireNonNull(kind);
        if (bitmap.isRecycled() || bitmap.getConfig() == Bitmap.Config.HARDWARE
                || bitmap.getWidth() != geometry.width || bitmap.getHeight() != geometry.height
                || reportedUptimeMillis < -1 || callbackUptimeMillis < 0 || preparationNanos < 0
                || (kind == Kind.PROJECTION_DISPLAY && (reportedUptimeMillis != -1 || acquisitionSequence <= 0 || reportedTimestampNanos < -1))) {
            throw new IllegalArgumentException("pixel frame");
        }
        this.reportedUptimeMillis = reportedUptimeMillis;
        this.callbackUptimeMillis = callbackUptimeMillis;
        this.preparationNanos = preparationNanos;
        this.acquisitionSequence = acquisitionSequence;
        this.reportedTimestampNanos = reportedTimestampNanos;
    }
}
