package com.subhub.app.censor.engine;

import static org.junit.Assert.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

public class ProjectionFrameMailboxTest {
    private static final class Raw {
        int closes;
        void close() { assertEquals("raw image closed twice", 0, closes++); }
    }
    private final ProjectionFrameMailbox<Raw> box = new ProjectionFrameMailbox<>();

    private ProjectionFrameMailbox.Frame<Raw> frame(Raw raw, long sequence) {
        return box.frame(raw, sequence, 9_000_000_000L, 120, raw::close);
    }

    @Test public void newestReplacesPendingAndIsConsumedOnlyOnce() {
        Raw old = new Raw(), latest = new Raw();
        assertEquals(ProjectionFrameMailbox.Offer.ACCEPTED, box.offer(frame(old, 1)));
        assertEquals(ProjectionFrameMailbox.Offer.ACCEPTED, box.offer(frame(latest, 2)));
        assertEquals(1, old.closes); assertEquals(0, latest.closes);
        ProjectionFrameMailbox<Raw>.Read read = box.take();
        assertSame(latest, read.raw()); assertEquals(2, read.frame.acquisitionSequence);
        read.close(); assertNull(box.take()); assertEquals(1, latest.closes);
        assertEquals(2, box.state().accepted); assertEquals(1, box.state().replaced);
        box.close(); assertTrue(box.isDrained());
    }

    @Test public void busyPreparationKeepsOnlyNewestWaitingImage() {
        Raw first = new Raw(), second = new Raw(), third = new Raw();
        box.offer(frame(first, 1)); ProjectionFrameMailbox<Raw>.Read read = box.take();
        box.offer(frame(second, 2)); box.offer(frame(third, 3));
        assertNull(box.take()); assertSame(first, read.raw());
        assertEquals(1, second.closes); assertEquals(0, first.closes); assertEquals(0, third.closes);
        assertEquals(1, box.state().pending); assertEquals(1, box.state().borrowed);
        read.close(); ProjectionFrameMailbox<Raw>.Read next = box.take();
        assertSame(third, next.raw()); next.close(); box.close(); assertTrue(box.isDrained());
    }

    @Test public void stopCannotCloseBorrowedImageAndRejectsNewArrival() {
        Raw borrowed = new Raw(), waiting = new Raw(), late = new Raw();
        box.offer(frame(borrowed, 1)); ProjectionFrameMailbox<Raw>.Read read = box.take();
        box.offer(frame(waiting, 2)); box.close();
        assertFalse(box.isDrained()); assertSame(borrowed, read.raw()); assertEquals(0, borrowed.closes);
        assertEquals(1, waiting.closes); assertNull(box.take());
        assertEquals(ProjectionFrameMailbox.Offer.CLOSED, box.offer(frame(late, 3)));
        assertEquals(1, late.closes); read.close(); assertTrue(box.isDrained());
    }

    @Test public void repeatedEnvelopeCannotDisposePendingOrBorrowedImage() {
        Raw raw = new Raw(); ProjectionFrameMailbox.Frame<Raw> frame = frame(raw, 1);
        box.offer(frame); frame.close(); assertEquals(0, raw.closes);
        assertEquals(ProjectionFrameMailbox.Offer.DUPLICATE, box.offer(frame));
        ProjectionFrameMailbox<Raw>.Read read = box.take();
        assertEquals(ProjectionFrameMailbox.Offer.DUPLICATE, box.offer(frame));
        frame.close(); assertEquals(0, raw.closes); read.close(); read.close(); frame.close();
        assertEquals(1, raw.closes); box.close(); box.close(); assertTrue(box.isDrained());
    }

