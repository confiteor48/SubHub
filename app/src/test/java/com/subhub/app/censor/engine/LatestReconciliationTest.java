package com.subhub.app.censor.engine;

import static org.junit.Assert.*;
import org.junit.Test;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

public final class LatestReconciliationTest {
    private static final class Queue implements Executor, LatestReconciliation.Delivery {
        final ArrayDeque<Runnable> pending = new ArrayDeque<>();
        public void execute(Runnable action) { pending.add(action); }
        public boolean post(Runnable action) { execute(action); return true; }
        void next() { pending.remove().run(); }
    }
    private static final class Harness {
        final Queue worker = new Queue(), main = new Queue();
        final AtomicInteger reads = new AtomicInteger();
        final List<Integer> commits = new ArrayList<>();
        final List<Throwable> faults = new ArrayList<>();
        final LatestReconciliation<Integer> lane = new LatestReconciliation<>(worker, main, reads::incrementAndGet, commits::add, faults::add);
    }
    @Test public void heldLookupCoalescesFiveHundredRequestsToOneLatestRead() {
        Harness h = new Harness(); h.lane.request();
        for (int i = 0; i < 500; i++) h.lane.request();
        assertEquals(1, h.worker.pending.size()); h.worker.next(); assertEquals(1, h.main.pending.size());
        h.main.next(); assertTrue(h.commits.isEmpty()); assertEquals(1, h.worker.pending.size());
        h.worker.next(); h.main.next(); assertEquals(Collections.singletonList(2), h.commits);
        assertEquals(2, h.reads.get()); assertTrue(h.worker.pending.isEmpty());
    }
    @Test public void heldMainDeliveryDoesNotAdmitAnotherLookup() {
        Harness h = new Harness(); h.lane.request(); h.worker.next();
        for (int i = 0; i < 500; i++) h.lane.request();
        assertTrue(h.worker.pending.isEmpty()); assertEquals(1, h.main.pending.size());
        h.main.next(); assertTrue(h.commits.isEmpty()); assertEquals(1, h.worker.pending.size());
    }
    @Test public void stopRevokesHeldResultWithoutRestarting() {
        Harness h = new Harness(); h.lane.request(); h.worker.next(); h.lane.invalidate(); h.main.next();
        assertTrue(h.commits.isEmpty()); assertTrue(h.worker.pending.isEmpty());
        h.lane.request(); h.worker.next(); h.main.next(); assertEquals(Collections.singletonList(2), h.commits);
    }
    @Test public void stopAlsoCancelsLatestWantedRequest() {
        Harness h = new Harness(); h.lane.request(); h.lane.request(); h.lane.invalidate(); h.worker.next(); h.main.next();
        assertTrue(h.commits.isEmpty()); assertTrue(h.worker.pending.isEmpty());
    }
    @Test public void closeWaitsForActualLookupAndTerminalDelivery() {
        Harness h = new Harness(); h.lane.request(); h.lane.close(); assertFalse(h.lane.drained());
        h.worker.next(); assertFalse(h.lane.drained()); h.main.next(); assertTrue(h.lane.drained());
        assertTrue(h.commits.isEmpty()); h.lane.request(); assertTrue(h.worker.pending.isEmpty());
    }
    @Test public void rejectedDeliveryCannotCommitOrRetryAndReleasesLookupReservation() {
        Queue worker = new Queue(); AtomicInteger commits = new AtomicInteger();
        LatestReconciliation<Integer> lane = new LatestReconciliation<>(worker, action -> false, () -> 1,
                value -> commits.incrementAndGet(), fault -> fail("No off-main product callback"));
        lane.request(); worker.next(); assertTrue(lane.drained()); assertEquals(0, commits.get());
        lane.request(); assertTrue(worker.pending.isEmpty());
    }
    @Test public void staleReadFaultCannotBeDiscardedAsObsoleteSuccess() {
        Queue worker = new Queue(), main = new Queue(); List<Throwable> faults = new ArrayList<>();
        AssertionError fault = new AssertionError("owned cleanup");
        LatestReconciliation<Integer> lane = new LatestReconciliation<>(worker, main, () -> { throw fault; },
                value -> fail("No commit"), faults::add);
        lane.request(); lane.request(); worker.next(); main.next();
        assertEquals(Collections.singletonList(fault), faults); assertTrue(lane.drained()); assertTrue(worker.pending.isEmpty());
    }
    @Test public void reentrantStopDuringCommitDoesNotRestart() {
        Queue worker = new Queue(), main = new Queue(); AtomicInteger commits = new AtomicInteger();
        @SuppressWarnings("unchecked") LatestReconciliation<Integer>[] ref = new LatestReconciliation[1];
        ref[0] = new LatestReconciliation<>(worker, main, () -> 1, value -> { commits.incrementAndGet(); ref[0].invalidate(); }, fault -> fail());
        ref[0].request(); worker.next(); main.next(); assertEquals(1, commits.get()); assertTrue(worker.pending.isEmpty());
    }
    @Test public void reentrantRequestSchedulesExactlyOneLookupAfterCommitReturns() {
        Queue worker = new Queue(), main = new Queue(); AtomicInteger commits = new AtomicInteger();
        @SuppressWarnings("unchecked") LatestReconciliation<Integer>[] ref = new LatestReconciliation[1];
        ref[0] = new LatestReconciliation<>(worker, main, () -> 1, value -> {
            if (commits.incrementAndGet() == 1) { ref[0].request(); ref[0].request(); assertTrue(worker.pending.isEmpty()); }
        }, fault -> fail());
        ref[0].request(); worker.next(); main.next(); assertEquals(1, worker.pending.size());
        worker.next(); main.next(); assertEquals(2, commits.get()); assertTrue(worker.pending.isEmpty());
    }
    @Test public void commitFailureClosesLaneAndDoesNotAdmitReentrantLatest() {
        Queue worker = new Queue(), main = new Queue(); List<Throwable> faults = new ArrayList<>();
        IllegalStateException error = new IllegalStateException("commit");
        LatestReconciliation<Integer> lane = new LatestReconciliation<>(worker, main, () -> 1, value -> { throw error; }, faults::add);
        lane.request(); worker.next(); main.next(); assertEquals(Collections.singletonList(error), faults); assertTrue(lane.drained());
    }
    @Test public void workerRejectionIsTerminalAndNotRetried() {
        List<Throwable> faults = new ArrayList<>(); AtomicInteger admissions = new AtomicInteger();
        RejectedExecutionException error = new RejectedExecutionException("worker rejected");
        LatestReconciliation<Integer> lane = new LatestReconciliation<>(action -> { admissions.incrementAndGet(); throw error; },
                action -> true, () -> 1, value -> fail(), faults::add);
        lane.request(); lane.request(); assertEquals(1, admissions.get()); assertEquals(Collections.singletonList(error), faults); assertTrue(lane.drained());
    }
    @Test public void uncertainAcceptedWorkerSubmissionCannotClaimDrainBeforeActualCallback() {
        Queue worker = new Queue(), main = new Queue(); List<Throwable> faults = new ArrayList<>();
        IllegalStateException error = new IllegalStateException("after accepting");
        LatestReconciliation<Integer> lane = new LatestReconciliation<>(action -> { worker.execute(action); throw error; },
                main, () -> 1, value -> fail(), faults::add);
        lane.request(); assertEquals(Collections.singletonList(error), faults); assertFalse(lane.drained());
        worker.next(); assertFalse(lane.drained()); main.next(); assertTrue(lane.drained());
    }
    @Test public void uncertainAcceptedDeliveryCannotClaimDrainBeforeActualDelivery() {
        Queue worker = new Queue(), main = new Queue();
        LatestReconciliation<Integer> lane = new LatestReconciliation<>(worker,
                action -> { main.post(action); throw new IllegalStateException("after delivery acceptance"); },
                () -> 1, value -> fail(), fault -> fail("No off-main callbacks"));
        lane.request(); worker.next(); assertFalse(lane.drained()); main.next(); assertTrue(lane.drained());
    }
}
