package com.subhub.app.censor.android;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;

import androidx.annotation.RequiresApi;

import com.subhub.app.censor.engine.CensorEngine;
import com.subhub.app.censor.engine.SceneEngine;
import com.subhub.app.censor.engine.VisualInferencePolicy;
import com.subhub.app.detection.DetectionEngine;
import com.subhub.app.detection.DetectorConfig;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Android lifetime boundary for the replacement owner. No tracker, cache or mutable overlay scene
 * lives here. Public lifecycle calls run on the main looper; model work and disposal do not.
 */
@RequiresApi(Build.VERSION_CODES.R)
public final class AccessibilityCensorHost implements AutoCloseable {
    public static final long PLATFORM_INTERVAL_MILLIS = 334;
    public static final long VISUAL_PARTITION = 1;

    public interface Observer extends CensorEngine.Observer, AccessibilityCapturer.Observer {
        default void providerReady(SceneEngine.Lane lane, String provider, long initializationMillis) { }
        default void modelUnavailable(SceneEngine.Lane lane) { }
        /** False means a native close failed, not that its resources have been proved disposed. */
        default void executorsTerminated(boolean resourcesDisposed) { }
    }

    public interface WorkerFactory {
        VisualDetectorWorker initialize(Context context, SceneEngine.Lane lane, DetectorConfig config,
                long partition, String fallbackProvider) throws Exception;
    }

    public interface CapturerFactory {
        CensorEngine.Capturer<PixelFrame> create(Executor callbackExecutor);
    }

    private static final class Configuration {
        final long token;
        final DetectorConfig detector;
        final boolean quality;
        Configuration(long token, DetectorConfig detector, boolean quality) {
            this.token = token; this.detector = Objects.requireNonNull(detector); this.quality = quality;
        }
    }

    private final Context context;
    private final Handler main;
    private final Observer observer;
    private final WorkerFactory factory;
    private final ExecutorService callbacks = executor("Censor-capture-copy", Process.THREAD_PRIORITY_DISPLAY);
    private final Slot fast = new Slot(SceneEngine.Lane.FAST);
    private final Slot quality = new Slot(SceneEngine.Lane.QUALITY);
    private final CensorEngine<PixelFrame> engine;
    private final AtomicBoolean disposing = new AtomicBoolean();
    private final AtomicBoolean disposalFailed = new AtomicBoolean();
    private volatile Configuration configuration;
    private volatile boolean active;
    private volatile boolean closed;
    private boolean qualityInitializing; // Main-looper admission; its task owns no captured pixels.
    private Configuration qualityInitializationFailed;

    public AccessibilityCensorHost(AccessibilityService service, SceneEngine.Policy scenePolicy,
            AccessibilityCapturer.TargetResolver targets, Observer observer) {
        this(service, scenePolicy, executor -> new AccessibilityCapturer(service, executor, targets, observer),
                VisualDetectorWorker::initialize, observer);
    }

    /** Native contract boundary. Production uses the actual service adapter above. */
    public AccessibilityCensorHost(Context context, SceneEngine.Policy scenePolicy,
            CapturerFactory capturer, WorkerFactory factory, Observer observer) {
        this.context = Objects.requireNonNull(context).getApplicationContext();
        this.main = new Handler(Looper.getMainLooper());
        this.factory = Objects.requireNonNull(factory);
        this.observer = Objects.requireNonNull(observer);
        Executor presentation = this::post;
        CensorEngine.Timer timer = (action, delay) -> {
            if (!main.postDelayed(action, delay)) throw new RejectedExecutionException("main looper closed");
            return () -> main.removeCallbacks(action);
        };
        engine = new CensorEngine<>(new CensorEngine.Policy(PLATFORM_INTERVAL_MILLIS, 500, 8, 3, false),
                scenePolicy, SystemClock::uptimeMillis, timer, capturer.create(callbacks),
                Arrays.asList(new CensorEngine.LaneWorker<>(fast.lane, fast.executor, fast),
                        new CensorEngine.LaneWorker<>(quality.lane, quality.executor, quality)),
                presentation, new CensorEngine.Observer() {
                    @Override public void trace(CensorEngine.Trace trace) { observer.trace(trace); }
                    @Override public void presentationChanged() { observer.presentationChanged(); }
                    @Override public void batchAccepted(SceneEngine.Batch batch) {
                        if (batch.lane == SceneEngine.Lane.FAST) {
                            Configuration value = configuration;
                            post(() -> {
                                if (active && !closed && value != null && configuration == value
                                        && batch.source.epoch == engine.currentEpoch()
                                        && batch.source.geometry == value.token) initializeQuality(value);
                            });
                        }
                        observer.batchAccepted(batch);
                    }
                    @Override public void drained() {
                        try { observer.drained(); } finally { disposeAfterDrain(); }
                    }
                });
    }

