package com.subhub.app.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.BiConsumer;

/** Bounded FIFO resource ownership across a blocking lookup and its asynchronous UI delivery. */
public final class SerialOwnedLookup<T> implements AutoCloseable {
    public enum Offer { ACCEPTED, FULL, CLOSED, EXECUTOR_REJECTED }
    public interface Lease {
        boolean isCurrent();
        void release();
    }
    public interface Processor<T> {
        /** May transfer the lease to a UI callback; that callback must release it in finally. */
        void process(T resource, Lease lease);
    }

    private final int capacity;
    private final Executor executor;
    private final Processor<T> processor;
    private final Consumer<T> disposer;
    private final BiConsumer<T, RuntimeException> failure;
    private final ArrayDeque<Entry> pending = new ArrayDeque<>();
    private long generation;
    private int outstanding;
    private boolean draining, closed;

    public SerialOwnedLookup(int capacity, Executor executor, Processor<T> processor,
            Consumer<T> disposer, BiConsumer<T, RuntimeException> failure) {
        if (capacity < 1) throw new IllegalArgumentException("positive capacity required");
        this.capacity = capacity;
        this.executor = Objects.requireNonNull(executor);
        this.processor = Objects.requireNonNull(processor);
        this.disposer = Objects.requireNonNull(disposer);
        this.failure = Objects.requireNonNull(failure);
    }

    /** Always takes ownership, including on rejection. Never coalesces unknown-producer deltas. */
    public Offer offer(T resource) {
        Objects.requireNonNull(resource);
        Offer result;
        List<Entry> abandoned = new ArrayList<>();
        synchronized (this) {
            if (closed) result = Offer.CLOSED;
            else if (outstanding >= capacity) result = Offer.FULL;
            else {
                Entry entry = new Entry(resource, generation);
                outstanding++;
                pending.addLast(entry);
                result = Offer.ACCEPTED;
                if (!draining) {
                    draining = true;
                    try {
                        executor.execute(this::drain);
                    } catch (RejectedExecutionException rejected) {
                        draining = false;
                        generation++;
                        abandoned.addAll(pending);
                        pending.clear();
                        result = Offer.EXECUTOR_REJECTED;
                    }
                }
            }
        }
        for (Entry entry : abandoned) entry.release();
        if (result == Offer.FULL || result == Offer.CLOSED) disposer.accept(resource);
        return result;
    }

    public synchronized int outstanding() { return outstanding; }

    /** Queued resources close now; a blocking lookup or posted UI delivery closes on completion. */
    public void invalidate() {
        List<Entry> abandoned;
        synchronized (this) {
            generation++;
            abandoned = new ArrayList<>(pending);
            pending.clear();
        }
        for (Entry entry : abandoned) entry.release();
    }

    @Override public void close() {
        synchronized (this) { closed = true; }
        invalidate();
    }

    private void drain() {
        try {
            while (true) {
                Entry entry;
                synchronized (this) { entry = pending.pollFirst(); }
                if (entry == null) return;
                if (!entry.isCurrent()) {
                    entry.release();
                    continue;
                }
                try {
                    processor.process(entry.resource, entry);
                } catch (RuntimeException error) {
                    entry.release();
                    failure.accept(entry.resource, error);
                } catch (Error error) {
                    entry.release();
                    throw error;
                }
            }
        } finally {
            List<Entry> abandoned = new ArrayList<>();
            synchronized (this) {
                draining = false;
                if (!closed && !pending.isEmpty()) {
                    draining = true;
                    try {
                        executor.execute(this::drain);
                    } catch (RejectedExecutionException rejected) {
                        draining = false;
                        generation++;
                        abandoned.addAll(pending);
                        pending.clear();
                    }
                }
            }
            for (Entry entry : abandoned) entry.release();
        }
    }

    private final class Entry implements Lease {
        final T resource;
        final long admittedGeneration;
        final AtomicBoolean released = new AtomicBoolean();
        Entry(T resource, long admittedGeneration) {
            this.resource = resource;
            this.admittedGeneration = admittedGeneration;
        }
        @Override public boolean isCurrent() {
            synchronized (SerialOwnedLookup.this) {
                return !closed && !released.get() && admittedGeneration == generation;
            }
        }
        @Override public void release() {
            if (!released.compareAndSet(false, true)) return;
            try { disposer.accept(resource); }
            finally {
                synchronized (SerialOwnedLookup.this) { outstanding--; }
            }
        }
    }
}
