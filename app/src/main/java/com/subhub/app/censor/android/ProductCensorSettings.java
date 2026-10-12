package com.subhub.app.censor.android;

import android.content.SharedPreferences;
import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.capture.CustomImageManager;
import com.subhub.app.capture.export.ExportSettings;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.settings.FeatureModuleManager;
import java.util.*;

/** Private immutable configuration only, never controller credentials, wallet data or persisted jobs. */
final class ProductCensorSettings implements SharedPreferences {
    private static final Set<String> EXTRA = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            SettingsRepository.KEY_TEXT_SMUT_ENABLED, SettingsRepository.KEY_TEXT_SMUT_SENSITIVITY,
            SettingsRepository.KEY_TEXT_SMUT_CATEGORIES, SettingsRepository.KEY_CAPTURE_METHOD,
            FeatureModuleManager.KEY_CENSOR_ENABLED, AppModeManager.KEY_ARMED,
            AppModeManager.KEY_INCLUDED_PACKAGES, AppModeManager.KEY_ALL_APPS,
            "app_include_all_packages_v1", "app_include_revision_v1", CustomImageManager.REVISION_KEY)));
    private final Map<String, Object> values;
    ProductCensorSettings(SharedPreferences source) {
        Map<String, Object> copy = new HashMap<>();
        for (Map.Entry<String, ?> entry : source.getAll().entrySet()) {
            if (!relevant(entry.getKey())) continue;
            Object value = entry.getValue();
            if (value instanceof Set<?>) value = Collections.unmodifiableSet(new HashSet<>((Set<?>) value));
            copy.put(entry.getKey(), value);
        }
        values = Collections.unmodifiableMap(copy);
    }
    static boolean relevant(String key) { return EXTRA.contains(key) || ExportSettings.allowed(key); }
    boolean same(ProductCensorSettings other) { return other != null && values.equals(other.values); }
    /** Appearance changes do not mint a new counting identity or revoke pending source evidence. */
    boolean sameParticipation(ProductCensorSettings other) {
        return other != null && included().equals(other.included())
                && getBoolean(AppModeManager.KEY_ARMED, false) == other.getBoolean(AppModeManager.KEY_ARMED, false)
                && getBoolean(FeatureModuleManager.KEY_CENSOR_ENABLED, true)
                    == other.getBoolean(FeatureModuleManager.KEY_CENSOR_ENABLED, true)
                && Objects.equals(getString(SettingsRepository.KEY_CAPTURE_METHOD, null),
                    other.getString(SettingsRepository.KEY_CAPTURE_METHOD, null));
    }
    Set<String> included() {
        Set<String> selected = getStringSet(AppModeManager.KEY_INCLUDED_PACKAGES, Collections.emptySet());
        return com.subhub.app.appmode.AppModePolicy.sanitizePackages(getBoolean(AppModeManager.KEY_ALL_APPS, false)
                ? getStringSet("app_include_all_packages_v1", selected) : selected);
    }
    public Map<String, ?> getAll() { return values; }
    public String getString(String k, String d) { return (String) values.getOrDefault(k, d); }
    @SuppressWarnings("unchecked") public Set<String> getStringSet(String k, Set<String> d) { return (Set<String>) values.getOrDefault(k, d); }
    public int getInt(String k, int d) { return (Integer) values.getOrDefault(k, d); }
    public long getLong(String k, long d) { return (Long) values.getOrDefault(k, d); }
    public float getFloat(String k, float d) { return (Float) values.getOrDefault(k, d); }
    public boolean getBoolean(String k, boolean d) { return (Boolean) values.getOrDefault(k, d); }
    public boolean contains(String key) { return values.containsKey(key); }
    public Editor edit() { throw new UnsupportedOperationException("Read-only product configuration"); }
    public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { }
    public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { }
}
