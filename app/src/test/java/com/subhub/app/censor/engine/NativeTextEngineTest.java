package com.subhub.app.censor.engine;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.Executor;
import org.junit.Test;

public class NativeTextEngineTest {
    @Test public void nativeAdmissionObserverDoesNotProveAsynchronousPresentationIsInstalled() {
        Harness h = new Harness();
        h.engine.executionControls(334, false, EnumSet.noneOf(SceneEngine.Lane.class));
        h.engine.start(42); h.fire(0); h.nativeExecutor.runAll();
        h.afterNativeAccepted = () -> assertNull("Presentation executor is deliberately still queued", h.engine.acquireRenderView());
        h.finishNative(0, 10, 20, 1000);
        assertEquals(1, h.nativeFrames.size()); assertNull(h.engine.acquireRenderView());
        NativeTextFrame admitted = h.nativeFrames.get(0); long originalDeadline = admitted.validUntilMillis;
        h.ui.runAll();
        try (CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView()) {
            assertNotNull(view); assertSame(admitted.source, view.snapshot.destination);
            assertEquals(1, masks(view).size()); assertSame(admitted.source, masks(view).get(0).evidenceSource);
        }
        assertEquals(originalDeadline, admitted.validUntilMillis);
        assertEquals(1, h.nativeInputs.size()); assertTrue(h.captures.isEmpty());
        h.close(); assertEquals(1, h.drained);
    }

    @Test public void sameParentCompleteCannotOmitObservedGroupAndBorrowGlobalNegativeAuthority() {
        for (boolean expired : new boolean[]{false, true}) {
            Harness h = observingHarness(); CensorEngine.NativeTextInput input = h.nativeInputs.get(0);
            CensorEngine.NativeTextCompletion completion = h.nativeCompletions.get(0);
            h.now = 80; assertTrue(completion.observe(1, observed(input, 0, 70, NODE, 1000, 65))); h.ui.runAll();
            h.now = expired ? 1100 : 210;
            if (expired) { try (CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView()) { assertTrue(masks(view).isEmpty()); } }
            NativeTextResult b = observed(input, 0, h.now - 10, OTHER_NODE, 1200, h.now - 15);
            NativeTextResult inconsistent = new NativeTextResult(1000, 2400, 7, 0, h.now - 10, b.candidates, 2, 2, 1,
                    NativeTextResult.Coverage.COMPLETE_TREE, input.scanGeneration(), input.scanGeneration(), input.hardGeneration, 10, 58);
            assertNull(completion.admit(inconsistent)); assertEquals(1, h.nativeFrames.size());
            assertFalse(h.engine.imageTextSuppressed()); assertEquals(1, h.evidence.size());
            h.ui.runAll();
            try (CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView()) {
                assertEquals(expired ? 0 : 1, masks(view).size());
                if (!expired) assertEquals(1000, masks(view).get(0).displayRegion.top, 0);
            }
            h.close(); assertEquals(1, h.drained);
        }
    }

    @Test public void fiveStreamingParentsCountOnlyTheirActualCompleteAcquisitions() {
        Harness h = observingHarness();
        for (int i = 0; i < 5; i++) {
            long start = i * 300L;
            if (i > 0) { h.fire(start); h.nativeExecutor.runAll(); }
            CensorEngine.NativeTextInput input = h.nativeInputs.get(i);
            NativeTextResult a = observed(input, start, start + 70, NODE, 1000, start + 65);
            h.now = start + 80; assertTrue(h.nativeCompletions.get(i).observe(1, a));
            h.ui.runAll(); CensorEngine.EvidenceReceipt early = latestEvidence(h);
            assertTrue(early.subjects.get(0).witnesses.isEmpty());
            assertFalse(early.subjects.get(0).confirmedForConsumption);
            assertTrue(h.engine.claimEvidence(early).subjects.isEmpty());
            assertFalse(h.engine.imageTextSuppressed());
            CensorEngine.ViewportCoverage coverage = h.engine.viewportCoverage();
            assertFalse(h.engine.dwellPresent(early, coverage, coverage.regions));
            NativeTextResult full = new NativeTextResult(1000, 2400, 7, start, start + 90, a.candidates, 1, 1, 1,
                    NativeTextResult.Coverage.COMPLETE_TREE, input.scanGeneration(), input.scanGeneration(), input.hardGeneration,
                    start + 10, start + 58);
            h.now = start + 100; assertNotNull(h.nativeCompletions.get(i).admit(full)); h.ui.runAll();
            CensorEngine.EvidenceReceipt receipt = h.evidence.get(h.evidence.size() - 1);
            assertEquals(i + 1, receipt.subjects.get(0).witnesses.size());
            assertEquals(input.identity, receipt.subjects.get(0).witnesses.get(i).producerIdentity);
            assertEquals(i == 4 ? 1 : 0, h.engine.claimEvidence(receipt).subjects.size());
            assertTrue(h.engine.claimEvidence(receipt).subjects.isEmpty());
            assertNull(h.engine.claimEvidence(early));
        }
        assertEquals(5, h.nativeInputs.size()); assertEquals(10, h.nativeFrames.size()); h.close();
    }

    @Test public void failedCancelledOrIncompleteParentCannotResurrectHiddenCompletedHistory() {
        for (String terminal : new String[]{"failure", "cancelled", "partial", "dirtyComplete", "dirtyPartial"}) {
            for (boolean nextObserves : new boolean[]{false, true}) {
                Harness h = observingHarness(); completedParents(h, 3);
                CensorEngine.EvidenceReceipt prior = latestEvidence(h);
                h.fire(900); h.nativeExecutor.runAll(); CensorEngine.NativeTextInput input = h.nativeInputs.get(3);
                CensorEngine.NativeTextCompletion completion = h.nativeCompletions.get(3);
                NativeTextResult a = observed(input, 900, 970, NODE, 1000, 965);
                h.now = 980; assertTrue(completion.observe(1, a)); h.ui.runAll();
                assertNull(h.engine.claimEvidence(prior)); assertTrue(latestEvidence(h).subjects.get(0).witnesses.isEmpty());
                h.now = 990;
                if (terminal.startsWith("dirty")) h.engine.requestNativeTextAt(h.now, CensorEngine.NativeTextChange.TREE_DIRTY);
                if (terminal.equals("failure")) completion.failure();
                else if (terminal.equals("cancelled")) completion.cancelled();
                else if (terminal.equals("dirtyComplete")) assertNull(completion.admit(completed(input, a, 990)));
                else assertNotNull(completion.admit(a));
                assertFalse(completion.observe(2, observed(input, 900, 990, OTHER_NODE, 1200, 985)));
                h.ui.runAll();
                h.engine.requestNativeTextAt(1200, false);
                h.fire(1200); h.nativeExecutor.runAll(); CensorEngine.NativeTextInput next = h.nativeInputs.get(4);
                NativeTextResult b = observed(next, 1200, 1270, NODE, 1000, 1265);
                h.now = 1280;
                if (nextObserves) assertTrue(h.nativeCompletions.get(4).observe(1, b));
                h.now = 1300; assertNotNull(h.nativeCompletions.get(4).admit(completed(next, b, 1290))); h.ui.runAll();
                CensorEngine.EvidenceReceipt receipt = latestEvidence(h);
                assertEquals(terminal + "/early=" + nextObserves, 1, receipt.subjects.get(0).witnesses.size());
                assertEquals(next.identity, receipt.subjects.get(0).witnesses.get(0).producerIdentity);
                assertTrue(h.engine.claimEvidence(receipt).subjects.isEmpty());
                h.close(); assertEquals(1, h.drained);
            }
        }
    }

    @Test public void directTerminalAfterAnObservationCannotCarryHiddenFullHistory() {
        Harness h = observingHarness(); completedParents(h, 3);
        h.fire(900); h.nativeExecutor.runAll(); CensorEngine.NativeTextInput input = h.nativeInputs.get(3);
        h.now = 980; assertTrue(h.nativeCompletions.get(3).observe(1, observed(input, 900, 970, NODE, 1000, 965)));
        h.now = 990; h.nativeCompletions.get(3).failure(); h.ui.runAll();
        h.nativeBeginMode = NativeTextResult.ReadMode.ADMITTED_NODE_RECLASSIFY;
        h.fire(1200); h.nativeExecutor.runAll(); CensorEngine.NativeTextInput direct = h.nativeInputs.get(4);
        NativeTextResult a = observed(direct, 1200, 1270, NODE, 1000, 1265);
        NativeTextResult partial = new NativeTextResult(1000, 2400, 7, 1200, 1270, a.candidates, 1, 1, 1,
                a.coverage, a.scanGeneration, a.confirmationGeneration, a.hardGeneration,
                a.classificationCompletedAtMillis, a.confirmationStartedAtMillis, direct.readMode());
        h.now = 1280; assertNotNull(h.nativeCompletions.get(4).admit(partial)); h.ui.runAll();
        assertEquals(1, latestEvidence(h).subjects.get(0).witnesses.size());
        assertEquals(direct.identity, latestEvidence(h).subjects.get(0).witnesses.get(0).producerIdentity);
        h.nativeBeginMode = NativeTextResult.ReadMode.FULL_DISCOVERY;
        h.fire(1500); h.nativeExecutor.runAll(); CensorEngine.NativeTextInput next = h.nativeInputs.get(5);
        NativeTextResult b = observed(next, 1500, 1570, NODE, 1000, 1565);
        h.now = 1580; assertTrue(h.nativeCompletions.get(5).observe(1, b));
        assertTrue(latestEvidence(h).subjects.get(0).witnesses.isEmpty());
        h.now = 1600; assertNotNull(h.nativeCompletions.get(5).admit(completed(next, b, 1590))); h.ui.runAll();
        assertEquals(1, latestEvidence(h).subjects.get(0).witnesses.size());
        assertEquals(next.identity, latestEvidence(h).subjects.get(0).witnesses.get(0).producerIdentity);
        h.close();
    }

    @Test public void expiryDiscardsHiddenHistoryEvenWhenNoSnapshotPrunedTheTrack() {
        for (boolean expiresBeforeObservation : new boolean[]{false, true}) {
            Harness h = observingHarness(); completedParents(h, 3);
            h.fire(900); h.nativeExecutor.runAll(); CensorEngine.NativeTextInput input = h.nativeInputs.get(3);
            long validated = expiresBeforeObservation ? 1665 : 965;
            h.now = validated + 15;
            assertTrue(h.nativeCompletions.get(3).observe(1, observed(input, 900, validated + 5, NODE, 1000, validated)));
            h.ui.runAll();
            // Do not run a timer, query render state or snapshot after crossing the old deadline.
            h.now = expiresBeforeObservation ? 1710 : 1970;
            NativeTextResult fresh = observed(input, 900, h.now - 5, NODE, 1000, h.now - 10);
            assertNotNull(h.nativeCompletions.get(3).admit(completed(input, fresh, h.now - 5))); h.ui.runAll();
            assertEquals(1, latestEvidence(h).subjects.get(0).witnesses.size());
            assertEquals(input.identity, latestEvidence(h).subjects.get(0).witnesses.get(0).producerIdentity);
            h.close();
        }
    }

    @Test public void hardFenceOrScopeRetirementCannotRestoreHiddenCompletedHistory() {
        for (String action : new String[]{"hard", "scope", "stop", "disable", "protection"}) {
            Harness h = observingHarness(); completedParents(h, 3);
            h.fire(900); h.nativeExecutor.runAll(); CensorEngine.NativeTextInput input = h.nativeInputs.get(3);
            NativeTextResult a = observed(input, 900, 970, NODE, 1000, 965);
            h.now = 980; assertTrue(h.nativeCompletions.get(3).observe(1, a)); h.ui.runAll();
            h.now = 990; fence(h, action); assertTrue(input.cancelled());
            assertNull(h.nativeCompletions.get(3).admit(completed(input, a, 990)));
            if (action.equals("stop")) h.engine.start(42);
            if (action.equals("disable")) h.engine.nativeTextControls(true);
            h.fire(1200); h.nativeExecutor.runAll(); CensorEngine.NativeTextInput next = h.nativeInputs.get(4);
            NativeTextResult b = observed(next, 1200, 1270, NODE, 1000, 1265);
            h.now = 1300; assertNotNull(h.nativeCompletions.get(4).admit(completed(next, b, 1290))); h.ui.runAll();
            assertEquals(action, 1, latestEvidence(h).subjects.get(0).witnesses.size());
            assertEquals(next.identity, latestEvidence(h).subjects.get(0).witnesses.get(0).producerIdentity);
            h.close(); assertEquals(1, h.drained);
        }
    }

