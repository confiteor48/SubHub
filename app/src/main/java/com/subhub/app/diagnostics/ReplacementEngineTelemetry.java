package com.subhub.app.diagnostics;

import com.subhub.app.censor.engine.CensorEngine;
import com.subhub.app.censor.engine.SceneEngine;
import com.subhub.app.censor.engine.NativeTextFrame;
import com.subhub.app.censor.engine.NativeTextResult;
import com.subhub.app.censor.engine.ScrollMotionLedger;
import com.subhub.app.censor.android.SnapshotCensorSurface;

/** Sanitized primitives only. These receipts never claim physical motion or compositor arrival. */
public final class ReplacementEngineTelemetry {
    private ReplacementEngineTelemetry() { }

    public static String nativeCostStart(long connection, com.subhub.app.censor.engine.NativeReadCost.Start value) {
        if (connection <= 0) throw new IllegalArgumentException("Replacement connection identity");
        return "REPLACEMENT_NATIVE_COST_START {\"schemaVersion\":" + nativeSchema(value.readMode) + ",\"clockDomain\":\"UPTIME_MILLIS\",\"connection\":" + connection
                + ",\"request\":" + value.request + ",\"epoch\":" + value.epoch + ",\"geometry\":" + value.geometry
                + ",\"hardGeneration\":" + value.hardGeneration + ",\"invokedAtMillis\":" + value.invokedAtMillis
                + ",\"readMode\":\"" + value.readMode.name() + "\"}";
    }

    public static String nativeCost(long connection, com.subhub.app.censor.engine.NativeReadCost value) {
        if (connection <= 0) throw new IllegalArgumentException("Replacement connection identity");
        StringBuilder costs = new StringBuilder("[");
        for (com.subhub.app.censor.engine.NativeReadCost.Cost cost : value.costs) {
            if (costs.length() > 1) costs.append(',');
            costs.append("{\"stage\":\"").append(cost.stage.name()).append("\",\"calls\":").append(cost.calls)
                    .append(",\"nanos\":").append(cost.nanos).append(",\"maximumNanos\":").append(cost.maximumNanos)
                    .append(",\"failedCalls\":").append(cost.failedCalls).append('}');
        }
        costs.append(']');
        return "REPLACEMENT_NATIVE_COST {\"schemaVersion\":" + nativeSchema(value.readMode) + ",\"clockDomain\":\"UPTIME_MILLIS\",\"connection\":" + connection
                + ",\"request\":" + value.request + ",\"epoch\":" + value.epoch + ",\"geometry\":" + value.geometry
                + ",\"hardGeneration\":" + value.hardGeneration + ",\"readMode\":\"" + value.readMode.name() + "\",\"scanGeneration\":" + value.scanGeneration
                + ",\"invokedAtMillis\":" + value.invokedAtMillis
                + ",\"readStartedAtMillis\":" + value.readStartedAtMillis + ",\"readFinishedAtMillis\":" + value.readFinishedAtMillis
                + ",\"terminalAtMillis\":" + value.terminalAtMillis + ",\"terminal\":\"" + value.terminal.name()
                + "\",\"durationClock\":\"SYSTEM_NANO_TIME\",\"elapsedNanos\":" + value.elapsedNanos
                + ",\"unattributedNanos\":" + value.unattributedNanos + ",\"rawCostCount\":" + value.costs.size() + ",\"costs\":" + costs + "}";
    }

