package com.subhub.app;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static org.junit.Assert.*;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.subhub.app.settings.SettingsRepository;
import java.io.File;
import java.io.FileOutputStream;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class ArrangementSheetAndroidTest {
    @Test public void walletReselectReturnsToOverviewAndArtworkCanBeReviewed() {
        boolean originalDom = com.subhub.app.security.ControllerPinManager.isDomModeActive();
        com.subhub.app.security.ControllerPinManager.enterDomMode();
        try (ActivityScenario<com.subhub.app.penance.PenanceActivity> scenario =
                ActivityScenario.launch(com.subhub.app.penance.PenanceActivity.class)) {
            scenario.onActivity(activity -> {
                try {
                    java.lang.reflect.Method open = activity.getClass().getDeclaredMethod("openWalletEditor", String.class);
                    open.setAccessible(true);
                    java.lang.reflect.Field section = activity.getClass().getDeclaredField("expandedWalletSection");
                    section.setAccessible(true);
                    open.invoke(activity, "rules");
                    assertEquals("rules", section.get(activity));
                    activity.findViewById(R.id.nav_money).performClick();
                    assertEquals("", section.get(activity));
                    activity.findViewById(R.id.nav_money).performClick();
                    assertEquals("", section.get(activity));
                } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
                com.subhub.app.onboarding.SetupFeaturePreviewView artwork =
                        new com.subhub.app.onboarding.SetupFeaturePreviewView(activity);
                artwork.measure(View.MeasureSpec.makeMeasureSpec(984, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                artwork.layout(0, 0, artwork.getMeasuredWidth(), artwork.getMeasuredHeight());
                save(artwork, "setup-artwork");
            });
        } finally {
            if (!originalDom) com.subhub.app.security.ControllerPinManager.enterSubMode();
        }
    }
    @Test public void assignmentsOpenReadableSheetsWithPreviewOnlyForCensor() throws Exception {
        SettingsRepository settings = new SettingsRepository(ApplicationProvider.getApplicationContext());
        java.util.Map<String, ?> before = settings.preferences().getAll();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            for (int card : new int[] {R.id.sub_censor_card, R.id.sub_limits_card,
                    R.id.sub_wallet_card, R.id.sub_atmosphere_card}) {
                scenario.onActivity(activity -> activity.findViewById(card).performClick());
                onView(withId(R.id.arrangement_detail_title)).inRoot(isDialog()).check((title, error) -> {
                    if (error != null) throw error;
                    ViewGroup content = (ViewGroup) title.getParent().getParent();
                    View preview = content.findViewById(R.id.arrangement_detail_preview);
                    assertEquals(card == R.id.sub_censor_card ? View.VISIBLE : View.GONE,
                            preview.getVisibility());
                    if (card == R.id.sub_censor_card) {
                        assertEquals(settings.loadAppearance().getType().getPreferenceValue(), preview.getTag());
                        assertEquals(preview.getWidth(), preview.getHeight());
                    }
                    assertTrue(content.getWidth() > 0);
                    checkText(content);
                    int[] origin = new int[2];
                    content.getLocationOnScreen(origin);
                    assertTrue("Sheet remains below the top inset", origin[1] > 0);
                    save(content, "sheet-" + card);
                });
                onView(withId(R.id.arrangement_detail_close)).inRoot(isDialog()).perform(click());
            }
        }
        assertEquals("Read-only assignment sheets retain saved settings", before, settings.preferences().getAll());
    }

    private static void checkText(View view) {
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            if (text.getLayout() != null) {
                assertTrue("Text height: " + text.getText(), text.getLayout().getHeight()
                        <= text.getHeight() - text.getCompoundPaddingTop() - text.getCompoundPaddingBottom());
                for (int line = 0; line < text.getLayout().getLineCount(); line++) {
                    assertTrue("Text width: " + text.getText(), text.getLayout().getLineMax(line)
                            <= text.getWidth() - text.getCompoundPaddingLeft() - text.getCompoundPaddingRight() + 1);
                }
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) checkText(group.getChildAt(i));
        }
    }

    private static void save(View view, String name) {
        Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bitmap));
        File directory = new File(view.getContext().getFilesDir(), "arrangement-review");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        String profile = view.getResources().getConfiguration().screenWidthDp + "-"
                + view.getResources().getConfiguration().fontScale;
        try (FileOutputStream output = new FileOutputStream(new File(directory, profile + "-" + name + ".png"))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        } catch (Exception failure) { throw new AssertionError(failure); }
        finally { bitmap.recycle(); }
    }
}
