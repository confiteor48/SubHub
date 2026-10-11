package com.subhub.app.appmode;

import android.accessibilityservice.AccessibilityServiceInfo;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;

import com.subhub.app.settings.CaptureMethod;
import com.subhub.app.settings.FeatureModuleManager;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.stats.StatsRepository;
import com.subhub.app.diagnostics.CensorLabCensorSession;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** One included-app scope shared by every enabled runtime feature. */
public final class AppModeManager {
    public static final String KEY_ARMED = "app_mode_armed";
    public static final String KEY_INCLUDED_PACKAGES = "app_included_packages_v1";
    public static final String KEY_ALL_APPS = "app_include_all_v1";
    private static final String KEY_ALL_APPS_PACKAGES = "app_include_all_packages_v1";
    private static final String KEY_SCOPE_REVISION = "app_include_revision_v1";

    private final Context context;
    private final SharedPreferences preferences;

    public AppModeManager(Context context) {
        this.context = context.getApplicationContext();
        preferences = this.context.getSharedPreferences(
                SettingsRepository.PREFERENCES_NAME, Context.MODE_PRIVATE);
        migrateAppSelection();
    }

    public boolean isArmed() {
        return preferences.getBoolean(KEY_ARMED, false);
    }

    public void setArmed(boolean armed) {
        boolean wasArmed = isArmed();
        preferences.edit().putBoolean(KEY_ARMED, armed).commit();
        syncProtectionSession(wasArmed, armed);
    }

    /** Effective runtime scope; All Apps never changes the saved checkbox selection. */
    public Set<String> getIncludedPackages() {
        migrateAppSelection();
        if (isAllApps()) return AppModePolicy.sanitizePackages(preferences.getStringSet(
                KEY_ALL_APPS_PACKAGES, getSelectedPackages()));
        return getSelectedPackages();
    }

    public Set<String> getSelectedPackages() {
        migrateAppSelection();
        return AppModePolicy.sanitizePackages(preferences.getStringSet(
                KEY_INCLUDED_PACKAGES, Collections.emptySet()));
    }

    /** Edit the retained selection without disabling a global override or changing allowances. */
    public void saveIncludedPackages(Set<String> packages) {
        synchronized (AppModeManager.class) {
            preferences.edit().putStringSet(KEY_INCLUDED_PACKAGES,
                    new LinkedHashSet<>(AppModePolicy.sanitizePackages(packages))).apply();
        }
    }

    public boolean isAllApps() {
        return preferences.getBoolean(KEY_ALL_APPS, false);
    }

    public long scopeRevision() {
        return preferences.getLong(KEY_SCOPE_REVISION, 0);
    }

    public void saveAllApps(Set<String> installedPackages) {
        synchronized (AppModeManager.class) {
            preferences.edit().putStringSet(KEY_ALL_APPS_PACKAGES,
                    new LinkedHashSet<>(AppModePolicy.sanitizePackages(installedPackages)))
                    .putBoolean(KEY_ALL_APPS, true)
                    .putLong(KEY_SCOPE_REVISION, scopeRevision() + 1).apply();
        }
    }

    public void setAllAppsEnabled(boolean enabled) {
        synchronized (AppModeManager.class) {
            preferences.edit().putBoolean(KEY_ALL_APPS, enabled)
                    .putLong(KEY_SCOPE_REVISION, scopeRevision() + 1).apply();
        }
    }

    /** An All Apps refresh cannot replace a later user selection. */
    public boolean refreshAllApps(long expectedRevision, Set<String> installedPackages) {
        synchronized (AppModeManager.class) {
            if (!isAllApps() || expectedRevision != scopeRevision()) return false;
            preferences.edit().putStringSet(KEY_ALL_APPS_PACKAGES,
                    new LinkedHashSet<>(AppModePolicy.sanitizePackages(installedPackages))).apply();
            return true;
        }
    }

    public void save(boolean armed, Set<String> packages) {
        boolean wasArmed = isArmed();
        synchronized (AppModeManager.class) {
        preferences.edit()
                .putBoolean(KEY_ARMED, armed)
                .putStringSet(
                        KEY_INCLUDED_PACKAGES,
                        new LinkedHashSet<>(AppModePolicy.sanitizePackages(packages)))
                .putBoolean(KEY_ALL_APPS, false)
                .putLong(KEY_SCOPE_REVISION, scopeRevision() + 1)
                .commit();
        }
        syncProtectionSession(wasArmed, armed);
    }

