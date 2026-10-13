package com.subhub.app.censor.engine;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.Executor;
import org.junit.Test;

public class NativeTextEngineTest {
    @Test public void nativeViewportHasRealReadSourceAndFiniteDeadlineWithoutPixelLease() {
        Harness h = new Harness(); h.engine.executionControls(334, false, EnumSet.noneOf(SceneEngine.Lane.class));
        h.engine.start(42); h.fire(0); h.nativeExecutor.runAll(); h.finishNative(0, 10, 20, 1000);
        CensorEngine.ViewportCoverage coverage = h.engine.viewportCoverage();
        assertEquals(CensorEngine.CoverageState.AVAILABLE, coverage.state); assertEquals(1011, coverage.validBeforeMillis);
        assertEquals(1, coverage.regions.size()); CensorEngine.DetectedRegion member = coverage.regions.get(0);
        assertTrue(member.nativeValidated); assertEquals(SceneEngine.SourceKind.NATIVE_TEXT, member.evidenceSource.kind);
        assertSame(member.evidenceSource, member.geometrySource); assertEquals(-1, member.evidenceSource.pixelAtMillis);
        assertEquals(1000, member.displayRegion.top, 0); assertEquals(0, h.engine.livePixelFrames());
        assertEquals(0, h.captures.size()); assertTrue(h.engine.coverageCurrent(coverage)); h.close();
    }

    @Test public void contentChangeRevokesNativeViewportImmediatelyWhileOldPaintIsStillRetained() {
        Harness h = new Harness(); h.engine.executionControls(334, false, EnumSet.noneOf(SceneEngine.Lane.class));
        h.engine.start(42); h.fire(0); h.nativeExecutor.runAll(); h.finishNative(0, 10, 20, 1000); h.ui.runAll();
        CensorEngine.ViewportCoverage old = h.engine.viewportCoverage(); h.engine.requestNativeTextAt(20, true);
        assertFalse(h.engine.coverageCurrent(old));
        CensorEngine.ViewportCoverage gap = h.engine.viewportCoverage();
        assertEquals(CensorEngine.CoverageState.NO_ELIGIBLE_REGIONS, gap.state); assertEquals(1, gap.ineligibleMembers);
        try (CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView()) { assertEquals(1, masks(view).size()); }
        h.fire(140); h.nativeExecutor.runAll(); h.finishNative(1, 150, 160, 1100);
        assertFalse(h.engine.coverageCurrent(gap));
        assertEquals(1100, h.engine.viewportCoverage().regions.get(0).displayRegion.top, 0); h.close();
    }

    @Test public void failedNativeRefreshIsNotAnEmptyViewportOrADeadlineRenewal() {
        Harness h = new Harness(); h.engine.executionControls(334, false, EnumSet.noneOf(SceneEngine.Lane.class));
        h.engine.start(42); h.fire(0); h.nativeExecutor.runAll(); h.finishNative(0, 10, 20, 1000); h.ui.runAll();
        CensorEngine.ViewportCoverage old = h.engine.viewportCoverage(); h.engine.requestNativeTextAt(20, false);
        assertTrue(h.engine.coverageCurrent(old)); h.fire(140); h.nativeExecutor.runAll(); h.nativeCompletions.get(1).failure();
        assertEquals(CensorEngine.CoverageState.AVAILABLE, h.engine.viewportCoverage().state);
        assertEquals(old.validBeforeMillis, h.engine.viewportCoverage().validBeforeMillis);
        h.now = 1011; assertFalse(h.engine.coverageCurrent(old));
        assertEquals(CensorEngine.CoverageState.EXPIRED, h.engine.viewportCoverage().state); h.close();
    }

    @Test public void acceptedNativeEmptyHasNoViewportRegionsAndRejectsOldReceipt() {
        Harness h = new Harness(); h.engine.executionControls(334, false, EnumSet.noneOf(SceneEngine.Lane.class));
        h.engine.start(42); h.fire(0); h.nativeExecutor.runAll(); h.finishNative(0, 10, 20, 1000); h.ui.runAll();
        CensorEngine.ViewportCoverage old = h.engine.viewportCoverage();
        h.engine.requestNativeTextAt(140, false); h.fire(140); h.nativeExecutor.runAll(); h.finishNative(1, 150, 160);
        assertFalse(h.engine.coverageCurrent(old));
        CensorEngine.ViewportCoverage empty = h.engine.viewportCoverage();
        assertEquals(CensorEngine.CoverageState.NO_ELIGIBLE_REGIONS, empty.state); assertTrue(empty.regions.isEmpty());
        assertTrue(h.engine.coverageCurrent(empty)); assertEquals(2, h.evidence.size()); h.close();
    }

