package com.subhub.app.penance;

import static org.junit.Assert.*;
import org.junit.Test;
import java.util.concurrent.atomic.AtomicInteger;

public final class ViewportDwellEpisodeTest {
    @Test public void elapsedGapsAndIdentityChurnRemainOneStationaryEpisode() {
        ViewportDwellEpisode episode = new ViewportDwellEpisode();
        assertNull(episode.observe(true, 100, 3000));
        assertNull(episode.observe(false, 2500, 3000));
        assertNull(episode.observe(false, 5000, 3000));
        ViewportDwellEpisode.Ticket ticket = episode.observe(true, 5001, 3000);
        assertNotNull(ticket); assertTrue(episode.claim(ticket, () -> true));
        for (int i = 0; i < 100; i++) assertNull(episode.observe(true, 6000 + i, 3000));
        assertNull(episode.observe(false, 10000, 3000));
        assertNull(episode.observe(true, 20000, 3000));
    }
    @Test public void exactThresholdHasOnlyOnePendingAndOneAdmission() {
        ViewportDwellEpisode episode = new ViewportDwellEpisode();
        episode.observe(true, 0, 3000); assertNull(episode.observe(true, 2999, 3000));
        ViewportDwellEpisode.Ticket ticket = episode.observe(true, 3000, 3000);
        assertNotNull(ticket); assertNull(episode.observe(true, 3001, 3000));
        AtomicInteger admissions = new AtomicInteger();
        assertTrue(episode.claim(ticket, () -> { admissions.incrementAndGet(); return true; }));
        assertFalse(episode.claim(ticket, () -> { admissions.incrementAndGet(); return true; }));
        assertEquals(1, admissions.get());
    }
    @Test public void definitePreAdmissionRejectionCanRetryButSpentAdmissionCannot() {
        ViewportDwellEpisode episode = new ViewportDwellEpisode(); episode.observe(true, 0, 1);
        ViewportDwellEpisode.Ticket rejected = episode.observe(true, 1, 1);
        assertFalse(episode.claim(rejected, () -> false)); episode.release(rejected);
        ViewportDwellEpisode.Ticket retry = episode.observe(true, 2, 1);
        assertNotNull(retry); assertNotSame(rejected, retry);
        assertFalse(episode.claim(rejected, () -> true)); assertTrue(episode.claim(retry, () -> true));
        episode.release(retry); assertNull(episode.observe(true, 3000, 1));
    }
    @Test public void resetRevokesPendingWithoutClearingOrChargingTheSuccessor() {
        ViewportDwellEpisode episode = new ViewportDwellEpisode(); episode.observe(true, 0, 1);
        ViewportDwellEpisode.Ticket old = episode.observe(true, 1, 1); episode.reset();
        assertNull(episode.observe(true, 2, 1));
        ViewportDwellEpisode.Ticket current = episode.observe(true, 3, 1);
        episode.release(old); assertFalse(episode.claim(old, () -> true));
        assertTrue(episode.current(current)); assertTrue(episode.claim(current, () -> true));
    }
    @Test public void otherOwnerAndReentrantRevocationCannotAdmit() {
        ViewportDwellEpisode first = new ViewportDwellEpisode(), other = new ViewportDwellEpisode();
        first.observe(true, 0, 1); other.observe(true, 0, 1);
        ViewportDwellEpisode.Ticket ticket = first.observe(true, 1, 1);
        other.observe(true, 1, 1); assertFalse(other.claim(ticket, () -> true));
        assertFalse(first.claim(ticket, () -> { first.reset(); return true; }));
        assertFalse(first.current(ticket));
    }
    @Test public void observationOrderAndOverflowDoNotInventElapsedTime() {
        ViewportDwellEpisode episode = new ViewportDwellEpisode(); episode.observe(true, 100, 3000);
        assertNull(episode.observe(true, 99, 1));
        assertNull(episode.observe(true, 3099, 3000));
        assertNotNull(episode.observe(true, 3100, 3000)); episode.reset();
        episode.observe(true, Long.MAX_VALUE - 2, 3);
        assertNull(episode.observe(true, Long.MAX_VALUE, 3));
        assertThrows(IllegalArgumentException.class, () -> episode.observe(true, -1, 1));
    }
    @Test public void emptyStartupNeverCreatesAnEpisodeAndThresholdRemainsLive() {
        ViewportDwellEpisode episode = new ViewportDwellEpisode();
        assertNull(episode.observe(false, 3000, 1)); assertNull(episode.observe(true, 4000, 3000));
        assertNull(episode.observe(true, 6000, 3000)); assertNotNull(episode.observe(true, 6001, 1000));
    }
}
