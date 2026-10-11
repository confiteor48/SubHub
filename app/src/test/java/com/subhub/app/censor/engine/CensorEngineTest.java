package com.subhub.app.censor.engine;

import static com.subhub.app.censor.engine.SceneEngine.Lane.FAST;
import static com.subhub.app.censor.engine.SceneEngine.Lane.QUALITY;
import static com.subhub.app.censor.engine.SceneEngine.Lane.TEXT;
import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import org.junit.Test;

public class CensorEngineTest {
    private static final CensorEngine.WorkTiming TIMING = new CensorEngine.WorkTiming(3, 10, 2);
    private static final SceneEngine.Region SUPPORT = new SceneEngine.Region(-10000, -10000, 10000, 10000);

    @Test public void callbackBeforeApiReturnKeepsSlotAndSpacingFromActualReturn() {
        Harness h = new Harness(); Pixel pixel = new Pixel(1);
        h.behavior = (request, callback) -> {
            assertTrue(request.tryDispatch());
            callback.success(pixel.payload());
            assertTrue(h.engine.captureInFlight()); assertEquals(1, h.engine.livePixelFrames());
            h.engine.requestCaptureAt(0); assertEquals(Long.MAX_VALUE, h.timer.nextAt());
            h.now = 20;
        };
        h.start(); h.fire(0);
        assertFalse(h.engine.captureInFlight()); assertEquals(354, h.timer.nextAt());
        assertEquals(1, h.fastExecutor.size()); assertFalse(pixel.closed);
        h.engine.close(); assertEquals(1, pixel.closes); h.ui.runAll();
    }

    @Test public void foregroundResolutionDoesNotOwnTheDispatchClock() {
        Harness h = new Harness();
        h.behavior = (request, callback) -> {
            h.now = 90; assertTrue(request.tryDispatch()); callback.failure(0); h.now = 120;
        };
        h.start(); h.fire(0);
        assertEquals(454, h.timer.nextAt());
    }

    @Test public void stopRestartWaitsForOldCallbackAndRejectsItsPixels() {
        Harness h = new Harness(); h.start(); h.fire(0);
        CaptureCall old = h.captures.get(0);
        h.engine.stop(); h.now = 50; h.engine.start(42);
        assertTrue(h.engine.captureInFlight()); assertEquals(Long.MAX_VALUE, h.timer.nextAt());
        Pixel oldPixel = h.deliver(0, 1); assertEquals(1, oldPixel.closes);
        assertEquals(334, h.timer.nextAt()); h.fire(334);
        Pixel duplicate = new Pixel(2); old.callback.success(duplicate.payload());
        assertEquals(1, duplicate.closes); assertTrue(h.engine.captureInFlight());
        Pixel current = h.deliver(1, 3); h.fastExecutor.runOne();
        assertEquals(h.engine.currentEpoch(), h.fast.calls.get(0).input.source.epoch);
        h.engine.close(); assertFalse(current.closed); h.fast.calls.get(0).completion.cancelled();
        assertEquals(1, current.closes);
    }

    @Test public void stoppedOwnerMayNotDispatchAfterForegroundResolution() {
        Harness h = new Harness();
        h.behavior = (request, callback) -> {
            h.engine.stop(); h.engine.start(42); assertFalse(request.tryDispatch());
        };
        h.start(); h.fire(0); assertFalse(h.engine.captureInFlight());
        assertEquals(0, h.traces.stream().filter(t -> t.stage == CensorEngine.Stage.CAPTURE_DISPATCHED).count());
    }

    @Test public void oldOrCancelledTimersCannotRequestNewCapture() {
        Harness h = new Harness(); h.start(); TimedTask old = h.timer.tasks.get(0);
        h.engine.stop(); h.engine.start(42); old.action.run();
        assertEquals(0, h.captures.size()); h.fire(0); assertEquals(1, h.captures.size());
        h.engine.close(); h.captures.get(0).callback.failure(0);
    }

    @Test public void failureBackoffSurvivesScrollWakeAndStopRestart() {
        Harness h = new Harness();
        h.behavior = (request, callback) -> callback.failure(600);
        h.start(); h.fire(0); h.engine.requestCaptureAt(0);
        assertEquals(600, h.timer.nextAt()); h.engine.stop(); h.engine.start(42);
        assertEquals(600, h.timer.nextAt());
    }

    @Test public void rejectedTimerHasNoPhantomDeadlineAndCanBeRetried() {
        Harness h = new Harness(); h.timer.reject = true; h.start();
        assertEquals(Long.MAX_VALUE, h.timer.nextAt()); assertEquals(1, h.engine.callbackFailures());
        h.timer.reject = false; h.engine.requestCaptureAt(0); h.fire(0);
        assertEquals(1, h.captures.size()); h.engine.close(); h.captures.get(0).callback.failure(0);
    }

