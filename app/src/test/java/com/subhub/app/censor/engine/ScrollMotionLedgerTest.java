package com.subhub.app.censor.engine;

import static org.junit.Assert.*;

import org.junit.Test;
import java.util.concurrent.atomic.AtomicLong;

public final class ScrollMotionLedgerTest {
    private static final ScrollMotionLedger.Scope SCOPE = new ScrollMotionLedger.Scope(1, 42, 7, ProtectionScope.normal(9));
    private static final AtomicLong DELIVERIES = new AtomicLong();
    private static ScrollMotionLedger ledger() { return ledger(8, 4, 16); }
    private static ScrollMotionLedger ledger(int sources, int producers, int intervals) {
        ScrollMotionLedger ledger = new ScrollMotionLedger(sources, producers, intervals); ledger.reset(SCOPE); return ledger;
    }
    private static SceneEngine.Source image(long identity, long cutoff) {
        return SceneEngine.Source.image(1, identity, identity, 42, 1000, 2400, cutoff, -1, SCOPE.protection);
    }
    private static ScrollMotionLedger.Sample sample(long owner, long producer, long time,
            ScrollMotionLedger.Coordinate x, ScrollMotionLedger.Coordinate y) {
        return new ScrollMotionLedger.Sample(SCOPE, DELIVERIES.incrementAndGet(), owner, producer, time, time + 1, time + 2, time + 3,
                x, y, new SceneEngine.Region(0, 300, 1000, 2400));
    }
    private static ScrollMotionLedger.Sample position(long producer, long time, long y) {
        return sample(10, producer, time, ScrollMotionLedger.Coordinate.absolute(0), ScrollMotionLedger.Coordinate.absolute(y));
    }
    private static ScrollMotionLedger.Receipt observe(ScrollMotionLedger ledger, ScrollMotionLedger.Sample sample) {
        return ledger.observe(sample, sample.readFinishedAtMillis + 1);
    }
    private static void unknownAxis(ScrollMotionLedger.AxisContinuation axis) {
        assertFalse(axis.available);
        try { axis.displacement(); fail("Unknown is not numeric zero"); } catch (IllegalStateException expected) { }
        try { axis.minimumObservedDisplacement(); fail(); } catch (IllegalStateException expected) { }
        try { axis.maximumObservedDisplacement(); fail(); } catch (IllegalStateException expected) { }
    }

    @Test public void delayedAdoptionUsesActualAdministrativeCheckpointWithoutChangingCallbackReceipt() {
        ScrollMotionLedger ledger = ledger(); SceneEngine.Source source = image(1, 100);
        observe(ledger, position(1, 110, 0)); ledger.gap();
        observe(ledger, position(1, 160, 0)); observe(ledger, position(1, 180, 100));
        assertTrue(ledger.registerImage(source, 200));
        unknownAxis(ledger.continuation(source, 10, 1).y);
        observe(ledger, position(1, 220, 200)); unknownAxis(ledger.continuation(source, 10, 1).y);
        observe(ledger, position(1, 240, 300));
        ScrollMotionLedger.Continuation tail = ledger.continuation(source, 10, 1);
        assertEquals(100, source.receivedAtMillis); assertEquals(200, tail.originAdmittedAtMillis);
        assertEquals(100, tail.y.displacement()); assertEquals(1, tail.omittedStraddlingIntervals);
        assertFalse(tail.originPixelPhaseKnown); assertFalse(tail.initialBiasKnown);
    }

    @Test public void administrativeCheckpointCannotPrecedeCallbackOrRetimeAnExistingOrigin() {
        ScrollMotionLedger ledger = ledger(); SceneEngine.Source source = image(1, 100);
        assertFalse(ledger.registerImage(source, 99)); assertEquals(0, ledger.originCount());
        assertTrue(ledger.registerImage(source, 200)); assertTrue(ledger.registerImage(source, 200));
        assertFalse(ledger.registerImage(source, 201)); assertFalse(ledger.registerImage(source));
        observe(ledger, position(1, 200, 0)); observe(ledger, position(1, 220, 100));
        assertEquals(200, ledger.continuation(source, 10, 1).originAdmittedAtMillis);
        assertEquals(100, ledger.continuation(source, 10, 1).y.displacement());
    }

