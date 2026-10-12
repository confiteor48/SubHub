package com.subhub.app.censor.engine;

import static org.junit.Assert.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class BoundedLatestWorkTest {
    private static final class Queue implements Executor {
        final ArrayDeque<Runnable> actions = new ArrayDeque<>();
        public void execute(Runnable action) { actions.add(action); }
        void next() { actions.remove().run(); }
    }
    @Test public void oneActiveAndOneLatestRemainBoundedAcrossFiveHundredOffers() {
        Queue queue = new Queue(); List<Integer> work = new ArrayList<>();
        BoundedLatestWork<Integer> lane = new BoundedLatestWork<>(queue, work::add, error -> fail());
        lane.offer(0);
        for (int i = 1; i <= 500; i++) lane.offer(i);
        assertEquals(2, lane.retainedItems()); assertEquals(1, queue.actions.size()); assertEquals(499, lane.drops());
        queue.next(); assertEquals(Collections.singletonList(0), work); assertEquals(1, queue.actions.size());
        queue.next(); assertEquals(Arrays.asList(0, 500), work); assertEquals(0, lane.retainedItems());
        lane.close(); assertTrue(lane.drained());
    }
    @Test public void invalidationRevokesQueuedWorkAndPendingButCanAdmitAFreshGeneration() {
        Queue queue = new Queue(); List<Integer> work = new ArrayList<>();
        BoundedLatestWork<Integer> lane = new BoundedLatestWork<>(queue, work::add, error -> fail());
        lane.offer(1); lane.offer(2); lane.invalidate(); lane.offer(3);
        queue.next(); assertTrue(work.isEmpty()); queue.next(); assertEquals(Collections.singletonList(3), work);
        lane.close(); assertTrue(lane.drained());
    }
    @Test public void closeWaitsForTheActualQueuedCallbackAndDoesNotRunIt() {
        Queue queue = new Queue(); AtomicInteger calls = new AtomicInteger();
        BoundedLatestWork<Integer> lane = new BoundedLatestWork<>(queue, value -> calls.incrementAndGet(), error -> fail());
        lane.offer(1); lane.offer(2); lane.close(); lane.offer(3);
        assertFalse(lane.drained()); assertEquals(1, lane.retainedItems()); queue.next();
        assertTrue(lane.drained()); assertEquals(0, calls.get()); assertTrue(queue.actions.isEmpty());
    }
    @Test public void closeDuringWorkRetainsCompletionOwnershipAndDropsThePendingItem() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor(); CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        BoundedLatestWork<Integer> lane = new BoundedLatestWork<>(worker, value -> {
            calls.incrementAndGet(); entered.countDown();
            try { assertTrue(release.await(5, TimeUnit.SECONDS)); } catch (InterruptedException error) { throw new AssertionError(error); }
        }, error -> fail());
        try {
            lane.offer(1); assertTrue(entered.await(5, TimeUnit.SECONDS)); lane.offer(2); lane.close();
            assertFalse(lane.drained()); assertEquals(1, lane.retainedItems()); release.countDown();
            worker.shutdown(); assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(lane.drained()); assertEquals(1, calls.get());
        } finally { release.countDown(); lane.close(); worker.shutdownNow(); }
    }
    @Test public void failedMutationCannotRunPendingOrBeRetried() {
        Queue queue = new Queue(); AtomicInteger calls = new AtomicInteger(); List<Throwable> faults = new ArrayList<>();
        IllegalStateException failure = new IllegalStateException("uncertain store");
        BoundedLatestWork<Integer> lane = new BoundedLatestWork<>(queue, value -> { calls.incrementAndGet(); throw failure; }, faults::add);
        lane.offer(1); lane.offer(2); queue.next(); lane.offer(3);
        assertEquals(Collections.singletonList(failure), faults); assertEquals(1, calls.get());
        assertTrue(lane.drained()); assertTrue(queue.actions.isEmpty());
    }
    @Test public void knownRejectionClosesAndDrainsWithoutRetry() {
        AtomicInteger submissions = new AtomicInteger(); List<Throwable> faults = new ArrayList<>();
        RejectedExecutionException failure = new RejectedExecutionException("closed executor");
        BoundedLatestWork<Integer> lane = new BoundedLatestWork<>(action -> { submissions.incrementAndGet(); throw failure; }, value -> fail(), faults::add);
        lane.offer(1); lane.offer(2); assertEquals(1, submissions.get()); assertTrue(lane.drained());
        assertEquals(Collections.singletonList(failure), faults);
    }
    @Test public void throwAfterAcceptingCannotInventCompletionOrReplayTheActualCallback() {
        Queue queue = new Queue(); AtomicInteger calls = new AtomicInteger(); List<Throwable> faults = new ArrayList<>();
        IllegalStateException failure = new IllegalStateException("accepted then failed");
        BoundedLatestWork<Integer> lane = new BoundedLatestWork<>(action -> { queue.execute(action); throw failure; }, value -> calls.incrementAndGet(), faults::add);
        lane.offer(1); assertFalse(lane.drained()); Runnable actual = queue.actions.remove(); actual.run(); actual.run();
        assertTrue(lane.drained()); assertEquals(0, calls.get()); assertEquals(Collections.singletonList(failure), faults);
    }
    @Test public void duplicateConcurrentRunnableCannotRunTwiceOrReleaseTheRealWorker() throws Exception {
        Queue queue = new Queue(); CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        BoundedLatestWork<Integer> lane = new BoundedLatestWork<>(queue, value -> {
            calls.incrementAndGet(); entered.countDown();
            try { assertTrue(release.await(5, TimeUnit.SECONDS)); } catch (InterruptedException error) { throw new AssertionError(error); }
        }, error -> fail());
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            lane.offer(1); Runnable action = queue.actions.remove(); Future<?> actual = worker.submit(action);
            assertTrue(entered.await(5, TimeUnit.SECONDS)); action.run(); lane.close();
            assertFalse(lane.drained()); assertEquals(1, calls.get()); release.countDown(); actual.get(5, TimeUnit.SECONDS);
            assertTrue(lane.drained()); action.run(); assertEquals(1, calls.get());
        } finally { release.countDown(); lane.close(); worker.shutdownNow(); }
    }
    @Test public void workerAndFailureCallbacksRunOutsideTheQueueLock() throws Exception {
        ExecutorService probe = Executors.newSingleThreadExecutor(); Queue queue = new Queue();
        @SuppressWarnings("unchecked") BoundedLatestWork<Integer>[] ref = new BoundedLatestWork[1];
        AtomicInteger faults = new AtomicInteger();
        try {
            ref[0] = new BoundedLatestWork<>(queue, value -> {
                try { assertEquals(1, (int) probe.submit(ref[0]::retainedItems).get(5, TimeUnit.SECONDS)); }
                catch (Exception error) { throw new AssertionError(error); }
                throw new IllegalStateException("store");
            }, failure -> {
                try { assertTrue(probe.submit(ref[0]::drained).get(5, TimeUnit.SECONDS)); faults.incrementAndGet(); }
                catch (Exception error) { throw new AssertionError(error); }
            });
            ref[0].offer(1); queue.next(); assertEquals(1, faults.get());
        } finally { if (ref[0] != null) ref[0].close(); probe.shutdownNow(); }
    }
}
