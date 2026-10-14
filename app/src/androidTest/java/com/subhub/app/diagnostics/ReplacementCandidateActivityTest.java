package com.subhub.app.diagnostics;

import static org.junit.Assert.*;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.matcher.ViewMatchers.withId;

import android.content.Context;
import android.content.SharedPreferences;
import android.Manifest;
import android.app.UiAutomation;
import android.graphics.Bitmap;
import android.provider.Settings;
import android.view.View;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.R;
import com.subhub.app.censor.android.ProductCensorQualification;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.service.ScreenshotAccessibilityService;
import com.subhub.app.util.StateToggle;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Owned-guest UI/selection proof, not a physical-device or detector-performance verdict. */
@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion = 34)
public final class ReplacementCandidateActivityTest {
    private Context context;
    private SharedPreferences preferences;
    private Map<String, ?> saved;
    private boolean wasDom;
    private String previousServices;
    private int previousEnabled;
    private UiAutomation automation;

    @Before public void prepareOnlyOwnedGuestSelection() {
        assertEquals("true", InstrumentationRegistry.getArguments().getString("bound_censor_fixture"));
        assertTrue("No phone authority", ProductCensorQualification.supported());
        assertFalse("No existing capture owner", ScreenshotAccessibilityService.isRunning());
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        previousServices = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        previousEnabled = Settings.Secure.getInt(context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0);
        assertFalse("No enabled capture intent may be borrowed", previousEnabled != 0 && previousServices != null
                && (previousServices.contains("ScreenshotAccessibilityService") || previousServices.contains("ScreenCapture")));
        preferences = context.getSharedPreferences(ProductCensorQualification.PREFERENCES, Context.MODE_PRIVATE);
        saved = new HashMap<>(preferences.getAll());
        wasDom = ControllerPinManager.isDomModeActive();
        ControllerPinManager.enterDomMode();
        assertTrue(preferences.edit().remove(ProductCensorQualification.CANDIDATE_SELECTION).putBoolean("enabled", false).commit());
        automation = InstrumentationRegistry.getInstrumentation().getUiAutomation(); // Before creating target Views.
    }

