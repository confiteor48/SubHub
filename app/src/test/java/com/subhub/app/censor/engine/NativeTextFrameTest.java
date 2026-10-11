package com.subhub.app.censor.engine;

import static org.junit.Assert.*;

import java.util.AbstractList;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

public class NativeTextFrameTest {
    private static final String A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String C = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";
    private static final String D = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd";
    private static SceneEngine scene(int sources, int tracks) {
        return new SceneEngine(1, new SceneEngine.Policy(sources, tracks, 64, 256, 5000, 20000, .5));
    }
    private static SceneEngine.Source image(long sequence) {
        return new SceneEngine.Source(1, sequence, 42, 1000, 2400, sequence * 10, -1);
    }
    private static SceneEngine.Region region(double y) { return new SceneEngine.Region(100, y, 200, y + 30); }
    private static NativeTextResult.Candidate candidate(String text, String node, double y) {
        return new NativeTextResult.Candidate("TEXT_SMUT", text, node, .9f, true, Collections.singletonList(region(y)));
    }
    private static NativeTextFrame frame(long sequence, NativeTextResult.Candidate... candidates) {
        long started = sequence * 10, finished = started + 5;
        NativeTextResult read = new NativeTextResult(1000, 2400, 7, started, finished,
                Arrays.asList(candidates), Math.max(1, candidates.length), candidates.length, candidates.length);
        return new NativeTextFrame(SceneEngine.Source.nativeText(1, sequence, 42, 1000, 2400, 7,
                started, finished, finished + 10), 2, read, 1000);
    }
    private static SceneEngine.Snapshot display(SceneEngine scene, long canvas, long nativeSource, long at) {
        return scene.snapshot(canvas, at, true, Collections.emptyMap());
    }
    private static SceneEngine.Mask nativeMask(SceneEngine.Snapshot snapshot) {
        for (SceneEngine.Group group : snapshot.groups) for (SceneEngine.Mask mask : group.members) {
            if (mask.nativeValidated) return mask;
        }
        throw new AssertionError("missing native mask");
    }
    private static void face(SceneEngine scene, long source) {
        assertTrue(scene.observe(new SceneEngine.Batch(image(source), SceneEngine.Lane.FAST,
                Collections.singletonList(new SceneEngine.Observation("FACE", 1, 0, region(500), .9f)), Collections.emptyList())));
    }

    @Test public void nativeAndImageKeepIndependentOriginsAndNoPixelClockAlias() {
        SceneEngine scene = scene(16, 100); assertTrue(scene.register(image(1))); face(scene, 1);
        NativeTextFrame frame = frame(2, candidate(A, C, 1000)); assertTrue(scene.admitNativeFrame(frame));
        SceneEngine.Snapshot snapshot = display(scene, 1, 2, 50);
        assertEquals(2, snapshot.groups.size()); assertEquals(1, snapshot.destination.sequence);
        SceneEngine.Mask nativeMask = nativeMask(snapshot);
        assertEquals(SceneEngine.SourceKind.NATIVE_TEXT, nativeMask.evidenceSource.kind);
        assertEquals(-1, nativeMask.evidenceSource.pixelAtMillis); assertEquals(0, nativeMask.evidenceSource.imageRequestIdentity);
        assertEquals(20, nativeMask.evidenceSource.readStartedAtMillis); assertEquals(25, nativeMask.evidenceSource.readFinishedAtMillis);
        assertTrue(nativeMask.nativeNodeIdentityKnown); assertFalse(nativeMask.confirmed); assertFalse(nativeMask.imageEstimated);
        assertEquals(1000, nativeMask.displayRegion.top, 0);
    }

    @Test public void providerNodeAndContentKeepLogicalIdentityAcrossActualNewReadPoses() {
        SceneEngine scene = scene(16, 100); scene.register(image(1));
        scene.admitNativeFrame(frame(2, candidate(A, C, 1000)));
        SceneEngine.Mask before = nativeMask(display(scene, 1, 2, 50));
        scene.admitNativeFrame(frame(3, candidate(A, C, 800)));
        SceneEngine.Mask after = nativeMask(display(scene, 1, 3, 60));
        assertEquals(before.identity, after.identity); assertEquals(before.textGroup, after.textGroup);
        assertEquals(800, after.displayRegion.top, 0); assertEquals(3, after.evidenceSource.sequence);
        assertFalse(after.confirmed); assertFalse(after.estimatedMotion);
    }

    @Test public void recycledProviderNodeCannotInheritDifferentContentIdentity() {
        SceneEngine scene = scene(16, 100); scene.register(image(1));
        scene.admitNativeFrame(frame(2, candidate(A, C, 1000)));
        long identity = nativeMask(display(scene, 1, 2, 50)).identity;
        scene.admitNativeFrame(frame(3, candidate(B, C, 1000)));
        assertNotEquals(identity, nativeMask(display(scene, 1, 3, 60)).identity);
        assertEquals(1, scene.trackCount());
    }

