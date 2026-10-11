package com.subhub.app.util;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.animation.DecelerateInterpolator;

import com.google.android.material.switchmaterial.SwitchMaterial;
import com.subhub.app.R;

/** A native, accessible switch with an explicit status pill instead of a sliding thumb. */
public final class StateToggle extends SwitchMaterial {
    private final Paint statusPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF statusBounds = new RectF();
    private final Paint feedbackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float touchX, touchY, feedbackProgress;
    private boolean touchFeedback;
    private ValueAnimator releaseFeedback;

    public StateToggle(Context context) {
        this(context, null);
    }

    public StateToggle(Context context, AttributeSet attributes) {
        super(context, attributes);
        setThumbDrawable(null);
        setTrackDrawable(null);
        setShowText(false);
        setTextOn(context.getString(R.string.control_state_on));
        setTextOff(context.getString(R.string.control_state_off));
        setSwitchPadding(dp(12));
        setMinHeight(dp(48));
        // SwitchCompat's stock ripple is tied to its hidden sliding thumb. Draw
        // quick, bounded feedback at the actual tap instead of that stale hotspot.
        setBackground(null);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (isEnabled() && event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            clearTouchFeedback();
            touchX = event.getX();
            touchY = event.getY();
            feedbackProgress = 0;
            touchFeedback = true;
            invalidate(); // The press appears immediately; only release fades.
        }
        boolean handled = super.onTouchEvent(event);
        if (event.getActionMasked() == MotionEvent.ACTION_CANCEL || !handled) {
            clearTouchFeedback();
        } else if (event.getActionMasked() == MotionEvent.ACTION_UP && touchFeedback) {
            if (!ValueAnimator.areAnimatorsEnabled()) clearTouchFeedback();
            else {
                releaseFeedback = ValueAnimator.ofFloat(0, 1);
                releaseFeedback.setDuration(90);
                releaseFeedback.setInterpolator(new DecelerateInterpolator());
                releaseFeedback.addUpdateListener(
                        animation -> {
                            feedbackProgress = (float) animation.getAnimatedValue();
                            if (feedbackProgress == 1) touchFeedback = false;
                            invalidate();
                        });
                releaseFeedback.start();
            }
        }
        return handled;
    }

    private void clearTouchFeedback() {
        if (releaseFeedback != null) {
            releaseFeedback.cancel();
            releaseFeedback = null;
        }
        touchFeedback = false;
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        clearTouchFeedback();
        super.onDetachedFromWindow();
    }

    @Override
    public void setChecked(boolean checked) {
        super.setChecked(checked);
        // There is no sliding thumb to animate. Finish SwitchCompat's otherwise
        // invisible 250ms animation without delaying the native checked state.
        jumpDrawablesToCurrentState();
    }

    @Override
    public void onMeasure(int widthSpec, int heightSpec) {
        statusPaint.setTextSize(12 * getResources().getDisplayMetrics().scaledDensity);
        int statusWidth =
                Math.max(
                        dp(64),
                        Math.round(
                                        Math.max(
                                                statusPaint.measureText(getTextOn().toString()),
                                                statusPaint.measureText(getTextOff().toString())))
                                + dp(24));
        if (getSwitchMinWidth() != statusWidth) setSwitchMinWidth(statusWidth);
        super.onMeasure(widthSpec, heightSpec);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int width = getSwitchMinWidth();
        int height = Math.max(dp(32), Math.round(statusPaint.getTextSize()) + dp(14));
        float left =
                getLayoutDirection() == LAYOUT_DIRECTION_RTL
                        ? getPaddingLeft()
                        : getWidth() - getPaddingRight() - width;
        float top = (getHeight() - height) / 2f;
        float inset = dp(1) / 2f;
        statusBounds.set(left + inset, top + inset, left + width - inset, top + height - inset);
        statusPaint.setStyle(Paint.Style.FILL);
        statusPaint.setColor(
                getContext()
                        .getColor(
                                isChecked()
                                        ? R.color.control_checked_surface
                                        : R.color.surface_card_raised));
        statusPaint.setAlpha(isEnabled() ? 255 : 110);
        canvas.drawRoundRect(statusBounds, dp(10), dp(10), statusPaint);
        statusPaint.setStyle(Paint.Style.STROKE);
        statusPaint.setStrokeWidth(dp(1));
        statusPaint.setColor(
                getContext()
                        .getColor(
                                isChecked()
                                        ? R.color.control_checked_outline
                                        : R.color.text_muted));
        statusPaint.setAlpha(isEnabled() ? 255 : 110);
        canvas.drawRoundRect(statusBounds, dp(10), dp(10), statusPaint);
        statusPaint.setStyle(Paint.Style.FILL);
        statusPaint.setTextAlign(Paint.Align.CENTER);
        statusPaint.setColor(
                getContext()
                        .getColor(
                                isChecked()
                                        ? R.color.control_checked_text
                                        : R.color.text_secondary));
        statusPaint.setAlpha(isEnabled() ? 255 : 110);
        Paint.FontMetrics metrics = statusPaint.getFontMetrics();
        float baseline = statusBounds.centerY() - (metrics.ascent + metrics.descent) / 2f;
        canvas.drawText(
                (isChecked() ? getTextOn() : getTextOff()).toString(),
                statusBounds.centerX(),
                baseline,
                statusPaint);
        if (isEnabled() && (isFocused() || isHovered())) {
            feedbackPaint.setColor(getContext().getColor(R.color.control_focus));
            feedbackPaint.setAlpha(170);
            feedbackPaint.setStyle(Paint.Style.STROKE);
            feedbackPaint.setStrokeWidth(dp(1));
            canvas.drawRoundRect(
                    dp(1),
                    dp(1),
                    getWidth() - dp(1),
                    getHeight() - dp(1),
                    dp(10),
                    dp(10),
                    feedbackPaint);
        }
        if (isEnabled() && touchFeedback) {
            feedbackPaint.setColor(getContext().getColor(R.color.control_focus));
            feedbackPaint.setStyle(Paint.Style.FILL);
            feedbackPaint.setAlpha(Math.round(40 * (1 - feedbackProgress)));
            int saved = canvas.save();
            canvas.clipRect(0, 0, getWidth(), getHeight());
            canvas.drawCircle(touchX, touchY, dp(18 + 8 * feedbackProgress), feedbackPaint);
            canvas.restoreToCount(saved);
        }
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
