package com.subhub.app.capture.export;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.*;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import com.subhub.app.R;
import com.subhub.app.capture.CensorRenderer;
import com.subhub.app.databinding.ActivityExportWorkspaceBinding;
import com.subhub.app.detection.DetectionEngine;
import com.subhub.app.security.ControllerPinGate;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.util.PreferencePage;
import com.subhub.app.util.PrimaryHeader;
import com.subhub.app.util.StateToggle;
import com.subhub.app.util.ThemedDialogs;
import java.io.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Configures and observes durable gallery jobs. Leaving this screen never cancels the service. */
public class ExportWorkspaceActivity extends PreferencePage {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Uri> selected = new ArrayList<>();
    private final List<Long> pendingDeleteIds = new ArrayList<>();
    private ExportJobStore store;
    private String job, preparedFingerprint, resultsFingerprint;
    private volatile boolean closed;
    private boolean preparing, deleteOriginals, suppressDelete;
    private Bitmap previewBitmap;
    private int previewPosition;
    private int previewIndex;
    private ActivityExportWorkspaceBinding binding;
    private Spinner previewSelection;
    private SeekBar framePosition;
    private RadioGroup qualityChoices, detectionChoices;
    private boolean renderingOptions;
    private final Map<String, MediaInfo> mediaInfo = new LinkedHashMap<>();
    private long selectionRevision;
    private String settingsFingerprint = "";

    private record MediaInfo(String name, boolean video) {}

