package com.subhub.app.censor.android;

import static org.junit.Assert.*;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.hardware.HardwareBuffer;
import android.os.SystemClock;
import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.core.app.ApplicationProvider;

import com.subhub.app.censor.engine.CensorEngine;
import com.subhub.app.censor.engine.SceneEngine;
import com.subhub.app.detection.BBox;
import com.subhub.app.detection.Detection;
import com.subhub.app.detection.DetectionEngine;
import com.subhub.app.detection.DetectorConfig;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Native pixels/worker contracts with an explicit fake API boundary, not a bound-service benchmark. */
@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion = 30)
public final class AccessibilityAdapterAndroidTest {
    private static final CensorEngine.WorkTiming TIMING = new CensorEngine.WorkTiming(3, 7, 2);

    @Test public void hardwareWrapperBufferAndReadableCopyHaveSeparateLifetimes() {
        try (Harness h = new Harness(34, false, 240, 320, 400, 600, 70, 100, 2)) {
            Bitmap source = pixels(240, 320);
            Bitmap hardware = source.copy(Bitmap.Config.HARDWARE, false); assertNotNull(hardware);
            HardwareBuffer buffer = hardware.getHardwareBuffer(); assertNotNull(buffer);
            Bitmap wrapped = Bitmap.wrapHardwareBuffer(buffer, hardware.getColorSpace()); assertNotNull(wrapped);
            hardware.recycle(); source.recycle();
            h.begin(); h.platform.deliver(new AccessibilityCapturer.RawFrame(wrapped, SystemClock.uptimeMillis(), buffer::close));
            assertTrue(wrapped.isRecycled()); assertTrue(buffer.isClosed());
            assertEquals(1, h.platform.windows); assertEquals(0, h.platform.displays); assertEquals(17, h.platform.windowId);
            assertEquals(1, h.engine.activeCount(SceneEngine.Lane.FAST)); h.fastExecutor.runOne();
            PixelFrame copy = h.inputs.get(0).pixels;
            assertFalse(copy.bitmap.isRecycled()); assertEquals(Color.rgb(37, 91, 143), copy.bitmap.getPixel(10, 10));
            assertEquals(-1, h.inputs.get(0).source.pixelAtMillis);
            assertTrue(copy.reportedUptimeMillis >= 0); assertTrue(copy.callbackUptimeMillis >= copy.reportedUptimeMillis);
            assertEquals(PixelFrame.Kind.ACCESSIBILITY_WINDOW, copy.kind);
            assertEquals(400, h.inputs.get(0).source.width); assertEquals(600, h.inputs.get(0).source.height);
            assertEquals(240, copy.bitmap.getWidth()); assertEquals(320, copy.bitmap.getHeight());
            h.engine.close(); assertTrue(copy.bitmap.isRecycled()); assertEquals(1, h.drained);
        }
    }

    @Test public void android13UsesDisplayWithoutInventingWindowScale() {
        try (Harness h = new Harness(33, false, 240, 320, 400, 600, 70, 100, 2)) {
            h.begin(); Bitmap raw = pixels(400, 600); h.platform.deliver(new AccessibilityCapturer.RawFrame(raw, 100, () -> { }));
            h.fastExecutor.runOne(); PixelFrame copied = h.inputs.get(0).pixels;
            assertEquals(0, h.platform.windows); assertEquals(1, h.platform.displays);
            assertEquals(PixelFrame.Kind.ACCESSIBILITY_DISPLAY, copied.kind);
            assertEquals(0, copied.geometry.left); assertEquals(0, copied.geometry.top);
            assertEquals(400, copied.geometry.width); assertEquals(600, copied.geometry.height);
            assertEquals(-1, h.inputs.get(0).source.pixelAtMillis);
        }
    }

