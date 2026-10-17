package com.subhub.app.censor.engine;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.junit.Test;

public class ProjectionCaptureAdapterTest {
    private static final class Raw {
        final int id;
        int closes;
        Raw(int id) { this.id = id; }
        void close() { assertEquals(0, closes++); }
    }
    private static final class Pixel {
        final Raw origin;
        int closes;
        boolean failCleanup;
        Pixel(Raw origin) { this.origin = origin; }
        void close() { assertEquals(0, closes++); if (failCleanup) throw new IllegalStateException("pixel cleanup"); }
    }
    private static final class Work {
        final CensorEngine.Input<Pixel> input;
        final CensorEngine.Completion completion;
        Work(CensorEngine.Input<Pixel> input, CensorEngine.Completion completion) {
            this.input = input; this.completion = completion;
        }
    }
    private static final class Task {
        final long at;
        final Runnable action;
        boolean cancelled, ran;
        Task(long at, Runnable action) { this.at = at; this.action = action; }
    }
    private static final class Harness implements AutoCloseable {
        long now;
        int drained, copies;
        final ProjectionFrameMailbox<Raw> mailbox = new ProjectionFrameMailbox<>();
        final PixelGeometry geometry = PixelGeometry.display(64, 128, 64, 128);
        final List<Task> tasks = new ArrayList<>();
        final List<Work> work = new ArrayList<>();
        final List<Pixel> copied = new ArrayList<>();
        final List<CensorEngine.Trace> traces = new ArrayList<>();
        ProjectionCaptureAdapter.Target<Raw> target;
        Runnable resolveHook;
        ProjectionCaptureAdapter.Copier<Raw, Pixel> copy = this::prepare;
        final CensorEngine<Pixel> engine;
        Harness() { this(42); }
        Harness(long selectedGeometry) {
            ProjectionCaptureAdapter<Raw, Pixel> adapter = new ProjectionCaptureAdapter<>(request -> {
                ProjectionCaptureAdapter.Target<Raw> selected = target;
                if (resolveHook != null) resolveHook.run();
                return selected != null && (resolveHook != null || engineCurrent(selected)) ? selected : null;
            }, (selected, raw) -> { copies++; return copy.copy(selected, raw); });
            engine = new CensorEngine<>(new CensorEngine.Policy(16, 5, 8, 0, false),
                    new SceneEngine.Policy(16, 100, 64, 256, 5000, 20000, .5), () -> now,
                    (action, delay) -> { Task task = new Task(now + delay, action); tasks.add(task); return () -> task.cancelled = true; },
                    adapter, Collections.singletonList(new CensorEngine.LaneWorker<>(SceneEngine.Lane.FAST,
                            Runnable::run, (input, completion) -> {
                                assertEquals("worker ran before raw cleanup", 1, input.pixels.origin.closes);
                                assertEquals("worker received disposed copy", 0, input.pixels.closes);
                                work.add(new Work(input, completion));
                            })), Runnable::run, new CensorEngine.Observer() {
                                @Override public void trace(CensorEngine.Trace trace) { traces.add(trace); }
                                @Override public void drained() { drained++; }
                            });
            engine.start(selectedGeometry, ProtectionScope.lab(7));
            target = new ProjectionCaptureAdapter.Target<>(engine.activeScope(), geometry, mailbox);
        }
        private boolean engineCurrent(ProjectionCaptureAdapter.Target<Raw> selected) {
            return engine != null && engine.isCurrent(selected.scope);
        }
        CensorEngine.Captured<Pixel> prepare(ProjectionCaptureAdapter.Target<Raw> selected, ProjectionFrameMailbox<Raw>.Read read) {
            return prepare(read.raw(), 64, 128, -1);
        }
        CensorEngine.Captured<Pixel> prepare(Raw raw, int width, int height, long pixelAt) {
            assertEquals(0, raw.closes); Pixel pixel = new Pixel(raw); copied.add(pixel);
            return new CensorEngine.Captured<>(pixel, width, height, pixelAt, pixel::close);
        }
        Raw offer(int identity) {
            Raw raw = new Raw(identity);
            assertEquals(ProjectionFrameMailbox.Offer.ACCEPTED,
                    mailbox.offer(mailbox.frame(raw, identity, Long.MAX_VALUE, now, raw::close)));
            return raw;
        }
        void fire() {
            Task task = tasks.stream().filter(t -> !t.cancelled && !t.ran)
                    .min(Comparator.comparingLong(t -> t.at)).orElseThrow(AssertionError::new);
            now = task.at; task.ran = true; task.action.run();
        }
        void replaceTarget() { target = new ProjectionCaptureAdapter.Target<>(target.scope, geometry, mailbox); }
        @Override public void close() {
            engine.close();
            for (Work item : work) item.completion.cancelled();
            mailbox.close(); assertFalse(engine.captureInFlight()); assertEquals(0, engine.livePixelFrames());
            assertEquals(0, engine.callbackFailures());
            if (engine.resourceFailures() == 0) assertEquals(1, drained);
        }
    }

