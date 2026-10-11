package com.subhub.app.settings;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.view.Gravity;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.subhub.app.R;

/** One app, one inclusion control. Rows recycle without retaining old callbacks. */
public final class IncludedAppRow extends LinearLayout {
    private final ImageView icon;
    private final TextView name;
    private final CheckBox included;

    public IncludedAppRow(Context context) {
        super(context);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setPadding(dp(8), dp(4), dp(4), dp(4));
        setBackgroundResource(R.drawable.bg_card);
        icon = new ImageView(context);
        icon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(icon, new LayoutParams(dp(28), dp(28)));
        name = new TextView(context);
        name.setTextColor(context.getColor(R.color.text_primary));
        com.subhub.app.util.UiIdentity.textSize(name, R.dimen.ui_text_body);
        name.setMaxLines(3);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        LayoutParams text = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1);
        text.setMarginStart(dp(8));
        text.setMarginEnd(dp(4));
        addView(name, text);
        included = new CheckBox(context);
        Drawable indicator = included.getButtonDrawable();
        if (indicator != null) {
            int extra = Math.max(0, dp(48) - indicator.getIntrinsicWidth());
            included.setButtonDrawable(null);
            included.setButtonDrawable(
                    new InsetDrawable(indicator, extra / 2, 0, extra - extra / 2, 0));
        }
        included.setButtonTintList(context.getColorStateList(R.color.toggle_tint));
        included.setPadding(0, 0, 0, 0);
        included.setMinWidth(dp(48));
        included.setMinHeight(dp(48));
        addView(included, new LayoutParams(dp(48), dp(48)));
        setOnClickListener(
                view -> {
                    if (included.isEnabled()) included.performClick();
                });
    }

    void bind(
            String label, String packageName, Drawable appIcon, boolean selected, boolean editing) {
        included.setOnCheckedChangeListener(null);
        icon.setImageDrawable(appIcon);
        name.setText(label);
        included.setContentDescription(
                getContext().getString(R.string.app_include_accessibility, label));
        included.setTag("included:" + packageName);
        included.setChecked(selected);
        included.setEnabled(editing);
    }

    public CheckBox choice() {
        return included;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
