package com.subhub.app.diagnostics;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.popup.PopupStormSettings;
import com.subhub.app.settings.CaptureMethod;
import com.subhub.app.settings.FeatureModuleManager;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.service.ScreenshotAccessibilityService;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Runtime policy/ledger tests only. Real MediaProjection consent and recording need a UI run. */
@RunWith(AndroidJUnit4.class)
public final class CensorLabCensorSessionAndroidTest {
    private Context context;
    private SharedPreferences preferences;
    private Map<String, ?> before;

    @Before public void seedOwnedEmulator() {
        assumeTrue(Build.MODEL.contains("sdk_gphone"));
        assertFalse(CensorLabCensorSession.isActive());
        context = ApplicationProvider.getApplicationContext();
        preferences = new SettingsRepository(context).preferences();
        // Materialize the existing migration before checking that Lab makes no settings writes.
        new AppModeManager(context);
        new FeatureModuleManager(context);
        before = preferences.getAll();
        assertTrue(preferences.edit().putBoolean(AppModeManager.KEY_ARMED, false)
                .putBoolean(AppModeManager.KEY_ALL_APPS, false)
                .putStringSet(AppModeManager.KEY_INCLUDED_PACKAGES, Set.of("chosen.app"))
                .putBoolean(FeatureModuleManager.KEY_CENSOR_ENABLED, false)
                .putBoolean(FeatureModuleManager.KEY_SUBLIMINAL_ENABLED, true)
                .putBoolean(PopupStormSettings.K_ENABLED, true)
                .putString(SettingsRepository.KEY_CAPTURE_METHOD,
                        CaptureMethod.SCREEN_RECORDING.preferenceValue()).commit());
    }

    @After public void restorePreferences() {
        if (preferences == null) return;
        assertFalse(CensorLabCensorSession.isActive());
        if ("true".equals(InstrumentationRegistry.getArguments().getString("labCensorUiSetup"))) {
            // Explicit, owned-emulator-only continuation into the real permission/recording UI.
            // Ordinary native invocations still restore their complete previous preferences.
            assertNotNull(context.getPackageManager().getLaunchIntentForPackage("com.android.chrome"));
            assertTrue(preferences.edit().putBoolean(AppModeManager.KEY_ARMED, false)
                    .putBoolean(AppModeManager.KEY_ALL_APPS, false)
                    .putStringSet(AppModeManager.KEY_INCLUDED_PACKAGES, Set.of("com.android.chrome"))
                    .putBoolean(FeatureModuleManager.KEY_CENSOR_ENABLED, false)
                    .putString(SettingsRepository.KEY_CAPTURE_METHOD,
                            CaptureMethod.SCREEN_RECORDING.preferenceValue()).commit());
            return;
        }
        SharedPreferences.Editor editor = preferences.edit().clear();
        for (Map.Entry<String, ?> entry : before.entrySet()) {
            Object value = entry.getValue();
            String key = entry.getKey();
            if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
            else if (value instanceof Integer) editor.putInt(key, (Integer) value);
            else if (value instanceof Long) editor.putLong(key, (Long) value);
            else if (value instanceof Float) editor.putFloat(key, (Float) value);
            else if (value instanceof String) editor.putString(key, (String) value);
            else if (value instanceof Set) {
                editor.putStringSet(key, new LinkedHashSet<>((Set<String>) value));
            }
        }
        assertTrue(editor.commit());
    }

    @Test public void stoppedNormalSessionGetsCensorOnlyWithoutChangingSavedState() {
        AppModeManager mode = new AppModeManager(context);
        Map<String, ?> saved = preferences.getAll();
        assertFalse(mode.shouldRecognize("chosen.app"));
        try (CensorLabCensorSession.Lease ignored = CensorLabCensorSession.acquire()) {
            assertTrue(mode.shouldRecognize("chosen.app"));
            assertFalse(mode.shouldRecognize("excluded.app"));
            assertFalse(mode.shouldRecognize(context.getPackageName()));
            assertFalse(mode.shouldRecognize("com.android.settings"));
            assertFalse(mode.shouldRecognize("com.google.android.permissioncontroller"));
            assertFalse(mode.shouldRecognize(mode.inputMethodPackage()));
            assertFalse(mode.isArmed());
            assertFalse(mode.shouldShowSubliminal("chosen.app"));
            assertFalse(mode.shouldShowPopups("chosen.app"));
            assertTrue(mode.timerScopeForForeground("chosen.app").isEmpty());
            assertEquals(saved, preferences.getAll());
        }
        assertFalse(mode.shouldRecognize("chosen.app"));
        assertEquals(saved, preferences.getAll());
    }

    @Test public void existingNormalIntentAndCapturePreferenceSurviveLabStop() {
        assertTrue(preferences.edit().putBoolean(AppModeManager.KEY_ARMED, true)
                .putBoolean(FeatureModuleManager.KEY_CENSOR_ENABLED, true)
                .putString(SettingsRepository.KEY_CAPTURE_METHOD,
                        CaptureMethod.APP_MODE.preferenceValue()).commit());
        AppModeManager mode = new AppModeManager(context);
        Map<String, ?> saved = preferences.getAll();
        assertTrue(mode.shouldRecognize("chosen.app"));
        assertTrue(mode.shouldShowSubliminal("chosen.app"));
        assertTrue(mode.shouldShowPopups("chosen.app"));
        try (CensorLabCensorSession.Lease ignored = CensorLabCensorSession.acquire()) {
            assertTrue(mode.isArmed());
            assertTrue(mode.shouldRecognize("chosen.app"));
            assertFalse(mode.shouldShowSubliminal("chosen.app"));
            assertFalse(mode.shouldShowPopups("chosen.app"));
            assertTrue(mode.timerScopeForForeground("chosen.app").isEmpty());
            assertEquals(saved, preferences.getAll());
        }
        assertTrue(mode.isArmed());
        assertTrue(mode.shouldShowSubliminal("chosen.app"));
        assertTrue(mode.shouldShowPopups("chosen.app"));
        assertEquals(saved, preferences.getAll());
    }

    @Test public void lateLabFrameDoesNotReachNormalLedgerAfterRecordingStops() throws Exception {
        ScreenshotAccessibilityService detachedService = new ScreenshotAccessibilityService();
        Method record = ScreenshotAccessibilityService.class.getDeclaredMethod(
                "recordSupplementedTrackState", java.util.List.class, int.class, int.class, boolean.class);
        record.setAccessible(true);
        try (CensorLabCensorSession.Lease ignored = CensorLabCensorSession.acquire()) {
            // A detached service has no stats/wallet dependencies. Reaching them would fail.
            record.invoke(detachedService, Collections.emptyList(), 1080, 1200, false);
        }
        record.invoke(detachedService, Collections.emptyList(), 1080, 1200, true);
    }
}
