package com.subhub.app.diagnostics;

import static org.junit.Assert.*;
import com.subhub.app.censor.engine.CensorEngine;
import com.subhub.app.censor.engine.SceneEngine;
import com.subhub.app.censor.engine.ProtectionScope;
import com.subhub.app.censor.engine.ScrollMotionLedger;
import java.lang.reflect.Constructor;
import org.junit.Test;

public final class ReplacementEngineTelemetryTest {
    @Test public void cohortSchemaSeparatesTwoNodeParentFromItsNonterminalObservation() {
        com.subhub.app.censor.engine.NativeTextResult.ReadMode mode = com.subhub.app.censor.engine.NativeTextResult.ReadMode.ADMITTED_NODE_COHORT_RECLASSIFY;
        String content = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        java.util.List<com.subhub.app.censor.engine.NativeTextResult.Candidate> candidates = new java.util.ArrayList<>();
        for (String node : new String[]{content, "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"})
            candidates.add(new com.subhub.app.censor.engine.NativeTextResult.Candidate("TEXT_SMUT", content, node, .9f, true,
                    java.util.Collections.singletonList(new SceneEngine.Region(1, 2, 10, 20)),
                    com.subhub.app.censor.engine.NativeTextResult.TextSource.TEXT, 0, 4, 4, 170));
        for (int ordinal : new int[]{1, 0}) {
            com.subhub.app.censor.engine.NativeTextResult read = new com.subhub.app.censor.engine.NativeTextResult(100, 200, 7, 100, 200,
                    ordinal == 1 ? candidates.subList(0, 1) : candidates, 2, 2, ordinal == 1 ? 1 : 2,
                    com.subhub.app.censor.engine.NativeTextResult.Coverage.POSITIVE_NODES_ONLY, 1, 1, 1, 120, 168, mode);
            String value = ReplacementEngineTelemetry.nativeRead(5, new com.subhub.app.censor.engine.NativeTextFrame(
                    SceneEngine.Source.nativeText(1, 2, 3, 100, 200, 7, 100, 200, 220), 9, read, 1000, 7, 90, 95, ordinal));
            assertTrue(value.contains("\"schemaVersion\":4")); assertTrue(value.contains("\"readMode\":\"" + mode.name() + "\""));
            assertTrue(value.contains("\"observationOrdinal\":" + ordinal));
            assertTrue(value.contains("\"candidateCount\":" + (ordinal == 1 ? 1 : 2))); assertFalse(value.contains(content));
        }
        assertTrue(ReplacementEngineTelemetry.nativeCostStart(5, new com.subhub.app.censor.engine.NativeReadCost.Start(
                7, 1, 3, 1, 90, mode)).contains("\"schemaVersion\":4"));
        java.util.List<com.subhub.app.censor.engine.NativeReadCost.Cost> costs = new java.util.ArrayList<>();
        for (com.subhub.app.censor.engine.NativeReadCost.Stage stage : com.subhub.app.censor.engine.NativeReadCost.Stage.values())
            costs.add(new com.subhub.app.censor.engine.NativeReadCost.Cost(stage, 0, 0, 0, 0));
        assertTrue(ReplacementEngineTelemetry.nativeCost(5, new com.subhub.app.censor.engine.NativeReadCost(7, 1, 3, 1, 1,
                90, 100, 200, 210, 100, com.subhub.app.censor.engine.NativeReadCost.Terminal.SUCCESS, costs, mode)).contains("\"schemaVersion\":4"));
    }

