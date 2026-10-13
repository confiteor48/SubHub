package com.subhub.app.diagnostics;

import com.subhub.app.censor.engine.CensorEngine;
import com.subhub.app.censor.engine.SceneEngine;
import com.subhub.app.censor.android.SnapshotCensorSurface;

/** Sanitized primitives only. These receipts never claim physical motion or compositor arrival. */
public final class ReplacementEngineTelemetry {
    private ReplacementEngineTelemetry() { }

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
