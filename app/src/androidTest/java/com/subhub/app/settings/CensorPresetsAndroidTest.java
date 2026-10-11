package com.subhub.app.settings;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.*;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.capture.export.ExportSettings;
import com.subhub.app.security.ControllerPinManager;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

@RunWith(AndroidJUnit4.class)
public final class CensorPresetsAndroidTest {
    private SharedPreferences live, exported, saved;
    private Map<String, ?> beforeLive, beforeExport, beforeSaved;
    private boolean wasDom;

    @Before public void prepare() {
        InstrumentationRegistry.getInstrumentation().getUiAutomation();
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        Context context = ApplicationProvider.getApplicationContext();
        live = new SettingsRepository(context).preferences();
        exported = ExportSettings.preferences(context);
        saved = context.getSharedPreferences("subhub_censor_looks", Context.MODE_PRIVATE);
        beforeLive = new LinkedHashMap<>(live.getAll());
        beforeExport = new LinkedHashMap<>(exported.getAll());
        beforeSaved = new LinkedHashMap<>(saved.getAll());
        wasDom = ControllerPinManager.isDomModeActive();
        ControllerPinManager.enterDomMode();
        saved.edit().clear().commit();
        live.edit().clear().putString("detection_quality", "high")
                .putString("censor_coverage", "whole_person").putString("capture_method", "screen_recording")
                .putString("censor_type", "box").commit();
    }

    @After public void restore() {
        SharedPreferenceTestRestore.restore(live, beforeLive);
        SharedPreferenceTestRestore.restore(exported, beforeExport);
        SharedPreferenceTestRestore.restore(saved, beforeSaved);
        if (wasDom) ControllerPinManager.enterDomMode(); else ControllerPinManager.enterSubMode();
    }

    @Test public void presetsFollowAppearanceDisclosure() {
        try (ActivityScenario<SettingsActivity> scenario = ActivityScenario.launch(SettingsActivity.class)) {
            onView(withId(com.subhub.app.R.id.button_censor_presets))
                    .check(matches(org.hamcrest.Matchers.not(isDisplayed())));
            onView(withId(com.subhub.app.R.id.button_appearance_details))
                    .perform(com.subhub.app.NativeUiActions.revealAboveNavigation(), click());
            onView(withId(com.subhub.app.R.id.button_censor_presets))
                    .perform(com.subhub.app.NativeUiActions.revealAboveNavigation()).check(matches(isDisplayed()));
            onView(withId(com.subhub.app.R.id.button_appearance_details))
                    .perform(com.subhub.app.NativeUiActions.revealAboveNavigation(), click());
            onView(withId(com.subhub.app.R.id.button_censor_presets))
                    .check(matches(org.hamcrest.Matchers.not(isDisplayed())));
        }
    }

