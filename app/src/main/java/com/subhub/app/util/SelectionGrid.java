package com.subhub.app.util;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;

/** Independent choices in two equal columns, reflowing for narrow or enlarged text. */
public final class SelectionGrid extends LinearLayout {
    private int columns = 1;
    private int[] rowHeights = new int[0];

    public SelectionGrid(Context context, AttributeSet attributes) { super(context, attributes); }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        if (MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED) {
            width = Math.round(320 * getResources().getDisplayMetrics().density);
        }
        int available = Math.max(0, width - getPaddingLeft() - getPaddingRight());
        int count = 0;
        for (int i = 0; i < getChildCount(); i++) if (getChildAt(i).getVisibility() != GONE) count++;
        int minimum = Math.round(132 * getResources().getDisplayMetrics().density
                * Math.max(1f, getResources().getConfiguration().fontScale));
        columns = available >= minimum * 2 ? 2 : 1;
        rowHeights = new int[(count + columns - 1) / columns];
        int cell = available / columns;
        int visible = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            LayoutParams params = (LayoutParams) child.getLayoutParams();
            child.measure(MeasureSpec.makeMeasureSpec(Math.max(0,
                    cell - params.leftMargin - params.rightMargin), MeasureSpec.EXACTLY),
                    getChildMeasureSpec(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                            0, params.height));
            int row = visible++ / columns;
            rowHeights[row] = Math.max(rowHeights[row],
                    child.getMeasuredHeight() + params.topMargin + params.bottomMargin);
        }
        visible = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            LayoutParams params = (LayoutParams) child.getLayoutParams();
            int row = visible++ / columns;
            child.measure(MeasureSpec.makeMeasureSpec(child.getMeasuredWidth(), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(Math.max(0, rowHeights[row]
                            - params.topMargin - params.bottomMargin), MeasureSpec.EXACTLY));
        }
        int height = getPaddingTop() + getPaddingBottom();
        for (int row : rowHeights) height += row;
        setMeasuredDimension(resolveSize(width, widthSpec), resolveSize(height, heightSpec));
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int cell = Math.max(0, right - left - getPaddingLeft() - getPaddingRight()) / columns;
        int y = getPaddingTop();
        int visible = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            int row = visible / columns;
            int column = visible % columns;
            if (column == 0 && row > 0) y += rowHeights[row - 1];
            if (getLayoutDirection() == LAYOUT_DIRECTION_RTL) column = columns - 1 - column;
            LayoutParams params = (LayoutParams) child.getLayoutParams();
            int x = getPaddingLeft() + column * cell + params.leftMargin;
            int childY = y + params.topMargin;
            child.layout(x, childY, x + child.getMeasuredWidth(), childY + child.getMeasuredHeight());
            visible++;
        }
    }
}
