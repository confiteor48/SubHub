package com.subhub.app.detection.text;

import static org.junit.Assert.*;

import com.subhub.app.censor.engine.CensorEngine;
import com.subhub.app.censor.engine.NativeTextResult;
import com.subhub.app.censor.engine.ProtectionScope;
import com.subhub.app.censor.engine.SceneEngine;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.Executor;
import org.junit.Test;

/** Actual selector/owner composition; no Android provider or product-store behavior is inferred. */
public class NativeAccountingScheduleTest {
    @Test public void singletonNaturalScheduleCountsOnlyFiveCompleteParents() { natural(1, false, false); }
    @Test public void twoNodeNaturalScheduleCountsOnlyFiveCompleteParents() { natural(2, false, false); }
    @Test public void fourNodeScheduleDoesNotMistakeSuccessfulOffNodeCohortsForFailures() { natural(4, false, false); }
    @Test public void softTreeChangesDoNotRequireAQuietGlobalTreeToKeepCompletedFacts() { natural(4, true, false); }
    @Test public void earlyCohortAndFullObservationsNeverExposeTheCompletedPrefix() { natural(2, false, true); }

    private static void natural(int nodes, boolean dirty, boolean early) {
        try (Harness h = new Harness(nodes)) {
            List<Long> fullParents = new ArrayList<>();
            int partials = 0;
            while (fullParents.size() < 5) {
                h.next(null);
                if (dirty && h.input().readMode() != NativeTextResult.ReadMode.FULL_DISCOVERY) {
                    h.now += 20; h.engine.requestNativeTextAt(h.now, CensorEngine.NativeTextChange.TREE_DIRTY);
                }
                boolean full = h.input().readMode() == NativeTextResult.ReadMode.FULL_DISCOVERY;
                h.finish(full, early);
                if (full) {
                    fullParents.add(h.input().identity);
                    for (SceneEngine.AcceptedSubject subject : h.current().subjects)
                        assertEquals(fullParents, witnessParents(subject));
                } else { partials++; partial(h); }
                assertEquals(full && fullParents.size() == 5 ? nodes : 0, h.claim());
                assertEquals(0, h.claim());
            }
            assertEquals(4 * (nodes == 4 ? 2 : 1), partials);
            assertEquals(5 + partials, h.inputs.size());
        }
    }

    @Test public void partialAcquisitionsCannotBeLaunderedIntoCompleteCredit() {
        try (Harness h = new Harness(1)) {
            h.next(NativeTextResult.ReadMode.FULL_DISCOVERY); h.finish(true, false);
            long first = h.input().identity;
            for (int i = 0; i < 8; i++) {
                h.next(NativeTextResult.ReadMode.ADMITTED_NODE_RECLASSIFY); h.finish(false, false); partial(h);
            }
            h.next(NativeTextResult.ReadMode.FULL_DISCOVERY); h.finish(true, false);
            assertEquals(java.util.Arrays.asList(first, h.input().identity), witnessParents(h.current().subjects.get(0)));
            assertEquals(0, h.claim());
        }
    }

    @Test public void failedCancelledAndRejectedParentsBreakTheHiddenPrefix() {
        for (String failure : new String[]{"failed", "cancelled", "rejected"}) {
            for (boolean observes : new boolean[]{false, true}) {
                try (Harness h = bridge()) {
                    h.next(NativeTextResult.ReadMode.FULL_DISCOVERY);
                    if (observes) { h.observe(); assertTrue(h.current().subjects.get(0).witnesses.isEmpty()); }
                    h.now = h.started + 100;
                    if (failure.equals("failed")) h.completion().failure();
                    else if (failure.equals("cancelled")) h.completion().cancelled();
                    else {
                        NativeTextResult stale = h.read(true, false);
                        h.engine.requestNativeTextAt(h.now, CensorEngine.NativeTextChange.TREE_DIRTY);
                        assertNull(h.completion().admit(stale));
                    }
                    h.engine.requestNativeTextAt(h.now + 120, false);
                    h.nextAt(h.now + 120, NativeTextResult.ReadMode.FULL_DISCOVERY); h.finish(true, false);
                    assertEquals(failure + "/observe=" + observes,
                            Collections.singletonList(h.input().identity), witnessParents(h.current().subjects.get(0)));
                    assertEquals(0, h.claim());
                }
            }
        }
    }