    @Test public void newerRawImageDoesNotPretendToMoveNativeBoundsOrRevalidateOldReceipt() {
        Harness h = new Harness(); h.engine.start(42); h.fire(0); h.nativeExecutor.runAll();
        h.finishNative(0, 10, 20, 1000); h.ui.runAll(); CensorEngine.ViewportCoverage old = h.engine.viewportCoverage();
        h.deliverImage(0, 30, 1000, 2400); // Native geometry does not use an IMAGE graph edge.
        assertFalse(h.engine.coverageCurrent(old));
        CensorEngine.ViewportCoverage current = h.engine.viewportCoverage();
        assertEquals(CensorEngine.CoverageState.AVAILABLE, current.state); assertEquals(1, current.regions.size());
        CensorEngine.DetectedRegion member = current.regions.get(0);
        assertEquals(1, member.evidenceSource.sequence); assertSame(member.evidenceSource, member.geometrySource);
        assertEquals(1000, member.displayRegion.top, 0); h.close();
    }

    @Test public void mixedViewportUsesEarliestSourceExpiryThenRefreshesSurvivingImageOnly() {
        Harness h = new Harness(); h.engine.start(42); h.fire(0); h.nativeExecutor.runAll();
        h.finishNative(0, 10, 20, 1000); h.deliverImage(0, 30, 1000, 2400); h.imageExecutor.runAll();
        h.finishImage(0, 40, 500); h.ui.runAll(); CensorEngine.ViewportCoverage mixed = h.engine.viewportCoverage();
        assertEquals(2, mixed.regions.size()); assertEquals(1011, mixed.validBeforeMillis);
        h.now = 1011; assertFalse(h.engine.coverageCurrent(mixed));
        assertEquals(CensorEngine.CoverageState.EXPIRED, h.engine.viewportCoverage().state);
        try (CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView()) { assertEquals(1, masks(view).size()); }
        CensorEngine.ViewportCoverage imageOnly = h.engine.viewportCoverage();
        assertEquals(CensorEngine.CoverageState.AVAILABLE, imageOnly.state); assertEquals(1, imageOnly.regions.size());
        assertFalse(imageOnly.regions.get(0).nativeValidated); assertEquals(5031, imageOnly.validBeforeMillis); h.close();
    }

    @Test public void repeatedViewportQueriesCannotStartNativeReadsOrSupplyMoreWitnesses() {
        Harness h = new Harness(); h.engine.executionControls(334, false, EnumSet.noneOf(SceneEngine.Lane.class));
        h.engine.start(42); h.fire(0); h.nativeExecutor.runAll(); h.finishNative(0, 10, 20, 1000); h.ui.runAll();
        for (int i = 0; i < 100; i++) assertEquals(1, h.engine.viewportCoverage().regions.size());
        assertEquals(1, h.nativeInputs.size()); assertEquals(1, h.nativeFrames.size()); assertEquals(1, h.evidence.size());
        assertEquals(1, h.evidence.get(0).subjects.get(0).witnesses.size()); assertEquals(0, h.engine.livePixelFrames());
        h.engine.nativeTextControls(false);
        assertEquals(CensorEngine.CoverageState.NO_ELIGIBLE_REGIONS, h.engine.viewportCoverage().state); h.close();
    }

    @Test public void nativeSourceReceiptsNeedFiveActualReadsAndConsumeOneLiveNode() {
        Harness h = new Harness(); h.engine.executionControls(334, false, EnumSet.noneOf(SceneEngine.Lane.class));
        h.engine.start(42, ProtectionScope.normal(9));
        for (int i = 0; i < 5; i++) {
            long at = i * 140L; if (i > 0) h.engine.requestNativeTextAt(at, false);
            h.fire(at); h.nativeExecutor.runAll();
            h.finishNative(i, at + 10, at + 20, 1000); h.ui.runAll();
            CensorEngine.EvidenceReceipt receipt = h.evidence.get(i);
            assertEquals(SceneEngine.SourceKind.NATIVE_TEXT, receipt.source.kind);
            assertEquals(0, receipt.source.imageRequestIdentity);
            assertEquals(ProtectionScope.normal(9), receipt.scope.protectionScope);
            assertEquals(i + 1, receipt.subjects.get(0).witnesses.size());
            assertEquals(i + 1, receipt.subjects.get(0).witnesses.get(i).producerIdentity);
            assertEquals(i == 4 ? 1 : 0, h.engine.claimEvidence(receipt).subjects.size());
        }
        assertTrue(h.engine.claimEvidence(h.evidence.get(4)).subjects.isEmpty());
        assertEquals(0, h.engine.livePixelFrames()); h.close(); assertEquals(1, h.drained);
    }

