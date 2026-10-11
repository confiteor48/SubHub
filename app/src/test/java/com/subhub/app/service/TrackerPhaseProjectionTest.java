package com.subhub.app.service;

import com.subhub.app.detection.BBox;
import com.subhub.app.detection.Detection;
import com.subhub.app.detection.DetectorConfig;
import com.subhub.app.detection.ObjectTracker;
import com.subhub.app.detection.RenderSourceReference;
import com.subhub.app.detection.TrackedObject;
import com.subhub.app.detection.VisualIdentityReconciler;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public final class TrackerPhaseProjectionTest {
    @Test public void fastForwardAndReverseCannotAssignThePreviousViewportNeighbor() {
        for (int scroll : new int[] {0, 20, 400, -400}) {
            ObjectTracker tracker = tracker();
            tracker.update(List.of(face(500), face(900)), 1_000_000_000L);
            tracker.update(List.of(face(500), face(900)), 1_100_000_000L);
            List<Detection> source = List.of(face(500 - scroll), face(900 - scroll));
            List<TrackedObject> sourceTracks = TrackerPhaseProjection.forSource(
                    tracker.activeTracks(), 0, 0, 0, scroll, 1344, 2992, 1344, 2992);
            VisualIdentityReconciler.Result identity = VisualIdentityReconciler.reconcile(
                    source, List.of(), sourceTracks);
            assertEquals(2, identity.realtimeLinked());
            assertEquals(1, identity.detections().get(0).getTrackId());
            assertEquals(2, identity.detections().get(1).getTrackId());
            // Identity analysis is read-only. The normal consume-motion/update path still owns mutation.
            assertEquals(500, tracker.activeTracks().get(0).getRawBox().getY());
            tracker.offsetActiveTracks(0, -scroll, 1344, 2992);
            List<TrackedObject> result = tracker.update(identity.detections(), 1_430_000_000L);
            assertEquals(2, result.size());
            assertEquals(500 - scroll, result.get(0).getRawBox().getY());
            assertEquals(900 - scroll, result.get(1).getRawBox().getY());
            assertEquals(0, result.get(0).getFramesMissing());
            assertEquals(0, result.get(1).getFramesMissing());
        }
    }

    @Test public void scaledAxesAndConsumedCameraUseOneSourcePhase() {
        ObjectTracker tracker = tracker();
        List<TrackedObject> tracks = tracker.update(List.of(face(500)), 1_000_000_000L);
        List<TrackedObject> projected = TrackerPhaseProjection.forSource(tracks,
                100, 300, 160, 500, 1344, 2992, 2688, 5984);
        assertEquals(new BBox(130, 400, 277, 255), projected.get(0).getRawBox());
        assertEquals(new BBox(160, 500, 277, 255), tracks.get(0).getRawBox());
    }

    @Test public void projectionPreservesSourceMetadataAndDetachedHistory() {
        ObjectTracker tracker = tracker();
        RenderSourceReference reference = RenderSourceReference.known(
                new RenderSourceReference.Origin(1, 2, 3, 1344, 2992, 4), 100, 5, 6);
        tracker.update(List.of(face(500).withRenderSourceReference(reference)), 1_000_000_000L);
        List<TrackedObject> tracks = tracker.update(List.of(), 1_100_000_000L);
        TrackedObject original = tracks.get(0);
        TrackedObject projected = TrackerPhaseProjection.forSource(tracks,
                0, 0, 0, 200, 1344, 2992, 1344, 2992).get(0);
        assertNotSame(original, projected);
        assertSame(reference, projected.getRenderSourceReference());
        assertEquals(original.getId(), projected.getId());
        assertEquals(original.getLastSeenNanos(), projected.getLastSeenNanos());
        assertEquals(original.getFramesMissing(), projected.getFramesMissing());
        assertEquals(original.getFramesTracked(), projected.getFramesTracked());
        assertEquals(original.getConfidence(), projected.getConfidence(), 0f);
        assertEquals(original.getObservationSource(), projected.getObservationSource());
        assertEquals(500, original.getRawBox().getY());
        tracker.offsetActiveTracks(0, -100, 1344, 2992);
        assertEquals(300, projected.getRawBox().getY());
    }

    @Test public void clippingAndHugeOffsetsNeverWrapIntoAnotherIdentity() {
        ObjectTracker tracker = tracker();
        List<TrackedObject> tracks = tracker.update(List.of(face(500)), 1_000_000_000L);
        assertEquals(new BBox(160, 0, 277, 155), TrackerPhaseProjection.forSource(tracks,
                0, 0, 0, 600, 1344, 2992, 1344, 2992).get(0).getRawBox());
        for (int extreme : new int[] {Integer.MAX_VALUE, Integer.MIN_VALUE, 10000, -10000}) {
            assertTrue(TrackerPhaseProjection.forSource(tracks,
                    0, 0, 0, extreme, 1344, 2992, 1344, 2992).isEmpty());
            assertTrue(tracks.get(0).isActive());
            assertEquals(500, tracks.get(0).getRawBox().getY());
        }
    }

    private static ObjectTracker tracker() {
        return new ObjectTracker(DetectorConfig.builder().motionPrediction(false).build());
    }

    private static Detection face(int y) {
        return new Detection("FACE_FEMALE", "face", .99f, new BBox(160, y, 277, 255), true, false);
    }
}
