package com.subhub.app.censor.engine;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/** One actual synchronous worker action plus one replaceable pending metadata item. */
public final class BoundedLatestWork<T> implements AutoCloseable {
    private final Executor executor;
    private final Consumer<T> work;
    private final Consumer<Throwable> failure;
    private Job active;
    private T pending;
    private long generation, drops;
    private boolean closed;

    private final class Job {
        final T value;
        final long generation;
        boolean started;
        Job(T value) { this.value = value; generation = BoundedLatestWork.this.generation; }
    }

    public BoundedLatestWork(Executor executor, Consumer<T> work, Consumer<Throwable> failure) {
        this.executor = Objects.requireNonNull(executor); this.work = Objects.requireNonNull(work);
        this.failure = Objects.requireNonNull(failure);
    }

    public void offer(T value) {
        Objects.requireNonNull(value); Job launch = null;
        synchronized (this) {
            if (closed) return;
            if (active != null) {
                if (pending != null) drops++;
                pending = value;
            } else { active = launch = new Job(value); }
        }
        if (launch != null) dispatch(launch);
    }

    /** Does not interrupt or invent completion of a mutation that already started. */
    public synchronized void invalidate() { generation++; pending = null; }
    @Override public synchronized void close() { closed = true; generation++; pending = null; }
    public synchronized boolean drained() { return closed && active == null; }
    public synchronized long drops() { return drops; }
    public synchronized int retainedItems() { return (active == null ? 0 : 1) + (pending == null ? 0 : 1); }

    private void dispatch(Job job) {
        try { executor.execute(() -> execute(job)); }
        catch (RejectedExecutionException rejected) {
            synchronized (this) { if (active == job) active = null; close(); }
            failure.accept(rejected);
        } catch (RuntimeException | Error uncertain) {
            // Arbitrary executor may have accepted before throwing. Its terminal still owns drain.
            close(); failure.accept(uncertain);
        }
    }

    private void execute(Job job) {
        boolean eligible;
        synchronized (this) {
            if (active != job || job.started) return;
            job.started = true;
            eligible = !closed && generation == job.generation;
        }
        Throwable error = null;
        try {
            if (eligible) work.accept(job.value);
        } catch (RuntimeException | Error caught) { error = caught; }
        Job launch = null;
        synchronized (this) {
            if (active != job) return;
            active = null;
            if (error != null) close();
            if (!closed && pending != null) { active = launch = new Job(pending); pending = null; }
        }
        if (error != null) failure.accept(error);
        if (launch != null) dispatch(launch);
    }
}