    @Test public void administrativeCheckpointOrderIsIndependentOfEarlierCallbackReceipts() {
        ScrollMotionLedger ledger = ledger(); SceneEngine.Source first = image(1, 100), second = image(2, 150);
        assertTrue(ledger.registerImage(first, 200)); assertFalse(ledger.registerImage(second, 199));
        assertTrue(ledger.registerImage(second, 220));
        observe(ledger, position(1, 200, 0)); observe(ledger, position(1, 220, 100)); observe(ledger, position(1, 240, 200));
        assertEquals(200, ledger.continuation(first, 10, 1).y.displacement());
        assertEquals(100, ledger.continuation(second, 10, 1).y.displacement());
    }

    @Test public void administrativeOriginsHaveIndependentTailsAndNeverAcquirePixelOrSupportAuthority() {
        ScrollMotionLedger ledger = ledger(); SceneEngine.Source a = image(1, 20), b = image(2, 70);
        assertTrue(ledger.registerImage(a)); assertTrue(ledger.registerImage(b));
        observe(ledger, position(1, 10, 0)); observe(ledger, position(1, 40, 100));
        observe(ledger, position(1, 80, 200)); observe(ledger, position(1, 120, 300));
        ScrollMotionLedger.Continuation first = ledger.continuation(a, 10, 1), second = ledger.continuation(b, 10, 1);
        assertEquals(200, first.y.displacement()); assertEquals(100, second.y.displacement());
        assertEquals(2, first.intervals); assertEquals(1, second.intervals);
        assertEquals(1, first.omittedStraddlingIntervals); assertEquals(1, second.omittedStraddlingIntervals);
        assertSame(a, first.origin); assertSame(b, second.origin);
        assertEquals(-1, a.pixelAtMillis); assertEquals(20, a.receivedAtMillis);
        assertFalse(first.originPixelPhaseKnown); assertFalse(first.initialBiasKnown);
        assertFalse(first.memberApplicabilityKnown); assertFalse(first.physicalErrorKnown);
        assertEquals(200, ledger.continuation(a, 10, 1).y.displacement()); // No accumulating painted-box rebase.
    }

    @Test public void reversalExtremaSurviveEqualEndpointsWithoutInventedPhysicalEnclosure() {
        ScrollMotionLedger ledger = ledger(); SceneEngine.Source origin = image(1, 10); ledger.registerImage(origin);
        observe(ledger, position(1, 10, 0)); observe(ledger, position(1, 20, 300));
        observe(ledger, position(1, 30, 0)); observe(ledger, position(1, 40, 200));
        ScrollMotionLedger.Continuation continuation = ledger.continuation(origin, 10, 1);
        assertEquals(200, continuation.y.displacement()); assertEquals(0, continuation.y.minimumObservedDisplacement());
        assertEquals(300, continuation.y.maximumObservedDisplacement());
        assertFalse(continuation.physicalErrorKnown);
    }

    @Test public void noInterpolationThroughCutoffAndNoSyntheticCurrentImageSource() {
        ScrollMotionLedger ledger = ledger(); SceneEngine.Source origin = image(1, 15); ledger.registerImage(origin);
        observe(ledger, position(1, 10, 0)); observe(ledger, position(1, 20, 400));
        unknownAxis(ledger.continuation(origin, 10, 1).y);
        observe(ledger, position(1, 30, 500));
        ScrollMotionLedger.Continuation continuation = ledger.continuation(origin, 10, 1);
        assertEquals(100, continuation.y.displacement()); assertEquals(1, continuation.omittedStraddlingIntervals);
        assertSame(origin, continuation.origin); assertEquals(1, ledger.originCount());
    }

    @Test public void pass105CompanionNumbersNeverBorrowAnotherProducerBaseline() {
        // Sanitized duplicate regression amounts, not imported private viewport/pixel evidence.
        ScrollMotionLedger ledger = ledger(); SceneEngine.Source origin = image(1, 336559700); ledger.registerImage(origin);
        observe(ledger, position(1, 336559700, 1519));
        observe(ledger, position(1, 336559725, 1238));
        ScrollMotionLedger.Sample companion = sample(10, 2, 336559737,
                ScrollMotionLedger.Coordinate.delta(0), ScrollMotionLedger.Coordinate.delta(-281));
        assertEquals(ScrollMotionLedger.Admission.BASELINE, observe(ledger, companion).admission);
        assertEquals(-281, ledger.continuation(origin, 10, 1).y.displacement());
        unknownAxis(ledger.continuation(origin, 10, 2).y);
        // Reported content -281 corresponds to screen +281. There is no global +562 aggregate.
        assertEquals(281, -ledger.continuation(origin, 10, 1).y.displacement());
        assertEquals(2, ledger.producerCount());
    }

