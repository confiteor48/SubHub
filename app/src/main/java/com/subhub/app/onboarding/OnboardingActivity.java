package com.subhub.app.onboarding;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.subhub.app.MainActivity;
import com.subhub.app.R;
import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.security.*;
import com.subhub.app.settings.*;
import com.subhub.app.util.*;

/**
 * Guided setup with resumable drafts; replay previews never overwrite saved feature/style choices.
 */
public final class OnboardingActivity extends PreferencePage {
    public static final String REPLAY = "replay";
    private static final int STEPS = 6;
    private int step;
    private boolean replay, censor, limits, wallet, appearanceChanged;
    private CensorAppearance.Type style;
    private LinearLayout body;
    private boolean resumeRefresh;
    private SetupWalletRulesView walletRules;
    private Bundle walletDraft;
    private final ActivityResultLauncher<String> notifications =
            registerForActivityResult(
                    new ActivityResultContracts.RequestPermission(), ignored -> render());

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        replay = getIntent().getBooleanExtra(REPLAY, false);
        FeatureModuleManager modules = new FeatureModuleManager(this);
        censor = modules.isCensorEnabled();
        limits = true;
        wallet = true;
        style = new SettingsRepository(this).loadAppearance().getType();
        if (!replay && state == null && OnboardingState.inProgress(this)) {
            android.content.SharedPreferences draft = getSharedPreferences("subhub_onboarding", 0);
            step = draft.getInt("draft_step", 0);
            if (draft.getInt("draft_flow_version", 1) < 2) {
                // Resume an older wizard on the equivalent page in the new six-step flow.
                int[] migratedSteps = {0, 3, 1, 4, 5, 5};
                step = migratedSteps[Math.max(0, Math.min(5, step))];
            }
            step = Math.max(0, Math.min(STEPS - 1, step));
            walletDraft = SetupWalletRulesView.readDraft(draft);
            censor = draft.getBoolean("draft_censor", censor);


            style =
                    CensorAppearance.Type.fromPreference(
                            draft.getString("draft_style", style.getPreferenceValue()));
            appearanceChanged = draft.getBoolean("draft_appearance_changed", false);
        }
        if (!replay) OnboardingState.begin(this);
        if (state != null) {
            step = Math.max(0, Math.min(STEPS - 1, state.getInt("step")));
            censor = state.getBoolean("censor");


            style = CensorAppearance.Type.fromPreference(state.getString("style"));
            appearanceChanged = state.getBoolean("appearance_changed");
            walletDraft = state.getBundle("wallet_rules");
        }
        getOnBackPressedDispatcher()
                .addCallback(
                        this,
                        new androidx.activity.OnBackPressedCallback(true) {
                            @Override
                            public void handleOnBackPressed() {
                                back();
                            }
                        });
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (resumeRefresh && body != null && step >= 4) render();
        if (appSelection != null) appSelection.reload();
        resumeRefresh = false;
    }

    @Override
    protected void onPause() {
        saveDraft();
        resumeRefresh = true;
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        captureWalletDraft();
        state.putInt("step", step);
        state.putBoolean("censor", censor);
        state.putBoolean("limits", limits);
        state.putBoolean("wallet", wallet);
        state.putString("style", style.getPreferenceValue());
        state.putBoolean("appearance_changed", appearanceChanged);
        state.putBundle("wallet_rules", walletDraft);
        super.onSaveInstanceState(state);
    }

    private void back() {
        if (step > 0) {
            step--;
            render();
        } else finishSetup();
    }

    private void render() {
        if (isFinishing() || isDestroyed()) return;
        saveDraft();
        walletRules = null;
        appSelection = null;
        setContentView(R.layout.activity_onboarding);
        page = findViewById(R.id.tour_content);
        body = page;
        PrimaryHeader.bindSecondary(findViewById(android.R.id.content), R.string.tour_title, false);
        PrimaryHeader.backButton(findViewById(android.R.id.content))
                .setOnClickListener(v -> back());
        ((TextView) findViewById(R.id.tour_progress))
                .setText(getString(R.string.tour_progress, step + 1, STEPS));
        LinearLayout progress = findViewById(R.id.tour_steps);
        for (int i = 0; i < STEPS; i++) {
            View segment = new View(this);
            segment.setBackgroundResource(
                    i <= step ? R.drawable.bg_primary_button : R.drawable.bg_outline_button);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(5), 1);
            p.setMargins(dp(3), 0, dp(3), 0);
            progress.addView(segment, p);
        }
        int[] titles = {
            R.string.tour_welcome,
            R.string.tour_choose_style,
            R.string.tour_wallet_rules,
            R.string.tour_select_apps,
            R.string.keyholder_home_title,
            R.string.tour_permissions
        };
        TextView title = text(body, getString(titles[step]), 26, false);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setId(R.id.tour_step_title);
        if (step == 0) welcome();
        else if (step == 1) appearance();
        else if (step == 2) walletRules();
        else if (step == 3) features();
        else if (step == 4) keyholder();
        else permissions();
        Button next = findViewById(R.id.tour_next);
        next.setBackgroundResource(R.drawable.bg_primary_button);
        next.setTextColor(getColor(R.color.text_primary));
        next.setText(
                step == 0
                        ? R.string.tour_get_started
                        : step == STEPS - 1 ? R.string.tour_finish : R.string.tour_next);
        next.setOnClickListener(
                v -> {
                    if (walletRules != null && !walletRules.applyRules()) return;
                    captureWalletDraft();
                    if (step == STEPS - 1) finishSetup();
                    else {
                        step++;
                        render();
                    }
                });
        findViewById(R.id.tour_skip).setOnClickListener(v -> finishSetup());
    }

    private void welcome() {
        text(body, getString(R.string.tour_intro), 16, true);
        SetupFeaturePreviewView features = new SetupFeaturePreviewView(this);
        features.setTag("setup-feature-overview");
        LinearLayout.LayoutParams featureParams = new LinearLayout.LayoutParams(-1, -2);
        featureParams.bottomMargin = getResources().getDimensionPixelSize(R.dimen.ui_gap_group);
        body.addView(features, featureParams);
        explain(body, R.drawable.ic_tab_settings, R.string.tour_dom_title, R.string.tour_dom_help);
        explain(body, R.drawable.ic_tab_home, R.string.tour_sub_title, R.string.tour_sub_help);
    }

    private com.subhub.app.settings.AppSelectionPanel appSelection;

    private void features() {
        LinearLayout apps = card(body);
        apps.setTag("setup-app-card");
        apps.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1));
        appSelection = new com.subhub.app.settings.AppSelectionPanel(this);
        apps.addView(appSelection, new LinearLayout.LayoutParams(-1, -1));
        appSelection.fillAvailableHeight();
        appSelection.bind(this,
                () -> ControllerPinManager.isDomModeActive()
                        || !replay && !ControllerPinManager.hasCredentials(this),
                this::saveDraft);
        appSelection.load();
    }

    private void walletRules() {
        walletRules = new SetupWalletRulesView(this, walletDraft,
                () -> ControllerPinManager.isDomModeActive()
                        || !replay && !ControllerPinManager.hasCredentials(this));
        body.addView(walletRules, new LinearLayout.LayoutParams(-1, -2));
        ScrollView scroll = findViewById(R.id.tour_scroll);
        scroll.post(() -> {
            if (walletRules != null && walletRules.getParent() == body) {
                walletRules.fitGraphic(scroll.getHeight() - body.getHeight());
            }
        });
    }

    private void appearance() {
        preview(body, style, dp(200));
        CensorAppearance.Type[] types = {
            CensorAppearance.Type.BOX,
            CensorAppearance.Type.PIXELATE,
            CensorAppearance.Type.BLUR,
            CensorAppearance.Type.STATIC
        };
        int[] labels = {
            R.string.style_box, R.string.style_pixelate, R.string.style_blur, R.string.style_static
        };
        SelectionGrid choices = new SelectionGrid(this, null);
        body.addView(choices, new LinearLayout.LayoutParams(-1, -2));
        for (int i = 0; i < types.length; i++) {
            final CensorAppearance.Type chosen = types[i];
            LinearLayout item = card(choices);
            item.setPadding(dp(10), dp(10), dp(10), dp(10));
            LinearLayout.LayoutParams itemParams =
                    (LinearLayout.LayoutParams) item.getLayoutParams();
            itemParams.setMargins(dp(4), dp(4), dp(4), dp(8));
            item.setLayoutParams(itemParams);
            item.setBackgroundResource(R.drawable.bg_choice_card);
            item.setSelected(style == chosen);
            preview(item, chosen, dp(86));
            Button pick =
                    button(
                            item,
                            getString(labels[i]),
                            () -> {
                                style = chosen;
                                appearanceChanged = true;
                                render();
                            });
            pick.setSelected(style == chosen);
            pick.setTag("setup-style:" + chosen.getPreferenceValue());
            pick.setTextColor(
                    getColor(style == chosen ? R.color.text_primary : R.color.accent_hot));
            pick.setBackgroundResource(
                    style == chosen ? R.drawable.bg_primary_button : R.drawable.bg_outline_button);
        }
    }

    private void keyholder() {
        text(body, getString(R.string.tour_keyholder_explanation), 15, true);
        LinearLayout pin =
                explain(
                        body,
                        R.drawable.ic_ux_lock,
                        R.string.keyholder_pin_heading,
                        R.string.tour_pin_help);
        text(
                pin,
                getString(
                        ControllerPinManager.isConfigured(this)
                                ? R.string.keyholder_status_pin
                                : R.string.keyholder_status_none),
                12,
                true);
        button(
                pin,
                getString(
                        ControllerPinManager.isConfigured(this)
                                ? R.string.keyholder_pin_change
                                : R.string.controller_pin_set),
                () -> {
                    if (!ControllerPinManager.hasCredentials(this))
                        ControllerPinManager.useWithoutKeyholder(this);
                    ControllerPinGate.changePin(this, this::render);
                });
        LinearLayout remote =
                explain(
                        body,
                        R.drawable.ic_keyholder,
                        R.string.keyholder_remote_title,
                        R.string.tour_remote_help);
        boolean paired = new ControllerAuthenticator(this).isPaired();
        text(
                remote,
                getString(paired ? R.string.authenticator_paired : R.string.authenticator_unpaired),
                12,
                true);
        button(
                remote,
                getString(paired ? R.string.keyholder_open : R.string.authenticator_pair),
                () -> {
                    if (!ControllerPinManager.hasCredentials(this))
                        ControllerPinManager.useWithoutKeyholder(this);
                    startActivity(
                            new Intent(this, AuthenticatorActivity.class)
                                    .putExtra("keyholder_method", "remote"));
                });
    }

    private void permissions() {
        boolean runtime = censor || limits;
        if (runtime)
            permission(
                    R.string.tour_accessibility_title,
                    R.string.tour_accessibility_help,
                    new AppModeManager(this).isAccessibilityEnabled(),
                    () -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        if (censor)
            permission(
                    R.string.tour_overlay_title,
                    R.string.tour_overlay_help,
                    Settings.canDrawOverlays(this),
                    () ->
                            startActivity(
                                    new Intent(
                                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            Uri.parse("package:" + getPackageName()))));
        if (Build.VERSION.SDK_INT >= 33 && (runtime || wallet))
            permission(
                    R.string.tour_notifications_title,
                    R.string.tour_notifications_help,
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                            == PackageManager.PERMISSION_GRANTED,
                    () -> notifications.launch(Manifest.permission.POST_NOTIFICATIONS));
        if (runtime)
            button(body, getString(R.string.permission_setup_open), () -> startActivity(
                    new Intent(this, com.subhub.app.help.PermissionSetupActivity.class)));
        if (!runtime && !wallet) text(body, getString(R.string.tour_no_permissions), 15, true);
    }

    private void permission(int name, int reason, boolean ready, Runnable open) {
        LinearLayout c = card(body);
        text(c, getString(name), 17, false).setTypeface(null, android.graphics.Typeface.BOLD);
        text(c, getString(reason), 14, true);
        button(c, getString(ready ? R.string.tour_allowed : R.string.tour_allow), open)
                .setEnabled(!ready);
    }

    private LinearLayout explain(LinearLayout parent, int resource, int title, int explanation) {
        LinearLayout c = card(parent);
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        c.addView(header, new LinearLayout.LayoutParams(-1, -2));
        icon(header, resource);
        TextView t = text(header, getString(title), 17, false);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        text(c, getString(explanation), 14, true);
        return c;
    }

    private void icon(LinearLayout parent, int resource) {
        ImageView image = new ImageView(this);
        image.setImageResource(resource);
        image.setBackgroundResource(R.drawable.bg_header_icon);
        image.setPadding(dp(8), dp(8), dp(8), dp(8));
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(36), dp(36));
        p.setMarginEnd(dp(10));
        parent.addView(image, p);
    }

    private void preview(LinearLayout parent, CensorAppearance.Type type, int height) {
        CensorPreviewView preview = new CensorPreviewView(this, null);
        // Keep the woman in the main preview, and use close-up effects in the
        // small choices so blur/pixelation remain legible at thumbnail size.
        preview.setPhonePreview(parent == body);
        preview.setTag(type.getPreferenceValue());
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, height);
        p.bottomMargin = dp(14);
        parent.addView(preview, p);
    }

    private void saveDraft() {
        captureWalletDraft();
        if (!replay && OnboardingState.inProgress(this))
            getSharedPreferences("subhub_onboarding", 0)
                    .edit()
                    .putInt("draft_step", step)
                    .putInt("draft_flow_version", 2)
                    .putString("draft_wallet_rules", SetupWalletRulesView.encodeDraft(walletDraft))
                    .putBoolean("draft_censor", censor)
                    .putBoolean("draft_limits", limits)
                    .putBoolean("draft_wallet", wallet)
                    .putString("draft_style", style.getPreferenceValue())
                    .putBoolean("draft_appearance_changed", appearanceChanged)
                    .apply();
    }

    private void captureWalletDraft() {
        if (walletRules != null) walletDraft = walletRules.snapshot();
    }

    private void finishSetup() {
        if (!replay) {

            if (appearanceChanged) {
                SettingsRepository settings = new SettingsRepository(this);
                CensorAppearance old = settings.loadAppearance();
                settings.saveAppearance(
                        style, old.getIntensity(), old.isShowBorder(), old.isShowText());
            }
            if (!ControllerPinManager.hasCredentials(this))
                ControllerPinManager.useWithoutKeyholder(this);
            OnboardingState.complete(this);
            ControllerPinManager.enterSubMode();
        }
        startActivity(
                new Intent(this, MainActivity.class)
                        .setAction(Intent.ACTION_MAIN)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
        finish();
    }
}