    @Test public void changedLiteralInFinalCompleteStartsANewHistoryInsteadOfBorrowingHiddenCredit() {
        Harness h = observingHarness(); completedParents(h, 3); long previousGroup = latestEvidence(h).subjects.get(0).textGroup;
        h.fire(900); h.nativeExecutor.runAll(); CensorEngine.NativeTextInput input = h.nativeInputs.get(3);
        h.now = 980; assertTrue(h.nativeCompletions.get(3).observe(1, observed(input, 900, 970, NODE, 1000, 965)));
        NativeTextResult.Candidate changed = new NativeTextResult.Candidate("TEXT_SMUT", OTHER_NODE, NODE, .9f, true,
                Collections.singletonList(region(1000)), NativeTextResult.TextSource.TEXT, 0, 4, 4, 985);
        NativeTextResult full = new NativeTextResult(1000, 2400, 7, 900, 990, Collections.singletonList(changed), 1, 1, 1,
                NativeTextResult.Coverage.COMPLETE_TREE, input.scanGeneration(), input.scanGeneration(), input.hardGeneration, 910, 958);
        h.now = 1000; assertNotNull(h.nativeCompletions.get(3).admit(full)); h.ui.runAll();
        assertEquals(1, latestEvidence(h).subjects.get(0).witnesses.size());
        assertNotEquals(previousGroup, latestEvidence(h).subjects.get(0).textGroup); h.close();
    }

    @Test public void previouslyQualifiedHistoryStaysHiddenAndCanOnlyBeConsumedOnceAfterRealCompletion() {
        for (boolean alreadyConsumed : new boolean[]{false, true}) {
            Harness h = observingHarness(); completedParents(h, 5);
            CensorEngine.EvidenceReceipt previous = latestEvidence(h);
            assertTrue(previous.subjects.get(0).confirmedForConsumption);
            if (alreadyConsumed) assertEquals(1, h.engine.claimEvidence(previous).subjects.size());
            h.fire(1500); h.nativeExecutor.runAll(); CensorEngine.NativeTextInput input = h.nativeInputs.get(5);
            NativeTextResult a = observed(input, 1500, 1570, NODE, 1000, 1565);
            h.now = 1580; assertTrue(h.nativeCompletions.get(5).observe(1, a)); h.ui.runAll();
            CensorEngine.EvidenceReceipt early = latestEvidence(h);
            assertTrue(early.subjects.get(0).witnesses.isEmpty()); assertFalse(early.subjects.get(0).confirmedForConsumption);
            assertTrue(h.engine.claimEvidence(early).subjects.isEmpty()); assertNull(h.engine.claimEvidence(previous));
            h.now = 1600; assertNotNull(h.nativeCompletions.get(5).admit(completed(input, a, 1590))); h.ui.runAll();
            CensorEngine.EvidenceReceipt last = latestEvidence(h); assertEquals(5, last.subjects.get(0).witnesses.size());
            assertEquals(previous.subjects.get(0).textGroup, last.subjects.get(0).textGroup);
            assertEquals(alreadyConsumed ? 0 : 1, h.engine.claimEvidence(last).subjects.size());
            assertTrue(h.engine.claimEvidence(last).subjects.isEmpty()); h.close();
        }
    }

    private static CensorEngine.EvidenceReceipt latestEvidence(Harness h) { return h.evidence.get(h.evidence.size() - 1); }
    private static void completedParents(Harness h, int count) {
        for (int i = 0; i < count; i++) {
            long start = i * 300L; if (i > 0) { h.fire(start); h.nativeExecutor.runAll(); }
            CensorEngine.NativeTextInput input = h.nativeInputs.get(i);
            NativeTextResult a = observed(input, start, start + 70, NODE, 1000, start + 65);
            h.now = start + 100; assertNotNull(h.nativeCompletions.get(i).admit(completed(input, a, start + 90))); h.ui.runAll();
        }
    }
    private static NativeTextResult completed(CensorEngine.NativeTextInput input, NativeTextResult observed, long end) {
        return new NativeTextResult(observed.width, observed.height, observed.windowId, observed.readStartedAtMillis, end,
                observed.candidates, observed.visitedNodes, observed.classifiedNodes, observed.confirmationReads,
                NativeTextResult.Coverage.COMPLETE_TREE, input.scanGeneration(), input.scanGeneration(), input.hardGeneration,
                observed.classificationCompletedAtMillis, observed.confirmationStartedAtMillis);
    }

    @Test public void positiveObservationPublishesWhileParentStillOwnsItsLaneAndLifetime() {
        Harness h = observingHarness(); CensorEngine.NativeTextInput input = h.nativeInputs.get(0);
        CensorEngine.NativeTextCompletion completion = h.nativeCompletions.get(0);
        h.now = 80; assertTrue(completion.observe(1, observed(input, 0, 70, NODE, 1000, 65))); h.ui.runAll();
        assertEquals(1, h.nativeFrames.size()); assertEquals(1, h.nativeFrames.get(0).observationOrdinal);
        assertEquals(input.identity, h.nativeFrames.get(0).requestIdentity);
        assertFalse(input.cancelled()); assertTrue(h.nativeCosts.isEmpty());
        assertFalse(h.engine.imageTextSuppressed());
        assertTrue(h.engine.claimEvidence(h.evidence.get(0)).subjects.isEmpty());
        try (CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView()) { assertEquals(1, masks(view).size()); }
        h.engine.requestNativeTextAt(80, false); h.fire(200); h.nativeExecutor.runAll();
        assertEquals("An observation cannot free the running native lane", 1, h.nativeInputs.size());
        h.engine.close(); assertEquals(0, h.drained); assertTrue(input.cancelled());
        assertFalse(completion.observe(2, observed(input, 0, 210, OTHER_NODE, 1200, 205)));
        completion.cancelled(); h.ui.runAll(); assertEquals(1, h.drained); h.close();
    }

    @Test public void trueTerminalAfterObservationsPreservesNodeAgeAndOneAcquisitionWitness() {
        Harness h = observingHarness(); CensorEngine.NativeTextInput input = h.nativeInputs.get(0);
        CensorEngine.NativeTextCompletion completion = h.nativeCompletions.get(0);
        NativeTextResult a = observed(input, 0, 70, NODE, 1000, 65);
        NativeTextResult b = observed(input, 0, 200, OTHER_NODE, 1200, 190);
        h.now = 80; assertTrue(completion.observe(1, a));
        h.now = 210; assertTrue(completion.observe(2, b));
        for (CensorEngine.EvidenceReceipt early : h.evidence) {
            assertTrue(early.subjects.get(0).witnesses.isEmpty());
            assertFalse(early.subjects.get(0).confirmedForConsumption);
        }
        assertFalse(h.engine.imageTextSuppressed());
        List<NativeTextResult.Candidate> both = java.util.Arrays.asList(a.candidates.get(0), b.candidates.get(0));
        NativeTextResult full = new NativeTextResult(1000, 2400, 7, 0, 300, both, 2, 2, 2,
                NativeTextResult.Coverage.COMPLETE_TREE, input.scanGeneration(), input.scanGeneration(), input.hardGeneration, 10, 58);
        h.now = 310; assertNotNull(completion.admit(full)); h.ui.runAll();
        assertEquals(3, h.nativeFrames.size()); assertEquals(0, h.nativeFrames.get(2).observationOrdinal);
        assertTrue(h.engine.imageTextSuppressed());
        assertEquals(1066, h.engine.viewportCoverage().validBeforeMillis);
        for (SceneEngine.AcceptedSubject subject : h.evidence.get(2).subjects) {
            assertEquals(1, subject.witnesses.size()); assertEquals(input.identity, subject.witnesses.get(0).producerIdentity);
            assertEquals(subject.region.top == 1000 ? 1065 : 1190, subject.validUntilMillis);
        }
        assertTrue(h.engine.claimEvidence(h.evidence.get(2)).subjects.isEmpty());
        assertFalse(completion.observe(3, observed(input, 0, 320, THIRD_NODE, 1400, 315)));
        assertNull(completion.admit(full)); h.close(); assertEquals(1, h.drained);
    }

    @Test public void earlyGroupDoesNotRenewUnobservedSiblingOrBorrowCompleteAuthority() {
        Harness h = observingHarness(); CensorEngine.NativeTextInput first = h.nativeInputs.get(0);
        h.now = 80; assertNotNull(h.nativeCompletions.get(0).admit(scheduledRead(first, 0, false))); h.ui.runAll();
        SceneEngine.Mask sibling;
        try (CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView()) {
            sibling = masks(view).stream().filter(mask -> mask.displayRegion.top == 1200).findFirst().get();
        }
        h.fire(200); h.nativeExecutor.runAll(); CensorEngine.NativeTextInput parent = h.nativeInputs.get(1);
        h.now = 280; assertTrue(h.nativeCompletions.get(1).observe(1, observed(parent, 200, 270, NODE, 900, 265)));
        assertFalse(h.engine.imageTextSuppressed());
        assertEquals(CensorEngine.CoverageState.NO_ELIGIBLE_REGIONS, h.engine.viewportCoverage().state);
        try (CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView()) {
            List<SceneEngine.Mask> retained = masks(view); assertEquals(2, retained.size());
            SceneEngine.Mask unchanged = retained.stream().filter(mask -> mask.displayRegion.top == 1200).findFirst().get();
            assertEquals(sibling.identity, unchanged.identity); assertEquals(sibling.textGroup, unchanged.textGroup);
            assertSame(sibling.evidenceSource, unchanged.evidenceSource);
        }
        h.fire(1066);
        try (CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView()) {
            assertEquals(1, masks(view).size()); assertEquals(900, masks(view).get(0).displayRegion.top, 0);
        }
        assertEquals(2, h.nativeInputs.size()); h.nativeCompletions.get(1).failure(); h.close();
    }

    @Test public void duplicateForeignOrNonpositiveObservationsCannotAcquireAnotherSource() {
        Harness h = observingHarness(); CensorEngine.NativeTextInput input = h.nativeInputs.get(0);
        CensorEngine.NativeTextCompletion completion = h.nativeCompletions.get(0); h.now = 100;
        NativeTextResult a = observed(input, 0, 70, NODE, 1000, 65);
        assertFalse(completion.observe(0, a)); assertFalse(completion.observe(65, a));
        assertFalse(completion.observe(1, scheduledRead(input, 0, false)));
        assertFalse(completion.observe(1, observed(input, 1, 71, NODE, 1000, 66)));
        assertTrue(completion.observe(1, a)); assertFalse(completion.observe(1, a));
        h.now = 210;
        assertFalse("Same node cannot manufacture repeated observations", completion.observe(2, observed(input, 0, 200, NODE, 800, 195)));
        assertTrue(completion.observe(2, observed(input, 0, 200, OTHER_NODE, 1200, 195)));
        assertFalse(completion.observe(1, observed(input, 0, 200, THIRD_NODE, 1400, 195)));
        assertEquals(2, h.nativeFrames.size()); assertEquals(2, h.evidence.size()); h.close();
    }

    @Test public void fencedParentCannotObserveMoreAndDoesNotPretendToBeDrained() {
        for (String fence : new String[]{"hard", "scope", "stop", "close", "disable", "protection"}) {
            Harness h = observingHarness(); CensorEngine.NativeTextInput input = h.nativeInputs.get(0);
            CensorEngine.NativeTextCompletion completion = h.nativeCompletions.get(0);
            h.now = 80; assertTrue(completion.observe(1, observed(input, 0, 70, NODE, 1000, 65)));
            fence(h, fence); assertTrue(input.cancelled());
            h.now = 210; assertFalse(fence, completion.observe(2, observed(input, 0, 200, OTHER_NODE, 1200, 195)));
            assertEquals(1, h.nativeFrames.size()); assertEquals(0, h.drained);
            h.engine.close(); assertEquals(0, h.drained); completion.cancelled(); h.ui.runAll();
            assertEquals(1, h.drained); h.close();
        }
    }

    @Test public void observerStopOrCloseFencesTheStillOwnedParentBeforeObservationReturns() {
        for (String action : new String[]{"stop", "close", "hard", "scope"}) {
            Harness h = observingHarness(); CensorEngine.NativeTextInput input = h.nativeInputs.get(0);
            h.afterNativeAccepted = () -> fence(h, action); h.now = 80;
            assertFalse(h.nativeCompletions.get(0).observe(1, observed(input, 0, 70, NODE, 1000, 65)));
            assertEquals(1, h.nativeFrames.size()); assertTrue(input.cancelled()); assertEquals(0, h.drained);
            h.afterNativeAccepted = () -> { }; h.close(); assertEquals(1, h.drained);
        }
    }

    @Test public void failedOrCancelledParentKeepsOnlyFiniteAlreadyObservedPositives() {
        for (boolean cancelled : new boolean[]{false, true}) {
            Harness h = observingHarness(); CensorEngine.NativeTextInput input = h.nativeInputs.get(0);
            CensorEngine.NativeTextCompletion completion = h.nativeCompletions.get(0);
            h.now = 80; assertTrue(completion.observe(1, observed(input, 0, 70, NODE, 1000, 65)));
            h.now = 210; if (cancelled) completion.cancelled(); else completion.failure();
            h.ui.runAll();
            assertFalse(h.engine.imageTextSuppressed());
            try (CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView()) { assertEquals(1, masks(view).size()); }
            h.fire(1066);
            try (CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView()) {
                assertNotNull("The established presentation can retain an empty destination", view);
                assertTrue(masks(view).isEmpty());
            }
            h.close(); assertEquals(1, h.drained);
        }
    }

