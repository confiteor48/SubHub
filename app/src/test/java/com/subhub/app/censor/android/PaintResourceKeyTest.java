package com.subhub.app.censor.android;

import static org.junit.Assert.*;

import com.subhub.app.censor.engine.CensorEngine;
import com.subhub.app.censor.engine.SceneEngine;
import com.subhub.app.detection.DetectionPreset;
import com.subhub.app.settings.CensorAppearance;
import org.junit.Test;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;

public class PaintResourceKeyTest {
    private static CensorAppearance appearance(CensorAppearance.Type type, boolean reverse) {
        return new CensorAppearance(type, 70, 0, false, false, CensorAppearance.BorderEffect.CLASSIC,
                false, 0xffffffff, Collections.singletonList("BLOCKED"), reverse, 100, "rectangle", "SubHub", "Access blocked.");
    }
    private static PaintResourceKey key(Harness h, CensorAppearance appearance, long generation, long assets) {
        try (CensorEngine<String>.RenderView view = h.engine.acquireRenderView()) { return PaintResourceKey.of(view, appearance, generation, assets); }
    }

    @Test public void displayMotionAndSceneRevisionDoNotReloadUnchangedSourceLocalResources() {
        Harness h = new Harness(3); h.engine.start(42); h.image(0, true);
        CensorAppearance appearance = appearance(CensorAppearance.Type.PIXELATE, false);
        PaintResourceKey before = key(h, appearance, 1, 0); long revision;
        try (CensorEngine<String>.RenderView held = h.engine.acquireRenderView()) {
            revision = held.snapshot.revision; h.image(334, false); h.map();
            try (CensorEngine<String>.RenderView view = h.engine.acquireRenderView()) {
                assertNotEquals(revision, view.snapshot.revision);
                assertEquals(200, view.snapshot.groups.get(0).members.get(0).displayRegion.top, 0);
            }
            PaintResourceKey after = key(h, appearance, 1, 0);
            assertEquals(1, before.members.size()); assertEquals(before, after); assertEquals(before.hashCode(), after.hashCode());
        }
        h.close();
    }

    @Test public void canvasPermissionAtSameSceneRevisionIsAnIndependentResourceDependency() {
        Harness h = new Harness(3); h.engine.start(42); h.image(0, true);
        CensorAppearance appearance = appearance(CensorAppearance.Type.BLUR, true);
        try (CensorEngine<String>.RenderView held = h.engine.acquireRenderView()) {
            h.image(334, false); h.map();
            PaintResourceKey before = key(h, appearance, 1, 0); long revision;
            try (CensorEngine<String>.RenderView view = h.engine.acquireRenderView()) { revision = view.snapshot.revision; }
            h.engine.canvasSamplingControls(true); h.ui.all(); PaintResourceKey after = key(h, appearance, 1, 0);
            try (CensorEngine<String>.RenderView view = h.engine.acquireRenderView()) {
                assertEquals(revision, view.snapshot.revision); assertNotNull(view.sourcePixels(1)); assertNull(view.canvasPixels());
            }
            assertNotEquals(before, after); assertNull(before.canvas); assertEquals(2, after.canvas.sequence);
            assertFalse(after.canvasPixels); assertTrue(after.members.isEmpty());
        }
        h.close();
    }

    @Test public void customAndNoiseResourcesDoNotReloadOnEveryAiSource() {
        for (CensorAppearance.Type type : new CensorAppearance.Type[]{CensorAppearance.Type.CUSTOM, CensorAppearance.Type.STATIC}) {
            Harness h = new Harness(3); h.engine.start(42); h.image(0, true);
            CensorAppearance appearance = appearance(type, false); PaintResourceKey before = key(h, appearance, 1, 8);
            h.image(334, true); PaintResourceKey after = key(h, appearance, 1, 8);
            assertTrue(before.members.isEmpty()); assertEquals(before, after);
            assertNotEquals(before, key(h, appearance, 2, 8)); assertNotEquals(before, key(h, appearance, 1, 9)); h.close();
        }
    }

    @Test public void newEvidenceSourceIsNotAnOldPixelsCacheHit() {
        Harness h = new Harness(3); h.engine.start(42); h.image(0, true);
        CensorAppearance appearance = appearance(CensorAppearance.Type.GLITCH, false); PaintResourceKey before = key(h, appearance, 1, 0);
        h.image(334, true); assertNotEquals(before, key(h, appearance, 1, 0)); h.close();
    }

