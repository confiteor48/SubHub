package com.subhub.app.censor.android;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;

import com.subhub.app.censor.engine.CensorEngine;
import com.subhub.app.censor.engine.PaintMapping;
import com.subhub.app.censor.engine.SceneEngine;
import com.subhub.app.settings.CensorAppearance;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Off-UI preparation, then one drawing-thread owner. Contains no scene, tracking or capture owner.
 * Input/artwork leases must remain held throughout prepare; input Bitmaps are never submitted to
 * hardware. Prepared Bitmaps are immutable. Once recorded by hardware they are never manually
 * recycled: Android's recorded-draw/native references own their backing lifetime after retirement.
 * close drops managed ownership, NOT proof that an in-flight GPU allocation has been disposed.
 */
public final class PreparedCensorPaint implements AutoCloseable {
    public static final int DEFAULT_MAXIMUM_BYTES = 32 * 1024 * 1024;
    public final long epoch, appearanceGeneration;
    public final CensorAppearance appearance;
    private final Map<Long, Tile> members = new HashMap<>();
    private final List<Bitmap> artwork = new ArrayList<>();
    private Tile canvas;
    private Bitmap noise;
    private int bytes;
    private boolean hardwareRecorded, closed;

    static final class Tile {
        final SceneEngine.Source source;
        final SceneEngine.Region evidence, support;
        final Bitmap bitmap;
        Tile(SceneEngine.Source source, SceneEngine.Region evidence, SceneEngine.Region support, Bitmap bitmap) {
            this.source = source; this.evidence = evidence; this.support = support; this.bitmap = bitmap;
        }
    }

    private PreparedCensorPaint(long epoch, long generation, CensorAppearance appearance) {
        this.epoch = epoch; appearanceGeneration = generation; this.appearance = Objects.requireNonNull(appearance);
    }

    /** Borrowed custom images must be stable under their own loader lease; this copies, never adopts. */
    public static PreparedCensorPaint prepare(CensorEngine<PixelFrame>.RenderView view,
            CensorAppearance appearance, long generation, List<Bitmap> customImages, int maximumBytes) {
        Objects.requireNonNull(view); Objects.requireNonNull(customImages);
        int customCount = customImages.size();
        if (generation < 0 || maximumBytes < 16384 || maximumBytes > 64 * 1024 * 1024
                || customCount < 0 || customCount > 64) throw new IllegalArgumentException("paint preparation budget");
        PreparedCensorPaint result = new PreparedCensorPaint(view.snapshot.destination.epoch, generation, appearance);
        Budget budget = new Budget(maximumBytes);
        try {
            CensorAppearance.Type type = effectiveType(appearance);
            if (type == CensorAppearance.Type.STATIC || type == CensorAppearance.Type.GLITCH) result.noise = noise(appearance, budget);
            boolean pixels = type == CensorAppearance.Type.PIXELATE || type == CensorAppearance.Type.BLUR
                    || type == CensorAppearance.Type.GLITCH || type == CensorAppearance.Type.TAPE;
            if (appearance.isReverseMode()) {
                if (pixels && view.canvasSource != null && view.canvasPixels() != null) {
                    SceneEngine.Source source = view.canvasSource;
                    result.canvas = tile(source, new SceneEngine.Region(0, 0, source.width, source.height),
                            view.canvasPixels(), type, appearance.getReverseStrength(), budget);
                }
            } else if (pixels) {
                for (SceneEngine.Group group : view.snapshot.groups) for (SceneEngine.Mask mask : group.members) {
                    PixelFrame frame = view.sourcePixels(mask.evidenceSource.sequence);
                    if (frame == null) continue;
                    SceneEngine.Region requested = PaintMapping.padded(mask.evidenceRegion,
                            appearance.getSizePadding(), isText(mask));
                    Tile tile = tile(mask.evidenceSource, requested, frame, type, appearance.getIntensity(), budget);
                    if (tile != null) result.members.put(mask.identity, tile);
                }
            }
            if (type == CensorAppearance.Type.CUSTOM) for (int index = 0; index < customCount; index++) {
                Bitmap image = customImages.get(index);
                if (image == null || image.isRecycled() || image.getConfig() == Bitmap.Config.HARDWARE) continue;
                long size = 4L * image.getWidth() * image.getHeight();
                if (!budget.take(size)) break;
                Bitmap copy = null;
                try { copy = image.copy(Bitmap.Config.ARGB_8888, false); }
                catch (RuntimeException | OutOfMemoryError unavailable) { /* Optional image stays absent. */ }
                if (copy != null) result.artwork.add(copy); else budget.give(size);
            }
            if (customImages.size() != customCount) throw new IllegalArgumentException("artwork generation changed");
            result.bytes = budget.used; return result;
        } catch (RuntimeException | Error failure) { result.close(); throw failure; }
    }

