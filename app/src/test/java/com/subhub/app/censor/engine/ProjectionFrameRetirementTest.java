package com.subhub.app.censor.engine;

import static org.junit.Assert.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

public class ProjectionFrameRetirementTest {
    private static final class Raw {
        int closes;
        void close() { assertEquals("double raw close", 0, closes++); }
    }
    private static ProjectionFrameMailbox.Frame<Raw> frame(ProjectionFrameMailbox<Raw> box, Raw raw, long sequence) {
        return box.frame(raw, sequence, -1, 0, raw::close);
    }
    private static void retiredState(ProjectionFrameMailbox.State state) {
        assertTrue(state.closed); assertEquals(0, state.pending); assertEquals(0, state.borrowed); assertEquals(0, state.disposing);
    }

    @Test public void emptyCloseNotifiesOnceOutsideLock() {
        AtomicInteger calls = new AtomicInteger(); AtomicReference<ProjectionFrameMailbox<Raw>> ref = new AtomicReference<>();
        ProjectionFrameMailbox<Raw> box = new ProjectionFrameMailbox<>(state -> {
            assertFalse(Thread.holdsLock(ref.get())); retiredState(state); calls.incrementAndGet();
        }); ref.set(box); box.close(); box.close();
        assertEquals(1, calls.get()); assertEquals(0, box.state().retirementCallbackFailures); assertTrue(box.isDrained());
    }

    @Test public void heldReadPostponesRetirementUntilRawCloseReturns() {
        AtomicInteger calls = new AtomicInteger(); Raw raw = new Raw();
        ProjectionFrameMailbox<Raw> box = new ProjectionFrameMailbox<>(state -> {
            retiredState(state); assertEquals(1, raw.closes); calls.incrementAndGet();
        }); box.offer(frame(box, raw, 1)); ProjectionFrameMailbox<Raw>.Read read = box.take();
        box.close(); assertEquals(0, calls.get()); assertFalse(box.isDrained()); assertSame(raw, read.raw());
        read.close(); read.close(); assertEquals(1, calls.get()); assertTrue(box.isDrained());
    }

    @Test public void pendingAndBorrowedMustBothRetire() {
        AtomicInteger calls = new AtomicInteger(); Raw one = new Raw(), two = new Raw();
        ProjectionFrameMailbox<Raw> box = new ProjectionFrameMailbox<>(state -> {
            retiredState(state); assertEquals(1, one.closes); assertEquals(1, two.closes); calls.incrementAndGet();
        }); box.offer(frame(box, one, 1)); ProjectionFrameMailbox<Raw>.Read read = box.take();
        box.offer(frame(box, two, 2)); box.close(); assertEquals(1, two.closes); assertEquals(0, calls.get());
        read.close(); assertEquals(1, calls.get()); assertTrue(box.isDrained());
    }

    @Test public void blockingDisposalCannotNotifyPrematurely() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger(); AtomicReference<Throwable> failure = new AtomicReference<>(); Raw raw = new Raw();
        ProjectionFrameMailbox<Raw> box = new ProjectionFrameMailbox<>(state -> { retiredState(state); calls.incrementAndGet(); });
        box.offer(box.frame(raw, 1, -1, 0, () -> {
            entered.countDown();
            try { assertTrue(release.await(2, TimeUnit.SECONDS)); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
            raw.close();
        }));
        Thread worker = new Thread(() -> { try { box.close(); } catch (Throwable error) { failure.set(error); } }); worker.start();
        try { assertTrue(entered.await(2, TimeUnit.SECONDS)); assertEquals(0, calls.get()); assertEquals(1, box.state().disposing); }
        finally { release.countDown(); worker.join(2000); }
        assertFalse(worker.isAlive()); assertNull(failure.get()); assertEquals(1, calls.get()); assertTrue(box.isDrained());
    }

    @Test public void failedRawCleanupSignalsLogicalRetirementNotSuccessfulDrain() {
        AtomicInteger calls = new AtomicInteger(); Raw raw = new Raw();
        ProjectionFrameMailbox<Raw> box = new ProjectionFrameMailbox<>(state -> {
            retiredState(state); assertEquals(1, state.resourceFailures); calls.incrementAndGet();
        }); box.offer(box.frame(raw, 1, -1, 0, () -> { raw.close(); throw new IllegalStateException("raw close"); }));
        box.close(); assertEquals(1, calls.get()); assertEquals(1, raw.closes);
        assertFalse(box.isDrained()); assertEquals(0, box.state().retirementCallbackFailures);
    }

