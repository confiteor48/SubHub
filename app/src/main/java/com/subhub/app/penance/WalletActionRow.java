package com.subhub.app.penance;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.view.ViewCompat;

import com.subhub.app.R;

/** A compact Wallet destination with a single accessible touch target. */
final class WalletActionRow extends LinearLayout {
    private final TextView summary;

    WalletActionRow(Context context, int title, int icon) {
        super(context);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setPadding(dp(16), dp(8), dp(16), dp(8));
        setMinimumHeight(dp(56));
        setClickable(true);
        setFocusable(true);
        android.util.TypedValue selectable = new android.util.TypedValue();
        context.getTheme()
                .resolveAttribute(android.R.attr.selectableItemBackground, selectable, true);
        setBackgroundResource(selectable.resourceId);
        ImageView image = new ImageView(context);
        image.setImageResource(icon);
        image.setImageTintList(
                android.content.res.ColorStateList.valueOf(context.getColor(R.color.accent_text)));
        image.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(image, new LayoutParams(dp(24), dp(24)));
        LinearLayout labels = new LinearLayout(context);
        labels.setOrientation(VERTICAL);
        LayoutParams labelParams = new LayoutParams(0, -2, 1);
        labelParams.setMarginStart(dp(14));
        addView(labels, labelParams);
        TextView heading = new TextView(context);
        heading.setText(title);
        com.subhub.app.util.UiIdentity.textSize(heading, R.dimen.ui_text_row_title);
        heading.setTypeface(null, Typeface.BOLD);
        heading.setTextColor(context.getColor(R.color.text_primary));
        labels.addView(heading, new LayoutParams(-1, -2));
        summary = new TextView(context);
        com.subhub.app.util.UiIdentity.textSize(summary, R.dimen.ui_text_caption);
        summary.setTextColor(context.getColor(R.color.text_secondary));
        LayoutParams details = new LayoutParams(-1, -2);
        details.topMargin = dp(2);
        labels.addView(summary, details);
        ImageView arrow = new ImageView(context);
        arrow.setImageResource(R.drawable.ic_wallet_chevron);
        arrow.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        LayoutParams arrowParams = new LayoutParams(dp(16), dp(16));
        arrowParams.setMarginStart(dp(12));
        addView(arrow, arrowParams);
        ViewCompat.setScreenReaderFocusable(this, true);
    }

    TextView summary() {
        return summary;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