    @Test public void equalExplicitDeltasFromOneProducerRemainAdditive() {
        ScrollMotionLedger ledger = ledger(); SceneEngine.Source origin = image(1, 10); ledger.registerImage(origin);
        observe(ledger, sample(10, 1, 10, ScrollMotionLedger.Coordinate.delta(0), ScrollMotionLedger.Coordinate.delta(281)));
        observe(ledger, sample(10, 1, 20, ScrollMotionLedger.Coordinate.delta(0), ScrollMotionLedger.Coordinate.delta(281)));
        observe(ledger, sample(10, 1, 30, ScrollMotionLedger.Coordinate.delta(0), ScrollMotionLedger.Coordinate.delta(281)));
        assertEquals(562, ledger.continuation(origin, 10, 1).y.displacement());
    }

    @Test public void distinctSameMillisecondDeliveriesAreNotSemanticDuplicates() {
        ScrollMotionLedger ledger = ledger(); SceneEngine.Source origin = image(1, 10); ledger.registerImage(origin);
        observe(ledger, sample(10, 1, 10, ScrollMotionLedger.Coordinate.delta(0), ScrollMotionLedger.Coordinate.delta(0)));
        observe(ledger, sample(10, 1, 20, ScrollMotionLedger.Coordinate.delta(0), ScrollMotionLedger.Coordinate.delta(281)));
        observe(ledger, sample(10, 1, 20, ScrollMotionLedger.Coordinate.delta(0), ScrollMotionLedger.Coordinate.delta(281)));
        assertEquals(562, ledger.continuation(origin, 10, 1).y.displacement());
    }

    @Test public void retiredOriginReplayCannotRearmItsGenerationAfterBoundedEviction() {
        ScrollMotionLedger ledger = ledger(1, 4, 16); SceneEngine.Source old = image(1, 10); ledger.registerImage(old);
        ledger.gap(); SceneEngine.Source fresh = image(2, 20); ledger.registerImage(fresh);
        assertFalse("An evicted source is not a new acquisition", ledger.registerImage(old));
        observe(ledger, position(1, 20, 100)); observe(ledger, position(1, 30, 200));
        unknownAxis(ledger.continuation(old, 10, 1).y);
        assertEquals(100, ledger.continuation(fresh, 10, 1).y.displacement());
    }

    @Test public void deltaCannotManufactureANegativeAbsoluteBaselineAndInventRecovery() {
        ScrollMotionLedger ledger = ledger(); SceneEngine.Source origin = image(1, 10); ledger.registerImage(origin);
        observe(ledger, position(1, 10, 0));
        ScrollMotionLedger.Receipt receipt = observe(ledger, sample(10, 1, 20,
                ScrollMotionLedger.Coordinate.delta(0), ScrollMotionLedger.Coordinate.delta(-2)));
        assertEquals(ScrollMotionLedger.Admission.GAP, receipt.admission);
        observe(ledger, position(1, 30, 0)); unknownAxis(ledger.continuation(origin, 10, 1).y);
    }

    @Test public void explicitUpdatePreservesSameProducerAbsoluteBaselineExactlyOnce() {
        ScrollMotionLedger ledger = ledger(); SceneEngine.Source origin = image(1, 10); ledger.registerImage(origin);
        observe(ledger, position(1, 10, 100));
        observe(ledger, sample(10, 1, 20, ScrollMotionLedger.Coordinate.delta(0), ScrollMotionLedger.Coordinate.delta(40)));
        observe(ledger, position(1, 30, 160));
        assertEquals(60, ledger.continuation(origin, 10, 1).y.displacement());
    }

    @Test public void contradictoryAbsoluteAndExplicitValuesMakeAGapNotAPreferredAuthority() {
        ScrollMotionLedger ledger = ledger(); SceneEngine.Source origin = image(1, 10); ledger.registerImage(origin);
        observe(ledger, position(1, 10, 100));
        ScrollMotionLedger.Receipt receipt = observe(ledger, sample(10, 1, 20,
                ScrollMotionLedger.Coordinate.delta(0), ScrollMotionLedger.Coordinate.absoluteAndDelta(160, 40)));
        assertEquals(ScrollMotionLedger.Admission.GAP, receipt.admission);
        assertEquals(ScrollMotionLedger.Refusal.CONFLICT, receipt.refusal);
        assertEquals(ScrollMotionLedger.Refusal.EXPLICIT_GAP, ledger.continuation(origin, 10, 1).refusal);
    }

