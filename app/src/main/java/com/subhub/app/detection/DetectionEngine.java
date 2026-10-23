package com.subhub.app.detection;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.RectF;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;
import ai.onnxruntime.providers.NNAPIFlags;

/** Owns the ONNX Runtime session and converts Android bitmaps to model input. */
public final class DetectionEngine implements AutoCloseable {
    private static final String TAG = "DetectionEngine";
    private static final String PROVIDER_PREFS = "detector_provider_cache";
    private static final String PROVIDER_CONFIG_REVISION = "ep-v3";
    private static final float INV_255 = 1f / 255f;
    private static final AtomicLong NATIVE_RUN_SEQUENCE = new AtomicLong();
    private static final ExecutorService REALTIME_PREPROCESS_EXECUTOR =
            Executors.newFixedThreadPool(3, runnable -> preprocessThread(
                    runnable, "SubHub-fast-input", Process.THREAD_PRIORITY_DISPLAY));
    private static final ExecutorService QUALITY_PREPROCESS_EXECUTOR =
            Executors.newFixedThreadPool(3, runnable -> preprocessThread(
                    runnable, "SubHub-quality-input", Process.THREAD_PRIORITY_DEFAULT));

    private final Context context;
    private final OrtEnvironment environment;
    private final DetectionPostProcessor postProcessor = new DetectionPostProcessor();
    private final InferencePerformanceHints performanceHints;
    private final boolean latencyPriority;
    private final boolean nnapiFp16Relaxation;
    private final ExecutorService preprocessExecutor;
    private final CpuExecutionBudget executionBudget;
    private final BoundedRgbInput boundedRgbInput;
    private boolean budgetRetired;
    private DetectorConfig config;
    private OrtSession session;
    private String inputName = "images";
    private String activeProvider = "uninitialized";
    private String activeModel = "";
    private Bitmap letterbox;
    private Canvas letterboxCanvas;
    private int bufferWidth;
    private int bufferHeight;
    private int[] pixels;
    private float[] inputValues;
    private FloatBuffer directInput;
    private OnnxTensor inputTensor;
    private Map<String, OnnxTensor> inferenceInputs;
    private long lastInferenceMs;
    private long lastPreprocessMs;
    private long lastRuntimeMs;
    private long lastPostprocessMs;
    private volatile boolean lastRunCancelled;
    private boolean lastRunComplete;
    private boolean strictResourceCleanup;
    private volatile long lastCancellationMs;
    private final AtomicReference<InferenceRun> activeInference = new AtomicReference<>();

    public DetectionEngine(Context context, DetectorConfig config) {
        this(context, config, true);
    }

    public DetectionEngine(
            Context context, DetectorConfig config, boolean latencyPriority) {
        this(context, config, latencyPriority, false);
    }

    DetectionEngine(
            Context context,
            DetectorConfig config,
            boolean latencyPriority,
            boolean nnapiFp16Relaxation) {
        this(context, config, latencyPriority, nnapiFp16Relaxation, null);
    }