    @Test public void incompleteFullTerminalCannotCarryACompletedPrefix() {
        try (Harness h = bridge()) {
            h.next(NativeTextResult.ReadMode.FULL_DISCOVERY); h.finish(false, false); partial(h);
            freshFull(h);
            assertEquals(Collections.singletonList(h.input().identity), witnessParents(h.current().subjects.get(0)));
        }
    }

    @Test public void hardContentAndProtectionFencesDiscardTheHiddenPrefix() {
        for (boolean scope : new boolean[]{false, true}) {
            try (Harness h = bridge()) {
                if (scope) h.engine.changeScope(42, ProtectionScope.normal(10));
                else h.engine.requestNativeTextAt(h.now, CensorEngine.NativeTextChange.HARD_CONTENT);
                h.nextAt(h.now + 120, NativeTextResult.ReadMode.FULL_DISCOVERY); h.finish(true, false);
                assertEquals(Collections.singletonList(h.input().identity), witnessParents(h.current().subjects.get(0)));
                assertEquals(0, h.claim());
            }
        }
    }

    @Test public void ownNodeExpiryCannotBeRenewedByAnOffNodeCohortOrANewRead() {
        try (Harness h = bridge()) {
            long deadline = h.current().subjects.get(0).validUntilMillis;
            // Do not query a render view or fire an expiry timer before the incoming read.
            h.nextAt(deadline + 1, NativeTextResult.ReadMode.FULL_DISCOVERY); h.finish(true, false);
            assertEquals(Collections.singletonList(h.input().identity), witnessParents(h.current().subjects.get(0)));
            assertEquals(0, h.claim());
        }
    }

    @Test public void changedLiteralCannotResurrectTheHiddenPrefix() {
        try (Harness h = bridge()) {
            h.content = digest("b");
            h.next(NativeTextResult.ReadMode.FULL_DISCOVERY); h.finish(true, false);
            assertEquals(Collections.singletonList(h.input().identity), witnessParents(h.current().subjects.get(0)));
            assertEquals(0, h.claim());
        }
    }

    @Test public void validationRollbackIsRejectedBeforeAnyBridgeRenewal() {
        try (Harness h = bridge()) {
            h.validationOverride = h.started + 64;
            h.next(NativeTextResult.ReadMode.FULL_DISCOVERY);
            assertThrows(IllegalArgumentException.class, () -> h.read(true, false));
        }
    }

    @Test public void alreadyConsumedNodeCannotChargeAgainAfterNaturalPartialRenewalAndReflow() {
        try (Harness h = new Harness(2)) {
            h.addresses = false;
            for (int i = 0; i < 5; i++) { h.next(null); h.finish(true, false); }
            assertEquals(2, h.claim()); h.addresses = true; h.extraLine = true;
            h.next(null); h.finish(false, true); partial(h);
            freshFull(h);
            assertEquals(0, h.claim());
            for (SceneEngine.AcceptedSubject subject : h.current().subjects) assertTrue(subject.alreadyConsumed);
        }
    }

    @Test public void preBridgeFailurePreservesOrdinaryFullIdsButNotTheirBridgeCapability() {
        try (Harness h = new Harness(1)) {
            h.addresses = false;
            for (int i = 0; i < 3; i++) { h.next(null); h.finish(true, false); }
            h.next(null); h.now = h.started + 100; h.completion().failure();
            h.nextAt(h.now + 120, NativeTextResult.ReadMode.FULL_DISCOVERY); h.finish(true, false);
            long fresh = h.input().identity;
            assertEquals(java.util.Arrays.asList(1L, 2L, 3L, fresh), witnessParents(h.current().subjects.get(0)));
            h.addresses = true; h.next(null); h.finish(false, false);
            freshFull(h);
            assertEquals(java.util.Arrays.asList(fresh, h.input().identity), witnessParents(h.current().subjects.get(0)));
            assertEquals(0, h.claim());
        }
    }