    @Test public void sameSizedWindowMovementDuringCleanupRejectsTheOldOrigin() {
        try (Harness h = new Harness(34, false, 240, 320, 400, 600, 70, 100, 2)) {
            h.begin(); Bitmap raw = pixels(240, 320);
            h.platform.deliver(new AccessibilityCapturer.RawFrame(raw, 100, () -> h.left += 10));
            assertTrue(raw.isRecycled()); assertEquals(0, h.engine.activeCount(SceneEngine.Lane.FAST));
            assertEquals(0, h.engine.livePixelFrames()); assertEquals(0, h.prepared); assertEquals(0, h.engine.resourceFailures());
        }
    }

    @Test public void mismatchedWindowDimensionsRejectInsteadOfStretching() {
        try (Harness h = new Harness(34, false, 240, 320, 400, 600, 70, 100, 2)) {
            h.begin(); Bitmap raw = pixels(240, 300); int[] closed = {0};
            h.platform.deliver(new AccessibilityCapturer.RawFrame(raw, 100, () -> closed[0]++));
            assertTrue(raw.isRecycled()); assertEquals(1, closed[0]); assertEquals(0, h.engine.livePixelFrames());
            assertEquals(0, h.fastExecutor.tasks.size());
        }
    }

    @Test public void staleScopeReleasesRawPixelsWithoutStartingAnyWorker() {
        try (Harness h = new Harness(34, false, 240, 320, 400, 600, 70, 100, 2)) {
            h.begin(); h.engine.stop(); h.engine.start(42); Bitmap raw = pixels(240, 320); int[] closed = {0};
            h.platform.deliver(new AccessibilityCapturer.RawFrame(raw, 100, () -> closed[0]++));
            assertTrue(raw.isRecycled()); assertEquals(1, closed[0]); assertFalse(h.engine.captureInFlight());
            assertEquals(0, h.engine.livePixelFrames()); assertEquals(0, h.fastExecutor.tasks.size());
        }
    }

    @Test public void repeatedRawObjectCannotCloseTheAdoptedReadableSource() {
        try (Harness h = new Harness(34, false, 240, 320, 400, 600, 70, 100, 2)) {
            h.begin(); int[] closed = {0};
            AccessibilityCapturer.RawFrame raw = new AccessibilityCapturer.RawFrame(pixels(240, 320), 100, () -> closed[0]++);
            h.platform.deliver(raw); h.platform.deliver(raw); h.fastExecutor.runOne();
            assertEquals(1, closed[0]); assertEquals(1, h.prepared); assertFalse(h.inputs.get(0).pixels.bitmap.isRecycled());
        }
    }

    @Test public void rawCleanupFailureStopsAcquisitionAndCannotReportDrain() {
        try (Harness h = new Harness(34, false, 240, 320, 400, 600, 70, 100, 2)) {
            h.begin(); Bitmap raw = pixels(240, 320);
            h.platform.deliver(new AccessibilityCapturer.RawFrame(raw, 100, () -> { throw new IllegalStateException("cleanup fixture"); }));
            assertTrue(raw.isRecycled()); assertEquals(1, h.engine.resourceFailures());
            assertEquals(0, h.engine.livePixelFrames()); assertEquals(0, h.fastExecutor.tasks.size());
            assertFalse(h.engine.captureInFlight()); h.engine.close(); assertEquals(0, h.drained);
        }
    }

    @Test public void noOpPreparationBorrowsInputUntilActualCompletion() {
        try (Harness h = new Harness(34, false, 64, 128, 64, 128, 0, 0, 1)) {
            h.fastModel.behavior = call -> {
                assertSame(h.inputs.get(0).pixels.bitmap, call.bitmap);
                assertFalse(call.bitmap.isRecycled()); return output(true, false, face(10, 20, 20, 30));
            };
            h.begin(); h.platform.deliver(new AccessibilityCapturer.RawFrame(pixels(64, 128), 100, () -> { }));
            h.fastExecutor.runOne(); assertEquals(1, h.batches.size());
            assertFalse(h.inputs.get(0).pixels.bitmap.isRecycled()); assertEquals(0, h.engine.resourceFailures());
            region(h.batches.get(0).observations.get(0).region, 10, 20, 30, 50);
            h.engine.close(); assertTrue(h.inputs.get(0).pixels.bitmap.isRecycled());
        }
    }

