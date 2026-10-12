package com.subhub.app.penance;

import static org.junit.Assert.*;

import com.subhub.app.detection.BBox;
import java.util.List;
import org.junit.Test;

public final class CensorTapTrackerTest {
    @Test public void partiallyCoveredClickDoesNotProveWhereTheFingerLanded() {
        CensorTapTracker tracker = new CensorTapTracker();
        publish(tracker, List.of(new BBox(100, 200, 100, 100)), 1000L);
        assertFalse(tracker.matchesClick(90, 190, 240, 340, 1000, 2000, 1200L));
    }

    @Test public void likeOrReplyEdgeOverlapDoesNotBecomeACensorTap() {
        CensorTapTracker tracker = new CensorTapTracker();
        publish(tracker, List.of(new BBox(100, 200, 200, 200)), 1000L);
        assertFalse(tracker.matchesClick(280, 240, 360, 360, 1000, 2000, 1200L));
    }

    @Test public void completeUnionCoverageCanProveAClickAcrossSeparateRegions() {
        CensorTapTracker tracker = new CensorTapTracker();
        publish(tracker, List.of(new BBox(100, 200, 50, 100), new BBox(150, 200, 50, 100)), 1000L);
        assertTrue(tracker.matchesClickAt(110, 210, 190, 290, 1100L, 1200L));
    }

    @Test public void evenOneUncoveredPixelRejectsAmbiguousUnionCoverage() {
        CensorTapTracker tracker = new CensorTapTracker();
        publish(tracker, List.of(new BBox(100, 200, 50, 100), new BBox(151, 200, 49, 100)), 1000L);
        assertFalse(tracker.matchesClickAt(110, 210, 190, 290, 1100L, 1200L));
    }

    @Test public void offscreenRegionsAreClippedAndCannotMatchAnOffscreenTarget() {
        CensorTapTracker tracker = new CensorTapTracker();
        publish(tracker, List.of(new BBox(-100, 200, 200, 100)), 1000L);
        assertTrue(tracker.matchesClickAt(0, 210, 90, 290, 1100L, 1200L));
        assertFalse(tracker.matchesClickAt(-10, 210, 90, 290, 1100L, 1200L));
        assertFalse(tracker.matchesPoint(-1f, 250f, 1000, 2000, 1200L));
    }

    @Test public void freshnessUsesBoundedMonotonicDrawTime() {
        CensorTapTracker tracker = new CensorTapTracker();
        publish(tracker, List.of(new BBox(100, 200, 100, 100)), 1000L);
        assertTrue(tracker.matchesClickAt(110, 210, 190, 290, 2400L, 2500L));
        assertFalse(tracker.matchesClickAt(110, 210, 190, 290, 2400L, 2501L));
        assertFalse(tracker.matchesClickAt(110, 210, 190, 290, 900L, 1200L));
        assertFalse(tracker.matchesClickAt(110, 210, 190, 290, 1300L, 1200L));
    }

    @Test public void stationaryRedrawRetainsTheGeometryStartForDelayedClicks() {
        CensorTapTracker tracker = new CensorTapTracker();
        List<BBox> regions = List.of(new BBox(100, 200, 100, 100));
        publish(tracker, regions, 1000L);
        publish(tracker, regions, 2000L);
        assertTrue(tracker.matchesClickAt(110, 210, 190, 290, 1900L, 2050L));
        publish(tracker, List.of(new BBox(100, 100, 100, 100)), 2100L);
        assertFalse(tracker.matchesClickAt(110, 110, 190, 190, 2050L, 2200L));
    }

    @Test public void asynchronousSameCoverageSuspendsBillingButRetainsStationaryClickContinuity() {
        CensorTapTracker tracker = new CensorTapTracker();
        List<BBox> regions = List.of(new BBox(100, 200, 100, 100));
        publish(tracker, regions, 1000L);
        tracker.suspendDisplayed(tracker, tracker.scope(), regions, viewport());
        assertFalse(tracker.matchesPoint(150, 250, 1000, 2000, 1200L));
        assertFalse(tracker.matchesClickAt(110, 210, 190, 290, 1100L, 1200L));
        publish(tracker, regions, 1300L);
        assertTrue(tracker.matchesClickAt(110, 210, 190, 290, 1100L, 1400L));
    }