    private void migrateAppSelection() {
        if (preferences.contains(KEY_INCLUDED_PACKAGES)) return;
        synchronized (AppModeManager.class) {
            if (preferences.contains(KEY_INCLUDED_PACKAGES)) return;
            Set<String> censor = preferences.getStringSet("app_mode_selected_packages", Set.of());
            Set<String> limits = preferences.getStringSet("app_timer_selected_packages", censor);
            Set<String> whispers =
                    preferences.getStringSet("subliminal_selected_packages", Set.of());
            boolean legacy =
                    preferences.contains(KEY_ARMED)
                            || preferences.contains("app_mode_kind")
                            || preferences.contains("app_mode_selected_packages")
                            || preferences.contains("app_timer_selected_packages")
                            || preferences.contains("subliminal_selected_packages");
            boolean all =
                    LegacyAppSelection.wasAllApps(
                            preferences.getString("app_mode_kind", "always"),
                            preferences.getBoolean("app_mode_kind_explicit_v2", false),
                            censor,
                            legacy);
            preferences
                    .edit()
                    .putStringSet(
                            KEY_INCLUDED_PACKAGES,
                            new LinkedHashSet<>(
                                    LegacyAppSelection.merge(
                                            false, censor, limits, whispers, Collections.emptySet())))
                    .putBoolean(KEY_ALL_APPS, all)
                    .remove("app_mode_kind")
                    .remove("app_mode_kind_explicit_v2")
                    .remove("app_mode_selected_packages")
                    .remove("app_timer_selected_packages")
                    .remove("subliminal_selected_packages")
                    .apply();
        }
    }

    /** Capture-method independent participation used by projection as well as Accessibility. */
    public boolean shouldCensorForeground(String foregroundPackage) {
        return new FeatureModuleManager(context).isCensorEnabled()
                && participates(foregroundPackage);
    }

    private boolean participates(String foregroundPackage) {
        return AppModePolicy.shouldRecognize(
                isArmed(),
                getIncludedPackages(),
                foregroundPackage,
                context.getPackageName(),
                inputMethodPackage());
    }

    public boolean shouldRecognize(String foregroundPackage) {
        // Lab reserves MediaProjection for video and runs only Accessibility censoring. It does
        // not replace the saved capture method, module switches or normal-session intent.
        if (CensorLabCensorSession.isActive()) {
            return AppModePolicy.shouldRecognize(true, getIncludedPackages(), foregroundPackage,
                    context.getPackageName(), inputMethodPackage());
        }
        if (!new FeatureModuleManager(context).isCensorEnabled()) return false;
        if (new SettingsRepository(context).loadCaptureMethod() != CaptureMethod.APP_MODE) {
            return false;
        }
        return participates(foregroundPackage);
    }

    public boolean shouldShowSubliminal(String foregroundPackage) {
        if (CensorLabCensorSession.isActive()) return false;
        if (!new FeatureModuleManager(context).isSubliminalEnabled()) return false;
        return participates(foregroundPackage);
    }

    /** Effective one-app scope for usage accounting, without enumerating installed apps. */
    public Set<String> timerScopeForForeground(String foregroundPackage) {
        if (CensorLabCensorSession.isActive()) return Set.of();
        if (!new FeatureModuleManager(context).isLimitsEnabled()) return Set.of();
        android.content.pm.ResolveInfo home = context.getPackageManager().resolveActivity(
                new android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME),
                android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
        String homePackage = home == null || home.activityInfo == null ? "" : home.activityInfo.packageName;
        boolean eligible = AppModePolicy.shouldLimit(isEffectivelyArmed(System.currentTimeMillis()),
                        getIncludedPackages(), foregroundPackage,
                context.getPackageName(), inputMethodPackage(), homePackage);
        return eligible ? Set.of(foregroundPackage.trim()) : Set.of();
    }

    /** Effects use the same included apps without requiring the Censor module. */
    public boolean shouldShowPopups(String foregroundPackage) {
        if (CensorLabCensorSession.isActive()) return false;
        if (!com.subhub.app.popup.PopupStormSettings.load(context).isEnabled()) return false;
        return participates(foregroundPackage);
    }

    public boolean isEffectivelyArmed(long nowMillis) {
        return isArmed();
    }

    public boolean isAccessibilityEnabled() {
        AccessibilityManager accessibility = context.getSystemService(AccessibilityManager.class);
        if (accessibility == null) return false;
        for (AccessibilityServiceInfo service : accessibility
                .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
            if (service.getResolveInfo() != null
                    && context.getPackageName().equals(
                    service.getResolveInfo().serviceInfo.packageName)) return true;
        }
        return false;
    }

    /**
     * App Mode already persists its exact armed/disarmed state across process and device restarts.
     */
    public void applyBootPolicy() {
        // Intentionally preserve KEY_ARMED. Android permissions remain independently revocable.
    }

    public String inputMethodPackage() {
        String setting = Settings.Secure.getString(
                context.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        if (setting == null) return "";
        int separator = setting.indexOf('/');
        return separator < 0 ? setting : setting.substring(0, separator);
    }

    /** One statistics session follows the explicit armed lifetime, not foreground app changes. */
    private void syncProtectionSession(boolean wasArmed, boolean armed) {
        if (wasArmed == armed) return;
        StatsRepository stats = new StatsRepository(context);
        if (armed) stats.startSession();
        else stats.endSession();
    }
}