    @Test public void acceptedNativeEmptyWithdrawsOldIdentityClaimAuthority() {
        Harness h = new Harness(); h.engine.executionControls(334, false, EnumSet.noneOf(SceneEngine.Lane.class));
        h.engine.start(42);
        for (int i = 0; i < 5; i++) {
            long at = i * 140L; if (i > 0) h.engine.requestNativeTextAt(at, false);
            h.fire(at); h.nativeExecutor.runAll(); h.finishNative(i, at + 10, at + 20, 1000); h.ui.runAll();
        }
        CensorEngine.EvidenceReceipt old = h.evidence.get(4);
        h.engine.requestNativeTextAt(700, false); h.fire(700); h.nativeExecutor.runAll(); h.finishNative(5, 710, 720); h.ui.runAll();
        assertNull(h.engine.claimEvidence(old)); assertTrue(h.evidence.get(5).subjects.isEmpty());
        assertTrue(h.engine.claimEvidence(h.evidence.get(5)).subjects.isEmpty()); h.close();
    }

    @Test public void nativeEvidenceExpiresWithoutAnotherProviderReadAndKeepsCapturedProtection() {
        Harness h = new Harness(); h.engine.executionControls(334, false, EnumSet.noneOf(SceneEngine.Lane.class));
        h.engine.start(42, ProtectionScope.lab(9));
        for (int i = 0; i < 5; i++) {
            long at = i * 140L; if (i > 0) h.engine.requestNativeTextAt(at, false);
            h.fire(at); h.nativeExecutor.runAll(); h.finishNative(i, at + 10, at + 20, 1000); h.ui.runAll();
        }
        CensorEngine.EvidenceReceipt receipt = h.evidence.get(4);
        assertFalse(receipt.scope.protectionScope.isNormal());
        h.now = receipt.subjects.get(0).validUntilMillis + 1; assertNull(h.engine.claimEvidence(receipt));
        h.engine.changeScope(42, ProtectionScope.normal(10)); assertNull(h.engine.claimEvidence(receipt)); h.close();
    }

    @Test public void onlyFiniteAcceptedNativeCoverageSuppressesImageOcrFallback() {
        Harness h = new Harness(); h.engine.executionControls(334, false, EnumSet.noneOf(SceneEngine.Lane.class));
        h.engine.start(42); h.fire(0); h.nativeExecutor.runAll(); assertFalse(h.engine.imageTextSuppressed());
        h.finishNative(0, 10, 20, 1000); assertTrue(h.engine.imageTextSuppressed());
        h.engine.requestNativeTextAt(20, false); h.fire(140); h.nativeExecutor.runAll(); h.nativeCompletions.get(1).failure();
        assertTrue("Failed provider read is not a complete empty replacement", h.engine.imageTextSuppressed());
        h.now = 1010; assertTrue(h.engine.imageTextSuppressed()); h.now = 1011; assertFalse(h.engine.imageTextSuppressed());
        h.engine.changeScope(43); assertFalse(h.engine.imageTextSuppressed()); h.close();
    }

