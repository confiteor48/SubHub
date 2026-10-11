package com.subhub.app.studio;

import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import com.subhub.app.util.StateToggle;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import com.subhub.app.R;
import com.subhub.app.pack.PackSettingCatalog;
import com.subhub.app.pack.SubHubPackSchema;
import com.subhub.app.util.CompactFieldLayout;
import com.subhub.app.settings.DetectionCategorySelection;
import org.json.JSONArray;
import org.json.JSONObject;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Draft-only native controls, grouped from the same typed contract used by import/apply. */
final class PackSectionEditor {
    private final AppCompatActivity activity;
    private final String section;
    private final JSONObject working;
    private final Consumer<JSONObject> saved;
    private final Map<String, View> rows = new LinkedHashMap<>();
    private final Map<String, View> controls = new LinkedHashMap<>();
    private final Map<Integer, CompactFieldLayout> groups = new LinkedHashMap<>();
    private final Set<String> invalidInputs = new LinkedHashSet<>();
    private boolean updating;

    static void show(AppCompatActivity activity, String section, String title,
            JSONObject values, Consumer<JSONObject> saved) {
        new PackSectionEditor(activity, section, values, saved).show(title);
    }

    private PackSectionEditor(AppCompatActivity activity, String section, JSONObject values,
            Consumer<JSONObject> saved) {
        this.activity = activity; this.section = section; this.saved = saved;
        working = PackSettingCatalog.complete(section, values);
    }