    @Test public void absentMemberPixelsRemainExplicitWithoutInventedInputDependencies() {
        Harness h = new Harness(0); h.engine.start(42); h.image(0, true);
        try (CensorEngine<String>.RenderView view = h.engine.acquireRenderView()) {
            assertEquals(1, view.snapshot.groups.size()); assertNull(view.sourcePixels(1));
            PaintResourceKey key = PaintResourceKey.of(view, appearance(CensorAppearance.Type.GLITCH, false), 1, 0);
            assertTrue(key.members.isEmpty()); assertNull(key.canvas);
        }
        h.close();
    }

    @Test public void existingPresetArtworkDimensionsCountsAndIndependentBudgetsArePreserved() {
        for (DetectionPreset preset : DetectionPreset.values()) {
            SnapshotPaintPolicy policy = SnapshotPaintPolicy.from(preset);
            assertEquals(preset.getCustomImageDimension(), policy.artworkDimension); assertEquals(preset.getCustomImageCount(), policy.artworkCount);
            assertEquals(4L * policy.artworkDimension * policy.artworkDimension * policy.artworkCount, policy.maximumArtworkBytes);
            assertTrue(policy.preparedBytes(appearance(CensorAppearance.Type.CUSTOM, false)) >= policy.maximumArtworkBytes);
            assertEquals(PreparedCensorPaint.DEFAULT_MAXIMUM_BYTES, policy.preparedBytes(appearance(CensorAppearance.Type.PIXELATE, true)));
        }
        assertEquals(64 * 1024 * 1024, SnapshotPaintPolicy.from(DetectionPreset.HIGH).preparedBytes(appearance(CensorAppearance.Type.CUSTOM, false)));
    }

    private static final class Queue implements Executor {
        final List<Runnable> values = new ArrayList<>();
        @Override public void execute(Runnable action) { values.add(action); }
        void all() { while (!values.isEmpty()) values.remove(0).run(); }
    }
    private static final class Harness implements AutoCloseable {
        long now; final Queue worker = new Queue(), ui = new Queue(); final List<Runnable> timers = new ArrayList<>();
        final List<CensorEngine.CaptureCallback<String>> callbacks = new ArrayList<>();
        final List<CensorEngine.Input<String>> inputs = new ArrayList<>(); final List<CensorEngine.Completion> completions = new ArrayList<>();
        final CensorEngine<String> engine;
        Harness(int effects) {
            engine = new CensorEngine<>(new CensorEngine.Policy(334, 0, 8, effects, false),
                    new SceneEngine.Policy(16, 100, 64, 256, 5000, 20000, .5), () -> now,
                    (action, delay) -> { timers.add(action); return () -> timers.remove(action); },
                    (request, callback) -> { assertTrue(request.tryDispatch()); callbacks.add(callback); },
                    Collections.singletonList(new CensorEngine.LaneWorker<>(SceneEngine.Lane.FAST, worker,
                            (input, completion) -> { inputs.add(input); completions.add(completion); })), ui, new CensorEngine.Observer() { });
        }
        void image(long at, boolean face) {
            now = at; timers.remove(0).run(); callbacks.get(callbacks.size() - 1).success(new CensorEngine.Captured<>("A" + at, 1000, 2400, -1, () -> { }));
            worker.all(); int index = inputs.size() - 1;
            completions.get(index).success(new SceneEngine.Batch(inputs.get(index).source, SceneEngine.Lane.FAST,
                    face ? Collections.singletonList(new SceneEngine.Observation("FACE", 1, 0, new SceneEngine.Region(100, 500, 200, 600), .9f)) : Collections.emptyList(),
                    Collections.emptyList()), new CensorEngine.WorkTiming(1, 2, 1)); ui.all();
        }
        void map() {
            assertTrue(engine.motion(new SceneEngine.Motion(engine.currentEpoch(), 1, 2, 1, 1,
                    SceneEngine.MotionAuthority.IMAGE_CORRESPONDENCE, new SceneEngine.Region(100, 500, 200, 600), 0, -300))); ui.all();
        }
        @Override public void close() { engine.close(); for (CensorEngine.CaptureCallback<String> callback : callbacks) callback.failure(0); worker.all(); ui.all(); }
    }
}