    /** Token identifies foreground/window/document AND inference settings, never just dimensions. */
    public void start(long token, DetectorConfig detector, boolean qualityEnabled) {
        onMain();
        if (closed || active) throw new IllegalStateException("host cannot start");
        configuration = new Configuration(token, detector, qualityEnabled);
        controls(); active = true; engine.start(token);
    }

    /** Same owner survives reconfiguration and waits for old callbacks/running native jobs. */
    public void changeScope(long token, DetectorConfig detector, boolean qualityEnabled) {
        onMain(); if (closed) return;
        if (configuration != null && configuration.token == token) {
            throw new IllegalArgumentException("scope/config token must change");
        }
        configuration = new Configuration(token, detector, qualityEnabled);
        // Fence old jobs before admitting new settings or changing provider concurrency.
        engine.changeScope(token); controls();
    }

    public void stop() { onMain(); if (closed) return; active = false; engine.stop(); }
    @Override public void close() { onMain(); if (closed) return; closed = true; active = false; engine.close(); }

    public long epoch() { return engine.currentEpoch(); }
    public boolean captureInFlight() { return engine.captureInFlight(); }
    public CensorEngine<PixelFrame>.RenderView acquireRenderView() { return engine.acquireRenderView(); }
    public boolean motion(SceneEngine.Motion motion) { return engine.motion(motion); }
    public void requestCapture() { onMain(); engine.requestCaptureAt(SystemClock.uptimeMillis()); }
    public void drawn(long revision) { engine.drawn(revision); }

    private void controls() {
        Configuration value = configuration;
        if (value == null || closed) return;
        EnumSet<SceneEngine.Lane> enabled = EnumSet.noneOf(SceneEngine.Lane.class);
        if (!value.detector.getEnabledCategories().isEmpty()) {
            enabled.add(SceneEngine.Lane.FAST);
            if (value.quality && quality.sessionMatches(value.detector)) enabled.add(SceneEngine.Lane.QUALITY);
        }
        boolean parallel = fast.sessionMatches(value.detector) && quality.sessionMatches(value.detector)
                && "CPU".equals(fast.provider) && "NNAPI".equals(quality.provider);
        engine.executionControls(Math.max(0, value.detector.getDetectionIntervalMs()), parallel, enabled);
    }

    /** Optional context initialization must not occupy a quality inference job and stall FAST. */
    private void initializeQuality(Configuration value) {
        if (!value.quality || value.detector.getEnabledCategories().isEmpty() || qualityInitializing
                || qualityInitializationFailed == value || quality.sessionMatches(value.detector)) return;
        qualityInitializing = true;
        quality.executor.execute(() -> {
            long began = SystemClock.uptimeMillis();
            boolean failed = false, resourceFailed = false;
            try { if (!closed && configuration == value) quality.prepare(value); }
            catch (Exception failure) {
                failed = true; resourceFailed = cleanupFailure(failure);
                if (resourceFailed) disposalFailed.set(true);
            }
            boolean unavailable = failed, unsafe = resourceFailed;
            long initializationMillis = Math.max(0, SystemClock.uptimeMillis() - began);
            post(() -> {
                qualityInitializing = false;
                if (unsafe) { engine.nativeResourceFailure(); return; }
                if (closed) return;
                if (configuration != value) {
                    // A same-key context may be reused; never lose readiness to an old-scope post.
                    controls();
                    if (active && configuration != null) initializeQuality(configuration);
                } else if (unavailable) {
                    qualityInitializationFailed = value;
                    observer.modelUnavailable(SceneEngine.Lane.QUALITY);
                } else {
                    controls();
                    observer.providerReady(SceneEngine.Lane.QUALITY, quality.provider, initializationMillis);
                }
            });
        });
    }

    private static boolean cleanupFailure(Exception failure) {
        return failure instanceof SessionRetirementFailure
                || failure instanceof VisualDetectorWorker.InitializationCleanupFailure
                || DetectionEngine.resourceCleanupFailed(failure);
    }

    private static final class SessionRetirementFailure extends RuntimeException {
        SessionRetirementFailure(RuntimeException failure) { super("Model retirement cleanup failed", failure); }
    }