    @Test public void qualityCropsPublishOnlyTheirOwnInspectedCategoryAndRegion() {
        try (Harness h = new Harness(34, true, 540, 1200, 600, 1500, 24, 50, 4)) {
            h.fastModel.behavior = call -> output(true, false);
            h.qualityModel.behavior = call -> output(true, false, face(100, 100, 50, 50));
            for (int pass = 0; pass < 3; pass++) {
                if (pass == 0) h.begin(); else h.next();
                h.platform.deliver(new AccessibilityCapturer.RawFrame(pixels(540, 1200), 100 + pass, () -> { }));
                h.fastExecutor.runOne(); h.qualityExecutor.runOne();
            }
            List<SceneEngine.Batch> quality = h.qualityBatches(); assertEquals(3, quality.size());
            for (int pass = 0; pass < 3; pass++) {
                SceneEngine.Batch batch = quality.get(pass);
                assertEquals(1, batch.inspections.size()); assertEquals("face", batch.inspections.get(0).category);
                assertSame(h.qualityInputs.get(pass).source, batch.source); assertEquals(600, batch.source.width);
                int cropTop = pass == 2 ? 216 : 0, cropHeight = pass == 0 ? 1200 : 984;
                region(batch.inspections.get(0).region, 24, 50 + cropTop, 564, 50 + cropTop + cropHeight);
                region(batch.observations.get(0).region, 124, 150 + cropTop, 174, 200 + cropTop);
                ModelCall call = h.qualityModel.calls.get(pass);
                assertEquals(540, call.sourceWidth); assertEquals(cropHeight, call.sourceHeight);
                assertEquals(512, call.inputWidth); assertEquals(512, call.inputHeight);
                assertTrue(call.bitmap.isRecycled()); // Temporary cleanup precedes terminal batch delivery.
                assertEquals(pass == 0 ? 230 : 281, call.preparedWidth);
                assertEquals(512, call.preparedHeight);
            }
        }
    }

    @Test public void incompleteAndCancelledQualityCannotPublishNegativeInspections() {
        for (boolean cancelled : new boolean[]{false, true}) {
            try (Harness h = new Harness(34, true, 540, 1200, 600, 1500, 24, 50, 4)) {
                h.fastModel.behavior = call -> output(true, false);
                h.qualityModel.behavior = call -> output(false, cancelled);
                h.begin(); h.platform.deliver(new AccessibilityCapturer.RawFrame(pixels(540, 1200), 100, () -> { }));
                h.fastExecutor.runOne(); h.qualityExecutor.runOne();
                assertTrue(h.qualityBatches().isEmpty()); assertEquals(0, h.engine.activeCount(SceneEngine.Lane.QUALITY));
                assertEquals(0, h.engine.resourceFailures());
            }
        }
    }

    @Test public void rectangularRetryUsesOneJobBindingForSuccessiveNativeRuns() {
        try (Harness h = new Harness(34, false, 540, 1200, 600, 1500, 24, 50, 4)) {
            h.fastModel.behavior = call -> {
                if (h.fastModel.calls.size() == 1) {
                    assertEquals(160, call.inputWidth); assertEquals(320, call.inputHeight);
                    throw new IllegalStateException("unsupported rectangular shape fixture");
                }
                assertEquals(320, call.inputWidth); assertEquals(320, call.inputHeight);
                return output(true, false, face(100, 100, 50, 50));
            };
            h.begin(); h.platform.deliver(new AccessibilityCapturer.RawFrame(pixels(540, 1200), 100, () -> { }));
            h.fastExecutor.runOne(); assertEquals(2, h.fastModel.calls.size()); assertEquals(1, h.batches.size());
            assertFalse(h.fastModel.calls.get(0).cancellation.cancel()); assertFalse(h.fastModel.calls.get(1).cancellation.cancel());
            assertEquals(0, h.engine.callbackFailures()); assertEquals(0, h.engine.resourceFailures());
        }
    }