    @Test public void retiredNativeSetCannotKeepOcrSuppressedThroughItsOldReceiptDeadline() {
        Harness h = new Harness(16, 2, 3); h.engine.start(42); h.fire(0); h.nativeExecutor.runAll();
        h.finishNative(0, 10, 20, 900, 1100); assertTrue(h.engine.imageTextSuppressed());
        h.deliverImage(0, 30, 1000, 2400); h.imageExecutor.runAll();
        CensorEngine.Input<Pixel> input = h.images.get(0);
        h.now = 40; h.imageCompletions.get(0).success(new SceneEngine.Batch(input.source, SceneEngine.Lane.FAST,
                java.util.Arrays.asList(new SceneEngine.Observation("FACE", 1, 0, region(500), .9f),
                        new SceneEngine.Observation("FACE", 1, 0, region(700), .9f)), Collections.emptyList()),
                new CensorEngine.WorkTiming(1, 2, 1)); h.ui.runAll();
        assertFalse("Scene retired its complete native set; old receipt cannot suppress fallback", h.engine.imageTextSuppressed());
        try (CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView()) {
            assertEquals(2, masks(view).size()); for (SceneEngine.Mask mask : masks(view)) assertFalse(mask.nativeValidated);
        }
        h.close(); assertEquals(1, h.drained);
    }
    private static final String CONTENT = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String NODE = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";
    private static SceneEngine.Region region(double y) { return new SceneEngine.Region(100, y, 200, y + 30); }
    private static List<SceneEngine.Mask> masks(CensorEngine<Pixel>.RenderView view) {
        List<SceneEngine.Mask> values = new ArrayList<>();
        for (SceneEngine.Group group : view.snapshot.groups) values.addAll(group.members);
        return values;
    }
    private static NativeTextResult read(long start, long end, int width, int height, double... ys) {
        List<NativeTextResult.Candidate> candidates = new ArrayList<>();
        for (double y : ys) candidates.add(new NativeTextResult.Candidate("TEXT_SMUT", CONTENT,
                ys.length == 1 ? NODE : null, .9f, true, Collections.singletonList(region(y))));
        return new NativeTextResult(width, height, 7, start, end, candidates,
                Math.max(1, candidates.size()), candidates.size(), candidates.size());
    }

    @Test public void nativeFirstInterleavingKeepsImageRequestLineageAndNeverAliasesEffectPixels() {
        Harness h = new Harness(); h.engine.start(42); h.fire(0); h.nativeExecutor.runAll();
        assertEquals(1, h.captures.size()); assertTrue(h.engine.captureInFlight()); assertEquals(0, h.images.size());
        h.finishNative(0, 10, 20, 1000); h.ui.runAll();
        NativeTextFrame frame = h.nativeFrames.get(0);
        assertEquals(1, frame.source.sequence); assertEquals(0, frame.source.imageRequestIdentity);
        assertEquals(0, frame.queuedAtMillis); assertEquals(0, frame.startedAtMillis);
        assertEquals(0, frame.source.readStartedAtMillis); assertEquals(10, frame.source.readFinishedAtMillis);
        assertEquals(20, frame.source.receivedAtMillis); assertEquals(-1, frame.source.pixelAtMillis);
        try (CensorEngine<Pixel>.RenderView nativeOnly = h.engine.acquireRenderView()) {
            assertNotNull(nativeOnly); assertEquals(1, nativeOnly.snapshot.groups.size());
            assertNull(nativeOnly.sourcePixels(frame.source.sequence));
            assertTrue(masks(nativeOnly).get(0).nativeValidated);
        }
        Pixel image = h.deliverImage(0, 30, 1000, 2400); h.imageExecutor.runAll();
        CensorEngine.Input<Pixel> input = h.images.get(0);
        assertEquals(2, input.source.sequence); assertEquals(1, input.source.imageRequestIdentity);
        h.finishImage(0, 50, 500); h.ui.runAll();
        try (CensorEngine<Pixel>.RenderView mixed = h.engine.acquireRenderView()) {
            assertEquals(2, mixed.snapshot.destination.sequence); assertEquals(2, masks(mixed).size());
            assertSame(image, mixed.sourcePixels(2)); assertNull(mixed.sourcePixels(1));
            for (SceneEngine.Mask mask : masks(mixed)) {
                assertEquals(mask.nativeValidated ? 1000 : 500, mask.displayRegion.top, 0);
                assertEquals(mask.nativeValidated ? 1 : 2, mask.evidenceSource.sequence);
            }
        }
        int publications = 0, unknown = 0, imagePublications = 0;
        boolean request = false, started = false, completed = false;
        for (CensorEngine.Trace trace : h.traces) {
            if (trace.stage == CensorEngine.Stage.CAPTURE_REQUESTED) { assertEquals(1, trace.source); request = true; }
            if (trace.stage == CensorEngine.Stage.JOB_STARTED) { assertEquals(1, trace.source); started = true; }
            if (trace.stage == CensorEngine.Stage.JOB_COMPLETED) { assertEquals(1, trace.source); completed = true; }
            if (trace.stage == CensorEngine.Stage.PRESENTED) {
                publications++;
                if (trace.source == 0) { unknown++; assertEquals(-1, trace.pixelAgeMillis); }
                else { imagePublications++; assertEquals(1, trace.source); }
            }
        }
        assertTrue(request && started && completed); assertEquals(2, publications);
        assertEquals(1, unknown); assertEquals(1, imagePublications); // Never parse unknown native rows as zero work.
        h.close(); assertEquals(1, image.closes); assertEquals(1, h.drained);
    }

