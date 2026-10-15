package com.subhub.app.censor.engine;

import static org.junit.Assert.*;

import java.util.AbstractList;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

public class NativeTextFrameTest {
    @Test public void cohortOneMemberIsOnlyObservationAndTwoMembersAreOnlyTerminal() {
        NativeTextResult early = cohort(proved(C, 190)), terminal = cohort(proved(C, 190), proved(D, 290));
        SceneEngine.Source source = SceneEngine.Source.nativeText(1, 2, 42, 1000, 2400, 7, 100, 300, 300);
        assertFalse(early.terminalInventoryValid()); assertTrue(early.observationInventoryValid(1));
        assertFalse(early.observationInventoryValid(2)); assertFalse(terminal.observationInventoryValid(1));
        assertThrows(IllegalArgumentException.class, () -> new NativeTextFrame(source, 2, early, 1000, 1, 90, 95));
        assertThrows(IllegalArgumentException.class, () -> new NativeTextFrame(source, 2, early, 1000, 1, 90, 95, 2));
        assertThrows(IllegalArgumentException.class, () -> new NativeTextFrame(source, 2, terminal, 1000, 1, 90, 95, 1));
        NativeTextFrame observed = new NativeTextFrame(source, 2, early, 1000, 1, 90, 95, 1);
        NativeTextFrame completed = new NativeTextFrame(SceneEngine.Source.nativeText(1, 3, 42, 1000, 2400, 7, 100, 300, 300),
                2, terminal, 1000, 1, 90, 95);
        assertTrue(completed.followsObservation(observed)); assertFalse(observed.followsObservation(completed));
        assertEquals(1190, completed.candidateValidUntilMillis(terminal.candidates.get(0)));
        assertEquals(1290, completed.candidateValidUntilMillis(terminal.candidates.get(1)));
    }

    @Test public void cohortTerminalCannotOmitItsObservedNodeEvenAfterTrackExpiry() {
        for (boolean prune : new boolean[]{false, true}) {
            SceneEngine scene = scene(16, 100); scene.register(image(1));
            NativeTextFrame first = new NativeTextFrame(SceneEngine.Source.nativeText(1, 2, 42, 1000, 2400, 7, 100, 300, 300),
                    2, cohort(proved(C, 190)), 1000, 1, 90, 95, 1);
            assertTrue(scene.admitNativeFrame(first));
            if (prune) assertTrue(scene.snapshot(1, 1300, false).groups.isEmpty());
            NativeTextResult foreign = new NativeTextResult(1000, 2400, 7, 100, 1400,
                    Arrays.asList(proved(D, 1350), proved(B, 1360)), 2, 2, 2, NativeTextResult.Coverage.POSITIVE_NODES_ONLY,
                    1, 1, 1, 120, 168, NativeTextResult.ReadMode.ADMITTED_NODE_COHORT_RECLASSIFY);
            NativeTextFrame terminal = new NativeTextFrame(SceneEngine.Source.nativeText(1, 3, 42, 1000, 2400, 7, 100, 1400, 1400),
                    2, foreign, 1000, 1, 90, 95);
            assertFalse(scene.admitNativeFrame(terminal)); assertNull(scene.source(3));
        }
    }

    @Test public void cohortCannotInventCompleteTreeDuplicateNodesOrDifferentSelectedInventory() {
        for (int[] counts : new int[][]{{1, 1, 1}, {2, 1, 1}, {3, 2, 1}, {2, 2, 0}, {2, 2, 2}}) {
            assertThrows(IllegalArgumentException.class, () -> new NativeTextResult(1000, 2400, 7, 100, 300,
                    Collections.singletonList(proved(C, 190)), counts[0], counts[1], counts[2], NativeTextResult.Coverage.POSITIVE_NODES_ONLY,
                    1, 1, 1, 120, 168, NativeTextResult.ReadMode.ADMITTED_NODE_COHORT_RECLASSIFY));
        }
        assertThrows(IllegalArgumentException.class, () -> cohort(proved(C, 190), proved(C, 290)));
        assertThrows(IllegalArgumentException.class, () -> new NativeTextResult(1000, 2400, 7, 100, 300,
                Arrays.asList(proved(C, 190), proved(D, 290)), 2, 2, 2, NativeTextResult.Coverage.COMPLETE_TREE,
                1, 1, 1, 120, 168, NativeTextResult.ReadMode.ADMITTED_NODE_COHORT_RECLASSIFY));
    }

