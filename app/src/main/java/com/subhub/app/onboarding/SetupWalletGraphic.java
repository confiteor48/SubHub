package com.subhub.app.onboarding;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import com.subhub.app.R;

/** Small native illustration, with no bitmap allocation or animation in the setup form. */
final class SetupWalletGraphic extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF shape = new RectF();
    private final String currencySymbol;

    SetupWalletGraphic(Context context) {
        super(context);
        currencySymbol = "EUR".equals(new com.subhub.app.penance.PenanceManager(context)
                .getCurrency()) ? "€" : "$";
        setTag("setup-wallet-graphic");
        setContentDescription(context.getString(R.string.tour_wallet_graphic));
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float scale = Math.min(getWidth() / 240f, getHeight() / 132f);
        canvas.save();
        canvas.translate(getWidth() / 2f, getHeight() / 2f);
        canvas.scale(scale, scale);
        coin(canvas, 62, -46, 18);
        canvas.save();
        canvas.rotate(-7);
        paint.setShader(new LinearGradient(-73, -44, 73, 44,
                getContext().getColor(R.color.control_pressed_surface),
                getContext().getColor(R.color.dialog_surface), Shader.TileMode.CLAMP));
        paint.setStyle(Paint.Style.FILL);
        shape.set(-73, -44, 73, 44);
        canvas.drawRoundRect(shape, 16, 16, paint);
        paint.setShader(null);
        paint.setColor(getContext().getColor(R.color.control_checked_outline));
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1);
        canvas.drawRoundRect(shape, 16, 16, paint);
        shape.inset(9, 9);
        paint.setAlpha(100);
        canvas.drawRoundRect(shape, 9, 9, paint);
        paint.setAlpha(255);
        shape.set(29, -16, 82, 17);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(getContext().getColor(R.color.control_checked_surface));
        canvas.drawRoundRect(shape, 8, 8, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(getContext().getColor(R.color.control_checked_indicator));
        canvas.drawRoundRect(shape, 8, 8, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(getContext().getColor(R.color.text_secondary));
        canvas.drawCircle(44, 0, 3.5f, paint);
        canvas.restore();
        coin(canvas, -82, 35, 17);
        canvas.restore();
    }

    private void coin(Canvas canvas, float x, float y, float radius) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(getContext().getColor(R.color.control_checked_outline));
        canvas.drawCircle(x, y, radius, paint);
        paint.setColor(getContext().getColor(R.color.control_checked_indicator));
        canvas.drawCircle(x, y, radius - 3, paint);
        paint.setColor(getContext().getColor(R.color.control_checked_text));
        paint.setTextSize(20);
        paint.setTextAlign(Paint.Align.CENTER);
        Paint.FontMetrics metrics = paint.getFontMetrics();
        canvas.drawText(currencySymbol, x, y - (metrics.ascent + metrics.descent) / 2, paint);
    }
}