    @Test public void sequenceRollbackAndEqualSequenceAreNotNewSources() {
        Raw first = new Raw(), same = new Raw(), older = new Raw(), newer = new Raw();
        box.offer(frame(first, 4)); box.take().close();
        assertEquals(ProjectionFrameMailbox.Offer.STALE, box.offer(frame(same, 4)));
        assertEquals(ProjectionFrameMailbox.Offer.STALE, box.offer(frame(older, 3)));
        assertEquals(1, same.closes); assertEquals(1, older.closes); assertNull(box.take());
        assertEquals(ProjectionFrameMailbox.Offer.ACCEPTED, box.offer(frame(newer, 5)));
        box.take().close(); box.close(); assertTrue(box.isDrained());
    }

    @Test public void rawTimestampIsPreservedWithoutClockConversionOrNoveltyAuthority() {
        Raw one = new Raw(), two = new Raw();
        box.offer(box.frame(one, 1, Long.MAX_VALUE, 10, one::close));
        ProjectionFrameMailbox<Raw>.Read read = box.take();
        assertEquals(Long.MAX_VALUE, read.frame.reportedTimestampNanos);
        assertEquals(10, read.frame.arrivalUptimeMillis); read.close();
        box.offer(box.frame(two, 2, Long.MAX_VALUE, 11, two::close));
        assertNotNull(read = box.take()); assertSame(two, read.raw()); read.close();
        box.close(); assertTrue(box.isDrained());
    }

    @Test public void differentGenerationWithIdenticalNumbersIsRefused() {
        ProjectionFrameMailbox<Raw> other = new ProjectionFrameMailbox<>(); Raw raw = new Raw();
        assertEquals(ProjectionFrameMailbox.Offer.FOREIGN,
                box.offer(other.frame(raw, 1, 5, 120, raw::close)));
        assertEquals(1, raw.closes); assertNull(box.take()); assertNull(other.take());
        box.close(); other.close(); assertTrue(box.isDrained()); assertTrue(other.isDrained());
    }

    @Test public void liveRawAliasDoesNotCloseAnExistingLeaseAndPoisonsAdmission() {
        Raw raw = new Raw(), next = new Raw();
        box.offer(frame(raw, 1)); ProjectionFrameMailbox<Raw>.Read read = box.take();
        ProjectionFrameMailbox.Frame<Raw> alias = frame(raw, 2);
        assertEquals(ProjectionFrameMailbox.Offer.LIVE_ALIAS, box.offer(alias)); alias.close();
        assertEquals(0, raw.closes); assertSame(raw, read.raw()); assertEquals(1, box.state().protocolFailures);
        assertEquals(ProjectionFrameMailbox.Offer.FAILED, box.offer(frame(next, 3)));
        assertEquals(1, next.closes); read.close(); box.close();
        assertEquals(1, raw.closes); assertFalse(box.isDrained());
    }

    @Test public void producerCanDiscardOnlyBeforeTransfer() {
        Raw raw = new Raw(); ProjectionFrameMailbox.Frame<Raw> frame = frame(raw, 1);
        frame.close(); frame.close(); assertEquals(1, raw.closes);
        assertEquals(ProjectionFrameMailbox.Offer.DUPLICATE, box.offer(frame));
        assertNull(box.take()); box.close(); assertTrue(box.isDrained());
    }

    @Test public void unofferedFrameAndAcquisitionRemainOutsideMailboxDrain() {
        Raw raw = new Raw(); ProjectionFrameMailbox.Frame<Raw> unoffered = frame(raw, 1);
        box.close(); assertTrue("no managed resource was admitted", box.isDrained());
        assertEquals("producer still owns its image", 0, raw.closes);
        assertEquals(ProjectionFrameMailbox.Offer.CLOSED, box.offer(unoffered));
        assertEquals(1, raw.closes); assertTrue(box.isDrained());
    }

    @Test public void cleanupFailureCannotBecomeEmptySuccessfulDrain() {
        Raw broken = new Raw(), pending = new Raw(), late = new Raw();
        box.offer(box.frame(broken, 1, -1, 1, () -> { broken.close(); throw new IllegalStateException("cleanup"); }));
        box.offer(frame(pending, 2)); assertEquals(1, box.state().resourceFailures);
        assertNull(box.take()); assertEquals(ProjectionFrameMailbox.Offer.FAILED, box.offer(frame(late, 3)));
        box.close(); assertEquals(1, pending.closes); assertEquals(1, late.closes);
        assertEquals(0, box.state().pending); assertEquals(0, box.state().disposing); assertFalse(box.isDrained());
    }

