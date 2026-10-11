package com.subhub.app.diagnostics;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;

import com.subhub.app.BuildConfig;
import com.subhub.app.detection.DetectorConfig;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.settings.CensorAppearance;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** Explicit, internal-only telemetry session with an optional user-approved screen recording. */
public final class CensorLabRecorder {
    private static final String PREFERENCES = "censor_lab_sessions";
    private static final String KEY_LATEST = "latest_session";
    private static final String KEY_ACTIVE = "active_session";
    private static final String SESSION_ROOT = "censor-lab/sessions";
    private static final String TRACE_FILE = "trace.ndjson";
    private static final String MANIFEST_FILE = "manifest.json";
    private static final String VIDEO_FILE = "screen-recording.mp4";
    private static final int MAX_COMPLETED_SESSIONS = 3;
    private static final int MAX_FIELD_LENGTH = 2_048;
    private static final Object LOCK = new Object();
    private static final AtomicLong EVENT_SEQUENCE = new AtomicLong();
    private static volatile ActiveSession active;
    private static volatile CompletedSession latestCompleted;
    private static volatile boolean finalizing;

    private CensorLabRecorder() {}

    public static boolean isActive() {
        return active != null;
    }

    public static String activeSessionId() {
        ActiveSession value = active;
        return value == null ? null : value.id;
    }

    public static SessionState state(Context context) {
        ActiveSession value = active;
        if (value != null) {
            return new SessionState(true, value.id, value.startedWallMillis,
                    value.buffer.size(), value.buffer.dropped(), null);
        }
        synchronized (LOCK) {
            if (active == null && !finalizing) {
                discardAbandonedSession(context.getApplicationContext());
            }
        }
        CompletedSession latest = latest(context);
        return latest == null
                ? new SessionState(false, null, 0L, 0, 0L, null)
                : new SessionState(false, latest.id, latest.startedWallMillis,
                        latest.eventCount, latest.droppedEvents, latest);
    }

    public static SessionState start(Context context) throws IOException {
        Context appContext = context.getApplicationContext();
        synchronized (LOCK) {
            if (active != null) return state(appContext);
            if (finalizing) throw new IOException("The previous Censor Lab session is finalizing");
            discardAbandonedSession(appContext);
            String id = shortId();
            File directory = sessionDirectory(appContext, id);
            if (!directory.mkdirs() && !directory.isDirectory()) {
                throw new IOException("Could not create the Censor Lab session directory");
            }
            ActiveSession created = new ActiveSession(id, directory,
                    SystemClock.elapsedRealtimeNanos(), System.currentTimeMillis());
            active = created;
            appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
                    .putString(KEY_ACTIVE, id).commit();
            add(created, "CensorLab", "SYNC_START session=" + id);
            return state(appContext);
        }
    }

    /** Returns the private output file for the active in-app recording session. */
    public static File activeVideoFile(Context context) throws IOException {
        synchronized (LOCK) {
            ActiveSession value = active;
            if (value == null) throw new IOException("No active Censor Lab session");
            File output = new File(value.directory, VIDEO_FILE);
            String root = value.directory.getCanonicalPath() + File.separator;
            if (!output.getCanonicalPath().startsWith(root)) {
                throw new IOException("Invalid Censor Lab recording path");
            }
            return output;
        }
    }

    public static void markVideoStarted(int width, int height, int frameRate, int bitRate) {
        ActiveSession value = active;
        if (value == null) return;
        synchronized (value.eventLock) {
            if (value.closed) return;
            value.videoStartedElapsedNanos = SystemClock.elapsedRealtimeNanos();
            value.videoWidth = Math.max(0, width);
            value.videoHeight = Math.max(0, height);
            value.videoFrameRate = Math.max(0, frameRate);
            value.videoBitRate = Math.max(0, bitRate);
            addLocked(value, "CensorLab", "VIDEO_START width=" + value.videoWidth
                    + " height=" + value.videoHeight + " fps=" + value.videoFrameRate
                    + " bitRate=" + value.videoBitRate);
        }
    }

