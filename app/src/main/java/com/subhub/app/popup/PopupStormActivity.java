package com.subhub.app.popup;

import com.subhub.app.util.PrimaryHeader;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.subhub.app.R;
import com.subhub.app.databinding.ActivityPopupStormBinding;
import com.subhub.app.security.ControllerEditMode;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.service.ScreenCaptureService;
import com.subhub.app.service.ScreenshotAccessibilityService;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Styled local configuration UI with explicit consent and a persistent escape control. */
public final class PopupStormActivity extends AppCompatActivity {
    private ActivityPopupStormBinding binding;
    private SharedPreferences preferences;
    private ControllerEditMode editMode;
    private boolean updatingEnabled;
    private final ActivityResultLauncher<Intent> enableOverlayPermission = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (Settings.canDrawOverlays(this)) completeEnable();
                else renderEnabled();
            });
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable statusUpdater = new Runnable() {
        @Override public void run() {
            if (binding == null) return;
            refreshStatus();
            ui.postDelayed(this, 500);
        }
    };
    private final ActivityResultLauncher<Uri> folderPicker = registerForActivityResult(
            new ActivityResultContracts.OpenDocumentTree(), this::onFolderPicked);
    private final ActivityResultLauncher<Intent> overlayPermission = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (Settings.canDrawOverlays(this)) preview();
                else refreshStatus();
            });

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityPopupStormBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        PrimaryHeader.bindSecondary(binding.getRoot(), R.string.popup_title, true);
        preferences = PopupStormSettings.preferences(this);
        binding.switchPopupStorm.setOnCheckedChangeListener((button, checked) -> {
            if (!updatingEnabled) setEffectEnabled(checked);
        });
        PopupStormManager.get().reloadSettings(this);
        PrimaryHeader.backButton(binding.getRoot()).setOnClickListener(view -> finish());
        binding.buttonAddFolder.setOnClickListener(view -> folderPicker.launch(null));
        binding.buttonStop.setOnClickListener(view -> {
            PopupStormManager.get().stop();
            refreshStatus();
        });
        binding.buttonPreview.setOnClickListener(view -> preview());
        binding.presetSlider.setLabelFormatter(value -> IntensityPresets.values()[Math.round(value)].getDisplayName());
        binding.presetSlider.addOnChangeListener((slider, value, fromUser) -> {
            if (fromUser) applyPreset(Math.round(value));
        });
        binding.presetSlider.addOnSliderTouchListener(new com.google.android.material.slider.Slider.OnSliderTouchListener() {
            @Override public void onStartTrackingTouch(com.google.android.material.slider.Slider slider) { }
            @Override public void onStopTrackingTouch(com.google.android.material.slider.Slider slider) {
                int step = Math.round(slider.getValue());
                if (!IntensityPresets.values()[step].name().equals(preferences.getString(PopupStormSettings.K_PRESET, "CUSTOM"))) {
                    applyPreset(step);
                }
            }
        });
        renderPreset();
        rebuildSettings();
        binding.buttonAdvancedDetails.setFocusable(true);
        binding.buttonAdvancedDetails.setText(getString(R.string.popup_advanced) + "  +");
        androidx.core.view.ViewCompat.setStateDescription(binding.buttonAdvancedDetails, getString(R.string.section_collapsed));
        binding.buttonAdvancedDetails.setOnClickListener(view -> {
            boolean expanded = binding.dynamicSettings.getVisibility() != View.VISIBLE;
            binding.dynamicSettings.setVisibility(expanded ? View.VISIBLE : View.GONE);
            binding.buttonAdvancedDetails.setText(getString(R.string.popup_advanced) + (expanded ? "  −" : "  +"));
            androidx.core.view.ViewCompat.setStateDescription(binding.buttonAdvancedDetails,
                    getString(expanded ? R.string.section_expanded : R.string.section_collapsed));
        });
        editMode = ControllerEditMode.bind(
                this, PrimaryHeader.editLockButton(binding.getRoot()), editing -> applyEditState());
    }

    @Override protected void onResume() {
        super.onResume();
        if (editMode != null) editMode.refresh();
        PopupStormManager.get().reloadSettings(this);
        ui.removeCallbacks(statusUpdater);
        ui.post(statusUpdater);
        mainDelayRefresh();
    }

    @Override protected void onPause() {
        ui.removeCallbacks(statusUpdater);
        super.onPause();
    }

    private void requestPreview() {
        if (!ControllerPinManager.isSessionUnlocked()) return;
        if (!preferences.getBoolean(PopupStormSettings.K_ACK, false)) {
            com.subhub.app.util.ThemedDialogs.builder(this)
                    .setTitle(R.string.popup_photosensitivity_title)
                    .setMessage(R.string.popup_photosensitivity_body)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.popup_acknowledge, (dialog, which) -> {
                        if (!ControllerPinManager.isSessionUnlocked()) return;
                        preferences.edit().putBoolean(PopupStormSettings.K_ACK, true).apply();
                        requestOverlayOrPreview();
                    }).show();
            return;
        }
        requestOverlayOrPreview();
    }

    private void requestOverlayOrPreview() {
        if (!ControllerPinManager.isSessionUnlocked()) return;
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.popup_overlay_permission, Toast.LENGTH_LONG).show();
            overlayPermission.launch(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } else preview();
    }

    private void preview() {
        if (!ControllerPinManager.isSessionUnlocked()) return;
        PopupStormSettings current = PopupStormSettings.load(this);
        if (!current.isAcknowledged() || !Settings.canDrawOverlays(this)) {
            requestPreview();
            return;
        }
        PopupStormManager.PreviewResult result = PopupStormManager.get().preview(this);
        if (result == PopupStormManager.PreviewResult.ALREADY_RUNNING) {
            Toast.makeText(this, R.string.popup_preview_already_running, Toast.LENGTH_SHORT).show();
        } else if (result == PopupStormManager.PreviewResult.UNAVAILABLE) {
            Toast.makeText(this, R.string.popup_preview_unavailable, Toast.LENGTH_SHORT).show();
        }
        refreshStatus();
    }

    private void renderPreset() {
        String selected = preferences.getString(PopupStormSettings.K_PRESET, "CUSTOM");
        IntensityPresets preset = null;
        for (IntensityPresets candidate : IntensityPresets.values()) {
            if (candidate.name().equals(selected)) preset = candidate;
        }
        binding.presetSlider.setValue(preset == null ? 1 : preset.ordinal());
        binding.presetValue.setText(preset == null
                ? getString(R.string.popup_custom_intensity) : preset.getDisplayName());
        binding.presetHint.setVisibility(preset == null ? View.VISIBLE : View.GONE);
    }

    private void applyPreset(int step) {
        if (!ControllerPinManager.isSessionUnlocked()) return;
        IntensityPresets.values()[step].apply(this);
        renderPreset();
        rebuildSettings();
        PopupStormManager.get().reloadSettings(this);
    }

    private void rebuildSettings() {
        binding.dynamicSettings.removeAllViews();
        LinearLayout behavior = card(R.string.popup_behavior);
        addFloatSlider(behavior, R.string.popup_spawn_rate, PopupStormSettings.K_SPAWN_RATE,
                0, 8, 2, .1f, "%.1f");
        addFloatSlider(behavior, R.string.popup_display_duration,
                PopupStormSettings.K_DISPLAY_DURATION, .3f, 6, 1, .1f, "%.1f");
        addIntSlider(behavior, R.string.popup_max_simultaneous,
                PopupStormSettings.K_MAX_SIMULTANEOUS, 1, 15, 8);
        addChoice(behavior, R.string.popup_position, PopupStormSettings.K_POSITION_MODE,
                new String[]{"random", "center"},
                new int[]{R.string.popup_random, R.string.popup_center}, "random");
        addChoice(behavior, R.string.popup_size_mode, PopupStormSettings.K_SIZE_MODE,
                new String[]{"random", "fixed"},
                new int[]{R.string.popup_random, R.string.popup_fixed}, "random");
        addIntSlider(behavior, R.string.popup_min_size, PopupStormSettings.K_MIN_SIZE, 80, 800, 160);
        addIntSlider(behavior, R.string.popup_max_size, PopupStormSettings.K_MAX_SIZE, 80, 1200, 380);
        addIntSlider(behavior, R.string.popup_fixed_size, PopupStormSettings.K_FIXED_SIZE, 80, 1200, 260);
        addToggle(behavior, R.string.popup_random_rotation, PopupStormSettings.K_RANDOM_ROT, false);
        addIntSlider(behavior, R.string.popup_rotation_max, PopupStormSettings.K_ROT_MAX, 0, 90, 25);
        addIntSlider(behavior, R.string.popup_fade_in, PopupStormSettings.K_FADE_IN, 0, 2000, 100);
        addIntSlider(behavior, R.string.popup_fade_out, PopupStormSettings.K_FADE_OUT, 0, 3000, 200);
        addToggle(behavior, R.string.popup_bouncing, PopupStormSettings.K_BOUNCING, false);
        addIntSlider(behavior, R.string.popup_bouncing_speed,
                PopupStormSettings.K_BOUNCING_SPEED, 10, 600, 60);
        addToggle(behavior, R.string.popup_tap_dismiss,
                PopupStormSettings.K_CLICK_DISMISS_ALL, true);

        LinearLayout modes = card(R.string.popup_modes);
        addToggle(modes, R.string.popup_burst_mode, PopupStormSettings.K_BURST_ENABLED, false);
        addFloatSlider(modes, R.string.popup_burst_frequency,
                PopupStormSettings.K_BURST_FREQUENCY, 5, 120, 30, 1, "%.0f");
        addFloatSlider(modes, R.string.popup_burst_duration,
                PopupStormSettings.K_BURST_DURATION, 1, 15, 4, .5f, "%.1f");
        addFloatSlider(modes, R.string.popup_burst_multiplier,
                PopupStormSettings.K_BURST_MULTIPLIER, 1, 5, 3, .5f, "%.1f");
        addIntSlider(modes, R.string.popup_denial_chance,
                PopupStormSettings.K_DENIAL_CHANCE, 0, 100, 0);
        addChoice(modes, R.string.popup_denial_style, PopupStormSettings.K_DENIAL_STYLE,
                new String[]{"blur", "pixelate", "mixed"},
                new int[]{R.string.popup_blur, R.string.popup_pixelate, R.string.popup_mixed}, "blur");
        addIntSlider(modes, R.string.popup_denial_intensity,
                PopupStormSettings.K_DENIAL_INTENSITY, 0, 100, 50);
        addToggle(modes, R.string.popup_denial_caption, PopupStormSettings.K_DENIAL_CAPTION, true);
        addTextField(modes, R.string.popup_caption_text,
                PopupStormSettings.K_DENIAL_CAPTION_TEXT, "NO");

        LinearLayout detection = card(R.string.popup_detection);
        addChoice(detection, R.string.popup_detection_mode, PopupStormSettings.K_DETECTION_MODE,
                new String[]{"off", "cover", "avoid"},
                new int[]{R.string.popup_off, R.string.popup_cover, R.string.popup_avoid}, "off");
        addIntSlider(detection, R.string.popup_avoid_padding,
                PopupStormSettings.K_AVOID_PADDING, 0, 400, 80);
        rebuildFolders();
        refreshStatus();
        applyEditState();
    }

    private void applyEditState() {
        if (binding == null) return;
        boolean editing = ControllerPinManager.isSessionUnlocked();
        renderEnabled();
        binding.buttonPreview.setEnabled(editing);
        binding.buttonAddFolder.setEnabled(editing);
        binding.presetSlider.setEnabled(editing);
        setEnabledRecursive(binding.dynamicSettings, editing);
        rebuildFolders();
        // Stop remains available as an unconditional safety action.
    }

    private void renderEnabled() {
        if (binding == null) return;
        updatingEnabled = true;
        binding.switchPopupStorm.setChecked(preferences.getBoolean(PopupStormSettings.K_ENABLED, false));
        binding.switchPopupStorm.setEnabled(ControllerPinManager.isDomModeActive());
        updatingEnabled = false;
    }

    private void setEffectEnabled(boolean enabled) {
        if (!ControllerPinManager.isDomModeActive()) { renderEnabled(); return; }
        if (!enabled) {
            preferences.edit().putBoolean(PopupStormSettings.K_ENABLED, false).apply();
            PopupStormManager.get().stop();
            PopupStormManager.get().reloadSettings(this);
            renderEnabled();
            refreshStatus();
        } else if (!preferences.getBoolean(PopupStormSettings.K_ACK, false)) {
            renderEnabled();
            com.subhub.app.util.ThemedDialogs.builder(this)
                    .setTitle(R.string.popup_photosensitivity_title)
                    .setMessage(R.string.popup_photosensitivity_body)
                    .setNegativeButton(android.R.string.cancel, (dialog, which) -> renderEnabled())
                    .setPositiveButton(R.string.popup_acknowledge, (dialog, which) -> {
                        if (!ControllerPinManager.isDomModeActive()) { renderEnabled(); return; }
                        preferences.edit().putBoolean(PopupStormSettings.K_ACK, true).apply();
                        requestEnableOverlay();
                    }).show();
        } else requestEnableOverlay();
    }

    private void requestEnableOverlay() {
        if (!ControllerPinManager.isDomModeActive()) { renderEnabled(); return; }
        if (!Settings.canDrawOverlays(this)) {
            renderEnabled();
            Toast.makeText(this, R.string.popup_overlay_permission, Toast.LENGTH_LONG).show();
            enableOverlayPermission.launch(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } else completeEnable();
    }

    private void completeEnable() {
        if (!ControllerPinManager.isDomModeActive() || !Settings.canDrawOverlays(this)
                || !preferences.getBoolean(PopupStormSettings.K_ACK, false)) {
            renderEnabled();
            return;
        }
        preferences.edit().putBoolean(PopupStormSettings.K_ENABLED, true).apply();
        PopupStormManager.get().reloadSettings(this);
        if (PopupStormActivationPolicy.shouldStart(ScreenCaptureService.isRunning(),
                ScreenshotAccessibilityService.isRecognitionActive())) PopupStormManager.get().start(this);
        renderEnabled();
        refreshStatus();
    }

    private static void setEnabledRecursive(View view, boolean enabled) {
        view.setEnabled(enabled);
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                setEnabledRecursive(group.getChildAt(index), enabled);
            }
        }
    }

    private LinearLayout card(int title) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = binding.dynamicSettings.getChildCount() == 0 ? dp(8) : dp(14);
        card.setLayoutParams(params);
        TextView header = (TextView) getLayoutInflater().inflate(R.layout.view_form_label, card, false);
        header.setText(title);
        card.addView(header);
        binding.dynamicSettings.addView(card);
        return card;
    }

    private TextView formLabel(String text, LinearLayout parent) {
        TextView label = (TextView) getLayoutInflater().inflate(R.layout.view_form_label, parent, false);
        label.setText(text);
        return label;
    }

    private void addToggle(LinearLayout parent, int title, String key, boolean defaultValue) {
        SwitchMaterial toggle = new com.subhub.app.util.StateToggle(this);
        toggle.setText(title);
        toggle.setEnabled(ControllerPinManager.isSessionUnlocked());
        toggle.setChecked(preferences.getBoolean(key, defaultValue));
        toggle.setOnCheckedChangeListener((button, checked) -> {
            preferences.edit().putBoolean(key, checked).apply();
            settingsChanged();
        });
        parent.addView(toggle);
    }

    private void addIntSlider(LinearLayout parent, int title, String key,
            int minimum, int maximum, int defaultValue) {
        int stored = Math.max(minimum, Math.min(maximum, preferences.getInt(key, defaultValue)));
        TextView value = formLabel(getString(title) + ": " + stored, parent);
        parent.addView(value);
        SeekBar slider = new SeekBar(this);
        slider.setMinimumHeight(dp(48));
        slider.setMax(maximum - minimum);
        slider.setProgress(stored - minimum);
        slider.setEnabled(ControllerPinManager.isSessionUnlocked());
        slider.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int actual = minimum + progress;
                value.setText(getString(title) + ": " + actual);
                if (fromUser) preferences.edit().putInt(key, actual).apply();
            }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { settingsChanged(); }
        });
        parent.addView(slider);
    }

    private void addFloatSlider(LinearLayout parent, int title, String key, float minimum,
            float maximum, float defaultValue, float step, String format) {
        float stored = Math.max(minimum, Math.min(maximum, preferences.getFloat(key, defaultValue)));
        int steps = Math.round((maximum - minimum) / step);
        TextView value = formLabel(getString(title) + ": " + String.format(Locale.ROOT, format, stored), parent);
        parent.addView(value);
        SeekBar slider = new SeekBar(this);
        slider.setMinimumHeight(dp(48));
        slider.setMax(steps);
        slider.setProgress(Math.round((stored - minimum) / step));
        slider.setEnabled(ControllerPinManager.isSessionUnlocked());
        slider.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float actual = minimum + progress * step;
                value.setText(getString(title) + ": " + String.format(Locale.ROOT, format, actual));
                if (fromUser) preferences.edit().putFloat(key, actual).apply();
            }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { settingsChanged(); }
        });
        parent.addView(slider);
    }

    private void addChoice(LinearLayout parent, int title, String key, String[] values,
            int[] labels, String defaultValue) {
        TextView heading = formLabel(getString(title), parent);
        parent.addView(heading);
        com.subhub.app.util.SelectionGrid group = new com.subhub.app.util.SelectionGrid(this, null);
        group.setTag("popup-choice:" + key);
        group.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        String selected = preferences.getString(key, defaultValue);
        for (int index = 0; index < values.length; index++) {
            Button option = new Button(this, null, 0, R.style.Widget_SubHub_SegmentedButton);
            option.setId(View.generateViewId());
            option.setTag(values[index]);
            option.setText(labels[index]);
            option.setEnabled(ControllerPinManager.isSessionUnlocked());
            option.setSelected(values[index].equals(selected));
            option.setMinHeight(dp(48));
            option.setMinimumHeight(dp(48));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            params.setMargins(dp(2), dp(2), dp(2), dp(2));
            group.addView(option, params);
            option.setOnClickListener(view -> {
                if (!ControllerPinManager.isSessionUnlocked()) return;
                for (int child = 0; child < group.getChildCount(); child++) {
                    group.getChildAt(child).setSelected(group.getChildAt(child) == view);
                }
                preferences.edit().putString(key, String.valueOf(view.getTag())).apply();
                settingsChanged();
            });
        }
        parent.addView(group);
    }

    private void addTextField(LinearLayout parent, int title, String key, String defaultValue) {
        TextView heading = formLabel(getString(title), parent);
        parent.addView(heading);
        EditText input = (EditText) getLayoutInflater().inflate(R.layout.view_preference_input, parent, false);
        input.setId(View.generateViewId());
        heading.setLabelFor(input.getId());
        input.setMinimumHeight(dp(48));
        input.setSingleLine(true);
        input.setMaxLines(1);
        input.setText(preferences.getString(key, defaultValue));
        com.subhub.app.util.UiIdentity.textSize(input, R.dimen.ui_text_body);
        input.setEnabled(ControllerPinManager.isSessionUnlocked());
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        View.OnFocusChangeListener save = (view, focused) -> {
            if (!focused) saveText(input, key, defaultValue);
        };
        input.setOnFocusChangeListener(save);
        input.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                saveText(input, key, defaultValue);
                input.clearFocus();
                return true;
            }
            return false;
        });
        parent.addView(input);
    }

    private void saveText(EditText input, String key, String defaultValue) {
        String value = input.getText().toString().trim();
        if (value.isEmpty()) value = defaultValue;
        if (value.length() > 32) value = value.substring(0, 32);
        preferences.edit().putString(key, value).apply();
        settingsChanged();
    }

    private void settingsChanged() {
        preferences.edit().remove(PopupStormSettings.K_PRESET).apply();
        renderPreset();
        PopupStormManager.get().reloadSettings(this);
        refreshStatus();
    }

    private void onFolderPicked(Uri uri) {
        if (uri == null) return;
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {
            Toast.makeText(this, R.string.popup_preview_unavailable, Toast.LENGTH_SHORT).show();
            return;
        }
        Set<String> folders = new LinkedHashSet<>(preferences.getStringSet(
                PopupStormSettings.K_FOLDERS, Set.of()));
        if (folders.size() < 20) folders.add(uri.toString());
        preferences.edit().putStringSet(PopupStormSettings.K_FOLDERS, folders).apply();
        PopupStormManager.get().reloadSettings(this);
        rebuildFolders();
        binding.libraryCount.setText(R.string.popup_library_scanning);
        mainDelayRefresh();
    }

    private void rebuildFolders() {
        binding.foldersList.removeAllViews();
        Set<String> folders = preferences.getStringSet(PopupStormSettings.K_FOLDERS, Set.of());
        if (folders == null || folders.isEmpty()) {
            TextView empty = label(getString(R.string.popup_no_folders), 11, false, R.color.text_muted);
            empty.setPadding(0, dp(10), 0, 0);
            binding.foldersList.addView(empty);
        } else {
            for (String value : new ArrayList<>(folders)) {
                LinearLayout row = new LinearLayout(this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                TextView name = label(prettyFolder(value), 11, false, R.color.text_secondary);
                name.setMinimumHeight(dp(48));
                row.addView(name, new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                Button remove = new Button(this);
                remove.setText(R.string.popup_remove);
                remove.setAllCaps(false);
                remove.setBackgroundResource(R.drawable.bg_outline_button);
                remove.setTextColor(getColor(R.color.accent));
                remove.setEnabled(ControllerPinManager.isSessionUnlocked());
                remove.setOnClickListener(view -> removeFolder(value));
                remove.setMinHeight(dp(48));
                remove.setMinimumHeight(dp(48));
                row.addView(remove, new LinearLayout.LayoutParams(
                        dp(104), ViewGroup.LayoutParams.WRAP_CONTENT));
                binding.foldersList.addView(row);
            }
        }
        int images = Math.max(1, PopupStormManager.get().libraryImageCount());
        binding.libraryCount.setText(getResources().getQuantityString(
                R.plurals.popup_library_count, images, images,
                folders == null ? 0 : folders.size()));
    }

    private void removeFolder(String value) {
        Set<String> folders = new LinkedHashSet<>(preferences.getStringSet(
                PopupStormSettings.K_FOLDERS, Set.of()));
        folders.remove(value);
        preferences.edit().putStringSet(PopupStormSettings.K_FOLDERS, folders).apply();
        try { getContentResolver().releasePersistableUriPermission(
                Uri.parse(value), Intent.FLAG_GRANT_READ_URI_PERMISSION); }
        catch (Exception ignored) {}
        PopupStormManager.get().reloadSettings(this);
        rebuildFolders();
        mainDelayRefresh();
    }

    private String prettyFolder(String value) {
        try {
            String last = Uri.parse(value).getLastPathSegment();
            return last == null ? value : Uri.decode(last).replace("primary:", "");
        } catch (Exception ignored) { return value; }
    }

    private void refreshStatus() {
        if (PopupStormManager.get().isPreviewing()) {
            long remaining = PopupStormManager.get().remainingPreviewMillis();
            binding.buttonPreview.setText(getString(R.string.popup_preview_active,
                    (int) ((remaining + 999L) / 1000L)));
        } else binding.buttonPreview.setText(R.string.popup_preview);
    }

    private void mainDelayRefresh() {
        binding.getRoot().postDelayed(() -> {
            if (binding == null) return;
            rebuildFolders();
            refreshStatus();
        }, 450);
    }

    private TextView label(String text, float size, boolean bold, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        com.subhub.app.util.UiIdentity.textSize(view, size == 12
                ? R.dimen.ui_text_label : R.dimen.ui_text_caption);
        view.setTextColor(getColor(color));
        if (bold) view.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        binding = null;
        super.onDestroy();
    }

    private abstract static class SimpleSeekListener implements SeekBar.OnSeekBarChangeListener {
        @Override public void onStartTrackingTouch(SeekBar seekBar) {}
    }
}
