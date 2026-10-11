package com.subhub.app.capture;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.Typeface;

import com.subhub.app.detection.BBox;
import com.subhub.app.detection.Detection;
import com.subhub.app.detection.DetectionEngine;
import com.subhub.app.overlay.CensorLabelLayout;
import com.subhub.app.settings.CensorAppearance;
import com.subhub.app.settings.LockpadArtwork;
import com.subhub.app.settings.SettingsRepository;

import java.util.List;

import ai.onnxruntime.OrtException;

/** Applies the same recovered censor vocabulary to a standalone mutable image. */
public final class CensorRenderer implements AutoCloseable {
    private final Context context;
    private final CustomImagePool customImages;
    private final LockpadArtwork lockpad = new LockpadArtwork();
    private final RectF lockpadLabel = new RectF();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint filtered = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint nearest = new Paint();
    private final Paint cyanShift = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint redShift = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Matrix borderMatrix = new Matrix();
    private long animationTime;

    public CensorRenderer(Context context) {
        this(context, null);
    }

    /** A non-null image list freezes custom artwork for an export job. */
    public CensorRenderer(Context context, List<Bitmap> frozenImages) {
        this.context = context.getApplicationContext();
        customImages = frozenImages == null ? new CustomImagePool(this.context)
                : new CustomImagePool(this.context, frozenImages);
        if (frozenImages == null) customImages.reload();
        border.setStyle(Paint.Style.STROKE);
        text.setTextAlign(Paint.Align.CENTER);
        text.setFakeBoldText(true);
        nearest.setFilterBitmap(false);
        cyanShift.setAlpha(220);
        cyanShift.setColorFilter(new PorterDuffColorFilter(
                Color.rgb(0, 180, 255), PorterDuff.Mode.SRC_ATOP));
        redShift.setAlpha(220);
        redShift.setColorFilter(new PorterDuffColorFilter(
                Color.rgb(255, 0, 80), PorterDuff.Mode.SRC_ATOP));
    }

    public RenderResult renderWithDetection(Bitmap source, DetectionEngine engine)
            throws OrtException {
        List<Detection> detections = engine.detect(source);
        Bitmap output = source.copy(Bitmap.Config.ARGB_8888, true);
        if (output == null) throw new IllegalStateException("Could not allocate export image");
        draw(output, source, detections, new SettingsRepository(context).loadAppearance());
        return new RenderResult(output, detections.size());
    }

    public void draw(
            Bitmap target,
            Bitmap source,
            List<Detection> detections,
            CensorAppearance appearance) {
        drawAtTime(target, source, detections, appearance, 0L);
    }

    /** Deterministic animation frames without changing the still-image export contract. */
    public void drawAtTime(
            Bitmap target,
            Bitmap source,
            List<Detection> detections,
            CensorAppearance appearance,
            long timeMillis) {
        animationTime = Math.max(0L, timeMillis);
        Canvas canvas = new Canvas(target);
        if (appearance.isReverseMode()) {
            drawEffect(canvas, source, new RectF(0, 0, target.getWidth(), target.getHeight()),
                    0, appearance.getType(), appearance.getReverseStrength(), appearance);
            for (int index = 0; index < detections.size(); index++) {
                RectF rect = paddedRect(detections.get(index).getBox(), target, appearance);
                int save = canvas.save();
                canvas.clipPath(shapePath(rect, appearance.getReverseCutoutShape()));
                canvas.drawBitmap(source, 0, 0, filtered);
                canvas.restoreToCount(save);
                if (appearance.isShowBorder()) drawBorder(canvas, rect, appearance);
            }
            return;
        }
        for (int index = 0; index < detections.size(); index++) {
            RectF rect = paddedRect(detections.get(index).getBox(), target, appearance);
            drawEffect(canvas, source, rect, index, appearance.getType(),
                    appearance.getIntensity(), appearance);
            if (appearance.isShowBorder()) drawBorder(canvas, rect, appearance);
        }
        // Labels are a final global layer so overlapping censor artwork cannot bury glyphs.
        for (int index = 0; index < detections.size(); index++) {
            RectF rect = paddedRect(detections.get(index).getBox(), target, appearance);
            if (appearance.isShowText()
                    && rect.width() >= 32 && rect.height() >= 16) {
                drawLabel(canvas, rect, index, appearance);
            }
        }
    }