    private static NativeTextResult cohort(NativeTextResult.Candidate... candidates) {
        return new NativeTextResult(1000, 2400, 7, 100, 300, Arrays.asList(candidates), 2, 2, candidates.length,
                NativeTextResult.Coverage.POSITIVE_NODES_ONLY, 1, 1, 1, 120, 168,
                NativeTextResult.ReadMode.ADMITTED_NODE_COHORT_RECLASSIFY);
    }

    @Test public void overlappingSameParentCompleteCannotWithdrawAnObservedUnexpiredSibling() {
        SceneEngine scene = scene(16, 100); scene.register(image(1));
        assertTrue(scene.admitNativeFrame(observationFrame(2, 1, 300, 190, C, 1000)));
        NativeTextFrame b = observationFrame(3, 2, 400, 390, D, 1200); assertTrue(scene.admitNativeFrame(b));
        NativeTextResult r = b.read;
        NativeTextResult incomplete = new NativeTextResult(1000, 2400, 7, 100, 500, r.candidates, 2, 2, 1,
                NativeTextResult.Coverage.COMPLETE_TREE, 1, 1, 1, 120, 168);
        NativeTextFrame finalB = new NativeTextFrame(SceneEngine.Source.nativeText(1, 4, 42, 1000, 2400, 7,
                100, 500, 500), 2, incomplete, 1000, 1, 90, 95);
        assertFalse(scene.admitNativeFrame(finalB)); assertNull(scene.source(4));
        assertEquals(2, scene.snapshot(1, 500, false).groups.size()); assertFalse(scene.hasNativeTextCoverage(2, 500));
    }

    @Test public void overlappingNativeObservationsRequireExactParentClocksAndIncreasingOrdinals() {
        for (String wrong : new String[]{"request", "queued", "started", "hard", "scan", "ordinal", "scope"}) {
            SceneEngine scene = scene(16, 100); scene.register(image(1));
            NativeTextFrame first = observationFrame(2, 1, 300, 190, C, 1000);
            assertTrue(scene.admitNativeFrame(first));
            NativeTextFrame next = observationFrame(3, wrong.equals("ordinal") ? 1 : 2, 400, 390, D, 1200);
            NativeTextResult r = next.read;
            if (wrong.equals("hard") || wrong.equals("scan")) r = new NativeTextResult(1000, 2400, 7, 100, 400,
                    r.candidates, 1, 1, 1, r.coverage, wrong.equals("scan") ? 2 : 1, 2,
                    wrong.equals("hard") ? 2 : 1, 120, 168);
            SceneEngine.Source s = wrong.equals("scope") ? SceneEngine.Source.nativeText(1, 3, 42, 1000, 2400, 7,
                    100, 400, 400, ProtectionScope.normal(10)) : next.source;
            NativeTextFrame foreign = new NativeTextFrame(s, 2, r, 1000, wrong.equals("request") ? 2 : 1,
                    wrong.equals("queued") ? 89 : 90, wrong.equals("started") ? 96 : 95, next.observationOrdinal);
            assertFalse(wrong, scene.admitNativeFrame(foreign)); assertNull(scene.source(3));
            assertTrue(wrong, scene.admitNativeFrame(observationFrame(4, 2, 400, 390, D, 1200)));
            assertEquals(2, scene.snapshot(1, 400, false).groups.size());
        }
    }

