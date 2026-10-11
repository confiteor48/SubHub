package com.subhub.app.service;

import com.subhub.app.detection.TrackedObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Identity comparisons must share the screenshot phase, before live tracker motion is consumed. */
final class TrackerPhaseProjection {
    private TrackerPhaseProjection() { }

    static List<TrackedObject> forSource(List<TrackedObject> tracks,
            long trackerCameraX, long trackerCameraY, long sourceCameraX, long sourceCameraY,
            int sourceWidth, int sourceHeight, int viewportWidth, int viewportHeight) {
        InferenceScrollReprojector.ScreenMotion motion = InferenceScrollReprojector.screenMotion(
                trackerCameraX, trackerCameraY, sourceCameraX, sourceCameraY);
        int dx = Math.round((long) motion.dx * sourceWidth / (float) Math.max(1, viewportWidth));
        int dy = Math.round((long) motion.dy * sourceHeight / (float) Math.max(1, viewportHeight));
        List<TrackedObject> projected = new ArrayList<>(tracks.size());
        for (TrackedObject track : tracks) {
            if (track == null || !track.isActive()) continue;
            TrackedObject snapshot = track.snapshotOffsetToViewport(dx, dy, sourceWidth, sourceHeight);
            if (snapshot.isActive() && snapshot.getBox().getArea() > 0L) projected.add(snapshot);
        }
        return Collections.unmodifiableList(projected);
    }
}