    @Test public void changedOrEmptyCoalescedPostBreaksPriorGeometryContinuity() {
        for (List<BBox> next : List.of(List.<BBox>of(), List.of(new BBox(100, 100, 100, 100)))) {
            CensorTapTracker tracker = new CensorTapTracker();
            List<BBox> original = List.of(new BBox(100, 200, 100, 100));
            publish(tracker, original, 1000L);
            tracker.suspendDisplayed(tracker, tracker.scope(), next, viewport());
            assertFalse(tracker.matchesPoint(150, 250, 1000, 2000, 1200L));
            publish(tracker, original, 1300L);
            assertFalse(tracker.matchesClickAt(110, 210, 190, 290, 1100L, 1400L));
            assertTrue(tracker.matchesClickAt(110, 210, 190, 290, 1350L, 1400L));
        }
    }

    @Test public void foreignAsyncPublisherCannotSuspendAnotherLiveMap() {
        CensorTapTracker tracker = new CensorTapTracker();
        publish(tracker, List.of(new BBox(100, 200, 100, 100)), 1000L);
        tracker.suspendDisplayed(new Object(), tracker.scope(), List.of(), viewport());
        assertTrue(tracker.matchesClickAt(110, 210, 190, 290, 1100L, 1200L));
    }

    @Test public void oldScopeCannotRearmAfterStopOrForegroundChange() {
        CensorTapTracker tracker = new CensorTapTracker();
        Object publisher = new Object();
        long oldScope = tracker.scope();
        tracker.publishDisplayed(publisher, oldScope, List.of(new BBox(100, 200, 100, 100)), viewport(), 1000L, false);
        tracker.clear();
        tracker.publishDisplayed(publisher, oldScope, List.of(new BBox(100, 200, 100, 100)), viewport(), 1100L, false);
        assertFalse(tracker.matchesClickAt(110, 210, 190, 290, 1200L, 1250L));
    }

    @Test public void oldDetachedPublisherCannotClearTheCurrentOverlay() {
        CensorTapTracker tracker = new CensorTapTracker();
        Object oldPublisher = new Object();
        publish(tracker, List.of(new BBox(100, 200, 100, 100)), 1000L);
        tracker.clearDisplayed(oldPublisher);
        assertTrue(tracker.matchesClickAt(110, 210, 190, 290, 1100L, 1200L));
    }

    @Test public void labOwnedSourceRemainsUnbillableAfterItsRecordingStops() {
        CensorTapTracker tracker = new CensorTapTracker();
        tracker.publishDisplayed(tracker, tracker.scope(), List.of(new BBox(100, 200, 100, 100)), viewport(), 1000L, true);
        assertFalse(tracker.matchesClickAt(110, 210, 190, 290, 1100L, 1200L));
    }

    @Test public void emptyOrOversizedClickTargetsCannotBorrowAContainingCensor() {
        CensorTapTracker tracker = new CensorTapTracker();
        publish(tracker, List.of(new BBox(100, 200, 100, 100)), 1000L);
        assertFalse(tracker.matchesClickAt(100, 200, 100, 200, 1100L, 1200L));
        assertFalse(tracker.matchesClickAt(0, 0, 1000, 2000, 1100L, 1200L));
    }

    private static void publish(CensorTapTracker tracker, List<BBox> regions, long drawnAt) {
        tracker.publishDisplayed(tracker, tracker.scope(), regions, viewport(), drawnAt, false);
    }

    @Test public void withdrawalPreservesScopeButEndsOldGeometryAndCannotClearAnotherPublisher() {
        CensorTapTracker tracker = new CensorTapTracker(); Object publisher = new Object();
        List<BBox> regions = List.of(new BBox(100, 200, 100, 100)); long scope = tracker.scope();
        tracker.publishDisplayed(publisher, scope, regions, viewport(), 1000, false);
        tracker.withdrawDisplayed(new Object());
        assertTrue(tracker.matchesPoint(110, 210, 1000, 2000, 1100));
        tracker.withdrawDisplayed(publisher); assertEquals(scope, tracker.scope());
        assertFalse(tracker.matchesPoint(110, 210, 1000, 2000, 1100));
        tracker.publishDisplayed(publisher, scope, regions, viewport(), 1200, false);
        assertFalse(tracker.matchesClickAt(110, 210, 190, 290, 1100, 1250));
        assertTrue(tracker.matchesClickAt(110, 210, 190, 290, 1200, 1250));
        tracker.clearDisplayed(publisher); assertEquals(scope + 1, tracker.scope());
    }

    private static BBox viewport() { return new BBox(0, 0, 1000, 2000); }
}