    @Test public void nativeCostKeepsIndependentElapsedAndOriginalUptimeWithoutPrivateNodeData() {
        java.util.List<com.subhub.app.censor.engine.NativeReadCost.Cost> costs = new java.util.ArrayList<>();
        for (com.subhub.app.censor.engine.NativeReadCost.Stage stage : com.subhub.app.censor.engine.NativeReadCost.Stage.values())
            costs.add(new com.subhub.app.censor.engine.NativeReadCost.Cost(stage, 1, 10, 10, 0));
        com.subhub.app.censor.engine.NativeReadCost cost = new com.subhub.app.censor.engine.NativeReadCost(1, 2, 3, 4, 5,
                90, 100, 200, 210, 100, com.subhub.app.censor.engine.NativeReadCost.Terminal.SUCCESS, costs);
        String value = ReplacementEngineTelemetry.nativeCost(9, cost);
        assertTrue(value.startsWith("REPLACEMENT_NATIVE_COST {"));
        assertTrue(value.contains("\"durationClock\":\"SYSTEM_NANO_TIME\"")); assertTrue(value.contains("\"unattributedNanos\":10"));
        assertTrue(value.contains("\"schemaVersion\":3")); assertTrue(value.contains("\"readMode\":\"FULL_DISCOVERY\""));
        assertTrue(value.contains("\"rawCostCount\":9")); assertFalse(value.contains("nodeKey")); assertFalse(value.contains("caption"));
        assertTrue(value.length() < 2048); assertThrows(IllegalArgumentException.class, () -> ReplacementEngineTelemetry.nativeCost(0, cost));
    }
    @Test public void nativeInvocationIsNotAZeroCostOrEvidenceTerminal() {
        String value = ReplacementEngineTelemetry.nativeCostStart(9, new com.subhub.app.censor.engine.NativeReadCost.Start(1, 2, 3, 4, 90));
        assertTrue(value.startsWith("REPLACEMENT_NATIVE_COST_START {")); assertTrue(value.contains("\"invokedAtMillis\":90"));
        assertFalse(value.contains("readStarted")); assertFalse(value.contains("Source")); assertFalse(value.contains("terminal"));
    }
    @Test public void typedNativeReadPublishesSeparateCoverageClocksAndPerNodeExpiryWithoutPrivateText() {
        String content = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        com.subhub.app.censor.engine.NativeTextResult.Candidate candidate = new com.subhub.app.censor.engine.NativeTextResult.Candidate(
                "TEXT_SMUT", content, content, .9f, true, java.util.Collections.singletonList(new SceneEngine.Region(1, 2, 10, 20)),
                com.subhub.app.censor.engine.NativeTextResult.TextSource.TEXT, 1, 4, 6, 170);
        com.subhub.app.censor.engine.NativeTextResult read = new com.subhub.app.censor.engine.NativeTextResult(100, 200, 7, 100, 200,
                java.util.Collections.singletonList(candidate), 3, 1, 1,
                com.subhub.app.censor.engine.NativeTextResult.Coverage.POSITIVE_NODES_ONLY, 1, 2, 1, 120, 168);
        com.subhub.app.censor.engine.NativeTextFrame frame = new com.subhub.app.censor.engine.NativeTextFrame(
                SceneEngine.Source.nativeText(1, 2, 3, 100, 200, 7, 100, 200, 220), 9, read, 1000, 7, 90, 95);
        String value = ReplacementEngineTelemetry.nativeRead(5, frame);
        assertTrue(value.startsWith("REPLACEMENT_NATIVE {")); assertTrue(value.contains("\"coverage\":\"POSITIVE_NODES_ONLY\""));
        assertTrue(value.contains("\"scanGeneration\":1,\"confirmationGeneration\":2,\"hardGeneration\":1"));
        assertTrue(value.contains("\"validatedAtMillis\":170,\"validUntilMillis\":1170"));
        assertTrue(value.contains("\"queuedAtMillis\":90,\"startedAtMillis\":95")); assertFalse(value.contains(content));
        assertFalse(value.contains("pixelAtMillis")); assertFalse(value.contains("contentKey"));
        assertTrue(value.contains("\"schemaVersion\":3")); assertTrue(value.contains("\"observationOrdinal\":0"));
    }

