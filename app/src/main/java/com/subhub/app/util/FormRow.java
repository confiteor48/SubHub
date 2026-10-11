package com.subhub.app.util;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.subhub.app.R;

/** Paired form fields share label height; narrow, enlarged-text forms stack naturally. */
public final class FormRow extends LinearLayout {
    private int lastWidthSpec = Integer.MIN_VALUE, lastLabels;
    private float lastFontScale;
    public FormRow(Context context, AttributeSet attrs) { super(context, attrs); }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int available = MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight();
        int gap = getResources().getDimensionPixelSize(R.dimen.ui_gap_group);
        int minimum = Math.round(120 * getResources().getDisplayMetrics().density
                * Math.max(1f, getResources().getConfiguration().fontScale));
        boolean stacked = available < minimum * getChildCount() + gap * (getChildCount() - 1);
        setOrientation(stacked ? VERTICAL : HORIZONTAL);
        int labels = 1;
        for (int i = 0; i < getChildCount(); i++) {
            TextView label = label(getChildAt(i));
            labels = 31 * labels + (label == null ? 0 : label.getText().toString().hashCode());
        }
        float fontScale = getResources().getConfiguration().fontScale;
        boolean reflow = widthSpec != lastWidthSpec || fontScale != lastFontScale || labels != lastLabels;
        lastWidthSpec = widthSpec; lastFontScale = fontScale; lastLabels = labels;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            LayoutParams params = (LayoutParams) child.getLayoutParams();
            int width = stacked ? LayoutParams.MATCH_PARENT : 0;
            int weight = stacked ? 0 : 1;
            int start = !stacked && i > 0 ? gap : 0;
            int top = stacked && i > 0 ? gap : 0;
            if (params.width != width || params.weight != weight || params.getMarginStart() != start || params.topMargin != top) {
                params.width = width; params.weight = weight;
                params.setMarginStart(start); params.topMargin = top;
                child.setLayoutParams(params);
            }
            TextView label = label(child);
            if (label != null && reflow) label.setMinimumHeight(0);
        }
        super.onMeasure(widthSpec, heightSpec);
        if (!stacked && reflow) {
            int height = 0;
            for (int i = 0; i < getChildCount(); i++) {
                TextView label = label(getChildAt(i));
                if (label != null) height = Math.max(height, label.getMeasuredHeight());
            }
            for (int i = 0; i < getChildCount(); i++) {
                TextView label = label(getChildAt(i));
                if (label != null) label.setMinimumHeight(height);
            }
            super.onMeasure(widthSpec, heightSpec);
        }
    }

    private static TextView label(View field) {
        if (!(field instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) field;
        return group.getChildCount() > 0 && group.getChildAt(0) instanceof TextView
                ? (TextView) group.getChildAt(0) : null;
    }
}
