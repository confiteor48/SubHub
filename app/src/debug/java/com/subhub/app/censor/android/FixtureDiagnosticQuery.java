package com.subhub.app.censor.android;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Failure-only diagnostics. Cancellation never establishes that a Binder worker has stopped. */
final class FixtureDiagnosticQuery {
    static final class Result<T> {
        final String status;
        final T value;
        final String failureType;
        final long startedAtMillis, returnedAtMillis, responseObservedAtMillis, snapshotAtMillis;
        final long maximumMillis;
        final boolean workerFinished;
        final Thread worker;
        Result(String status, T value, String failureType, long started, long returned,
                long response, long snapshot, long maximum, boolean finished, Thread worker) {
            this.status = status; this.value = value; this.failureType = failureType;
            startedAtMillis = started; returnedAtMillis = returned;
            responseObservedAtMillis = response; snapshotAtMillis = snapshot;
            maximumMillis = maximum; workerFinished = finished; this.worker = worker;
        }
    }

    private static final class Work<T> implements Runnable {
        final Callable<T> query;
        final LongSupplier clock;
        final CountDownLatch returned = new CountDownLatch(1);
        volatile long startedAtMillis = -1, returnedAtMillis = -1;
        T value;
        Throwable failure;
        Work(Callable<T> query, LongSupplier clock) { this.query = query; this.clock = clock; }
        @Override public void run() {
            startedAtMillis = clock.getAsLong();
            try { value = query.call(); }
            catch (Exception | Error error) { failure = error; }
            finally { returnedAtMillis = clock.getAsLong(); returned.countDown(); }
        }
    }

    static <T> Result<T> read(String name, long maximumMillis, LongSupplier clock, Callable<T> query)
            throws InterruptedException {
        if (maximumMillis < 1 || maximumMillis > 1000) throw new IllegalArgumentException("Diagnostic response bound");
        Work<T> work = new Work<>(query, clock);
        Thread worker = new Thread(work, "FixtureDiagnostic-" + name);
        worker.setDaemon(true); worker.start();
        boolean responded;
        try { responded = work.returned.await(maximumMillis, TimeUnit.MILLISECONDS); }
        catch (InterruptedException failure) { worker.interrupt(); throw failure; }
        long responseAt = clock.getAsLong();
        if (!responded) worker.interrupt();
        worker.join(50); // A bounded observation, not a claim that interrupt terminates Binder.
        boolean finished = !worker.isAlive();
        long startedAt = work.startedAtMillis, returnedAt = work.returnedAtMillis;
        long snapshotAt = clock.getAsLong();
        return new Result<>(!responded ? "TIMEOUT" : work.failure == null ? "SUCCESS" : "ERROR",
                responded && work.failure == null ? work.value : null,
                responded && work.failure != null ? work.failure.getClass().getName() : null,
                startedAt, returnedAt, responseAt, snapshotAt,
                maximumMillis, finished, worker);
    }
}