    @Test public void realEngineAdmitsFreshSourceAfterRawCloseAndOwnsCopyUntilTerminal() {
        try (Harness h = new Harness()) {
            Raw raw = h.offer(1); h.fire(); assertEquals(1, h.work.size());
            Work work = h.work.get(0); assertSame(raw, work.input.pixels.origin);
            assertEquals(1, work.input.source.imageRequestIdentity); assertEquals(42, work.input.source.geometry);
            assertEquals(ProtectionScope.lab(7), work.input.source.protectionScope);
            assertEquals(-1, work.input.source.pixelAtMillis); assertEquals(h.now, work.input.source.receivedAtMillis);
            assertEquals(1, raw.closes); assertEquals(0, h.copied.get(0).closes);
            assertFalse(h.engine.captureInFlight()); assertEquals(1, h.engine.livePixelFrames());
        }
    }

    @Test public void noImageDoesNotReusePreviousPixelsOrMintAnotherSource() {
        try (Harness h = new Harness()) {
            h.offer(1); h.fire(); h.fire();
            assertEquals(1, h.copies); assertEquals(1, h.work.size());
            assertEquals(0, h.engine.pendingCount(SceneEngine.Lane.FAST));
            assertEquals(2, h.traces.stream().filter(t -> t.stage == CensorEngine.Stage.CAPTURE_DISPATCHED).count());
        }
    }

    @Test public void newestRawImageIsSelectedInsteadOfEarlierPendingImage() {
        try (Harness h = new Harness()) {
            Raw first = h.offer(1), latest = h.offer(2); h.fire();
            assertEquals(1, first.closes); assertEquals(1, latest.closes);
            assertEquals(1, h.copies); assertSame(latest, h.work.get(0).input.pixels.origin);
        }
    }

    @Test public void unknownRawTimestampNeverBecomesEnginePixelClock() {
        try (Harness h = new Harness()) {
            h.offer(1); h.fire();
            assertEquals(-1, h.work.get(0).input.source.pixelAtMillis);
            assertTrue(h.traces.stream().allMatch(t -> t.pixelAgeMillis == -1));
        }
    }

    @Test public void missingTargetCannotConsumeAnUnprotectedImage() {
        try (Harness h = new Harness()) {
            Raw raw = h.offer(1); h.target = null; h.fire();
            assertEquals(0, h.copies); assertEquals(0, raw.closes); assertEquals(1, h.mailbox.state().pending);
            assertEquals(0, h.work.size());
        }
    }

    @Test public void wrongScopeIsRejectedBeforeTakingAnotherOwnersImage() {
        try (Harness h = new Harness(); Harness other = new Harness(99)) {
            Raw raw = other.offer(1); h.target = other.target; h.fire();
            assertEquals(0, h.copies); assertEquals(0, raw.closes); assertEquals(1, other.mailbox.state().pending);
            assertEquals(0, h.work.size());
        }
    }

    @Test public void actualDispatchGateRefusalLeavesRawWithItsProducerOwner() {
        try (Harness h = new Harness()) {
            Raw raw = h.offer(1); h.resolveHook = h.engine::stop; h.fire();
            assertEquals(0, h.copies); assertEquals(0, raw.closes); assertEquals(1, h.mailbox.state().pending);
            assertEquals(0, h.work.size()); assertFalse(h.engine.captureInFlight());
        }
    }

    @Test public void sameNumericForeignScopeIsRejectedByRealOwnerAuthority() {
        try (Harness h = new Harness(); Harness other = new Harness()) {
            assertEquals(h.target.scope.epoch, other.target.scope.epoch);
            assertEquals(h.target.scope.geometry, other.target.scope.geometry);
            assertFalse(h.engine.isCurrent(other.target.scope));
            Raw raw = other.offer(1); h.target = other.target; h.fire();
            assertEquals(0, h.copies); assertEquals(0, raw.closes); assertEquals(0, h.work.size());
        }
    }