    @Test public void finalParentResultCannotRedateOrReposeAnEarlierValidationAndClosesObservationPrivilege() {
        for (String changed : new String[]{"older", "pose", "content", "none"}) {
            SceneEngine scene = scene(16, 100); scene.register(image(1));
            NativeTextFrame first = observationFrame(2, 1, 300, 190, C, 1000); assertTrue(scene.admitNativeFrame(first));
            NativeTextResult.Candidate candidate = new NativeTextResult.Candidate("TEXT_SMUT", changed.equals("content") ? B : A,
                    C, .9f, true, Collections.singletonList(region(changed.equals("pose") ? 900 : 1000)),
                    NativeTextResult.TextSource.TEXT, 0, 4, 4, changed.equals("older") ? 185 : 190);
            NativeTextResult full = new NativeTextResult(1000, 2400, 7, 100, 400, Collections.singletonList(candidate), 1, 1, 1,
                    NativeTextResult.Coverage.COMPLETE_TREE, 1, 1, 1, 120, 168);
            NativeTextFrame terminal = new NativeTextFrame(SceneEngine.Source.nativeText(1, 3, 42, 1000, 2400, 7,
                    100, 400, 400), 2, full, 1000, 1, 90, 95);
            assertEquals(changed, changed.equals("none"), scene.admitNativeFrame(terminal));
            if (changed.equals("none")) {
                scene.snapshot(1, 400, false);
                assertEquals(1191, scene.ready().nextChangeAtMillis);
                assertFalse(scene.admitNativeFrame(observationFrame(4, 2, 500, 490, D, 1200)));
            }
        }
    }

    @Test public void nonterminalNativeFrameCannotBorrowWholeTreeOrUnboundParentAuthority() {
        NativeTextFrame original = observationFrame(2, 1, 300, 190, C, 1000);
        assertThrows(IllegalArgumentException.class, () -> new NativeTextFrame(original.source, 2, original.read, 1000, -1, -1, -1, 1));
        assertThrows(IllegalArgumentException.class, () -> new NativeTextFrame(original.source, 2, original.read, 1000, 1, 90, 95, 65));
        NativeTextResult r = original.read;
        NativeTextResult complete = new NativeTextResult(1000, 2400, 7, 100, 300, r.candidates, 1, 1, 1,
                NativeTextResult.Coverage.COMPLETE_TREE, 1, 1, 1, 120, 168);
        assertThrows(IllegalArgumentException.class, () -> new NativeTextFrame(original.source, 2, complete, 1000, 1, 90, 95, 1));
        NativeTextResult direct = new NativeTextResult(1000, 2400, 7, 100, 300, r.candidates, 1, 1, 1,
                r.coverage, 1, 1, 1, 120, 168, NativeTextResult.ReadMode.ADMITTED_NODE_RECLASSIFY);
        assertThrows(IllegalArgumentException.class, () -> new NativeTextFrame(original.source, 2, direct, 1000, 1, 90, 95, 1));
    }

    private static NativeTextFrame observationFrame(long sequence, int ordinal, long finished, long validated, String node, double y) {
        NativeTextResult.Candidate candidate = new NativeTextResult.Candidate("TEXT_SMUT", A, node, .9f, true,
                Collections.singletonList(region(y)), NativeTextResult.TextSource.TEXT, 0, 4, 4, validated);
        NativeTextResult read = new NativeTextResult(1000, 2400, 7, 100, finished, Collections.singletonList(candidate), 1, 1, 1,
                NativeTextResult.Coverage.POSITIVE_NODES_ONLY, 1, 1, 1, 120, 168);
        return new NativeTextFrame(SceneEngine.Source.nativeText(1, sequence, 42, 1000, 2400, 7, 100, finished, finished),
                2, read, 1000, 1, 90, 95, ordinal);
    }