    @Test public void duplicateTransferredPayloadCannotDisposeAnExecutingSource() {
        Harness h = new Harness(); h.start(); h.fire(0);
        Pixel pixel = new Pixel(1); CensorEngine.Captured<Pixel> payload = pixel.payload();
        h.captures.get(0).callback.success(payload); h.fastExecutor.runOne();
        h.captures.get(0).callback.success(payload); payload.close();
        assertFalse(pixel.closed);
        Pixel freshDuplicate = new Pixel(2); h.captures.get(0).callback.success(freshDuplicate.payload());
        assertEquals(1, freshDuplicate.closes); assertFalse(pixel.closed);
        h.engine.close(); h.fast.calls.get(0).completion.cancelled();
        assertEquals(1, pixel.closes);
    }

    @Test public void newerCapturesReplaceOnlyPendingFastNotExecutingFast() {
        Harness h = new Harness(); h.start(); h.fire(0); Pixel first = h.deliver(0, 1); h.fastExecutor.runOne();
        h.fire(334); Pixel second = h.deliver(1, 2);
        h.fire(668); Pixel third = h.deliver(2, 3);
        assertFalse(first.closed); assertEquals(1, second.closes); assertFalse(third.closed);
        assertEquals(0, h.fast.cancellations); assertEquals(1, h.engine.pendingCount(FAST));
        h.fast.succeed(0, face(500)); h.ui.runAll();
        try (CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView()) {
            assertEquals(1, view.snapshot.destination.sequence); assertSame(first, view.sourcePixels(1));
        }
        h.fastExecutor.runOne(); assertEquals(3, h.fast.calls.get(1).input.source.sequence);
        h.engine.close(); assertFalse(third.closed); h.fast.calls.get(1).completion.cancelled();
        assertEquals(1, first.closes); assertEquals(1, third.closes);
    }

    @Test public void actualAsyncCompletionAfterStopOwnsFinalCleanup() {
        Harness h = new Harness(); h.start(); h.fire(0); Pixel pixel = h.deliver(0, 1); h.fastExecutor.runOne();
        WorkCall work = h.fast.calls.get(0); h.engine.close();
        assertTrue(work.input.cancelled()); assertFalse(pixel.closed); assertEquals(0, h.drained);
        work.completion.success(batch(work.input, FAST, face(500)), TIMING);
        assertEquals(1, pixel.closes); assertEquals(1, h.drained); assertNull(h.engine.acquireRenderView());
        work.completion.failure(); work.completion.cancelled(); assertEquals(1, pixel.closes);
    }

    @Test public void executorQueuedJobCanBeRetiredWithoutStartingOrReadingFreedPixels() {
        Harness h = new Harness(); h.start(); h.fire(0); Pixel pixel = h.deliver(0, 1);
        assertEquals(1, h.fastExecutor.size()); h.engine.close(); assertEquals(1, pixel.closes);
        h.fastExecutor.runAll(); assertEquals(0, h.fast.calls.size()); assertEquals(1, h.drained);
    }

    @Test public void rejectedWorkerExecutorReleasesOnlyItsOwnedInput() {
        Harness h = new Harness(); h.fastExecutor.reject = true; h.start(); h.fire(0);
        Pixel pixel = h.deliver(0, 1); assertEquals(1, pixel.closes);
        assertEquals(0, h.engine.activeCount(FAST)); assertEquals(0, h.engine.pendingCount(FAST));
        assertTrue(h.traces.stream().anyMatch(t -> t.stage == CensorEngine.Stage.JOB_REJECTED));
    }

    @Test public void failureCancellationAndStartExceptionAreTerminalExactlyOnce() {
        for (int terminal = 0; terminal < 3; terminal++) {
            Harness h = new Harness(); h.start(); h.fire(0); Pixel pixel = h.deliver(0, 1);
            if (terminal == 2) h.fast.throwBeforeLaunch = true;
            h.fastExecutor.runOne();
            if (terminal != 2) {
                WorkCall work = h.fast.calls.get(0);
                if (terminal == 0) work.completion.failure(); else work.completion.cancelled();
                work.completion.failure();
            }
            assertEquals(1, pixel.closes); assertEquals(0, h.engine.activeCount(FAST));
        }
    }

    @Test public void resultMustUseItsExactInputSourceAndLane() {
        Harness h = new Harness(); h.start(); h.fire(0); Pixel pixel = h.deliver(0, 1); h.fastExecutor.runOne();
        WorkCall work = h.fast.calls.get(0); SceneEngine.Source s = work.input.source;
        SceneEngine.Source forged = new SceneEngine.Source(s.epoch, s.sequence, s.geometry, s.width, s.height,
                s.receivedAtMillis, s.pixelAtMillis);
        work.completion.success(new SceneEngine.Batch(forged, FAST, Collections.singletonList(face(500)),
                Collections.emptyList()), TIMING);
        assertEquals(1, pixel.closes); assertNull(h.engine.acquireRenderView());
        assertTrue(h.traces.stream().anyMatch(t -> t.stage == CensorEngine.Stage.RESULT_REJECTED));
        h.fire(334); Pixel next = h.deliver(1, 2); h.fastExecutor.runOne();
        WorkCall second = h.fast.calls.get(1); second.completion.success(batch(second.input, QUALITY, face(500)), TIMING);
        assertEquals(1, next.closes); assertNull(h.engine.acquireRenderView());
    }

