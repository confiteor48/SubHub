package com.subhub.app.censor.engine;

import static com.subhub.app.censor.engine.SceneEngine.Lane.FAST;
import static com.subhub.app.censor.engine.SceneEngine.Lane.QUALITY;
import static com.subhub.app.censor.engine.SceneEngine.Lane.TEXT;
import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import org.junit.Test;

public class CensorEngineTest {
    private static final CensorEngine.WorkTiming TIMING = new CensorEngine.WorkTiming(3, 10, 2);
    private static final SceneEngine.Region SUPPORT = new SceneEngine.Region(-10000, -10000, 10000, 10000);

    private static ScrollMotionLedger.Sample scroll(Harness h, long delivery, long at, long y) {
        return new ScrollMotionLedger.Sample(h.engine.scrollScope(), delivery, 10, 1, at, at + 1, at + 2, at + 3,
                ScrollMotionLedger.Coordinate.absolute(0), ScrollMotionLedger.Coordinate.absolute(y), SUPPORT);
    }

    @Test public void callbackBeforeGapCannotUseItsReceiptAsLaterAdministrativeAdmissionCutoff() {
        Harness h = new Harness(); Pixel pixel = new Pixel(1);
        h.behavior = (request, callback) -> {
            assertTrue(request.tryDispatch()); h.now = 100; callback.success(pixel.payload());
            assertTrue(h.fast.calls.isEmpty());
            h.now = 154;
            h.engine.observeScroll(new ScrollMotionLedger.Sample(h.engine.scrollScope(), 1, 0, 0, 150, 151, -1, -1,
                    ScrollMotionLedger.Coordinate.unknown(), ScrollMotionLedger.Coordinate.unknown(), null), true);
            h.now = 164; h.engine.observeScroll(scroll(h, 2, 160, 0), false);
            h.now = 184; h.engine.observeScroll(scroll(h, 3, 180, 100), false);
            h.now = 200; // Actual capture API return, not the earlier callback receipt or a pixel clock.
        };
        h.engine.start(42, ProtectionScope.UNSPECIFIED, 7); h.fire(0); h.fastExecutor.runOne();
        SceneEngine.Source origin = h.fast.calls.get(0).input.source;
        assertEquals(100, origin.receivedAtMillis); assertEquals(-1, origin.pixelAtMillis);
        assertEquals(200, h.engine.scrollContinuation(origin, 10, 1).originAdmittedAtMillis);
        assertFalse("Pre-admission intervals cannot be a post-admission continuation",
                h.engine.scrollContinuation(origin, 10, 1).y.available);
        h.now = 224; h.engine.observeScroll(scroll(h, 4, 220, 200), false);
        assertFalse("Crossing the actual admission cutoff is not split or interpolated",
                h.engine.scrollContinuation(origin, 10, 1).y.available);
        h.now = 244; h.engine.observeScroll(scroll(h, 5, 240, 300), false);
        ScrollMotionLedger.Continuation tail = h.engine.scrollContinuation(origin, 10, 1);
        assertEquals(100, tail.y.displacement()); assertEquals(1, tail.omittedStraddlingIntervals);
        assertFalse(tail.originPixelPhaseKnown); assertFalse(tail.initialBiasKnown);
        h.engine.close(); h.fast.calls.get(0).completion.cancelled(); h.ui.runAll(); assertEquals(1, pixel.closes);
    }

    @Test public void refusedOptionalScrollOriginCannotStrandCapturedPixelsOrTheScenePin() {
        Harness h = new Harness(); Pixel pixel = new Pixel(1);
        h.behavior = (request, callback) -> {
            assertTrue(request.tryDispatch()); h.now = 100; callback.success(pixel.payload());
            h.now = 99; // Adverse clock input: ledger must refuse, never retime receipt to make it valid.
        };
        h.engine.start(42, ProtectionScope.UNSPECIFIED, 7); h.fire(0);
        h.now = 101; h.fastExecutor.runOne(); SceneEngine.Source source = h.fast.calls.get(0).input.source;
        assertEquals(100, source.receivedAtMillis);
        assertEquals(ScrollMotionLedger.Refusal.SOURCE, h.engine.scrollContinuation(source, 10, 1).refusal);
        h.fast.succeed(0, face(500)); h.ui.runAll();
        assertEquals(1, h.evidence.size()); assertEquals(500, h.engine.viewportCoverage().regions.get(0).displayRegion.top, 0);
        h.engine.close(); h.ui.runAll(); assertEquals(1, pixel.closes); assertEquals(1, h.drained);
    }

    @Test public void actualCaptureRegistersScrollOriginWithoutChangingFirstFastGeometryEvidenceOrExpiry() {
        Harness h = new Harness(); h.engine.start(42, ProtectionScope.UNSPECIFIED, 7); h.fire(0);
        Pixel pixel = h.deliver(0, 1); h.fastExecutor.runOne(); h.fast.succeed(0, face(500)); h.ui.runAll();
        SceneEngine.Source origin = h.fast.calls.get(0).input.source;
        CensorEngine.EvidenceReceipt evidence = h.evidence.get(0); CensorEngine.ViewportCoverage coverage = h.engine.viewportCoverage();
        long beforeExpiry, beforeRevision;
        try (CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView()) {
            beforeExpiry = view.snapshot.nextChangeAtMillis; beforeRevision = view.snapshot.revision;
        }
        h.now = 14; h.engine.observeScroll(scroll(h, 1, 10, 0), false);
        h.now = 24; h.engine.observeScroll(scroll(h, 2, 20, 100), false);
        ScrollMotionLedger.Continuation continuation = h.engine.scrollContinuation(origin, 10, 1);
        assertEquals(100, continuation.y.displacement()); assertSame(origin, continuation.origin);
        assertEquals(0, origin.receivedAtMillis); assertEquals(-1, origin.pixelAtMillis);
        assertFalse(continuation.memberApplicabilityKnown); assertFalse(continuation.initialBiasKnown);
        assertEquals(2, h.scrolls.size()); assertSame(evidence, h.evidence.get(0)); assertEquals(1, h.evidence.size());
        assertTrue(h.engine.coverageCurrent(coverage)); assertEquals(500, coverage.regions.get(0).displayRegion.top, 0);
        try (CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView()) {
            assertEquals(beforeRevision, view.snapshot.revision); assertEquals(beforeExpiry, view.snapshot.nextChangeAtMillis);
            assertEquals(500, view.snapshot.groups.get(0).members.get(0).displayRegion.top, 0);
        }
        assertEquals(1, h.captures.size()); h.engine.close(); h.ui.runAll(); assertEquals(1, pixel.closes);
    }

    @Test public void lateInferenceOriginsUseTheirOwnAdministrativeCutoffsNotLatestGlobalCamera() {
        Harness h = new Harness(); h.engine.start(42, ProtectionScope.UNSPECIFIED, 7); h.fire(0);
        Pixel first = h.deliver(0, 1); h.fastExecutor.runOne(); h.fast.succeed(0, face(500)); h.ui.runAll();
        SceneEngine.Source old = h.fast.calls.get(0).input.source;
        h.now = 14; h.engine.observeScroll(scroll(h, 1, 10, 0), false);
        h.now = 24; h.engine.observeScroll(scroll(h, 2, 20, 100), false);
        h.fire(334); Pixel second = h.deliver(1, 2); h.fastExecutor.runOne();
        SceneEngine.Source recent = h.fast.calls.get(1).input.source;
        h.now = 354; h.engine.observeScroll(scroll(h, 3, 350, 200), false);
        h.now = 404; h.engine.observeScroll(scroll(h, 4, 400, 300), false);
        assertEquals(300, h.engine.scrollContinuation(old, 10, 1).y.displacement());
        ScrollMotionLedger.Continuation tail = h.engine.scrollContinuation(recent, 10, 1);
        assertEquals(100, tail.y.displacement()); assertEquals(1, tail.omittedStraddlingIntervals);
        assertEquals(334, recent.receivedAtMillis); assertFalse(tail.originPixelPhaseKnown);
        h.engine.close(); h.fast.calls.get(1).completion.cancelled(); h.ui.runAll();
        assertEquals(1, first.closes); assertEquals(1, second.closes);
    }

    @Test public void unknownLookupClocksRemainUnknownAndGapCannotRenewEvidenceOrContinueOldOrigin() {
        Harness h = new Harness(); h.engine.start(42, ProtectionScope.UNSPECIFIED, 7); h.fire(0);
        h.deliver(0, 1); h.fastExecutor.runOne(); h.fast.succeed(0, face(500)); h.ui.runAll();
        SceneEngine.Source origin = h.fast.calls.get(0).input.source;
        h.now = 14; h.engine.observeScroll(scroll(h, 1, 10, 0), false);
        h.now = 24; h.engine.observeScroll(scroll(h, 2, 20, 100), false);
        CensorEngine.ViewportCoverage coverage = h.engine.viewportCoverage();
        ScrollMotionLedger.Sample missing = new ScrollMotionLedger.Sample(h.engine.scrollScope(), 3, 0, 0, 40, 41, -1, -1,
                ScrollMotionLedger.Coordinate.unknown(), ScrollMotionLedger.Coordinate.unknown(), null);
        h.now = 42; ScrollMotionLedger.Receipt receipt = h.engine.observeScroll(missing, true);
        assertEquals(ScrollMotionLedger.Refusal.EXPLICIT_GAP, receipt.refusal);
        assertEquals(-1, receipt.sample.readStartedAtMillis); assertEquals(-1, receipt.sample.readFinishedAtMillis);
        h.now = 64; h.engine.observeScroll(scroll(h, 4, 60, 300), false);
        assertEquals(ScrollMotionLedger.Refusal.EXPLICIT_GAP, h.engine.scrollContinuation(origin, 10, 1).refusal);
        assertEquals(1, h.evidence.size()); assertTrue(h.engine.coverageCurrent(coverage));
        h.engine.close(); h.ui.runAll();
    }

    @Test public void realWindowScopeReplacementRejectsOldInputWithoutGappingNewScope() {
        Harness h = new Harness(); h.engine.start(42, ProtectionScope.UNSPECIFIED, 7); h.fire(0);
        h.deliver(0, 1); h.fastExecutor.runOne(); SceneEngine.Source old = h.fast.calls.get(0).input.source;
        ScrollMotionLedger.Sample packet = scroll(h, 1, 10, 0);
        h.engine.changeScope(43, ProtectionScope.lab(99), 8);
        assertEquals(8, h.engine.scrollScope().windowId); assertEquals(43, h.engine.scrollScope().geometry);
        h.now = 14; ScrollMotionLedger.Receipt receipt = h.engine.observeScroll(packet, false);
        assertEquals(ScrollMotionLedger.Admission.STALE, receipt.admission);
        assertEquals(ScrollMotionLedger.Refusal.SCOPE, h.engine.scrollContinuation(old, 10, 1).refusal);
        h.engine.close(); h.fast.calls.get(0).completion.cancelled(); h.ui.runAll();
    }

    @Test public void inactiveUnselectedAndClosedOwnerCannotAcquireScrollInputOrCreateSources() {
        Harness h = new Harness(); assertNull(h.engine.scrollScope());
        ScrollMotionLedger.Sample packet = new ScrollMotionLedger.Sample(new ScrollMotionLedger.Scope(0, 42, 7, ProtectionScope.UNSPECIFIED),
                1, 10, 1, 0, 1, 2, 3, ScrollMotionLedger.Coordinate.delta(0), ScrollMotionLedger.Coordinate.delta(50), null);
        assertNull(h.engine.observeScroll(packet, false)); assertTrue(h.scrolls.isEmpty()); assertTrue(h.captures.isEmpty());
        h.engine.close(); h.ui.runAll(); assertNull(h.engine.observeScroll(packet, false)); assertNull(h.engine.scrollScope());
        assertTrue(h.scrolls.isEmpty()); assertTrue(h.captures.isEmpty());
    }

    @Test public void scrollRevokesOriginalObservationAuthorityWithoutChangingCoverageOrCountClaims() {
        Harness h = new Harness(true, false, false, 8, 2, 16); h.start(); h.fire(0); h.deliver(0, 1);
        h.fastExecutor.runOne(); h.fast.succeed(0, face(500));
        CensorEngine.EvidenceReceipt old = h.evidence.get(0); CensorEngine.ViewportCoverage coverage = h.engine.viewportCoverage();
        assertTrue(h.engine.dwellPresent(old, coverage, coverage.regions));
        h.engine.discardDwellObservations(h.engine.activeScope());
        assertTrue(h.engine.coverageCurrent(coverage)); assertNotNull(h.engine.claimEvidence(old));
        assertFalse(h.engine.observationCurrent(old, coverage)); assertFalse(h.engine.dwellPresent(old, coverage, coverage.regions));
        h.qualityExecutor.runOne(); h.quality.succeed(0, face(500));
        CensorEngine.EvidenceReceipt current = h.evidence.get(1); CensorEngine.ViewportCoverage updated = h.engine.viewportCoverage();
        assertTrue(h.engine.dwellPresent(current, updated, updated.regions));
        assertEquals(1, current.subjects.get(0).witnesses.size()); h.engine.close(); h.ui.runAll();
    }

    @Test public void authenticDwellObservationDoesNotConsumeWitnessesAndRejectsStaleOrForeignJoins() {
        Harness h = new Harness(), other = new Harness(); h.start(); other.start(); h.fire(0); h.deliver(0, 1);
        h.fastExecutor.runOne(); h.fast.succeed(0, face(500));
        CensorEngine.EvidenceReceipt tick = h.evidence.get(0); CensorEngine.ViewportCoverage coverage = h.engine.viewportCoverage();
        assertTrue(tick.positiveInput); assertTrue(h.engine.dwellPresent(tick, coverage, coverage.regions));
        assertFalse(other.engine.dwellPresent(tick, coverage, coverage.regions));
        assertTrue(h.engine.claimEvidence(tick).subjects.isEmpty()); assertEquals(1, tick.subjects.get(0).witnesses.size());
        h.ui.runAll(); h.fire(334); h.deliver(1, 2); h.engine.motion(motion(h.engine.currentEpoch(), 1, 2, 0));
        CensorEngine.ViewportCoverage supported = h.engine.viewportCoverage(); assertEquals(1, supported.regions.size());
        assertFalse(h.engine.dwellPresent(tick, supported, supported.regions));
        h.engine.close(); other.engine.close(); h.fastExecutor.runAll(); h.ui.runAll(); other.ui.runAll();
    }
    @Test public void emptySameSourceQualityCannotBorrowFastInventoryAsADwellPositive() {
        Harness h = new Harness(true, false, false, 8, 2, 16); h.start(); h.fire(0); h.deliver(0, 1);
        h.fastExecutor.runOne(); h.fast.succeed(0, face(500)); h.qualityExecutor.runOne(); h.quality.succeed(0);
        CensorEngine.EvidenceReceipt emptyQuality = h.evidence.get(1);
        assertFalse(emptyQuality.positiveInput); assertEquals(1, emptyQuality.subjects.size());
        assertEquals(FAST, emptyQuality.subjects.get(0).evidenceLane);
        CensorEngine.ViewportCoverage coverage = h.engine.viewportCoverage(); assertTrue(coverage.regions.get(0).dwellPositive);
        assertTrue(h.engine.observationCurrent(emptyQuality, coverage));
        assertFalse(h.engine.dwellPresent(emptyQuality, coverage, coverage.regions)); h.engine.close(); h.ui.runAll();
    }
    @Test public void emptyFastMissRemainsAHintButNotADwellPositive() {
        Harness h = new Harness(); h.start(); h.fire(0); h.deliver(0, 1); h.fastExecutor.runOne(); h.fast.succeed(0, face(500));
        h.ui.runAll(); h.fire(334); h.deliver(1, 2); h.engine.motion(motion(h.engine.currentEpoch(), 1, 2, 0));
        h.fastExecutor.runOne(); h.fast.succeed(1);
        CensorEngine.ViewportCoverage coverage = h.engine.viewportCoverage(); assertEquals(1, coverage.regions.size());
        assertFalse(coverage.regions.get(0).dwellPositive);
        assertFalse(h.engine.dwellPresent(h.evidence.get(1), coverage, coverage.regions)); h.engine.close(); h.ui.runAll();
    }
    @Test public void dwellCannotUseForeignVisibleMembersOrAnExpiredReceiptAfterStyleQueries() {
        Harness h = new Harness(), other = new Harness(); h.start(); other.start();
        for (Harness value : Arrays.asList(h, other)) {
            value.fire(0); value.deliver(0, 1); value.fastExecutor.runOne(); value.fast.succeed(0, face(500));
        }
        CensorEngine.EvidenceReceipt tick = h.evidence.get(0); CensorEngine.ViewportCoverage initial = h.engine.viewportCoverage();
        assertFalse(h.engine.dwellPresent(tick, initial, other.engine.viewportCoverage().regions));
        h.now = 5001; assertFalse(h.engine.dwellPresent(tick, initial, initial.regions));
        assertEquals(1, h.evidence.size()); assertEquals(1, h.captures.size());
        h.engine.close(); other.engine.close(); h.ui.runAll(); other.ui.runAll();
    }