    @Test public void directReadHasKnownSingleNodePartialProvenanceEvenWhenTreeRevisionsAgree() {
        NativeTextResult read = new NativeTextResult(1000, 2400, 7, 100, 300,
                Collections.singletonList(proved(C, 190)), 1, 1, 1, NativeTextResult.Coverage.POSITIVE_NODES_ONLY,
                1, 1, 1, 120, 168, NativeTextResult.ReadMode.ADMITTED_NODE_RECLASSIFY);
        assertEquals(NativeTextResult.ReadMode.ADMITTED_NODE_RECLASSIFY, read.readMode);
        assertEquals(NativeTextResult.Coverage.POSITIVE_NODES_ONLY, read.coverage);
        assertThrows(IllegalArgumentException.class, () -> new NativeTextResult(1000, 2400, 7, 100, 300,
                Collections.singletonList(proved(C, 190)), 1, 1, 1, NativeTextResult.Coverage.COMPLETE_TREE,
                1, 1, 1, 120, 168, NativeTextResult.ReadMode.ADMITTED_NODE_RECLASSIFY));
    }

    @Test public void directReadCannotInventAVisitedTreeOrAnEmptySelectedNode() {
        assertThrows(IllegalArgumentException.class, () -> new NativeTextResult(1000, 2400, 7, 100, 300,
                Collections.singletonList(proved(C, 190)), 5, 1, 1, NativeTextResult.Coverage.POSITIVE_NODES_ONLY,
                1, 1, 1, 120, 168, NativeTextResult.ReadMode.ADMITTED_NODE_RECLASSIFY));
        assertThrows(IllegalArgumentException.class, () -> new NativeTextResult(1000, 2400, 7, 100, 300,
                Collections.emptyList(), 1, 1, 1, NativeTextResult.Coverage.POSITIVE_NODES_ONLY,
                1, 1, 1, 120, -1, NativeTextResult.ReadMode.ADMITTED_NODE_RECLASSIFY));
    }
    @Test public void laterAdmissionCannotReorderAnOlderNodeValidationBehindALaterFrameFinish() {
        SceneEngine scene = scene(16, 100); scene.register(image(1));
        NativeTextResult original = partial(100, 120, 168, 300, proved(C, 290));
        NativeTextFrame first = new NativeTextFrame(SceneEngine.Source.nativeText(1, 2, 42, 1000, 2400, 7,
                100, 300, 300), 2, original, 1000);
        assertTrue(scene.admitNativeFrame(first));
        NativeTextResult olderNode = partial(100, 120, 168, 400, proved(C, 190));
        NativeTextFrame later = new NativeTextFrame(SceneEngine.Source.nativeText(1, 3, 42, 1000, 2400, 7,
                100, 400, 400), 2, olderNode, 1000);
        assertFalse(scene.admitNativeFrame(later)); assertNull(scene.source(3));
        assertSame(first.source, nativeMask(scene.snapshot(1, 400, false)).evidenceSource);
        assertEquals(1291, scene.ready().nextChangeAtMillis);
    }

    @Test public void typedCompleteCannotClaimValidationBeforeConfirmationOrBorrowUnknownClocks() {
        assertThrows(IllegalArgumentException.class, () -> new NativeTextResult(1000, 2400, 7, 100, 300,
                Collections.singletonList(proved(C, 110)), 1, 1, 1, NativeTextResult.Coverage.COMPLETE_TREE,
                1, 1, 1, 120, 168));
        assertThrows(IllegalArgumentException.class, () -> new NativeTextResult(1000, 2400, 7, 100, 300,
                Collections.singletonList(proved(C, 190)), 1, 1, 1, NativeTextResult.Coverage.COMPLETE_TREE,
                1, 1, 1, -1, -1));
        assertThrows(IllegalArgumentException.class, () -> new NativeTextResult(1000, 2400, 7, 100, 300,
                Collections.singletonList(proved(C, 190)), 1, 1, 1, NativeTextResult.Coverage.COMPLETE_TREE,
                -1, -1, -1, 120, 168));
    }