    @Test public void nativeOnlyReadNeedsNoVisualCategoriesCapturePermissionOrModelInitialization() {
        Harness h = new Harness(); h.engine.executionControls(334, false, EnumSet.noneOf(SceneEngine.Lane.class));
        h.engine.start(42); h.fire(0); h.nativeExecutor.runAll(); h.finishNative(0, 10, 20, 1000); h.ui.runAll();
        assertTrue(h.captures.isEmpty()); assertTrue(h.images.isEmpty()); assertEquals(0, h.engine.livePixelFrames());
        try (CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView()) {
            assertNotNull(view); assertNull(view.sourcePixels(view.snapshot.destination.sequence));
            assertEquals(SceneEngine.SourceKind.NATIVE_TEXT, view.snapshot.destination.kind);
        }
        h.close(); assertEquals(1, h.drained);
    }

    @Test public void nativeRefreshCannotReplaceReadyImageCanvasOrImageHintEligibility() {
        Harness h = new Harness(); h.engine.start(42); h.fire(0); h.nativeExecutor.runAll();
        Pixel a = h.deliverImage(0, 10, 1000, 2400); h.imageExecutor.runAll(); h.finishImage(0, 20, 500); h.ui.runAll();
        h.finishNative(0, 30, 40, 1000); h.ui.runAll();
        long canvas;
        try (CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView()) {
            canvas = view.snapshot.destination.sequence; assertEquals(1, canvas); assertEquals(2, masks(view).size());
        }
        h.fire(160); h.nativeExecutor.runAll(); h.finishNative(1, 170, 180, 900); h.ui.runAll();
        try (CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView()) {
            assertEquals(canvas, view.snapshot.destination.sequence); assertEquals(2, masks(view).size());
            assertSame(a, view.sourcePixels(canvas));
        }
        ImagePresentationEstimate wrong = new ImagePresentationEstimate(h.engine.currentEpoch(), 1,
                h.nativeFrames.get(1).source.sequence, 1, 1,
                Collections.singletonList(new ImagePresentationEstimate.Piece(region(500), 0, 0, 0, 0)));
        assertFalse(h.engine.presentationEstimate(wrong)); h.close(); assertEquals(1, a.closes);
    }

    @Test public void queuedAndRunningNativeCancellationKeepRealTerminalDrainOwnership() {
        for (boolean running : new boolean[]{false, true}) {
            Harness h = new Harness(); h.engine.start(42); h.fire(0);
            if (running) h.nativeExecutor.runAll();
            h.engine.close(); h.captures.get(0).callback.failure(0);
            assertEquals(running ? 0 : 1, h.drained);
            h.nativeExecutor.runAll();
            if (running) {
                assertTrue(h.nativeInputs.get(0).cancelled()); assertEquals(1, h.nativeCancellations);
                h.nativeCompletions.get(0).cancelled();
                h.nativeCompletions.get(0).failure();
            }
            assertEquals(1, h.drained); assertFalse(h.engine.nativeTextInFlight());
            assertTrue(h.nativeFrames.isEmpty());
        }
    }

    @Test public void latestContentDemandCoalescesAndRejectsObsoleteReadWithoutEarlyCleanup() {
        Harness h = new Harness(); h.engine.start(42); h.fire(0); h.nativeExecutor.runAll();
        h.now = 10; h.engine.requestNativeTextAt(10, true); h.engine.requestNativeTextAt(11, true);
        assertTrue(h.engine.nativeTextInFlight()); assertEquals(1, h.nativeCancellations);
        h.finishNative(0, 20, 30, 1000); assertTrue(h.nativeFrames.isEmpty());
        h.fire(150); h.nativeExecutor.runAll(); assertEquals(2, h.nativeInputs.size());
        h.finishNative(1, 160, 170, 900); h.ui.runAll();
        assertEquals(1, h.nativeFrames.size()); h.close(); assertEquals(1, h.drained);
    }