    @Test public void nonterminalNativeGroupCannotMasqueradeAsAnotherTerminalAcquisition() {
        String content = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        com.subhub.app.censor.engine.NativeTextResult.Candidate candidate = new com.subhub.app.censor.engine.NativeTextResult.Candidate(
                "TEXT_SMUT", content, content, .9f, true, java.util.Collections.singletonList(new SceneEngine.Region(1, 2, 10, 20)),
                com.subhub.app.censor.engine.NativeTextResult.TextSource.TEXT, 1, 4, 6, 170);
        com.subhub.app.censor.engine.NativeTextResult read = new com.subhub.app.censor.engine.NativeTextResult(100, 200, 7, 100, 200,
                java.util.Collections.singletonList(candidate), 3, 2, 1,
                com.subhub.app.censor.engine.NativeTextResult.Coverage.POSITIVE_NODES_ONLY, 1, 2, 1, 120, 168);
        com.subhub.app.censor.engine.NativeTextFrame frame = new com.subhub.app.censor.engine.NativeTextFrame(
                SceneEngine.Source.nativeText(1, 2, 3, 100, 200, 7, 100, 200, 220), 9, read, 1000, 7, 90, 95, 1);
        String value = ReplacementEngineTelemetry.nativeRead(5, frame);
        assertTrue(value.contains("\"request\":7,\"observationOrdinal\":1"));
        assertTrue(value.contains("\"readFinishedAtMillis\":200"));
        assertTrue(value.contains("\"validatedAtMillis\":170,\"validUntilMillis\":1170"));
        assertFalse(value.contains(content));
    }

    @Test public void legacyCompleteNativeTelemetryKeepsUnknownProvenanceInsteadOfInventingConfirmation() {
        com.subhub.app.censor.engine.NativeTextResult read = new com.subhub.app.censor.engine.NativeTextResult(100, 200, 7, 100, 200,
                java.util.Collections.emptyList(), 3, 0, 0);
        com.subhub.app.censor.engine.NativeTextFrame frame = new com.subhub.app.censor.engine.NativeTextFrame(
                SceneEngine.Source.nativeText(1, 2, 3, 100, 200, 7, 100, 200, 220), 9, read, 1000);
        String value = ReplacementEngineTelemetry.nativeRead(5, frame);
        assertTrue(value.contains("\"request\":-1,\"observationOrdinal\":0,\"queuedAtMillis\":-1,\"startedAtMillis\":-1"));
        assertTrue(value.contains("\"classificationCompletedAtMillis\":-1,\"confirmationStartedAtMillis\":-1"));
        assertTrue(value.contains("\"candidateCount\":0,\"regionCount\":0,\"nodes\":[]"));
        assertThrows(IllegalArgumentException.class, () -> ReplacementEngineTelemetry.nativeRead(0, frame));
    }

    private CensorEngine.Trace row(CensorEngine.Stage stage, SceneEngine.Lane lane,
            long age, CensorEngine.WorkTiming work) throws Exception {
        Constructor<CensorEngine.Trace> factory = CensorEngine.Trace.class.getDeclaredConstructor(
                CensorEngine.Stage.class, long.class, long.class, SceneEngine.Lane.class,
                long.class, long.class, long.class, CensorEngine.WorkTiming.class);
        factory.setAccessible(true);
        return factory.newInstance(stage, 2L, 17L, lane, 100L, 7L, age, work);
    }

    @Test public void unknownPixelAndWorkClocksRemainUnknown() throws Exception {
        String value = ReplacementEngineTelemetry.trace(5, row(CensorEngine.Stage.CAPTURE_REQUESTED, null, -1, null));
        assertTrue(value.startsWith("REPLACEMENT_TRACE {\"schemaVersion\":1,"));
        assertTrue(value.contains("\"pixelAgeMillis\":-1"));
        assertTrue(value.contains("\"preprocessMillis\":-1,\"runtimeMillis\":-1,\"postprocessMillis\":-1"));
        assertTrue(value.contains("\"lane\":\"NONE\""));
        assertFalse(value.contains("compositor"));
    }