    @Test public void oneOriginSharedByTwoNativePartitionsOwnsOneScenePinNotOnePerAdmission() {
        SceneEngine scene = scene(2, 100); scene.register(image(1)); scene.pin(image(1));
        NativeTextFrame original = typedFrame(2, false, typedCandidate(C, A, 2, 1000));
        NativeTextFrame other = new NativeTextFrame(original.source, 3, original.read, 1000,
                original.requestIdentity, original.queuedAtMillis, original.startedAtMillis);
        assertTrue(scene.admitNativeFrame(original)); assertTrue(scene.admitNativeFrame(other));
        assertTrue(scene.unpin(original.source)); assertTrue(scene.unpin(original.source));
        scene.clearNative(2); assertFalse(scene.register(image(3)));
        scene.clearNative(3); assertTrue(scene.register(image(3)));
    }

    @Test public void partialAdmissionRetainsUnseenNodeOriginIdentityAndUnchangedExpiry() {
        SceneEngine scene = scene(16, 100); scene.register(image(1));
        NativeTextFrame full = typedFrame(2, false, typedCandidate(C, A, 2, 1000), typedCandidate(D, A, 2, 1200));
        assertTrue(scene.admitNativeFrame(full));
        SceneEngine.Mask unseen = nodeAt(scene.snapshot(1, 500, false), 1200);
        assertTrue(scene.admitNativeFrame(typedFrame(3, true, typedCandidate(C, A, 3, 800))));
        SceneEngine.Snapshot after = scene.snapshot(1, 700, false);
        SceneEngine.Mask retained = nodeAt(after, 1200);
        assertEquals(unseen.identity, retained.identity); assertEquals(unseen.textGroup, retained.textGroup);
        assertSame(unseen.evidenceSource, retained.evidenceSource); assertSame(unseen.evidenceRegion, retained.evidenceRegion);
        assertEquals(2, after.groups.size()); assertFalse(scene.hasNativeTextCoverage(2, 700));
        SceneEngine.Snapshot expired = scene.snapshot(1, 1461, false);
        assertEquals(1, expired.groups.size()); assertEquals(800, nativeMask(expired).displayRegion.top, 0);
    }

    @Test public void partialSelectedNodeReplacesObsoleteContentAndEveryOldReflowLine() {
        SceneEngine scene = scene(16, 100); scene.register(image(1));
        NativeTextResult.Candidate old = new NativeTextResult.Candidate("TEXT_SMUT", A, C, .9f, true,
                Arrays.asList(region(1000), region(1200)), NativeTextResult.TextSource.TEXT, 0, 4, 4, 460);
        assertTrue(scene.admitNativeFrame(typedFrame(2, false, old, typedCandidate(D, A, 2, 1400))));
        SceneEngine.Mask first = nodeAt(scene.snapshot(1, 500, false), 1000);
        assertTrue(scene.admitNativeFrame(typedFrame(3, true, typedCandidate(C, B, 3, 800))));
        SceneEngine.Snapshot after = scene.snapshot(1, 700, false);
        assertEquals(2, after.groups.size()); assertEquals(2, scene.trackCount());
        assertNotEquals(first.identity, nodeAt(after, 800).identity);
        assertNotEquals(first.textGroup, nodeAt(after, 800).textGroup);
        nodeAt(after, 1400);
    }

    @Test public void emptyPartialDoesNotRegisterAnOriginReplaceCoverageOrRetireAnyNode() {
        SceneEngine scene = scene(16, 100); scene.register(image(1));
        assertTrue(scene.admitNativeFrame(typedFrame(2, false, typedCandidate(C, A, 2, 1000))));
        SceneEngine.Snapshot before = scene.snapshot(1, 500, false);
        assertFalse(scene.admitNativeFrame(typedFrame(3, true)));
        assertNull(scene.source(3)); assertEquals(2, scene.sourceCount());
        assertEquals(before.revision, scene.snapshot(1, 700, false).revision);
        assertSame(nativeMask(before).evidenceSource, nativeMask(scene.ready()).evidenceSource);
    }

