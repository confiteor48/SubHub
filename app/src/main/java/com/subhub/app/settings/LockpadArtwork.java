package com.subhub.app.settings;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;

/** Opaque effect artwork. The caller owns destination geometry, padding and final phrase labels. */
public final class LockpadArtwork {
    private static final float[] FASTENER_X = {17, 463};
    private static final float[] FASTENER_Y = {17, 178};
    private static final String[] DIGITS = {"1", "2", "3", "4", "5", "6", "7", "8", "9"};
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint digits = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF shape = new RectF();
    private final Path shackle = new Path();
    private Shader metal;
    private int cachedAccent;

    public LockpadArtwork() {
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        digits.setTextAlign(Paint.Align.CENTER);
        digits.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
    }

    public void draw(Canvas canvas, RectF destination, EffectPalette palette, long timeMillis) {
        if (!valid(destination)) return;
        int save = canvas.save();
        canvas.clipRect(destination);
        fill.setShader(null);
        fill.setColor(palette.first());
        fill.setAlpha(255);
        // Rounded decorative chrome must never leave the protected corners transparent.
        canvas.drawRect(destination, fill);
        boolean full = fullLayout(destination);
        canvas.translate(destination.left, destination.top);
        canvas.scale(destination.width() / 480f, destination.height() / 195f);
        prepareMetal(palette.third());
        rounded(canvas, 2, 2, 478, 193, 17, blend(palette.first(), palette.third(), .36f));
        rounded(canvas, 7, 7, 473, 188, 14, palette.first());
        if (!full) {
            lock(canvas, 154, 30, 172, 135, palette);
        } else {
            rounded(canvas, 29, 25, 285, 74, 9, blend(palette.first(), Color.BLACK, .58f));
            long tick = Math.max(0L, timeMillis) % 3000L;
            int depressed = tick < 1100 ? (int) (tick / 160) % 9 : -1;
            for (int index = 0; index < 9; index++) {
                float x = 32 + index % 3 * 84, y = 86 + index / 3 * 30;
                boolean pressed = index == depressed;
                rounded(canvas, x, y, x + 71, y + 25, 5,
                        blend(palette.first(), Color.BLACK, .68f));
                rounded(canvas, x, y + (pressed ? 4 : 0), x + 71,
                        y + 22, 5, blend(palette.first(), palette.second(), pressed ? .55f : .26f));
                // Single digits are part of the keypad artwork, not user phrases.
                digits.setColor(palette.third());
                digits.setTextSize(17);
                String digit = DIGITS[index];
                float available = 61;
                if (17 * Math.min(destination.width() / 480f, destination.height() / 195f) >= 8
                        && digits.measureText(digit) <= available) {
                    float baseline = y + 12 - (digits.ascent() + digits.descent()) / 2;
                    canvas.drawText(digit, x + 35.5f, baseline, digits);
                }
            }
            rounded(canvas, 309, 26, 449, 165, 14, blend(palette.first(), Color.BLACK, .50f));
            rounded(canvas, 315, 31, 443, 159, 11, blend(palette.first(), palette.second(), .20f));
            lock(canvas, 338, 47, 83, 99, palette);
        }
        // Fasteners are cached primitive geometry; no images or frame resources are retained.
        for (float x : FASTENER_X) {
            for (float y : FASTENER_Y) {
                fill.setShader(metal);
                canvas.drawCircle(x, y, 5, fill);
                fill.setShader(null);
                stroke.setShader(null);
                stroke.setColor(blend(palette.first(), Color.BLACK, .4f));
                stroke.setStrokeWidth(1.5f);
                canvas.drawLine(x - 2, y + 2, x + 2, y - 2, stroke);
            }
        }
        fill.setShader(null);
        stroke.setShader(null);
        canvas.restoreToCount(save);
    }

    /** Empty for compact artwork: the host must omit text rather than cover the lock or crop it. */
    public static void labelBounds(RectF destination, RectF out) {
        if (!valid(destination) || !fullLayout(destination)) { out.setEmpty(); return; }
        out.set(destination.left + destination.width() * 33 / 480f,
                destination.top + destination.height() * 29 / 195f,
                destination.left + destination.width() * 281 / 480f,
                destination.top + destination.height() * 70 / 195f);
    }

    private static boolean fullLayout(RectF rect) {
        return rect.width() >= 100 && rect.height() >= 64
                && rect.width() / rect.height() >= 1.45f
                && rect.width() / rect.height() <= 4.5f;
    }

    private static boolean valid(RectF rect) {
        return rect != null && Float.isFinite(rect.left) && Float.isFinite(rect.top)
                && Float.isFinite(rect.right) && Float.isFinite(rect.bottom)
                && Float.isFinite(rect.width()) && Float.isFinite(rect.height())
                && rect.width() > 0 && rect.height() > 0;
    }

    private void lock(Canvas canvas, float x, float y, float width, float height,
                      EffectPalette palette) {
        shackle.reset();
        shackle.moveTo(x + width * .15f, y + height * .50f);
        shackle.lineTo(x + width * .15f, y + height * .25f);
        shackle.cubicTo(x + width * .15f, y - height * .05f,
                x + width * .85f, y - height * .05f,
                x + width * .85f, y + height * .25f);
        shackle.lineTo(x + width * .85f, y + height * .50f);
        stroke.setShader(metal);
        stroke.setStrokeWidth(width * .11f);
        canvas.drawPath(shackle, stroke);
        stroke.setShader(null);
        rounded(canvas, x, y + height * .38f, x + width, y + height, width * .12f,
                palette.second());
        fill.setColor(blend(palette.first(), Color.BLACK, .40f));
        canvas.drawCircle(x + width / 2, y + height * .64f, width * .085f, fill);
        shape.set(x + width * .463f, y + height * .64f,
                x + width * .537f, y + height * .83f);
        canvas.drawRect(shape, fill);
    }

    private void rounded(Canvas canvas, float left, float top, float right, float bottom,
                         float radius, int color) {
        fill.setShader(null);
        fill.setColor(color);
        shape.set(left, top, right, bottom);
        canvas.drawRoundRect(shape, radius, radius, fill);
    }

    private void prepareMetal(int accent) {
        if (metal != null && cachedAccent == accent) return;
        cachedAccent = accent;
        metal = new LinearGradient(0, 0, 480, 195,
                new int[]{blend(accent, Color.WHITE, .7f), blend(accent, Color.BLACK, .5f),
                        accent, blend(accent, Color.WHITE, .5f)},
                new float[]{0, .3f, .6f, 1}, Shader.TileMode.CLAMP);
    }

    private static int blend(int a, int b, float fraction) {
        return Color.rgb(Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * fraction),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * fraction),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * fraction));
    }
}
