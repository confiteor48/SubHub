package com.subhub.app.censor.android;

import android.content.Context;
import android.graphics.Bitmap;

import com.subhub.app.censor.engine.CensorEngine;
import com.subhub.app.censor.engine.SceneEngine;
import com.subhub.app.censor.engine.VisualInferencePolicy;
import com.subhub.app.detection.BBox;
import com.subhub.app.detection.Detection;
import com.subhub.app.detection.DetectionEngine;
import com.subhub.app.detection.DetectorConfig;
import com.subhub.app.service.InferenceBitmapPreparer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Model execution only: one completed source-local batch, no retained detections or scene state. */
public final class VisualDetectorWorker implements CensorEngine.Worker<PixelFrame>, AutoCloseable {
    public interface Model extends AutoCloseable {
        String provider();
        ModelOutput infer(Bitmap bitmap, int sourceWidth, int sourceHeight, int inputWidth, int inputHeight,
                BooleanSupplier cancelled, Consumer<DetectionEngine.RunCancellation> binding) throws Exception;
        @Override void close();
    }

    public static final class ModelOutput {
        public final List<Detection> detections;
        public final boolean complete;
        public final boolean cancelled;
        public final CensorEngine.WorkTiming timing;
        public ModelOutput(List<Detection> detections, boolean complete, boolean cancelled, CensorEngine.WorkTiming timing) {
            this.detections = Collections.unmodifiableList(new ArrayList<>(detections));
            this.complete = complete;
            this.cancelled = cancelled;
            this.timing = Objects.requireNonNull(timing);
        }
    }

    private final SceneEngine.Lane lane;
    private final DetectorConfig config;
    private final int threads;
    private final long partition;
    private final Model model;
    private long qualityPass;
    private boolean rectangularDisabled;

    public VisualDetectorWorker(SceneEngine.Lane lane, DetectorConfig configured, long partition, Model model) {
        if (lane != SceneEngine.Lane.FAST && lane != SceneEngine.Lane.QUALITY) throw new IllegalArgumentException("visual lane");
        this.lane = lane;
        this.config = lane == SceneEngine.Lane.FAST ? VisualInferencePolicy.fast(configured) : VisualInferencePolicy.quality(configured);
        this.threads = configured.getInferenceThreads();
        this.partition = partition;
        this.model = Objects.requireNonNull(model);
    }

    /** Initialize on its lane executor. Optional quality initialization must not block fast startup. */
    public static VisualDetectorWorker initialize(Context context, SceneEngine.Lane lane, DetectorConfig config,
            long partition, String fallbackProvider) throws Exception {
        if (lane != SceneEngine.Lane.FAST && lane != SceneEngine.Lane.QUALITY) throw new IllegalArgumentException("visual lane");
        DetectorConfig selected = lane == SceneEngine.Lane.FAST ? VisualInferencePolicy.fast(config) : VisualInferencePolicy.quality(config);
        DetectionEngine detector = new DetectionEngine(context, selected, lane == SceneEngine.Lane.FAST);
        try {
            if (lane == SceneEngine.Lane.QUALITY) detector.initializeWithoutBenchmark(fallbackProvider);
            else if (config.getInferenceThreads() >= 3 && config.getInferenceResolution() > VisualInferencePolicy.FAST_RESOLUTION) {
                try { detector.initializeForProvider("CPU"); }
                catch (Exception unsupportedCpu) { detector.initialize(); }
            } else detector.initialize();
            return new VisualDetectorWorker(lane, config, partition, new NativeModel(detector));
        } catch (Exception initializationFailure) { detector.close(); throw initializationFailure; }
    }