    @Test public void nativeApplySaveReopenRenameAndDeletePreserveDetection() {
        Context context = ApplicationProvider.getApplicationContext();
        java.util.Set<String> phrases = new java.util.LinkedHashSet<>(java.util.Collections.singleton("MY CUSTOM PHRASE"));
        live.edit().putStringSet("custom_phrases", phrases)
                .putString("effect_palette_tape_1", "#123456").commit();
        try (ActivityScenario<CensorPresetsActivity> scenario = ActivityScenario.launch(CensorPresetsActivity.class)) {
            onView(withTagValue(is("look:builtin:lockpad"))).perform(scrollTo(), click());
            assertEquals("error_popup", live.getString("censor_type", ""));
            assertEquals("high", live.getString("detection_quality", ""));
            assertEquals("whole_person", live.getString("censor_coverage", ""));
            assertEquals("screen_recording", live.getString("capture_method", ""));
            assertEquals(phrases, live.getStringSet("custom_phrases", java.util.Collections.emptySet()));
            assertEquals("#123456", live.getString("effect_palette_tape_1", ""));
            scenario.onActivity(activity -> assertTrue(activity.findViewById(android.R.id.content)
                    .findViewWithTag("look:builtin:lockpad").isSelected()));
            onView(withTagValue(is("preset:save"))).perform(scrollTo(), click());
            onView(withHint("Preset name")).perform(click(), replaceText("Night lock"), closeSoftKeyboard());
            onView(withHint("Preset name")).check(matches(withText("Night lock")));
            awaitDialogFrame();
            onView(withId(android.R.id.button1)).perform(click());
            retainScreen(context, "preset-save-state.png");
            assertEquals("The native Save action created a preset", 1, new CensorLookStore(context).saved().size());
            CensorLook look = new CensorLookStore(context).saved().get(0);
            assertEquals("Night lock", look.name());
            live.edit().putString("censor_type", "box").putInt("censor_intensity", 12).commit();
            scenario.recreate();
            onView(withTagValue(is("look:" + look.id()))).perform(scrollTo(), click());
            assertEquals("error_popup", live.getString("censor_type", ""));
            assertEquals(75, live.getInt("censor_intensity", -1));
            onView(withContentDescription("Edit Night lock")).perform(scrollTo(), click());
            onView(withText("Rename")).perform(click());
            onView(withHint("Preset name")).perform(click(), replaceText("Saved lock"), closeSoftKeyboard());
            awaitDialogFrame();
            onView(withId(android.R.id.button1)).perform(click());
            assertEquals("Saved lock", new CensorLookStore(context).saved().get(0).name());
            onView(withContentDescription("Edit Saved lock")).perform(scrollTo(), click());
            onView(withText("Delete")).perform(click());
            assertTrue(new CensorLookStore(context).saved().isEmpty());
            assertEquals("error_popup", live.getString("censor_type", ""));
        }
    }

    @Test public void exportPresetDoesNotWriteLiveAppearanceOrDetection() {
        Context context = ApplicationProvider.getApplicationContext();
        exported.edit().putString("censor_type", "blur").putString("detection_quality", "low").commit();
        Intent intent = new Intent(context, CensorPresetsActivity.class)
                .putExtra(CensorPresetsActivity.EXTRA_EXPORT, true);
        try (ActivityScenario<CensorPresetsActivity> ignored = ActivityScenario.launch(intent)) {
            onView(withTagValue(is("look:builtin:lockpad"))).perform(scrollTo(), click());
            assertEquals("error_popup", exported.getString("censor_type", ""));
            assertEquals("low", exported.getString("detection_quality", ""));
            assertEquals("box", live.getString("censor_type", ""));
            assertEquals("high", live.getString("detection_quality", ""));
        }
    }

    @Test public void subCanViewPreviewsButCannotApplyOrSave() {
        ControllerPinManager.enterSubMode();
        Map<String, ?> original = new CensorLook("before", "Before", live.getAll()).values();
        try (ActivityScenario<CensorPresetsActivity> scenario = ActivityScenario.launch(CensorPresetsActivity.class)) {
            scenario.onActivity(activity -> {
                View root = activity.findViewById(android.R.id.content);
                View save = root.findViewWithTag("preset:save");
                View lockpad = root.findViewWithTag("look:builtin:lockpad");
                assertFalse(save.isEnabled()); assertFalse(lockpad.isEnabled());
                save.performClick(); lockpad.performClick();
            });
            assertEquals(original, new CensorLook("after", "After", live.getAll()).values());
            assertEquals("high", live.getString("detection_quality", ""));
            assertEquals("whole_person", live.getString("censor_coverage", ""));
            assertEquals("screen_recording", live.getString("capture_method", ""));
        }
    }

    @Test public void savedLookUpdateAndDeleteDoNotModifyActiveEditors() {
        Context context = ApplicationProvider.getApplicationContext();
        CensorLookStore store = new CensorLookStore(context);
        CensorLook look = store.save("First", live);
        live.edit().putString("censor_type", "error_popup").putInt("censor_intensity", 91).commit();
        store.update(look.id(), live);
        assertEquals(91, store.saved().get(0).values().get("censor_intensity"));
        Map<String, ?> active = new LinkedHashMap<>(live.getAll());
        store.delete(look.id());
        assertTrue("Deleting a preset does not write editor settings", active.equals(live.getAll()));
        assertTrue(store.saved().isEmpty());
    }