    private void drawEffect(
            Canvas canvas,
            Bitmap source,
            RectF rect,
            int id,
            CensorAppearance.Type requestedType,
            int intensity,
            CensorAppearance appearance) {
        CensorAppearance.Type type = requestedType;
        if (appearance.isReverseMode() && (type == CensorAppearance.Type.BOX
                || type == CensorAppearance.Type.CUSTOM)) {
            type = CensorAppearance.Type.PIXELATE;
        }
        switch (type) {
            case PIXELATE:
                drawPixelate(canvas, source, rect, intensity, false);
                break;
            case BLUR:
                drawPixelate(canvas, source, rect, intensity, true);
                break;
            case CUSTOM:
                if (!drawCustom(canvas, rect, id)) drawSolid(canvas, rect, appearance);
                break;
            case STATIC:
                drawStatic(canvas, rect, id, intensity, appearance);
                break;
            case GLITCH:
                drawGlitch(canvas, source, rect, id, intensity, appearance);
                break;
            case TAPE:
                drawTape(canvas, source, rect, id, intensity, appearance);
                break;
            case ERROR_POPUP:
                lockpad.draw(canvas, rect, appearance.getEffectPalette(), animationTime);
                break;
            case BOX:
            default:
                drawSolid(canvas, rect, appearance);
                break;
        }
    }

    private void drawSolid(Canvas canvas, RectF rect, CensorAppearance appearance) {
        fill.setShader(null);
        fill.setColor(appearance.getEffectPalette().first());
        fill.setAlpha(255);
        canvas.drawRoundRect(rect, 10, 10, fill);
    }

    private void drawPixelate(
            Canvas canvas, Bitmap source, RectF destination, int intensity, boolean soften) {
        Rect sourceRect = sourceRect(source, destination);
        int clamped = Math.max(1, Math.min(100, intensity));
        int divisor;
        if (soften) {
            divisor = Math.max(2, Math.round(2 + (clamped - 1) / 99f * 18f));
        } else {
            int minimum = Math.max(1, Math.min(sourceRect.width(), sourceRect.height()));
            float fraction = (clamped - 1) / 99f;
            int minimumBlock = Math.max(3, Math.round(minimum * .025f));
            int maximumBlock = Math.max(minimumBlock + 1, Math.round(minimum * .45f));
            divisor = Math.max(2, Math.min(minimum,
                    Math.round(minimumBlock + (maximumBlock - minimumBlock)
                            * fraction * fraction)));
        }
        int width = Math.max(1, sourceRect.width() / divisor);
        int height = Math.max(1, sourceRect.height() / divisor);
        Bitmap small = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        new Canvas(small).drawBitmap(source, sourceRect,
                new Rect(0, 0, width, height), filtered);
        canvas.drawBitmap(small, null, destination, soften ? filtered : nearest);
        small.recycle();
    }

    private boolean drawCustom(Canvas canvas, RectF rect, int id) {
        CustomImagePool.PreparedImage prepared = customImages.imageFor(id);
        Bitmap bitmap = prepared == null ? null : prepared.bitmap();
        if (bitmap == null || bitmap.isRecycled()) return false;
        float targetRatio = rect.width() / Math.max(1f, rect.height());
        Rect crop = prepared.cropFor(targetRatio);
        canvas.drawBitmap(bitmap, crop, rect, filtered);
        return true;
    }

