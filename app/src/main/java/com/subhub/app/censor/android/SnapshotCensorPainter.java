package com.subhub.app.censor.android;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.Typeface;

import com.subhub.app.censor.engine.CensorEngine;
import com.subhub.app.censor.engine.PaintMapping;
import com.subhub.app.censor.engine.ProtectionScope;
import com.subhub.app.censor.engine.SceneEngine;
import com.subhub.app.overlay.CensorLabelLayout;
import com.subhub.app.settings.CensorAppearance;
import com.subhub.app.settings.LockpadArtwork;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Paints current immutable owner geometry. No retention, camera, association or scene mutation. */
public final class SnapshotCensorPainter implements AutoCloseable {
    /** A presentation estimate only; it neither changes evidence nor grants tap coverage. */
    public static final class Pose {
        public final SceneEngine.Region region, clip;
        public Pose(SceneEngine.Region region, SceneEngine.Region clip) {
            this.region = java.util.Objects.requireNonNull(region);
            this.clip = java.util.Objects.requireNonNull(clip);
        }
    }
    public interface Presentation { Pose pose(SceneEngine.Mask mask, long timeMillis); }
    /** Actual recorded member coverage; the attached View still owns visible/scope/tap validation. */
    public static final class PaintedMember {
        public final long identity;
        public final ProtectionScope protectionScope;
        public final List<SceneEngine.Region> tapInteriors;
        public final SurfaceSubmission.Member recording;
        private PaintedMember(SceneEngine.Mask mask, List<SceneEngine.Region> interiors, SurfaceSubmission.Member recording) {
            identity = mask.identity; protectionScope = mask.evidenceSource.protectionScope;
            tapInteriors = Collections.unmodifiableList(interiors);
            this.recording = recording;
        }
    }
    public static final class Result {
        public final List<PaintedMember> members;
        public final boolean reverseBackground;
        public final boolean rectangularTapCoverageQualified;
        /** Recording a draw is deliberately NOT a compositor/pixel-arrival acknowledgement. */
        private Result(List<PaintedMember> members, boolean reverse, boolean rectangularClip) {
            this.members = Collections.unmodifiableList(members); reverseBackground = reverse;
            rectangularTapCoverageQualified = rectangularClip && !reverse;
        }
    }