    @Override public void start(CensorEngine.Input<PixelFrame> input, CensorEngine.Completion completion) {
        RunBinding binding = new RunBinding();
        input.onCancellation(binding::cancel);
        InferenceBitmapPreparer.Prepared prepared = null;
        SceneEngine.Batch batch = null;
        CensorEngine.WorkTiming timing = null;
        boolean cancelled = false;
        boolean failed = false;
        boolean cleanupFailed = false;
        try {
            PixelFrame frame = input.pixels;
            if (input.source.width != frame.geometry.displayWidth || input.source.height != frame.geometry.displayHeight) {
                throw new IllegalArgumentException("source/display dimensions");
            }
            if (input.cancelled()) cancelled = true;
            else {
                VisualInferencePolicy.Crop crop = lane == SceneEngine.Lane.QUALITY
                        ? VisualInferencePolicy.qualityCrop(frame.bitmap.getWidth(), frame.bitmap.getHeight(), qualityPass++)
                        : new VisualInferencePolicy.Crop(0, 0, frame.bitmap.getWidth(), frame.bitmap.getHeight());
                long preparationStarted = System.nanoTime();
                prepared = InferenceBitmapPreparer.prepareRegion(frame.bitmap, crop.left, crop.top, crop.width, crop.height,
                        config.getInferenceResolution());
                if (prepared == null) throw new IllegalStateException("inference preparation unavailable");
                long preparationMillis = Math.max(0, (System.nanoTime() - preparationStarted) / 1_000_000L);
                ModelOutput result = infer(input, prepared, binding);
                cancelled = input.cancelled() || result.cancelled;
                if (!cancelled && result.complete) {
                    long mappingStarted = System.nanoTime();
                    List<SceneEngine.Observation> observations = new ArrayList<>();
                    for (Detection detection : result.detections) {
                        if (detection == null) throw new IllegalArgumentException("null detection");
                        BBox box = detection.getBox();
                        long x1 = Math.max(0L, box.getX()), y1 = Math.max(0L, box.getY());
                        long x2 = Math.min((long) crop.width, (long) box.getX() + box.getWidth());
                        long y2 = Math.min((long) crop.height, (long) box.getY() + box.getHeight());
                        if (x2 <= x1 || y2 <= y1) continue;
                        SceneEngine.Region region = frame.geometry.toDisplay(new BBox((int) x1 + crop.left,
                                (int) y1 + crop.top, (int) (x2 - x1), (int) (y2 - y1)));
                        if (region != null) observations.add(new SceneEngine.Observation(detection.getCategory(), partition,
                                0, region, detection.getConfidence()));
                    }
                    List<SceneEngine.Inspection> inspections = new ArrayList<>();
                    if (lane == SceneEngine.Lane.QUALITY) {
                        SceneEngine.Region inspected = frame.geometry.toDisplay(new BBox(crop.left, crop.top, crop.width, crop.height));
                        for (String category : VisualInferencePolicy.inspectedCategories(config)) {
                            inspections.add(new SceneEngine.Inspection(category, partition, inspected));
                        }
                    }
                    batch = new SceneEngine.Batch(input.source, lane, observations, inspections);
                    timing = new CensorEngine.WorkTiming(preparationMillis + result.timing.preprocessMillis,
                            result.timing.runtimeMillis, result.timing.postprocessMillis
                                    + Math.max(0, (System.nanoTime() - mappingStarted) / 1_000_000L));
                } else if (!cancelled) failed = true;
            }
        } catch (Exception inferenceFailure) {
            cleanupFailed = DetectionEngine.resourceCleanupFailed(inferenceFailure);
            cancelled = input.cancelled(); failed = !cancelled;
        }
        finally {
            binding.finish();
            // A no-op software scale can alias the runtime's borrowed source. Never recycle it here.
            try {
                if (prepared != null && prepared.bitmap != input.pixels.bitmap && !prepared.bitmap.isRecycled()) prepared.bitmap.recycle();
            } catch (RuntimeException cleanupFailure) { cleanupFailed = true; }
        }
        if (cleanupFailed) completion.resourceFailure();
        else if (cancelled || input.cancelled()) completion.cancelled();
        else if (failed || batch == null) completion.failure();
        else completion.success(batch, timing);
    }

    private ModelOutput infer(CensorEngine.Input<PixelFrame> input, InferenceBitmapPreparer.Prepared prepared,
            RunBinding binding) throws Exception {
        int size = config.getInferenceResolution();
        int[] rectangle = lane == SceneEngine.Lane.FAST && threads >= 3 && !rectangularDisabled && "CPU".equals(model.provider())
                ? VisualInferencePolicy.rectangularInput(prepared.sourceWidth, prepared.sourceHeight, size) : null;
        if (rectangle != null) {
            try {
                ModelOutput output = model.infer(prepared.bitmap, prepared.sourceWidth, prepared.sourceHeight,
                        rectangle[0], rectangle[1], input::cancelled, binding::bind);
                if (output.complete || output.cancelled || input.cancelled()) return output;
            } catch (Exception unsupportedShape) {
                if (input.cancelled() || DetectionEngine.resourceCleanupFailed(unsupportedShape)) throw unsupportedShape;
            }
            rectangularDisabled = true;
        }
        return model.infer(prepared.bitmap, prepared.sourceWidth, prepared.sourceHeight, size, size,
                input::cancelled, binding::bind);
    }

    /** One exact job binding may own successive child runs during a rectangular-to-square retry. */
    private static final class RunBinding {
        private DetectionEngine.RunCancellation child;
        private boolean cancelled;
        private boolean finished;
        void bind(DetectionEngine.RunCancellation next) {
            boolean signal;
            synchronized (this) { child = Objects.requireNonNull(next); signal = cancelled || finished; }
            if (signal) next.cancel();
        }
        void cancel() {
            DetectionEngine.RunCancellation current;
            synchronized (this) { if (finished) return; cancelled = true; current = child; }
            if (current != null) current.cancel();
        }
        synchronized void finish() { finished = true; child = null; }
    }

    /** Close only after the owner reports all jobs drained, never while a native run uses input. */
    @Override public void close() { model.close(); }

    private static final class NativeModel implements Model {
        private final DetectionEngine detector;
        NativeModel(DetectionEngine detector) { this.detector = detector; }
        @Override public String provider() { return detector.getActiveProvider(); }
        @Override public ModelOutput infer(Bitmap bitmap, int sourceWidth, int sourceHeight, int inputWidth, int inputHeight,
                BooleanSupplier cancelled, Consumer<DetectionEngine.RunCancellation> binding) throws Exception {
            DetectionEngine.ControlledResult result = detector.detectControlled(bitmap, sourceWidth, sourceHeight,
                    inputWidth, inputHeight, cancelled, binding);
            return new ModelOutput(result.detections, result.complete, result.cancelled,
                    new CensorEngine.WorkTiming(result.preprocessMillis, result.runtimeMillis, result.postprocessMillis));
        }
        @Override public void close() { detector.close(); }
    }
}
