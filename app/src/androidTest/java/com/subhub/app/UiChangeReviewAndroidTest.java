package com.subhub.app;

import static org.junit.Assert.*;
import android.app.Activity;
import android.content.*;
import android.graphics.*;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.appmode.*;
import com.subhub.app.help.HelpActivity;
import com.subhub.app.onboarding.*;
import com.subhub.app.penance.*;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.settings.*;
import java.io.*;
import java.util.*;
import org.junit.*;

/** Named native review captures with synthetic settings; no service, payment, or recording. */
public final class UiChangeReviewAndroidTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final Map<SharedPreferences, Map<String, ?>> before = new LinkedHashMap<>();
    @Before public void fixture() {
        for (String name : new String[] {SettingsRepository.PREFERENCES_NAME, PenanceManager.PREFS_NAME,
                PayPalCredentialStore.PREFS_NAME, "subhub_onboarding"}) {
            SharedPreferences prefs = context.getSharedPreferences(name, 0);
            before.put(prefs, prefs.getAll());
        }
        OnboardingState.complete(context);
        ControllerPinManager.setPin(context, "2468");
        ControllerPinManager.enterDomMode();
        new AppModeManager(context).save(false, Set.of("com.android.chrome", "com.android.settings"));
        new AppTimerManager(context).saveSettings(true, 30, true, 60);
        new AppTimerManager(context).saveAllowances(Set.of("com.android.chrome", "com.android.settings"),
                Map.of("com.android.chrome", 15, "com.android.settings", 40));
        new PenanceManager(context).configure(true, Map.of(PenanceInfraction.NEW_DETECTION, 125,
                PenanceInfraction.CENSORED_TAP, 75), 1000, 5000, 10, 15);
        new PaidPauseManager(context).configure(false, 500, 15);
        new PayPalCredentialStore(context).clear();
        new HardcoreAutoPayManager(context).disable();
        new FeatureModuleManager(context).setCensorEnabled(false);
    }
    @After public void restore() {
        for (Map.Entry<SharedPreferences, Map<String, ?>> entry : before.entrySet())
            SharedPreferenceTestRestore.restore(entry.getKey(), entry.getValue());
        ControllerPinManager.enterSubMode();
    }
    @Test public void captureCurrentChangesForUserReview() {
        try (ActivityScenario<SettingsActivity> page = ActivityScenario.launch(SettingsActivity.class)) {
            settle();
            page.onActivity(a -> {
                assertTrue(((CompoundButton) a.findViewById(R.id.radio_preset_off)).isChecked());
                a.findViewById(R.id.censor_detection_section).requestRectangleOnScreen(
                        new Rect(0, 0, a.findViewById(R.id.censor_detection_section).getWidth(), 1), true);
            });
            settle(); page.onActivity(a -> capture(a, "off-card"));
        }
        try (ActivityScenario<GlobalSettingsActivity> page = ActivityScenario.launch(GlobalSettingsActivity.class)) {
            settle(); page.onActivity(a -> capture(a, "settings"));
            page.onActivity(a -> a.findViewById(android.R.id.content).findViewWithTag("settings:apps").performClick());
            settle(); page.onActivity(a -> capture(a, "settings-apps"));
            page.onActivity(a -> ((ScrollView) a.findViewById(R.id.settings_sections).getParent()).fullScroll(View.FOCUS_DOWN));
            settle(); page.onActivity(a -> capture(a, "settings-help"));
        }
        try (ActivityScenario<OnboardingActivity> page = ActivityScenario.launch(
                new Intent(context, OnboardingActivity.class).putExtra(OnboardingActivity.REPLAY, true))) {
            page.onActivity(a -> a.findViewById(R.id.tour_next).performClick());
            settle(); page.onActivity(a -> capture(a, "setup-figure"));
            page.onActivity(a -> a.findViewById(R.id.tour_next).performClick());
            settle(); page.onActivity(a -> capture(a, "setup-wallet"));
            page.onActivity(a -> a.findViewById(R.id.tour_next).performClick());
            settle(); page.onActivity(a -> capture(a, "setup-apps"));
        }
        try (ActivityScenario<HelpActivity> page = ActivityScenario.launch(HelpActivity.class)) {
            settle(); page.onActivity(a -> capture(a, "help"));
            page.onActivity(a -> {
                ((EditText) a.findViewById(R.id.help_search)).setText("Wallet");
                LinearLayout question = a.findViewById(R.id.help_sections).findViewWithTag("help_question:help_rework_wallet_title");
                question.getChildAt(0).performClick();
                a.findViewById(R.id.help_search).requestRectangleOnScreen(new Rect(0,0,1,1),true);
            });
            settle(); page.onActivity(a -> capture(a, "help-answer"));
        }
        try (ActivityScenario<AppModeActivity> page = ActivityScenario.launch(AppModeActivity.class)) {
            settle();
            SystemClock.sleep(700);
            page.onActivity(a -> {
                assertTrue(((CompoundButton) a.findViewById(R.id.per_app_limit_enabled)).isChecked());
                assertTrue(((CompoundButton) a.findViewById(R.id.total_limit_enabled)).isChecked());
                capture(a, "limits");
            });
        }
        try (ActivityScenario<PenanceActivity> page = ActivityScenario.launch(PenanceActivity.class)) {
            settle(); page.onActivity(a -> capture(a, "wallet"));
            page.onActivity(a -> a.findViewById(android.R.id.content).findViewWithTag("wallet:rules").performClick());
            settle(); page.onActivity(a -> capture(a, "wallet-rules"));
        }
        try (ActivityScenario<com.subhub.app.atmosphere.AtmosphereActivity> page = ActivityScenario.launch(com.subhub.app.atmosphere.AtmosphereActivity.class)) {
            settle(); page.onActivity(a -> capture(a, "rituals"));
            page.onActivity(a -> a.findViewById(R.id.whispers_card).requestRectangleOnScreen(new Rect(0,0,1,1),true));
            settle(); page.onActivity(a -> capture(a, "rituals-effects"));
            page.onActivity(a -> a.findViewById(R.id.achievements_home_card).requestRectangleOnScreen(
                    new Rect(0, 0, 1, a.findViewById(R.id.achievements_home_card).getHeight()), true));
            settle(); page.onActivity(a -> {
                LinearLayout badges = a.findViewById(R.id.achievements_home_badges);
                assertEquals(5, badges.getChildCount());
                android.widget.HorizontalScrollView strip = (android.widget.HorizontalScrollView) badges.getParent();
                assertTrue(strip.isHorizontalFadingEdgeEnabled());
                View fifth = badges.getChildAt(4);
                assertNotNull(fifth.getBackground());
                assertTrue("The fifth badge should peek into the strip", fifth.getLeft() < strip.getWidth());
                assertTrue("The fifth badge should continue beyond the strip", fifth.getRight() > strip.getWidth());
                capture(a, "rituals-achievements");
            });
        }
    }
    private static void settle() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        SystemClock.sleep(450);
    }
    private static void capture(Activity a, String name) {
        View root = a.getWindow().getDecorView();
        Bitmap image = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(image));
        try (FileOutputStream out = new FileOutputStream(new File(a.getFilesDir(), "review-" + name + ".png"))) {
            assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, out));
        } catch (IOException failure) { throw new AssertionError(failure); }
        finally { image.recycle(); }
    }
}