    @Test public void defaultZeroOffsetsAndOmittedNegativeOneDeltaAreUnknownNotNoMovement() {
        ScrollMotionLedger.Coordinate empty = ScrollMotionLedger.Coordinate.accessibility(0, 0, -1);
        assertFalse(empty.absoluteKnown); assertFalse(empty.deltaKnown);
        assertTrue(ScrollMotionLedger.Coordinate.accessibility(0, 0, 1).deltaKnown);
        assertEquals(1, ScrollMotionLedger.Coordinate.accessibility(0, 0, 1).delta);
        assertTrue(ScrollMotionLedger.Coordinate.accessibility(0, 0, 0).deltaKnown);
        assertFalse(ScrollMotionLedger.Coordinate.accessibility(10, 9, 2).valid);
        ScrollMotionLedger ledger = ledger(); SceneEngine.Source origin = image(1, 10); ledger.registerImage(origin);
        observe(ledger, sample(10, 1, 10, empty, empty));
        assertEquals(ScrollMotionLedger.Admission.UNKNOWN, observe(ledger, sample(10, 1, 20, empty, empty)).admission);
        unknownAxis(ledger.continuation(origin, 10, 1).x); unknownAxis(ledger.continuation(origin, 10, 1).y);
    }

    @Test public void axesRemainIndependentAndUnknownCannotBeFilledWithOtherAxisZero() {
        ScrollMotionLedger ledger = ledger(); SceneEngine.Source origin = image(1, 10); ledger.registerImage(origin);
        observe(ledger, sample(10, 1, 10, ScrollMotionLedger.Coordinate.unknown(), ScrollMotionLedger.Coordinate.absolute(100)));
        observe(ledger, sample(10, 1, 20, ScrollMotionLedger.Coordinate.unknown(), ScrollMotionLedger.Coordinate.absolute(140)));
        ScrollMotionLedger.Continuation continuation = ledger.continuation(origin, 10, 1);
        unknownAxis(continuation.x); assertEquals(40, continuation.y.displacement());
        assertEquals(ScrollMotionLedger.Refusal.UNKNOWN_AXIS, continuation.refusal);
    }

    @Test public void exactDuplicateDoesNotRenewOriginalClocksOrAppendAnInterval() {
        ScrollMotionLedger ledger = ledger(); SceneEngine.Source origin = image(1, 10); ledger.registerImage(origin);
        observe(ledger, position(1, 10, 0)); ScrollMotionLedger.Sample sample = position(1, 20, 50); observe(ledger, sample);
        long originalReceipt = ledger.continuation(origin, 10, 1).latestReceivedAtMillis;
        assertEquals(ScrollMotionLedger.Admission.DUPLICATE, ledger.observe(sample, 1000).admission);
        assertEquals(1, ledger.intervalCount()); assertEquals(originalReceipt, ledger.continuation(origin, 10, 1).latestReceivedAtMillis);
        assertEquals(50, ledger.continuation(origin, 10, 1).y.displacement());
    }

    @Test public void conflictingReplayAndLateSourceCannotReviseAlreadyAcceptedHistory() {
        for (boolean replay : new boolean[]{true, false}) {
            ScrollMotionLedger ledger = ledger(); SceneEngine.Source origin = image(1, 10); ledger.registerImage(origin);
            observe(ledger, position(1, 10, 0)); ScrollMotionLedger.Sample original = position(1, 20, 100);
            observe(ledger, original);
            ScrollMotionLedger.Sample changed = replay ? new ScrollMotionLedger.Sample(SCOPE, original.deliveryIdentity,
                    10, 1, 20, 21, 22, 23, ScrollMotionLedger.Coordinate.absolute(0),
                    ScrollMotionLedger.Coordinate.absolute(120), original.reportedViewport) : position(1, 15, 120);
            ScrollMotionLedger.Receipt receipt = observe(ledger, changed);
            assertEquals(ScrollMotionLedger.Admission.GAP, receipt.admission);
            assertEquals(replay ? ScrollMotionLedger.Refusal.CONFLICT : ScrollMotionLedger.Refusal.ORDER, receipt.refusal);
            unknownAxis(ledger.continuation(origin, 10, 1).y);
        }
    }

