package com.subhub.app.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.subhub.app.MainActivity;
import com.subhub.app.R;
import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.appmode.ForegroundAppState;
import com.subhub.app.appmode.ProtectionSessionManager;
import com.subhub.app.appmode.ResumeNotificationManager;
import com.subhub.app.capture.MediaProjectionLeaseRegistry;
import com.subhub.app.capture.ScreenCaptureManager;
import com.subhub.app.commitment.CommitmentManager;
import com.subhub.app.detection.Detection;
import com.subhub.app.detection.DetectionEngine;
import com.subhub.app.detection.DetectorConfig;
import com.subhub.app.detection.ObjectTracker;
import com.subhub.app.detection.TrackedObject;
import com.subhub.app.diagnostics.DiagnosticsRepository;
import com.subhub.app.overlay.OverlayController;
import com.subhub.app.penance.CensorTapTracker;
import com.subhub.app.penance.DwellInfractionTracker;
import com.subhub.app.penance.PenanceChargeNotifier;
import com.subhub.app.penance.PenanceInfraction;
import com.subhub.app.penance.PenanceManager;
import com.subhub.app.popup.PopupStormManager;
import com.subhub.app.security.HardcoreModeManager;
import com.subhub.app.security.ProtectionStopPolicy;

import com.subhub.app.settings.CensorAppearance;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.stats.AchievementManager;
import com.subhub.app.stats.StatsRepository;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Foreground pipeline: capture -> local ONNX inference -> tracking -> touch-through overlay. */
public final class ScreenCaptureService extends Service {
    private static final String TAG = "ScreenCaptureService";
    private static final String CHANNEL_ID = "subhub_protection";
    private static final int NOTIFICATION_ID = 1701;
    private static final String ACTION_START = "com.subhub.app.action.START";
    private static final String ACTION_STOP = "com.subhub.app.action.STOP";
    private static final String EXTRA_RESULT_CODE = "projection_result_code";
    private static final String EXTRA_RESULT_DATA = "projection_result_data";
    private static final String DIAGNOSTICS_MODE = "MediaProjection";
    private static volatile boolean running;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean firstFrameReported = new AtomicBoolean();
    private final SharedPreferences.OnSharedPreferenceChangeListener settingsListener =
            (preferences, key) -> reloadSettings();
    private ScheduledExecutorService executor;
    private ExecutorService inferenceExecutor;
    private LatestFrameBroker<ProjectionFrame> frameBroker;
    private CaptureLoadGovernor loadGovernor;
    private MediaProjection projection;
    private ScreenCaptureManager capture;
    private ForegroundAppState.Snapshot clearedScope, processedScope;
    private DetectionEngine detector;
    private ObjectTracker tracker;
    private OverlayController overlay;
    private SettingsRepository settings;
    private StatsRepository stats;
    private PenanceManager penance;
    private final DwellInfractionTracker dwellTracker = new DwellInfractionTracker();
    private final CensorTapTracker tapTracker = CensorTapTracker.shared();
    private volatile DetectorConfig detectorConfig;
    private volatile boolean overlayNeedsSourceFrame;
    private volatile boolean explicitlyStoppedByController;
    private final AtomicBoolean projectionLeaseHeld = new AtomicBoolean();