    @Test public void unavailableOrAlreadyExpiredNativeResultKeepsPreviousWholeFrameAndAge() {
        Harness h = new Harness(); h.engine.start(42); h.fire(0); h.nativeExecutor.runAll();
        h.finishNative(0, 10, 20, 1000, 1200); h.ui.runAll();
        long revision;
        try (CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView()) { revision = view.snapshot.revision; }
        h.engine.requestNativeTextAt(20, false); h.fire(140); h.nativeExecutor.runAll();
        h.nativeCompletions.get(1).failure(); h.ui.runAll();
        try (CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView()) {
            assertEquals(revision, view.snapshot.revision); assertEquals(2, masks(view).size());
            assertEquals(1011, view.snapshot.nextChangeAtMillis);
        }
        h.now = 1011; h.ui.runAll();
        try (CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView()) { assertTrue(view.snapshot.groups.isEmpty()); }
        h.close(); assertEquals(1, h.drained);
    }

    @Test public void nativeThenChangedFirstImageDimensionsFenceTheCommonEpoch() {
        Harness h = new Harness(); h.engine.start(42); h.fire(0); h.nativeExecutor.runAll();
        h.finishNative(0, 10, 20, 1000); h.ui.runAll();
        CensorEngine<Pixel>.RenderView held = h.engine.acquireRenderView();
        long epoch = h.engine.currentEpoch(); Pixel discarded = h.deliverImage(0, 30, 900, 1600);
        assertEquals(epoch + 1, h.engine.currentEpoch()); assertEquals(1, discarded.closes);
        assertNull(h.engine.acquireRenderView()); assertTrue(h.images.isEmpty());
        assertTrue(masks(held).get(0).nativeValidated); held.close(); h.close(); assertEquals(1, h.drained);
    }

    @Test public void invalidReadIntervalAndTightOptionalCapacityCannotClearReadyPixels() {
        for (boolean capacity : new boolean[]{false, true}) {
            Harness h = new Harness(capacity ? 10 : 16); h.engine.start(42); h.fire(0); h.nativeExecutor.runAll();
            Pixel pixel = h.deliverImage(0, 10, 1000, 2400); h.imageExecutor.runAll(); h.finishImage(0, 20, 500); h.ui.runAll();
            h.now = 30;
            h.nativeCompletions.get(0).success(read(capacity ? 0 : 1, 25, 1000, 2400, 1000));
            h.ui.runAll(); assertTrue(h.nativeFrames.isEmpty());
            try (CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView()) {
                assertEquals(1, masks(view).size()); assertSame(pixel, view.sourcePixels(view.snapshot.destination.sequence));
            }
            h.close(); assertEquals(1, pixel.closes); assertEquals(1, h.drained);
        }
    }

    @Test public void equalNativeAndImagePartitionNumbersCannotHideEitherKindOfEvidence() {
        for (boolean nativeFirst : new boolean[]{false, true}) {
            Harness h = new Harness(16, 1); h.engine.start(42); h.fire(0); h.nativeExecutor.runAll();
            if (nativeFirst) { h.finishNative(0, 10, 20, 1000); h.ui.runAll(); }
            Pixel image = h.deliverImage(0, nativeFirst ? 30 : 10, 1000, 2400);
            h.imageExecutor.runAll(); h.finishImage(0, nativeFirst ? 40 : 20, 500); h.ui.runAll();
            if (!nativeFirst) { h.finishNative(0, 30, 40, 1000); h.ui.runAll(); }
            try (CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView()) {
                assertEquals("Partition numbers cannot alias source kinds", 2, masks(view).size());
                for (SceneEngine.Mask mask : masks(view)) {
                    assertEquals(mask.nativeValidated ? 1000 : 500, mask.displayRegion.top, 0);
                    assertEquals(mask.nativeValidated ? SceneEngine.SourceKind.NATIVE_TEXT : SceneEngine.SourceKind.IMAGE,
                            mask.geometrySource.kind);
                }
                assertSame(image, view.sourcePixels(view.snapshot.destination.sequence));
            }
            h.close(); assertEquals(1, image.closes); assertEquals(1, h.drained);
        }
    }

    @Test public void earlyNativeTimerDeliveryCannotBypassActualCompletionRefreshFloor() {
        Harness h = new Harness(); h.engine.start(42); h.fire(0); h.nativeExecutor.runAll();
        h.finishNative(0, 10, 40, 1000); h.engine.requestNativeTextAt(40, false);
        h.now = 159;
        for (Timed timed : new ArrayList<>(h.timers)) {
            if (!timed.ran && !timed.cancelled && timed.at == 160) { timed.ran = true; timed.action.run(); }
        }
        h.nativeExecutor.runAll(); assertEquals("An early timer is not read admission", 1, h.nativeInputs.size());
        h.fire(160); h.nativeExecutor.runAll(); assertEquals(2, h.nativeInputs.size());
        assertEquals(160L, h.nativeStartTimes.get(1).longValue()); h.close(); assertEquals(1, h.drained);
    }

