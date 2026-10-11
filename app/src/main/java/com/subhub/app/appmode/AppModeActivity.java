package com.subhub.app.appmode;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.subhub.app.R;
import com.subhub.app.databinding.ActivityAppModeBinding;
import com.subhub.app.security.ControllerEditMode;
import com.subhub.app.security.ControllerPinGate;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.util.AsyncUiScope;
import com.subhub.app.util.PrimaryHeader;
import com.subhub.app.util.SubHubNavigation;

import java.text.Collator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Daily recorded usage and independent app allowances for the shared included-app scope. */
public final class AppModeActivity extends AppCompatActivity {
    private ActivityAppModeBinding binding;
    private AppModeManager manager;
    private AppTimerManager timers;
    private boolean editingUnlocked;
    private boolean populatingTimers, dirtyTimers;
    private AsyncUiScope appData;
    private final Map<String, AppLimitRow> appRows = new LinkedHashMap<>();
    private Set<String> displayedPackages = java.util.Collections.emptySet(),
            loadingPackages,
            failedPackages;
    private Bundle restoredDrafts;
    private final Handler usageHandler = new Handler(Looper.getMainLooper());
    private final Runnable usageTick =
            new Runnable() {
                @Override
                public void run() {
                    if (binding == null || manager == null) return;
                    if (editingUnlocked != editingAllowed()) applyEditState();
                    renderPerAppAllowances();
                    renderTimerUsage();
                    usageHandler.postDelayed(this, 1000L);
                }
            };
    private final Map<String, EditText> allowanceInputs = new LinkedHashMap<>();
    private final Handler autoSaveHandler = new Handler(Looper.getMainLooper());
    private final Runnable persistTimers = () -> save(false);
    private final TextWatcher autoSaveWatcher =
            new TextWatcher() {
                @Override
                public void beforeTextChanged(
                        CharSequence value, int start, int count, int after) {}

                @Override
                public void onTextChanged(CharSequence value, int start, int before, int count) {}

                @Override
                public void afterTextChanged(Editable value) {
                    scheduleAutoSave();
                    renderTimerUsage();
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityAppModeBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        PrimaryHeader.bind(binding.getRoot(), R.drawable.ic_nav_limits, R.string.app_mode_title, 0);
        manager = new AppModeManager(this);
        timers = new AppTimerManager(this);
        appData = AsyncUiScope.forPage(this);
        if (savedInstanceState != null) {
            restoredDrafts = savedInstanceState.getBundle("allowance_drafts");
            dirtyTimers = savedInstanceState.getBoolean("timer_drafts_dirty", false);
        }
        binding.limitsManageApps.setOnClickListener(
                view ->
                        ControllerPinGate.require(
                                this,
                                () ->
                                        startActivity(
                                                new Intent(
                                                                this,
                                                                com.subhub.app.settings
                                                                        .GlobalSettingsActivity
                                                                        .class)
                                                        .putExtra(
                                                                com.subhub.app.settings
                                                                        .GlobalSettingsActivity
                                                                        .EXTRA_SHOW_INCLUDED_APPS,
                                                                true)),
                                false));
        SubHubNavigation.bind(this, binding.getRoot(), SubHubNavigation.Screen.LIMITS);
        AppTimerManager.Settings timerSettings = timers.loadSettings();
        binding.perAppLimitEnabled.setChecked(timerSettings.perAppEnabled);
        binding.totalLimitEnabled.setChecked(timerSettings.totalEnabled);
        binding.totalLimitMinutes.setText(String.valueOf(timerSettings.totalMinutes));
        binding.perAppLimitEnabled.setOnCheckedChangeListener(
                (button, checked) -> {
                    if (populatingTimers) return;
                    if (!editingAllowed() || checked && rejectUnassignedLimit()) {
                        restoreTimerValues();
                        return;
                    }
                    if (!checked) timers.disableBudget(true);
                    renderTimerControls();
                    scheduleAutoSave();
                    if (checked) promptForAccessibility();
                });
        binding.totalLimitEnabled.setOnCheckedChangeListener(
                (button, checked) -> {
                    if (populatingTimers) return;
                    if (!editingAllowed() || checked && rejectUnassignedLimit()) {
                        restoreTimerValues();
                        return;
                    }
                    if (!checked) timers.disableBudget(false);
                    renderTimerControls();
                    scheduleAutoSave();
                    if (checked) promptForAccessibility();
                });
        binding.totalLimitMinutes.addTextChangedListener(autoSaveWatcher);
        binding.totalLimitMinutes.setOnFocusChangeListener(
                (view, focused) -> {
                    if (!focused) commitTimers(true);
                });
        PrimaryHeader.backButton(binding.getRoot()).setOnClickListener(view -> finish());
        PrimaryHeader.editLockButton(binding.getRoot())
                .setOnClickListener(view -> toggleEditSession());
        renderPerAppAllowances();
        renderTimerControls();
        renderTimerUsage();
        applyEditState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (manager == null) return;
        failedPackages = null;
        if (!dirtyTimers || !editingAllowed()) restoreTimerValues();
        renderPerAppAllowances();
        applyEditState();
        usageHandler.removeCallbacks(usageTick);
        usageHandler.post(usageTick);
    }

    @Override
    protected void onPause() {
        autoSaveHandler.removeCallbacks(persistTimers);
        if (editingAllowed()) save(false);
        usageHandler.removeCallbacks(usageTick);
        super.onPause();
    }

    private void toggleEditSession() {
        if (ControllerPinManager.isSessionUnlocked()) {
            ControllerEditMode.enterSubMode(this);
        } else ControllerPinGate.unlock(this, this::applyEditState, false);
    }

    private void applyEditState() {
        if (binding == null) return;
        boolean wasEditing = editingUnlocked;
        editingUnlocked = editingAllowed();
        if (wasEditing && !editingUnlocked) {
            autoSaveHandler.removeCallbacks(persistTimers);
            restoreTimerValues();
        }
        ControllerEditMode.renderButton(this, PrimaryHeader.editLockButton(binding.getRoot()));
        View[] editable = {binding.perAppLimitEnabled, binding.totalLimitEnabled};
        for (View view : editable) view.setEnabled(editingUnlocked);
        renderTimerControls();
        SubHubNavigation.bind(this, binding.getRoot(), SubHubNavigation.Screen.LIMITS);
    }

    private boolean editingAllowed() {
        return ControllerPinManager.isDomModeActive();
    }

    private boolean rejectUnassignedLimit() {
        if (populatingTimers || !editingAllowed() || !manager.getIncludedPackages().isEmpty())
            return false;
        restoreTimerValues();
        return true;
    }

    private boolean save(boolean showInvalid) {
        if (binding == null || timers == null || manager == null || !editingAllowed()) return false;
        if (!dirtyTimers) return true;
        Set<String> included = manager.getIncludedPackages();
        if (binding.perAppLimitEnabled.isChecked() && !included.equals(displayedPackages))
            return false;
        boolean watchedAppsRequired =
                binding.perAppLimitEnabled.isChecked() || binding.totalLimitEnabled.isChecked();
        if (watchedAppsRequired && manager.getIncludedPackages().isEmpty()) {
            if (showInvalid)
                Toast.makeText(this, R.string.app_mode_select_one, Toast.LENGTH_SHORT).show();
            return false;
        }
        Integer totalMinutes =
                readMinutes(binding.totalLimitMinutes, binding.totalLimitEnabled.isChecked());
        AppTimerManager.Settings existing = timers.loadSettings();
        int defaultMinutes = existing.perAppMinutes;
        if (!binding.totalLimitEnabled.isChecked()) totalMinutes = existing.totalMinutes;
        Map<String, Integer> allowances = new LinkedHashMap<>();
        if (binding.perAppLimitEnabled.isChecked()) {
            for (Map.Entry<String, EditText> entry : allowanceInputs.entrySet()) {
                Integer minutes = readMinutes(entry.getValue(), true);
                if (minutes == null) {
                    if (showInvalid)
                        Toast.makeText(this, R.string.app_timer_invalid_minutes, Toast.LENGTH_SHORT)
                                .show();
                    return false;
                }
                allowances.put(entry.getKey(), minutes);
            }
        }
        if (totalMinutes == null) {
            if (showInvalid)
                Toast.makeText(this, R.string.app_timer_invalid_minutes, Toast.LENGTH_SHORT).show();
            return false;
        }
        timers.saveSettings(
                binding.perAppLimitEnabled.isChecked(),
                defaultMinutes,
                binding.totalLimitEnabled.isChecked(),
                totalMinutes);
        if (binding.perAppLimitEnabled.isChecked()) {
            timers.updateAllowances(allowances);
        }
        dirtyTimers = false;
        return true;
    }

    private void scheduleAutoSave() {
        if (populatingTimers || !editingAllowed()) return;
        dirtyTimers = true;
        autoSaveHandler.removeCallbacks(persistTimers);
        autoSaveHandler.postDelayed(persistTimers, 450L);
    }

    private void commitTimers(boolean restoreIfInvalid) {
        if (populatingTimers || !editingAllowed()) return;
        autoSaveHandler.removeCallbacks(persistTimers);
        if (!save(restoreIfInvalid) && restoreIfInvalid) restoreTimerValues();
    }

    private void restoreTimerValues() {
        populatingTimers = true;
        AppTimerManager.Settings saved = timers.loadSettings();
        binding.perAppLimitEnabled.setChecked(saved.perAppEnabled);
        binding.totalLimitEnabled.setChecked(saved.totalEnabled);
        binding.totalLimitMinutes.setText(String.valueOf(saved.totalMinutes));
        Map<String, Integer> allowances = timers.loadAllowances(manager.getIncludedPackages());
        for (Map.Entry<String, EditText> entry : allowanceInputs.entrySet()) {
            Integer minutes = allowances.get(entry.getKey());
            if (minutes != null) entry.getValue().setText(String.valueOf(minutes));
        }
        renderPerAppAllowances();
        populatingTimers = false;
        dirtyTimers = false;
        restoredDrafts = null;
        renderTimerControls();
    }

    private void promptForAccessibility() {
        if (populatingTimers || !editingAllowed() || accessibilityEnabled()) return;
        Toast.makeText(this, R.string.app_mode_enable_prompt, Toast.LENGTH_LONG).show();
        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
    }

    private void renderTimerControls() {
        boolean perAppEnabled = binding.perAppLimitEnabled.isChecked();
        for (EditText input : allowanceInputs.values()) {
            input.setEnabled(editingUnlocked && perAppEnabled);
        }
        binding.totalLimitMinutes.setEnabled(
                editingUnlocked && binding.totalLimitEnabled.isChecked());
        renderTimerUsage();
    }

    private void renderPerAppAllowances() {
        if (binding == null || manager == null || timers == null) return;
        Set<String> requested =
                java.util.Collections.unmodifiableSet(
                        new java.util.LinkedHashSet<>(manager.getIncludedPackages()));
        if (requested.equals(loadingPackages)
                || requested.equals(failedPackages)
                || requested.equals(displayedPackages) && !appRows.isEmpty()) return;
        if (requested.isEmpty()) {
            if (!appRows.isEmpty() || binding.perAppAllowancesList.getChildCount() == 0) {
                boolean previous = populatingTimers;
                populatingTimers = true;
                binding.perAppAllowancesList.removeAllViews();
                appRows.clear();
                allowanceInputs.clear();
                populatingTimers = previous;
                message(R.string.limits_no_apps);
            }
            displayedPackages = requested;
            loadingPackages = null;
            appData.cancel("limits-apps");
            return;
        }
        loadingPackages = requested;
        if (appRows.isEmpty()) {
            binding.perAppAllowancesList.removeAllViews();
            message(R.string.limits_loading_apps);
        }
        android.content.Context app = getApplicationContext();
        appData.load(
                "limits-apps",
                () -> loadApps(app, requested),
                models -> {
                    loadingPackages = null;
                    if (binding == null || !manager.getIncludedPackages().equals(requested)) return;
                    Map<String, String> drafts = new LinkedHashMap<>();
                    for (Map.Entry<String, EditText> entry : allowanceInputs.entrySet())
                        drafts.put(entry.getKey(), entry.getValue().getText().toString());
                    boolean previous = populatingTimers;
                    populatingTimers = true;
                    binding.perAppAllowancesList.removeAllViews();
                    appRows.clear();
                    allowanceInputs.clear();
                    for (AppInfo model : models) {
                        if (!appRows.isEmpty()) {
                            View line = new View(this);
                            line.setBackgroundResource(R.color.outline_subtle);
                            binding.perAppAllowancesList.addView(
                                    line, new LinearLayout.LayoutParams(-1, dp(1)));
                        }
                        String value =
                                drafts.getOrDefault(
                                        model.packageName, String.valueOf(model.allowance));
                        if (restoredDrafts != null && restoredDrafts.containsKey(model.packageName))
                            value = restoredDrafts.getString(model.packageName);
                        AppLimitRow row =
                                new AppLimitRow(
                                        this, model.packageName, model.name, model.icon, value);
                        EditText input = row.allowance;
                        input.addTextChangedListener(autoSaveWatcher);
                        input.setOnFocusChangeListener(
                                (view, focused) -> {
                                    if (!focused) commitTimers(true);
                                });
                        allowanceInputs.put(model.packageName, input);
                        appRows.put(model.packageName, row);
                        binding.perAppAllowancesList.addView(
                                row, new LinearLayout.LayoutParams(-1, -2));
                    }
                    restoredDrafts = null;
                    displayedPackages = requested;
                    populatingTimers = previous;
                    renderTimerControls();
                    renderTimerUsage();
                    if (dirtyTimers) scheduleAutoSave();
                },
                failure -> {
                    loadingPackages = null;
                    failedPackages = requested;
                    if (binding != null && appRows.isEmpty()) {
                        binding.perAppAllowancesList.removeAllViews();
                        message(R.string.limits_apps_unavailable);
                    }
                });
    }

    private void message(int label) {
        TextView empty = new TextView(this);
        empty.setText(label);
        com.subhub.app.util.UiIdentity.textSize(empty, R.dimen.ui_text_body);
        empty.setTextColor(getColor(R.color.text_secondary));
        empty.setPadding(0, dp(8), 0, dp(8));
        binding.perAppAllowancesList.addView(empty, new LinearLayout.LayoutParams(-1, -2));
    }

    private static List<AppInfo> loadApps(android.content.Context app, Set<String> included)
            throws InterruptedException {
        PackageManager packages = app.getPackageManager();
        Map<String, Integer> allowances = new AppTimerManager(app).loadAllowances(included);
        List<AppInfo> models = new ArrayList<>();
        for (String name : included) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            String label = name;
            android.graphics.drawable.Drawable icon = packages.getDefaultActivityIcon();
            try {
                ApplicationInfo info = packages.getApplicationInfo(name, 0);
                CharSequence display = packages.getApplicationLabel(info);
                if (display != null) label = display.toString();
                icon = packages.getApplicationIcon(info);
            } catch (PackageManager.NameNotFoundException | RuntimeException removed) {
            }
            models.add(new AppInfo(name, label, icon, allowances.get(name)));
        }
        Collator collator = Collator.getInstance(Locale.getDefault());
        models.sort((left, right) -> collator.compare(left.name, right.name));
        return models;
    }