    private static Tile tile(SceneEngine.Source source, SceneEngine.Region requested, PixelFrame frame,
            CensorAppearance.Type type, int intensity, Budget budget) {
        if (frame.geometry.displayWidth != source.width || frame.geometry.displayHeight != source.height) return null;
        PaintMapping sample = PaintMapping.sample(frame.geometry, requested, requested);
        if (sample == null) return null;
        Rect crop = new Rect((int) Math.floor(sample.buffer.left), (int) Math.floor(sample.buffer.top),
                (int) Math.ceil(sample.buffer.right), (int) Math.ceil(sample.buffer.bottom));
        int divisor = 1, clamped = Math.max(1, Math.min(100, intensity));
        if (type == CensorAppearance.Type.BLUR) divisor = Math.max(2, Math.round(2 + (clamped - 1) / 99f * 18));
        else if (type == CensorAppearance.Type.PIXELATE) {
            int minimum = Math.min(crop.width(), crop.height()); float fraction = (clamped - 1) / 99f;
            int low = Math.max(3, Math.round(minimum * .025f));
            int high = Math.max(low + 1, Math.round(minimum * .45f));
            divisor = Math.max(2, Math.min(minimum, Math.round(low + (high - low) * fraction * fraction)));
        }
        int width = Math.max(1, crop.width() / divisor), height = Math.max(1, crop.height() / divisor);
        long size = 4L * width * height;
        // The mutable software-only intermediate and immutable result coexist during the copy.
        if (!budget.take(size * 2)) return null;
        Bitmap intermediate = null, immutable = null;
        try {
            intermediate = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            new Canvas(intermediate).drawBitmap(frame.bitmap, crop, new Rect(0, 0, width, height),
                    new Paint(Paint.FILTER_BITMAP_FLAG));
            immutable = intermediate.copy(Bitmap.Config.ARGB_8888, false);
            if (immutable == null) return null;
            return new Tile(source, requested, new SceneEngine.Region(frame.geometry.left + crop.left,
                    frame.geometry.top + crop.top, frame.geometry.left + crop.right, frame.geometry.top + crop.bottom), immutable);
        } catch (RuntimeException | OutOfMemoryError unavailable) { return null; }
        finally {
            if (intermediate != null) intermediate.recycle();
            budget.give(immutable == null ? size * 2 : size);
        }
    }

    private static Bitmap noise(CensorAppearance appearance, Budget budget) {
        // The int input array and immutable Bitmap coexist; account for both, not only the result.
        if (!budget.take(32768)) return null;
        int[] colors = new int[4096]; long seed = 0x5bd1e995L;
        float contrast = .35f + Math.max(1, appearance.isReverseMode() ? appearance.getReverseStrength() : appearance.getIntensity()) / 100f * .65f;
        for (int index = 0; index < colors.length; index++) {
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            float level = (128 * (1 - contrast) + (seed >>> 56) * contrast) / 255f;
            colors[index] = SnapshotCensorPainter.blend(appearance.getEffectPalette().first(),
                    appearance.getEffectPalette().second(), level);
        }
        Bitmap bitmap = null;
        try { bitmap = Bitmap.createBitmap(colors, 64, 64, Bitmap.Config.ARGB_8888); return bitmap; }
        catch (RuntimeException | OutOfMemoryError unavailable) { return null; }
        finally { budget.give(bitmap == null ? 32768 : 16384); }
    }

    static CensorAppearance.Type effectiveType(CensorAppearance appearance) {
        CensorAppearance.Type type = appearance.getType();
        return appearance.isReverseMode() && (type == CensorAppearance.Type.BOX || type == CensorAppearance.Type.CUSTOM)
                ? CensorAppearance.Type.PIXELATE : type;
    }
    static boolean isText(SceneEngine.Mask mask) {
        return mask.nativeValidated || mask.textGroup != 0 || "TEXT_SMUT".equalsIgnoreCase(mask.category);
    }
    static boolean same(SceneEngine.Region a, SceneEngine.Region b) {
        return a.left == b.left && a.top == b.top && a.right == b.right && a.bottom == b.bottom;
    }
    boolean compatible(CensorEngine<PixelFrame>.RenderView view, long generation) {
        return !closed && epoch == view.snapshot.destination.epoch && appearanceGeneration == generation;
    }
    Tile member(SceneEngine.Mask mask) {
        Tile tile = members.get(mask.identity);
        SceneEngine.Region requested = PaintMapping.padded(mask.evidenceRegion, appearance.getSizePadding(), isText(mask));
        return tile != null && tile.source == mask.evidenceSource && same(tile.evidence, requested) ? tile : null;
    }
    Tile canvas() { return canvas; }
    Bitmap noise() { return noise; }
    Bitmap artwork(long identity) { return artwork.isEmpty() ? null : artwork.get((int) Math.floorMod(identity, (long) artwork.size())); }
    void hardwareRecorded() { if (closed) throw new IllegalStateException("closed paint"); hardwareRecorded = true; }
    public int allocationBytes() { return bytes; }
    public boolean isClosed() { return closed; }
    @Override public void close() {
        if (closed) return;
        closed = true;
        if (!hardwareRecorded) {
            for (Tile tile : members.values()) tile.bitmap.recycle();
            if (canvas != null) canvas.bitmap.recycle();
            if (noise != null) noise.recycle();
            for (Bitmap bitmap : artwork) bitmap.recycle();
        }
        members.clear(); artwork.clear(); canvas = null; noise = null; bytes = 0;
    }
    private static final class Budget {
        final int maximum; int used;
        Budget(int maximum) { this.maximum = maximum; }
        boolean take(long size) { if (size < 0 || size > maximum - used) return false; used += (int) size; return true; }
        void give(long size) { used -= (int) size; }
    }
}
