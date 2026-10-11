package com.subhub.app.util;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ComposeShader;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import com.subhub.app.R;
import java.util.Locale;
import java.util.function.IntConsumer;

/** Shared opaque-color picker. Changes remain local until the explicit confirmation. */
public final class ColorPickerDialog {
    private ColorPickerDialog() { }

    public static void show(Context context, String title, int initial, IntConsumer accepted) {
        LinearLayout body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(20 * context.getResources().getDisplayMetrics().density);
        body.setPadding(padding, padding / 2, padding, padding);
        TextView preview = new TextView(context);
        preview.setMinHeight(padding * 3);
        preview.setGravity(android.view.Gravity.CENTER);
        preview.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        com.subhub.app.util.UiIdentity.textSize(preview, R.dimen.ui_text_title);
        preview.setTag("color_preview");
        body.addView(preview);
        Wheel wheel = new Wheel(context);
        wheel.setTag("color_wheel");
        wheel.setContentDescription(context.getString(R.string.color_wheel_description));
        body.addView(wheel, new LinearLayout.LayoutParams(-1, padding * 11));
        String[] labels = {context.getString(R.string.color_value),
                context.getString(R.string.color_red), context.getString(R.string.color_green),
                context.getString(R.string.color_blue)};
        SeekBar[] sliders = new SeekBar[4];
        TextView[] captions = new TextView[4];
        int[] selected = {initial | 0xff000000};
        boolean[] updating = {false};
        Runnable render = () -> {
            updating[0] = true;
            float[] hsv = new float[3]; Color.colorToHSV(selected[0], hsv);
            wheel.setColor(selected[0]);
            int[] values = {Math.round(hsv[2] * 255), Color.red(selected[0]),
                    Color.green(selected[0]), Color.blue(selected[0])};
            for (int index = 0; index < 4; index++) {
                sliders[index].setProgress(values[index]);
                captions[index].setText(labels[index] + " · " + values[index]);
            }
            preview.setText(String.format(Locale.ROOT, "#%06X", selected[0] & 0xffffff));
            android.graphics.drawable.GradientDrawable swatch = new android.graphics.drawable.GradientDrawable();
            swatch.setColor(selected[0]);
            swatch.setCornerRadius(padding * .7f);
            swatch.setStroke(Math.max(1, padding / 20), context.getColor(R.color.outline_default));
            preview.setBackground(swatch);
            preview.setTextColor(androidx.core.graphics.ColorUtils.calculateContrast(Color.BLACK, selected[0])
                    >= androidx.core.graphics.ColorUtils.calculateContrast(Color.WHITE, selected[0])
                    ? Color.BLACK : Color.WHITE);
            updating[0] = false;
        };
        for (int index = 0; index < 4; index++) {
            final int channel = index;
            captions[index] = (TextView) android.view.LayoutInflater.from(context)
                    .inflate(R.layout.view_form_label, body, false);
            body.addView(captions[index]);
            SeekBar slider = new SeekBar(context);
            sliders[index] = slider; slider.setMax(255);
            slider.setKeyProgressIncrement(1);
            slider.setFocusable(true);
            slider.setFocusableInTouchMode(true);
            slider.setMinimumHeight(context.getResources().getDimensionPixelSize(R.dimen.control_min_height));
            slider.setProgressTintList(android.content.res.ColorStateList.valueOf(context.getColor(R.color.accent)));
            slider.setThumbTintList(android.content.res.ColorStateList.valueOf(context.getColor(R.color.accent_text)));
            slider.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(context.getColor(R.color.outline_subtle)));
            slider.setContentDescription(labels[index]);
            slider.setTag("color_channel:" + index);
            body.addView(slider);
            slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                    if (updating[0] || !fromUser) return;
                    int color = selected[0];
                    if (channel == 0) {
                        float[] hsv = new float[3]; Color.colorToHSV(color, hsv);
                        hsv[2] = value / 255f; selected[0] = Color.HSVToColor(hsv);
                    } else selected[0] = Color.rgb(channel == 1 ? value : Color.red(color),
                            channel == 2 ? value : Color.green(color), channel == 3 ? value : Color.blue(color));
                    render.run();
                }
                @Override public void onStartTrackingTouch(SeekBar bar) { }
                @Override public void onStopTrackingTouch(SeekBar bar) { }
            });
        }
        wheel.listener = color -> { selected[0] = color; render.run(); };
        render.run();
        ScrollView scroll = new ScrollView(context); scroll.addView(body);
        com.subhub.app.util.ThemedDialogs.builder(context).setTitle(title).setView(scroll)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> accepted.accept(selected[0]))
                .show();
    }

    public static final class Wheel extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float[] hsv = {0, 0, 1};
        private Shader spectrum;
        private float cx, cy, radius;
        private IntConsumer listener;
        public Wheel(Context context) { super(context); setClickable(true); }
        void setColor(int color) { Color.colorToHSV(color, hsv); invalidate(); }
        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            cx = width / 2f; cy = height / 2f;
            radius = Math.max(1, Math.min(width, height) / 2f - 12 * getResources().getDisplayMetrics().density);
            spectrum = new ComposeShader(new SweepGradient(cx, cy,
                    new int[]{Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE,
                            Color.MAGENTA, Color.RED}, null),
                    new RadialGradient(cx, cy, radius, Color.WHITE, Color.TRANSPARENT, Shader.TileMode.CLAMP),
                    PorterDuff.Mode.SRC_OVER);
        }
        @Override protected void onDraw(Canvas canvas) {
            paint.setStyle(Paint.Style.FILL); paint.setShader(spectrum);
            canvas.drawCircle(cx, cy, radius, paint); paint.setShader(null);
            paint.setColor(Color.BLACK); paint.setAlpha(Math.round((1 - hsv[2]) * 255));
            canvas.drawCircle(cx, cy, radius, paint); paint.setAlpha(255);
            float angle = (float) Math.toRadians(hsv[0]);
            float x = cx + (float) Math.cos(angle) * hsv[1] * radius;
            float y = cy + (float) Math.sin(angle) * hsv[1] * radius;
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(4);
            paint.setColor(Color.BLACK); canvas.drawCircle(x, y, 10, paint);
            paint.setStrokeWidth(2); paint.setColor(Color.WHITE); canvas.drawCircle(x, y, 10, paint);
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            if (!isEnabled()) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_MOVE:
                    getParent().requestDisallowInterceptTouchEvent(true);
                    float dx = event.getX() - cx, dy = event.getY() - cy;
                    hsv[0] = ((float) Math.toDegrees(Math.atan2(dy, dx)) + 360) % 360;
                    hsv[1] = Math.min(1, (float) Math.hypot(dx, dy) / radius);
                    // Black has no visible hue; picking the wheel reveals the chosen color.
                    if (hsv[2] == 0) hsv[2] = 1;
                    if (listener != null) listener.accept(Color.HSVToColor(hsv));
                    invalidate(); return true;
                case MotionEvent.ACTION_UP:
                    getParent().requestDisallowInterceptTouchEvent(false); performClick(); return true;
                case MotionEvent.ACTION_CANCEL:
                    getParent().requestDisallowInterceptTouchEvent(false); return true;
                default: return false;
            }
        }
        @Override public boolean performClick() { super.performClick(); return true; }
    }
}