    /** Test-only explicit resource grant; production constructors preserve their existing policy. */
    DetectionEngine(Context context, DetectorConfig config, boolean latencyPriority,
            boolean nnapiFp16Relaxation, CpuExecutionBudget executionBudget) {
        if (executionBudget != null) executionBudget.validate(latencyPriority, Runtime.getRuntime().availableProcessors());
        this.context = context.getApplicationContext();
        this.config = config;
        this.latencyPriority = latencyPriority;
        this.nnapiFp16Relaxation = nnapiFp16Relaxation;
        this.executionBudget = executionBudget;
        this.strictResourceCleanup = executionBudget != null;
        AtomicLong inputSequence = new AtomicLong();
        this.boundedRgbInput = executionBudget == null ? null : new BoundedRgbInput(executionBudget.inputWorkers,
                action -> {
                    // No throwable Android setup outside ThreadPoolExecutor.Worker.run(): an
                    // accepted channel must enter the executor's cleanup and latch protocol.
                    Thread thread = new Thread(action, "Cen-F-in-" + inputSequence.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                });
        this.preprocessExecutor = latencyPriority
                ? REALTIME_PREPROCESS_EXECUTOR : QUALITY_PREPROCESS_EXECUTOR;
        this.environment = OrtEnvironment.getEnvironment();
        performanceHints = new InferencePerformanceHints(this.context);
        allocateBuffers(config.getInferenceResolution());
    }

    /** Replacement workers must learn about failed context disposal instead of quietly retrying. */
    public synchronized void requireStrictResourceCleanup() {
        if (session != null || inputTensor != null) throw new IllegalStateException("enable before initialization");
        strictResourceCleanup = true;
    }

    public synchronized void initialize() throws IOException, OrtException {
        if (executionBudget != null) throw new IllegalStateException("Bounded test requires explicit CPU initialization");
        closeSession();
        List<String> candidates = Collections.singletonList(config.getModelFilename());

        Exception lastFailure = null;
        for (String model : candidates) {
            byte[] bytes;
            try {
                bytes = readAsset(model);
            } catch (IOException error) {
                lastFailure = error;
                continue;
            }
            String cacheKey = providerCacheKey(model);
            SharedPreferences providerPrefs = context.getSharedPreferences(
                    PROVIDER_PREFS, Context.MODE_PRIVATE);
            String cachedProvider = providerPrefs.getString(cacheKey, null);
            ProviderCandidate fastest = cachedProvider == null ? null
                    : selectFastestProvider(bytes, model, new String[]{cachedProvider});
            if (fastest == null && cachedProvider != null) {
                providerPrefs.edit().remove(cacheKey).apply();
            }
            if (fastest == null) {
                fastest = selectFastestProvider(
                        bytes, model, new String[]{"NNAPI", "XNNPACK", "CPU"});
            }
            if (fastest != null) {
                session = fastest.session;
                inputName = fastest.session.getInputNames().iterator().next();
                activeProvider = fastest.provider;
                activeModel = model;
                Log.i(TAG, "Loaded " + model + " using fastest provider "
                        + fastest.provider + " (" + fastest.benchmarkNanos / 1_000_000f
                        + " ms" + (cachedProvider == null ? "" : ", cached")
                        + ", profile=" + (latencyPriority ? "realtime" : "quality") + ")");
                providerPrefs.edit().putString(cacheKey, fastest.provider).apply();
                return;
            }
        }
        if (lastFailure instanceof IOException) throw (IOException) lastFailure;
        if (lastFailure instanceof OrtException) throw (OrtException) lastFailure;
        throw new IOException("No compatible ONNX model asset was found", lastFailure);
    }

    /**
     * Creates a session without running provider benchmarks. Live Accessibility startup uses this
     * for optional quality refinement so provider selection cannot compete with real-time work.
     */
    public synchronized void initializeWithoutBenchmark(String fallbackProvider)
            throws IOException, OrtException {
        if (executionBudget != null) throw new IllegalStateException("Bounded test forbids provider fallback");
        closeSession();
        String model = config.getModelFilename();
        byte[] bytes = readAsset(model);
        String cacheKey = providerCacheKey(model);
        SharedPreferences providerPrefs = context.getSharedPreferences(
                PROVIDER_PREFS, Context.MODE_PRIVATE);
        String cachedProvider = providerPrefs.getString(cacheKey, null);
        List<String> providers = new java.util.ArrayList<>();
        // This entry point is used by the optional quality lane. Prefer the device accelerator
        // before a cached CPU/XNNPACK winner from another profile; otherwise Accessibility's
        // split-hardware admission can silently disable quality forever.
        addUniqueProvider(providers, "NNAPI");
        addUniqueProvider(providers, cachedProvider);
        addUniqueProvider(providers, fallbackProvider);
        addUniqueProvider(providers, "CPU");
        Exception lastFailure = null;
        for (String provider : providers) {
            try (OptionsLease lease = new OptionsLease(optionsFor(provider), strictResourceCleanup)) {
                session = environment.createSession(bytes, lease.options);
                inputName = session.getInputNames().iterator().next();
                activeProvider = provider;
                activeModel = model;
                providerPrefs.edit().putString(cacheKey, provider).apply();
                Log.i(TAG, "Loaded " + model + " using direct provider " + provider
                        + " (profile=" + (latencyPriority ? "realtime" : "quality") + ")");
                return;
            } catch (Exception error) {
                if (strictResourceCleanup && resourceCleanupFailed(error)) throw new ResourceCleanupFailure(error);
                lastFailure = error;
                closeSession();
                if (provider.equals(cachedProvider)) {
                    providerPrefs.edit().remove(cacheKey).apply();
                }
                Log.w(TAG, "Direct provider " + provider + " rejected " + model
                        + ": " + error.getMessage());
            }
        }
        if (lastFailure instanceof IOException) throw (IOException) lastFailure;
        if (lastFailure instanceof OrtException) throw (OrtException) lastFailure;
        throw new IOException("No direct provider could initialize " + model, lastFailure);
    }

    private static void addUniqueProvider(List<String> providers, String provider) {
        if (provider == null || provider.trim().isEmpty() || providers.contains(provider)) return;
        providers.add(provider);
    }

    /** Device benchmark hook: creates one requested EP without provider-selection side effects. */
    public synchronized void initializeForProvider(String provider) throws IOException, OrtException {
        if (executionBudget != null) {
            if (budgetRetired) throw new IllegalStateException("Bounded detector is retired");
            executionBudget.requireProvider(provider);
        }
        closeSession();
        String model = config.getModelFilename();
        byte[] bytes = readAsset(model);
        try (OptionsLease lease = new OptionsLease(optionsFor(provider), strictResourceCleanup)) {
            session = environment.createSession(bytes, lease.options);
        }
        inputName = session.getInputNames().iterator().next();
        activeProvider = provider;
        activeModel = model;
        Log.i(TAG, "Loaded " + model + " using forced provider " + provider
                + " (profile=" + (latencyPriority ? "realtime" : "quality")
                + ", fp16Relaxation=" + nnapiFp16Relaxation + ")");
    }

    public synchronized List<Detection> detect(Bitmap frame) throws OrtException {
        if (frame == null) return Collections.emptyList();
        return detect(frame, frame.getWidth(), frame.getHeight());
    }

    /** Detects from a pre-scaled bitmap while mapping results to the original source frame. */
    public synchronized List<Detection> detect(
            Bitmap frame, int sourceWidth, int sourceHeight) throws OrtException {
        return detect(frame, sourceWidth, sourceHeight, null);
    }

    /** Quality-only overload with a lock-free admission token owned by the caller. */
    public synchronized List<Detection> detect(
            Bitmap frame,
            int sourceWidth,
            int sourceHeight,
            BooleanSupplier cancellationProbe) throws OrtException {
        int size = config.getInferenceResolution();
        return detectInternal(
                frame, sourceWidth, sourceHeight, size, size, cancellationProbe, false);
    }

    public interface RunCancellation { boolean cancel(); }

    public static final class ResourceCleanupFailure extends RuntimeException {
        ResourceCleanupFailure(Throwable cause) { super("Native inference resource cleanup failed", cause); }
    }

    /** Try-with-resources can suppress a cleanup fault beneath the original decode exception. */
    public static boolean resourceCleanupFailed(Throwable failure) {
        return resourceCleanupFailed(failure, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private static boolean resourceCleanupFailed(Throwable failure, Set<Throwable> visited) {
        if (failure == null || visited.contains(failure)) return false;
        if (failure instanceof ResourceCleanupFailure) return true;
        if (visited.size() >= 32) return true; // Unsupported exception depth cannot prove clean resources.
        visited.add(failure);
        if (resourceCleanupFailed(failure.getCause(), visited)) return true;
        for (Throwable suppressed : failure.getSuppressed()) if (resourceCleanupFailed(suppressed, visited)) return true;
        return false;
    }

    /** Immutable outcome of one controlled call, including whether its crop was actually inspected. */
    public static final class ControlledResult {
        public final List<Detection> detections;
        public final boolean complete;
        public final boolean cancelled;
        public final long preprocessMillis;
        public final long runtimeMillis;
        public final long postprocessMillis;

        private ControlledResult(List<Detection> detections, boolean complete, boolean cancelled,
                long preprocessMillis, long runtimeMillis, long postprocessMillis) {
            this.detections = Collections.unmodifiableList(new java.util.ArrayList<>(detections));
            this.complete = complete;
            this.cancelled = cancelled;
            this.preprocessMillis = complete ? preprocessMillis : 0;
            this.runtimeMillis = complete ? runtimeMillis : 0;
            this.postprocessMillis = complete ? postprocessMillis : 0;
        }
    }

    /**
     * Replacement-worker entry point. Bind cancellation to this run, never to the lane's current
     * session. A handle remains safe after its own completion/close and cannot cancel another run.
     */
    public synchronized ControlledResult detectControlled(Bitmap frame, int sourceWidth, int sourceHeight,
            int inputWidth, int inputHeight, BooleanSupplier cancellationProbe,
            Consumer<RunCancellation> cancellationBinding) throws OrtException {
        java.util.Objects.requireNonNull(cancellationProbe);
        java.util.Objects.requireNonNull(cancellationBinding);
        if (inputWidth <= 0 || inputHeight <= 0) throw new IllegalArgumentException("input dimensions");
        boolean rectangular = inputWidth != inputHeight;
        if (rectangular && !"CPU".equals(activeProvider)) {
            throw new IllegalStateException("Rectangular inference requires the CPU provider");
        }
        List<Detection> detected = detectInternal(frame, sourceWidth, sourceHeight, inputWidth, inputHeight,
                cancellationProbe, rectangular, cancellationBinding);
        return new ControlledResult(detected, lastRunComplete, lastRunCancelled,
                lastPreprocessMs, lastRuntimeMs, lastPostprocessMs);
    }

    /**
     * Runs an opt-in rectangular input through the CPU execution provider.
     *
     * <p>The bundled model declares dynamic height and width. Accessibility's portrait fast
     * bitmap is commonly about 144x320 for a 320px long edge, while the legacy square path pads
     * it to 320x320. This method keeps the source aspect ratio and avoids that padding, but is
     * deliberately CPU-only: NNAPI and XNNPACK provider support for changing dynamic shapes is
     * device/runtime-specific and must not be enabled by accident in the live lane.</p>
     */
    public synchronized List<Detection> detectRectangular(
            Bitmap frame,
            int sourceWidth,
            int sourceHeight,
            int inputWidth,
            int inputHeight) throws OrtException {
        return detectRectangular(
                frame, sourceWidth, sourceHeight, inputWidth, inputHeight, null);
    }

    /** Quality-cancellation overload for the rectangular benchmark path. */
    public synchronized List<Detection> detectRectangular(
            Bitmap frame,
            int sourceWidth,
            int sourceHeight,
            int inputWidth,
            int inputHeight,
            BooleanSupplier cancellationProbe) throws OrtException {
        if (!"CPU".equals(activeProvider)) {
            throw new IllegalStateException(
                    "Rectangular inference requires the CPU provider; active=" + activeProvider);
        }
        if (inputWidth <= 0 || inputHeight <= 0) {
            throw new IllegalArgumentException("Rectangular input dimensions must be positive");
        }
        return detectInternal(
                frame, sourceWidth, sourceHeight, inputWidth, inputHeight, cancellationProbe, true);
    }

    private List<Detection> detectInternal(
            Bitmap frame,
            int sourceWidth,
            int sourceHeight,
            int inputWidth,
            int inputHeight,
            BooleanSupplier cancellationProbe,
            boolean rectangularInput) throws OrtException {
        return detectInternal(frame, sourceWidth, sourceHeight, inputWidth, inputHeight,
                cancellationProbe, rectangularInput, null);
    }

    private List<Detection> detectInternal(Bitmap frame, int sourceWidth, int sourceHeight,
            int inputWidth, int inputHeight, BooleanSupplier cancellationProbe, boolean rectangularInput,
            Consumer<RunCancellation> cancellationBinding) throws OrtException {
        lastRunComplete = false;
        lastRunCancelled = false;
        if (session == null || frame == null || frame.getWidth() <= 0 || frame.getHeight() <= 0) {
            return Collections.emptyList();
        }
        sourceWidth = Math.max(1, sourceWidth);
        sourceHeight = Math.max(1, sourceHeight);
        InferenceRun inferenceRun = latencyPriority && cancellationBinding == null ? null : new InferenceRun();
        if (inferenceRun != null && !activeInference.compareAndSet(null, inferenceRun)) {
            inferenceRun.close();
            throw new IllegalStateException("Quality inference is already active");
        }
        long started = SystemClock.elapsedRealtimeNanos();
        try {
            if (cancellationBinding != null) cancellationBinding.accept(inferenceRun::cancel);
            if (shouldCancel(inferenceRun, cancellationProbe)) {
                return recordCancelledRun(started);
            }
            performanceHints.begin(latencyPriority
                    ? config.getDetectionIntervalMs()
                    : Math.max(200L, config.getDetectionIntervalMs()));
            ensureBuffers(inputWidth, inputHeight);
            float scale = Math.min(
                    (float) inputWidth / sourceWidth,
                    (float) inputHeight / sourceHeight);
            int expectedWidth = Math.max(1, Math.round(sourceWidth * scale));
            int expectedHeight = Math.max(1, Math.round(sourceHeight * scale));
            if (frame.getWidth() == expectedWidth && frame.getHeight() == expectedHeight
                    && expectedWidth <= inputWidth && expectedHeight <= inputHeight) {
                // Accessibility already paid for a hardware-accelerated downscale before readback.
                // Write that compact bitmap directly into the letterboxed pixel stride instead of
                // software-scaling/copying it through a second Canvas on every inference pass.
                Arrays.fill(pixels, Color.BLACK);
                frame.getPixels(pixels, 0, inputWidth, 0, 0, expectedWidth, expectedHeight);
            } else {
                letterbox.eraseColor(Color.BLACK);
                letterboxCanvas.drawBitmap(
                        frame,
                        null,
                        new RectF(0f, 0f, sourceWidth * scale, sourceHeight * scale),
                        null);
                letterbox.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight);
            }

            int plane = inputWidth * inputHeight;
            populateInputValues(plane);
            directInput.clear();
            directInput.put(inputValues, 0, plane * 3);
            directInput.flip();
            if (shouldCancel(inferenceRun, cancellationProbe)) {
                return recordCancelledRun(started);
            }

            ensureInputTensor(inputWidth, inputHeight);
            long runtimeStarted = SystemClock.elapsedRealtimeNanos();
            long nativeRunId = NATIVE_RUN_SEQUENCE.incrementAndGet();
            String lane = latencyPriority ? "fast" : "quality";
            boolean nativeCompleted = false;
            OrtSession.Result nativeResult;
            if (inferenceRun != null) inferenceRun.enterNativeRun();
            Log.i(TAG, "INFERENCE_NATIVE_BEGIN lane=" + lane
                    + " runId=" + nativeRunId
                    + " provider=" + activeProvider
                    + " uptimeNanos=" + runtimeStarted);
            try {
                nativeResult = inferenceRun == null
                        ? session.run(inferenceInputs)
                        : session.run(inferenceInputs, inferenceRun.options);
                nativeCompleted = true;
            } catch (OrtException error) {
                if (shouldCancel(inferenceRun, cancellationProbe)) {
                    return recordCancelledRun(started);
                }
                throw error;
            } finally {
                if (inferenceRun != null) inferenceRun.leaveNativeRun();
                long nativeEnded = SystemClock.elapsedRealtimeNanos();
                boolean cancelled = inferenceRun != null
                        && inferenceRun.isCancellationRequested();
                Log.i(TAG, "INFERENCE_NATIVE_END lane=" + lane
                        + " runId=" + nativeRunId
                        + " status=" + (cancelled ? "cancelled"
                                : nativeCompleted ? "completed" : "error")
                        + " durationMs=" + nanosToMillis(nativeEnded - runtimeStarted)
                        + " uptimeNanos=" + nativeEnded);
            }
            try (OutputLease lease = new OutputLease(nativeResult, cancellationBinding != null)) {
                OrtSession.Result result = lease.result;
                long runtimeFinished = SystemClock.elapsedRealtimeNanos();
                if (inferenceRun != null && inferenceRun.isCancellationRequested()) {
                    return recordCancelledRun(started);
                }
                OnnxValue value = result.get(0);
                if (!(value instanceof OnnxTensor)) {
                    Log.e(TAG, "Unexpected model output type: " + value.getClass());
                    return Collections.emptyList();
                }
                OnnxTensor output = (OnnxTensor) value;
                TensorInfo info = output.getInfo();
                long[] shape = info.getShape();
                if (shape.length != 3 || shape[0] != 1L
                        || shape[1] != DetectionPostProcessor.OUTPUT_FEATURES
                        || shape[1] > Integer.MAX_VALUE || shape[2] <= 0L
                        || shape[2] > Integer.MAX_VALUE) {
                    Log.e(TAG, "Unexpected model output shape: "
                            + java.util.Arrays.toString(shape));
                    return Collections.emptyList();
                }
                FloatBuffer outputBuffer = output.getFloatBuffer();
                if (cancellationBinding != null && !TensorInspection.complete(outputBuffer, shape[1], shape[2])) {
                    Log.e(TAG, "Controlled inference returned an incomplete or nonfinite tensor");
                    return Collections.emptyList();
                }
                List<Detection> detections = rectangularInput
                        ? postProcessor.decode(
                                outputBuffer,
                                (int) shape[1],
                                (int) shape[2],
                                sourceWidth,
                                sourceHeight,
                                inputWidth,
                                inputHeight,
                                config)
                        : postProcessor.decode(
                                outputBuffer,
                                (int) shape[1],
                                (int) shape[2],
                                sourceWidth,
                                sourceHeight,
                                inputWidth,
                                config);
                long finished = SystemClock.elapsedRealtimeNanos();
                // Close the cancellation window before making decoded output observable. A fast
                // frame and this commit race on the same monitor: whichever wins determines
                // whether the quality result is discarded or may safely reach the caller.
                if (shouldCancel(inferenceRun, cancellationProbe)
                        || (inferenceRun != null && !inferenceRun.finishForCommit())) {
                    return recordCancelledRun(started);
                }
                lastPreprocessMs = nanosToMillis(runtimeStarted - started);
                lastRuntimeMs = nanosToMillis(runtimeFinished - runtimeStarted);
                lastPostprocessMs = nanosToMillis(finished - runtimeFinished);
                lastInferenceMs = nanosToMillis(finished - started);
                lastRunComplete = true;
                performanceHints.report(finished - started);
                return detections;
            }
        } finally {
            if (inferenceRun != null) {
                activeInference.compareAndSet(inferenceRun, null);
                inferenceRun.close();
            }
        }
    }

    /** Best-effort cancellation used only to give newly arrived real-time work priority. */
    public boolean cancelActiveInference() {
        InferenceRun inferenceRun = activeInference.get();
        return inferenceRun != null && inferenceRun.cancel();
    }

    public boolean wasLastRunCancelled() { return lastRunCancelled; }
    public long getLastCancellationMs() { return lastCancellationMs; }
    public boolean isNativeInferenceRunning() {
        InferenceRun inferenceRun = activeInference.get();
        return inferenceRun != null && inferenceRun.isNativeRunActive();
    }

    private static boolean shouldCancel(
            InferenceRun inferenceRun,
            BooleanSupplier cancellationProbe) {
        if (inferenceRun == null) return false;
        if (inferenceRun.isCancellationRequested()) return true;
        if (cancellationProbe == null || !cancellationProbe.getAsBoolean()) return false;
        inferenceRun.cancel();
        return true;
    }

    private List<Detection> recordCancelledRun(long startedNanos) {
        long elapsed = SystemClock.elapsedRealtimeNanos() - startedNanos;
        lastRunCancelled = true;
        lastCancellationMs = nanosToMillis(elapsed);
        lastInferenceMs = lastCancellationMs;
        lastPreprocessMs = 0L;
        lastRuntimeMs = 0L;
        lastPostprocessMs = 0L;
        return Collections.emptyList();
    }

    public synchronized void setConfig(DetectorConfig value) {
        if (executionBudget != null) {
            if (budgetRetired) throw new IllegalStateException("Bounded detector is retired");
            executionBudget.requireSameSession(config, value);
        }
        boolean resize = value.getInferenceResolution() != config.getInferenceResolution();
        config = value;
        if (resize) allocateBuffers(value.getInferenceResolution());
    }

    public String getActiveProvider() { return activeProvider; }
    public String getActiveModel() { return activeModel; }
    public int getInferenceResolution() { return config.getInferenceResolution(); }
    public long getLastInferenceMs() { return lastInferenceMs; }
    public long getLastPreprocessMs() { return lastPreprocessMs; }
    public long getLastRuntimeMs() { return lastRuntimeMs; }
    public long getLastPostprocessMs() { return lastPostprocessMs; }

    private static long nanosToMillis(long nanos) {
        return Math.max(0L, Math.round(nanos / 1_000_000d));
    }

    private OrtSession.SessionOptions optionsFor(String provider) throws OrtException {
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        try { configureOptions(options, provider); return options; }
        catch (OrtException | RuntimeException failure) {
            try { new OptionsLease(options, strictResourceCleanup).close(); }
            catch (RuntimeException cleanup) { cleanup.addSuppressed(failure); throw cleanup; }
            throw failure;
        }
    }

    private void configureOptions(OrtSession.SessionOptions options, String provider) throws OrtException {
        if (executionBudget != null) executionBudget.requireProvider(provider);
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL);
        options.setInterOpNumThreads(1);
        // The real-time lane deliberately keeps its CPU workers hot between 334 ms captures; the
        // optional quality lane sleeps so it cannot consume the latency budget while idle.
        options.addConfigEntry("session.inter_op.allow_spinning", "0");
        int threads = Math.max(1, Math.min(config.getInferenceThreads(),
                Runtime.getRuntime().availableProcessors()));
        if (executionBudget != null) {
            threads = executionBudget.nativeThreads(config.getInferenceThreads(), Runtime.getRuntime().availableProcessors());
        }
        if ("NNAPI".equals(provider)) {
            options.addConfigEntry("session.intra_op.allow_spinning",
                    latencyPriority ? "1" : "0");
            options.setIntraOpNumThreads(threads);
            // NNAPI's reference CPU device is often slower than ORT's optimized CPU kernels.
            // Unsupported accelerator nodes still fall back to ORT without changing precision.
            EnumSet<NNAPIFlags> flags = EnumSet.of(NNAPIFlags.CPU_DISABLED);
            if (nnapiFp16Relaxation) flags.add(NNAPIFlags.USE_FP16);
            options.addNnapi(flags);
        } else if ("XNNPACK".equals(provider)) {
            // XNNPACK owns a separate intra-op pool. A second multi-threaded ORT pool competes
            // with it and can make mobile inference slower while consuming substantially more CPU.
            options.addConfigEntry("session.intra_op.allow_spinning", "0");
            options.setIntraOpNumThreads(1);
            options.addXnnpack(Collections.singletonMap(
                    "intra_op_num_threads", Integer.toString(threads)));
        } else {
            options.addConfigEntry("session.intra_op.allow_spinning",
                    latencyPriority && (executionBudget == null || executionBudget.allowSpinning) ? "1" : "0");
            options.setIntraOpNumThreads(threads);
            options.addCPU(true);
        }
    }

    private long benchmark(OrtSession candidate) throws OrtException {
        int size = config.getInferenceResolution();
        FloatBuffer benchmarkInput = ByteBuffer.allocateDirect(
                size * size * 3 * Float.BYTES)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        for (int index = 0; index < benchmarkInput.capacity(); index++) {
            benchmarkInput.put((index & 0xff) * INV_255);
        }
        benchmarkInput.flip();
        try (TensorLease lease = new TensorLease(OnnxTensor.createTensor(
                environment,
                benchmarkInput,
                new long[]{1, 3, size, size}), strictResourceCleanup)) {
            OnnxTensor tensor = lease.tensor;
            Map<String, OnnxTensor> inputs = new LinkedHashMap<>();
            inputs.put(candidate.getInputNames().iterator().next(), tensor);
            long[] samples = new long[4];
            for (int pass = 0; pass < 5; pass++) {
                long started = SystemClock.elapsedRealtimeNanos();
                try (OutputLease ignored = new OutputLease(candidate.run(inputs), strictResourceCleanup)) {
                    // The first pass warms kernels; the next four select on sustained median speed.
                }
                long elapsed = SystemClock.elapsedRealtimeNanos() - started;
                if (pass > 0) samples[pass - 1] = elapsed;
            }
            Arrays.sort(samples);
            return samples[1] + (samples[2] - samples[1]) / 2L;
        }
    }

    private ProviderCandidate selectFastestProvider(
            byte[] modelBytes, String model, String[] providers) {
        OrtSession fastestSession = null;
        String fastestProvider = null;
        long fastestNanos = Long.MAX_VALUE;
        try {
            for (String provider : providers) {
                OrtSession candidate = null;
                try (OptionsLease lease = new OptionsLease(optionsFor(provider), strictResourceCleanup)) {
                    candidate = environment.createSession(modelBytes, lease.options);
                    long benchmarkNanos = benchmark(candidate);
                    Log.i(TAG, provider + " benchmark for " + model + ": "
                            + benchmarkNanos / 1_000_000f + " ms profile="
                            + (latencyPriority ? "realtime" : "quality"));
                    if (benchmarkNanos < fastestNanos) {
                        OrtSession retired = fastestSession;
                        fastestSession = null;
                        closeQuietly(retired);
                        fastestSession = candidate;
                        candidate = null;
                        fastestProvider = provider;
                        fastestNanos = benchmarkNanos;
                    }
                } catch (Exception error) {
                    if (strictResourceCleanup && resourceCleanupFailed(error)) throw new ResourceCleanupFailure(error);
                    Log.w(TAG, provider + " rejected " + model + ": " + error.getMessage());
                } finally {
                    closeQuietly(candidate);
                }
            }
        } catch (RuntimeException failure) {
            try { closeQuietly(fastestSession); }
            catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
        return fastestSession == null ? null
                : new ProviderCandidate(fastestSession, fastestProvider, fastestNanos);
    }

    private String providerCacheKey(String model) {
        String identity = android.os.Build.FINGERPRINT + '|' + model + '|'
                + config.getInferenceResolution() + '|' + config.getInferenceThreads()
                + '|' + (latencyPriority ? "realtime" : "quality")
                + '|' + PROVIDER_CONFIG_REVISION
                + (nnapiFp16Relaxation ? "|nnapi-fp16" : "");
        return "provider_" + Integer.toHexString(identity.hashCode());
    }

    private byte[] readAsset(String name) throws IOException {
        try (InputStream source = context.getAssets().open(name);
             ByteArrayOutputStream target = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[8192];
            int read;
            while ((read = source.read(chunk)) >= 0) target.write(chunk, 0, read);
            return target.toByteArray();
        }
    }

    private void allocateBuffers(int size) {
        allocateBuffers(size, size);
    }

    private void allocateBuffers(int width, int height) {
        closeInputTensor();
        bufferWidth = width;
        bufferHeight = height;
        pixels = new int[width * height];
        inputValues = new float[width * height * 3];
        directInput = ByteBuffer.allocateDirect(width * height * 3 * Float.BYTES)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        if (letterbox != null) letterbox.recycle();
        letterbox = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        letterboxCanvas = new Canvas(letterbox);
    }

    private void ensureBuffers(int width, int height) {
        if (bufferWidth != width || bufferHeight != height) allocateBuffers(width, height);
    }

    private void populateInputValues(int plane) {
        if (boundedRgbInput != null) {
            boundedRgbInput.write(pixels, inputValues, plane);
            return;
        }
        CountDownLatch finished = new CountDownLatch(3);
        preprocessExecutor.execute(() -> fillChannel(plane, 0, 16, finished));
        preprocessExecutor.execute(() -> fillChannel(plane, plane, 8, finished));
        preprocessExecutor.execute(() -> fillChannel(plane, plane * 2, 0, finished));
        boolean interrupted = false;
        while (true) {
            try {
                finished.await();
                break;
            } catch (InterruptedException ignored) {
                // Complete the shared-buffer write before returning. Restore interruption after
                // all three disjoint channel workers have finished.
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private void fillChannel(int plane, int outputOffset, int shift, CountDownLatch finished) {
        try {
            for (int index = 0; index < plane; index++) {
                inputValues[outputOffset + index] =
                        ((pixels[index] >>> shift) & 0xff) * INV_255;
            }
        } finally {
            finished.countDown();
        }
    }

    private static Thread preprocessThread(
            Runnable runnable, String name, int androidPriority) {
        Thread thread = new Thread(() -> {
            Process.setThreadPriority(androidPriority);
            runnable.run();
        }, name);
        thread.setDaemon(true);
        return thread;
    }

    /** Keeps the native-backed tensor alive across frames instead of reallocating JNI storage. */
    private void ensureInputTensor(int width, int height) throws OrtException {
        if (inputTensor != null && inferenceInputs != null
                && bufferWidth == width && bufferHeight == height) return;
        closeInputTensor();
        inputTensor = OnnxTensor.createTensor(
                environment, directInput, new long[]{1, 3, height, width});
        inferenceInputs = new LinkedHashMap<>(1);
        inferenceInputs.put(inputName, inputTensor);
    }

    private void closeInputTensor() {
        inferenceInputs = null;
        if (inputTensor != null) {
            OnnxTensor retired = inputTensor;
            inputTensor = null;
            new TensorLease(retired, strictResourceCleanup).close();
        }
    }

    private void closeSession() {
        closeInputTensor();
        OrtSession retired = session;
        session = null;
        closeQuietly(retired);
    }

    private void closeQuietly(OrtSession value) {
        if (value == null) return;
        closeNativeSession(value::close, strictResourceCleanup);
    }

    interface NativeSessionDisposer { void close() throws OrtException; }
    static void closeNativeSession(NativeSessionDisposer disposer, boolean strict) {
        try { disposer.close(); }
        catch (OrtException error) {
            if (strict) throw new ResourceCleanupFailure(error);
            Log.w(TAG, "Could not close ONNX session cleanly", error);
        } catch (RuntimeException error) {
            if (strict) throw new ResourceCleanupFailure(error);
            throw error;
        }
    }

    private static final class OptionsLease implements AutoCloseable {
        final OrtSession.SessionOptions options;
        final boolean strict;
        OptionsLease(OrtSession.SessionOptions options, boolean strict) { this.options = options; this.strict = strict; }
        @Override public void close() {
            try { options.close(); }
            catch (RuntimeException failure) {
                if (strict) throw new ResourceCleanupFailure(failure);
                throw failure;
            }
        }
    }

    private static final class TensorLease implements AutoCloseable {
        final OnnxTensor tensor;
        final boolean strict;
        TensorLease(OnnxTensor tensor, boolean strict) { this.tensor = tensor; this.strict = strict; }
        @Override public void close() {
            try { tensor.close(); }
            catch (RuntimeException failure) {
                if (strict) throw new ResourceCleanupFailure(failure);
                throw failure;
            }
        }
    }

    private static final class ProviderCandidate {
        final OrtSession session;
        final String provider;
        final long benchmarkNanos;

        ProviderCandidate(OrtSession session, String provider, long benchmarkNanos) {
            this.session = session;
            this.provider = provider;
            this.benchmarkNanos = benchmarkNanos;
        }
    }

    private static final class OutputLease implements AutoCloseable {
        final OrtSession.Result result;
        final boolean controlled;
        OutputLease(OrtSession.Result result, boolean controlled) { this.result = result; this.controlled = controlled; }
        @Override public void close() {
            try { result.close(); }
            catch (RuntimeException cleanupFailure) {
                if (controlled) throw new ResourceCleanupFailure(cleanupFailure);
                throw cleanupFailure;
            }
        }
    }

    private static final class InferenceRun implements AutoCloseable {
        final OrtSession.RunOptions options;
        private final AtomicBoolean cancellationRequested = new AtomicBoolean();
        private volatile boolean nativeRunActive;
        private boolean commitClosed;
        private boolean closed;

        InferenceRun() throws OrtException {
            options = new OrtSession.RunOptions();
        }

        synchronized boolean cancel() {
            if (closed || commitClosed
                    || !cancellationRequested.compareAndSet(false, true)) return false;
            try {
                options.setTerminate(true);
            } catch (OrtException error) {
                Log.w(TAG, "Could not preempt quality inference", error);
            }
            // Even if the execution provider cannot interrupt its current native call, the
            // software cancellation remains authoritative and its result will not be committed.
            return true;
        }

        synchronized boolean finishForCommit() {
            if (closed || cancellationRequested.get()) return false;
            commitClosed = true;
            return true;
        }

        boolean isCancellationRequested() { return cancellationRequested.get(); }
        boolean isNativeRunActive() { return nativeRunActive; }
        void enterNativeRun() { nativeRunActive = true; }
        void leaveNativeRun() { nativeRunActive = false; }

        @Override
        public synchronized void close() {
            if (closed) return;
            closed = true;
            try { options.close(); }
            catch (RuntimeException cleanupFailure) { throw new ResourceCleanupFailure(cleanupFailure); }
        }
    }

    @Override
    public synchronized void close() {
        if (executionBudget != null) {
            budgetRetired = true;
            CpuExecutionBudget.closeAll(performanceHints::close, this::closeInputTensor,
                    () -> {
                        OrtSession retired = session; session = null;
                        closeQuietly(retired);
                    },
                    () -> {
                        inputValues = null; directInput = null;
                        Bitmap retired = letterbox; letterbox = null; letterboxCanvas = null;
                        if (retired != null) retired.recycle();
                    }, boundedRgbInput::close);
            return;
        }
        performanceHints.close();
        closeSession();
        inputValues = null;
        directInput = null;
        if (letterbox != null) letterbox.recycle();
        letterbox = null;
        letterboxCanvas = null;
    }
}
