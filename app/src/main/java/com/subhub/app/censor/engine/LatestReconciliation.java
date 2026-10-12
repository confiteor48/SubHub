package com.subhub.app.censor.engine;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** One lookup, then one terminal delivery; requests coalesce while either stage is outstanding. */
public final class LatestReconciliation<T> implements AutoCloseable {
    public interface Delivery { boolean post(Runnable command); }
    private final Executor worker;
    private final Delivery main;
    private final Supplier<T> read;
    private final Consumer<T> commit;
    private final Consumer<Throwable> failure;
    private long generation;
    private boolean outstanding, wanted, closed;

    /** Lifecycle and deliveries are serialized by the caller's main executor. */
    public LatestReconciliation(Executor worker, Delivery main, Supplier<T> read,
            Consumer<T> commit, Consumer<Throwable> failure) {
        this.worker = Objects.requireNonNull(worker); this.main = Objects.requireNonNull(main);
        this.read = Objects.requireNonNull(read); this.commit = Objects.requireNonNull(commit);
        this.failure = Objects.requireNonNull(failure);
    }
    public synchronized void request() {
        if (closed) return;
        generation++; wanted = true;
        if (!outstanding) launch();
    }
    /** Revoke a held result without scheduling a replacement (e.g. a product stop). */
    public synchronized void invalidate() { generation++; wanted = false; }
    public synchronized long generation() { return generation; }
    public synchronized boolean isCurrent(long selected) { return !closed && generation == selected; }
    @Override public synchronized void close() { closed = true; wanted = false; generation++; }
    public synchronized boolean drained() { return closed && !outstanding; }

    private void launch() {
        final long selected = generation;
        outstanding = true; wanted = false;
        try { worker.execute(() -> {
            T value = null; Throwable error = null;
            try { value = read.get(); }
            catch (RuntimeException | Error caught) { error = caught; }
            final T result = value; final Throwable caught = error;
            try {
                if (!main.post(() -> finish(selected, result, caught))) {
                    // Known rejection: no product callback or retry, actual lookup already completed.
                    synchronized (LatestReconciliation.this) { closed = true; wanted = false; outstanding = false; generation++; }
                }
            } catch (RuntimeException | Error uncertain) {
                // May have enqueued before throwing: retain the reservation until actual delivery.
                synchronized (LatestReconciliation.this) { closed = true; wanted = false; generation++; }
            }
        }); }
        catch (RejectedExecutionException error) {
            outstanding = false; closed = true; generation++; failure.accept(error);
        }
        catch (RuntimeException | Error uncertain) {
            // Arbitrary executors can throw after accepting. Do not manufacture a completed read.
            closed = true; wanted = false; generation++; failure.accept(uncertain);
        }
    }
    private synchronized void finish(long selected, T value, Throwable error) {
        try {
            // A stale lookup's cleanup fault is still a resource fault, not a harmless stale result.
            if (!closed && error != null) { close(); failure.accept(error); }
            else if (isCurrent(selected)) {
                try { commit.accept(value); }
                catch (RuntimeException | Error commitFailure) { close(); failure.accept(commitFailure); }
            }
        } finally {
            outstanding = false;
            if (!closed && wanted) launch();
        }
    }
}
