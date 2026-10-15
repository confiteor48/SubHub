package com.subhub.app.censor.android;

import static org.junit.Assert.*;

import android.app.UiAutomation;
import android.graphics.Bitmap;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.TaskCompletionSource;
import com.subhub.app.censor.engine.CensorEngine;
import com.subhub.app.censor.engine.NativeTextFrame;
import com.subhub.app.censor.engine.NativeTextResult;
import com.subhub.app.censor.engine.PixelGeometry;
import com.subhub.app.censor.engine.SceneEngine;
import com.subhub.app.detection.DetectorConfig;
import com.subhub.app.detection.text.ImageTextLayout;
import com.subhub.app.detection.text.NativeTextReader;
import com.subhub.app.detection.text.SmutTextClassifier;
import com.subhub.app.detection.text.TextSmutConfig;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Real host/reader/OCR lane; controlled provider, captures and recognizer. Not a service or speed test. */
@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion = 33)
public final class NativeReadAuthorityHostAndroidTest {
    @Test public void completePartialCompleteControlsActualHostOcrDispatchAndRejectsInflightFallback() throws Exception {
        Harness h = new Harness(); boolean passed = false; Throwable primary = null;
        long firstImage = -1, partialImage = -1, finalImage = -1;
        try (Harness owned = h) {
            h.start(); assertTrue("First stable cohort read held", h.firstDirect.await(5, TimeUnit.SECONDS));
            NativeTextFrame initial = h.terminalFrames.get(0); assertEquals(NativeTextResult.Coverage.COMPLETE_TREE, initial.read.coverage);
            firstImage = h.deliverNext(); final long first = firstImage;
            await("Complete authority cancels actual host OCR slot", () -> h.terminal(first, CensorEngine.Stage.JOB_CANCELLED));
            assertEquals(0, h.ocrInitializations.get()); assertEquals(0, h.recognizer.processes.get());
            assertTrue(h.trace(first, CensorEngine.Stage.JOB_CANCELLED).atMillis <= deadline(initial));

            h.firstDirectRelease.countDown(); assertTrue("Next bounded FULL held", h.nextFull.await(5, TimeUnit.SECONDS));
            assertEquals(2, h.terminalFrames.size());
            assertEquals(NativeTextResult.ReadMode.ADMITTED_NODE_COHORT_RECLASSIFY, h.terminalFrames.get(1).read.readMode);
            assertEquals(NativeTextResult.Coverage.POSITIVE_NODES_ONLY, h.terminalFrames.get(1).read.coverage);
            assertEquals(2, h.terminalFrames.get(1).read.candidates.size());
            partialImage = h.deliverNext(); assertTrue("Partial authority permits real OCR worker invocation", h.recognizer.entered.await(5, TimeUnit.SECONDS));
            assertEquals(1, h.ocrInitializations.get()); assertEquals(1, h.recognizer.processes.get());
            assertFalse(h.recognizer.task.getTask().isComplete());

            h.nextFullRelease.countDown(); await("Fresh COMPLETE admitted", () -> h.terminalFrames.size() >= 3);
            NativeTextFrame fresh = h.terminalFrames.get(2); assertEquals(NativeTextResult.Coverage.COMPLETE_TREE, fresh.read.coverage);
            h.recognizer.task.setResult(Collections.singletonList(Collections.singletonList(
                    new ImageTextLayout.Line("porn", new SceneEngine.Region(10, 20, 100, 50)))));
            final long partial = partialImage;
            await("In-flight fallback cannot publish after complete authority returns", () -> h.terminal(partial, CensorEngine.Stage.RESULT_REJECTED));
            assertTrue(h.batches.isEmpty());
            assertTrue("Successor cohort held before it can revoke COMPLETE", h.finalDirect.await(5, TimeUnit.SECONDS));
            finalImage = h.deliverNext(); final long last = finalImage;
            await("Fresh complete authority again cancels host OCR slot", () -> h.terminal(last, CensorEngine.Stage.JOB_CANCELLED));
            assertEquals(1, h.ocrInitializations.get()); assertEquals(1, h.recognizer.processes.get());
            assertTrue(h.trace(last, CensorEngine.Stage.JOB_CANCELLED).atMillis <= deadline(fresh));
            assertEquals(0, h.visualInitializations.get()); passed = true;
        } catch (Exception | Error failure) { primary = failure; throw failure; }
        finally {
            try { h.write(passed, firstImage, partialImage, finalImage); }
            catch (Exception | Error failure) { if (primary == null) throw failure; if (primary != failure) primary.addSuppressed(failure); }
        }
    }