    @Test public void duplicateDoneCallbackCannotBreakTheNewerAccountingSegment() {
        try (Harness h = bridge()) {
            h.next(NativeTextResult.ReadMode.FULL_DISCOVERY);
            CensorEngine.NativeTextCompletion old = h.completion();
            h.now = h.started + 100; old.failure();
            h.nextAt(h.now + 120, NativeTextResult.ReadMode.FULL_DISCOVERY); h.finish(true, false);
            long fresh = h.input().identity;
            h.next(NativeTextResult.ReadMode.ADMITTED_NODE_RECLASSIFY); h.finish(false, false);
            old.cancelled(); old.failure();
            freshFull(h);
            assertEquals(java.util.Arrays.asList(fresh, h.input().identity), witnessParents(h.current().subjects.get(0)));
        }
    }

    @Test public void optionalObservationRefusalAloneCannotBreakTheParent() {
        try (Harness h = bridge()) {
            h.next(NativeTextResult.ReadMode.FULL_DISCOVERY); h.now = h.started + 80;
            assertFalse(h.completion().observe(0, h.read(false, true)));
            h.finish(true, false);
            assertEquals(java.util.Arrays.asList(1L, 2L, 3L, h.input().identity), witnessParents(h.current().subjects.get(0)));
        }
    }

    @Test public void terminalClockExceptionCannotLeaveAUsableHiddenPrefix() {
        try (Harness h = bridge()) {
            h.next(NativeTextResult.ReadMode.FULL_DISCOVERY); h.now = h.started + 100;
            NativeTextResult result = h.read(true, false);
            h.throwClock = true;
            try { assertThrows(IllegalStateException.class, () -> h.completion().admit(result)); }
            finally { h.throwClock = false; }
            h.engine.requestNativeTextAt(h.now + 120, false);
            h.nextAt(h.now + 120, NativeTextResult.ReadMode.FULL_DISCOVERY); h.finish(true, false);
            assertEquals(Collections.singletonList(h.input().identity), witnessParents(h.current().subjects.get(0)));
        }
    }

    @Test public void executorRefusalBreaksThePrefixWithoutInventingAProviderRead() {
        try (Harness h = bridge()) {
            int before = h.inputs.size(); h.nativeQueue.reject = true;
            h.pumpAt(h.started + 150, NativeTextResult.ReadMode.FULL_DISCOVERY, true);
            assertEquals(before, h.inputs.size()); h.nativeQueue.reject = false;
            h.nextAt(h.now + 120, NativeTextResult.ReadMode.FULL_DISCOVERY); h.finish(true, false);
            assertEquals(Collections.singletonList(h.input().identity), witnessParents(h.current().subjects.get(0)));
        }
    }

    @Test public void postScenePresentationFailureCannotPublishRestoredCredit() { postSceneFailure(2); }
    @Test public void postSceneReceiptFailureCannotPublishRestoredCredit() { postSceneFailure(3); }
    @Test public void postSceneRenewalFailureCannotPublishRestoredCredit() { postSceneFailure(4); }
    @Test public void postSceneSecondWakeFailureCannotStrandOrPublishRestoredCredit() { postSceneFailure(5); }

    private static void postSceneFailure(int clockCall) {
        try (Harness h = bridge()) {
            int receipts = h.receipts.size();
            h.next(NativeTextResult.ReadMode.FULL_DISCOVERY); h.now = h.started + 100;
            NativeTextResult result = h.read(true, false);
            h.failClockAfter = clockCall;
            assertThrows(IllegalStateException.class, () -> h.completion().admit(result));
            assertEquals(0, h.failClockAfter);
            h.ui.drain(); assertEquals(receipts, h.receipts.size());
            h.engine.requestNativeTextAt(h.now + 120, false);
            h.nextAt(h.now + 120, NativeTextResult.ReadMode.FULL_DISCOVERY); h.finish(true, false);
            assertEquals(Collections.singletonList(h.input().identity), witnessParents(h.current().subjects.get(0)));
            assertEquals(0, h.claim());
            try (CensorEngine<Object>.RenderView view = h.engine.acquireRenderView()) {
                assertNotNull(view);
                assertEquals(h.current().source.sequence, view.snapshot.destination.sequence);
            }
        }
    }

