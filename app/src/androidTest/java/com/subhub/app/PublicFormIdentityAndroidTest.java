package com.subhub.app;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.penance.*;
import com.subhub.app.onboarding.OnboardingState;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.settings.SharedPreferenceTestRestore;
import com.subhub.app.util.StateToggle;
import java.io.File;
import java.io.FileOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;

/** Equivalent form roles must have identical resolved styling, beyond text-fit checks. */
public final class PublicFormIdentityAndroidTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final Map<SharedPreferences, Map<String, ?>> before = new LinkedHashMap<>();
    @Before public void fixture() {
        assertTrue(android.os.Build.MODEL.contains("sdk_gphone"));
        for (String name : new String[]{SettingsRepository.PREFERENCES_NAME, PenanceManager.PREFS_NAME,
                PayPalCredentialStore.PREFS_NAME, "subhub_onboarding"}) {
            SharedPreferences prefs = context.getSharedPreferences(name, 0); before.put(prefs, prefs.getAll());
        }
        OnboardingState.complete(context); ControllerPinManager.setPin(context, "2468"); ControllerPinManager.enterDomMode();
        new PayPalCredentialStore(context).clear(); new HardcoreAutoPayManager(context).disable();
        java.util.Set<String> selected = java.util.Set.of("com.android.chrome", "com.android.settings");
        com.subhub.app.appmode.AppModeManager apps = new com.subhub.app.appmode.AppModeManager(context);
        apps.setAllAppsEnabled(false); apps.save(false, selected);
        com.subhub.app.appmode.AppTimerManager timers = new com.subhub.app.appmode.AppTimerManager(context);
        timers.saveSettings(true, 30, true, 60);
        timers.saveAllowances(selected, Map.of("com.android.chrome", 15, "com.android.settings", 40));
        assertEquals(15, timers.allowanceMinutes("com.android.chrome"));
        assertEquals(40, timers.allowanceMinutes("com.android.settings"));
        new PenanceManager(context).configure(true, Map.of(PenanceInfraction.NEW_DETECTION, 125,
                PenanceInfraction.CENSORED_DWELL, 75, PenanceInfraction.CENSORED_TAP, 50,
                PenanceInfraction.WATCHED_APP_OPEN, 100, PenanceInfraction.TAMPER_ATTEMPT, 200), 5000, 20000, 10, 30);
        new PaidPauseManager(context).configure(true, 500, 15);
    }
    @After public void restore() {
        for (Map.Entry<SharedPreferences, Map<String, ?>> entry : before.entrySet()) SharedPreferenceTestRestore.restore(entry.getKey(), entry.getValue());
        ControllerPinManager.enterSubMode();
    }
    @Test public void walletRulesCapsAndPauseUseTheSameFormRoles() {
        try (ActivityScenario<PenanceActivity> page = ActivityScenario.launch(PenanceActivity.class)) {
            settle();
            page.onActivity(a -> a.findViewById(android.R.id.content).findViewWithTag("wallet:rules").performClick()); settle();
            page.onActivity(a -> {
                int[] inputs = {R.id.rule_detection_amount, R.id.detection_batch, R.id.rule_dwell_amount,
                        R.id.dwell_seconds, R.id.rule_tap_amount, R.id.rule_app_open_amount,
                        R.id.rule_tamper_amount, R.id.tamper_cooldown_minutes, R.id.daily_cap, R.id.weekly_cap, R.id.mercy_minutes};
                for (int id : inputs) {
                    EditText input = a.findViewById(id); assertInput(a, input);
                    TextView label = findLabel(a.findViewById(R.id.rule_config_card), id); assertNotNull("Missing field label", label);
                    TextView expected = (TextView) a.getLayoutInflater().inflate(R.layout.view_form_label, null, false);
                    assertTextRole("field label " + id, expected, label);
                }
                assertSwitches(a, a.findViewById(R.id.rule_config_card));
                assertRows(a.findViewById(R.id.rule_config_card));
                captureSections(a, a.findViewById(R.id.rule_config_card), "rules-caps");
            });
        }
        try (ActivityScenario<PenanceActivity> page = ActivityScenario.launch(PenanceActivity.class)) {
            page.onActivity(a -> a.findViewById(android.R.id.content).findViewWithTag("wallet:pause").performClick()); settle();
            page.onActivity(a -> {
                assertInput(a, a.findViewById(R.id.paid_pause_amount)); assertInput(a, a.findViewById(R.id.paid_pause_minutes));
                assertSwitches(a, a.findViewById(R.id.paid_pause_config_card));
                assertRows(a.findViewById(R.id.paid_pause_config_card));
                captureSections(a, a.findViewById(R.id.paid_pause_config_card), "paid-pause");
            });
        }
        for (String editor : new String[]{"paypal", "corrections"}) {
            try (ActivityScenario<PenanceActivity> page = ActivityScenario.launch(PenanceActivity.class)) {
                page.onActivity(a -> a.findViewById(android.R.id.content).findViewWithTag("wallet:" + editor).performClick());
                settle();
                page.onActivity(a -> {
                    assertFormControls(a, a.findViewById(R.id.wallet_editor));
                    assertRows(a.findViewById(R.id.wallet_editor));
                    captureSections(a, a.findViewById(R.id.wallet_editor), "wallet-" + editor);
                });
            }
        }
    }
    private static void assertInput(android.app.Activity a, EditText actual) {
        EditText expected = (EditText) a.getLayoutInflater().inflate(R.layout.view_preference_input, null, false);
        android.graphics.Typeface typeface = expected.getTypeface();
        expected.setInputType(actual.getInputType()); expected.setTypeface(typeface); expected.setEnabled(actual.isEnabled());
        assertTextRole("input " + actual.getId(), expected, actual);
        assertEquals("Input start padding", expected.getPaddingStart(), actual.getPaddingStart());
        assertEquals("Input end padding", expected.getPaddingEnd(), actual.getPaddingEnd());
        assertEquals("Input top padding", expected.getPaddingTop(), actual.getPaddingTop());
        assertEquals("Input bottom padding", expected.getPaddingBottom(), actual.getPaddingBottom());
        assertEquals("Input font padding", expected.getIncludeFontPadding(), actual.getIncludeFontPadding());
        assertEquals("Input alpha", 1f, actual.getAlpha(), 0f);
        if (actual.getParent() instanceof android.widget.LinearLayout) {
            android.widget.LinearLayout parent = (android.widget.LinearLayout) actual.getParent();
            int index = parent.indexOfChild(actual);
            if (parent.getOrientation() == android.widget.LinearLayout.VERTICAL && index > 0
                    && parent.getChildAt(index - 1) instanceof TextView)
                assertEquals("Label/help to input gap " + actual.getId() + " on " + a.getClass().getSimpleName(), a.getResources().getDimensionPixelSize(R.dimen.ui_gap_inline),
                        ((android.view.ViewGroup.MarginLayoutParams) actual.getLayoutParams()).topMargin);
        }
        assertNull("Input background tint", actual.getBackgroundTintList());
        if (actual.isShown()) assertTrue("Input touch height", actual.getHeight() >= a.getResources().getDimensionPixelSize(R.dimen.control_min_height));
    }

    @Test public void publicEditorsShareInputAndSwitchStylesInBothControlStates() throws Exception {
        for (String name : new String[]{"appmode.AppModeActivity", "settings.GlobalSettingsActivity",
                "settings.SettingsActivity", "subliminal.SubliminalSettingsActivity", "popup.PopupStormActivity",
                "capture.export.ExportAppearanceActivity", "studio.StudioActivity", "privacy.PrivacyActivity"}) {
            Class<? extends android.app.Activity> type = Class.forName("com.subhub.app." + name).asSubclass(android.app.Activity.class);
            try (ActivityScenario<? extends android.app.Activity> page = ActivityScenario.launch(type)) {
                settle();
                page.onActivity(a -> {
                    View advanced = a.findViewById(R.id.button_advanced_details);
                    if (advanced != null) advanced.performClick();
                    View timing = a.findViewById(R.id.advanced_enabled);
                    if (timing instanceof StateToggle) ((StateToggle) timing).setChecked(true);
                    View custom = a.findViewById(R.id.pack_custom);
                    if (custom instanceof android.widget.CompoundButton) ((android.widget.CompoundButton) custom).setChecked(true);
                    View appearance = a.findViewById(R.id.button_appearance_details);
                    if (appearance != null) appearance.performClick();
                    View capture = a.findViewById(R.id.button_capture_details);
                    if (capture != null) capture.performClick();
                    View areas = a.findViewById(R.id.button_other_areas);
                    if (areas != null) areas.performClick();
                    View apps = a.findViewById(android.R.id.content).findViewWithTag("settings:apps");
                    if (apps != null) apps.performClick();
                });
                settle();
                page.onActivity(a -> {
                    assertFormControls(a, a.findViewById(android.R.id.content));
                    assertRows(a.findViewById(android.R.id.content));
                    captureScrollContents(a, a.findViewById(android.R.id.content), name.replace('.', '-'));
                });
            }
        }
    }

    @Test public void everyPackSectionAndColorDialogUsesSharedFormControls() {
        try (ActivityScenario<com.subhub.app.studio.StudioActivity> page = ActivityScenario.launch(com.subhub.app.studio.StudioActivity.class)) {
            for (String section : new String[]{"modules", "censor", "wallet", "limits", "subliminal", "popup"}) {
                page.onActivity(a -> openPackEditor(a, section));
                settle();
                androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.isRoot())
                        .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).check((view, missing) -> {
                            if (missing != null) throw missing;
                            expandPackGroups(view);
                        });
                settle();
                androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.isRoot())
                        .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).check((view, missing) -> {
                            if (missing != null) throw missing;
                            android.app.Activity a = activity(view.getContext());
                            assertFormControls(a, view); assertRows(view); captureScrollContents(a, view, "pack-" + section);
                        });
                androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withId(android.R.id.button2))
                        .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(androidx.test.espresso.action.ViewActions.click());
            }
            page.onActivity(a -> com.subhub.app.util.ColorPickerDialog.show(a, "Color", android.graphics.Color.RED, ignored -> {}));
            settle();
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.isRoot())
                    .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).check((view, missing) -> {
                        if (missing != null) throw missing;
                        captureScrollContents(activity(view.getContext()), view, "color-dialog");
                    });
            androidx.test.espresso.Espresso.pressBack();
        }
    }

    private static void openPackEditor(androidx.appcompat.app.AppCompatActivity a, String section) {
        try {
            java.lang.reflect.Method show = Class.forName("com.subhub.app.studio.PackSectionEditor")
                    .getDeclaredMethod("show", androidx.appcompat.app.AppCompatActivity.class,
                            String.class, String.class, org.json.JSONObject.class, java.util.function.Consumer.class);
            show.setAccessible(true);
            show.invoke(null, a, section, section, com.subhub.app.pack.PackSettingCatalog.defaults(section),
                    (java.util.function.Consumer<org.json.JSONObject>) ignored -> {});
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private static android.app.Activity activity(Context context) {
        while (context instanceof android.content.ContextWrapper) {
            if (context instanceof android.app.Activity) return (android.app.Activity) context;
            context = ((android.content.ContextWrapper) context).getBaseContext();
        }
        throw new AssertionError("Missing dialog activity");
    }

    private static void expandPackGroups(View view) {
        if (view.getTag() instanceof String && ((String) view.getTag()).startsWith("pack_group:")) {
            ViewGroup parent = (ViewGroup) view.getParent();
            int index = parent.indexOfChild(view);
            if (index + 1 < parent.getChildCount() && parent.getChildAt(index + 1).getVisibility() == View.GONE) view.performClick();
        }
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) expandPackGroups(((ViewGroup) view).getChildAt(i));
    }

    private static void assertFormControls(android.app.Activity a, View view) {
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            for (int id : new int[]{R.string.privacy_discreet_help, R.string.privacy_lock_help,
                    R.string.app_selection_all_help, R.string.export_independent_help,
                    R.string.export_custom_assets_help}) {
                if (a.getString(id).contentEquals(text)) {
                    TextView reference = (TextView) a.getLayoutInflater().inflate(R.layout.view_form_help, null, false);
                    assertTextRole("Field help " + id, reference, (TextView) view);
                }
            }
        }
        if (view.isShown() && (view instanceof android.widget.RadioButton || view instanceof android.widget.Button)) {
            TextView label = (TextView) view;
            android.text.Layout layout = label.getLayout();
            String text = label.getText().toString();
            if (layout != null) for (int line = 0; line + 1 < layout.getLineCount(); line++) {
                int end = layout.getLineEnd(line);
                if (end > 0 && end < text.length()) assertFalse("Control splits a word: " + text,
                        Character.isLetterOrDigit(text.charAt(end - 1)) && Character.isLetterOrDigit(text.charAt(end)));
            }
        }
        if (view instanceof EditText) {
            EditText input = (EditText) view;
            assertInput(a, input);
            boolean enabled = input.isEnabled(); input.setEnabled(false); assertInput(a, input); input.setEnabled(enabled);
        }
        if (view instanceof StateToggle) {
            assertSwitches(a, view);
            boolean enabled = view.isEnabled(); view.setEnabled(false); assertSwitches(a, view); view.setEnabled(enabled);
        }
        if (view instanceof android.widget.CheckBox || view instanceof android.widget.RadioButton) {
            TextView choice = (TextView) view;
            boolean enabled = view.isEnabled(); view.setEnabled(false);
            assertEquals("Disabled choice color", a.getColor(R.color.text_muted), choice.getCurrentTextColor());
            view.setEnabled(enabled);
        }
        if (view instanceof android.widget.Spinner) {
            View selected = ((android.widget.Spinner) view).getSelectedView();
            if (selected instanceof TextView) {
                TextView reference = (TextView) a.getLayoutInflater().inflate(R.layout.view_form_spinner_value, null, false);
                assertTextRole("Selected dropdown value", reference, (TextView) selected);
            }
        }
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) assertFormControls(a, ((ViewGroup) view).getChildAt(i));
    }

    private static void assertRows(View view) {
        if (view instanceof com.subhub.app.util.CompactFieldLayout && view.isShown()) {
            ViewGroup grid = (ViewGroup) view;
            for (int i = 1; i < grid.getChildCount(); i++) {
                View left = grid.getChildAt(i - 1), right = grid.getChildAt(i);
                if (!left.isShown() || !right.isShown() || left.getTop() != right.getTop()
                        || !(left instanceof ViewGroup) || !(right instanceof ViewGroup)) continue;
                ViewGroup first = (ViewGroup) left, second = (ViewGroup) right;
                if (first.getChildCount() < 2 || second.getChildCount() < 2) continue;
                View a = first.getChildAt(0), b = second.getChildAt(0);
                if (a instanceof TextView && b instanceof TextView
                        && !(a instanceof android.widget.CompoundButton) && !(b instanceof android.widget.CompoundButton))
                    assertEquals("Pack control top alignment", first.getChildAt(1).getTop(), second.getChildAt(1).getTop());
            }
        }
        if (view instanceof com.subhub.app.util.FormRow && view.isShown()) {
            android.widget.LinearLayout row = (android.widget.LinearLayout) view;
            if (row.getChildCount() == 2) {
                ViewGroup first = (ViewGroup) row.getChildAt(0), second = (ViewGroup) row.getChildAt(1);
                View input1 = first.getChildAt(first.getChildCount() - 1);
                View input2 = second.getChildAt(second.getChildCount() - 1);
                if (row.getOrientation() == android.widget.LinearLayout.HORIZONTAL) {
                    assertEquals("Paired input top alignment", first.getTop() + input1.getTop(), second.getTop() + input2.getTop());
                    assertTrue("Paired field gap", first.getRight() < second.getLeft() || second.getRight() < first.getLeft());
                } else assertTrue("Stacked fields cannot overlap", first.getBottom() < second.getTop());
            }
        }
        if (view instanceof com.subhub.app.util.SelectionGrid && view.isShown()) {
            ViewGroup grid = (ViewGroup) view;
            for (int i = 1; i < grid.getChildCount(); i++) {
                View previous = grid.getChildAt(i - 1), next = grid.getChildAt(i);
                if (previous.getVisibility() != View.GONE && next.getVisibility() != View.GONE && previous.getTop() == next.getTop())
                    assertEquals("Choice tiles in one row share height", previous.getHeight(), next.getHeight());
            }
        }
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) assertRows(((ViewGroup) view).getChildAt(i));
    }

    private static void captureScrollContents(android.app.Activity a, View view, String name) {
        if (view instanceof android.widget.ScrollView && ((ViewGroup) view).getChildCount() > 0) {
            captureSections(a, ((ViewGroup) view).getChildAt(0), name);
            return;
        }
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) captureScrollContents(a, ((ViewGroup) view).getChildAt(i), name + "-" + i);
    }
    private static void assertTextRole(String label, TextView expected, TextView actual) {
        assertEquals(label + " size", expected.getTextSize(), actual.getTextSize(), 0.01f);
        assertEquals(label + " color", expected.getCurrentTextColor(), actual.getCurrentTextColor());
        assertEquals(label + " typeface", expected.getTypeface(), actual.getTypeface());
        assertEquals(label + " font padding", expected.getIncludeFontPadding(), actual.getIncludeFontPadding());
    }
    private static void assertSwitches(android.app.Activity a, View view) {
        if (view instanceof StateToggle) {
            StateToggle expected = (StateToggle) a.getLayoutInflater().inflate(R.layout.view_form_toggle, null, false);
            expected.setEnabled(view.isEnabled()); assertTextRole("switch", expected, (StateToggle) view);
        }
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) assertSwitches(a, ((ViewGroup) view).getChildAt(i));
    }
    private static TextView findLabel(View view, int inputId) {
        if (view instanceof TextView && ((TextView) view).getLabelFor() == inputId) return (TextView) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            TextView match = findLabel(((ViewGroup) view).getChildAt(i), inputId); if (match != null) return match;
        }
        return null;
    }
    private static void captureSections(android.app.Activity a, View root, String name) {
        // Capture the whole laid-out form, including every middle section the earlier sweep missed.
        Bitmap bitmap = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap); canvas.drawColor(a.getColor(R.color.background)); root.draw(canvas);
        String profile = a.getResources().getConfiguration().screenWidthDp + "-" + a.getResources().getConfiguration().fontScale;
        try (FileOutputStream out = new FileOutputStream(new File(a.getFilesDir(), "public-form-" + name + "-" + profile + ".png"))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out));
        } catch (java.io.IOException failure) { throw new AssertionError(failure); } finally { bitmap.recycle(); }
    }
    private static void settle() { InstrumentationRegistry.getInstrumentation().waitForIdleSync(); SystemClock.sleep(600); }
}
