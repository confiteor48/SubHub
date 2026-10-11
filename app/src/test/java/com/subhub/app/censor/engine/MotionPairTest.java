package com.subhub.app.censor.engine;

import static com.subhub.app.censor.engine.SceneEngine.Lane.FAST;
import static com.subhub.app.censor.engine.SceneEngine.MotionAuthority.IMAGE_CORRESPONDENCE;
import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.AbstractList;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class MotionPairTest {
    private static final SceneEngine.Region HEADER = new SceneEngine.Region(0, 0, 1000, 80);
    private static final SceneEngine.Region BODY = new SceneEngine.Region(0, 500, 1000, 2400);

    private static SceneEngine engine() {
        return engine(2);
    }

    private static SceneEngine engine(int count) {
        return engine(count, new SceneEngine.Policy(16, 100, 64, 256, 500, 5000, .5));
    }

    private static SceneEngine engine(int count, SceneEngine.Policy policy) {
        SceneEngine engine = new SceneEngine(1, policy);
        for (int i = 1; i <= count; i++) {
            assertTrue(engine.register(new SceneEngine.Source(1, i, 42, 1000, 2400, i * 100, -1)));
        }
        return engine;
    }

    private static void observeMixed(SceneEngine engine) {
        engine.observe(new SceneEngine.Batch(engine.source(1), FAST, Arrays.asList(
                new SceneEngine.Observation("HEADER", 1, 0, new SceneEngine.Region(100, 50, 200, 70), .9f),
                new SceneEngine.Observation("BODY", 1, 0, new SceneEngine.Region(100, 500, 200, 600), .9f)),
                Collections.emptyList()));
    }

    private static SceneEngine.Motion measured(long from, long to, long revision, SceneEngine.Region support, double dy) {
        return new SceneEngine.Motion(1, from, to, 1, revision, IMAGE_CORRESPONDENCE, support, 0, dy);
    }

    private static SceneEngine.MotionPair mixedPair(long revision) {
        return new SceneEngine.MotionPair(Arrays.asList(measured(1, 2, revision, HEADER, 0),
                measured(1, 2, revision, BODY, -400)));
    }

    private static List<SceneEngine.Mask> masks(SceneEngine.Snapshot snapshot) {
        List<SceneEngine.Mask> result = new ArrayList<>();
        for (SceneEngine.Group group : snapshot.groups) result.addAll(group.members);
        return result;
    }

    private static void positions(SceneEngine.Snapshot snapshot, double... expected) {
        Arrays.sort(expected);
        assertArrayEquals(expected, masks(snapshot).stream().mapToDouble(mask -> mask.displayRegion.top).sorted().toArray(), 0);
    }

    @Test public void singletonCompatibilityCannotAppendADisjointDomainToAnAdmittedPair() {
        SceneEngine engine = engine(); observeMixed(engine);
        assertTrue(engine.motion(new SceneEngine.Motion(1, 1, 2, 1, 1, IMAGE_CORRESPONDENCE, BODY, 0, -400)));
        assertFalse("A completed measured pair cannot be expanded incrementally",
                engine.motion(new SceneEngine.Motion(1, 1, 2, 1, 1, IMAGE_CORRESPONDENCE, HEADER, 0, 0)));
        SceneEngine.Snapshot snapshot = engine.snapshot(1, 210, false, Collections.singletonMap(1L, 2L));
        assertEquals(2, snapshot.groups.size());
        for (SceneEngine.Group group : snapshot.groups) for (SceneEngine.Mask mask : group.members) {
            boolean header = "HEADER".equals(mask.category);
            assertEquals(header ? 1 : 2, mask.geometrySource.sequence); assertEquals(header, mask.heldGeometry);
            assertEquals(1, mask.evidenceSource.sequence);
        }
    }

    @Test public void oneAtomicPairMovesBothActualPartitionMembersWithoutBorrowingTheNewSource() {
        SceneEngine engine = engine(); observeMixed(engine);
        assertEquals(SceneEngine.MotionAdmission.ADMITTED, engine.admitMotion(mixedPair(1)));
        SceneEngine.Snapshot snapshot = engine.snapshot(1, 210, false, Collections.singletonMap(1L, 2L));
        positions(snapshot, 50, 100);
        for (SceneEngine.Mask mask : masks(snapshot)) {
            assertEquals(1, mask.partition); assertEquals(2, mask.geometrySource.sequence);
            assertEquals(1, mask.evidenceSource.sequence); assertEquals(100, mask.evidenceSource.receivedAtMillis);
            assertFalse(mask.estimatedMotion); assertFalse(mask.heldGeometry); assertFalse(mask.confirmed);
        }
    }

    @Test public void inverseProjectionUsesEachPieceDestinationSupport() {
        SceneEngine engine = engine(); engine.admitMotion(mixedPair(1));
        engine.observe(new SceneEngine.Batch(engine.source(2), FAST, Arrays.asList(
                new SceneEngine.Observation("HEADER", 1, 0, new SceneEngine.Region(100, 50, 200, 70), .9f),
                new SceneEngine.Observation("BODY", 1, 0, new SceneEngine.Region(100, 100, 200, 200), .9f)),
                Collections.emptyList()));
        SceneEngine.Snapshot snapshot = engine.snapshot(1, 210, false); positions(snapshot, 50, 500);
        for (SceneEngine.Mask mask : masks(snapshot)) {
            assertEquals(1, mask.geometrySource.sequence); assertEquals(2, mask.evidenceSource.sequence);
        }
    }

    @Test public void unknownGapsAndWholeBoxesCrossingPiecesCannotGainCorrespondence() {
        for (SceneEngine.Region region : Arrays.asList(new SceneEngine.Region(100, 200, 200, 300),
                new SceneEngine.Region(100, 60, 200, 520))) {
            SceneEngine engine = engine();
            engine.observe(new SceneEngine.Batch(engine.source(1), FAST, Collections.singletonList(
                    new SceneEngine.Observation("CROSSING", 1, 0, region, .9f)), Collections.emptyList()));
            engine.admitMotion(mixedPair(1));
            SceneEngine.Snapshot snapshot = engine.snapshot(2, 210, false);
            positions(snapshot); assertEquals(1, snapshot.unresolvedTracks);
            SceneEngine.Snapshot hold = engine.snapshot(1, 210, false, Collections.singletonMap(1L, 2L));
            assertEquals(1, masks(hold).size()); assertTrue(masks(hold).get(0).heldGeometry);
            assertEquals(1, masks(hold).get(0).geometrySource.sequence);
        }
    }

    @Test public void sourceOrDestinationAmbiguityRejectsTheWholeProposalWithoutDestroyingPriorAuthority() {
        SceneEngine.Region overlappingSource = new SceneEngine.Region(0, 40, 1000, 80);
        for (SceneEngine.MotionPair invalid : Arrays.asList(
                new SceneEngine.MotionPair(Arrays.asList(measured(1, 2, 2, HEADER, 0),
                        measured(1, 2, 2, overlappingSource, 1000))),
                new SceneEngine.MotionPair(Arrays.asList(measured(1, 2, 2, HEADER, 0),
                        measured(1, 2, 2, BODY, -500))))) {
            SceneEngine engine = engine(); observeMixed(engine); engine.admitMotion(mixedPair(1));
            SceneEngine.Snapshot prior = engine.snapshot(2, 210, false);
            assertEquals(SceneEngine.MotionAdmission.INVALID, engine.admitMotion(invalid));
            assertSame(prior, engine.ready()); assertFalse(engine.hasMotionConflict());
            positions(engine.snapshot(2, 210, false), 50, 100);
        }
    }

    @Test public void exactRepeatsAreOrderIndependentAndOnlyAdvanceTheStaleResultWatermark() {
        SceneEngine engine = engine(); observeMixed(engine); engine.admitMotion(mixedPair(1));
        SceneEngine.Snapshot prior = engine.snapshot(2, 210, false);
        SceneEngine.MotionPair repeated = new SceneEngine.MotionPair(Arrays.asList(
                measured(1, 2, 3, BODY, -400), measured(1, 2, 3, HEADER, 0)));
        assertEquals(SceneEngine.MotionAdmission.DUPLICATE, engine.admitMotion(repeated));
        assertSame(prior, engine.ready());
        assertEquals(SceneEngine.MotionAdmission.STALE, engine.admitMotion(new SceneEngine.MotionPair(Arrays.asList(
                measured(1, 2, 2, HEADER, 0), measured(1, 2, 2, BODY, -300)))));
        assertFalse(engine.hasMotionConflict()); positions(engine.snapshot(2, 600, false), 50, 100);
        positions(engine.snapshot(2, 601, false));
        assertEquals(SceneEngine.MotionAdmission.CONFLICT, engine.admitMotion(new SceneEngine.MotionPair(Arrays.asList(
                measured(1, 2, 3, HEADER, 0), measured(1, 2, 3, BODY, -300)))));
        assertTrue(engine.hasMotionConflict());
    }

    @Test public void consistentSupportChangesDoNotExpandOrInvalidateAnImmutablePair() {
        SceneEngine engine = engine(); observeMixed(engine); engine.admitMotion(mixedPair(1));
        SceneEngine.Snapshot prior = engine.snapshot(2, 210, false);
        assertEquals(SceneEngine.MotionAdmission.INVALID, engine.admitMotion(new SceneEngine.MotionPair(Arrays.asList(
                measured(1, 2, 2, HEADER, 0), measured(1, 2, 2, new SceneEngine.Region(0, 501, 1000, 2400), -400)))));
        assertSame(prior, engine.ready()); assertFalse(engine.hasMotionConflict());
        positions(engine.snapshot(2, 210, false), 50, 100);
    }

    @Test public void everyPieceCountsAgainstCapacityWithoutConsumingARejectedPairIdentity() {
        SceneEngine engine = engine(2, new SceneEngine.Policy(16, 100, 1, 256, 500, 5000, .5));
        observeMixed(engine); SceneEngine.Snapshot prior = engine.snapshot(1, 210, false);
        assertEquals(SceneEngine.MotionAdmission.CAPACITY, engine.admitMotion(mixedPair(1)));
        assertSame(prior, engine.ready()); assertFalse(engine.hasMotionConflict());
        assertEquals(SceneEngine.MotionAdmission.ADMITTED, engine.admitMotion(measured(1, 2, 1, HEADER, 0)));
        SceneEngine.Snapshot current = engine.snapshot(1, 210, false);
        positions(current, 50, 500); assertEquals(1, current.capacityDrops);
    }

    @Test public void validationBudgetsAreSharedAcrossPiecesAndBothEndpointDomains() {
        for (int[] bounds : new int[][]{{7, 256}, {128, 20}}) {
            SceneEngine.Policy policy = new SceneEngine.Policy(16, 100, 64, 256, 500, 5000, .5, bounds[0], bounds[1]);
            SceneEngine control = engine(2, policy);
            assertEquals(SceneEngine.MotionAdmission.ADMITTED, control.admitMotion(measured(1, 2, 1, HEADER, 0)));
            SceneEngine engine = engine(2, policy); observeMixed(engine);
            SceneEngine.Snapshot prior = engine.snapshot(1, 210, false);
            assertEquals(SceneEngine.MotionAdmission.CHECK_BUDGET, engine.admitMotion(mixedPair(1)));
            assertSame(prior, engine.ready()); assertFalse(engine.hasMotionConflict());
            positions(engine.snapshot(1, 210, false), 50, 500);
        }
    }

    @Test public void pieceConstructionCannotMixOriginsAuthorityRevisionOrBorrowMutableLists() {
        SceneEngine.Motion first = measured(1, 2, 1, HEADER, 0);
        assertThrows(IllegalArgumentException.class, () -> new SceneEngine.MotionPair(Collections.emptyList()));
        for (SceneEngine.Motion mixed : Arrays.asList(measured(1, 3, 1, BODY, -400), measured(1, 2, 2, BODY, -400),
                new SceneEngine.Motion(2, 1, 2, 1, 1, IMAGE_CORRESPONDENCE, BODY, 0, -400),
                new SceneEngine.Motion(1, 1, 2, 2, 1, IMAGE_CORRESPONDENCE, BODY, 0, -400),
                new SceneEngine.Motion(1, 1, 2, 1, 1, SceneEngine.MotionAuthority.EVENT_ESTIMATE, BODY, 0, -400))) {
            assertThrows(IllegalArgumentException.class, () -> new SceneEngine.MotionPair(Arrays.asList(first, mixed)));
        }
        List<SceneEngine.Motion> input = new ArrayList<>(Arrays.asList(first, measured(1, 2, 1, BODY, -400)));
        SceneEngine.MotionPair pair = new SceneEngine.MotionPair(input); input.clear();
        assertEquals(2, pair.pieces.size());
        assertThrows(UnsupportedOperationException.class, () -> pair.pieces.clear());
        assertThrows(IllegalArgumentException.class, () -> new SceneEngine.MotionPair(Arrays.asList(
                new SceneEngine.Motion(1, 1, 2, 1, 1, SceneEngine.MotionAuthority.EVENT_ESTIMATE, HEADER, 0, 0),
                new SceneEngine.Motion(1, 1, 2, 1, 1, SceneEngine.MotionAuthority.EVENT_ESTIMATE, BODY, 0, -400))));
    }

    @Test public void constructionRejectsOversizedInputBeforeCopyingAndAdmitsTheExactHardBound() {
        List<SceneEngine.Motion> oversized = new AbstractList<SceneEngine.Motion>() {
            @Override public int size() { return SceneEngine.MotionPair.MAXIMUM_PIECES + 1; }
            @Override public SceneEngine.Motion get(int index) { throw new AssertionError("Oversized input was read"); }
            @Override public Object[] toArray() { throw new AssertionError("Oversized input was copied"); }
        };
        assertThrows(IllegalArgumentException.class, () -> new SceneEngine.MotionPair(oversized));
        List<SceneEngine.Motion> maximum = new ArrayList<>();
        for (int i = 0; i < SceneEngine.MotionPair.MAXIMUM_PIECES; i++) {
            maximum.add(measured(1, 2, 1, new SceneEngine.Region(i * 10, 0, i * 10 + 5, 20), 0));
        }
        SceneEngine.MotionPair pair = new SceneEngine.MotionPair(maximum);
        assertEquals(SceneEngine.MotionPair.MAXIMUM_PIECES, pair.pieces.size());
        assertEquals(SceneEngine.MotionAdmission.ADMITTED, engine().admitMotion(pair));
    }

    @Test public void signedZeroCannotMakeTheOrderOfAnExactDuplicateSignificant() {
        SceneEngine engine = engine(); engine.admitMotion(mixedPair(1));
        SceneEngine.Region negativeHeader = new SceneEngine.Region(-0.0, -0.0, 1000, 80);
        SceneEngine.Region negativeBody = new SceneEngine.Region(-0.0, 500, 1000, 2400);
        assertEquals(SceneEngine.MotionAdmission.DUPLICATE, engine.admitMotion(new SceneEngine.MotionPair(Arrays.asList(
                measured(1, 2, 2, negativeBody, -400), measured(1, 2, 2, negativeHeader, -0.0)))));
    }

    @Test public void touchingSupportsDoNotOverlapButCannotInventOneWholeBoxMappingAcrossTheirBoundary() {
        SceneEngine engine = engine();
        SceneEngine.MotionPair adjacent = new SceneEngine.MotionPair(Arrays.asList(
                measured(1, 2, 1, new SceneEngine.Region(0, 0, 1000, 100), 0),
                measured(1, 2, 1, new SceneEngine.Region(0, 100, 1000, 2400), 0)));
        assertEquals(SceneEngine.MotionAdmission.ADMITTED, engine.admitMotion(adjacent));
        engine.observe(new SceneEngine.Batch(engine.source(1), FAST, Collections.singletonList(
                new SceneEngine.Observation("CROSSING", 1, 0, new SceneEngine.Region(100, 50, 200, 150), .9f)),
                Collections.emptyList()));
        SceneEngine.Snapshot snapshot = engine.snapshot(2, 210, false);
        positions(snapshot); assertEquals(1, snapshot.unresolvedTracks);
    }

    @Test public void staleInverseCorrectionIsBenignEvenWhenANewerEquivalentWouldConflict() {
        SceneEngine engine = engine();
        assertEquals(SceneEngine.MotionAdmission.ADMITTED, engine.admitMotion(new SceneEngine.Motion(
                1, 1, 2, 1, 3, IMAGE_CORRESPONDENCE, new SceneEngine.Region(0, 0, 100, 100), 0, 0)));
        assertEquals(SceneEngine.MotionAdmission.STALE, engine.admitMotion(new SceneEngine.Motion(
                1, 1, 2, 1, 2, IMAGE_CORRESPONDENCE, new SceneEngine.Region(200, 0, 300, 100), -200, 0)));
        assertFalse(engine.hasMotionConflict());
    }

    @Test public void broadDisjointSourceRoutesStillConflictWhenTheirDestinationsConverge() {
        SceneEngine engine = engine(4); observeMixed(engine);
        SceneEngine.Region header = new SceneEngine.Region(0, 0, 1000, 200);
        SceneEngine.Region body = new SceneEngine.Region(0, 400, 1000, 2400);
        assertEquals(SceneEngine.MotionAdmission.ADMITTED, engine.admitMotion(measured(1, 2, 1, header, 0)));
        assertEquals(SceneEngine.MotionAdmission.ADMITTED, engine.admitMotion(measured(2, 4, 1, header, 0)));
        assertEquals(SceneEngine.MotionAdmission.ADMITTED, engine.admitMotion(measured(1, 3, 1, body, 0)));
        assertEquals(SceneEngine.MotionAdmission.CONFLICT, engine.admitMotion(measured(3, 4, 1, body, -400)));
        positions(engine.snapshot(1, 410, false));
    }

    @Test public void anImagePairReplacesTheSingletonEstimateAsOneAuthorityTransition() {
        SceneEngine engine = engine(2, new SceneEngine.Policy(16, 100, 2, 256, 500, 5000, .5));
        observeMixed(engine);
        assertTrue(engine.motion(new SceneEngine.Motion(1, 1, 2, 1, 1, SceneEngine.MotionAuthority.EVENT_ESTIMATE,
                new SceneEngine.Region(-1000, -1000, 3000, 4000), 0, -200)));
        assertEquals(SceneEngine.MotionAdmission.ADMITTED, engine.admitMotion(mixedPair(1)));
        SceneEngine.Snapshot snapshot = engine.snapshot(2, 210, true); positions(snapshot, 50, 100);
        for (SceneEngine.Mask mask : masks(snapshot)) assertFalse(mask.estimatedMotion);
    }

    @Test public void independentPiecePathsPermitOnlyActuallyReachableNegativeEvidence() {
        SceneEngine engine = engine(3);
        engine.observe(new SceneEngine.Batch(engine.source(1), FAST, Arrays.asList(
                new SceneEngine.Observation("HEADER", 1, 0, new SceneEngine.Region(100, 50, 200, 70), .9f),
                new SceneEngine.Observation("BODY", 1, 0, new SceneEngine.Region(100, 500, 200, 600), .9f),
                new SceneEngine.Observation("GAP", 1, 0, new SceneEngine.Region(100, 200, 200, 250), .9f)),
                Collections.emptyList()));
        engine.admitMotion(mixedPair(1));
        assertEquals(SceneEngine.MotionAdmission.ADMITTED, engine.admitMotion(new SceneEngine.MotionPair(Arrays.asList(
                measured(2, 3, 1, HEADER, 0), measured(2, 3, 1, new SceneEngine.Region(0, 100, 1000, 2000), 50)))));
        List<SceneEngine.Inspection> inspected = Arrays.asList(
                new SceneEngine.Inspection("HEADER", 1, new SceneEngine.Region(0, 0, 1000, 2400)),
                new SceneEngine.Inspection("BODY", 1, new SceneEngine.Region(0, 0, 1000, 2400)),
                new SceneEngine.Inspection("GAP", 1, new SceneEngine.Region(0, 0, 1000, 2400)));
        for (int i = 2; i <= 3; i++) {
            assertTrue(engine.observe(new SceneEngine.Batch(engine.source(i), SceneEngine.Lane.QUALITY,
                    Collections.emptyList(), inspected)));
        }
        assertEquals(1, engine.trackCount());
        SceneEngine.Snapshot snapshot = engine.snapshot(1, 600, false, Collections.singletonMap(1L, 3L));
        positions(snapshot, 200); SceneEngine.Mask gap = masks(snapshot).get(0);
        assertEquals("GAP", gap.category); assertTrue(gap.heldGeometry); assertFalse(gap.confirmed);
        assertEquals(1, gap.geometrySource.sequence); assertEquals(1, gap.evidenceSource.sequence);
        assertEquals(601, snapshot.nextChangeAtMillis);
        positions(engine.snapshot(1, 601, false, Collections.singletonMap(1L, 3L)));
    }

    @Test public void pixelSourceTextGroupingCannotTurnItsUnionBoundsIntoUnknownGapAuthority() {
        SceneEngine engine = engine(3);
        // This is image-origin text evidence, not a claim that native Accessibility reads share pixels.
        engine.observe(new SceneEngine.Batch(engine.source(1), SceneEngine.Lane.TEXT, Arrays.asList(
                new SceneEngine.Observation("WORD", 1, 7, new SceneEngine.Region(100, 50, 200, 70), .9f),
                new SceneEngine.Observation("WORD", 1, 7, new SceneEngine.Region(100, 500, 200, 530), .9f),
                new SceneEngine.Observation("WORD", 1, 7, new SceneEngine.Region(100, 200, 200, 220), .9f)),
                Collections.emptyList()));
        engine.admitMotion(mixedPair(1));
        SceneEngine.Snapshot snapshot = engine.snapshot(1, 210, false, Collections.singletonMap(1L, 2L));
        assertEquals(1, snapshot.groups.size()); assertEquals(3, snapshot.groups.get(0).members.size());
        positions(snapshot, 50, 100, 200);
        assertEquals(50, snapshot.groups.get(0).bounds.top, 0); assertEquals(220, snapshot.groups.get(0).bounds.bottom, 0);
        for (SceneEngine.Mask mask : masks(snapshot)) {
            assertEquals(1, mask.evidenceSource.sequence); assertEquals(7, mask.textGroup);
            boolean gap = mask.evidenceRegion.top == 200;
            assertEquals(gap ? 1 : 2, mask.geometrySource.sequence); assertEquals(gap, mask.heldGeometry);
        }
        engine.admitMotion(new SceneEngine.MotionPair(Arrays.asList(measured(2, 3, 1, HEADER, 0),
                measured(2, 3, 1, new SceneEngine.Region(0, 100, 1000, 2000), 0))));
        for (int i = 2; i <= 3; i++) {
            engine.observe(new SceneEngine.Batch(engine.source(i), SceneEngine.Lane.QUALITY, Collections.emptyList(),
                    Collections.singletonList(new SceneEngine.Inspection("WORD", 1, new SceneEngine.Region(0, 0, 1000, 2400)))));
        }
        SceneEngine.Snapshot remaining = engine.snapshot(1, 310, false, Collections.singletonMap(1L, 3L));
        positions(remaining, 200); assertEquals(1, engine.trackCount());
        assertEquals(1, masks(remaining).get(0).geometrySource.sequence); assertTrue(masks(remaining).get(0).heldGeometry);
    }

    @Test public void aNewInverseContradictionInvalidatesEvenWhenItsSourceSupportIsDisjoint() {
        SceneEngine engine = engine();
        assertEquals(SceneEngine.MotionAdmission.ADMITTED, engine.admitMotion(new SceneEngine.Motion(
                1, 1, 2, 1, 1, IMAGE_CORRESPONDENCE, new SceneEngine.Region(0, 0, 100, 100), 0, 0)));
        assertEquals(SceneEngine.MotionAdmission.CONFLICT, engine.admitMotion(new SceneEngine.Motion(
                1, 1, 2, 1, 2, IMAGE_CORRESPONDENCE, new SceneEngine.Region(200, 0, 300, 100), -200, 0)));
        assertTrue(engine.hasMotionConflict());
    }

    @Test public void inverseConvergenceAcrossDifferentPairsInvalidatesInBothInsertionOrders() {
        for (int[] order : new int[][]{{0, 1, 2}, {1, 2, 0}}) {
            SceneEngine engine = engine(3);
            SceneEngine.Motion[] edges = {
                    new SceneEngine.Motion(1, 1, 2, 1, 1, IMAGE_CORRESPONDENCE,
                            new SceneEngine.Region(0, 0, 100, 100), 0, 0),
                    new SceneEngine.Motion(1, 1, 3, 1, 1, IMAGE_CORRESPONDENCE,
                            new SceneEngine.Region(0, 200, 100, 300), 0, -200),
                    new SceneEngine.Motion(1, 2, 3, 1, 1, IMAGE_CORRESPONDENCE,
                            new SceneEngine.Region(0, 0, 100, 100), 0, 0)};
            for (int i = 0; i < 2; i++) assertEquals(SceneEngine.MotionAdmission.ADMITTED,
                    engine.admitMotion(edges[order[i]]));
            assertEquals(SceneEngine.MotionAdmission.CONFLICT, engine.admitMotion(edges[order[2]]));
            assertTrue(engine.hasMotionConflict());
        }
    }
}