    @Test public void sameTextInTwoKnownNodesRemainsTwoLogicalGroups() {
        SceneEngine scene = scene(16, 100); scene.register(image(1));
        assertTrue(scene.admitNativeFrame(frame(2, candidate(A, C, 1000), candidate(A, D, 1200))));
        SceneEngine.Snapshot snapshot = display(scene, 1, 2, 50);
        assertEquals(2, snapshot.groups.size()); assertNotEquals(snapshot.groups.get(0).members.get(0).textGroup,
                snapshot.groups.get(1).members.get(0).textGroup);
    }

    @Test public void anonymousSamePoseCoverageDoesNotBecomeMovingProviderIdentity() {
        SceneEngine scene = scene(16, 100); scene.register(image(1));
        scene.admitNativeFrame(frame(2, candidate(A, null, 1000)));
        SceneEngine.Mask first = nativeMask(display(scene, 1, 2, 50));
        scene.admitNativeFrame(frame(3, candidate(A, null, 1000)));
        SceneEngine.Mask second = nativeMask(display(scene, 1, 3, 60));
        assertEquals(first.identity, second.identity); assertFalse(second.nativeNodeIdentityKnown);
        scene.admitNativeFrame(frame(4, candidate(A, null, 800)));
        assertNotEquals(second.identity, nativeMask(display(scene, 1, 4, 70)).identity);
    }

    @Test public void individualNativeLinesRemainMembersNotGapHullCoverage() {
        SceneEngine scene = scene(16, 100); scene.register(image(1));
        NativeTextResult.Candidate lines = new NativeTextResult.Candidate("TEXT_SMUT", A, C, .9f, true,
                Arrays.asList(region(1000), region(1200)));
        assertTrue(scene.admitNativeFrame(frame(2, lines)));
        SceneEngine.Snapshot snapshot = display(scene, 1, 2, 50);
        assertEquals(1, snapshot.groups.size()); assertEquals(2, snapshot.groups.get(0).members.size());
        assertEquals(1030, snapshot.groups.get(0).members.get(0).displayRegion.bottom, 0);
        assertEquals(1200, snapshot.groups.get(0).members.get(1).displayRegion.top, 0);
    }

    @Test public void equalTextGroupAndPartitionNumbersCannotFuseNativeAndImageText() {
        SceneEngine scene = scene(16, 100); scene.register(image(1));
        assertTrue(scene.observe(new SceneEngine.Batch(image(1), SceneEngine.Lane.TEXT,
                Collections.singletonList(new SceneEngine.Observation("TEXT_SMUT", 1, 1, region(500), .9f)), Collections.emptyList())));
        NativeTextFrame original = frame(2, candidate(A, C, 1000));
        assertTrue(scene.admitNativeFrame(new NativeTextFrame(original.source, 1, original.read, 1000)));
        SceneEngine.Snapshot snapshot = scene.snapshot(1, 50, false);
        assertEquals(2, snapshot.groups.size());
        for (SceneEngine.Group group : snapshot.groups) {
            assertEquals(1, group.members.size());
            assertEquals(group.members.get(0).evidenceSource.kind, group.members.get(0).geometrySource.kind);
        }
    }

    @Test public void nativeMetadataCannotBeSuppliedAsAnImagePartitionRenderBasis() {
        SceneEngine scene = scene(16, 100); scene.register(image(1)); face(scene, 1);
        assertTrue(scene.admitNativeFrame(frame(2, candidate(A, C, 1000))));
        SceneEngine.Snapshot before = scene.snapshot(1, 50, false);
        try { scene.snapshot(1, 60, true, Collections.singletonMap(1L, 2L)); fail("Native image basis admitted"); }
        catch (IllegalArgumentException expected) { }
        assertSame(before, scene.ready());
    }

    @Test public void nativeReadCannotBecomeEventMotionTransitBetweenImages() {
        SceneEngine scene = scene(16, 100); scene.register(image(1)); face(scene, 1);
        scene.admitNativeFrame(frame(2, candidate(A, C, 1000))); scene.register(image(3));
        assertEquals(SceneEngine.MotionAdmission.INVALID, scene.admitMotion(new SceneEngine.Motion(1, 1, 2, 1, 1,
                SceneEngine.MotionAuthority.EVENT_ESTIMATE, new SceneEngine.Region(0, 0, 1000, 2400), 0, -20)));
        assertEquals(SceneEngine.MotionAdmission.INVALID, scene.admitMotion(new SceneEngine.Motion(1, 2, 3, 1, 1,
                SceneEngine.MotionAuthority.EVENT_ESTIMATE, new SceneEngine.Region(0, 0, 1000, 2400), 0, -30)));
        SceneEngine.Snapshot snapshot = display(scene, 3, 2, 60);
        for (SceneEngine.Group group : snapshot.groups) for (SceneEngine.Mask mask : group.members) {
            assertEquals(mask.nativeValidated ? 1000 : 500, mask.displayRegion.top, 0);
            assertFalse(mask.estimatedMotion);
        }
    }

