package com.subhub.app.util;

import android.util.TypedValue;
import android.widget.TextView;

import androidx.annotation.DimenRes;

/** The same scalable typography tokens for XML and dynamically created UI. */
public final class UiIdentity {
    private UiIdentity() {}

    public static void textSize(TextView view, @DimenRes int size) {
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, view.getResources().getDimensionPixelSize(size));
    }

    public static void inputType(android.widget.EditText view, int type) {
        android.graphics.Typeface typeface = view.getTypeface();
        view.setInputType(type);
        view.setTypeface(typeface);
    }
}
