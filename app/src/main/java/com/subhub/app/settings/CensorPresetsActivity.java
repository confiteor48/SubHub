package com.subhub.app.settings;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.subhub.app.R;
import com.subhub.app.capture.export.ExportSettings;
import com.subhub.app.security.ControllerEditMode;
import com.subhub.app.security.ControllerPinGate;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.util.PreferencePage;
import com.subhub.app.util.PrimaryHeader;
import com.subhub.app.util.SelectionGrid;
import com.subhub.app.util.ThemedDialogs;
import com.subhub.app.util.UiIdentity;
import org.json.JSONException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Built-in and saved appearance looks, applied only to the editor that opened this page. */
public final class CensorPresetsActivity extends PreferencePage {
    public static final String EXTRA_EXPORT = "export_editor";
    private CensorLookStore store;
    private SharedPreferences target;
    private int scrollPosition;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        store = new CensorLookStore(this);
        target = getIntent().getBooleanExtra(EXTRA_EXPORT, false)
                ? ExportSettings.preferences(this) : new SettingsRepository(this).preferences();
        scrollPosition = state == null ? 0 : state.getInt("preset_scroll", 0);
    }

    @Override protected void onResume() { super.onResume(); render(); }

    private void render() {
        if (isFinishing() || isDestroyed()) return;
        if (page != null && page.getParent() instanceof android.widget.ScrollView)
            scrollPosition = ((android.widget.ScrollView) page.getParent()).getScrollY();
        page(R.string.censor_presets_title);
        PrimaryHeader.bindSecondary(page, R.string.censor_presets_title, true);
        TextView edit = PrimaryHeader.editLockButton(page);
        ControllerEditMode.renderButton(this, edit);
        edit.setOnClickListener(view -> {
            if (ControllerPinManager.isSessionUnlocked()) {
                ControllerPinManager.enterSubMode();
                render();
            } else ControllerPinGate.unlock(this, this::render, false);
        });
        if (getIntent().getBooleanExtra(EXTRA_EXPORT, false))
            fieldLabel(page, getString(R.string.export_appearance_title));
        Button save = button(page, getString(R.string.censor_preset_save), () -> nameDialog(null));
        save.setTag("preset:save");
        save.setEnabled(editing());
        fieldLabel(page, getString(R.string.censor_presets_builtin));
        addGrid(CensorLookCatalog.all(), false);
        fieldLabel(page, getString(R.string.censor_presets_saved));
        try {
            List<CensorLook> saved = store.saved();
            if (saved.isEmpty()) fieldHelp(page, getString(R.string.censor_presets_empty));
            else addGrid(saved, true);
        } catch (IllegalStateException unreadable) { fieldHelp(page, unreadable.getMessage()); }
        android.widget.ScrollView scroll = (android.widget.ScrollView) page.getParent();
        scroll.post(() -> scroll.scrollTo(0, scrollPosition));
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (page != null && page.getParent() instanceof android.widget.ScrollView)
            state.putInt("preset_scroll", ((android.widget.ScrollView) page.getParent()).getScrollY());
    }

    private boolean editing() { return ControllerPinManager.isSessionUnlocked(); }

    private void addGrid(List<CensorLook> looks, boolean saved) {
        SelectionGrid grid = new SelectionGrid(this, null);
        page.addView(grid, new LinearLayout.LayoutParams(-1, -2));
        Map<String, Object> current;
        try { current = new CensorLook("current", "Current", target.getAll()).values(); }
        catch (IllegalArgumentException invalid) { current = Collections.emptyMap(); }
        for (CensorLook look : looks) {
            LinearLayout tile = new LinearLayout(this);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setBackgroundResource(R.drawable.bg_style_tile);
            int inset = dp(8);
            tile.setPadding(inset, inset, inset, inset);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2);
            params.setMargins(dp(3), dp(3), dp(3), dp(3));
            grid.addView(tile, params);
            CensorAppearance appearance;
            try {
                appearance = SettingsRepository.forPreferences(
                        ExportSettings.frozen(look.encode().getJSONObject("settings"))).loadAppearance();
            } catch (JSONException invalid) { throw new IllegalStateException("Invalid preset", invalid); }
            CensorPresetPreview preview = new CensorPresetPreview(this, appearance);
            tile.addView(preview, new LinearLayout.LayoutParams(-1, dp(92)));
            Button apply = (Button) getLayoutInflater().inflate(R.layout.view_ux_action, tile, false);
            apply.setText(look.name());
            apply.setTag("look:" + look.id());
            boolean selected = current.equals(look.values());
            if (!saved) {
                selected = true;
                for (Map.Entry<String, Object> value : look.values().entrySet())
                    if (!value.getValue().equals(current.get(value.getKey()))) { selected = false; break; }
            }
            apply.setBackgroundResource(R.drawable.bg_segment_button);
            apply.setSelected(selected);
            apply.setEnabled(editing());
            tile.addView(apply);
            apply.setOnClickListener(view -> {
                if (!editing()) return;
                try { CensorLookStore.apply(look, target); render(); }
                catch (IllegalStateException invalid) { notice(invalid.getMessage()); }
            });
            if (saved) {
                Button manage = button(tile, getString(R.string.censor_preset_edit), () -> manage(look));
                manage.setContentDescription(getString(R.string.censor_preset_edit_name, look.name()));
                manage.setEnabled(editing());
            }
        }
    }

    private void manage(CensorLook look) {
        if (!editing()) return;
        String[] actions = {getString(R.string.censor_preset_rename),
                getString(R.string.censor_preset_update), getString(R.string.censor_preset_delete)};
        ThemedDialogs.builder(this).setTitle(look.name()).setItems(actions, (dialog, which) -> {
            if (!editing()) return;
            if (which == 0) { nameDialog(look); return; }
            try {
                if (which == 1) store.update(look.id(), target);
                else store.delete(look.id());
                render();
            } catch (IllegalStateException | IllegalArgumentException invalid) { notice(invalid.getMessage()); }
        }).show();
    }

    private void nameDialog(CensorLook rename) {
        if (!editing()) return;
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        int margin = getResources().getDimensionPixelSize(R.dimen.ui_gap_group);
        fields.setPadding(margin, margin, margin, margin);
        EditText name = (EditText) getLayoutInflater().inflate(R.layout.view_preference_input, fields, false);
        UiIdentity.inputType(name, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        name.setSingleLine();
        name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(CensorLook.MAX_NAME)});
        name.setHint(R.string.censor_preset_name);
        name.setContentDescription(getString(R.string.censor_preset_name));
        if (rename != null) name.setText(rename.name());
        fields.addView(name);
        androidx.appcompat.app.AlertDialog dialog = ThemedDialogs.builder(this)
                .setTitle(rename == null ? R.string.censor_preset_save : R.string.censor_preset_rename)
                .setView(fields).setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.censor_preset_save_action, null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    if (!editing()) { dialog.dismiss(); return; }
                    try {
                        if (rename == null) store.save(name.getText().toString(), target);
                        else store.rename(rename.id(), name.getText().toString());
                        dialog.dismiss(); render();
                    } catch (IllegalArgumentException invalid) { name.setError(invalid.getMessage()); }
                    catch (IllegalStateException invalid) { notice(invalid.getMessage()); }
                }));
        dialog.show();
    }
}