    @Test public void nativeExpiryUsesReadEndNotReceiptAndExpiresWholeGroupTogether() {
        SceneEngine scene = scene(16, 100); scene.register(image(1)); face(scene, 1);
        scene.admitNativeFrame(frame(2, new NativeTextResult.Candidate("TEXT_SMUT", A, C, .9f, true,
                Arrays.asList(region(1000), region(1200)))));
        assertEquals(1026, display(scene, 1, 2, 1025).nextChangeAtMillis);
        SceneEngine.Snapshot expired = display(scene, 1, 2, 1026);
        assertEquals(1, expired.groups.size()); assertFalse(expired.groups.get(0).members.get(0).nativeValidated);
    }

    @Test public void optionalNativeTrackCapacityIsRejectedBeforeImageMetadataEviction() {
        SceneEngine scene = scene(2, 1); scene.register(image(1)); scene.register(image(2)); face(scene, 1);
        assertTrue(scene.motion(new SceneEngine.Motion(1, 1, 2, 1, 1,
                SceneEngine.MotionAuthority.IMAGE_CORRESPONDENCE, new SceneEngine.Region(0, 300, 1000, 2400), 0, -400)));
        assertFalse(scene.admitNativeFrame(frame(3, candidate(A, C, 1000))));
        assertNull(scene.source(3)); assertNotNull(scene.source(1)); assertNotNull(scene.source(2));
        assertEquals(100, scene.snapshot(2, 50, false).groups.get(0).members.get(0).displayRegion.top, 0);
    }

    @Test public void imagePressureDropsWholeOptionalNativeFrameNotPartialLines() {
        SceneEngine scene = scene(16, 2); scene.register(image(1));
        scene.admitNativeFrame(frame(2, new NativeTextResult.Candidate("TEXT_SMUT", A, C, .9f, true,
                Arrays.asList(region(1000), region(1200)))));
        face(scene, 1);
        assertEquals(1, scene.trackCount()); assertEquals(1, scene.snapshot(1, 50, false).groups.size());
    }

    @Test public void nativeCannotBeImageMatchEndpointOrImageEligibilityTarget() {
        SceneEngine scene = scene(16, 100); scene.register(image(1)); scene.admitNativeFrame(frame(2, candidate(A, C, 1000)));
        assertFalse(scene.advanceImageTarget(2));
        assertEquals(SceneEngine.MotionAdmission.INVALID, scene.admitMotion(new SceneEngine.Motion(1, 1, 2, 1, 1,
                SceneEngine.MotionAuthority.IMAGE_CORRESPONDENCE, region(500), 0, 0)));
        assertEquals(SceneEngine.PresentationAdmission.INVALID, scene.admitPresentation(new ImagePresentationEstimate(1, 1, 2, 1, 1,
                Collections.singletonList(new ImagePresentationEstimate.Piece(region(500), 0, 0, 0, 0)))));
        try { scene.snapshot(1, 50, true, Collections.emptyMap(), 2); fail(); }
        catch (IllegalArgumentException expected) { }
        int[] rgb = new int[8 * 8]; Arrays.fill(rgb, 0xff123456);
        assertNull(PixelPairMatcher.Image.copyOf(scene.source(2), PixelGeometry.display(8, 8, 1000, 2400), 8, 8,
                PixelPairMatcher.Provenance.UNCENSORED_ACCESSIBILITY_DISPLAY, PixelPairMatcher.Sampling.EXACT_RASTER, rgb));
    }

    @Test public void malformedAmbiguousOrUnconfirmedReadsRejectBeforeOwningSceneState() {
        try { frame(2, candidate(A, C, 1000), candidate(B, C, 1200)); fail(); }
        catch (IllegalArgumentException expected) { }
        try { frame(2, candidate(A, null, 1000), candidate(A, null, 1000)); fail(); }
        catch (IllegalArgumentException expected) { }
        try { new NativeTextResult(1000, 2400, 7, 0, 10,
                Collections.singletonList(candidate(A, C, 1000)), 1, 1, 0); fail(); }
        catch (IllegalArgumentException expected) { }
        try { SceneEngine.Source.nativeText(1, 1, 42, 1000, 2400, 7, 20, 30, 25); fail(); }
        catch (IllegalArgumentException expected) { }
    }

    @Test public void hugeCandidateListRejectsBeforeAllocationOrElementReads() {
        try { new NativeTextResult(1000, 2400, 7, 0, 10,
                new AbstractList<NativeTextResult.Candidate>() {
                    @Override public int size() { return Integer.MAX_VALUE; }
                    @Override public NativeTextResult.Candidate get(int index) { throw new AssertionError("unbounded read"); }
                }, 900, 900, 64); fail(); }
        catch (IllegalArgumentException expected) { }
    }
}