    @Test public void failedRestorationPreservesAnAlreadyQueuedPartialPresentation() {
        try (Harness h = new Harness(1)) {
            h.addresses = false;
            for (int i = 0; i < 3; i++) { h.next(null); h.finish(true, false); }
            h.addresses = true; h.next(null); h.now = h.started + 100;
            NativeTextResult partial = h.read(false, false);
            assertNotNull(h.completion().admit(partial));
            h.schedule.terminal(h.input().readMode(), h.nodes);
            int before = h.receipts.size();
            h.next(NativeTextResult.ReadMode.FULL_DISCOVERY); h.now = h.started + 100;
            NativeTextResult full = h.read(true, false); h.failClockAfter = 3;
            assertThrows(IllegalStateException.class, () -> h.completion().admit(full));
            // Evidence delivery is synchronous; only presentation is still queued.
            h.ui.drain(); assertEquals(before, h.receipts.size());
            assertEquals(NativeTextResult.Coverage.POSITIVE_NODES_ONLY, h.current().nativeCoverage);
            try (CensorEngine<Object>.RenderView view = h.engine.acquireRenderView()) {
                assertNotNull(view);
                assertEquals(h.current().source.sequence, view.snapshot.destination.sequence);
            }
            h.engine.requestNativeTextAt(h.now + 120, false);
            h.nextAt(h.now + 120, NativeTextResult.ReadMode.FULL_DISCOVERY); h.finish(true, false);
            assertEquals(Collections.singletonList(h.input().identity), witnessParents(h.current().subjects.get(0)));
            assertEquals(0, h.claim());
        }
    }

    @Test public void failedRestorationCannotChargeAnAlreadyConsumedReflowedNodeAgain() {
        try (Harness h = new Harness(1)) {
            h.addresses = false;
            for (int i = 0; i < 5; i++) { h.next(null); h.finish(true, false); }
            assertEquals(1, h.claim());
            h.addresses = true; h.extraLine = true; h.next(null); h.finish(false, false);
            h.next(NativeTextResult.ReadMode.FULL_DISCOVERY); h.now = h.started + 100;
            NativeTextResult full = h.read(true, false); h.failClockAfter = 3;
            assertThrows(IllegalStateException.class, () -> h.completion().admit(full));
            h.engine.requestNativeTextAt(h.now + 120, false);
            h.nextAt(h.now + 120, NativeTextResult.ReadMode.FULL_DISCOVERY); h.finish(true, false);
            assertEquals(2, h.current().subjects.size()); assertEquals(0, h.claim());
            for (SceneEngine.AcceptedSubject subject : h.current().subjects) assertTrue(subject.alreadyConsumed);
        }
    }

    @Test public void postSceneFailureDoesNotEraseOrdinaryPrebridgeFullHistory() {
        try (Harness h = new Harness(1)) {
            h.addresses = false;
            for (int i = 0; i < 3; i++) { h.next(null); h.finish(true, false); }
            h.next(null); h.now = h.started + 100;
            NativeTextResult result = h.read(true, false); long failed = h.input().identity;
            h.failClockAfter = 2;
            assertThrows(IllegalStateException.class, () -> h.completion().admit(result));
            h.engine.requestNativeTextAt(h.now + 120, false);
            h.nextAt(h.now + 120, NativeTextResult.ReadMode.FULL_DISCOVERY); h.finish(true, false);
            assertEquals(java.util.Arrays.asList(1L, 2L, 3L, failed, h.input().identity),
                    witnessParents(h.current().subjects.get(0)));
            // Ordinary all-FULL semantics remain public, but these old facts must
            // not gain private bridge provenance after the failed parent.
            h.addresses = true; h.next(null); h.finish(false, false); freshFull(h);
            assertEquals(2, h.current().subjects.get(0).witnesses.size());
        }
    }

    @Test public void queuedCancellationAndItsLateRunnableCannotBreakANewerSegment() {
        try (Harness h = bridge()) {
            int before = h.inputs.size();
            h.pumpAt(h.started + 150, NativeTextResult.ReadMode.FULL_DISCOVERY, false);
            assertEquals(before, h.inputs.size()); assertEquals(1, h.nativeQueue.actions.size());
            Runnable old = h.nativeQueue.actions.get(0);
            h.engine.requestNativeTextAt(h.now, CensorEngine.NativeTextChange.HARD_CONTENT);
            h.nextAt(h.now + 120, NativeTextResult.ReadMode.FULL_DISCOVERY); h.finish(true, false);
            long fresh = h.input().identity;
            h.next(NativeTextResult.ReadMode.ADMITTED_NODE_RECLASSIFY); h.finish(false, false);
            old.run(); freshFull(h);
            assertEquals(java.util.Arrays.asList(fresh, h.input().identity), witnessParents(h.current().subjects.get(0)));
        }
    }