    private static void main(Runnable action) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> { try { action.run(); } catch (Throwable value) { failure.set(value); } });
        if (failure.get() instanceof Error) throw (Error) failure.get();
        if (failure.get() instanceof RuntimeException) throw (RuntimeException) failure.get();
        if (failure.get() != null) throw new AssertionError(failure.get());
    }
    private static long deadline(NativeTextFrame frame) {
        long result = Long.MAX_VALUE;
        for (NativeTextResult.Candidate candidate : frame.read.candidates) result = Math.min(result, frame.candidateValidUntilMillis(candidate));
        return result;
    }
    private static void await(String label, BooleanSupplier condition) throws InterruptedException {
        long deadline = SystemClock.uptimeMillis() + 5000;
        while (!condition.getAsBoolean()) { assertTrue(label, SystemClock.uptimeMillis() < deadline); Thread.sleep(10); }
    }
    private static void hold(CountDownLatch release) {
        try { assertTrue("Controlled host reader hold", release.await(8, TimeUnit.SECONDS)); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
    }
    private static final class Capture {
        final CensorEngine.CaptureRequest request; final CensorEngine.CaptureCallback<PixelFrame> callback;
        Capture(CensorEngine.CaptureRequest request, CensorEngine.CaptureCallback<PixelFrame> callback) { this.request = request; this.callback = callback; }
    }
    private static final class Recognizer implements ImageTextWorker.Recognizer {
        final AtomicInteger processes = new AtomicInteger(), closes = new AtomicInteger();
        final CountDownLatch entered = new CountDownLatch(1);
        final TaskCompletionSource<List<List<ImageTextLayout.Line>>> task = new TaskCompletionSource<>();
        volatile long processAt = -1;
        @Override public Task<?> process(Bitmap bitmap) {
            assertNotEquals(Looper.getMainLooper(), Looper.myLooper()); assertEquals(1, processes.incrementAndGet());
            processAt = SystemClock.uptimeMillis(); entered.countDown(); return task.getTask();
        }
        @SuppressWarnings("unchecked") @Override public List<List<ImageTextLayout.Line>> lines(Task<?> terminal) {
            assertSame(task.getTask(), terminal); assertTrue(terminal.isComplete()); return (List<List<ImageTextLayout.Line>>) terminal.getResult();
        }
        @Override public void close() { closes.incrementAndGet(); }
    }
    private static final class Harness implements AutoCloseable {
        // Establish UiAutomation before target Views, as required for native click/provider fixtures.
        final UiAutomation automation = InstrumentationRegistry.getInstrumentation().getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        final ActivityScenario<NativeTextFixtureActivity> scenario = ActivityScenario.launch(NativeTextFixtureActivity.class);
        final List<NativeTextFrame> frames = new CopyOnWriteArrayList<>();
        final List<NativeTextFrame> terminalFrames = new CopyOnWriteArrayList<>();
        final List<CensorEngine.Trace> traces = new CopyOnWriteArrayList<>();
        final List<SceneEngine.Batch> batches = new CopyOnWriteArrayList<>();
        final List<Capture> allCaptures = new CopyOnWriteArrayList<>();
        final BlockingQueue<Capture> captures = new LinkedBlockingQueue<>();
        final CountDownLatch firstDirect = new CountDownLatch(1), firstDirectRelease = new CountDownLatch(1);
        final CountDownLatch nextFull = new CountDownLatch(1), nextFullRelease = new CountDownLatch(1);
        final CountDownLatch finalDirect = new CountDownLatch(1), finalDirectRelease = new CountDownLatch(1), terminated = new CountDownLatch(1);
        final AtomicInteger fulls = new AtomicInteger(), directs = new AtomicInteger();
        final AtomicInteger ocrInitializations = new AtomicInteger(), visualInitializations = new AtomicInteger();
        final Recognizer recognizer = new Recognizer();
        AccessibilityCensorHost host; NativeTextFixtureActivity activity;
        volatile NativeTextReader.Scope selectedScope;
        int width, height; volatile boolean disposed;
        Harness() {
            scenario.onActivity(value -> { activity = value; width = value.getResources().getDisplayMetrics().widthPixels;
                height = value.getResources().getDisplayMetrics().heightPixels; });
            main(() -> host = new AccessibilityCensorHost(activity, new SceneEngine.Policy(16, 100, 64, 256, 5000, 20000, .5),
                    executor -> (request, callback) -> {
                        assertTrue(request.tryDispatch()); Capture capture = new Capture(request, callback); allCaptures.add(capture); captures.add(capture);
                    }, (context, lane, config, partition, fallback) -> {
                        visualInitializations.incrementAndGet(); throw new AssertionError("No visual model in controlled native/OCR test");
                    }, new AccessibilityCensorHost.Observer() {
                        @Override public void trace(CensorEngine.Trace trace) { traces.add(trace); }
                        @Override public void nativeTextAccepted(NativeTextFrame frame) {
                            frames.add(frame); if (frame.observationOrdinal == 0) terminalFrames.add(frame);
                        }
                        @Override public void batchAccepted(SceneEngine.Batch batch) { batches.add(batch); }
                        @Override public void executorsTerminated(boolean value) { disposed = value; terminated.countDown(); }
                    }, new NativeTextReader.Provider() {
                        @Override public boolean addressRefreshSupported() { return true; }
                        @Override public NativeTextReader.Tree open(CensorEngine.NativeTextInput input) throws Exception {
                            long deadline = SystemClock.uptimeMillis() + 5000;
                            while (SystemClock.uptimeMillis() < deadline) {
                                AccessibilityNodeInfo root = automation.getRootInActiveWindow();
                                if (root != null) {
                                    if ("com.subhub.app".contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) {
                                        NativeTextReader.Tree tree = new NativeTextReader.Tree(root, width, height, activity.contentGeneration.get());
                                        selectedScope = new NativeTextReader.Scope(width, height, tree.windowId, tree.packageName, tree.contentGeneration);
                                        return tree;
                                    }
                                    root.recycle();
                                }
                                Thread.sleep(10);
                            }
                            throw new IllegalStateException("Controlled provider root unavailable");
                        }
                        @Override public NativeTextReader.Scope openScope(CensorEngine.NativeTextInput input) { return selectedScope; }
                        @Override public boolean current(CensorEngine.NativeTextInput input, NativeTextReader.Tree tree) {
                            return current(input, (NativeTextReader.Scope) tree, NativeTextReader.Boundary.RESULT_END);
                        }
                        @Override public boolean locallyCurrent(CensorEngine.NativeTextInput input, NativeTextReader.Scope scope) {
                            return valid(input, scope);
                        }
                        @Override public boolean current(CensorEngine.NativeTextInput input, NativeTextReader.Scope scope, NativeTextReader.Boundary boundary) {
                            if (boundary == NativeTextReader.Boundary.OPENED) {
                                if (input.readMode() == NativeTextResult.ReadMode.FULL_DISCOVERY && fulls.incrementAndGet() == 2) {
                                    nextFull.countDown(); hold(nextFullRelease);
                                } else if (input.readMode() == NativeTextResult.ReadMode.ADMITTED_NODE_COHORT_RECLASSIFY) {
                                    if (directs.incrementAndGet() == 1) { firstDirect.countDown(); hold(firstDirectRelease); }
                                    else if (fulls.get() >= 2) { finalDirect.countDown(); hold(finalDirectRelease); }
                                }
                            }
                            return valid(input, scope);
                        }
                        private boolean valid(CensorEngine.NativeTextInput input, NativeTextReader.Scope scope) {
                            return !input.cancelled() && input.epoch == host.epoch() && input.geometry == 42
                                    && scope != null && scope.contentGeneration == activity.contentGeneration.get();
                        }
                    }, (context, partition) -> {
                        ocrInitializations.incrementAndGet(); return new ImageTextWorker(recognizer, new SmutTextClassifier(), partition);
                    }));
        }
        void start() {
            DetectorConfig config = DetectorConfig.builder().enabledCategories(Collections.emptySet()).inferenceThreads(4).build();
            main(() -> host.start(42, config, false, new TextSmutConfig(true, TextSmutConfig.SENSITIVITY_BALANCED, null), false, false));
        }
        long deliverNext() throws InterruptedException {
            Capture capture = captures.poll(5, TimeUnit.SECONDS); assertNotNull("Controlled image request", capture);
            long before = traces.stream().filter(t -> t.stage == CensorEngine.Stage.JOB_QUEUED && t.lane == SceneEngine.Lane.TEXT)
                    .mapToLong(t -> t.source).max().orElse(0);
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            PixelFrame frame = new PixelFrame(bitmap, PixelGeometry.display(width, height, width, height),
                    PixelFrame.Kind.ACCESSIBILITY_DISPLAY, -1, SystemClock.uptimeMillis(), 0);
            CensorEngine.Captured<PixelFrame> payload = new CensorEngine.Captured<>(frame, width, height, -1, bitmap::recycle);
            try { capture.callback.success(payload); } finally { payload.close(); }
            long source = traces.stream().filter(t -> t.stage == CensorEngine.Stage.JOB_QUEUED && t.lane == SceneEngine.Lane.TEXT)
                    .mapToLong(t -> t.source).max().orElse(0);
            assertTrue("New actual source queued to TEXT lane", source > before); return source;
        }
        boolean terminal(long source, CensorEngine.Stage stage) { return trace(source, stage) != null; }
        CensorEngine.Trace trace(long source, CensorEngine.Stage stage) {
            return traces.stream().filter(t -> t.source == source && t.lane == SceneEngine.Lane.TEXT && t.stage == stage).findFirst().orElse(null);
        }
        @Override public void close() throws Exception {
            main(host::close); firstDirectRelease.countDown(); nextFullRelease.countDown(); finalDirectRelease.countDown();
            recognizer.task.trySetResult(Collections.emptyList());
            for (Capture capture : allCaptures) capture.callback.failure(0);
            assertTrue("Actual host executors terminate", terminated.await(5, TimeUnit.SECONDS)); assertTrue(disposed); scenario.close();
        }
        void write(boolean passed, long firstImage, long partialImage, long finalImage) throws Exception {
            JSONArray nativeFrames = new JSONArray(), events = new JSONArray();
            for (NativeTextFrame frame : frames) nativeFrames.put(new JSONObject(com.subhub.app.diagnostics.ReplacementEngineTelemetry.nativeRead(1, frame)
                    .substring("REPLACEMENT_NATIVE ".length())));
            for (CensorEngine.Trace trace : traces) events.put(new JSONObject().put("source", trace.source).put("epoch", trace.epoch)
                    .put("stage", trace.stage.name()).put("lane", trace.lane == null ? JSONObject.NULL : trace.lane.name()).put("atMillis", trace.atMillis));
            JSONObject receipt = new JSONObject().put("schemaVersion", 2).put("label", "native-authority-host")
                    .put("pid", android.os.Process.myPid())
                    .put("controlledProviderCaptureRecognizer", true).put("actualHost", true).put("actualBoundService", false)
                    .put("bodyPassed", passed).put("managedDrain", disposed).put("firstImageSource", firstImage)
                    .put("partialImageSource", partialImage).put("finalImageSource", finalImage).put("ocrInitializations", ocrInitializations.get())
                    .put("ocrProcesses", recognizer.processes.get()).put("ocrCloses", recognizer.closes.get()).put("ocrProcessAtMillis", recognizer.processAt)
                    .put("visualInitializations", visualInitializations.get()).put("imageBatchAdmissions", batches.size())
                    .put("rawFrameCount", nativeFrames.length()).put("frames", nativeFrames)
                    .put("rawTerminalFrameCount", terminalFrames.size())
                    .put("rawObservationCount", frames.size() - terminalFrames.size())
                    .put("rawTraceCount", events.length()).put("traces", events)
                    .put("pixelPerformanceAcceptance", false);
            File directory = activity.getExternalFilesDir("censor-tap-fixtures-20261011"); assertNotNull(directory);
            assertTrue(directory.isDirectory() || directory.mkdirs());
            try (FileOutputStream output = new FileOutputStream(new File(directory, "native-authority-host-" + System.nanoTime() + ".json"))) {
                output.write(receipt.toString().getBytes(StandardCharsets.UTF_8));
            }
        }
    }
}