    @Test public void earlyObservationCannotHideLaterResourceFailureOrAuthorizeAfterTerminalCost() {
        Harness h = observingHarness(); CensorEngine.NativeTextInput input = h.nativeInputs.get(0);
        CensorEngine.NativeTextCompletion completion = h.nativeCompletions.get(0);
        completion.costStarted(new NativeReadCost.Start(input.identity, input.epoch, input.geometry, input.hardGeneration, 0));
        h.now = 80; assertTrue(completion.observe(1, observed(input, 0, 70, NODE, 1000, 65)));
        h.now = 210; completion.cost(cost(input, 0, 200));
        assertFalse(completion.observe(2, observed(input, 0, 200, OTHER_NODE, 1200, 195)));
        completion.resourceFailure(); h.engine.close(); h.ui.runAll(); assertEquals(0, h.drained);
        assertEquals(1, h.engine.resourceFailures()); h.close();
    }

    private static final String OTHER_NODE = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd";
    private static final String THIRD_NODE = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee";
    private static Harness observingHarness() {
        Harness h = new Harness(); h.engine.executionControls(334, false, EnumSet.noneOf(SceneEngine.Lane.class));
        h.engine.start(42); h.fire(0); h.nativeExecutor.runAll(); return h;
    }
    private static NativeTextResult observed(CensorEngine.NativeTextInput input, long start, long end,
            String node, double y, long validated) {
        NativeTextResult.Candidate candidate = new NativeTextResult.Candidate("TEXT_SMUT", CONTENT, node, .9f, true,
                Collections.singletonList(region(y)), NativeTextResult.TextSource.TEXT, 0, 4, 4, validated);
        return new NativeTextResult(1000, 2400, 7, start, end, Collections.singletonList(candidate), 1, 1, 1,
                NativeTextResult.Coverage.POSITIVE_NODES_ONLY, input.scanGeneration(), input.scanGeneration(), input.hardGeneration,
                start + 10, start + 58);
    }
    private static void fence(Harness h, String fence) {
        if (fence.equals("hard")) h.engine.requestNativeTextAt(h.now, CensorEngine.NativeTextChange.HARD_CONTENT);
        else if (fence.equals("scope")) h.engine.changeScope(43);
        else if (fence.equals("stop")) h.engine.stop();
        else if (fence.equals("close")) h.engine.close();
        else if (fence.equals("disable")) h.engine.nativeTextControls(false);
        else h.engine.changeScope(42, ProtectionScope.normal(99));
    }

    @Test public void cohortObservationAndTerminalAreOnePartialParentWithIndependentNodeDeadlines() {
        Harness h = cohortHarness(); CensorEngine.NativeTextInput input = h.nativeInputs.get(0);
        NativeTextResult.Candidate a = observed(input, 0, 70, NODE, 1000, 65).candidates.get(0);
        NativeTextResult.Candidate b = observed(input, 0, 100, OTHER_NODE, 1200, 95).candidates.get(0);
        NativeTextResult early = cohort(input, 70, a), terminal = cohort(input, 100, a, b);
        h.now = 80; assertTrue(h.nativeCompletions.get(0).observe(1, early)); assertTrue(h.engine.nativeTextInFlight());
        h.ui.runAll(); assertTrue(h.engine.claimEvidence(latestEvidence(h)).subjects.isEmpty());
        h.now = 110; CensorEngine.NativeTextAdmission admission = h.nativeCompletions.get(0).admit(terminal);
        assertNotNull(admission); assertTrue(admission.matches(input, terminal)); h.ui.runAll();
        assertEquals(1, h.nativeInputs.size()); assertEquals(2, h.nativeFrames.size()); assertFalse(h.engine.nativeTextInFlight());
        assertEquals(1065, admission.frame.candidateValidUntilMillis(a)); assertEquals(1095, admission.frame.candidateValidUntilMillis(b));
        assertFalse(h.engine.imageTextSuppressed()); assertTrue(h.engine.claimEvidence(latestEvidence(h)).subjects.isEmpty());
        assertEquals(CensorEngine.CoverageState.NO_ELIGIBLE_REGIONS, h.engine.viewportCoverage().state);
        h.fire(1066); h.ui.runAll();
        try (CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView()) {
            assertEquals(1, masks(view).size()); assertEquals(1200, masks(view).get(0).displayRegion.top, 0);
        }
        h.close(); assertEquals(1, h.drained);
    }

    @Test public void cohortCannotCompleteByOmittingItsUnobservedSecondMember() {
        Harness h = cohortHarness(); CensorEngine.NativeTextInput input = h.nativeInputs.get(0);
        NativeTextResult early = cohort(input, 70, observed(input, 0, 70, NODE, 1000, 65).candidates.get(0));
        h.now = 80; assertTrue(h.nativeCompletions.get(0).observe(1, early));
        h.now = 90; assertNull(h.nativeCompletions.get(0).admit(early)); assertFalse(h.engine.nativeTextInFlight());
        assertEquals(1, h.nativeFrames.size()); assertEquals(1, h.nativeFrames.get(0).observationOrdinal);
        h.fire(1066); h.ui.runAll();
        try (CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView()) { assertTrue(masks(view).isEmpty()); }
        h.close(); assertEquals(1, h.drained);
    }

    @Test public void cohortTerminalCannotReplaceItsObservedMemberWithTwoOtherNodes() {
        Harness h = cohortHarness(); CensorEngine.NativeTextInput input = h.nativeInputs.get(0);
        NativeTextResult early = cohort(input, 70, observed(input, 0, 70, NODE, 1000, 65).candidates.get(0));
        h.now = 80; assertTrue(h.nativeCompletions.get(0).observe(1, early));
        NativeTextResult substituted = cohort(input, 1100,
                observed(input, 0, 1100, OTHER_NODE, 1200, 1095).candidates.get(0),
                observed(input, 0, 1100, THIRD_NODE, 1400, 1095).candidates.get(0));
        // Parent inventory must survive even after the original observed A has expired/pruned.
        h.now = 1110; h.ui.runAll();
        try (CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView()) { assertTrue(masks(view).isEmpty()); }
        assertNull(h.nativeCompletions.get(0).admit(substituted)); assertEquals(1, h.nativeFrames.size());
        h.close(); assertEquals(1, h.drained);
    }

    @Test public void failedOrCancelledCohortCannotRedateItsAlreadyObservedFirstMember() {
        for (boolean cancelled : new boolean[]{false, true}) {
            Harness h = cohortHarness(); CensorEngine.NativeTextInput input = h.nativeInputs.get(0);
            NativeTextResult early = cohort(input, 70, observed(input, 0, 70, NODE, 1000, 65).candidates.get(0));
            h.now = 80; assertTrue(h.nativeCompletions.get(0).observe(1, early));
            h.now = 900; if (cancelled) h.nativeCompletions.get(0).cancelled(); else h.nativeCompletions.get(0).failure();
            assertEquals(1, h.nativeFrames.size()); assertEquals(1065, h.nativeFrames.get(0).candidateValidUntilMillis(early.candidates.get(0)));
            assertFalse(h.nativeCompletions.get(0).observe(1, early)); h.fire(1066); h.ui.runAll();
            try (CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView()) { assertTrue(masks(view).isEmpty()); }
            h.close(); assertEquals(1, h.drained);
        }
    }

    @Test public void cohortOrdinalShapeAndReentrantFenceCannotManufactureSecondGroupAuthority() {
        for (String action : new String[]{"stop", "close", "hard", "scope", "disable", "protection"}) {
            Harness h = cohortHarness(); CensorEngine.NativeTextInput input = h.nativeInputs.get(0);
            NativeTextResult.Candidate a = observed(input, 0, 70, NODE, 1000, 65).candidates.get(0);
            NativeTextResult.Candidate b = observed(input, 0, 100, OTHER_NODE, 1200, 95).candidates.get(0);
            NativeTextResult early = cohort(input, 70, a), terminal = cohort(input, 100, a, b);
            h.now = 80; assertFalse(h.nativeCompletions.get(0).observe(2, early));
            h.afterNativeAccepted = () -> fence(h, action);
            assertFalse(h.nativeCompletions.get(0).observe(1, early)); assertTrue(input.cancelled());
            h.now = 110; assertNull(h.nativeCompletions.get(0).admit(terminal)); assertEquals(1, h.nativeFrames.size());
            h.afterNativeAccepted = () -> { }; h.close(); assertEquals(1, h.drained);
        }
    }

    private static Harness cohortHarness() {
        Harness h = new Harness(); h.nativeBeginMode = NativeTextResult.ReadMode.ADMITTED_NODE_COHORT_RECLASSIFY;
        h.engine.executionControls(334, false, EnumSet.noneOf(SceneEngine.Lane.class));
        h.engine.start(42, ProtectionScope.normal(9)); h.fire(0); h.nativeExecutor.runAll(); return h;
    }
    private static NativeTextResult cohort(CensorEngine.NativeTextInput input, long end, NativeTextResult.Candidate... candidates) {
        return new NativeTextResult(1000, 2400, 7, 0, end, java.util.Arrays.asList(candidates), 2, 2, candidates.length,
                NativeTextResult.Coverage.POSITIVE_NODES_ONLY, input.scanGeneration(), input.scanGeneration(), input.hardGeneration,
                10, 58, input.readMode());
    }

    @Test public void selectedAcquisitionModeCannotBeRetaggedByASecondBeginOrAResult() {
        Harness h = new Harness(); h.engine.start(42); h.fire(0); h.nativeExecutor.runAll();
        CensorEngine.NativeTextInput input = h.nativeInputs.get(0);
        assertEquals(NativeTextResult.ReadMode.FULL_DISCOVERY, input.readMode());
        assertEquals(-1, input.beginRead(NativeTextResult.ReadMode.ADMITTED_NODE_RECLASSIFY));
        assertEquals(NativeTextResult.ReadMode.FULL_DISCOVERY, input.readMode());
        NativeTextResult read = typedRead(0, 70, input.scanGeneration(), input.scanGeneration(), input.hardGeneration, true, 65);
        NativeTextResult direct = new NativeTextResult(read.width, read.height, read.windowId, 0, 70, read.candidates,
                1, 1, 1, read.coverage, read.scanGeneration, read.confirmationGeneration, read.hardGeneration,
                read.classificationCompletedAtMillis, read.confirmationStartedAtMillis, NativeTextResult.ReadMode.ADMITTED_NODE_RECLASSIFY);
        h.now = 80; assertNull(h.nativeCompletions.get(0).admit(direct)); assertTrue(h.nativeFrames.isEmpty()); h.close();
    }

    @Test public void actualSelectedDirectModeAdmitsOnlyPartialNonconsumingFacts() {
        Harness h = new Harness(); h.nativeBeginMode = NativeTextResult.ReadMode.ADMITTED_NODE_RECLASSIFY;
        h.engine.start(42); h.fire(0); h.nativeExecutor.runAll(); CensorEngine.NativeTextInput input = h.nativeInputs.get(0);
        NativeTextResult original = typedRead(0, 70, input.scanGeneration(), input.scanGeneration(), input.hardGeneration, true, 65);
        NativeTextResult direct = new NativeTextResult(original.width, original.height, original.windowId, 0, 70, original.candidates,
                1, 1, 1, original.coverage, original.scanGeneration, original.confirmationGeneration, original.hardGeneration,
                original.classificationCompletedAtMillis, original.confirmationStartedAtMillis, NativeTextResult.ReadMode.ADMITTED_NODE_RECLASSIFY);
        h.now = 80; assertNotNull(h.nativeCompletions.get(0).admit(direct)); h.ui.runAll();
        assertEquals(NativeTextResult.ReadMode.ADMITTED_NODE_RECLASSIFY, h.nativeFrames.get(0).read.readMode);
        assertFalse(h.engine.imageTextSuppressed()); assertTrue(h.engine.claimEvidence(h.evidence.get(0)).subjects.isEmpty());
        assertEquals(CensorEngine.CoverageState.NO_ELIGIBLE_REGIONS, h.engine.viewportCoverage().state); h.close();
    }

    @Test public void terminalCostsCannotBorrowAnotherAcquisitionMode() {
        Harness h = new Harness(); h.engine.start(42); h.fire(0); h.nativeExecutor.runAll();
        CensorEngine.NativeTextInput input = h.nativeInputs.get(0); CensorEngine.NativeTextCompletion completion = h.nativeCompletions.get(0);
        completion.costStarted(new NativeReadCost.Start(input.identity, input.epoch, input.geometry, input.hardGeneration, 0));
        h.now = 100; NativeReadCost original = cost(input, 0, 100);
        NativeReadCost foreign = new NativeReadCost(original.request, original.epoch, original.geometry, original.hardGeneration,
                original.scanGeneration, 0, 0, 100, 100, 100, NativeReadCost.Terminal.SUCCESS, original.costs,
                NativeTextResult.ReadMode.ADMITTED_NODE_RECLASSIFY);
        assertThrows(IllegalArgumentException.class, () -> completion.cost(foreign)); assertTrue(h.nativeCosts.isEmpty());
        completion.failure(); h.close(); assertEquals(1, h.drained);
    }
    @Test public void explicitAddressAdmissionBindsOnlyTheExactRequestAndExactAdmittedRead() {
        Harness h = new Harness(); h.engine.start(42); h.fire(0); h.nativeExecutor.runAll();
        CensorEngine.NativeTextInput input = h.nativeInputs.get(0);
        NativeTextResult read = typedRead(0, 70, input.scanGeneration(), input.scanGeneration(), input.hardGeneration, true, 65);
        h.now = 80; CensorEngine.NativeTextAdmission admission = h.nativeCompletions.get(0).admit(read);
        assertNotNull(admission); assertSame(h.nativeFrames.get(0), admission.frame); assertTrue(admission.matches(input, read));
        assertFalse(admission.matches(input, typedRead(0, 70, input.scanGeneration(), input.scanGeneration(), input.hardGeneration, true, 65)));
        int timers = h.timers.size(); assertNull(h.nativeCompletions.get(0).admit(read)); assertEquals(timers, h.timers.size());
        assertEquals(1, h.nativeFrames.size()); assertEquals(1, h.evidence.size());
        h.fire(200); h.nativeExecutor.runAll(); assertFalse(admission.matches(h.nativeInputs.get(1), read));
        h.close(); assertEquals(1, h.drained);
    }