    @Test public void viewportStatesDistinguishWithdrawalUnobservedAndAcceptedEmpty() {
        Harness h = new Harness();
        CensorEngine.ViewportCoverage withdrawn = h.engine.viewportCoverage();
        assertEquals(CensorEngine.CoverageState.WITHDRAWN, withdrawn.state);
        assertNull(withdrawn.scope); assertNull(withdrawn.canvas); assertTrue(h.engine.coverageCurrent(withdrawn));
        h.start(); assertFalse(h.engine.coverageCurrent(withdrawn));
        CensorEngine.ViewportCoverage unseen = h.engine.viewportCoverage();
        assertEquals(CensorEngine.CoverageState.UNOBSERVED, unseen.state); assertTrue(h.engine.coverageCurrent(unseen));
        h.fire(0); h.deliver(0, 1); h.fastExecutor.runOne();
        assertEquals(CensorEngine.CoverageState.UNOBSERVED, h.engine.viewportCoverage().state);
        h.fast.succeed(0);
        CensorEngine.ViewportCoverage empty = h.engine.viewportCoverage();
        assertEquals(CensorEngine.CoverageState.NO_ELIGIBLE_REGIONS, empty.state);
        assertTrue(empty.regions.isEmpty()); assertEquals(1, empty.canvas.sequence);
        assertFalse(h.engine.coverageCurrent(unseen)); assertTrue(h.engine.coverageCurrent(empty));
        h.engine.close();
    }

    @Test public void detectedViewportIsAvailableBeforePaintWithoutMintingCountWitnesses() {
        Harness h = new Harness(); h.start(); h.fire(0); Pixel pixel = h.deliver(0, 1);
        h.fastExecutor.runOne(); h.fast.succeed(0, face(500));
        CensorEngine.ViewportCoverage coverage = h.engine.viewportCoverage();
        assertEquals(CensorEngine.CoverageState.AVAILABLE, coverage.state);
        assertEquals(1, coverage.regions.size()); assertTrue(h.engine.coverageCurrent(coverage));
        CensorEngine.DetectedRegion member = coverage.regions.get(0);
        assertSame(coverage.canvas, member.evidenceSource); assertSame(member.evidenceSource, member.geometrySource);
        assertEquals(500, member.displayRegion.top, 0); assertFalse(member.physicalErrorKnown);
        assertEquals(-1, coverage.compositorAtMillis); assertFalse(member.nativeValidated);
        assertThrows(UnsupportedOperationException.class, () -> coverage.regions.clear());
        assertEquals(1, h.evidence.size()); assertEquals(1, h.evidence.get(0).subjects.get(0).witnesses.size());
        assertTrue(h.engine.claimEvidence(h.evidence.get(0)).subjects.isEmpty());
        assertEquals(0, pixel.closes); h.engine.close(); h.ui.runAll(); assertEquals(1, pixel.closes);
    }

    @Test public void rawImageAdmissionRevokesCoverageBeforeFastOrUiWithoutChangingOldPresentation() {
        Harness h = new Harness(); h.start(); h.fire(0); h.deliver(0, 1);
        h.fastExecutor.runOne(); h.fast.succeed(0, face(500)); h.ui.runAll();
        CensorEngine.ViewportCoverage old = h.engine.viewportCoverage();
        long revision;
        try (CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView()) { revision = view.snapshot.revision; }
        h.fire(334); h.deliver(1, 2); // FAST and UI intentionally held.
        assertFalse(h.engine.coverageCurrent(old));
        CensorEngine.ViewportCoverage unknown = h.engine.viewportCoverage();
        assertEquals(CensorEngine.CoverageState.NO_ELIGIBLE_REGIONS, unknown.state);
        assertEquals(1, unknown.ineligibleMembers); assertTrue(unknown.regions.isEmpty());
        try (CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView()) {
            assertEquals(revision, view.snapshot.revision); assertEquals(1, view.snapshot.destination.sequence);
        }
        h.fastExecutor.runOne(); h.fast.succeed(1, face(700));
        assertFalse(h.engine.coverageCurrent(unknown));
        assertEquals(2, h.engine.viewportCoverage().regions.get(0).geometrySource.sequence);
        h.engine.close(); h.ui.runAll();
    }

    @Test public void imageEstimateAndHeldGeometryNeverBecomeEligibleDetectedViewport() {
        Harness h = new Harness(); h.start(); h.fire(0); h.deliver(0, 1);
        h.fastExecutor.runOne(); h.fast.succeed(0, face(500)); h.ui.runAll();
        h.fire(334); h.deliver(1, 2); assertTrue(h.engine.presentationEstimate(imageHint(h, 1, 2, -400)));
        CensorEngine.ViewportCoverage estimate = h.engine.viewportCoverage();
        assertEquals(CensorEngine.CoverageState.NO_ELIGIBLE_REGIONS, estimate.state);
        assertEquals(1, estimate.ineligibleMembers); h.ui.runAll();
        h.fastExecutor.runOne(); h.fast.succeed(1); h.ui.runAll();
        h.fire(668); h.deliver(2, 3); h.fastExecutor.runOne(); h.fast.succeed(2); h.ui.runAll();
        try (CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView()) {
            assertTrue(masks(view.snapshot).get(0).heldGeometry);
        }
        CensorEngine.ViewportCoverage held = h.engine.viewportCoverage();
        assertEquals(CensorEngine.CoverageState.NO_ELIGIBLE_REGIONS, held.state);
        assertEquals(1, held.ineligibleMembers); h.engine.close();
    }

    @Test public void supportedMeasuredProjectionPreservesBothSourceOrigins() {
        Harness h = new Harness(); h.start(); h.fire(0); h.deliver(0, 1);
        h.fastExecutor.runOne(); h.fast.succeed(0, face(500)); h.ui.runAll();
        h.fire(334); h.deliver(1, 2);
        assertTrue(h.engine.motion(motion(h.engine.currentEpoch(), 1, 2, -300)));
        CensorEngine.ViewportCoverage coverage = h.engine.viewportCoverage();
        assertEquals(CensorEngine.CoverageState.AVAILABLE, coverage.state);
        CensorEngine.DetectedRegion member = coverage.regions.get(0);
        assertEquals(1, member.evidenceSource.sequence); assertEquals(2, member.geometrySource.sequence);
        assertEquals(200, member.displayRegion.top, 0); assertFalse(member.physicalErrorKnown);
        h.engine.close(); h.fastExecutor.runAll(); h.ui.runAll();
    }

    @Test public void viewportExpiryRejectsBeforeLateTimerAndDoesNotRenewOnQueriesOrStyle() {
        Harness h = new Harness(); h.start(); h.fire(0); h.deliver(0, 1);
        h.fastExecutor.runOne(); h.fast.succeed(0, face(500)); h.ui.runAll();
        CensorEngine.ViewportCoverage initial = h.engine.viewportCoverage(); assertEquals(5001, initial.validBeforeMillis);
        h.now = 5000; assertTrue(h.engine.coverageCurrent(initial));
        h.engine.canvasSamplingControls(true);
        CensorEngine.ViewportCoverage styled = h.engine.viewportCoverage(); assertEquals(5001, styled.validBeforeMillis);
        for (int i = 0; i < 50; i++) assertEquals(5001, h.engine.viewportCoverage().validBeforeMillis);
        assertEquals(1, h.evidence.size()); assertEquals(1, h.captures.size());
        h.now = 5001; assertFalse(h.engine.coverageCurrent(styled));
        CensorEngine.ViewportCoverage expired = h.engine.viewportCoverage();
        assertEquals(CensorEngine.CoverageState.EXPIRED, expired.state); assertTrue(expired.regions.isEmpty());
        assertFalse(h.engine.coverageCurrent(expired)); h.engine.close(); h.ui.runAll();
    }

    @Test public void rendererExpiryRefreshProducesExplicitNoEligibleRegionsWithoutNewCapture() {
        Harness h = new Harness(); h.start(); h.fire(0); h.deliver(0, 1);
        h.fastExecutor.runOne(); h.fast.succeed(0, face(500)); h.ui.runAll(); h.now = 5001;
        assertEquals(CensorEngine.CoverageState.EXPIRED, h.engine.viewportCoverage().state);
        try (CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView()) { assertTrue(view.snapshot.groups.isEmpty()); }
        assertEquals(CensorEngine.CoverageState.NO_ELIGIBLE_REGIONS, h.engine.viewportCoverage().state);
        assertEquals(1, h.captures.size()); assertEquals(1, h.evidence.size()); h.engine.close(); h.ui.runAll();
    }

    @Test public void viewportReceiptRejectsForeignOwnerAndScopeAbaIncludingOldWithdrawal() {
        Harness first = new Harness(), other = new Harness(); first.start(); other.start();
        CensorEngine.ViewportCoverage unseen = first.engine.viewportCoverage();
        assertFalse(other.engine.coverageCurrent(unseen));
        first.engine.stop(); CensorEngine.ViewportCoverage stopped = first.engine.viewportCoverage();
        assertTrue(first.engine.coverageCurrent(stopped)); assertFalse(first.engine.coverageCurrent(unseen));
        first.start(); assertFalse(first.engine.coverageCurrent(stopped));
        first.engine.changeScope(42); assertFalse(first.engine.coverageCurrent(unseen));
        first.engine.close(); other.engine.close(); first.ui.runAll(); other.ui.runAll();
    }

    @Test public void textGroupViewportRetainsSeparateInteriorsRatherThanItsUnionHole() {
        Harness h = new Harness(false, true, false, 8, 2, 16); h.start(); h.fire(0); h.deliver(0, 1);
        h.fastExecutor.runOne(); h.fast.succeed(0); h.textExecutor.runOne(); h.text.succeed(0, text(500), text(1000));
        CensorEngine.ViewportCoverage coverage = h.engine.viewportCoverage();
        assertEquals(2, coverage.regions.size()); assertEquals(77, coverage.regions.get(0).textGroup);
        assertEquals(600, coverage.regions.get(0).displayRegion.bottom, 0);
        assertEquals(1000, coverage.regions.get(1).displayRegion.top, 0);
        h.engine.close(); h.ui.runAll();
    }

    @Test public void displayClipUsesHalfOpenBoundsWithoutCoordinateConversion() {
        Harness h = new Harness(); h.start(); h.fire(0); h.deliver(0, 1); h.fastExecutor.runOne();
        h.fast.succeed(0, new SceneEngine.Observation("FACE", 1, 0,
                new SceneEngine.Region(-100, -20, 1200, 2500), .9f), face(2400));
        CensorEngine.ViewportCoverage coverage = h.engine.viewportCoverage(); assertEquals(1, coverage.regions.size());
        SceneEngine.Region clipped = coverage.regions.get(0).displayRegion;
        assertEquals(0, clipped.left, 0); assertEquals(0, clipped.top, 0);
        assertEquals(1000, clipped.right, 0); assertEquals(2400, clipped.bottom, 0);
        SceneEngine.Region window = new SceneEngine.Region(20, 80, 900, 2000);
        SceneEngine.Region withinWindow = clipped.clippedTo(window);
        assertEquals(20, withinWindow.left, 0); assertEquals(80, withinWindow.top, 0);
        assertEquals(900, withinWindow.right, 0); assertEquals(2000, withinWindow.bottom, 0);
        assertNull(new SceneEngine.Region(900, 80, 1000, 2000).clippedTo(window));
        h.engine.close(); h.ui.runAll();
    }

    @Test public void coldReverseAndReversePaintCannotInventOrSuppressDetectedRegions() {
        Harness h = new Harness(); h.start();
        CensorEngine.PaintReceipt cold = h.engine.acknowledgePaint(h.engine.activeScope(), -1, 1,
                Collections.emptyList(), true);
        assertNotNull(cold); assertEquals(CensorEngine.CoverageState.UNOBSERVED, h.engine.viewportCoverage().state);
        h.fire(0); h.deliver(0, 1); h.fastExecutor.runOne(); h.fast.succeed(0, face(500)); h.ui.runAll();
        CensorEngine.ViewportCoverage detected = h.engine.viewportCoverage();
        try (CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView()) {
            CensorEngine.PaintReceipt reverse = h.engine.acknowledgePaint(h.engine.activeScope(), view.snapshot.revision,
                    2, Collections.emptyList(), true);
            assertNotNull(reverse); assertTrue(reverse.members.isEmpty());
        }
        assertTrue(h.engine.coverageCurrent(detected)); assertEquals(1, detected.regions.size());
        assertEquals(1, h.engine.viewportCoverage().regions.size()); h.engine.close();
    }

    @Test public void rejectedPresentationCannotRestoreAnOlderViewportReceipt() {
        Harness h = new Harness(); h.start(); h.fire(0); h.deliver(0, 1);
        h.fastExecutor.runOne(); h.fast.succeed(0, face(500)); h.ui.runAll();
        CensorEngine.ViewportCoverage old = h.engine.viewportCoverage(); h.ui.reject = true;
        h.engine.canvasSamplingControls(true);
        assertFalse(h.engine.coverageCurrent(old));
        CensorEngine.ViewportCoverage constructed = h.engine.viewportCoverage();
        assertEquals(CensorEngine.CoverageState.AVAILABLE, constructed.state);
        assertTrue(h.engine.coverageCurrent(constructed)); assertEquals(1, constructed.regions.size());
        h.ui.reject = false; h.engine.close(); h.ui.runAll();
    }

    @Test public void acceptedSourceReceiptsConsumeFiveFreshFastInputsExactlyOnce() {
        Harness h = new Harness(); h.start();
        for (int i = 0; i < 6; i++) {
            h.fire(i * 334L); h.deliver(i, i + 1);
            if (i > 0) assertTrue(h.engine.motion(new SceneEngine.Motion(h.engine.currentEpoch(), i, i + 1,
                    1, 1, SceneEngine.MotionAuthority.IMAGE_CORRESPONDENCE, SUPPORT, 0, 0)));
            h.fastExecutor.runOne(); h.fast.succeed(i, face(500)); h.ui.runAll();
            CensorEngine.EvidenceReceipt receipt = h.evidence.get(i);
            assertEquals(i + 1, receipt.source.imageRequestIdentity);
            assertEquals(Math.min(5, i + 1), receipt.subjects.get(0).witnesses.size());
            CensorEngine.EvidenceClaim claim = h.engine.claimEvidence(receipt);
            assertNotNull(claim); assertEquals(i == 4 ? 1 : 0, claim.subjects.size());
            assertTrue(h.engine.claimEvidence(receipt).subjects.isEmpty());
            assertFalse(receipt.subjects.get(0).confirmedForConsumption && i < 4);
        }
        assertThrows(UnsupportedOperationException.class, () -> h.evidence.get(4).subjects.clear());
        h.engine.close(); assertEquals(1, h.drained);
    }

    @Test public void foreignOwnerAndOldScopeCannotConsumeAnEqualNumericIdentity() {
        Harness first = fiveSourceHarness(); Harness second = fiveSourceHarness();
        CensorEngine.EvidenceReceipt receipt = first.evidence.get(4);
        assertEquals(receipt.subjects.get(0).identity, second.evidence.get(4).subjects.get(0).identity);
        assertNull(second.engine.claimEvidence(receipt));
        first.engine.changeScope(43, ProtectionScope.normal(9));
        assertNull(first.engine.claimEvidence(receipt));
        first.engine.close(); second.engine.close();
    }

