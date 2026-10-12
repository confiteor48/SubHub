package com.subhub.app.diagnostics;

import static org.junit.Assert.*;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.*;
import org.junit.Test;

public final class CensorLabCensorSessionTest {
    @Test public void normalAdmissionRejectsCapturedLabAndInactiveAba() {
        CensorLabCensorSession.Stamp old = CensorLabCensorSession.stamp(); AtomicInteger calls = new AtomicInteger();
        assertEquals(Integer.valueOf(1), CensorLabCensorSession.withCurrentNormal(old, calls::incrementAndGet, -1));
        try (CensorLabCensorSession.Lease ignored = CensorLabCensorSession.acquire()) {
            assertEquals(Integer.valueOf(-1), CensorLabCensorSession.withCurrentNormal(CensorLabCensorSession.stamp(), calls::incrementAndGet, -1));
        }
        assertEquals(Integer.valueOf(-1), CensorLabCensorSession.withCurrentNormal(old, calls::incrementAndGet, -1));
        assertEquals(Integer.valueOf(-1), CensorLabCensorSession.withCurrentNormal(null, calls::incrementAndGet, -1));
        assertEquals(1, calls.get());
    }
    @Test public void nestedAdmissionAndReentrantLabTransitionAreRejectedAndFinallyReleasesTheGate() {
        CensorLabCensorSession.Stamp normal = CensorLabCensorSession.stamp();
        assertThrows(IllegalStateException.class, () -> CensorLabCensorSession.withCurrentNormal(normal,
                () -> CensorLabCensorSession.withCurrentNormal(normal, () -> true, false), false));
        assertThrows(IllegalStateException.class, () -> CensorLabCensorSession.withCurrentNormal(normal,
                CensorLabCensorSession::acquire, null));
        assertTrue(normal.isCurrent());
        assertThrows(IllegalArgumentException.class, () -> CensorLabCensorSession.withCurrentNormal(normal,
                () -> { throw new IllegalArgumentException("fixture"); }, false));
        assertTrue(CensorLabCensorSession.withCurrentNormal(normal, () -> true, false));
        try (CensorLabCensorSession.Lease ignored = CensorLabCensorSession.acquire()) { assertTrue(CensorLabCensorSession.isActive()); }
    }
    @Test public void transitionLinearizesAfterAnAlreadyAdmittedShortMutation() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch admitted = new CountDownLatch(1), release = new CountDownLatch(1), starting = new CountDownLatch(1);
        CensorLabCensorSession.Stamp normal = CensorLabCensorSession.stamp(); CensorLabCensorSession.Lease lease = null;
        try {
            Future<Boolean> mutation = pool.submit(() -> CensorLabCensorSession.withCurrentNormal(normal, () -> {
                admitted.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); } catch (InterruptedException error) { throw new AssertionError(error); }
                assertTrue(normal.isCurrent()); return true;
            }, false));
            assertTrue(admitted.await(5, TimeUnit.SECONDS));
            Future<CensorLabCensorSession.Lease> transition = pool.submit(() -> { starting.countDown(); return CensorLabCensorSession.acquire(); });
            assertTrue(starting.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> transition.get(100, TimeUnit.MILLISECONDS));
            release.countDown(); assertTrue(mutation.get(5, TimeUnit.SECONDS)); lease = transition.get(5, TimeUnit.SECONDS);
            assertFalse(normal.isCurrent()); assertTrue(CensorLabCensorSession.isActive());
        } finally { release.countDown(); if (lease != null) lease.close(); pool.shutdownNow(); }
    }
    @Test public void transitionObserversAreOutsideTheAuthorityLock() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor(); AtomicInteger observations = new AtomicInteger();
        try (CensorLabCensorSession.Subscription ignored = CensorLabCensorSession.observe(() -> {
            try {
                boolean normal = pool.submit(() -> CensorLabCensorSession.withCurrentNormal(
                        CensorLabCensorSession.stamp(), () -> true, false)).get(5, TimeUnit.SECONDS);
                assertEquals(!CensorLabCensorSession.isActive(), normal); observations.incrementAndGet();
            } catch (Exception error) { throw new AssertionError(error); }
        })) {
            try (CensorLabCensorSession.Lease lease = CensorLabCensorSession.acquire()) { assertEquals(1, observations.get()); }
            assertEquals(2, observations.get());
        } finally { pool.shutdownNow(); }
    }

    @Test public void stampsFenceInactiveAbaAndRecordingReplacementWithoutExposingCloseAuthority() {
        CensorLabCensorSession.Stamp inactive = CensorLabCensorSession.stamp();
        assertFalse(inactive.active); assertTrue(inactive.isCurrent());
        CensorLabCensorSession.Stamp first;
        try (CensorLabCensorSession.Lease ignored = CensorLabCensorSession.acquire()) {
            first = CensorLabCensorSession.stamp(); assertTrue(first.active); assertTrue(first.isCurrent());
            assertFalse(inactive.isCurrent());
        }
        assertFalse(first.isCurrent()); assertFalse(inactive.isCurrent());
        try (CensorLabCensorSession.Lease ignored = CensorLabCensorSession.acquire()) {
            assertFalse(first.isCurrent()); assertNotSame(first, CensorLabCensorSession.stamp());
        }
        assertFalse(first.isCurrent()); assertFalse(inactive.isCurrent());
    }
    @Test public void ownedLifetimeNotifiesStartAndStopWithoutPersisting() {
        AtomicInteger changes = new AtomicInteger();
        try (CensorLabCensorSession.Subscription ignored =
                     CensorLabCensorSession.observe(changes::incrementAndGet)) {
            assertFalse(CensorLabCensorSession.isActive());
            try (CensorLabCensorSession.Lease lease = CensorLabCensorSession.acquire()) {
                assertTrue(CensorLabCensorSession.isActive());
                assertEquals(1, changes.get());
            }
            assertFalse(CensorLabCensorSession.isActive());
            assertEquals(2, changes.get());
        }
    }

    @Test public void duplicateOwnerIsRejectedAndOldCloseCannotStopNewSession() {
        CensorLabCensorSession.Lease old = CensorLabCensorSession.acquire();
        try {
            assertThrows(IllegalStateException.class, CensorLabCensorSession::acquire);
        } finally { old.close(); }
        try (CensorLabCensorSession.Lease fresh = CensorLabCensorSession.acquire()) {
            old.close();
            assertTrue(CensorLabCensorSession.isActive());
        }
        assertFalse(CensorLabCensorSession.isActive());
    }

    @Test public void disconnectedObserverIsNotRetained() {
        AtomicInteger changes = new AtomicInteger();
        CensorLabCensorSession.Subscription subscription =
                CensorLabCensorSession.observe(changes::incrementAndGet);
        subscription.close();
        subscription.close();
        try (CensorLabCensorSession.Lease ignored = CensorLabCensorSession.acquire()) {
            assertEquals(0, changes.get());
        }
        assertEquals(0, changes.get());
    }

    @Test public void labOwnedFrameRemainsNonBillableAfterSessionEnds() {
        assertTrue(CensorLabCensorSession.allowsNormalCensorEffects(false));
        try (CensorLabCensorSession.Lease ignored = CensorLabCensorSession.acquire()) {
            assertFalse(CensorLabCensorSession.allowsNormalCensorEffects(false));
            assertFalse(CensorLabCensorSession.allowsNormalCensorEffects(true));
        }
        assertFalse(CensorLabCensorSession.allowsNormalCensorEffects(true));
        assertTrue(CensorLabCensorSession.allowsNormalCensorEffects(false));
    }

    @Test public void failedObserverCannotLeakLeaseOrBlockOtherObservers() {
        AtomicInteger changes = new AtomicInteger();
        try (CensorLabCensorSession.Subscription broken = CensorLabCensorSession.observe(() -> {
                    throw new IllegalStateException("Fixture observer disconnected");
                });
                CensorLabCensorSession.Subscription healthy =
                        CensorLabCensorSession.observe(changes::incrementAndGet)) {
            try (CensorLabCensorSession.Lease ignored = CensorLabCensorSession.acquire()) {
                assertTrue(CensorLabCensorSession.isActive());
                assertEquals(1, changes.get());
            }
            assertFalse(CensorLabCensorSession.isActive());
            assertEquals(2, changes.get());
        }
    }
}
