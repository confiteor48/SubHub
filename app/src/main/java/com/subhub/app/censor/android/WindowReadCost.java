package com.subhub.app.censor.android;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/** Optional diagnostics only. Durations never grant a source, scope, pose or resource lease. */
final class WindowReadCost {
    enum Stage { DISPLAY, WINDOW_LIST, FILTER, ROOT, ROOT_IDENTITY, CLEANUP }
    enum Operation { OPEN_TREE, OPEN_SCOPE, CURRENT }

    /** Sequence is local to this Reader, never an engine request or an admission capability. */
    static final class Call {
        final long epoch, request, sequence, startedAtMillis, threadId;
        final Operation operation;
        final String boundary, threadName;
        Call(long epoch, long request, long sequence, Operation operation, String boundary, long at) {
            if (epoch < 0 || request <= 0 || sequence <= 0 || at < 0) throw new IllegalArgumentException("Window cost parent");
            this.epoch = epoch; this.request = request; this.sequence = sequence;
            this.operation = Objects.requireNonNull(operation); this.boundary = Objects.requireNonNull(boundary);
            startedAtMillis = at; Thread thread = Thread.currentThread(); threadId = thread.getId(); threadName = thread.getName();
        }
    }

    static final class Sample {
        final Call call;
        final Result result;
        final long finishedAtMillis;
        final boolean returnedNormally, rootTransferred;
        Sample(Call call, Result result, long finished, boolean normal, boolean transferred) {
            if (finished < call.startedAtMillis || (transferred && !normal)) throw new IllegalArgumentException("Window cost terminal");
            this.call = call; this.result = result; finishedAtMillis = finished;
            returnedNormally = normal; rootTransferred = transferred;
        }
    }

    interface Observer {
        void started(Call call);
        void completed(Sample sample);
    }

    static final class Session {
        final Call call;
        final Observer observer;
        final Probe probe;
        private volatile boolean invalid;
        Session(Call call, Observer observer, Probe probe) { this.call = call; this.observer = observer; this.probe = probe; }
    }

    /** Optional, fail-isolated observation. No callbacks or clock reads when disabled. */
    static final class Diagnostics {
        private final LongSupplier nanos, uptime;
        private final AtomicLong sequence = new AtomicLong(), entries = new AtomicLong(), refused = new AtomicLong(),
                beginFailures = new AtomicLong(), attempts = new AtomicLong(), failures = new AtomicLong(),
                startCallbacks = new AtomicLong(), endCallbacks = new AtomicLong(),
                startCallbackNanos = new AtomicLong(), endCallbackNanos = new AtomicLong();
        private volatile Observer observer;
        Diagnostics(LongSupplier nanos, LongSupplier uptime) {
            this.nanos = Objects.requireNonNull(nanos); this.uptime = Objects.requireNonNull(uptime);
        }
        void observer(Observer observer) { this.observer = observer; }
        boolean entry() {
            if (observer == null) return false;
            entries.incrementAndGet(); return true;
        }
        void refused() { refused.incrementAndGet(); }
        void beginFailed() { beginFailures.incrementAndGet(); }
        long entries() { return entries.get(); }
        long refusedEntries() { return refused.get(); }
        long throwingEntries() { return beginFailures.get(); }
        long attempts() { return attempts.get(); }
        long failures() { return failures.get(); }
        long startCallbacks() { return startCallbacks.get(); }
        long endCallbacks() { return endCallbacks.get(); }
        long startCallbackNanos() { return startCallbackNanos.get(); }
        long endCallbackNanos() { return endCallbackNanos.get(); }

        Session start(long epoch, long request, Operation operation, String boundary) {
            Observer selected = observer;
            if (selected == null) return null;
            attempts.incrementAndGet();
            try {
                Call call = new Call(epoch, request, sequence.incrementAndGet(), operation, boundary, uptime.getAsLong());
                long before = nanos.getAsLong();
                try { startCallbacks.incrementAndGet(); selected.started(call); }
                finally { startCallbackNanos.addAndGet(duration(before)); }
                return new Session(call, selected, new Probe(nanos));
            } catch (RuntimeException | Error failure) { failures.incrementAndGet(); return null; }
        }