    @Test public void nativeRefreshFloorUsesActualCompletionAndCannotChangeImageApiSpacing() {
        Harness h = new Harness(); h.engine.start(42); h.fire(0); h.nativeExecutor.runAll();
        h.finishNative(0, 10, 40, 1000); h.engine.requestNativeTextAt(40, false);
        h.fire(159); assertEquals(1, h.nativeInputs.size()); h.fire(160); h.nativeExecutor.runAll();
        assertEquals(2, h.nativeInputs.size()); h.nativeCompletions.get(1).failure();
        h.captures.get(0).callback.failure(0); h.fire(333); assertEquals(1, h.captures.size());
        h.fire(334); assertEquals(2, h.captures.size()); h.close(); assertEquals(1, h.drained);
    }

    @Test public void nativeCanvasNeverBorrowsImagePixelsAndCapturesOriginalLabTapProvenance() {
        Harness h = new Harness(); h.engine.canvasSamplingControls(true);
        h.engine.executionControls(334, false, EnumSet.noneOf(SceneEngine.Lane.class));
        h.engine.start(42, ProtectionScope.lab(9)); h.fire(0); h.nativeExecutor.runAll();
        assertEquals(ProtectionScope.lab(9), h.nativeInputs.get(0).protectionScope);
        h.finishNative(0, 10, 20, 1000); h.ui.runAll();
        CensorEngine<Pixel>.RenderView held = h.engine.acquireRenderView();
        assertEquals(ProtectionScope.lab(9), held.snapshot.destination.protectionScope);
        assertNull(held.canvasSource); assertNull(held.canvasPixels());
        assertNull(held.sourcePixels(held.snapshot.destination.sequence));
        h.engine.changeScope(42, ProtectionScope.normal(10));
        assertEquals(ProtectionScope.lab(9), masks(held).get(0).evidenceSource.protectionScope);
        assertFalse(masks(held).get(0).evidenceSource.protectionScope.isNormal());
        assertNull(h.engine.acquireRenderView()); h.close(); assertEquals(0, h.drained);
        held.close(); assertEquals(1, h.drained); assertEquals(0, h.engine.livePixelFrames());
    }

    @Test public void lateNativeReadCannotBeRelabeledAfterSameGeometryProtectionChange() {
        Harness h = new Harness(); h.engine.executionControls(334, false, EnumSet.noneOf(SceneEngine.Lane.class));
        h.engine.start(42, ProtectionScope.lab(9)); h.fire(0); h.nativeExecutor.runAll();
        long epoch = h.engine.currentEpoch(); h.now = 10; h.engine.changeScope(42, ProtectionScope.normal(10));
        assertEquals(epoch + 1, h.engine.currentEpoch()); assertEquals(1, h.nativeCancellations);
        assertTrue(h.engine.nativeTextInFlight()); assertEquals(ProtectionScope.lab(9), h.nativeInputs.get(0).protectionScope);
        h.finishNative(0, 15, 20, 1000); assertTrue(h.nativeFrames.isEmpty());
        h.fire(139); h.nativeExecutor.runAll(); assertEquals(1, h.nativeInputs.size());
        h.fire(140); h.nativeExecutor.runAll(); assertEquals(2, h.nativeInputs.size());
        assertEquals(ProtectionScope.normal(10), h.nativeInputs.get(1).protectionScope);
        h.finishNative(1, 150, 160, 900); h.ui.runAll();
        assertEquals(1, h.nativeFrames.size()); assertEquals(ProtectionScope.normal(10), h.nativeFrames.get(0).source.protectionScope);
        h.close(); assertEquals(1, h.drained);
    }

