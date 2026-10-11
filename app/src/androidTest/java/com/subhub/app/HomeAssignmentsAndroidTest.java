package com.subhub.app;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static org.junit.Assert.*;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.appmode.AppTimerManager;
import com.subhub.app.onboarding.OnboardingState;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.settings.FeatureModuleManager;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.settings.SharedPreferenceTestRestore;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

public class HomeAssignmentsAndroidTest {
    @Test public void everyHomeAssignmentOmitsAppNamesWithoutChangingAssignments() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Map<SharedPreferences, Map<String, ?>> before = new LinkedHashMap<>();
        for (String name : new String[] {SettingsRepository.PREFERENCES_NAME, "subhub_onboarding", "subhub_privacy"}) {
            SharedPreferences prefs = context.getSharedPreferences(name, 0);
            before.put(prefs, prefs.getAll());
        }
        Set<String> selected = Set.of("com.android.chrome", "com.android.settings");
        OnboardingState.complete(context);
        ControllerPinManager.setPin(context, "2468");
        ControllerPinManager.enterSubMode();
        context.getSharedPreferences("subhub_privacy", 0).edit().putBoolean("app_lock", false).commit();
        new FeatureModuleManager(context).save(true, true, true, true);
        AppModeManager apps = new AppModeManager(context);
        apps.setAllAppsEnabled(false);
        apps.save(false, selected);
        new AppTimerManager(context).saveSettings(true, 30, true, 60);
        new AppTimerManager(context).saveAllowances(selected,
                Map.of("com.android.chrome", 15, "com.android.settings", 40));
        try (ActivityScenario<MainActivity> page = ActivityScenario.launch(MainActivity.class)) {
            for (int id : new int[] {R.id.sub_censor_card, R.id.sub_limits_card, R.id.sub_wallet_card, R.id.sub_atmosphere_card}) {
                onView(withId(id)).perform(scrollTo(), click());
                onView(withId(R.id.arrangement_detail_rows)).inRoot(isDialog()).check((view, error) -> {
                    assertNull(error);
                    String details = text(view);
                    assertFalse(details.contains(context.getString(R.string.arrangement_apps)));
                    assertFalse(details.contains("Chrome"));
                    assertFalse(details.contains("Settings"));
                    assertFalse(details.isEmpty());
                });
                onView(withId(R.id.arrangement_detail_close)).inRoot(isDialog()).perform(click());
            }
            assertEquals(selected, apps.getSelectedPackages());
            assertEquals(15, new AppTimerManager(context).allowanceMinutes("com.android.chrome"));
            assertEquals(40, new AppTimerManager(context).allowanceMinutes("com.android.settings"));
        } finally {
            for (Map.Entry<SharedPreferences, Map<String, ?>> entry : before.entrySet())
                SharedPreferenceTestRestore.restore(entry.getKey(), entry.getValue());
            ControllerPinManager.enterSubMode();
        }
    }
    private static String text(View view) {
        String value = view instanceof TextView ? ((TextView) view).getText().toString() : "";
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            value += "\n" + text(((ViewGroup) view).getChildAt(i));
        return value;
    }
}
