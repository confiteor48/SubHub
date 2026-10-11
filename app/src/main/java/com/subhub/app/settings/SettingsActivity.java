package com.subhub.app.settings;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.subhub.app.R;
import com.subhub.app.capture.CensorImageEditor;
import com.subhub.app.databinding.ActivitySettingsBinding;
import com.subhub.app.detection.DetectionPreset;
import com.subhub.app.detection.DetectorConfig;
import com.subhub.app.detection.text.TextSmutConfig;
import com.subhub.app.overlay.CensorPhrases;
import com.subhub.app.security.ControllerEditMode;
import com.subhub.app.security.ControllerPinGate;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.stats.StatsRepository;
import com.subhub.app.util.PrimaryHeader;
import com.subhub.app.util.SubHubNavigation;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.IntConsumer;

/** Styled, source-native editor for live detector and censor preferences. */
public final class SettingsActivity extends AppCompatActivity {
    private ActivitySettingsBinding binding;
    private SettingsRepository repository;
    private StatsRepository stats;
    private CensorImageEditor images;
    private boolean bindingValues;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivitySettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        PrimaryHeader.bind(binding.getRoot(), R.drawable.ic_nav_censor,
                R.string.censor_header_title, 0);
        binding.getRoot().setFocusableInTouchMode(true);
        binding.getRoot().requestFocus();
        repository = new SettingsRepository(this);
        stats = new StatsRepository(this);
        images = new CensorImageEditor(this, binding.buttonAddCensorImages,
                binding.censorImagesStatus, binding.censorImagesList);
        adaptBorderChoices();
        renderDetectionLabels();
        arrangeCensorSections();
        bindDisclosures();
        bindValues();
        attachListeners();
        applyLockState();
        PrimaryHeader.backButton(binding.getRoot()).setOnClickListener(view -> finish());
        PrimaryHeader.editLockButton(binding.getRoot())
                .setOnClickListener(view -> toggleEditSession());
        SubHubNavigation.bind(this, binding.getRoot(), SubHubNavigation.Screen.CENSOR);
    }

    private void toggleEditSession() {
        if (ControllerPinManager.isSessionUnlocked()) {
            saveCustomPhrases();
            ControllerEditMode.enterSubMode(this);
        } else {
            ControllerPinGate.unlock(this, this::applyLockState, false);
        }
    }

    private void arrangeCensorSections() {
        LinearLayout page = (LinearLayout) binding.censorAppearance.getParent();
        page.removeView(binding.censorFilterRules);
        page.addView(binding.censorFilterRules, page.indexOfChild(binding.censorAppearance));
        binding.censorFilterRules.removeView(binding.censorCaptureSection);
        binding.censorFilterRules.addView(binding.censorCaptureSection);
    }

    private void bindDisclosures() {
        bindDisclosure(binding.buttonAppearanceDetails, binding.appearanceContent);
        bindDisclosure(binding.buttonCaptureDetails, binding.captureOptionsContent);
        binding.buttonOtherAreas.setOnClickListener(view -> {
            binding.otherAreaGrid.setVisibility(binding.otherAreaGrid.getVisibility() == View.VISIBLE
                    ? View.GONE : View.VISIBLE);
            refreshSectionSummaries();
        });
    }

    private void bindDisclosure(TextView header, View content) {
        CharSequence title = header.getText();
        content.setVisibility(View.GONE);
        header.setText(title + "  +");
        header.setFocusable(true);
        header.setOnClickListener(view -> {
            boolean expanded = content.getVisibility() != View.VISIBLE;
            content.setVisibility(expanded ? View.VISIBLE : View.GONE);
            header.setText(title + (expanded ? "  −" : "  +"));
            androidx.core.view.ViewCompat.setStateDescription(header,
                    getString(expanded ? R.string.section_expanded : R.string.section_collapsed));
        });
        androidx.core.view.ViewCompat.setStateDescription(header, getString(R.string.section_collapsed));
    }

    private void refreshSectionSummaries() {
        int selected = 0;
        for (CompoundButton area : new CompoundButton[] {binding.switchFaces,
                binding.switchMaleChest, binding.switchBelly, binding.switchFeet, binding.switchArmpits}) {
            if (area.isChecked()) selected++;
        }
        boolean expanded = binding.otherAreaGrid.getVisibility() == View.VISIBLE;
        binding.buttonOtherAreas.setText(getString(R.string.censor_other_areas) + " · "
                + (selected == 0 ? getString(R.string.censor_no_areas_selected)
                    : getString(R.string.censor_areas_selected, selected)) + (expanded ? "  −" : "  +"));
        androidx.core.view.ViewCompat.setStateDescription(binding.buttonOtherAreas,
                getString(expanded ? R.string.section_expanded : R.string.section_collapsed));
        binding.censorImagesSection.setVisibility(typeFor(checkedStyleId()) == CensorAppearance.Type.CUSTOM
                && !binding.switchReverse.isChecked() ? View.VISIBLE : View.GONE);
    }

    private void renderDetectionLabels() {
        RadioButton[] choices = {binding.radioPresetLow, binding.radioPresetMedium, binding.radioPresetHigh};
        int[] labels = {R.string.detection_level_low, R.string.detection_level_medium, R.string.detection_level_high};
        int[] explanations = {R.string.detection_low_help, R.string.detection_medium_help, R.string.detection_high_help};
        for (int index = 0; index < choices.length; index++) {
            String label = getString(labels[index]);
            android.text.SpannableString text = new android.text.SpannableString(label + "\n" + getString(explanations[index]));
            int flags = android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE;
            text.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, label.length(), flags);
            text.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.ITALIC), label.length() + 1, text.length(), flags);
            text.setSpan(new android.text.style.RelativeSizeSpan(.8f), label.length() + 1, text.length(), flags);
            text.setSpan(new android.text.style.ForegroundColorSpan(getColor(R.color.text_secondary)), label.length() + 1, text.length(), flags);
            com.subhub.app.util.UiIdentity.textSize(choices[index], R.dimen.ui_text_row_title);
            choices[index].setText(text);
        }
        com.subhub.app.util.UiIdentity.textSize(binding.radioPresetOff, R.dimen.ui_text_row_title);
        binding.radioPresetOff.setTypeface(null, android.graphics.Typeface.BOLD);
    }

    private void adaptBorderChoices() {
        android.content.res.Configuration config = getResources().getConfiguration();
        boolean stack = config.screenWidthDp < 480 || config.fontScale >= 1.3f;
        binding.borderEffectGroup.setOrientation(stack ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        for (int index = 0; index < binding.borderEffectGroup.getChildCount(); index++) {
            RadioButton choice = (RadioButton) binding.borderEffectGroup.getChildAt(index);
            android.widget.RadioGroup.LayoutParams params = (android.widget.RadioGroup.LayoutParams) choice.getLayoutParams();
            params.width = stack ? ViewGroup.LayoutParams.MATCH_PARENT : 0;
            params.weight = stack ? 0f : 1f;
            choice.setLayoutParams(params);
            com.subhub.app.util.UiIdentity.textSize(choice, R.dimen.ui_text_row_title);
            if (stack) {
                android.graphics.drawable.Drawable icon = choice.getCompoundDrawables()[1];
                choice.setCompoundDrawablesWithIntrinsicBounds(icon, null, null, null);
                choice.setGravity(Gravity.CENTER_VERTICAL);
                choice.setPadding(dp(12), dp(8), dp(12), dp(8));
                choice.setCompoundDrawablePadding(dp(8));
            }
        }
    }

    private void bindValues() {
        bindingValues = true;
        CensorAppearance appearance = repository.loadAppearance();
        binding.captureMethodGroup.check(repository.loadCaptureMethod() == CaptureMethod.APP_MODE
                ? R.id.radio_capture_app_mode : R.id.radio_capture_recording);
        binding.coverageGroup.check(repository.loadDetectorConfig().getCensorCoverage()
                == com.subhub.app.detection.CensorCoverage.WHOLE_PERSON
                ? R.id.radio_coverage_person : R.id.radio_coverage_areas);
        setCheckedStyle(radioFor(appearance.getType()));
        binding.intensitySeek.setProgress(appearance.getIntensity());
        binding.intensityValue.setText(percent(appearance.getIntensity()));
        int padding = Math.round(appearance.getSizePadding() * 100);
        binding.paddingSeek.setProgress(padding);
        binding.paddingValue.setText(percent(padding));
        binding.switchBorder.setChecked(appearance.isShowBorder());
        binding.switchText.setChecked(appearance.isShowText());
        binding.switchAnimateBorder.setChecked(appearance.isAnimateBorder());
        binding.borderEffectGroup.check(radioFor(appearance.getBorderEffect()));
        binding.switchReverse.setChecked(appearance.isReverseMode());
        binding.reverseStrengthSeek.setProgress(appearance.getReverseStrength());
        binding.reverseStrengthValue.setText(percent(appearance.getReverseStrength()));
        renderPaletteControls();
        renderColorButton(binding.borderColor, appearance.getBorderColor());
        renderColorButton(binding.gradientStart, appearance.getGradientStart());
        renderColorButton(binding.gradientEnd, appearance.getGradientEnd());
        binding.borderPreview.setAppearance(appearance);

        DetectionPreset preset = repository.loadDetectionPreset();
        binding.presetGroup.check(new FeatureModuleManager(this).isCensorEnabled()
                ? radioFor(preset) : R.id.radio_preset_off);
        DetectorConfig detector = repository.loadDetectorConfig();
        int confidence = Math.round(detector.getConfidenceThreshold() * 100);
        binding.confidenceSeek.setProgress(confidence);
        binding.confidenceValue.setText(percent(confidence));
        Set<String> categories = detector.getEnabledCategories();
        binding.switchGenitalsFemale.setChecked(categories.contains("genitals_female"));
        binding.switchGenitalsMale.setChecked(categories.contains("genitals_male"));
        binding.switchBreasts.setChecked(categories.contains("breasts"));
        binding.switchButtocks.setChecked(DetectionCategorySelection.isSelected(categories, "buttocks"));
        binding.switchFaces.setChecked(categories.contains("face"));
        binding.switchMaleChest.setChecked(categories.contains("male_chest"));
        binding.switchBelly.setChecked(categories.contains("belly"));
        binding.switchFeet.setChecked(categories.contains("feet"));
        binding.switchArmpits.setChecked(categories.contains("armpits"));
        binding.switchCovered.setChecked(containsCoveredCategory(categories));

        TextSmutConfig textSmut = repository.loadTextSmutConfig();
        binding.switchSmutText.setChecked(textSmut.isEnabled());
        binding.smutSensitivityGroup.check(radioFor(textSmut.getSensitivity()));
        Set<String> smutCategories = textSmut.getEnabledCategories();
        binding.switchSmutExplicit.setChecked(
                smutCategories.contains(TextSmutConfig.CATEGORY_EXPLICIT));
        binding.switchSmutFetish.setChecked(
                smutCategories.contains(TextSmutConfig.CATEGORY_FETISH));
        binding.switchSmutSolicitation.setChecked(
                smutCategories.contains(TextSmutConfig.CATEGORY_SOLICITATION));

        Set<String> phraseCategories = repository.preferences().getStringSet(
                SettingsRepository.KEY_ENABLED_PHRASE_CATEGORIES,
                CensorPhrases.DEFAULT_ENABLED);
        binding.switchPhraseShort.setChecked(phraseCategories.contains("short"));
        binding.switchPhraseDenial.setChecked(phraseCategories.contains("denial"));
        binding.switchPhraseHumiliation.setChecked(phraseCategories.contains("humiliation"));
        binding.switchPhraseEdge.setChecked(phraseCategories.contains("edge"));
        binding.switchPhraseFindom.setChecked(phraseCategories.contains("findom"));
        binding.switchPhraseNtr.setChecked(phraseCategories.contains("ntr"));
        binding.switchPhraseGooner.setChecked(phraseCategories.contains("gooner"));
        Set<String> customPhrases = repository.preferences().getStringSet(
                SettingsRepository.KEY_CUSTOM_PHRASES, new LinkedHashSet<>());
        binding.customPhrases.setText(joinLines(customPhrases));
        bindingValues = false;
    }

    private void attachListeners() {
        binding.captureMethodGroup.setOnCheckedChangeListener((group, checkedId) -> saveAll());
        binding.coverageGroup.setOnCheckedChangeListener((group, checkedId) -> saveAll());
        for (int id : styleRadioIds()) {
            RadioButton radio = findViewById(id);
            radio.setOnCheckedChangeListener((button, checked) -> {
                if (bindingValues || !checked) return;
                bindingValues = true;
                for (int otherId : styleRadioIds()) {
                    if (otherId != button.getId()) ((RadioButton) findViewById(otherId)).setChecked(false);
                }
                bindingValues = false;
                saveAll();
                renderPaletteControls();
            });
        }
        binding.borderEffectGroup.setOnCheckedChangeListener((group, checkedId) -> saveAll());
        binding.smutSensitivityGroup.setOnCheckedChangeListener((group, checkedId) -> saveAll());
        binding.presetGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (bindingValues) return;
            if (!ControllerPinManager.isDomModeActive()) {
                bindValues();
                applyLockState();
                return;
            }
            if (checkedId == R.id.radio_preset_off) {
                new FeatureModuleManager(this).setCensorEnabled(false);
                SubHubNavigation.bind(this, binding.getRoot(), SubHubNavigation.Screen.CENSOR);
                return;
            }
            DetectionPreset preset = presetFor(checkedId);
            repository.saveDetectionPreset(preset);
            bindingValues = true;
            int confidence = Math.round(preset.getConfidence() * 100);
            binding.confidenceSeek.setProgress(confidence);
            binding.confidenceValue.setText(percent(confidence));
            bindingValues = false;
            saveAll();
            new FeatureModuleManager(this).setCensorEnabled(true);
        });
        binding.intensitySeek.setOnSeekBarChangeListener(new SavingSeekListener() {
            @Override public void update(int progress, boolean fromUser) {
                binding.intensityValue.setText(percent(progress));
                if (fromUser) saveAll();
            }
        });
        binding.paddingSeek.setOnSeekBarChangeListener(new SavingSeekListener() {
            @Override public void update(int progress, boolean fromUser) {
                binding.paddingValue.setText(percent(progress));
                if (fromUser) saveAll();
            }
        });
        binding.confidenceSeek.setOnSeekBarChangeListener(new SavingSeekListener() {
            @Override public void update(int progress, boolean fromUser) {
                binding.confidenceValue.setText(percent(progress));
                if (fromUser) saveAll();
            }
        });
        binding.reverseStrengthSeek.setOnSeekBarChangeListener(new SavingSeekListener() {
            @Override public void update(int progress, boolean fromUser) {
                binding.reverseStrengthValue.setText(percent(progress));
                if (fromUser) saveAll();
            }
        });
        CompoundButton.OnCheckedChangeListener changed = (button, checked) -> saveAll();
        binding.switchBorder.setOnCheckedChangeListener((button, checked) -> {
            saveAll();
            syncBorderControlState();
        });
        binding.switchText.setOnCheckedChangeListener(changed);
        binding.switchAnimateBorder.setOnCheckedChangeListener(changed);
        binding.switchReverse.setOnCheckedChangeListener(changed);
        binding.switchGenitalsFemale.setOnCheckedChangeListener(changed);
        binding.switchGenitalsMale.setOnCheckedChangeListener(changed);
        binding.switchBreasts.setOnCheckedChangeListener(changed);
        binding.switchButtocks.setOnCheckedChangeListener(changed);
        binding.switchFaces.setOnCheckedChangeListener(changed);
        binding.switchMaleChest.setOnCheckedChangeListener(changed);
        binding.switchBelly.setOnCheckedChangeListener(changed);
        binding.switchFeet.setOnCheckedChangeListener(changed);
        binding.switchArmpits.setOnCheckedChangeListener(changed);
        binding.switchCovered.setOnCheckedChangeListener(changed);
        binding.switchSmutText.setOnCheckedChangeListener((button, checked) -> {
            saveAll();
            applyLockState();
        });
        binding.switchSmutExplicit.setOnCheckedChangeListener(changed);
        binding.switchSmutFetish.setOnCheckedChangeListener(changed);
        binding.switchSmutSolicitation.setOnCheckedChangeListener(changed);
        binding.switchPhraseShort.setOnCheckedChangeListener(changed);
        binding.switchPhraseDenial.setOnCheckedChangeListener(changed);
        binding.switchPhraseHumiliation.setOnCheckedChangeListener(changed);
        binding.switchPhraseEdge.setOnCheckedChangeListener(changed);
        binding.switchPhraseFindom.setOnCheckedChangeListener(changed);
        binding.switchPhraseNtr.setOnCheckedChangeListener(changed);
        binding.switchPhraseGooner.setOnCheckedChangeListener(changed);
        binding.buttonSavePhrases.setOnClickListener(view -> {
            saveCustomPhrases();
            saveAll();
            Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show();
        });
        binding.customPhrases.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) { }
            @Override public void afterTextChanged(android.text.Editable value) {
                if (!bindingValues && ControllerPinManager.isSessionUnlocked()) saveCustomPhrases();
            }
        });
        binding.paletteColorOne.setOnClickListener(view -> pickEffectColor(1));
        binding.gradientStart.setOnClickListener(view -> pickGradientColor(true));
        binding.gradientEnd.setOnClickListener(view -> pickGradientColor(false));
        binding.paletteColorTwo.setOnClickListener(view -> pickEffectColor(2));
        binding.paletteColorThree.setOnClickListener(view -> pickEffectColor(3));
        binding.borderColor.setOnClickListener(view -> showColorDialog(
                getString(R.string.border_color_label), repository.loadAppearance().getBorderColor(),
                color -> {
                    repository.preferences().edit().putString(
                            SettingsRepository.KEY_BORDER_COLOR,
                            SettingsRepository.colorString(color)).apply();
                    renderColorButton(binding.borderColor, color);
                    stats.setBorderColorChanged();
                    refreshBorderPreview();
                }));
    }

    private void applyLockState() {
        boolean editing = ControllerPinManager.isSessionUnlocked();
        ControllerEditMode.renderButton(this, PrimaryHeader.editLockButton(binding.getRoot()));
        setEnabledRecursive(binding.styleGroup,
                editing);
        setEnabledRecursive(binding.captureMethodGroup,
                editing);
        setEnabledRecursive(binding.coverageGroup,
                editing);
        binding.intensitySeek.setEnabled(
                editing);
        binding.paddingSeek.setEnabled(
                editing);
        binding.switchBorder.setEnabled(
                editing);
        boolean paletteEnabled = editing;
        setEnabledRecursive(binding.effectPaletteGroup, paletteEnabled);
        binding.switchText.setEnabled(
                editing);
        syncBorderControlState();
        binding.switchReverse.setEnabled(
                editing);
        binding.reverseStrengthSeek.setEnabled(
                editing);
        images.refresh();
        setEnabledRecursive(binding.presetGroup,
                editing);
        binding.confidenceSeek.setEnabled(
                editing);
        boolean categoriesEnabled =
                editing;
        CompoundButton[] categories = {
                binding.switchGenitalsFemale, binding.switchGenitalsMale, binding.switchBreasts,
                binding.switchButtocks, binding.switchFaces,
                binding.switchMaleChest, binding.switchBelly, binding.switchFeet,
                binding.switchArmpits, binding.switchCovered};
        for (CompoundButton category : categories) category.setEnabled(categoriesEnabled);
        binding.switchSmutText.setEnabled(
                editing);
        boolean smutDetailsEnabled = editing && binding.switchSmutText.isChecked();
        binding.textMatchingDetails.setVisibility(binding.switchSmutText.isChecked() ? View.VISIBLE : View.GONE);
        setEnabledRecursive(binding.smutSensitivityGroup, smutDetailsEnabled);
        boolean smutCategoriesEnabled = smutDetailsEnabled;
        binding.switchSmutExplicit.setEnabled(smutCategoriesEnabled);
        binding.switchSmutFetish.setEnabled(smutCategoriesEnabled);
        binding.switchSmutSolicitation.setEnabled(smutCategoriesEnabled);
        boolean phrasesEnabled =
                editing;
        CompoundButton[] phraseCategories = {
                binding.switchPhraseShort, binding.switchPhraseDenial,
                binding.switchPhraseHumiliation, binding.switchPhraseEdge,
                binding.switchPhraseFindom, binding.switchPhraseNtr, binding.switchPhraseGooner};
        for (CompoundButton category : phraseCategories) category.setEnabled(phrasesEnabled);
        boolean customPhrasesEnabled =
                editing;
        binding.customPhrases.setEnabled(customPhrasesEnabled);
        binding.buttonSavePhrases.setEnabled(customPhrasesEnabled && phrasesEnabled);
        refreshSectionSummaries();
    }

    private void syncBorderControlState() {
        boolean editing = ControllerPinManager.isSessionUnlocked();
        boolean active = binding.switchBorder.isChecked();
        binding.borderPreview.setAlpha(active ? 1f : .55f);
        binding.borderColor.setEnabled(active && editing);
        binding.borderColorField.setAlpha(active ? 1f : .5f);
        boolean gradient = binding.borderEffectGroup.getCheckedRadioButtonId() == R.id.radio_border_gradient;
        binding.borderColorField.setVisibility(gradient ? View.GONE : View.VISIBLE);
        binding.gradientColors.setVisibility(gradient ? View.VISIBLE : View.GONE);
        binding.gradientStart.setEnabled(active && editing);
        binding.gradientEnd.setEnabled(active && editing);
        binding.gradientColors.setAlpha(active ? 1f : .5f);
        binding.switchAnimateBorder.setEnabled(active && editing);
        setEnabledRecursive(binding.borderEffectGroup, active && editing);
        binding.borderEffectGroup.setAlpha(active ? 1f : .5f);
    }

    private void renderPaletteControls() {
        CensorAppearance.Type type = typeFor(checkedStyleId());
        int count = paletteSlotCount(type);
        binding.effectPaletteGroup.setVisibility(count == 0 ? View.GONE : View.VISIBLE);
        binding.paletteColorOneField.setVisibility(count >= 1 ? View.VISIBLE : View.GONE);
        binding.paletteColorTwoField.setVisibility(count >= 2 ? View.VISIBLE : View.GONE);
        binding.paletteColorThreeField.setVisibility(count >= 3 ? View.VISIBLE : View.GONE);
        if (count == 0) return;
        int[] labels = paletteLabels(type);
        binding.paletteColorOneLabel.setText(labels[0]);
        if (count >= 2) binding.paletteColorTwoLabel.setText(labels[1]);
        if (count >= 3) binding.paletteColorThreeLabel.setText(labels[2]);
        EffectPalette palette = repository.loadEffectPalette(type);
        renderColorButton(binding.paletteColorOne, palette.first());
        renderColorButton(binding.paletteColorTwo, palette.second());
        renderColorButton(binding.paletteColorThree, palette.third());
    }

    private void pickEffectColor(int slot) {
        CensorAppearance.Type type = typeFor(checkedStyleId());
        EffectPalette current = repository.loadEffectPalette(type);
        int selected = slot == 1 ? current.first() : slot == 2 ? current.second() : current.third();
        int[] labels = paletteLabels(type);
        showColorDialog(getString(labels[Math.max(0, Math.min(2, slot - 1))]), selected, color -> {
            EffectPalette updated = new EffectPalette(
                    slot == 1 ? color : current.first(),
                    slot == 2 ? color : current.second(),
                    slot == 3 ? color : current.third());
            repository.saveEffectPalette(type, updated);
            renderPaletteControls();
        });
    }

    private void pickGradientColor(boolean start) {
        CensorAppearance appearance = repository.loadAppearance();
        showColorDialog(getString(start ? R.string.gradient_start : R.string.gradient_end),
                start ? appearance.getGradientStart() : appearance.getGradientEnd(), color -> {
                    repository.preferences().edit().putString(start ? SettingsRepository.KEY_GRADIENT_START
                            : SettingsRepository.KEY_GRADIENT_END, SettingsRepository.colorString(color)).apply();
                    renderColorButton(start ? binding.gradientStart : binding.gradientEnd, color);
                    binding.borderPreview.setAppearance(repository.loadAppearance());
                    stats.setBorderColorChanged();
                });
    }

    private void showColorDialog(String title, int current, IntConsumer accepted) {
        com.subhub.app.util.ColorPickerDialog.show(this, title, current, color -> {
            if (ControllerPinManager.isSessionUnlocked()) accepted.accept(color);
        });
    }

    private void renderColorButton(TextView button, int color) {
        button.setText(SettingsRepository.colorString(color).substring(3));
        button.setTextColor(isLight(color) ? Color.BLACK : Color.WHITE);
        button.setGravity(Gravity.CENTER);
        button.setBackground(swatchBackground(color));
    }

    private void refreshBorderPreview() {
        if (binding != null) {
            binding.borderPreview.setAppearance(repository.loadAppearance());
            syncBorderControlState();
        }
    }

    private GradientDrawable swatchBackground(int color) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(12));
        background.setStroke(dp(1), getColor(R.color.accent));
        return background;
    }

    private static boolean isLight(int color) {
        return Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114
                >= 160_000;
    }

    private int paletteSlotCount(CensorAppearance.Type type) {
        switch (type) {
            case BOX: return 1;
            case STATIC: return 2;
            case GLITCH:
            case TAPE:
            case ERROR_POPUP: return 3;
            default: return 0;
        }
    }

    private int[] paletteLabels(CensorAppearance.Type type) {
        switch (type) {
            case STATIC:
                return new int[]{R.string.effect_color_dark, R.string.effect_color_light,
                        R.string.effect_color_light};
            case GLITCH:
                return new int[]{R.string.effect_color_left_shift,
                        R.string.effect_color_right_shift, R.string.effect_color_flash};
            case TAPE:
                return new int[]{R.string.effect_color_base,
                        R.string.effect_color_stripe_one, R.string.effect_color_stripe_two};
            case ERROR_POPUP:
                return new int[]{R.string.effect_color_panel,
                        R.string.effect_color_alert, R.string.effect_color_action};
            default:
                return new int[]{R.string.effect_color_fill,
                        R.string.effect_color_light, R.string.effect_color_flash};
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static void setEnabledRecursive(View view, boolean enabled) {
        view.setEnabled(enabled);
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int index = 0; index < group.getChildCount(); index++) {
            setEnabledRecursive(group.getChildAt(index), enabled);
        }
    }

    private void saveAll() {
        if (bindingValues) return;
        if (ControllerPinManager.isSessionUnlocked()) {
            repository.preferences().edit().putString(SettingsRepository.KEY_CENSOR_COVERAGE,
                    binding.radioCoveragePerson.isChecked() ? "whole_person" : "detected_areas").apply();
        }
        repository.saveCaptureMethod(binding.radioCaptureAppMode.isChecked()
                ? CaptureMethod.APP_MODE : CaptureMethod.SCREEN_RECORDING);
        String previousStyle = repository.preferences().getString(
                SettingsRepository.KEY_CENSOR_TYPE, "box");
        String selectedStyle = typeFor(checkedStyleId())
                .getPreferenceValue();
        String selectedBorder = borderFor(binding.borderEffectGroup.getCheckedRadioButtonId())
                .preferenceValue();
        repository.saveAppearance(
                typeFor(checkedStyleId()),
                binding.intensitySeek.getProgress(),
                binding.switchBorder.isChecked(),
                binding.switchText.isChecked());
        repository.preferences().edit()
                .putFloat(SettingsRepository.KEY_CENSOR_SIZE_PADDING,
                        binding.paddingSeek.getProgress() / 100f)
                .putBoolean(SettingsRepository.KEY_ANIMATE_BORDER,
                        binding.switchAnimateBorder.isChecked())
                .putString(SettingsRepository.KEY_BORDER_EFFECT,
                        borderFor(binding.borderEffectGroup.getCheckedRadioButtonId()).preferenceValue())
                .putBoolean(SettingsRepository.KEY_REVERSE_MODE, binding.switchReverse.isChecked())
                .putFloat(SettingsRepository.KEY_REVERSE_STRENGTH,
                        binding.reverseStrengthSeek.getProgress() / 100f)
                .putStringSet(SettingsRepository.KEY_ENABLED_PHRASE_CATEGORIES,
                        selectedPhraseCategories())
                .apply();

        Set<String> categories = new LinkedHashSet<>();
        if (binding.switchGenitalsFemale.isChecked()) categories.add("genitals_female");
        if (binding.switchGenitalsMale.isChecked()) categories.add("genitals_male");
        if (binding.switchBreasts.isChecked()) categories.add("breasts");
        DetectionCategorySelection.setSelected(categories, "buttocks", binding.switchButtocks.isChecked());
        if (binding.switchFaces.isChecked()) categories.add("face");
        if (binding.switchMaleChest.isChecked()) categories.add("male_chest");
        if (binding.switchBelly.isChecked()) categories.add("belly");
        if (binding.switchFeet.isChecked()) categories.add("feet");
        if (binding.switchArmpits.isChecked()) categories.add("armpits");
        if (binding.switchCovered.isChecked()) {
            categories.add("genitals_covered");
            categories.add("breasts_covered");
            categories.add("buttocks_covered");
            categories.add("anus_covered");
            categories.add("belly_covered");
            categories.add("feet_covered");
            categories.add("armpits_covered");
        }
        repository.saveDetection(binding.confidenceSeek.getProgress(), categories);
        Set<String> smutCategories = new LinkedHashSet<>();
        if (binding.switchSmutExplicit.isChecked()) {
            smutCategories.add(TextSmutConfig.CATEGORY_EXPLICIT);
        }
        if (binding.switchSmutFetish.isChecked()) {
            smutCategories.add(TextSmutConfig.CATEGORY_FETISH);
        }
        if (binding.switchSmutSolicitation.isChecked()) {
            smutCategories.add(TextSmutConfig.CATEGORY_SOLICITATION);
        }
        repository.saveTextSmutConfig(new TextSmutConfig(
                binding.switchSmutText.isChecked(),
                textSensitivityFor(binding.smutSensitivityGroup.getCheckedRadioButtonId()),
                smutCategories));
        stats.recordCensorStyleTried(selectedStyle);
        stats.recordBorderEffectTried(selectedBorder);
        if (!selectedStyle.equals(previousStyle)) stats.incrementCensorStyleChanges();
        refreshBorderPreview();
        refreshSectionSummaries();
    }

    private void saveCustomPhrases() {
        Set<String> values = new LinkedHashSet<>();
        for (String line : binding.customPhrases.getText().toString().split("\\R")) {
            String phrase = line.trim();
            if (!phrase.isEmpty()) values.add(phrase.length() > 80 ? phrase.substring(0, 80) : phrase);
        }
        repository.preferences().edit()
                .putStringSet(SettingsRepository.KEY_CUSTOM_PHRASES, values)
                .apply();
        stats.setCustomPhrasesCount(values.size());
    }

    private Set<String> selectedPhraseCategories() {
        Set<String> values = new LinkedHashSet<>();
        if (binding.switchPhraseShort.isChecked()) values.add("short");
        if (binding.switchPhraseDenial.isChecked()) values.add("denial");
        if (binding.switchPhraseHumiliation.isChecked()) values.add("humiliation");
        if (binding.switchPhraseEdge.isChecked()) values.add("edge");
        if (binding.switchPhraseFindom.isChecked()) values.add("findom");
        if (binding.switchPhraseNtr.isChecked()) values.add("ntr");
        if (binding.switchPhraseGooner.isChecked()) values.add("gooner");
        return values;
    }

    private int radioFor(CensorAppearance.Type type) {
        switch (type) {
            case PIXELATE: return R.id.radio_pixelate;
            case BLUR: return R.id.radio_blur;
            case CUSTOM: return R.id.radio_custom;
            case STATIC: return R.id.radio_static;
            case GLITCH: return R.id.radio_glitch;
            case TAPE: return R.id.radio_tape;
            case ERROR_POPUP: return R.id.radio_error;
            default: return R.id.radio_box;
        }
    }

    private void setCheckedStyle(int checkedId) {
        for (int id : styleRadioIds()) {
            ((RadioButton) findViewById(id)).setChecked(id == checkedId);
        }
    }

    private int checkedStyleId() {
        for (int id : styleRadioIds()) {
            if (((RadioButton) findViewById(id)).isChecked()) return id;
        }
        return R.id.radio_box;
    }

    private static int[] styleRadioIds() {
        return new int[]{R.id.radio_box, R.id.radio_pixelate, R.id.radio_blur,
                R.id.radio_custom, R.id.radio_static, R.id.radio_glitch,
                R.id.radio_tape, R.id.radio_error};
    }

    private CensorAppearance.Type typeFor(int id) {
        if (id == R.id.radio_pixelate) return CensorAppearance.Type.PIXELATE;
        if (id == R.id.radio_blur) return CensorAppearance.Type.BLUR;
        if (id == R.id.radio_custom) return CensorAppearance.Type.CUSTOM;
        if (id == R.id.radio_static) return CensorAppearance.Type.STATIC;
        if (id == R.id.radio_glitch) return CensorAppearance.Type.GLITCH;
        if (id == R.id.radio_tape) return CensorAppearance.Type.TAPE;
        if (id == R.id.radio_error) return CensorAppearance.Type.ERROR_POPUP;
        return CensorAppearance.Type.BOX;
    }

    private int radioFor(CensorAppearance.BorderEffect effect) {
        switch (effect) {
            case GLOW: return R.id.radio_border_glow;
            case GRADIENT: return R.id.radio_border_gradient;
            case RAINBOW: return R.id.radio_border_rainbow;
            default: return R.id.radio_border_classic;
        }
    }

    private CensorAppearance.BorderEffect borderFor(int id) {
        if (id == R.id.radio_border_glow) return CensorAppearance.BorderEffect.GLOW;
        if (id == R.id.radio_border_gradient) return CensorAppearance.BorderEffect.GRADIENT;
        if (id == R.id.radio_border_rainbow) return CensorAppearance.BorderEffect.RAINBOW;
        return CensorAppearance.BorderEffect.CLASSIC;
    }

    private int radioFor(DetectionPreset preset) {
        switch (preset) {
            case LOW: return R.id.radio_preset_low;
            case HIGH: return R.id.radio_preset_high;
            default: return R.id.radio_preset_medium;
        }
    }

    private int radioFor(int textSensitivity) {
        if (textSensitivity == TextSmutConfig.SENSITIVITY_STRICT) return R.id.radio_smut_strict;
        if (textSensitivity == TextSmutConfig.SENSITIVITY_BROAD) return R.id.radio_smut_broad;
        return R.id.radio_smut_balanced;
    }

    private int textSensitivityFor(int id) {
        if (id == R.id.radio_smut_strict) return TextSmutConfig.SENSITIVITY_STRICT;
        if (id == R.id.radio_smut_broad) return TextSmutConfig.SENSITIVITY_BROAD;
        return TextSmutConfig.SENSITIVITY_BALANCED;
    }

    private DetectionPreset presetFor(int id) {
        if (id == R.id.radio_preset_low) return DetectionPreset.LOW;
        if (id == R.id.radio_preset_high) return DetectionPreset.HIGH;
        return DetectionPreset.MEDIUM;
    }

    private static boolean containsCoveredCategory(Set<String> categories) {
        for (String value : categories) if (value.endsWith("_covered")) return true;
        return false;
    }

    private static String joinLines(Set<String> values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (result.length() > 0) result.append('\n');
            result.append(value);
        }
        return result.toString();
    }

    private String percent(int value) { return value + "%"; }

    @Override
    protected void onResume() {
        super.onResume();
        SubHubNavigation.bind(this, binding.getRoot(), SubHubNavigation.Screen.CENSOR);
        if (binding != null) {
            bindValues();
            applyLockState();
        }
    }

    @Override
    protected void onDestroy() {
        if (images != null) images.close();
        binding = null;
        super.onDestroy();
    }

    private abstract static class SavingSeekListener implements SeekBar.OnSeekBarChangeListener {
        abstract void update(int progress, boolean fromUser);
        @Override public final void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            update(progress, fromUser);
        }
        @Override public void onStartTrackingTouch(SeekBar seekBar) {}
        @Override public void onStopTrackingTouch(SeekBar seekBar) {}
    }
}