    @Test public void oldReceiptCannotGainEligibilityFromALaterSourceOrClaimAnUpdatedIdentity() {
        Harness h = fiveSourceHarness();
        assertNull(h.engine.claimEvidence(h.evidence.get(3)));
        assertEquals(1, h.engine.claimEvidence(h.evidence.get(4)).subjects.size());
        h.engine.stop(); assertNull(h.engine.claimEvidence(h.evidence.get(4)));
        h.engine.start(42); assertNull(h.engine.claimEvidence(h.evidence.get(4)));
        h.engine.close();
    }

    @Test public void lateMotionMergesDoNotMintAnEvidenceReceiptOrRescueRemovedAliases() {
        Harness h = new Harness(); h.start();
        for (int i = 0; i < 5; i++) {
            h.fire(i * 334L); h.deliver(i, i + 1); h.fastExecutor.runOne(); h.fast.succeed(i, face(500)); h.ui.runAll();
            assertEquals(1, h.evidence.get(i).subjects.get(0).witnesses.size());
        }
        CensorEngine.EvidenceReceipt oldAlias = h.evidence.get(4);
        for (int i = 1; i < 5; i++) assertTrue(h.engine.motion(new SceneEngine.Motion(h.engine.currentEpoch(), i, i + 1,
                1, 1, SceneEngine.MotionAuthority.IMAGE_CORRESPONDENCE, SUPPORT, 0, 0)));
        h.ui.runAll(); assertEquals(5, h.evidence.size()); assertNull(h.engine.claimEvidence(oldAlias));
        h.fire(5 * 334L); h.deliver(5, 6);
        assertTrue(h.engine.motion(new SceneEngine.Motion(h.engine.currentEpoch(), 5, 6,
                1, 1, SceneEngine.MotionAuthority.IMAGE_CORRESPONDENCE, SUPPORT, 0, 0)));
        h.fastExecutor.runOne(); h.fast.succeed(5, face(500)); h.ui.runAll();
        assertEquals(1, h.engine.claimEvidence(h.evidence.get(5)).subjects.size()); h.engine.close();
    }

    @Test public void presentationStyleAndDrawCallbacksCannotSupplyFreshEvidence() {
        Harness h = fiveSourceHarness(); int accepted = h.evidence.size();
        h.engine.canvasSamplingControls(true); h.ui.runAll();
        try (CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView()) {
            h.engine.drawn(view.snapshot.revision);
        }
        h.ui.runAll(); assertEquals(accepted, h.evidence.size());
        assertEquals(1, h.engine.claimEvidence(h.evidence.get(4)).subjects.size());
        h.engine.close();
    }

    @Test public void emptyAcceptedInputHasNoCarriedSubjectAuthorityAndFailedInputHasNoReceipt() {
        Harness h = new Harness(); h.start(); h.fire(0); h.deliver(0, 1); h.fastExecutor.runOne();
        h.fast.succeed(0, face(500)); h.ui.runAll();
        h.fire(334); h.deliver(1, 2); h.fastExecutor.runOne(); h.fast.calls.get(1).completion.failure(); h.ui.runAll();
        assertEquals(1, h.evidence.size());
        h.fire(668); h.deliver(2, 3); h.fastExecutor.runOne(); h.fast.succeed(2); h.ui.runAll();
        assertEquals(2, h.evidence.size()); assertTrue(h.evidence.get(1).subjects.isEmpty());
        assertTrue(h.engine.claimEvidence(h.evidence.get(1)).subjects.isEmpty()); h.engine.close();
    }

    @Test public void expiredReceiptRejectsEvenBeforeALateDisplayTimerRuns() {
        Harness h = fiveSourceHarness(); CensorEngine.EvidenceReceipt receipt = h.evidence.get(4);
        h.now = receipt.subjects.get(0).validUntilMillis + 1;
        assertNull(h.engine.claimEvidence(receipt)); h.engine.close();
    }

    @Test public void sameSourceEmptyOtherLaneReceiptIsExplicitlyASourceStateInventory() {
        Harness h = new Harness(true, false, true, 8, 2, 16);
        h.start(); h.fire(0); h.deliver(0, 1); h.fastExecutor.runOne();
        h.fast.succeed(0, face(500)); h.qualityExecutor.runOne(); h.quality.succeed(0); h.ui.runAll();
        assertEquals(2, h.evidence.size());
        CensorEngine.EvidenceReceipt inventory = h.evidence.get(1);
        assertEquals(QUALITY, inventory.triggerLane);
        assertEquals(1, inventory.subjects.size());
        assertEquals(FAST, inventory.subjects.get(0).evidenceLane);
        assertEquals(1, inventory.subjects.get(0).witnesses.size());
        assertFalse(inventory.subjects.get(0).confirmedForConsumption);
        assertTrue(h.engine.claimEvidence(inventory).subjects.isEmpty()); h.engine.close();
    }

    @Test public void reentrantAcceptanceRevocationCannotDeliverAnEvidenceCapability() {
        Harness h = new Harness(); h.acceptHook = ignored -> h.engine.stop();
        h.start(); h.fire(0); h.deliver(0, 1); h.fastExecutor.runOne(); h.fast.succeed(0, face(500)); h.ui.runAll();
        assertEquals(1, h.accepted.size()); assertTrue(h.evidence.isEmpty()); h.engine.close();
    }

    @Test public void originalEvidenceClaimDeliversOnceOnlyToItsBoundConsumer() {
        Harness h = fiveSourceHarness(); Object consumer = new Object();
        CensorEngine.EvidenceClaim claim = h.engine.claimEvidence(h.evidence.get(4), consumer);
        assertNull(claim.takeFor(null)); assertNull(claim.takeFor(new Object()));
        assertSame(claim.subjects, claim.takeFor(consumer)); assertNull(claim.takeFor(consumer));
        CensorEngine.EvidenceClaim freshWrapper = h.engine.claimEvidence(h.evidence.get(4), consumer);
        assertTrue(freshWrapper.takeFor(consumer).isEmpty()); h.engine.close();
    }

    @Test public void unboundCoreClaimCannotBecomeAConsumerCapability() {
        Harness h = fiveSourceHarness(); CensorEngine.EvidenceClaim claim = h.engine.claimEvidence(h.evidence.get(4));
        assertEquals(1, claim.subjects.size()); assertNull(claim.takeFor(new Object())); assertNull(claim.takeFor(null)); h.engine.close();
    }

