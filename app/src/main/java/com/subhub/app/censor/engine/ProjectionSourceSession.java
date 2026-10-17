package com.subhub.app.censor.engine;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/** One raw projection generation. No capture timer, inference, scene, permission or presentation owner. */
public final class ProjectionSourceSession<R> implements AutoCloseable {
    public interface SerialProducer {
        /** Enqueue FIFO, never inline. False/throw means not enqueued; no accepted task may run concurrently. */
        boolean post(Runnable action);
    }
    public interface Callbacks {
        void imageAvailable();
        void revoked();
        void resized(int width, int height);
    }
    public interface Endpoint<R> {
        /**
         * Register stop before ONE display creation. Partials remain owned here if this throws.
         * Only acquireLatest may acquire raw resources, synchronously on the serial producer.
         */
        void start(Callbacks callbacks);
        Acquired<R> acquireLatest();
        /** Called at the serial fence, before mailbox closure; must not close the reader. */
        void detachImages();
        // Called only after producer fence + raw retirement, possibly on the last borrower's thread.
        void releaseDisplay();
        void closeReader();
        void unregisterCallback();
        void stopProjection();
    }
    public static final class Acquired<R> {
        public final R raw;
        public final long reportedTimestampNanos;
        private final Runnable disposer;
        /** Exclusive new raw resource, not an alias or an asynchronous borrow retained by the endpoint. */
        public Acquired(R raw, long reportedTimestampNanos, Runnable disposer) {
            this.raw = Objects.requireNonNull(raw); this.disposer = Objects.requireNonNull(disposer);
            this.reportedTimestampNanos = reportedTimestampNanos;
        }
    }
    public enum Failure { START, ACQUIRE, CLOCK, RAW_CLOSE, POST, INVALIDATE, DETACH, DISPLAY_CLOSE, READER_CLOSE, CALLBACK_CLOSE, PROJECTION_STOP }
    public static final class State {
        public final boolean startRequested, started, stopping, invalidationCompleted, producerFenced, rawRetired, cleanupCompleted;
        public final long acquisitionSequence;
        public final Map<Failure, Long> failures;
        public final ProjectionFrameMailbox.State mailbox;
        private State(ProjectionSourceSession<?> source) {
            startRequested = source.startRequested; started = source.started; stopping = source.stopping;
            invalidationCompleted = source.invalidationCompleted; producerFenced = source.producerFenced;
            rawRetired = source.rawRetired; cleanupCompleted = source.cleanupCompleted;
            acquisitionSequence = source.acquisitionSequence;
            failures = Collections.unmodifiableMap(new EnumMap<>(source.failures)); mailbox = source.mailbox.state();
        }
        public boolean failed() {
            return !failures.isEmpty() || mailbox.resourceFailures != 0 || mailbox.protocolFailures != 0
                    || mailbox.retirementCallbackFailures != 0;
        }
    }

    private final int width, height;
    private final SerialProducer producer;
    private final Endpoint<R> endpoint;
    private final LongSupplier uptimeMillis;
    private final Runnable invalidateScope;
    private final ProjectionFrameMailbox<R> mailbox;
    private final EnumMap<Failure, Long> failures = new EnumMap<>(Failure.class);
    private final Callbacks callbacks = new Callbacks() {
        @Override public void imageAvailable() { signalImage(); }
        @Override public void revoked() { close(); }
        @Override public void resized(int w, int h) { if (w != width || h != height) close(); }
    };
    private boolean startRequested, started, stopping, invalidationCompleted, imageQueued;
    private boolean fenceStarted, producerFenced, rawRetired, cleanupStarted, cleanupCompleted;
    private long acquisitionSequence;

