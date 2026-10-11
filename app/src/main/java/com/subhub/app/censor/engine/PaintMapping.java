package com.subhub.app.censor.engine;

/** Affine artwork mapping only. This neither estimates physical pose nor expands known support. */
public final class PaintMapping {
    public final SceneEngine.Region buffer;
    public final SceneEngine.Region sourceSupport;
    public final SceneEngine.Region destination;

    private PaintMapping(SceneEngine.Region buffer, SceneEngine.Region sourceSupport, SceneEngine.Region destination) {
        this.buffer = buffer; this.sourceSupport = sourceSupport; this.destination = destination;
    }

    public static PaintMapping sample(PixelGeometry pixels, SceneEngine.Region evidence, SceneEngine.Region display) {
        SceneEngine.Region buffer = pixels.toBuffer(evidence);
        if (buffer == null) return null;
        SceneEngine.Region support = buffer.translated(pixels.left, pixels.top);
        return new PaintMapping(buffer, support, map(evidence, display, support));
    }

    /** Map a supported subregion; caller clips painting to the requested destination. */
    public static SceneEngine.Region map(SceneEngine.Region source, SceneEngine.Region destination, SceneEngine.Region support) {
        double sx = (destination.right - destination.left) / (source.right - source.left);
        double sy = (destination.bottom - destination.top) / (source.bottom - source.top);
        return new SceneEngine.Region(destination.left + (support.left - source.left) * sx,
                destination.top + (support.top - source.top) * sy,
                destination.left + (support.right - source.left) * sx,
                destination.top + (support.bottom - source.top) * sy);
    }

    public static SceneEngine.Region padded(SceneEngine.Region region, float padding, boolean text) {
        double fraction = Float.isFinite(padding) ? Math.max(0, Math.min(text ? .025 : 1, padding)) : 0;
        double x = (region.right - region.left) * fraction, y = (region.bottom - region.top) * fraction;
        return new SceneEngine.Region(region.left - x, region.top - y, region.right + x, region.bottom + y);
    }
}