    @Test public void stopDuringModelWorkKeepsPixelsUntilTheTerminalCancellation() {
        try (Harness h = new Harness(34, false, 540, 1200, 600, 1500, 24, 50, 4)) {
            h.fastModel.behavior = call -> {
                h.engine.close();
                assertFalse(h.inputs.get(0).pixels.bitmap.isRecycled()); assertFalse(call.bitmap.isRecycled());
                assertTrue(call.cancelled.getAsBoolean()); assertEquals(1, call.cancellation.signals); assertEquals(0, h.drained);
                return output(false, true);
            };
            h.begin(); h.platform.deliver(new AccessibilityCapturer.RawFrame(pixels(540, 1200), 100, () -> { }));
            h.fastExecutor.runOne(); assertTrue(h.inputs.get(0).pixels.bitmap.isRecycled());
            assertTrue(h.fastModel.calls.get(0).bitmap.isRecycled()); assertTrue(h.batches.isEmpty()); assertEquals(1, h.drained);
        }
    }

    @Test public void actualCpuWorkerRunsThroughTheReplacementOwner() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        DetectorConfig configured = DetectorConfig.builder().inferenceThreads(3).inferenceResolution(512).build();
        VisualDetectorWorker worker = VisualDetectorWorker.initialize(context, SceneEngine.Lane.FAST, configured, 1, "CPU");
        Bitmap bitmap = pixels(64, 128);
        List<Scheduled> timers = new ArrayList<>(); List<CensorEngine.Trace> traces = new ArrayList<>(); int[] drained = {0};
        CensorEngine<PixelFrame> engine = new CensorEngine<>(new CensorEngine.Policy(334, 0, 4, 1, false),
                new SceneEngine.Policy(8, 64, 64, 256, 5000, 20000, .5), SystemClock::uptimeMillis,
                (action, delay) -> { Scheduled task = new Scheduled(action); timers.add(task); return () -> task.cancelled = true; },
                (request, callback) -> {
                    assertTrue(request.tryDispatch());
                    PixelFrame frame = new PixelFrame(bitmap,
                            com.subhub.app.censor.engine.PixelGeometry.window(1000, 2000, 100, 200, 64, 128, 64, 128),
                            PixelFrame.Kind.ACCESSIBILITY_WINDOW, -1, SystemClock.uptimeMillis(), 0);
                    callback.success(new CensorEngine.Captured<>(frame, 1000, 2000, -1, bitmap::recycle));
                }, Collections.singletonList(new CensorEngine.LaneWorker<>(SceneEngine.Lane.FAST, Runnable::run, worker)),
                Runnable::run, new CensorEngine.Observer() {
                    @Override public void trace(CensorEngine.Trace trace) { traces.add(trace); }
                    @Override public void drained() { drained[0]++; }
                });
        try {
            engine.start(42); Scheduled capture = timers.remove(0); capture.action.run();
            CensorEngine.Trace completed = traces.stream().filter(t -> t.stage == CensorEngine.Stage.JOB_COMPLETED)
                    .findFirst().orElseThrow(AssertionError::new);
            assertNotNull(completed.work); assertTrue(completed.work.runtimeMillis > 0);
            assertEquals(-1, completed.pixelAgeMillis); assertEquals(0, engine.resourceFailures());
            assertEquals(0, engine.activeCount(SceneEngine.Lane.FAST));
            assertFalse(traces.stream().anyMatch(t -> t.stage == CensorEngine.Stage.JOB_FAILED || t.stage == CensorEngine.Stage.RESULT_REJECTED));
            engine.close(); assertEquals(1, drained[0]); assertTrue(bitmap.isRecycled());
        } finally { engine.close(); worker.close(); if (!bitmap.isRecycled()) bitmap.recycle(); }
    }

    private static Bitmap pixels(int width, int height) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        new Canvas(bitmap).drawColor(Color.rgb(37, 91, 143)); return bitmap;
    }

    private static Detection face(int x, int y, int width, int height) {
        return new Detection("FACE_FEMALE", "face", .9f, new BBox(x, y, width, height), false, true);
    }
    private static VisualDetectorWorker.ModelOutput output(boolean complete, boolean cancelled, Detection... detections) {
        return new VisualDetectorWorker.ModelOutput(Arrays.asList(detections), complete, cancelled, TIMING);
    }
    private static void region(SceneEngine.Region actual, double left, double top, double right, double bottom) {
        assertEquals(left, actual.left, 0); assertEquals(top, actual.top, 0);
        assertEquals(right, actual.right, 0); assertEquals(bottom, actual.bottom, 0);
    }

    private interface Behavior { VisualDetectorWorker.ModelOutput run(ModelCall call) throws Exception; }
    private static final class NativeCancellation implements DetectionEngine.RunCancellation {
        boolean closed;
        int signals;
        @Override public boolean cancel() { if (closed || signals != 0) return false; signals++; return true; }
    }
    private static final class ModelCall {
        final Bitmap bitmap;
        final int preparedWidth, preparedHeight, sourceWidth, sourceHeight, inputWidth, inputHeight;
        final BooleanSupplier cancelled;
        final NativeCancellation cancellation = new NativeCancellation();
        ModelCall(Bitmap bitmap, int sourceWidth, int sourceHeight, int inputWidth, int inputHeight, BooleanSupplier cancelled) {
            this.bitmap = bitmap; this.preparedWidth = bitmap.getWidth(); this.preparedHeight = bitmap.getHeight();
            this.sourceWidth = sourceWidth; this.sourceHeight = sourceHeight; this.inputWidth = inputWidth;
            this.inputHeight = inputHeight; this.cancelled = cancelled;
        }
    }
    private static final class FakeModel implements VisualDetectorWorker.Model {
        final List<ModelCall> calls = new ArrayList<>();
        Behavior behavior = call -> output(true, false, face(20, 30, 40, 50));
        @Override public String provider() { return "CPU"; }
        @Override public VisualDetectorWorker.ModelOutput infer(Bitmap bitmap, int sourceWidth, int sourceHeight,
                int inputWidth, int inputHeight, BooleanSupplier cancelled, Consumer<DetectionEngine.RunCancellation> binding) throws Exception {
            ModelCall call = new ModelCall(bitmap, sourceWidth, sourceHeight, inputWidth, inputHeight, cancelled);
            calls.add(call); binding.accept(call.cancellation);
            try { return behavior.run(call); }
            finally { call.cancellation.closed = true; }
        }
        @Override public void close() { }
    }
    private static final class Queue implements Executor {
        final List<Runnable> tasks = new ArrayList<>();
        @Override public void execute(Runnable action) { tasks.add(action); }
        void runOne() { assertFalse(tasks.isEmpty()); tasks.remove(0).run(); }
        void runAll() { while (!tasks.isEmpty()) runOne(); }
    }
    private static final class FakePlatform implements AccessibilityCapturer.Platform {
        AccessibilityCapturer.RawCallback callback;
        int windows, displays, windowId;
        @Override public void window(int windowId, Executor executor, AccessibilityCapturer.RawCallback callback) {
            this.windowId = windowId; windows++; this.callback = callback;
        }
        @Override public void display(Executor executor, AccessibilityCapturer.RawCallback callback) { displays++; this.callback = callback; }
        void deliver(AccessibilityCapturer.RawFrame raw) { callback.success(raw); }
    }
    private static final class Scheduled {
        final Runnable action;
        boolean cancelled;
        Scheduled(Runnable action) { this.action = action; }
    }

    private static final class Harness implements AutoCloseable {
        long now;
        int left;
        int prepared;
        int drained;
        final List<Scheduled> timers = new ArrayList<>();
        final Queue fastExecutor = new Queue(), qualityExecutor = new Queue();
        final FakePlatform platform = new FakePlatform();
        final FakeModel fastModel = new FakeModel(), qualityModel = new FakeModel();
        final List<CensorEngine.Input<PixelFrame>> inputs = new ArrayList<>(), qualityInputs = new ArrayList<>();
        final List<SceneEngine.Batch> batches = new ArrayList<>();
        final VisualDetectorWorker fast, quality;
        final CensorEngine<PixelFrame> engine;

        Harness(int sdk, boolean qualityEnabled, int width, int height, int displayWidth, int displayHeight,
                int left, int top, int threads) {
            this.left = left;
            DetectorConfig configured = DetectorConfig.builder().inferenceThreads(threads).inferenceResolution(512)
                    .enabledCategories(new LinkedHashSet<>(Arrays.asList("face", "unsupported"))).build();
            fast = new VisualDetectorWorker(SceneEngine.Lane.FAST, configured, 1, fastModel);
            quality = new VisualDetectorWorker(SceneEngine.Lane.QUALITY, configured, 1, qualityModel);
            AccessibilityCapturer capturer = new AccessibilityCapturer(platform, sdk, Runnable::run,
                    request -> target(request, width, height, displayWidth, displayHeight, top), new AccessibilityCapturer.Observer() {
                        @Override public void prepared(long requestId, PixelFrame.Kind kind, long nanos, int bytes) { prepared++; }
                    });
            List<CensorEngine.LaneWorker<PixelFrame>> workers = new ArrayList<>();
            workers.add(new CensorEngine.LaneWorker<>(SceneEngine.Lane.FAST, fastExecutor, recording(fast, inputs)));
            if (qualityEnabled) workers.add(new CensorEngine.LaneWorker<>(SceneEngine.Lane.QUALITY, qualityExecutor, recording(quality, qualityInputs)));
            engine = new CensorEngine<>(new CensorEngine.Policy(334, 0, 6, 2, false),
                    new SceneEngine.Policy(16, 64, 64, 256, 5000, 20000, .5), () -> now,
                    (action, delay) -> { Scheduled task = new Scheduled(action); timers.add(task); return () -> task.cancelled = true; },
                    capturer, workers, Runnable::run, new CensorEngine.Observer() {
                        @Override public void drained() { drained++; }
                    });
        }
        private AccessibilityCapturer.Target target(CensorEngine.CaptureRequest request, int width, int height,
                int displayWidth, int displayHeight, int top) {
            return new AccessibilityCapturer.Target(engine.currentEpoch(), 42, 17, "fixture", displayWidth, displayHeight,
                    left, top, width, height);
        }
        void begin() { engine.start(42); fire(); }
        void next() { now += 334; fire(); }
        void fire() {
            Scheduled scheduled = timers.stream().filter(t -> !t.cancelled).findFirst().orElseThrow(AssertionError::new);
            timers.remove(scheduled); scheduled.action.run();
        }
        private CensorEngine.Worker<PixelFrame> recording(VisualDetectorWorker worker, List<CensorEngine.Input<PixelFrame>> observed) {
            return (input, completion) -> {
                observed.add(input);
                worker.start(input, new CensorEngine.Completion() {
                    @Override public void success(SceneEngine.Batch batch, CensorEngine.WorkTiming timing) {
                        assertFalse(input.pixels.bitmap.isRecycled()); batches.add(batch); completion.success(batch, timing);
                    }
                    @Override public void failure() { completion.failure(); }
                    @Override public void cancelled() { completion.cancelled(); }
                    @Override public void resourceFailure() { completion.resourceFailure(); }
                });
            };
        }
        List<SceneEngine.Batch> qualityBatches() {
            List<SceneEngine.Batch> result = new ArrayList<>();
            for (SceneEngine.Batch batch : batches) if (batch.lane == SceneEngine.Lane.QUALITY) result.add(batch);
            return result;
        }
        @Override public void close() {
            engine.close();
            if (engine.captureInFlight()) platform.callback.failure(-1);
            fastExecutor.runAll(); qualityExecutor.runAll(); fast.close(); quality.close();
        }
    }
}