    private final class Slot implements CensorEngine.Worker<PixelFrame> {
        final SceneEngine.Lane lane;
        final ExecutorService executor;
        private VisualDetectorWorker worker; // This serial executor owns access and disposal.
        private Configuration advertisedConfiguration; // A reused session needs current-scope admission too.
        private volatile DetectorConfig sessionConfig;
        private volatile String provider = "";
        Slot(SceneEngine.Lane lane) {
            this.lane = lane;
            executor = executor("Censor-" + lane.name(), lane == SceneEngine.Lane.FAST
                    ? Process.THREAD_PRIORITY_DISPLAY : Process.THREAD_PRIORITY_BACKGROUND);
        }

        boolean sessionMatches(DetectorConfig candidate) {
            DetectorConfig selected = lane == SceneEngine.Lane.FAST
                    ? VisualInferencePolicy.fast(candidate) : VisualInferencePolicy.quality(candidate);
            DetectorConfig current = sessionConfig;
            return current != null && selected.getModelFilename().equals(current.getModelFilename())
                    && selected.getInferenceResolution() == current.getInferenceResolution()
                    && selected.getInferenceThreads() == current.getInferenceThreads();
        }

        @Override public void start(CensorEngine.Input<PixelFrame> input, CensorEngine.Completion completion) {
            Configuration value = configuration;
            if (value == null || input.cancelled() || value.token != input.source.geometry) {
                completion.cancelled(); return;
            }
            try {
                if (lane == SceneEngine.Lane.QUALITY && !sessionMatches(value.detector)) {
                    completion.cancelled(); return; // Readiness precedes inference admission.
                }
                long began = SystemClock.uptimeMillis();
                boolean initialized = prepare(value);
                long initializationMillis = initialized ? Math.max(0, SystemClock.uptimeMillis() - began) : 0;
                if (initialized || advertisedConfiguration != value) {
                    String selectedProvider = provider;
                    post(() -> {
                        if (!closed && configuration == value) {
                            controls();
                            observer.providerReady(lane, selectedProvider, initializationMillis);
                        }
                    });
                    advertisedConfiguration = value;
                }
                if (input.cancelled() || configuration != value) { completion.cancelled(); return; }
                worker.start(input, completion);
            } catch (Exception failure) {
                if (cleanupFailure(failure)) { disposalFailed.set(true); completion.resourceFailure(); }
                else if (input.cancelled()) completion.cancelled(); else completion.failure();
            }
        }

        /** This slot's serial executor also owns readiness work before any QUALITY pixel job. */
        boolean prepare(Configuration value) throws Exception {
            if (sessionMatches(value.detector)) { worker.configure(value.detector); return false; }
            provider = ""; sessionConfig = null;
            if (worker != null) {
                VisualDetectorWorker retired = worker; worker = null;
                try { retired.close(); }
                catch (RuntimeException failure) { throw new SessionRetirementFailure(failure); }
            }
            if (closed || configuration != value) return false;
            worker = Objects.requireNonNull(factory.initialize(context, lane, value.detector,
                    VISUAL_PARTITION, fast.provider));
            provider = Objects.requireNonNull(worker.provider());
            sessionConfig = lane == SceneEngine.Lane.FAST
                    ? VisualInferencePolicy.fast(value.detector) : VisualInferencePolicy.quality(value.detector);
            return true;
        }

        void dispose(Runnable finished) {
            executor.execute(() -> {
                try { if (worker != null) worker.close(); }
                catch (RuntimeException failure) { disposalFailed.set(true); }
                finally {
                    worker = null; provider = ""; sessionConfig = null; advertisedConfiguration = null;
                    finished.run();
                }
            });
            executor.shutdown();
        }
    }

    private void disposeAfterDrain() {
        if (!disposing.compareAndSet(false, true)) return;
        callbacks.shutdown();
        AtomicInteger remaining = new AtomicInteger(2);
        Runnable done = () -> { if (remaining.decrementAndGet() == 0) post(this::awaitExecutorTermination); };
        fast.dispose(done); quality.dispose(done);
    }

    private void awaitExecutorTermination() {
        if (!callbacks.isTerminated() || !fast.executor.isTerminated() || !quality.executor.isTerminated()) {
            main.postDelayed(this::awaitExecutorTermination, 10); return;
        }
        observer.executorsTerminated(!disposalFailed.get());
    }

    private void post(Runnable action) {
        if (!main.post(action)) throw new RejectedExecutionException("main looper closed");
    }
    private static void onMain() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("main-looper lifecycle required");
    }
    private static ExecutorService executor(String name, int priority) {
        return Executors.newSingleThreadExecutor(action -> new Thread(() -> {
            Process.setThreadPriority(priority); action.run();
        }, name));
    }
}