    @Test public void stopAndDisableDiscardHiddenCreditBeforeARealRestart() {
        for (boolean stop : new boolean[]{false, true}) {
            try (Harness h = bridge()) {
                CensorEngine.EvidenceReceipt old = h.current();
                if (stop) { h.engine.stop(); h.engine.start(42, ProtectionScope.normal(9)); }
                else { h.engine.nativeTextControls(false); h.engine.nativeTextControls(true); }
                assertNull(h.engine.claimEvidence(old));
                h.nextAt(h.now + 120, NativeTextResult.ReadMode.FULL_DISCOVERY); h.finish(true, false);
                assertEquals(Collections.singletonList(h.input().identity), witnessParents(h.current().subjects.get(0)));
            }
        }
    }

    @Test public void nativeResourceFailureRevokesTheBridgeAndCannotBeReportedAsDrained() {
        try (Harness h = bridge()) {
            CensorEngine.EvidenceReceipt old = h.current(); h.next(NativeTextResult.ReadMode.FULL_DISCOVERY);
            h.now = h.started + 100; h.completion().resourceFailure();
            assertEquals(1, h.engine.resourceFailures()); assertNull(h.engine.claimEvidence(old));
            int before = h.inputs.size(); h.engine.requestNativeTextAt(h.now + 120, false);
            h.pumpAt(h.now + 120, NativeTextResult.ReadMode.FULL_DISCOVERY, true);
            assertEquals(before, h.inputs.size());
        }
    }

    @Test public void changedNodeSpanAndWindowCannotBorrowTheOldNodesPrefix() {
        for (String change : new String[]{"node", "span", "window"}) {
            try (Harness h = bridge()) {
                if (change.equals("node")) h.nodeOverride = digest("e");
                else if (change.equals("span")) h.matchStart = 1;
                else h.windowId = 8;
                freshFull(h);
                assertEquals(change, Collections.singletonList(h.input().identity), witnessParents(h.current().subjects.get(0)));
                assertEquals(0, h.claim());
            }
        }
    }

    @Test public void unconsumedReflowCanCountTheNodeOnceWithoutBorrowingNewLineCredit() {
        try (Harness h = bridge()) {
            h.extraLine = true; freshFull(h);
            assertEquals(0, h.claim());
            h.next(NativeTextResult.ReadMode.ADMITTED_NODE_RECLASSIFY); h.finish(false, true); partial(h);
            freshFull(h);
            assertEquals(2, h.current().subjects.size()); assertEquals(1, h.claim()); assertEquals(0, h.claim());
            freshFull(h); assertEquals(0, h.claim());
            for (SceneEngine.AcceptedSubject subject : h.current().subjects) assertTrue(subject.alreadyConsumed);
        }
    }

    private static Harness bridge() {
        Harness h = new Harness(1);
        h.addresses = false;
        for (int i = 0; i < 3; i++) { h.next(null); h.finish(true, false); }
        h.addresses = true; h.next(null); h.finish(false, false); partial(h);
        return h;
    }

    private static void freshFull(Harness h) { h.next(NativeTextResult.ReadMode.FULL_DISCOVERY); h.finish(true, false); }
    private static String digest(String value) { return String.join("", Collections.nCopies(64, value)); }
    private static List<Long> witnessParents(SceneEngine.AcceptedSubject subject) {
        List<Long> ids = new ArrayList<>();
        for (SceneEngine.EvidenceWitness witness : subject.witnesses) {
            assertEquals(SceneEngine.SourceKind.NATIVE_TEXT, witness.kind); ids.add(witness.producerIdentity);
        }
        return ids;
    }
    private static void partial(Harness h) {
        assertEquals(NativeTextResult.Coverage.POSITIVE_NODES_ONLY, h.current().nativeCoverage);
        assertFalse(h.engine.imageTextSuppressed());
        CensorEngine.ViewportCoverage coverage = h.engine.viewportCoverage();
        assertFalse(h.engine.dwellPresent(h.current(), coverage, coverage.regions)); assertEquals(0, h.claim());
        for (SceneEngine.AcceptedSubject subject : h.current().subjects) assertFalse(subject.confirmedForConsumption);
    }

