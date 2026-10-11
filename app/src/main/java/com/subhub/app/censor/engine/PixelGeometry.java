package com.subhub.app.censor.engine;

import com.subhub.app.detection.BBox;

/**
 * An explicit capture-buffer rectangle in physical display coordinates, not a guessed scale.
 * Factories check dimensional consistency; the platform must separately qualify the supplied origin.
 */
public final class PixelGeometry {
    public final int displayWidth;
    public final int displayHeight;
    public final int left;
    public final int top;
    public final int width;
    public final int height;

    private PixelGeometry(int displayWidth, int displayHeight, int left, int top, int width, int height) {
        this.displayWidth = displayWidth;
        this.displayHeight = displayHeight;
        this.left = left;
        this.top = top;
        this.width = width;
        this.height = height;
    }

    public static PixelGeometry display(int displayWidth, int displayHeight, int bufferWidth, int bufferHeight) {
        return window(displayWidth, displayHeight, 0, 0, displayWidth, displayHeight, bufferWidth, bufferHeight);
    }

    /** Null means inconsistent dimensions. A value alone does not prove the window's pixel origin. */
    public static PixelGeometry window(int displayWidth, int displayHeight, int left, int top,
            int width, int height, int bufferWidth, int bufferHeight) {
        if (displayWidth <= 0 || displayHeight <= 0 || left < 0 || top < 0 || width <= 0 || height <= 0
                || (long) left + width > displayWidth || (long) top + height > displayHeight
                || bufferWidth != width || bufferHeight != height) return null;
        return new PixelGeometry(displayWidth, displayHeight, left, top, width, height);
    }

    public SceneEngine.Region coverage() {
        return new SceneEngine.Region(left, top, (double) left + width, (double) top + height);
    }

    /** Map an already decoded buffer-local observation; unsupported/empty geometry stays absent. */
    public SceneEngine.Region toDisplay(BBox box) {
        if (box == null || box.getWidth() <= 0 || box.getHeight() <= 0) return null;
        long x1 = Math.max(0L, box.getX());
        long y1 = Math.max(0L, box.getY());
        long x2 = Math.min((long) width, (long) box.getX() + box.getWidth());
        long y2 = Math.min((long) height, (long) box.getY() + box.getHeight());
        if (x2 <= x1 || y2 <= y1) return null;
        return new SceneEngine.Region(left + x1, top + y1, left + x2, top + y2);
    }

    /** Sampling the evidence source requires the inverse of its own capture mapping. */
    public SceneEngine.Region toBuffer(SceneEngine.Region displayRegion) {
        if (displayRegion == null) return null;
        double x1 = Math.max(0, displayRegion.left - left);
        double y1 = Math.max(0, displayRegion.top - top);
        double x2 = Math.min(width, displayRegion.right - left);
        double y2 = Math.min(height, displayRegion.bottom - top);
        return x2 > x1 && y2 > y1 ? new SceneEngine.Region(x1, y1, x2, y2) : null;
    }
}