    @Test public void initialResolverErrorTerminatesWithoutConsumingRaw() {
        try (Harness h = new Harness()) {
            Raw raw = h.offer(1); h.resolveHook = () -> { throw new AssertionError("initial resolver error"); };
            h.fire(); assertEquals(0, h.copies); assertEquals(0, raw.closes);
            assertFalse(h.engine.captureInFlight()); assertEquals(1, h.engine.resourceFailures());
        }
    }

    @Test public void initialResolverRuntimeFailureIsAnOrdinaryTerminal() {
        try (Harness h = new Harness()) {
            Raw raw = h.offer(1); h.resolveHook = () -> { throw new IllegalStateException("scope unavailable"); };
            h.fire(); assertEquals(0, h.copies); assertEquals(0, raw.closes);
            assertFalse(h.engine.captureInFlight()); assertEquals(0, h.engine.resourceFailures());
        }
    }

    @Test public void initialResolverResourceFailureIsNotAnOrdinaryUnavailableScope() {
        try (Harness h = new Harness()) {
            Raw raw = h.offer(1);
            h.resolveHook = () -> { throw new ProjectionCaptureAdapter.ResourceFailure(new IllegalStateException("source resource")); };
            h.fire(); assertEquals(0, h.copies); assertEquals(0, raw.closes);
            assertFalse(h.engine.captureInFlight()); assertEquals(1, h.engine.resourceFailures());
        }
    }

    @Test public void finalResolverResourceFailureDisposesPreparedPixelsAndFaultsEngine() {
        try (Harness h = new Harness()) {
            int[] resolves = {0};
            h.resolveHook = () -> { if (++resolves[0] == 2) throw new ProjectionCaptureAdapter.ResourceFailure(new IllegalStateException("source resource")); };
            Raw raw = h.offer(1); h.fire(); assertEquals(1, raw.closes);
            assertEquals(1, h.copied.get(0).closes); assertEquals(0, h.work.size());
            assertFalse(h.engine.captureInFlight()); assertEquals(1, h.engine.resourceFailures());
        }
    }

    @Test public void finalResolverErrorDisposesPreparedPixelsAndFaultsEngine() {
        try (Harness h = new Harness()) {
            int[] resolves = {0};
            h.resolveHook = () -> { if (++resolves[0] == 2) throw new AssertionError("final resolver error"); };
            Raw raw = h.offer(1); h.fire(); assertEquals(1, raw.closes);
            assertEquals(1, h.copied.get(0).closes); assertEquals(0, h.work.size());
            assertFalse(h.engine.captureInFlight()); assertEquals(1, h.engine.resourceFailures());
        }
    }

    @Test public void realEngineEpochFenceRejectsCutoverAfterFinalTargetSnapshot() {
        try (Harness h = new Harness()) {
            int[] resolves = {0}; long epoch = h.engine.currentEpoch();
            h.resolveHook = () -> { if (++resolves[0] == 2) h.engine.changeScope(42, ProtectionScope.lab(7)); };
            Raw raw = h.offer(1); h.fire(); assertEquals(1, raw.closes);
            assertTrue(h.engine.currentEpoch() > epoch); assertEquals(1, h.copied.get(0).closes);
            assertEquals(0, h.work.size()); assertFalse(h.engine.captureInFlight());
        }
    }

    @Test public void sameSizedTargetReplacementDuringCopyDiscardsPreparedPixels() {
        try (Harness h = new Harness()) {
            h.copy = (target, raw) -> { CensorEngine.Captured<Pixel> result = h.prepare(target, raw); h.replaceTarget(); return result; };
            Raw raw = h.offer(1); h.fire();
            assertEquals(1, raw.closes); assertEquals(1, h.copied.get(0).closes);
            assertEquals(0, h.work.size()); assertEquals(0, h.engine.livePixelFrames());
        }
    }

    @Test public void engineStopDuringCopyCannotPublishAnOldScope() {
        try (Harness h = new Harness()) {
            h.copy = (target, raw) -> { CensorEngine.Captured<Pixel> result = h.prepare(target, raw); h.engine.stop(); return result; };
            Raw raw = h.offer(1); h.fire();
            assertEquals(1, raw.closes); assertEquals(1, h.copied.get(0).closes);
            assertEquals(0, h.work.size()); assertFalse(h.engine.captureInFlight());
        }
    }

