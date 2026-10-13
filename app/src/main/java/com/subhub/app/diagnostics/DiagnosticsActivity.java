package com.subhub.app.diagnostics;

import com.subhub.app.util.PrimaryHeader;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.subhub.app.BuildConfig;
import com.subhub.app.R;
import com.subhub.app.databinding.ActivityDiagnosticsBinding;
import com.subhub.app.detection.DetectorConfig;
import com.subhub.app.capture.MediaProjectionLeaseRegistry;
import com.subhub.app.pack.SubHubPackManager;
import com.subhub.app.security.ControllerEditMode;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.service.ScreenCaptureService;
import com.subhub.app.service.ScreenshotAccessibilityService;
import com.subhub.app.settings.SettingsRepository;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Local runtime health plus explicit, user-shared Censor Lab bundles; no socket or secret fields. */
public final class DiagnosticsActivity extends AppCompatActivity {
    private final Handler refreshHandler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            render();
            refreshHandler.postDelayed(this, 500);
        }
    };
    private ActivityDiagnosticsBinding binding;
    private SettingsRepository settings;
    private ControllerEditMode editMode;
    private MediaProjectionManager projectionManager;
    private final ExecutorService labIo = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "censor-lab-export");
        thread.setDaemon(true);
        return thread;
    });
    private boolean labBusy;
    private boolean shareWhenReady;
    private String startMarkerSession;
    private String renderedLabFailure;
    private File pendingSaveBundle;
    private final ActivityResultLauncher<String> saveLabDocument = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("application/zip"), uri -> {
                File bundle = pendingSaveBundle;
                pendingSaveBundle = null;
                if (uri == null || bundle == null) {
                    labBusy = false;
                    renderLabState();
                    return;
                }
                labIo.execute(() -> {
                    try {
                        CensorLabDownloads.copy(getApplicationContext(), bundle, uri);
                        finishLabSave(bundle, null);
                    } catch (Exception error) { finishLabSave(bundle, error); }
                });
            });
    private final ActivityResultLauncher<Intent> recordingPermission = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
                    labBusy = false;
                    Toast.makeText(this, R.string.diagnostics_lab_capture_cancelled,
                            Toast.LENGTH_SHORT).show();
                    renderLabState();
                    return;
                }
                try {
                    Intent service = CensorLabRecordingService.startIntent(
                            this, result.getResultCode(), result.getData());
                    ContextCompat.startForegroundService(this, service);
                } catch (Exception error) {
                    labBusy = false;
                    showLabFailure(error);
                }
                renderLabState();
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityDiagnosticsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        PrimaryHeader.bindSecondary(binding.getRoot(), R.string.diagnostics_title, true);
        settings = new SettingsRepository(this);
        projectionManager = (MediaProjectionManager)
                getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        PrimaryHeader.backButton(binding.getRoot()).setOnClickListener(view -> finish());
        binding.buttonRefresh.setOnClickListener(view -> render());
        binding.buttonCensorLabStart.setOnClickListener(view -> startLabSession());
        binding.buttonCensorLabStop.setOnClickListener(view -> stopLabSession());
        binding.buttonCensorLabAttach.setOnClickListener(view -> exportAndShare(true));
        binding.buttonCensorLabShareTrace.setOnClickListener(view -> exportAndShare(false));
        binding.buttonCensorLabSave.setOnClickListener(view -> saveLabBundle());
        if (savedInstanceState != null) {
            String pending = savedInstanceState.getString("pendingLabSave");
            if (pending != null) {
                try {
                    pendingSaveBundle = CensorLabDownloads.requireBundle(this, new File(pending));
                    labBusy = true;
                } catch (java.io.IOException ignored) { pendingSaveBundle = null; }
            }
        }
        binding.switchDiagnosticsOverlay.setChecked(settings.preferences().getBoolean(
                DiagnosticsRepository.PREF_OVERLAY, false));
        binding.switchDiagnosticsOverlay.setOnCheckedChangeListener((button, checked) ->
                settings.preferences().edit().putBoolean(
                        DiagnosticsRepository.PREF_OVERLAY, checked).apply());
        boolean candidateAvailable = com.subhub.app.censor.android.ProductCensorQualification.candidateSupported();
        binding.replacementCandidateControls.setVisibility(candidateAvailable ? View.VISIBLE : View.GONE);
        binding.switchReplacementCandidate.setChecked(
                com.subhub.app.censor.android.ProductCensorQualification.candidateSelected(this));
        binding.switchReplacementCandidate.setOnCheckedChangeListener((button, checked) -> {
            if (!candidateAvailable || !ControllerPinManager.isDomModeActive()) {
                button.setChecked(com.subhub.app.censor.android.ProductCensorQualification.candidateSelected(this));
                return;
            }
            com.subhub.app.censor.android.ProductCensorQualification.selectCandidate(this, checked);
        });
        if (ControllerPinManager.isDomModeActive()) {
            editMode = ControllerEditMode.bind(this, PrimaryHeader.editLockButton(binding.getRoot()), editing -> {
                binding.switchDiagnosticsOverlay.setEnabled(editing);
                binding.switchReplacementCandidate.setEnabled(editing && candidateAvailable);
            });
        } else {
            PrimaryHeader.editLockButton(binding.getRoot()).setVisibility(android.view.View.GONE);
            binding.switchDiagnosticsOverlay.setEnabled(false);
            binding.switchReplacementCandidate.setEnabled(false);
        }
        render();
    }

    @Override protected void onResume() {
        super.onResume();
        if (editMode != null) editMode.refresh();
        refreshHandler.removeCallbacks(refresh);
        refreshHandler.post(refresh);
    }

    @Override protected void onPause() {
        refreshHandler.removeCallbacks(refresh);
        super.onPause();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (pendingSaveBundle != null) state.putString("pendingLabSave", pendingSaveBundle.getPath());
    }

    @Override protected void onDestroy() {
        labIo.shutdown();
        binding = null;
        super.onDestroy();
    }

    private void render() {
        if (binding == null) return;
        DiagnosticsRepository.Snapshot live = DiagnosticsRepository.snapshot();
        String state = live.isRunning() ? (live.isReady() ? "RUNNING" : "INITIALIZING") : "IDLE";
        binding.runtimeStatus.setText(String.format(Locale.ROOT,
                "State: %s\nCapture: %s\nProvider: %s\nModel: %s\nModel input: %d × %d\nUptime: %s",
                state, live.getMode(), live.getProvider(), live.getModel(), live.getResolution(),
                live.getResolution(), duration(live.getUptimeMs())));
        binding.performanceStatus.setText(String.format(Locale.ROOT,
                "Frames: %d processed / %d stale dropped\nRegions: %d total / %d latest\nInference: %d ms latest / %d ms average / %d ms peak\nStages: %d ms preprocess / %d ms runtime / %d ms postprocess\nFrame age before UI: %d ms\nEnd-to-end overlay publish: %d ms\nText: %d Accessibility candidates / %d stable / %d OCR stable\nOCR: %d runs / %d ms latest / %d ms average / %d ms peak / %d stale dropped\nLatest frame: %d × %d\nLast sanitized failure: %s",
                live.getFrames(), live.getDroppedFrames(),
                live.getTotalDetections(), live.getLastDetections(),
                live.getLastInferenceMs(), live.getAverageInferenceMs(), live.getPeakInferenceMs(),
                live.getLastPreprocessMs(), live.getLastRuntimeMs(),
                live.getLastPostprocessMs(), live.getLastFrameAgeMs(),
                live.getLastPublishDelayMs(),
                live.getLastAccessibilityTextCandidates(),
                live.getLastAccessibilityTextStable(), live.getLastOcrTextStable(),
                live.getOcrRuns(), live.getLastOcrMs(), live.getAverageOcrMs(),
                live.getPeakOcrMs(), live.getStaleOcrResults(),
                live.getFrameWidth(), live.getFrameHeight(), live.getLastFailure()));

        DetectorConfig config = settings.loadDetectorConfig();
        binding.configurationStatus.setText(String.format(Locale.ROOT,
                "Preset: %s\nRequested model: %s\nInput: %d × %d\nCapture scale: %.0f%%\nInterval: %d ms\nConfidence: %.0f%%\nEnabled detector categories: %d\nConfiguration pack: %s",
                settings.loadDetectionPreset().preferenceValue(), config.getModelFilename(),
                config.getInferenceResolution(), config.getInferenceResolution(),
                config.getCaptureScale() * 100f, config.getDetectionIntervalMs(),
                config.getConfidenceThreshold() * 100f, config.getEnabledCategories().size(),
                new SubHubPackManager(this).activePackId() == null ? "None" : "Active"));

        boolean notifications = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
        PowerManager power = (PowerManager) getSystemService(Context.POWER_SERVICE);
        boolean batteryExempt = power != null && power.isIgnoringBatteryOptimizations(getPackageName());
        binding.permissionsStatus.setText(String.format(Locale.ROOT,
                "Overlay permission: %s\nNotifications: %s\nMediaProjection capture: %s\nAccessibility capture: %s\nBattery optimization exemption: %s",
                yesNo(Settings.canDrawOverlays(this)), yesNo(notifications),
                running(ScreenCaptureService.isRunning()
                        || CensorLabRecordingService.isActive()),
                running(ScreenshotAccessibilityService.isRunning()), yesNo(batteryExempt)));

        binding.buildStatus.setText(String.format(Locale.ROOT,
                "SubHub %s (%d)\nAndroid %d\n%s %s\nDiagnostics transport: local bundle via Android share sheet",
                BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, Build.VERSION.SDK_INT,
                Build.MANUFACTURER, Build.MODEL));
        renderLabState();
    }

    private void startLabSession() {
        if (labBusy || CensorLabRecorder.isActive()
                || CensorLabRecordingService.isActive()) return;
        if (ScreenCaptureService.isRunning()
                || MediaProjectionLeaseRegistry.owner() != null) {
            Toast.makeText(this, R.string.diagnostics_lab_projection_conflict,
                    Toast.LENGTH_LONG).show();
            return;
        }
        try {
            labBusy = true;
            shareWhenReady = false;
            renderedLabFailure = null;
            Intent request;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                request = projectionManager.createScreenCaptureIntent(
                        MediaProjectionConfig.createConfigForDefaultDisplay());
            } else {
                request = projectionManager.createScreenCaptureIntent();
            }
            recordingPermission.launch(request);
            renderLabState();
        } catch (Exception error) {
            labBusy = false;
            showLabFailure(error);
        }
    }

    private void stopLabSession() {
        if (labBusy) return;
        CensorLabRecordingService.Snapshot recording =
                CensorLabRecordingService.snapshot();
        if (recording.phase == CensorLabRecordingService.Phase.RECORDING
                || recording.phase == CensorLabRecordingService.Phase.STARTING) {
            String id = recording.sessionId == null
                    ? CensorLabRecorder.activeSessionId() : recording.sessionId;
            labBusy = true;
            shareWhenReady = true;
            showSyncMarker(false, id);
            Toast.makeText(this, R.string.diagnostics_lab_stopping,
                    Toast.LENGTH_SHORT).show();
            startService(CensorLabRecordingService.stopIntent(
                    this, 1_050L, "user-stop"));
            renderLabState();
            return;
        }
        if (!CensorLabRecorder.isActive()) return;
        String id = CensorLabRecorder.activeSessionId();
        labBusy = true;
        showSyncMarker(false, id);
        Toast.makeText(this, R.string.diagnostics_lab_stopping, Toast.LENGTH_SHORT).show();
        renderLabState();
        labIo.execute(() -> {
            SystemClock.sleep(1_050L);
            try {
                CensorLabRecorder.stop(getApplicationContext());
                runOnUiThread(() -> {
                    if (binding == null) return;
                    labBusy = false;
                    renderLabState();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (binding == null) return;
                    labBusy = false;
                    showLabFailure(error);
                    renderLabState();
                });
            }
        });
    }

    private void exportAndShare(boolean includeVideo) {
        if (labBusy || CensorLabRecorder.isActive()) return;
        CensorLabRecorder.CompletedSession session = CensorLabRecorder.latest(this);
        if (session == null) {
            Toast.makeText(this, R.string.diagnostics_lab_no_session,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        labBusy = true;
        renderLabState();
        Toast.makeText(this, R.string.diagnostics_lab_exporting, Toast.LENGTH_SHORT).show();
        labIo.execute(() -> {
            try {
                File bundle = CensorLabBundleExporter.export(
                        getApplicationContext(), session, includeVideo);
                runOnUiThread(() -> {
                    if (binding == null) return;
                    labBusy = false;
                    renderLabState();
                    shareBundle(bundle);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (binding == null) return;
                    labBusy = false;
                    showLabFailure(error);
                    renderLabState();
                });
            }
        });
    }

    private void saveLabBundle() {
        if (labBusy || CensorLabRecorder.isActive()) return;
        CensorLabRecorder.CompletedSession session = CensorLabRecorder.latest(this);
        if (session == null) return;
        labBusy = true;
        renderLabState();
        Toast.makeText(this, R.string.diagnostics_lab_exporting, Toast.LENGTH_SHORT).show();
        labIo.execute(() -> {
            try {
                File bundle = CensorLabBundleExporter.export(getApplicationContext(), session, true);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    CensorLabDownloads.save(getApplicationContext(), bundle);
                    finishLabSave(bundle, null);
                } else {
                    runOnUiThread(() -> {
                        if (binding == null) return;
                        pendingSaveBundle = bundle;
                        saveLabDocument.launch(bundle.getName());
                    });
                }
            } catch (Exception error) { finishLabSave(null, error); }
        });
    }

    private void finishLabSave(File bundle, Exception error) {
        runOnUiThread(() -> {
            if (binding == null) return;
            labBusy = false;
            if (error != null) showLabFailure(error);
            else Toast.makeText(this, getString(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    ? R.string.diagnostics_lab_saved_downloads : R.string.diagnostics_lab_saved,
                    bundle.getName()), Toast.LENGTH_LONG).show();
            renderLabState();
        });
    }

    private void shareBundle(File bundle) {
        if (binding == null || bundle == null || !bundle.isFile()) return;
        android.net.Uri uri = FileProvider.getUriForFile(getApplicationContext(),
                getPackageName() + ".updates", bundle);
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType("application/zip")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(send,
                getString(R.string.diagnostics_lab_share_chooser)));
    }

    private void showSyncMarker(boolean starting, String id) {
        String safeId = id == null ? "unknown" : id;
        binding.censorLabSyncMarker.setText(getString(starting
                ? R.string.diagnostics_lab_sync_start : R.string.diagnostics_lab_sync_stop,
                safeId));
        binding.censorLabSyncMarker.setVisibility(View.VISIBLE);
        binding.diagnosticsScroll.smoothScrollTo(0, 0);
        binding.censorLabSyncMarker.postOnAnimation(() ->
                CensorLabRecorder.mark(starting ? "SYNC_START_UI_VISIBLE" : "SYNC_STOP_UI_VISIBLE"));
        binding.censorLabSyncMarker.postDelayed(() -> {
            if (binding != null) binding.censorLabSyncMarker.setVisibility(View.GONE);
        }, 1_250L);
    }

    private void renderLabState() {
        if (binding == null) return;
        CensorLabRecorder.SessionState state = CensorLabRecorder.state(this);
        CensorLabRecordingService.Snapshot recording =
                CensorLabRecordingService.snapshot();
        if (recording.phase == CensorLabRecordingService.Phase.RECORDING) {
            labBusy = false;
            String sessionId = recording.sessionId == null ? state.id : recording.sessionId;
            binding.censorLabStatus.setText(getString(R.string.diagnostics_lab_recording,
                    sessionId, state.eventCount, state.droppedEvents));
            if (sessionId != null && !sessionId.equals(startMarkerSession)) {
                startMarkerSession = sessionId;
                showSyncMarker(true, sessionId);
                Toast.makeText(this, R.string.diagnostics_lab_started,
                        Toast.LENGTH_LONG).show();
            }
        } else if (recording.phase == CensorLabRecordingService.Phase.STARTING) {
            binding.censorLabStatus.setText(R.string.diagnostics_lab_preparing);
        } else if (recording.phase == CensorLabRecordingService.Phase.STOPPING) {
            binding.censorLabStatus.setText(R.string.diagnostics_lab_finalizing);
        } else if (recording.phase == CensorLabRecordingService.Phase.FAILED) {
            labBusy = false;
            binding.censorLabStatus.setText(getString(R.string.diagnostics_lab_failed_status,
                    recording.failure == null ? "Unknown failure" : recording.failure));
            if (recording.failure != null
                    && !recording.failure.equals(renderedLabFailure)) {
                renderedLabFailure = recording.failure;
                Toast.makeText(this, getString(
                        R.string.diagnostics_lab_failed, recording.failure),
                        Toast.LENGTH_LONG).show();
            }
        } else if (state.active) {
            binding.censorLabStatus.setText(getString(R.string.diagnostics_lab_active,
                    state.id, state.eventCount, state.droppedEvents));
        } else if (state.completed != null) {
            String stopped = new SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                    .format(new Date(state.completed.stoppedWallMillis));
            binding.censorLabStatus.setText(getString(R.string.diagnostics_lab_complete,
                    state.id, state.eventCount, state.droppedEvents, stopped));
        } else {
            binding.censorLabStatus.setText(R.string.diagnostics_lab_idle);
        }
        boolean recordingActive = recording.phase == CensorLabRecordingService.Phase.STARTING
                || recording.phase == CensorLabRecordingService.Phase.RECORDING
                || recording.phase == CensorLabRecordingService.Phase.STOPPING;
        binding.buttonCensorLabStart.setEnabled(!labBusy && !state.active && !recordingActive);
        binding.buttonCensorLabStop.setEnabled(!labBusy
                && (state.active || recording.phase == CensorLabRecordingService.Phase.RECORDING));
        boolean canShare = !labBusy && !state.active && !recordingActive
                && state.completed != null;
        binding.buttonCensorLabAttach.setEnabled(canShare);
        binding.buttonCensorLabAttach.setText(state.completed != null
                && state.completed.video != null
                ? R.string.diagnostics_lab_share_capture
                : R.string.diagnostics_lab_share_bundle);
        binding.buttonCensorLabShareTrace.setEnabled(canShare);
        binding.buttonCensorLabSave.setEnabled(canShare);
        if (recording.phase == CensorLabRecordingService.Phase.READY
                && shareWhenReady && recording.bundle != null) {
            shareWhenReady = false;
            labBusy = false;
            binding.getRoot().post(() -> shareBundle(recording.bundle));
        }
    }

    private void showLabFailure(Exception error) {
        String detail = error == null || error.getMessage() == null
                ? "Unknown failure" : error.getMessage();
        Toast.makeText(this, getString(R.string.diagnostics_lab_failed, detail),
                Toast.LENGTH_LONG).show();
    }

    private static String yesNo(boolean value) { return value ? "Granted" : "Not granted"; }
    private static String running(boolean value) { return value ? "Running" : "Stopped"; }

    private static String duration(long milliseconds) {
        long seconds = milliseconds / 1000;
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }
}
