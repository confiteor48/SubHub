package com.subhub.app.util;

import android.content.Context;
import android.view.*;
import android.widget.*;

import androidx.core.view.ViewCompat;

import com.subhub.app.R;

/** Shared expandable settings card, with one header, summary and content owner. */
public final class ExpandableSectionView extends LinearLayout {
    private final LinearLayout header, content;
    private final TextView summary, arrow;
    private boolean summaryVisible = true;
    private boolean summaryWhenExpanded;
    private boolean collapsible = true;

    public ExpandableSectionView(Context context, String key, int title, int icon) {
        super(context);
        setOrientation(VERTICAL);
        setBackgroundResource(R.drawable.bg_card);
        int side = getResources().getDimensionPixelSize(R.dimen.ui_gap_group);
        int vertical = getResources().getDimensionPixelSize(R.dimen.ui_gap_control);
        setPadding(side, vertical, side, vertical);
        LayoutInflater.from(context).inflate(R.layout.view_expandable_section, this, true);
        header = findViewById(R.id.section_header);
        content = findViewById(R.id.section_content);
        summary = findViewById(R.id.section_summary);
        arrow = findViewById(R.id.section_arrow);
        ((TextView) findViewById(R.id.section_title)).setText(title);
        ((ImageView) findViewById(R.id.section_icon)).setImageResource(icon);
        header.setTag("settings:" + key);
        ViewCompat.setScreenReaderFocusable(header, true);
        setExpanded(false);
    }

    public LinearLayout header() {
        return header;
    }

    public LinearLayout content() {
        return content;
    }

    public TextView summary() {
        return summary;
    }

    public void setSummaryVisible(boolean visible) {
        summaryVisible = visible;
    }

    public void setSummaryWhenExpanded(boolean visible) {
        summaryWhenExpanded = visible;
    }

    public void setCollapsible(boolean collapsible) {
        this.collapsible = collapsible;
        header.setClickable(collapsible);
        header.setFocusable(collapsible);
        ViewCompat.setScreenReaderFocusable(header, collapsible);
        ViewCompat.setAccessibilityHeading(findViewById(R.id.section_title), !collapsible);
    }

    public void setExpanded(boolean expanded) {
        expanded = expanded || !collapsible;
        content.setVisibility(expanded ? VISIBLE : GONE);
        arrow.setText(expanded ? "⌃" : "⌄");
        arrow.setVisibility(collapsible ? VISIBLE : GONE);
        summary.setVisibility(summaryVisible && (!expanded || !collapsible || summaryWhenExpanded) ? VISIBLE : GONE);
        ViewCompat.setStateDescription(
                header,
                !collapsible ? null : getContext()
                        .getString(
                                expanded
                                        ? R.string.keyholder_expanded
                                        : R.string.keyholder_collapsed));
    }

    public void addControls(View... controls) {
        for (View control : controls) {
            if (control.getParent() != null) ((ViewGroup) control.getParent()).removeView(control);
            if (control instanceof LinearLayout) {
                control.setBackground(null);
                control.setPadding(0, 0, 0, 0);
            }
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            params.bottomMargin = getResources().getDimensionPixelSize(R.dimen.ui_gap_control);
            content.addView(control, params);
        }
    }

    public void reveal() {
        post(
                () ->
                        requestRectangleOnScreen(
                                new android.graphics.Rect(
                                        0, 0, getWidth(), Math.min(getHeight(), dp(280))),
                                false));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