    private static final class Pixel {
        int closes;
        CensorEngine.Captured<Pixel> payload(int width, int height) {
            return new CensorEngine.Captured<>(this, width, height, -1, () -> { assertEquals(0, closes); closes++; });
        }
    }
    private static final class Queue implements Executor {
        final List<Runnable> actions = new ArrayList<>();
        @Override public void execute(Runnable action) { actions.add(action); }
        void runAll() { while (!actions.isEmpty()) actions.remove(0).run(); }
    }
    private static final class Capture {
        final CensorEngine.CaptureRequest request;
        final CensorEngine.CaptureCallback<Pixel> callback;
        Capture(CensorEngine.CaptureRequest request, CensorEngine.CaptureCallback<Pixel> callback) {
            this.request = request; this.callback = callback;
        }
    }
    private static final class Timed {
        long at; Runnable action; boolean cancelled, ran;
        Timed(long at, Runnable action) { this.at = at; this.action = action; }
    }
    private static final class Harness {
        long now;
        int drained, nativeCancellations;
        final Queue imageExecutor = new Queue(), nativeExecutor = new Queue(), ui = new Queue();
        final List<Timed> timers = new ArrayList<>();
        final List<Capture> captures = new ArrayList<>();
        final List<CensorEngine.Input<Pixel>> images = new ArrayList<>();
        final List<CensorEngine.Completion> imageCompletions = new ArrayList<>();
        final List<CensorEngine.NativeTextInput> nativeInputs = new ArrayList<>();
        final List<CensorEngine.NativeTextCompletion> nativeCompletions = new ArrayList<>();
        final List<NativeTextFrame> nativeFrames = new ArrayList<>();
        final List<CensorEngine.EvidenceReceipt> evidence = new ArrayList<>();
        final List<CensorEngine.Trace> traces = new ArrayList<>();
        final CensorEngine<Pixel> engine;
        Harness() { this(16); }
        Harness(int sources) { this(sources, 2); }
        Harness(int sources, long nativePartition) {
            this(sources, nativePartition, 100);
        }
        Harness(int sources, long nativePartition, int maximumTracks) {
            engine = new CensorEngine<>(new CensorEngine.Policy(334, 0, 8, 2, false),
                    new SceneEngine.Policy(sources, maximumTracks, 64, 256, 5000, 20000, .5), () -> now,
                    (action, delay) -> { Timed task = new Timed(now + delay, action); timers.add(task); return () -> task.cancelled = true; },
                    (request, callback) -> { assertTrue(request.tryDispatch()); captures.add(new Capture(request, callback)); },
                    Collections.singletonList(new CensorEngine.LaneWorker<>(SceneEngine.Lane.FAST, imageExecutor,
                            (input, completion) -> { images.add(input); imageCompletions.add(completion); })), ui,
                    new CensorEngine.Observer() {
                        @Override public void trace(CensorEngine.Trace trace) { traces.add(trace); }
                        @Override public void nativeTextAccepted(NativeTextFrame frame) { nativeFrames.add(frame); }
                        @Override public void evidenceAccepted(CensorEngine.EvidenceReceipt receipt) { evidence.add(receipt); }
                        @Override public void drained() { drained++; }
                    }, new CensorEngine.NativeTextLane(nativePartition, 120, 1000, nativeExecutor, (input, completion) -> {
                        nativeInputs.add(input); nativeCompletions.add(completion);
                        input.onCancellation(() -> nativeCancellations++);
                        long start = input.beginRead(); assertTrue(start >= 0); nativeStartTimes.add(start);
                    }));
        }
        void fire(long at) {
            assertTrue(at >= now); now = at;
            boolean found;
            do {
                found = false;
                for (Timed timed : new ArrayList<>(timers)) {
                    if (!timed.ran && !timed.cancelled && timed.at <= now) { timed.ran = true; timed.action.run(); found = true; }
                }
            } while (found);
        }
        void finishNative(int index, long end, long receipt, double... ys) {
            now = receipt;
            nativeCompletions.get(index).success(read(nativeInputsStarted(index),
                    end, 1000, 2400, ys));
        }
        long nativeInputsStarted(int index) {
            // The fake native operation starts at its actual executor invocation, recorded below.
            return nativeStartTimes.get(index);
        }
        final List<Long> nativeStartTimes = new ArrayList<>();
        Pixel deliverImage(int index, long at, int width, int height) {
            now = at; Pixel pixel = new Pixel(); captures.get(index).callback.success(pixel.payload(width, height)); return pixel;
        }
        void finishImage(int index, long at, double y) {
            now = at; imageCompletions.get(index).success(new SceneEngine.Batch(images.get(index).source, SceneEngine.Lane.FAST,
                    Collections.singletonList(new SceneEngine.Observation("FACE", 1, 0, region(y), .9f)), Collections.emptyList()),
                    new CensorEngine.WorkTiming(1, 2, 1));
        }
        void close() {
            engine.close();
            for (Capture capture : captures) capture.callback.failure(0);
            for (CensorEngine.NativeTextCompletion completion : nativeCompletions) completion.cancelled();
            for (CensorEngine.Completion completion : imageCompletions) completion.cancelled();
            nativeExecutor.runAll(); imageExecutor.runAll(); ui.runAll();
        }
    }
}
