package com.subhub.app.censor.android;

import static org.junit.Assert.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public final class WindowReadCostTest {
    private final AtomicLong clock = new AtomicLong();
    private WindowReadCost.Probe probe() { return new WindowReadCost.Probe(clock::get); }
    private void at(long value) { clock.set(value); }
    private WindowReadCost.Cost cost(WindowReadCost.Result result, WindowReadCost.Stage stage) { return result.costs.get(stage.ordinal()); }

    @Test public void exclusiveNestedCleanupIsNotCountedAgainAsIdentityOrFiltering() {
        WindowReadCost.Probe p = probe(); at(10); WindowReadCost.Frame filter = p.begin(WindowReadCost.Stage.FILTER);
        at(20); WindowReadCost.Frame identity = p.begin(WindowReadCost.Stage.ROOT_IDENTITY);
        at(30); WindowReadCost.Frame cleanup = p.begin(WindowReadCost.Stage.CLEANUP);
        at(50); p.end(cleanup, true); at(60); p.end(identity, true); at(80); p.end(filter, true);
        at(100); WindowReadCost.Result r = p.finish();
        assertEquals(100, r.elapsedNanos); assertEquals(30, r.residualNanos);
        assertEquals(30, cost(r, WindowReadCost.Stage.FILTER).exclusiveNanos);
        assertEquals(20, cost(r, WindowReadCost.Stage.ROOT_IDENTITY).exclusiveNanos);
        assertEquals(20, cost(r, WindowReadCost.Stage.CLEANUP).exclusiveNanos);
    }

    @Test public void repeatedReturnedAndThrowingCallsRetainCountsAndMaxima() {
        WindowReadCost.Probe p = probe(); at(10); WindowReadCost.Frame one = p.begin(WindowReadCost.Stage.ROOT);
        at(30); p.end(one, true); at(40); WindowReadCost.Frame two = p.begin(WindowReadCost.Stage.ROOT);
        at(70); p.end(two, false); at(80); WindowReadCost.Result r = p.finish();
        WindowReadCost.Cost root = cost(r, WindowReadCost.Stage.ROOT);
        assertEquals(2, root.calls); assertEquals(1, root.thrownCalls); assertEquals(50, root.exclusiveNanos);
        assertEquals(30, root.maximumExclusiveNanos); assertEquals(30, r.residualNanos);
        for (WindowReadCost.Cost item : r.costs) if (item.stage != WindowReadCost.Stage.ROOT) assertEquals(0, item.calls);
    }

    @Test public void missingOrOutOfOrderCloseFailsRatherThanInventingZeroWork() {
        WindowReadCost.Probe p = probe(); WindowReadCost.Frame first = p.begin(WindowReadCost.Stage.FILTER);
        p.begin(WindowReadCost.Stage.ROOT);
        assertThrows(IllegalStateException.class, () -> p.end(first, true));
        assertThrows(IllegalStateException.class, p::finish);
        WindowReadCost.Probe missing = probe(); missing.begin(WindowReadCost.Stage.CLEANUP);
        assertThrows(IllegalStateException.class, missing::finish);
    }

    @Test public void duplicateCloseAndTerminalCannotProduceExtraMeasurements() {
        WindowReadCost.Probe p = probe(); WindowReadCost.Frame frame = p.begin(WindowReadCost.Stage.ROOT);
        p.end(frame, true); assertThrows(IllegalStateException.class, () -> p.end(frame, true));
        WindowReadCost.Probe done = probe(); done.finish();
        assertThrows(IllegalStateException.class, done::finish);
        assertThrows(IllegalStateException.class, () -> done.begin(WindowReadCost.Stage.ROOT));
    }

    @Test public void foreignFrameCannotCloseAnotherOwner() {
        WindowReadCost.Probe first = probe(), second = probe(); WindowReadCost.Frame frame = first.begin(WindowReadCost.Stage.ROOT);
        assertThrows(IllegalStateException.class, () -> second.end(frame, true)); first.end(frame, true); first.finish();
    }

    @Test public void wrongThreadWithSameNameCannotClaimOwnerClock() throws Exception {
        WindowReadCost.Probe p = probe(); AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread wrong = new Thread(() -> { try { p.begin(WindowReadCost.Stage.ROOT); } catch (Throwable e) { failure.set(e); } });
        wrong.setName(Thread.currentThread().getName()); wrong.start(); wrong.join(1000); assertFalse(wrong.isAlive());
        assertTrue(failure.get() instanceof IllegalStateException); assertThrows(IllegalStateException.class, p::finish);
    }

    @Test public void reversedClockIsUnknownNotNegativeOrZeroCost() {
        at(100); WindowReadCost.Probe p = probe(); at(90);
        assertThrows(IllegalStateException.class, () -> p.begin(WindowReadCost.Stage.ROOT));
        assertThrows(IllegalStateException.class, p::finish);
    }

    @Test public void absoluteNanoClockMayBeNegativeWithoutInventingAnEpochMapping() {
        at(-100); WindowReadCost.Probe p = probe(); at(-90); WindowReadCost.Frame frame = p.begin(WindowReadCost.Stage.DISPLAY);
        at(-50); p.end(frame, true); at(-10); WindowReadCost.Result r = p.finish();
        assertEquals(90, r.elapsedNanos); assertEquals(40, cost(r, WindowReadCost.Stage.DISPLAY).exclusiveNanos);
    }

    private WindowReadCost.Diagnostics diagnostics() { return new WindowReadCost.Diagnostics(clock::get, () -> 100L); }
    private static class Recording implements WindowReadCost.Observer {
        final List<WindowReadCost.Call> starts = new ArrayList<>();
        final List<WindowReadCost.Sample> ends = new ArrayList<>();
        @Override public void started(WindowReadCost.Call call) { starts.add(call); }
        @Override public void completed(WindowReadCost.Sample sample) { ends.add(sample); }
    }

    @Test public void disabledDiagnosticsDoNotReadClocksOrCallAnObserver() {
        WindowReadCost.Diagnostics d = new WindowReadCost.Diagnostics(() -> { throw new AssertionError(); }, () -> { throw new AssertionError(); });
        assertFalse(d.entry());
        assertNull(d.start(1, 2, WindowReadCost.Operation.CURRENT, "RESULT_END"));
        d.begin(null, WindowReadCost.Stage.ROOT); d.end(null, null, true); d.finish(null, true, false);
        assertEquals(0, d.attempts()); assertEquals(0, d.failures());
    }

    @Test public void actualParentOperationAndRepeatedBoundaryRemainDistinct() {
        WindowReadCost.Diagnostics d = diagnostics(); Recording observer = new Recording(); d.observer(observer);
        WindowReadCost.Session first = d.start(7, 12, WindowReadCost.Operation.OPEN_SCOPE, "PROVIDER_OPEN");
        at(10); d.finish(first, true, false);
        WindowReadCost.Session second = d.start(7, 12, WindowReadCost.Operation.CURRENT, "OPENED");
        at(20); d.finish(second, true, false);
        WindowReadCost.Session third = d.start(7, 12, WindowReadCost.Operation.CURRENT, "OPENED");
        at(30); d.finish(third, true, false);
        assertEquals(3, d.attempts()); assertEquals(0, d.failures()); assertEquals(3, observer.ends.size());
        assertEquals(7, third.call.epoch); assertEquals(12, third.call.request); assertEquals(3, third.call.sequence);
        assertEquals(Thread.currentThread().getId(), third.call.threadId);
        assertSame(WindowReadCost.Operation.OPEN_SCOPE, first.call.operation);
        assertEquals("PROVIDER_OPEN", first.call.boundary); assertEquals("OPENED", second.call.boundary);
        assertNotEquals(second.call.sequence, third.call.sequence);
    }

    @Test public void callbacksHaveSeparateOverheadOutsideExclusiveBody() {
        WindowReadCost.Diagnostics d = diagnostics(); Recording observer = new Recording() {
            @Override public void started(WindowReadCost.Call call) { super.started(call); at(10); }
            @Override public void completed(WindowReadCost.Sample sample) { super.completed(sample); at(60); }
        }; d.observer(observer);
        WindowReadCost.Session session = d.start(1, 2, WindowReadCost.Operation.CURRENT, "RESULT_END");
        at(20); WindowReadCost.Frame frame = d.begin(session, WindowReadCost.Stage.ROOT);
        at(40); d.end(session, frame, true); at(50); d.finish(session, true, false);
        assertEquals(10, d.startCallbackNanos()); assertEquals(10, d.endCallbackNanos());
        assertEquals(40, observer.ends.get(0).result.elapsedNanos);
        assertEquals(20, cost(observer.ends.get(0).result, WindowReadCost.Stage.ROOT).exclusiveNanos);
        assertEquals(1, d.startCallbacks()); assertEquals(1, d.endCallbacks());
    }

    @Test public void throwingStartCannotEscapeOrManufactureACompletedRead() {
        WindowReadCost.Diagnostics d = diagnostics(); d.observer(new Recording() {
            @Override public void started(WindowReadCost.Call call) { throw new AssertionError("observer start"); }
        });
        assertNull(d.start(1, 2, WindowReadCost.Operation.OPEN_TREE, "PROVIDER_OPEN"));
        assertEquals(1, d.attempts()); assertEquals(1, d.failures()); assertEquals(0, d.endCallbacks());
    }

    @Test public void throwingEmissionCannotReplaceBodyFailureOrAcquireSuppressedAuthority() {
        WindowReadCost.Diagnostics d = diagnostics(); d.observer(new Recording() {
            @Override public void completed(WindowReadCost.Sample sample) { throw new AssertionError("observer end"); }
        });
        RuntimeException body = new IllegalStateException("body"), cleanup = new IllegalArgumentException("cleanup");
        WindowReadCost.Session session = d.start(1, 2, WindowReadCost.Operation.CURRENT, "RESULT_END");
        Throwable seen = assertThrows(IllegalStateException.class, () -> {
            try { body.addSuppressed(cleanup); throw body; }
            finally { d.finish(session, false, false); }
        });
        assertSame(body, seen); assertArrayEquals(new Throwable[]{cleanup}, seen.getSuppressed()); assertEquals(1, d.failures());
    }

    @Test public void observerReplacementCannotStealAnAdmittedSessionsTerminal() {
        WindowReadCost.Diagnostics d = diagnostics(); Recording original = new Recording(), later = new Recording(); d.observer(original);
        WindowReadCost.Session session = d.start(1, 2, WindowReadCost.Operation.OPEN_TREE, "PROVIDER_OPEN");
        d.observer(later); at(20); d.finish(session, true, true);
        assertEquals(1, original.ends.size()); assertTrue(original.ends.get(0).rootTransferred); assertTrue(later.ends.isEmpty());
    }

    @Test public void failedClockOrIncompleteStageIsExplicitUnknownNotCompletedZero() {
        WindowReadCost.Diagnostics d = diagnostics(); Recording observer = new Recording(); d.observer(observer);
        WindowReadCost.Session session = d.start(1, 2, WindowReadCost.Operation.CURRENT, "SCAN_END");
        WindowReadCost.Frame frame = d.begin(session, WindowReadCost.Stage.ROOT); at(-1); d.end(session, frame, true);
        d.finish(session, true, false); assertEquals(2, d.failures()); assertTrue(observer.ends.isEmpty());
    }

    @Test public void disposalOnlyIsNotTransferAndExceptionalReturnCannotTransfer() {
        WindowReadCost.Diagnostics d = diagnostics(); Recording observer = new Recording(); d.observer(observer);
        WindowReadCost.Session session = d.start(1, 2, WindowReadCost.Operation.CURRENT, "OPENED");
        WindowReadCost.Frame frame = d.begin(session, WindowReadCost.Stage.CLEANUP); at(10); d.end(session, frame, false);
        at(20); d.finish(session, false, false);
        assertFalse(observer.ends.get(0).rootTransferred); assertFalse(observer.ends.get(0).returnedNormally);
        assertEquals(1, cost(observer.ends.get(0).result, WindowReadCost.Stage.CLEANUP).thrownCalls);
        assertThrows(IllegalArgumentException.class, () -> new WindowReadCost.Sample(session.call, observer.ends.get(0).result, 100, false, true));
    }

    @Test public void oneShotClockFailureAtStageBeginPoisonsWholeSample() {
        AtomicBoolean failNext = new AtomicBoolean();
        WindowReadCost.Diagnostics d = new WindowReadCost.Diagnostics(() -> {
            if (failNext.getAndSet(false)) throw new IllegalStateException("one shot clock"); return clock.get();
        }, () -> 100L);
        Recording observer = new Recording(); d.observer(observer);
        WindowReadCost.Session session = d.start(1, 2, WindowReadCost.Operation.CURRENT, "SCAN_END");
        failNext.set(true); assertNull(d.begin(session, WindowReadCost.Stage.ROOT));
        at(20); d.finish(session, true, false);
        assertEquals(2, d.failures()); assertEquals(1, observer.starts.size()); assertTrue(observer.ends.isEmpty());
    }
}
