package com.subhub.app.help;

import com.subhub.app.util.PrimaryHeader;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.AccessibilityDelegateCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;

import com.subhub.app.R;
import com.subhub.app.BuildConfig;
import com.subhub.app.databinding.ActivityHelpBinding;
import com.subhub.app.security.ControllerEditMode;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.ControllerPinGate;
import com.subhub.app.diagnostics.DiagnosticsActivity;
import com.subhub.app.util.LocaleHelper;
import com.subhub.app.update.UpdateCandidate;
import com.subhub.app.update.UpdateStateStore;
import com.subhub.app.update.UpdatesActivity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Searchable local help with explicit permission repair and troubleshooting entry points. */
public final class HelpActivity extends AppCompatActivity {
    private ActivityHelpBinding binding;
    private final List<Question> questions = new ArrayList<>();
    private int expandedTitle;
    private final ActivityResultLauncher<Intent> overlaySettings = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> renderPermissions());
    private final ActivityResultLauncher<String> notificationPermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> renderPermissions());

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityHelpBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        PrimaryHeader.bindSecondary(binding.getRoot(), R.string.help_title, true);
        binding.getRoot().setFocusableInTouchMode(true);
        binding.getRoot().requestFocus();
        PrimaryHeader.backButton(binding.getRoot()).setOnClickListener(view -> finish());
        binding.buttonFixPermissions.setOnClickListener(view -> repairNextPermission());
        binding.buttonAccessibility.setOnClickListener(view ->
                startActivity(new Intent(this, PermissionSetupActivity.class)));
        binding.buttonLanguage.setOnClickListener(view -> showLanguageChooser());
        binding.buttonUpdates.setOnClickListener(view ->
                startActivity(new Intent(this, UpdatesActivity.class)));
        binding.helpDiagnostics.setOnClickListener(view ->
                startActivity(new Intent(this, DiagnosticsActivity.class)));
        addSections();
        PrimaryHeader.editLockButton(binding.getRoot()).setOnClickListener(view -> {
            if (ControllerPinManager.isDomModeActive()) {
                ControllerEditMode.enterSubMode(this);
            } else {
                ControllerPinGate.unlock(this, this::renderEditableActions, false);
            }
        });
        binding.helpSearch.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                filterQuestions(text.toString());
            }
            @Override public void afterTextChanged(android.text.Editable text) {}
        });
        if (savedInstanceState != null) {
            expandedTitle = savedInstanceState.getInt("help_expanded", 0);
            binding.helpSearch.setText(savedInstanceState.getString("help_query", ""));
            renderQuestions();
        }
        binding.helpBuild.setText(getString(R.string.help_rework_build,
                BuildConfig.VERSION_NAME, BuildConfig.BUILD_DATE));
        renderEditableActions();
    }

    @Override protected void onResume() {
        super.onResume();
        renderEditableActions();
        renderPermissions();
        renderLanguage();
        renderUpdates();
    }

    private void addSections() {
        int[][] sections = {
                {R.string.help_rework_start_title, R.string.help_rework_start_body},
                {R.string.help_rework_modes_title, R.string.help_rework_modes_body},
                {R.string.help_rework_service_title, R.string.help_rework_service_body},
                {R.string.help_rework_apps_title, R.string.help_rework_apps_body},
                {R.string.help_rework_permissions_title, R.string.help_rework_permissions_body},
                {R.string.help_rework_censor_title, R.string.help_rework_censor_body},
                {R.string.help_rework_limits_title, R.string.help_rework_limits_body},
                {R.string.help_rework_wallet_title, R.string.help_rework_wallet_body},
                {R.string.help_rework_paypal_title, R.string.help_rework_paypal_body},
                {R.string.help_rework_hardcore_title, R.string.help_rework_hardcore_body},
                {R.string.help_rework_rituals_title, R.string.help_rework_rituals_body},
                {R.string.help_rework_gallery_title, R.string.help_rework_gallery_body},
                {R.string.help_rework_packs_title, R.string.help_rework_packs_body},
                {R.string.help_rework_troubleshoot_title, R.string.help_rework_troubleshoot_body},
                {R.string.help_rework_privacy_title, R.string.help_rework_privacy_body}
        };
        for (int[] section : sections) addSection(section[0], section[1]);
    }

    private void addSection(int title, int body) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        card.setPadding(dp(16), 0, dp(16), 0);
        card.setTag("help_question:" + getResources().getResourceEntryName(title));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        if (binding.helpSections.getChildCount() > 0) params.topMargin = dp(8);
        card.setLayoutParams(params);
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        headerRow.setPadding(0, dp(12), 0, dp(12));
        headerRow.setMinimumHeight(dp(56));
        headerRow.setFocusable(true);
        android.util.TypedValue ripple = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        headerRow.setBackgroundResource(ripple.resourceId);
        TextView header = new TextView(this);
        header.setText(title);
        header.setTextColor(getColor(R.color.text_primary));
        com.subhub.app.util.UiIdentity.textSize(header, R.dimen.ui_text_section);
        header.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        ViewCompat.setAccessibilityDelegate(headerRow, new AccessibilityDelegateCompat() {
            @Override public void onInitializeAccessibilityNodeInfo(View host,
                    AccessibilityNodeInfoCompat info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(android.widget.Button.class.getName());
            }
        });
        headerRow.addView(header, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView indicator = new TextView(this);
        indicator.setTextColor(getColor(R.color.accent));
        com.subhub.app.util.UiIdentity.textSize(indicator, R.dimen.ui_icon_text);
        indicator.setGravity(Gravity.CENTER);
        indicator.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        headerRow.addView(indicator, new LinearLayout.LayoutParams(dp(28),
                LinearLayout.LayoutParams.WRAP_CONTENT));
        TextView content = new TextView(this);
        content.setText(body);
        content.setTextColor(getColor(R.color.text_secondary));
        com.subhub.app.util.UiIdentity.textSize(content, R.dimen.ui_text_row_title);
        content.setLineSpacing(dp(4), 1f);
        content.setPadding(0, 0, 0, dp(16));
        content.setVisibility(View.GONE);
        headerRow.setOnClickListener(view -> {
            expandedTitle = expandedTitle == title ? 0 : title;
            renderQuestions();
        });
        card.addView(headerRow);
        card.addView(content);
        binding.helpSections.addView(card);
        questions.add(new Question(title, card, headerRow, indicator, content,
                (getString(title) + " " + getString(body)).toLowerCase(Locale.ROOT)));
        renderQuestions();
    }

    private void renderQuestions() {
        for (Question question : questions) {
            boolean expanded = question.title == expandedTitle;
            question.content.setVisibility(expanded ? View.VISIBLE : View.GONE);
            question.indicator.setText(expanded ? "−" : "+");
            question.header.setContentDescription(getString(question.title));
            ViewCompat.setStateDescription(question.header,
                    getString(expanded ? R.string.help_rework_expanded : R.string.help_rework_collapsed));
        }
    }

    private void filterQuestions(String query) {
        String normalized = query.trim().toLowerCase(Locale.ROOT);
        boolean anyVisible = false;
        for (Question question : questions) {
            boolean matches = normalized.isEmpty() || question.searchText.contains(normalized);
            question.card.setVisibility(matches ? View.VISIBLE : View.GONE);
            anyVisible |= matches;
        }
        binding.helpNoResults.setVisibility(anyVisible ? View.GONE : View.VISIBLE);
    }

    private void renderEditableActions() {
        boolean dom = ControllerPinManager.isDomModeActive();
        ControllerEditMode.renderButton(this, PrimaryHeader.editLockButton(binding.getRoot()));
        binding.buttonFixPermissions.setVisibility(View.VISIBLE);
        binding.buttonLanguage.setVisibility(View.VISIBLE);
        ControllerPinGate.markLocked(binding.buttonFixPermissions);
        ControllerPinGate.markLocked(binding.buttonLanguage);
    }

    private static final class Question {
        final int title;
        final LinearLayout card;
        final LinearLayout header;
        final TextView indicator;
        final TextView content;
        final String searchText;

        Question(int title, LinearLayout card, LinearLayout header, TextView indicator,
                TextView content, String searchText) {
            this.title = title;
            this.card = card;
            this.header = header;
            this.indicator = indicator;
            this.content = content;
            this.searchText = searchText;
        }
    }

    private void repairNextPermission() {
        if (!ControllerPinManager.isDomModeActive()) { ControllerPinGate.notifyLocked(this); return; }
        if (new com.subhub.app.settings.FeatureModuleManager(this).hasRuntimeFeature()
                && !new com.subhub.app.appmode.AppModeManager(this).isAccessibilityEnabled()) {
            startActivity(new Intent(this, PermissionSetupActivity.class));
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            overlaySettings.launch(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS);
            return;
        }
        Toast.makeText(this, R.string.help_rework_access_ready, Toast.LENGTH_SHORT).show();
    }

    private void renderPermissions() {
        if (binding == null) return;
        List<String> missing = new ArrayList<>();
        if (new com.subhub.app.settings.FeatureModuleManager(this).hasRuntimeFeature()
                && !new com.subhub.app.appmode.AppModeManager(this).isAccessibilityEnabled())
            missing.add(getString(R.string.tour_accessibility_title));
        if (!Settings.canDrawOverlays(this)) missing.add(getString(R.string.permission_overlay_name));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            missing.add(getString(R.string.permission_notifications_name));
        }
        binding.permissionStatus.setText(missing.isEmpty()
                ? getString(R.string.help_rework_access_ready)
                : getString(R.string.help_rework_access_missing, String.join(", ", missing)));
        binding.buttonFixPermissions.setEnabled(!missing.isEmpty());
    }

    private void showLanguageChooser() {
        if (!ControllerPinManager.isDomModeActive()) { ControllerPinGate.notifyLocked(this); return; }
        List<String> codes = LocaleHelper.SUPPORTED;
        String[] labels = new String[]{
                getString(R.string.language_system_default), getString(R.string.language_english),
                getString(R.string.language_french), getString(R.string.language_spanish),
                getString(R.string.language_portuguese), getString(R.string.language_german),
                getString(R.string.language_japanese), getString(R.string.language_chinese_simplified),
                getString(R.string.language_chinese_traditional), getString(R.string.language_korean),
                getString(R.string.language_russian)};
        int selected = Math.max(0, codes.indexOf(LocaleHelper.getLanguage(this)));
        com.subhub.app.util.ThemedDialogs.builder(this)
                .setTitle(R.string.settings_language)
                .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                    if (ControllerPinManager.isDomModeActive())
                        LocaleHelper.setLanguage(this, codes.get(which));
                    dialog.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void renderLanguage() {
        String code = LocaleHelper.getLanguage(this);
        int index = LocaleHelper.SUPPORTED.indexOf(code);
        int[] labels = {R.string.language_system_default, R.string.language_english,
                R.string.language_french, R.string.language_spanish, R.string.language_portuguese,
                R.string.language_german, R.string.language_japanese,
                R.string.language_chinese_simplified, R.string.language_chinese_traditional,
                R.string.language_korean, R.string.language_russian};
        binding.languageStatus.setText(getString(R.string.help_rework_language,
                getString(labels[Math.max(0, index)])));
    }

    private void renderUpdates() {
        UpdateCandidate candidate = new UpdateStateStore(this).candidate();
        binding.updateSummary.setVisibility(candidate == null ? View.GONE : View.VISIBLE);
        binding.updateSummary.setText(candidate == null ? ""
                : getString(R.string.update_help_available, candidate.manifest.versionName));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("help_expanded", expandedTitle);
        state.putString("help_query", binding.helpSearch.getText().toString());
        super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() {
        binding = null;
        super.onDestroy();
    }
}