    /** Admitted classified facts; observationOrdinal separates early groups from actual terminal admissions. */
    public static String nativeRead(long connection, NativeTextFrame frame) {
        if (connection <= 0) throw new IllegalArgumentException("Replacement connection identity");
        NativeTextResult read = frame.read; SceneEngine.Source source = frame.source;
        StringBuilder nodes = new StringBuilder("[");
        for (NativeTextResult.Candidate candidate : read.candidates) {
            if (nodes.length() > 1) nodes.append(',');
            nodes.append("{\"validatedAtMillis\":").append(candidate.validatedAtMillis)
                    .append(",\"validUntilMillis\":").append(frame.candidateValidUntilMillis(candidate))
                    .append(",\"textSource\":\"").append(candidate.textSource.name()).append('"')
                    .append(",\"matchStart\":").append(candidate.matchStart).append(",\"matchEnd\":").append(candidate.matchEnd)
                    .append(",\"normalizedLength\":").append(candidate.normalizedLength)
                    .append(",\"lineCount\":").append(candidate.regions.size())
                    .append(",\"exactGeometry\":").append(candidate.exactGeometry).append('}');
        }
        nodes.append(']');
        return "REPLACEMENT_NATIVE {\"schemaVersion\":" + nativeSchema(read.readMode) + ",\"clockDomain\":\"UPTIME_MILLIS\",\"connection\":" + connection
                + ",\"epoch\":" + source.epoch + ",\"sequence\":" + source.sequence + ",\"request\":" + frame.requestIdentity
                + ",\"observationOrdinal\":" + frame.observationOrdinal
                + ",\"queuedAtMillis\":" + frame.queuedAtMillis + ",\"startedAtMillis\":" + frame.startedAtMillis
                + ",\"readStartedAtMillis\":" + source.readStartedAtMillis + ",\"readFinishedAtMillis\":" + source.readFinishedAtMillis
                + ",\"receivedAtMillis\":" + source.receivedAtMillis + ",\"maximumAgeMillis\":" + frame.maximumAgeMillis
                + ",\"coverage\":\"" + read.coverage.name() + "\",\"readMode\":\"" + read.readMode.name() + "\",\"scanGeneration\":" + read.scanGeneration
                + ",\"confirmationGeneration\":" + read.confirmationGeneration + ",\"hardGeneration\":" + read.hardGeneration
                + ",\"classificationCompletedAtMillis\":" + read.classificationCompletedAtMillis
                + ",\"confirmationStartedAtMillis\":" + read.confirmationStartedAtMillis
                + ",\"visitedNodes\":" + read.visitedNodes + ",\"classifiedNodes\":" + read.classifiedNodes
                + ",\"confirmationReads\":" + read.confirmationReads + ",\"candidateCount\":" + read.candidates.size()
                + ",\"regionCount\":" + read.regionCount() + ",\"nodes\":" + nodes + "}";
    }

    private static int nativeSchema(NativeTextResult.ReadMode mode) {
        return mode == NativeTextResult.ReadMode.ADMITTED_NODE_COHORT_RECLASSIFY ? 4 : 3;
    }

    public static String trace(long connection, CensorEngine.Trace value) {
        if (connection <= 0) throw new IllegalArgumentException("Replacement connection identity");
        CensorEngine.WorkTiming work = value.work;
        return "REPLACEMENT_TRACE {\"schemaVersion\":1,\"clockDomain\":\"UPTIME_MILLIS\",\"stage\":\""
                + value.stage.name() + "\",\"connection\":" + connection + ",\"epoch\":" + value.epoch + ",\"imageRequest\":" + value.source
                + ",\"lane\":\"" + (value.lane == null ? "NONE" : value.lane.name())
                + "\",\"atMillis\":" + value.atMillis + ",\"queueMillis\":" + value.queueMillis
                + ",\"pixelAgeMillis\":" + value.pixelAgeMillis
                + ",\"preprocessMillis\":" + (work == null ? -1 : work.preprocessMillis)
                + ",\"runtimeMillis\":" + (work == null ? -1 : work.runtimeMillis)
                + ",\"postprocessMillis\":" + (work == null ? -1 : work.postprocessMillis) + "}";
    }

