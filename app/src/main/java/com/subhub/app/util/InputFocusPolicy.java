package com.subhub.app.util;

import android.app.Dialog;
import android.app.Activity;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

/** Ordinary forms open for reading; only an explicit Dom credential prompt opens the keyboard. */
public final class InputFocusPolicy {
    private InputFocusPolicy() {}

    public static void openForReading(View root) {
        if (root instanceof ViewGroup)
            ((ViewGroup) root).setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        root.setFocusableInTouchMode(true);
        root.requestFocus();
    }

    public static void focusController(Dialog dialog, EditText input) {
        Window window = dialog.getWindow();
        if (window == null) return;
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        input.requestFocus();
        WindowCompat.getInsetsController(window, input).show(WindowInsetsCompat.Type.ime());
    }

    public static void openActivityForReading(Activity activity) {
        Window window = activity.getWindow();
        View content = activity.findViewById(android.R.id.content);
        View decor = window.getDecorView();
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                | (window.getAttributes().softInputMode & WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST));
        openForReading(content);
        Runnable hide = () -> {
            openForReading(content);
            WindowCompat.getInsetsController(window, content).hide(WindowInsetsCompat.Type.ime());
        };
        if (decor.hasWindowFocus()) hide.run();
        else decor.getViewTreeObserver().addOnWindowFocusChangeListener(
                new android.view.ViewTreeObserver.OnWindowFocusChangeListener() {
                    @Override public void onWindowFocusChanged(boolean focused) {
                        if (!focused) return;
                        decor.getViewTreeObserver().removeOnWindowFocusChangeListener(this);
                        hide.run();
                    }
                });
    }

    public static void showError(EditText input, String message) {
        input.setError(message);
        input.requestRectangleOnScreen(new Rect(0, 0, input.getWidth(), input.getHeight()), true);
    }
}