    @Test public void explicitWorkAndRequestLineageArePreserved() throws Exception {
        String value = ReplacementEngineTelemetry.trace(5, row(CensorEngine.Stage.JOB_COMPLETED, SceneEngine.Lane.FAST,
                32, new CensorEngine.WorkTiming(3, 17, 2)));
        assertTrue(value.contains("\"clockDomain\":\"UPTIME_MILLIS\""));
        assertTrue(value.contains("\"epoch\":2,\"imageRequest\":17"));
        assertTrue(value.contains("\"atMillis\":100,\"queueMillis\":7,\"pixelAgeMillis\":32"));
        assertTrue(value.contains("\"preprocessMillis\":3,\"runtimeMillis\":17,\"postprocessMillis\":2"));
    }

    @Test public void everyStageIsAnExplicitLiteral() throws Exception {
        for (CensorEngine.Stage stage : CensorEngine.Stage.values()) {
            String value = ReplacementEngineTelemetry.trace(5, row(stage, SceneEngine.Lane.TEXT, -1, null));
            assertTrue(value.contains("\"stage\":\"" + stage.name() + "\""));
            assertFalse(value.contains("\n"));
            assertTrue(value.length() < 2048);
        }
    }

    private ScrollMotionLedger.Sample scrollSample(long delivery, long sourceAt, long readAt,
            ScrollMotionLedger.Coordinate x, ScrollMotionLedger.Coordinate y, SceneEngine.Region bounds) {
        return new ScrollMotionLedger.Sample(new ScrollMotionLedger.Scope(2, 3, 4, ProtectionScope.normal(7)),
                delivery, 8, 9, sourceAt, 101, readAt, readAt, x, y, bounds);
    }
    private ScrollMotionLedger scrollLedger() {
        ScrollMotionLedger ledger = new ScrollMotionLedger(4, 2, 8);
        ledger.reset(new ScrollMotionLedger.Scope(2, 3, 4, ProtectionScope.normal(7))); return ledger;
    }

    @Test public void scrollOwnerKindIsBoundedAdvisoryMetadataNotTheSelectedCurve() {
        for (ScrollMotionLedger.OwnerKind kind : ScrollMotionLedger.OwnerKind.values()) {
            try (ScrollMotionLedger ledger = scrollLedger()) {
                ScrollMotionLedger.Sample sample = new ScrollMotionLedger.Sample(
                        new ScrollMotionLedger.Scope(2, 3, 4, ProtectionScope.normal(7)), 1, 8, 9, 100, 101, 102, 102,
                        ScrollMotionLedger.Coordinate.delta(0), ScrollMotionLedger.Coordinate.delta(0), null, kind);
                String value = ReplacementEngineTelemetry.scroll(5, ledger.observe(sample, 103));
                assertTrue(value.startsWith("REPLACEMENT_SCROLL {\"schemaVersion\":2,"));
                assertTrue(value.contains("\"ownerKind\":\"" + kind.name() + "\""));
                assertFalse(value.contains("androidx.")); assertFalse(value.contains("package"));
                assertFalse(value.contains("className")); assertFalse(value.contains("selectedCurve"));
                assertTrue(value.endsWith("\"pixelPhaseKnown\":false,\"memberApplicabilityKnown\":false,\"physicalErrorKnown\":false}"));
            }
        }
    }

    @Test public void unknownScrollAxesStayNullRatherThanBecomingLiteralZero() {
        try (ScrollMotionLedger ledger = scrollLedger()) {
            String value = ReplacementEngineTelemetry.scroll(5, ledger.observe(scrollSample(1, 100, 102,
                    ScrollMotionLedger.Coordinate.unknown(), ScrollMotionLedger.Coordinate.absoluteAndDelta(0, 0), null), 103));
            assertTrue(value.contains("\"x\":{\"valid\":true,\"absoluteKnown\":false,\"absolute\":null,\"deltaKnown\":false,\"delta\":null}"));
            assertTrue(value.contains("\"y\":{\"valid\":true,\"absoluteKnown\":true,\"absolute\":0,\"deltaKnown\":true,\"delta\":0}"));
            assertTrue(value.contains("\"reportedViewport\":null"));
        }
    }