    @Test public void fencedStoppedClosedOrDisabledReadCannotAuthorizeAnAddress() {
        for (String fence : new String[]{"hard", "scope", "stop", "close", "disable", "protection"}) {
            Harness h = new Harness(); h.engine.start(42); h.fire(0); h.nativeExecutor.runAll();
            CensorEngine.NativeTextInput input = h.nativeInputs.get(0);
            NativeTextResult read = typedRead(0, 70, input.scanGeneration(), input.scanGeneration(), input.hardGeneration, true, 65);
            if (fence.equals("hard")) h.engine.requestNativeTextAt(0, CensorEngine.NativeTextChange.HARD_CONTENT);
            else if (fence.equals("scope")) h.engine.changeScope(43);
            else if (fence.equals("stop")) h.engine.stop();
            else if (fence.equals("close")) h.engine.close();
            else if (fence.equals("disable")) h.engine.nativeTextControls(false);
            else h.engine.changeScope(42, ProtectionScope.normal(99));
            h.now = 80; assertNull(fence, h.nativeCompletions.get(0).admit(read));
            assertTrue(h.nativeFrames.isEmpty()); assertTrue(h.evidence.isEmpty()); h.close(); assertEquals(1, h.drained);
        }
    }

    @Test public void staleAgeWrongProvenanceAndSceneCapacityCannotAuthorizeRetainedHandles() {
        for (String refusal : new String[]{"age", "provenance", "capacity"}) {
            Harness h = new Harness(16, 2, refusal.equals("capacity") ? 1 : 100);
            h.engine.start(42); h.fire(0); h.nativeExecutor.runAll();
            CensorEngine.NativeTextInput input = h.nativeInputs.get(0);
            NativeTextResult read = typedRead(0, 70, input.scanGeneration(), input.scanGeneration(),
                    input.hardGeneration + (refusal.equals("provenance") ? 1 : 0), true, 65);
            if (refusal.equals("capacity")) {
                List<NativeTextResult.Candidate> candidates = new ArrayList<>(read.candidates);
                candidates.add(new NativeTextResult.Candidate("TEXT_SMUT", CONTENT,
                        "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd", .9f, true,
                        Collections.singletonList(region(1200)), NativeTextResult.TextSource.TEXT, 0, 4, 4, 65));
                read = new NativeTextResult(1000, 2400, 7, 0, 70, candidates, 2, 2, 2,
                        NativeTextResult.Coverage.POSITIVE_NODES_ONLY, input.scanGeneration(), input.scanGeneration(),
                        input.hardGeneration, 10, 58);
            }
            h.now = refusal.equals("age") ? 1200 : 80; assertNull(refusal, h.nativeCompletions.get(0).admit(read));
            assertTrue(h.nativeFrames.isEmpty()); assertTrue(h.evidence.isEmpty()); h.close(); assertEquals(1, h.drained);
        }
    }

    @Test public void reentrantAcceptedObserverFenceCannotReturnAnAddressAuthorization() {
        for (String fence : new String[]{"hard", "scope", "stop", "close", "disable", "protection"}) {
            Harness h = new Harness(); h.engine.start(42); h.fire(0); h.nativeExecutor.runAll();
            CensorEngine.NativeTextInput input = h.nativeInputs.get(0);
            NativeTextResult read = typedRead(0, 70, input.scanGeneration(), input.scanGeneration(), input.hardGeneration, true, 65);
            h.afterNativeAccepted = () -> {
                if (fence.equals("hard")) h.engine.requestNativeTextAt(h.now, CensorEngine.NativeTextChange.HARD_CONTENT);
                else if (fence.equals("scope")) h.engine.changeScope(43);
                else if (fence.equals("stop")) h.engine.stop();
                else if (fence.equals("close")) h.engine.close();
                else if (fence.equals("disable")) h.engine.nativeTextControls(false);
                else h.engine.changeScope(42, ProtectionScope.normal(99));
            };
            h.now = 80; assertNull(fence, h.nativeCompletions.get(0).admit(read));
            assertEquals("Historical Scene admission did happen before the observer fence", 1, h.nativeFrames.size());
            h.afterNativeAccepted = () -> { }; h.close(); assertEquals(1, h.drained);
        }
    }

    @Test public void dirtyTreeDoesNotTurnHistoricalAddressAdmissionIntoWholeTreeAuthority() {
        Harness h = new Harness(); h.engine.start(42); h.fire(0); h.nativeExecutor.runAll();
        CensorEngine.NativeTextInput input = h.nativeInputs.get(0);
        NativeTextResult read = typedRead(0, 70, input.scanGeneration(), input.scanGeneration(), input.hardGeneration, false, 65);
        CensorEngine.ViewportCoverage[] before = {null};
        h.afterNativeAccepted = () -> {
            before[0] = h.engine.viewportCoverage();
            h.engine.requestNativeTextAt(h.now, CensorEngine.NativeTextChange.TREE_DIRTY);
        };
        h.now = 80; CensorEngine.NativeTextAdmission admission = h.nativeCompletions.get(0).admit(read);
        assertNotNull(admission); assertTrue(admission.matches(input, read)); assertFalse(h.engine.imageTextSuppressed());
        assertFalse(h.engine.coverageCurrent(before[0]));
        assertEquals(CensorEngine.CoverageState.NO_ELIGIBLE_REGIONS, h.engine.viewportCoverage().state);
        h.close(); assertEquals(1, h.drained);
    }

    @Test public void partialAddressAdmissionCannotGrantDwellCountingOrGlobalOcrSuppression() {
        Harness h = new Harness(); h.engine.start(42); h.fire(0); h.nativeExecutor.runAll();
        CensorEngine.NativeTextInput input = h.nativeInputs.get(0);
        NativeTextResult read = typedRead(0, 70, input.scanGeneration(), input.scanGeneration(), input.hardGeneration, true, 65);
        h.now = 80; assertNotNull(h.nativeCompletions.get(0).admit(read)); h.ui.runAll();
        assertEquals(CensorEngine.CoverageState.NO_ELIGIBLE_REGIONS, h.engine.viewportCoverage().state);
        assertFalse(h.engine.imageTextSuppressed()); assertTrue(h.engine.claimEvidence(h.evidence.get(0)).subjects.isEmpty());
        h.close(); assertEquals(1, h.drained);
    }

    @Test public void stablePartialRenewalCannotBorrowCompleteAuthorityOrRenewAnUnobservedSibling() {
        Harness h = new Harness(16, 2, 100, true);
        h.engine.start(42); h.fire(0); h.nativeExecutor.runAll();
        CensorEngine.NativeTextInput initial = h.nativeInputs.get(0);
        h.now = 80; assertNotNull(h.nativeCompletions.get(0).admit(scheduledRead(initial, 0, false)));
        h.ui.runAll(); NativeTextFrame complete = h.nativeFrames.get(0);
        h.deliverImage(0, 90, 1000, 2400); h.textExecutor.runAll();
        // The host gates OCR dispatch; this pure worker deliberately bypasses that gate to
        // exercise the engine's independent result-admission fence as well.
        assertEquals(1, h.textInputs.size()); assertTrue(h.engine.imageTextSuppressed());
        CensorEngine.Input<Pixel> suppressedOcr = h.textInputs.get(0);
        h.textCompletions.get(0).success(SceneEngine.Batch.imageText(suppressedOcr.source, 3,
                Collections.singletonList(new SceneEngine.Observation("TEXT_SMUT", 3, 1, region(400), .9f))),
                new CensorEngine.WorkTiming(1, 1, 1));
        assertEquals(1, h.evidence.size());
        CensorEngine.ViewportCoverage before = h.engine.viewportCoverage();
        assertEquals(CensorEngine.CoverageState.AVAILABLE, before.state); assertEquals(2, before.regions.size());

        h.nativeBeginMode = NativeTextResult.ReadMode.ADMITTED_NODE_RECLASSIFY;
        h.fire(200); h.nativeExecutor.runAll(); CensorEngine.NativeTextInput direct = h.nativeInputs.get(1);
        assertEquals(initial.scanGeneration(), direct.scanGeneration());
        h.now = 280; assertNotNull(h.nativeCompletions.get(1).admit(scheduledRead(direct, 200, true))); h.ui.runAll();
        NativeTextFrame partial = h.nativeFrames.get(1);
        assertFalse(h.engine.coverageCurrent(before)); assertFalse(h.engine.imageTextSuppressed());
        CensorEngine.ViewportCoverage gap = h.engine.viewportCoverage();
        assertEquals(CensorEngine.CoverageState.NO_ELIGIBLE_REGIONS, gap.state);
        assertTrue(h.engine.claimEvidence(h.evidence.get(1)).subjects.isEmpty());
        assertFalse(h.engine.dwellPresent(h.evidence.get(1), gap, gap.regions));
        try (CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView()) {
            assertEquals(2, masks(view).size());
            for (SceneEngine.Mask mask : masks(view))
                assertSame(mask.displayRegion.top == 1000 ? partial.source : complete.source, mask.evidenceSource);
        }

        h.fire(334); h.deliverImage(1, 350, 1000, 2400); h.textExecutor.runAll();
        assertEquals(2, h.textInputs.size()); assertEquals(350, h.textInputs.get(1).source.receivedAtMillis);
        h.now = 1066; // No producer wake: only the sibling's original observation has expired.
        try (CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView()) {
            assertEquals(1, masks(view).size()); assertEquals(1000, masks(view).get(0).displayRegion.top, 0);
            assertSame(partial.source, masks(view).get(0).evidenceSource);
        }

        h.nativeBeginMode = NativeTextResult.ReadMode.FULL_DISCOVERY;
        h.fire(1100); h.nativeExecutor.runAll(); CensorEngine.NativeTextInput fresh = h.nativeInputs.get(2);
        h.now = 1180; assertNotNull(h.nativeCompletions.get(2).admit(scheduledRead(fresh, 1100, false))); h.ui.runAll();
        assertTrue(h.engine.imageTextSuppressed()); assertEquals(2, h.engine.viewportCoverage().regions.size());
        assertEquals(CensorEngine.CoverageState.AVAILABLE, h.engine.viewportCoverage().state);
        assertFalse(h.evidence.get(2).subjects.isEmpty());
        int receipts = h.evidence.size(); CensorEngine.Input<Pixel> oldOcr = h.textInputs.get(1);
        h.textCompletions.get(1).success(SceneEngine.Batch.imageText(oldOcr.source, 3,
                Collections.singletonList(new SceneEngine.Observation("TEXT_SMUT", 3, 1, region(400), .9f))),
                new CensorEngine.WorkTiming(1, 1, 1));
        assertEquals(receipts, h.evidence.size());
        try (CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView()) {
            assertEquals(2, masks(view).size());
            for (SceneEngine.Mask mask : masks(view)) assertTrue(mask.nativeValidated);
        }
        h.deliverImage(2, 1190, 1000, 2400); h.textExecutor.runAll(); assertEquals(3, h.textInputs.size());
        assertTrue(h.engine.imageTextSuppressed());
        h.close(); assertEquals(1, h.drained);
    }

    private static NativeTextResult scheduledRead(CensorEngine.NativeTextInput input, long start, boolean partial) {
        List<NativeTextResult.Candidate> candidates = new ArrayList<>();
        candidates.add(new NativeTextResult.Candidate("TEXT_SMUT", CONTENT, NODE, .9f, true,
                Collections.singletonList(region(1000)), NativeTextResult.TextSource.TEXT, 0, 4, 4, start + 65));
        if (!partial) candidates.add(new NativeTextResult.Candidate("TEXT_SMUT", CONTENT,
                "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd", .9f, true,
                Collections.singletonList(region(1200)), NativeTextResult.TextSource.TEXT, 0, 4, 4, start + 65));
        return new NativeTextResult(1000, 2400, 7, start, start + 70, candidates, candidates.size(), candidates.size(),
                candidates.size(), partial ? NativeTextResult.Coverage.POSITIVE_NODES_ONLY : NativeTextResult.Coverage.COMPLETE_TREE,
                input.scanGeneration(), input.scanGeneration(), input.hardGeneration, start + 10, start + 58, input.readMode());
    }