        Frame begin(Session session, Stage stage) {
            if (session == null || session.invalid) return null;
            try { return session.probe.begin(stage); }
            catch (RuntimeException | Error failure) { session.invalid = true; failures.incrementAndGet(); return null; }
        }
        void end(Session session, Frame frame, boolean normal) {
            if (session == null || session.invalid || frame == null) return;
            try { session.probe.end(frame, normal); }
            catch (RuntimeException | Error failure) { session.invalid = true; failures.incrementAndGet(); }
        }
        void finish(Session session, boolean normal, boolean transferred) {
            if (session == null) return;
            try {
                if (session.invalid) throw new IllegalStateException("Incomplete window cost session");
                // Caller has already executed endRead. Emission is outside the measured body.
                Result result = session.probe.finish();
                Sample sample = new Sample(session.call, result, uptime.getAsLong(), normal, transferred);
                long before = nanos.getAsLong();
                try { endCallbacks.incrementAndGet(); session.observer.completed(sample); }
                finally { endCallbackNanos.addAndGet(duration(before)); }
            } catch (RuntimeException | Error failure) { failures.incrementAndGet(); }
        }
        private long duration(long before) {
            long elapsed = nanos.getAsLong() - before;
            if (elapsed < 0) throw new IllegalStateException("Observer cost clock reversed");
            return elapsed;
        }
    }

    static final class Cost {
        final Stage stage;
        final long calls, exclusiveNanos, maximumExclusiveNanos, thrownCalls;
        Cost(Stage stage, long calls, long nanos, long maximum, long thrown) {
            this.stage = stage; this.calls = calls; exclusiveNanos = nanos;
            maximumExclusiveNanos = maximum; thrownCalls = thrown;
        }
    }

    static final class Result {
        final long startedNanos, finishedNanos, elapsedNanos, residualNanos;
        final List<Cost> costs;
        Result(long start, long end, long residual, List<Cost> costs) {
            startedNanos = start; finishedNanos = end; elapsedNanos = end - start;
            residualNanos = residual; this.costs = Collections.unmodifiableList(costs);
        }
    }

    static final class Frame {
        final Stage stage;
        private final Probe owner;
        private final Frame parent;
        private final long startedNanos;
        private long nestedNanos;
        private boolean closed;
        Frame(Probe owner, Stage stage, Frame parent, long start) {
            this.owner = owner; this.stage = stage; this.parent = parent; startedNanos = start;
        }
    }

    static final class Probe {
        private final LongSupplier clock;
        private final Thread thread = Thread.currentThread();
        private final long startedNanos;
        private final long[] calls = new long[Stage.values().length], nanos = new long[calls.length],
                maximum = new long[calls.length], thrown = new long[calls.length];
        private Frame active;
        private boolean sealed, invalid;
        private long previousNanos;

        Probe(LongSupplier clock) {
            this.clock = Objects.requireNonNull(clock); startedNanos = previousNanos = clock.getAsLong();
        }

        Frame begin(Stage stage) {
            check(); Objects.requireNonNull(stage); long at = now();
            calls[stage.ordinal()]++;
            active = new Frame(this, stage, active, at); return active;
        }

        void end(Frame frame, boolean returnedNormally) {
            check();
            if (frame == null || frame.owner != this || frame != active || frame.closed) fail("Cost frame order or ownership");
            long duration = now() - frame.startedNanos;
            if (duration < frame.nestedNanos) fail("Nested costs exceed parent");
            long exclusive = duration - frame.nestedNanos; int index = frame.stage.ordinal();
            nanos[index] = Math.addExact(nanos[index], exclusive); maximum[index] = Math.max(maximum[index], exclusive);
            if (!returnedNormally) thrown[index]++;
            frame.closed = true; active = frame.parent;
            if (active != null) active.nestedNanos = Math.addExact(active.nestedNanos, duration);
        }

        Result finish() {
            check(); if (active != null) fail("Incomplete cost frames");
            long end = now(), elapsed = end - startedNanos, measured = 0; List<Cost> result = new ArrayList<>();
            for (Stage stage : Stage.values()) {
                int i = stage.ordinal(); measured = Math.addExact(measured, nanos[i]);
                result.add(new Cost(stage, calls[i], nanos[i], maximum[i], thrown[i]));
            }
            if (measured > elapsed) fail("Overlapping exclusive costs");
            sealed = true; return new Result(startedNanos, end, elapsed - measured, result);
        }

        private long now() {
            long at = clock.getAsLong(); if (at < previousNanos) fail("Cost clock reversed"); previousNanos = at; return at;
        }
        private void check() {
            if (Thread.currentThread() != thread || sealed || invalid) fail("Cost owner, terminal or validity differs");
        }
        private void fail(String message) { invalid = true; throw new IllegalStateException(message); }
    }

    private WindowReadCost() { }
}