    private void drawStatic(Canvas canvas, RectF rect, int id, int intensity,
            CensorAppearance appearance) {
        int clamped = Math.max(1, Math.min(100, intensity));
        int cell = Math.max(2, Math.min(7, 7 - clamped / 20));
        float contrast = .35f + clamped / 100f * .65f;
        long seed = id * 1103515245L + Float.floatToIntBits(rect.left)
                + animationTime / 90L * 6364136223846793005L;
        for (float y = rect.top; y < rect.bottom; y += cell) {
            for (float x = rect.left; x < rect.right; x += cell) {
                seed = seed * 6364136223846793005L + 1442695040888963407L;
                int raw = (int) ((seed >>> 56) & 0xff);
                int value = Math.max(0, Math.min(255,
                        Math.round(128f * (1f - contrast) + raw * contrast)));
                fill.setColor(blendColor(appearance.getEffectPalette().first(),
                        appearance.getEffectPalette().second(), value / 255f));
                fill.setAlpha(255);
                canvas.drawRect(x, y, Math.min(rect.right, x + cell),
                        Math.min(rect.bottom, y + cell), fill);
            }
        }
        fill.setColor(appearance.getEffectPalette().first());
        fill.setAlpha(70);
        for (float y = rect.top; y < rect.bottom; y += Math.max(4, cell * 2f)) {
            canvas.drawRect(rect.left, y, rect.right, Math.min(rect.bottom, y + 1f), fill);
        }
    }

    private void drawGlitch(Canvas canvas, Bitmap source, RectF rect, int id, int intensity,
            CensorAppearance appearance) {
        Rect sourceRegion = sourceRect(source, rect);
        int save = canvas.save();
        canvas.clipRect(rect);
        fill.setShader(null);
        fill.setColor(Color.rgb(7, 5, 12));
        fill.setAlpha(255);
        canvas.drawRect(rect, fill);
        float strength = Math.max(1, Math.min(100, intensity)) / 100f;
        cyanShift.setColorFilter(new PorterDuffColorFilter(
                appearance.getEffectPalette().first(), PorterDuff.Mode.SRC_ATOP));
        redShift.setColorFilter(new PorterDuffColorFilter(
                appearance.getEffectPalette().second(), PorterDuff.Mode.SRC_ATOP));
        float shift = Math.max(3f, (.05f + .10f * strength) * rect.width());
        RectF shifted = new RectF(rect);
        shifted.offset(-shift, 0);
        canvas.drawBitmap(source, sourceRegion, shifted, cyanShift);
        shifted.offset(shift * 2f, 0);
        canvas.drawBitmap(source, sourceRegion, shifted, redShift);
        int bands = Math.max(6, Math.round(8 + strength * 10));
        int sourceBandHeight = Math.max(3, sourceRegion.height() / 11);
        for (int index = 0; index < bands; index++) {
            int available = Math.max(1, sourceRegion.height() - sourceBandHeight);
            long tick = animationTime / 90L;
            int sourceTop = sourceRegion.top + hashInt(id * 131L + index * 31L + tick * 961L) % available;
            int sourceBottom = Math.min(sourceRegion.bottom, sourceTop + sourceBandHeight);
            float topRatio = (sourceTop - sourceRegion.top) / (float) sourceRegion.height();
            float bottomRatio = (sourceBottom - sourceRegion.top) / (float) sourceRegion.height();
            float offset = ((index + tick) % 3 - 1) * shift;
            Rect bandSource = new Rect(sourceRegion.left, sourceTop,
                    sourceRegion.right, sourceBottom);
            RectF bandDestination = new RectF(rect.left + offset,
                    rect.top + rect.height() * topRatio, rect.right + offset,
                    rect.top + rect.height() * bottomRatio);
            canvas.drawBitmap(source, bandSource, bandDestination, filtered);
            fill.setColor(appearance.getEffectPalette().third());
            fill.setAlpha(175);
            canvas.drawRect(rect.left, bandDestination.top, rect.right,
                    bandDestination.bottom, fill);
        }
        canvas.restoreToCount(save);
    }