    @Test public void leaseCleanupErrorAlsoPoisonsSubsequentAdmission() {
        Raw raw = new Raw();
        box.offer(box.frame(raw, 1, -1, 1, () -> { raw.close(); throw new AssertionError("native disposal"); }));
        ProjectionFrameMailbox<Raw>.Read read = box.take(); read.close(); read.close();
        assertEquals(1, raw.closes); assertEquals(1, box.state().resourceFailures);
        assertNull(box.take()); box.close(); assertFalse(box.isDrained());
    }

    @Test public void disposalIsOutsideLockAndRetainsTerminalOwnership() {
        Raw raw = new Raw();
        box.offer(box.frame(raw, 1, -1, 0, () -> {
            assertFalse(Thread.holdsLock(box)); assertEquals(1, box.state().disposing);
            box.close(); assertFalse(box.isDrained()); assertNull(box.take()); raw.close();
        }));
        ProjectionFrameMailbox<Raw>.Read read = box.take(); read.close();
        assertTrue(box.isDrained()); assertEquals(1, raw.closes);
    }

    @Test public void producerReentryCannotLoseNewerPendingImage() {
        Raw first = new Raw(), middle = new Raw(), latest = new Raw();
        box.offer(box.frame(first, 1, -1, 0, () -> {
            assertFalse(Thread.holdsLock(box)); rawClose(first);
            assertEquals(ProjectionFrameMailbox.Offer.ACCEPTED, box.offer(frame(latest, 3)));
            assertNull("cleanup must finish before preparing", box.take());
        }));
        box.offer(frame(middle, 2));
        assertEquals(1, middle.closes); ProjectionFrameMailbox<Raw>.Read read = box.take();
        assertSame(latest, read.raw()); read.close(); box.close(); assertTrue(box.isDrained());
    }

    @Test public void blockingCleanupIsNotReportedAsDrain() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<Throwable> failed = new AtomicReference<>(); Raw raw = new Raw();
        box.offer(box.frame(raw, 1, -1, 1, () -> {
            assertFalse(Thread.holdsLock(box)); entered.countDown();
            try { assertTrue(release.await(2, TimeUnit.SECONDS)); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
            raw.close();
        }));
        ProjectionFrameMailbox<Raw>.Read read = box.take();
        Thread worker = new Thread(() -> { try { read.close(); } catch (Throwable failure) { failed.set(failure); } });
        worker.start();
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS)); box.close();
            assertFalse(box.isDrained()); assertEquals(1, box.state().disposing); assertEquals(0, raw.closes);
        } finally { release.countDown(); worker.join(2000); }
        assertFalse(worker.isAlive()); assertNull(failed.get()); assertTrue(box.isDrained());
    }

    @Test public void closedLeaseCannotReadRawPixels() {
        Raw raw = new Raw(); box.offer(frame(raw, 1)); ProjectionFrameMailbox<Raw>.Read read = box.take();
        read.close(); assertThrows(IllegalStateException.class, read::raw); box.close(); assertTrue(box.isDrained());
    }

    @Test public void invalidMetadataDoesNotTakeCallerOwnership() {
        Raw raw = new Raw();
        assertThrows(IllegalArgumentException.class, () -> box.frame(raw, 0, -1, 0, raw::close));
        assertThrows(IllegalArgumentException.class, () -> box.frame(raw, 1, -2, 0, raw::close));
        assertThrows(IllegalArgumentException.class, () -> box.frame(raw, 1, -1, -1, raw::close));
        assertEquals(0, raw.closes); raw.close(); box.close(); assertTrue(box.isDrained());
    }

    private static void rawClose(Raw raw) { raw.close(); }
}
