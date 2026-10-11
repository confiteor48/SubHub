package com.subhub.app.censor.android;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.graphics.RectF;
import android.os.Parcelable;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import androidx.appcompat.widget.AppCompatTextView;
import java.util.concurrent.atomic.AtomicLong;

/** Synthetic provider content only; never included in a signed release or populated from user data. */
public final class NativeTextFixtureActivity extends Activity {
    public final AtomicLong contentGeneration = new AtomicLong();
    public ProviderText first, second;
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setWindowAnimations(0); overridePendingTransition(0, 0);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(android.graphics.Color.WHITE); root.setPadding(32, 48, 32, 32);
        first = new ProviderText("native-first"); second = new ProviderText("native-second");
        first.setText("send nudes"); second.setText("touch yourself");
        root.addView(first); root.addView(second); setContentView(root);
    }
    public void content(String firstText, String secondText) {
        contentGeneration.incrementAndGet(); first.setText(firstText); second.setText(secondText);
    }
    public final class ProviderText extends AppCompatTextView {
        public String providerIdentity;
        public boolean swapDuringGeometry;
        public boolean omitMatchedLastLine, offscreenGeometry, noGeometry, omitNonGlyphUnits;
        ProviderText(String identity) {
            super(NativeTextFixtureActivity.this); providerIdentity = identity;
            setTextSize(20); setTextColor(android.graphics.Color.BLACK); setPadding(0, 12, 0, 12);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        }
        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) info.setUniqueId(providerIdentity);
            if (noGeometry) info.setAvailableExtraData(java.util.Collections.emptyList());
        }
        @Override public void addExtraDataToAccessibilityNodeInfo(AccessibilityNodeInfo info, String key, Bundle arguments) {
            if (swapDuringGeometry) {
                swapDuringGeometry = false; contentGeneration.incrementAndGet(); setText("porn c2");
                info.setText(getText());
            }
            super.addExtraDataToAccessibilityNodeInfo(info, key, arguments);
            if (AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY.equals(key)) {
                Parcelable[] characters = info.getExtras().getParcelableArray(key);
                if (characters != null) {
                    int lastLine = getText().toString().lastIndexOf('\n') + 1;
                    for (int i = 0; i < characters.length; i++) {
                        char unit = getText().charAt(i);
                        int type = Character.getType(unit);
                        boolean nonGlyph = Character.isWhitespace(unit) || Character.isSpaceChar(unit)
                                || Character.isLowSurrogate(unit) || type == Character.FORMAT
                                || type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK
                                || type == Character.ENCLOSING_MARK;
                        if ((omitMatchedLastLine && i >= lastLine) || (omitNonGlyphUnits && nonGlyph)) characters[i] = null;
                        else if (offscreenGeometry && characters[i] instanceof RectF) {
                            RectF glyph = new RectF((RectF) characters[i]);
                            glyph.offset(0, getResources().getDisplayMetrics().heightPixels + 100);
                            characters[i] = glyph;
                        }
                    }
                    info.getExtras().putParcelableArray(key, characters);
                }
            }
        }
    }
}