    private void drawTape(
            Canvas canvas, Bitmap source, RectF rect, int id, int intensity,
            CensorAppearance appearance) {
        int save = canvas.save();
        canvas.clipRect(rect);
        filtered.setAlpha(70);
        canvas.drawBitmap(source, sourceRect(source, rect), rect, filtered);
        filtered.setAlpha(255);
        fill.setShader(null);
        fill.setColor(appearance.getEffectPalette().first());
        fill.setAlpha(210);
        canvas.drawRect(rect, fill);
        float spacing = Math.max(16f, Math.min(52f,
                50f - Math.max(1, Math.min(100, intensity)) * .25f));
        Paint red = new Paint(Paint.ANTI_ALIAS_FLAG);
        red.setColor(appearance.getEffectPalette().second());
        red.setStrokeWidth(Math.max(5f, spacing / 3f));
        Paint yellow = new Paint(Paint.ANTI_ALIAS_FLAG);
        yellow.setColor(appearance.getEffectPalette().third());
        yellow.setStrokeWidth(Math.max(3f, spacing / 5f));
        // Complete whole stripe cycles so a cached four-second loop has no seam.
        float cycles = Math.max(1, Math.round(4000f / (spacing * 25f)));
        float shift = ((animationTime % 4000L) / 4000f * spacing * cycles + id * 7f) % spacing;
        float rise = rect.height() * .45f;
        for (float x = rect.left - rect.width(); x < rect.right + rect.width(); x += spacing) {
            canvas.drawLine(x + shift, rect.bottom, x + shift + rise, rect.top, red);
        }
        for (float x = rect.left - rect.width() + spacing / 2f;
             x < rect.right + rect.width(); x += spacing) {
            canvas.drawLine(x + shift, rect.bottom, x + shift + rise, rect.top, yellow);
        }
        canvas.restoreToCount(save);
    }

    private static long mix(long seed) {
        long first = (seed ^ (seed >>> 33)) * -49064778989728563L;
        long second = (first ^ (first >>> 33)) * -4265267296055464877L;
        return second ^ (second >>> 33);
    }

    private static int hashInt(long seed) {
        return (int) (mix(seed) & 0x7fffffffL);
    }

