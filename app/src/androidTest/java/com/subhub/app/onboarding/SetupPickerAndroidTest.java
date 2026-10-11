package com.subhub.app.onboarding;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.*;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import android.app.*;
import android.content.Context;
import android.graphics.*;
import android.view.*;
import android.widget.*;

import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;

import com.subhub.app.R;
import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.settings.*;
import com.subhub.app.util.PrimaryHeader;

import org.junit.Test;

import java.io.*;
import java.lang.reflect.Field;

public class SetupPickerAndroidTest {
    @Test
    public void inlineAppChoicesSurviveRecreationAndSetupPreservesFeatureSettings() throws Exception {
        assumeTrue(android.os.Build.MODEL.contains("sdk_gphone"));
        Instrumentation instrument = InstrumentationRegistry.getInstrumentation();
        Context context = instrument.getTargetContext();
        android.content.SharedPreferences settings = context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0);
        android.content.SharedPreferences onboarding = context.getSharedPreferences("subhub_onboarding", 0);
        java.util.Map<String, ?> previousSettings = settings.getAll();
        java.util.Map<String, ?> previousOnboarding = onboarding.getAll();
        onboarding.edit().clear().commit();
        ControllerPinManager.useWithoutKeyholder(context);
        ControllerPinManager.enterDomMode();
        new FeatureModuleManager(context).save(true, true, true, true);
        boolean walletRulesEnabled = new com.subhub.app.penance.PenanceManager(context).isEnabled();
        new AppModeManager(context).setAllAppsEnabled(false);
        new AppModeManager(context).setArmed(false);
        java.util.concurrent.atomic.AtomicReference<String> changedPackage = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean selected = new java.util.concurrent.atomic.AtomicBoolean();
        try (ActivityScenario<OnboardingActivity> tour = ActivityScenario.launch(OnboardingActivity.class)) {
            tour.onActivity(activity -> {
                View scene = activity.findViewById(android.R.id.content).findViewWithTag("setup-feature-overview");
                assertNotNull(scene);
                assertEquals(activity.getString(R.string.tour_feature_scene_accessibility), scene.getContentDescription());
                CensorPreviewView sample = findPreview(scene);
                assertNotNull(sample);
                Bitmap frame = Bitmap.createBitmap(sample.getWidth(), sample.getHeight(), Bitmap.Config.ARGB_8888);
                sample.draw(new Canvas(frame));
                frame.recycle();
                try {
                    Field field = CensorPreviewView.class.getDeclaredField("phoneBitmap");
                    field.setAccessible(true);
                    Bitmap phone = (Bitmap) field.get(sample);
                    assertTrue("Rendered sample is portrait", phone.getHeight() > phone.getWidth());
                } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
                capture(activity, "setup-mobile.png");
            });
            onView(withId(R.id.tour_next)).perform(click());
            onView(withId(R.id.tour_next)).perform(click());
            onView(withId(R.id.tour_next)).perform(click());
            awaitApps(tour, instrument);
            tour.onActivity(activity -> {
                assertFalse(text(activity.findViewById(android.R.id.content)).contains("Whispers"));
                ListView list = activity.findViewById(R.id.app_list);
                View card = activity.findViewById(android.R.id.content).findViewWithTag("setup-app-card");
                android.graphics.Rect cardBounds = new android.graphics.Rect(), scrollBounds = new android.graphics.Rect();
                card.getGlobalVisibleRect(cardBounds);
                activity.findViewById(R.id.tour_scroll).getGlobalVisibleRect(scrollBounds);
                assertTrue("Apps use the available height", Math.abs(cardBounds.bottom
                        - scrollBounds.bottom + activity.findViewById(R.id.tour_content).getPaddingBottom()) <= 3);
                assertTrue("App list is tall", list.getHeight() > activity.getResources().getDisplayMetrics().density * 360);
                IncludedAppRow row = (IncludedAppRow) list.getChildAt(0);
                assertNotNull(row);
                CheckBox choice = row.choice();
                changedPackage.set(((String) choice.getTag()).substring("included:".length()));
                selected.set(!choice.isChecked());
                choice.setChecked(selected.get());
                assertEquals(selected.get(), new AppModeManager(context).getSelectedPackages().contains(changedPackage.get()));
                capture(activity, "setup-apps.png");
            });
            tour.recreate();
            awaitApps(tour, instrument);
            onView(withId(R.id.tour_progress)).check(matches(withText(context.getString(R.string.tour_progress, 4, 6))));
            assertEquals(selected.get(), new AppModeManager(context).getSelectedPackages().contains(changedPackage.get()));
            for (int step = 3; step < 5; step++) onView(withId(R.id.tour_next)).perform(click());
            tour.onActivity(activity -> {
                String content = text(activity.findViewById(android.R.id.content));
                assertFalse(content.contains("Whispers"));
                assertFalse(content.contains("Start from Home"));
                assertFalse(content.contains("Configure the details"));
                capture(activity, "setup-review.png");
            });
            onView(withId(R.id.tour_skip)).perform(click());
            assertTrue(new FeatureModuleManager(context).isWalletEnabled());
            assertEquals(walletRulesEnabled, new com.subhub.app.penance.PenanceManager(context).isEnabled());
            assertTrue(new FeatureModuleManager(context).isSubliminalEnabled());
            assertEquals(selected.get(), new AppModeManager(context).getSelectedPackages().contains(changedPackage.get()));
            assertFalse(new AppModeManager(context).isArmed());
        } finally {
            SharedPreferenceTestRestore.restore(settings, previousSettings);
            SharedPreferenceTestRestore.restore(onboarding, previousOnboarding);
            ControllerPinManager.enterSubMode();
        }
    }

    private static void awaitApps(ActivityScenario<OnboardingActivity> tour, Instrumentation instrument) {
        java.util.concurrent.atomic.AtomicBoolean ready = new java.util.concurrent.atomic.AtomicBoolean();
        for (int attempt = 0; attempt < 50 && !ready.get(); attempt++) {
            instrument.waitForIdleSync();
            tour.onActivity(activity -> {
                ListView list = activity.findViewById(R.id.app_list);
                ready.set(list.getAdapter() != null && list.getAdapter().getCount() > 0 && list.getChildCount() > 0);
            });
            if (!ready.get()) android.os.SystemClock.sleep(100);
        }
        assertTrue("Installed app choices must finish loading", ready.get());
    }

    private static CensorPreviewView findPreview(View view) {
        if (view instanceof CensorPreviewView) return (CensorPreviewView) view;
        if (view instanceof ViewGroup)
            for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
                CensorPreviewView found = findPreview(((ViewGroup) view).getChildAt(i));
                if (found != null) return found;
            }
        return null;
    }

    private static String text(View view) {
        String result = view instanceof TextView ? ((TextView) view).getText().toString() : "";
        if (view instanceof ViewGroup)
            for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
                result += "\n" + text(((ViewGroup) view).getChildAt(i));
        return result;
    }

    private static void capture(Activity activity, String name) {
        View root = activity.getWindow().getDecorView();
        root.post(
                () -> {
                    Bitmap frame =
                            Bitmap.createBitmap(
                                    root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
                    root.draw(new Canvas(frame));
                    try (FileOutputStream out =
                            new FileOutputStream(new File(activity.getFilesDir(), name))) {
                        frame.compress(Bitmap.CompressFormat.PNG, 100, out);
                    } catch (IOException error) {
                        throw new AssertionError(error);
                    } finally {
                        frame.recycle();
                    }
                });
    }
}
