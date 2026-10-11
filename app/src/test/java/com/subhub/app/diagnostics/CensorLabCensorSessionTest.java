package com.subhub.app.diagnostics;

import static org.junit.Assert.*;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class CensorLabCensorSessionTest {
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
