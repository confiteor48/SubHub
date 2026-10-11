package com.subhub.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.core.view.ViewCompat;

import com.subhub.app.appmode.AppModeActivity;
import com.subhub.app.atmosphere.AtmosphereActivity;
import com.subhub.app.penance.PenanceActivity;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.settings.FeatureModuleManager;
import com.subhub.app.settings.GlobalSettingsActivity;
import com.subhub.app.settings.SettingsActivity;
import com.subhub.app.util.PrimaryHeader;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class PrimaryHeaderContractTest {
    private Context context;

    @Before public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        ControllerPinManager.enterDomMode();
        new FeatureModuleManager(context).save(true, true, true);
    }

    @Test public void everyPrimaryDestinationUsesTheSameHeaderGeometry() {
        assertHeader(MainActivity.class, R.string.app_name);
        assertHeader(SettingsActivity.class, R.string.censor_header_title);
        assertHeader(AppModeActivity.class, R.string.app_mode_title);
        assertHeader(PenanceActivity.class, R.string.penance_title);
        assertHeader(AtmosphereActivity.class, R.string.atmosphere_title);
        assertHeader(GlobalSettingsActivity.class, R.string.global_settings_title);
    }

    @Test public void primaryHeaderFitsNarrowPhonesAndEnlargedText() {
        int[] widths = {320, 360, 411, 600, 840};
        float[] fontScales = {1f, 1.3f, 2f};
        for (int width : widths) {
            for (float fontScale : fontScales) {
                Configuration configuration = new Configuration(context.getResources()
                        .getConfiguration());
                configuration.screenWidthDp = width;
                configuration.smallestScreenWidthDp = width;
                configuration.fontScale = fontScale;
                Context themed = new ContextThemeWrapper(
                        context.createConfigurationContext(configuration), R.style.Theme_SubHub);
                ViewGroup header = (ViewGroup) LayoutInflater.from(themed)
                        .inflate(R.layout.view_primary_header, null, false);
                PrimaryHeader.bind(header, R.drawable.ic_atmosphere,
                        R.string.atmosphere_title, 0);
                int pageMargin = themed.getResources().getDimensionPixelSize(R.dimen.page_margin);
                int headerWidth = dp(themed, width) - 2 * pageMargin;
                header.measure(View.MeasureSpec.makeMeasureSpec(headerWidth, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                header.layout(0, 0, headerWidth, header.getMeasuredHeight());
                assertTextFits(header, header.findViewById(R.id.primary_header_title));
                assertEquals("Primary titles should not split a word at " + width
                                + "dp / font " + fontScale, 1,
                        ((TextView) header.findViewById(R.id.primary_header_title)).getLineCount());
                assertEquals(View.GONE,
                        header.findViewById(R.id.primary_header_subtitle).getVisibility());
                assertTextFits(header, header.findViewById(R.id.button_edit_lock));
                assertTrue(header.findViewById(R.id.button_edit_lock).getHeight()
                        >= dp(themed, 48));
                assertTrue(header.getHeight() >= themed.getResources()
                        .getDimensionPixelSize(R.dimen.primary_header_height));
                if (width == 411 && fontScale == 1f) {
                    assertEquals(dp(themed, 64), header.getHeight());
                }
            }
        }
    }

    @Test public void subModeKeepsCompactHeadersWithoutRedundantSubtitles() {
        ControllerPinManager.enterSubMode();
        try {
            assertHeader(MainActivity.class, R.string.app_name);
            assertHeader(GlobalSettingsActivity.class, R.string.global_settings_title);
            assertHeader(PenanceActivity.class, R.string.penance_title);
        } finally {
            ControllerPinManager.enterDomMode();
        }
    }

    @Test public void secondaryHeadersFitEveryPageTitleAndControllerState() {
        int[] titles = {R.string.commitment_title, R.string.custom_images_title,
                R.string.diagnostics_title, R.string.export_title, R.string.export_workspace_title,
                R.string.authenticator_title, R.string.keyholder_handover_title, R.string.help_title,
                R.string.popup_title,
                R.string.statistics_title, R.string.achievements_title,
                R.string.subliminal_title, R.string.update_title, R.string.studio_title};
        for (int width : new int[] {320, 411, 600}) {
            for (float fontScale : new float[] {1f, 1.3f, 2f}) {
                Configuration configuration = new Configuration(context.getResources()
                        .getConfiguration());
                configuration.screenWidthDp = width;
                configuration.fontScale = fontScale;
                Context themed = new ContextThemeWrapper(
                        context.createConfigurationContext(configuration), R.style.Theme_SubHub);
                for (int title : titles) for (boolean controller : new boolean[] {false, true}) {
                    ViewGroup root = (ViewGroup) LayoutInflater.from(themed)
                            .inflate(R.layout.view_secondary_header, null, false);
                    PrimaryHeader.bindSecondary(root, title, controller);
                    int availableWidth = dp(themed, width) - 2 * themed.getResources()
                            .getDimensionPixelSize(R.dimen.page_margin);
                    root.measure(View.MeasureSpec.makeMeasureSpec(
                                    availableWidth, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                    root.layout(0, 0, availableWidth, root.getMeasuredHeight());
                    ViewGroup header = (ViewGroup) PrimaryHeader.view(root);
                    assertTextFits(header, root.findViewById(R.id.primary_header_title));
                    assertTextFits(header, PrimaryHeader.backButton(root));
                    assertTrue(PrimaryHeader.backButton(root).getHeight() >= dp(themed, 48));
                    assertTrue(PrimaryHeader.backButton(root).getWidth() >= dp(themed, 48));
                    assertEquals(View.GONE, PrimaryHeader.subtitle(root).getVisibility());
                    if (controller) {
                        assertTextFits(header, PrimaryHeader.editLockButton(root));
                        assertTrue(PrimaryHeader.editLockButton(root).getHeight()
                                >= dp(themed, 48));
                    } else assertEquals(View.GONE,
                            PrimaryHeader.editLockButton(root).getVisibility());
                }
            }
        }
    }

    @Test public void informationalSubtitleRemainsVisibleAndReadable() {
        Context themed = new ContextThemeWrapper(context, R.style.Theme_SubHub);
        ViewGroup header = (ViewGroup) LayoutInflater.from(themed)
                .inflate(R.layout.view_primary_header, null, false);
        PrimaryHeader.bind(header, R.drawable.ic_nav_money,
                R.string.penance_title, R.string.wallet_currency_label);
        int width = context.getResources().getDisplayMetrics().widthPixels
                - 2 * context.getResources().getDimensionPixelSize(R.dimen.page_margin);
        header.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        header.layout(0, 0, width, header.getMeasuredHeight());
        assertEquals(View.VISIBLE, PrimaryHeader.subtitle(header).getVisibility());
        assertTextFits(header, PrimaryHeader.subtitle(header));
    }

    private static void assertTextFits(ViewGroup header, TextView text) {
        assertTrue("Text must have a layout", text.getLayout() != null);
        int available = text.getWidth() - text.getCompoundPaddingLeft()
                - text.getCompoundPaddingRight();
        for (int line = 0; line < text.getLineCount(); line++) {
            assertEquals("Header text must not be ellipsized", 0,
                    text.getLayout().getEllipsisCount(line));
            assertTrue("Header text exceeds its width: " + text.getText()
                            + ", visible=" + text.getLayout().getLineMax(line)
                            + ", available=" + available + ", header=" + header.getWidth(),
                    text.getLayout().getLineMax(line) <= available + 1);
        }
        assertTrue("Header text exceeds its height", text.getLayout().getHeight()
                <= text.getHeight() - text.getCompoundPaddingTop() - text.getCompoundPaddingBottom());
        Rect bounds = new Rect(0, 0, text.getWidth(), text.getHeight());
        header.offsetDescendantRectToMyCoords(text, bounds);
        assertTrue(bounds.top >= header.getPaddingTop());
        assertTrue(bounds.bottom <= header.getHeight() - header.getPaddingBottom());
    }

    private void assertHeader(Class<? extends Activity> activityClass, int titleRes) {
        Intent intent = new Intent(context, activityClass);
        if (activityClass == MainActivity.class) {
            intent.setAction(Intent.ACTION_MAIN)
                    .putExtra(MainActivity.EXTRA_SUPPRESS_PERMISSION_READINESS, true);
        }
        try (ActivityScenario<? extends Activity> scenario = ActivityScenario.launch(intent)) {
            scenario.onActivity(activity -> {
                View header = activity.findViewById(R.id.primary_header);
                ImageView icon = activity.findViewById(R.id.primary_header_icon);
                TextView title = activity.findViewById(R.id.primary_header_title);
                TextView subtitle = activity.findViewById(R.id.primary_header_subtitle);
                assertEquals(activity.getResources().getDimensionPixelSize(
                        R.dimen.primary_header_top), header.getTop());
                assertTrue(header.getHeight() >= activity.getResources()
                        .getDimensionPixelSize(R.dimen.primary_header_height));
                assertEquals(activity.getResources().getDisplayMetrics().widthPixels
                        - 2 * activity.getResources().getDimensionPixelSize(R.dimen.page_margin),
                        header.getWidth());
                if (icon.getVisibility() == View.VISIBLE) {
                    assertEquals(dp(activity, 44), icon.getWidth());
                }
                assertEquals(activity.getString(titleRes), title.getText().toString());
                assertEquals(Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 20,
                        activity.getResources().getDisplayMetrics())), title.getTextSize(), 0.01f);
                assertEquals(Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12,
                        activity.getResources().getDisplayMetrics())), subtitle.getTextSize(), 0.01f);
                assertEquals(View.GONE, subtitle.getVisibility());
                assertTrue(ViewCompat.isAccessibilityHeading(title));
                assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO,
                        icon.getImportantForAccessibility());
            });
        }
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