    @Test public void controlledWakesRunOnlyDueUncancelledTasksInDeadlineOrder() {
        Harness h = new Harness(); List<Integer> order = new ArrayList<>();
        h.timers.add(new Timed(10, () -> order.add(10)));
        h.timers.add(new Timed(5, () -> { order.add(5); h.timers.add(new Timed(7, () -> order.add(7))); }));
        Timed cancelled = new Timed(1, () -> fail("Cancelled task ran")); cancelled.cancelled = true; h.timers.add(cancelled);
        h.timers.add(new Timed(20, () -> order.add(20)));
        h.fire(10); assertEquals(java.util.Arrays.asList(5, 7, 10), order); assertEquals(10, h.now);
        h.fire(20); assertEquals(java.util.Arrays.asList(5, 7, 10, 20), order); h.close();
    }

    @Test public void legacyCompletionMayPublishButCannotAuthorizeRetainedHandles() {
        final int[] calls = {0};
        CensorEngine.NativeTextCompletion legacy = new CensorEngine.NativeTextCompletion() {
            @Override public void success(NativeTextResult read) { calls[0]++; }
            @Override public void failure() { fail(); }
            @Override public void cancelled() { fail(); }
            @Override public void resourceFailure() { fail(); }
        };
        assertNull(legacy.admit(read(0, 70, 1000, 2400, 1000))); assertEquals(1, calls[0]);
    }

    private static NativeReadCost cost(CensorEngine.NativeTextInput input, long invoked, long end) {
        List<NativeReadCost.Cost> costs = new ArrayList<>();
        for (NativeReadCost.Stage stage : NativeReadCost.Stage.values()) costs.add(new NativeReadCost.Cost(stage, 1, 1, 1, 0));
        return new NativeReadCost(input.identity, input.epoch, input.geometry, input.hardGeneration, input.scanGeneration(),
                invoked, invoked, end, end, 100, NativeReadCost.Terminal.SUCCESS, costs);
    }
    @Test public void invocationAndTerminalCostsCannotCreateEvidenceOrRenewScheduling() {
        Harness h = new Harness(); h.engine.start(42); h.fire(0); h.nativeExecutor.runAll();
        CensorEngine.NativeTextInput input = h.nativeInputs.get(0); CensorEngine.NativeTextCompletion completion = h.nativeCompletions.get(0);
        completion.costStarted(new NativeReadCost.Start(input.identity, input.epoch, input.geometry, input.hardGeneration, 0));
        h.now = 100; completion.cost(cost(input, 0, 100));
        assertEquals(1, h.nativeCosts.size()); assertTrue(h.nativeFrames.isEmpty()); assertTrue(h.evidence.isEmpty());
        assertTrue(h.engine.nativeTextInFlight()); assertEquals(0, h.engine.nativeCostDiagnosticFailures());
        assertThrows(IllegalArgumentException.class, () -> completion.cost(cost(input, 0, 100)));
        h.close(); assertEquals(1, h.drained);
    }
    @Test public void diagnosticFailureCannotPreventTheRealReadTerminal() {
        Harness h = new Harness(); h.engine.start(42); h.fire(0); h.nativeExecutor.runAll();
        CensorEngine.NativeTextInput input = h.nativeInputs.get(0); CensorEngine.NativeTextCompletion completion = h.nativeCompletions.get(0);
        completion.costStarted(new NativeReadCost.Start(input.identity, input.epoch, input.geometry, input.hardGeneration, 0));
        h.throwNativeCost = true; h.now = 80; completion.cost(cost(input, 0, 70));
        assertEquals(1, h.engine.nativeCostDiagnosticFailures());
        h.finishNative(0, 70, 80, 1000); h.ui.runAll(); assertEquals(1, h.nativeFrames.size());
        h.close(); assertEquals(1, h.drained);
    }
    @Test public void foreignInvocationOrTerminalRequestCannotBeReportedAsThisOwner() {
        Harness h = new Harness(); h.engine.start(42); h.fire(0); h.nativeExecutor.runAll();
        CensorEngine.NativeTextInput input = h.nativeInputs.get(0); CensorEngine.NativeTextCompletion completion = h.nativeCompletions.get(0);
        assertThrows(IllegalArgumentException.class, () -> completion.costStarted(new NativeReadCost.Start(input.identity + 1,
                input.epoch, input.geometry, input.hardGeneration, 0)));
        h.now = 100; assertThrows(IllegalArgumentException.class, () -> completion.cost(cost(input, 0, 100)));
        assertTrue(h.nativeCosts.isEmpty()); assertTrue(h.nativeFrames.isEmpty()); h.close();
    }
    @Test public void imageMotionBasisCannotConsumeTheReservationAlreadyHeldByUnselectedNativeOrigins() {
        Harness h = retainedNativeOrigins(false);
        SceneEngine.Source from = h.images.get(0).source, to = h.images.get(1).source;
        assertTrue(h.engine.motion(new SceneEngine.Motion(h.engine.currentEpoch(), from.sequence, to.sequence, 1, 1,
                SceneEngine.MotionAuthority.IMAGE_CORRESPONDENCE, new SceneEngine.Region(0, 0, 1000, 2400), 0, -50)));
        assertEquals("Correspondence may be admitted, but optional image metadata is refused", 0, h.engine.renderBasisCount());
        assertEquals(1, h.engine.basisAdmissionDrops()); h.close(); assertEquals(1, h.drained);
    }

    @Test public void imageOcrBasisCannotConsumeTheReservationAlreadyHeldByUnselectedNativeOrigins() {
        Harness h = retainedNativeOrigins(true);
        h.textCompletions.get(0).cancelled(); h.textExecutor.runAll();
        CensorEngine.Input<Pixel> input = h.textInputs.get(1);
        h.textCompletions.get(1).success(SceneEngine.Batch.imageText(input.source, 3,
                Collections.singletonList(new SceneEngine.Observation("TEXT_SMUT", 3, 1, region(400), .9f))),
                new CensorEngine.WorkTiming(1, 1, 1));
        assertEquals(0, h.engine.renderBasisCount()); assertEquals(1, h.engine.basisAdmissionDrops());
        h.close(); assertEquals(1, h.drained);
    }

    private static Harness retainedNativeOrigins(boolean ocr) {
        Harness h = new Harness(12, 2, 100, ocr); h.engine.start(42); h.fire(0);
        h.deliverImage(0, 5, 1000, 2400); h.imageExecutor.runAll(); h.finishImage(0, 6, 500); h.ui.runAll();
        h.nativeExecutor.runAll(); long start = h.nativeInputsStarted(0); CensorEngine.NativeTextInput initial = h.nativeInputs.get(0);
        NativeTextResult.Candidate first = new NativeTextResult.Candidate("TEXT_SMUT", CONTENT, NODE, .9f, true,
                Collections.singletonList(region(1000)), NativeTextResult.TextSource.TEXT, 0, 4, 4, start + 65);
        NativeTextResult.Candidate second = new NativeTextResult.Candidate("TEXT_SMUT", CONTENT,
                "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd", .9f, true,
                Collections.singletonList(region(1200)), NativeTextResult.TextSource.TEXT, 0, 4, 4, start + 65);
        h.now = start + 80; h.nativeCompletions.get(0).success(new NativeTextResult(1000, 2400, 7, start, start + 70,
                java.util.Arrays.asList(first, second), 2, 2, 2, NativeTextResult.Coverage.COMPLETE_TREE,
                initial.scanGeneration(), initial.scanGeneration(), initial.hardGeneration, start + 10, start + 58)); h.ui.runAll();
        h.fire(start + 200); h.nativeExecutor.runAll(); long next = h.nativeInputsStarted(1); CensorEngine.NativeTextInput partial = h.nativeInputs.get(1);
        h.now = next + 80; h.nativeCompletions.get(1).success(typedRead(next, next + 70, partial.scanGeneration(),
                partial.scanGeneration(), partial.hardGeneration, true, next + 65)); h.ui.runAll();
        h.fire(334); h.deliverImage(1, 350, 1000, 2400); h.imageExecutor.runAll(); h.finishImage(1, 360, 500); h.ui.runAll();
        h.textExecutor.runAll();
        assertEquals(2, h.nativeFrames.size()); assertFalse(h.engine.imageTextSuppressed());
        return h;
    }

    @Test public void dirtyTreeCannotRetagOriginalScanAsTheLatestRevisionForCompleteOrPartialFacts() {
        for (boolean partial : new boolean[]{false, true}) {
            Harness h = new Harness(); h.engine.executionControls(334, false, EnumSet.noneOf(SceneEngine.Lane.class));
            h.engine.start(42); h.fire(0); h.nativeExecutor.runAll();
            CensorEngine.NativeTextInput input = h.nativeInputs.get(0); long original = input.scanGeneration();
            h.now = 20; h.engine.requestNativeTextAt(20, CensorEngine.NativeTextChange.TREE_DIRTY);
            assertEquals(original, input.scanGeneration()); assertEquals(original + 1, input.treeGeneration());
            h.now = 80; h.nativeCompletions.get(0).success(typedRead(0, 70, original + 1, original + 1, input.hardGeneration, partial, 65));
            assertTrue(h.nativeFrames.isEmpty()); assertTrue(h.evidence.isEmpty()); assertFalse(h.engine.imageTextSuppressed()); h.close();
        }
    }

    @Test public void hardFenceResetsUnconsumedWitnessesButDoesNotReplayAnAlreadyConsumedContinuingNode() {
        for (boolean consumed : new boolean[]{false, true}) {
            Harness h = fiveReads(); CensorEngine.EvidenceReceipt before = h.evidence.get(4);
            if (consumed) assertEquals(1, h.engine.claimEvidence(before).subjects.size());
            h.engine.requestNativeTextAt(h.now, CensorEngine.NativeTextChange.HARD_CONTENT);
            h.fire(700); h.nativeExecutor.runAll(); h.finishNative(5, 710, 720, 1000); h.ui.runAll();
            CensorEngine.EvidenceReceipt after = h.evidence.get(5);
            assertEquals(1, after.subjects.get(0).witnesses.size()); assertFalse(after.subjects.get(0).confirmedForConsumption);
            assertEquals(consumed, after.subjects.get(0).alreadyConsumed);
            assertTrue(h.engine.evidenceCurrent(after)); assertTrue(h.engine.claimEvidence(after).subjects.isEmpty()); h.close();
        }
    }

    @Test public void dirtyTreeRevokesWholeAuthorityWithoutCancellingTheHeldNativeOwner() {
        Harness h = fiveReads(); CensorEngine.EvidenceReceipt complete = h.evidence.get(4);
        CensorEngine.ViewportCoverage old = h.engine.viewportCoverage();
        h.fire(700); h.nativeExecutor.runAll(); assertTrue(h.engine.nativeTextInFlight());
        h.engine.requestNativeTextAt(700, CensorEngine.NativeTextChange.TREE_DIRTY);
        assertEquals(0, h.nativeCancellations); assertTrue(h.engine.nativeTextInFlight());
        assertFalse(h.engine.coverageCurrent(old)); assertFalse(h.engine.imageTextSuppressed());
        assertFalse(h.engine.evidenceCurrent(complete)); assertNull(h.engine.claimEvidence(complete));
        try (CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView()) { assertEquals(1, masks(view).size()); }
        h.close(); assertEquals(1, h.drained);
    }

    @Test public void individuallyClassifiedPartialSurvivesRepeatedSoftRevisionsButNotAHardFence() {
        Harness h = new Harness(); h.engine.executionControls(334, false, EnumSet.noneOf(SceneEngine.Lane.class));
        h.engine.start(42); h.fire(0); h.nativeExecutor.runAll();
        long scan = h.nativeInputs.get(0).treeGeneration(), hard = h.nativeInputs.get(0).hardGeneration;
        for (int i = 1; i <= 4; i++) { h.now = i * 10; h.engine.requestNativeTextAt(h.now, CensorEngine.NativeTextChange.TREE_DIRTY); }
        assertEquals(0, h.nativeCancellations); assertEquals(scan + 4, h.nativeInputs.get(0).treeGeneration());
        h.now = 80; h.nativeCompletions.get(0).success(typedRead(0, 70, scan, scan + 4, hard, true, 65)); h.ui.runAll();
        assertEquals(1, h.nativeFrames.size()); assertEquals(1, h.evidence.size());
        CensorEngine.EvidenceReceipt partial = h.evidence.get(0); assertTrue(h.engine.evidenceCurrent(partial));
        h.engine.requestNativeTextAt(80, CensorEngine.NativeTextChange.TREE_DIRTY);
        assertTrue(h.engine.evidenceCurrent(partial)); assertTrue(h.engine.claimEvidence(partial).subjects.isEmpty());
        h.engine.requestNativeTextAt(80, CensorEngine.NativeTextChange.HARD_CONTENT);
        assertFalse(h.engine.evidenceCurrent(partial)); assertNull(h.engine.claimEvidence(partial)); h.close();
    }

