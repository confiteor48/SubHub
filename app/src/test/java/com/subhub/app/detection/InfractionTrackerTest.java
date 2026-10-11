package com.subhub.app.detection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.subhub.app.penance.DwellInfractionTracker;

import org.junit.Test;

import java.util.List;

public final class InfractionTrackerTest {
    @Test public void lingerChargesOncePerStationaryScreenEpisode() {
        DwellInfractionTracker tracker = new DwellInfractionTracker();
        TrackedObject stable = track(7, new BBox(100, 100, 80, 80));
        assertEquals(0, tracker.update(List.of(stable), 1_000L, 5_000L));
        assertEquals(0, tracker.update(List.of(stable), 5_999L, 5_000L));
        assertEquals(1, tracker.update(List.of(stable), 6_000L, 5_000L));
        assertEquals(0, tracker.update(List.of(stable), 20_000L, 5_000L));
        TrackedObject replacement = track(8, new BBox(100, 100, 80, 80));
        assertEquals(0, tracker.update(List.of(replacement), 30_000L, 5_000L));
        tracker.onScroll();
        assertEquals(0, tracker.update(List.of(stable), 31_000L, 5_000L));
        assertEquals(1, tracker.update(List.of(stable), 36_000L, 5_000L));
    }

    @Test public void trackedMovementStartsANewDwellWindow() {
        DwellInfractionTracker tracker = new DwellInfractionTracker();
        TrackedObject original = track(1, new BBox(20, 20, 80, 80));
        tracker.update(List.of(original), 0L, 5_000L);
        original.update(detection(new BBox(260, 260, 80, 80)),
                new BBox(260, 260, 80, 80), 240, 240, 1L);
        assertEquals(0, tracker.update(List.of(original), 5_000L, 5_000L));
        TrackedObject replacement = track(2, new BBox(260, 260, 80, 80));
        assertEquals(1, tracker.update(List.of(replacement), 10_000L, 5_000L));
    }

    @Test public void changingVideoDetectionsShareOneUnscrolledDwellWindow() {
        DwellInfractionTracker tracker = new DwellInfractionTracker();

        assertEquals(0, tracker.update(List.of(
                track(1, new BBox(20, 20, 80, 80))), 1_000L, 5_000L, false));
        assertEquals(0, tracker.update(List.of(
                track(2, new BBox(200, 100, 140, 160))), 3_000L, 5_000L, false));
        assertEquals(1, tracker.update(List.of(
                track(3, new BBox(80, 240, 220, 120))), 6_000L, 5_000L, false));
        assertEquals(0, tracker.update(List.of(
                track(4, new BBox(300, 300, 100, 100))), 12_000L, 5_000L, false));

        tracker.onScroll();
        assertEquals(0, tracker.update(List.of(
                track(5, new BBox(40, 40, 90, 90))), 13_000L, 5_000L, false));
        assertEquals(1, tracker.update(List.of(
                track(6, new BBox(260, 120, 180, 120))), 18_000L, 5_000L, false));
    }

    private static TrackedObject track(int id, BBox box) {
        return new TrackedObject(id, detection(box), 0L);
    }

    private static Detection detection(BBox box) {
        return new Detection("TEST", "test", 0.9f, box, true, true);
    }
}
