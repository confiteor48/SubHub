package com.subhub.app.censor.android;

import static org.junit.Assert.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

public final class FixtureDiagnosticQueryTest {
    private static long now() { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime()); }

    @Test public void successfulReadHasARealResponseAndObservedWorkerClosure() throws Exception {
        FixtureDiagnosticQuery.Result<Boolean> result = FixtureDiagnosticQuery.read("success", 1000,
                FixtureDiagnosticQueryTest::now, () -> true);
        assertEquals("SUCCESS", result.status); assertEquals(Boolean.TRUE, result.value);
        assertNull(result.failureType); assertTrue(result.workerFinished); assertFalse(result.worker.isAlive());
        assertTrue(result.startedAtMillis <= result.returnedAtMillis);
        assertTrue(result.returnedAtMillis <= result.responseObservedAtMillis);
        assertTrue(result.responseObservedAtMillis <= result.snapshotAtMillis);
    }
    @Test public void queryExceptionIsAResultNotAReplacementAssertion() throws Exception {
        FixtureDiagnosticQuery.Result<Boolean> result = FixtureDiagnosticQuery.read("error", 1000,
                FixtureDiagnosticQueryTest::now, () -> { throw new IllegalStateException("private payload"); });
        assertEquals("ERROR", result.status); assertNull(result.value);
        assertEquals(IllegalStateException.class.getName(), result.failureType); assertTrue(result.workerFinished);
    }
    @Test public void nullResponseIsNotAWorkerTimeout() throws Exception {
        FixtureDiagnosticQuery.Result<Object> result = FixtureDiagnosticQuery.read("null", 1000,
                FixtureDiagnosticQueryTest::now, () -> null);
        assertEquals("SUCCESS", result.status); assertNull(result.value); assertTrue(result.workerFinished);
    }
    @Test public void interruptibleTimeoutCannotBorrowItsCancellationResponse() throws Exception {
        FixtureDiagnosticQuery.Result<Boolean> result = FixtureDiagnosticQuery.read("interruptible", 100,
                FixtureDiagnosticQueryTest::now, () -> {
                    try { new CountDownLatch(1).await(); }
                    catch (InterruptedException ignored) { return true; }
                    return false;
                });
        result.worker.join(1000);
        assertEquals("TIMEOUT", result.status); assertNull(result.value); assertNull(result.failureType);
        assertFalse(result.worker.isAlive());
    }
    @Test public void actualLiveWorkerAndLateCompletionRemainUnknownAtResponseDeadline() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        FixtureDiagnosticQuery.Result<Boolean> result = null;
        try {
            result = FixtureDiagnosticQuery.read("noninterruptible", 100, FixtureDiagnosticQueryTest::now, () -> {
                while (true) try { release.await(); return true; }
                catch (InterruptedException ignored) { /* Model a Binder call that has not returned. */ }
            });
            assertEquals("TIMEOUT", result.status); assertNull(result.value); assertNull(result.failureType);
            assertFalse(result.workerFinished); assertTrue(result.worker.isAlive()); assertEquals(-1, result.returnedAtMillis);
        } finally {
            release.countDown(); if (result != null) { result.worker.join(1000); assertFalse(result.worker.isAlive()); }
        }
        assertEquals("TIMEOUT", result.status); assertNull(result.value);
    }
    @Test public void invalidResponseBoundsNeverStartAWorker() {
        AtomicBoolean queried = new AtomicBoolean();
        for (long maximum : new long[]{0, -1, 1001}) {
            assertThrows(IllegalArgumentException.class, () -> FixtureDiagnosticQuery.read("invalid", maximum,
                    FixtureDiagnosticQueryTest::now, () -> queried.compareAndSet(false, true)));
        }
        assertFalse(queried.get());
    }
}
