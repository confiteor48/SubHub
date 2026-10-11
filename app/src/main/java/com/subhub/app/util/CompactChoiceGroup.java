package com.subhub.app.util;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.RadioGroup;

/** Radio semantics with equal-width wrapping and equal-height cards within each row. */
public final class CompactChoiceGroup extends RadioGroup {
    private int columns = 1;
    private int preferredColumns, minimumCellWidthDp = 88;
    private int[] rowHeights = new int[0];

    public CompactChoiceGroup(Context context, AttributeSet attributes) {
        super(context, attributes);
    }

    public void setPreferredColumns(int count, int minimumWidthDp) {
        if (count < 1 || minimumWidthDp < 48)
            throw new IllegalArgumentException("Invalid choice layout");
        preferredColumns = count;
        minimumCellWidthDp = minimumWidthDp;
        requestLayout();
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        if (MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED) {
            width = Math.round(320 * getResources().getDisplayMetrics().density);
        }
        int available = Math.max(0, width - getPaddingLeft() - getPaddingRight());
        int count = 0;
        for (int i = 0; i < getChildCount(); i++) if (getChildAt(i).getVisibility() != GONE) count++;
        float density = getResources().getDisplayMetrics().density;
        float fontScale = getResources().getConfiguration().fontScale;
        int minimum = Math.round(minimumCellWidthDp * density * Math.max(1f, fontScale));
        // Preserve whole words when labels need more width than the base cell size.
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (!(child instanceof android.widget.TextView) || child.getVisibility() == GONE) continue;
            android.widget.TextView label = (android.widget.TextView) child;
            LayoutParams params = (LayoutParams) child.getLayoutParams();
            int insets = label.getCompoundPaddingLeft() + label.getCompoundPaddingRight()
                    + params.leftMargin + params.rightMargin;
            for (String word : label.getText().toString().split("\\s+"))
                minimum = Math.max(minimum, (int) Math.ceil(label.getPaint().measureText(word)) + insets);
        }
        int desired =
                preferredColumns > 0
                        ? Math.min(preferredColumns, count)
                        : count == 4 ? 2 : Math.min(3, count);
        columns = Math.max(1, Math.min(desired, available / Math.max(1, minimum)));
        rowHeights = new int[(count + columns - 1) / columns];
        int cell = available / columns;
        int visible = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            LayoutParams params = (LayoutParams) child.getLayoutParams();
            int childWidth = Math.max(0, cell - params.leftMargin - params.rightMargin);
            child.measure(MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY),
                    getChildMeasureSpec(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                            0, params.height));
            int row = visible++ / columns;
            rowHeights[row] = Math.max(rowHeights[row],
                    child.getMeasuredHeight() + params.topMargin + params.bottomMargin);
        }
        // A multiline subtitle can make one choice taller. Stretch its row siblings
        // after natural measurement rather than only reserving an invisible tall row.
        visible = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            LayoutParams params = (LayoutParams) child.getLayoutParams();
            int row = visible++ / columns;
            child.measure(MeasureSpec.makeMeasureSpec(child.getMeasuredWidth(), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(Math.max(0,
                            rowHeights[row] - params.topMargin - params.bottomMargin), MeasureSpec.EXACTLY));
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