    private static final class AppInfo {
        final String packageName, name;
        final android.graphics.drawable.Drawable icon;
        final int allowance;

        AppInfo(
                String packageName,
                String name,
                android.graphics.drawable.Drawable icon,
                int allowance) {
            this.packageName = packageName;
            this.name = name;
            this.icon = icon;
            this.allowance = allowance;
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void renderTimerUsage() {
        if (binding == null || timers == null || manager == null) return;
        long now = System.currentTimeMillis();
        Set<String> included = manager.getIncludedPackages();
        Map<String, AppTimerManager.UsageSnapshot> usage = timers.snapshots(included, now);
        long totalUsed = usage.get("").totalUsedMillis;
        AppTimerManager.Settings saved = timers.loadSettings();
        Integer totalDraft = readMinutes(binding.totalLimitMinutes, true);
        int totalMinutes = totalDraft == null ? saved.totalMinutes : totalDraft;
        boolean combined = binding.totalLimitEnabled.isChecked(),
                individual = binding.perAppLimitEnabled.isChecked();
        long totalBudget = AppTimerManager.minutesToMillis(totalMinutes);
        long sharedRemaining = Math.max(0L, totalBudget - totalUsed);
        binding.limitsTodayAmount.setText(formatUsage(totalUsed));
        binding.limitsTodayScope.setText(getString(R.string.limits_today_scope, included.size()));
        binding.timerUsageStatus.setText(
                !combined && !individual
                        ? R.string.limits_off
                        : included.isEmpty()
                                ? R.string.limits_no_apps
                                : !manager.isEffectivelyArmed(now)
                                        ? R.string.limits_protection_off
                                        : !accessibilityEnabled()
                                                ? R.string.limits_access_needed
                                                : !com.subhub.app.service
                                                                .ScreenshotAccessibilityService
                                                                .isRunning()
                                                        ? R.string.limits_waiting_service
                                                        : R.string.limits_tracking);
        binding.limitsCombinedUsage.setText(
                combined
                        ? sharedRemaining == 0L
                                ? getString(R.string.limits_shared_reached)
                                : getString(
                                        R.string.limits_usage_remaining,
                                        formatUsage(totalUsed),
                                        formatUsage(sharedRemaining))
                        : getString(R.string.limits_usage_only, formatUsage(totalUsed)));
        binding.limitsCombinedProgress.setVisibility(combined ? View.VISIBLE : View.GONE);
        binding.limitsCombinedProgress.setProgress(LimitsUsage.percent(totalUsed, totalBudget));
        for (Map.Entry<String, AppLimitRow> entry : appRows.entrySet()) {
            AppTimerManager.UsageSnapshot current = usage.get(entry.getKey());
            if (current == null) continue;
            Integer draft = readMinutes(entry.getValue().allowance, true);
            int minutes = draft == null ? timers.allowanceMinutes(entry.getKey()) : draft;
            long budget = AppTimerManager.minutesToMillis(minutes);
            LimitsUsage state =
                    LimitsUsage.forApp(
                            current.appUsedMillis,
                            budget,
                            totalUsed,
                            totalBudget,
                            individual,
                            combined);
            long remaining = state.remainingMillis;
            boolean limited = state.limited;
            String description =
                    limited
                            ? remaining == 0L
                                    ? getString(R.string.limits_reached)
                                    : getString(
                                            R.string.limits_usage_remaining,
                                            formatUsage(current.appUsedMillis),
                                            formatUsage(remaining))
                            : getString(
                                    R.string.limits_usage_only, formatUsage(current.appUsedMillis));
            entry.getValue().renderUsage(description, state.progressPercent, limited);
        }
    }

    private Integer readMinutes(EditText input, boolean required) {
        String value = input.getText() == null ? "" : input.getText().toString().trim();
        if (!required) {
            try {
                return AppTimerManager.sanitizeMinutes(Integer.parseInt(value));
            } catch (NumberFormatException ignored) {
                return 1;
            }
        }
        try {
            int minutes = Integer.parseInt(value);
            return minutes >= 1 && minutes <= 1440 ? minutes : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String formatUsage(long millis) {
        if (millis > 0L && millis < 60_000L) return getString(R.string.limits_less_than_minute);
        long totalMinutes = Math.max(0L, millis) / 60_000L;
        long hours = totalMinutes / 60L;
        long minutes = totalMinutes % 60L;
        return hours > 0L ? hours + "h " + minutes + "m" : minutes + "m";
    }

    private boolean accessibilityEnabled() {
        return manager.isAccessibilityEnabled();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        Bundle drafts = restoredDrafts == null ? new Bundle() : new Bundle(restoredDrafts);
        for (Map.Entry<String, EditText> entry : allowanceInputs.entrySet())
            drafts.putString(entry.getKey(), entry.getValue().getText().toString());
        state.putBundle("allowance_drafts", drafts);
        state.putBoolean("timer_drafts_dirty", dirtyTimers);
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onDestroy() {
        usageHandler.removeCallbacksAndMessages(null);
        autoSaveHandler.removeCallbacksAndMessages(null);
        binding = null;
        super.onDestroy();
    }
}
