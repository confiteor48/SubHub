package com.subhub.app;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.junit.Assert.*;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.atmosphere.AtmosphereActivity;
import com.subhub.app.onboarding.OnboardingActivity;
import com.subhub.app.popup.PopupStormActivity;
import com.subhub.app.popup.PopupStormSettings;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.settings.FeatureModuleManager;
import com.subhub.app.settings.GlobalSettingsActivity;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.settings.SharedPreferenceTestRestore;
import com.subhub.app.subliminal.SubliminalSettingsActivity;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Map;
import org.junit.*;

/** Focused follow-up contracts and captures; never starts a real payment or service. */
public class UiFollowupAndroidTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private SharedPreferences preferences;
    private Map<String, ?> original;

    @Before public void fixture() {
        preferences = context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0);
        original = preferences.getAll();
        ControllerPinManager.setPin(context, "2468");
        ControllerPinManager.enterDomMode();
    }

    @After public void restore() {
        SharedPreferenceTestRestore.restore(preferences, original);
        ControllerPinManager.enterSubMode();
    }

    @Test public void settingsSummaryUsesInstalledBuildAndAppsNeedOnlyOneSection() {
        try (ActivityScenario<GlobalSettingsActivity> page = ActivityScenario.launch(GlobalSettingsActivity.class)) {
            page.onActivity(a -> {
                com.subhub.app.util.ExpandableSectionView help = section(a, "help");
                assertEquals("Help & About", ((TextView) help.findViewById(R.id.section_title)).getText().toString());
                assertEquals("Build: " + BuildConfig.VERSION_NAME + " | " + BuildConfig.BUILD_DATE,
                        help.summary().getText().toString());
                assertTrue(help.summary().isShown());
                com.subhub.app.util.ExpandableSectionView privacy = section(a, "privacy");
                assertEquals("Privacy & Permissions", ((TextView) privacy.findViewById(R.id.section_title)).getText().toString());
                assertFalse(privacy.summary().getText().toString().contains("access settings"));
                assertNull(a.findViewById(R.id.feature_areas_card));
                assertTrue(a.findViewById(R.id.privacy_discreet_toggle).isShown());
                assertTrue(a.findViewById(R.id.button_help).isShown());
                assertFalse(privacy.header().isClickable());
                assertFalse(help.header().isClickable());
                assertNull(a.findViewById(R.id.button_packs));
                capture(a, "settings-overview");
                section(a, "apps").header().performClick();
                assertTrue(a.findViewById(R.id.app_list_content).isShown());
                assertNull(a.findViewById(R.id.button_toggle_apps));
            });
            page.recreate();
            page.onActivity(a -> {
                assertTrue(a.findViewById(R.id.app_list_content).isShown());
                android.widget.ScrollView scroll = (android.widget.ScrollView) a.findViewById(R.id.settings_sections).getParent();
                scroll.scrollTo(0, Integer.MAX_VALUE);
                capture(a, "settings-help");
            });
        }
    }

    @Test public void ritualsAreCompactAndKeyholderCannotOpenInSub() {
        preferences.edit().putBoolean(PopupStormSettings.K_ENABLED, false).commit();
        new FeatureModuleManager(context).setSubliminalEnabled(true);
        ControllerPinManager.enterSubMode();
        try (ActivityScenario<AtmosphereActivity> page = ActivityScenario.launch(AtmosphereActivity.class)) {
            page.onActivity(a -> {
                assertNull(a.findViewById(R.id.switch_whispers));
                assertNull(a.findViewById(R.id.switch_popup_storm));
                assertEquals("Enabled", ((TextView) a.findViewById(R.id.whispers_status)).getText().toString());
                assertEquals("Disabled", ((TextView) a.findViewById(R.id.popup_storm_status)).getText().toString());
                capture(a, "rituals-overview");
                a.findViewById(R.id.rituals_keyholder_card).performClick();
            });
            onView(androidx.test.espresso.matcher.ViewMatchers.withHint(R.string.controller_pin_label))
                    .check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist());
            page.onActivity(a -> assertEquals(.45f, a.findViewById(R.id.rituals_keyholder_card).getAlpha(), .01f));
            page.onActivity(a -> assertFalse(ControllerPinManager.isDomModeActive()));
        }
    }

    @Test public void detailTogglesPersistAndPopupConsentCannotBeSkipped() {
        new FeatureModuleManager(context).setSubliminalEnabled(false);
        try (ActivityScenario<SubliminalSettingsActivity> page = ActivityScenario.launch(SubliminalSettingsActivity.class)) {
            onView(withId(R.id.switch_whispers)).perform(scrollTo(), click());
            assertTrue(new FeatureModuleManager(context).isSubliminalEnabled());
            page.recreate();
            page.onActivity(a -> {
                assertTrue(((CompoundButton) a.findViewById(R.id.switch_whispers)).isChecked());
                capture(a, "subliminal-toggle");
                ControllerPinManager.enterSubMode();
                ((CompoundButton) a.findViewById(R.id.switch_whispers)).setChecked(false);
                assertTrue(new FeatureModuleManager(context).isSubliminalEnabled());
            });
        }
        ControllerPinManager.enterDomMode();
        preferences.edit().putBoolean(PopupStormSettings.K_ENABLED, false)
                .putBoolean(PopupStormSettings.K_ACK, false).commit();
        try (ActivityScenario<PopupStormActivity> page = ActivityScenario.launch(PopupStormActivity.class)) {
            onView(withId(R.id.switch_popup_storm)).perform(scrollTo(), click());
            onView(withText(android.R.string.cancel)).perform(click());
            assertFalse(PopupStormSettings.load(context).isEnabled());
            preferences.edit().putBoolean(PopupStormSettings.K_ACK, true).commit();
            onView(withId(R.id.switch_popup_storm)).perform(scrollTo(), click());
            assertTrue(PopupStormSettings.load(context).isEnabled());
            page.recreate();
            page.onActivity(a -> {
                assertTrue(((CompoundButton) a.findViewById(R.id.switch_popup_storm)).isChecked());
                capture(a, "popup-toggle");
                ControllerPinManager.enterSubMode();
                ((CompoundButton) a.findViewById(R.id.switch_popup_storm)).setChecked(false);
                assertTrue(PopupStormSettings.load(context).isEnabled());
            });
        }
    }

    @Test public void tourShowsTheDetailedMaskedFigure() {
        try (ActivityScenario<OnboardingActivity> page = ActivityScenario.launch(
                new Intent(context, OnboardingActivity.class).putExtra(OnboardingActivity.REPLAY, true))) {
            onView(withId(R.id.tour_next)).perform(click());
            page.onActivity(a -> capture(a, "tour-figure"));
        }
    }

    private static com.subhub.app.util.ExpandableSectionView section(Activity a, String key) {
        View header = a.findViewById(android.R.id.content).findViewWithTag("settings:" + key);
        return (com.subhub.app.util.ExpandableSectionView) header.getParent();
    }

    private static void capture(Activity activity, String name) {
        View root = activity.getWindow().getDecorView();
        Runnable save = () -> {
            Bitmap image = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
            root.draw(new Canvas(image));
            try (FileOutputStream out = new FileOutputStream(new File(activity.getFilesDir(), "followup-" + name + ".png"))) {
                assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, out));
            } catch (Exception error) { throw new AssertionError(error); }
            finally { image.recycle(); }
        };
        if (root.getWidth() > 0 && root.getHeight() > 0) save.run();
        else root.post(save);
    }
}
