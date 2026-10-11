package com.subhub.app.capture.export;
import android.os.Bundle;
import android.widget.*;
import com.subhub.app.R;
import com.subhub.app.security.ControllerPinGate;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.settings.CensorAppearance;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.util.ColorPickerDialog;
import com.subhub.app.util.PreferencePage;
import com.subhub.app.util.ThemedDialogs;
import java.util.*;

/** Export-only controls share the live appearance vocabulary without writing its preferences. */
public final class ExportAppearanceActivity extends PreferencePage {
    public static final String EXTRA_SHOW_CATEGORIES = "export_show_categories";
    private SettingsRepository settings;
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); page(R.string.export_appearance_title);
        ControllerPinGate.require(this,
                () -> {
                    render();
                    if (getIntent().getBooleanExtra(EXTRA_SHOW_CATEGORIES, false)) {
                        getIntent().removeExtra(EXTRA_SHOW_CATEGORIES);
                        categories();
                    }
                }, true);
    }
    private void render() {
        if (isDestroyed() || isFinishing()) return;
        settings = SettingsRepository.forPreferences(ExportSettings.preferences(this));
        page(R.string.export_appearance_title);
        fieldHelp(page, getString(R.string.export_independent_help));
        CensorAppearance appearance = settings.loadAppearance();
        LinearLayout style = card(page);
        button(style, getString(R.string.export_style_value, label(appearance.getType().getPreferenceValue())), () -> {
            CensorAppearance.Type[] types = CensorAppearance.Type.values(); String[] labels = new String[types.length];
            int selected = 0;
            for (int i = 0; i < types.length; i++) { labels[i] = label(types[i].getPreferenceValue()); if (types[i] == appearance.getType()) selected = i; }
            ThemedDialogs.builder(this).setTitle(R.string.export_style_title).setSingleChoiceItems(labels, selected, (d, which) -> {
                if (ControllerPinManager.isDomModeActive()) settings.preferences().edit().putString(SettingsRepository.KEY_CENSOR_TYPE, types[which].getPreferenceValue()).apply();
                d.dismiss(); render();
            }).setNegativeButton(android.R.string.cancel, null).show();
        });
        slider(style, R.string.export_intensity, appearance.getIntensity(), 100, value ->
                settings.preferences().edit().putInt(SettingsRepository.KEY_CENSOR_INTENSITY, value).apply());
        slider(style, R.string.export_padding, Math.round(appearance.getSizePadding() * 100), 100, value ->
                settings.preferences().edit().putFloat(SettingsRepository.KEY_CENSOR_SIZE_PADDING, value / 100f).apply());
        button(style, getString(R.string.export_palette), () -> colors());
        toggle(style, R.string.export_border, appearance.isShowBorder(), checked -> writeBoolean(SettingsRepository.KEY_SHOW_BORDER, checked));
        toggle(style, R.string.export_labels, appearance.isShowText(), checked -> writeBoolean(SettingsRepository.KEY_SHOW_TEXT, checked));
        toggle(style, R.string.export_reverse, appearance.isReverseMode(), checked -> writeBoolean(SettingsRepository.KEY_REVERSE_MODE, checked));
        slider(style, R.string.export_reverse_strength, appearance.getReverseStrength(), 100, value ->
                settings.preferences().edit().putFloat(SettingsRepository.KEY_REVERSE_STRENGTH, value / 100f).apply());
        LinearLayout categories = card(page);
        button(categories, getString(R.string.export_categories), this::categories);
        slider(categories, R.string.export_confidence, Math.round(settings.loadDetectorConfig().getConfidenceThreshold() * 100), 100, value ->
                settings.preferences().edit().putInt(SettingsRepository.KEY_CONFIDENCE, Math.max(1, value)).apply());
        fieldHelp(categories, getString(R.string.export_custom_assets_help));
        button(page, getString(R.string.export_reset_live), () -> {
            if (!ControllerPinManager.isDomModeActive()) return;
            ThemedDialogs.builder(this).setTitle(R.string.export_reset_live).setMessage(R.string.export_reset_help)
                    .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.export_reset_live, (d, w) -> {
                        if (ControllerPinManager.isDomModeActive()) { ExportSettings.reset(this); render(); }
                    }).show();
        });
    }
    private void writeBoolean(String key, boolean value) {
        if (ControllerPinManager.isDomModeActive()) settings.preferences().edit().putBoolean(key, value).apply();
    }
    private void colors() {
        CensorAppearance appearance = settings.loadAppearance();
        String[] labels = {getString(R.string.export_color_one), getString(R.string.export_color_two), getString(R.string.export_color_three), getString(R.string.export_border_color)};
        int[] colors = {appearance.getEffectPalette().first(), appearance.getEffectPalette().second(), appearance.getEffectPalette().third(), appearance.getBorderColor()};
        ThemedDialogs.builder(this).setTitle(R.string.export_palette).setItems(labels, (dialog, which) ->
                ColorPickerDialog.show(this, labels[which], colors[which], color -> {
                    if (ControllerPinManager.isDomModeActive()) settings.preferences().edit().putString(which == 3 ? SettingsRepository.KEY_BORDER_COLOR
                            : SettingsRepository.paletteKey(appearance.getType(), which + 1), SettingsRepository.colorString(color)).apply();
                })).show();
    }
    private void categories() {
        String[] keys = {"genitals_female", "genitals_male", "breasts", "buttocks", "male_chest", "belly", "feet", "armpits", "face_female", "face_male",
                "genitals_covered", "breasts_covered", "buttocks_covered", "belly_covered", "feet_covered", "armpits_covered"};
        String[] labels = getResources().getStringArray(R.array.export_category_labels);
        Set<String> selected = new LinkedHashSet<>(settings.loadDetectorConfig().getEnabledCategories());
        boolean[] checked = new boolean[keys.length];
        for (int i = 0; i < keys.length; i++) checked[i] = selected.contains(keys[i])
                || (keys[i].startsWith("face_") && selected.contains("face"))
                || (keys[i].equals("buttocks") && selected.contains("anus"))
                || (keys[i].equals("buttocks_covered") && selected.contains("anus_covered"));
        ThemedDialogs.builder(this).setTitle(R.string.export_categories).setMultiChoiceItems(labels, checked, (d, which, value) -> checked[which] = value)
                .setNegativeButton(android.R.string.cancel, null).setPositiveButton(android.R.string.ok, (d, which) -> {
                    if (!ControllerPinManager.isDomModeActive()) return;
                    Set<String> values = new LinkedHashSet<>();
                    for (int i = 0; i < keys.length; i++) if (checked[i]) {
                        values.add(keys[i]); if (keys[i].equals("buttocks")) values.add("anus");
                        if (keys[i].equals("buttocks_covered")) values.add("anus_covered");
                    }
                    settings.preferences().edit().putStringSet(SettingsRepository.KEY_ENABLED_CATEGORIES, values).apply();
                }).show();
    }
    private void slider(LinearLayout parent, int label, int value, int max, java.util.function.IntConsumer change) {
        TextView caption = fieldLabel(parent, getString(label) + " · " + value + "%");
        SeekBar slider = new SeekBar(this); slider.setMax(max); slider.setProgress(value); slider.setContentDescription(getString(label));
        parent.addView(slider, new LinearLayout.LayoutParams(-1, dp(48)));
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int progress, boolean user) { caption.setText(getString(label) + " · " + progress + "%"); if (user && ControllerPinManager.isDomModeActive()) change.accept(progress); }
            public void onStartTrackingTouch(SeekBar s) { }
            public void onStopTrackingTouch(SeekBar s) { }
        });
    }
    static String label(String value) { String spaced = value.replace('_', ' '); return spaced.substring(0, 1).toUpperCase(Locale.getDefault()) + spaced.substring(1); }
}
