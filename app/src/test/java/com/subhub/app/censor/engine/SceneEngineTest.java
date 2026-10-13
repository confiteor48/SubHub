package com.subhub.app.censor.engine;

import static com.subhub.app.censor.engine.SceneEngine.Lane.FAST;
import static com.subhub.app.censor.engine.SceneEngine.Lane.QUALITY;
import static com.subhub.app.censor.engine.SceneEngine.Lane.TEXT;
import static com.subhub.app.censor.engine.SceneEngine.MotionAuthority.EVENT_ESTIMATE;
import static com.subhub.app.censor.engine.SceneEngine.MotionAuthority.IMAGE_CORRESPONDENCE;
import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class SceneEngineTest {
    private static final SceneEngine.Region SUPPORT = new SceneEngine.Region(-10000, -10000, 10000, 10000);

    @Test public void completeEmptyFastMissesDwellWithoutRemovingRetainedSupportedGeometry() {
        SceneEngine engine = engine(); register(engine, 2); engine.motion(motion(1, 2, 0));
        engine.observe(batch(source(1), FAST, face(500)));
        assertTrue(masks(engine.snapshot(1, 110, false)).get(0).dwellPositive);
        engine.observe(batch(source(2), FAST));
        SceneEngine.Mask retained = masks(engine.snapshot(2, 210, false)).get(0);
        assertFalse(retained.dwellPositive); assertEquals(500, retained.displayRegion.top, 0);
        assertEquals(1, retained.evidenceSource.sequence); assertEquals(2, retained.geometrySource.sequence);
    }
    @Test public void oldEmptyFastCannotMissANewerPositiveOrPoisonANewEpoch() {
        SceneEngine engine = engine(); register(engine, 2); engine.observe(batch(source(2), FAST, face(500)));
        engine.observe(batch(source(1), FAST));
        assertTrue(masks(engine.snapshot(2, 210, false)).get(0).dwellPositive);
        engine.reset(2); SceneEngine.Source fresh = new SceneEngine.Source(2, 1, 42, 1000, 2400, 100, -1);
        engine.register(fresh); engine.observe(batch(fresh, FAST, face(500)));
        assertTrue(masks(engine.snapshot(1, 110, false)).get(0).dwellPositive);
    }
    @Test public void oneQualifiedRegionalNegativeOnlyMissesItsMemberNotUnrelatedPositive() {
        SceneEngine engine = engine(); register(engine, 2); engine.motion(motion(1, 2, 0));
        engine.observe(batch(source(1), FAST, face(500), face(900)));
        engine.observe(emptyQuality(source(2), "FACE", 1, new SceneEngine.Region(0, 400, 1000, 700)));
        List<SceneEngine.Mask> retained = masks(engine.snapshot(2, 210, false)); assertEquals(2, retained.size());
        for (SceneEngine.Mask member : retained) assertEquals(member.displayRegion.top != 500, member.dwellPositive);
    }
    @Test public void cachedConfirmedQualityRemainsPositiveOnEmptyFastWithoutRenewingEvidence() {
        SceneEngine engine = engine(); register(engine, 3); engine.motion(motion(1, 2, 0)); engine.motion(motion(2, 3, 0));
        engine.observe(batch(source(1), QUALITY, face(500))); engine.observe(batch(source(2), QUALITY, face(500)));
        engine.observe(batch(source(3), FAST));
        SceneEngine.Mask member = masks(engine.snapshot(3, 310, false)).get(0);
        assertTrue(member.dwellPositive); assertTrue(member.confirmed); assertEquals(2, member.evidenceSource.sequence);
        assertEquals(3, member.geometrySource.sequence); assertEquals(200, member.evidenceSource.receivedAtMillis);
    }

    @Test public void completeOcrSetReplacesOnlyItsImagePartitionWithoutMotionOrDuplicateRows() {
        SceneEngine engine = engine(); register(engine, 3);
        engine.observe(batch(source(1), FAST, face(500)));
        engine.observe(SceneEngine.Batch.imageText(source(1), 3, Collections.singletonList(observation("TEXT", 3, 9, 100, 700))));
        assertTrue(engine.observe(SceneEngine.Batch.imageText(source(2), 3,
                Collections.singletonList(observation("TEXT", 3, 9, 100, 800)))));
        positions(engine.snapshot(1, 210, false, Collections.singletonMap(3L, 2L)), 500, 800);
        assertTrue(engine.observe(SceneEngine.Batch.imageText(source(3), 3, Collections.emptyList())));
        positions(engine.snapshot(1, 310, false), 500);
        assertFalse(engine.observe(SceneEngine.Batch.imageText(source(1), 3,
                Collections.singletonList(observation("TEXT", 3, 9, 100, 700)))));
        positions(engine.snapshot(1, 310, false), 500);
    }

    @Test public void completeOcrDoesNotRemoveNativeWithTheSamePartitionAndGroup() {
        SceneEngine engine = engine(); register(engine, 2);
        engine.observe(SceneEngine.Batch.imageText(source(1), 3, Collections.singletonList(observation("TEXT", 3, 1, 100, 700))));
        String digest = String.join("", Collections.nCopies(64, "a"));
        NativeTextResult read = new NativeTextResult(1000, 2400, 7, 300, 310,
                Collections.singletonList(new NativeTextResult.Candidate("TEXT", digest, null, .9f, true,
                        Collections.singletonList(new SceneEngine.Region(100, 900, 200, 1000)))), 1, 1, 1);
        SceneEngine.Source nativeSource = SceneEngine.Source.nativeText(1, 3, 42, 1000, 2400, 7, 300, 310, 320,
                ProtectionScope.UNSPECIFIED);
        assertTrue(engine.admitNativeFrame(new NativeTextFrame(nativeSource, 3, read, 1000)));
        assertTrue(engine.register(source(4)));
        assertTrue(engine.observe(SceneEngine.Batch.imageText(source(4), 3, Collections.emptyList())));
        SceneEngine.Snapshot view = engine.snapshot(1, 410, false);
        assertEquals(1, masks(view).size()); assertTrue(masks(view).get(0).nativeValidated); positions(view, 900);
    }

    @Test public void overCapacityCompleteOcrCannotPartiallyReplaceAValidOldSet() {
        SceneEngine engine = new SceneEngine(1, new SceneEngine.Policy(16, 2, 64, 256, 500, 5000, .5));
        register(engine, 2); engine.observe(batch(source(1), FAST, face(500)));
        engine.observe(SceneEngine.Batch.imageText(source(1), 3, Collections.singletonList(observation("TEXT", 3, 1, 100, 700))));
        assertFalse(engine.observe(SceneEngine.Batch.imageText(source(2), 3, Arrays.asList(
                observation("TEXT", 3, 1, 100, 800), observation("TEXT", 3, 1, 100, 1000)))));
        positions(engine.snapshot(1, 210, false), 500, 700);
    }

    @Test public void completeOcrRejectsMixedOrUnsupportedGeometryBeforeSceneMutation() {
        assertThrows(IllegalArgumentException.class, () -> SceneEngine.Batch.imageText(source(1), 3,
                Collections.singletonList(observation("TEXT", 4, 1, 100, 500))));
        assertThrows(IllegalArgumentException.class, () -> SceneEngine.Batch.imageText(source(1), 3,
                Collections.singletonList(observation("TEXT", 3, 0, 100, 500))));
        assertThrows(IllegalArgumentException.class, () -> SceneEngine.Batch.imageText(source(1), 3,
                Collections.singletonList(observation("TEXT", 3, 1, -500, 500))));
    }

    private static SceneEngine engine() {
        return new SceneEngine(1, new SceneEngine.Policy(16, 100, 64, 256, 500, 5000, .5));
    }

    private static SceneEngine.Source source(long sequence) {
        return new SceneEngine.Source(1, sequence, 42, 1000, 2400, sequence * 100, -1);
    }

    private static SceneEngine.Observation face(double y) {
        return observation("FACE", 1, 0, 100, y);
    }

    private static SceneEngine.Observation observation(String category, long partition, long group, double x, double y) {
        return new SceneEngine.Observation(category, partition, group,
                new SceneEngine.Region(x, y, x + 100, y + 100), .9f);
    }

    private static SceneEngine.Batch batch(SceneEngine.Source source, SceneEngine.Lane lane,
            SceneEngine.Observation... observations) {
        return new SceneEngine.Batch(source, lane, Arrays.asList(observations), Collections.emptyList());
    }

    private static SceneEngine.Batch emptyQuality(SceneEngine.Source source, String category,
            long partition, SceneEngine.Region inspected) {
        return new SceneEngine.Batch(source, QUALITY, Collections.emptyList(),
                Collections.singletonList(new SceneEngine.Inspection(category, partition, inspected)));
    }

    private static void register(SceneEngine engine, int count) {
        for (int i = 1; i <= count; i++) assertTrue(engine.register(source(i)));
    }

    private static SceneEngine.Motion motion(long from, long to, double dy) {
        return new SceneEngine.Motion(1, from, to, 1, 1, IMAGE_CORRESPONDENCE, SUPPORT, 0, dy);
    }

    private static List<SceneEngine.Mask> masks(SceneEngine.Snapshot snapshot) {
        List<SceneEngine.Mask> result = new ArrayList<>();
        for (SceneEngine.Group group : snapshot.groups) result.addAll(group.members);
        return result;
    }

    private static void positions(SceneEngine.Snapshot snapshot, double... expected) {
        double[] actual = masks(snapshot).stream().mapToDouble(mask -> mask.displayRegion.top).sorted().toArray();
        Arrays.sort(expected);
        assertArrayEquals(expected, actual, .00001);
    }

    @Test public void retentionDeadlineIsSourceLocalAndChangesAtTheFirstInvalidMillisecond() {
        SceneEngine engine = engine(); register(engine, 2);
        engine.observe(batch(source(1), FAST, face(500)));
        engine.observe(batch(source(2), FAST, face(900)));
        engine.motion(motion(1, 2, 0));
        assertEquals(601, engine.snapshot(2, 600, false).nextChangeAtMillis);
        positions(engine.ready(), 500, 900);
        assertEquals(701, engine.snapshot(2, 601, false).nextChangeAtMillis);
        positions(engine.ready(), 900);
        assertEquals(Long.MAX_VALUE, engine.snapshot(2, 701, false).nextChangeAtMillis);
        positions(engine.ready());
    }

    @Test public void bodyOnlyBasisCannotClearAHeaderInTheSameActualVisualPartition() {
        SceneEngine engine = engine(); register(engine, 2);
        engine.observe(batch(source(1), FAST, observation("HEADER", 1, 0, 100, 50), face(500)));
        assertTrue(engine.motion(new SceneEngine.Motion(1, 1, 2, 1, 1, IMAGE_CORRESPONDENCE,
                new SceneEngine.Region(0, 300, 1000, 2400), 0, -400)));
        SceneEngine.Snapshot snapshot = engine.snapshot(1, 210, false, Collections.singletonMap(1L, 2L));
        positions(snapshot, 50, 100);
        for (SceneEngine.Mask mask : masks(snapshot)) {
            assertEquals("HEADER".equals(mask.category) ? 1 : 2, mask.geometrySource.sequence);
            assertEquals(1, mask.evidenceSource.sequence);
            assertEquals(100, mask.evidenceSource.receivedAtMillis);
        }
    }

    @Test public void laterPartialBasisCannotRollAReadyBodyBackToTheOriginalCanvas() {
        SceneEngine engine = engine(); register(engine, 3);
        engine.observe(batch(source(1), FAST, face(500)));
        assertTrue(engine.motion(motion(1, 2, -400)));
        positions(engine.snapshot(1, 210, false, Collections.singletonMap(1L, 2L)), 100);
        assertTrue(engine.motion(new SceneEngine.Motion(1, 2, 3, 1, 1, IMAGE_CORRESPONDENCE,
                new SceneEngine.Region(0, 1000, 1000, 2400), 0, -400)));
        SceneEngine.Snapshot snapshot = engine.snapshot(1, 310, false, Collections.singletonMap(1L, 3L));
        positions(snapshot, 100);
        assertEquals(2, masks(snapshot).get(0).geometrySource.sequence);
        assertEquals(1, masks(snapshot).get(0).evidenceSource.sequence);
    }

    @Test public void partialBasisBeforeFirstFastUsesTheLatestActuallySupportedImageSource() {
        SceneEngine engine = engine(); register(engine, 3);
        engine.motion(motion(1, 2, -400));
        engine.motion(new SceneEngine.Motion(1, 2, 3, 1, 1, IMAGE_CORRESPONDENCE,
                new SceneEngine.Region(0, 1000, 1000, 2400), 0, -400));
        engine.observe(batch(source(1), FAST, face(500)));
        SceneEngine.Snapshot snapshot = engine.snapshot(1, 310, false, Collections.singletonMap(1L, 3L));
        positions(snapshot, 100);
        assertEquals(2, masks(snapshot).get(0).geometrySource.sequence);
    }

    @Test public void aNewConflictingMeasuredDiamondClearsReadyCoverageInBothOrders() {
        for (int[] order : new int[][]{{0, 1, 2, 3}, {2, 3, 0, 1}}) {
            SceneEngine engine = engine(); register(engine, 4);
            engine.observe(batch(source(1), FAST, face(900)));
            SceneEngine.Motion[] edges = {motion(1, 2, -200), motion(2, 4, -200),
                    motion(1, 3, -200), motion(3, 4, -100)};
            for (int i = 0; i < 3; i++) assertTrue(engine.motion(edges[order[i]]));
            assertEquals(1, masks(engine.snapshot(4, 410, false)).size());
            assertFalse("A contradictory measured route was admitted", engine.motion(edges[order[3]]));
            positions(engine.snapshot(4, 410, false));
            assertFalse("A conflicted epoch cannot accept new evidence",
                    engine.observe(batch(source(4), FAST, face(500))));
        }
    }

    @Test public void aConsistentMeasuredDiamondHasTheSameGeometryInBothOrders() {
        for (int[] order : new int[][]{{0, 1, 2, 3}, {2, 3, 0, 1}}) {
            SceneEngine engine = engine(); register(engine, 4);
            engine.observe(batch(source(1), FAST, face(900)));
            SceneEngine.Motion[] edges = {motion(1, 2, -200), motion(2, 4, -200),
                    motion(1, 3, -200), motion(3, 4, -200)};
            for (int index : order) assertTrue(engine.motion(edges[index]));
            positions(engine.snapshot(4, 410, false), 500);
        }
    }

    @Test public void aNewerMeasuredCorrectionInvalidatesRatherThanKeepingDerivedIdentity() {
        SceneEngine engine = engine(); register(engine, 2); engine.motion(motion(1, 2, -400));
        engine.observe(batch(source(1), FAST, face(500))); engine.observe(batch(source(2), FAST, face(100)));
        positions(engine.snapshot(2, 210, false), 100); assertEquals(1, engine.trackCount());
        assertFalse(engine.motion(new SceneEngine.Motion(1, 1, 2, 1, 2,
                IMAGE_CORRESPONDENCE, SUPPORT, 0, -300)));
        positions(engine.snapshot(2, 210, false));
        assertFalse(engine.observe(batch(source(2), QUALITY, face(100))));
    }

    @Test public void exactMeasuredRepeatAndOlderCorrectionDoNotClearValidReadyCoverage() {
        SceneEngine engine = engine(); register(engine, 2);
        SceneEngine.Motion measured = new SceneEngine.Motion(1, 1, 2, 1, 2,
                IMAGE_CORRESPONDENCE, SUPPORT, 0, -30);
        engine.motion(measured); engine.observe(batch(source(1), FAST, face(500)));
        positions(engine.snapshot(2, 210, false), 470);
        assertFalse(engine.motion(measured));
        assertFalse(engine.motion(new SceneEngine.Motion(1, 1, 2, 1, 1,
                IMAGE_CORRESPONDENCE, SUPPORT, 0, -40)));
        assertFalse(engine.motion(new SceneEngine.Motion(1, 1, 2, 1, 3,
                IMAGE_CORRESPONDENCE, SUPPORT, 0, -30)));
        positions(engine.snapshot(2, 210, false), 470);
    }

    @Test public void measuredConflictOutsideLiveBoxesStillInvalidatesTheAuthoritativeScene() {
        SceneEngine engine = engine(); register(engine, 4); engine.observe(batch(source(1), FAST, face(500)));
        SceneEngine.Region original = new SceneEngine.Region(0, 1000, 1000, 2000);
        SceneEngine.Region shifted = new SceneEngine.Region(0, 900, 1000, 1900);
        assertTrue(engine.motion(new SceneEngine.Motion(1, 1, 2, 1, 1, IMAGE_CORRESPONDENCE, original, 0, -100)));
        assertTrue(engine.motion(new SceneEngine.Motion(1, 2, 4, 1, 1, IMAGE_CORRESPONDENCE, shifted, 0, -100)));
        assertTrue(engine.motion(new SceneEngine.Motion(1, 1, 3, 1, 1, IMAGE_CORRESPONDENCE, original, 0, -100)));
        positions(engine.snapshot(1, 410, false), 500);
        assertFalse(engine.motion(new SceneEngine.Motion(1, 3, 4, 1, 1, IMAGE_CORRESPONDENCE, shifted, 0, -50)));
        positions(engine.snapshot(1, 410, false));
    }

    @Test public void sourceAndDestinationDisjointMeasuredPathsAreNotAGlobalConflict() {
        SceneEngine engine = engine(); register(engine, 4);
        engine.observe(batch(source(1), FAST, new SceneEngine.Observation("HEADER", 1, 0,
                new SceneEngine.Region(100, 50, 200, 70), .9f), face(500)));
        // Source-disjoint alone is insufficient: the shifted body must not converge into header pixels.
        SceneEngine.Region header = new SceneEngine.Region(0, 0, 1000, 80);
        SceneEngine.Region body = new SceneEngine.Region(0, 500, 1000, 2400);
        assertTrue(engine.motion(new SceneEngine.Motion(1, 1, 2, 1, 1, IMAGE_CORRESPONDENCE, header, 0, 0)));
        assertTrue(engine.motion(new SceneEngine.Motion(1, 2, 4, 1, 1, IMAGE_CORRESPONDENCE, header, 0, 0)));
        assertTrue(engine.motion(new SceneEngine.Motion(1, 1, 3, 1, 1, IMAGE_CORRESPONDENCE, body, 0, 0)));
        assertTrue(engine.motion(new SceneEngine.Motion(1, 3, 4, 1, 1, IMAGE_CORRESPONDENCE, body, 0, -400)));
        positions(engine.snapshot(4, 410, false), 50, 100);
    }

    @Test public void exhaustedMotionValidationRejectsWithoutDeclaringConsistencyOrInvalidatingValidCoverage() {
        // One singleton now inspects both endpoint domains (4 states/6 checks). The next relation
        // must exhaust these whole-result bounds, rather than resetting them for each direction.
        for (int[] limits : new int[][]{{4, 64}, {64, 6}}) {
            SceneEngine engine = new SceneEngine(1,
                    new SceneEngine.Policy(16, 100, 64, 256, 500, 5000, .5, limits[0], limits[1]));
            register(engine, 3); engine.observe(batch(source(1), FAST, face(500)));
            assertEquals(SceneEngine.MotionAdmission.ADMITTED, engine.admitMotion(motion(1, 2, 0)));
            SceneEngine.Snapshot prior = engine.snapshot(2, 210, false);
            assertEquals(SceneEngine.MotionAdmission.CHECK_BUDGET, engine.admitMotion(motion(2, 3, 0)));
            assertFalse(engine.hasMotionConflict()); assertSame(prior, engine.ready());
            SceneEngine.Snapshot current = engine.snapshot(2, 210, false);
            positions(current, 500); assertEquals(1, current.capacityDrops);
            assertTrue(engine.observe(batch(source(2), FAST, face(500))));
        }
    }

    @Test public void inverseMeasuredRouteAlsoDetectsAContradictoryTranslation() {
        SceneEngine engine = engine(); register(engine, 3);
        assertTrue(engine.motion(motion(1, 2, -400)));
        assertTrue(engine.motion(motion(1, 3, -300)));
        engine.observe(batch(source(2), FAST, face(500)));
        positions(engine.snapshot(2, 310, false), 500);
        assertEquals(SceneEngine.MotionAdmission.CONFLICT, engine.admitMotion(motion(2, 3, -200)));
        positions(engine.snapshot(2, 310, false));
    }

    @Test public void partialRouteSupportMustBePulledBackBeforeComparingTranslations() {
        for (boolean overlaps : new boolean[]{false, true}) {
            SceneEngine engine = engine(); register(engine, 3);
            assertTrue(engine.motion(new SceneEngine.Motion(1, 1, 2, 1, 1, IMAGE_CORRESPONDENCE,
                    new SceneEngine.Region(0, 400, 1000, 1400), 0, -400)));
            assertTrue(engine.motion(new SceneEngine.Motion(1, 2, 3, 1, 1, IMAGE_CORRESPONDENCE,
                    new SceneEngine.Region(0, 900, 1000, 1400), 0, -100)));
            SceneEngine.Region candidate = overlaps ? new SceneEngine.Region(0, 1350, 1000, 1450)
                    : new SceneEngine.Region(0, 400, 1000, 900);
            assertEquals(overlaps ? SceneEngine.MotionAdmission.CONFLICT : SceneEngine.MotionAdmission.ADMITTED,
                    engine.admitMotion(new SceneEngine.Motion(1, 1, 3, 1, 1, IMAGE_CORRESPONDENCE,
                            candidate, 0, -300)));
            assertEquals(overlaps, engine.hasMotionConflict());
        }
    }

    @Test public void consistentSupportOnlyChangesCannotConsumeTheImmutableKeyOrInvalidateCoverage() {
        SceneEngine engine = engine(); register(engine, 2);
        assertTrue(engine.motion(motion(1, 2, -400)));
        engine.observe(batch(source(1), FAST, face(500)));
        SceneEngine.Snapshot prior = engine.snapshot(2, 210, false);
        assertEquals(SceneEngine.MotionAdmission.INVALID, engine.admitMotion(new SceneEngine.Motion(
                1, 1, 2, 1, 2, IMAGE_CORRESPONDENCE, new SceneEngine.Region(0, 300, 1000, 1200), 0, -400)));
        assertSame(prior, engine.ready()); assertFalse(engine.hasMotionConflict());
        positions(engine.snapshot(2, 210, false), 100);
    }

    @Test public void typedCorrectionOutcomeKeepsTheLatestDuplicateWatermarkAndRequiresAConflictReset() {
        SceneEngine engine = engine(); register(engine, 2);
        engine.observe(batch(source(1), FAST, face(500)));
        assertEquals(SceneEngine.MotionAdmission.ADMITTED, engine.admitMotion(motion(1, 2, -30)));
        SceneEngine.Snapshot prior = engine.snapshot(2, 210, false);
        assertEquals(SceneEngine.MotionAdmission.DUPLICATE, engine.admitMotion(new SceneEngine.Motion(
                1, 1, 2, 1, 3, IMAGE_CORRESPONDENCE, SUPPORT, 0, -30)));
        assertEquals(SceneEngine.MotionAdmission.STALE, engine.admitMotion(new SceneEngine.Motion(
                1, 1, 2, 1, 2, IMAGE_CORRESPONDENCE, SUPPORT, 0, -40)));
        assertSame(prior, engine.ready()); assertFalse(engine.hasMotionConflict());
        assertEquals(SceneEngine.MotionAdmission.CONFLICT, engine.admitMotion(new SceneEngine.Motion(
                1, 1, 2, 1, 3, IMAGE_CORRESPONDENCE, SUPPORT, 0, -40)));
        assertTrue(engine.hasMotionConflict()); assertFalse(engine.register(source(3)));
        positions(engine.snapshot(2, 210, false));
        engine.reset(2);
        SceneEngine.Source next = new SceneEngine.Source(2, 1, 42, 1000, 2400, 500, -1);
        assertTrue(engine.register(next)); assertTrue(engine.observe(batch(next, FAST, face(600))));
        positions(engine.snapshot(1, 510, false), 600); assertFalse(engine.hasMotionConflict());
    }

    @Test public void numericalAdditionRoundoffIsNotAnInventedPhysicalMotionTolerance() {
        SceneEngine engine = engine(); register(engine, 4);
        assertTrue(engine.motion(motion(1, 2, .1))); assertTrue(engine.motion(motion(2, 4, .2)));
        assertTrue(engine.motion(motion(1, 3, .05))); assertTrue(engine.motion(motion(3, 4, .25)));
        assertFalse(engine.hasMotionConflict());
        assertEquals(SceneEngine.MotionAdmission.CONFLICT,
                engine.admitMotion(new SceneEngine.Motion(1, 1, 4, 1, 1, IMAGE_CORRESPONDENCE,
                        SUPPORT, 0, .3 + 1e-8)));
    }

    @Test public void heldGeometryCannotSupplyNegativeReachabilityConfirmationOrANewLifetime() {
        SceneEngine engine = engine(); register(engine, 4);
        engine.observe(batch(source(1), FAST, face(500))); engine.motion(motion(1, 2, -400));
        engine.motion(new SceneEngine.Motion(1, 2, 3, 1, 1, IMAGE_CORRESPONDENCE,
                new SceneEngine.Region(0, 1000, 1000, 2400), 0, -400));
        engine.motion(new SceneEngine.Motion(1, 3, 4, 1, 1, IMAGE_CORRESPONDENCE,
                new SceneEngine.Region(0, 600, 1000, 2000), 0, -400));
        SceneEngine.Snapshot held = engine.snapshot(1, 410, false, Collections.singletonMap(1L, 4L));
        positions(held, 100); assertTrue(masks(held).get(0).heldGeometry);
        assertEquals(2, masks(held).get(0).geometrySource.sequence); assertEquals(601, held.nextChangeAtMillis);
        assertFalse(masks(held).get(0).confirmed);
        engine.observe(emptyQuality(source(3), "FACE", 1, SUPPORT));
        engine.observe(emptyQuality(source(4), "FACE", 1, SUPPORT));
        positions(engine.snapshot(1, 600, false, Collections.singletonMap(1L, 4L)), 100);
        positions(engine.snapshot(1, 601, false, Collections.singletonMap(1L, 4L)));
    }

    @Test public void anOptionalImageBasisCannotBorrowAnEventOnlyRouteForItsUnsupportedHeader() {
        SceneEngine engine = engine(); register(engine, 3);
        engine.observe(batch(source(1), FAST, face(50), face(500)));
        assertTrue(engine.motion(new SceneEngine.Motion(1, 1, 2, 1, 1, IMAGE_CORRESPONDENCE,
                new SceneEngine.Region(0, 300, 1000, 2400), 0, -400)));
        assertTrue(engine.motion(new SceneEngine.Motion(1, 1, 3, 1, 1, EVENT_ESTIMATE, SUPPORT, 0, 20)));
        assertTrue(engine.motion(new SceneEngine.Motion(1, 2, 3, 1, 1, EVENT_ESTIMATE, SUPPORT, 0, 0)));
        SceneEngine.Snapshot snapshot = engine.snapshot(1, 310, true, Collections.singletonMap(1L, 2L));
        positions(snapshot, 50, 100);
        for (SceneEngine.Mask mask : masks(snapshot)) {
            assertFalse(mask.estimatedMotion);
            assertEquals(mask.evidenceRegion.top == 50 ? 1 : 2, mask.geometrySource.sequence);
        }
        // Ordinary canvas presentation still explicitly permits event estimates.
        assertTrue(masks(engine.snapshot(3, 310, true)).stream().anyMatch(mask -> mask.estimatedMotion));
    }

    @Test public void unrepresentableMeasuredRouteRejectsWithoutClearingValidCoverage() {
        SceneEngine engine = engine(); register(engine, 3);
        assertEquals(SceneEngine.MotionAdmission.ADMITTED, engine.admitMotion(new SceneEngine.Motion(
                1, 1, 2, 1, 1, IMAGE_CORRESPONDENCE, new SceneEngine.Region(-1e308, 0, -.9e308, 100), 1e308, 0)));
        engine.observe(batch(source(1), FAST, face(500)));
        SceneEngine.Snapshot prior = engine.snapshot(1, 310, false);
        // Bidirectional provisional validation detects composition overflow before storing B-to-C,
        // not only when a later third edge happens to inspect that already-unrepresentable route.
        assertEquals(SceneEngine.MotionAdmission.UNREPRESENTABLE, engine.admitMotion(new SceneEngine.Motion(
                1, 2, 3, 1, 1, IMAGE_CORRESPONDENCE, new SceneEngine.Region(0, 0, .1e308, 100), 1e308, 0)));
        assertSame(prior, engine.ready()); assertFalse(engine.hasMotionConflict());
        positions(engine.snapshot(1, 310, false), 500);
    }

    @Test public void newEvidenceRecomputesSupportedGeometryInsteadOfReusingAnOldHeldBox() {
        SceneEngine engine = engine(); register(engine, 3);
        engine.observe(batch(source(1), FAST, face(500))); engine.motion(motion(1, 2, -400));
        positions(engine.snapshot(1, 210, false, Collections.singletonMap(1L, 2L)), 100);
        engine.motion(new SceneEngine.Motion(1, 2, 3, 1, 1, IMAGE_CORRESPONDENCE,
                new SceneEngine.Region(0, 1000, 1000, 2400), 0, -400));
        engine.observe(batch(source(1), QUALITY, face(510)));
        SceneEngine.Snapshot current = engine.snapshot(1, 310, false, Collections.singletonMap(1L, 3L));
        positions(current, 110); assertEquals(510, masks(current).get(0).evidenceRegion.top, 0);
    }

    @Test public void heldSnapshotCannotResurrectATrackRemovedByQualifiedNegativeEvidence() {
        SceneEngine engine = engine(); register(engine, 4);
        engine.observe(batch(source(1), FAST, face(500))); engine.motion(motion(1, 2, -400));
        positions(engine.snapshot(1, 210, false, Collections.singletonMap(1L, 2L)), 100);
        engine.motion(motion(2, 3, 0));
        engine.observe(emptyQuality(source(2), "FACE", 1, SUPPORT));
        engine.observe(emptyQuality(source(3), "FACE", 1, SUPPORT));
        assertEquals(0, engine.trackCount());
        engine.motion(new SceneEngine.Motion(1, 3, 4, 1, 1, IMAGE_CORRESPONDENCE,
                new SceneEngine.Region(0, 1000, 1000, 2400), 0, -400));
        positions(engine.snapshot(1, 410, false, Collections.singletonMap(1L, 4L)));
    }

    @Test public void confirmedAndTentativeTracksKeepTheirOwnRetentionDeadlines() {
        SceneEngine engine = engine(); register(engine, 2); engine.motion(motion(1, 2, 0));
        engine.observe(batch(source(1), QUALITY, face(500)));
        engine.observe(batch(source(2), QUALITY, face(500), face(900)));
        assertEquals(701, engine.snapshot(2, 210, false).nextChangeAtMillis);
        assertEquals(1, engine.ready().tentativeTracks);
        assertEquals(5201, engine.snapshot(2, 701, false).nextChangeAtMillis);
        positions(engine.ready(), 500);
        assertEquals(0, engine.ready().tentativeTracks);
    }

    @Test public void unrepresentableRetentionDeadlineSaturatesWithoutAnImmediateExpiryLoop() {
        SceneEngine engine = engine();
        SceneEngine.Source source = new SceneEngine.Source(1, 1, 42, 1000, 2400,
                Long.MAX_VALUE - 100, -1);
        engine.register(source); engine.observe(batch(source, FAST, face(500)));
        assertEquals(Long.MAX_VALUE, engine.snapshot(1, Long.MAX_VALUE, false).nextChangeAtMillis);
        positions(engine.ready(), 500);
    }

    @Test public void explicitPartitionRenderBasesKeepBodyHeaderAndTextSourcesIndependent() {
        SceneEngine engine = engine(); register(engine, 2);
        engine.observe(batch(source(1), FAST, face(500), observation("HEADER", 2, 0, 100, 50)));
        engine.observe(batch(source(1), TEXT, observation("TEXT", 3, 7, 100, 800)));
        engine.motion(motion(1, 2, -400));
        SceneEngine.Snapshot snapshot = engine.snapshot(1, 210, false, Collections.singletonMap(1L, 2L));
        assertEquals(1, snapshot.destination.sequence); positions(snapshot, 100, 50, 800);
        for (SceneEngine.Mask mask : masks(snapshot)) {
            assertEquals(mask.partition == 1 ? 2 : 1, mask.geometrySource.sequence);
            assertEquals(1, mask.evidenceSource.sequence);
        }
        assertEquals(new java.util.HashSet<>(Arrays.asList(1L, 2L, 3L)), engine.activePartitions());
    }

    @Test public void incompatiblePartitionBasisRejectsBeforeMutatingRetentionOrReadyCoverage() {
        SceneEngine engine = engine(); register(engine, 1); engine.observe(batch(source(1), FAST, face(500)));
        SceneEngine.Snapshot ready = engine.snapshot(1, 110, false);
        try { engine.snapshot(1, 5000, false, Collections.singletonMap(1L, 99L)); fail("unknown basis accepted"); }
        catch (IllegalArgumentException expected) { }
        assertSame(ready, engine.ready()); assertEquals(1, engine.trackCount());
        SceneEngine.Source incompatible = new SceneEngine.Source(1, 2, 43, 1000, 2400, 200, -1);
        engine.register(incompatible);
        try { engine.snapshot(1, 5000, false, Collections.singletonMap(1L, 2L)); fail("wrong scope accepted"); }
        catch (IllegalArgumentException expected) { }
        assertSame(ready, engine.ready()); assertEquals(1, engine.trackCount());
    }

    @Test public void aRetiredFreshObservationCannotBeBackProjectedThroughItsEvictionRebase() {
        SceneEngine engine = new SceneEngine(1, new SceneEngine.Policy(3, 100, 64, 256, 500, 5000, .5));
        register(engine, 3); assertTrue(engine.pin(source(2)));
        engine.motion(motion(2, 3, 200)); engine.observe(batch(source(3), FAST, face(300)));
        engine.register(source(4)); engine.register(source(5));
        assertNull(engine.source(3));
        SceneEngine.Snapshot snapshot = engine.snapshot(5, 510, false, Collections.singletonMap(1L, 2L));
        positions(snapshot, 300); assertEquals(3, masks(snapshot).get(0).geometrySource.sequence);
        assertEquals(3, masks(snapshot).get(0).evidenceSource.sequence);
    }

    @Test public void aTentativeQualitySourceCannotHideFirstFastCoverageAtItsQualifiedBasis() {
        SceneEngine engine = engine(); register(engine, 3);
        engine.observe(batch(source(1), FAST, face(500))); engine.motion(motion(1, 2, -400));
        engine.observe(batch(source(3), QUALITY, face(350)));
        SceneEngine.Snapshot snapshot = engine.snapshot(1, 310, true, Collections.singletonMap(1L, 2L));
        positions(snapshot, 100); assertEquals(1, snapshot.tentativeTracks);
        assertEquals(2, masks(snapshot).get(0).geometrySource.sequence);
    }

    @Test public void aFreshDirectSourceIsNotForcedBackwardEvenWhenAnInverseImageMapExists() {
        SceneEngine engine = engine(); register(engine, 3);
        engine.motion(motion(2, 3, 200)); engine.observe(batch(source(3), FAST, face(300)));
        SceneEngine.Snapshot snapshot = engine.snapshot(3, 310, true, Collections.singletonMap(1L, 2L));
        positions(snapshot, 300); assertEquals(3, masks(snapshot).get(0).geometrySource.sequence);
        assertFalse(masks(snapshot).get(0).estimatedMotion);
    }

    @Test public void exactEstimatedDuplicateCoverageDoesNotMergeUnsupportedIdentities() {
        SceneEngine engine = engine(); register(engine, 2);
        engine.motion(new SceneEngine.Motion(1, 1, 2, 1, 1, EVENT_ESTIMATE, SUPPORT, 0, -400));
        engine.observe(batch(source(1), FAST, face(500))); engine.observe(batch(source(2), FAST, face(100)));
        SceneEngine.Snapshot snapshot = engine.snapshot(2, 210, true);
        positions(snapshot, 100); assertEquals(1, snapshot.redundantEstimatedMasks);
        assertEquals(2, engine.trackCount()); assertFalse(masks(snapshot).get(0).confirmed);
        assertEquals(2, masks(snapshot).get(0).evidenceSource.sequence);
    }

    @Test public void firstFastDoesNotWaitForQualityOrAnotherCapture() {
        SceneEngine engine = engine(); register(engine, 1);
        assertTrue(engine.observe(batch(source(1), FAST, face(500))));
        SceneEngine.Snapshot snapshot = engine.snapshot(1, 110, false);
        positions(snapshot, 500);
        assertFalse(masks(snapshot).get(0).confirmed);
        assertEquals(-1, masks(snapshot).get(0).evidenceSource.pixelAtMillis);
    }

    @Test public void sameCaptureFastAndQualityAreNotTwoIndependentConfirmations() {
        SceneEngine engine = engine(); register(engine, 1);
        engine.observe(batch(source(1), QUALITY, face(500)));
        assertEquals(1, engine.snapshot(1, 110, false).tentativeTracks);
        engine.observe(batch(source(1), FAST, face(500)));
        assertFalse(masks(engine.snapshot(1, 120, false)).get(0).confirmed);
        assertFalse(engine.observe(batch(source(1), QUALITY, face(500))));
    }

    @Test public void independentQualityConfirmationIsDeliveryOrderIndependent() {
        for (boolean reverse : new boolean[] {false, true}) {
            SceneEngine engine = engine(); register(engine, 2); engine.motion(motion(1, 2, -400));
            SceneEngine.Batch old = batch(source(1), QUALITY, face(500));
            SceneEngine.Batch recent = batch(source(2), QUALITY, face(100));
            engine.observe(reverse ? recent : old); engine.observe(reverse ? old : recent);
            SceneEngine.Snapshot snapshot = engine.snapshot(2, 210, false);
            positions(snapshot, 100);
            assertTrue(masks(snapshot).get(0).confirmed);
            assertEquals(2, masks(snapshot).get(0).evidenceSource.sequence);
        }
    }

    @Test public void neighboringIdentitiesSurviveAllMapAndResultDeliveryOrders() {
        int[][] orders = {{0,1,2}, {0,2,1}, {1,0,2}, {1,2,0}, {2,0,1}, {2,1,0}};
        for (int direction : new int[] {-1, 1}) {
            double dy = direction * 400;
            for (int[] order : orders) {
                SceneEngine engine = engine(); register(engine, 2);
                for (int action : order) {
                    if (action == 0) engine.observe(batch(source(1), FAST, face(500), face(900)));
                    if (action == 1) engine.observe(batch(source(2), FAST, face(500 + dy), face(900 + dy)));
                    if (action == 2) engine.motion(motion(1, 2, dy));
                }
                positions(engine.snapshot(2, 210, false), 500 + dy, 900 + dy);
                assertEquals(Arrays.toString(order), 2, engine.trackCount());
                for (SceneEngine.Mask mask : masks(engine.ready())) assertEquals(2, mask.evidenceSource.sequence);
            }
        }
    }

    @Test public void mixedFreshAndRetainedMembersKeepTheirOwnEffectSources() {
        SceneEngine engine = engine(); register(engine, 2); engine.motion(motion(1, 2, -400));
        engine.observe(batch(source(1), FAST, face(500), face(900)));
        engine.observe(batch(source(2), FAST, face(100)));
        List<SceneEngine.Mask> masks = masks(engine.snapshot(2, 210, false));
        assertEquals(2, masks.size());
        SceneEngine.Mask retained = masks.stream().filter(mask -> mask.evidenceSource.sequence == 1).findFirst().get();
        assertEquals(500, retained.displayRegion.top, 0);
        assertEquals(900, retained.evidenceRegion.top, 0);
    }

    @Test public void overlappingEventAndImageMotionIsReplacedNotAdded() {
        SceneEngine engine = engine(); register(engine, 2); engine.observe(batch(source(1), FAST, face(500)));
        assertTrue(engine.motion(new SceneEngine.Motion(1, 1, 2, 1, 1, EVENT_ESTIMATE, SUPPORT, 0, -40)));
        SceneEngine.Snapshot estimated = engine.snapshot(2, 210, true);
        positions(estimated, 460); assertTrue(masks(estimated).get(0).estimatedMotion);
        assertEquals(1, engine.snapshot(2, 210, false).unresolvedTracks);
        assertTrue(engine.motion(motion(1, 2, -40)));
        positions(engine.snapshot(2, 210, true), 460);
        assertFalse(masks(engine.ready()).get(0).estimatedMotion);
        assertFalse(engine.motion(new SceneEngine.Motion(1, 1, 2, 1, 99, EVENT_ESTIMATE, SUPPORT, 0, -80)));
        positions(engine.snapshot(2, 210, true), 460);
    }

    @Test public void estimateCannotAuthorizeIdentityOrConfirmation() {
        SceneEngine engine = engine(); register(engine, 2);
        engine.motion(new SceneEngine.Motion(1, 1, 2, 1, 1, EVENT_ESTIMATE, SUPPORT, 0, -400));
        engine.observe(batch(source(1), QUALITY, face(500))); engine.observe(batch(source(2), QUALITY, face(100)));
        assertEquals(2, engine.trackCount());
        assertEquals(2, engine.snapshot(2, 210, true).tentativeTracks);
        engine.motion(motion(1, 2, -400));
        positions(engine.snapshot(2, 210, false), 100);
        assertTrue(masks(engine.ready()).get(0).confirmed);
    }

    @Test public void measuredRevisionCannotBeOverwrittenByOlderResult() {
        SceneEngine engine = engine(); register(engine, 2); engine.observe(batch(source(1), FAST, face(500)));
        engine.motion(new SceneEngine.Motion(1, 1, 2, 1, 2, IMAGE_CORRESPONDENCE, SUPPORT, 0, -30));
        assertFalse(engine.motion(motion(1, 2, -40)));
        positions(engine.snapshot(2, 210, false), 470);
    }

    @Test public void stickyHeaderAndScrollingBodyUseSeparatePartitions() {
        SceneEngine engine = engine(); register(engine, 2);
        engine.observe(batch(source(1), FAST, observation("FACE", 0, 0, 500, 20), face(500)));
        engine.motion(new SceneEngine.Motion(1, 1, 2, 0, 1, IMAGE_CORRESPONDENCE, SUPPORT, 0, 0));
        engine.motion(motion(1, 2, -400));
        positions(engine.snapshot(2, 210, false), 20, 100);
    }

    @Test public void offscreenReversalDoesNotAccumulateClippingError() {
        SceneEngine engine = engine(); register(engine, 3); engine.observe(batch(source(1), FAST, face(100)));
        engine.motion(motion(1, 2, -400)); engine.motion(motion(2, 3, 400));
        positions(engine.snapshot(2, 210, false));
        positions(engine.snapshot(3, 310, false), 100);
    }

    @Test public void horizontalCorrespondencePreservesBothAxes() {
        SceneEngine engine = engine(); register(engine, 2); engine.observe(batch(source(1), FAST, face(500)));
        engine.motion(new SceneEngine.Motion(1, 1, 2, 1, 1, IMAGE_CORRESPONDENCE, SUPPORT, 200, -40));
        SceneEngine.Mask mask = masks(engine.snapshot(2, 210, false)).get(0);
        assertEquals(300, mask.displayRegion.left, 0); assertEquals(460, mask.displayRegion.top, 0);
    }

    @Test public void unsupportedRegionAndMissingEdgeStayUnknown() {
        SceneEngine engine = engine(); register(engine, 3);
        engine.observe(batch(source(1), FAST, face(500), observation("FACE", 1, 0, 700, 900)));
        engine.motion(new SceneEngine.Motion(1, 1, 2, 1, 1, IMAGE_CORRESPONDENCE,
                new SceneEngine.Region(0, 0, 500, 2400), 0, -40));
        SceneEngine.Snapshot partial = engine.snapshot(2, 210, false);
        positions(partial, 460); assertEquals(1, partial.unresolvedTracks);
        SceneEngine.Snapshot missing = engine.snapshot(3, 310, false);
        positions(missing); assertEquals(2, missing.unresolvedTracks);
    }

    @Test public void changedCropOrGeometryCannotAliasOldSources() {
        SceneEngine engine = engine(); register(engine, 1); engine.observe(batch(source(1), FAST, face(500)));
        SceneEngine.Source cropped = new SceneEngine.Source(1, 2, 43, 1000, 2400, 200, -1);
        assertTrue(engine.register(cropped)); assertFalse(engine.motion(motion(1, 2, 0)));
        assertEquals(1, engine.snapshot(2, 210, false).unresolvedTracks);
    }

    @Test public void qualityNegativesNeedTwoIndependentNewerInspections() {
        SceneEngine engine = engine(); register(engine, 4);
        engine.motion(motion(1, 2, 0)); engine.motion(motion(2, 3, 0)); engine.motion(motion(3, 4, 0));
        engine.observe(batch(source(1), QUALITY, face(500))); engine.observe(batch(source(2), QUALITY, face(500)));
        engine.observe(emptyQuality(source(3), "FACE", 1, SUPPORT));
        positions(engine.snapshot(3, 310, false), 500);
        assertFalse(engine.observe(emptyQuality(source(3), "FACE", 1, SUPPORT)));
        positions(engine.snapshot(3, 310, false), 500);
        engine.observe(emptyQuality(source(4), "FACE", 1, SUPPORT));
        positions(engine.snapshot(4, 410, false)); assertEquals(0, engine.trackCount());
    }

    @Test public void oldAndUnrelatedQualityCannotContradictCurrentCoverage() {
        SceneEngine engine = engine(); register(engine, 4);
        engine.motion(motion(1, 2, 0)); engine.motion(motion(2, 3, 0)); engine.motion(motion(3, 4, 0));
        engine.observe(batch(source(2), FAST, face(500)));
        engine.observe(emptyQuality(source(1), "FACE", 1, SUPPORT));
        engine.observe(emptyQuality(source(3), "OTHER", 1, SUPPORT));
        engine.observe(emptyQuality(source(4), "FACE", 1, new SceneEngine.Region(0, 0, 1000, 400)));
        positions(engine.snapshot(4, 410, false), 500);
    }

    @Test public void latePositiveCannotResurrectAfterNewerInspectedAbsence() {
        SceneEngine engine = engine(); register(engine, 3);
        engine.motion(motion(1, 2, 0)); engine.motion(motion(2, 3, 0));
        engine.observe(emptyQuality(source(2), "FACE", 1, SUPPORT));
        engine.observe(emptyQuality(source(3), "FACE", 1, SUPPORT));
        engine.observe(batch(source(1), FAST, face(500)));
        positions(engine.snapshot(3, 310, false)); assertEquals(0, engine.trackCount());
    }

    @Test public void lateMapMustApplyAlreadyDeliveredQualityContradictions() {
        SceneEngine engine = engine(); register(engine, 3);
        engine.observe(emptyQuality(source(2), "FACE", 1, SUPPORT));
        engine.observe(emptyQuality(source(3), "FACE", 1, SUPPORT));
        engine.observe(batch(source(1), FAST, face(500)));
        engine.motion(motion(1, 2, 0)); engine.motion(motion(2, 3, 0));
        positions(engine.snapshot(3, 310, false)); assertEquals(0, engine.trackCount());
    }

    @Test public void olderPositiveDoesNotRenewOrMoveNewerEvidence() {
        SceneEngine engine = engine(); register(engine, 2); engine.motion(motion(1, 2, -400));
        engine.observe(batch(source(2), FAST, face(100))); engine.observe(batch(source(1), FAST, face(500)));
        SceneEngine.Mask mask = masks(engine.snapshot(2, 250, false)).get(0);
        assertEquals(2, mask.evidenceSource.sequence); assertEquals(100, mask.evidenceRegion.top, 0);
        positions(engine.snapshot(2, 701, false));
    }

    @Test public void textGroupingKeepsPerMemberSamplingOrigins() {
        SceneEngine engine = engine(); register(engine, 2); engine.motion(motion(1, 2, -400));
        engine.observe(batch(source(1), TEXT, observation("TEXT", 1, 77, 100, 500),
                observation("TEXT", 1, 77, 100, 650)));
        engine.observe(batch(source(2), TEXT, observation("TEXT", 1, 77, 100, 100)));
        SceneEngine.Snapshot snapshot = engine.snapshot(2, 210, false);
        assertEquals(1, snapshot.groups.size()); assertEquals(2, snapshot.groups.get(0).members.size());
        assertEquals(100, snapshot.groups.get(0).bounds.top, 0);
        assertEquals(350, snapshot.groups.get(0).bounds.bottom, 0);
        assertEquals(2, snapshot.groups.get(0).members.stream().map(mask -> mask.evidenceSource.sequence).distinct().count());
    }

    @Test public void snapshotAndBatchListsAreImmutableAndDetachedFromInputs() {
        SceneEngine engine = engine(); register(engine, 1);
        List<SceneEngine.Observation> input = new ArrayList<>(Collections.singletonList(face(500)));
        SceneEngine.Batch batch = new SceneEngine.Batch(source(1), FAST, input, Collections.emptyList());
        input.clear(); engine.observe(batch);
        SceneEngine.Snapshot old = engine.snapshot(1, 110, false);
        assertThrows(UnsupportedOperationException.class, () -> old.groups.clear());
        assertThrows(UnsupportedOperationException.class, () -> old.groups.get(0).members.clear());
        assertThrows(UnsupportedOperationException.class, () -> batch.observations.clear());
        engine.reset(2); positions(old, 500);
    }

    @Test public void captureArrivalAndSourceEvictionDoNotClearReadySnapshot() {
        SceneEngine engine = new SceneEngine(1, new SceneEngine.Policy(2, 100, 64, 256, 500, 5000, .5));
        engine.register(source(1)); engine.observe(batch(source(1), FAST, face(500)));
        SceneEngine.Snapshot ready = engine.snapshot(1, 110, false);
        engine.register(source(2)); engine.register(source(3));
        assertSame(ready, engine.ready()); positions(ready, 500); assertEquals(2, engine.sourceCount());
        assertEquals(1, engine.snapshot(3, 310, false).unresolvedTracks);
    }

    @Test public void evictedGeometryRebasesOnlyWithMeasuredEvidenceAndKeepsEffectOrigin() {
        SceneEngine engine = new SceneEngine(1, new SceneEngine.Policy(2, 100, 64, 256, 500, 5000, .5));
        engine.register(source(1)); engine.register(source(2)); engine.observe(batch(source(1), FAST, face(500)));
        engine.motion(motion(1, 2, -400)); engine.register(source(3)); engine.motion(motion(2, 3, 20));
        positions(engine.snapshot(3, 310, false), 120);
        assertEquals(1, masks(engine.ready()).get(0).evidenceSource.sequence);
        assertEquals(500, masks(engine.ready()).get(0).evidenceRegion.top, 0);
    }

    @Test public void independentConfirmationSurvivesReferenceMetadataEviction() {
        SceneEngine engine = new SceneEngine(1, new SceneEngine.Policy(2, 100, 64, 256, 500, 5000, .5));
        engine.register(source(1)); engine.register(source(2)); engine.motion(motion(1, 2, -400));
        engine.observe(batch(source(1), QUALITY, face(500)));
        engine.register(source(3));
        engine.observe(batch(source(2), QUALITY, face(100)));
        positions(engine.snapshot(2, 310, false), 100);
        assertTrue(masks(engine.ready()).get(0).confirmed);
    }

    @Test public void pinnedExecutingSourcesPreventEvictionAndBoundAdmission() {
        SceneEngine engine = new SceneEngine(1, new SceneEngine.Policy(2, 100, 64, 256, 500, 5000, .5));
        engine.register(source(1)); engine.register(source(2)); assertTrue(engine.pin(source(1))); assertTrue(engine.pin(source(2)));
        assertFalse(engine.register(source(3))); assertEquals(2, engine.sourceCount());
        engine.unpin(source(2)); assertTrue(engine.register(source(3)));
        assertTrue(engine.observe(batch(source(1), FAST, face(500))));
        engine.unpin(source(1)); assertThrows(IllegalStateException.class, () -> engine.unpin(source(1)));
    }

    @Test public void structuralEpochRejectsOldResultsAndCorrespondences() {
        SceneEngine engine = engine(); register(engine, 2); engine.observe(batch(source(1), FAST, face(500)));
        engine.reset(2); assertNull(engine.ready());
        assertFalse(engine.observe(batch(source(2), FAST, face(100))));
        assertFalse(engine.motion(motion(1, 2, -400))); assertFalse(engine.register(source(3)));
    }

    @Test public void sourceSequenceCannotBeReusedWithDifferentDescriptor() {
        SceneEngine engine = engine(); register(engine, 1);
        SceneEngine.Source altered = new SceneEngine.Source(1, 1, 42, 1000, 2400, 999, -1);
        assertFalse(engine.register(altered)); assertFalse(engine.observe(batch(altered, FAST, face(500))));
    }

    @Test public void frameAndTrackBoundsRemainExplicit() {
        SceneEngine engine = new SceneEngine(1, new SceneEngine.Policy(2, 2, 64, 256, 500, 5000, .5));
        for (int i = 1; i <= 20; i++) {
            assertTrue(engine.register(source(i)));
            engine.observe(batch(source(i), FAST, face(500), face(900), face(1300)));
            assertTrue(engine.sourceCount() <= 2); assertTrue(engine.trackCount() <= 2);
        }
        assertFalse(engine.observe(batch(source(1), FAST, face(500))));
    }

    @Test public void unknownAndMalformedEvidenceNeverBecomesExactByDefault() {
        assertThrows(IllegalArgumentException.class, () -> new SceneEngine.Region(0, 0, Double.NaN, 100));
        assertThrows(IllegalArgumentException.class, () -> new SceneEngine.Motion(1, 2, 1, 1, 1,
                IMAGE_CORRESPONDENCE, SUPPORT, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new SceneEngine.Batch(source(1), FAST,
                Collections.emptyList(), Collections.singletonList(new SceneEngine.Inspection("FACE", 1, SUPPORT))));
    }

    @Test public void fastAndQualityIndicesCannotMergeDistinctSameFrameSubjects() {
        SceneEngine engine = engine(); register(engine, 1);
        engine.observe(batch(source(1), FAST, face(500), face(520)));
        engine.observe(batch(source(1), QUALITY, face(520)));
        positions(engine.snapshot(1, 110, false), 500, 520);
        assertEquals(2, engine.trackCount());
    }

    @Test public void oldMotionAndReleaseCannotMutateReusedSequenceInNewEpoch() {
        SceneEngine engine = engine(); register(engine, 2); assertTrue(engine.pin(source(1)));
        engine.reset(2);
        SceneEngine.Source fresh = new SceneEngine.Source(2, 1, 42, 1000, 2400, 100, -1);
        assertTrue(engine.register(fresh)); assertTrue(engine.pin(fresh));
        assertTrue(engine.register(new SceneEngine.Source(2, 2, 42, 1000, 2400, 200, -1)));
        assertFalse(engine.motion(motion(1, 2, -400)));
        assertFalse(engine.unpin(source(1)));
        assertTrue(engine.unpin(fresh));
    }

    @Test public void evictedSourceCannotBeReRegisteredToReplayEvidence() {
        SceneEngine engine = new SceneEngine(1, new SceneEngine.Policy(2, 2, 64, 256, 500, 5000, .5));
        register(engine, 3);
        assertFalse(engine.register(source(1)));
        assertFalse(engine.observe(batch(source(1), FAST, face(500))));
    }

    @Test public void admittedMeasuredCorrespondenceCannotKeepConfirmationUnderARevisedMap() {
        SceneEngine engine = engine(); register(engine, 2);
        engine.observe(batch(source(1), QUALITY, face(500)));
        engine.observe(batch(source(2), QUALITY, face(500)));
        engine.motion(motion(1, 2, 0));
        assertTrue(masks(engine.snapshot(2, 210, false)).get(0).confirmed);
        assertFalse(engine.motion(new SceneEngine.Motion(1, 1, 2, 1, 2,
                IMAGE_CORRESPONDENCE, SUPPORT, 0, -400)));
        // Rejecting the revision alone cannot preserve confirmation derived from contradicted authority.
        positions(engine.snapshot(2, 210, false));
    }

    @Test public void correspondenceAndBatchCapacityRejectionsAreVisibleWithoutClearingCoverage() {
        SceneEngine engine = new SceneEngine(1, new SceneEngine.Policy(4, 4, 1, 2, 500, 5000, .5));
        register(engine, 3); engine.observe(batch(source(1), FAST, face(500)));
        engine.motion(motion(1, 2, 0));
        assertFalse(engine.motion(motion(2, 3, 0)));
        assertFalse(engine.observe(batch(source(2), FAST, face(500), face(900), face(1300))));
        SceneEngine.Snapshot snapshot = engine.snapshot(2, 210, false);
        positions(snapshot, 500); assertEquals(2, snapshot.capacityDrops);
        // A rejected oversized result did not consume the valid source/lane delivery identity.
        assertTrue(engine.observe(batch(source(2), FAST, face(500))));
    }

    @Test public void nonFinitePolicyAndOverflowingInverseSupportAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new SceneEngine.Policy(2, 2, 2, 2, 500, 5000, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> new SceneEngine.Motion(1, 1, 2, 1, 1,
                IMAGE_CORRESPONDENCE, SUPPORT, Double.MAX_VALUE, 0));
    }

    @Test public void distinctCooccurringSubjectsStayDistinctAfterOriginalSourceEviction() {
        SceneEngine engine = new SceneEngine(1, new SceneEngine.Policy(2, 100, 64, 256, 500, 5000, .5));
        register(engine, 2);
        engine.observe(batch(source(1), FAST, face(500), face(520)));
        engine.motion(motion(1, 2, 0));
        engine.register(source(3)); engine.motion(motion(2, 3, 0));
        positions(engine.snapshot(3, 310, false), 500, 520);
        assertEquals(2, engine.trackCount());
    }

    @Test public void qualifiedNegativeWitnessesSurviveSourceEviction() {
        SceneEngine engine = new SceneEngine(1, new SceneEngine.Policy(2, 100, 64, 256, 500, 5000, .5));
        register(engine, 2); engine.pin(source(1)); engine.motion(motion(1, 2, 0));
        engine.observe(batch(source(1), QUALITY, face(500)));
        engine.observe(batch(source(2), QUALITY, face(500)));
        engine.register(source(3)); engine.motion(motion(1, 3, 0));
        engine.observe(emptyQuality(source(3), "FACE", 1, SUPPORT));
        positions(engine.snapshot(3, 310, false), 500);
        engine.register(source(4)); engine.motion(motion(1, 4, 0));
        engine.observe(emptyQuality(source(4), "FACE", 1, SUPPORT));
        positions(engine.snapshot(4, 410, false)); assertEquals(0, engine.trackCount());
    }

    @Test public void extremeFiniteTranslationThatLosesRegionPrecisionStaysUnknown() {
        SceneEngine engine = engine(); register(engine, 2);
        SceneEngine.Observation tiny = new SceneEngine.Observation("FACE", 1, 0,
                new SceneEngine.Region(0, 100, 1, 101), .9f);
        engine.observe(batch(source(1), FAST, tiny));
        engine.motion(new SceneEngine.Motion(1, 1, 2, 1, 1,
                IMAGE_CORRESPONDENCE, SUPPORT, 1e16, 0));
        assertEquals(1, engine.snapshot(2, 210, false).unresolvedTracks);
    }
}
