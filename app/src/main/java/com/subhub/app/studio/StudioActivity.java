package com.subhub.app.studio;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import com.subhub.app.R;
import com.subhub.app.databinding.ActivityStudioBinding;
import com.subhub.app.pack.PackSettingCatalog;
import com.subhub.app.pack.SubHubPack;
import com.subhub.app.pack.SubHubPackArchive;
import com.subhub.app.pack.SubHubPackManager;
import com.subhub.app.pack.SubHubPackSchema;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.util.PrimaryHeader;
import com.subhub.app.util.StateToggle;
import com.subhub.app.util.SubHubNavigation;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Always-available creator, library, importer, previewer, and share surface for arrangements. */
public final class StudioActivity extends AppCompatActivity {
    public static final String EXTRA_FROM_RITUALS = "studio_from_rituals";
    public static final String EXTRA_IMPORT_PACK = "studio_import_pack";
    private static final long MAX_IMAGE_BYTES = 25L * 1024L * 1024L;
    private static final String[] SECTION_ORDER = {
            SubHubPackSchema.MODULES, SubHubPackSchema.CENSOR, SubHubPackSchema.LIMITS,
            SubHubPackSchema.WALLET, SubHubPackSchema.SUBLIMINAL, SubHubPackSchema.POPUP
    };
    private static final long[] DURATION_VALUES = {
            0L, 3_600_000L, 86_400_000L, 604_800_000L, 2_592_000_000L, -1L
    };

    private ActivityStudioBinding binding;
    private SubHubPackManager manager;
    private StudioPayPalTransfer payPalTransfer;
    private SubHubPack draft;
    private boolean suppressEvents;
    private String assetTarget = "censor";
    private final Handler editorHandler = new Handler(Looper.getMainLooper());
    private ExecutorService storage;
    private StudioState retained;
    private final Runnable pendingSave = this::flushDraftSave;
    private long saveRevision;
    private boolean actionBusy;
    private long libraryRequest;
    private long draftsRequest;
    private int editorStep;
    private long assetRequest;
    private final List<Button> stepButtons = new ArrayList<>();
    private final Map<View, Boolean> busyStates = new LinkedHashMap<>();
    private final Map<String, StateToggle> includes = new LinkedHashMap<>();
    private final Map<String, TextView> sectionSummaries = new LinkedHashMap<>();
    private final Map<String, JSONObject> sectionDrafts = new LinkedHashMap<>();