    @After public void restoreExactSelectionAndRole() {
        if (saved == null) return;
        // UI automation can make an inactive remembered Accessibility selection globally enabled.
        // Remove that registration before restoring the exact guest state for the next fixture.
        automation = InstrumentationRegistry.getInstrumentation().getUiAutomation(UiAutomation.FLAG_DONT_USE_ACCESSIBILITY);
        automation.adoptShellPermissionIdentity(Manifest.permission.WRITE_SECURE_SETTINGS);
        try {
            assertTrue(Settings.Secure.putString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, previousServices));
            assertTrue(Settings.Secure.putInt(context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, previousEnabled));
        } finally { automation.dropShellPermissionIdentity(); }
        SharedPreferences.Editor editor = preferences.edit().clear();
        for (Map.Entry<String, ?> entry : saved.entrySet()) {
            String key = entry.getKey(); Object value = entry.getValue();
            if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
            else if (value instanceof String) editor.putString(key, (String) value);
            else if (value instanceof Integer) editor.putInt(key, (Integer) value);
            else if (value instanceof Long) editor.putLong(key, (Long) value);
            else if (value instanceof Float) editor.putFloat(key, (Float) value);
            else if (value instanceof Set<?>) {
                Set<String> strings = new HashSet<>();
                for (Object item : (Set<?>) value) strings.add((String) item);
                editor.putStringSet(key, strings);
            } else throw new AssertionError("Unknown owned fixture preference type");
        }
        assertTrue(editor.commit());
        if (wasDom) ControllerPinManager.enterDomMode(); else ControllerPinManager.enterSubMode();
        assertEquals(saved, preferences.getAll());
        assertEquals(previousServices, Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES));
        assertEquals(previousEnabled, Settings.Secure.getInt(context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0));
    }

    @Test public void ordinaryBuildHidesControlAndIgnoresStaleSelection() {
        Assume.assumeFalse(ProductCensorQualification.candidateSupported());
        assertTrue(preferences.edit().putBoolean(ProductCensorQualification.CANDIDATE_SELECTION, true).commit());
        assertFalse(ProductCensorQualification.candidateSelected(context));
        assertFalse(ProductCensorQualification.enabled(context));
        try (ActivityScenario<DiagnosticsActivity> scenario = ActivityScenario.launch(DiagnosticsActivity.class)) {
            scenario.onActivity(activity -> assertEquals(View.GONE,
                    activity.findViewById(R.id.replacement_candidate_controls).getVisibility()));
        }
        try { ProductCensorQualification.selectCandidate(context, true); fail("Ordinary build cannot select candidate"); }
        catch (IllegalStateException expected) { }
        assertTrue(preferences.getBoolean(ProductCensorQualification.CANDIDATE_SELECTION, false));
        assertFalse(ScreenshotAccessibilityService.isRunning());
    }

    @Test public void supportedTestBuildDefaultsOffAndNativeTapPersistsSelection() throws Exception {
        Assume.assumeTrue(ProductCensorQualification.candidateSupported());
        assertFalse(ProductCensorQualification.candidateSelected(context));
        assertFalse(ProductCensorQualification.enabled(context));
        try (ActivityScenario<DiagnosticsActivity> scenario = ActivityScenario.launch(DiagnosticsActivity.class)) {
            scenario.onActivity(activity -> {
                assertEquals(View.VISIBLE, activity.findViewById(R.id.replacement_candidate_controls).getVisibility());
                StateToggle toggle = activity.findViewById(R.id.switch_replacement_candidate);
                assertTrue(toggle.isEnabled()); assertFalse(toggle.isChecked());
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            onView(withId(R.id.switch_replacement_candidate)).perform(scrollTo(), click());
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> assertTrue(((StateToggle) activity.findViewById(R.id.switch_replacement_candidate)).isChecked()));
            assertTrue(ProductCensorQualification.candidateSelected(context));
            assertTrue(ProductCensorQualification.enabled(context));
            assertFalse("Fixture key is independent", preferences.getBoolean("enabled", true));
            assertFalse("A preference write cannot start/swap the service", ScreenshotAccessibilityService.isRunning());
            scenario.recreate();
            scenario.onActivity(activity -> assertTrue(((StateToggle) activity.findViewById(R.id.switch_replacement_candidate)).isChecked()));
            assertTrue(ProductCensorQualification.candidateSelected(context));
            if ("true".equals(InstrumentationRegistry.getArguments().getString("tap_fixture_capture"))) {
                String run = InstrumentationRegistry.getArguments().getString("candidate_evidence_run");
                savePixels(run == null ? UUID.randomUUID().toString().replace("-", "") : run);
            }
            onView(withId(R.id.switch_replacement_candidate)).perform(scrollTo(), click());
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertFalse(ProductCensorQualification.candidateSelected(context));
            assertFalse(ProductCensorQualification.enabled(context));
        }
    }

    @Test public void supportedTestSubCannotPersistSelection() {
        Assume.assumeTrue(ProductCensorQualification.candidateSupported());
        ControllerPinManager.enterSubMode();
        try (ActivityScenario<DiagnosticsActivity> scenario = ActivityScenario.launch(DiagnosticsActivity.class)) {
            scenario.onActivity(activity -> {
                StateToggle toggle = activity.findViewById(R.id.switch_replacement_candidate);
                assertFalse(toggle.isEnabled());
                toggle.performClick();
                toggle.setChecked(true); // Adverse programmatic delivery must not bypass role authority.
                assertFalse(ProductCensorQualification.candidateSelected(activity));
            });
        }
        try { ProductCensorQualification.selectCandidate(context, true); fail("Sub cannot select candidate"); }
        catch (IllegalStateException expected) { }
        assertFalse(preferences.contains(ProductCensorQualification.CANDIDATE_SELECTION));
    }

    @Test public void supportedTestMalformedSelectionCannotEnableOwner() {
        Assume.assumeTrue(ProductCensorQualification.candidateSupported());
        assertTrue(preferences.edit().putString(ProductCensorQualification.CANDIDATE_SELECTION, "true").commit());
        assertFalse(ProductCensorQualification.candidateSelected(context));
        assertFalse(ProductCensorQualification.enabled(context));
    }

    @Test public void captureNamespacesRejectInvalidAndExistingRuns() {
        String run = UUID.randomUUID().toString().replace("-", "");
        File directory = freshEvidenceDirectory(run);
        assertTrue(directory.isDirectory());
        assertThrows(AssertionError.class, () -> freshEvidenceDirectory(run));
        assertThrows(IllegalArgumentException.class, () -> freshEvidenceDirectory("../retained"));
    }

    private File freshEvidenceDirectory(String run) {
        if (run == null || !run.matches("[a-f0-9]{32}")) {
            throw new IllegalArgumentException("A bounded capture run identity is required");
        }
        File parent = context.getExternalFilesDir(null);
        assertNotNull(parent);
        File directory = new File(parent, "replacement-candidate-" + run);
        assertTrue("Refuse a retained capture namespace", directory.mkdir());
        return directory;
    }

    private void savePixels(String run) throws Exception {
        File directory = freshEvidenceDirectory(run); // Reject retained evidence before acquiring pixels.
        Bitmap pixels = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull(pixels);
        try {
            File file = new File(directory, "selected.png");
            assertFalse("Retain previous evidence", file.exists());
            try (FileOutputStream output = new FileOutputStream(file)) {
                assertTrue(pixels.compress(Bitmap.CompressFormat.PNG, 100, output));
            }
        } finally { pixels.recycle(); }
    }
}