    @Test public void partialCannotSuppressGlobalOcrOrEraseIndependentImageText() {
        SceneEngine scene = scene(16, 100); scene.register(image(1));
        assertTrue(scene.observe(SceneEngine.Batch.imageText(image(1), 1,
                Collections.singletonList(new SceneEngine.Observation("TEXT_SMUT", 1, 1, region(500), .9f)))));
        assertTrue(scene.admitNativeFrame(typedFrame(2, true, typedCandidate(C, A, 2, 1000))));
        assertFalse(scene.hasNativeTextCoverage(2, 500)); assertEquals(2, scene.snapshot(1, 500, false).groups.size());
    }

    @Test public void partialWitnessesCannotBeConsumedOrLaunderedByTheFirstLaterCompleteRead() {
        SceneEngine scene = scene(32, 100); scene.register(image(1));
        NativeTextFrame latest = null;
        for (int i = 2; i <= 6; i++) {
            latest = typedFrame(i, true, typedCandidate(C, A, i, 1000)); assertTrue(scene.admitNativeFrame(latest));
        }
        java.util.List<SceneEngine.AcceptedSubject> partialSubjects = scene.acceptedSubjects(latest.source, 1300);
        assertEquals(5, partialSubjects.get(0).witnesses.size()); assertFalse(partialSubjects.get(0).confirmedForConsumption);
        assertTrue(scene.consumeEvidence(latest.source, partialSubjects, 1300).isEmpty());
        for (int i = 7; i <= 11; i++) {
            latest = typedFrame(i, false, typedCandidate(C, A, i, 1000)); assertTrue(scene.admitNativeFrame(latest));
            java.util.List<SceneEngine.AcceptedSubject> current = scene.acceptedSubjects(latest.source, i * 200 + 80);
            assertEquals(i - 6, current.get(0).witnesses.size());
            assertEquals(i == 11, current.get(0).confirmedForConsumption);
        }
        assertEquals(1, scene.consumeEvidence(latest.source, scene.acceptedSubjects(latest.source, 2300), 2300).size());
    }

    @Test public void consumedContinuingNodeDoesNotBecomeANewFeeWhenReflowAddsALine() {
        SceneEngine scene = scene(32, 100); scene.register(image(1));
        NativeTextFrame latest = null;
        for (int i = 2; i <= 6; i++) {
            latest = typedFrame(i, false, typedCandidate(C, A, i, 1000)); assertTrue(scene.admitNativeFrame(latest));
        }
        assertEquals(1, scene.consumeEvidence(latest.source, scene.acceptedSubjects(latest.source, 1300), 1300).size());
        for (int i = 7; i <= 11; i++) {
            NativeTextResult.Candidate reflow = new NativeTextResult.Candidate("TEXT_SMUT", A, C, .9f, true,
                    Arrays.asList(region(800), region(1000)), NativeTextResult.TextSource.TEXT, 0, 4, 4, i * 200 + 60);
            latest = typedFrame(i, false, reflow); assertTrue(scene.admitNativeFrame(latest));
        }
        java.util.List<SceneEngine.AcceptedSubject> reflow = scene.acceptedSubjects(latest.source, 2300);
        assertEquals(2, reflow.size()); assertTrue(reflow.get(0).alreadyConsumed); assertTrue(reflow.get(1).alreadyConsumed);
        assertTrue(scene.consumeEvidence(latest.source, reflow, 2300).isEmpty());
    }

    @Test public void retainedNativeOriginsHaveTheirOwnBoundedPinsAfterCallerReleasesItsLatestBasis() {
        SceneEngine scene = scene(3, 100); scene.register(image(1)); scene.pin(image(1));
        NativeTextFrame original = typedFrame(2, false, typedCandidate(C, A, 2, 1000), typedCandidate(D, A, 2, 1200));
        assertTrue(scene.admitNativeFrame(original)); assertTrue(scene.unpin(original.source));
        NativeTextFrame update = typedFrame(3, true, typedCandidate(C, A, 3, 800));
        assertTrue(scene.admitNativeFrame(update)); assertTrue(scene.unpin(update.source));
        assertFalse(scene.register(image(4))); assertNotNull(scene.source(2)); assertNotNull(scene.source(3));
        scene.clearNative(2); assertTrue(scene.register(image(4)));
    }