    private final ActivityResultLauncher<String[]> importPicker = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), this::importPack);
    private final ActivityResultLauncher<String[]> imagePicker = registerForActivityResult(
            new ActivityResultContracts.OpenMultipleDocuments(), this::addImages);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityStudioBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        retained = new ViewModelProvider(this).get(StudioState.class);
        storage = retained.storage;
        PrimaryHeader.bindSecondary(binding.getRoot(), R.string.studio_title, false);
        PrimaryHeader.backButton(binding.getRoot()).setVisibility(View.VISIBLE);
        PrimaryHeader.backButton(binding.getRoot()).setOnClickListener(view -> {
                            if (!getIntent().getBooleanExtra(EXTRA_FROM_RITUALS, false))
                                startActivity(new Intent(this, com.subhub.app.settings.GlobalSettingsActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
            finish();
        });
        manager = new SubHubPackManager(this);
        payPalTransfer = new StudioPayPalTransfer(this, manager);
        SubHubNavigation.bind(this, binding.getRoot(),
                getIntent().getBooleanExtra(EXTRA_FROM_RITUALS, false)
                        ? SubHubNavigation.Screen.ATMOSPHERE
                        : SubHubNavigation.Screen.SETTINGS);
        setupTabs();
        setupEditor();
        binding.buttonImport.setOnClickListener(view -> importPicker.launch(
                new String[]{SubHubPackArchive.MIME_TYPE, "application/octet-stream", "*/*"}));
        binding.buttonBlank.setOnClickListener(view -> openDraft(manager.createBlank()));
        binding.buttonCapture.setOnClickListener(view -> storageAction(manager::captureCurrent, this::openDraft));
        renderLibrary();
        renderDrafts();
        if (savedInstanceState == null && getIntent().getBooleanExtra(EXTRA_IMPORT_PACK, false)) {
            getIntent().removeExtra(EXTRA_IMPORT_PACK);
            binding.getRoot()
                    .post(
                            () ->
                                    importPicker.launch(
                                            new String[] {
                                                SubHubPackArchive.MIME_TYPE,
                                                "application/octet-stream",
                                                "*/*"
                                            }));
        }
        if (savedInstanceState != null) {
            assetTarget = savedInstanceState.getString("assetTarget", "censor");
            String id = savedInstanceState.getString("draftId");
            int step = savedInstanceState.getInt("editorStep", 0);
            int panel = savedInstanceState.getInt("panel", R.id.library_panel);
            if (id != null) storageAction(() -> retained.draft != null
                    && id.equals(retained.draft.getId()) ? retained.draft.snapshot()
                    : manager.findDraft(id), restored -> {
                if (restored != null) {
                    openDraft(restored);
                    sectionDrafts.putAll(retained.sections);
                    showEditorStep(step);
                    View selected = binding.getRoot().findViewById(panel);
                    if (selected != null) showPanel(selected);
                }
            });
            else {
                View selected = binding.getRoot().findViewById(panel);
                if (selected != null) showPanel(selected);
            }
        }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        flushDraftSave();
        // A queued image import publishes its newer snapshot on the shared storage executor.
        // Do not overwrite that result with the activity's temporarily older draft.
        if (!actionBusy) retained.draft = draft == null ? null : draft.snapshot();
        retained.sections.clear();
        retained.sections.putAll(sectionDrafts);
        if (draft != null) state.putString("draftId", draft.getId());
        state.putString("assetTarget", assetTarget);
        state.putInt("editorStep", editorStep);
        for (View panel : new View[] {binding.libraryPanel, binding.draftsPanel,
                binding.createPanel, binding.editorPanel}) {
            if (panel.getVisibility() == View.VISIBLE) state.putInt("panel", panel.getId());
        }
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (binding != null) {
            SubHubNavigation.bind(this, binding.getRoot(),
                    getIntent().getBooleanExtra(EXTRA_FROM_RITUALS, false)
                            ? SubHubNavigation.Screen.ATMOSPHERE
                            : SubHubNavigation.Screen.SETTINGS);
            applySpaceVisibility();
        }
    }

    @Override protected void onPause() {
        if (payPalTransfer != null) payPalTransfer.pause();
        editorHandler.removeCallbacks(pendingSave);
        flushDraftSave();
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (payPalTransfer != null) payPalTransfer.destroy();
        editorHandler.removeCallbacks(pendingSave);
        super.onDestroy();
    }

    /** Rotation keeps draft bytes and serial I/O together, rather than racing old/new saves. */
    public static final class StudioState extends ViewModel {
        final ExecutorService storage = Executors.newSingleThreadExecutor();
        final Map<String, JSONObject> sections = new LinkedHashMap<>();
        volatile SubHubPack draft;
        @Override protected void onCleared() { storage.shutdown(); }
    }

    private void setupTabs() {
        binding.tabLibrary.setOnClickListener(view -> showPanel(binding.libraryPanel));
        binding.tabDrafts.setOnClickListener(view -> {
            renderDrafts();
            showPanel(binding.draftsPanel);
        });
        binding.tabCreate.setOnClickListener(view -> showPanel(binding.createPanel));
        updateTabSelection(binding.libraryPanel);
        applySpaceVisibility();
    }

    private void applySpaceVisibility() {
        // Creating/editing a draft never changes live configuration; only Apply requires Dom.
        binding.tabDrafts.setVisibility(View.VISIBLE);
        binding.tabCreate.setVisibility(View.VISIBLE);
        updateSectionSummaries();
    }

    private void showPanel(View panel) {
        binding.libraryPanel.setVisibility(panel == binding.libraryPanel ? View.VISIBLE : View.GONE);
        binding.draftsPanel.setVisibility(panel == binding.draftsPanel ? View.VISIBLE : View.GONE);
        binding.createPanel.setVisibility(panel == binding.createPanel ? View.VISIBLE : View.GONE);
        binding.editorPanel.setVisibility(panel == binding.editorPanel ? View.VISIBLE : View.GONE);
        updateTabSelection(panel);
        binding.studioScroll.smoothScrollTo(0, 0);
    }

    private void updateTabSelection(View panel) {
        binding.tabLibrary.setSelected(panel == binding.libraryPanel);
        binding.tabDrafts.setSelected(panel == binding.draftsPanel);
        binding.tabCreate.setSelected(panel == binding.createPanel || panel == binding.editorPanel);
    }

    private void setupEditor() {
        String[] durations = {
                getString(R.string.studio_recommend_none), getString(R.string.studio_recommend_1h),
                getString(R.string.studio_recommend_24h), getString(R.string.studio_recommend_7d),
                getString(R.string.studio_recommend_30d),
                getString(R.string.studio_recommend_permanent)
        };
        ArrayAdapter<String> durationAdapter = new ArrayAdapter<>(this, R.layout.view_form_spinner_value, durations);
        durationAdapter.setDropDownViewResource(R.layout.view_form_spinner_option);
        binding.recommendDuration.setAdapter(durationAdapter);
        addMetadataWatcher(binding.packName);
        addMetadataWatcher(binding.packAuthor);
        addMetadataWatcher(binding.packDescription);
        addMetadataWatcher(binding.packVersion);
        binding.recommendHardcore.setOnCheckedChangeListener((button, checked) -> saveEditor());
        binding.recommendDuration.setOnItemSelectedListener(new SimpleItemSelectedListener(this::saveEditor));
        binding.buttonCensorAssets.setOnClickListener(view -> pickImages("censor"));
        binding.buttonPopupAssets.setOnClickListener(view -> pickImages("popup"));
        binding.buttonCoverAsset.setOnClickListener(view -> pickImages("cover"));
        binding.buttonPublish.setOnClickListener(view -> publishDraft());
        binding.buttonExport.setOnClickListener(view -> share(draft));
        binding.buttonDeleteDraft.setOnClickListener(view -> deleteDraft());
        int[] titles = {R.string.pack_editor_step_details, R.string.pack_editor_step_features,
                R.string.pack_editor_step_images, R.string.pack_editor_step_review};
        for (int row = 0; row < 2; row++) {
            LinearLayout actions = actionRow();
            for (int column = 0; column < 2; column++) {
                int step = row * 2 + column;
                Button button = outlineButton(getString(titles[step]));
                button.setTag("pack_step:" + step);
                button.setOnClickListener(view -> showEditorStep(step));
                actions.addView(button, weighted());
                stepButtons.add(button);
            }
            binding.editorSteps.addView(actions);
        }
        binding.editorBack.setOnClickListener(view -> showEditorStep(editorStep - 1));
        binding.editorNext.setOnClickListener(view -> showEditorStep(editorStep + 1));
        showEditorStep(0);
        buildSectionRows();
    }

    private void showEditorStep(int step) {
        editorStep = Math.max(0, Math.min(3, step));
        binding.detailsStep.setVisibility(editorStep == 0 ? View.VISIBLE : View.GONE);
        binding.featuresStep.setVisibility(editorStep == 1 ? View.VISIBLE : View.GONE);
        binding.imagesStep.setVisibility(editorStep == 2 ? View.VISIBLE : View.GONE);
        binding.recommendationsStep.setVisibility(editorStep == 3 ? View.VISIBLE : View.GONE);
        binding.reviewStep.setVisibility(editorStep == 3 ? View.VISIBLE : View.GONE);
        binding.buttonPublish.setVisibility(editorStep == 3 ? View.VISIBLE : View.GONE);
        binding.buttonExport.setVisibility(editorStep == 3 ? View.VISIBLE : View.GONE);
        binding.editorBack.setEnabled(editorStep > 0 && !actionBusy);
        binding.editorNext.setEnabled(editorStep < 3 && !actionBusy);
        for (int index = 0; index < stepButtons.size(); index++) {
            stepButtons.get(index).setSelected(index == editorStep);
        }
        binding.studioScroll.smoothScrollTo(0, 0);
    }

    private void buildSectionRows() {
        binding.sectionList.removeAllViews();
        includes.clear();
        sectionSummaries.clear();
        for (String section : SECTION_ORDER) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(12), dp(8), dp(12), dp(10));
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cardParams.topMargin = dp(8);
            card.setLayoutParams(cardParams);
            card.setBackgroundResource(R.drawable.bg_sub_module_card);

            StateToggle include = new StateToggle(this);
            include.setText(getString(R.string.pack_editor_include_section, sectionTitle(section)));
            com.subhub.app.util.UiIdentity.textSize(include, R.dimen.ui_text_body);
            include.setMinHeight(dp(48));
            include.setTag("pack_include:" + section);
            card.addView(include);
            TextView summary = label("", false);
            card.addView(summary);
            sectionSummaries.put(section, summary);
            Button configure = outlineButton(getString(R.string.pack_editor_settings));
            configure.setTag("pack_configure:" + section);
            configure.setOnClickListener(view -> editSection(section));
            card.addView(configure, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            Button copy = outlineButton(getString(R.string.pack_editor_current));
            copy.setTag("pack_copy:" + section);
            copy.setOnClickListener(view -> confirmReplaceSection(section, false));
            card.addView(copy);
            Button defaults = outlineButton(getString(R.string.pack_editor_defaults));
            defaults.setTag("pack_defaults:" + section);
            defaults.setOnClickListener(view -> confirmReplaceSection(section, true));
            card.addView(defaults);
            if (SubHubPackSchema.WALLET.equals(section)) {
                Button attach = outlineButton(getString(R.string.pack_editor_attach_paypal));
                attach.setTag("pack_attach_paypal");
                attach.setOnClickListener(view -> payPalTransfer.attach(draft, this::saveEditor));
                card.addView(attach);
                Button remove = outlineButton(getString(R.string.pack_editor_remove_paypal));
                remove.setTag("pack_remove_paypal");
                remove.setOnClickListener(view -> {
                    if (draft == null || !ControllerPinManager.isDomModeActive()) return;
                    com.subhub.app.util.ThemedDialogs.builder(this).setTitle(R.string.pack_editor_remove_paypal)
                            .setMessage(R.string.pack_editor_remove_paypal_body)
                            .setNegativeButton(android.R.string.cancel, null)
                            .setPositiveButton(R.string.pack_editor_remove_paypal, (dialog, which) -> {
                                payPalTransfer.pause();
                                try { draft.setEncryptedPayPal(null); saveEditor(); }
                                catch (java.security.GeneralSecurityException ignored) { }
                            }).show();
                });
                card.addView(remove);
            }
            binding.sectionList.addView(card);
            includes.put(section, include);
            include.setOnCheckedChangeListener((button, checked) -> {
                if (suppressEvents || draft == null) return;
                if (!checked && SubHubPackSchema.WALLET.equals(section) && draft.hasEncryptedPayPal()) {
                    com.subhub.app.util.ThemedDialogs.builder(this).setTitle(R.string.pack_editor_remove_paypal)
                            .setMessage(R.string.pack_editor_remove_paypal_body)
                            .setNegativeButton(android.R.string.cancel, (dialog, which) -> restoreIncludedWallet())
                            .setOnCancelListener(dialog -> restoreIncludedWallet())
                            .setPositiveButton(android.R.string.ok, (dialog, which) -> setSectionIncluded(section, false))
                            .show();
                    return;
                }
                setSectionIncluded(section, checked);
            });
        }
    }

    private void restoreIncludedWallet() {
        suppressEvents = true;
        includes.get(SubHubPackSchema.WALLET).setChecked(true);
        suppressEvents = false;
    }

    private void setSectionIncluded(String section, boolean included) {
        if (draft == null) return;
        if (included) {
            JSONObject value = sectionDrafts.get(section);
            if (value == null) value = PackSettingCatalog.defaults(section);
            draft.setSection(section, value);
            sectionDrafts.put(section, value);
        } else {
            JSONObject existing = draft.getSection(section);
            if (existing != null) sectionDrafts.put(section, existing);
            if (SubHubPackSchema.WALLET.equals(section)) payPalTransfer.pause();
            draft.setSection(section, null);
        }
        saveEditor();
    }

    private void editSection(String section) {
        if (draft == null) return;
        String id = draft.getId();
        JSONObject existing = draft.getSection(section);
        if (existing == null) existing = sectionDrafts.get(section);
        PackSectionEditor.show(this, section, sectionTitle(section), existing, values -> {
            if (draft == null || !id.equals(draft.getId())) return;
            sectionDrafts.put(section, values);
            draft.setSection(section, values);
            suppressEvents = true;
            includes.get(section).setChecked(true);
            suppressEvents = false;
            saveEditor();
        });
    }

    private void confirmReplaceSection(String section, boolean defaults) {
        if (draft == null) return;
        com.subhub.app.util.ThemedDialogs.builder(this).setTitle(R.string.pack_editor_reset_title)
                .setMessage(R.string.pack_editor_reset_body)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(defaults ? R.string.pack_editor_defaults : R.string.pack_editor_current,
                        (dialog, which) -> {
                            try {
                                JSONObject values = defaults ? PackSettingCatalog.defaults(section)
                                        : manager.captureSection(section);
                                sectionDrafts.put(section, values);
                                draft.setSection(section, values);
                                suppressEvents = true;
                                includes.get(section).setChecked(true);
                                suppressEvents = false;
                                saveEditor();
                            } catch (IllegalArgumentException invalid) {
                                toast(getString(R.string.pack_apply_invalid));
                            }
                        }).show();
    }

    private void updateSectionSummaries() {
        if (draft == null) return;
        for (String section : SECTION_ORDER) {
            int count = PackSettingCatalog.fields(section).size();
            sectionSummaries.get(section).setText(getResources().getQuantityString(
                    R.plurals.pack_editor_setting_count, count, count));
        }
        View attach = binding.sectionList.findViewWithTag("pack_attach_paypal");
        View remove = binding.sectionList.findViewWithTag("pack_remove_paypal");
        boolean walletIncluded = draft.getIncludedSections().contains(SubHubPackSchema.WALLET);
        if (attach != null) attach.setEnabled(walletIncluded && ControllerPinManager.isDomModeActive());
        if (remove != null) {
            remove.setVisibility(draft.hasEncryptedPayPal() ? View.VISIBLE : View.GONE);
            remove.setEnabled(ControllerPinManager.isDomModeActive());
        }
    }

    private void openDraft(SubHubPack value) {
        flushDraftSave();
        if (payPalTransfer != null) payPalTransfer.pause();
        draft = value;
        sectionDrafts.clear();
        suppressEvents = true;
        binding.packName.setText(value.getName());
        binding.packAuthor.setText(value.getAuthor());
        binding.packDescription.setText(value.getDescription());
        binding.packVersion.setText(value.getPackVersion());
        for (String section : SECTION_ORDER) {
            boolean included = value.getIncludedSections().contains(section);
            includes.get(section).setChecked(included);
            includes.get(section).setEnabled(true);
            if (included) sectionDrafts.put(section, value.getSection(section));
        }
        JSONObject recommendations = value.getRecommendations();
        binding.recommendHardcore.setChecked(recommendations.optBoolean("hardcoreSuggested", false));
        long duration = recommendations.optLong("serviceDurationMillis", 0L);
        int index = 0;
        for (int item = 0; item < DURATION_VALUES.length; item++) {
            if (DURATION_VALUES[item] == duration) index = item;
        }
        binding.recommendDuration.setSelection(index);
        suppressEvents = false;
        updatePreview();
        autosave();
        showPanel(binding.editorPanel);
        showEditorStep(0);
        renderAssets();
    }

    private void saveEditor() {
        if (suppressEvents || draft == null) return;
        draft.setMetadata(binding.packName.getText().toString(),
                binding.packAuthor.getText().toString(), binding.packDescription.getText().toString(),
                binding.packVersion.getText().toString());
        JSONObject recommendations = new JSONObject();
        try {
            recommendations.put("hardcoreSuggested", binding.recommendHardcore.isChecked());
            recommendations.put("serviceDurationMillis",
                    DURATION_VALUES[binding.recommendDuration.getSelectedItemPosition()]);
        } catch (Exception ignored) {}
        draft.setRecommendations(recommendations);
        autosave();
        updatePreview();
    }

    private void autosave() {
        if (draft == null) return;
        saveRevision++;
        binding.draftSaveStatus.setText(R.string.pack_editor_saving);
        editorHandler.removeCallbacks(pendingSave);
        editorHandler.postDelayed(pendingSave, 400L);
    }

    private void flushDraftSave() {
        editorHandler.removeCallbacks(pendingSave);
        if (draft == null || actionBusy || storage.isShutdown()) return;
        SubHubPack snapshot = draft.snapshot();
        long revision = saveRevision;
        storage.execute(() -> {
            boolean saved = true;
            try { manager.saveDraft(snapshot); }
            catch (IOException error) { saved = false; }
            boolean success = saved;
            editorHandler.post(() -> {
                if (isDestroyed() || draft == null || !draft.getId().equals(snapshot.getId())
                        || saveRevision != revision) return;
                binding.draftSaveStatus.setText(success ? R.string.pack_editor_saved : R.string.pack_editor_save_failed);
            });
        });
    }

    private <T> void storageAction(Callable<T> operation, Consumer<T> completed) {
        if (actionBusy || storage.isShutdown()) return;
        flushDraftSave();
        actionBusy = true;
        updateActionState();
        storage.execute(() -> {
            T value;
            try { value = operation.call(); }
            catch (Exception error) {
                editorHandler.post(() -> {
                    if (isDestroyed()) return;
                    actionBusy = false;
                    updateActionState();
                    toast(getString(error instanceof IllegalArgumentException
                            ? R.string.pack_apply_invalid : R.string.pack_apply_storage));
                });
                return;
            }
            editorHandler.post(() -> {
                if (isDestroyed()) return;
                actionBusy = false;
                updateActionState();
                completed.accept(value);
            });
        });
    }

    private void updateActionState() {
        if (actionBusy) rememberAndDisable(binding.getRoot());
        else {
            for (Map.Entry<View, Boolean> entry : busyStates.entrySet()) entry.getKey().setEnabled(entry.getValue());
            busyStates.clear();
            showEditorStep(editorStep);
            updateSectionSummaries();
        }
    }

    private void rememberAndDisable(View view) {
        if (view instanceof Button || view instanceof android.widget.EditText
                || view instanceof android.widget.Spinner) {
            busyStates.putIfAbsent(view, view.isEnabled());
            view.setEnabled(false);
        }
        if (view instanceof ViewGroup) for (int index = 0; index < ((ViewGroup) view).getChildCount(); index++) {
            rememberAndDisable(((ViewGroup) view).getChildAt(index));
        }
    }

    private void updatePreview() {
        if (draft == null) return;
        updateSectionSummaries();
        int censorAssets = 0;
        int popupAssets = 0;
        int coverAssets = 0;
        for (String path : draft.getAssetPaths()) {
            if (path.startsWith("assets/censor/")) censorAssets++;
            if (path.startsWith("assets/popup/")) popupAssets++;
            if (path.startsWith("assets/cover/")) coverAssets++;
        }
        binding.assetSummary.setText(censorAssets == 0 && popupAssets == 0 && coverAssets == 0
                ? getString(R.string.studio_assets_empty)
                : getString(R.string.pack_editor_assets_summary, censorAssets, popupAssets, coverAssets));
        String creator = draft.getAuthor().isBlank() ? getString(R.string.pack_editor_private)
                : getString(R.string.pack_editor_by_author, draft.getAuthor());
        binding.previewText.setText(draft.getName() + " · v" + draft.getPackVersion() + "\n"
                + creator + "\n" + getResources().getQuantityString(R.plurals.pack_editor_section_count,
                        draft.getIncludedSections().size(), draft.getIncludedSections().size())
                + "\n" + (draft.hasEncryptedPayPal()
                ? getString(R.string.pack_editor_paypal_attached)
                : getString(R.string.studio_no_secrets)));
    }

    private void renderLibrary() {
        binding.libraryList.removeAllViews();
        binding.libraryEmpty.setText(R.string.pack_editor_loading);
        binding.libraryEmpty.setVisibility(View.VISIBLE);
        long request = ++libraryRequest;
        storage.execute(() -> {
            List<SubHubPackManager.Record> records = manager.listLibrary();
            editorHandler.post(() -> {
                if (isDestroyed() || request != libraryRequest) return;
                binding.libraryEmpty.setText(R.string.studio_library_empty);
                binding.libraryEmpty.setVisibility(records.isEmpty() ? View.VISIBLE : View.GONE);
                for (SubHubPackManager.Record record : records) addLibraryCard(record);
            });
        });
    }

    private void renderDrafts() {
        flushDraftSave();
        binding.draftsList.removeAllViews();
        binding.draftsEmpty.setText(R.string.pack_editor_loading);
        binding.draftsEmpty.setVisibility(View.VISIBLE);
        long request = ++draftsRequest;
        storage.execute(() -> {
            List<SubHubPackManager.Record> records = manager.listDrafts();
            editorHandler.post(() -> {
                if (isDestroyed() || request != draftsRequest) return;
                binding.draftsEmpty.setText(R.string.studio_drafts_empty);
                binding.draftsEmpty.setVisibility(records.isEmpty() ? View.VISIBLE : View.GONE);
                for (SubHubPackManager.Record record : records) addDraftCard(record);
            });
        });
    }

    private void loadRecord(SubHubPackManager.Record record, Consumer<SubHubPack> completed) {
        storageAction(() -> {
            SubHubPack loaded = record.draft ? manager.findDraft(record.pack.getId())
                    : manager.findLibrary(record.pack.getId());
            if (loaded == null) throw new IOException("Stored pack could not be validated");
            return loaded;
        }, completed);
    }

    private void addDraftCard(SubHubPackManager.Record record) {
        LinearLayout card = packCard(record.pack,
                getResources().getQuantityString(R.plurals.pack_editor_section_count,
                        record.pack.getIncludedSections().size(), record.pack.getIncludedSections().size()));
        LinearLayout actions = actionRow();
        Button edit = outlineButton(getString(R.string.studio_edit));
        edit.setOnClickListener(view -> loadRecord(record, this::openDraft));
        Button duplicate = outlineButton(getString(R.string.studio_duplicate));
        duplicate.setOnClickListener(view -> loadRecord(record, this::duplicatePack));
        Button share = outlineButton(getString(R.string.studio_share));
        share.setOnClickListener(view -> loadRecord(record, this::share));
        actions.addView(edit, weighted()); actions.addView(duplicate, weighted());
        actions.addView(share, weighted());
        card.addView(actions);
        binding.draftsList.addView(card);
    }

    private void addLibraryCard(SubHubPackManager.Record record) {
        String detail = getResources().getQuantityString(R.plurals.pack_editor_section_count,
                record.pack.getIncludedSections().size(), record.pack.getIncludedSections().size())
                + " · " + getResources().getQuantityString(R.plurals.pack_editor_image_count,
                        record.assetCount, record.assetCount);
        LinearLayout card = packCard(record.pack, detail);
        if (record.active) {
            TextView active = label(getString(R.string.studio_active), true);
            active.setTextColor(getColor(R.color.accent));
            card.addView(active);
        }
        LinearLayout actions = actionRow();
        Button apply = outlineButton(record.active
                ? getString(R.string.studio_deactivate) : getString(R.string.studio_apply));
        apply.setTag("pack_apply:" + record.pack.getId());
        apply.setOnClickListener(view -> {
            if (!ControllerPinManager.isDomModeActive()) {
                toast(getString(R.string.studio_unlock_required));
            } else if (record.active) {
                com.subhub.app.util.ThemedDialogs.builder(this).setTitle(R.string.pack_editor_restore_title)
                        .setMessage(R.string.pack_editor_restore_body)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(android.R.string.ok, (dialog, which) -> storageAction(
                                manager::deactivate, restored -> {
                                    if (!restored) toast(manager.failureMessage());
                                    renderLibrary();
                                })).show();
            } else loadRecord(record, this::review);
        });
        Button share = outlineButton(getString(R.string.studio_share));
        share.setOnClickListener(view -> loadRecord(record, this::share));
        Button duplicate = outlineButton(getString(R.string.studio_duplicate));
        duplicate.setOnClickListener(view -> loadRecord(record, this::duplicatePack));
        Button delete = outlineButton(getString(R.string.studio_delete));
        delete.setOnClickListener(view -> com.subhub.app.util.ThemedDialogs.builder(this)
                .setTitle(R.string.studio_delete_title)
                .setMessage(record.pack.getName())
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.studio_delete, (dialog, which) -> {
                    storageAction(() -> manager.deleteLibrary(record.pack.getId()), deleted -> {
                        toast(getString(deleted ? R.string.studio_deleted : R.string.studio_unlock_required));
                        renderLibrary();
                    });
                }).show());
        actions.addView(apply, weighted()); actions.addView(share, weighted());
        card.addView(actions);
        LinearLayout secondary = actionRow();
        secondary.addView(duplicate, weighted()); secondary.addView(delete, weighted());
        card.addView(secondary);
        binding.libraryList.addView(card);
    }


    private void review(SubHubPack pack) {
        List<String> included = new ArrayList<>(pack.getIncludedSections());
        if (included.isEmpty()) { toast(getString(R.string.studio_no_sections)); return; }
        boolean[] selected = new boolean[included.size()];
        java.util.Arrays.fill(selected, true);
        CharSequence[] labels = new CharSequence[included.size()];
        for (int index = 0; index < labels.length; index++) labels[index] = sectionTitle(included.get(index));
        com.subhub.app.util.ThemedDialogs.builder(this).setTitle(R.string.studio_review_title)
                .setMultiChoiceItems(labels, selected, (dialog, which, checked) -> selected[which] = checked)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.studio_apply, (dialog, which) -> {
                    Set<String> sections = new LinkedHashSet<>();
                    for (int index = 0; index < included.size(); index++) {
                        if (selected[index]) sections.add(included.get(index));
                    }
                    showDiff(pack, sections);
                }).show();
    }

    private void showDiff(SubHubPack pack, Set<String> sections) {
        if (sections.isEmpty()) { toast(getString(R.string.studio_no_sections)); return; }
        String message = String.join("\n\n", manager.diff(pack, sections));
        com.subhub.app.util.ThemedDialogs.builder(this).setTitle(pack.getName()).setMessage(message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.studio_apply_confirm, (dialog, which) -> {
                    if (pack.hasEncryptedPayPal() && sections.contains(SubHubPackSchema.WALLET)) {
                        payPalTransfer.activate(pack, sections, this::renderLibrary);
                        return;
                    }
                    storageAction(() -> manager.activate(pack, sections), applied -> {
                        toast(applied ? getString(R.string.studio_applied) : manager.failureMessage());
                        renderLibrary();
                    });
                }).show();
    }

    private void duplicatePack(SubHubPack source) {
        if (source.hasEncryptedPayPal()) {
            com.subhub.app.util.ThemedDialogs.builder(this).setTitle(R.string.pack_editor_duplicate_title)
                    .setMessage(R.string.pack_editor_duplicate_body)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.studio_duplicate, (d, w) -> {
                        openDraft(source.duplicate());
                    }).show();
        } else openDraft(source.duplicate());
    }

    private void publishDraft() {
        if (payPalTransfer.isWorking()) { toast(getString(R.string.pack_editor_wait_encryption)); return; }
        saveEditor();
        if (draft == null || draft.getIncludedSections().isEmpty()) {
            toast(getString(R.string.studio_no_sections)); return;
        }
        SubHubPack snapshot = draft.snapshot();
        storageAction(() -> { manager.addToLibrary(snapshot); return snapshot; }, stored -> {
            toast(getString(R.string.studio_library_added));
            renderLibrary();
            showPanel(binding.libraryPanel);
        });
    }

    private void deleteDraft() {
        if (draft == null) return;
        payPalTransfer.pause();
        String id = draft.getId();
        editorHandler.removeCallbacks(pendingSave);
        draft = null;
        storageAction(() -> { manager.deleteDraft(id); return Boolean.TRUE; }, deleted -> {
            renderDrafts();
            showPanel(binding.draftsPanel);
        });
    }

    private void importPack(Uri uri) {
        if (uri == null) return;
        storageAction(() -> manager.importPack(uri), pack -> {
            toast(getString(pack.getId().equals(manager.activePackId())
                    ? R.string.studio_active_update_imported : R.string.studio_imported,
                    pack.getName()));
            renderLibrary();
        });
    }

    private void share(SubHubPack pack) {
        if (pack == null) return;
        if (payPalTransfer.isWorking()) { toast(getString(R.string.pack_editor_wait_encryption)); return; }
        SubHubPack snapshot = pack.snapshot();
        storageAction(() -> manager.exportForShare(snapshot), file -> {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".updates", file);
            Intent send = new Intent(Intent.ACTION_SEND).setType(SubHubPackArchive.MIME_TYPE)
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try { startActivity(Intent.createChooser(send, getString(R.string.studio_share_chooser))); }
            catch (android.content.ActivityNotFoundException unavailable) {
                toast(getString(R.string.pack_editor_share_unavailable));
            }
        });
    }

    private void pickImages(String target) {
        if (actionBusy || draft == null) return;
        assetTarget = target;
        imagePicker.launch(new String[]{"image/png", "image/jpeg", "image/webp"});
    }

    private void addImages(List<Uri> uris) {
        if (draft == null || uris == null || uris.isEmpty() || actionBusy) return;
        SubHubPack target = draft.snapshot();
        String folder = assetTarget;
        List<Uri> selected = new ArrayList<>(uris);
        storageAction(() -> {
            int failed = 0;
            int added = 0;
            for (Uri uri : selected) {
                if ("cover".equals(folder) && added > 0) break;
                long existing = target.getAssetPaths().stream()
                        .filter(path -> path.startsWith("assets/" + folder + "/")).count();
                if (!"cover".equals(folder) && existing >= 64) { failed++; continue; }
                try (InputStream input = getContentResolver().openInputStream(uri)) {
                    if (input == null) throw new IOException("Unreadable image");
                    ByteArrayOutputStream output = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    long total = 0L;
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        total += read;
                        if (total > MAX_IMAGE_BYTES) throw new IOException("Oversize image");
                        output.write(buffer, 0, read);
                    }
                    byte[] bytes = output.toByteArray();
                    BitmapFactory.Options bounds = new BitmapFactory.Options();
                    bounds.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0
                            || !Set.of("image/png", "image/jpeg", "image/webp").contains(bounds.outMimeType)) {
                        throw new IOException("Unsupported image");
                    }
                    String extension = "image/jpeg".equals(bounds.outMimeType) ? "jpg"
                            : "image/webp".equals(bounds.outMimeType) ? "webp" : "png";
                    String path = "assets/" + folder + "/" + java.util.UUID.randomUUID() + "." + extension;
                            // Admit the new bytes before removing the old cover: failed replacement
                            // keeps it.
                            target.putAsset(path, bytes);
                    if ("cover".equals(folder)) for (String previous : target.getAssetPaths()) {
                        if (previous.startsWith("assets/cover/") && !previous.equals(path)) target.removeAsset(previous);
                    }
                    added++;
                } catch (IOException | IllegalArgumentException error) { failed++; }
            }
            manager.saveDraft(target);
            retained.draft = target.snapshot();
            return new ImageImport(target, failed);
        }, result -> {
            if (draft == null || !draft.getId().equals(result.pack.getId())) return;
            draft = result.pack;
            if (result.failed > 0) toast(getString(R.string.pack_editor_image_failed));
            autosave();
            updatePreview();
            renderAssets();
        });
    }

    private record ImageImport(SubHubPack pack, int failed) { }

    private void renderAssets() {
        binding.assetList.removeAllViews();
        if (draft == null || storage.isShutdown()) return;
        long request = ++assetRequest;
        SubHubPack snapshot = draft.snapshot();
        storage.execute(() -> {
            Map<String, Bitmap> thumbnails = new LinkedHashMap<>();
            for (String path : snapshot.getAssetPaths()) {
                byte[] bytes = snapshot.getAsset(path);
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
                options.inSampleSize = 1;
                while (options.outWidth / options.inSampleSize > 128
                        || options.outHeight / options.inSampleSize > 128) options.inSampleSize *= 2;
                options.inJustDecodeBounds = false;
                thumbnails.put(path, BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options));
            }
            editorHandler.post(() -> {
                if (isDestroyed() || request != assetRequest || draft == null
                        || !draft.getId().equals(snapshot.getId())) {
                    for (Bitmap image : thumbnails.values()) if (image != null) image.recycle();
                    return;
                }
                int index = 0;
                for (Map.Entry<String, Bitmap> entry : thumbnails.entrySet()) {
                    String path = entry.getKey();
                    LinearLayout row = actionRow();
                    ImageView image = new ImageView(this);
                    image.setImageBitmap(entry.getValue());
                    image.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                    image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                    row.addView(image, new LinearLayout.LayoutParams(dp(64), dp(64)));
                    String group = path.startsWith("assets/cover/") ? getString(R.string.pack_editor_cover)
                            : path.startsWith("assets/popup/") ? sectionTitle(SubHubPackSchema.POPUP)
                            : sectionTitle(SubHubPackSchema.CENSOR);
                    TextView name = label(getString(R.string.pack_editor_image_label, group, ++index), false);
                    row.addView(name, weighted());
                    Button remove = outlineButton(getString(R.string.pack_editor_remove_image));
                    remove.setTag("pack_remove_asset:" + path);
                    remove.setOnClickListener(view -> {
                        if (actionBusy || draft == null) return;
                        draft.removeAsset(path);
                        autosave();
                        updatePreview();
                        renderAssets();
                    });
                    row.addView(remove, weighted());
                    binding.assetList.addView(row);
                }
                if (actionBusy) rememberAndDisable(binding.assetList);
            });
        });
    }

    private LinearLayout packCard(SubHubPack pack, String detail) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        card.setBackgroundResource(R.drawable.bg_card);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(10);
        card.setLayoutParams(params);
        card.addView(label(pack.getName(), true));
        String byline = pack.getAuthor().isBlank() ? "v" + pack.getPackVersion()
                : "by " + pack.getAuthor() + " · v" + pack.getPackVersion();
        card.addView(label(byline, false));
        if (!pack.getDescription().isBlank()) card.addView(label(pack.getDescription(), false));
        card.addView(label(detail, false));
        if (pack.hasEncryptedPayPal()) card.addView(label("Encrypted PayPal · passphrase required", false));
        return card;
    }

    private LinearLayout actionRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(8);
        row.setLayoutParams(params);
        return row;
    }

    private Button outlineButton(String text) {
        Button button = new Button(this, null, 0, R.style.Widget_SubHub_CompactOutlineButton);
        button.setText(text);
        com.subhub.app.util.UiIdentity.textSize(button, R.dimen.ui_text_body);
        button.setAllCaps(false);
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setMinWidth(0);
        button.setPadding(dp(4), 0, dp(4), 0);
        return button;
    }

    private LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        params.setMarginEnd(dp(4));
        return params;
    }

    private TextView label(String text, boolean title) {
        TextView value = new TextView(this);
        value.setText(text);
        value.setTextColor(getColor(title ? R.color.text_primary : R.color.text_secondary));
        com.subhub.app.util.UiIdentity.textSize(value,
                title ? R.dimen.ui_text_section : R.dimen.ui_text_label);
        if (title) value.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        if (!title) value.setPadding(0, dp(3), 0, 0);
        return value;
    }

    private void addMetadataWatcher(TextView field) {
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) {
                saveEditor();
            }
            @Override public void afterTextChanged(Editable value) {}
        });
    }

    private String sectionTitle(String section) {
        switch (section) {
            case SubHubPackSchema.MODULES: return getString(R.string.studio_section_modules);
            case SubHubPackSchema.CENSOR: return getString(R.string.studio_section_censor);
            case SubHubPackSchema.LIMITS: return getString(R.string.studio_section_limits);
            case SubHubPackSchema.WALLET: return getString(R.string.studio_section_wallet);
            case SubHubPackSchema.SUBLIMINAL: return getString(R.string.studio_section_subliminal);
            case SubHubPackSchema.POPUP: return getString(R.string.studio_section_popup);
            default: return section;
        }
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void toast(String message) {
        Toast.makeText(this, message == null ? "Studio action failed" : message,
                Toast.LENGTH_LONG).show();
    }

    private static final class SimpleItemSelectedListener
            implements android.widget.AdapterView.OnItemSelectedListener {
        private final Runnable callback;
        SimpleItemSelectedListener(Runnable callback) { this.callback = callback; }
        @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view,
                int position, long id) { callback.run(); }
        @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
    }
}