    private void show(String title) {
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(false);
        LinearLayout content = column();
        content.setPadding(dp(16), dp(8), dp(16), dp(12));
        scroll.addView(content);
        content.addView(label(activity.getString(R.string.pack_editor_draft_only), false));
        if (SubHubPackSchema.WALLET.equals(section)) {
            content.addView(label(activity.getString(R.string.pack_editor_wallet_note), false));
        }
        if (SubHubPackSchema.POPUP.equals(section)) {
            content.addView(label(activity.getString(R.string.pack_editor_popup_note), false));
        }
        for (PackSettingCatalog.Field field : PackSettingCatalog.fields(section)) {
            CompactFieldLayout group = groups.get(field.group);
            if (group == null) {
                group = new CompactFieldLayout(activity);
                group.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
                group.setPadding(dp(8), 0, dp(8), dp(10));
                Button heading = action(activity.getString(field.group) + " ▸");
                heading.setTag("pack_group:" + section + ":" + field.group);
                ViewCompat.setAccessibilityHeading(heading, true);
                CompactFieldLayout expanded = group;
                heading.setOnClickListener(view -> {
                    boolean open = expanded.getVisibility() != View.VISIBLE;
                    expanded.setVisibility(open ? View.VISIBLE : View.GONE);
                    heading.setText(activity.getString(field.group) + (open ? " ▾" : " ▸"));
                    ViewCompat.setStateDescription(heading, activity.getString(open
                            ? R.string.pack_editor_expanded : R.string.pack_editor_collapsed));
                });
                group.setVisibility(groups.isEmpty() ? View.VISIBLE : View.GONE);
                ViewCompat.setStateDescription(heading, activity.getString(groups.isEmpty()
                        ? R.string.pack_editor_expanded : R.string.pack_editor_collapsed));
                if (groups.isEmpty()) heading.setText(activity.getString(field.group) + " ▾");
                content.addView(heading);
                content.addView(group);
                groups.put(field.group, group);
                if (field.group == R.string.pack_group_timing) {
                    group.addField(label(activity.getString(R.string.pack_editor_timing_help), false), true);
                }
            }
            LinearLayout row = column();
            row.setPadding(0, 0, 0, 0);
            rows.put(field.key, row);
            if (field.kind == PackSettingCatalog.Kind.BOOLEAN) {
                StateToggle check = new StateToggle(activity);
                check.setText(field.label);
                com.subhub.app.util.UiIdentity.textSize(check, R.dimen.ui_text_body);
                check.setMinHeight(dp(48));
                check.setTag(field.key);
                check.setChecked(working.optBoolean(field.key));
                check.setOnCheckedChangeListener((button, checked) -> changed(field, checked));
                controls.put(field.key, check);
                row.addView(check);
            } else {
                boolean tributeAmount = SubHubPackSchema.WALLET.equals(section)
                        && field.key.startsWith("rule_") && field.key.endsWith("_cents");
                TextView name = label(activity.getString(tributeAmount
                        ? R.string.pack_editor_tribute_amount : field.label), true);
                row.addView(name);
                View control = makeControl(field);
                control.setId(View.generateViewId());
                control.setTag(field.key);
                control.setContentDescription(activity.getString(field.label));
                name.setLabelFor(control.getId());
                controls.put(field.key, control);
                LinearLayout.LayoutParams controlParams = new LinearLayout.LayoutParams(-1, -2);
                controlParams.topMargin = activity.getResources().getDimensionPixelSize(R.dimen.ui_gap_inline);
                row.addView(control, controlParams);
            }
            if ("censor_coverage".equals(field.key)) {
                row.addView(label(activity.getString(R.string.pack_editor_image_coverage_help), false));
            }
            boolean fullWidth = field.kind == PackSettingCatalog.Kind.TEXT
                    || field.kind == PackSettingCatalog.Kind.SELECTION
                    || (SubHubPackSchema.WALLET.equals(section) && "enabled".equals(field.key))
                    || "error_popup_text".equals(field.key);
            group.addField(row, fullWidth);
        }
        refreshApplicability();
        AlertDialog dialog = com.subhub.app.util.ThemedDialogs.builder(activity)
                .setTitle(activity.getString(R.string.pack_editor_title, title))
                .setView(scroll)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.pack_editor_save, null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    for (String key : invalidInputs) {
                        View control = controls.get(key);
                        if (control != null && control.isEnabled() && rows.get(key).getVisibility() == View.VISIBLE) {
                            showInvalid(key, R.string.pack_editor_invalid);
                            return;
                        }
                    }
                    try {
                        JSONObject clean = PackSettingCatalog.sanitize(section, working);
                        saved.accept(clean);
                        dialog.dismiss();
                    } catch (PackSettingCatalog.ValidationException invalid) {
                        int message = switch (invalid.problem) {
                            case MINIMUM_MAXIMUM -> R.string.pack_editor_minmax;
                            case SPENDING_CAP -> R.string.pack_editor_caps;
                            case MESSAGE_LIMIT -> R.string.pack_editor_message_limit;
                            case VALUE -> R.string.pack_editor_invalid;
                        };
                        showInvalid(invalid.key, message);
                    } catch (IllegalArgumentException invalid) {
                        Toast.makeText(activity, R.string.pack_apply_invalid, Toast.LENGTH_LONG).show();
                    }
                }));
        dialog.show();
    }

    private View makeControl(PackSettingCatalog.Field field) {
        if (field.kind == PackSettingCatalog.Kind.COLOR) {
            Button pick = new Button(activity);
            pick.setMinHeight(dp(48));
            pick.setText(field.displayText(working.opt(field.key)));
            pick.setOnClickListener(view -> com.subhub.app.util.ColorPickerDialog.show(activity,
                    activity.getString(field.label), android.graphics.Color.parseColor(working.optString(field.key)),
                    color -> {
                        changed(field, field.normalize(com.subhub.app.settings.SettingsRepository.colorString(color)));
                        pick.setText(field.displayText(working.opt(field.key)));
                    }));
            return pick;
        }
        if (!field.choices.isEmpty() && field.kind != PackSettingCatalog.Kind.SELECTION) {
            Spinner spinner = new androidx.appcompat.widget.AppCompatSpinner(activity, null,
                    androidx.appcompat.R.attr.spinnerStyle, Spinner.MODE_DROPDOWN);
            List<String> labels = new ArrayList<>();
            for (String value : field.choices) {
                labels.add(activity.getString(PackSettingCatalog.choiceLabel(field, value)));
            }
            ArrayAdapter<String> adapter = new ArrayAdapter<>(activity, R.layout.view_form_spinner_value, labels);
            adapter.setDropDownViewResource(R.layout.view_form_spinner_option);
            spinner.setAdapter(adapter);
            spinner.setMinimumHeight(dp(48));
            spinner.setSelection(Math.max(0, field.choices.indexOf(String.valueOf(working.opt(field.key)))));
            spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                    if (updating || position != spinner.getSelectedItemPosition()) return;
                    String choice = field.choices.get(position);
                    changed(field, field.kind == PackSettingCatalog.Kind.INTEGER ? Integer.parseInt(choice) : choice);
                }
                @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
            });
            return spinner;
        }
        if (field.kind == PackSettingCatalog.Kind.SELECTION && !field.choices.isEmpty()) {
            Button pick = action(selectionSummary(field));
            pick.setOnClickListener(view -> chooseItems(field, pick));
            return pick;
        }
        EditText input = new EditText(activity, null, 0, R.style.Widget_SubHub_Input);
        input.setMinHeight(dp(48));
        com.subhub.app.util.UiIdentity.textSize(input, R.dimen.ui_text_body);
        boolean multiline = field.kind == PackSettingCatalog.Kind.SELECTION
                || "subliminal_custom_phrases".equals(field.key) || "error_popup_text".equals(field.key);
        if (multiline) {
            com.subhub.app.util.UiIdentity.inputType(input, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            input.setMinLines(3);
            input.setMaxLines(8);
            input.setHorizontallyScrolling(false);
        } else if (field.kind == PackSettingCatalog.Kind.TEXT || field.kind == PackSettingCatalog.Kind.COLOR) {
            com.subhub.app.util.UiIdentity.inputType(input, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            input.setSingleLine(true);
        } else {
            com.subhub.app.util.UiIdentity.inputType(input, InputType.TYPE_CLASS_NUMBER
                    | (field.kind == PackSettingCatalog.Kind.MONEY || field.kind == PackSettingCatalog.Kind.DECIMAL
                    || field.kind == PackSettingCatalog.Kind.RATIO ? InputType.TYPE_NUMBER_FLAG_DECIMAL : 0));
            input.setSingleLine(true);
        }
        if (field.kind == PackSettingCatalog.Kind.SELECTION) {
            input.setHint(R.string.pack_editor_one_per_line);
            input.setText(selectionText(field));
        } else {
            input.setText(field.displayText(working.opt(field.key)));
        }
        if (field.kind == PackSettingCatalog.Kind.TEXT || field.kind == PackSettingCatalog.Kind.COLOR) {
            input.setFilters(new InputFilter[] {new InputFilter.LengthFilter(
                    field.kind == PackSettingCatalog.Kind.COLOR ? 9 : field.maximumLength)});
        }
        if (field.kind == PackSettingCatalog.Kind.COLOR) input.setHint(R.string.pack_editor_color_hint);
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable value) {
                if (updating) return;
                try {
                    Object parsed;
                    if (field.kind == PackSettingCatalog.Kind.SELECTION) {
                        Set<String> lines = new LinkedHashSet<>();
                        for (String line : value.toString().split("\\R")) {
                            if (!line.trim().isEmpty()) lines.add(line.trim());
                        }
                        parsed = field.normalize(new JSONArray(lines));
                    } else parsed = field.parseText(value.toString());
                    invalidInputs.remove(field.key);
                    input.setError(null);
                    changed(field, parsed);
                } catch (IllegalArgumentException invalid) {
                    invalidInputs.add(field.key);
                    input.setError(inputError(field));
                }
            }
        });
        return input;
    }

    private String inputError(PackSettingCatalog.Field field) {
        if (field.kind == PackSettingCatalog.Kind.COLOR) return activity.getString(R.string.pack_editor_color_hint);
        if (field.kind == PackSettingCatalog.Kind.TEXT || field.kind == PackSettingCatalog.Kind.SELECTION) {
            return activity.getString(R.string.pack_editor_invalid);
        }
        return activity.getString(R.string.pack_editor_range, boundary(field, field.minimum),
                boundary(field, field.maximum));
    }

    private static String boundary(PackSettingCatalog.Field field, double value) {
        BigDecimal number = BigDecimal.valueOf(value);
        if (field.kind == PackSettingCatalog.Kind.MONEY) number = number.movePointLeft(2);
        if (field.kind == PackSettingCatalog.Kind.RATIO) number = number.movePointRight(2);
        return number.stripTrailingZeros().toPlainString();
    }

    private void chooseItems(PackSettingCatalog.Field field, Button button) {
        JSONArray current = working.optJSONArray(field.key);
        Set<String> selected = new LinkedHashSet<>();
        if (current != null) for (int index = 0; index < current.length(); index++) selected.add(current.optString(index));
        boolean bodyCategories = "enabled_categories".equals(field.key);
        List<String> visibleChoices = bodyCategories
                ? DetectionCategorySelection.choicesForDisplay(field.choices) : field.choices;
        String[] labels = new String[visibleChoices.size()];
        boolean[] checked = new boolean[labels.length];
        for (int index = 0; index < labels.length; index++) {
            String value = visibleChoices.get(index);
            labels[index] = activity.getString(PackSettingCatalog.choiceLabel(field, value));
            checked[index] = bodyCategories ? DetectionCategorySelection.isSelected(selected, value) : selected.contains(value);
        }
        com.subhub.app.util.ThemedDialogs.builder(activity).setTitle(field.label)
                .setMultiChoiceItems(labels, checked, (dialog, position, on) -> checked[position] = on)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    Set<String> choices = new LinkedHashSet<>();
                    for (int index = 0; index < checked.length; index++) {
                        if (bodyCategories) DetectionCategorySelection.setSelected(choices, visibleChoices.get(index), checked[index]);
                        else if (checked[index]) choices.add(visibleChoices.get(index));
                    }
                    changed(field, new JSONArray(choices));
                    button.setText(selectionSummary(field));
                }).show();
    }

    private String selectionSummary(PackSettingCatalog.Field field) {
        JSONArray array = working.optJSONArray(field.key);
        int count = array == null ? 0 : array.length();
        if (array != null && "enabled_categories".equals(field.key)) {
            List<String> categories = new ArrayList<>();
            for (int index = 0; index < array.length(); index++) categories.add(array.optString(index));
            count = DetectionCategorySelection.choicesForDisplay(categories).size();
        }
        return activity.getResources().getQuantityString(R.plurals.pack_editor_selection_count, count, count);
    }

    private String selectionText(PackSettingCatalog.Field field) {
        JSONArray values = working.optJSONArray(field.key);
        List<String> lines = new ArrayList<>();
        if (values != null) for (int index = 0; index < values.length(); index++) lines.add(values.optString(index));
        return String.join("\n", lines);
    }

    private void changed(PackSettingCatalog.Field field, Object value) {
        if (updating) return;
        Object normalized = field.normalize(value);
        if (String.valueOf(working.opt(field.key)).equals(String.valueOf(normalized))) return;
        put(field.key, normalized);
        if ("detection_quality".equals(field.key)) {
            int confidence = switch (String.valueOf(normalized)) {
                case "low" -> 30; case "high" -> 18; default -> 25;
            };
            put("detection_confidence_percent", confidence);
            invalidInputs.remove("detection_confidence_percent");
        }
        if ("subliminal_preset".equals(field.key) || "subliminal_advanced".equals(field.key)) {
            if (!working.optBoolean("subliminal_advanced")) presetSubliminal();
        }
        if ("popup_storm_active_preset".equals(field.key)) presetPopup();
        else if (SubHubPackSchema.POPUP.equals(section)) put("popup_storm_active_preset", "CUSTOM");
        refreshApplicability();
        refreshControls();
    }

    private void presetSubliminal() {
        long[] values = switch (working.optString("subliminal_preset", "normal")) {
            case "gentle" -> new long[] {3, 2400, 45000, 90000, 18};
            case "strict" -> new long[] {7, 1600, 15000, 40000, 20};
            case "ultra" -> new long[] {10, 1200, 8000, 25000, 21};
            default -> new long[] {5, 2000, 25000, 60000, 19};
        };
        String[] keys = {"subliminal_opacity_percent", "subliminal_visible_ms",
                "subliminal_min_interval_ms", "subliminal_max_interval_ms", "subliminal_text_size_sp"};
        for (int index = 0; index < keys.length; index++) {
            PackSettingCatalog.Field field = PackSettingCatalog.field(section, keys[index]);
            put(keys[index], field.normalize(values[index]));
            invalidInputs.remove(keys[index]);
        }
    }

    private void presetPopup() {
        String preset = working.optString("popup_storm_active_preset", "CUSTOM");
        float[] values = switch (preset) {
            case "GENTLE" -> new float[] {.5f, 1.5f, 3, 0, 30, 4};
            case "MEDIUM" -> new float[] {2, 1, 8, 0, 30, 4};
            case "INTENSE" -> new float[] {4, .7f, 12, 1, 25, 4};
            case "OVERLOAD" -> new float[] {7, .5f, 15, 1, 15, 5};
            default -> null;
        };
        if (values == null) return;
        String[] keys = {"popup_storm_spawn_rate", "popup_storm_display_duration",
                "popup_storm_max_simultaneous", "popup_storm_burst_enabled",
                "popup_storm_burst_frequency", "popup_storm_burst_duration"};
        for (int index = 0; index < keys.length; index++) {
            PackSettingCatalog.Field field = PackSettingCatalog.field(section, keys[index]);
            put(keys[index], field.normalize(field.kind == PackSettingCatalog.Kind.BOOLEAN
                    ? values[index] != 0 : values[index]));
            invalidInputs.remove(keys[index]);
        }
    }

    private void refreshControls() {
        updating = true;
        for (PackSettingCatalog.Field field : PackSettingCatalog.fields(section)) {
            View control = controls.get(field.key);
            if (control instanceof Spinner) {
                ((Spinner) control).setSelection(Math.max(0, field.choices.indexOf(String.valueOf(working.opt(field.key)))));
            } else if (control instanceof StateToggle) {
                ((StateToggle) control).setChecked(working.optBoolean(field.key));
            } else if (control instanceof Button && field.kind == PackSettingCatalog.Kind.COLOR) {
                ((Button) control).setText(field.displayText(working.opt(field.key)));
            } else if (control instanceof EditText && !control.hasFocus() && !invalidInputs.contains(field.key)) {
                ((EditText) control).setText(field.kind == PackSettingCatalog.Kind.SELECTION
                        ? selectionText(field) : field.displayText(working.opt(field.key)));
            }
        }
        updating = false;
    }

    private void refreshApplicability() {
        for (PackSettingCatalog.Field field : PackSettingCatalog.fields(section)) {
            if (field.group == R.string.pack_group_palettes) {
                String prefix = "effect_palette_" + working.optString("censor_type", "box") + "_";
                rows.get(field.key).setVisibility(field.key.startsWith(prefix) ? View.VISIBLE : View.GONE);
            }
            if (field.key.startsWith("border_gradient_")) {
                rows.get(field.key).setVisibility("gradient".equals(working.optString("border_effect"))
                        ? View.VISIBLE : View.GONE);
            }
            if ("border_color".equals(field.key)) {
                rows.get(field.key).setVisibility("gradient".equals(working.optString("border_effect"))
                        ? View.GONE : View.VISIBLE);
            }
            if (field.group == R.string.pack_group_timing && !"subliminal_advanced".equals(field.key)) {
                controls.get(field.key).setEnabled(working.optBoolean("subliminal_advanced"));
            }
        }
    }

    private void showInvalid(String key, int message) {
        PackSettingCatalog.Field field = PackSettingCatalog.field(section, key);
        String name = field == null ? activity.getString(R.string.pack_editor_settings) : activity.getString(field.label);
        Toast.makeText(activity, activity.getString(R.string.pack_editor_validation,
                name, activity.getString(message)), Toast.LENGTH_LONG).show();
        if (field != null) {
            groups.get(field.group).setVisibility(View.VISIBLE);
            View control = controls.get(key);
            if (control != null) control.requestRectangleOnScreen(
                    new android.graphics.Rect(0, 0, control.getWidth(), control.getHeight()), true);
        }
    }

    private void put(String key, Object value) {
        try { working.put(key, value); }
        catch (org.json.JSONException invalid) { throw new IllegalArgumentException(invalid); }
    }

    private LinearLayout column() {
        LinearLayout result = new LinearLayout(activity);
        result.setOrientation(LinearLayout.VERTICAL);
        result.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return result;
    }

    private TextView label(String text, boolean heading) {
        TextView result = (TextView) android.view.LayoutInflater.from(activity).inflate(
                heading ? R.layout.view_form_label : R.layout.view_form_help, null, false);
        result.setText(text);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = heading ? 0
                : activity.getResources().getDimensionPixelSize(R.dimen.ui_gap_inline);
        result.setLayoutParams(params);
        return result;
    }

    private Button action(String text) {
        Button result = new Button(activity, null, 0, R.style.Widget_SubHub_CompactOutlineButton);
        result.setText(text);
        result.setAllCaps(false);
        result.setMinHeight(dp(48));
        result.setMinimumHeight(dp(48));
        result.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return result;
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