    @Test public void blockedUiCoalescesBeforePostingAndReleasesReplacedEffects() {
        Harness h = new Harness(); h.start(); List<Pixel> pixels = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            h.fire(i * 334L); pixels.add(h.deliver(i, i + 1)); h.fastExecutor.runOne(); h.fast.succeed(i, face(500));
        }
        assertEquals(1, h.ui.size()); assertEquals(0, h.presentations);
        for (int i = 0; i < 9; i++) assertEquals(1, pixels.get(i).closes);
        assertEquals(0, pixels.get(9).closes);
        h.ui.runOne(); assertEquals(1, h.presentations);
        try (CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView()) {
            assertEquals(10, view.snapshot.destination.sequence); assertSame(pixels.get(9), view.sourcePixels(10));
        }
        h.engine.close(); assertEquals(1, pixels.get(9).closes);
    }

    @Test public void rejectedUiPublicationDoesNotLeakEffectsOrReplaceCurrentScene() {
        Harness h = new Harness(); h.ui.reject = true; h.start(); h.fire(0);
        Pixel pixel = h.deliver(0, 1); h.fastExecutor.runOne(); h.fast.succeed(0, face(500));
        assertEquals(1, pixel.closes); assertNull(h.engine.acquireRenderView());
        assertTrue(h.traces.stream().anyMatch(t -> t.stage == CensorEngine.Stage.PRESENTATION_REJECTED));
    }

    @Test public void mixedOriginEffectSourcesAndDrawLeaseSurviveOwnerClose() {
        Harness h = new Harness(); h.start(); h.fire(0); Pixel first = h.deliver(0, 1);
        h.fastExecutor.runOne(); h.fast.succeed(0, face(500), face(900)); h.ui.runAll();
        h.fire(334); Pixel second = h.deliver(1, 2); h.fastExecutor.runOne();
        assertTrue(h.engine.motion(motion(h.engine.currentEpoch(), 1, 2, -400)));
        h.fast.succeed(1, face(100)); h.ui.runAll();
        CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView();
        assertSame(first, view.sourcePixels(1)); assertSame(second, view.sourcePixels(2));
        List<SceneEngine.Mask> masks = masks(view.snapshot); assertEquals(2, masks.size());
        SceneEngine.Mask retained = masks.stream().filter(m -> m.evidenceSource.sequence == 1).findFirst().get();
        assertEquals(900, retained.evidenceRegion.top, 0); assertEquals(500, retained.displayRegion.top, 0);
        h.engine.close(); assertFalse(first.closed); assertFalse(second.closed); assertEquals(0, h.drained);
        view.close(); view.close(); assertEquals(1, first.closes); assertEquals(1, second.closes); assertEquals(1, h.drained);
        assertThrows(IllegalStateException.class, () -> view.sourcePixels(1));
    }

    @Test public void effectLimitMeansExplicitMissingSourceNotSamplingTheNewestPacket() {
        Harness h = new Harness(false, false, false, 8, 1, 16); h.start(); h.fire(0); Pixel first = h.deliver(0, 1);
        h.fastExecutor.runOne(); h.fast.succeed(0, face(500), face(900)); h.ui.runAll();
        h.fire(334); Pixel second = h.deliver(1, 2); h.fastExecutor.runOne();
        h.engine.motion(motion(h.engine.currentEpoch(), 1, 2, -400)); h.fast.succeed(1, face(100)); h.ui.runAll();
        try (CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView()) {
            assertNull(view.sourcePixels(1)); assertSame(second, view.sourcePixels(2));
            assertEquals(2, masks(view.snapshot).size());
        }
        assertEquals(1, first.closes); h.engine.close(); assertEquals(1, second.closes);
    }

    @Test public void pixelQuotaBackpressuresAcquisitionUntilActualLeaseRelease() {
        Harness h = new Harness(false, false, false, 2, 2, 16); h.start(); h.fire(0); Pixel first = h.deliver(0, 1);
        h.fastExecutor.runOne(); h.fast.succeed(0, face(500)); h.ui.runAll();
        h.fire(334); Pixel second = h.deliver(1, 2); h.fastExecutor.runOne();
        h.now = 668; h.engine.requestCaptureAt(h.now);
        assertEquals(2, h.captures.size()); assertEquals(Long.MAX_VALUE, h.timer.nextAt());
        assertEquals(2, h.engine.livePixelFrames());
        h.fast.succeed(1, face(500)); assertFalse(first.closed); h.ui.runAll();
        assertEquals(1, first.closes); assertEquals(668, h.timer.nextAt()); h.fire(668);
        assertEquals(3, h.captures.size()); assertFalse(second.closed);
        h.engine.close(); h.captures.get(2).callback.failure(0); assertEquals(1, second.closes);
    }

    @Test public void sceneSourceQuotaRejectsAndReleasesNewPayloadWithoutDroppingExecutingJob() {
        Harness h = new Harness(false, false, false, 8, 0, 2); h.start(); h.fire(0); Pixel first = h.deliver(0, 1);
        h.fastExecutor.runOne(); h.fire(334); Pixel second = h.deliver(1, 2);
        h.fire(668); Pixel rejected = h.deliver(2, 3);
        assertEquals(1, rejected.closes); assertFalse(first.closed); assertFalse(second.closed);
        assertEquals(1, h.engine.activeCount(FAST)); assertEquals(1, h.engine.pendingCount(FAST));
        assertTrue(h.traces.stream().anyMatch(t -> t.stage == CensorEngine.Stage.SOURCE_REJECTED));
        h.engine.close(); h.fast.calls.get(0).completion.cancelled();
        assertEquals(1, first.closes); assertEquals(1, second.closes);
    }

    @Test public void serializedQualityYieldsToFastWithoutPretendingNativeCancellationFinished() {
        Harness h = new Harness(true, false, false, 8, 2, 16); h.start(); h.fire(0); Pixel first = h.deliver(0, 1);
        h.fastExecutor.runOne(); assertEquals(0, h.qualityExecutor.size());
        h.fast.succeed(0, face(500)); h.ui.runAll(); h.qualityExecutor.runOne();
        WorkCall quality = h.quality.calls.get(0);
        h.fire(334); Pixel second = h.deliver(1, 2);
        assertTrue(quality.input.cancelled()); assertEquals(1, h.quality.cancellations);
        assertEquals(0, h.engine.activeCount(FAST)); assertEquals(1, h.engine.pendingCount(FAST));
        assertFalse(first.closed); assertEquals(1, h.engine.activeCount(QUALITY));
        h.quality.succeed(0, face(500)); h.fastExecutor.runOne();
        assertEquals(2, h.fast.calls.get(1).input.source.sequence);
        h.engine.close(); assertFalse(second.closed); h.fast.calls.get(1).completion.cancelled();
        assertEquals(1, first.closes); assertEquals(1, second.closes);
    }

    @Test public void qualifiedParallelPolicyKeepsRunningQualityWhileFastStarts() {
        Harness h = new Harness(true, false, true, 8, 2, 16); h.start(); h.fire(0); h.deliver(0, 1);
        h.fastExecutor.runOne(); h.fast.succeed(0, face(500)); h.qualityExecutor.runOne();
        h.fire(334); h.deliver(1, 2); h.fastExecutor.runOne();
        assertEquals(1, h.engine.activeCount(FAST)); assertEquals(1, h.engine.activeCount(QUALITY));
        assertEquals(0, h.quality.cancellations); assertFalse(h.quality.calls.get(0).input.cancelled());
        h.engine.close(); h.fast.calls.get(1).completion.cancelled(); h.quality.calls.get(0).completion.cancelled();
    }

    @Test public void oldQualityDoesNotChangeNewerPresentationDestinationUnderSourcePressure() {
        Harness h = new Harness(true, false, true, 8, 0, 2); h.start(); h.fire(0); h.deliver(0, 1);
        h.fastExecutor.runOne(); h.fast.succeed(0, face(500)); h.qualityExecutor.runOne();
        h.fire(334); h.deliver(1, 2); h.fastExecutor.runOne();
        h.engine.motion(motion(h.engine.currentEpoch(), 1, 2, -400)); h.fast.succeed(1, face(100)); h.ui.runAll();
        h.fire(668); Pixel rejected = h.deliver(2, 3); assertEquals(1, rejected.closes);
        h.quality.succeed(0, face(500)); h.ui.runAll();
        try (CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView()) {
            assertEquals(2, view.snapshot.destination.sequence);
            assertEquals(100, masks(view.snapshot).get(0).displayRegion.top, 0);
        }
        h.engine.close();
    }

    @Test public void textRunsIndependentlyAndSceneGroupingStaysInsideTheOwner() {
        Harness h = new Harness(false, true, false, 8, 2, 16); h.start(); h.fire(0); h.deliver(0, 1);
        h.textExecutor.runOne(); h.text.succeed(0, text(500), text(650)); h.ui.runAll();
        assertEquals(0, h.fast.calls.size());
        try (CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView()) {
            assertEquals(1, view.snapshot.groups.size()); assertEquals(2, view.snapshot.groups.get(0).members.size());
        }
        h.fastExecutor.runOne(); h.fast.succeed(0, face(100)); h.ui.runAll();
        try (CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView()) { assertEquals(2, view.snapshot.groups.size()); }
        h.engine.close();
    }

    @Test public void workQueuePixelPublicationAndDrawClocksStaySeparate() {
        Harness h = new Harness(); h.start(); h.fire(0);
        Pixel pixel = new Pixel(1); h.captures.get(0).callback.success(pixel.payload(0));
        h.now = 120; h.fastExecutor.runOne(); h.now = 150; h.fast.succeed(0, face(500));
        h.now = 170; h.ui.runAll();
        CensorEngine.Trace started = h.traces.stream().filter(t -> t.stage == CensorEngine.Stage.JOB_STARTED).findFirst().get();
        CensorEngine.Trace completed = h.traces.stream().filter(t -> t.stage == CensorEngine.Stage.JOB_COMPLETED).findFirst().get();
        CensorEngine.Trace presented = h.traces.stream().filter(t -> t.stage == CensorEngine.Stage.PRESENTED).findFirst().get();
        assertEquals(120, started.queueMillis); assertEquals(150, completed.atMillis);
        assertSame(TIMING, completed.work); assertEquals(10, completed.work.runtimeMillis);
        assertEquals(170, presented.atMillis); assertEquals(170, presented.pixelAgeMillis);
        assertFalse(h.traces.stream().anyMatch(t -> t.stage == CensorEngine.Stage.DRAWN));
        try (CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView()) {
            h.now = 190; h.engine.drawn(view.snapshot.revision);
        }
        CensorEngine.Trace drawn = h.traces.stream().filter(t -> t.stage == CensorEngine.Stage.DRAWN).findFirst().get();
        assertEquals(190, drawn.atMillis); h.engine.close();
    }

    @Test public void unknownPixelTimeAndEmptyPresentationCannotManufactureFirstCensorProof() {
        Harness h = new Harness(); h.start(); h.fire(0); h.deliver(0, 1); h.fastExecutor.runOne(); h.fast.succeed(0);
        h.ui.runAll(); try (CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView()) { h.engine.drawn(view.snapshot.revision); }
        assertFalse(h.traces.stream().anyMatch(t -> t.stage == CensorEngine.Stage.DRAWN));
        assertTrue(h.traces.stream().allMatch(t -> t.pixelAgeMillis == -1)); h.engine.close();
    }

    @Test public void observerExceptionDoesNotStrandCleanupSchedulingOrOwnership() {
        Harness h = new Harness(); h.throwTrace = true; h.start(); h.fire(0); Pixel pixel = h.deliver(0, 1);
        h.fastExecutor.runOne(); h.fast.calls.get(0).completion.failure();
        assertEquals(1, pixel.closes); assertEquals(334, h.timer.nextAt());
        assertTrue(h.engine.callbackFailures() > 0); h.engine.close();
    }

    @Test public void everyEffectLeaseIsReleasedEvenIfOneDisposerFails() {
        Harness h = new Harness(); h.start(); h.fire(0); Pixel first = h.deliver(0, 1);
        h.fastExecutor.runOne(); h.fast.succeed(0, face(500), face(900)); h.ui.runAll();
        h.fire(334); Pixel second = h.deliver(1, 2); h.fastExecutor.runOne();
        h.engine.motion(motion(h.engine.currentEpoch(), 1, 2, -400)); h.fast.succeed(1, face(100)); h.ui.runAll();
        CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView(); second.throwDispose = true;
        h.engine.close(); assertThrows(IllegalStateException.class, view::close);
        assertEquals(1, first.closes); assertEquals(1, second.closes); assertEquals(0, h.engine.livePixelFrames());
        assertEquals(1, h.engine.resourceFailures()); assertEquals(0, h.drained);
        assertTrue(h.traces.stream().anyMatch(t -> t.stage == CensorEngine.Stage.RESOURCE_FAILED));
    }

    @Test public void permanentCloseAndScopeFenceDoNotReuseTheOldReadyScene() {
        Harness h = new Harness(); h.start(); h.fire(0); h.deliver(0, 1); h.fastExecutor.runOne();
        WorkCall old = h.fast.calls.get(0); h.engine.changeScope(43);
        assertTrue(old.input.cancelled()); old.completion.success(batch(old.input, FAST, face(500)), TIMING);
        h.ui.runAll(); assertNull(h.engine.acquireRenderView()); h.engine.close();
        assertThrows(IllegalStateException.class, () -> h.engine.start(42));
    }

    @Test public void deferredOldCancellationCannotTargetNewQualityOperation() {
        Harness h = new Harness(true, false, false, 8, 2, 16); h.start(); h.fire(0); h.deliver(0, 1);
        h.fastExecutor.runOne(); h.fast.succeed(0, face(500)); h.qualityExecutor.runOne();
        h.traceHook = trace -> {
            if (trace.stage != CensorEngine.Stage.JOB_QUEUED || trace.source != 2 || trace.lane != FAST) return;
            h.traceHook = null;
            h.quality.succeed(0, face(500));
            h.fastExecutor.runOne(); h.fast.succeed(1, face(500)); h.qualityExecutor.runOne();
            assertFalse(h.quality.calls.get(1).input.cancelled());
        };
        h.fire(334); h.deliver(1, 2);
        assertEquals(2, h.quality.calls.size());
        assertEquals(0, h.quality.calls.get(1).cancellationSignals);
        assertFalse(h.quality.calls.get(1).input.cancelled());
        h.engine.close(); h.quality.calls.get(1).completion.cancelled();
    }

    @Test public void rejectedCaptureDisposalFailureCannotReportDrained() {
        Harness h = new Harness(); h.start(); h.fire(0); h.engine.close();
        Pixel pixel = new Pixel(1); pixel.throwBeforeDispose = true;
        h.captures.get(0).callback.success(pixel.payload());
        assertFalse(pixel.closed); assertEquals(1, pixel.disposalAttempts);
        assertEquals(1, h.engine.resourceFailures()); assertEquals(0, h.drained);
        assertTrue(h.traces.stream().anyMatch(t -> t.stage == CensorEngine.Stage.RESOURCE_FAILED
                && t.source == 1 && t.epoch == h.captures.get(0).request.epoch));
    }

    @Test public void freshDuplicateDisposalFailureStopsNewAcquisitionWithoutFreeingActiveInput() {
        Harness h = new Harness(); h.start(); h.fire(0); Pixel active = h.deliver(0, 1); h.fastExecutor.runOne();
        Pixel duplicate = new Pixel(2); duplicate.throwBeforeDispose = true;
        h.captures.get(0).callback.success(duplicate.payload());
        assertEquals(1, h.engine.resourceFailures()); assertFalse(active.closed);
        assertEquals(Long.MAX_VALUE, h.timer.nextAt());
        h.engine.requestCaptureAt(0); assertEquals(Long.MAX_VALUE, h.timer.nextAt());
        h.engine.close(); h.fast.calls.get(0).completion.cancelled(); assertEquals(1, active.closes);
        assertEquals(0, h.drained);
    }

    @Test public void stoppedStaleCaptureRemainsOwnedWhileItsDisposerBlocks() throws Exception {
        Harness h = new Harness(); h.start(); h.fire(0); h.engine.stop();
        CountDownLatch disposing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger disposals = new AtomicInteger();
        AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
        CensorEngine.Captured<Pixel> stale = new CensorEngine.Captured<>(new Pixel(1), 1000, 2400, -1, () -> {
            disposals.incrementAndGet(); disposing.countDown(); awaitRelease(release);
        });
        Thread callback = new Thread(() -> {
            try { h.captures.get(0).callback.success(stale); }
            catch (Throwable failure) { callbackFailure.set(failure); }
        }, "stale-capture-disposer");
        callback.start();
        try {
            assertTrue("disposer did not start", disposing.await(5, TimeUnit.SECONDS));
            assertFalse(h.engine.captureInFlight()); assertEquals(1, h.engine.livePixelFrames());
            h.captures.get(0).callback.success(stale); // Same claimed payload is not another owner.
            assertEquals(1, h.engine.livePixelFrames()); assertEquals(1, disposals.get());
            h.engine.close(); assertEquals(0, h.drained);
        } finally { release.countDown(); callback.join(5000); }
        assertFalse("callback did not finish", callback.isAlive()); assertNull(callbackFailure.get());
        assertEquals(0, h.engine.livePixelFrames()); assertEquals(1, disposals.get()); assertEquals(1, h.drained);
        h.captures.get(0).callback.success(stale); assertEquals(1, disposals.get()); assertEquals(1, h.drained);
    }

    @Test public void alreadyScheduledCaptureCannotPassBlockedDuplicateDisposal() throws Exception {
        Harness h = new Harness(); h.start(); h.fire(0); h.deliver(0, 1);
        CountDownLatch disposing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
        CensorEngine.Captured<Pixel> duplicate = new CensorEngine.Captured<>(new Pixel(2), 1000, 2400, -1, () -> {
            disposing.countDown(); awaitRelease(release);
        });
        Thread callback = new Thread(() -> {
            try { h.captures.get(0).callback.success(duplicate); }
            catch (Throwable failure) { callbackFailure.set(failure); }
        }, "duplicate-capture-disposer");
        callback.start();
        try {
            assertTrue("disposer did not start", disposing.await(5, TimeUnit.SECONDS));
            assertEquals(2, h.engine.livePixelFrames()); h.fire(334);
            assertEquals(1, h.captures.size()); assertEquals(Long.MAX_VALUE, h.timer.nextAt());
        } finally { release.countDown(); callback.join(5000); }
        assertFalse("callback did not finish", callback.isAlive()); assertNull(callbackFailure.get());
        assertEquals(1, h.engine.livePixelFrames()); assertEquals(334, h.timer.nextAt());
        h.fire(334); assertEquals(2, h.captures.size());
        h.engine.close(); h.captures.get(1).callback.failure(0); assertEquals(1, h.drained);
    }

    @Test public void solidFallbackDrawHandleAlsoDelaysPermanentDrain() {
        Harness h = new Harness(false, false, false, 8, 0, 16); h.start(); h.fire(0);
        Pixel source = h.deliver(0, 1); h.fastExecutor.runOne(); h.fast.succeed(0, face(500)); h.ui.runAll();
        CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView();
        assertNotNull(view); assertNull(view.sourcePixels(1)); assertEquals(1, source.closes);
        h.engine.close(); assertEquals(0, h.engine.livePixelFrames()); assertEquals(0, h.drained);
        assertEquals(1, masks(view.snapshot).size()); view.close(); assertEquals(1, h.drained);
        view.close(); assertEquals(1, h.drained);
    }

    private static void awaitRelease(CountDownLatch release) {
        try { assertTrue("disposal release timed out", release.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted);
        }
    }

    private static SceneEngine.Observation face(double y) {
        return new SceneEngine.Observation("FACE", 1, 0, new SceneEngine.Region(100, y, 200, y + 100), .9f);
    }

    private static SceneEngine.Observation text(double y) {
        return new SceneEngine.Observation("TEXT", 1, 77, new SceneEngine.Region(100, y, 500, y + 100), .9f);
    }

    private static SceneEngine.Motion motion(long epoch, long from, long to, double dy) {
        return new SceneEngine.Motion(epoch, from, to, 1, 1, SceneEngine.MotionAuthority.IMAGE_CORRESPONDENCE,
                SUPPORT, 0, dy);
    }

    private static SceneEngine.Batch batch(CensorEngine.Input<Pixel> input, SceneEngine.Lane lane,
            SceneEngine.Observation... observations) {
        return new SceneEngine.Batch(input.source, lane, Arrays.asList(observations), Collections.emptyList());
    }

    private static List<SceneEngine.Mask> masks(SceneEngine.Snapshot snapshot) {
        List<SceneEngine.Mask> masks = new ArrayList<>();
        for (SceneEngine.Group group : snapshot.groups) masks.addAll(group.members);
        return masks;
    }

    private static final class Pixel {
        final int id;
        boolean closed;
        int closes;
        int disposalAttempts;
        boolean throwDispose;
        boolean throwBeforeDispose;
        Pixel(int id) { this.id = id; }
        CensorEngine.Captured<Pixel> payload() { return payload(-1); }
        CensorEngine.Captured<Pixel> payload(long pixelAtMillis) {
            return new CensorEngine.Captured<>(this, 1000, 2400, pixelAtMillis, () -> {
                disposalAttempts++;
                if (throwBeforeDispose) throw new IllegalStateException("before actual pixel disposal");
                assertFalse("pixels released twice", closed); closed = true; closes++;
                if (throwDispose) throw new IllegalStateException("test disposer failure");
            });
        }
    }

    private static final class ManualExecutor implements Executor {
        final List<Runnable> tasks = new ArrayList<>();
        boolean reject;
        @Override public void execute(Runnable action) {
            if (reject) throw new RejectedExecutionException(); tasks.add(action);
        }
        int size() { return tasks.size(); }
        void runOne() { assertFalse("no queued work", tasks.isEmpty()); tasks.remove(0).run(); }
        void runAll() { while (!tasks.isEmpty()) runOne(); }
    }

    private static final class WorkCall {
        final CensorEngine.Input<Pixel> input;
        final CensorEngine.Completion completion;
        boolean terminal;
        int cancellationSignals;
        WorkCall(CensorEngine.Input<Pixel> input, CensorEngine.Completion completion) {
            this.input = input;
            this.completion = new CensorEngine.Completion() {
                @Override public void success(SceneEngine.Batch batch, CensorEngine.WorkTiming timing) {
                    terminal = true; completion.success(batch, timing);
                }
                @Override public void failure() { terminal = true; completion.failure(); }
                @Override public void cancelled() { terminal = true; completion.cancelled(); }
            };
        }
    }

    private static final class FakeWorker implements CensorEngine.Worker<Pixel> {
        final SceneEngine.Lane lane;
        final List<WorkCall> calls = new ArrayList<>();
        int cancellations;
        boolean throwBeforeLaunch;
        FakeWorker(SceneEngine.Lane lane) { this.lane = lane; }
        @Override public void start(CensorEngine.Input<Pixel> input, CensorEngine.Completion completion) {
            if (throwBeforeLaunch) throw new IllegalStateException("before native launch");
            assertFalse("worker read freed pixels", input.pixels.closed);
            WorkCall call = new WorkCall(input, completion); calls.add(call);
            input.onCancellation(() -> { if (!call.terminal) { cancellations++; call.cancellationSignals++; } });
        }
        void succeed(int index, SceneEngine.Observation... observations) {
            WorkCall work = calls.get(index); assertFalse(work.input.pixels.closed);
            work.completion.success(batch(work.input, lane, observations), TIMING);
        }
    }

    private static final class CaptureCall {
        final CensorEngine.CaptureRequest request;
        final CensorEngine.CaptureCallback<Pixel> callback;
        CaptureCall(CensorEngine.CaptureRequest request, CensorEngine.CaptureCallback<Pixel> callback) {
            this.request = request; this.callback = callback;
        }
    }

    private static final class TimedTask {
        final long at;
        final Runnable action;
        boolean cancelled;
        boolean ran;
        TimedTask(long at, Runnable action) { this.at = at; this.action = action; }
    }

    private static final class Harness {
        long now;
        int presentations;
        volatile int drained;
        boolean throwTrace;
        final ManualExecutor fastExecutor = new ManualExecutor();
        final ManualExecutor qualityExecutor = new ManualExecutor();
        final ManualExecutor textExecutor = new ManualExecutor();
        final ManualExecutor ui = new ManualExecutor();
        final FakeWorker fast = new FakeWorker(FAST);
        final FakeWorker quality = new FakeWorker(QUALITY);
        final FakeWorker text = new FakeWorker(TEXT);
        final List<CaptureCall> captures = new ArrayList<>();
        final List<CensorEngine.Trace> traces = Collections.synchronizedList(new ArrayList<>());
        BiConsumer<CensorEngine.CaptureRequest, CensorEngine.CaptureCallback<Pixel>> behavior;
        java.util.function.Consumer<CensorEngine.Trace> traceHook;
        final FakeTimer timer = new FakeTimer();
        final CensorEngine<Pixel> engine;

        Harness() { this(false, false, false, 8, 2, 16); }

        Harness(boolean qualityEnabled, boolean textEnabled, boolean parallelQuality,
                int maximumFrames, int maximumEffects, int maximumSources) {
            List<CensorEngine.LaneWorker<Pixel>> workers = new ArrayList<>();
            workers.add(new CensorEngine.LaneWorker<>(FAST, fastExecutor, fast));
            if (qualityEnabled) workers.add(new CensorEngine.LaneWorker<>(QUALITY, qualityExecutor, quality));
            if (textEnabled) workers.add(new CensorEngine.LaneWorker<>(TEXT, textExecutor, text));
            engine = new CensorEngine<>(new CensorEngine.Policy(334, 0, maximumFrames, maximumEffects, parallelQuality),
                    new SceneEngine.Policy(maximumSources, 100, 64, 256, 5000, 20000, .5),
                    () -> now, timer, (request, callback) -> {
                        captures.add(new CaptureCall(request, callback));
                        if (behavior != null) behavior.accept(request, callback);
                        else assertTrue(request.tryDispatch());
                    }, workers, ui, new CensorEngine.Observer() {
                        @Override public void trace(CensorEngine.Trace trace) {
                            if (throwTrace) throw new IllegalStateException("test observer failure");
                            traces.add(trace);
                            if (traceHook != null) traceHook.accept(trace);
                        }
                        @Override public void presentationChanged() { presentations++; }
                        @Override public void drained() { drained++; }
                    });
        }

        void start() { engine.start(42); }
        void fire(long at) { timer.fire(at); }
        Pixel deliver(int captureIndex, int pixelId) {
            Pixel pixel = new Pixel(pixelId); captures.get(captureIndex).callback.success(pixel.payload()); return pixel;
        }

        final class FakeTimer implements CensorEngine.Timer {
            final List<TimedTask> tasks = new ArrayList<>();
            boolean reject;
            @Override public CensorEngine.Cancellation schedule(Runnable action, long delayMillis) {
                if (reject) throw new RejectedExecutionException();
                TimedTask task = new TimedTask(now + delayMillis, action); tasks.add(task);
                return () -> task.cancelled = true;
            }
            long nextAt() {
                return tasks.stream().filter(t -> !t.cancelled && !t.ran).mapToLong(t -> t.at).min().orElse(Long.MAX_VALUE);
            }
            void fire(long at) {
                assertTrue(at >= now); assertTrue("timer not due", nextAt() <= at); now = at;
                TimedTask task = tasks.stream().filter(t -> !t.cancelled && !t.ran && t.at <= at)
                        .findFirst().orElseThrow(AssertionError::new);
                task.ran = true; task.action.run();
            }
        }
    }
}