    @Test public void scopedReportedCoordinatesAndClocksDoNotGrantPixelOrMemberSupport() {
        try (ScrollMotionLedger ledger = scrollLedger()) {
            String value = ReplacementEngineTelemetry.scroll(5, ledger.observe(scrollSample(1, 100, 102,
                    ScrollMotionLedger.Coordinate.delta(0), ScrollMotionLedger.Coordinate.absoluteAndDelta(100, -40),
                    new SceneEngine.Region(10, 20, 500, 800)), 103));
            assertTrue(value.startsWith("REPLACEMENT_SCROLL {\"schemaVersion\":2,"));
            assertTrue(value.contains("\"connection\":5,\"epoch\":2,\"geometry\":3,\"windowId\":4,\"protectionOrigin\":\"NORMAL\",\"tapScope\":7"));
            assertTrue(value.contains("\"delivery\":1,\"owner\":8,\"producer\":9"));
            assertTrue(value.contains("\"sourceAtMillis\":100,\"receivedAtMillis\":101,\"readStartedAtMillis\":102,\"readFinishedAtMillis\":102,\"admittedAtMillis\":103"));
            assertTrue(value.contains("\"reportedViewport\":{\"left\":10.0,\"top\":20.0,\"right\":500.0,\"bottom\":800.0}"));
            assertTrue(value.endsWith("\"pixelPhaseKnown\":false,\"memberApplicabilityKnown\":false,\"physicalErrorKnown\":false}"));
            assertFalse(value.contains("package")); assertFalse(value.contains("node")); assertFalse(value.contains("text"));
            assertTrue(value.length() < 2048);
        }
    }

    @Test public void missingReadIsAnExplicitGapWithOriginalUnknownReadClocks() {
        try (ScrollMotionLedger ledger = scrollLedger()) {
            String value = ReplacementEngineTelemetry.scroll(5, ledger.gap(scrollSample(1, 100, -1,
                    ScrollMotionLedger.Coordinate.unknown(), ScrollMotionLedger.Coordinate.delta(1), null), 103));
            assertTrue(value.contains("\"readStartedAtMillis\":-1,\"readFinishedAtMillis\":-1"));
            assertTrue(value.contains("\"admission\":\"GAP\",\"refusal\":\"EXPLICIT_GAP\""));
            assertTrue(value.contains("\"producerCount\":0,\"intervalCount\":0"));
        }
    }

    @Test public void rejectedOriginalClockIsNotNormalizedIntoReceiptTime() {
        try (ScrollMotionLedger ledger = scrollLedger()) {
            String value = ReplacementEngineTelemetry.scroll(5, ledger.observe(scrollSample(1, -7, 102,
                    ScrollMotionLedger.Coordinate.delta(0), ScrollMotionLedger.Coordinate.delta(0), null), 103));
            assertTrue(value.contains("\"sourceAtMillis\":-7,\"receivedAtMillis\":101"));
            assertTrue(value.contains("\"admission\":\"GAP\",\"refusal\":\"CLOCK\""));
        }
    }

    @Test(expected = IllegalArgumentException.class) public void scrollConnectionIdentityIsRequired() {
        try (ScrollMotionLedger ledger = scrollLedger()) {
            ReplacementEngineTelemetry.scroll(0, ledger.observe(scrollSample(1, 100, 102,
                    ScrollMotionLedger.Coordinate.delta(0), ScrollMotionLedger.Coordinate.delta(0), null), 103));
        }
    }
}
