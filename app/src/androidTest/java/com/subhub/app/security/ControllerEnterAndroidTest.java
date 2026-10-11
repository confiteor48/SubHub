package com.subhub.app.security;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.pressImeActionButton;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.withHint;
import static org.junit.Assert.*;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.KeyEvent;
import android.widget.EditText;

import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.UiController;
import androidx.test.espresso.ViewAction;
import androidx.test.platform.app.InstrumentationRegistry;

import com.subhub.app.R;
import com.subhub.app.help.HelpActivity;
import com.subhub.app.penance.PayPalCredentialStore;
import com.subhub.app.penance.PenanceManager;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.settings.SharedPreferenceTestRestore;

import org.hamcrest.Matcher;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Enter exercises the real dialog buttons, including their existing validation and role checks. */
public final class ControllerEnterAndroidTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final Map<SharedPreferences, Map<String, ?>> before = new LinkedHashMap<>();

    @Before public void fixture() {
        for (String name : new String[] {SettingsRepository.PREFERENCES_NAME,
                "subhub_controller_auth", PenanceManager.PREFS_NAME, PayPalCredentialStore.PREFS_NAME}) {
            SharedPreferences prefs = context.getSharedPreferences(name, 0);
            before.put(prefs, prefs.getAll());
            assertTrue(prefs.edit().clear().commit());
        }
        assertTrue(ControllerPinManager.setPin(context, "2468"));
        ControllerPinManager.enterSubMode();
    }

    @After public void restore() {
        for (Map.Entry<SharedPreferences, Map<String, ?>> entry : before.entrySet())
            SharedPreferenceTestRestore.restore(entry.getKey(), entry.getValue());
        ControllerPinManager.enterSubMode();
    }

    @Test public void imeDoneValidatesThePinAndUnlocksOnlyWhenCorrect() {
        AtomicInteger authorized = new AtomicInteger();
        try (ActivityScenario<HelpActivity> page = ActivityScenario.launch(HelpActivity.class)) {
            page.onActivity(a -> ControllerPinGate.unlock(a, authorized::incrementAndGet, false));
            onView(withHint(R.string.controller_pin_label)).inRoot(isDialog())
                    .perform(replaceText("1111"), pressImeActionButton());
            assertEquals(0, authorized.get());
            assertFalse(ControllerPinManager.isDomModeActive());
            onView(withHint(R.string.controller_pin_label)).inRoot(isDialog()).check((view, missing) -> {
                assertNull(missing);
                assertNotNull(((EditText) view).getError());
            });
            onView(withHint(R.string.controller_pin_label)).inRoot(isDialog())
                    .perform(replaceText("2468"), pressImeActionButton());
            assertEquals(1, authorized.get());
            assertTrue(ControllerPinManager.isDomModeActive());
        }
    }

    @Test public void hardwareEnterSubmitsTheChangedPinOnceOnRelease() {
        AtomicInteger changed = new AtomicInteger();
        ControllerPinManager.enterDomMode();
        try (ActivityScenario<HelpActivity> page = ActivityScenario.launch(HelpActivity.class)) {
            page.onActivity(a -> ControllerPinGate.changePin(a, changed::incrementAndGet));
            onView(withHint(R.string.controller_pin_label)).inRoot(isDialog()).perform(replaceText("1357"));
            onView(withHint(R.string.controller_pin_confirm_label)).inRoot(isDialog())
                    .perform(replaceText("1357"), physicalEnter(changed));
            assertEquals(1, changed.get());
            assertTrue(ControllerPinManager.verify(context, "1357"));
        }
    }

    @Test public void setupEnterKeepsConfirmationValidationAndFinishesInSubMode() {
        AtomicInteger configured = new AtomicInteger();
        try (ActivityScenario<HelpActivity> page = ActivityScenario.launch(HelpActivity.class)) {
            page.onActivity(a -> {
                // The custom runner prepares a PIN during activity creation.
                // Clear it afterward so this fixture exercises actual first setup.
                assertTrue(context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0).edit().clear().commit());
                assertFalse(ControllerPinManager.hasCredentials(context));
                ControllerPinGate.ensureConfigured(a, configured::incrementAndGet);
            });
            onView(withHint(R.string.controller_pin_label)).inRoot(isDialog())
                    .perform(replaceText("8642"), pressImeActionButton());
            assertEquals(0, configured.get());
            assertFalse(ControllerPinManager.hasCredentials(context));
            onView(withHint(R.string.controller_pin_confirm_label)).inRoot(isDialog()).check((view, missing) -> {
                assertNull(missing);
                assertNotNull(((EditText) view).getError());
            });
            onView(withHint(R.string.controller_pin_confirm_label)).inRoot(isDialog())
                    .perform(replaceText("8642"), pressImeActionButton());
            assertEquals(1, configured.get());
            assertTrue(ControllerPinManager.hasCredentials(context));
            assertFalse(ControllerPinManager.isDomModeActive());
        }
    }

    private static ViewAction physicalEnter(AtomicInteger submitted) {
        return new ViewAction() {
            @Override public Matcher<android.view.View> getConstraints() {
                return androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom(EditText.class);
            }
            @Override public String getDescription() { return "Submit with one physical Enter key cycle"; }
            @Override public void perform(UiController controller, android.view.View view) {
                EditText field = (EditText) view;
                field.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
                assertEquals("Key down must not submit", 0, submitted.get());
                field.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
                controller.loopMainThreadUntilIdle();
            }
        };
    }
}