    @Test public void gapNeverResetsAnOldOriginsOffsetToZeroAndContinues() {
        ScrollMotionLedger ledger = ledger(); SceneEngine.Source old = image(1, 10); ledger.registerImage(old);
        observe(ledger, position(1, 10, 0)); observe(ledger, position(1, 20, 100));
        ledger.gap(); assertTrue(ledger.registerImage(old));
        SceneEngine.Source fresh = image(2, 30); ledger.registerImage(fresh);
        observe(ledger, position(1, 30, 100)); observe(ledger, position(1, 40, 200));
        assertEquals(ScrollMotionLedger.Refusal.EXPLICIT_GAP, ledger.continuation(old, 10, 1).refusal);
        unknownAxis(ledger.continuation(old, 10, 1).y); assertEquals(100, ledger.continuation(fresh, 10, 1).y.displacement());
    }

    @Test public void clippedViewportChangesCannotMintDisplacementOrWholeMemberApplicability() {
        ScrollMotionLedger ledger = ledger(); SceneEngine.Source origin = image(1, 10); ledger.registerImage(origin);
        observe(ledger, position(1, 10, 50));
        ScrollMotionLedger.Sample clipped = new ScrollMotionLedger.Sample(SCOPE, DELIVERIES.incrementAndGet(), 10, 1, 20, 21, 22, 23,
                ScrollMotionLedger.Coordinate.absolute(0), ScrollMotionLedger.Coordinate.absolute(50),
                new SceneEngine.Region(0, 500, 1000, 2400));
        ScrollMotionLedger.Receipt receipt = observe(ledger, clipped);
        ScrollMotionLedger.Continuation continuation = ledger.continuation(origin, 10, 1);
        assertEquals(0, continuation.y.displacement()); assertFalse(continuation.memberApplicabilityKnown);
        assertFalse(continuation.physicalErrorKnown); assertSame(clipped.reportedViewport, receipt.sample.reportedViewport);
    }

    @Test public void malformedFutureOrReadClocksAreGapsWithoutReceiptTimeSubstitution() {
        long[][] clocks = {{20, 19, 21, 22, 23}, {-1, 20, 21, 22, 23}, {20, 21, 20, 22, 23},
                {20, 21, 22, 21, 23}, {20, 21, 22, 23, 22}};
        for (long[] clock : clocks) {
            ScrollMotionLedger ledger = ledger(); SceneEngine.Source origin = image(1, 10); ledger.registerImage(origin);
            observe(ledger, position(1, 10, 0));
            ScrollMotionLedger.Sample bad = new ScrollMotionLedger.Sample(SCOPE, DELIVERIES.incrementAndGet(), 10, 1, clock[0], clock[1], clock[2], clock[3],
                    ScrollMotionLedger.Coordinate.delta(0), ScrollMotionLedger.Coordinate.delta(100), null);
            ScrollMotionLedger.Receipt receipt = ledger.observe(bad, clock[4]);
            assertEquals(ScrollMotionLedger.Refusal.CLOCK, receipt.refusal); assertSame(bad, receipt.sample);
            unknownAxis(ledger.continuation(origin, 10, 1).y);
        }
    }

    @Test public void staleWindowGeometryEpochAndProtectionCannotInvalidateTheCurrentTimeline() {
        ScrollMotionLedger.Scope[] stale = {new ScrollMotionLedger.Scope(0, 42, 7, SCOPE.protection),
                new ScrollMotionLedger.Scope(1, 43, 7, SCOPE.protection), new ScrollMotionLedger.Scope(1, 42, 8, SCOPE.protection),
                new ScrollMotionLedger.Scope(1, 42, 7, ProtectionScope.lab(9))};
        for (ScrollMotionLedger.Scope scope : stale) {
            ScrollMotionLedger ledger = ledger(); SceneEngine.Source origin = image(1, 10); ledger.registerImage(origin);
            observe(ledger, position(1, 10, 0)); observe(ledger, position(1, 20, 100));
            ScrollMotionLedger.Sample old = new ScrollMotionLedger.Sample(scope, DELIVERIES.incrementAndGet(), 10, 1, 30, 31, 32, 33,
                    ScrollMotionLedger.Coordinate.delta(0), ScrollMotionLedger.Coordinate.delta(100), null);
            assertEquals(ScrollMotionLedger.Admission.STALE, observe(ledger, old).admission);
            assertEquals(100, ledger.continuation(origin, 10, 1).y.displacement());
        }
    }