    private TextView selectedSummary, status;
    private LinearLayout results, resultCard;
    private ImageView preview;
    private Button pick, configure, areas, start, previewButton, retry, delete, cancel;
    private StateToggle mute, deletion;
    private ActivityResultLauncher<String[]> picker;
    private ActivityResultLauncher<String> storagePermission;
    private ActivityResultLauncher<IntentSenderRequest> deleteConsent;
    private final Runnable refresh = new Runnable() {
        @Override public void run() { if (!closed) { renderResults(); main.postDelayed(this, 750); } }
    };
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); ExportJobStore.recoverProcess(this); store = new ExportJobStore(this);
        picker = registerForActivityResult(new ActivityResultContracts.OpenMultipleDocuments(), this::select);
        storagePermission = registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            if (granted) prepare(true); else notice(getString(R.string.export_storage_needed));
        });
        deleteConsent = registerForActivityResult(new ActivityResultContracts.StartIntentSenderForResult(), result -> {
            if (result.getResultCode() == Activity.RESULT_OK && job != null) {
                int count = 0;
                for (ExportJobStore.Item item : store.items(job)) if (pendingDeleteIds.contains(item.id)) { markDeleted(item); count++; }
                notice(getString(R.string.export_deleted_count, count));
            }
            pendingDeleteIds.clear(); renderResults();
        });
        if (state != null) {
            job = state.getString("job"); preparedFingerprint = state.getString("fingerprint");
            ArrayList<String> uris = state.getStringArrayList("selected"); if (uris != null) for (String uri : uris) selected.add(Uri.parse(uri));
            long[] pending = state.getLongArray("pending_delete"); if (pending != null) for (long id : pending) pendingDeleteIds.add(id);
            deleteOriginals = state.getBoolean("delete_originals", false);
            previewIndex = state.getInt("preview_index", 0);
            previewPosition = state.getInt("preview_position", 0);
        } else job = store.latest();
        build();
    }
    private void build() {
        binding = ActivityExportWorkspaceBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        page = binding.exportPage;
        PrimaryHeader.bindSecondary(binding.getRoot(), R.string.export_workspace_title, false);
        PrimaryHeader.backButton(binding.getRoot()).setOnClickListener(view -> finish());
        pick = binding.buttonPickImages;
        pick.setOnClickListener(view -> picker.launch(new String[] {"image/*", "video/*"}));
        configure = binding.exportLookButton;
        configure.setOnClickListener(view -> openAppearance(false));
        areas = binding.exportAreasButton;
        areas.setOnClickListener(view -> openAppearance(true));
        selectedSummary = binding.exportSelectedSummary;
        status = binding.exportStatus;
        binding.exportQualityChoices.setPreferredColumns(4, 70);
        qualityChoices = binding.exportQualityChoices;
        detectionChoices = binding.exportDetectionChoices;
        ExportOptions current = ExportSettings.options(this);
        mute = binding.exportMute;
        mute.setChecked(current.mute);
        mute.setOnCheckedChangeListener(
                (view, checked) -> {
                    if (renderingOptions || !mute.isEnabled()) return;
                    ExportSettings.preferences(this).edit().putBoolean("export_mute", checked).apply(); invalidatePreview();
        });
        deletion = binding.switchDeleteOriginals;
        deletion.setChecked(deleteOriginals);
        deletion.setOnCheckedChangeListener((view, checked) -> setDelete(checked));
        qualityChoices.setOnCheckedChangeListener(
                (group, id) -> {
                    if (renderingOptions || !group.isEnabled()) return;
                    ExportOptions.Quality selectedQuality =
                            id == R.id.export_quality_draft
                                    ? ExportOptions.Quality.DRAFT
                                    : id == R.id.export_quality_high
                                            ? ExportOptions.Quality.HIGH
                                            : id == R.id.export_quality_best
                                                    ? ExportOptions.Quality.BEST
                                                    : ExportOptions.Quality.BALANCED;
                    ExportSettings.preferences(this)
                            .edit()
                            .putString("export_quality", selectedQuality.name())
                            .apply();
                    invalidatePreview();
                    refreshSummary();
                });
        detectionChoices.setOnCheckedChangeListener(
                (group, id) -> {
                    if (renderingOptions || !group.isEnabled()) return;
                    ExportSettings.preferences(this)
                            .edit()
                            .putBoolean("export_fast", id == R.id.export_detection_fast)
                            .apply();
                    invalidatePreview();
                    refreshSummary();
                });
        previewSelection = binding.exportPreviewSelection;
        previewSelection.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                        if (previewIndex != position) {
                            previewIndex = position;
                            previewPosition = 0;
                            clearPreview();
                        }
                        refreshMediaControls(); }
            public void onNothingSelected(AdapterView<?> parent) { previewIndex = 0; }
        });
        previewButton = binding.exportPreviewButton;
        previewButton.setOnClickListener(view -> prepare(false));
        framePosition = binding.exportPreviewPosition;
        framePosition.setProgress(previewPosition);
        framePosition.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar view, int progress, boolean user) { if (user) previewPosition = progress; }
            public void onStartTrackingTouch(SeekBar view) { }
            public void onStopTrackingTouch(SeekBar view) { if (!selected.isEmpty() && !preparing && view.isEnabled()) prepare(false); }
        });
        preview = binding.exportPreviewImage;
        start = binding.exportStartBatchButton;
        start.setOnClickListener(view -> startExport());
        results = binding.exportResultsList;
        resultCard = binding.exportResultCard;
        cancel = binding.buttonCancelExport;
        cancel.setOnClickListener(
                view -> {
            if (ExportService.isRunning()) startService(new Intent(this, ExportService.class).setAction(ExportService.ACTION_CANCEL));
        });
        retry = binding.exportRetryBatch;
        retry.setOnClickListener(
                view -> {
            if (job != null && !ExportService.isRunning()) { store.retry(job); launchJob(job); }
        });
        delete = binding.exportDeleteSaved;
        delete.setOnClickListener(view -> deleteSavedOriginals());
        updatePreviewSelection();
        loadMediaInfo();
        refreshSummary(); renderResults();
    }

    private void openAppearance(boolean categories) {
        ControllerPinGate.require(
                this,
                () ->
                        startActivity(
                                new Intent(this, ExportAppearanceActivity.class)
                                        .putExtra(
                                                ExportAppearanceActivity.EXTRA_SHOW_CATEGORIES,
                                                categories)),
                false);
    }
    private void select(List<Uri> uris) {
        if (uris == null || uris.isEmpty()) return;
        selected.clear(); selected.addAll(uris);
        for (Uri uri : uris) try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
        catch (SecurityException ignored) { /* Provider may grant only transient access. */ }
        previewIndex = 0;
        previewPosition = 0; invalidatePreview();
        updatePreviewSelection();
        loadMediaInfo();
        renderResults();
    }
    private void updatePreviewSelection() {
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < selected.size(); i++) labels.add(mediaName(selected.get(i).toString(), i + 1));
        int chosen = Math.max(0, Math.min(previewIndex, selected.size() - 1));
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, R.layout.view_form_spinner_value, labels);
        adapter.setDropDownViewResource(R.layout.view_form_spinner_option); previewSelection.setAdapter(adapter);
        previewSelection.setSelection(chosen);
        previewIndex = chosen;
        refreshMediaControls();
    }

    private void loadMediaInfo() {
        long revision = ++selectionRevision;
        Set<String> sources = new LinkedHashSet<>();
        for (Uri uri : selected) sources.add(uri.toString());
        if (job != null) for (ExportJobStore.Item item : store.items(job)) sources.add(item.source);
        android.content.Context app = getApplicationContext();
        worker.execute(
                () -> {
                    Map<String, MediaInfo> loaded = new LinkedHashMap<>();
                    for (String source : sources) {
                        Uri uri = Uri.parse(source);
                        String name = "";
                        boolean video = false;
                        try (Cursor cursor =
                                app.getContentResolver()
                                        .query(
                                                uri,
                                                new String[] {OpenableColumns.DISPLAY_NAME},
                                                null,
                                                null,
                                                null)) {
                            if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
                        } catch (RuntimeException ignored) {
                        }
                        try {
                            video = ExportMedia.isVideo(app, uri);
                        } catch (Exception ignored) {
                        }
                        loaded.put(source, new MediaInfo(name == null ? "" : name, video));
                    }
                    main.post(
                            () -> {
                                if (closed || revision != selectionRevision) return;
                                mediaInfo.clear();
                                mediaInfo.putAll(loaded);
                                updatePreviewSelection();
                                resultsFingerprint = null;
                                renderResults();
                            });
                });
    }

    private String mediaName(String source, int number) {
        MediaInfo info = mediaInfo.get(source);
        return info == null || info.name().isEmpty()
                ? getString(R.string.gallery_item_number, number)
                : info.name();
    }

    private void refreshMediaControls() {
        boolean any = !selected.isEmpty();
        MediaInfo current =
                any
                        ? mediaInfo.get(
                                selected.get(Math.min(previewIndex, selected.size() - 1))
                                        .toString())
                        : null;
        boolean video = current != null && current.video();
        previewSelection.setVisibility(selected.size() > 1 ? View.VISIBLE : View.GONE);
        previewButton.setVisibility(any ? View.VISIBLE : View.GONE);
        selectedSummary.setVisibility(any ? View.VISIBLE : View.GONE);
        selectedSummary.setText(
                selected.size() == 1
                        ? mediaName(selected.get(0).toString(), 1)
                        : getString(R.string.export_selected_count, selected.size()));
        framePosition.setVisibility(video ? View.VISIBLE : View.GONE);
        framePosition.setProgress(previewPosition);
        binding.exportFrameNote.setVisibility(video ? View.VISIBLE : View.GONE);
        binding.exportVideoOptions.setVisibility(
                !any
                                || selected.stream()
                                        .map(uri -> mediaInfo.get(uri.toString()))
                                        .anyMatch(info -> info != null && info.video())
                        ? View.VISIBLE
                        : View.GONE);
        binding.exportPreviewHint.setText(
                any ? R.string.gallery_preview_hint : R.string.export_select_first);
    }
    private void setDelete(boolean checked) {
        if (suppressDelete) return;
        if (!deletion.isEnabled()) {
            suppressDelete = true; deletion.setChecked(deleteOriginals); suppressDelete = false;
            return;
        }
        if (!checked) { deleteOriginals = false; invalidatePreview(); return; }
        suppressDelete = true; deletion.setChecked(false); suppressDelete = false;
        ThemedDialogs.builder(this).setTitle(R.string.export_delete_warning_title)
                .setMessage(R.string.export_delete_ready_help).setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.export_delete_warning_enable, (d, which) -> {
                    if (preparing || ExportService.isRunning()) return;
                    deleteOriginals = true; suppressDelete = true; deletion.setChecked(true); suppressDelete = false; invalidatePreview();
                }).show();
    }
    private void refreshSummary() {
        SettingsRepository settings = SettingsRepository.forPreferences(ExportSettings.preferences(this));
        ExportOptions options = ExportSettings.options(this);
        configure.setText(
                ExportAppearanceActivity.label(settings.loadAppearance().getType().getPreferenceValue()));
        areas.setText(getString(R.string.gallery_areas_count,
                        settings.loadDetectorConfig().getEnabledCategories().size()));
        renderingOptions = true;
        qualityChoices.check(
                options.quality == ExportOptions.Quality.DRAFT
                        ? R.id.export_quality_draft
                        : options.quality == ExportOptions.Quality.HIGH
                                ? R.id.export_quality_high
                                : options.quality == ExportOptions.Quality.BEST
                                        ? R.id.export_quality_best
                                        : R.id.export_quality_balanced);
        detectionChoices.check(
                options.detectEvery == 2 ? R.id.export_detection_fast : R.id.export_detection_full);
        mute.setChecked(options.mute);
        renderingOptions = false;
        String next = ExportSettings.preferences(this).getAll().toString();
        if (!settingsFingerprint.isEmpty() && !next.equals(settingsFingerprint))
            invalidatePreview();
        settingsFingerprint = next;
    }
    private void invalidatePreview() {
        preparedFingerprint = null;
        clearPreview();
    }

    private void clearPreview() {
        if (preview != null) { preview.setImageDrawable(null); preview.setVisibility(View.GONE); }
        if (binding != null) binding.exportPreviewEmpty.setVisibility(View.VISIBLE);
        if (previewBitmap != null) { previewBitmap.recycle(); previewBitmap = null; }
    }
    private void startExport() {
        if (Build.VERSION.SDK_INT < 29 && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE); return;
        }
        prepare(true);
    }
    private void prepare(boolean export) {
        if (preparing) return;
        if (ExportService.isRunning()) { notice(getString(R.string.export_wait_running)); return; }
        if (selected.isEmpty()) { notice(getString(R.string.export_select_first)); return; }
        final List<Uri> sources = new ArrayList<>(selected);
        final ExportOptions current = ExportSettings.options(this);
        final ExportOptions options = new ExportOptions(current.quality, current.detectEvery, current.mute, deleteOriginals);
        final org.json.JSONObject snapshot;
        try { snapshot = ExportSettings.snapshot(this); } catch (Exception e) { notice(getString(R.string.export_preview_failed)); return; }
        String fingerprint = snapshot.toString() + options.quality + options.detectEvery + options.mute + options.deleteOriginals + sources;
        boolean reusable = job != null && fingerprint.equals(preparedFingerprint)
                && store.items(job).stream().allMatch(item -> item.state == ExportJobStore.State.DRAFT);
        final String previousJob = job; final int position = previewPosition;
        final Uri previewSource = sources.get(Math.min(previewIndex, sources.size() - 1));
        preparing = true; status.setText(R.string.export_preparing); renderResults();
        worker.execute(() -> {
            try (ExportJobStore backgroundStore = new ExportJobStore(this)) {
                String readyJob = reusable ? previousJob : backgroundStore.createDraft(sources, snapshot, options);
                if (!reusable && previousJob != null) backgroundStore.discardDraft(previousJob);
                Bitmap image = export ? null : previewFrame(backgroundStore, readyJob, previewSource, position);
                if (export) {
                    backgroundStore.enqueue(readyJob);
                    try { ContextCompat.startForegroundService(getApplicationContext(), new Intent(getApplicationContext(), ExportService.class)
                            .setAction(ExportService.ACTION_START).putExtra(ExportService.EXTRA_JOB, readyJob)); }
                    catch (RuntimeException rejected) { backgroundStore.recover(); throw rejected; }
                }
                main.post(() -> {
                    if (closed) { if (image != null) image.recycle(); return; }
                    job = readyJob; preparedFingerprint = fingerprint; preparing = false;
                    if (export) status.setText(R.string.gallery_ready);
                    else {
                        preview.setImageBitmap(image); preview.setVisibility(View.VISIBLE);
                                        binding.exportPreviewEmpty.setVisibility(View.GONE);
                        if (previewBitmap != null && previewBitmap != image) previewBitmap.recycle(); previewBitmap = image;
                        status.setText(R.string.gallery_preview_ready);
                    }
                    renderResults();
                });
            } catch (Exception error) { main.post(() -> { if (!closed) { preparing = false; status.setText(R.string.export_preview_failed); renderResults(); } }); }
        });
    }
    private Bitmap previewFrame(ExportJobStore backgroundStore, String prepared, Uri source, int position) throws Exception {
        SettingsRepository settings = SettingsRepository.forPreferences(ExportSettings.frozen(backgroundStore.snapshot(prepared)));
        Bitmap image;
        if (ExportMedia.isVideo(this, source)) {
            MediaMetadataRetriever reader = new MediaMetadataRetriever();
            try {
                reader.setDataSource(this, source);
                String durationString = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                long duration = durationString == null ? 0 : Long.parseLong(durationString);
                image = reader.getFrameAtTime(duration * 1000 * position / 100, MediaMetadataRetriever.OPTION_CLOSEST);
            } finally { reader.release(); }
            if (image == null) throw new IOException("Video preview unavailable");
            int[] size = backgroundStore.options(prepared).dimensions(image.getWidth(), image.getHeight());
            Bitmap scaled = Bitmap.createScaledBitmap(image, size[0], size[1], true);
            if (scaled != image) image.recycle(); image = scaled;
        } else image = ExportMedia.image(this, source, backgroundStore.options(prepared).quality.longestEdge);
        try (DetectionEngine engine = new DetectionEngine(this, settings.loadDetectorConfig());
             CensorRenderer renderer = new CensorRenderer(this, ExportMedia.assets(backgroundStore.directory(prepared),
                     settings.loadDetectionPreset().getCustomImageDimension(), settings.loadDetectionPreset().getCustomImageCount()))) {
            engine.initialize(); Bitmap output = image.copy(Bitmap.Config.ARGB_8888, true);
            if (output == null) throw new IOException("Preview unavailable");
            try { renderer.draw(output, image, engine.detect(image), settings.loadAppearance()); return output; }
            catch (Exception e) { output.recycle(); throw e; }
        } finally { image.recycle(); }
    }
    private void launchJob(String id) {
        try { ContextCompat.startForegroundService(this, new Intent(this, ExportService.class).setAction(ExportService.ACTION_START).putExtra(ExportService.EXTRA_JOB, id)); }
        catch (RuntimeException rejected) { store.recover(); notice(getString(R.string.export_wait_running)); }
        status.setText(R.string.gallery_ready); renderResults();
    }
    private void renderResults() {
        if (results == null || closed) return;
        List<ExportJobStore.Item> items = job == null ? Collections.emptyList() : store.items(job);
        boolean running = ExportService.isRunning() || items.stream().anyMatch(item -> item.state == ExportJobStore.State.PENDING || item.state == ExportJobStore.State.RUNNING);
        boolean editable = !preparing && !running;
        pick.setEnabled(editable); configure.setEnabled(editable);
        areas.setEnabled(editable);
        ControllerPinGate.markLocked(configure);
        ControllerPinGate.markLocked(areas);
        setChoicesEnabled(qualityChoices, editable);
        setChoicesEnabled(detectionChoices, editable); mute.setEnabled(editable); deletion.setEnabled(editable);
        previewSelection.setEnabled(editable);
        framePosition.setEnabled(editable);
        binding.exportPreviewProgress.setVisibility(preparing ? View.VISIBLE : View.GONE);
        binding.exportPreviewEmpty.setVisibility(
                preparing || previewBitmap != null ? View.GONE : View.VISIBLE);
        start.setEnabled(editable && !selected.isEmpty()); previewButton.setEnabled(editable && !selected.isEmpty());
        resultCard.setVisibility(items.stream().anyMatch(item -> item.state != ExportJobStore.State.DRAFT) ? View.VISIBLE : View.GONE);
        cancel.setVisibility(running ? View.VISIBLE : View.GONE);
        if (job == null) { retry.setVisibility(View.GONE); delete.setVisibility(View.GONE); return; }
        StringBuilder fingerprint = new StringBuilder(job);
        boolean unfinished = false, deletable = false;
        for (ExportJobStore.Item item : items) {
            fingerprint.append(item.state).append(item.progress).append(item.message);
            unfinished |= item.state == ExportJobStore.State.FAILED || item.state == ExportJobStore.State.CANCELLED || item.state == ExportJobStore.State.INTERRUPTED;
            deletable |= item.state == ExportJobStore.State.SAVED && !"Original deleted".equals(item.message);
        }
        retry.setVisibility(unfinished && editable ? View.VISIBLE : View.GONE);
        delete.setVisibility(deletable && editable && store.options(job).deleteOriginals ? View.VISIBLE : View.GONE);
        if (!preparing && !items.isEmpty() && items.stream().noneMatch(item -> item.state == ExportJobStore.State.DRAFT)) {
            long saved = items.stream().filter(item -> item.state == ExportJobStore.State.SAVED).count();
            status.setText(getString(running ? R.string.export_batch_running : R.string.export_batch_finished, saved, items.size()));
        }
        if (fingerprint.toString().equals(resultsFingerprint)) return;
        resultsFingerprint = fingerprint.toString(); results.removeAllViews(); int number = 0;
        for (ExportJobStore.Item item : items) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, dp(14), 0, dp(14));
            results.addView(row, new LinearLayout.LayoutParams(-1, -2));
            LinearLayout top = new LinearLayout(this);
            top.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.addView(top, new LinearLayout.LayoutParams(-1, -2));
            ImageView icon = new ImageView(this);
            icon.setImageResource(R.drawable.ic_tab_export);
            icon.setImageTintList(
                    android.content.res.ColorStateList.valueOf(getColor(R.color.accent_text)));
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            top.addView(icon, new LinearLayout.LayoutParams(dp(26), dp(26)));
            LinearLayout labels = new LinearLayout(this);
            labels.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0, -2, 1);
            labelParams.setMarginStart(dp(12));
            top.addView(labels, labelParams);
            TextView name = text(labels, mediaName(item.source, ++number), 14, false);
            name.setTypeface(null, android.graphics.Typeface.BOLD);
            name.setPadding(0, 0, 0, dp(3));
            TextView stateLabel =
                    text(
                            labels, ExportAppearanceActivity.label(item.state.name().toLowerCase(Locale.ROOT)),
                            12,
                            true);
            stateLabel.setPadding(0, 0, 0, 0);
            if (item.state == ExportJobStore.State.RUNNING
                    || item.state == ExportJobStore.State.PENDING) {
                ProgressBar progress =
                        new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
                progress.setMax(100);
                progress.setProgress(item.progress);
                progress.setProgressTintList(
                        android.content.res.ColorStateList.valueOf(getColor(R.color.accent_text)));
                progress.setContentDescription(
                        getString(R.string.gallery_export_progress, item.progress));
                LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(-1, dp(6));
                progressParams.topMargin = dp(12);
                row.addView(progress, progressParams);
            }
            if (!item.message.isEmpty() && !"Saved".equals(item.message)) text(row, item.message, 12, true);
            if (item.state == ExportJobStore.State.SAVED && item.output != null) {
                com.subhub.app.util.WalletBalanceRow actions =
                        new com.subhub.app.util.WalletBalanceRow(this, null);
                row.addView(actions, new LinearLayout.LayoutParams(-1, -2));
                button(
                        actions, getString(R.string.export_open_copy), () -> open(Uri.parse(item.output), false));
                button(
                        actions, getString(R.string.export_share_copy), () -> open(Uri.parse(item.output), true));
            }
            if (number < items.size()) {
                View divider = new View(this);
                divider.setBackgroundResource(R.color.outline_subtle);
                results.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
            }
        }
    }

    private void setChoicesEnabled(RadioGroup group, boolean enabled) {
        group.setEnabled(enabled);
        for (int i = 0; i < group.getChildCount(); i++) group.getChildAt(i).setEnabled(enabled);
    }
    private void open(Uri uri, boolean share) {
        String type = getContentResolver().getType(uri);
        Intent intent = share ? new Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, uri)
                : new Intent(Intent.ACTION_VIEW).setDataAndType(uri, type);
        if (share) intent.setClipData(ClipData.newRawUri("Export", uri));
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivity(share ? Intent.createChooser(intent, getString(R.string.export_share_copy)) : intent); }
        catch (ActivityNotFoundException unavailable) { notice(getString(R.string.export_cannot_open)); }
    }
    private void deleteSavedOriginals() {
        if (job == null || ExportService.isRunning()) return;
        final String approvedJob = job;
        ThemedDialogs.builder(this).setTitle(R.string.export_delete_warning_title)
                .setMessage(R.string.export_delete_ready_help).setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.export_delete_saved, (d, w) -> {
                    if (approvedJob.equals(job)) requestDeletion();
                }).show();
    }
    private void markDeleted(ExportJobStore.Item item) { store.update(item.id, ExportJobStore.State.SAVED, Uri.parse(item.output), "Original deleted", 100); }
    private void requestDeletion() {
        if (job == null || ExportService.isRunning()) return;
        List<Uri> media = new ArrayList<>(); pendingDeleteIds.clear(); int deleted = 0;
        for (ExportJobStore.Item item : store.items(job)) {
            if (item.state != ExportJobStore.State.SAVED || item.output == null || "Original deleted".equals(item.message)) continue;
            try {
                try (ParcelFileDescriptor fd = getContentResolver().openFileDescriptor(Uri.parse(item.output), "r")) { if (fd == null || fd.getStatSize() == 0) continue; }
                Uri source = Uri.parse(item.source);
                Uri mediaUri = "media".equals(source.getAuthority()) ? source : Build.VERSION.SDK_INT >= 29 ? MediaStore.getMediaUri(this, source) : null;
                if (Build.VERSION.SDK_INT >= 30 && mediaUri != null) { media.add(mediaUri); pendingDeleteIds.add(item.id); }
                else if (DocumentsContract.isDocumentUri(this, source) && DocumentsContract.deleteDocument(getContentResolver(), source)) { markDeleted(item); deleted++; }
            } catch (Exception denied) { /* Failed deletion leaves the source and saved copy intact. */ }
        }
        if (!media.isEmpty() && Build.VERSION.SDK_INT >= 30) {
            try { deleteConsent.launch(new IntentSenderRequest.Builder(MediaStore.createDeleteRequest(getContentResolver(), media).getIntentSender()).build()); }
            catch (RuntimeException denied) { pendingDeleteIds.clear(); notice(getString(R.string.export_deleted_count, deleted)); }
        } else notice(getString(R.string.export_deleted_count, deleted));
        renderResults();
    }
    protected Uri saveBitmapCopy(Bitmap bitmap) throws IOException {
        String temporaryJob;
        try { temporaryJob = store.create(Collections.singletonList(Uri.EMPTY), ExportSettings.snapshot(this), ExportOptions.defaults()); }
        catch (org.json.JSONException error) { throw new IOException(error); }
        File file = new File(store.directory(temporaryJob), "legacy.jpg");
        try {
            try (OutputStream output = new FileOutputStream(file)) { if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)) throw new IOException("Encoding failed"); }
            return ExportMedia.publish(this, store, store.items(temporaryJob).get(0), file, false);
        } finally { if (file.exists() && !file.delete()) file.deleteOnExit(); }
    }
    @Override protected void onResume() { super.onResume(); if (binding != null) refreshSummary(); main.removeCallbacks(refresh); main.post(refresh); }
    @Override protected void onPause() { main.removeCallbacks(refresh); super.onPause(); }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("preview_index", previewIndex);
        state.putInt("preview_position", previewPosition);
        state.putString("job", job); state.putString("fingerprint", preparedFingerprint); state.putBoolean("delete_originals", deleteOriginals);
        ArrayList<String> uris = new ArrayList<>(); for (Uri uri : selected) uris.add(uri.toString()); state.putStringArrayList("selected", uris);
        long[] pending = new long[pendingDeleteIds.size()]; for (int i = 0; i < pending.length; i++) pending[i] = pendingDeleteIds.get(i);
        state.putLongArray("pending_delete", pending); super.onSaveInstanceState(state);
    }
    @Override protected void onDestroy() { closed = true; main.removeCallbacksAndMessages(null); worker.shutdown(); if (previewBitmap != null) previewBitmap.recycle(); if (store != null) store.close(); super.onDestroy(); }
}