    private static int blendColor(int from, int to, float fraction) {
        float value = Math.max(0f, Math.min(1f, fraction));
        return Color.rgb(
                Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * value),
                Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * value),
                Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * value));
    }

    private static int contrastText(int background) {
        int luminance = Color.red(background) * 299 + Color.green(background) * 587
                + Color.blue(background) * 114;
        return luminance >= 150_000 ? Color.rgb(20, 20, 28) : Color.WHITE;
    }

    private void drawBorder(Canvas canvas, RectF rect, CensorAppearance appearance) {
        if (rect.isEmpty()) return;
        border.setStrokeWidth(Math.max(2, Math.min(rect.width(), rect.height()) * .025f));
        border.setColor(appearance.getBorderColor());
        border.setAlpha(255);
        border.setShader(null);
        int save = canvas.save();
        canvas.clipRect(rect);
        if (appearance.getBorderEffect() == CensorAppearance.BorderEffect.GLOW) {
            for (int step = 4; step >= 1; step--) {
                border.setStrokeWidth(2 + step * 3);
                border.setAlpha(30 + step * 12);
                drawShape(canvas, rect, border, appearance);
            }
            border.setAlpha(255);
            border.setStrokeWidth(3);
        } else if (appearance.getBorderEffect() == CensorAppearance.BorderEffect.GRADIENT) {
            border.setShader(new LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
                    appearance.getGradientStart(), appearance.getGradientEnd(), Shader.TileMode.CLAMP));
        } else if (appearance.getBorderEffect() == CensorAppearance.BorderEffect.RAINBOW) {
            border.setShader(new SweepGradient(rect.centerX(), rect.centerY(), new int[]{
                    Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE,
                    Color.MAGENTA, Color.RED}, null));
        }
        if (border.getShader() != null && appearance.isAnimateBorder()) {
            borderMatrix.setRotate((animationTime % 4000L) / 4000f * 360f,
                    rect.centerX(), rect.centerY());
            border.getShader().setLocalMatrix(borderMatrix);
        }
        drawShape(canvas, rect, border, appearance);
        canvas.restoreToCount(save);
        border.setShader(null);
        border.setAlpha(255);
    }

    private void drawShape(Canvas canvas, RectF rect, Paint paint, CensorAppearance appearance) {
        if (appearance.isReverseMode() && "ellipse".equals(appearance.getReverseCutoutShape())) {
            canvas.drawOval(rect, paint);
        } else if (appearance.isReverseMode()
                && "rounded".equals(appearance.getReverseCutoutShape())) {
            float radius = Math.min(rect.width(), rect.height()) * .22f;
            canvas.drawRoundRect(rect, radius, radius, paint);
        } else {
            canvas.drawRoundRect(rect, 10, 10, paint);
        }
    }

    private void drawLabel(
            Canvas canvas, RectF rect, int stableId, CensorAppearance appearance) {
        if (appearance.getType() == CensorAppearance.Type.ERROR_POPUP) {
            LockpadArtwork.labelBounds(rect, lockpadLabel);
            if (lockpadLabel.isEmpty()) return;
            rect = lockpadLabel;
        }
        text.setColor(Color.WHITE);
        float maximumWidth = Math.max(18f, rect.width() - 10f);
        float minimumSize = 7f;
        float heightFraction = appearance.getType() == CensorAppearance.Type.ERROR_POPUP ? .60f : .20f;
        float maximumSize = Math.min(36f, Math.max(minimumSize, rect.height() * heightFraction));
        text.setTextSize(minimumSize);
        String selected = CensorLabelLayout.selectPhrase(
                appearance.getPhrases(), stableId, maximumWidth, text::measureText);
        text.setTextSize(maximumSize);
        float measured = text.measureText(selected);
        text.setTextSize(measured <= maximumWidth || measured <= 0f
                ? maximumSize : Math.max(minimumSize,
                maximumSize * maximumWidth / measured));
        text.setShadowLayer(3, 0, 1, Color.BLACK);
        float baseline = rect.centerY() - (text.ascent() + text.descent()) / 2f;
        String fitted = CensorLabelLayout.ellipsize(
                selected, maximumWidth, text::measureText);
        canvas.drawText(fitted, rect.centerX(), baseline, text);
        text.clearShadowLayer();
    }

    private static RectF paddedRect(
            BBox box, Bitmap target, CensorAppearance appearance) {
        BBox padded = box.padded(appearance.getSizePadding(), target.getWidth(), target.getHeight());
        return new RectF(padded.getX(), padded.getY(), padded.getRight(), padded.getBottom());
    }

    private static Rect sourceRect(Bitmap source, RectF destination) {
        int left = Math.max(0, Math.min(source.getWidth() - 1, Math.round(destination.left)));
        int top = Math.max(0, Math.min(source.getHeight() - 1, Math.round(destination.top)));
        int right = Math.max(left + 1,
                Math.min(source.getWidth(), Math.round(destination.right)));
        int bottom = Math.max(top + 1,
                Math.min(source.getHeight(), Math.round(destination.bottom)));
        return new Rect(left, top, right, bottom);
    }

    private static Path shapePath(RectF rect, String shape) {
        Path path = new Path();
        if ("ellipse".equals(shape)) path.addOval(rect, Path.Direction.CW);
        else if ("rounded".equals(shape)) {
            float radius = Math.min(rect.width(), rect.height()) * .22f;
            path.addRoundRect(rect, radius, radius, Path.Direction.CW);
        } else path.addRect(rect, Path.Direction.CW);
        return path;
    }

    @Override
    public void close() {
        customImages.close();
    }

    public static final class RenderResult {
        private final Bitmap bitmap;
        private final int detectionCount;

        RenderResult(Bitmap bitmap, int detectionCount) {
            this.bitmap = bitmap;
            this.detectionCount = detectionCount;
        }

        public Bitmap getBitmap() { return bitmap; }
        public int getDetectionCount() { return detectionCount; }
    }
}