    @Test public void concurrentOriginalClaimDeliveryHasExactlyOneWinner() throws Exception {
        Harness h = fiveSourceHarness(); Object consumer = new Object();
        CensorEngine.EvidenceClaim claim = h.engine.claimEvidence(h.evidence.get(4), consumer);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(4), go = new java.util.concurrent.CountDownLatch(1);
        java.util.List<java.util.concurrent.Future<Integer>> attempts = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < 4; i++) attempts.add(pool.submit(() -> {
                ready.countDown(); assertTrue(go.await(5, java.util.concurrent.TimeUnit.SECONDS));
                return claim.takeFor(consumer) == null ? 0 : 1;
            }));
            assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)); go.countDown(); int winners = 0;
            for (java.util.concurrent.Future<Integer> attempt : attempts) winners += attempt.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(1, winners);
        } finally { go.countDown(); pool.shutdownNow(); h.engine.close(); }
    }

    private static Harness fiveSourceHarness() {
        Harness h = new Harness(); h.start();
        for (int i = 0; i < 5; i++) {
            h.fire(i * 334L); h.deliver(i, i + 1);
            if (i > 0) assertTrue(h.engine.motion(new SceneEngine.Motion(h.engine.currentEpoch(), i, i + 1,
                    1, 1, SceneEngine.MotionAuthority.IMAGE_CORRESPONDENCE, SUPPORT, 0, 0)));
            h.fastExecutor.runOne(); h.fast.succeed(i, face(500)); h.ui.runAll();
        }
        return h;
    }

    @Test public void independentImageOcrBDoesNotStealBlockedFastCanvasA() {
        Harness h = new Harness(false, true, false, 8, 2, 16);
        h.start(); h.fire(0); Pixel a = h.deliver(0, 1);
        h.fastExecutor.runOne(); h.textExecutor.runOne();
        h.text.succeed(0); h.fast.succeed(0, face(500)); h.ui.runAll();
        h.fire(334); Pixel b = h.deliver(1, 2);
        h.fastExecutor.runOne(); h.textExecutor.runOne();
        SceneEngine.Observation ocr = new SceneEngine.Observation("TEXT", 3, 88,
                new SceneEngine.Region(100, 1000, 500, 1100), .9f);
        h.text.calls.get(1).completion.success(SceneEngine.Batch.imageText(
                h.text.calls.get(1).input.source, 3, Collections.singletonList(ocr)), TIMING); h.ui.runAll();
        try (CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView()) {
            assertEquals("OCR cannot advance the visual canvas without correspondence", 1,
                    view.snapshot.destination.sequence);
            List<SceneEngine.Mask> values = masks(view.snapshot);
            assertEquals(2, values.size());
            SceneEngine.Mask face = values.stream().filter(m -> m.category.equals("FACE")).findFirst().get();
            SceneEngine.Mask text = values.stream().filter(m -> m.category.equals("TEXT")).findFirst().get();
            assertEquals(500, face.displayRegion.top, 0); assertEquals(1, face.geometrySource.sequence);
            assertEquals(1000, text.displayRegion.top, 0); assertEquals(2, text.geometrySource.sequence);
            assertSame(a, view.sourcePixels(1)); assertSame(b, view.sourcePixels(2));
            assertFalse(face.estimatedMotion); assertFalse(text.estimatedMotion);
        } finally { h.engine.close(); h.fast.calls.get(1).completion.cancelled(); }
        assertEquals(1, a.closes); assertEquals(1, b.closes); assertEquals(1, h.drained);
    }

    @Test public void firstFastAReclaimsItsCanvasAfterEarlierTextBWithoutErasingBText() {
        Harness h = new Harness(false, true, false, 8, 2, 16);
        h.start(); h.fire(0); h.deliver(0, 1); h.fastExecutor.runOne(); h.textExecutor.runOne();
        h.text.calls.get(0).completion.failure();
        h.fire(334); h.deliver(1, 2); h.textExecutor.runOne();
        WorkCall call = h.text.calls.get(1);
        call.completion.success(SceneEngine.Batch.imageText(call.input.source, 3, Collections.singletonList(
                new SceneEngine.Observation("TEXT", 3, 1, new SceneEngine.Region(100, 1000, 500, 1100), .9f))), TIMING);
        h.ui.runAll();
        try (CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView()) { assertEquals(2, view.snapshot.destination.sequence); }
        h.fast.succeed(0, face(500)); h.ui.runAll();
        try (CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView()) {
            assertEquals(1, view.snapshot.destination.sequence); assertEquals(2, masks(view.snapshot).size());
        }
        h.engine.close(); h.fastExecutor.runAll(); assertEquals(1, h.drained);
    }

    @Test public void failedOcrKeepsTheLastCompleteSetButSuccessfulEmptyClearsOnlyOcr() {
        Harness h = new Harness(false, true, false, 8, 2, 16);
        h.start(); h.fire(0); h.deliver(0, 1); h.fastExecutor.runOne(); h.textExecutor.runOne();
        h.fast.succeed(0, face(500));
        WorkCall a = h.text.calls.get(0);
        a.completion.success(SceneEngine.Batch.imageText(a.input.source, 3, Collections.singletonList(
                new SceneEngine.Observation("TEXT", 3, 1, new SceneEngine.Region(100, 1000, 500, 1100), .9f))), TIMING); h.ui.runAll();
        h.fire(334); h.deliver(1, 2); h.fastExecutor.runOne(); h.textExecutor.runOne();
        h.text.calls.get(1).completion.failure(); h.ui.runAll();
        try (CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView()) { assertEquals(2, masks(view.snapshot).size()); }
        h.fire(668); h.deliver(2, 3); h.textExecutor.runOne();
        WorkCall c = h.text.calls.get(2);
        c.completion.success(SceneEngine.Batch.imageText(c.input.source, 3, Collections.emptyList()), TIMING);
        h.fast.succeed(1, face(500)); h.ui.runAll();
        try (CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView()) {
            assertEquals(1, masks(view.snapshot).size()); assertEquals("FACE", masks(view.snapshot).get(0).category);
        }
        h.engine.close(); h.fastExecutor.runAll(); assertEquals(1, h.drained);
    }

    private static ImagePresentationEstimate imageHint(Harness h, long from, long to, double dy) {
        return new ImagePresentationEstimate(h.engine.currentEpoch(), from, to, 1, 1,
                Collections.singletonList(new ImagePresentationEstimate.Piece(
                        new SceneEngine.Region(100, 500, 200, 600), 0, 0, dy, dy)));
    }

    @Test public void pixelTextAliasIsNotIndependentNativeAcquisition() {
        Harness h = new Harness(false, true, false, 8, 2, 16);
        h.start(); h.fire(0); h.deliver(0, 1); h.fastExecutor.runOne(); h.textExecutor.runOne();
        h.text.succeed(0); h.fast.succeed(0, face(500)); h.ui.runAll();
        h.fire(334); h.deliver(1, 2); h.fastExecutor.runOne(); h.textExecutor.runOne();
        h.text.succeed(1, text(1000)); h.ui.runAll();
        try (CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView()) {
            // IMAGE/TEXT now has its own basis, but remains unsafe as a native-tree alias.
            assertEquals(1, view.snapshot.destination.sequence);
            assertEquals(2, masks(view.snapshot).size());
            SceneEngine.Mask imageText = masks(view.snapshot).stream()
                    .filter(m -> m.category.equals("TEXT")).findFirst().get();
            assertEquals(SceneEngine.SourceKind.IMAGE, imageText.evidenceSource.kind);
            assertEquals(2, imageText.geometrySource.sequence);
        } finally { h.engine.close(); h.fast.calls.get(1).completion.cancelled(); }
    }

    @Test public void imageHintBeforeFirstFastUsesActualBWithoutBAiOrBEffectPixels() {
        Harness h = new Harness(); h.start(); h.fire(0); Pixel a = h.deliver(0, 1); h.fastExecutor.runOne();
        h.fire(334); Pixel b = h.deliver(1, 2);
        assertTrue(h.engine.presentationEstimate(imageHint(h, 1, 2, -400)));
        assertEquals(0, h.engine.renderBasisCount()); assertEquals(1, h.fast.calls.size());
        h.fast.succeed(0, face(500)); h.ui.runAll();
        try (CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView()) {
            SceneEngine.Mask mask = view.snapshot.groups.get(0).members.get(0);
            assertEquals(1, view.snapshot.destination.sequence);
            assertEquals(100, mask.displayRegion.top, 0); assertEquals(2, mask.geometrySource.sequence);
            assertTrue(mask.imageEstimated); assertSame(a, view.sourcePixels(1)); assertNull(view.sourcePixels(2));
        }
        assertFalse(a.closed); assertFalse(b.closed);
        h.engine.close(); h.fastExecutor.runAll();
        assertEquals(1, a.closes); assertEquals(1, b.closes);
    }

    @Test public void lateEmptyFastKeepsTypedImageHoldWithoutRollingBackOrBorrowingPixels() {
        Harness h = new Harness(); h.start(); h.fire(0); Pixel a = h.deliver(0, 1); h.fastExecutor.runOne();
        h.fire(334); Pixel b = h.deliver(1, 2); h.engine.presentationEstimate(imageHint(h, 1, 2, -400));
        h.fast.succeed(0, face(500)); h.ui.runAll(); h.fastExecutor.runOne(); h.fast.succeed(1); h.ui.runAll();
        h.fire(668); Pixel c = h.deliver(2, 3); h.fastExecutor.runOne(); h.fast.succeed(2); h.ui.runAll();
        try (CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView()) {
            SceneEngine.Mask mask = view.snapshot.groups.get(0).members.get(0);
            assertEquals(3, view.snapshot.destination.sequence); assertEquals(2, mask.geometrySource.sequence);
            assertEquals(100, mask.displayRegion.top, 0); assertTrue(mask.imageEstimated); assertTrue(mask.heldGeometry);
            assertEquals(1, mask.evidenceSource.sequence); assertSame(a, view.sourcePixels(1));
            assertNull(view.sourcePixels(2)); assertNull(view.sourcePixels(3));
        }
        assertEquals(0, h.engine.renderBasisCount()); h.engine.close();
        assertEquals(1, a.closes); assertEquals(1, b.closes); assertEquals(1, c.closes);
    }

    @Test public void aRetainedOldDrawRevisionCannotDescribeANewImageOnlyPresentation() {
        Harness h = new Harness(); h.start(); h.fire(0); Pixel a = h.deliver(0, 1);
        h.fastExecutor.runOne(); h.fast.succeed(0, face(500)); h.ui.runAll();
        CensorEngine<Pixel>.RenderView old = h.engine.acquireRenderView();
        h.fire(334); Pixel b = h.deliver(1, 2); assertTrue(h.engine.presentationEstimate(imageHint(h, 1, 2, -400)));
        h.ui.runAll();
        try (CensorEngine<Pixel>.RenderView current = h.engine.acquireRenderView()) {
            assertNotEquals(old.snapshot.revision, current.snapshot.revision);
            long before = h.traces.stream().filter(t -> t.stage == CensorEngine.Stage.DRAWN).count();
            h.engine.drawn(old.snapshot.revision);
            assertEquals(before, h.traces.stream().filter(t -> t.stage == CensorEngine.Stage.DRAWN).count());
            h.engine.drawn(current.snapshot.revision);
            assertEquals(before + 1, h.traces.stream().filter(t -> t.stage == CensorEngine.Stage.DRAWN).count());
        }
        h.engine.close(); assertFalse(a.closed); old.close(); h.fastExecutor.runAll();
        assertEquals(1, a.closes); assertEquals(1, b.closes);
    }

    @Test public void stoppedScopeRejectsOldImageHintsAndKeepsActualReadLifetime() {
        Harness h = new Harness(); h.start(); h.fire(0); Pixel a = h.deliver(0, 1); h.fastExecutor.runOne();
        h.fire(334); Pixel b = h.deliver(1, 2); ImagePresentationEstimate hint = imageHint(h, 1, 2, -400);
        h.engine.presentationEstimate(hint); h.fast.succeed(0, face(500)); h.ui.runAll();
        CensorEngine<Pixel>.RenderView held = h.engine.acquireRenderView();
        h.engine.changeScope(77); assertFalse(h.engine.presentationEstimate(hint)); assertNull(h.engine.acquireRenderView());
        assertFalse(a.closed); assertSame(a, held.sourcePixels(1)); held.close();
        h.engine.close(); h.fastExecutor.runAll(); assertEquals(1, a.closes); assertEquals(1, b.closes);
    }

    @Test public void lateBTextCannotRetargetTheActualCImageOrReadmitAnObsoleteHint() {
        Harness h = new Harness(false, true, false, 8, 2, 16);
        h.start(); h.fire(0); Pixel a = h.deliver(0, 1); h.fastExecutor.runOne(); h.textExecutor.runOne();
        h.text.succeed(0); h.fast.succeed(0, face(500)); h.ui.runAll();
        h.fire(334); Pixel b = h.deliver(1, 2); h.fastExecutor.runOne(); h.textExecutor.runOne();
        ImagePresentationEstimate old = imageHint(h, 1, 2, -400);
        assertTrue(h.engine.presentationEstimate(old)); h.ui.runAll();
        h.fire(668); Pixel c = h.deliver(2, 3);
        assertFalse(h.engine.presentationEstimate(old)); h.text.succeed(1); h.ui.runAll();
        try (CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView()) {
            SceneEngine.Mask mask = view.snapshot.groups.get(0).members.get(0);
            assertTrue(mask.imageEstimated); assertTrue(mask.heldGeometry);
            assertEquals(2, mask.geometrySource.sequence); assertEquals(100, mask.displayRegion.top, 0);
            assertSame(a, view.sourcePixels(1)); assertNull(view.sourcePixels(2)); assertNull(view.sourcePixels(3));
        }
        h.fast.succeed(1); h.engine.close(); h.fastExecutor.runAll(); h.textExecutor.runAll();
        assertEquals(1, a.closes); assertEquals(1, b.closes); assertEquals(1, c.closes);
    }

    @Test public void displayDimensionChangeFencesOldImagePoseWithoutStrandingCapturePixels() {
        Harness h = new Harness(); h.start(); h.fire(0); Pixel a = h.deliver(0, 1);
        h.fastExecutor.runOne(); h.fast.succeed(0, face(500)); h.ui.runAll();
        h.fire(334); Pixel b = h.deliver(1, 2); h.fastExecutor.runOne();
        ImagePresentationEstimate oldHint = imageHint(h, 1, 2, -400);
        assertTrue(h.engine.presentationEstimate(oldHint)); h.ui.runAll();
        CensorEngine<Pixel>.RenderView held = h.engine.acquireRenderView();
        long oldEpoch = h.engine.currentEpoch();
        h.fire(668); Pixel changed = new Pixel(3);
        h.captures.get(2).callback.success(changed.payload(900, 1600));
        assertEquals(oldEpoch + 1, h.engine.currentEpoch());
        assertEquals(1, changed.closes); assertFalse(h.engine.captureInFlight());
        assertNull(h.engine.acquireRenderView()); assertFalse(h.engine.presentationEstimate(oldHint));
        assertEquals(0, h.engine.pendingCount(FAST)); assertTrue(h.fast.calls.get(1).input.cancelled());
        assertFalse(a.closed); assertFalse(b.closed); assertSame(a, held.sourcePixels(1));
        h.fast.calls.get(1).completion.cancelled(); assertEquals(1, b.closes);
        assertEquals(1002, h.timer.nextAt()); h.fire(1002); Pixel fresh = new Pixel(4);
        h.captures.get(3).callback.success(fresh.payload(900, 1600)); h.fastExecutor.runOne();
        assertEquals(oldEpoch + 1, h.fast.calls.get(2).input.source.epoch);
        h.fast.succeed(2, face(200)); h.ui.runAll();
        try (CensorEngine<Pixel>.RenderView current = h.engine.acquireRenderView()) {
            assertEquals(900, current.snapshot.destination.width); assertEquals(1600, current.snapshot.destination.height);
            assertFalse(masks(current.snapshot).get(0).imageEstimated); assertSame(fresh, current.sourcePixels(4));
        }
        assertEquals(0, h.engine.callbackFailures()); h.engine.close(); assertEquals(0, h.drained);
        held.close(); assertEquals(1, a.closes); assertEquals(1, fresh.closes);
        assertEquals(0, h.engine.livePixelFrames()); assertEquals(1, h.drained);
    }

    @Test public void callbackBeforeApiReturnKeepsSlotAndSpacingFromActualReturn() {
        Harness h = new Harness(); Pixel pixel = new Pixel(1);
        h.behavior = (request, callback) -> {
            assertTrue(request.tryDispatch());
            callback.success(pixel.payload());
            assertTrue(h.engine.captureInFlight()); assertEquals(1, h.engine.livePixelFrames());
            h.engine.requestCaptureAt(0); assertEquals(Long.MAX_VALUE, h.timer.nextAt());
            h.now = 20;
        };
        h.start(); h.fire(0);
        assertFalse(h.engine.captureInFlight()); assertEquals(354, h.timer.nextAt());
        assertEquals(1, h.fastExecutor.size()); assertFalse(pixel.closed);
        h.engine.close(); assertEquals(1, pixel.closes); h.ui.runAll();
    }

    @Test public void foregroundResolutionDoesNotOwnTheDispatchClock() {
        Harness h = new Harness();
        h.behavior = (request, callback) -> {
            h.now = 90; assertTrue(request.tryDispatch()); callback.failure(0); h.now = 120;
        };
        h.start(); h.fire(0);
        assertEquals(454, h.timer.nextAt());
    }

    @Test public void stopRestartWaitsForOldCallbackAndRejectsItsPixels() {
        Harness h = new Harness(); h.start(); h.fire(0);
        CaptureCall old = h.captures.get(0);
        h.engine.stop(); h.now = 50; h.engine.start(42);
        assertTrue(h.engine.captureInFlight()); assertEquals(Long.MAX_VALUE, h.timer.nextAt());
        Pixel oldPixel = h.deliver(0, 1); assertEquals(1, oldPixel.closes);
        assertEquals(334, h.timer.nextAt()); h.fire(334);
        Pixel duplicate = new Pixel(2); old.callback.success(duplicate.payload());
        assertEquals(1, duplicate.closes); assertTrue(h.engine.captureInFlight());
        Pixel current = h.deliver(1, 3); h.fastExecutor.runOne();
        assertEquals(h.engine.currentEpoch(), h.fast.calls.get(0).input.source.epoch);
        h.engine.close(); assertFalse(current.closed); h.fast.calls.get(0).completion.cancelled();
        assertEquals(1, current.closes);
    }

    @Test public void stoppedOwnerMayNotDispatchAfterForegroundResolution() {
        Harness h = new Harness();
        h.behavior = (request, callback) -> {
            h.engine.stop(); h.engine.start(42); assertFalse(request.tryDispatch());
        };
        h.start(); h.fire(0); assertFalse(h.engine.captureInFlight());
        assertEquals(0, h.traces.stream().filter(t -> t.stage == CensorEngine.Stage.CAPTURE_DISPATCHED).count());
    }

    @Test public void oldOrCancelledTimersCannotRequestNewCapture() {
        Harness h = new Harness(); h.start(); TimedTask old = h.timer.tasks.get(0);
        h.engine.stop(); h.engine.start(42); old.action.run();
        assertEquals(0, h.captures.size()); h.fire(0); assertEquals(1, h.captures.size());
        h.engine.close(); h.captures.get(0).callback.failure(0);
    }

    @Test public void failureBackoffSurvivesScrollWakeAndStopRestart() {
        Harness h = new Harness();
        h.behavior = (request, callback) -> callback.failure(600);
        h.start(); h.fire(0); h.engine.requestCaptureAt(0);
        assertEquals(600, h.timer.nextAt()); h.engine.stop(); h.engine.start(42);
        assertEquals(600, h.timer.nextAt());
    }

    @Test public void rejectedTimerHasNoPhantomDeadlineAndCanBeRetried() {
        Harness h = new Harness(); h.timer.reject = true; h.start();
        assertEquals(Long.MAX_VALUE, h.timer.nextAt()); assertEquals(1, h.engine.callbackFailures());
        h.timer.reject = false; h.engine.requestCaptureAt(0); h.fire(0);
        assertEquals(1, h.captures.size()); h.engine.close(); h.captures.get(0).callback.failure(0);
    }

    @Test public void duplicateTransferredPayloadCannotDisposeAnExecutingSource() {
        Harness h = new Harness(); h.start(); h.fire(0);
        Pixel pixel = new Pixel(1); CensorEngine.Captured<Pixel> payload = pixel.payload();
        h.captures.get(0).callback.success(payload); h.fastExecutor.runOne();
        h.captures.get(0).callback.success(payload); payload.close();
        assertFalse(pixel.closed);
        Pixel freshDuplicate = new Pixel(2); h.captures.get(0).callback.success(freshDuplicate.payload());
        assertEquals(1, freshDuplicate.closes); assertFalse(pixel.closed);
        h.engine.close(); h.fast.calls.get(0).completion.cancelled();
        assertEquals(1, pixel.closes);
    }

    @Test public void newerCapturesReplaceOnlyPendingFastNotExecutingFast() {
        Harness h = new Harness(); h.start(); h.fire(0); Pixel first = h.deliver(0, 1); h.fastExecutor.runOne();
        h.fire(334); Pixel second = h.deliver(1, 2);
        h.fire(668); Pixel third = h.deliver(2, 3);
        assertFalse(first.closed); assertEquals(1, second.closes); assertFalse(third.closed);
        assertEquals(0, h.fast.cancellations); assertEquals(1, h.engine.pendingCount(FAST));
        h.fast.succeed(0, face(500)); h.ui.runAll();
        try (CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView()) {
            assertEquals(1, view.snapshot.destination.sequence); assertSame(first, view.sourcePixels(1));
        }
        h.fastExecutor.runOne(); assertEquals(3, h.fast.calls.get(1).input.source.sequence);
        h.engine.close(); assertFalse(third.closed); h.fast.calls.get(1).completion.cancelled();
        assertEquals(1, first.closes); assertEquals(1, third.closes);
    }

    @Test public void actualAsyncCompletionAfterStopOwnsFinalCleanup() {
        Harness h = new Harness(); h.start(); h.fire(0); Pixel pixel = h.deliver(0, 1); h.fastExecutor.runOne();
        WorkCall work = h.fast.calls.get(0); h.engine.close();
        assertTrue(work.input.cancelled()); assertFalse(pixel.closed); assertEquals(0, h.drained);
        work.completion.success(batch(work.input, FAST, face(500)), TIMING);
        assertEquals(1, pixel.closes); assertEquals(1, h.drained); assertNull(h.engine.acquireRenderView());
        work.completion.failure(); work.completion.cancelled(); assertEquals(1, pixel.closes);
    }

    @Test public void executorQueuedJobCanBeRetiredWithoutStartingOrReadingFreedPixels() {
        Harness h = new Harness(); h.start(); h.fire(0); Pixel pixel = h.deliver(0, 1);
        assertEquals(1, h.fastExecutor.size()); h.engine.close(); assertEquals(1, pixel.closes);
        h.fastExecutor.runAll(); assertEquals(0, h.fast.calls.size()); assertEquals(1, h.drained);
    }

    @Test public void rejectedWorkerExecutorReleasesOnlyItsOwnedInput() {
        Harness h = new Harness(); h.fastExecutor.reject = true; h.start(); h.fire(0);
        Pixel pixel = h.deliver(0, 1); assertEquals(1, pixel.closes);
        assertEquals(0, h.engine.activeCount(FAST)); assertEquals(0, h.engine.pendingCount(FAST));
        assertTrue(h.traces.stream().anyMatch(t -> t.stage == CensorEngine.Stage.JOB_REJECTED));
    }

    @Test public void failureCancellationAndStartExceptionAreTerminalExactlyOnce() {
        for (int terminal = 0; terminal < 3; terminal++) {
            Harness h = new Harness(); h.start(); h.fire(0); Pixel pixel = h.deliver(0, 1);
            if (terminal == 2) h.fast.throwBeforeLaunch = true;
            h.fastExecutor.runOne();
            if (terminal != 2) {
                WorkCall work = h.fast.calls.get(0);
                if (terminal == 0) work.completion.failure(); else work.completion.cancelled();
                work.completion.failure();
            }
            assertEquals(1, pixel.closes); assertEquals(0, h.engine.activeCount(FAST));
        }
    }

    @Test public void resultMustUseItsExactInputSourceAndLane() {
        Harness h = new Harness(); h.start(); h.fire(0); Pixel pixel = h.deliver(0, 1); h.fastExecutor.runOne();
        WorkCall work = h.fast.calls.get(0); SceneEngine.Source s = work.input.source;
        SceneEngine.Source forged = new SceneEngine.Source(s.epoch, s.sequence, s.geometry, s.width, s.height,
                s.receivedAtMillis, s.pixelAtMillis);
        work.completion.success(new SceneEngine.Batch(forged, FAST, Collections.singletonList(face(500)),
                Collections.emptyList()), TIMING);
        assertEquals(1, pixel.closes); assertNull(h.engine.acquireRenderView());
        assertTrue(h.traces.stream().anyMatch(t -> t.stage == CensorEngine.Stage.RESULT_REJECTED));
        h.fire(334); Pixel next = h.deliver(1, 2); h.fastExecutor.runOne();
        WorkCall second = h.fast.calls.get(1); second.completion.success(batch(second.input, QUALITY, face(500)), TIMING);
        assertEquals(1, next.closes); assertNull(h.engine.acquireRenderView());
    }

    @Test public void blockedUiCoalescesBeforePostingAndReleasesReplacedEffects() {
        Harness h = new Harness(); h.start(); List<Pixel> pixels = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            h.fire(i * 334L); pixels.add(h.deliver(i, i + 1)); h.fastExecutor.runOne(); h.fast.succeed(i, face(500));
        }
        assertEquals(1, h.ui.size()); assertEquals(0, h.presentations);
        for (int i = 0; i < 9; i++) assertEquals(1, pixels.get(i).closes);
        assertEquals(0, pixels.get(9).closes);
        h.ui.runOne(); assertEquals(1, h.presentations);
        try (CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView()) {
            assertEquals(10, view.snapshot.destination.sequence); assertSame(pixels.get(9), view.sourcePixels(10));
        }
        h.engine.close(); assertEquals(1, pixels.get(9).closes);
    }

    @Test public void rejectedUiPublicationDoesNotLeakEffectsOrReplaceCurrentScene() {
        Harness h = new Harness(); h.ui.reject = true; h.start(); h.fire(0);
        Pixel pixel = h.deliver(0, 1); h.fastExecutor.runOne(); h.fast.succeed(0, face(500));
        assertEquals(1, pixel.closes); assertNull(h.engine.acquireRenderView());
        assertTrue(h.traces.stream().anyMatch(t -> t.stage == CensorEngine.Stage.PRESENTATION_REJECTED));
    }

    @Test public void mixedOriginEffectSourcesAndDrawLeaseSurviveOwnerClose() {
        Harness h = new Harness(); h.start(); h.fire(0); Pixel first = h.deliver(0, 1);
        h.fastExecutor.runOne(); h.fast.succeed(0, face(500), face(900)); h.ui.runAll();
        h.fire(334); Pixel second = h.deliver(1, 2); h.fastExecutor.runOne();
        assertTrue(h.engine.motion(motion(h.engine.currentEpoch(), 1, 2, -400)));
        h.fast.succeed(1, face(100)); h.ui.runAll();
        CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView();
        assertSame(first, view.sourcePixels(1)); assertSame(second, view.sourcePixels(2));
        List<SceneEngine.Mask> masks = masks(view.snapshot); assertEquals(2, masks.size());
        SceneEngine.Mask retained = masks.stream().filter(m -> m.evidenceSource.sequence == 1).findFirst().get();
        assertEquals(900, retained.evidenceRegion.top, 0); assertEquals(500, retained.displayRegion.top, 0);
        h.engine.close(); assertFalse(first.closed); assertFalse(second.closed); assertEquals(0, h.drained);
        view.close(); view.close(); assertEquals(1, first.closes); assertEquals(1, second.closes); assertEquals(1, h.drained);
        assertThrows(IllegalStateException.class, () -> view.sourcePixels(1));
    }

    @Test public void effectLimitMeansExplicitMissingSourceNotSamplingTheNewestPacket() {
        Harness h = new Harness(false, false, false, 8, 1, 16); h.start(); h.fire(0); Pixel first = h.deliver(0, 1);
        h.fastExecutor.runOne(); h.fast.succeed(0, face(500), face(900)); h.ui.runAll();
        h.fire(334); Pixel second = h.deliver(1, 2); h.fastExecutor.runOne();
        h.engine.motion(motion(h.engine.currentEpoch(), 1, 2, -400)); h.fast.succeed(1, face(100)); h.ui.runAll();
        try (CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView()) {
            assertNull(view.sourcePixels(1)); assertSame(second, view.sourcePixels(2));
            assertEquals(2, masks(view.snapshot).size());
        }
        assertEquals(1, first.closes); h.engine.close(); assertEquals(1, second.closes);
    }

    @Test public void pixelQuotaBackpressuresAcquisitionUntilActualLeaseRelease() {
        Harness h = new Harness(false, false, false, 2, 2, 16); h.start(); h.fire(0); Pixel first = h.deliver(0, 1);
        h.fastExecutor.runOne(); h.fast.succeed(0, face(500)); h.ui.runAll();
        h.fire(334); Pixel second = h.deliver(1, 2); h.fastExecutor.runOne();
        h.now = 668; h.engine.requestCaptureAt(h.now);
        // Capture is still backpressured, but the independent display expiry remains scheduled.
        assertEquals(2, h.captures.size()); assertEquals(5001, h.timer.nextAt());
        assertEquals(2, h.engine.livePixelFrames());
        h.fast.succeed(1, face(500)); assertFalse(first.closed); h.ui.runAll();
        assertEquals(1, first.closes); assertEquals(668, h.timer.nextAt()); h.fire(668);
        assertEquals(3, h.captures.size()); assertFalse(second.closed);
        h.engine.close(); h.captures.get(2).callback.failure(0); assertEquals(1, second.closes);
    }

    @Test public void sceneSourceQuotaRejectsAndReleasesNewPayloadWithoutDroppingExecutingJob() {
        Harness h = new Harness(false, false, false, 8, 0, 2); h.start(); h.fire(0); Pixel first = h.deliver(0, 1);
        h.fastExecutor.runOne(); h.fire(334); Pixel second = h.deliver(1, 2);
        h.fire(668); Pixel rejected = h.deliver(2, 3);
        assertEquals(1, rejected.closes); assertFalse(first.closed); assertFalse(second.closed);
        assertEquals(1, h.engine.activeCount(FAST)); assertEquals(1, h.engine.pendingCount(FAST));
        assertTrue(h.traces.stream().anyMatch(t -> t.stage == CensorEngine.Stage.SOURCE_REJECTED));
        h.engine.close(); h.fast.calls.get(0).completion.cancelled();
        assertEquals(1, first.closes); assertEquals(1, second.closes);
    }

    @Test public void serializedQualityFinishesBeforeLatestFastAndNextQuality() {
        Harness h = new Harness(true, false, false, 8, 2, 16); h.start(); h.fire(0); Pixel first = h.deliver(0, 1);
        h.fastExecutor.runOne(); assertEquals(0, h.qualityExecutor.size());
        h.fast.succeed(0, face(500)); h.ui.runAll(); h.qualityExecutor.runOne();
        WorkCall quality = h.quality.calls.get(0);
        h.fire(334); Pixel second = h.deliver(1, 2);
        h.fire(668); Pixel third = h.deliver(2, 3);
        assertFalse(quality.input.cancelled()); assertEquals(0, h.quality.cancellations);
        assertEquals(0, h.engine.activeCount(FAST)); assertEquals(1, h.engine.pendingCount(FAST));
        assertEquals(1, h.engine.pendingCount(QUALITY)); assertEquals(1, second.closes);
        assertFalse(first.closed); assertEquals(1, h.engine.activeCount(QUALITY));
        h.quality.succeed(0, face(500)); h.fastExecutor.runOne();
        assertEquals(1, h.accepted.stream().filter(batch -> batch.lane == QUALITY
                && batch.source == quality.input.source).count());
        assertEquals(3, h.fast.calls.get(1).input.source.sequence);
        assertEquals(0, h.qualityExecutor.size()); assertEquals(1, h.quality.calls.size());
        h.fast.succeed(1, face(100)); h.qualityExecutor.runOne();
        assertEquals(3, h.quality.calls.get(1).input.source.sequence);
        h.engine.close(); assertFalse(third.closed); h.quality.calls.get(1).completion.cancelled();
        assertEquals(1, first.closes); assertEquals(1, second.closes); assertEquals(1, third.closes);
        assertEquals(1, h.drained);
    }

    @Test public void serializedQualityStillCancelsOnScopeDisableAndStopWithoutEarlyRelease() {
        for (int control = 0; control < 3; control++) {
            Harness h = new Harness(true, false, false, 8, 2, 16); h.start(); h.fire(0);
            Pixel first = h.deliver(0, 1); h.fastExecutor.runOne(); h.fast.succeed(0, face(500));
            h.ui.runAll(); h.qualityExecutor.runOne(); WorkCall quality = h.quality.calls.get(0);
            h.fire(334); Pixel second = h.deliver(1, 2);
            if (control == 0) h.engine.changeScope(43);
            else if (control == 1) {
                h.engine.executionControls(334, false, Collections.singleton(FAST));
                h.engine.executionControls(334, false, java.util.EnumSet.of(FAST, QUALITY));
            } else h.engine.stop();
            assertTrue(quality.input.cancelled()); assertEquals(1, quality.cancellationSignals);
            assertFalse(first.closed); assertEquals(1, h.engine.activeCount(QUALITY));
            assertEquals(0, h.fastExecutor.size());
            h.quality.succeed(0, face(100));
            assertEquals(0, h.accepted.stream().filter(batch -> batch.lane == QUALITY).count());
            h.engine.close(); h.fastExecutor.runAll();
            assertEquals(1, first.closes); assertEquals(1, second.closes); assertEquals(1, h.drained);
        }
    }

    @Test public void serializedQueuedQualityKeepsAdmissionButStopPreventsReadingReleasedPixels() {
        Harness h = new Harness(true, false, false, 8, 0, 16); h.start(); h.fire(0);
        Pixel first = h.deliver(0, 1); h.fastExecutor.runOne(); h.fast.succeed(0, face(500));
        assertEquals(1, h.qualityExecutor.size());
        h.fire(334); Pixel second = h.deliver(1, 2);
        assertEquals(0, h.fastExecutor.size()); assertEquals(1, h.engine.pendingCount(FAST));
        assertFalse(first.closed);
        h.engine.stop(); h.qualityExecutor.runAll(); h.fastExecutor.runAll(); h.engine.close();
        assertTrue(h.quality.calls.isEmpty()); assertEquals(1, first.closes); assertEquals(1, second.closes);
        assertEquals(1, h.drained);
    }

    @Test public void switchingParallelAdmissionToSerialWaitsForBothActualVisualTerminals() {
        Harness h = new Harness(true, false, true, 8, 0, 16); h.start(); h.fire(0); h.deliver(0, 1);
        h.fastExecutor.runOne(); h.fast.succeed(0, face(500)); h.qualityExecutor.runOne();
        h.fire(334); h.deliver(1, 2); h.fastExecutor.runOne();
        h.engine.executionControls(334, false, java.util.EnumSet.of(FAST, QUALITY));
        h.fire(668); h.deliver(2, 3);
        assertFalse(h.quality.calls.get(0).input.cancelled()); assertFalse(h.fast.calls.get(1).input.cancelled());
        h.quality.succeed(0, face(500));
        assertEquals(0, h.fastExecutor.size()); assertEquals(0, h.qualityExecutor.size());
        h.fast.succeed(1, face(500)); assertEquals(1, h.fastExecutor.size());
        assertEquals(0, h.qualityExecutor.size()); h.fastExecutor.runOne();
        assertEquals(3, h.fast.calls.get(2).input.source.sequence);
        h.engine.close(); h.fast.calls.get(2).completion.cancelled(); assertEquals(1, h.drained);
    }

    @Test public void qualifiedParallelPolicyKeepsRunningQualityWhileFastStarts() {
        Harness h = new Harness(true, false, true, 8, 2, 16); h.start(); h.fire(0); h.deliver(0, 1);
        h.fastExecutor.runOne(); h.fast.succeed(0, face(500)); h.qualityExecutor.runOne();
        h.fire(334); h.deliver(1, 2); h.fastExecutor.runOne();
        assertEquals(1, h.engine.activeCount(FAST)); assertEquals(1, h.engine.activeCount(QUALITY));
        assertEquals(0, h.quality.cancellations); assertFalse(h.quality.calls.get(0).input.cancelled());
        h.engine.close(); h.fast.calls.get(1).completion.cancelled(); h.quality.calls.get(0).completion.cancelled();
    }

    @Test public void oldQualityDoesNotChangeNewerPresentationDestinationUnderSourcePressure() {
        Harness h = new Harness(true, false, true, 8, 0, 2); h.start(); h.fire(0); h.deliver(0, 1);
        h.fastExecutor.runOne(); h.fast.succeed(0, face(500)); h.qualityExecutor.runOne();
        h.fire(334); h.deliver(1, 2); h.fastExecutor.runOne();
        h.engine.motion(motion(h.engine.currentEpoch(), 1, 2, -400)); h.fast.succeed(1, face(100)); h.ui.runAll();
        h.fire(668); Pixel rejected = h.deliver(2, 3); assertEquals(1, rejected.closes);
        h.quality.succeed(0, face(500)); h.ui.runAll();
        try (CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView()) {
            assertEquals(2, view.snapshot.destination.sequence);
            assertEquals(100, masks(view.snapshot).get(0).displayRegion.top, 0);
        }
        h.engine.close();
    }

    @Test public void textRunsIndependentlyAndSceneGroupingStaysInsideTheOwner() {
        Harness h = new Harness(false, true, false, 8, 2, 16); h.start(); h.fire(0); h.deliver(0, 1);
        h.textExecutor.runOne(); h.text.succeed(0, text(500), text(650)); h.ui.runAll();
        assertEquals(0, h.fast.calls.size());
        try (CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView()) {
            assertEquals(1, view.snapshot.groups.size()); assertEquals(2, view.snapshot.groups.get(0).members.size());
        }
        h.fastExecutor.runOne(); h.fast.succeed(0, face(100)); h.ui.runAll();
        try (CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView()) { assertEquals(2, view.snapshot.groups.size()); }
        h.engine.close();
    }

    @Test public void workQueuePixelPublicationAndDrawClocksStaySeparate() {
        Harness h = new Harness(); h.start(); h.fire(0);
        Pixel pixel = new Pixel(1); h.captures.get(0).callback.success(pixel.payload(0));
        h.now = 120; h.fastExecutor.runOne(); h.now = 150; h.fast.succeed(0, face(500));
        h.now = 170; h.ui.runAll();
        CensorEngine.Trace started = h.traces.stream().filter(t -> t.stage == CensorEngine.Stage.JOB_STARTED).findFirst().get();
        CensorEngine.Trace completed = h.traces.stream().filter(t -> t.stage == CensorEngine.Stage.JOB_COMPLETED).findFirst().get();
        CensorEngine.Trace presented = h.traces.stream().filter(t -> t.stage == CensorEngine.Stage.PRESENTED).findFirst().get();
        assertEquals(120, started.queueMillis); assertEquals(150, completed.atMillis);
        assertSame(TIMING, completed.work); assertEquals(10, completed.work.runtimeMillis);
        assertEquals(170, presented.atMillis); assertEquals(170, presented.pixelAgeMillis);
        assertFalse(h.traces.stream().anyMatch(t -> t.stage == CensorEngine.Stage.DRAWN));
        try (CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView()) {
            h.now = 190; h.engine.drawn(view.snapshot.revision);
        }
        CensorEngine.Trace drawn = h.traces.stream().filter(t -> t.stage == CensorEngine.Stage.DRAWN).findFirst().get();
        assertEquals(190, drawn.atMillis); h.engine.close();
    }

    @Test public void unknownPixelTimeAndEmptyPresentationCannotManufactureFirstCensorProof() {
        Harness h = new Harness(); h.start(); h.fire(0); h.deliver(0, 1); h.fastExecutor.runOne(); h.fast.succeed(0);
        h.ui.runAll(); try (CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView()) { h.engine.drawn(view.snapshot.revision); }
        assertFalse(h.traces.stream().anyMatch(t -> t.stage == CensorEngine.Stage.DRAWN));
        assertTrue(h.traces.stream().allMatch(t -> t.pixelAgeMillis == -1)); h.engine.close();
    }

    @Test public void observerExceptionDoesNotStrandCleanupSchedulingOrOwnership() {
        Harness h = new Harness(); h.throwTrace = true; h.start(); h.fire(0); Pixel pixel = h.deliver(0, 1);
        h.fastExecutor.runOne(); h.fast.calls.get(0).completion.failure();
        assertEquals(1, pixel.closes); assertEquals(334, h.timer.nextAt());
        assertTrue(h.engine.callbackFailures() > 0); h.engine.close();
    }

    @Test public void everyEffectLeaseIsReleasedEvenIfOneDisposerFails() {
        Harness h = new Harness(); h.start(); h.fire(0); Pixel first = h.deliver(0, 1);
        h.fastExecutor.runOne(); h.fast.succeed(0, face(500), face(900)); h.ui.runAll();
        h.fire(334); Pixel second = h.deliver(1, 2); h.fastExecutor.runOne();
        h.engine.motion(motion(h.engine.currentEpoch(), 1, 2, -400)); h.fast.succeed(1, face(100)); h.ui.runAll();
        CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView(); second.throwDispose = true;
        h.engine.close(); assertThrows(IllegalStateException.class, view::close);
        assertEquals(1, first.closes); assertEquals(1, second.closes); assertEquals(0, h.engine.livePixelFrames());
        assertEquals(1, h.engine.resourceFailures()); assertEquals(0, h.drained);
        assertTrue(h.traces.stream().anyMatch(t -> t.stage == CensorEngine.Stage.RESOURCE_FAILED));
    }

    @Test public void permanentCloseAndScopeFenceDoNotReuseTheOldReadyScene() {
        Harness h = new Harness(); h.start(); h.fire(0); h.deliver(0, 1); h.fastExecutor.runOne();
        WorkCall old = h.fast.calls.get(0); h.engine.changeScope(43);
        assertTrue(old.input.cancelled()); old.completion.success(batch(old.input, FAST, face(500)), TIMING);
        h.ui.runAll(); assertNull(h.engine.acquireRenderView()); h.engine.close();
        assertThrows(IllegalStateException.class, () -> h.engine.start(42));
    }

    @Test public void refreshTraceReentryCannotCancelNextQualityOperation() {
        Harness h = new Harness(true, false, false, 8, 2, 16); h.start(); h.fire(0); h.deliver(0, 1);
        h.fastExecutor.runOne(); h.fast.succeed(0, face(500)); h.qualityExecutor.runOne();
        h.traceHook = trace -> {
            if (trace.stage != CensorEngine.Stage.JOB_QUEUED || trace.source != 2 || trace.lane != FAST) return;
            h.traceHook = null;
            h.quality.succeed(0, face(500));
            h.fastExecutor.runOne(); h.fast.succeed(1, face(500)); h.qualityExecutor.runOne();
            assertFalse(h.quality.calls.get(1).input.cancelled());
        };
        h.fire(334); h.deliver(1, 2);
        assertEquals(2, h.quality.calls.size());
        assertEquals(0, h.quality.calls.get(1).cancellationSignals);
        assertFalse(h.quality.calls.get(1).input.cancelled());
        h.engine.close(); h.quality.calls.get(1).completion.cancelled();
    }

    @Test public void rejectedCaptureDisposalFailureCannotReportDrained() {
        Harness h = new Harness(); h.start(); h.fire(0); h.engine.close();
        Pixel pixel = new Pixel(1); pixel.throwBeforeDispose = true;
        h.captures.get(0).callback.success(pixel.payload());
        assertFalse(pixel.closed); assertEquals(1, pixel.disposalAttempts);
        assertEquals(1, h.engine.resourceFailures()); assertEquals(0, h.drained);
        assertTrue(h.traces.stream().anyMatch(t -> t.stage == CensorEngine.Stage.RESOURCE_FAILED
                && t.source == 1 && t.epoch == h.captures.get(0).request.epoch));
    }

    @Test public void freshDuplicateDisposalFailureStopsNewAcquisitionWithoutFreeingActiveInput() {
        Harness h = new Harness(); h.start(); h.fire(0); Pixel active = h.deliver(0, 1); h.fastExecutor.runOne();
        Pixel duplicate = new Pixel(2); duplicate.throwBeforeDispose = true;
        h.captures.get(0).callback.success(duplicate.payload());
        assertEquals(1, h.engine.resourceFailures()); assertFalse(active.closed);
        assertEquals(Long.MAX_VALUE, h.timer.nextAt());
        h.engine.requestCaptureAt(0); assertEquals(Long.MAX_VALUE, h.timer.nextAt());
        h.engine.close(); h.fast.calls.get(0).completion.cancelled(); assertEquals(1, active.closes);
        assertEquals(0, h.drained);
    }

    @Test public void stoppedStaleCaptureRemainsOwnedWhileItsDisposerBlocks() throws Exception {
        Harness h = new Harness(); h.start(); h.fire(0); h.engine.stop();
        CountDownLatch disposing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger disposals = new AtomicInteger();
        AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
        CensorEngine.Captured<Pixel> stale = new CensorEngine.Captured<>(new Pixel(1), 1000, 2400, -1, () -> {
            disposals.incrementAndGet(); disposing.countDown(); awaitRelease(release);
        });
        Thread callback = new Thread(() -> {
            try { h.captures.get(0).callback.success(stale); }
            catch (Throwable failure) { callbackFailure.set(failure); }
        }, "stale-capture-disposer");
        callback.start();
        try {
            assertTrue("disposer did not start", disposing.await(5, TimeUnit.SECONDS));
            assertFalse(h.engine.captureInFlight()); assertEquals(1, h.engine.livePixelFrames());
            h.captures.get(0).callback.success(stale); // Same claimed payload is not another owner.
            assertEquals(1, h.engine.livePixelFrames()); assertEquals(1, disposals.get());
            h.engine.close(); assertEquals(0, h.drained);
        } finally { release.countDown(); callback.join(5000); }
        assertFalse("callback did not finish", callback.isAlive()); assertNull(callbackFailure.get());
        assertEquals(0, h.engine.livePixelFrames()); assertEquals(1, disposals.get()); assertEquals(1, h.drained);
        h.captures.get(0).callback.success(stale); assertEquals(1, disposals.get()); assertEquals(1, h.drained);
    }

    @Test public void alreadyScheduledCaptureCannotPassBlockedDuplicateDisposal() throws Exception {
        Harness h = new Harness(); h.start(); h.fire(0); h.deliver(0, 1);
        CountDownLatch disposing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
        CensorEngine.Captured<Pixel> duplicate = new CensorEngine.Captured<>(new Pixel(2), 1000, 2400, -1, () -> {
            disposing.countDown(); awaitRelease(release);
        });
        Thread callback = new Thread(() -> {
            try { h.captures.get(0).callback.success(duplicate); }
            catch (Throwable failure) { callbackFailure.set(failure); }
        }, "duplicate-capture-disposer");
        callback.start();
        try {
            assertTrue("disposer did not start", disposing.await(5, TimeUnit.SECONDS));
            assertEquals(2, h.engine.livePixelFrames()); h.fire(334);
            assertEquals(1, h.captures.size()); assertEquals(Long.MAX_VALUE, h.timer.nextAt());
        } finally { release.countDown(); callback.join(5000); }
        assertFalse("callback did not finish", callback.isAlive()); assertNull(callbackFailure.get());
        assertEquals(1, h.engine.livePixelFrames()); assertEquals(334, h.timer.nextAt());
        h.fire(334); assertEquals(2, h.captures.size());
        h.engine.close(); h.captures.get(1).callback.failure(0); assertEquals(1, h.drained);
    }

    @Test public void solidFallbackDrawHandleAlsoDelaysPermanentDrain() {
        Harness h = new Harness(false, false, false, 8, 0, 16); h.start(); h.fire(0);
        Pixel source = h.deliver(0, 1); h.fastExecutor.runOne(); h.fast.succeed(0, face(500)); h.ui.runAll();
        CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView();
        assertNotNull(view); assertNull(view.sourcePixels(1)); assertEquals(1, source.closes);
        h.engine.close(); assertEquals(0, h.engine.livePixelFrames()); assertEquals(0, h.drained);
        assertEquals(1, masks(view.snapshot).size()); view.close(); assertEquals(1, h.drained);
        view.close(); assertEquals(1, h.drained);
    }

    @Test public void requestFreezesGeometryAndPlatformCleanupFaultCannotPretendDrain() {
        Harness h = new Harness(); h.start(); h.fire(0);
        assertEquals(42, h.captures.get(0).request.geometry);
        h.captures.get(0).callback.resourceFailure();
        assertEquals(1, h.engine.resourceFailures()); assertFalse(h.engine.captureInFlight());
        assertEquals(Long.MAX_VALUE, h.timer.nextAt()); h.engine.close(); assertEquals(0, h.drained);
    }

    @Test public void workerCleanupFaultCannotBecomeSuccessfulCoverageOrDrain() {
        Harness h = new Harness(); h.start(); h.fire(0); Pixel pixel = h.deliver(0, 1); h.fastExecutor.runOne();
        h.fast.calls.get(0).completion.resourceFailure();
        assertEquals(1, pixel.closes); assertEquals(1, h.engine.resourceFailures());
        assertEquals(Long.MAX_VALUE, h.timer.nextAt()); h.ui.runAll(); assertNull(h.engine.acquireRenderView());
        h.engine.close(); assertEquals(0, h.drained);
    }

    @Test public void cleanupFaultDoesNotLaunchPendingWorkOnAnotherLane() {
        Harness h = new Harness(true, false, false, 8, 0, 16); h.start(); h.fire(0); h.deliver(0, 1);
        h.fastExecutor.runOne(); h.fast.succeed(0, face(500)); h.qualityExecutor.runOne();
        h.fire(334); Pixel pending = h.deliver(1, 2); h.quality.calls.get(0).completion.resourceFailure();
        assertEquals(1, h.engine.resourceFailures()); assertEquals(0, h.engine.activeCount(FAST));
        assertEquals(0, h.engine.pendingCount(FAST)); assertEquals(0, h.fastExecutor.size());
        h.engine.close(); assertEquals(1, pending.closes); assertEquals(0, h.drained);
    }

    private static void awaitRelease(CountDownLatch release) {
        try { assertTrue("disposal release timed out", release.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted);
        }
    }

    @Test public void settingsSpacingChangesKeepTheSameCallbackAndPlatformFloor() {
        Harness h = new Harness(); h.start(); h.fire(0);
        long epoch = h.engine.currentEpoch();
        h.engine.executionControls(1000, false, Collections.singleton(FAST));
        assertTrue(h.engine.captureInFlight()); assertEquals(epoch, h.engine.currentEpoch());
        h.deliver(0, 1); assertEquals(1000, h.timer.nextAt());
        h.engine.executionControls(0, false, Collections.singleton(FAST));
        assertEquals(334, h.timer.nextAt());
        h.engine.close(); h.fastExecutor.runAll();
    }

    @Test public void disabledRunningLaneKeepsPixelsAndRejectsItsLateSuccessAfterReenable() {
        Harness h = new Harness(true, false, true, 8, 2, 16); h.start(); h.fire(0);
        Pixel first = h.deliver(0, 1); h.fastExecutor.runOne(); h.fast.succeed(0, face(500));
        h.qualityExecutor.runOne(); WorkCall quality = h.quality.calls.get(0);
        h.engine.executionControls(334, false, Collections.singleton(FAST));
        assertTrue(quality.input.cancelled()); assertFalse(first.closed);
        h.engine.executionControls(334, true, java.util.EnumSet.of(FAST, QUALITY));
        quality.completion.success(batch(quality.input, QUALITY, face(100)), TIMING);
        h.ui.runAll();
        try (CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView()) {
            assertEquals(500, masks(view.snapshot).get(0).displayRegion.top, 0);
        }
        h.engine.close(); assertEquals(1, first.closes);
    }

    @Test public void disablingQueuedLanesPreventsTheirWorkerFromReadingReleasedPixels() {
        Harness h = new Harness(true, true, true, 8, 0, 16); h.start(); h.fire(0);
        Pixel pixel = h.deliver(0, 1);
        h.engine.executionControls(334, false, Collections.emptySet());
        assertEquals(1, pixel.closes); assertEquals(Long.MAX_VALUE, h.timer.nextAt());
        h.fastExecutor.runAll(); h.textExecutor.runAll(); h.qualityExecutor.runAll();
        assertTrue(h.fast.calls.isEmpty()); assertTrue(h.text.calls.isEmpty()); assertTrue(h.quality.calls.isEmpty());
        h.engine.close(); assertEquals(1, h.drained);
    }

    @Test public void textOnlyConfigurationDoesNotRunOrWaitForFast() {
        Harness h = new Harness(true, true, false, 8, 2, 16);
        h.engine.executionControls(334, false, Collections.singleton(TEXT)); h.start(); h.fire(0);
        h.deliver(0, 1); assertEquals(0, h.fastExecutor.size()); assertEquals(0, h.qualityExecutor.size());
        h.textExecutor.runOne(); h.text.succeed(0, text(600)); h.ui.runAll();
        try (CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView()) {
            assertEquals("TEXT", masks(view.snapshot).get(0).category);
        }
        h.engine.close();
    }

    @Test public void providerAdmissionChangesDoNotResetFirstFastOrSourceOwnership() {
        Harness h = new Harness(true, false, false, 8, 2, 16); h.start(); h.fire(0);
        h.deliver(0, 1); h.fastExecutor.runOne(); h.fast.succeed(0, face(500)); h.qualityExecutor.runOne();
        h.fire(334); Pixel next = h.deliver(1, 2); assertEquals(0, h.fastExecutor.size());
        h.engine.executionControls(334, true, java.util.EnumSet.of(FAST, QUALITY));
        assertEquals(1, h.fastExecutor.size()); h.fastExecutor.runOne(); assertFalse(next.closed);
        h.engine.close(); h.fast.calls.get(1).completion.cancelled(); h.quality.calls.get(0).completion.cancelled();
        assertEquals(1, next.closes);
    }

    @Test public void disabledDuringForegroundResolutionDoesNotCallThePlatform() {
        Harness h = new Harness(); h.behavior = (request, callback) -> {
            h.engine.executionControls(334, false, Collections.emptySet()); assertFalse(request.tryDispatch());
        };
        h.start(); h.fire(0); assertFalse(h.engine.captureInFlight());
        assertEquals(Long.MAX_VALUE, h.timer.nextAt());
        h.engine.executionControls(334, false, Collections.singleton(FAST));
        assertEquals(0, h.timer.nextAt()); h.engine.close();
    }

    @Test public void acceptedEvidenceHookDoesNotReportFailedOrCanceledWork() {
        Harness h = new Harness(); h.start(); h.fire(0); h.deliver(0, 1); h.fastExecutor.runOne();
        h.fast.calls.get(0).completion.failure(); assertTrue(h.accepted.isEmpty());
        h.fire(334); h.deliver(1, 2); h.fastExecutor.runOne();
        h.engine.executionControls(334, false, Collections.emptySet());
        h.fast.succeed(1, face(500)); assertTrue(h.accepted.isEmpty());
        h.engine.executionControls(334, false, Collections.singleton(FAST));
        h.fire(668); h.deliver(2, 3); h.fastExecutor.runOne(); h.fast.succeed(2, face(600));
        assertEquals(1, h.accepted.size()); assertSame(h.fast.calls.get(2).input.source, h.accepted.get(0).source);
        assertEquals(FAST, h.accepted.get(0).lane); h.engine.close();
    }

    @Test public void nativeContextCleanupFaultCannotPretendSuccessfulDrainOrAdmitMoreWork() {
        Harness h = new Harness(); h.start(); h.fire(0); Pixel pixels = h.deliver(0, 1); h.fastExecutor.runOne();
        h.engine.nativeResourceFailure(); assertEquals(1, h.engine.resourceFailures());
        assertTrue(h.traces.stream().filter(t -> t.stage == CensorEngine.Stage.RESOURCE_FAILED)
                .allMatch(t -> t.source == 0 && t.pixelAgeMillis == -1));
        assertFalse(pixels.closed); assertTrue(h.fast.calls.get(0).input.cancelled());
        h.fast.calls.get(0).completion.cancelled(); assertEquals(1, pixels.closes);
        h.engine.requestCaptureAt(0); assertEquals(Long.MAX_VALUE, h.timer.nextAt());
        h.engine.close(); assertEquals(0, h.drained);
    }

    @Test public void displayExpiryClearsWithoutAnotherCaptureOrInferenceCompletion() {
        Harness h = new Harness(); h.start(); h.fire(0); Pixel pixels = h.deliver(0, 1);
        h.fastExecutor.runOne(); h.fast.succeed(0, face(500)); h.ui.runAll();
        h.fire(334); // The next platform callback stays outstanding indefinitely.
        int accepted = h.accepted.size();
        assertEquals(5001, h.timer.nextAt()); h.fire(5001); h.ui.runAll();
        try (CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView()) {
            assertNotNull(view); assertTrue(view.snapshot.groups.isEmpty());
            assertEquals(Long.MAX_VALUE, view.snapshot.nextChangeAtMillis);
        }
        assertEquals(accepted, h.accepted.size()); assertEquals(1, pixels.closes);
        assertEquals(2, h.captures.size()); assertTrue(h.engine.captureInFlight());
        h.engine.close(); h.captures.get(1).callback.failure(0);
    }

    @Test public void drawingAtTheExactBoundaryKeepsCoverageButLateTimerCannotPermitAnExpiredDraw() {
        Harness h = new Harness(); h.start(); h.fire(0); Pixel pixels = h.deliver(0, 1);
        h.fastExecutor.runOne(); h.fast.succeed(0, face(500)); h.ui.runAll();
        CensorEngine<Pixel>.RenderView held = h.engine.acquireRenderView();
        h.now = 5000;
        try (CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView()) {
            assertSame(held.snapshot, view.snapshot); assertEquals(1, masks(view.snapshot).size());
        }
        h.now = 5001; // Do not deliver any timer or UI task before the actual draw acquisition.
        try (CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView()) {
            assertTrue(view.snapshot.groups.isEmpty()); assertNull(view.sourcePixels(1));
        }
        assertFalse(pixels.closed); assertSame(pixels, held.sourcePixels(1));
        held.close(); assertEquals(1, pixels.closes); h.ui.runAll();
        h.engine.close();
    }

    @Test public void queuedUiPublicationCannotFlashEvidenceThatExpiredWhileTheUiWasBlocked() {
        Harness h = new Harness(); h.start(); h.fire(0); Pixel pixels = h.deliver(0, 1);
        h.fastExecutor.runOne(); h.fast.succeed(0, face(500));
        h.now = 6000; h.ui.runAll();
        try (CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView()) {
            assertTrue(view.snapshot.groups.isEmpty()); assertNull(view.sourcePixels(1));
        }
        assertEquals(1, pixels.closes); assertEquals(1, h.accepted.size()); h.engine.close();
    }

    @Test public void renewalReplacesTheDeadlineAndCancelledOldExpiryCannotClearNewEvidence() {
        Harness h = new Harness(); h.start(); h.fire(0); h.deliver(0, 1);
        h.fastExecutor.runOne(); h.fast.succeed(0, face(500)); h.ui.runAll();
        TimedTask oldExpiry = h.timer.tasks.stream().filter(t -> t.at == 5001).findFirst().get();
        h.fire(334); h.deliver(1, 2); h.fastExecutor.runOne();
        h.engine.motion(motion(h.engine.currentEpoch(), 1, 2, 0));
        h.fast.succeed(1, face(500)); h.ui.runAll();
        assertTrue(oldExpiry.cancelled); h.now = 5001; oldExpiry.action.run();
        try (CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView()) {
            assertEquals(1, masks(view.snapshot).size()); assertEquals(5335, view.snapshot.nextChangeAtMillis);
        }
        h.engine.close();
    }

    @Test public void oldDisplayExpiryCannotPublishIntoAChangedScope() {
        Harness h = new Harness(); h.start(); h.fire(0); h.deliver(0, 1);
        h.fastExecutor.runOne(); h.fast.succeed(0, face(500)); h.ui.runAll();
        TimedTask oldExpiry = h.timer.tasks.stream().filter(t -> t.at == 5001).findFirst().get();
        h.engine.changeScope(43); h.ui.runAll(); int publications = h.presentations;
        oldExpiry.action.run(); assertTrue(oldExpiry.cancelled); assertEquals(publications, h.presentations);
        assertNull(h.engine.acquireRenderView()); h.engine.close();
    }

    @Test public void rejectedExpiryWakeCannotAuthorizeExpiredDrawsOrCreateAPhantomDeadline() {
        Harness h = new Harness(); h.start(); h.fire(0); h.deliver(0, 1); h.fastExecutor.runOne();
        h.timer.reject = true; h.fast.succeed(0, face(500)); h.ui.runAll();
        assertTrue(h.engine.callbackFailures() > 0);
        assertTrue(h.timer.tasks.stream().noneMatch(t -> !t.cancelled && !t.ran && t.at == 5001));
        h.now = 6000;
        try (CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView()) {
            assertTrue(view.snapshot.groups.isEmpty());
        }
        h.engine.close();
    }

    @Test public void drawTimeRefreshHonorsReentrantScopeInvalidationBeforeReturningTheLease() {
        Harness h = new Harness(); h.start(); h.fire(0); Pixel pixels = h.deliver(0, 1);
        h.fastExecutor.runOne(); h.fast.succeed(0, face(500)); h.ui.runAll();
        h.traceHook = trace -> { if (trace.stage == CensorEngine.Stage.PRESENTED) h.engine.stop(); };
        h.now = 5001; assertNull(h.engine.acquireRenderView()); assertEquals(1, pixels.closes);
        h.traceHook = null; h.engine.close();
    }

    @Test public void qualifiedImageMapMovesReadyCoverageBeforeTheNextFastResult() {
        Harness h = new Harness(); h.start(); h.fire(0); Pixel first = h.deliver(0, 1);
        h.fastExecutor.runOne(); h.fast.succeed(0, face(500)); h.ui.runAll();
        h.fire(334); Pixel second = h.deliver(1, 2); h.fastExecutor.runOne();
        assertTrue(h.engine.motion(motion(h.engine.currentEpoch(), 1, 2, -400))); h.ui.runAll();
        try (CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView()) {
            assertEquals(1, view.snapshot.destination.sequence); assertEquals(1, masks(view.snapshot).size());
            SceneEngine.Mask mask = masks(view.snapshot).get(0);
            assertEquals(100, mask.displayRegion.top, 0); assertEquals(2, mask.geometrySource.sequence);
            assertEquals(500, mask.evidenceRegion.top, 0); assertEquals(1, mask.evidenceSource.sequence);
            assertSame(first, view.sourcePixels(1)); assertNull(view.sourcePixels(2)); assertFalse(mask.confirmed);
        }
        assertEquals(1, h.accepted.size()); assertFalse(second.closed);
        h.engine.close(); h.fast.calls.get(1).completion.cancelled();
        assertEquals(1, first.closes); assertEquals(1, second.closes);
    }

    @Test public void measuredConflictInvalidatesExactlyOneEpochWithoutFreeingActualResourceOwners() {
        Harness h = new Harness(); h.start(); h.fire(0); Pixel first = h.deliver(0, 1);
        h.fastExecutor.runOne(); h.fast.succeed(0, face(500)); h.ui.runAll();
        CensorEngine<Pixel>.RenderView held = h.engine.acquireRenderView(); assertNotNull(held);
        h.fire(334); Pixel running = h.deliver(1, 2); h.fastExecutor.runOne();
        h.fire(668); Pixel replaced = h.deliver(2, 3);
        h.fire(1002); Pixel pending = h.deliver(3, 4);
        assertEquals(1, replaced.closes); h.fire(1336); // An actual old-epoch callback stays outstanding.
        long epoch = h.engine.currentEpoch();
        assertTrue(h.engine.motion(motion(epoch, 1, 2, -200)));
        assertTrue(h.engine.motion(motion(epoch, 2, 4, -200)));
        assertTrue(h.engine.motion(motion(epoch, 1, 3, -200))); h.ui.runAll();
        SceneEngine.Motion conflict = motion(epoch, 3, 4, -100);
        assertFalse(h.engine.motion(conflict));
        assertEquals(epoch + 1, h.engine.currentEpoch());
        assertEquals(1, h.fast.calls.get(1).cancellationSignals);
        assertTrue(h.fast.calls.get(1).input.cancelled());
        assertNull(h.engine.acquireRenderView()); assertEquals(0, h.engine.renderBasisCount());
        assertFalse(first.closed); assertFalse(running.closed); assertEquals(1, pending.closes);
        assertSame(first, held.sourcePixels(1)); assertTrue(h.engine.captureInFlight());
        assertFalse(h.engine.motion(conflict)); assertEquals(epoch + 1, h.engine.currentEpoch());
        h.fast.succeed(1, face(300)); assertEquals(1, running.closes); assertEquals(1, h.accepted.size());
        Pixel oldCallback = h.deliver(4, 5); assertEquals(1, oldCallback.closes);
        assertEquals(1670, h.timer.nextAt()); h.fire(1670); Pixel fresh = h.deliver(5, 6);
        h.fastExecutor.runOne(); h.fast.succeed(2, face(600)); h.ui.runAll();
        try (CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView()) {
            assertEquals(epoch + 1, view.snapshot.destination.epoch);
            assertEquals(600, masks(view.snapshot).get(0).displayRegion.top, 0);
            assertSame(fresh, view.sourcePixels(6));
        }
        h.engine.close(); assertEquals(0, h.drained); assertFalse(first.closed);
        held.close(); assertEquals(1, first.closes); assertEquals(1, fresh.closes); assertEquals(1, h.drained);
    }

    @Test public void anAtomicPairMapsBothMembersBeforeTheNextInferenceWithoutAHalfPublishedBasis() {
        Harness h = new Harness(); h.start(); h.fire(0); Pixel evidence = h.deliver(0, 1);
        h.fastExecutor.runOne(); h.fire(334); Pixel later = h.deliver(1, 2);
        long epoch = h.engine.currentEpoch();
        SceneEngine.MotionPair pair = new SceneEngine.MotionPair(Arrays.asList(
                new SceneEngine.Motion(epoch, 1, 2, 1, 1, SceneEngine.MotionAuthority.IMAGE_CORRESPONDENCE,
                        new SceneEngine.Region(0, 0, 1000, 80), 0, 0),
                new SceneEngine.Motion(epoch, 1, 2, 1, 1, SceneEngine.MotionAuthority.IMAGE_CORRESPONDENCE,
                        new SceneEngine.Region(0, 500, 1000, 2400), 0, -400)));
        assertTrue(h.engine.motion(pair)); assertNull(h.engine.acquireRenderView());
        h.fast.succeed(0, new SceneEngine.Observation("HEADER", 1, 0, new SceneEngine.Region(100, 50, 200, 70), .9f), face(500));
        h.fastExecutor.runOne(); h.ui.runAll();
        try (CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView()) {
            assertEquals(2, masks(view.snapshot).size()); assertEquals(1, view.snapshot.destination.sequence);
            for (SceneEngine.Mask mask : masks(view.snapshot)) {
                assertEquals(2, mask.geometrySource.sequence); assertEquals(1, mask.evidenceSource.sequence);
                assertFalse(mask.heldGeometry); assertFalse(mask.estimatedMotion);
                assertEquals("HEADER".equals(mask.category) ? 50 : 100, mask.displayRegion.top, 0);
            }
            assertSame(evidence, view.sourcePixels(1)); assertNull(view.sourcePixels(2));
        }
        assertEquals(1, h.accepted.size()); assertEquals(1, h.engine.activeCount(FAST));
        assertFalse(evidence.closed); assertFalse(later.closed);
        h.engine.close(); assertFalse(later.closed); h.fast.calls.get(1).completion.cancelled();
        assertEquals(1, evidence.closes); assertEquals(1, later.closes); assertEquals(1, h.drained);
    }

    @Test public void aRejectedAtomicPairRollsBackItsBasisPinAndCannotConsumeTheValidPair() {
        Harness h = new Harness(); h.start(); h.fire(0); Pixel evidence = h.deliver(0, 1);
        h.fastExecutor.runOne(); h.fast.succeed(0, face(500)); h.ui.runAll();
        h.fire(334); h.deliver(1, 2); h.fastExecutor.runOne(); long epoch = h.engine.currentEpoch();
        SceneEngine.Snapshot prior;
        try (CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView()) { prior = view.snapshot; }
        int presentations = h.presentations;
        assertFalse(h.engine.motion(new SceneEngine.MotionPair(Arrays.asList(
                new SceneEngine.Motion(epoch, 1, 2, 1, 1, SceneEngine.MotionAuthority.IMAGE_CORRESPONDENCE,
                        new SceneEngine.Region(0, 0, 1000, 80), 0, 0),
                new SceneEngine.Motion(epoch, 1, 2, 1, 1, SceneEngine.MotionAuthority.IMAGE_CORRESPONDENCE,
                        new SceneEngine.Region(0, 500, 1000, 2400), 0, -500)))));
        assertEquals(0, h.engine.renderBasisCount()); assertEquals(epoch, h.engine.currentEpoch());
        assertEquals(presentations, h.presentations);
        try (CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView()) { assertSame(prior, view.snapshot); }
        assertTrue(h.engine.motion(motion(epoch, 1, 2, -400))); h.ui.runAll();
        try (CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView()) {
            assertEquals(100, masks(view.snapshot).get(0).displayRegion.top, 0); assertSame(evidence, view.sourcePixels(1));
        }
        h.engine.close(); h.fast.calls.get(1).completion.cancelled(); assertEquals(1, h.drained);
    }

    @Test public void aMapBeforeTheFirstFastResultDoesNotDelayOrDoubleRebaseFirstCoverage() {
        Harness h = new Harness(); h.start(); h.fire(0); h.deliver(0, 1); h.fastExecutor.runOne();
        h.fire(334); h.deliver(1, 2);
        assertTrue(h.engine.motion(motion(h.engine.currentEpoch(), 1, 2, -400)));
        assertNull(h.engine.acquireRenderView()); h.fast.succeed(0, face(500)); h.ui.runAll();
        long identity;
        try (CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView()) {
            SceneEngine.Mask mask = masks(view.snapshot).get(0); identity = mask.identity;
            assertEquals(100, mask.displayRegion.top, 0); assertEquals(2, mask.geometrySource.sequence);
        }
        h.fastExecutor.runOne(); h.fast.succeed(1, face(100)); h.ui.runAll();
        try (CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView()) {
            assertEquals(1, masks(view.snapshot).size()); SceneEngine.Mask mask = masks(view.snapshot).get(0);
            assertEquals(identity, mask.identity); assertEquals(100, mask.displayRegion.top, 0);
            assertEquals(2, mask.evidenceSource.sequence); assertEquals(2, mask.geometrySource.sequence);
        }
        h.engine.close();
    }

    @Test public void readyBodyAndHeaderUseIndependentPartitionBasesAndOriginalSamplingPixels() {
        Harness h = new Harness(); h.start(); h.fire(0); Pixel first = h.deliver(0, 1);
        h.fastExecutor.runOne();
        SceneEngine.Observation header = new SceneEngine.Observation("HEADER", 2, 0,
                new SceneEngine.Region(100, 50, 200, 100), .9f);
        h.fast.succeed(0, face(500), header); h.ui.runAll();
        h.fire(334); h.deliver(1, 2); h.fastExecutor.runOne();
        h.engine.motion(motion(h.engine.currentEpoch(), 1, 2, -400)); h.ui.runAll();
        try (CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView()) {
            assertEquals(2, masks(view.snapshot).size());
            for (SceneEngine.Mask mask : masks(view.snapshot)) {
                assertEquals(mask.partition == 1 ? 100 : 50, mask.displayRegion.top, 0);
                assertEquals(mask.partition == 1 ? 2 : 1, mask.geometrySource.sequence);
                assertSame(first, view.sourcePixels(mask.evidenceSource.sequence));
            }
        }
        h.engine.close(); h.fast.calls.get(1).completion.cancelled();
    }

    @Test public void basisMetadataSurvivesBitmapRetirementWithoutBorrowingTheWrongPixels() {
        Harness h = new Harness(); h.start(); h.fire(0); Pixel first = h.deliver(0, 1);
        h.fastExecutor.runOne(); h.fast.succeed(0, face(500)); h.ui.runAll();
        h.fire(334); Pixel second = h.deliver(1, 2); h.fastExecutor.runOne();
        h.engine.motion(motion(h.engine.currentEpoch(), 1, 2, -400)); h.ui.runAll();
        h.fast.calls.get(1).completion.failure(); assertEquals(1, second.closes);
        h.fire(668); h.deliver(2, 3); h.fastExecutor.runOne();
        assertTrue(h.engine.motion(motion(h.engine.currentEpoch(), 2, 3, 200))); h.ui.runAll();
        try (CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView()) {
            SceneEngine.Mask mask = masks(view.snapshot).get(0);
            assertEquals(300, mask.displayRegion.top, 0); assertEquals(3, mask.geometrySource.sequence);
            assertSame(first, view.sourcePixels(1)); assertNull(view.sourcePixels(2)); assertNull(view.sourcePixels(3));
        }
        h.engine.close(); h.fast.calls.get(2).completion.cancelled();
    }

    @Test public void estimatesCannotAdvanceImageBasesAndLaterMeasuredMotionIsNotAddedTwice() {
        Harness h = new Harness(); h.start(); h.fire(0); h.deliver(0, 1);
        h.fastExecutor.runOne(); h.fast.succeed(0, face(500)); h.ui.runAll();
        h.fire(334); h.deliver(1, 2); h.fastExecutor.runOne();
        SceneEngine.Motion estimate = new SceneEngine.Motion(h.engine.currentEpoch(), 1, 2, 1, 0,
                SceneEngine.MotionAuthority.EVENT_ESTIMATE, SUPPORT, 0, -400);
        assertTrue(h.engine.motion(estimate)); h.ui.runAll(); assertEquals(0, h.engine.renderBasisCount());
        try (CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView()) {
            assertEquals(500, masks(view.snapshot).get(0).displayRegion.top, 0);
        }
        assertTrue(h.engine.motion(motion(h.engine.currentEpoch(), 1, 2, -400))); h.ui.runAll();
        assertFalse(h.engine.motion(estimate)); assertEquals(1, h.engine.renderBasisCount());
        try (CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView()) {
            assertEquals(100, masks(view.snapshot).get(0).displayRegion.top, 0);
            assertFalse(masks(view.snapshot).get(0).confirmed);
        }
        h.engine.close(); h.fast.calls.get(1).completion.cancelled();
    }

    @Test public void delayedOlderMapsCannotMoveANewerPartitionBasisBackwards() {
        Harness h = new Harness(); h.start(); h.fire(0); h.deliver(0, 1);
        h.fastExecutor.runOne(); h.fast.succeed(0, face(900)); h.ui.runAll();
        h.fire(334); h.deliver(1, 2); h.fastExecutor.runOne(); h.fire(668); h.deliver(2, 3);
        h.engine.motion(motion(h.engine.currentEpoch(), 1, 3, -800)); h.ui.runAll();
        assertTrue(h.engine.motion(motion(h.engine.currentEpoch(), 1, 2, -400))); h.ui.runAll();
        try (CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView()) {
            assertEquals(100, masks(view.snapshot).get(0).displayRegion.top, 0);
            assertEquals(3, masks(view.snapshot).get(0).geometrySource.sequence);
        }
        h.engine.close(); h.fast.calls.get(1).completion.cancelled();
    }

    @Test public void displayExpiryAndScopeInvalidationReleasePartitionBasisOwnership() {
        Harness h = new Harness(); h.start(); h.fire(0); h.deliver(0, 1);
        h.fastExecutor.runOne(); h.fast.succeed(0, face(500)); h.ui.runAll();
        h.fire(334); h.deliver(1, 2); h.fastExecutor.runOne();
        h.engine.motion(motion(h.engine.currentEpoch(), 1, 2, -400)); h.ui.runAll();
        assertEquals(1, h.engine.renderBasisCount());
        h.now = 5001;
        try (CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView()) {
            assertTrue(view.snapshot.groups.isEmpty()); assertEquals(0, h.engine.renderBasisCount());
        }
        h.engine.changeScope(43); assertEquals(0, h.engine.renderBasisCount());
        assertNull(h.engine.acquireRenderView()); h.engine.close(); h.fast.calls.get(1).completion.cancelled();
    }

    @Test public void basisAdmissionIsBoundedAndUnusedPartitionPinsRetireAtFirstPublication() {
        Harness h = new Harness(); h.start(); h.fire(0); h.deliver(0, 1); h.fastExecutor.runOne();
        h.fire(334); Pixel second = h.deliver(1, 2);
        for (int partition = 1; partition <= 64; partition++) {
            assertTrue(h.engine.motion(new SceneEngine.Motion(h.engine.currentEpoch(), 1, 2,
                    partition, 1, SceneEngine.MotionAuthority.IMAGE_CORRESPONDENCE, SUPPORT, 0, -400)));
        }
        assertEquals(64, h.engine.renderBasisCount());
        assertFalse(h.engine.motion(new SceneEngine.Motion(h.engine.currentEpoch(), 1, 2,
                65, 1, SceneEngine.MotionAuthority.IMAGE_CORRESPONDENCE, SUPPORT, 0, -400)));
        h.fast.succeed(0, face(500)); h.ui.runAll(); assertEquals(1, h.engine.renderBasisCount());
        h.engine.close(); assertEquals(0, h.engine.renderBasisCount()); assertEquals(1, second.closes);
    }

    @Test public void freshUnmatchedHitsCannotBeHiddenOrBackProjectedByAnOlderCarriedBasis() {
        for (SceneEngine.MotionAuthority bridge : Arrays.asList(null,
                SceneEngine.MotionAuthority.EVENT_ESTIMATE, SceneEngine.MotionAuthority.IMAGE_CORRESPONDENCE)) {
            Harness h = new Harness(); h.start(); h.fire(0); h.deliver(0, 1);
            h.fastExecutor.runOne(); h.fast.succeed(0, face(500)); h.ui.runAll();
            h.fire(334); h.deliver(1, 2); h.fastExecutor.runOne();
            h.engine.motion(motion(h.engine.currentEpoch(), 1, 2, -400)); h.ui.runAll();
            h.fast.calls.get(1).completion.failure();
            h.fire(668); Pixel fresh = h.deliver(2, 3); h.fastExecutor.runOne();
            if (bridge != null) h.engine.motion(new SceneEngine.Motion(h.engine.currentEpoch(), 2, 3,
                    1, 1, bridge, SUPPORT, 0, 200));
            h.fast.succeed(2, face(300)); h.ui.runAll();
            try (CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView()) {
                assertEquals(1, masks(view.snapshot).size()); SceneEngine.Mask mask = masks(view.snapshot).get(0);
                assertEquals(300, mask.displayRegion.top, 0); assertEquals(3, mask.geometrySource.sequence);
                assertSame(fresh, view.sourcePixels(3));
            }
            h.engine.close();
        }
    }

    @Test public void optionalBasisPinsCannotStarveActiveAndLatestPendingCaptures() {
        Harness h = new Harness(false, false, false, 8, 2, 3);
        h.start(); h.fire(0); h.deliver(0, 1); h.fastExecutor.runOne();
        h.fast.succeed(0, face(500)); h.ui.runAll();
        h.fire(334); Pixel retired = h.deliver(1, 2); h.fastExecutor.runOne();
        assertTrue(h.engine.motion(motion(h.engine.currentEpoch(), 1, 2, -400)));
        assertEquals(0, h.engine.renderBasisCount()); assertEquals(1, h.engine.basisAdmissionDrops());
        h.fast.calls.get(1).completion.failure(); assertEquals(1, retired.closes);
        h.fire(668); Pixel running = h.deliver(2, 3); h.fastExecutor.runOne();
        h.fire(1002); Pixel pending = h.deliver(3, 4);
        assertEquals(1, h.engine.activeCount(FAST)); assertEquals(1, h.engine.pendingCount(FAST));
        assertFalse(running.closed); assertFalse(pending.closed);
        assertFalse(h.traces.stream().anyMatch(trace -> trace.stage == CensorEngine.Stage.SOURCE_REJECTED));
        h.engine.close(); assertFalse(running.closed); assertEquals(1, pending.closes);
        h.fast.calls.get(2).completion.cancelled(); assertEquals(1, running.closes);
    }

    private static SceneEngine.Observation face(double y) {
        return new SceneEngine.Observation("FACE", 1, 0, new SceneEngine.Region(100, y, 200, y + 100), .9f);
    }

    private static SceneEngine.Observation text(double y) {
        return new SceneEngine.Observation("TEXT", 1, 77, new SceneEngine.Region(100, y, 500, y + 100), .9f);
    }

    private static SceneEngine.Motion motion(long epoch, long from, long to, double dy) {
        return new SceneEngine.Motion(epoch, from, to, 1, 1, SceneEngine.MotionAuthority.IMAGE_CORRESPONDENCE,
                SUPPORT, 0, dy);
    }

    private static SceneEngine.Batch batch(CensorEngine.Input<Pixel> input, SceneEngine.Lane lane,
            SceneEngine.Observation... observations) {
        return new SceneEngine.Batch(input.source, lane, Arrays.asList(observations), Collections.emptyList());
    }

    private static List<SceneEngine.Mask> masks(SceneEngine.Snapshot snapshot) {
        List<SceneEngine.Mask> masks = new ArrayList<>();
        for (SceneEngine.Group group : snapshot.groups) masks.addAll(group.members);
        return masks;
    }

    private static final class Pixel {
        final int id;
        boolean closed;
        int closes;
        int disposalAttempts;
        boolean throwDispose;
        boolean throwBeforeDispose;
        Pixel(int id) { this.id = id; }
        CensorEngine.Captured<Pixel> payload() { return payload(-1); }
        CensorEngine.Captured<Pixel> payload(long pixelAtMillis) {
            return payload(1000, 2400, pixelAtMillis);
        }
        CensorEngine.Captured<Pixel> payload(int width, int height) { return payload(width, height, -1); }
        CensorEngine.Captured<Pixel> payload(int width, int height, long pixelAtMillis) {
            return new CensorEngine.Captured<>(this, width, height, pixelAtMillis, () -> {
                disposalAttempts++;
                if (throwBeforeDispose) throw new IllegalStateException("before actual pixel disposal");
                assertFalse("pixels released twice", closed); closed = true; closes++;
                if (throwDispose) throw new IllegalStateException("test disposer failure");
            });
        }
    }

    private static final class ManualExecutor implements Executor {
        final List<Runnable> tasks = new ArrayList<>();
        boolean reject;
        @Override public void execute(Runnable action) {
            if (reject) throw new RejectedExecutionException(); tasks.add(action);
        }
        int size() { return tasks.size(); }
        void runOne() { assertFalse("no queued work", tasks.isEmpty()); tasks.remove(0).run(); }
        void runAll() { while (!tasks.isEmpty()) runOne(); }
    }

    private static final class WorkCall {
        final CensorEngine.Input<Pixel> input;
        final CensorEngine.Completion completion;
        boolean terminal;
        int cancellationSignals;
        WorkCall(CensorEngine.Input<Pixel> input, CensorEngine.Completion completion) {
            this.input = input;
            this.completion = new CensorEngine.Completion() {
                @Override public void success(SceneEngine.Batch batch, CensorEngine.WorkTiming timing) {
                    terminal = true; completion.success(batch, timing);
                }
                @Override public void failure() { terminal = true; completion.failure(); }
                @Override public void cancelled() { terminal = true; completion.cancelled(); }
                @Override public void resourceFailure() { terminal = true; completion.resourceFailure(); }
            };
        }
    }

    private static final class FakeWorker implements CensorEngine.Worker<Pixel> {
        final SceneEngine.Lane lane;
        final List<WorkCall> calls = new ArrayList<>();
        int cancellations;
        boolean throwBeforeLaunch;
        FakeWorker(SceneEngine.Lane lane) { this.lane = lane; }
        @Override public void start(CensorEngine.Input<Pixel> input, CensorEngine.Completion completion) {
            if (throwBeforeLaunch) throw new IllegalStateException("before native launch");
            assertFalse("worker read freed pixels", input.pixels.closed);
            WorkCall call = new WorkCall(input, completion); calls.add(call);
            input.onCancellation(() -> { if (!call.terminal) { cancellations++; call.cancellationSignals++; } });
        }
        void succeed(int index, SceneEngine.Observation... observations) {
            WorkCall work = calls.get(index); assertFalse(work.input.pixels.closed);
            work.completion.success(batch(work.input, lane, observations), TIMING);
        }
    }

    private static final class CaptureCall {
        final CensorEngine.CaptureRequest request;
        final CensorEngine.CaptureCallback<Pixel> callback;
        CaptureCall(CensorEngine.CaptureRequest request, CensorEngine.CaptureCallback<Pixel> callback) {
            this.request = request; this.callback = callback;
        }
    }

    private static final class TimedTask {
        final long at;
        final Runnable action;
        boolean cancelled;
        boolean ran;
        TimedTask(long at, Runnable action) { this.at = at; this.action = action; }
    }

    private static final class Harness {
        long now;
        int presentations;
        volatile int drained;
        boolean throwTrace;
        final ManualExecutor fastExecutor = new ManualExecutor();
        final ManualExecutor qualityExecutor = new ManualExecutor();
        final ManualExecutor textExecutor = new ManualExecutor();
        final ManualExecutor ui = new ManualExecutor();
        final FakeWorker fast = new FakeWorker(FAST);
        final FakeWorker quality = new FakeWorker(QUALITY);
        final FakeWorker text = new FakeWorker(TEXT);
        final List<CaptureCall> captures = new ArrayList<>();
        final List<CensorEngine.Trace> traces = Collections.synchronizedList(new ArrayList<>());
        final List<SceneEngine.Batch> accepted = new ArrayList<>();
        final List<CensorEngine.EvidenceReceipt> evidence = new ArrayList<>();
        final List<ScrollMotionLedger.Receipt> scrolls = new ArrayList<>();
        java.util.function.Consumer<SceneEngine.Batch> acceptHook;
        BiConsumer<CensorEngine.CaptureRequest, CensorEngine.CaptureCallback<Pixel>> behavior;
        java.util.function.Consumer<CensorEngine.Trace> traceHook;
        final FakeTimer timer = new FakeTimer();
        final CensorEngine<Pixel> engine;

        Harness() { this(false, false, false, 8, 2, 16); }

        Harness(boolean qualityEnabled, boolean textEnabled, boolean parallelQuality,
                int maximumFrames, int maximumEffects, int maximumSources) {
            List<CensorEngine.LaneWorker<Pixel>> workers = new ArrayList<>();
            workers.add(new CensorEngine.LaneWorker<>(FAST, fastExecutor, fast));
            if (qualityEnabled) workers.add(new CensorEngine.LaneWorker<>(QUALITY, qualityExecutor, quality));
            if (textEnabled) workers.add(new CensorEngine.LaneWorker<>(TEXT, textExecutor, text));
            engine = new CensorEngine<>(new CensorEngine.Policy(334, 0, maximumFrames, maximumEffects, parallelQuality),
                    new SceneEngine.Policy(maximumSources, 100, 64, 256, 5000, 20000, .5),
                    () -> now, timer, (request, callback) -> {
                        captures.add(new CaptureCall(request, callback));
                        if (behavior != null) behavior.accept(request, callback);
                        else assertTrue(request.tryDispatch());
                    }, workers, ui, new CensorEngine.Observer() {
                        @Override public void trace(CensorEngine.Trace trace) {
                            if (throwTrace) throw new IllegalStateException("test observer failure");
                            traces.add(trace);
                            if (traceHook != null) traceHook.accept(trace);
                        }
                        @Override public void scrollReceived(ScrollMotionLedger.Receipt receipt) {
                            assertFalse("scroll callback owns runtime lock", Thread.holdsLock(engine)); scrolls.add(receipt);
                        }
                        @Override public void presentationChanged() { presentations++; }
                        @Override public void batchAccepted(SceneEngine.Batch batch) {
                            accepted.add(batch); if (acceptHook != null) acceptHook.accept(batch);
                        }
                        @Override public void evidenceAccepted(CensorEngine.EvidenceReceipt receipt) {
                            assertFalse("evidence callback owns runtime lock", Thread.holdsLock(engine));
                            evidence.add(receipt);
                        }
                        @Override public void drained() { drained++; }
                    });
        }

        void start() { engine.start(42); }
        void fire(long at) { timer.fire(at); }
        Pixel deliver(int captureIndex, int pixelId) {
            Pixel pixel = new Pixel(pixelId); captures.get(captureIndex).callback.success(pixel.payload()); return pixel;
        }

        final class FakeTimer implements CensorEngine.Timer {
            final List<TimedTask> tasks = new ArrayList<>();
            boolean reject;
            @Override public CensorEngine.Cancellation schedule(Runnable action, long delayMillis) {
                if (reject) throw new RejectedExecutionException();
                TimedTask task = new TimedTask(now + delayMillis, action); tasks.add(task);
                return () -> task.cancelled = true;
            }
            long nextAt() {
                return tasks.stream().filter(t -> !t.cancelled && !t.ran).mapToLong(t -> t.at).min().orElse(Long.MAX_VALUE);
            }
            void fire(long at) {
                assertTrue(at >= now); assertTrue("timer not due", nextAt() <= at); now = at;
                TimedTask task = tasks.stream().filter(t -> !t.cancelled && !t.ran && t.at <= at)
                        .min(java.util.Comparator.comparingLong(t -> t.at)).orElseThrow(AssertionError::new);
                task.ran = true; task.action.run();
            }
        }
    }
}
