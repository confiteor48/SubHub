package com.subhub.app.onboarding;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.View;
import android.widget.FrameLayout;
import android.graphics.Path;
import com.subhub.app.settings.CensorPreviewView;

/** Synthetic Setup illustration linking content cover, tribute rules and daily app time. */
public final class SetupFeaturePreviewView extends FrameLayout {
    private static Path line(float... points) {
        Path path = new Path();
        path.moveTo(points[0], points[1]);
        for (int index = 2; index < points.length; index += 2) path.lineTo(points[index], points[index + 1]);
        return path;
    }
    private static Path curve(float... points) {
        Path path = new Path();
        path.moveTo(points[0], points[1]);
        for (int index = 2; index < points.length; index += 6)
            path.cubicTo(points[index], points[index + 1], points[index + 2], points[index + 3], points[index + 4], points[index + 5]);
        return path;
    }
    private static Path arc(float left, float top, float right, float bottom, float start, float sweep) {
        Path path = new Path();
        path.addArc(left, top, right, bottom, start, sweep);
        return path;
    }
    private final DashPathEffect connectorDash = new DashPathEffect(new float[]{3f, 4f}, 0);
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final CensorPreviewView phone;
    private final int cardSurface;
    private final int subtleOutline;
    private final int raisedSurface;
    private final int checkedIndicator;
    private final int checkedOutline;
    private final int checkedSurface;
    private final int primaryText;
    private final int accentText;
    private final int secondaryText;
    private final int dialogSurface;
    private final int defaultOutline;
    private static final Path PATH_0 = curve(72f, 84f, 97f, 84f, 105f, 56f, 122f, 56f);
    private static final Path PATH_1 = line(116f, 51f, 122f, 56f, 116f, 61f);
    private static final Path PATH_2 = curve(69f, 170f, 95f, 170f, 105f, 142f, 122f, 142f);
    private static final Path PATH_3 = line(116f, 137f, 122f, 142f, 116f, 147f);
    private static final Path PATH_4 = curve(158f, 48f, 148f, 43f, 141f, 49f, 147f, 54f, 150f, 57f, 158f, 56f, 158f, 62f, 158f, 68f, 148f, 69f, 143f, 64f);
    private static final Path PATH_5 = line(151f, 44f, 151f, 69f);
    private static final Path PATH_6 = line(152f, 29f, 152f, 25f);
    private static final Path PATH_7 = line(173f, 35f, 176f, 32f);
    private static final Path PATH_8 = line(179f, 56f, 183f, 56f);
    private static final Path PATH_9 = line(131f, 35f, 128f, 32f);
    private static final Path PATH_10 = arc(133f, 123f, 171f, 161f, -90f, 270f);
    private static final Path PATH_11 = line(152f, 132f, 152f, 142f, 159f, 147f);
    private final Typeface normal = Typeface.create("sans-serif", Typeface.NORMAL);
    private final Typeface bold = Typeface.create("sans-serif", Typeface.BOLD);
    public SetupFeaturePreviewView(Context context) {
        super(context);
        cardSurface = context.getColor(com.subhub.app.R.color.surface_card);
        subtleOutline = context.getColor(com.subhub.app.R.color.outline_subtle);
        raisedSurface = context.getColor(com.subhub.app.R.color.surface_card_raised);
        checkedIndicator = context.getColor(com.subhub.app.R.color.control_checked_indicator);
        checkedOutline = context.getColor(com.subhub.app.R.color.control_checked_outline);
        checkedSurface = context.getColor(com.subhub.app.R.color.control_checked_surface);
        primaryText = context.getColor(com.subhub.app.R.color.text_primary);
        accentText = context.getColor(com.subhub.app.R.color.accent_text);
        secondaryText = context.getColor(com.subhub.app.R.color.text_secondary);
        dialogSurface = context.getColor(com.subhub.app.R.color.dialog_surface);
        defaultOutline = context.getColor(com.subhub.app.R.color.outline_default);
        setWillNotDraw(false);
        setContentDescription(context.getString(com.subhub.app.R.string.tour_feature_scene_accessibility));
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        phone = new CensorPreviewView(context, null);
        phone.setPhonePreview(true);
        phone.setTag("box");
        addView(phone, new FrameLayout.LayoutParams(1, 1));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }
    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        float scale = width / 328f;
        int height = Math.round(214f * scale);
        setMeasuredDimension(width, resolveSize(height, heightSpec));
        phone.measure(MeasureSpec.makeMeasureSpec(Math.round(100f * scale), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(Math.round(200f * scale), MeasureSpec.EXACTLY));
    }
    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        float scale = getWidth() / 328f;
        int x = Math.round(18f * scale), y = Math.round(5f * scale);
        phone.layout(x, y, x + phone.getMeasuredWidth(), y + phone.getMeasuredHeight());
    }
    @Override protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        int checkpoint = canvas.save();
        canvas.scale(getWidth() / 328f, getWidth() / 328f);
        paint.setStyle(Paint.Style.FILL);
        paint.setPathEffect(null);
        paint.setColor(Color.rgb(26, 19, 34));
        canvas.drawRect(26f, 156f, 111f, 193f, paint);
        paint.setColor(primaryText);
        paint.setTypeface(bold);
        paint.setTextSize(12f);
        canvas.drawText(getContext().getString(com.subhub.app.R.string.tour_scene_censor), 29f, 167f, paint);
        paint.setColor(secondaryText);
        paint.setTypeface(normal);
        paint.setTextSize(8f);
        String help = getContext().getString(com.subhub.app.R.string.tour_scene_censor_help);
        int split = paint.breakText(help, true, 70f, null);
        if (split < help.length()) {
            int space = help.lastIndexOf(' ', split);
            if (space > 0) split = space;
        }
        drawFittedText(canvas, help.substring(0, split).trim(), 29f, 180f, 70f);
        if (split < help.length()) drawFittedText(canvas, help.substring(split).trim(), 29f, 190f, 70f);
        paint.setColor(accentText);
        canvas.drawRoundRect(56f, 194f, 82f, 196f, 1f, 1f, paint);
        canvas.restoreToCount(checkpoint);
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int checkpoint = canvas.save();
        canvas.scale(getWidth() / 328f, getWidth() / 328f);
        paint.setPathEffect(null);
        paint.setStyle(Paint.Style.FILL); paint.setColor(cardSurface); canvas.drawRoundRect(0f, 0f, 328f, 214f, 16f, 16f, paint);
        paint.setStyle(Paint.Style.STROKE); paint.setColor(subtleOutline); paint.setStrokeWidth(1f);
        canvas.drawRoundRect(0f, 0f, 328f, 214f, 16f, 16f, paint);
        paint.setPathEffect(null);
        paint.setStyle(Paint.Style.FILL); paint.setColor(raisedSurface); canvas.drawRoundRect(122f, 24f, 312f, 88f, 12f, 12f, paint);
        paint.setStyle(Paint.Style.STROKE); paint.setColor(subtleOutline); paint.setStrokeWidth(1f);
        canvas.drawRoundRect(122f, 24f, 312f, 88f, 12f, 12f, paint);
        paint.setPathEffect(null);
        paint.setStyle(Paint.Style.FILL); paint.setColor(raisedSurface); canvas.drawRoundRect(122f, 110f, 312f, 174f, 12f, 12f, paint);
        paint.setStyle(Paint.Style.STROKE); paint.setColor(subtleOutline); paint.setStrokeWidth(1f);
        canvas.drawRoundRect(122f, 110f, 312f, 174f, 12f, 12f, paint);
        paint.setPathEffect(null);
        paint.setStyle(Paint.Style.STROKE); paint.setColor(checkedIndicator); paint.setStrokeWidth(1.5f);
        paint.setPathEffect(connectorDash);
        canvas.drawPath(PATH_0, paint);
        paint.setPathEffect(null);
        paint.setStyle(Paint.Style.STROKE); paint.setColor(checkedIndicator); paint.setStrokeWidth(1.5f);
        canvas.drawPath(PATH_1, paint);
        paint.setPathEffect(null);
        paint.setStyle(Paint.Style.STROKE); paint.setColor(checkedOutline); paint.setStrokeWidth(1.5f);
        paint.setPathEffect(connectorDash);
        canvas.drawPath(PATH_2, paint);
        paint.setPathEffect(null);
        paint.setStyle(Paint.Style.STROKE); paint.setColor(checkedOutline); paint.setStrokeWidth(1.5f);
        canvas.drawPath(PATH_3, paint);
        paint.setPathEffect(null);
        paint.setStyle(Paint.Style.FILL); paint.setColor(checkedSurface); canvas.drawCircle(152f, 56f, 19f, paint);
        paint.setStyle(Paint.Style.STROKE); paint.setColor(checkedIndicator); paint.setStrokeWidth(1.5f);
        canvas.drawCircle(152f, 56f, 19f, paint);
        paint.setPathEffect(null);
        paint.setStyle(Paint.Style.STROKE); paint.setColor(primaryText); paint.setStrokeWidth(2.6f);
        canvas.drawPath(PATH_4, paint);
        paint.setPathEffect(null);
        paint.setStyle(Paint.Style.STROKE); paint.setColor(primaryText); paint.setStrokeWidth(2.3f);
        canvas.drawPath(PATH_5, paint);
        paint.setPathEffect(null);
        paint.setStyle(Paint.Style.STROKE); paint.setColor(accentText); paint.setStrokeWidth(1.7f);
        canvas.drawPath(PATH_6, paint);
        paint.setPathEffect(null);
        paint.setStyle(Paint.Style.STROKE); paint.setColor(accentText); paint.setStrokeWidth(1.7f);
        canvas.drawPath(PATH_7, paint);
        paint.setPathEffect(null);
        paint.setStyle(Paint.Style.STROKE); paint.setColor(accentText); paint.setStrokeWidth(1.7f);
        canvas.drawPath(PATH_8, paint);
        paint.setPathEffect(null);
        paint.setStyle(Paint.Style.STROKE); paint.setColor(accentText); paint.setStrokeWidth(1.7f);
        canvas.drawPath(PATH_9, paint);
        paint.setStyle(Paint.Style.FILL); paint.setColor(primaryText); paint.setTypeface(bold); paint.setTextSize(14f); canvas.drawText(getContext().getString(com.subhub.app.R.string.tour_scene_wallet), 180f, 51f, paint);
        paint.setStyle(Paint.Style.FILL); paint.setColor(secondaryText); paint.setTypeface(normal); paint.setTextSize(12f); drawFittedText(canvas, getContext().getString(com.subhub.app.R.string.tour_scene_wallet_help), 180f, 70f, 130f);
        paint.setPathEffect(null);
        paint.setStyle(Paint.Style.FILL); paint.setColor(dialogSurface); canvas.drawCircle(152f, 142f, 19f, paint);
        paint.setStyle(Paint.Style.STROKE); paint.setColor(defaultOutline); paint.setStrokeWidth(3f);
        canvas.drawCircle(152f, 142f, 19f, paint);
        paint.setPathEffect(null);
        paint.setStyle(Paint.Style.STROKE); paint.setColor(checkedIndicator); paint.setStrokeWidth(3f);
        canvas.drawPath(PATH_10, paint);
        paint.setPathEffect(null);
        paint.setStyle(Paint.Style.STROKE); paint.setColor(primaryText); paint.setStrokeWidth(2f);
        canvas.drawPath(PATH_11, paint);
        paint.setStyle(Paint.Style.FILL); paint.setColor(primaryText); paint.setTypeface(bold); paint.setTextSize(14f); canvas.drawText(getContext().getString(com.subhub.app.R.string.tour_scene_limits), 180f, 137f, paint);
        paint.setStyle(Paint.Style.FILL); paint.setColor(secondaryText); paint.setTypeface(normal); paint.setTextSize(12f); drawFittedText(canvas, getContext().getString(com.subhub.app.R.string.tour_scene_limits_help), 180f, 156f, 130f);
        canvas.restoreToCount(checkpoint);
    }

    private void drawFittedText(Canvas canvas, String text, float x, float baseline, float width) {
        float originalSize = paint.getTextSize();
        float measured = paint.measureText(text);
        if (measured > width) paint.setTextSize(originalSize * width / measured);
        canvas.drawText(text, x, baseline, paint);
        paint.setTextSize(originalSize);
    }
}