    @Test public void partialCapacityFailureIsAtomicAndDoesNotRetireUnselectedNativeOrigins() {
        SceneEngine scene = scene(2, 100); scene.register(image(1)); scene.pin(image(1));
        NativeTextFrame original = typedFrame(2, false, typedCandidate(C, A, 2, 1000), typedCandidate(D, A, 2, 1200));
        assertTrue(scene.admitNativeFrame(original)); assertTrue(scene.unpin(original.source));
        SceneEngine.Snapshot before = scene.snapshot(1, 500, false);
        assertFalse(scene.admitNativeFrame(typedFrame(3, true, typedCandidate(C, A, 3, 800))));
        assertNull(scene.source(3)); assertEquals(2, scene.snapshot(1, 700, false).groups.size());
        assertEquals(before.revision, scene.ready().revision); assertSame(nativeMask(before).evidenceSource, nativeMask(scene.ready()).evidenceSource);
    }

    private static NativeTextResult.Candidate typedCandidate(String node, String content, long sequence, double y) {
        return new NativeTextResult.Candidate("TEXT_SMUT", content, node, .9f, true, Collections.singletonList(region(y)),
                NativeTextResult.TextSource.TEXT, 0, 4, 4, sequence * 200 + 60);
    }
    private static NativeTextFrame typedFrame(long sequence, boolean partial, NativeTextResult.Candidate... candidates) {
        long start = sequence * 200;
        NativeTextResult read = new NativeTextResult(1000, 2400, 7, start, start + 80, Arrays.asList(candidates),
                Math.max(1, candidates.length), candidates.length, candidates.length,
                partial ? NativeTextResult.Coverage.POSITIVE_NODES_ONLY : NativeTextResult.Coverage.COMPLETE_TREE,
                1, partial ? 2 : 1, 1, start + 10, candidates.length == 0 ? -1 : start + 58);
        return new NativeTextFrame(SceneEngine.Source.nativeText(1, sequence, 42, 1000, 2400, 7,
                start, start + 80, start + 80), 2, read, 1000, sequence, start - 1, start);
    }
    private static SceneEngine.Mask nodeAt(SceneEngine.Snapshot snapshot, double y) {
        for (SceneEngine.Group group : snapshot.groups) for (SceneEngine.Mask mask : group.members) {
            if (mask.nativeValidated && mask.displayRegion.top == y) return mask;
        }
        throw new AssertionError("missing node at " + y);
    }

    @Test public void partialFactsRequireExactIdentityMatchSpanAndActualConfirmationClocks() {
        NativeTextResult.Candidate proved = proved("cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc", 190);
        NativeTextResult partial = partial(100, 120, 168, 300, proved);
        assertEquals(NativeTextResult.Coverage.POSITIVE_NODES_ONLY, partial.coverage);
        assertEquals(1, partial.scanGeneration); assertEquals(2, partial.confirmationGeneration);
        assertTrue(proved.matchProvenanceKnown()); assertEquals(190, proved.validatedAtMillis);
        assertThrows(IllegalArgumentException.class, () -> partial(100, 120, 167, 300, proved));
        assertThrows(IllegalArgumentException.class, () -> partial(100, 120, 168, 300, candidate(A, C, 1000)));
        assertThrows(IllegalArgumentException.class, () -> partial(100, 120, 168, 300, proved(C, 301)));
        assertThrows(IllegalArgumentException.class, () -> partial(100, 120, 168, 300, proved(C, 160)));
    }

