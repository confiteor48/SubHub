package com.subhub.app.appmode;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.subhub.app.R;

/** One app's retained daily allowance and live recorded usage, with no nested card surface. */
final class AppLimitRow extends LinearLayout {
    final EditText allowance;
    private final TextView usage;
    private final ProgressBar progress;
    private String receipt = "";

    AppLimitRow(Context context, String packageName, String name, Drawable icon, String minutes) {
        super(context);
        setOrientation(VERTICAL);
        setPadding(0, dp(8), 0, dp(8));
        boolean large = getResources().getConfiguration().fontScale >= 1.5f
                || getResources().getConfiguration().screenWidthDp < 340;
        LinearLayout header = new LinearLayout(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        addView(header, new LayoutParams(-1, -2));
        ImageView image = new ImageView(context);
        image.setImageDrawable(icon);
        image.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        header.addView(image, new LayoutParams(dp(28), dp(28)));
        LinearLayout labels = new LinearLayout(context);
        labels.setOrientation(VERTICAL);
        LayoutParams labelParams = new LayoutParams(0, -2, 1);
        labelParams.setMarginStart(dp(8));
        labelParams.setMarginEnd(dp(8));
        header.addView(labels, labelParams);
        TextView title = text(name, 14, false);
        title.setTypeface(null, Typeface.BOLD);
        labels.addView(title, new LayoutParams(-1, -2));
        usage = text("", 11, true);
        LayoutParams usageParams = new LayoutParams(-1, -2);
        usageParams.topMargin = dp(2);
        labels.addView(usage, usageParams);
        allowance = new androidx.appcompat.widget.AppCompatEditText(context);
        allowance.setId(View.generateViewId());
        allowance.setTag("limit:" + packageName);
        allowance.setContentDescription(
                context.getString(R.string.app_timer_allowance_accessibility, name));
        allowance.setBackgroundResource(R.drawable.bg_input);
        com.subhub.app.util.UiIdentity.inputType(allowance, InputType.TYPE_CLASS_NUMBER);
        allowance.setFilters(new InputFilter[] {new InputFilter.LengthFilter(4)});
        allowance.setSingleLine(true);
        allowance.setIncludeFontPadding(false);
        com.subhub.app.util.UiIdentity.textSize(allowance, R.dimen.ui_text_body);
        allowance.setGravity(Gravity.CENTER);
        allowance.setText(minutes);
        LayoutParams inputParams = new LayoutParams(dp(88), dp(48));
        if (large) {
            inputParams.gravity = Gravity.END;
            inputParams.topMargin = dp(4);
            addView(allowance, inputParams);
        } else header.addView(allowance, inputParams);
        progress = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgressTintList(
                android.content.res.ColorStateList.valueOf(context.getColor(R.color.accent_mid)));
        progress.setProgressBackgroundTintList(
                android.content.res.ColorStateList.valueOf(
                        context.getColor(R.color.outline_subtle)));
        LayoutParams progressParams = new LayoutParams(-1, dp(3));
        progressParams.topMargin = dp(4);
        labels.addView(progress, progressParams);
    }

    void renderUsage(String description, int percent, boolean limited) {
        String next = description + "|" + percent + "|" + limited;
        if (next.equals(receipt)) return;
        receipt = next;
        usage.setText(description);
        progress.setVisibility(limited ? VISIBLE : GONE);
        progress.setProgress(percent);
        progress.setContentDescription(description);
    }

    private TextView text(String value, int size, boolean secondary) {
        TextView label = new TextView(getContext());
        label.setText(value);
        com.subhub.app.util.UiIdentity.textSize(label,
                size == 14 ? R.dimen.ui_text_row_title : R.dimen.ui_text_caption);
        label.setTextColor(
                getContext().getColor(secondary ? R.color.text_secondary : R.color.text_primary));
        return label;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
