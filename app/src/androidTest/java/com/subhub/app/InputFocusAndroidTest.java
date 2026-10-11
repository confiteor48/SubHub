package com.subhub.app;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.withHint;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withTagValue;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.*;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.View;
import android.widget.EditText;
import androidx.appcompat.app.AlertDialog;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.help.HelpActivity;
import com.subhub.app.onboarding.OnboardingState;
import com.subhub.app.security.ControllerPinGate;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.settings.SharedPreferenceTestRestore;
import com.subhub.app.util.ThemedDialogs;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class InputFocusAndroidTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final Map<SharedPreferences, Map<String, ?>> before = new LinkedHashMap<>();
    @Before public void prepare() {
        for (String name : new String[] {SettingsRepository.PREFERENCES_NAME, "subhub_onboarding", "subhub_privacy", "subhub_controller_auth"}) {
            SharedPreferences prefs = context.getSharedPreferences(name, 0);
            before.put(prefs, prefs.getAll());
        }
        context.getSharedPreferences("subhub_privacy", 0).edit().putBoolean("app_lock", false).commit();
        OnboardingState.complete(context);
        ControllerPinManager.setPin(context, "2468");
        ControllerPinManager.enterSubMode();
    }
    @After public void restore() {
        for (Map.Entry<SharedPreferences, Map<String, ?>> entry : before.entrySet())
            SharedPreferenceTestRestore.restore(entry.getKey(), entry.getValue());
        ControllerPinManager.enterSubMode();
    }

    @Test public void helpSearchOpensForReadingAndKeyboardRequiresATapEvenAfterRecreation() {
        AtomicReference<View> search = new AtomicReference<>();
        try (ActivityScenario<HelpActivity> page = ActivityScenario.launch(HelpActivity.class)) {
            page.onActivity(a -> { search.set(a.findViewById(R.id.help_search)); assertFalse(search.get().hasFocus()); });
            awaitIme(search.get(), false);
            onView(withId(R.id.help_search)).perform(click());
            awaitIme(search.get(), true);
            page.recreate();
            page.onActivity(a -> { search.set(a.findViewById(R.id.help_search)); assertFalse(search.get().hasFocus()); });
            awaitIme(search.get(), false);
        }
    }

    @Test public void ordinaryDialogInputDoesNotFocusUntilTapped() {
        AtomicReference<EditText> input = new AtomicReference<>();
        AtomicReference<AlertDialog> dialog = new AtomicReference<>();
        try (ActivityScenario<HelpActivity> page = ActivityScenario.launch(HelpActivity.class)) {
            page.onActivity(a -> {
                EditText field = new EditText(a);
                field.setTag("ordinary-dialog-input");
                input.set(field);
                dialog.set(ThemedDialogs.builder(a).setView(field)
                        .setNegativeButton(android.R.string.cancel, null).show());
            });
            assertFalse(input.get().hasFocus());
            awaitIme(input.get(), false);
            onView(withTagValue(is((Object) "ordinary-dialog-input"))).inRoot(isDialog()).perform(click());
            awaitIme(input.get(), true);
            page.onActivity(a -> dialog.get().dismiss());
        }
    }

    @Test public void domUnlockAndSetupKeepAutomaticFocusAndKeyboard() {
        AtomicReference<View> pin = new AtomicReference<>();
        try (ActivityScenario<HelpActivity> page = ActivityScenario.launch(HelpActivity.class)) {
            page.onActivity(a -> ControllerPinGate.unlock(a, () -> {}, false));
            onView(withHint(R.string.controller_pin_label)).inRoot(isDialog()).check((view, error) -> {
                assertNull(error); assertTrue(view.hasFocus()); pin.set(view);
            });
            awaitIme(pin.get(), true);
            onView(withId(android.R.id.button2)).inRoot(isDialog()).perform(click());
            page.onActivity(a -> {
                context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0).edit()
                        .remove("controller_pin_salt").remove("controller_pin_hash")
                        .remove("controller_keyholder_optional").commit();
                context.getSharedPreferences("subhub_controller_auth", 0).edit().clear().commit();
                ControllerPinGate.ensureConfigured(a, () -> {});
            });
            onView(withHint(R.string.controller_pin_label)).inRoot(isDialog()).check((view, error) -> {
                assertNull(error); assertTrue(view.hasFocus()); pin.set(view);
            });
            awaitIme(pin.get(), true);
        }
    }

    private static void awaitIme(View view, boolean visible) {
        boolean[] ready = {false};
        long deadline = SystemClock.uptimeMillis() + 5000;
        while (SystemClock.uptimeMillis() < deadline && !ready[0]) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(view);
                ready[0] = insets != null && insets.isVisible(WindowInsetsCompat.Type.ime()) == visible;
            });
            if (!ready[0]) SystemClock.sleep(50);
        }
        assertTrue("Keyboard visibility should be " + visible, ready[0]);
    }
}