    @Test public void mailboxStopDuringCopyCannotPublishEvenIfResolverRetainsTarget() {
        try (Harness h = new Harness()) {
            h.copy = (target, raw) -> { CensorEngine.Captured<Pixel> result = h.prepare(target, raw); h.mailbox.close(); return result; };
            h.offer(1); h.fire(); assertEquals(0, h.work.size()); assertEquals(1, h.copied.get(0).closes);
            assertTrue(h.mailbox.isDrained());
        }
    }

    @Test public void rawCleanupFailureIsEngineResourceFailureNotOrdinaryRetry() {
        try (Harness h = new Harness()) {
            Raw raw = new Raw(1);
            h.mailbox.offer(h.mailbox.frame(raw, 1, -1, 0, () -> { raw.close(); throw new IllegalStateException("raw cleanup"); }));
            h.fire(); assertEquals(1, raw.closes); assertEquals(1, h.copied.get(0).closes);
            assertEquals(1, h.engine.resourceFailures()); assertEquals(0, h.work.size());
        }
    }

    @Test public void poisonedOwnerCannotBecomeNoImageRetry() {
        try (Harness h = new Harness()) {
            Raw raw = new Raw(1);
            h.mailbox.offer(h.mailbox.frame(raw, 1, -1, 0, () -> { raw.close(); throw new IllegalStateException("raw cleanup"); }));
            h.offer(2); h.fire();
            assertEquals(0, h.copies); assertEquals(1, h.engine.resourceFailures()); assertEquals(0, h.work.size());
        }
    }

    @Test public void explicitPartialCleanupFailureFaultsTheRealEngine() {
        try (Harness h = new Harness()) {
            h.copy = (target, raw) -> { throw new ProjectionCaptureAdapter.ResourceFailure(new IllegalStateException("partial cleanup")); };
            Raw raw = h.offer(1); h.fire(); assertEquals(1, raw.closes);
            assertEquals(1, h.engine.resourceFailures()); assertEquals(0, h.work.size());
        }
    }

    @Test public void ordinaryCopyFailureClosesRawWithoutInventingAResourceLeak() {
        try (Harness h = new Harness()) {
            h.copy = (target, raw) -> { throw new IllegalArgumentException("unsupported format; no partial output"); };
            Raw raw = h.offer(1); h.fire(); assertEquals(1, raw.closes);
            assertEquals(0, h.engine.resourceFailures()); assertEquals(0, h.work.size());
        }
    }

    @Test public void unexpectedCopyErrorStillClosesRawAndTerminatesCapture() {
        try (Harness h = new Harness()) {
            h.copy = (target, raw) -> { throw new AssertionError("uncertain native state"); };
            Raw raw = h.offer(1); h.fire(); assertEquals(1, raw.closes);
            assertFalse(h.engine.captureInFlight()); assertEquals(1, h.engine.resourceFailures());
        }
    }

    @Test public void mismatchedDimensionsAreDisposedBeforeEngineAdmission() {
        try (Harness h = new Harness()) {
            h.copy = (target, raw) -> h.prepare(raw.raw(), 32, 128, -1);
            h.offer(1); h.fire(); assertEquals(0, h.work.size()); assertEquals(1, h.copied.get(0).closes);
        }
    }

    @Test public void inventedPixelClockIsDisposedBeforeEngineAdmission() {
        try (Harness h = new Harness()) {
            h.copy = (target, raw) -> h.prepare(raw.raw(), 64, 128, raw.frame.arrivalUptimeMillis);
            h.offer(1); h.fire(); assertEquals(0, h.work.size()); assertEquals(1, h.copied.get(0).closes);
        }
    }

    @Test public void failedDiscardOfObsoletePreparedPixelsFaultsEngine() {
        try (Harness h = new Harness()) {
            h.copy = (target, raw) -> {
                CensorEngine.Captured<Pixel> result = h.prepare(target, raw);
                result.pixels.failCleanup = true; h.replaceTarget(); return result;
            };
            Raw raw = h.offer(1); h.fire(); assertEquals(1, raw.closes);
            assertEquals(1, h.copied.get(0).closes); assertEquals(0, h.work.size());
            assertEquals(1, h.engine.resourceFailures());
        }
    }
}