    private static final class Queue implements Executor {
        final List<Runnable> actions = new ArrayList<>();
        boolean reject;
        public void execute(Runnable action) {
            if (reject) throw new java.util.concurrent.RejectedExecutionException("Controlled native executor refusal");
            actions.add(action);
        }
        void drain() {
            int count = 0;
            while (!actions.isEmpty()) { assertTrue(++count < 100); actions.remove(0).run(); }
        }
    }
    private static final class Timed {
        final long at; final Runnable action; boolean cancelled, ran;
        Timed(long at, Runnable action) { this.at = at; this.action = action; }
    }
    private static final class Harness implements AutoCloseable {
        final int nodes;
        final NativeReadSchedule schedule = new NativeReadSchedule();
        final Queue nativeQueue = new Queue(), ui = new Queue();
        final List<Timed> timers = new ArrayList<>();
        final List<CensorEngine.NativeTextInput> inputs = new ArrayList<>();
        final List<CensorEngine.NativeTextCompletion> completions = new ArrayList<>();
        final List<CensorEngine.EvidenceReceipt> receipts = new ArrayList<>();
        final CensorEngine<Object> engine;
        boolean addresses = true, extraLine, throwClock;
        String content = digest("a");
        String nodeOverride;
        long now, started = -150, validationOverride = -1;
        int cohortOffset, selectedOffset, drained, failClockAfter;
        int matchStart, windowId = 7;
        NativeTextResult.ReadMode forced;
        Harness(int nodes) {
            this.nodes = nodes;
            CensorEngine.LaneWorker<Object> image = new CensorEngine.LaneWorker<>(SceneEngine.Lane.FAST,
                    Runnable::run, (input, completion) -> fail("No image/model work"));
            engine = new CensorEngine<>(new CensorEngine.Policy(334, 0, 8, 2, false),
                    new SceneEngine.Policy(64, 100, 64, 256, 5000, 20000, .5),
                    () -> {
                        if (throwClock || (failClockAfter > 0 && --failClockAfter == 0))
                            throw new IllegalStateException("Controlled terminal clock failure");
                        return now;
                    },
                    (action, delay) -> { Timed timed = new Timed(now + delay, action); timers.add(timed); return () -> timed.cancelled = true; },
                    (request, callback) -> fail("No screenshot acquisition"), Collections.singletonList(image), ui,
                    new CensorEngine.Observer() {
                        public void evidenceAccepted(CensorEngine.EvidenceReceipt receipt) { receipts.add(receipt); }
                        public void drained() { drained++; }
                    }, new CensorEngine.NativeTextLane(2, 120, 1000, nativeQueue, (input, completion) -> {
                        inputs.add(input); completions.add(completion);
                        NativeTextResult.ReadMode mode = forced != null ? forced : schedule.next(addresses, inputs.size() == 1 ? 0 : nodes);
                        started = input.beginRead(mode); assertEquals(now, started);
                        selectedOffset = mode == NativeTextResult.ReadMode.ADMITTED_NODE_COHORT_RECLASSIFY ? cohortOffset : 0;
                        if (mode == NativeTextResult.ReadMode.ADMITTED_NODE_COHORT_RECLASSIFY) cohortOffset = (cohortOffset + 2) % nodes;
                        else if (mode == NativeTextResult.ReadMode.FULL_DISCOVERY) cohortOffset = 0;
                    }));
            engine.executionControls(334, false, EnumSet.noneOf(SceneEngine.Lane.class));
            engine.start(42, ProtectionScope.normal(9));
        }
        void next(NativeTextResult.ReadMode selected) { nextAt(Math.max(now, started + 150), selected); }
        void nextAt(long at, NativeTextResult.ReadMode selected) {
            int previous = inputs.size(); pumpAt(at, selected, true); assertEquals(previous + 1, inputs.size());
        }
        void pumpAt(long at, NativeTextResult.ReadMode selected, boolean drain) {
            forced = selected; assertTrue(at >= now); now = at;
            int count = 0;
            while (true) {
                Timed due = null;
                for (Timed timed : timers)
                    if (!timed.ran && !timed.cancelled && timed.at <= now && (due == null || timed.at < due.at)) due = timed;
                if (due == null) break;
                assertTrue(++count < 100); due.ran = true; due.action.run();
            }
            if (drain) nativeQueue.drain();
        }
        CensorEngine.NativeTextInput input() { return inputs.get(inputs.size() - 1); }
        CensorEngine.NativeTextCompletion completion() { return completions.get(completions.size() - 1); }
        CensorEngine.EvidenceReceipt current() { return receipts.get(receipts.size() - 1); }
        int claim() { CensorEngine.EvidenceClaim claim = engine.claimEvidence(current()); assertNotNull(claim); return claim.subjects.size(); }
        NativeTextResult read(boolean complete, boolean observation) {
            NativeTextResult.ReadMode mode = input().readMode();
            int selected = mode == NativeTextResult.ReadMode.FULL_DISCOVERY ? nodes
                    : mode == NativeTextResult.ReadMode.ADMITTED_NODE_COHORT_RECLASSIFY ? 2 : 1;
            List<NativeTextResult.Candidate> candidates = new ArrayList<>();
            for (int i = 0; i < (observation ? 1 : selected); i++) {
                int node = selectedOffset + i;
                List<SceneEngine.Region> lines = new ArrayList<>();
                lines.add(new SceneEngine.Region(100, 1000 + node * 200, 200, 1030 + node * 200));
                if (extraLine) lines.add(new SceneEngine.Region(100, 1040 + node * 200, 200, 1070 + node * 200));
                candidates.add(new NativeTextResult.Candidate("TEXT_SMUT", content,
                        nodeOverride != null ? nodeOverride : digest(Integer.toHexString(node + 10)), .9f, true, lines,
                        NativeTextResult.TextSource.TEXT, matchStart, 4, 4,
                        validationOverride >= 0 ? validationOverride : started + 65));
            }
            return new NativeTextResult(1000, 2400, windowId, started, started + (observation ? 70 : 90), candidates,
                    selected, selected, candidates.size(), complete && !observation
                            ? NativeTextResult.Coverage.COMPLETE_TREE : NativeTextResult.Coverage.POSITIVE_NODES_ONLY,
                    input().scanGeneration(), engine.nativeTreeGeneration(), input().hardGeneration,
                    started + 10, started + 58, mode);
        }
        void observe() {
            now = started + 80; assertTrue(completion().observe(1, read(false, true))); ui.drain();
            assertTrue(current().subjects.get(0).witnesses.isEmpty()); partial(this);
        }
        void finish(boolean complete, boolean early) {
            if (early && input().readMode() != NativeTextResult.ReadMode.ADMITTED_NODE_RECLASSIFY) observe();
            now = started + 100;
            NativeTextResult result = read(complete, false);
            CensorEngine.NativeTextAdmission admission = completion().admit(result);
            assertNotNull(admission); assertTrue(admission.matches(input(), result));
            schedule.terminal(input().readMode(), nodes); ui.drain();
            assertTrue(engine.evidenceCurrent(current()));
            for (SceneEngine.AcceptedSubject subject : current().subjects) {
                assertEquals((validationOverride >= 0 ? validationOverride : started + 65) + 1000, subject.validUntilMillis);
                System.out.println("NATIVE_ACCOUNTING parent=" + input().identity + " mode=" + input().readMode()
                        + " source=" + current().source.sequence + " subject=" + subject.identity
                        + " witnesses=" + witnessParents(subject) + " deadline=" + subject.validUntilMillis);
            }
        }
        public void close() {
            engine.close();
            for (CensorEngine.NativeTextCompletion completion : completions) completion.cancelled();
            nativeQueue.reject = false; nativeQueue.drain(); ui.drain();
            assertEquals(engine.resourceFailures() == 0 ? 1 : 0, drained); assertEquals(0, engine.livePixelFrames());
        }
    }
}
