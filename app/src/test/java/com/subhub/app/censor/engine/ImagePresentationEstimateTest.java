package com.subhub.app.censor.engine;

import static org.junit.Assert.*;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class ImagePresentationEstimateTest {
    private static SceneEngine scene() { return scene(16, 64); }
    private static SceneEngine scene(int sources, int edges) {
        return new SceneEngine(1, new SceneEngine.Policy(sources, 100, edges, 256, 500, 5000, .5));
    }
    private static SceneEngine.Source source(long sequence) {
        return new SceneEngine.Source(1, sequence, 42, 1000, 2400, sequence * 100, -1);
    }
    private static void register(SceneEngine scene, int count) {
        for (int i = 1; i <= count; i++) assertTrue(scene.register(source(i)));
    }
    private static SceneEngine.Region region(double y) { return new SceneEngine.Region(100, y, 200, y + 100); }
    private static SceneEngine.Batch positive(long sequence, SceneEngine.Lane lane, double y) {
        return new SceneEngine.Batch(source(sequence), lane,
                Collections.singletonList(new SceneEngine.Observation("FACE", 1, 0, region(y), .9f)), Collections.emptyList());
    }
    private static SceneEngine.Batch negative(long sequence, double y) {
        return new SceneEngine.Batch(source(sequence), SceneEngine.Lane.QUALITY, Collections.emptyList(),
                Collections.singletonList(new SceneEngine.Inspection("FACE", 1, region(y))));
    }
    private static ImagePresentationEstimate hint(long from, long to, double dy) {
        return hint(from, to, 1, new ImagePresentationEstimate.Piece(region(500), 0, 0, dy, dy));
    }
    private static ImagePresentationEstimate hint(long from, long to, long revision,
            ImagePresentationEstimate.Piece... pieces) {
        return new ImagePresentationEstimate(1, from, to, 1, revision, Arrays.asList(pieces));
    }
    private static List<SceneEngine.Mask> masks(SceneEngine.Snapshot snapshot) {
        List<SceneEngine.Mask> values = new ArrayList<>();
        for (SceneEngine.Group group : snapshot.groups) values.addAll(group.members);
        return values;
    }
    private static SceneEngine.Snapshot display(SceneEngine scene, long canvas, long target, long now) {
        return scene.snapshot(canvas, now, true, Collections.emptyMap(), target);
    }
    private static SceneEngine.Motion exact(long from, long to, double dy) {
        return new SceneEngine.Motion(1, from, to, 1, 1, SceneEngine.MotionAuthority.IMAGE_CORRESPONDENCE,
                new SceneEngine.Region(0, 300, 1000, 2400), 0, dy);
    }

    @Test public void directHintBeforeFirstFastMovesOnlyItsOwnSupportedMemberAndKeepsAOrigin() {
        SceneEngine scene = scene(); register(scene, 2);
        assertEquals(SceneEngine.PresentationAdmission.ADMITTED, scene.admitPresentation(hint(1, 2, -400)));
        scene.observe(positive(1, SceneEngine.Lane.FAST, 500));
        SceneEngine.Snapshot snapshot = display(scene, 1, 2, 210);
        SceneEngine.Mask mask = masks(snapshot).get(0);
        assertEquals(100, mask.displayRegion.top, 0);
        assertEquals(1, snapshot.destination.sequence);
        assertEquals(2, mask.geometrySource.sequence);
        assertEquals(1, mask.evidenceSource.sequence);
        assertEquals(500, mask.evidenceRegion.top, 0);
        assertTrue(mask.imageEstimated); assertTrue(mask.estimatedMotion);
        assertFalse(mask.physicalErrorKnown); assertFalse(mask.heldGeometry);
    }

    @Test public void qualityIdentitiesCannotMergeOrConfirmFromHints() {
        SceneEngine scene = scene(); register(scene, 2);
        scene.observe(positive(1, SceneEngine.Lane.QUALITY, 500));
        scene.observe(positive(2, SceneEngine.Lane.QUALITY, 100));
        assertEquals(SceneEngine.PresentationAdmission.ADMITTED, scene.admitPresentation(hint(1, 2, -400)));
        assertEquals(2, scene.trackCount());
        assertEquals(2, display(scene, 2, 2, 210).tentativeTracks);
    }

    @Test public void laterQualityNegativesCannotUseImageHints() {
        SceneEngine scene = scene(); register(scene, 3);
        scene.observe(positive(1, SceneEngine.Lane.FAST, 500));
        scene.observe(negative(2, 100)); scene.observe(negative(3, 50));
        scene.admitPresentation(hint(1, 2, -400)); scene.admitPresentation(hint(1, 3, -450));
        assertEquals(1, scene.trackCount());
        assertEquals(1, masks(display(scene, 3, 3, 310)).size());
    }

    @Test public void uncertainRevisionCannotInvalidateExactScene() {
        SceneEngine scene = scene(); register(scene, 2);
        scene.observe(positive(1, SceneEngine.Lane.FAST, 500));
        scene.admitPresentation(hint(1, 2, -400));
        assertEquals(SceneEngine.PresentationAdmission.INVALID, scene.admitPresentation(hint(1, 2, 2,
                new ImagePresentationEstimate.Piece(region(500), 0, 0, -402, -398))));
        assertFalse(scene.hasMotionConflict());
        assertEquals(100, masks(display(scene, 1, 2, 210)).get(0).displayRegion.top, 0);
    }

    @Test public void directHintsNeverChainOrInvert() {
        SceneEngine scene = scene(); register(scene, 3);
        scene.admitPresentation(hint(1, 2, -400));
        scene.admitPresentation(hint(2, 3, 1, new ImagePresentationEstimate.Piece(region(100), 0, 0, -50, -50)));
        scene.observe(positive(1, SceneEngine.Lane.FAST, 500));
        assertEquals(500, masks(display(scene, 1, 3, 310)).get(0).displayRegion.top, 0);
        scene.observe(positive(2, SceneEngine.Lane.FAST, 100));
        for (SceneEngine.Mask mask : masks(display(scene, 2, 1, 310))) {
            assertFalse(mask.imageEstimated);
        }
    }

    @Test public void eventAndImageAreAlternativesNotAddedInBothOrders() {
        for (boolean imageFirst : new boolean[]{true, false}) {
            SceneEngine scene = scene(); register(scene, 2);
            scene.observe(positive(1, SceneEngine.Lane.FAST, 500));
            SceneEngine.Motion event = new SceneEngine.Motion(1, 1, 2, 1, 1,
                    SceneEngine.MotionAuthority.EVENT_ESTIMATE, new SceneEngine.Region(0, 300, 1000, 2400), 0, -400);
            if (imageFirst) scene.admitPresentation(hint(1, 2, -400));
            scene.motion(event);
            if (!imageFirst) scene.admitPresentation(hint(1, 2, -400));
            assertEquals(100, masks(display(scene, 2, 2, 210)).get(0).displayRegion.top, 0);
        }
    }

    @Test public void exactCorrespondenceKeepsItsLegitimateIdentityAuthorityAndOutranksHint() {
        SceneEngine scene = scene(16, 1); register(scene, 2);
        scene.admitPresentation(hint(1, 2, -400));
        scene.observe(positive(1, SceneEngine.Lane.QUALITY, 500));
        scene.observe(positive(2, SceneEngine.Lane.QUALITY, 100));
        assertTrue(scene.motion(exact(1, 2, -400)));
        assertEquals(1, scene.trackCount());
        SceneEngine.Mask mask = masks(display(scene, 2, 2, 210)).get(0);
        assertTrue(mask.confirmed); assertFalse(mask.imageEstimated); assertFalse(mask.estimatedMotion);
    }

    @Test public void localizationEnvelopeNotNominalDisplacementDefinesRenderedCoverage() {
        SceneEngine scene = scene(); register(scene, 2);
        scene.observe(positive(1, SceneEngine.Lane.FAST, 500));
        scene.admitPresentation(hint(1, 2, 1, new ImagePresentationEstimate.Piece(region(500), -2, 3, -401, -399)));
        SceneEngine.Mask mask = masks(display(scene, 1, 2, 210)).get(0);
        assertEquals(98, mask.displayRegion.left, 0); assertEquals(203, mask.displayRegion.right, 0);
        assertEquals(99, mask.displayRegion.top, 0); assertEquals(201, mask.displayRegion.bottom, 0);
        assertEquals(500, mask.evidenceRegion.top, 0); assertFalse(mask.physicalErrorKnown);
    }

    @Test public void overlappingDestinationEnvelopesRejectWholePair() {
        SceneEngine scene = scene(); register(scene, 2);
        ImagePresentationEstimate.Piece a = new ImagePresentationEstimate.Piece(new SceneEngine.Region(10, 10, 20, 20), 0, 20, 0, 0);
        ImagePresentationEstimate.Piece b = new ImagePresentationEstimate.Piece(new SceneEngine.Region(30, 10, 40, 20), -20, 0, 0, 0);
        assertEquals(SceneEngine.PresentationAdmission.INVALID, scene.admitPresentation(hint(1, 2, 1, a, b)));
        assertEquals(0, scene.presentationPieceCount());
    }

    @Test public void supportCannotBeEnlargedToCrossAMemberOrGroupHull() {
        SceneEngine scene = scene(); register(scene, 2);
        scene.observe(positive(1, SceneEngine.Lane.FAST, 500));
        scene.admitPresentation(hint(1, 2, 1, new ImagePresentationEstimate.Piece(
                new SceneEngine.Region(100, 500, 150, 550), 0, 0, -400, -400)));
        SceneEngine.Mask mask = masks(display(scene, 1, 2, 210)).get(0);
        assertEquals(500, mask.displayRegion.top, 0); assertFalse(mask.imageEstimated);
    }

    @Test public void newerUnmatchedImageAndEmptyFastOrTextCannotRollHeldHintBack() {
        for (SceneEngine.Lane lane : new SceneEngine.Lane[]{SceneEngine.Lane.FAST, SceneEngine.Lane.TEXT}) {
            SceneEngine scene = scene(); register(scene, 2);
            scene.observe(positive(1, SceneEngine.Lane.FAST, 500)); scene.admitPresentation(hint(1, 2, -400));
            display(scene, 1, 2, 210);
            scene.register(source(3));
            scene.observe(new SceneEngine.Batch(source(3), lane, Collections.emptyList(), Collections.emptyList()));
            SceneEngine.Mask held = masks(display(scene, 3, 3, 310)).get(0);
            assertEquals(100, held.displayRegion.top, 0); assertEquals(2, held.geometrySource.sequence);
            assertEquals(1, held.evidenceSource.sequence); assertTrue(held.imageEstimated); assertTrue(held.heldGeometry);
        }
    }

    @Test public void hintAndHoldNeverRenewOriginalExpiry() {
        SceneEngine scene = scene(); register(scene, 3);
        scene.observe(positive(1, SceneEngine.Lane.FAST, 500)); scene.admitPresentation(hint(1, 2, -400));
        assertEquals(601, display(scene, 1, 2, 600).nextChangeAtMillis);
        assertTrue(display(scene, 3, 3, 601).groups.isEmpty());
    }

    @Test public void repeatStaleAndChangedSupportRemainBoundedWithoutAgeOrRevisionRenewal() {
        SceneEngine scene = scene(); register(scene, 2);
        scene.admitPresentation(hint(1, 2, -400));
        scene.observe(positive(1, SceneEngine.Lane.FAST, 500));
        long revision = display(scene, 1, 2, 210).revision;
        assertEquals(SceneEngine.PresentationAdmission.DUPLICATE, scene.admitPresentation(hint(1, 2, 2,
                new ImagePresentationEstimate.Piece(region(500), -0.0, 0, -400, -400))));
        assertEquals(revision, display(scene, 1, 2, 210).revision);
        assertEquals(SceneEngine.PresentationAdmission.STALE, scene.admitPresentation(hint(1, 2, -400)));
        assertEquals(1, scene.presentationPieceCount());
    }

    @Test public void optionalHintsCannotPinSourcesOrStarveExactCapacity() {
        SceneEngine scene = scene(2, 1); register(scene, 2);
        scene.admitPresentation(hint(1, 2, -400));
        assertTrue(scene.motion(exact(1, 2, -400)));
        assertTrue(scene.register(source(3)));
        assertEquals(0, scene.presentationPieceCount());
        assertEquals(2, scene.sourceCount());
    }

    @Test public void targetEvictionRetainsOnlyTypedConstructedHoldNotAnAdmissibleRelation() {
        SceneEngine scene = scene(2, 64); register(scene, 2);
        scene.pin(source(1));
        scene.observe(positive(1, SceneEngine.Lane.FAST, 500)); scene.admitPresentation(hint(1, 2, -400));
        display(scene, 1, 2, 210); assertTrue(scene.register(source(3)));
        assertEquals(0, scene.presentationPieceCount());
        SceneEngine.Mask held = masks(display(scene, 3, 3, 310)).get(0);
        assertTrue(held.heldGeometry); assertTrue(held.imageEstimated); assertEquals(2, held.geometrySource.sequence);
        scene.reset(2); assertEquals(0, scene.presentationPieceCount());
    }

    @Test public void hugeConstructionRejectsBeforeReadingAndOverflowRejects() {
        try {
            new ImagePresentationEstimate(1, 1, 2, 1, 1, new AbstractList<ImagePresentationEstimate.Piece>() {
                @Override public int size() { return Integer.MAX_VALUE; }
                @Override public ImagePresentationEstimate.Piece get(int index) { throw new AssertionError("unbounded read"); }
            }); fail();
        } catch (IllegalArgumentException expected) { }
        try {
            new ImagePresentationEstimate.Piece(new SceneEngine.Region(1, 1, Double.MAX_VALUE, 2),
                    Double.MAX_VALUE, Double.MAX_VALUE, 0, 0); fail();
        } catch (IllegalArgumentException expected) { }
    }

    @Test public void latestRawImageWithoutAHintCannotChooseAnUnrequestedExactRenderBasis() {
        SceneEngine scene = scene(); register(scene, 2);
        scene.observe(positive(1, SceneEngine.Lane.FAST, 500));
        scene.motion(exact(1, 2, -400));
        SceneEngine.Mask mask = masks(display(scene, 1, 2, 210)).get(0);
        assertEquals(500, mask.displayRegion.top, 0);
        assertEquals(1, mask.geometrySource.sequence);
    }

    @Test public void suppressedImageMemberKeepsConstructedPoseAfterFreshDuplicateExpires() {
        SceneEngine scene = scene(); register(scene, 4);
        scene.observe(positive(1, SceneEngine.Lane.QUALITY, 500));
        assertTrue(scene.motion(exact(1, 2, 0)));
        scene.observe(positive(2, SceneEngine.Lane.QUALITY, 500));
        scene.admitPresentation(hint(2, 4, -400));
        scene.observe(positive(4, SceneEngine.Lane.FAST, 100));
        SceneEngine.Snapshot covered = display(scene, 4, 4, 410);
        assertEquals(1, covered.redundantEstimatedMasks);
        assertEquals(1, covered.groups.size());
        assertTrue(scene.register(source(5)));
        SceneEngine.Snapshot afterFreshExpiry = display(scene, 4, 5, 902);
        assertEquals(1, masks(afterFreshExpiry).size());
        SceneEngine.Mask held = masks(afterFreshExpiry).get(0);
        assertEquals(100, held.displayRegion.top, 0); assertEquals(4, held.geometrySource.sequence);
        assertEquals(2, held.evidenceSource.sequence); assertTrue(held.imageEstimated); assertTrue(held.heldGeometry);
        assertTrue(held.confirmed); assertEquals(5201, afterFreshExpiry.nextChangeAtMillis);
    }

    @Test public void olderIneligiblePairsCannotConsumeCurrentHintSlotsForever() {
        SceneEngine scene = scene(16, 1); register(scene, 4);
        assertEquals(SceneEngine.PresentationAdmission.ADMITTED, scene.admitPresentation(hint(1, 2, -400)));
        scene.observe(positive(3, SceneEngine.Lane.FAST, 500));
        display(scene, 3, 4, 410);
        assertEquals(SceneEngine.PresentationAdmission.ADMITTED, scene.admitPresentation(hint(3, 4, -400)));
        assertEquals(1, scene.presentationPieceCount());
    }

    @Test public void rejectedImageTargetCannotAdvanceEligibilityOrRetireValidHints() {
        SceneEngine scene = scene(); register(scene, 2);
        scene.observe(positive(1, SceneEngine.Lane.FAST, 500));
        scene.admitPresentation(hint(1, 2, -400));
        SceneEngine.Snapshot before = display(scene, 1, 2, 210);
        assertTrue(scene.register(new SceneEngine.Source(1, 3, 99, 1000, 2400, 300, -1)));
        long revision = display(scene, 1, 2, 310).revision;
        SceneEngine.Snapshot readyBefore = scene.ready();
        try {
            display(scene, 1, 3, 310); fail("incompatible target accepted");
        } catch (IllegalArgumentException expected) { }
        assertSame(readyBefore, scene.ready());
        assertEquals(1, scene.presentationPieceCount());
        assertEquals(SceneEngine.PresentationAdmission.DUPLICATE, scene.admitPresentation(hint(1, 2, 2,
                new ImagePresentationEstimate.Piece(region(500), 0, 0, -400, -400))));
        SceneEngine.Snapshot after = display(scene, 1, 2, 310);
        assertEquals(revision, after.revision);
        assertEquals(masks(before).get(0).displayRegion.top, masks(after).get(0).displayRegion.top, 0);
        assertFalse(masks(after).get(0).heldGeometry);
    }

    @Test public void rejectedRenderBasisCannotAdvanceEligibilityOrRetireValidHints() {
        SceneEngine scene = scene(); register(scene, 3);
        scene.observe(positive(1, SceneEngine.Lane.FAST, 500)); scene.admitPresentation(hint(1, 2, -400));
        SceneEngine.Snapshot before = display(scene, 1, 2, 310);
        try {
            scene.snapshot(1, 310, true, Collections.singletonMap(1L, 99L), 3);
            fail("unknown basis accepted");
        } catch (IllegalArgumentException expected) { }
        assertSame(before, scene.ready());
        assertEquals(1, scene.presentationPieceCount());
        assertEquals(SceneEngine.PresentationAdmission.DUPLICATE, scene.admitPresentation(hint(1, 2, 2,
                new ImagePresentationEstimate.Piece(region(500), 0, 0, -400, -400))));
        SceneEngine.Snapshot after = display(scene, 1, 2, 310);
        assertEquals(before.revision, after.revision);
        assertEquals(100, masks(after).get(0).displayRegion.top, 0);
        assertFalse(masks(after).get(0).heldGeometry);
    }
}