    @Test public void partialUnknownRevisionAndAnonymousOrMalformedSpanCannotBorrowCompleteCompatibility() {
        NativeTextResult.Candidate proved = proved(C, 190);
        assertThrows(IllegalArgumentException.class, () -> new NativeTextResult(1000, 2400, 7, 100, 300,
                Collections.singletonList(proved), 1, 1, 1, NativeTextResult.Coverage.POSITIVE_NODES_ONLY,
                -1, -1, -1, 120, 168));
        assertThrows(IllegalArgumentException.class, () -> partial(100, 120, 168, 300, proved(null, 190)));
        assertThrows(IllegalArgumentException.class, () -> new NativeTextResult.Candidate("TEXT_SMUT", A, C, .9f, true,
                Collections.singletonList(region(1000)), NativeTextResult.TextSource.TEXT, 0, 5, 4, 190));
        assertThrows(IllegalArgumentException.class, () -> new NativeTextResult.Candidate("TEXT_SMUT", A, C, .9f, true,
                Collections.singletonList(region(1000)), NativeTextResult.TextSource.TEXT, -1, 4, 4, 190));
    }

    @Test public void completeResultCannotRelabelCrossRevisionFactsAsWholeTreeCoverage() {
        assertThrows(IllegalArgumentException.class, () -> new NativeTextResult(1000, 2400, 7, 100, 300,
                Collections.singletonList(proved(C, 190)), 1, 1, 1, NativeTextResult.Coverage.COMPLETE_TREE,
                1, 2, 1, 120, 168));
        NativeTextResult legacy = frame(2, candidate(A, C, 1000)).read;
        assertEquals(NativeTextResult.Coverage.COMPLETE_TREE, legacy.coverage);
        assertEquals(-1, legacy.scanGeneration); assertEquals(-1, legacy.classificationCompletedAtMillis);
    }

    @Test public void eachCandidateExpiresFromItsValidationNotLaterSiblingOrFrameCompletion() {
        NativeTextResult.Candidate first = proved(C, 190), later = proved(D, 290);
        NativeTextResult read = partial(100, 120, 168, 300, first, later);
        NativeTextFrame frame = new NativeTextFrame(SceneEngine.Source.nativeText(1, 3, 42, 1000, 2400, 7,
                100, 300, 320), 2, read, 1000);
        assertEquals(1300, frame.validUntilMillis); assertEquals(1190, frame.candidateValidUntilMillis(first));
        assertEquals(1290, frame.candidateValidUntilMillis(later));
        assertThrows(IllegalArgumentException.class, () -> frame.candidateValidUntilMillis(proved(C, 190)));
    }

    @Test public void textSourceOrNormalizedMatchSpanCannotInheritTheOldSemanticBinding() {
        NativeTextResult.Candidate original = proved(C, 190);
        NativeTextResult.Candidate description = new NativeTextResult.Candidate("TEXT_SMUT", A, C, .9f, true,
                Collections.singletonList(region(1000)), NativeTextResult.TextSource.CONTENT_DESCRIPTION, 0, 4, 4, 190);
        NativeTextResult.Candidate span = new NativeTextResult.Candidate("TEXT_SMUT", A, C, .9f, true,
                Collections.singletonList(region(1000)), NativeTextResult.TextSource.TEXT, 1, 4, 4, 190);
        assertFalse(original.sameContent(description)); assertFalse(original.sameContent(span));
        assertTrue(original.sameContent(proved(C, 290)));
    }

    private static NativeTextResult.Candidate proved(String node, long at) {
        return new NativeTextResult.Candidate("TEXT_SMUT", A, node, .9f, true, Collections.singletonList(region(1000)),
                NativeTextResult.TextSource.TEXT, 0, 4, 4, at);
    }
    private static NativeTextResult partial(long start, long classified, long confirming, long finish,
            NativeTextResult.Candidate... candidates) {
        return new NativeTextResult(1000, 2400, 7, start, finish, Arrays.asList(candidates),
                Math.max(1, candidates.length), candidates.length, candidates.length, NativeTextResult.Coverage.POSITIVE_NODES_ONLY,
                1, 2, 1, classified, confirming);
    }

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