    private final float density;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG), filtered = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint nearest = new Paint(), shifted = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint clear = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();
    private final LockpadArtwork lockpad = new LockpadArtwork();
    private Bitmap cachedNoise;
    private BitmapShader noiseShader;
    private boolean closed;

    public SnapshotCensorPainter(float density) {
        if (!Float.isFinite(density) || density <= 0) throw new IllegalArgumentException("density");
        this.density = density; border.setStyle(Paint.Style.STROKE);
        text.setTextAlign(Paint.Align.CENTER); text.setTypeface(Typeface.DEFAULT_BOLD);
        clear.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
    }

    /** Caller must acquire a fresh RenderView for EVERY draw, not replay a prepared scene. */
    public Result draw(Canvas canvas, CensorEngine<PixelFrame>.RenderView view, PreparedCensorPaint prepared,
            CensorAppearance appearance, long generation, long artworkTimeMillis) {
        return draw(canvas, view, prepared, appearance, generation, artworkTimeMillis, false, null);
    }

    /**
     * Only for a caller-owned rectangular surface/clip chain, without path/outline/shader holes.
     * Canvas.getClipBounds alone cannot establish this contract. Unknown clips use draw instead,
     * whose rendered members deliberately carry NO tap-attribution interiors.
     */
    public Result drawRectangularClip(Canvas canvas, CensorEngine<PixelFrame>.RenderView view, PreparedCensorPaint prepared,
            CensorAppearance appearance, long generation, long artworkTimeMillis) {
        return draw(canvas, view, prepared, appearance, generation, artworkTimeMillis, true, null);
    }

    public Result draw(Canvas canvas, CensorEngine<PixelFrame>.RenderView view, PreparedCensorPaint prepared,
            CensorAppearance appearance, long generation, long artworkTimeMillis, boolean rectangularClip,
            Presentation presentation) {
        return draw(canvas, view, prepared, appearance, generation, artworkTimeMillis, rectangularClip, presentation, false);
    }
    Result draw(Canvas canvas, CensorEngine<PixelFrame>.RenderView view, PreparedCensorPaint prepared,
            CensorAppearance appearance, long generation, long artworkTimeMillis, boolean rectangularClip,
            Presentation presentation, boolean recordGeometry) {
        if (closed) throw new IllegalStateException("closed painter");
        boolean valid = prepared != null && prepared.appearance == appearance && prepared.compatible(view, generation);
        if (valid && canvas.isHardwareAccelerated()) prepared.hardwareRecorded();
        long time = Math.max(0, artworkTimeMillis);
        List<SceneEngine.Mask> visible = new ArrayList<>(); List<RectF> rects = new ArrayList<>();
        List<RectF> ownerClips = new ArrayList<>();
        Rect clip = canvas.getClipBounds();
        for (SceneEngine.Group group : view.snapshot.groups) for (SceneEngine.Mask mask : group.members) {
            Pose pose = presentation == null || appearance.isReverseMode() ? null : presentation.pose(mask, time);
            RectF rect = rect(PaintMapping.padded(pose == null ? mask.displayRegion : pose.region,
                    appearance.getSizePadding(), PreparedCensorPaint.isText(mask)));
            RectF ownerClip = pose == null ? null : rect(pose.clip);
            if (intersects(rect, clip) && (ownerClip == null || RectF.intersects(rect, ownerClip))) {
                visible.add(mask); rects.add(rect); ownerClips.add(ownerClip);
            }
        }
        List<PaintedMember> result = new ArrayList<>();
        if (appearance.isReverseMode()) {
            RectF whole = new RectF(0, 0, view.snapshot.destination.width, view.snapshot.destination.height);
            int layer = canvas.saveLayer(whole, null);
            PreparedCensorPaint.Tile tile = valid && view.canvasSource != null && view.canvasPixels() != null
                    && prepared.canvas() != null && prepared.canvas().source == view.canvasSource ? prepared.canvas() : null;
            effect(canvas, whole, tile, tile == null ? null : tile.evidence, valid ? prepared : null,
                    0, PreparedCensorPaint.effectiveType(appearance), appearance.getReverseStrength(), appearance, time);
            for (RectF hole : rects) shape(canvas, hole, clear, appearance, true);
            canvas.restoreToCount(layer);
            if (appearance.isShowBorder()) for (RectF hole : rects) border(canvas, hole, appearance, time);
            return new Result(result, intersects(whole, clip), false); // Reveal holes never become billable members.
        }
        for (int index = 0; index < visible.size(); index++) {
            SceneEngine.Mask mask = visible.get(index); RectF destination = rects.get(index);
            PreparedCensorPaint.Tile tile = valid && view.sourcePixels(mask.evidenceSource.sequence) != null
                    ? prepared.member(mask) : null;
            SceneEngine.Region requested = PaintMapping.padded(mask.evidenceRegion, appearance.getSizePadding(), PreparedCensorPaint.isText(mask));
            int saved = canvas.save();
            try {
                if (ownerClips.get(index) != null) canvas.clipRect(ownerClips.get(index));
                // Clip the FULL translated destination. Clipping then stretching a source tile
                // would distort its sampled pixels when the mask leaves the scroll owner.
                effect(canvas, destination, tile, requested, valid ? prepared : null, mask.identity,
                        appearance.getType(), appearance.getIntensity(), appearance, time);
                if (appearance.isShowBorder()) border(canvas, destination, appearance, time);
            } finally { canvas.restoreToCount(saved); }
            result.add(new PaintedMember(mask, rectangularClip && ownerClips.get(index) == null
                    ? interiors(destination, clip) : Collections.emptyList(), recordGeometry && index < SurfaceSubmission.MAXIMUM_MEMBERS
                    ? recording(mask, destination, clip, ownerClips.get(index)) : null));
        }
        // Final global layer: subsequent overlapping artwork cannot bury a previous member label.
        if (appearance.isShowText()) {
            List<LabelLayout> labels = new ArrayList<>();
            for (int index = 0; index < visible.size(); index++) {
                LabelLayout label = labelLayout(rects.get(index), visible.get(index).identity, appearance);
                labels.add(label);
            }
            fill.setShader(null); fill.setColor(Color.BLACK); fill.setAlpha(205);
            for (int index = 0; index < labels.size(); index++) {
                LabelLayout label = labels.get(index); if (label == null) continue;
                int saved = canvas.save();
                if (ownerClips.get(index) != null) canvas.clipRect(ownerClips.get(index));
                canvas.drawRoundRect(label.band, dp(5), dp(5), fill); canvas.restoreToCount(saved);
            }
            fill.setAlpha(255);
            for (int index = 0; index < labels.size(); index++) {
                LabelLayout label = labels.get(index); if (label == null) continue;
                text.setColor(0xfff8f1fc); text.setTextSize(label.size); text.setShadowLayer(dp(3), 0, dp(1), Color.BLACK);
                int saved = canvas.save(); canvas.clipRect(label.band);
                if (ownerClips.get(index) != null) canvas.clipRect(ownerClips.get(index));
                canvas.drawText(label.value, label.x, label.baseline, text); canvas.restoreToCount(saved); text.clearShadowLayer();
            }
        }
        return new Result(result, false, rectangularClip);
    }

    private static SurfaceSubmission.Member recording(SceneEngine.Mask mask, RectF destination, Rect canvasClip, RectF ownerClip) {
        try {
            return new SurfaceSubmission.Member(mask.identity, mask.geometrySource, mask.evidenceSource,
                    region(destination), new SceneEngine.Region(canvasClip.left, canvasClip.top, canvasClip.right, canvasClip.bottom),
                    ownerClip == null ? null : region(ownerClip));
        } catch (RuntimeException | Error unavailable) { return null; } // Missing metadata stays explicitly incomplete.
    }

    /** Explicit source-free cold fallback. Its caller supplies ACTIVE scope and real View bounds. */
    public void drawColdReverse(Canvas canvas, RectF visibleBounds, CensorAppearance appearance, long timeMillis) {
        if (closed) throw new IllegalStateException("closed painter");
        if (!appearance.isReverseMode() || visibleBounds.isEmpty()) return;
        effect(canvas, visibleBounds, null, null, null, 0, PreparedCensorPaint.effectiveType(appearance),
                appearance.getReverseStrength(), appearance, Math.max(0, timeMillis));
    }

    private void effect(Canvas canvas, RectF rect, PreparedCensorPaint.Tile tile, SceneEngine.Region evidence,
            PreparedCensorPaint prepared, long id, CensorAppearance.Type type, int intensity,
            CensorAppearance appearance, long time) {
        fill.setShader(null); fill.setAlpha(255); fill.setColor(appearance.getEffectPalette().first());
        if (type == CensorAppearance.Type.BOX && !appearance.isReverseMode()) canvas.drawRoundRect(rect, dp(8), dp(8), fill);
        else canvas.drawRect(rect, fill); // Unsupported pixel portions and transparent artwork stay protected.
        switch (type) {
            case PIXELATE: case BLUR:
                if (tile != null) sample(canvas, tile, evidence, rect, type == CensorAppearance.Type.BLUR ? filtered : nearest);
                break;
            case CUSTOM:
                Bitmap image = prepared == null ? null : prepared.artwork(id);
                if (image != null) canvas.drawBitmap(image, centerCrop(image, rect.width() / rect.height()), rect, filtered);
                break;
            case STATIC: staticTexture(canvas, rect, prepared, id, intensity, appearance, time); break;
            case GLITCH:
                if (tile == null) staticTexture(canvas, rect, prepared, id, intensity, appearance, time);
                else glitch(canvas, rect, tile, evidence, id, intensity, appearance, time);
                break;
            case TAPE:
                if (tile != null) { filtered.setAlpha(70); sample(canvas, tile, evidence, rect, filtered); filtered.setAlpha(255); }
                tape(canvas, rect, id, intensity, appearance, time); break;
            case ERROR_POPUP: lockpad.draw(canvas, rect, appearance.getEffectPalette(), time); break;
            default: break;
        }
    }

    private void sample(Canvas canvas, PreparedCensorPaint.Tile tile, SceneEngine.Region evidence, RectF destination, Paint paint) {
        SceneEngine.Region mapped = PaintMapping.map(evidence, region(destination), tile.support);
        int saved = canvas.save(); canvas.clipRect(destination);
        canvas.drawBitmap(tile.bitmap, null, rect(mapped), paint); canvas.restoreToCount(saved);
    }

    private void staticTexture(Canvas canvas, RectF rect, PreparedCensorPaint prepared, long id, int intensity,
            CensorAppearance appearance, long time) {
        Bitmap noise = prepared == null ? null : prepared.noise();
        if (noise != null) {
            if (cachedNoise != noise) { cachedNoise = noise; noiseShader = new BitmapShader(noise, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT); }
            float cell = Math.max(2, Math.min(7, 7 - intensity / 20));
            matrix.reset(); matrix.setScale(cell, cell);
            long tick = time / 90; matrix.postTranslate((tick * 17 + id * 7) % 64 * cell, (tick * 29 + id * 11) % 64 * cell);
            noiseShader.setLocalMatrix(matrix); fill.setShader(noiseShader); fill.setAlpha(255); canvas.drawRect(rect, fill); fill.setShader(null);
        } else {
            // Allocation-free source-independent texture; bounded even on a full-screen fallback.
            for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
                fill.setColor(blend(appearance.getEffectPalette().first(), appearance.getEffectPalette().second(),
                        hash(id + time / 90 * 961 + y * 131 + x * 31) % 256 / 255f));
                canvas.drawRect(rect.left + rect.width() * x / 16, rect.top + rect.height() * y / 16,
                        rect.left + rect.width() * (x + 1) / 16, rect.top + rect.height() * (y + 1) / 16, fill);
            }
        }
    }

    private void glitch(Canvas canvas, RectF rect, PreparedCensorPaint.Tile tile, SceneEngine.Region evidence,
            long id, int intensity, CensorAppearance appearance, long time) {
        int saved = canvas.save(); canvas.clipRect(rect);
        float strength = Math.max(1, intensity) / 100f, offset = Math.max(3, (.05f + .10f * strength) * rect.width());
        shifted.setAlpha(220);
        shifted.setColorFilter(new PorterDuffColorFilter(appearance.getEffectPalette().first(), PorterDuff.Mode.SRC_ATOP));
        RectF moved = new RectF(rect); moved.offset(-offset, 0); sample(canvas, tile, evidence, moved, shifted);
        shifted.setColorFilter(new PorterDuffColorFilter(appearance.getEffectPalette().second(), PorterDuff.Mode.SRC_ATOP));
        moved.offset(offset * 2, 0); sample(canvas, tile, evidence, moved, shifted); shifted.setColorFilter(null);
        int bands = Math.max(6, Math.round(8 + strength * 10));
        SceneEngine.Region mapped = PaintMapping.map(evidence, region(rect), tile.support);
        for (int index = 0; index < bands; index++) {
            int height = Math.max(1, tile.bitmap.getHeight() / 11);
            int top = hash(id * 131 + index * 31 + time / 90 * 961) % Math.max(1, tile.bitmap.getHeight() - height);
            float y1 = (float) (mapped.top + (mapped.bottom - mapped.top) * top / tile.bitmap.getHeight());
            float y2 = (float) (mapped.top + (mapped.bottom - mapped.top) * (top + height) / tile.bitmap.getHeight());
            float dx = ((index + time / 90) % 3 - 1) * offset;
            canvas.drawBitmap(tile.bitmap, new Rect(0, top, tile.bitmap.getWidth(), top + height),
                    new RectF((float) mapped.left + dx, y1, (float) mapped.right + dx, y2), filtered);
            fill.setColor(appearance.getEffectPalette().third()); fill.setAlpha(175);
            canvas.drawRect(rect.left, y1, rect.right, y2, fill);
        }
        fill.setAlpha(255); canvas.restoreToCount(saved);
    }

    private void tape(Canvas canvas, RectF rect, long id, int intensity, CensorAppearance appearance, long time) {
        int saved = canvas.save(); canvas.clipRect(rect); fill.setColor(appearance.getEffectPalette().first());
        fill.setAlpha(210); canvas.drawRect(rect, fill); fill.setAlpha(255);
        float spacing = Math.max(16, Math.min(52, 50 - Math.max(1, intensity) * .25f));
        float cycles = Math.max(1, Math.round(4000 / (spacing * 25)));
        float shift = (time % 4000 / 4000f * spacing * cycles + id * 7 % spacing) % spacing;
        float rise = rect.height() * .45f;
        for (int layer = 0; layer < 2; layer++) {
            fill.setColor(layer == 0 ? appearance.getEffectPalette().second() : appearance.getEffectPalette().third());
            fill.setStrokeWidth(Math.max(layer == 0 ? 5 : 3, spacing / (layer == 0 ? 3 : 5)));
            for (float x = rect.left - rect.width() + layer * spacing / 2; x < rect.right + rect.width(); x += spacing) {
                canvas.drawLine(x + shift, rect.bottom, x + shift + rise, rect.top, fill);
            }
        }
        canvas.restoreToCount(saved);
    }

    private void border(Canvas canvas, RectF rect, CensorAppearance appearance, long time) {
        int saved = canvas.save(); canvas.clipRect(rect); border.setShader(null); border.setAlpha(255);
        border.setColor(appearance.getBorderColor()); border.setStrokeWidth(Math.max(2, Math.min(rect.width(), rect.height()) * .025f));
        if (appearance.getBorderEffect() == CensorAppearance.BorderEffect.GLOW) {
            for (int step = 4; step >= 1; step--) { border.setStrokeWidth(2 + step * 3); border.setAlpha(30 + step * 12); shape(canvas, rect, border, appearance, false); }
            border.setAlpha(255); border.setStrokeWidth(3);
        } else if (appearance.getBorderEffect() == CensorAppearance.BorderEffect.GRADIENT) {
            border.setShader(new LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
                    appearance.getGradientStart(), appearance.getGradientEnd(), Shader.TileMode.CLAMP));
        } else if (appearance.getBorderEffect() == CensorAppearance.BorderEffect.RAINBOW) {
            border.setShader(new SweepGradient(rect.centerX(), rect.centerY(), new int[]{Color.RED, Color.YELLOW,
                    Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED}, null));
        }
        if (border.getShader() != null && appearance.isAnimateBorder()) { matrix.setRotate(time % 4000 / 4000f * 360, rect.centerX(), rect.centerY()); border.getShader().setLocalMatrix(matrix); }
        shape(canvas, rect, border, appearance, false); border.setShader(null); canvas.restoreToCount(saved);
    }

    private void shape(Canvas canvas, RectF rect, Paint paint, CensorAppearance appearance, boolean hole) {
        if (appearance.isReverseMode()) {
            if ("ellipse".equals(appearance.getReverseCutoutShape())) { canvas.drawOval(rect, paint); return; }
            if ("rounded".equals(appearance.getReverseCutoutShape())) { float radius = Math.min(rect.width(), rect.height()) * .22f; canvas.drawRoundRect(rect, radius, radius, paint); return; }
            canvas.drawRect(rect, paint); return;
        }
        canvas.drawRoundRect(rect, dp(8), dp(8), paint);
    }

    static final class LabelLayout {
        final RectF band;
        final String value;
        final float x, baseline, size, measuredWidth, maximumWidth, glyphTop, glyphBottom;
        LabelLayout(RectF band, String value, float x, float baseline, float size, float measuredWidth,
                float maximumWidth, float glyphTop, float glyphBottom) {
            this.band = band; this.value = value; this.x = x; this.baseline = baseline; this.size = size;
            this.measuredWidth = measuredWidth; this.maximumWidth = maximumWidth;
            this.glyphTop = glyphTop; this.glyphBottom = glyphBottom;
        }
    }

    LabelLayout labelLayout(RectF destination, long id, CensorAppearance appearance) {
        if (destination.width() < dp(32) || destination.height() < dp(16)) return null;
        RectF rect = new RectF(destination);
        boolean lockpadLabel = appearance.getType() == CensorAppearance.Type.ERROR_POPUP;
        if (lockpadLabel) LockpadArtwork.labelBounds(destination, rect);
        if (rect.isEmpty()) return null;
        float width = lockpadLabel ? rect.width() - dp(10) : Math.max(dp(18), rect.width() - dp(10));
        float minimum = dp(7);
        float maximum = Math.min(dp(14), Math.max(minimum, rect.height() * (lockpadLabel ? .60f : .20f)));
        text.setTextSize(minimum);
        if (width <= 0 || text.descent() - text.ascent() > rect.height()) return null;
        String selected = CensorLabelLayout.selectPhrase(appearance.getPhrases(), (int) (id ^ id >>> 32), width, text::measureText);
        text.setTextSize(maximum); float measured = text.measureText(selected);
        if (measured > width) text.setTextSize(Math.max(minimum, maximum * width / measured));
        String fitted = CensorLabelLayout.ellipsize(selected, width, text::measureText);
        measured = text.measureText(fitted);
        if (measured > width || text.descent() - text.ascent() > rect.height()) return null;
        float baseline = rect.centerY() - (text.ascent() + text.descent()) / 2;
        float bandHeight = Math.min(rect.height(), Math.max(dp(18), text.getTextSize() * 1.65f));
        return new LabelLayout(new RectF(rect.left, rect.centerY() - bandHeight / 2, rect.right, rect.centerY() + bandHeight / 2),
                fitted, rect.centerX(), baseline, text.getTextSize(), measured, width, baseline + text.ascent(), baseline + text.descent());
    }

    private List<SceneEngine.Region> interiors(RectF rect, Rect clip) {
        float radius = Math.min(dp(8), Math.min(rect.width(), rect.height()) / 2);
        List<SceneEngine.Region> result = new ArrayList<>(2);
        interior(result, rect.left + radius, rect.top, rect.right - radius, rect.bottom, clip);
        interior(result, rect.left, rect.top + radius, rect.right, rect.bottom - radius, clip);
        if (result.isEmpty()) {
            // A fully rounded square has no cross strips, but still has an opaque central square.
            // One physical pixel of inset keeps attribution away from antialiased circle edges.
            float inset = radius * (1 - (float) (1 / Math.sqrt(2))) + 1;
            interior(result, rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset, clip);
        }
        return result;
    }
    private static void interior(List<SceneEngine.Region> out, float left, float top, float right, float bottom, Rect clip) {
        double x = Math.ceil(Math.max(left, clip.left)), y = Math.ceil(Math.max(top, clip.top));
        double endX = Math.floor(Math.min(right, clip.right)), endY = Math.floor(Math.min(bottom, clip.bottom));
        if (endX > x && endY > y) out.add(new SceneEngine.Region(x, y, endX, endY));
    }
    private static Rect centerCrop(Bitmap image, float targetRatio) {
        if (image.getWidth() / (float) image.getHeight() > targetRatio) {
            int width = Math.max(1, Math.min(image.getWidth(), Math.round(image.getHeight() * targetRatio)));
            int left = (image.getWidth() - width) / 2; return new Rect(left, 0, left + width, image.getHeight());
        }
        int height = Math.max(1, Math.min(image.getHeight(), Math.round(image.getWidth() / targetRatio)));
        int top = (image.getHeight() - height) / 2; return new Rect(0, top, image.getWidth(), top + height);
    }
    private static boolean intersects(RectF rect, Rect clip) { return rect.right > clip.left && rect.bottom > clip.top && rect.left < clip.right && rect.top < clip.bottom; }
    private static RectF rect(SceneEngine.Region r) { return new RectF((float) r.left, (float) r.top, (float) r.right, (float) r.bottom); }
    private static SceneEngine.Region region(RectF r) { return new SceneEngine.Region(r.left, r.top, r.right, r.bottom); }
    private float dp(float value) { return value * density; }
    private static int hash(long seed) { seed = (seed ^ seed >>> 33) * -49064778989728563L; seed = (seed ^ seed >>> 33) * -4265267296055464877L; return (int) ((seed ^ seed >>> 33) & 0x7fffffff); }
    static int blend(int a, int b, float fraction) {
        float f = Math.max(0, Math.min(1, fraction));
        return Color.rgb(Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * f),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * f), Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f));
    }
    @Override public void close() { closed = true; cachedNoise = null; noiseShader = null; fill.setShader(null); border.setShader(null); }
}
