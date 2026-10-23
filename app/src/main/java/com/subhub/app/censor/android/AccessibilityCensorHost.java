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
import com.subhub.app.censor.engine.ProtectionScope;
import com.subhub.app.censor.engine.VisualInferencePolicy;
import com.subhub.app.detection.DetectionEngine;
import com.subhub.app.detection.DetectorConfig;
import com.subhub.app.detection.text.NativeTextReader;
import com.subhub.app.detection.text.SmutTextClassifier;
import com.subhub.app.detection.text.TextSmutConfig;
import com.subhub.app.censor.engine.NativeTextFrame;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Objects;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
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
    public static final long NATIVE_TEXT_PARTITION = 2;
    public static final long IMAGE_TEXT_PARTITION = 3;
    public static final long NATIVE_REFRESH_MILLIS = 120;
    public static final long NATIVE_CONFIRMATION_MILLIS = 48;
    public static final long NATIVE_MAXIMUM_AGE_MILLIS = 1000;

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

    public interface ImageTextFactory {
        ImageTextWorker initialize(Context context, long partition);
    }

    private static final class Configuration {
        final long token;
        final DetectorConfig detector;
        final boolean quality;
        final TextSmutConfig text;
        final boolean semanticText, exactText;
        Configuration(long token, DetectorConfig detector, boolean quality) {
            this(token, detector, quality, null, false, false);
        }
        Configuration(long token, DetectorConfig detector, boolean quality,
                TextSmutConfig text, boolean semanticText, boolean exactText) {
            this.token = token; this.detector = Objects.requireNonNull(detector); this.quality = quality;
            this.text = text; this.semanticText = semanticText; this.exactText = exactText;
        }
    }

    private final Context context;
    private final Handler main;
    private final Observer observer;
    private final WorkerFactory factory;
    private final ImageTextFactory imageTextFactory;
    private final ScheduledThreadPoolExecutor callbacks = captureExecutor();
    private final Slot fast = new Slot(SceneEngine.Lane.FAST);
    private final Slot quality = new Slot(SceneEngine.Lane.QUALITY);
    private final ImageTextSlot imageText = new ImageTextSlot();
    private final CensorEngine<PixelFrame> engine;
    private final NativeTextReader.Provider textProvider;
    private final ScheduledExecutorService nativeReads;
    private NativeTextReader textReader; // Its serial executor owns access, including confirmation.
    private Configuration textReaderConfiguration;
    private long textReaderEpoch;
    private final AtomicBoolean disposing = new AtomicBoolean();
    private final AtomicBoolean disposalFailed = new AtomicBoolean();
    private volatile Configuration configuration;
    private volatile boolean active;
    private volatile boolean closed;
    private boolean fastInitializing; // Main-looper admission; the task owns no captured pixels.
    private volatile Configuration fastInitializationFailed; // Latched before queued pixel work.
    private boolean qualityInitializing; // Main-looper admission; its task owns no captured pixels.
    private Configuration qualityInitializationFailed;
    private ProtectionScope protectionScope = ProtectionScope.UNSPECIFIED;

    public AccessibilityCensorHost(AccessibilityService service, SceneEngine.Policy scenePolicy,
            AccessibilityCapturer.TargetResolver targets, Observer observer) {
        this(service, scenePolicy, executor -> new AccessibilityCapturer(service, executor, targets, observer),
                VisualDetectorWorker::initialize, observer);
    }

    /** Native contract boundary. Production uses the actual service adapter above. */
    public AccessibilityCensorHost(Context context, SceneEngine.Policy scenePolicy,
            CapturerFactory capturer, WorkerFactory factory, Observer observer) {
        this(context, scenePolicy, capturer, factory, observer, null);
    }

    public AccessibilityCensorHost(Context context, SceneEngine.Policy scenePolicy,
            CapturerFactory capturer, WorkerFactory factory, Observer observer,
            NativeTextReader.Provider textProvider) {
        this(context, scenePolicy, capturer, factory, observer, textProvider, ImageTextWorker::initialize);
    }

    public AccessibilityCensorHost(Context context, SceneEngine.Policy scenePolicy,
            CapturerFactory capturer, WorkerFactory factory, Observer observer,
            NativeTextReader.Provider textProvider, ImageTextFactory imageTextFactory) {
        this.context = Objects.requireNonNull(context).getApplicationContext();
        this.main = new Handler(Looper.getMainLooper());
        this.factory = Objects.requireNonNull(factory);
        this.imageTextFactory = Objects.requireNonNull(imageTextFactory);
        this.observer = Objects.requireNonNull(observer);
        this.textProvider = textProvider;
        nativeReads = textProvider == null ? null : Executors.newSingleThreadScheduledExecutor(action -> new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT); action.run();
        }, "Censor-native-text"));
        Executor presentation = this::post;
        CensorEngine.Timer timer = (action, delay) -> {
            java.util.concurrent.ScheduledFuture<?> future = callbacks.schedule(action, delay, TimeUnit.MILLISECONDS);
            return () -> future.cancel(false);
        };
        engine = new CensorEngine<>(new CensorEngine.Policy(PLATFORM_INTERVAL_MILLIS, 500, 8, 3, false),
                scenePolicy, SystemClock::uptimeMillis, timer, capturer.create(callbacks),
                Arrays.asList(new CensorEngine.LaneWorker<>(fast.lane, fast.executor, fast),
                        new CensorEngine.LaneWorker<>(quality.lane, quality.executor, quality),
                        new CensorEngine.LaneWorker<>(SceneEngine.Lane.TEXT, imageText.executor, imageText)),
                presentation, new CensorEngine.Observer() {
                    @Override public void trace(CensorEngine.Trace trace) { observer.trace(trace); }
                    @Override public void scrollReceived(com.subhub.app.censor.engine.ScrollMotionLedger.Receipt receipt) {
                        observer.scrollReceived(receipt);
                    }
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
                    @Override public void nativeTextAccepted(NativeTextFrame frame) { observer.nativeTextAccepted(frame); }
                    @Override public void nativeReadCost(com.subhub.app.censor.engine.NativeReadCost value) { observer.nativeReadCost(value); }
                    @Override public void nativeReadCostStarted(com.subhub.app.censor.engine.NativeReadCost.Start value) { observer.nativeReadCostStarted(value); }
                    @Override public void evidenceAccepted(CensorEngine.EvidenceReceipt receipt) { observer.evidenceAccepted(receipt); }
                    @Override public void painted(CensorEngine.PaintReceipt receipt) { observer.painted(receipt); }
                    @Override public void drained() {
                        try { observer.drained(); } finally { disposeAfterDrain(); }
                    }
                }, textProvider == null ? null : new CensorEngine.NativeTextLane(NATIVE_TEXT_PARTITION,
                        NATIVE_REFRESH_MILLIS, NATIVE_MAXIMUM_AGE_MILLIS, nativeReads, this::readNative));
        if (textProvider != null) engine.nativeTextControls(false);
    }

    /** Token identifies foreground/window/document AND inference settings, never just dimensions. */
    public void start(long token, DetectorConfig detector, boolean qualityEnabled) {
        start(token, detector, qualityEnabled, null, false, false);
    }

    public void start(long token, DetectorConfig detector, boolean qualityEnabled,
            TextSmutConfig text, boolean semanticText, boolean exactText) {
        start(token, detector, qualityEnabled, text, semanticText, exactText, ProtectionScope.UNSPECIFIED);
    }

    public void start(long token, DetectorConfig detector, boolean qualityEnabled,
            TextSmutConfig text, boolean semanticText, boolean exactText, ProtectionScope protectionScope) {
        start(token, detector, qualityEnabled, text, semanticText, exactText, protectionScope, -1);
    }

    public void start(long token, DetectorConfig detector, boolean qualityEnabled,
            TextSmutConfig text, boolean semanticText, boolean exactText, ProtectionScope protectionScope, int windowId) {
        onMain();
        if (closed || active) throw new IllegalStateException("host cannot start");
        requireTextSource(detector, text);
        this.protectionScope = Objects.requireNonNull(protectionScope);
        Configuration selected = new Configuration(token, detector, qualityEnabled, text, semanticText, exactText);
        Configuration previous = configuration; long previousEpoch = engine.currentEpoch();
        configuration = selected;
        retireNativeReader(previous, previousEpoch);
        controls();
        if (closed || configuration != selected) return;
        active = true;
        initializeFast(selected);
        if (closed || !active || configuration != selected) return;
        engine.start(token, protectionScope, windowId);
    }

    /** Same owner survives reconfiguration and waits for old callbacks/running native jobs. */
    public void changeScope(long token, DetectorConfig detector, boolean qualityEnabled) {
        changeScope(token, detector, qualityEnabled, null, false, false);
    }

    public void changeScope(long token, DetectorConfig detector, boolean qualityEnabled,
            TextSmutConfig text, boolean semanticText, boolean exactText) {
        changeScope(token, detector, qualityEnabled, text, semanticText, exactText, protectionScope);
    }

    /** One owner transition for both geometry/settings and provenance. Nested observer changes win. */
    public void changeScope(long token, DetectorConfig detector, boolean qualityEnabled,
            TextSmutConfig text, boolean semanticText, boolean exactText, ProtectionScope scope) {
        changeScope(token, detector, qualityEnabled, text, semanticText, exactText, scope, -1);
    }

    public void changeScope(long token, DetectorConfig detector, boolean qualityEnabled,
            TextSmutConfig text, boolean semanticText, boolean exactText, ProtectionScope scope, int windowId) {
        onMain(); if (closed) return;
        if (configuration != null && configuration.token == token) {
            throw new IllegalArgumentException("scope/config token must change");
        }
        requireTextSource(detector, text);
        Configuration selected = new Configuration(token, detector, qualityEnabled, text, semanticText, exactText);
        Configuration previous = configuration; long previousEpoch = engine.currentEpoch();
        protectionScope = Objects.requireNonNull(scope);
        configuration = selected;
        // Queue readiness before this transition can schedule a new capture/pixel job. The
        // serial lane still waits for any executing old inference before touching its context.
        if (active) initializeFast(selected);
        // Fence old jobs before admitting new settings or changing provider concurrency.
        engine.changeScope(token, scope, windowId);
        retireNativeReader(previous, previousEpoch);
        if (!closed && configuration == selected) controls();
    }

    /** Lab/tap provenance changes fence the same geometry, without reinitializing model settings. */
    public void changeProtectionScope(ProtectionScope scope) {
        onMain(); Objects.requireNonNull(scope);
        if (closed || protectionScope.equals(scope)) return;
        Configuration previous = configuration; long previousEpoch = engine.currentEpoch();
        protectionScope = scope;
        if (configuration != null) engine.changeScope(configuration.token, scope);
        retireNativeReader(previous, previousEpoch);
    }

    /** Called by the appearance controller, never as a side effect of View.onDraw. */
    public void canvasSamplingControls(boolean enabled) { onMain(); engine.canvasSamplingControls(enabled); }

    public void stop() {
        onMain(); if (closed) return;
        Configuration previous = configuration; long previousEpoch = engine.currentEpoch();
        active = false; engine.stop(); retireNativeReader(previous, previousEpoch);
    }
    @Override public void close() {
        onMain(); if (closed) return;
        Configuration previous = configuration; long previousEpoch = engine.currentEpoch();
        closed = true; active = false; engine.close(); retireNativeReader(previous, previousEpoch);
    }

    public long epoch() { return engine.currentEpoch(); }
    /** Passive diagnostic loss inventory; it never schedules or renews evidence. */
    public long nativeCostDiagnosticFailures() { return engine.nativeCostDiagnosticFailures(); }
    public boolean captureInFlight() { return engine.captureInFlight(); }
    public CensorEngine<PixelFrame>.RenderView acquireRenderView() { return engine.acquireRenderView(); }
    public CensorEngine.ActiveScope activeScope() { return engine.activeScope(); }
    public boolean isCurrent(CensorEngine.ActiveScope scope) { return engine.isCurrent(scope); }
    public boolean evidenceCurrent(CensorEngine.EvidenceReceipt receipt) { return engine.evidenceCurrent(receipt); }
    public CensorEngine.ViewportCoverage viewportCoverage() { return engine.viewportCoverage(); }
    public boolean coverageCurrent(CensorEngine.ViewportCoverage receipt) { return engine.coverageCurrent(receipt); }
    public boolean observationCurrent(CensorEngine.EvidenceReceipt observation, CensorEngine.ViewportCoverage coverage) {
        return engine.observationCurrent(observation, coverage);
    }
    public void discardDwellObservations(CensorEngine.ActiveScope scope) { engine.discardDwellObservations(scope); }
    public boolean dwellPresent(CensorEngine.EvidenceReceipt observation, CensorEngine.ViewportCoverage coverage,
            List<CensorEngine.DetectedRegion> visible) { return engine.dwellPresent(observation, coverage, visible); }
    public CensorEngine.EvidenceClaim claimEvidence(CensorEngine.EvidenceReceipt receipt, Object consumerScope) {
        return engine.claimEvidence(receipt, consumerScope);
    }
    public CensorEngine.PaintReceipt acknowledgePaint(CensorEngine.ActiveScope scope, long revision,
            long appearanceGeneration, java.util.List<Long> members, boolean reverseBackground) {
        return engine.acknowledgePaint(scope, revision, appearanceGeneration, members, reverseBackground);
    }
    public boolean motion(SceneEngine.Motion motion) { return engine.motion(motion); }
    public boolean motion(SceneEngine.MotionPair pair) { return engine.motion(pair); }
    public com.subhub.app.censor.engine.ScrollMotionLedger.Scope scrollScope() { return engine.scrollScope(); }
    public void observeScroll(com.subhub.app.censor.engine.ScrollMotionLedger.Sample sample, boolean missingLookup) {
        engine.observeScroll(sample, missingLookup);
    }
    public void platformResourceFailure() { engine.nativeResourceFailure(); }
    public boolean presentationEstimate(com.subhub.app.censor.engine.ImagePresentationEstimate estimate) {
        return engine.presentationEstimate(estimate);
    }
    public void requestCapture() { onMain(); engine.requestCaptureAt(SystemClock.uptimeMillis()); }
    public void requestNativeText(boolean contentChanged) {
        onMain(); engine.requestNativeTextAt(SystemClock.uptimeMillis(), contentChanged);
    }
    public void requestNativeText(CensorEngine.NativeTextChange change) {
        onMain(); engine.requestNativeTextAt(SystemClock.uptimeMillis(), change);
    }
    public void drawn(long revision) { engine.drawn(revision); }

    private void controls() {
        Configuration value = configuration;
        if (value == null || closed) return;
        engine.nativeTextControls(textProvider != null && value.text != null && value.text.isEnabled());
        if (closed || configuration != value) return;
        EnumSet<SceneEngine.Lane> enabled = EnumSet.noneOf(SceneEngine.Lane.class);
        if (value.text != null && value.text.isEnabled() && !value.text.getEnabledCategories().isEmpty()
                && value.detector.getInferenceThreads() >= 4) enabled.add(SceneEngine.Lane.TEXT);
        if (!value.detector.getEnabledCategories().isEmpty()) {
            if (fastInitializationFailed != value) enabled.add(SceneEngine.Lane.FAST);
            if (value.quality && quality.sessionMatches(value.detector)) enabled.add(SceneEngine.Lane.QUALITY);
        }
        // Provider labels do not qualify independent resources: NNAPI can run ORT CPU kernels.
        engine.executionControls(Math.max(0, value.detector.getDetectionIntervalMs()), false, enabled);
    }

    private void requireTextSource(DetectorConfig detector, TextSmutConfig text) {
        if (text != null && text.isEnabled() && textProvider == null && detector.getInferenceThreads() < 4) {
            throw new IllegalStateException("native text provider unavailable for this preset");
        }
    }

    private void readNative(CensorEngine.NativeTextInput input, CensorEngine.NativeTextCompletion completion) {
        Configuration value = configuration;
        if (input.cancelled() || closed || value == null || value.text == null || !value.text.isEnabled()
                || input.geometry != value.token) { completion.cancelled(); return; }
        if (textReaderConfiguration != value || textReaderEpoch != input.epoch) {
            try { if (textReader != null) textReader.close(); }
            catch (RuntimeException | Error failure) { disposalFailed.set(true); completion.resourceFailure(); return; }
            textReader = new NativeTextReader(textProvider, new SmutTextClassifier(context), value.text,
                    value.semanticText, value.exactText, nativeReads, NATIVE_CONFIRMATION_MILLIS);
            textReaderConfiguration = value;
            textReaderEpoch = input.epoch;
        }
        textReader.start(input, completion);
    }

    private void retireNativeReader(Configuration previous, long epoch) {
        if (nativeReads == null || previous == null || disposing.get()) return;
        try {
            nativeReads.execute(() -> {
                if (!nativeReaderRetirementMatches(textReaderConfiguration, textReaderEpoch, previous, epoch)) return;
                NativeTextReader retired = textReader; textReader = null; textReaderConfiguration = null;
                try { if (retired != null) retired.close(); }
                catch (RuntimeException | Error failure) {
                    disposalFailed.set(true); engine.nativeResourceFailure();
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException failure) {
            if (!disposing.get()) { disposalFailed.set(true); engine.nativeResourceFailure(); }
        }
    }

    /** Independent engine invalidation may advance the fence beyond a retained reader's epoch. */
    static boolean nativeReaderRetirementMatches(Object ownedConfiguration, long ownedEpoch,
            Object previousConfiguration, long fenceEpoch) {
        return ownedConfiguration != null && ownedConfiguration == previousConfiguration
                && ownedEpoch > 0 && ownedEpoch <= fenceEpoch;
    }

    /** Pixel-free readiness overlaps acquisition, without moving context ownership off its lane. */
    private void initializeFast(Configuration value) {
        if (!active || closed || value.detector.getEnabledCategories().isEmpty() || fastInitializing
                || fastInitializationFailed == value || fast.sessionMatches(value.detector)) return;
        fastInitializing = true;
        try {
            fast.executor.execute(() -> {
                long began = SystemClock.uptimeMillis();
                boolean failed = false, unsafe = false;
                try {
                    if (active && !closed && configuration == value) {
                        fast.prepare(value);
                        fast.advertisedConfiguration = value;
                    }
                } catch (Exception | Error failure) {
                    failed = true;
                    // An Error from context creation has no established native cleanup contract.
                    unsafe = failure instanceof Error || cleanupFailure(failure);
                    fastInitializationFailed = value;
                    if (unsafe) disposalFailed.set(true);
                }
                boolean unavailable = failed, resourceFailed = unsafe;
                long initializationMillis = Math.max(0, SystemClock.uptimeMillis() - began);
                post(() -> {
                    fastInitializing = false;
                    if (resourceFailed) { engine.nativeResourceFailure(); return; }
                    if (closed || !active) return;
                    if (configuration != value) {
                        controls();
                        if (active && !closed && configuration != null) initializeFast(configuration);
                    } else if (unavailable) {
                        controls();
                        if (active && !closed && configuration == value) observer.modelUnavailable(SceneEngine.Lane.FAST);
                    } else {
                        controls();
                        if (active && !closed && configuration == value && fast.sessionMatches(value.detector)) {
                            observer.providerReady(SceneEngine.Lane.FAST, fast.provider, initializationMillis);
                        }
                    }
                });
            });
        } catch (RejectedExecutionException rejected) {
            fastInitializing = false;
            fastInitializationFailed = value;
            controls();
            if (active && !closed && configuration == value) observer.modelUnavailable(SceneEngine.Lane.FAST);
        }
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
            catch (Exception | Error failure) {
                failed = true; resourceFailed = failure instanceof Error || cleanupFailure(failure);
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

    private static boolean cleanupFailure(Throwable failure) {
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
            if (lane == SceneEngine.Lane.FAST && fastInitializationFailed == value) {
                completion.failure(); return; // Capture arrivals never retry failed readiness.
            }
            boolean initializationAttempted = false;
            try {
                if (lane == SceneEngine.Lane.QUALITY && !sessionMatches(value.detector)) {
                    completion.cancelled(); return; // Readiness precedes inference admission.
                }
                long began = SystemClock.uptimeMillis();
                initializationAttempted = !sessionMatches(value.detector);
                boolean initialized = prepare(value);
                long initializationMillis = initialized ? Math.max(0, SystemClock.uptimeMillis() - began) : 0;
                if (initialized || advertisedConfiguration != value) {
                    String selectedProvider = provider;
                    post(() -> {
                        if (active && !closed && configuration == value) {
                            controls();
                            if (active && !closed && configuration == value) {
                                observer.providerReady(lane, selectedProvider, initializationMillis);
                            }
                        }
                    });
                    advertisedConfiguration = value;
                }
                if (input.cancelled() || configuration != value) { completion.cancelled(); return; }
                worker.start(input, completion);
            } catch (Exception | Error failure) {
                boolean unsafe = failure instanceof Error || cleanupFailure(failure);
                if (lane == SceneEngine.Lane.FAST && initializationAttempted && !sessionMatches(value.detector)) {
                    fastInitializationFailed = value;
                    if (!unsafe) post(() -> {
                        if (active && !closed && configuration == value) {
                            controls();
                            if (active && !closed && configuration == value) observer.modelUnavailable(SceneEngine.Lane.FAST);
                        }
                    });
                }
                if (unsafe) { disposalFailed.set(true); completion.resourceFailure(); }
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
                catch (RuntimeException | Error failure) { disposalFailed.set(true); }
                finally {
                    worker = null; provider = ""; sessionConfig = null; advertisedConfiguration = null;
                    finished.run();
                }
            });
            executor.shutdown();
        }
    }

    private final class ImageTextSlot implements CensorEngine.Worker<PixelFrame> {
        final ExecutorService executor = executor("Censor-image-text", Process.THREAD_PRIORITY_BACKGROUND);
        private ImageTextWorker worker; // The serial TEXT lane owns SDK use, mapping and close.
        @Override public void start(CensorEngine.Input<PixelFrame> input, CensorEngine.Completion completion) {
            Configuration value = configuration;
            if (closed || input.cancelled() || value == null || value.token != input.source.geometry
                    || value.text == null || !value.text.isEnabled() || engine.imageTextSuppressed()) {
                completion.cancelled(); return;
            }
            try {
                if (worker == null) {
                    long began = SystemClock.uptimeMillis();
                    worker = Objects.requireNonNull(imageTextFactory.initialize(context, IMAGE_TEXT_PARTITION));
                    long initializationMillis = Math.max(0, SystemClock.uptimeMillis() - began);
                    post(() -> {
                        if (!closed && configuration == value) observer.providerReady(SceneEngine.Lane.TEXT, "MLKit", initializationMillis);
                    });
                }
                worker.configure(value.text, value.semanticText);
                if (input.cancelled() || configuration != value || engine.imageTextSuppressed()) {
                    completion.cancelled(); return;
                }
                worker.start(input, completion);
            } catch (RuntimeException | Error failure) {
                if (input.cancelled()) completion.cancelled(); else completion.failure();
            }
        }
        void dispose(Runnable finished) {
            executor.execute(() -> {
                try { if (worker != null) worker.close(); }
                catch (RuntimeException | Error failure) { disposalFailed.set(true); }
                finally { worker = null; finished.run(); }
            });
            executor.shutdown();
        }
    }

    private void disposeAfterDrain() {
        if (!disposing.compareAndSet(false, true)) return;
        callbacks.shutdown();
        if (nativeReads != null) {
            nativeReads.execute(() -> {
                try { if (textReader != null) textReader.close(); }
                catch (RuntimeException | Error failure) { disposalFailed.set(true); }
                finally { textReader = null; textReaderConfiguration = null; }
            });
            nativeReads.shutdown();
        }
        AtomicInteger remaining = new AtomicInteger(3);
        Runnable done = () -> { if (remaining.decrementAndGet() == 0) post(this::awaitExecutorTermination); };
        fast.dispose(done); quality.dispose(done); imageText.dispose(done);
    }

    private void awaitExecutorTermination() {
        if (!callbacks.isTerminated() || !fast.executor.isTerminated() || !quality.executor.isTerminated()
                || !imageText.executor.isTerminated()
                || (nativeReads != null && !nativeReads.isTerminated())) {
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
    private static ScheduledThreadPoolExecutor captureExecutor() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, action -> new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY); action.run();
        }, "Censor-capture-copy"));
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return executor;
    }
}
