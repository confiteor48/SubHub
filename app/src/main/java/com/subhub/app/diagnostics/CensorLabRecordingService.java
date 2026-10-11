package com.subhub.app.diagnostics;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaMetadataRetriever;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.StatFs;
import android.os.SystemClock;
import android.view.Display;
import android.view.Surface;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.subhub.app.R;
import com.subhub.app.capture.MediaProjectionLeaseRegistry;
import com.subhub.app.service.ScreenCaptureService;
import com.subhub.app.service.ScreenshotAccessibilityService;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Explicit, bounded, video-only MediaProjection recorder for Censor Lab sessions. */
public final class CensorLabRecordingService extends Service {
    private static final String CHANNEL_ID = "subhub_censor_lab";
    private static final int NOTIFICATION_ID = 1704;
    private static final String ACTION_START = "com.subhub.app.action.START_CENSOR_LAB_RECORDING";
    private static final String ACTION_STOP = "com.subhub.app.action.STOP_CENSOR_LAB_RECORDING";
    private static final String EXTRA_RESULT_CODE = "projection_result_code";
    private static final String EXTRA_RESULT_DATA = "projection_result_data";
    private static final String EXTRA_STOP_DELAY_MS = "stop_delay_ms";
    private static final String EXTRA_STOP_REASON = "stop_reason";
    private static final long MAX_DURATION_MS = 150_000L;
    private static final long MAX_VIDEO_BYTES = 256L * 1024L * 1024L;
    private static final long MIN_FREE_BYTES = 384L * 1024L * 1024L;
    private static final long MIN_VALID_VIDEO_BYTES = 1_024L;
    private static final Object STATE_LOCK = new Object();
    private static volatile Snapshot state = Snapshot.idle();

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Object resourceLock = new Object();
    private final Object stopRequestLock = new Object();
    private final AtomicBoolean terminal = new AtomicBoolean();
    private final AtomicBoolean projectionLeaseHeld = new AtomicBoolean();
    private final Object censorSessionLock = new Object();
    private CensorLabCensorSession.Lease censorSession;
    private boolean censorSessionRetired;
    private ScheduledExecutorService worker;
    private MediaProjection projection;
    private MediaProjection.Callback projectionCallback;
    private MediaRecorder recorder;
    private Surface recorderSurface;
    private VirtualDisplay virtualDisplay;
    private File videoFile;
    private boolean recorderStarted;
    private DisplayManager displayManager;
    private DisplayManager.DisplayListener displayListener;
    private int sourceWidth;
    private int sourceHeight;
    private int sourceRotation;
    private ScheduledFuture<?> stopTask;
    private long stopDeadlineUptime = Long.MAX_VALUE;

    public enum Phase { IDLE, STARTING, RECORDING, STOPPING, READY, FAILED }