    @Test public void nativePresetTextFitsAndArtworkCaptureIsRetained() {
        try (ActivityScenario<CensorPresetsActivity> scenario = ActivityScenario.launch(CensorPresetsActivity.class)) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                View decor = activity.getWindow().getDecorView();
                Bitmap screen = Bitmap.createBitmap(decor.getWidth(), decor.getHeight(), Bitmap.Config.ARGB_8888);
                try {
                    decor.draw(new Canvas(screen));
                    File capture = new File(activity.getExternalFilesDir(null), "presets-native.png");
                    try (FileOutputStream out = new FileOutputStream(capture)) {
                        assertTrue(screen.compress(Bitmap.CompressFormat.PNG, 100, out));
                    } catch (java.io.IOException failed) { throw new AssertionError(failed); }
                } finally { screen.recycle(); }
                assertTrue("Measured native preset labels", assertText(activity.findViewById(android.R.id.content)) >= 15);
                Bitmap bitmap = Bitmap.createBitmap(480, 195, Bitmap.Config.ARGB_8888);
                try {
                    new LockpadArtwork().draw(new Canvas(bitmap), new android.graphics.RectF(0, 0, 480, 195),
                            EffectPalette.defaultsFor(CensorAppearance.Type.ERROR_POPUP), 1800);
                    File file = new File(activity.getExternalFilesDir(null), "lockpad-native.png");
                    try (FileOutputStream out = new FileOutputStream(file)) {
                        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out));
                    } catch (java.io.IOException failed) { throw new AssertionError(failed); }
                } finally { bitmap.recycle(); }
            });
        }
    }

    private static int assertText(View view) {
        int measured = 0;
        if (view instanceof TextView && view.isShown() && ((TextView) view).getText().length() > 0) {
            TextView text = (TextView) view;
            assertNotNull("Native text has a measured layout", text.getLayout());
            if (text.getLayout() != null) {
                measured++;
                int width = text.getWidth() - text.getCompoundPaddingLeft() - text.getCompoundPaddingRight();
                for (int line = 0; line < text.getLayout().getLineCount(); line++) {
                    assertEquals("No hidden preset name", 0, text.getLayout().getEllipsisCount(line));
                    assertTrue(text.getText().toString(), text.getLayout().getLineMax(line) <= width + 1);
                }
                assertTrue(text.getLayout().getHeight() <= text.getHeight()
                        - text.getCompoundPaddingTop() - text.getCompoundPaddingBottom());
            }
        }
        if (view instanceof ViewGroup) for (int index = 0; index < ((ViewGroup) view).getChildCount(); index++)
            measured += assertText(((ViewGroup) view).getChildAt(index));
        return measured;
    }

    private static void awaitDialogFrame() {
        java.util.concurrent.CountDownLatch frame = new java.util.concurrent.CountDownLatch(1);
        onView(withId(android.R.id.button1)).perform(new androidx.test.espresso.ViewAction() {
            public org.hamcrest.Matcher<View> getConstraints() { return isDisplayed(); }
            public String getDescription() { return "Wait for the current dialog frame after keyboard movement"; }
            public void perform(androidx.test.espresso.UiController controller, View button) {
                View root = button.getRootView();
                if (android.os.Build.VERSION.SDK_INT >= 29 && root.isHardwareAccelerated()) {
                    root.getViewTreeObserver().registerFrameCommitCallback(frame::countDown);
                    root.invalidate();
                } else root.post(frame::countDown);
            }
        });
        try { assertTrue("Dialog frame committed", frame.await(5, java.util.concurrent.TimeUnit.SECONDS)); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new AssertionError(interrupted);
        }
    }

    private static void retainScreen(Context context, String name) {
        Bitmap screenshot = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull("Native save screen is retained", screenshot);
        try (FileOutputStream out = new FileOutputStream(new File(context.getExternalFilesDir(null), name))) {
            assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, out));
        } catch (java.io.IOException failed) { throw new AssertionError(failed); }
        finally { screenshot.recycle(); }
    }
}