    @Test public void dirtyRevisionCannotBeRelabelledAsACompleteOrLegacyUnknownRead() {
        for (boolean legacy : new boolean[]{false, true}) {
            Harness h = new Harness(); h.engine.executionControls(334, false, EnumSet.noneOf(SceneEngine.Lane.class));
            h.engine.start(42); h.fire(0); h.nativeExecutor.runAll();
            long scan = h.nativeInputs.get(0).treeGeneration(), hard = h.nativeInputs.get(0).hardGeneration;
            h.now = 20; h.engine.requestNativeTextAt(20, CensorEngine.NativeTextChange.TREE_DIRTY);
            h.now = 80; h.nativeCompletions.get(0).success(legacy ? read(0, 70, 1000, 2400, 1000)
                    : typedRead(0, 70, scan, scan, hard, false, 65));
            assertTrue(h.nativeFrames.isEmpty()); assertTrue(h.evidence.isEmpty()); assertFalse(h.engine.imageTextSuppressed());
            assertEquals(0, h.nativeCancellations); h.close();
        }
    }

    @Test public void typedPartialCannotBorrowAnotherHardRevisionOrAFutureSoftRevision() {
        for (boolean futureHard : new boolean[]{false, true}) {
            Harness h = new Harness(); h.engine.executionControls(334, false, EnumSet.noneOf(SceneEngine.Lane.class));
            h.engine.start(42); h.fire(0); h.nativeExecutor.runAll();
            long scan = h.nativeInputs.get(0).treeGeneration(), hard = h.nativeInputs.get(0).hardGeneration;
            h.now = 80; h.nativeCompletions.get(0).success(typedRead(0, 70, scan,
                    scan + (futureHard ? 0 : 1), hard + (futureHard ? 1 : 0), true, 65));
            assertTrue(h.nativeFrames.isEmpty()); assertTrue(h.evidence.isEmpty()); h.close();
        }
    }

    @Test public void hardContentFenceRevokesAnUnclaimedFiveReadNativeCapability() {
        Harness h = fiveReads(); CensorEngine.EvidenceReceipt receipt = h.evidence.get(4);
        assertTrue(receipt.subjects.get(0).confirmedForConsumption);
        h.engine.requestNativeTextAt(h.now, true);
        assertNull(h.engine.claimEvidence(receipt)); h.close(); assertEquals(1, h.drained);
    }

    @Test public void hardContentFenceCannotKeepACommittedNativeClaimCurrentForLaterEffects() {
        Harness h = fiveReads(); CensorEngine.EvidenceReceipt receipt = h.evidence.get(4);
        CensorEngine.EvidenceClaim committed = h.engine.claimEvidence(receipt, h);
        assertEquals(1, committed.takeFor(h).size());
        h.engine.requestNativeTextAt(h.now, true);
        assertFalse(h.engine.evidenceCurrent(receipt));
        assertNull(h.engine.claimEvidence(receipt, h)); assertNull(committed.takeFor(h)); h.close();
    }

    @Test public void hardContentFenceImmediatelyRevokesOldGlobalOcrSuppression() {
        Harness h = new Harness(); h.engine.executionControls(334, false, EnumSet.noneOf(SceneEngine.Lane.class));
        h.engine.start(42); h.fire(0); h.nativeExecutor.runAll(); h.finishNative(0, 10, 20, 1000);
        assertTrue(h.engine.imageTextSuppressed()); h.engine.requestNativeTextAt(20, true);
        assertFalse(h.engine.imageTextSuppressed()); h.close();
    }

    @Test public void typedPartialNativeCanPaintButCannotEnterViewportDwellAuthority() {
        Harness h = new Harness(); h.engine.executionControls(334, false, EnumSet.noneOf(SceneEngine.Lane.class));
        h.engine.start(42); h.fire(0); h.nativeExecutor.runAll();
        h.now = 80; h.nativeCompletions.get(0).success(typedRead(0, 70, 1, 1, 1, true, 65)); h.ui.runAll();
        assertEquals(1, h.nativeFrames.size()); assertEquals(1, h.evidence.size());
        try (CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView()) { assertEquals(1, masks(view).size()); }
        CensorEngine.ViewportCoverage coverage = h.engine.viewportCoverage();
        assertEquals(CensorEngine.CoverageState.NO_ELIGIBLE_REGIONS, coverage.state); assertEquals(1, coverage.ineligibleMembers);
        assertFalse(h.engine.observationCurrent(h.evidence.get(0), coverage));
        assertFalse(h.engine.imageTextSuppressed()); assertTrue(h.engine.claimEvidence(h.evidence.get(0)).subjects.isEmpty());
        h.close(); assertEquals(1, h.drained);
    }

    private static Harness fiveReads() {
        Harness h = new Harness(); h.engine.executionControls(334, false, EnumSet.noneOf(SceneEngine.Lane.class)); h.engine.start(42);
        for (int i = 0; i < 5; i++) {
            long at = i * 140L; h.fire(at); h.nativeExecutor.runAll(); h.finishNative(i, at + 10, at + 20, 1000); h.ui.runAll();
        }
        return h;
    }
    private static NativeTextResult typedRead(long start, long finish, long scan, long confirm, long hard,
            boolean partial, long validated) {
        NativeTextResult.Candidate candidate = new NativeTextResult.Candidate("TEXT_SMUT", CONTENT, NODE, .9f, true,
                Collections.singletonList(region(1000)), NativeTextResult.TextSource.TEXT, 0, 4, 4, validated);
        return new NativeTextResult(1000, 2400, 7, start, finish, Collections.singletonList(candidate), 1, 1, 1,
                partial ? NativeTextResult.Coverage.POSITIVE_NODES_ONLY : NativeTextResult.Coverage.COMPLETE_TREE,
                scan, confirm, hard, start + 10, start + 58);
    }

    @Test public void stationaryNativeOnlyProducerRenewsWithRealReadAndUnchangedFiniteAge() {
        Harness h = new Harness(); h.engine.executionControls(334, false, EnumSet.noneOf(SceneEngine.Lane.class));
        h.engine.start(42); h.fire(0); h.nativeExecutor.runAll(); h.finishNative(0, 10, 20, 1000); h.ui.runAll();
        CensorEngine.ViewportCoverage original = h.engine.viewportCoverage();
        for (int i = 0; i < 20; i++) assertTrue(h.engine.coverageCurrent(original));
        h.fire(119); h.nativeExecutor.runAll(); assertEquals(1, h.nativeInputs.size());
        h.fire(120); h.nativeExecutor.runAll();
        assertEquals("Stationary renewal is independent of queries, images or explicit requests", 2, h.nativeInputs.size());
        assertEquals(1, h.nativeFrames.size()); assertEquals(1, h.evidence.size());
        assertTrue(h.engine.coverageCurrent(original));
        h.finishNative(1, 150, 160, 1000); h.ui.runAll();
        assertEquals(2, h.nativeFrames.size()); assertEquals(2, h.evidence.size());
        CensorEngine.ViewportCoverage renewed = h.engine.viewportCoverage();
        assertEquals(renewed.regions.get(0).evidenceSource.readFinishedAtMillis + 1001, renewed.validBeforeMillis);
        assertEquals(original.regions.get(0).identity, renewed.regions.get(0).identity);
        assertEquals(2, h.evidence.get(1).subjects.get(0).witnesses.size());
        assertTrue(h.captures.isEmpty()); assertTrue(h.images.isEmpty()); assertEquals(0, h.engine.livePixelFrames());
        h.close(); assertEquals(1, h.drained);
    }

    @Test public void heldRenewalLetsOldNativeEvidenceExpireWithoutInventingAnEmptyRead() {
        Harness h = new Harness(); h.engine.executionControls(334, false, EnumSet.noneOf(SceneEngine.Lane.class));
        h.engine.start(42); h.fire(0); h.nativeExecutor.runAll(); h.finishNative(0, 10, 20, 1000); h.ui.runAll();
        CensorEngine.ViewportCoverage old = h.engine.viewportCoverage();
        h.fire(140); h.nativeExecutor.runAll(); assertTrue(h.engine.nativeTextInFlight());
        h.fire(old.validBeforeMillis); h.ui.runAll();
        assertFalse(h.engine.coverageCurrent(old)); assertNull(h.engine.claimEvidence(h.evidence.get(0)));
        try (CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView()) { assertTrue(masks(view).isEmpty()); }
        assertEquals(1, h.evidence.size()); assertEquals(1, h.nativeFrames.size());
        h.fire(10000); h.nativeExecutor.runAll(); assertEquals("Only one actual held operation", 2, h.nativeInputs.size());
        h.close(); assertEquals(1, h.drained);
    }

    @Test public void failedBeforeBeginReadAndEarlierEventDemandCannotHotSpinRenewal() {
        for (boolean throwing : new boolean[]{false, true}) {
            Harness h = new Harness(); h.engine.executionControls(334, false, EnumSet.noneOf(SceneEngine.Lane.class));
            h.failNativeBeforeRead = !throwing; h.throwNativeBeforeRead = throwing;
            h.engine.start(42); h.fire(0); h.engine.requestNativeTextAt(0, false); h.nativeExecutor.runAll();
            assertEquals(1, h.nativeInputs.size()); assertFalse(h.engine.nativeTextInFlight());
            h.fire(119); h.nativeExecutor.runAll(); assertEquals(1, h.nativeInputs.size());
            h.fire(120); h.nativeExecutor.runAll(); assertEquals(2, h.nativeInputs.size());
            h.engine.requestNativeTextAt(0, false); h.fire(239); h.nativeExecutor.runAll(); assertEquals(2, h.nativeInputs.size());
            h.fire(240); h.nativeExecutor.runAll(); assertEquals(3, h.nativeInputs.size());
            assertTrue(h.nativeStartTimes.isEmpty()); assertTrue(h.evidence.isEmpty()); assertTrue(h.nativeFrames.isEmpty());
            h.close(); assertEquals(1, h.drained);
        }
    }

    @Test public void rejectedNativeExecutorUsesTerminalFloorAndOneRenewalTimer() {
        Harness h = new Harness(); h.engine.executionControls(334, false, EnumSet.noneOf(SceneEngine.Lane.class));
        h.nativeExecutor.reject = true; h.engine.start(42); h.fire(0);
        assertEquals(1, h.nativeExecutor.rejected); assertFalse(h.engine.nativeTextInFlight());
        h.engine.requestNativeTextAt(0, false); h.fire(119); assertEquals(1, h.nativeExecutor.rejected);
        h.fire(120); assertEquals(2, h.nativeExecutor.rejected);
        assertEquals(1, h.timers.stream().filter(t -> !t.ran && !t.cancelled).count());
        assertTrue(h.nativeInputs.isEmpty()); assertTrue(h.evidence.isEmpty()); h.close(); assertEquals(1, h.drained);
    }

    @Test public void nativeRenewalAndExplicitDemandCoalesceWithoutDuplicateTerminalAuthority() {
        Harness h = new Harness(); h.engine.executionControls(334, false, EnumSet.noneOf(SceneEngine.Lane.class));
        h.engine.start(42); h.fire(0); h.nativeExecutor.runAll(); h.finishNative(0, 10, 20, 1000);
        int timerCount = h.timers.size();
        for (int i = 0; i < 100; i++) h.engine.requestNativeTextAt(20, false);
        assertEquals(timerCount, h.timers.size());
        assertEquals(1, h.timers.stream().filter(t -> !t.ran && !t.cancelled && t.at == 120).count());
        h.now = 100;
        h.nativeCompletions.get(0).failure(); h.nativeCompletions.get(0).resourceFailure(); h.finishNative(0, 10, 100, 1000);
        assertEquals(timerCount, h.timers.size()); assertEquals(1, h.evidence.size()); assertTrue(h.engine.activeScope() != null);
        h.fire(140); h.nativeExecutor.runAll(); assertEquals(2, h.nativeInputs.size());
        assertEquals(140L, h.nativeStartTimes.get(1).longValue()); h.close(); assertEquals(1, h.drained);
    }

    @Test public void explicitNativeCancellationCannotEstablishAutonomousWork() {
        Harness h = new Harness(); h.engine.executionControls(334, false, EnumSet.noneOf(SceneEngine.Lane.class));
        h.engine.start(42); h.fire(0); h.nativeExecutor.runAll(); h.now = 10; h.nativeCompletions.get(0).cancelled();
        h.fire(10000); h.nativeExecutor.runAll(); assertEquals(1, h.nativeInputs.size());
        assertTrue(h.timers.stream().noneMatch(t -> !t.ran && !t.cancelled)); assertTrue(h.evidence.isEmpty());
        h.close(); assertEquals(1, h.drained);
    }

    @Test public void disabledStoppedClosedOrUnsafeNativeTerminalsCannotRenew() {
        for (int fence = 0; fence < 4; fence++) {
            Harness h = new Harness(); h.engine.executionControls(334, false, EnumSet.noneOf(SceneEngine.Lane.class));
            h.engine.start(42); h.fire(0); h.nativeExecutor.runAll(); h.now = 10;
            if (fence == 0) h.engine.nativeTextControls(false);
            else if (fence == 1) h.engine.stop();
            else if (fence == 2) h.engine.close();
            else h.nativeCompletions.get(0).resourceFailure();
            h.nativeCompletions.get(0).failure(); h.fire(10000); h.nativeExecutor.runAll();
            assertEquals(1, h.nativeInputs.size()); assertTrue(h.evidence.isEmpty());
            assertTrue(h.timers.stream().noneMatch(t -> !t.ran && !t.cancelled)); h.close();
            assertEquals(fence == 3 ? 0 : 1, h.drained);
        }
    }