    public static Intent startIntent(Context context, int resultCode, Intent resultData) {
        return new Intent(context, CensorLabRecordingService.class)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, resultData);
    }

    public static Intent stopIntent(Context context, long delayMillis, String reason) {
        return new Intent(context, CensorLabRecordingService.class)
                .setAction(ACTION_STOP)
                .putExtra(EXTRA_STOP_DELAY_MS, Math.max(0L, delayMillis))
                .putExtra(EXTRA_STOP_REASON, safeReason(reason));
    }

    public static Snapshot snapshot() { return state; }

    public static boolean isActive() {
        Phase phase = state.phase;
        return phase == Phase.STARTING || phase == Phase.RECORDING || phase == Phase.STOPPING;
    }

    @Override public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        worker = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "censor-lab-recorder");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        if (ACTION_STOP.equals(intent.getAction())) {
            if (isActive()) {
                requestStop(intent.getLongExtra(EXTRA_STOP_DELAY_MS, 0L),
                        intent.getStringExtra(EXTRA_STOP_REASON));
            } else {
                // A delayed/duplicate stop after finalization must not recreate an idle service
                // record which can look like an active MediaProjection owner.
                stopSelf(startId);
            }
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(intent.getAction()) || isActive() || terminal.get()) {
            return START_NOT_STICKY;
        }
        if (!MediaProjectionLeaseRegistry.acquire(
                MediaProjectionLeaseRegistry.Owner.CENSOR_LAB)) {
            setState(new Snapshot(Phase.FAILED, null, null,
                    "Another screen-capture session is already active"));
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        projectionLeaseHeld.set(true);

        setState(new Snapshot(Phase.STARTING, null, null, null));
        NotificationManager notifications = getSystemService(NotificationManager.class);
        if (notifications != null) notifications.cancel(NOTIFICATION_ID);
        try {
            beginForeground(buildRecordingNotification(false));
        } catch (Exception error) {
            terminal.set(true);
            setState(new Snapshot(Phase.FAILED, null, null, safeFailure(error)));
            stopSelf();
            return START_NOT_STICKY;
        }
        Intent request = new Intent(intent);
        worker.execute(() -> startRecording(request));
        return START_NOT_STICKY;
    }

    private void startRecording(Intent request) {
        synchronized (resourceLock) {
            try {
                if (ScreenCaptureService.isRunning()) {
                    throw new IllegalStateException("Screen Capture mode is already active");
                }
                if (!ScreenshotAccessibilityService.isRunning()) {
                    throw new IllegalStateException("Accessibility service is not connected");
                }
                int resultCode = request.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED);
                Intent resultData = projectionData(request);
                if (resultCode != Activity.RESULT_OK || resultData == null) {
                    throw new SecurityException("Android screen-capture approval was not granted");
                }

                CensorLabRecorder.SessionState session = CensorLabRecorder.start(this);
                videoFile = CensorLabRecorder.activeVideoFile(this);
                ensureStorage(videoFile);
                if (startupCancelled()) return;

                MediaProjectionManager manager = (MediaProjectionManager)
                        getSystemService(Context.MEDIA_PROJECTION_SERVICE);
                projection = manager == null ? null
                        : manager.getMediaProjection(resultCode, resultData);
                if (projection == null) throw new SecurityException("Invalid screen-capture grant");
                projectionCallback = new MediaProjection.Callback() {
                    private int lastWidth;
                    private int lastHeight;

                    @Override public void onStop() {
                        requestStop(0L, "projection-revoked");
                    }

                    @Override public void onCapturedContentResize(int width, int height) {
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return;
                        if (lastWidth == 0 || lastHeight == 0) {
                            lastWidth = width;
                            lastHeight = height;
                            return;
                        }
                        if (width != lastWidth || height != lastHeight) {
                            requestStop(0L, "captured-content-resized");
                        }
                    }
                };
                projection.registerCallback(projectionCallback, mainHandler);
                if (startupCancelled()) return;

                DisplaySpec display = displaySpec();
                sourceWidth = display.width;
                sourceHeight = display.height;
                sourceRotation = display.rotation;
                PreparedRecorder prepared = prepareRecorder(display);
                recorder = prepared.recorder;
                recorderSurface = recorder.getSurface();
                if (startupCancelled()) return;
                virtualDisplay = projection.createVirtualDisplay(
                        "SubHubCensorLab",
                        prepared.profile.width,
                        prepared.profile.height,
                        display.densityDpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        recorderSurface,
                        null,
                        mainHandler);
                if (virtualDisplay == null) throw new IOException("Could not create capture display");
                if (startupCancelled()) return;
                recorder.start();
                recorderStarted = true;
                if (startupCancelled()) return;
                synchronized (censorSessionLock) {
                    if (censorSessionRetired) return;
                    censorSession = CensorLabCensorSession.acquire();
                }
                CensorLabRecorder.markCensorSession(true);
                CensorLabRecorder.markVideoStarted(
                        prepared.profile.width, prepared.profile.height,
                        prepared.profile.frameRate, prepared.profile.bitRate);
                if (startupCancelled()) return;
                registerDisplayListener();
                setState(new Snapshot(Phase.RECORDING, session.id, null, null));
                updateForeground(buildRecordingNotification(true));
                worker.schedule(() -> requestStop(0L, "duration-limit"),
                        MAX_DURATION_MS, TimeUnit.MILLISECONDS);
                worker.scheduleWithFixedDelay(() -> {
                    File current = videoFile;
                    if (current != null && current.isFile()
                            && current.length() >= MAX_VIDEO_BYTES) {
                        requestStop(0L, "size-limit");
                    }
                }, 1L, 1L, TimeUnit.SECONDS);
            } catch (Exception error) {
                failStart(error);
            }
        }
    }

    private PreparedRecorder prepareRecorder(DisplaySpec display) throws IOException {
        List<CensorLabVideoProfile> requested = CensorLabVideoProfile.candidates(
                display.width, display.height, display.refreshRateHz);
        List<CensorLabVideoProfile> ordered = new ArrayList<>();
        for (CensorLabVideoProfile profile : requested) {
            if (CensorLabVideoProfile.hasEncoderSupport(profile)) ordered.add(profile);
        }
        for (CensorLabVideoProfile profile : requested) {
            if (!ordered.contains(profile)) ordered.add(profile);
        }
        Exception latest = null;
        for (CensorLabVideoProfile profile : ordered) {
            MediaRecorder candidate = null;
            try {
                if (videoFile.isFile()) videoFile.delete();
                candidate = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                        ? new MediaRecorder(this) : new MediaRecorder();
                candidate.setVideoSource(MediaRecorder.VideoSource.SURFACE);
                candidate.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                candidate.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
                candidate.setVideoSize(profile.width, profile.height);
                candidate.setVideoFrameRate(profile.frameRate);
                candidate.setVideoEncodingBitRate(profile.bitRate);
                candidate.setMaxDuration((int) MAX_DURATION_MS);
                try {
                    candidate.setMaxFileSize(MAX_VIDEO_BYTES);
                } catch (RuntimeException ignored) {
                    // The service also enforces this limit from the private file length.
                }
                candidate.setOrientationHint(0);
                candidate.setOutputFile(videoFile.getAbsolutePath());
                candidate.setOnInfoListener((ignored, what, extra) -> {
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                        requestStop(0L, "duration-limit");
                    } else if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) {
                        requestStop(0L, "size-limit");
                    }
                });
                candidate.setOnErrorListener((ignored, what, extra) ->
                        requestStop(0L, "recorder-error"));
                candidate.prepare();
                return new PreparedRecorder(candidate, profile);
            } catch (Exception error) {
                latest = error;
                if (candidate != null) {
                    try { candidate.release(); } catch (RuntimeException ignored) {}
                }
            }
        }
        throw new IOException("No compatible H.264 screen-recording profile", latest);
    }

    private void requestStop(long delayMillis, String reason) {
        ScheduledExecutorService executor = worker;
        if (executor == null || executor.isShutdown()) return;
        long boundedDelay = Math.max(0L, delayMillis);
        long deadline = SystemClock.uptimeMillis() + boundedDelay;
        synchronized (stopRequestLock) {
            Snapshot current = state;
            if (current.phase == Phase.READY || current.phase == Phase.FAILED
                    || current.phase == Phase.IDLE || terminal.get()) return;
            if (stopTask != null && !stopTask.isDone()
                    && deadline >= stopDeadlineUptime) return;
            if (stopTask != null) stopTask.cancel(false);
            stopDeadlineUptime = deadline;
            setState(new Snapshot(Phase.STOPPING, current.sessionId, null, null));
            stopTask = executor.schedule(() -> finishRecording(safeReason(reason)),
                    boundedDelay, TimeUnit.MILLISECONDS);
        }
    }

    private void finishRecording(String reason) {
        if (!terminal.compareAndSet(false, true)) return;
        synchronized (resourceLock) {
            unregisterDisplayListener();
            CensorLabRecorder.mark("VIDEO_STOP_REQUESTED reason=" + safeReason(reason));
            boolean validVideo = false;
            if (recorder != null && recorderStarted) {
                try {
                    recorder.stop();
                } catch (RuntimeException ignored) {
                    // Limit/revocation callbacks can race MediaRecorder's own asynchronous stop.
                    // Validate the finalized container instead of treating that race as data loss.
                }
            }
            recorderStarted = false;
            releaseCensorSession();
            releaseCaptureResources(true);
            validVideo = isPlayableVideo(videoFile)
                    && videoFile.length() <= MAX_VIDEO_BYTES;
            long bytes = videoFile != null && videoFile.isFile() ? videoFile.length() : 0L;
            if (!validVideo && videoFile != null && videoFile.isFile()) videoFile.delete();
            CensorLabRecorder.markVideoStopped(validVideo, validVideo ? bytes : 0L, reason);
            try {
                CensorLabRecorder.CompletedSession session = CensorLabRecorder.stop(this);
                File bundle = CensorLabBundleExporter.export(this, session, validVideo);
                setState(new Snapshot(Phase.READY,
                        session == null ? null : session.id, bundle, null));
            } catch (Exception error) {
                setState(new Snapshot(Phase.FAILED,
                        CensorLabRecorder.activeSessionId(), null, safeFailure(error)));
            }
            stopForeground(STOP_FOREGROUND_REMOVE);
            releaseProjectionLease();
            if (state.phase == Phase.READY) publishReadyNotification();
            stopSelf();
            ScheduledExecutorService executor = worker;
            if (executor != null) executor.shutdownNow();
        }
    }

    private void failStart(Exception error) {
        if (!terminal.compareAndSet(false, true)) return;
        releaseCensorSession();
        unregisterDisplayListener();
        releaseCaptureResources(true);
        if (videoFile != null && videoFile.isFile()) videoFile.delete();
        if (CensorLabRecorder.isActive()) {
            CensorLabRecorder.mark("VIDEO_START_FAILED type=" + error.getClass().getSimpleName());
            CensorLabRecorder.markVideoStopped(false, 0L, "start-failed");
            try { CensorLabRecorder.stop(this); } catch (IOException ignored) {}
        }
        setState(new Snapshot(Phase.FAILED, null, null, safeFailure(error)));
        stopForeground(STOP_FOREGROUND_REMOVE);
        releaseProjectionLease();
        stopSelf();
        ScheduledExecutorService executor = worker;
        if (executor != null) executor.shutdownNow();
    }

    private void releaseCensorSession() {
        CensorLabCensorSession.Lease retired;
        synchronized (censorSessionLock) {
            censorSessionRetired = true;
            retired = censorSession;
            censorSession = null;
        }
        if (retired != null) {
            retired.close();
            CensorLabRecorder.markCensorSession(false);
        }
    }

    private void releaseCaptureResources(boolean stopProjection) {
        if (recorder != null) {
            try { recorder.release(); } catch (RuntimeException ignored) {}
            recorder = null;
        }
        if (virtualDisplay != null) {
            try { virtualDisplay.release(); } catch (RuntimeException ignored) {}
            virtualDisplay = null;
        }
        if (recorderSurface != null) {
            try { recorderSurface.release(); } catch (RuntimeException ignored) {}
            recorderSurface = null;
        }
        if (projection != null) {
            if (projectionCallback != null) {
                try { projection.unregisterCallback(projectionCallback); }
                catch (RuntimeException ignored) {}
            }
            if (stopProjection) {
                try { projection.stop(); } catch (RuntimeException ignored) {}
            }
            projection = null;
            projectionCallback = null;
        }
    }

    private void releaseProjectionLease() {
        if (projectionLeaseHeld.compareAndSet(true, false)) {
            MediaProjectionLeaseRegistry.release(
                    MediaProjectionLeaseRegistry.Owner.CENSOR_LAB);
        }
    }

    private void registerDisplayListener() {
        displayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        if (displayManager == null) return;
        displayListener = new DisplayManager.DisplayListener() {
            @Override public void onDisplayAdded(int displayId) {}
            @Override public void onDisplayRemoved(int displayId) {}
            @Override public void onDisplayChanged(int displayId) {
                if (displayId != Display.DEFAULT_DISPLAY || terminal.get()) return;
                DisplaySpec current = displaySpec();
                if (current.width != sourceWidth || current.height != sourceHeight
                        || current.rotation != sourceRotation) {
                    requestStop(0L, "display-changed");
                }
            }
        };
        displayManager.registerDisplayListener(displayListener, mainHandler);
    }

    private void unregisterDisplayListener() {
        if (displayManager != null && displayListener != null) {
            try { displayManager.unregisterDisplayListener(displayListener); }
            catch (RuntimeException ignored) {}
        }
        displayListener = null;
        displayManager = null;
    }

    private DisplaySpec displaySpec() {
        WindowManager windows = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
        int width;
        int height;
        int rotation = Surface.ROTATION_0;
        float refreshRate = 60f;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Rect bounds = windows.getMaximumWindowMetrics().getBounds();
            width = bounds.width();
            height = bounds.height();
            DisplayManager displays = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
            Display display = displays == null ? null
                    : displays.getDisplay(Display.DEFAULT_DISPLAY);
            if (display != null) {
                rotation = display.getRotation();
                refreshRate = display.getRefreshRate();
            }
        } else {
            Display display = windows.getDefaultDisplay();
            display.getRealMetrics(metrics);
            width = metrics.widthPixels;
            height = metrics.heightPixels;
            rotation = display.getRotation();
            refreshRate = display.getRefreshRate();
        }
        return new DisplaySpec(width, height, metrics.densityDpi, rotation, refreshRate);
    }

    private static boolean isPlayableVideo(File file) {
        if (file == null || !file.isFile() || file.length() < MIN_VALID_VIDEO_BYTES) return false;
        MediaMetadataRetriever metadata = new MediaMetadataRetriever();
        try {
            metadata.setDataSource(file.getAbsolutePath());
            String duration = metadata.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION);
            return duration != null && Long.parseLong(duration) > 0L;
        } catch (Exception ignored) {
            return false;
        } finally {
            try { metadata.release(); } catch (Exception ignored) {}
        }
    }

    private boolean startupCancelled() {
        Phase phase = state.phase;
        return terminal.get() || phase == Phase.STOPPING
                || phase == Phase.FAILED || phase == Phase.READY;
    }

    private void ensureStorage(File output) throws IOException {
        File parent = output.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
            throw new IOException("Could not create the recording directory");
        }
        if (new StatFs(parent.getAbsolutePath()).getAvailableBytes() < MIN_FREE_BYTES) {
            throw new IOException("Not enough free space for a Censor Lab recording");
        }
    }

    private Intent projectionData(Intent source) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return source.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class);
        }
        @SuppressWarnings("deprecation")
        Intent value = source.getParcelableExtra(EXTRA_RESULT_DATA);
        return value;
    }

    private void beginForeground(Notification notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void updateForeground(Notification notification) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(NOTIFICATION_ID, notification);
    }

    private Notification buildRecordingNotification(boolean active) {
        Intent open = new Intent(this, DiagnosticsActivity.class);
        PendingIntent openApp = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                stopIntent(this, 0L, "notification-stop"),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(getString(R.string.diagnostics_lab_notification_title))
                .setContentText(getString(active
                        ? R.string.diagnostics_lab_notification_recording
                        : R.string.diagnostics_lab_notification_starting))
                .setContentIntent(openApp)
                .setOngoing(true)
                .setSilent(true);
        if (active) builder.addAction(0, getString(R.string.diagnostics_lab_stop), stop);
        return com.subhub.app.privacy.PrivacyNotifications.present(this, NOTIFICATION_ID, builder.build());
    }

    private void publishReadyNotification() {
        Intent open = new Intent(this, DiagnosticsActivity.class);
        PendingIntent openApp = PendingIntent.getActivity(this, 2, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification ready = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(getString(R.string.diagnostics_lab_ready_title))
                .setContentText(getString(R.string.diagnostics_lab_ready_notification))
                .setContentIntent(openApp)
                .setAutoCancel(true)
                .setSilent(true)
                .build();
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(NOTIFICATION_ID, com.subhub.app.privacy.PrivacyNotifications.present(this, NOTIFICATION_ID, ready));
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.diagnostics_lab_notification_channel),
                NotificationManager.IMPORTANCE_LOW);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(channel);
    }

    private static void setState(Snapshot next) {
        synchronized (STATE_LOCK) { state = next; }
    }

    private static String safeFailure(Throwable error) {
        if (error == null) return "Unknown failure";
        String message = error.getMessage();
        if (message != null && (message.startsWith("Screen Capture mode")
                || message.startsWith("Another screen-capture")
                || message.startsWith("Android screen-capture")
                || message.startsWith("Accessibility service")
                || message.startsWith("Not enough free space")
                || message.startsWith("No compatible H.264"))) return message;
        return error.getClass().getSimpleName();
    }

    private static String safeReason(String reason) {
        if (reason == null || reason.trim().isEmpty()) return "unknown";
        String safe = reason.replaceAll("[^a-zA-Z0-9._-]", "-");
        return safe.substring(0, Math.min(64, safe.length()));
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        releaseCensorSession();
        if (!terminal.get() && isActive()) {
            // Never stop MediaRecorder or stream a ZIP on Android's service/main thread.
            // The foreground worker retains this service instance until finalization completes.
            requestStop(0L, "service-destroyed");
        } else if (worker != null && !worker.isShutdown()) {
            worker.shutdownNow();
        }
        if (terminal.get()) releaseProjectionLease();
        super.onDestroy();
    }

    public static final class Snapshot {
        public final Phase phase;
        public final String sessionId;
        public final File bundle;
        public final String failure;

        Snapshot(Phase phase, String sessionId, File bundle, String failure) {
            this.phase = phase;
            this.sessionId = sessionId;
            this.bundle = bundle;
            this.failure = failure;
        }

        static Snapshot idle() { return new Snapshot(Phase.IDLE, null, null, null); }
    }

    private static final class PreparedRecorder {
        final MediaRecorder recorder;
        final CensorLabVideoProfile profile;

        PreparedRecorder(MediaRecorder recorder, CensorLabVideoProfile profile) {
            this.recorder = recorder;
            this.profile = profile;
        }
    }

    private static final class DisplaySpec {
        final int width;
        final int height;
        final int densityDpi;
        final int rotation;
        final float refreshRateHz;

        DisplaySpec(int width, int height, int densityDpi, int rotation,
                float refreshRateHz) {
            this.width = width;
            this.height = height;
            this.densityDpi = densityDpi;
            this.rotation = rotation;
            this.refreshRateHz = refreshRateHz;
        }
    }
}
