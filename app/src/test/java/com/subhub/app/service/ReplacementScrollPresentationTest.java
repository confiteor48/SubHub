package com.subhub.app.service;

import com.subhub.app.censor.engine.NativeTextFrame;
import com.subhub.app.censor.engine.NativeTextResult;
import com.subhub.app.censor.engine.ProtectionScope;
import com.subhub.app.censor.engine.SceneEngine;
import com.subhub.app.censor.engine.ScrollMotionLedger;
import com.subhub.app.overlay.ViewportMotion;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ReplacementScrollPresentationTest {
    private static final ScrollMotionLedger.Scope SCOPE = new ScrollMotionLedger.Scope(1, 42, 7, ProtectionScope.normal(9));
    private static final SceneEngine.Region OWNER = new SceneEngine.Region(0, 200, 1000, 2200);
    private static final SceneEngine.Region BOX = new SceneEngine.Region(100, 600, 300, 800);
    private final ReplacementScrollPresentation presentation = new ReplacementScrollPresentation();
    private final ScrollMotionLedger ledger = new ScrollMotionLedger(64, 16, 256);
    private long delivery;
    private ScrollMotionLedger.OwnerKind ownerKind = ScrollMotionLedger.OwnerKind.UNKNOWN;

    public ReplacementScrollPresentationTest() { presentation.reset(42, 7); ledger.reset(SCOPE); }
    private void captured(long request, long time) { presentation.captured(1, 42, request, time, time + 10); }
    private ScrollMotionLedger.Receipt event(long producer, long owner, long time, long arrival,
            ScrollMotionLedger.Coordinate x, ScrollMotionLedger.Coordinate y, SceneEngine.Region bounds) {
        ScrollMotionLedger.Sample sample = new ScrollMotionLedger.Sample(SCOPE, ++delivery, owner, producer,
                time, arrival, arrival + 1, arrival + 2, x, y, bounds, ownerKind);
        ScrollMotionLedger.Receipt receipt = ledger.observe(sample, arrival + 3);
        presentation.observe(receipt); return receipt;
    }
    private void position(long time, long y) {
        event(1, 10, time, time, ScrollMotionLedger.Coordinate.absolute(0), ScrollMotionLedger.Coordinate.absolute(y), OWNER);
    }
    private static SceneEngine.Mask mask(long request, SceneEngine.Region region) {
        SceneEngine scene = new SceneEngine(1, new SceneEngine.Policy(16, 100, 64, 256, 500, 5000, .5));
        SceneEngine.Source source = SceneEngine.Source.image(1, request, request, 42, 1000, 2400, 10, -1, SCOPE.protection);
        assertTrue(scene.register(source));
        assertTrue(scene.observe(new SceneEngine.Batch(source, SceneEngine.Lane.FAST,
                Collections.singletonList(new SceneEngine.Observation("FACE", 1, 0, region, .9f)), Collections.emptyList())));
        return scene.snapshot(request, 11, false).groups.get(0).members.get(0);
    }
    private double top(long request, long now) {
        ReplacementScrollPresentation.Pose pose = presentation.pose(mask(request, BOX), now);
        return pose == null ? BOX.top : pose.region.top;
    }

    @Test public void mixedOriginsSubtractTheirOwnSourceBaselineWithoutMutatingEvidence() {
        captured(1, 50); captured(2, 150); position(100, 0); position(200, 100);
        assertEquals(500, top(1, 2000), .001); assertEquals(550, top(2, 2000), .001);
        SceneEngine.Mask source = mask(1, BOX); presentation.pose(source, 2000);
        assertEquals(600, source.displayRegion.top, 0); assertEquals(600, source.evidenceRegion.top, 0);
        assertEquals(-1, source.geometrySource.pixelAtMillis);
        assertEquals(OWNER.top, presentation.pose(source, 2000).clip.top, 0);
    }
    @Test public void originAtLatestMeasurementDoesNotApplyItsDisplacementTwice() {
        captured(1, 200); position(100, 0); position(200, 100);
        assertEquals(600, top(1, 2000), .001);
        position(300, 180); assertEquals(520, top(1, 2000), .001);
    }
    @Test public void captureBetweenEventsDoesNotReapplyItsPreCaptureForecast() {
        position(1000, 0); position(1100, 300); position(1200, 600);
        captured(1, 1250);
        assertEquals("Fresh geometry must start at its own presentation origin", 600, top(1, 1250), .001);
    }
    @Test public void laterEventsDoNotRetrospectivelyChangeAnAlreadyAvailableOriginEstimate() {
        ViewportMotion reference = new ViewportMotion(); reference.reset(0, 0, 1003);
        position(1000, 0);
        for (int i = 1; i <= 2; i++) {
            long time = 1000 + i * 100;
            position(time, i * 300); reference.addReportedDelta(0f, -300f, time + 3, 1000, 2000, time);
        }
        captured(1, 1250); float origin = reference.position(1250).y;
        position(1300, 900); reference.addReportedDelta(0f, -300f, 1303, 1000, 2000, 1300);
        assertEquals(600 + reference.position(1340).y - origin, top(1, 1340), .001);
    }
    @Test public void eightSecondFastPauseReverseSweepKeepsEveryFreshOriginStable() {
        for (boolean nativeX : new boolean[]{false, true}) {
            presentation.reset(42, 7); ledger.reset(SCOPE); delivery = 0;
            presentation.configureNativeContext(nativeX ? "com.twitter.android" : "org.chromium.chrome.stable", 1, .015f);
            ownerKind = ScrollMotionLedger.OwnerKind.RECYCLER_VIEW;
            long nextEvent = 1000;
            for (int frame = 1; frame <= 24; frame++) {
                long sourceAt = 1025 + frame * 350;
                while (nextEvent + 3 <= sourceAt) { position(nextEvent, scriptedScroll(nextEvent)); nextEvent += 100; }
                captured(frame, sourceAt);
                assertEquals("New source phase frame=" + frame + " nativeX=" + nativeX, 600, top(frame, sourceAt), .001);
                // A later source must not overwrite an older source's own origin.
                if (frame > 1) assertTrue(Double.isFinite(top(frame - 1, sourceAt)));
            }
        }
    }
    private static long scriptedScroll(long at) {
        long elapsed = at - 1000;
        return elapsed < 2800 ? 10000 + elapsed * 3 : elapsed < 3600 ? 18400
                : elapsed < 6400 ? 18400 - (elapsed - 3600) * 3 : 10000;
    }
    @Test public void sourceIdentityCannotBeRetimedAndEvictionDoesNotBorrowNewestOrigin() {
        captured(1, 50); captured(1, 200); position(100, 0); position(200, 100);
        assertEquals(500, top(1, 2000), .001);
        for (int i = 2; i <= 33; i++) captured(i, 200);
        assertNull(presentation.pose(mask(1, BOX), 2000)); assertEquals(600, top(33, 2000), .001);
    }
    @Test public void missingOrWrongScopeAndFutureOriginsNeverBorrowCallbackTime() {
        position(100, 0); position(200, 100);
        presentation.captured(1, 42, 1, -1, 200);
        assertNull(presentation.pose(mask(1, BOX), 2000));
        presentation.captured(1, 43, 1, 50, 200);
        assertNull(presentation.pose(mask(1, BOX), 2000));
        captured(1, 3000); assertNull(presentation.pose(mask(1, BOX), 2000));
    }
    @Test public void nativeTextAndChromeOutsideTheOwnerAreNotShifted() {
        captured(1, 50); position(100, 0); position(200, 100);
        assertNull(presentation.pose(mask(1, new SceneEngine.Region(100, 0, 300, 100)), 2000));
        SceneEngine scene = new SceneEngine(1, new SceneEngine.Policy(16, 100, 64, 256, 500, 5000, .5));
        SceneEngine.Source source = SceneEngine.Source.nativeText(1, 1, 42, 1000, 2400, 7, 100, 110, 120, SCOPE.protection);
        NativeTextResult read = new NativeTextResult(1000, 2400, 7, 100, 110,
                Collections.singletonList(new NativeTextResult.Candidate("TEXT", String.join("", Collections.nCopies(64, "a")),
                        null, .9f, true, Collections.singletonList(BOX))), 1, 1, 1);
        assertTrue(scene.admitNativeFrame(new NativeTextFrame(source, 3, read, 1000)));
        SceneEngine.Mask nativeMask = scene.snapshot(1, 121, false).groups.get(0).members.get(0);
        assertTrue(nativeMask.nativeValidated); assertNull(presentation.pose(nativeMask, 2000));
    }
    @Test public void nestedOwnersAreAmbiguousNotAdditive() {
        captured(1, 50); position(100, 0); position(200, 100);
        event(2, 20, 210, 210, ScrollMotionLedger.Coordinate.delta(0), ScrollMotionLedger.Coordinate.delta(50),
                new SceneEngine.Region(0, 400, 1000, 2000));
        assertNull(presentation.pose(mask(1, BOX), 2000));
    }
    @Test public void absoluteExplicitCompanionIsOneMovementButSameProducerContinues() {
        captured(1, 50); position(100, 0); position(200, 100);
        event(2, 10, 201, 201, ScrollMotionLedger.Coordinate.delta(0), ScrollMotionLedger.Coordinate.delta(100), OWNER);
        assertEquals(500, top(1, 2000), .001);
        position(300, 200); assertEquals(400, top(1, 2000), .001);
    }
    @Test public void sameDeliveryDoesNotDoubleMotion() {
        captured(1, 50); position(100, 0);
        ScrollMotionLedger.Receipt receipt = event(1, 10, 200, 200,
                ScrollMotionLedger.Coordinate.absolute(0), ScrollMotionLedger.Coordinate.absolute(100), OWNER);
        assertFalse(presentation.observe(ledger.observe(receipt.sample, 205)));
        assertEquals(500, top(1, 2000), .001);
    }
    @Test public void distinctSameMillisecondDeliveriesFromOneProducerRemainAdditive() {
        captured(1, 50);
        for (int i = 0; i < 2; i++) event(1, 10, 100, 100,
                ScrollMotionLedger.Coordinate.delta(0), ScrollMotionLedger.Coordinate.delta(20), OWNER);
        assertEquals(560, top(1, 2000), .001);
    }
    @Test public void olderCrossProducerCallbackInvalidatesRatherThanReordersTheHistory() {
        captured(1, 50); position(100, 0); position(200, 100);
        event(2, 10, 190, 210, ScrollMotionLedger.Coordinate.delta(0), ScrollMotionLedger.Coordinate.delta(20), OWNER);
        assertNull(presentation.pose(mask(1, BOX), 2000));
    }
    @Test public void verticalOnlyCoordinatesRemainUsefulWithoutClaimingAnObservedZeroX() {
        captured(1, 50);
        event(1, 10, 100, 100, ScrollMotionLedger.Coordinate.unknown(), ScrollMotionLedger.Coordinate.absolute(0), OWNER);
        event(1, 10, 200, 200, ScrollMotionLedger.Coordinate.unknown(), ScrollMotionLedger.Coordinate.absolute(100), OWNER);
        ReplacementScrollPresentation.Pose pose = presentation.pose(mask(1, BOX), 2000);
        assertNotNull(pose); assertEquals(100, pose.region.left, .001); assertEquals(500, pose.region.top, .001);
    }
    @Test public void gapInvalidatesOriginsEvenBeforeTheFirstPresentationEvent() {
        captured(1, 50);
        ScrollMotionLedger.Sample sample = new ScrollMotionLedger.Sample(SCOPE, ++delivery, 10, 1, 100, 100, 101, 102,
                ScrollMotionLedger.Coordinate.unknown(), ScrollMotionLedger.Coordinate.unknown(), OWNER);
        presentation.observe(ledger.gap(sample, 103)); position(200, 0); position(300, 100);
        assertNull(presentation.pose(mask(1, BOX), 2000));
    }
    @Test public void resetAndOwnerResizeRetireOldPresentationAndAnimation() {
        captured(1, 50); position(100, 0); position(200, 100); position(300, 200);
        presentation.reset(43, 8); assertFalse(presentation.isAnimating(303));
        assertNull(presentation.pose(mask(1, BOX), 2000));
        presentation.reset(42, 7); captured(1, 50); position(400, 300);
        event(1, 10, 500, 500, ScrollMotionLedger.Coordinate.absolute(0), ScrollMotionLedger.Coordinate.absolute(400),
                new SceneEngine.Region(0, 300, 1000, 2200));
        assertNull(presentation.pose(mask(1, BOX), 2000));
    }
    @Test public void lateCallbacksApplyKnownDisplacementWithoutRestartingExpiredPrediction() {
        captured(1, 50); position(100, 0); position(200, 100);
        event(1, 10, 300, 900, ScrollMotionLedger.Coordinate.absolute(0), ScrollMotionLedger.Coordinate.absolute(200), OWNER);
        // Reuse the old 32 ms correction, not a new immediate jump or another fling.
        assertEquals(500, top(1, 903), .001); assertEquals(400, top(1, 935), .001);
        assertEquals(400, top(1, 1100), .001); assertFalse(presentation.isAnimating(935));
    }
    @Test public void reversalSettlesAndDisplayFramesAdvanceBetweenMeasurements() {
        captured(1, 50); position(100, 0); position(200, 100); position(300, 200);
        assertTrue(presentation.isAnimating(320)); assertNotEquals(top(1, 320), top(1, 336), .01);
        position(400, 100); assertEquals(500, top(1, 2000), .001); assertFalse(presentation.isAnimating(2000));
    }
    @Test public void boundedHistoryDoesNotContinueAnEvictedOrigin() {
        captured(1, 50);
        for (int i = 1; i <= 194; i++) position(i * 100L, i * 10L);
        assertNull(presentation.pose(mask(1, BOX), 20000));
    }
    @Test public void preparingUnusedOriginCannotInvalidateUnchangedExistingPaint() {
        for (boolean nativeCurve : new boolean[]{false, true}) {
            presentation.reset(42, 7); ledger.reset(SCOPE); delivery = 0;
            presentation.configureNativeContext(nativeCurve ? "com.twitter.android" : "org.chromium.chrome.stable", 1, .015f);
            ownerKind = ScrollMotionLedger.OwnerKind.RECYCLER_VIEW;
            captured(1, 1050); position(1000, 0); position(1100, 100); position(1200, 200);
            double oldPose = top(1, 1216); long oldRevision = presentation.revision();
            boolean oldAnimation = presentation.isAnimating(1216);
            presentation.preparedBeforeAdmission(1, 42, 2, 1210, 1220);
            assertEquals(oldPose, top(1, 1216), .000001);
            assertEquals(oldAnimation, presentation.isAnimating(1216));
            assertEquals("Unused pre-admission metadata cannot reject an unchanged in-flight old paint", oldRevision, presentation.revision());
        }
    }
    @Test public void preAdmissionEvictionStillInvalidatesExistingPaint() {
        captured(1, 50); position(100, 0); position(200, 100);
        for (int request = 2; request <= 32; request++) presentation.preparedBeforeAdmission(1, 42, request, 200, 210);
        assertNotNull(presentation.pose(mask(1, BOX), 240)); long before = presentation.revision();
        presentation.preparedBeforeAdmission(1, 42, 33, 200, 210);
        assertTrue(presentation.revision() > before); assertNull(presentation.pose(mask(1, BOX), 240));
    }
    @Test public void lateFirstRegistrationMustInvalidatePreviouslyUnknownMemberPose() {
        position(100, 0); position(200, 100);
        assertEquals(BOX.top, top(1, 240), 0); long before = presentation.revision();
        captured(1, 50);
        assertNotEquals(BOX.top, top(1, 240), .01); assertTrue(presentation.revision() > before);
    }
}
