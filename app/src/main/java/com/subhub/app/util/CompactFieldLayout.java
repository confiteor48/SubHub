package com.subhub.app.util;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import java.util.ArrayList;
import java.util.List;

/** Two-column form flow: long fields span both columns and hidden fields leave no holes. */
public final class CompactFieldLayout extends ViewGroup {
    private final List<View> visible = new ArrayList<>();
    private final List<Integer> spans = new ArrayList<>();
    private int columns = 1;
    private int gap;
    private int lastWidthSpec = Integer.MIN_VALUE, lastLabels;
    private float lastFontScale;

    public CompactFieldLayout(Context context) { super(context); }

    public void addField(View field, boolean fullWidth) {
        LayoutParams params = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        params.fullWidth = fullWidth;
        addView(field, params);
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        float density = getResources().getDisplayMetrics().density;
        if (MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED) width = Math.round(320 * density);
        gap = Math.round(8 * density);
        int available = Math.max(0, width - getPaddingLeft() - getPaddingRight());
        int minimum = Math.round(120 * density * Math.max(1f, getResources().getConfiguration().fontScale));
        columns = available >= minimum * 2 + gap ? 2 : 1;
        int cell = (available - gap * (columns - 1)) / columns;
        int labels = 1;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            android.widget.TextView label = fieldLabel(child);
            labels = 31 * labels + child.getVisibility();
            if (label != null) labels = 31 * labels + label.getText().toString().hashCode();
        }
        float fontScale = getResources().getConfiguration().fontScale;
        if (lastWidthSpec != widthSpec || lastFontScale != fontScale || lastLabels != labels) {
            for (int i = 0; i < getChildCount(); i++) {
                android.widget.TextView label = fieldLabel(getChildAt(i));
                if (label != null) label.setMinimumHeight(0);
            }
        }
        lastWidthSpec = widthSpec; lastFontScale = fontScale; lastLabels = labels;
        visible.clear(); spans.clear();
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            LayoutParams params = (LayoutParams) child.getLayoutParams();
            int span = params.fullWidth || minimumLabelWidth(child) > cell ? columns : 1;
            child.measure(MeasureSpec.makeMeasureSpec(span == columns ? available : cell, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            visible.add(child); spans.add(span);
        }
        if (columns == 2) for (int i = 0; i + 1 < visible.size();) {
            if (spans.get(i) != 1 || spans.get(i + 1) != 1) { i++; continue; }
            View first = visible.get(i), second = visible.get(i + 1);
            android.widget.TextView a = fieldLabel(first), b = fieldLabel(second);
            if (a != null && b != null) {
                int height = Math.max(a.getMeasuredHeight(), b.getMeasuredHeight());
                a.setMinimumHeight(height); b.setMinimumHeight(height);
                first.measure(MeasureSpec.makeMeasureSpec(cell, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                second.measure(MeasureSpec.makeMeasureSpec(cell, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            }
            i += 2;
        }
        int used = 0, rowHeight = 0, height = getPaddingTop();
        for (int i = 0; i < visible.size(); i++) {
            View child = visible.get(i);
            int span = spans.get(i);
            if (used + span > columns) { height += rowHeight + gap; used = 0; rowHeight = 0; }
            rowHeight = Math.max(rowHeight, child.getMeasuredHeight());
            used += span;
            if (used == columns) { height += rowHeight + gap; used = 0; rowHeight = 0; }
        }
        height += rowHeight;
        if (used == 0 && !visible.isEmpty()) height -= gap;
        height += getPaddingBottom();
        setMeasuredDimension(resolveSize(width, widthSpec), resolveSize(height, heightSpec));
    }

    private static android.widget.TextView fieldLabel(View view) {
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        if (group.getChildCount() < 2) return null;
        View first = group.getChildAt(0);
        return first instanceof android.widget.TextView && !(first instanceof android.widget.CompoundButton)
                && !(first instanceof android.widget.EditText) ? (android.widget.TextView) first : null;
    }

    private int minimumLabelWidth(View view) {
        int minimum = 0;
        if (view instanceof android.widget.TextView && !(view instanceof android.widget.EditText)) {
            android.widget.TextView label = (android.widget.TextView) view;
            int insets = label.getCompoundPaddingLeft() + label.getCompoundPaddingRight();
            if (label instanceof StateToggle) {
                StateToggle toggle = (StateToggle) label;
                int status = Math.max(toggle.getSwitchMinWidth(), Math.round(64 * getResources().getDisplayMetrics().density));
                insets = label.getPaddingLeft() + label.getPaddingRight() + status + toggle.getSwitchPadding();
            }
            for (String word : label.getText().toString().split("\\s+"))
                minimum = Math.max(minimum, (int) Math.ceil(label.getPaint().measureText(word)) + insets);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++)
                minimum = Math.max(minimum, minimumLabelWidth(group.getChildAt(i)));
            minimum += group.getPaddingLeft() + group.getPaddingRight();
        }
        return minimum;
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int available = Math.max(0, right - left - getPaddingLeft() - getPaddingRight());
        int cell = (available - gap * (columns - 1)) / columns;
        int used = 0, y = getPaddingTop(), rowHeight = 0;
        for (int i = 0; i < visible.size(); i++) {
            View child = visible.get(i);
            int span = spans.get(i);
            if (used + span > columns) { y += rowHeight + gap; used = 0; rowHeight = 0; }
            int x = getPaddingLeft() + used * (cell + gap);
            if (getLayoutDirection() == LAYOUT_DIRECTION_RTL) x = getPaddingLeft() + available - (x - getPaddingLeft()) - child.getMeasuredWidth();
            child.layout(x, y, x + child.getMeasuredWidth(), y + child.getMeasuredHeight());
            rowHeight = Math.max(rowHeight, child.getMeasuredHeight());
            used += span;
            if (used == columns) { y += rowHeight + gap; used = 0; rowHeight = 0; }
        }
    }

    public static final class LayoutParams extends ViewGroup.LayoutParams {
        boolean fullWidth;
        public LayoutParams(int width, int height) { super(width, height); }
    }
}