    public static Intent startIntent(Context context, int resultCode, Intent resultData) {
        return new Intent(context, ScreenCaptureService.class)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, resultData);
    }

    public static Intent stopIntent(Context context) {
        return new Intent(context, ScreenCaptureService.class).setAction(ACTION_STOP);
    }

    public static boolean isRunning() { return running; }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        executor = Executors.newSingleThreadScheduledExecutor();
        inferenceExecutor = Executors.newSingleThreadExecutor();
        loadGovernor = new CaptureLoadGovernor(this);
        settings = new SettingsRepository(this);
        stats = new StatsRepository(this);
        penance = new PenanceManager(this);
        settings.preferences().registerOnSharedPreferenceChangeListener(settingsListener);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        if (ACTION_STOP.equals(intent.getAction())) {
            if (ProtectionStopPolicy.decision(this)
                    != ProtectionStopPolicy.Decision.ALLOW) return START_STICKY;
            explicitlyStoppedByController = true;
            ProtectionSessionManager.markMediaProjectionExplicitlyStopped(this);
            new AppModeManager(this).setArmed(false);
            ResumeNotificationManager.cancel(this);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(intent.getAction()) || running) return START_NOT_STICKY;
        if (!MediaProjectionLeaseRegistry.acquire(
                MediaProjectionLeaseRegistry.Owner.PROTECTION)) {
            explicitlyStoppedByController = true;
            ProtectionSessionManager.markMediaProjectionExplicitlyStopped(this);
            new AppModeManager(this).setArmed(false);
            ResumeNotificationManager.cancel(this);
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        projectionLeaseHeld.set(true);

        startForeground(NOTIFICATION_ID, buildNotification());
        Intent projectionData = projectionData(intent);
        if (projectionData == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        projection = manager.getMediaProjection(
                intent.getIntExtra(EXTRA_RESULT_CODE, 0), projectionData);
        if (projection == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                stopSelf();
            }
        }, mainHandler);

        running = true;
        ProtectionSessionManager.markMediaProjectionStarted(this);
        new AppModeManager(this).setArmed(true);
        stats.startSession();
        overlay = new OverlayController(this);
        CensorAppearance appearance = settings.loadAppearance();
        overlayNeedsSourceFrame = appearance.requiresSourceFrame();
        overlay.setAppearance(appearance);
        overlay.setMaxExtrapolationMs(settings.loadDetectorConfig().getMaxExtrapolationMs());
        overlay.show();
        PopupStormManager.get().start(this);
        executor.execute(this::startPipeline);
        return START_NOT_STICKY;
    }

    private void startPipeline() {
        try {
            DetectorConfig config = settings.loadDetectorConfig();
            detectorConfig = config;
            DiagnosticsRepository.begin(DIAGNOSTICS_MODE, config.getInferenceResolution());
            detector = new DetectionEngine(this, config);
            detector.initialize();
            DiagnosticsRepository.ready(DIAGNOSTICS_MODE, detector.getActiveProvider(),
                    detector.getActiveModel(), config.getInferenceResolution());
            tracker = new ObjectTracker(config);

            DisplayInfo display = displayInfo();
            capture = new ScreenCaptureManager(
                    projection,
                    display.width,
                    display.height,
                    display.densityDpi,
                    config.getInferenceResolution(),
                    config.getCaptureScale());
            capture.start();
            frameBroker = new LatestFrameBroker<>(
                    inferenceExecutor,
                    this::processFrame,
                    ProjectionFrame::close);
            executor.scheduleWithFixedDelay(
                    this::captureLatestFrame,
                    0,
                    16,
                    TimeUnit.MILLISECONDS);
        } catch (Exception error) {
            DiagnosticsRepository.fail(DIAGNOSTICS_MODE, error);
            Log.e(TAG, "Could not start on-device protection", error);
            stopSelf();
        }
    }

    private void reloadSettings() {
        if (overlay != null) overlay.clearPersonCoverage();
        if (!censorConfigured()) {
            if (overlay != null) overlay.clear();
            if (tracker != null) tracker.clear();
            tapTracker.clear();
            dwellTracker.clear();
            PopupStormManager.get().updateDetections(java.util.Collections.emptyList());
        }
        CensorAppearance appearance = settings.loadAppearance();
        overlayNeedsSourceFrame = appearance.requiresSourceFrame();
        if (overlay != null) overlay.setAppearance(appearance);
        if (overlay != null) overlay.setDiagnostics(diagnosticsOverlayText());
        DetectorConfig config = settings.loadDetectorConfig();
        detectorConfig = config;
        if (overlay != null) overlay.setMaxExtrapolationMs(config.getMaxExtrapolationMs());
        if (detector != null) detector.setConfig(config);
        if (tracker != null) tracker.setConfig(config);
        PopupStormManager.get().reloadSettings(this);
        PopupStormManager.get().syncServiceParticipation(this,
                running && new AppModeManager(this).shouldShowPopups(
                                                ForegroundAppState.snapshot().packageName));
    }

    private void captureLatestFrame() {
        if (!running || capture == null) return;
        ForegroundAppState.Snapshot scope = ForegroundAppState.snapshot();
        if (!censorConfigured()) {
            if (clearedScope != scope) {
                clearedScope = scope;
                mainHandler.post(
                        () -> {
                            if (overlay != null && !censorConfigured()) overlay.clear();
                        });
            }
            return;
        }
        clearedScope = null;
        DetectorConfig currentConfig = detectorConfig;
        if (loadGovernor != null && !loadGovernor.shouldCapture(
                SystemClock.uptimeMillis(),
                currentConfig == null ? 0L : currentConfig.getDetectionIntervalMs())) return;
        try {
            Bitmap frame = capture.acquireLatestFrame();
            LatestFrameBroker<ProjectionFrame> broker = frameBroker;
            if (frame == null) return;
            if (broker == null) frame.recycle();
            else {
                broker.submit(new ProjectionFrame(frame, capture, SystemClock.uptimeMillis(), scope));
            }
        } catch (Exception error) {
            DiagnosticsRepository.fail(DIAGNOSTICS_MODE, error);
            Log.w(TAG, "Frame capture failed", error);
        }
    }

    private void processFrame(ProjectionFrame candidate) {
        Bitmap frame = candidate.bitmap();
        try {
            if (!running || !censorConfigured() || !ForegroundAppState.isCurrent(candidate.scope)
                    || frame == null || frame.isRecycled()) return;
            if (processedScope != candidate.scope) {
                tracker.clear();
                tapTracker.clear();
                dwellTracker.clear();
                processedScope = candidate.scope;
            }
            List<Detection> detections = detector.detect(frame);
            if (!censorConfigured() || !ForegroundAppState.isCurrent(candidate.scope)) return;
            List<TrackedObject> tracks = tracker.update(detections);
            DetectorConfig currentConfig = detectorConfig;
            int recordedBlocks = stats.recordTracks(tracks, currentConfig == null
                    ? null : currentConfig.getEnabledCategories());
            long now = System.currentTimeMillis();
            if (recordedBlocks > 0) {
                int charged = penance.recordInfraction(
                        PenanceInfraction.NEW_DETECTION, recordedBlocks, now);
                PenanceChargeNotifier.show(this, penance,
                        PenanceInfraction.NEW_DETECTION, charged, now);
                new AchievementManager(this).checkAchievements(stats.load());
            }
            int dwellInfractions = dwellTracker.update(
                    tracks, now, penance.getDwellSeconds() * 1_000L);
            if (dwellInfractions > 0) {
                int charged = penance.recordInfraction(
                        PenanceInfraction.CENSORED_DWELL, dwellInfractions, now);
                PenanceChargeNotifier.show(this, penance,
                        PenanceInfraction.CENSORED_DWELL, charged, now);
            }
            int width = capture.getCaptureWidth();
            int height = capture.getCaptureHeight();
            PopupStormManager.get().updateTrackedObjects(tracks, width, height);
            DiagnosticsRepository.Snapshot diagnostics = DiagnosticsRepository.recordFrame(
                    DIAGNOSTICS_MODE,
                    detector.getLastInferenceMs(),
                    detector.getLastPreprocessMs(),
                    detector.getLastRuntimeMs(),
                    detector.getLastPostprocessMs(),
                    SystemClock.uptimeMillis() - candidate.capturedAtUptimeMillis,
                    frameBroker == null ? 0L : frameBroker.droppedCount(),
                    tracks.size(),
                    width,
                    height);
            String diagnosticText = diagnosticsOverlayText(diagnostics);
            Bitmap overlayFrame = overlayNeedsSourceFrame ? candidate.detachBitmap() : null;
            Runnable overlayFrameRelease = overlayFrame == null ? null
                    : () -> candidate.releaseDetachedBitmap(overlayFrame);
            if (firstFrameReported.compareAndSet(false, true)) {
                Log.i(TAG, "First frame processed with "
                        + detector.getActiveModel() + " on " + detector.getActiveProvider()
                        + " in " + detector.getLastInferenceMs() + " ms at "
                        + width + "x" + height);
            }
            mainHandler.post(() -> {
                if (overlay != null && censorConfigured()
                                && ForegroundAppState.isCurrent(candidate.scope)) {
                    overlay.setDiagnostics(diagnosticText);
                    overlay.setTapSourceLabOwned(false);
                    if (overlayFrameRelease == null) {
                        overlay.update(tracks, width, height, overlayFrame);
                    } else {
                        overlay.updatePooledFrame(
                                tracks, width, height, overlayFrame, overlayFrameRelease);
                    }
                    DiagnosticsRepository.recordPublishDelay(DIAGNOSTICS_MODE,
                            SystemClock.uptimeMillis() - candidate.capturedAtUptimeMillis);
                }
                else if (overlayFrameRelease != null) overlayFrameRelease.run();
                else if (overlayFrame != null) overlayFrame.recycle();
            });
        } catch (Exception error) {
            DiagnosticsRepository.fail(DIAGNOSTICS_MODE, error);
            Log.w(TAG, "Frame processing failed", error);
        }
    }

    private DisplayInfo displayInfo() {
        WindowManager manager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Rect bounds = manager.getMaximumWindowMetrics().getBounds();
            return new DisplayInfo(bounds.width(), bounds.height(), metrics.densityDpi);
        }
        manager.getDefaultDisplay().getRealMetrics(metrics);
        return new DisplayInfo(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi);
    }

    private Intent projectionData(Intent source) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return source.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class);
        }
        @SuppressWarnings("deprecation")
        Intent value = source.getParcelableExtra(EXTRA_RESULT_DATA);
        return value;
    }

    private String diagnosticsOverlayText() {
        return diagnosticsOverlayText(DiagnosticsRepository.snapshot());
    }

    private String diagnosticsOverlayText(DiagnosticsRepository.Snapshot snapshot) {
        return settings != null && settings.preferences().getBoolean(
                DiagnosticsRepository.PREF_OVERLAY, false)
                ? DiagnosticsRepository.overlayText(snapshot) : "";
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openApp = PendingIntent.getActivity(
                this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(
                this, 1, stopIntent(this), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_text))
                .setContentIntent(openApp)
                .setOngoing(true)
                .setSilent(true);
        if (ProtectionStopPolicy.showNotificationStop(this)) {
            builder.addAction(0, getString(R.string.notification_stop), stop);
        }
        return com.subhub.app.privacy.PrivacyNotifications.present(this, NOTIFICATION_ID, builder.build());
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    private boolean censorConfigured() {
        return settings != null && new AppModeManager(this)
                        .shouldCensorForeground(ForegroundAppState.snapshot().packageName);
    }

    @Override
    public void onDestroy() {
        running = false;
        new AppModeManager(this).setArmed(false);
        DiagnosticsRepository.stop(DIAGNOSTICS_MODE);
        if (stats != null) stats.endSession();
        if (settings != null) {
            settings.preferences().unregisterOnSharedPreferenceChangeListener(settingsListener);
        }
        if (executor != null) executor.shutdownNow();
        if (frameBroker != null) frameBroker.close();
        frameBroker = null;
        if (inferenceExecutor != null) {
            inferenceExecutor.shutdownNow();
            try {
                inferenceExecutor.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        if (capture != null) capture.close();
        if (loadGovernor != null) loadGovernor.close();
        loadGovernor = null;
        if (detector != null) detector.close();
        dwellTracker.clear();
        tapTracker.clear();
        OverlayController activeOverlay = overlay;
        overlay = null;
        if (activeOverlay != null) activeOverlay.close();
        PopupStormManager.get().stop();
        if (projection != null) projection.stop();
        if (projectionLeaseHeld.compareAndSet(true, false)) {
            MediaProjectionLeaseRegistry.release(
                    MediaProjectionLeaseRegistry.Owner.PROTECTION);
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        if (!explicitlyStoppedByController) {
            CommitmentManager.reinforceProtection(this);
            new HardcoreModeManager(this).applyBootPolicy();
        }
        super.onDestroy();
    }

    private static final class DisplayInfo {
        private final int width;
        private final int height;
        private final int densityDpi;

        private DisplayInfo(int width, int height, int densityDpi) {
            this.width = width;
            this.height = height;
            this.densityDpi = densityDpi;
        }
    }

    private static final class ProjectionFrame implements AutoCloseable {
        private Bitmap bitmap;
        private final ScreenCaptureManager owner;
        private final long capturedAtUptimeMillis;

        private final ForegroundAppState.Snapshot scope;

        private ProjectionFrame(
                Bitmap bitmap,
                ScreenCaptureManager owner,
                long capturedAtUptimeMillis,
                ForegroundAppState.Snapshot scope) {
            this.bitmap = bitmap;
            this.owner = owner;
            this.capturedAtUptimeMillis = capturedAtUptimeMillis;
            this.scope = scope;
        }

        private Bitmap bitmap() { return bitmap; }

        private Bitmap detachBitmap() {
            Bitmap value = bitmap;
            bitmap = null;
            return value;
        }

        private void releaseDetachedBitmap(Bitmap detached) {
            if (detached != null && !detached.isRecycled()) owner.releaseFrame(detached);
        }

        @Override
        public void close() {
            if (bitmap != null && !bitmap.isRecycled()) owner.releaseFrame(bitmap);
            bitmap = null;
        }
    }
}