    /** Invalidate only this generation's engine epoch, never a replacement scope; it may block/throw. */
    public ProjectionSourceSession(int width, int height, SerialProducer producer, Endpoint<R> endpoint,
            LongSupplier uptimeMillis, Runnable invalidateScope) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("source dimensions");
        this.width = width; this.height = height;
        this.producer = Objects.requireNonNull(producer); this.endpoint = Objects.requireNonNull(endpoint);
        this.uptimeMillis = Objects.requireNonNull(uptimeMillis); this.invalidateScope = Objects.requireNonNull(invalidateScope);
        mailbox = new ProjectionFrameMailbox<>(state -> rawRetired());
    }

    public ProjectionFrameMailbox<R> mailbox() { return mailbox; }

    public void start() {
        synchronized (this) {
            if (startRequested || stopping) throw new IllegalStateException("single-use projection source");
            startRequested = true;
        }
        post(this::startOnProducer);
    }

    public synchronized State state() { return new State(this); }
    public synchronized boolean available() { return started && !stopping && failures.isEmpty(); }
    public synchronized boolean isDrained() {
        return producerFenced && cleanupCompleted && failures.isEmpty() && mailbox.isDrained();
    }

    @Override public void close() {
        synchronized (this) { if (stopping) return; stopping = true; }
        try { invalidateScope.run(); }
        catch (RuntimeException | Error failure) { record(Failure.INVALIDATE); }
        finally { synchronized (this) { invalidationCompleted = true; } }
        post(this::fenceOnProducer);
    }

    private void post(Runnable action) {
        boolean accepted = false;
        try { accepted = producer.post(action); }
        catch (RuntimeException | Error failure) { /* The producer contract says no task was enqueued. */ }
        if (!accepted) { record(Failure.POST); close(); }
    }

    private void startOnProducer() {
        if (stopping()) { fenceOnProducer(); return; }
        try {
            endpoint.start(callbacks);
            synchronized (this) { started = true; }
        } catch (RuntimeException | Error failure) { record(Failure.START); close(); }
        finally { if (stopping()) fenceOnProducer(); }
    }

    private void signalImage() {
        synchronized (this) {
            if (stopping || imageQueued) return;
            imageQueued = true;
        }
        post(this::acquireOnProducer);
    }

    private void acquireOnProducer() {
        boolean canAcquire;
        synchronized (this) { imageQueued = false; canAcquire = started && !stopping; }
        if (!canAcquire) { if (stopping()) fenceOnProducer(); return; }
        Acquired<R> acquired = null;
        try {
            acquired = endpoint.acquireLatest();
            if (acquired != null && !stopping()) {
                long sequence;
                synchronized (this) {
                    if (acquisitionSequence == Long.MAX_VALUE) throw new IllegalStateException("source sequence exhausted");
                    sequence = ++acquisitionSequence;
                }
                long arrival;
                try { arrival = uptimeMillis.getAsLong(); }
                catch (RuntimeException | Error failure) { record(Failure.CLOCK); throw failure; }
                ProjectionFrameMailbox.Frame<R> frame = mailbox.frame(acquired.raw, sequence,
                        acquired.reportedTimestampNanos, arrival, acquired.disposer);
                acquired = null; // offer takes exclusive custody even on ordinary refusal or live-alias rejection.
                mailbox.offer(frame);
                if (mailboxFailed()) close();
            }
        } catch (RuntimeException | Error failure) { record(Failure.ACQUIRE); close(); }
        finally {
            if (acquired != null) {
                try { acquired.disposer.run(); }
                catch (RuntimeException | Error failure) { record(Failure.RAW_CLOSE); close(); }
            }
            if (stopping()) fenceOnProducer();
        }
    }

    private synchronized boolean stopping() { return stopping; }
    private synchronized void record(Failure failure) { failures.put(failure, failures.getOrDefault(failure, 0L) + 1); }
    private boolean mailboxFailed() {
        ProjectionFrameMailbox.State value = mailbox.state();
        return value.resourceFailures != 0 || value.protocolFailures != 0 || value.retirementCallbackFailures != 0;
    }

    private void fenceOnProducer() {
        synchronized (this) {
            if (!stopping || !invalidationCompleted || fenceStarted) return;
            fenceStarted = true;
        }
        attempt(Failure.DETACH, endpoint::detachImages);
        synchronized (this) { producerFenced = true; }
        mailbox.close(); // No acquired/unoffered producer image remains; a borrowed copy still owns its raw.
        retireEndpoint(); // Also handles a mailbox externally closed before this producer fence.
    }

    private void rawRetired() {
        synchronized (this) { rawRetired = true; }
        retireEndpoint();
    }

    private void retireEndpoint() {
        synchronized (this) {
            if (!producerFenced || !rawRetired || cleanupStarted) return;
            cleanupStarted = true;
        }
        attempt(Failure.DISPLAY_CLOSE, endpoint::releaseDisplay);
        attempt(Failure.READER_CLOSE, endpoint::closeReader);
        attempt(Failure.CALLBACK_CLOSE, endpoint::unregisterCallback);
        attempt(Failure.PROJECTION_STOP, endpoint::stopProjection);
        synchronized (this) { cleanupCompleted = true; }
    }

    private void attempt(Failure kind, Runnable action) {
        try { action.run(); } catch (RuntimeException | Error failure) { record(kind); }
    }
}