    public static void markVideoStopped(boolean valid, long bytes, String reason) {
        ActiveSession value = active;
        if (value == null) return;
        synchronized (value.eventLock) {
            if (value.closed) return;
            value.videoStoppedElapsedNanos = SystemClock.elapsedRealtimeNanos();
            value.videoValid = valid;
            value.videoBytes = Math.max(0L, bytes);
            value.videoStopReason = sanitize(reason, "unknown");
            addLocked(value, "CensorLab", "VIDEO_STOP valid=" + valid
                    + " bytes=" + value.videoBytes + " reason=" + value.videoStopReason);
        }
    }

    public static void markCensorSession(boolean started) {
        ActiveSession value = active;
        if (value == null) return;
        synchronized (value.eventLock) {
            if (value.closed) return;
            long now = SystemClock.elapsedRealtimeNanos();
            if (started) {
                value.censorStartedElapsedNanos = now;
            } else {
                value.censorStoppedElapsedNanos = now;
            }
            addLocked(value, "CensorLab", "MARKER CENSOR_SESSION_" + (started ? "STARTED" : "STOPPED"));
        }
    }

    public static void mark(String marker) {
        record("CensorLab", "MARKER " + sanitize(marker, "unknown"));
    }

    public static void record(String tag, String message) {
        ActiveSession value = active;
        if (value == null) return;
        add(value, tag, message);
    }

    public static CompletedSession stop(Context context) throws IOException {
        Context appContext = context.getApplicationContext();
        ActiveSession ending;
        synchronized (LOCK) {
            ending = active;
            if (ending == null) return latest(appContext);
            synchronized (ending.eventLock) {
                addLocked(ending, "CensorLab", "SYNC_STOP session=" + ending.id);
                ending.closed = true;
                finalizing = true;
                active = null;
            }
        }
        try {
            long stoppedElapsedNanos = SystemClock.elapsedRealtimeNanos();
            long stoppedWallMillis = System.currentTimeMillis();
            List<CensorLabEventBuffer.Event> events = ending.buffer.snapshot();
            File trace = new File(ending.directory, TRACE_FILE);
            writeTrace(trace, events);
            File manifest = new File(ending.directory, MANIFEST_FILE);
            writeManifest(appContext, ending, stoppedElapsedNanos, stoppedWallMillis,
                    events.size(), ending.buffer.dropped(), manifest);
            File video = new File(ending.directory, VIDEO_FILE);
            if (!ending.videoValid || !video.isFile() || video.length() <= 0L) video = null;
            CompletedSession completed = new CompletedSession(ending.id, ending.directory,
                    manifest, trace, video, ending.startedWallMillis, stoppedWallMillis,
                    events.size(), ending.buffer.dropped());
            latestCompleted = completed;
            appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
                    .remove(KEY_ACTIVE)
                    .putString(KEY_LATEST, ending.id).commit();
            pruneCompletedSessions(appContext, ending.id);
            return completed;
        } finally {
            finalizing = false;
        }
    }

