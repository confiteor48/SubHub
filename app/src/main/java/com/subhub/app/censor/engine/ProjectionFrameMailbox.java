package com.subhub.app.censor.engine;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Consuming raw-image handoff for ONE projection/reader/scope/geometry generation.
 * The platform producer must acquire and offer serially; a consumer may copy/close concurrently.
 * This owner retains one newest image and permits one preparation lease, not inference or drawing.
 * No engine Source is minted here. A raw timestamp has no implied uptime/pixel-clock mapping.
 *
 * Never reuse a raw resource in another envelope, including after its disposal. Live aliases are
 * rejected without closing another lease's resource; detecting historical reuse would require an
 * unbounded tombstone inventory. Stop the exact mailbox before replacing its generation. The
 * reader must remain open until its serial producer has actually stopped AND isDrained(), including
 * disposal callbacks actually returning. An acquisition or producer-owned unoffered Frame is outside
 * this mailbox's inventory; isDrained() alone never grants permission to close an ImageReader.
 */
public final class ProjectionFrameMailbox<R> implements AutoCloseable {
    public enum Offer { ACCEPTED, DUPLICATE, STALE, CLOSED, FOREIGN, FAILED, LIVE_ALIAS }

    /** Exclusive producer-owned image until offered; close discards only an untransferred image. */
    public static final class Frame<R> implements AutoCloseable {
        public final long acquisitionSequence;
        public final long reportedTimestampNanos;
        public final long arrivalUptimeMillis;
        private final Object owner;
        private final R raw;
        private final Runnable disposer;
        private final AtomicInteger ownership = new AtomicInteger(); // producer, mailbox, closed

        private Frame(Object owner, R raw, long sequence, long timestamp, long arrival, Runnable disposer) {
            if (sequence <= 0 || timestamp < -1 || arrival < 0) throw new IllegalArgumentException("image metadata");
            this.owner = owner; this.raw = Objects.requireNonNull(raw);
            this.disposer = Objects.requireNonNull(disposer);
            acquisitionSequence = sequence; reportedTimestampNanos = timestamp; arrivalUptimeMillis = arrival;
        }

        @Override public void close() {
            if (ownership.compareAndSet(0, 2)) disposer.run();
        }
    }

    /** Only the consumer closes this lease; close of the mailbox cannot revoke its readable image. */
    public final class Read implements AutoCloseable {
        public final Frame<R> frame;
        private boolean released;
        private Read(Frame<R> frame) { this.frame = frame; }
        public R raw() {
            synchronized (ProjectionFrameMailbox.this) {
                if (released) throw new IllegalStateException("closed projection read");
                return frame.raw;
            }
        }
        @Override public void close() {
            synchronized (ProjectionFrameMailbox.this) {
                if (released) return;
                released = true; active = null; disposing++;
            }
            dispose(frame);
        }
    }

    public static final class State {
        public final boolean closed;
        public final int pending, borrowed, disposing;
        public final long accepted, replaced, rejected, resourceFailures, protocolFailures;
        private State(boolean closed, int pending, int borrowed, int disposing, long accepted,
                long replaced, long rejected, long resourceFailures, long protocolFailures) {
            this.closed = closed; this.pending = pending; this.borrowed = borrowed; this.disposing = disposing;
            this.accepted = accepted; this.replaced = replaced; this.rejected = rejected;
            this.resourceFailures = resourceFailures; this.protocolFailures = protocolFailures;
        }
    }

    private final Map<R, Frame<R>> owned = new IdentityHashMap<>();
    private Frame<R> pending;
    private Read active;
    private long lastSequence, accepted, replaced, rejected, resourceFailures, protocolFailures;
    private int disposing;
    private boolean closed;

    /** Creating metadata does not transfer the caller's image or establish a new capture. */
    public Frame<R> frame(R raw, long sequence, long reportedTimestampNanos,
            long arrivalUptimeMillis, Runnable disposer) {
        return new Frame<>(this, raw, sequence, reportedTimestampNanos, arrivalUptimeMillis, disposer);
    }

    /** Takes exclusive ownership even when refusing the image. Duplicate envelopes transfer nothing. */
    public Offer offer(Frame<R> frame) {
        Objects.requireNonNull(frame);
        if (!frame.ownership.compareAndSet(0, 1)) return Offer.DUPLICATE;
        Frame<R> discard;
        Offer result;
        synchronized (this) {
            if (owned.containsKey(frame.raw)) {
                // A forged second envelope must not dispose a pending, borrowed or closing image.
                frame.ownership.set(2); protocolFailures++; rejected++; return Offer.LIVE_ALIAS;
            }
            owned.put(frame.raw, frame);
            if (frame.owner != this) result = Offer.FOREIGN;
            else if (closed) result = Offer.CLOSED;
            else if (resourceFailures != 0 || protocolFailures != 0) result = Offer.FAILED;
            else if (frame.acquisitionSequence <= lastSequence) result = Offer.STALE;
            else result = Offer.ACCEPTED;
            if (result == Offer.ACCEPTED) {
                lastSequence = frame.acquisitionSequence; accepted++;
                discard = pending; pending = frame;
                if (discard != null) replaced++;
            } else { rejected++; discard = frame; }
            if (discard != null) disposing++;
        }
        if (discard != null) dispose(discard);
        return result;
    }

    /** Consumes once. A busy consumer or in-flight cleanup leaves the newest waiting image intact. */
    public synchronized Read take() {
        if (closed || resourceFailures != 0 || protocolFailures != 0 || active != null
                || disposing != 0 || pending == null) return null;
        Frame<R> selected = pending; pending = null;
        active = new Read(selected); return active;
    }

    @Override public void close() {
        Frame<R> discard;
        synchronized (this) {
            if (closed) return;
            closed = true; discard = pending; pending = null;
            if (discard != null) disposing++;
        }
        if (discard != null) dispose(discard);
    }

    /** Managed-resource drain only, NOT producer termination. Cleanup failure remains incomplete. */
    public synchronized boolean isDrained() {
        return closed && pending == null && active == null && disposing == 0 && owned.isEmpty()
                && resourceFailures == 0 && protocolFailures == 0;
    }

    public synchronized State state() {
        return new State(closed, pending == null ? 0 : 1, active == null ? 0 : 1, disposing,
                accepted, replaced, rejected, resourceFailures, protocolFailures);
    }

    private void dispose(Frame<R> frame) {
        boolean failed = false;
        try {
            if (!frame.ownership.compareAndSet(1, 2)) throw new IllegalStateException("image ownership");
            frame.disposer.run();
        } catch (RuntimeException | Error failure) { failed = true; }
        finally {
            synchronized (this) {
                if (failed) resourceFailures++;
                owned.remove(frame.raw); disposing--;
            }
        }
    }
}