    @Test public void staleGenerationTerminalUsesOnlyExistingNewContentDemand() {
        Harness h = new Harness(); h.engine.executionControls(334, false, EnumSet.noneOf(SceneEngine.Lane.class));
        h.engine.start(42); h.fire(0); h.nativeExecutor.runAll(); h.now = 10; h.engine.requestNativeTextAt(10, true);
        h.now = 20; h.nativeCompletions.get(0).failure(); h.fire(139); h.nativeExecutor.runAll(); assertEquals(1, h.nativeInputs.size());
        h.fire(140); h.nativeExecutor.runAll(); assertEquals(2, h.nativeInputs.size());
        h.nativeCompletions.get(1).cancelled(); h.fire(10000); h.nativeExecutor.runAll();
        assertEquals("Stale work and a cancelled successor do not create periodic demand", 2, h.nativeInputs.size());
        assertTrue(h.evidence.isEmpty()); h.close(); assertEquals(1, h.drained);
    }

    @Test public void imageResourceFailureKeepsRequestIdentityAfterIndependentNativeSource() {
        Harness h = new Harness(); h.engine.start(42); h.fire(0); h.nativeExecutor.runAll();
        h.finishNative(0, 10, 20, 1000); h.deliverImage(0, 30, 1000, 2400); h.imageExecutor.runAll();
        SceneEngine.Source source = h.images.get(0).source;
        assertEquals(2, source.sequence); assertEquals(1, source.imageRequestIdentity);
        h.imageCompletions.get(0).resourceFailure();
        CensorEngine.Trace failure = h.traces.stream().filter(t -> t.stage == CensorEngine.Stage.RESOURCE_FAILED)
                .findFirst().orElseThrow(AssertionError::new);
        assertEquals(source.epoch, failure.epoch); assertEquals(1, failure.source);
        assertEquals(-1, failure.pixelAgeMillis); assertNull(failure.work);
        h.close(); assertEquals(0, h.drained);
    }

    @Test public void nativeScrollObservationBarrierDoesNotWithdrawValidGeometryOrCountEvidence() {
        Harness h = new Harness(); h.engine.executionControls(334, false, EnumSet.noneOf(SceneEngine.Lane.class));
        h.engine.start(42); h.fire(0); h.nativeExecutor.runAll(); h.finishNative(0, 10, 20, 1000);
        CensorEngine.EvidenceReceipt old = h.evidence.get(0); CensorEngine.ViewportCoverage coverage = h.engine.viewportCoverage();
        h.engine.discardDwellObservations(h.engine.activeScope());
        assertTrue(h.engine.coverageCurrent(coverage)); assertNotNull(h.engine.claimEvidence(old));
        assertFalse(h.engine.dwellPresent(old, coverage, coverage.regions)); h.close();
    }
    @Test public void emptyImageResultMayUseCurrentCachedNativeButChangedGenerationCannot() {
        Harness h = new Harness(); h.engine.start(42); h.fire(0); h.nativeExecutor.runAll();
        h.finishNative(0, 10, 20, 1000); h.deliverImage(0, 30, 1000, 2400); h.imageExecutor.runAll();
        h.now = 40; h.imageCompletions.get(0).success(new SceneEngine.Batch(h.images.get(0).source,
                SceneEngine.Lane.FAST, Collections.emptyList(), Collections.emptyList()), new CensorEngine.WorkTiming(1, 2, 1));
        CensorEngine.EvidenceReceipt tick = h.evidence.get(1); assertFalse(tick.positiveInput); assertTrue(tick.subjects.isEmpty());
        CensorEngine.ViewportCoverage coverage = h.engine.viewportCoverage();
        assertEquals(1, coverage.regions.size()); assertTrue(coverage.regions.get(0).nativeValidated);
        assertTrue(h.engine.dwellPresent(tick, coverage, coverage.regions));
        h.engine.requestNativeTextAt(40, true);
        assertFalse(h.engine.dwellPresent(tick, coverage, coverage.regions));
        CensorEngine.ViewportCoverage gap = h.engine.viewportCoverage();
        assertFalse(h.engine.dwellPresent(tick, gap, gap.regions)); h.close();
    }
    @Test public void currentNativePositiveIsIndependentOfFiveWitnessConsumptionAndCannotSurviveExpiry() {
        Harness h = new Harness(); h.engine.executionControls(334, false, EnumSet.noneOf(SceneEngine.Lane.class));
        h.engine.start(42); h.fire(0); h.nativeExecutor.runAll(); h.finishNative(0, 10, 20, 1000);
        CensorEngine.EvidenceReceipt tick = h.evidence.get(0); CensorEngine.ViewportCoverage coverage = h.engine.viewportCoverage();
        assertTrue(h.engine.dwellPresent(tick, coverage, coverage.regions));
        assertEquals(1, tick.subjects.get(0).witnesses.size()); assertTrue(h.engine.claimEvidence(tick).subjects.isEmpty());
        h.now = coverage.validBeforeMillis; assertFalse(h.engine.dwellPresent(tick, coverage, coverage.regions));
        assertEquals(1, h.nativeInputs.size()); assertEquals(1, h.evidence.size()); h.close();
    }
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

    @Test public void throwingReplacementTraceCannotLeakNewlyRetainedImageEffects() {
        Harness h = new Harness(); h.engine.start(42); h.fire(0); h.nativeExecutor.runAll();
        Pixel pixel = h.deliverImage(0, 10, 1000, 2400); h.imageExecutor.runAll();
        h.finishImage(0, 20, 500); // Actual image presentation is pending, deliberately not drained.
        h.now = 30; NativeTextResult result = read(0, 25, 1000, 2400, 1000);
        h.failClockAfter = 3; // Terminal, replacement trace, then snapshot before retaining pixel effects.
        assertThrows(IllegalStateException.class, () -> h.nativeCompletions.get(0).admit(result));
        assertEquals(0, h.failClockAfter); h.ui.runAll();
        try (CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView()) {
            assertNotNull(view); assertEquals(1, masks(view).size());
            assertSame(pixel, view.sourcePixels(view.snapshot.destination.sequence));
        }
        h.close(); assertEquals(1, pixel.closes); assertEquals(1, h.drained);
    }

    @Test public void invalidationTraceFailureStillReleasesPixelsWithoutUndoingResourceQuarantine() {
        Harness h = new Harness(); h.engine.start(42); h.fire(0); h.nativeExecutor.runAll();
        Pixel pixel = h.deliverImage(0, 10, 1000, 2400); h.imageExecutor.runAll();
        h.finishImage(0, 20, 500); h.ui.runAll(); h.now = 30;
        h.failClockAfter = 2; // Terminal then the invalidation trace, after cleanup was queued.
        assertThrows(IllegalStateException.class, () -> h.nativeCompletions.get(0).resourceFailure());
        assertEquals(0, h.failClockAfter); assertEquals(1, h.engine.resourceFailures());
        h.ui.runAll(); assertNull(h.engine.acquireRenderView());
        h.close(); assertEquals(1, pixel.closes); assertEquals(0, h.drained);
    }

