package com.subhub.app.censor.engine;

import static org.junit.Assert.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class ProjectionSourceSessionTest {
    private static final class Serial implements ProjectionSourceSession.SerialProducer {
        final Deque<Runnable> queue = new ArrayDeque<>(); boolean accept = true; int maximumQueued;
        @Override public synchronized boolean post(Runnable action) {
            if (!accept) return false;
            queue.addLast(action); maximumQueued = Math.max(maximumQueued, queue.size()); return true;
        }
        void next() { Runnable action; synchronized (this) { action = queue.removeFirst(); } action.run(); }
        synchronized int size() { return queue.size(); }
        void drain() { for (int i = 0; size() != 0; i++) { assertTrue("unbounded own queue", i < 32); next(); } }
    }
    private static final class Raw {
        final Fixture owner; final String name; int closes; boolean failClose;
        Raw(Fixture owner, String name) { this.owner = owner; this.name = name; }
        void close() {
            owner.outside(); assertEquals("double raw close", 0, closes++); owner.log.add("raw." + name);
            if (failClose) throw new IllegalStateException("raw cleanup");
        }
    }
    private static final class Endpoint implements ProjectionSourceSession.Endpoint<Raw> {
        final Fixture owner; final Deque<ProjectionSourceSession.Acquired<Raw>> images = new ArrayDeque<>();
        final EnumMap<ProjectionSourceSession.Failure, Integer> calls = new EnumMap<>(ProjectionSourceSession.Failure.class);
        final EnumSet<ProjectionSourceSession.Failure> fail = EnumSet.noneOf(ProjectionSourceSession.Failure.class);
        ProjectionSourceSession.Callbacks callbacks; Runnable onStart, onAcquire, onDisplayClose; int starts, acquires;
        Endpoint(Fixture owner) { this.owner = owner; }
        @Override public void start(ProjectionSourceSession.Callbacks value) {
            owner.outside(); callbacks = value; starts++; owner.log.add("start");
            if (onStart != null) onStart.run(); action(ProjectionSourceSession.Failure.START); owner.log.add("start.return");
        }
        @Override public ProjectionSourceSession.Acquired<Raw> acquireLatest() {
            owner.outside(); acquires++; owner.log.add("acquire"); if (onAcquire != null) onAcquire.run();
            action(ProjectionSourceSession.Failure.ACQUIRE); return images.pollFirst();
        }
        void give(Raw raw, long stamp) { images.addLast(new ProjectionSourceSession.Acquired<>(raw, stamp, raw::close)); }
        void signal() { callbacks.imageAvailable(); }
        void action(ProjectionSourceSession.Failure kind) {
            owner.outside(); calls.put(kind, calls.getOrDefault(kind, 0) + 1); owner.log.add(kind.name());
            if (fail.contains(kind)) throw new IllegalStateException(kind.name());
        }
        @Override public void detachImages() { action(ProjectionSourceSession.Failure.DETACH); }
        @Override public void releaseDisplay() { action(ProjectionSourceSession.Failure.DISPLAY_CLOSE); if (onDisplayClose != null) onDisplayClose.run(); }
        @Override public void closeReader() { action(ProjectionSourceSession.Failure.READER_CLOSE); }
        @Override public void unregisterCallback() { action(ProjectionSourceSession.Failure.CALLBACK_CLOSE); }
        @Override public void stopProjection() { action(ProjectionSourceSession.Failure.PROJECTION_STOP); }
    }
    private static final class Fixture {
        final Serial serial = new Serial(); final List<String> log = Collections.synchronizedList(new ArrayList<>());
        final Endpoint endpoint = new Endpoint(this); final ProjectionSourceSession<Raw> source;
        int invalidations; long now = 123; Runnable onInvalidate; boolean clockFails;
        Fixture() {
            source = new ProjectionSourceSession<>(3, 2, serial, endpoint, () -> {
                outside(); if (clockFails) throw new IllegalStateException("clock"); return now;
            }, () -> {
                outside(); invalidations++; log.add("invalidate"); if (onInvalidate != null) onInvalidate.run(); log.add("invalidate.return");
            });
        }
        void outside() { assertFalse(Thread.holdsLock(source)); if (source != null) assertFalse(Thread.holdsLock(source.mailbox())); }
        void ready() { source.start(); assertEquals(0, endpoint.starts); serial.drain(); assertTrue(source.available()); }
        Raw raw(String name) { return new Raw(this, name); }
        void image(Raw raw) { endpoint.give(raw, 456789); endpoint.signal(); serial.drain(); }
        void stop() { source.close(); serial.drain(); }
        void cleanupOnce() {
            for (ProjectionSourceSession.Failure kind : cleanupKinds()) assertEquals(kind.name(), Integer.valueOf(1), endpoint.calls.get(kind));
        }
    }
    private static ProjectionSourceSession.Failure[] cleanupKinds() {
        return new ProjectionSourceSession.Failure[] {ProjectionSourceSession.Failure.DETACH, ProjectionSourceSession.Failure.DISPLAY_CLOSE,
                ProjectionSourceSession.Failure.READER_CLOSE, ProjectionSourceSession.Failure.CALLBACK_CLOSE, ProjectionSourceSession.Failure.PROJECTION_STOP};
    }
    private static void before(Fixture f, String first, String last) {
        assertTrue(first + " missing", f.log.contains(first)); assertTrue(last + " missing", f.log.contains(last));
        assertTrue(first + " before " + last + ": " + f.log, f.log.indexOf(first) < f.log.indexOf(last));
    }
    private static void await(CountDownLatch value) {
        try { assertTrue(value.await(2, TimeUnit.SECONDS)); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }

    @Test public void invalidConstructionDoesNotStartOrOwnEndpoint() {
        Fixture f = new Fixture();
        assertThrows(IllegalArgumentException.class, () -> new ProjectionSourceSession<>(0, 2, f.serial, f.endpoint, () -> 0, () -> { }));
        assertThrows(NullPointerException.class, () -> new ProjectionSourceSession<Raw>(3, 2, null, f.endpoint, () -> 0, () -> { }));
        assertEquals(0, f.endpoint.starts); assertEquals(0, f.serial.size());
    }
    @Test public void startIsQueuedAndCannotCreateAnotherGeneration() {
        Fixture f = new Fixture(); f.ready(); assertEquals(1, f.endpoint.starts);
        assertThrows(IllegalStateException.class, f.source::start); f.stop(); f.source.close(); f.serial.drain();
        assertEquals(1, f.endpoint.starts); assertEquals(1, f.invalidations); f.cleanupOnce(); assertTrue(f.source.isDrained());
    }
    @Test public void acquisitionSignalsCoalesceWithoutMintingCallbackFrames() {
        Fixture f = new Fixture(); f.ready();
        for (int i = 0; i < 100; i++) f.endpoint.signal();
        assertEquals(1, f.serial.size()); assertEquals(0, f.endpoint.acquires); f.serial.drain();
        assertEquals(1, f.endpoint.acquires); assertEquals(0, f.source.state().mailbox.accepted); f.stop();
    }
    @Test public void metadataPreservesRawStampAndUsesActualAcquisitionArrival() {
        Fixture f = new Fixture(); f.ready(); Raw raw = f.raw("one"); f.image(raw);
        ProjectionFrameMailbox<Raw>.Read read = f.source.mailbox().take();
        assertSame(raw, read.raw()); assertEquals(1, read.frame.acquisitionSequence);
        assertEquals(456789, read.frame.reportedTimestampNanos); assertEquals(123, read.frame.arrivalUptimeMillis);
        read.close(); f.stop(); assertTrue(f.source.isDrained());
    }
    @Test public void nullAcquisitionsNeverReuseConsumedRawImages() {
        Fixture f = new Fixture(); f.ready(); Raw raw = f.raw("one"); f.image(raw);
        f.source.mailbox().take().close(); f.endpoint.signal(); f.serial.drain();
        assertNull(f.source.mailbox().take()); assertEquals(1, f.source.state().acquisitionSequence); f.stop();
    }
    @Test public void closeBeforeStartInvalidatesAndNeverCreatesDisplay() {
        Fixture f = new Fixture(); f.stop(); assertEquals(0, f.endpoint.starts);
        assertThrows(IllegalStateException.class, f.source::start); f.cleanupOnce(); assertTrue(f.source.isDrained());
    }
    @Test public void heldBorrowPostponesReaderCleanupButNotProducerFence() {
        Fixture f = new Fixture(); f.ready(); Raw one = f.raw("one"), two = f.raw("two"); f.image(one);
        ProjectionFrameMailbox<Raw>.Read read = f.source.mailbox().take(); f.image(two); f.stop();
        assertTrue(f.source.state().producerFenced); assertFalse(f.source.state().rawRetired);
        assertEquals(1, two.closes); assertEquals(0, one.closes); assertSame(one, read.raw());
        assertFalse(f.endpoint.calls.containsKey(ProjectionSourceSession.Failure.READER_CLOSE));
        read.close(); f.cleanupOnce(); assertTrue(f.source.isDrained());
        before(f, "invalidate.return", "DETACH"); before(f, "raw.one", "READER_CLOSE"); before(f, "raw.two", "READER_CLOSE");
    }
    @Test public void stopDuringAcquisitionClosesUnofferedImageBeforeReader() throws Exception {
        Fixture f = new Fixture(); f.ready(); Raw raw = f.raw("unoffered"); f.endpoint.give(raw, 789);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        f.endpoint.onAcquire = () -> { entered.countDown(); await(release); };
        f.endpoint.signal(); AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> { try { f.serial.next(); } catch (Throwable error) { failure.set(error); } }); worker.start();
        try { await(entered); f.source.close(); assertFalse(f.source.state().producerFenced); assertEquals(0, raw.closes); }
        finally { release.countDown(); worker.join(2000); }
        assertFalse(worker.isAlive()); assertNull(failure.get()); f.serial.drain();
        assertEquals(0, f.source.state().mailbox.accepted); assertEquals(1, raw.closes); f.cleanupOnce(); assertTrue(f.source.isDrained());
        before(f, "raw.unoffered", "DETACH"); before(f, "raw.unoffered", "READER_CLOSE");
    }
    @Test public void revokeBeforeStartupReturnsRetiresItsLateCreatedPartials() {
        Fixture f = new Fixture(); f.endpoint.onStart = () -> { f.endpoint.callbacks.revoked(); f.log.add("late.display.return"); };
        f.source.start(); f.serial.drain(); f.cleanupOnce(); assertEquals(1, f.endpoint.starts); assertTrue(f.source.isDrained());
        before(f, "late.display.return", "DISPLAY_CLOSE");
    }
    @Test public void partialStartupFailureIsNotSuccessfulDrainAfterCleanup() {
        Fixture f = new Fixture(); f.endpoint.fail.add(ProjectionSourceSession.Failure.START); f.source.start(); f.serial.drain();
        f.cleanupOnce(); assertTrue(f.source.state().cleanupCompleted); assertTrue(f.source.state().failed()); assertFalse(f.source.isDrained());
        assertEquals(Long.valueOf(1), f.source.state().failures.get(ProjectionSourceSession.Failure.START));
    }
    @Test public void acquisitionExceptionStopsProducerWithoutRetryLoop() {
        Fixture f = new Fixture(); f.ready(); f.endpoint.fail.add(ProjectionSourceSession.Failure.ACQUIRE); f.endpoint.signal(); f.serial.drain();
        f.endpoint.signal(); f.serial.drain(); assertEquals(1, f.endpoint.acquires); f.cleanupOnce(); assertFalse(f.source.isDrained());
    }
    @Test public void invalidRawTimestampClosesTheUnofferedResource() {
        Fixture f = new Fixture(); f.ready(); Raw raw = f.raw("badstamp"); f.endpoint.give(raw, -2); f.endpoint.signal(); f.serial.drain();
        assertEquals(1, raw.closes); assertEquals(0, f.source.state().mailbox.accepted); assertTrue(f.source.state().failed()); f.cleanupOnce();
    }
    @Test public void failedArrivalClockClosesRawWithoutInventedTimestamp() {
        Fixture f = new Fixture(); f.ready(); f.clockFails = true; Raw raw = f.raw("clock"); f.image(raw);
        assertEquals(1, raw.closes); assertEquals(0, f.source.state().mailbox.accepted);
        assertEquals(Long.valueOf(1), f.source.state().failures.get(ProjectionSourceSession.Failure.CLOCK)); assertFalse(f.source.isDrained());
    }
    @Test public void unofferedRawCleanupFailureStillAttemptsEndpointRecovery() {
        Fixture f = new Fixture(); f.ready(); Raw raw = f.raw("unoffered"); raw.failClose = true;
        f.endpoint.onAcquire = () -> f.endpoint.callbacks.revoked(); f.image(raw);
        assertEquals(1, raw.closes); assertEquals(Long.valueOf(1), f.source.state().failures.get(ProjectionSourceSession.Failure.RAW_CLOSE));
        f.cleanupOnce(); assertTrue(f.source.state().cleanupCompleted); assertFalse(f.source.isDrained());
    }
    @Test public void borrowedRawCleanupFailureCannotBecomeSuccessfulSourceDrain() {
        Fixture f = new Fixture(); f.ready(); Raw raw = f.raw("borrowed"); raw.failClose = true; f.image(raw);
        ProjectionFrameMailbox<Raw>.Read read = f.source.mailbox().take(); f.stop(); read.close();
        assertEquals(1, f.source.state().mailbox.resourceFailures); f.cleanupOnce(); assertFalse(f.source.isDrained());
    }
    @Test public void rejectedStopPostCannotPretendProducerFencedOrReaderClosed() {
        Fixture f = new Fixture(); f.ready(); f.serial.accept = false; f.source.close();
        assertEquals(1, f.invalidations); assertFalse(f.source.available()); assertFalse(f.source.state().producerFenced);
        assertFalse(f.source.state().cleanupCompleted); assertTrue(f.endpoint.calls.keySet().stream().noneMatch(k -> k == ProjectionSourceSession.Failure.READER_CLOSE));
        assertEquals(Long.valueOf(1), f.source.state().failures.get(ProjectionSourceSession.Failure.POST)); assertFalse(f.source.isDrained());
    }
    @Test public void existingQueuedActionCanFenceAfterRejectedStopButFailureStaysLatched() {
        Fixture f = new Fixture(); f.ready(); f.endpoint.signal(); f.serial.accept = false; f.source.close(); f.serial.drain();
        assertEquals(0, f.endpoint.acquires); f.cleanupOnce(); assertTrue(f.source.state().producerFenced);
        assertTrue(f.source.state().cleanupCompleted); assertFalse(f.source.isDrained());
    }
    @Test public void eachEndpointCleanupFailurePreservesAllOtherCleanupAttempts() {
        for (ProjectionSourceSession.Failure kind : cleanupKinds()) {
            Fixture f = new Fixture(); f.ready(); f.endpoint.fail.add(kind); f.stop(); f.source.close(); f.serial.drain();
            f.cleanupOnce(); assertEquals(Long.valueOf(1), f.source.state().failures.get(kind));
            assertTrue(f.source.state().cleanupCompleted); assertFalse(f.source.isDrained());
        }
    }
    @Test public void throwingInvalidatorCannotHideFailureBehindResourceCleanup() {
        Fixture f = new Fixture(); f.ready(); f.onInvalidate = () -> { throw new AssertionError("invalidate"); }; f.stop();
        assertTrue(f.source.state().invalidationCompleted); f.cleanupOnce(); assertFalse(f.source.isDrained());
        assertEquals(Long.valueOf(1), f.source.state().failures.get(ProjectionSourceSession.Failure.INVALIDATE));
    }
    @Test public void prematureExternalMailboxClosureCannotCloseReaderBeforeProducerFence() {
        Fixture f = new Fixture(); f.ready(); f.source.mailbox().close();
        assertTrue(f.source.state().rawRetired); assertFalse(f.source.state().producerFenced);
        assertFalse(f.endpoint.calls.containsKey(ProjectionSourceSession.Failure.READER_CLOSE)); f.stop(); f.cleanupOnce(); assertTrue(f.source.isDrained());
    }
    @Test public void lateOldCallbacksCannotRetireAnotherSourceGeneration() {
        Fixture old = new Fixture(); old.ready(); old.stop(); Fixture fresh = new Fixture(); fresh.ready();
        old.endpoint.callbacks.imageAvailable(); old.endpoint.callbacks.resized(99, 99); old.endpoint.callbacks.revoked(); old.serial.drain();
        assertTrue(fresh.source.available()); assertEquals(0, fresh.invalidations); assertEquals(0, old.endpoint.acquires);
        old.cleanupOnce(); fresh.stop(); fresh.cleanupOnce();
    }
    @Test public void resizeToDifferentContentInvalidatesInsteadOfReusingGeometry() {
        Fixture f = new Fixture(); f.ready(); f.endpoint.callbacks.resized(3, 2); assertTrue(f.source.available());
        f.endpoint.callbacks.resized(2, 3); assertFalse(f.source.available()); assertEquals(1, f.invalidations); f.serial.drain();
        f.cleanupOnce(); assertTrue(f.source.isDrained());
    }
    @Test public void liveRawAliasRefusalCannotDisposeTheBorrowedImage() {
        Fixture f = new Fixture(); f.ready(); Raw raw = f.raw("alias"); f.image(raw);
        ProjectionFrameMailbox<Raw>.Read read = f.source.mailbox().take(); f.image(raw);
        assertEquals(0, raw.closes); assertSame(raw, read.raw()); assertEquals(1, f.source.state().mailbox.protocolFailures);
        assertTrue(f.source.state().producerFenced); assertFalse(f.source.state().cleanupCompleted); read.close();
        assertEquals(1, raw.closes); f.cleanupOnce(); assertFalse(f.source.isDrained());
    }
    @Test public void producerFenceWaitsForActualInvalidationReturn() throws Exception {
        Fixture f = new Fixture(); f.ready(); CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        f.onInvalidate = () -> { entered.countDown(); await(release); }; f.endpoint.signal();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread stopper = new Thread(() -> { try { f.source.close(); } catch (Throwable error) { failure.set(error); } }); stopper.start();
        try { await(entered); f.serial.next(); assertFalse(f.source.state().producerFenced); assertFalse(f.source.state().invalidationCompleted); }
        finally { release.countDown(); stopper.join(2000); }
        assertFalse(stopper.isAlive()); assertNull(failure.get()); f.serial.drain(); f.cleanupOnce(); assertTrue(f.source.isDrained());
        before(f, "invalidate.return", "DETACH");
    }
    @Test public void cleanupReentryCannotDisposeTheEndpointTwice() {
        Fixture f = new Fixture(); f.ready(); f.endpoint.onDisplayClose = () -> { f.source.close(); f.endpoint.callbacks.imageAvailable(); };
        f.stop(); f.cleanupOnce(); assertEquals(1, f.invalidations); assertTrue(f.source.isDrained());
    }
    @Test public void imageCallbackBeforeStartupReturnIsAcquiredOnlyAfterStartupCompletes() {
        Fixture f = new Fixture(); Raw raw = f.raw("startup"); f.endpoint.give(raw, 77);
        f.endpoint.onStart = () -> { f.endpoint.signal(); assertEquals(0, f.endpoint.acquires); };
        f.ready(); assertEquals(1, f.endpoint.acquires); before(f, "start.return", "acquire");
        f.source.mailbox().take().close(); f.stop(); assertTrue(f.source.isDrained());
    }
}