    @Test public void unknownOwnerCapacityAndOwnerReplacementProduceExplicitGaps() {
        for (long[] identity : new long[][]{{0, 1}, {10, 0}, {11, 1}, {10, 2}}) {
            ScrollMotionLedger ledger = ledger(8, 1, 16); SceneEngine.Source origin = image(1, 10); ledger.registerImage(origin);
            observe(ledger, position(1, 10, 0));
            assertEquals(ScrollMotionLedger.Admission.GAP, observe(ledger, sample(identity[0], identity[1], 20,
                    ScrollMotionLedger.Coordinate.delta(0), ScrollMotionLedger.Coordinate.delta(100))).admission);
            unknownAxis(ledger.continuation(origin, 10, 1).y); assertEquals(0, ledger.producerCount());
        }
    }

    @Test public void historyEvictionAndOriginEvictionStayUnknownRatherThanContinuingAnIncompletePrefix() {
        ScrollMotionLedger ledger = ledger(1, 4, 1); SceneEngine.Source old = image(1, 10); ledger.registerImage(old);
        observe(ledger, position(1, 10, 0)); observe(ledger, position(1, 20, 100)); observe(ledger, position(1, 30, 200));
        assertEquals(1, ledger.intervalCount()); assertEquals(ScrollMotionLedger.Refusal.HISTORY, ledger.continuation(old, 10, 1).refusal);
        SceneEngine.Source next = image(2, 20); ledger.registerImage(next);
        assertEquals(1, ledger.originCount()); assertEquals(100, ledger.continuation(next, 10, 1).y.displacement());
        assertEquals(ScrollMotionLedger.Refusal.SOURCE, ledger.continuation(old, 10, 1).refusal);
    }

    @Test public void arithmeticOverflowIsARefusalNotSaturationOrAClippedMotion() {
        ScrollMotionLedger ledger = ledger(); SceneEngine.Source origin = image(1, 10); ledger.registerImage(origin);
        observe(ledger, sample(10, 1, 10, ScrollMotionLedger.Coordinate.absolute(0), ScrollMotionLedger.Coordinate.absolute(Long.MAX_VALUE)));
        ScrollMotionLedger.Receipt receipt = observe(ledger, sample(10, 1, 20,
                ScrollMotionLedger.Coordinate.delta(0), ScrollMotionLedger.Coordinate.delta(1)));
        assertEquals(ScrollMotionLedger.Refusal.OVERFLOW, receipt.refusal); unknownAxis(ledger.continuation(origin, 10, 1).y);
    }

    @Test public void copiedOrNativeSourceCannotReplaceRetireOrRetimeAnImageOrigin() {
        ScrollMotionLedger ledger = ledger(); SceneEngine.Source origin = image(1, 10); ledger.registerImage(origin);
        assertFalse(ledger.registerImage(image(1, 100)));
        assertFalse(ledger.registerImage(SceneEngine.Source.nativeText(1, 2, 42, 1000, 2400, 7, 10, 11, 12, SCOPE.protection)));
        observe(ledger, position(1, 10, 0)); observe(ledger, position(1, 20, 100));
        assertEquals(100, ledger.continuation(origin, 10, 1).y.displacement());
        assertEquals(ScrollMotionLedger.Refusal.SOURCE, ledger.continuation(image(1, 10), 10, 1).refusal);
        assertEquals(10, origin.receivedAtMillis); assertEquals(1, ledger.originCount());
    }

    @Test public void resetAndCloseRetireAllSourcesAndCannotReviveOldIntervals() {
        ScrollMotionLedger ledger = ledger(); SceneEngine.Source origin = image(1, 10); ledger.registerImage(origin);
        observe(ledger, position(1, 10, 0)); observe(ledger, position(1, 20, 100));
        ledger.reset(new ScrollMotionLedger.Scope(2, 42, 7, SCOPE.protection));
        assertEquals(0, ledger.originCount()); assertEquals(0, ledger.intervalCount());
        assertEquals(ScrollMotionLedger.Refusal.SCOPE, ledger.continuation(origin, 10, 1).refusal);
        ledger.close(); assertEquals(ScrollMotionLedger.Admission.CLOSED, observe(ledger, position(1, 30, 200)).admission);
        try { ledger.reset(SCOPE); fail(); } catch (IllegalStateException expected) { }
    }
}
