package com.subhub.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.text.Layout;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;

import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.appmode.AppTimerManager;
import com.subhub.app.onboarding.OnboardingActivity;
import com.subhub.app.onboarding.OnboardingState;
import com.subhub.app.penance.HardcoreAutoPayManager;
import com.subhub.app.penance.PaidPauseManager;
import com.subhub.app.penance.PayPalCredentialStore;
import com.subhub.app.penance.PenanceInfraction;
import com.subhub.app.penance.PenanceManager;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.settings.SharedPreferenceTestRestore;

import org.json.JSONArray;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Native layout coverage: synthetic data, no service start, provider write or media capture. */
public final class VisualIdentityAndroidTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final Map<SharedPreferences, Map<String, ?>> before = new LinkedHashMap<>();
    private final List<String> failures = new ArrayList<>();

    @Before public void fixture() throws Exception {
        assertTrue("Use the owned review emulator", android.os.Build.MODEL.contains("sdk_gphone"));
        for (String name : new String[] {SettingsRepository.PREFERENCES_NAME,
                PenanceManager.PREFS_NAME, PayPalCredentialStore.PREFS_NAME,
                "subhub_onboarding", "subhub_privacy", "subhub_service_duration_ui"}) {
            SharedPreferences prefs = context.getSharedPreferences(name, 0);
            before.put(prefs, prefs.getAll());
        }
        OnboardingState.complete(context);
        ControllerPinManager.setPin(context, "2468");
        ControllerPinManager.enterDomMode();
        context.getSharedPreferences("subhub_privacy", 0).edit().putBoolean("app_lock", false).commit();
        context.getPackageManager().getApplicationInfo("com.android.chrome", 0);
        context.getPackageManager().getApplicationInfo("com.android.settings", 0);
        Set<String> selected = Set.of("com.android.chrome", "com.android.settings");
        AppModeManager apps = new AppModeManager(context);
        apps.setAllAppsEnabled(false);
        apps.save(false, selected);
        AppTimerManager timers = new AppTimerManager(context);
        timers.saveSettings(true, 30, true, 60);
        timers.saveAllowances(selected, Map.of("com.android.chrome", 15, "com.android.settings", 40));
        assertEquals(15, timers.allowanceMinutes("com.android.chrome"));
        assertEquals(40, timers.allowanceMinutes("com.android.settings"));
        new PenanceManager(context).configure(true,
                Map.of(PenanceInfraction.NEW_DETECTION, 125, PenanceInfraction.CENSORED_TAP, 75),
                1000, 5000, 10, 15);
        new PaidPauseManager(context).configure(false, 500, 15);
        new PayPalCredentialStore(context).clear();
        new HardcoreAutoPayManager(context).disable();
    }

    @After public void restore() {
        for (Map.Entry<SharedPreferences, Map<String, ?>> entry : before.entrySet())
            SharedPreferenceTestRestore.restore(entry.getKey(), entry.getValue());
        ControllerPinManager.enterSubMode();
    }

    @Test public void primaryPagesKeepTextAndControlsInsideTheirBounds() throws Exception {
        for (String page : new String[] {"MainActivity", "settings.SettingsActivity",
                "appmode.AppModeActivity", "penance.PenanceActivity",
                "atmosphere.AtmosphereActivity", "settings.GlobalSettingsActivity"}) review(page);
        ControllerPinManager.enterSubMode();
        review("MainActivity");
        review("settings.GlobalSettingsActivity");
        finishReview("primary");
    }

    @Test public void secondaryPagesUseReadableLayoutsAndFullTouchTargets() throws Exception {
        for (String page : new String[] {"help.HelpActivity", "help.PermissionSetupActivity",
                "settings.SetupAppsActivity", "security.AuthenticatorActivity",
                "privacy.PrivacyActivity", "stats.StatsActivity", "stats.AchievementsActivity",
                "subliminal.SubliminalSettingsActivity", "popup.PopupStormActivity",
                "capture.CustomImagesActivity", "capture.ExportActivity",
                "capture.export.ExportAppearanceActivity",
                "studio.StudioActivity", "diagnostics.DiagnosticsActivity",
                "update.UpdatesActivity", "commitment.CommitmentActivity"}) review(page);
        finishReview("secondary");
    }

    @Test public void walletEditorsAndEverySetupStepKeepTheirContentReadable() throws Exception {
        for (String editor : new String[] {"rules", "pause", "paypal", "corrections"}) {
            try (ActivityScenario<com.subhub.app.penance.PenanceActivity> page =
                    ActivityScenario.launch(com.subhub.app.penance.PenanceActivity.class)) {
                page.onActivity(a -> a.findViewById(android.R.id.content)
                        .findViewWithTag("wallet:" + editor).performClick());
                settle();
                page.onActivity(a -> inspect(a, "wallet-" + editor));
            }
        }
        try (ActivityScenario<OnboardingActivity> page = ActivityScenario.launch(
                new Intent(context, OnboardingActivity.class).putExtra(OnboardingActivity.REPLAY, true))) {
            for (int step = 0; step < 6; step++) {
                settle();
                final int number = step;
                page.onActivity(a -> inspect(a, "setup-" + number));
                if (step == 1) {
                    for (String style : new String[] {"pixelate", "blur"}) {
                        page.onActivity(a -> a.findViewById(android.R.id.content)
                                .findViewWithTag("setup-style:" + style).performClick());
                        settle();
                        page.onActivity(a -> inspect(a, "setup-preview-" + style));
                    }
                }
                if (step < 5) page.onActivity(a -> a.findViewById(R.id.tour_next).performClick());
            }
        }
        finishReview("editors-setup");
    }

    @Test public void sharedTextPaletteRemainsReadableOnEverySurface() {
        int backdrop = context.getColor(R.color.background);
        for (int surface : new int[] {R.color.surface_card, R.color.surface_card_raised, R.color.dialog_surface}) {
            int color = ColorUtils.compositeColors(context.getColor(surface), backdrop);
            for (int foreground : new int[] {R.color.text_primary, R.color.text_secondary,
                    R.color.text_muted, R.color.accent_text}) {
                assertTrue("Shared small text must retain 4.5:1 contrast",
                        ColorUtils.calculateContrast(context.getColor(foreground), color) >= 4.5);
            }
        }
    }

    @Test public void appUnlockKeepsTheSharedLayoutWhenDeviceAuthenticationIsUnavailable() throws Exception {
        int availability = androidx.biometric.BiometricManager.from(context).canAuthenticate(
                com.subhub.app.privacy.AppUnlockActivity.AUTHENTICATORS);
        assertTrue("Review AVD must not have enrolled device authentication",
                availability != androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS);
        context.getSharedPreferences("subhub_privacy", 0).edit().putBoolean("app_lock", true).commit();
        review("privacy.AppUnlockActivity");
        finishReview("app-unlock");
    }

    @Test public void expandedSettingsAnswersAndPackTabsKeepTheirLayoutRules() throws Exception {
        try (ActivityScenario<com.subhub.app.settings.GlobalSettingsActivity> page =
                ActivityScenario.launch(com.subhub.app.settings.GlobalSettingsActivity.class)) {
            page.onActivity(a -> a.findViewById(android.R.id.content).findViewWithTag("settings:apps").performClick());
            settle();
            page.onActivity(a -> inspect(a, "settings-apps-expanded"));
        }
        try (ActivityScenario<com.subhub.app.settings.SettingsActivity> page =
                ActivityScenario.launch(com.subhub.app.settings.SettingsActivity.class)) {
            page.onActivity(a -> {
                a.findViewById(R.id.button_appearance_details).performClick();
                a.findViewById(R.id.button_capture_details).performClick();
                a.findViewById(R.id.button_other_areas).performClick();
            });
            for (int style : new int[] {R.id.radio_box, R.id.radio_pixelate, R.id.radio_blur,
                    R.id.radio_custom, R.id.radio_static, R.id.radio_glitch, R.id.radio_tape, R.id.radio_error}) {
                page.onActivity(a -> a.findViewById(style).performClick());
                settle();
                page.onActivity(a -> inspect(a, "censor-expanded-" + a.getResources().getResourceEntryName(style)));
            }
        }
        try (ActivityScenario<com.subhub.app.help.HelpActivity> page =
                ActivityScenario.launch(com.subhub.app.help.HelpActivity.class)) {
            List<View> questions = new ArrayList<>();
            page.onActivity(a -> collectTagged(a.findViewById(android.R.id.content), "help_question:", questions));
            assertTrue("The review must include every answer", questions.size() >= 15);
            for (View question : questions) {
                page.onActivity(a -> ((ViewGroup) question).getChildAt(0).performClick());
                settle();
                page.onActivity(a -> inspect(a, question.getTag().toString()));
            }
        }
        try (ActivityScenario<com.subhub.app.studio.StudioActivity> page =
                ActivityScenario.launch(com.subhub.app.studio.StudioActivity.class)) {
            for (int tab : new int[] {R.id.tab_drafts, R.id.tab_create, R.id.tab_library}) {
                page.onActivity(a -> a.findViewById(tab).performClick());
                settle();
                page.onActivity(a -> inspect(a, "packs-" + a.getResources().getResourceEntryName(tab)));
            }
        }
        try (ActivityScenario<com.subhub.app.security.AuthenticatorActivity> page =
                ActivityScenario.launch(com.subhub.app.security.AuthenticatorActivity.class)) {
            page.onActivity(a -> a.findViewById(R.id.keyholder_remote_header).performClick());
            settle();
            page.onActivity(a -> inspect(a, "keyholder-remote-expanded"));
            page.onActivity(a -> a.findViewById(R.id.keyholder_pair_button).performClick());
            settle();
            page.onActivity(a -> inspect(a, "keyholder-pairing"));
            page.onActivity(a -> a.findViewById(R.id.authenticator_pairing_cancel).performClick());
        }
        finishReview("expanded");
    }

    @Test public void limitsKeepDistinctAllowancesWithoutPaddedAppRows() {
        try (ActivityScenario<com.subhub.app.appmode.AppModeActivity> page =
                ActivityScenario.launch(com.subhub.app.appmode.AppModeActivity.class)) {
            final EditText[] fields = new EditText[2];
            long deadline = SystemClock.uptimeMillis() + 5000;
            while (SystemClock.uptimeMillis() < deadline) {
                page.onActivity(a -> {
                    View root = a.findViewById(android.R.id.content);
                    fields[0] = root.findViewWithTag("limit:com.android.chrome");
                    fields[1] = root.findViewWithTag("limit:com.android.settings");
                });
                if (fields[0] != null && fields[1] != null) break;
                SystemClock.sleep(100);
            }
            page.onActivity(a -> {
                assertTrue("Both selected installed apps must be rendered", fields[0] != null && fields[1] != null);
                assertEquals("15", fields[0].getText().toString());
                assertEquals("40", fields[1].getText().toString());
                for (EditText field : fields) {
                    int minimum = a.getResources().getDimensionPixelSize(R.dimen.control_min_height);
                    assertTrue(field.getHeight() >= minimum);
                    if (a.getResources().getConfiguration().fontScale <= 1.05f
                            && a.getResources().getConfiguration().screenWidthDp >= 340) {
                        View row = (View) field.getParent().getParent();
                        int compactMaximum = Math.round(72 * a.getResources().getDisplayMetrics().density);
                        assertTrue("Compact app rows should follow Wallet density", row.getHeight() <= compactMaximum);
                    }
                }
            });
        }
    }

    private void review(String name) throws Exception {
        @SuppressWarnings("unchecked")
        Class<? extends Activity> type = (Class<? extends Activity>) Class.forName("com.subhub.app." + name);
        Intent intent = new Intent(context, type);
        if (type == MainActivity.class) intent.setAction(Intent.ACTION_MAIN);
        try (ActivityScenario<? extends Activity> page = ActivityScenario.launch(intent)) {
            settle();
            String label = name + (ControllerPinManager.isDomModeActive() ? "-dom" : "-sub");
            page.onActivity(a -> inspect(a, label));
            page.onActivity(a -> scrollToBottom(a.findViewById(android.R.id.content)));
            settle();
            page.onActivity(a -> capture(a, label + "-bottom"));
        }
    }

    private void inspect(Activity activity, String name) {
        View content = activity.findViewById(android.R.id.content);
        assertTrue("Activity content must be laid out: " + name, content.getWidth() > 0);
        inspectView(content, name);
        capture(activity, name);
    }

    private void inspectView(View view, String page) {
        if (view.getVisibility() != View.VISIBLE) return;
        if (view instanceof TextView && view.getHeight() > 0) {
            TextView text = (TextView) view;
            Layout layout = text.getLayout();
            if (layout != null && text.getText().length() > 0) {
                String label = page + ": " + text.getText().toString().replace('\n', ' ');
                int width = text.getWidth() - text.getCompoundPaddingLeft() - text.getCompoundPaddingRight();
                int height = text.getHeight() - text.getCompoundPaddingTop() - text.getCompoundPaddingBottom();
                int renderedHeight = view instanceof EditText
                        ? layout.getLineBottom(0) - layout.getLineTop(0) : layout.getHeight();
                if (renderedHeight > height + 1) failures.add("Clipped text height: " + label);
                if (!(view instanceof EditText) && text.getEllipsize() == null) {
                    for (int line = 0; line < layout.getLineCount(); line++) {
                        // Measure visible text, including indents, without counting
                        // trailing wrap whitespace as clipped ink.
                        if (layout.getLineMax(line) > width + 1) {
                            failures.add("Clipped text width: " + label); break;
                        }
                    }
                }
            }
            if (view instanceof Button || view instanceof CompoundButton || view.isClickable()) {
                int minimum = context.getResources().getDimensionPixelSize(R.dimen.control_min_height);
                if (view.getHeight() < minimum) failures.add("Small touch target: " + page + ": " + text.getText());
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) inspectView(group.getChildAt(index), page);
        }
    }

    private static void scrollToBottom(View view) {
        if (view instanceof ScrollView) ((ScrollView) view).fullScroll(View.FOCUS_DOWN);
        else if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) scrollToBottom(group.getChildAt(i));
        }
    }

    private static void collectTagged(View view, String prefix, List<View> matches) {
        if (view.getTag() instanceof String && ((String) view.getTag()).startsWith(prefix)) matches.add(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collectTagged(group.getChildAt(i), prefix, matches);
        }
    }

    private void capture(Activity activity, String name) {
        View root = activity.findViewById(android.R.id.content);
        Bitmap bitmap = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(bitmap);
            android.graphics.drawable.Drawable background = activity.getWindow().getDecorView().getBackground();
            if (background != null) {
                background = background.getConstantState() == null ? background
                        : background.getConstantState().newDrawable(activity.getResources()).mutate();
                background.setBounds(0, 0, root.getWidth(), root.getHeight());
                background.draw(canvas);
            }
            root.draw(canvas);
            File directory = new File(context.getFilesDir(), "identity-review");
            assertTrue(directory.isDirectory() || directory.mkdirs());
            String profile = context.getResources().getConfiguration().screenWidthDp + "-"
                    + context.getResources().getConfiguration().fontScale;
            try (FileOutputStream output = new FileOutputStream(new File(directory, profile + "-" + name + ".png"))) {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
            }
        } catch (Exception failure) { throw new AssertionError(failure); }
        finally { bitmap.recycle(); }
    }

    private void finishReview(String name) throws Exception {
        File report = new File(context.getFilesDir(), "identity-" + name + "-failures.json");
        try (FileOutputStream output = new FileOutputStream(report)) {
            output.write(new JSONArray(failures).toString(2).getBytes(StandardCharsets.UTF_8));
        }
        assertTrue(failures.toString(), failures.isEmpty());
    }

    private static void settle() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        SystemClock.sleep(500);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }
}