    @Test public void failedParentRenewalClockCannotStrandTheNextActualProviderRead() {
        Harness h = new Harness(); h.engine.start(42); h.fire(0); h.nativeExecutor.runAll(); h.now = 100;
        h.failClockAfter = 3; // Terminal, renewal wake, duplicate wake with an unattached timer.
        assertThrows(IllegalStateException.class, () -> h.nativeCompletions.get(0).failure());
        assertEquals(0, h.failClockAfter);
        h.engine.requestNativeTextAt(220, false); h.fire(220); h.nativeExecutor.runAll();
        assertEquals(2, h.nativeInputs.size());
        h.close(); assertEquals(1, h.drained);
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

    @Test public void earlyNativeTimerDeliveryCannotBypassAdmittedReadStartSpacing() {
        Harness h = new Harness(); h.engine.start(42); h.fire(0); h.nativeExecutor.runAll();
        h.finishNative(0, 10, 40, 1000); h.engine.requestNativeTextAt(40, false);
        h.now = 119;
        for (Timed timed : new ArrayList<>(h.timers)) {
            if (!timed.ran && !timed.cancelled && timed.at == 120) { timed.ran = true; timed.action.run(); }
        }
        h.nativeExecutor.runAll(); assertEquals("An early timer is not read admission", 1, h.nativeInputs.size());
        h.fire(120); h.nativeExecutor.runAll(); assertEquals(2, h.nativeInputs.size());
        assertEquals(120L, h.nativeStartTimes.get(1).longValue()); h.close(); assertEquals(1, h.drained);
    }

    @Test public void admittedNativeStartSpacingCannotChangeImageApiSpacing() {
        Harness h = new Harness(); h.engine.start(42); h.fire(0); h.nativeExecutor.runAll();
        h.finishNative(0, 10, 40, 1000); h.engine.requestNativeTextAt(40, false);
        h.fire(119); assertEquals(1, h.nativeInputs.size()); h.fire(120); h.nativeExecutor.runAll();
        assertEquals(2, h.nativeInputs.size()); h.nativeCompletions.get(1).failure();
        h.captures.get(0).callback.failure(0); h.fire(333); assertEquals(1, h.captures.size());
        h.fire(334); assertEquals(2, h.captures.size()); h.close(); assertEquals(1, h.drained);
    }

    @Test public void slowAdmittedParentsReclaimIdleButShortSuccessorsKeepActualStartSpacing() {
        for (NativeTextResult.ReadMode mode : NativeTextResult.ReadMode.values()) {
            Harness h = new Harness(); h.nativeBeginMode = mode;
            h.engine.executionControls(334, false, EnumSet.noneOf(SceneEngine.Lane.class));
            h.engine.start(42); h.fire(0); h.nativeExecutor.runAll();
            h.fire(300); h.nativeExecutor.runAll(); assertEquals("A held parent still owns the lane", 1, h.nativeInputs.size());
            assertNotNull(h.nativeCompletions.get(0).admit(pacingRead(h.nativeInputs.get(0), 0, 290)));
            h.fire(300); h.nativeExecutor.runAll(); assertEquals(mode.toString(), 2, h.nativeInputs.size());
            assertEquals(300L, h.nativeStartTimes.get(1).longValue());
            h.now = 380; assertNotNull(h.nativeCompletions.get(1).admit(pacingRead(h.nativeInputs.get(1), 300, 370)));
            h.fire(419); h.nativeExecutor.runAll(); assertEquals(2, h.nativeInputs.size());
            h.fire(420); h.nativeExecutor.runAll(); assertEquals(3, h.nativeInputs.size());
            assertEquals(420L, h.nativeStartTimes.get(2).longValue());
            assertEquals(2, h.nativeFrames.size()); assertEquals(2, h.evidence.size()); h.close(); assertEquals(1, h.drained);
        }
    }

    @Test public void rejectedOrFailedSlowAttemptRetainsFullTerminalCooldownEvenWithEarlierDemand() {
        for (String terminal : new String[]{"failure", "cancelled", "interval", "capacity", "expired", "expiredNode", "inventory", "costSuccess"}) {
            Harness h = new Harness(16, 2, terminal.equals("capacity") ? 1 : 100);
            h.engine.executionControls(334, false, EnumSet.noneOf(SceneEngine.Lane.class));
            h.engine.start(42); h.fire(0); h.nativeExecutor.runAll(); h.engine.requestNativeTextAt(0, false);
            long finished = terminal.startsWith("expired") ? 1200 : 300;
            CensorEngine.NativeTextInput input = h.nativeInputs.get(0);
            CensorEngine.NativeTextCompletion completion = h.nativeCompletions.get(0);
            if (terminal.equals("inventory")) {
                h.now = 80; assertTrue(completion.observe(1, observed(input, 0, 70, NODE, 1000, 65)));
            }
            h.now = finished;
            if (terminal.equals("failure")) completion.failure();
            else if (terminal.equals("cancelled")) completion.cancelled();
            else {
                if (terminal.equals("costSuccess")) {
                    completion.costStarted(new NativeReadCost.Start(input.identity, input.epoch, input.geometry, input.hardGeneration, 0));
                    completion.cost(cost(input, 0, 290)); assertTrue(h.engine.nativeTextInFlight());
                }
                NativeTextResult result = terminal.equals("capacity") ? read(0, 290, 1000, 2400, 1000, 1200)
                        : terminal.equals("expired") ? pacingRead(input, 0, 70)
                        : terminal.equals("expiredNode") ? completed(input, observed(input, 0, 1190, NODE, 1000, 65), 1190)
                        : terminal.equals("inventory") ? completed(input, observed(input, 0, 290, OTHER_NODE, 1200, 285), 290)
                        : read(1, 290, 1000, 2400, 1000);
                assertNull(terminal, completion.admit(result));
            }
            assertEquals(terminal, terminal.equals("inventory") ? 1 : 0, h.nativeFrames.size());
            assertEquals(terminal, terminal.equals("inventory") ? 1 : 0, h.evidence.size());
            h.fire(finished + 119); h.nativeExecutor.runAll(); assertEquals(terminal, 1, h.nativeInputs.size());
            h.fire(finished + 120); h.nativeExecutor.runAll(); assertEquals(terminal, 2, h.nativeInputs.size());
            assertEquals(finished + 120, h.nativeStartTimes.get(1).longValue()); h.close(); assertEquals(1, h.drained);
        }
    }

    @Test public void successfulStartSpacingUsesActualBeginNotQueueTime() {
        Harness h = new Harness(); h.engine.executionControls(334, false, EnumSet.noneOf(SceneEngine.Lane.class));
        h.engine.start(42); h.fire(0); h.now = 100; h.nativeExecutor.runAll();
        assertEquals(100L, h.nativeStartTimes.get(0).longValue());
        h.now = 190; assertNotNull(h.nativeCompletions.get(0).admit(pacingRead(h.nativeInputs.get(0), 100, 180)));
        h.fire(219); h.nativeExecutor.runAll(); assertEquals(1, h.nativeInputs.size());
        h.fire(220); h.nativeExecutor.runAll(); assertEquals(2, h.nativeInputs.size());
        assertEquals(220L, h.nativeStartTimes.get(1).longValue()); h.close();
    }

    @Test public void failedOrRejectedSuccessorClearsThePreviousAdmittedStartBudget() {
        for (String terminal : new String[]{"failure", "cancelled", "interval", "capacity", "expiredNode", "tree", "hardProvenance", "inventory", "costSuccess"}) {
            Harness h = new Harness(16, 2, terminal.equals("capacity") ? 1 : 100);
            h.engine.executionControls(334, false, EnumSet.noneOf(SceneEngine.Lane.class));
            h.engine.start(42); h.fire(0); h.nativeExecutor.runAll(); h.now = 300;
            assertNotNull(h.nativeCompletions.get(0).admit(pacingRead(h.nativeInputs.get(0), 0, 290)));
            h.fire(300); h.nativeExecutor.runAll(); assertEquals(2, h.nativeInputs.size());
            CensorEngine.NativeTextInput input = h.nativeInputs.get(1);
            CensorEngine.NativeTextCompletion completion = h.nativeCompletions.get(1);
            h.engine.requestNativeTextAt(0, false);
            if (terminal.equals("inventory")) {
                h.now = 380; assertTrue(completion.observe(1, observed(input, 300, 370, NODE, 1000, 365)));
            }
            long finished = terminal.equals("expiredNode") ? 1400 : 700; h.now = finished;
            if (terminal.equals("failure")) completion.failure();
            else if (terminal.equals("cancelled")) completion.cancelled();
            else {
                if (terminal.equals("costSuccess")) {
                    completion.costStarted(new NativeReadCost.Start(input.identity, input.epoch, input.geometry, input.hardGeneration, 300));
                    completion.cost(cost(input, 300, 690));
                }
                NativeTextResult result = pacingRead(input, 300, 690);
                if (terminal.equals("capacity")) result = read(300, 690, 1000, 2400, 1000, 1200);
                else if (terminal.equals("interval") || terminal.equals("costSuccess")) result = read(301, 690, 1000, 2400, 1000);
                else if (terminal.equals("expiredNode")) result = completed(input, observed(input, 300, 1390, NODE, 1000, 365), 1390);
                else if (terminal.equals("inventory")) result = completed(input, observed(input, 300, 690, OTHER_NODE, 1200, 685), 690);
                else if (terminal.equals("tree")) h.engine.requestNativeTextAt(finished, CensorEngine.NativeTextChange.TREE_DIRTY);
                else if (terminal.equals("hardProvenance")) result = new NativeTextResult(1000, 2400, 7, 300, 690,
                        result.candidates, 1, 1, 1, result.coverage, result.scanGeneration, result.confirmationGeneration,
                        input.hardGeneration + 1, result.classificationCompletedAtMillis, result.confirmationStartedAtMillis);
                assertNull(terminal, completion.admit(result));
            }
            assertEquals(terminal, terminal.equals("inventory") ? 2 : 1, h.nativeFrames.size());
            h.fire(finished + 119); h.nativeExecutor.runAll(); assertEquals(terminal, 2, h.nativeInputs.size());
            h.fire(finished + 120); h.nativeExecutor.runAll(); assertEquals(terminal, 3, h.nativeInputs.size());
            assertEquals(finished + 120, h.nativeStartTimes.get(2).longValue()); h.close();
        }
    }

    @Test public void reentrantFenceCannotCarryAnOldAdmittedSuccessBudgetIntoTheNextScope() {
        for (String action : new String[]{"hard", "scope", "stop", "disable", "protection", "close", "resource"}) {
            Harness h = observingHarness(); CensorEngine.NativeTextInput input = h.nativeInputs.get(0);
            h.afterNativeAccepted = () -> {
                if (action.equals("resource")) h.engine.nativeResourceFailure(); else fence(h, action);
            };
            h.now = 300; assertNull(action, h.nativeCompletions.get(0).admit(pacingRead(input, 0, 290)));
            if (action.equals("stop")) h.engine.start(42);
            if (action.equals("disable")) h.engine.nativeTextControls(true);
            h.fire(419); h.nativeExecutor.runAll(); assertEquals(action, 1, h.nativeInputs.size());
            h.fire(420); h.nativeExecutor.runAll();
            assertEquals(action, action.equals("close") || action.equals("resource") ? 1 : 2, h.nativeInputs.size());
            h.close();
        }
    }

    @Test public void completeEmptyAdmissionStillUsesBoundedActualStartPacingWithoutPositiveCredit() {
        Harness h = observingHarness(); h.now = 300;
        assertNotNull(h.nativeCompletions.get(0).admit(read(0, 290, 1000, 2400)));
        assertTrue(latestEvidence(h).subjects.isEmpty());
        h.fire(300); h.nativeExecutor.runAll(); assertEquals(2, h.nativeInputs.size());
        assertEquals(300L, h.nativeStartTimes.get(1).longValue()); h.close();
    }

    @Test public void laterFenceRevokesAnAlreadyAttachedSuccessfulTimerBeforeNewScopeAdmission() {
        for (String action : new String[]{"hard", "scope", "stop", "disable", "protection"}) {
            Harness h = observingHarness(); h.now = 300;
            assertNotNull(h.nativeCompletions.get(0).admit(pacingRead(h.nativeInputs.get(0), 0, 290)));
            fence(h, action);
            if (action.equals("stop")) h.engine.start(42);
            if (action.equals("disable")) h.engine.nativeTextControls(true);
            h.fire(419); h.nativeExecutor.runAll(); assertEquals(action, 1, h.nativeInputs.size());
            h.fire(420); h.nativeExecutor.runAll(); assertEquals(action, 2, h.nativeInputs.size());
            assertEquals(420L, h.nativeStartTimes.get(1).longValue()); h.close();
        }
    }

    private static NativeTextResult pacingRead(CensorEngine.NativeTextInput input, long start, long end) {
        NativeTextResult a = observed(input, start, end, NODE, 1000, end - 5);
        if (input.readMode() == NativeTextResult.ReadMode.FULL_DISCOVERY) return completed(input, a, end);
        if (input.readMode() == NativeTextResult.ReadMode.ADMITTED_NODE_COHORT_RECLASSIFY) {
            NativeTextResult b = observed(input, start, end, OTHER_NODE, 1200, end - 5);
            return new NativeTextResult(a.width, a.height, a.windowId, start, end,
                    java.util.Arrays.asList(a.candidates.get(0), b.candidates.get(0)), 2, 2, 2,
                    a.coverage, a.scanGeneration, a.confirmationGeneration, a.hardGeneration,
                    a.classificationCompletedAtMillis, a.confirmationStartedAtMillis, input.readMode());
        }
        return new NativeTextResult(a.width, a.height, a.windowId, start, end, a.candidates, 1, 1, 1,
                a.coverage, a.scanGeneration, a.confirmationGeneration, a.hardGeneration,
                a.classificationCompletedAtMillis, a.confirmationStartedAtMillis, input.readMode());
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
        boolean reject; int rejected;
        @Override public void execute(Runnable action) {
            if (reject) { rejected++; throw new java.util.concurrent.RejectedExecutionException("Controlled native executor refusal"); }
            actions.add(action);
        }
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
        int drained, nativeCancellations, failClockAfter;
        boolean failNativeBeforeRead, throwNativeBeforeRead, throwNativeCost;
        Runnable afterNativeAccepted = () -> { };
        NativeTextResult.ReadMode nativeBeginMode = NativeTextResult.ReadMode.FULL_DISCOVERY;
        final Queue imageExecutor = new Queue(), nativeExecutor = new Queue(), textExecutor = new Queue(), ui = new Queue();
        final List<Timed> timers = new ArrayList<>();
        final List<Capture> captures = new ArrayList<>();
        final List<CensorEngine.Input<Pixel>> images = new ArrayList<>();
        final List<CensorEngine.Completion> imageCompletions = new ArrayList<>();
        final List<CensorEngine.Input<Pixel>> textInputs = new ArrayList<>();
        final List<CensorEngine.Completion> textCompletions = new ArrayList<>();
        final List<CensorEngine.NativeTextInput> nativeInputs = new ArrayList<>();
        final List<CensorEngine.NativeTextCompletion> nativeCompletions = new ArrayList<>();
        final List<NativeTextFrame> nativeFrames = new ArrayList<>();
        final List<NativeReadCost> nativeCosts = new ArrayList<>();
        final List<CensorEngine.EvidenceReceipt> evidence = new ArrayList<>();
        final List<CensorEngine.Trace> traces = new ArrayList<>();
        final CensorEngine<Pixel> engine;
        Harness() { this(16); }
        Harness(int sources) { this(sources, 2); }
        Harness(int sources, long nativePartition) {
            this(sources, nativePartition, 100);
        }
        Harness(int sources, long nativePartition, int maximumTracks) {
            this(sources, nativePartition, maximumTracks, false);
        }
        Harness(int sources, long nativePartition, int maximumTracks, boolean ocr) {
            List<CensorEngine.LaneWorker<Pixel>> workers = new ArrayList<>();
            workers.add(new CensorEngine.LaneWorker<>(SceneEngine.Lane.FAST, imageExecutor,
                    (input, completion) -> { images.add(input); imageCompletions.add(completion); }));
            if (ocr) workers.add(new CensorEngine.LaneWorker<>(SceneEngine.Lane.TEXT, textExecutor,
                    (input, completion) -> { textInputs.add(input); textCompletions.add(completion); }));
            engine = new CensorEngine<>(new CensorEngine.Policy(334, 0, 8, 2, false),
                    new SceneEngine.Policy(sources, maximumTracks, 64, 256, 5000, 20000, .5), () -> {
                        if (failClockAfter > 0 && --failClockAfter == 0)
                            throw new IllegalStateException("Controlled terminal clock failure");
                        return now;
                    },
                    (action, delay) -> { Timed task = new Timed(now + delay, action); timers.add(task); return () -> task.cancelled = true; },
                    (request, callback) -> { assertTrue(request.tryDispatch()); captures.add(new Capture(request, callback)); },
                    workers, ui,
                    new CensorEngine.Observer() {
                        @Override public void trace(CensorEngine.Trace trace) { traces.add(trace); }
                        @Override public void nativeTextAccepted(NativeTextFrame frame) { nativeFrames.add(frame); afterNativeAccepted.run(); }
                        @Override public void nativeReadCost(NativeReadCost value) {
                            if (throwNativeCost) throw new IllegalStateException("Controlled diagnostic failure"); nativeCosts.add(value);
                        }
                        @Override public void evidenceAccepted(CensorEngine.EvidenceReceipt receipt) { evidence.add(receipt); }
                        @Override public void drained() { drained++; }
                    }, new CensorEngine.NativeTextLane(nativePartition, 120, 1000, nativeExecutor, (input, completion) -> {
                        nativeInputs.add(input); nativeCompletions.add(completion);
                        input.onCancellation(() -> nativeCancellations++);
                        if (throwNativeBeforeRead) throw new IllegalStateException("Controlled failure before read admission");
                        if (failNativeBeforeRead) { completion.failure(); return; }
                        long start = input.beginRead(nativeBeginMode); assertTrue(start >= 0); nativeStartTimes.add(start);
                    }));
        }
        void fire(long at) {
            assertTrue(at >= now); now = at;
            while (true) {
                Timed due = null;
                for (Timed timed : timers)
                    if (!timed.ran && !timed.cancelled && timed.at <= now && (due == null || timed.at < due.at)) due = timed;
                if (due == null) return;
                due.ran = true; due.action.run();
            }
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
            for (CensorEngine.Completion completion : textCompletions) completion.cancelled();
            nativeExecutor.runAll(); imageExecutor.runAll(); textExecutor.runAll(); ui.runAll();
        }
    }
}
