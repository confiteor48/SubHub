package com.subhub.app.censor.engine;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class NativeAccountingProvenanceTest {
    private static final String CONTENT = digest("a"), NODE = digest("c"), OTHER = digest("d");

    @Test public void publicUnknownFullCannotBeBlessedByTheLatestKnownFull() {
        mixedPrefix(null, new Object());
    }

    @Test public void foreignOwnerFullCannotEnterAnotherOwnersBridge() {
        mixedPrefix(new Object(), new Object());
    }

    private static void mixedPrefix(Object original, Object current) {
        SceneEngine scene = scene();
        admit(scene, frame(1, 0, false, original));
        NativeTextFrame known = frame(2, 150, false, current); admit(scene, known);
        assertEquals(Arrays.asList(1L, 2L), ids(scene, known)); // Ordinary FULL history is unchanged.
        admit(scene, frame(3, 300, true, current));
        NativeTextFrame full = frame(4, 450, false, current); admit(scene, full);
        assertEquals(Arrays.asList(2L, 4L), ids(scene, full));
        assertTrue(scene.consumeEvidence(full.source, scene.acceptedSubjects(full.source, full.source.receivedAtMillis),
                full.source.receivedAtMillis).isEmpty());
    }

    @Test public void expiredPreBridgeFullRetainsOrdinaryIdsButLosesPrivateBridgeEligibility() {
        SceneEngine scene = scene(); Object chain = new Object();
        admit(scene, frame(1, 0, false, chain));
        // No snapshot prunes the old node before this incoming update.
        NativeTextFrame afterGap = frame(2, 1100, false, chain); admit(scene, afterGap);
        assertEquals(Arrays.asList(1L, 2L), ids(scene, afterGap));
        admit(scene, frame(3, 1250, true, chain));
        NativeTextFrame full = frame(4, 1400, false, chain); admit(scene, full);
        assertEquals(Arrays.asList(2L, 4L), ids(scene, full));
    }

    @Test public void defaultUnknownFramesKeepConservativeTerminalPartialResets() {
        SceneEngine scene = scene();
        admit(scene, frame(1, 0, false, null)); admit(scene, frame(2, 150, true, null));
        NativeTextFrame full = frame(3, 300, false, null); admit(scene, full);
        assertEquals(Collections.singletonList(3L), ids(scene, full));
    }

    @Test public void anExpiredNodeCannotBorrowItsSiblingsLaterValidationDeadline() {
        SceneEngine scene = scene(); Object chain = new Object();
        NativeTextFrame full = custom(1, 0, 90, false, chain,
                candidate(NODE, 65, 1000), candidate(OTHER, 70, 1200));
        admit(scene, full);
        // Both original FULL nodes are still valid at receipt1030. Their partial
        // validations are independent; the later sibling never renews the first.
        admit(scene, custom(2, 150, 1020, true, chain,
                candidate(NODE, 215, 1000), candidate(OTHER, 1000, 1200)));
        NativeTextFrame next = custom(3, 1250, 1340, false, chain,
                candidate(NODE, 1315, 1000), candidate(OTHER, 1320, 1200));
        admit(scene, next);
        List<SceneEngine.AcceptedSubject> subjects = scene.acceptedSubjects(next.source, next.source.receivedAtMillis);
        assertEquals(2, subjects.size());
        for (SceneEngine.AcceptedSubject subject : subjects)
            assertEquals(subject.region.top == 1000 ? Collections.singletonList(3L) : Arrays.asList(1L, 3L), ids(subject));
    }

    private static SceneEngine scene() {
        return new SceneEngine(1, new SceneEngine.Policy(32, 100, 64, 256, 5000, 20000, .5));
    }
    private static void admit(SceneEngine scene, NativeTextFrame frame) { assertTrue(scene.admitNativeFrame(frame)); }
    private static NativeTextFrame frame(long parent, long start, boolean partial, Object chain) {
        return custom(parent, start, start + 90, partial, chain, candidate(NODE, start + 65, 1000));
    }
    private static NativeTextFrame custom(long parent, long start, long end, boolean partial, Object chain,
            NativeTextResult.Candidate... candidates) {
        NativeTextResult.ReadMode mode = !partial ? NativeTextResult.ReadMode.FULL_DISCOVERY
                : candidates.length == 2 ? NativeTextResult.ReadMode.ADMITTED_NODE_COHORT_RECLASSIFY
                    : NativeTextResult.ReadMode.ADMITTED_NODE_RECLASSIFY;
        NativeTextResult read = new NativeTextResult(1000, 2400, 7, start, end, Arrays.asList(candidates),
                candidates.length, candidates.length, candidates.length,
                partial ? NativeTextResult.Coverage.POSITIVE_NODES_ONLY : NativeTextResult.Coverage.COMPLETE_TREE,
                0, 0, 0, start + 10, start + 58, mode);
        SceneEngine.Source source = SceneEngine.Source.nativeText(1, parent, 42, 1000, 2400, 7,
                start, end, end + 10, ProtectionScope.normal(9));
        return chain == null ? new NativeTextFrame(source, 2, read, 1000, parent, start, start, 0)
                : new NativeTextFrame(source, 2, read, 1000, parent, start, start, 0, chain);
    }
    private static NativeTextResult.Candidate candidate(String node, long validated, double top) {
        return new NativeTextResult.Candidate("TEXT_SMUT", CONTENT, node, .9f, true,
                Collections.singletonList(new SceneEngine.Region(100, top, 200, top + 30)),
                NativeTextResult.TextSource.TEXT, 0, 4, 4, validated);
    }
    private static List<Long> ids(SceneEngine scene, NativeTextFrame frame) {
        return ids(scene.acceptedSubjects(frame.source, frame.source.receivedAtMillis).get(0));
    }
    private static List<Long> ids(SceneEngine.AcceptedSubject subject) {
        List<Long> ids = new ArrayList<>();
        for (SceneEngine.EvidenceWitness witness : subject.witnesses) ids.add(witness.producerIdentity);
        return ids;
    }
    private static String digest(String value) { return String.join("", Collections.nCopies(64, value)); }
}