    /** Copied event coordinates only; unknown values and refused original clocks stay explicit. */
    public static String scroll(long connection, ScrollMotionLedger.Receipt value) {
        if (connection <= 0) throw new IllegalArgumentException("Replacement connection identity");
        ScrollMotionLedger.Sample sample = value.sample;
        SceneEngine.Region viewport = sample.reportedViewport;
        return "REPLACEMENT_SCROLL {\"schemaVersion\":2,\"clockDomain\":\"UPTIME_MILLIS\",\"connection\":"
                + connection + ",\"epoch\":" + sample.scope.epoch + ",\"geometry\":" + sample.scope.geometry
                + ",\"windowId\":" + sample.scope.windowId + ",\"protectionOrigin\":\"" + sample.scope.protection.origin.name()
                + "\",\"tapScope\":" + sample.scope.protection.tapScope + ",\"delivery\":" + sample.deliveryIdentity
                + ",\"owner\":" + sample.owner + ",\"producer\":" + sample.producer
                + ",\"ownerKind\":\"" + sample.ownerKind.name() + "\""
                + ",\"sourceAtMillis\":" + sample.sourceAtMillis + ",\"receivedAtMillis\":" + sample.receivedAtMillis
                + ",\"readStartedAtMillis\":" + sample.readStartedAtMillis + ",\"readFinishedAtMillis\":" + sample.readFinishedAtMillis
                + ",\"admittedAtMillis\":" + value.admittedAtMillis + ",\"admission\":\"" + value.admission.name()
                + "\",\"refusal\":\"" + value.refusal.name() + "\",\"generation\":" + value.generation
                + ",\"producerCount\":" + value.producerCount + ",\"intervalCount\":" + value.intervalCount
                + ",\"x\":" + coordinate(sample.x) + ",\"y\":" + coordinate(sample.y)
                + ",\"reportedViewport\":" + (viewport == null ? "null" : "{\"left\":" + viewport.left
                    + ",\"top\":" + viewport.top + ",\"right\":" + viewport.right + ",\"bottom\":" + viewport.bottom + "}")
                + ",\"pixelPhaseKnown\":false,\"memberApplicabilityKnown\":false,\"physicalErrorKnown\":false}";
    }

    private static String coordinate(ScrollMotionLedger.Coordinate value) {
        return "{\"valid\":" + value.valid + ",\"absoluteKnown\":" + value.absoluteKnown
                + ",\"absolute\":" + (value.absoluteKnown ? Long.toString(value.absolute) : "null")
                + ",\"deltaKnown\":" + value.deltaKnown + ",\"delta\":" + (value.deltaKnown ? Long.toString(value.delta) : "null") + "}";
    }

    public static String post(long connection, SnapshotCensorSurface.Post value) {
        if (connection <= 0) throw new IllegalArgumentException("Replacement connection identity");
        CensorEngine.PaintReceipt paint = value.acceptedPaint;
        SceneEngine.Source canvas = paint == null ? null : paint.canvas;
        return "REPLACEMENT_POST {\"schemaVersion\":1,\"clockDomain\":\"UPTIME_MILLIS\",\"connection\":"
                + connection + ",\"endpointGeneration\":"
                + value.endpointGeneration + ",\"postSerial\":" + value.postSerial
                + ",\"recordedAtMillis\":" + value.recordedAtMillis + ",\"postReturnedAtMillis\":" + value.postReturnedAtMillis
                + ",\"uiAdmittedAtMillis\":" + value.uiAdmittedAtMillis + ",\"compositorAtMillis\":" + value.compositorAtMillis
                + ",\"queuedNanos\":" + value.queuedNanos + ",\"lockNanos\":" + value.lockNanos
                + ",\"recordingNanos\":" + value.recordingNanos + ",\"postNanos\":" + value.postNanos
                + ",\"mainThread\":" + value.mainThread + ",\"acceptedPaint\":" + (paint != null)
                + ",\"memberCount\":" + (paint == null ? 0 : paint.members.size())
                + ",\"canvasKnown\":" + (canvas != null) + ",\"canvasKind\":\"" + (canvas == null ? "NONE" : canvas.kind.name())
                + "\",\"epoch\":" + (canvas == null ? 0 : canvas.epoch) + ",\"sequence\":" + (canvas == null ? 0 : canvas.sequence)
                + ",\"geometry\":" + (canvas == null ? 0 : canvas.geometry)
                + ",\"imageRequest\":" + (canvas == null ? 0 : canvas.imageRequestIdentity)
                + ",\"pixelAtMillis\":" + (canvas == null ? -1 : canvas.pixelAtMillis) + "}";
    }
}
