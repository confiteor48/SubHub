package com.subhub.app.censor.engine;

import static org.junit.Assert.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class ProjectionRetirementCompletionTest {
    @Test public void successfulDrainWaitsForObserverReturn() throws Exception { check(false); }
    @Test public void failingObserverNeverExposesSuccessfulDrain() throws Exception { check(true); }

    private static void check(boolean failObserver) throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<Throwable> workerFailure = new AtomicReference<>();
        ProjectionFrameMailbox<Object> box = new ProjectionFrameMailbox<>(state -> {
            assertTrue(state.closed); assertEquals(0, state.pending); assertEquals(0, state.borrowed);
            assertEquals(0, state.disposing); entered.countDown();
            try { assertTrue(release.await(2, TimeUnit.SECONDS)); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
            if (failObserver) throw new IllegalStateException("observer terminal failure");
        });
        Thread worker = new Thread(() -> {
            try { box.close(); } catch (Throwable failure) { workerFailure.set(failure); }
        }); worker.start();
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertEquals(0, box.state().retirementCallbackFailures);
            assertFalse("observer is not terminal", box.isDrained());
        } finally { release.countDown(); worker.join(2000); }
        assertFalse(worker.isAlive()); assertNull(workerFailure.get());
        assertEquals(failObserver ? 1 : 0, box.state().retirementCallbackFailures);
        assertEquals(!failObserver, box.isDrained());
    }
}