    public static CompletedSession latest(Context context) {
        CompletedSession cached = latestCompleted;
        if (cached != null && cached.manifest.isFile() && cached.trace.isFile()) return cached;
        SharedPreferences preferences = context.getApplicationContext()
                .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        String id = preferences.getString(KEY_LATEST, null);
        if (!validId(id)) return null;
        File directory = sessionDirectory(context.getApplicationContext(), id);
        File manifest = new File(directory, MANIFEST_FILE);
        File trace = new File(directory, TRACE_FILE);
        if (!manifest.isFile() || !trace.isFile()) return null;
        try {
            JSONObject stored = new JSONObject(readUtf8(manifest));
            File video = new File(directory, VIDEO_FILE);
            if (!video.isFile() || video.length() <= 0L) video = null;
            CompletedSession restored = new CompletedSession(id, directory, manifest, trace, video,
                    stored.optLong("startedWallMillis"), stored.optLong("stoppedWallMillis"),
                    stored.optInt("eventCount"), stored.optLong("droppedEvents"));
            latestCompleted = restored;
            return restored;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void add(ActiveSession session, String tag, String message) {
        synchronized (session.eventLock) {
            if (session.closed) return;
            addLocked(session, tag, message);
        }
    }

    private static void addLocked(ActiveSession session, String tag, String message) {
        session.buffer.offer(new CensorLabEventBuffer.Event(
                EVENT_SEQUENCE.incrementAndGet(), SystemClock.elapsedRealtimeNanos(),
                SystemClock.uptimeMillis(),
                System.currentTimeMillis(), sanitize(Thread.currentThread().getName(), "thread"),
                sanitize(tag, "Unknown"), sanitize(message, "")));
    }

    private static void writeTrace(File output, List<CensorLabEventBuffer.Event> events)
            throws IOException {
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(output), StandardCharsets.UTF_8), 64 * 1024)) {
            for (CensorLabEventBuffer.Event event : events) {
                try {
                    JSONObject line = new JSONObject()
                            .put("sequence", event.sequence)
                            .put("elapsedNanos", event.elapsedNanos)
                            .put("observedUptimeMillis", event.uptimeMillis)
                            .put("wallMillis", event.wallMillis)
                            .put("thread", event.thread)
                            .put("tag", event.tag)
                            .put("message", event.message);
                    writer.write(line.toString());
                    writer.newLine();
                } catch (JSONException error) {
                    throw new IOException("Could not encode Censor Lab telemetry", error);
                }
            }
        }
    }

    private static String readUtf8(File input) throws IOException {
        try (FileInputStream stream = new FileInputStream(input);
                ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = stream.read(buffer)) >= 0) {
                if (read > 0) bytes.write(buffer, 0, read);
            }
            return bytes.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static void writeManifest(Context context, ActiveSession session,
            long stoppedElapsedNanos, long stoppedWallMillis, int eventCount,
            long droppedEvents, File output) throws IOException {
        try {
            SettingsRepository settings = new SettingsRepository(context);
            DetectorConfig config = settings.loadDetectorConfig();
            CensorAppearance appearance = settings.loadAppearance();
            DiagnosticsRepository.Snapshot runtime = DiagnosticsRepository.snapshot();
            DisplayMetrics metrics = context.getResources().getDisplayMetrics();
            float refreshRate = 0f;
            WindowManager windows = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            // This recorder deliberately uses the application Context so it can finish after the
            // Diagnostics activity is gone. Application Context#getDisplay() throws on newer
            // Android releases; WindowManager's default display remains valid for this manifest.
            Display display = windows == null ? null : windows.getDefaultDisplay();
            if (display != null) refreshRate = display.getRefreshRate();
            List<String> categories = new ArrayList<>(config.getEnabledCategories());
            Collections.sort(categories);
            JSONArray enabledCategories = new JSONArray();
            for (String category : categories) enabledCategories.put(category);
            File videoFile = new File(session.directory, VIDEO_FILE);
            boolean videoAttached = session.videoValid && videoFile.isFile()
                    && videoFile.length() > 0L;
            JSONObject manifest = new JSONObject()
                    .put("schemaVersion", 1)
                    .put("traceSchemaVersion", 2)
                    .put("sessionId", session.id)
                    .put("startedElapsedNanos", session.startedElapsedNanos)
                    .put("stoppedElapsedNanos", stoppedElapsedNanos)
                    .put("startedWallMillis", session.startedWallMillis)
                    .put("stoppedWallMillis", stoppedWallMillis)
                    .put("eventCount", eventCount)
                    .put("droppedEvents", droppedEvents)
                    .put("videoAttached", videoAttached)
                    .put("app", new JSONObject()
                            .put("versionName", BuildConfig.VERSION_NAME)
                            .put("versionCode", BuildConfig.VERSION_CODE))
                    .put("device", new JSONObject()
                            .put("androidSdk", Build.VERSION.SDK_INT)
                            .put("manufacturer", sanitize(Build.MANUFACTURER, "Unknown"))
                            .put("model", sanitize(Build.MODEL, "Unknown"))
                            .put("displayWidth", metrics.widthPixels)
                            .put("displayHeight", metrics.heightPixels)
                            .put("densityDpi", metrics.densityDpi)
                            .put("refreshRateHz", Math.round(refreshRate * 100f) / 100f))
                    .put("capture", new JSONObject()
                            .put("runtimeMode", session.censorStartedElapsedNanos > 0L
                                    ? "Accessibility screenshot" : runtime.getMode())
                            .put("provider", runtime.getProvider())
                            .put("model", runtime.getModel())
                            .put("preset", settings.loadDetectionPreset().preferenceValue())
                            .put("censorCoverage", config.getCensorCoverage().preferenceValue())
                            .put("inferenceResolution", config.getInferenceResolution())
                            .put("captureScale", config.getCaptureScale())
                            .put("detectionIntervalMs", config.getDetectionIntervalMs())
                            .put("overlayAppearance", new JSONObject()
                                    .put("type", appearance.getType().getPreferenceValue())
                                    .put("showBorder", appearance.isShowBorder())
                                    .put("borderColor", appearance.getBorderColor())
                                    .put("showText", appearance.isShowText())
                                    .put("sizePadding", appearance.getSizePadding())
                                    .put("reverseMode", appearance.isReverseMode()))
                            .put("enabledCategories", enabledCategories))
                    .put("recording", new JSONObject()
                            .put("censorSession", new JSONObject()
                                    .put("kind", session.censorStartedElapsedNanos > 0L
                                            ? "temporary-censor-only" : "none")
                                    .put("startedElapsedNanos", session.censorStartedElapsedNanos)
                                    .put("stoppedElapsedNanos", session.censorStoppedElapsedNanos))
                            .put("inAppScreenRecording", videoAttached)
                            .put("sessionUsedScreenEncoder",
                                    session.videoStartedElapsedNanos > 0L)
                            .put("videoKind", videoAttached
                                    ? "mediaprojection-display" : "none")
                            .put("width", session.videoWidth)
                            .put("height", session.videoHeight)
                            .put("frameRate", session.videoFrameRate)
                            .put("bitRate", session.videoBitRate)
                            .put("startedElapsedNanos", session.videoStartedElapsedNanos)
                            .put("stoppedElapsedNanos", session.videoStoppedElapsedNanos)
                            .put("bytes", videoAttached ? videoFile.length() : 0L)
                            .put("stopReason", sanitize(session.videoStopReason, "none"))
                            .put("measurementOverhead", videoAttached
                                    ? "hardware screen encoder active" : "none"))
                    .put("privacy", new JSONObject()
                            .put("pixelCapture", videoAttached)
                            .put("ocrTextStored", false)
                            .put("foregroundPackageStored", false)
                            .put("transport", "Android share sheet only"));
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(output), StandardCharsets.UTF_8))) {
                writer.write(manifest.toString(2));
                writer.newLine();
            }
        } catch (JSONException error) {
            throw new IOException("Could not encode the Censor Lab manifest", error);
        }
    }

    private static File sessionDirectory(Context context, String id) {
        return new File(new File(context.getFilesDir(), SESSION_ROOT), id);
    }

    private static void pruneCompletedSessions(Context context, String keepId) {
        File root = new File(context.getFilesDir(), SESSION_ROOT);
        File[] listed = root.listFiles(file -> file.isDirectory() && validId(file.getName()));
        if (listed == null || listed.length <= MAX_COMPLETED_SESSIONS) return;
        List<File> sessions = new ArrayList<>();
        Collections.addAll(sessions, listed);
        sessions.sort((left, right) -> {
            if (left.getName().equals(keepId)) return -1;
            if (right.getName().equals(keepId)) return 1;
            return Long.compare(sessionModified(right), sessionModified(left));
        });
        for (int index = MAX_COMPLETED_SESSIONS; index < sessions.size(); index++) {
            File session = sessions.get(index);
            File manifest = new File(session, MANIFEST_FILE);
            File trace = new File(session, TRACE_FILE);
            File video = new File(session, VIDEO_FILE);
            if (manifest.isFile()) manifest.delete();
            if (trace.isFile()) trace.delete();
            if (video.isFile()) video.delete();
            session.delete();
        }
    }

    private static void discardAbandonedSession(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(
                PREFERENCES, Context.MODE_PRIVATE);
        String id = preferences.getString(KEY_ACTIVE, null);
        if (!validId(id)) return;
        File directory = sessionDirectory(context, id);
        File manifest = new File(directory, MANIFEST_FILE);
        File trace = new File(directory, TRACE_FILE);
        File video = new File(directory, VIDEO_FILE);
        if (manifest.isFile()) manifest.delete();
        if (trace.isFile()) trace.delete();
        if (video.isFile()) video.delete();
        directory.delete();
        preferences.edit().remove(KEY_ACTIVE).commit();
    }

    private static long sessionModified(File directory) {
        File manifest = new File(directory, MANIFEST_FILE);
        return manifest.isFile() ? manifest.lastModified() : directory.lastModified();
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10)
                .toLowerCase(Locale.ROOT);
    }

    private static boolean validId(String id) {
        return id != null && id.matches("[a-f0-9]{10}");
    }

    private static String sanitize(String value, String fallback) {
        String safe = value == null ? fallback : value.replaceAll("[\\r\\n\\t]", " ").trim();
        if (safe == null || safe.isEmpty()) safe = fallback;
        if (safe == null) safe = "";
        return safe.substring(0, Math.min(MAX_FIELD_LENGTH, safe.length()));
    }

    private static final class ActiveSession {
        final String id;
        final File directory;
        final long startedElapsedNanos;
        final long startedWallMillis;
        final CensorLabEventBuffer buffer = new CensorLabEventBuffer();
        final Object eventLock = new Object();
        volatile boolean closed;
        long censorStartedElapsedNanos;
        long censorStoppedElapsedNanos;
        long videoStartedElapsedNanos;
        long videoStoppedElapsedNanos;
        int videoWidth;
        int videoHeight;
        int videoFrameRate;
        int videoBitRate;
        boolean videoValid;
        long videoBytes;
        String videoStopReason = "none";

        ActiveSession(String id, File directory, long startedElapsedNanos,
                long startedWallMillis) {
            this.id = id;
            this.directory = directory;
            this.startedElapsedNanos = startedElapsedNanos;
            this.startedWallMillis = startedWallMillis;
        }
    }

    public static final class SessionState {
        public final boolean active;
        public final String id;
        public final long startedWallMillis;
        public final int eventCount;
        public final long droppedEvents;
        public final CompletedSession completed;

        SessionState(boolean active, String id, long startedWallMillis, int eventCount,
                long droppedEvents, CompletedSession completed) {
            this.active = active;
            this.id = id;
            this.startedWallMillis = startedWallMillis;
            this.eventCount = eventCount;
            this.droppedEvents = droppedEvents;
            this.completed = completed;
        }
    }

    public static final class CompletedSession {
        public final String id;
        public final File directory;
        public final File manifest;
        public final File trace;
        public final File video;
        public final long startedWallMillis;
        public final long stoppedWallMillis;
        public final int eventCount;
        public final long droppedEvents;

        CompletedSession(String id, File directory, File manifest, File trace, File video,
                long startedWallMillis, long stoppedWallMillis, int eventCount,
                long droppedEvents) {
            this.id = id;
            this.directory = directory;
            this.manifest = manifest;
            this.trace = trace;
            this.video = video;
            this.startedWallMillis = startedWallMillis;
            this.stoppedWallMillis = stoppedWallMillis;
            this.eventCount = eventCount;
            this.droppedEvents = droppedEvents;
        }
    }
}
