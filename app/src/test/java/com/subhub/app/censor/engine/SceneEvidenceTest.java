package com.subhub.app.censor.engine;

import static org.junit.Assert.*;
import static com.subhub.app.censor.engine.SceneEngine.Lane.*;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class SceneEvidenceTest {
    private static final SceneEngine.Region REGION = new SceneEngine.Region(100, 100, 200, 200);
    private static final SceneEngine.Region SUPPORT = new SceneEngine.Region(0, 0, 1000, 2000);
    private static final String CONTENT = String.join("", Collections.nCopies(64, "a"));
    private static final String NODE = String.join("", Collections.nCopies(64, "b"));

    private static SceneEngine scene(int tracks) {
        return new SceneEngine(1, new SceneEngine.Policy(16, tracks, 64, 256, 5000, 20000, .5));
    }
    private static SceneEngine.Source image(long sequence, long request) {
        return SceneEngine.Source.image(1, sequence, request, 42, 1000, 2000, sequence * 100, -1,
                ProtectionScope.normal(7));
    }
    private static SceneEngine.Batch positive(SceneEngine.Source source, SceneEngine.Lane lane) {
        return new SceneEngine.Batch(source, lane, Collections.singletonList(
                new SceneEngine.Observation("FACE", 1, 0, REGION, .9f)), Collections.emptyList());
    }
    private static void map(SceneEngine scene, long from, long to) {
        assertTrue(scene.motion(new SceneEngine.Motion(1, from, to, 1, 1,
                SceneEngine.MotionAuthority.IMAGE_CORRESPONDENCE, SUPPORT, 0, 0)));
    }
    private static SceneEngine.Source admit(SceneEngine scene, long sequence, boolean mapped) {
        SceneEngine.Source source = image(sequence, sequence);
        assertTrue(scene.register(source));
        if (mapped && sequence > 1) map(scene, sequence - 1, sequence);
        assertTrue(scene.observe(positive(source, FAST)));
        return source;
    }

    @Test public void fiveFreshFastSourcesQualifyWithoutQualityRetentionAndConsumeOnce() {
        SceneEngine scene = scene(100); SceneEngine.Source source = null;
        for (int i = 1; i <= 5; i++) {
            source = admit(scene, i, true);
            SceneEngine.AcceptedSubject subject = scene.acceptedSubjects(source, i * 100).get(0);
            assertEquals(i, subject.witnesses.size());
            assertEquals(i == 5, subject.confirmedForConsumption);
        }
        assertFalse(scene.snapshot(5, 500, false).groups.get(0).members.get(0).confirmed);
        List<SceneEngine.AcceptedSubject> subjects = scene.acceptedSubjects(source, 500);
        assertEquals(1, scene.consumeEvidence(source, subjects, 500).size());
        assertEquals(0, scene.consumeEvidence(source, subjects, 500).size());
        assertTrue(scene.acceptedSubjects(source, 500).get(0).alreadyConsumed);
        assertThrows(UnsupportedOperationException.class, () -> subjects.clear());
        assertThrows(UnsupportedOperationException.class, () -> subjects.get(0).witnesses.clear());
    }

    @Test public void lanesAndDuplicateDeliveriesAreNotDistinctSources() {
        SceneEngine scene = scene(100);
        SceneEngine.Source a = admit(scene, 1, false);
        assertTrue(scene.observe(positive(a, QUALITY)));
        assertFalse(scene.observe(positive(a, FAST)));
        assertEquals(1, scene.acceptedSubjects(a, 100).get(0).witnesses.size());
        SceneEngine.Source b = admit(scene, 2, true);
        assertTrue(scene.observe(positive(b, QUALITY)));
        assertTrue(scene.snapshot(2, 200, false).groups.get(0).members.get(0).confirmed);
        assertFalse(scene.acceptedSubjects(b, 200).get(0).confirmedForConsumption);
        assertEquals(2, scene.acceptedSubjects(b, 200).get(0).witnesses.size());
    }

    @Test public void aSequenceAliasOfOneImageRequestStillContributesOneWitness() {
        SceneEngine scene = scene(100); SceneEngine.Source source = null;
        for (int i = 1; i <= 5; i++) {
            source = image(i, 991);
            assertTrue(scene.register(source)); if (i > 1) map(scene, i - 1, i);
            assertTrue(scene.observe(positive(source, FAST)));
        }
        SceneEngine.AcceptedSubject subject = scene.acceptedSubjects(source, 500).get(0);
        assertEquals(1, subject.witnesses.size()); assertFalse(subject.confirmedForConsumption);
        assertEquals(991, subject.witnesses.get(0).producerIdentity);
    }

    @Test public void outOfOrderQualityCannotIncreaseFreshConfirmation() {
        SceneEngine scene = scene(100);
        SceneEngine.Source[] sources = new SceneEngine.Source[5];
        for (int i = 0; i < 5; i++) {
            sources[i] = image(i + 1, i + 1); assertTrue(scene.register(sources[i]));
            if (i > 0) map(scene, i, i + 1);
        }
        assertTrue(scene.observe(positive(sources[0], FAST)));
        assertTrue(scene.observe(positive(sources[4], FAST)));
        for (int i = 1; i < 4; i++) assertTrue(scene.observe(positive(sources[i], QUALITY)));
        assertEquals(2, scene.acceptedSubjects(sources[4], 500).get(0).witnesses.size());
        assertTrue(scene.acceptedSubjects(sources[1], 500).isEmpty());
    }

    @Test public void unknownMapsDoNotConfirmAndLateMergeUnionsExistingWitnesses() {
        SceneEngine scene = scene(100); SceneEngine.Source source = null;
        for (int i = 1; i <= 5; i++) {
            source = admit(scene, i, false);
            assertEquals(1, scene.acceptedSubjects(source, 500).get(0).witnesses.size());
        }
        List<SceneEngine.AcceptedSubject> removedAlias = scene.acceptedSubjects(source, 500);
        for (int i = 1; i < 5; i++) map(scene, i, i + 1);
        assertNull(scene.consumeEvidence(source, removedAlias, 500));
        source = admit(scene, 6, true);
        SceneEngine.AcceptedSubject canonical = scene.acceptedSubjects(source, 600).get(0);
        assertEquals(1, canonical.identity); assertEquals(5, canonical.witnesses.size());
        assertEquals(1, scene.consumeEvidence(source, Collections.singletonList(canonical), 600).size());
    }

    @Test public void consumedAliasMergingIntoAnOlderUnconsumedIdentityCannotConsumeAgain() {
        SceneEngine scene = scene(100);
        admit(scene, 1, false); // Older unresolved identity survives the later canonical merge.
        SceneEngine.Source source = admit(scene, 2, false);
        for (int i = 3; i <= 6; i++) source = admit(scene, i, true);
        assertEquals(2, scene.acceptedSubjects(source, 600).get(0).identity);
        assertEquals(1, scene.consumeEvidence(source, scene.acceptedSubjects(source, 600), 600).size());
        map(scene, 1, 2);
        source = admit(scene, 7, true);
        SceneEngine.AcceptedSubject canonical = scene.acceptedSubjects(source, 700).get(0);
        assertEquals(1, canonical.identity); assertTrue(canonical.alreadyConsumed);
        assertEquals(0, scene.consumeEvidence(source, Collections.singletonList(canonical), 700).size());
    }

    @Test public void positiveSubjectsObservedTogetherRemainDistinct() {
        SceneEngine scene = scene(100); SceneEngine.Source source = null;
        for (int i = 1; i <= 5; i++) {
            source = image(i, i); assertTrue(scene.register(source)); if (i > 1) map(scene, i - 1, i);
            SceneEngine.Observation first = positive(source, FAST).observations.get(0);
            assertTrue(scene.observe(new SceneEngine.Batch(source, FAST, Arrays.asList(first, first), Collections.emptyList())));
        }
        List<SceneEngine.AcceptedSubject> subjects = scene.acceptedSubjects(source, 500);
        assertEquals(2, subjects.size()); assertNotEquals(subjects.get(0).identity, subjects.get(1).identity);
        assertEquals(2, scene.consumeEvidence(source, subjects, 500).size());
    }

    @Test public void negativeRetirementAndFiniteExpiryRejectOldSubjectClaims() {
        SceneEngine scene = scene(100); SceneEngine.Source source = null;
        for (int i = 1; i <= 5; i++) source = admit(scene, i, true);
        List<SceneEngine.AcceptedSubject> subjects = scene.acceptedSubjects(source, 500);
        assertEquals(5500, subjects.get(0).validUntilMillis);
        assertNull(scene.consumeEvidence(source, subjects, 5501));
        SceneEngine.Source negative = image(6, 6); assertTrue(scene.register(negative)); map(scene, 5, 6);
        assertTrue(scene.observe(new SceneEngine.Batch(negative, QUALITY, Collections.emptyList(),
                Collections.singletonList(new SceneEngine.Inspection("FACE", 1, SUPPORT)))));
        assertNull(scene.consumeEvidence(source, subjects, 600));
        assertTrue(scene.acceptedSubjects(negative, 600).isEmpty());
    }

    @Test public void validationIsAtomicAcrossRetiredAndSurvivingIdentities() {
        SceneEngine scene = scene(2); SceneEngine.Source source = null;
        SceneEngine.Region second = new SceneEngine.Region(400, 100, 500, 200);
        for (int i = 1; i <= 5; i++) {
            source = image(i, i); assertTrue(scene.register(source)); if (i > 1) map(scene, i - 1, i);
            assertTrue(scene.observe(new SceneEngine.Batch(source, FAST, Arrays.asList(
                    positive(source, FAST).observations.get(0), new SceneEngine.Observation("OTHER", 1, 0, second, .9f)), Collections.emptyList())));
        }
        List<SceneEngine.AcceptedSubject> subjects = scene.acceptedSubjects(source, 500);
        SceneEngine.Source next = image(6, 6); assertTrue(scene.register(next)); map(scene, 5, 6);
        assertTrue(scene.observe(new SceneEngine.Batch(next, QUALITY, Collections.emptyList(),
                Collections.singletonList(new SceneEngine.Inspection("OTHER", 1, SUPPORT)))));
        assertNull(scene.consumeEvidence(source, subjects, 600));
        assertEquals(1, scene.consumeEvidence(source, Collections.singletonList(subjects.get(0)), 600).size());
    }

    @Test public void nativeReadsNeedRealRequestProvenanceAndPreserveNodeContentContinuity() {
        SceneEngine scene = scene(100); SceneEngine.Source source = null;
        for (int i = 1; i <= 5; i++) {
            source = nativeSource(i);
            assertTrue(scene.admitNativeFrame(nativeFrame(source, i, CONTENT, NODE)));
        }
        SceneEngine.AcceptedSubject subject = scene.acceptedSubjects(source, 500).get(0);
        assertEquals(5, subject.witnesses.size()); assertTrue(subject.confirmedForConsumption);
        assertEquals(SceneEngine.SourceKind.NATIVE_TEXT, subject.witnesses.get(0).kind);
        assertEquals(1, scene.consumeEvidence(source, Collections.singletonList(subject), 500).size());
        source = nativeSource(6);
        assertTrue(scene.admitNativeFrame(nativeFrame(source, 6, NODE, NODE)));
        assertNotEquals(subject.identity, scene.acceptedSubjects(source, 600).get(0).identity);
        assertEquals(1, scene.acceptedSubjects(source, 600).get(0).witnesses.size());
        assertNull(scene.consumeEvidence(subject.source, Collections.singletonList(subject), 600));
    }

    @Test public void separateNodeObservationsPreserveOnlyCompletedHistoryAndTheirOwnValidationAges() {
        SceneEngine scene = scene(100);
        String secondNode = String.join("", Collections.nCopies(64, "c"));
        NativeTextFrame prior = null;
        for (int i = 0; i < 3; i++) {
            long start = i * 300L;
            prior = accountingFrame(i + 1, i + 1, start, start + 90, 0,
                    accountingCandidate(NODE, start + 65), accountingCandidate(secondNode, start + 65));
            assertTrue(scene.admitNativeFrame(prior));
        }
        List<SceneEngine.AcceptedSubject> before = scene.acceptedSubjects(prior.source, 700);
        assertEquals(2, before.size()); assertEquals(3, before.get(0).witnesses.size());
        NativeTextFrame first = accountingFrame(4, 4, 900, 970, 1, accountingCandidate(NODE, 965));
        assertTrue(scene.admitNativeFrame(first));
        SceneEngine.AcceptedSubject a = scene.acceptedSubjects(first.source, 980).get(0);
        assertTrue(a.witnesses.isEmpty()); assertFalse(a.confirmedForConsumption); assertEquals(1965, a.validUntilMillis);
        assertFalse(scene.hasNativeTextCoverage(2, 980));
        SceneEngine.Snapshot intermediate = scene.snapshot(4, 980, false);
        assertEquals(2, intermediate.groups.size());
        for (SceneEngine.Group group : intermediate.groups) {
            SceneEngine.Mask mask = group.members.get(0);
            if (mask.evidenceSource == first.source) assertFalse(mask.dwellPositive);
            else assertSame(prior.source, mask.evidenceSource);
        }
        NativeTextFrame second = accountingFrame(5, 4, 900, 1600, 2, accountingCandidate(secondNode, 1595));
        assertTrue(scene.admitNativeFrame(second));
        assertTrue(scene.acceptedSubjects(second.source, 1610).get(0).witnesses.isEmpty());
        NativeTextFrame complete = accountingFrame(6, 4, 900, 1700, 0,
                first.read.candidates.get(0), second.read.candidates.get(0));
        assertTrue(scene.admitNativeFrame(complete));
        List<SceneEngine.AcceptedSubject> after = scene.acceptedSubjects(complete.source, 1710);
        assertEquals(2, after.size());
        for (SceneEngine.AcceptedSubject subject : after) {
            assertEquals(4, subject.witnesses.size()); assertEquals(4, subject.witnesses.get(3).producerIdentity);
            assertEquals(complete.source.sequence, subject.witnesses.get(3).sourceSequence);
            assertEquals(subject.region.left == 100 ? 1965 : 2595, subject.validUntilMillis);
            assertEquals(subject.region.left == 100 ? before.get(0).identity : before.get(1).identity, subject.identity);
            assertFalse(subject.confirmedForConsumption);
        }
        assertEquals(1966, scene.snapshot(6, 1710, false).nextChangeAtMillis);
        assertEquals(1, scene.snapshot(6, 1966, false).groups.size());
    }

    private static NativeTextResult.Candidate accountingCandidate(String node, long validated) {
        SceneEngine.Region region = node.equals(NODE) ? REGION : new SceneEngine.Region(400, 100, 500, 200);
        return new NativeTextResult.Candidate("TEXT", CONTENT, node, .9f, true, Collections.singletonList(region),
                NativeTextResult.TextSource.TEXT, 0, 4, 4, validated);
    }
    private static NativeTextFrame accountingFrame(long sequence, long request, long start, long end, int ordinal,
            NativeTextResult.Candidate... candidates) {
        NativeTextResult read = new NativeTextResult(1000, 2000, 7, start, end, Arrays.asList(candidates),
                candidates.length, candidates.length, candidates.length,
                ordinal > 0 ? NativeTextResult.Coverage.POSITIVE_NODES_ONLY : NativeTextResult.Coverage.COMPLETE_TREE,
                1, 1, 1, start + 10, start + 58);
        SceneEngine.Source source = SceneEngine.Source.nativeText(1, sequence, 42, 1000, 2000, 7, start, end, end + 10,
                ProtectionScope.normal(7));
        return new NativeTextFrame(source, 2, read, 1000, request, start, start, ordinal);
    }

    @Test public void repeatedNativeProducerAndUnknownReadLineageCannotConfirm() {
        SceneEngine scene = scene(100); SceneEngine.Source source = null;
        for (int i = 1; i <= 5; i++) {
            source = nativeSource(i); assertTrue(scene.admitNativeFrame(nativeFrame(source, 9, CONTENT, NODE)));
        }
        assertEquals(1, scene.acceptedSubjects(source, 500).get(0).witnesses.size());
        scene.clearNative(2);
        for (int i = 6; i <= 10; i++) {
            source = nativeSource(i);
            NativeTextFrame frame = nativeFrame(source, i, CONTENT, NODE);
            assertTrue(scene.admitNativeFrame(new NativeTextFrame(source, 2, frame.read, 1000)));
        }
        assertEquals(0, scene.acceptedSubjects(source, 1000).get(0).witnesses.size());
        assertFalse(scene.acceptedSubjects(source, 1000).get(0).confirmedForConsumption);
    }

    @Test public void completeOcrReplacementDoesNotInventFiveSourceIdentity() {
        SceneEngine scene = scene(100); long previous = 0;
        for (int i = 1; i <= 5; i++) {
            SceneEngine.Source source = image(i, i); assertTrue(scene.register(source));
            assertTrue(scene.observe(SceneEngine.Batch.imageText(source, 3, Collections.singletonList(
                    new SceneEngine.Observation("TEXT", 3, 1, REGION, .9f)))));
            SceneEngine.AcceptedSubject subject = scene.acceptedSubjects(source, i * 100).get(0);
            assertTrue(subject.identity > previous); previous = subject.identity;
            assertEquals(1, subject.witnesses.size()); assertFalse(subject.confirmedForConsumption);
        }
    }

    @Test public void witnessMemoryStaysBoundedAndLongIdsAreNotNarrowed() throws Exception {
        SceneEngine scene = scene(100);
        Field counter = SceneEngine.class.getDeclaredField("nextIdentity"); counter.setAccessible(true);
        counter.setLong(scene, Integer.MAX_VALUE);
        SceneEngine.Source source = null;
        for (int i = 1; i <= 32; i++) source = admit(scene, i, true);
        assertNull(scene.source(1)); // Real metadata eviction does not erase accumulated facts.
        SceneEngine.AcceptedSubject subject = scene.acceptedSubjects(source, 3200).get(0);
        assertEquals((long) Integer.MAX_VALUE + 1, subject.identity);
        assertEquals(5, subject.witnesses.size());
        assertEquals(subject.identity, scene.consumeEvidence(source, Collections.singletonList(subject), 3200).get(0).identity);
    }

    @Test public void capacityRetirementCannotLeaveAConsumableOldIdentity() {
        SceneEngine scene = scene(1); SceneEngine.Source source = null;
        for (int i = 1; i <= 5; i++) source = admit(scene, i, true);
        List<SceneEngine.AcceptedSubject> old = scene.acceptedSubjects(source, 500);
        SceneEngine.Source next = image(6, 6); assertTrue(scene.register(next));
        assertTrue(scene.observe(new SceneEngine.Batch(next, FAST, Collections.singletonList(
                new SceneEngine.Observation("OTHER", 1, 0, REGION, .9f)), Collections.emptyList())));
        assertNull(scene.consumeEvidence(source, old, 600));
        SceneEngine.AcceptedSubject subject = scene.acceptedSubjects(next, 600).get(0);
        assertNotEquals(old.get(0).identity, subject.identity); assertEquals(1, subject.witnesses.size());
    }

    @Test public void exactLifetimeBoundaryRemainsValidAndEpochResetNeverReusesIdentity() {
        SceneEngine scene = scene(100); SceneEngine.Source source = null;
        for (int i = 1; i <= 5; i++) source = admit(scene, i, true);
        List<SceneEngine.AcceptedSubject> old = scene.acceptedSubjects(source, 500);
        assertEquals(1, scene.consumeEvidence(source, old, old.get(0).validUntilMillis).size());
        scene.reset(2); assertNull(scene.consumeEvidence(source, old, 500));
        SceneEngine.Source fresh = SceneEngine.Source.image(2, 1, 1, 42, 1000, 2000, 600, -1);
        assertTrue(scene.register(fresh)); assertTrue(scene.observe(positive(fresh, FAST)));
        assertTrue(scene.acceptedSubjects(fresh, 600).get(0).identity > old.get(0).identity);
    }

    @Test public void sameSourceQualityRefinementCannotKeepAnOffscreenOldReceiptConsumable() {
        SceneEngine scene = scene(100); SceneEngine.Source source = null;
        SceneEngine.Region partial = new SceneEngine.Region(-200, 10, 10, 210);
        for (int i = 1; i <= 5; i++) {
            source = image(i, i); assertTrue(scene.register(source));
            if (i > 1) assertTrue(scene.motion(new SceneEngine.Motion(1, i - 1, i, 1, 1,
                    SceneEngine.MotionAuthority.IMAGE_CORRESPONDENCE,
                    new SceneEngine.Region(-10000, -10000, 10000, 10000), 0, 0)));
            assertTrue(scene.observe(new SceneEngine.Batch(source, FAST, Collections.singletonList(
                    new SceneEngine.Observation("FACE", 1, 0, partial, .9f)), Collections.emptyList())));
        }
        List<SceneEngine.AcceptedSubject> old = scene.acceptedSubjects(source, 500);
        assertTrue(old.get(0).confirmedForConsumption);
        assertTrue(scene.observe(new SceneEngine.Batch(source, QUALITY, Collections.singletonList(
                new SceneEngine.Observation("FACE", 1, 0, new SceneEngine.Region(-200, 10, 0, 210), .9f)), Collections.emptyList())));
        assertTrue(scene.acceptedSubjects(source, 500).isEmpty());
        assertNull(scene.consumeEvidence(source, old, 500));
    }

    @Test public void sameSourceVisibleRefinementNeedsACurrentReceiptWithoutAnotherWitness() {
        SceneEngine scene = scene(100); SceneEngine.Source source = null;
        for (int i = 1; i <= 5; i++) source = admit(scene, i, true);
        List<SceneEngine.AcceptedSubject> old = scene.acceptedSubjects(source, 500);
        assertTrue(scene.observe(new SceneEngine.Batch(source, QUALITY, Collections.singletonList(
                new SceneEngine.Observation("FACE", 1, 0, new SceneEngine.Region(100, 101, 200, 201), .9f)), Collections.emptyList())));
        assertNull(scene.consumeEvidence(source, old, 500));
        List<SceneEngine.AcceptedSubject> current = scene.acceptedSubjects(source, 500);
        assertEquals(5, current.get(0).witnesses.size()); assertEquals(QUALITY, current.get(0).evidenceLane);
        assertEquals(1, scene.consumeEvidence(source, current, 500).size());
    }

    private static SceneEngine.Source nativeSource(long sequence) {
        return SceneEngine.Source.nativeText(1, sequence, 42, 1000, 2000, 7,
                sequence * 100 - 10, sequence * 100 - 5, sequence * 100, ProtectionScope.normal(7));
    }
    private static NativeTextFrame nativeFrame(SceneEngine.Source source, long request, String content, String node) {
        NativeTextResult read = new NativeTextResult(1000, 2000, 7, source.readStartedAtMillis, source.readFinishedAtMillis,
                Collections.singletonList(new NativeTextResult.Candidate("TEXT", content, node, .9f, true,
                        Collections.singletonList(REGION))), 1, 1, 1);
        return new NativeTextFrame(source, 2, read, 1000, request, source.readStartedAtMillis, source.readStartedAtMillis);
    }
}