    @Test public void failedProtocolStillSignalsRetirementAfterLastLease() {
        AtomicInteger calls = new AtomicInteger(); Raw raw = new Raw();
        ProjectionFrameMailbox<Raw> box = new ProjectionFrameMailbox<>(state -> {
            retiredState(state); assertEquals(1, state.protocolFailures); calls.incrementAndGet();
        }); box.offer(frame(box, raw, 1)); ProjectionFrameMailbox<Raw>.Read read = box.take();
        assertEquals(ProjectionFrameMailbox.Offer.LIVE_ALIAS, box.offer(frame(box, raw, 2)));
        box.close(); assertEquals(0, calls.get()); read.close(); assertEquals(1, calls.get()); assertFalse(box.isDrained());
    }

    @Test public void throwingObserverIsRecordedWithoutRetryingDisposal() {
        AtomicInteger calls = new AtomicInteger(); Raw raw = new Raw();
        ProjectionFrameMailbox<Raw> box = new ProjectionFrameMailbox<>(state -> {
            calls.incrementAndGet(); throw new AssertionError("retirement delivery");
        }); box.offer(frame(box, raw, 1)); box.close(); box.close();
        assertEquals(1, raw.closes); assertEquals(1, calls.get()); assertEquals(1, box.state().retirementCallbackFailures);
        assertEquals(0, box.state().resourceFailures); assertFalse(box.isDrained());
    }

    @Test public void reentrantObserverCannotNotifyOrDisposeTwice() {
        AtomicInteger calls = new AtomicInteger(); AtomicReference<ProjectionFrameMailbox<Raw>> ref = new AtomicReference<>(); Raw raw = new Raw();
        ProjectionFrameMailbox<Raw> box = new ProjectionFrameMailbox<>(state -> {
            assertFalse(Thread.holdsLock(ref.get())); retiredState(state); calls.incrementAndGet(); ref.get().close();
            assertNull(ref.get().take());
        }); ref.set(box); box.offer(frame(box, raw, 1)); box.close();
        assertEquals(1, calls.get()); assertEquals(1, raw.closes); assertTrue(box.isDrained());
    }

    @Test public void producerOwnedUnofferedImageIsOutsideRetirementAuthority() {
        AtomicInteger calls = new AtomicInteger(); Raw raw = new Raw();
        ProjectionFrameMailbox<Raw> box = new ProjectionFrameMailbox<>(state -> { retiredState(state); calls.incrementAndGet(); });
        ProjectionFrameMailbox.Frame<Raw> heldByProducer = frame(box, raw, 1);
        box.close(); assertEquals(1, calls.get()); assertEquals(0, raw.closes);
        assertEquals(ProjectionFrameMailbox.Offer.CLOSED, box.offer(heldByProducer));
        assertEquals(1, raw.closes); assertEquals(1, calls.get()); assertTrue(box.isDrained());
    }

    @Test public void concurrentCloseAndReadReleaseNotifyExactlyOnce() throws Exception {
        AtomicInteger calls = new AtomicInteger(); CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>(); Raw raw = new Raw();
        ProjectionFrameMailbox<Raw> box = new ProjectionFrameMailbox<>(state -> { retiredState(state); calls.incrementAndGet(); });
        box.offer(frame(box, raw, 1)); ProjectionFrameMailbox<Raw>.Read read = box.take();
        Thread closer = new Thread(() -> run(start, box::close, failure));
        Thread releaser = new Thread(() -> run(start, read::close, failure)); closer.start(); releaser.start(); start.countDown();
        closer.join(2000); releaser.join(2000);
        assertFalse(closer.isAlive()); assertFalse(releaser.isAlive()); assertNull(failure.get());
        assertEquals(1, raw.closes); assertEquals(1, calls.get()); assertTrue(box.isDrained());
    }

    @Test public void callbackCannotConsumeAClosedMailbox() {
        AtomicInteger calls = new AtomicInteger(); AtomicReference<ProjectionFrameMailbox<Raw>> ref = new AtomicReference<>(); Raw late = new Raw();
        ProjectionFrameMailbox<Raw> box = new ProjectionFrameMailbox<>(state -> {
            calls.incrementAndGet(); assertNull(ref.get().take());
            assertEquals(ProjectionFrameMailbox.Offer.CLOSED, ref.get().offer(frame(ref.get(), late, 2)));
        }); ref.set(box); box.close(); assertEquals(1, calls.get()); assertEquals(1, late.closes); assertTrue(box.isDrained());
    }

    @Test public void observerMustBeSuppliedBeforePublication() {
        assertThrows(NullPointerException.class, () -> new ProjectionFrameMailbox<Raw>(null));
    }

    private static void run(CountDownLatch start, Runnable action, AtomicReference<Throwable> failure) {
        try { assertTrue(start.await(2, TimeUnit.SECONDS)); action.run(); }
        catch (Throwable error) { failure.compareAndSet(null, error); }
    }
}
