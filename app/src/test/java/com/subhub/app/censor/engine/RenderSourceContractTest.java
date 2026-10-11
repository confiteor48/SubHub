package com.subhub.app.censor.engine;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import org.junit.Test;

/** Source/resource controls, not evidence of measured physical motion or compositor arrival. */
public class RenderSourceContractTest {
    private static SceneEngine.Region region() { return new SceneEngine.Region(100, 500, 200, 600); }

    @Test public void memberLeaseIsNotPermissionToSampleThePresentedCanvas() {
        Harness h = new Harness(2);
        h.engine.start(42);
        Pixel a = h.image(0, true); h.ui.runAll();
        Pixel b = h.image(334, false);
        assertTrue(h.engine.motion(new SceneEngine.Motion(h.engine.currentEpoch(), 1, 2, 1, 1,
                SceneEngine.MotionAuthority.IMAGE_CORRESPONDENCE, region(), 0, -300)));
        h.ui.runAll();
        try (CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView()) {
            assertEquals(2, view.snapshot.destination.sequence);
            assertEquals(1, view.snapshot.groups.size());
            SceneEngine.Mask mask = view.snapshot.groups.get(0).members.get(0);
            assertEquals(1, mask.evidenceSource.sequence);
            assertEquals(200, mask.displayRegion.top, 0);
            assertSame(a, view.sourcePixels(1));
            assertNull("Member pixels are not the canvas", view.sourcePixels(2));
        }
        assertEquals(1, b.closes); h.close(); assertEquals(1, a.closes); assertEquals(1, h.drained);
    }

    @Test public void explicitCanvasReservesTheExistingCapAndKeepsEachMembersOrigin() {
        for (int quota : new int[]{0, 1, 2}) {
            Harness h = new Harness(quota); h.engine.canvasSamplingControls(true);
            h.engine.start(42, ProtectionScope.normal(17));
            Pixel a = h.image(0, true); h.ui.runAll();
            CensorEngine<Pixel>.RenderView held = h.engine.acquireRenderView();
            Pixel b = h.image(334, false);
            assertTrue(h.engine.motion(new SceneEngine.Motion(h.engine.currentEpoch(), 1, 2, 1, 1,
                    SceneEngine.MotionAuthority.IMAGE_CORRESPONDENCE, region(), 0, -300)));
            h.ui.runAll();
            try (CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView()) {
                assertSame(view.snapshot.destination, view.canvasSource);
                assertEquals(2, view.canvasSource.sequence);
                assertEquals(ProtectionScope.normal(17), view.canvasSource.protectionScope);
                assertEquals(quota == 0 ? null : b, view.canvasPixels());
                assertEquals(quota == 2 ? a : null, view.sourcePixels(1));
                assertEquals(1, view.snapshot.groups.size());
                assertEquals(200, view.snapshot.groups.get(0).members.get(0).displayRegion.top, 0);
                assertEquals(1, view.snapshot.groups.get(0).members.get(0).evidenceSource.sequence);
            }
            h.engine.close(); h.ui.runAll();
            assertEquals("Held handle still owns its original resource", quota == 0 ? 1 : 0, a.closes);
            if (quota > 0) assertSame(a, held.canvasPixels());
            assertEquals(0, h.drained); held.close();
            assertEquals(1, a.closes); assertEquals(1, b.closes); assertEquals(1, h.drained);
            assertThrows(IllegalStateException.class, held::canvasPixels);
        }
    }

    @Test public void missingCanvasIsExplicitAndControlsNeverResurrectAlreadyClosedPixels() {
        Harness h = new Harness(2); h.engine.start(42);
        Pixel a = h.image(0, true); h.ui.runAll(); Pixel b = h.image(334, false);
        assertTrue(h.engine.motion(new SceneEngine.Motion(h.engine.currentEpoch(), 1, 2, 1, 1,
                SceneEngine.MotionAuthority.IMAGE_CORRESPONDENCE, region(), 0, -300)));
        h.ui.runAll(); long revision, expiry, epoch = h.engine.currentEpoch();
        try (CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView()) {
            assertNull(view.canvasSource); assertNull(view.canvasPixels());
            revision = view.snapshot.revision; expiry = view.snapshot.nextChangeAtMillis;
        }
        assertEquals(1, b.closes); int captures = h.requests.size();
        h.engine.canvasSamplingControls(true); h.ui.runAll();
        try (CensorEngine<Pixel>.RenderView view = h.engine.acquireRenderView()) {
            assertEquals(2, view.canvasSource.sequence); assertNull(view.canvasPixels());
            assertSame(a, view.sourcePixels(1)); assertEquals(revision, view.snapshot.revision);
            assertEquals(expiry, view.snapshot.nextChangeAtMillis);
        }
        assertEquals(epoch, h.engine.currentEpoch()); assertEquals(captures, h.requests.size());
        h.close(); assertEquals(1, a.closes); assertEquals(1, b.closes); assertEquals(1, h.drained);
    }

    @Test public void blockedUiAndHeldHandlesDisposeOnlyAfterTheirActualRelease() {
        Harness h = new Harness(1); h.engine.canvasSamplingControls(true); h.engine.start(42);
        Pixel a = h.image(0, true); h.ui.runAll(); CensorEngine<Pixel>.RenderView heldA = h.engine.acquireRenderView();
        Pixel b = h.image(334, false); assertEquals(0, b.closes);
        h.engine.canvasSamplingControls(false); // Replaces the undelivered B-only resource packet.
        assertEquals(1, b.closes); assertEquals(0, a.closes);
        h.engine.canvasSamplingControls(true); h.ui.runAll();
        CensorEngine<Pixel>.RenderView heldB = h.engine.acquireRenderView();
        assertEquals(2, heldB.canvasSource.sequence); assertNull(heldB.canvasPixels());
        h.engine.close(); h.ui.runAll(); assertNull(h.engine.acquireRenderView()); assertEquals(0, h.drained);
        assertSame(a, heldA.canvasPixels()); heldB.close(); assertEquals(0, h.drained);
        heldA.close(); assertEquals(1, a.closes); assertEquals(1, h.drained);
        heldA.close(); heldB.close(); assertEquals(1, h.drained);
    }

    @Test public void protectionChangeFencesSameGeometryWithoutRelabelingOldCaptureOrBypassingSpacing() {
        Harness h = new Harness(1); h.engine.start(42, ProtectionScope.lab(3)); h.fire(0);
        CensorEngine.CaptureRequest old = h.requests.get(0); long epoch = h.engine.currentEpoch();
        h.now = 10; h.engine.changeScope(42, ProtectionScope.normal(4));
        assertEquals(epoch + 1, h.engine.currentEpoch()); assertEquals(ProtectionScope.lab(3), old.protectionScope);
        assertTrue(h.engine.captureInFlight()); Pixel late = new Pixel(); h.callbacks.get(0).success(late.payload());
        assertEquals(1, late.closes); assertTrue(h.inputs.isEmpty()); h.fire(333); assertEquals(1, h.requests.size());
        Pixel current = h.image(334, true); h.ui.runAll();
        assertEquals(42, h.requests.get(1).geometry); assertEquals(ProtectionScope.normal(4), h.requests.get(1).protectionScope);
        assertEquals(ProtectionScope.normal(4), h.inputs.get(0).source.protectionScope);
        h.engine.changeScope(43); // Legacy geometry-only API preserves explicitly supplied provenance.
        assertEquals(ProtectionScope.normal(4), h.inputs.get(0).source.protectionScope);
        Pixel next = h.image(668, true); assertEquals(ProtectionScope.normal(4), h.requests.get(2).protectionScope);
        h.close(); assertEquals(1, current.closes); assertEquals(1, next.closes); assertEquals(1, h.drained);
    }

    @Test public void labOriginAndCapturedTapScopeSurviveOldHandleAfterLabEnds() {
        Harness h = new Harness(1); h.engine.start(42, ProtectionScope.lab(3)); h.image(0, true); h.ui.runAll();
        CensorEngine<Pixel>.RenderView held = h.engine.acquireRenderView();
        h.engine.changeScope(42, ProtectionScope.normal(4));
        ProtectionScope captured = held.snapshot.groups.get(0).members.get(0).evidenceSource.protectionScope;
        assertEquals(ProtectionScope.lab(3), captured); assertFalse(captured.isNormal()); assertEquals(3, captured.tapScope);
        assertNull(h.engine.acquireRenderView()); h.close(); assertEquals(0, h.drained); held.close(); assertEquals(1, h.drained);
    }

    @Test public void descriptorsCannotBeReplayedToRelabelProvenanceAndLegacyFactoriesStayUnspecified() {
        SceneEngine scene = new SceneEngine(1, new SceneEngine.Policy(16, 100, 64, 256, 5000, 20000, .5));
        SceneEngine.Source a = SceneEngine.Source.image(1, 1, 1, 42, 1000, 2400, 10, -1, ProtectionScope.lab(3));
        assertTrue(scene.register(a));
        assertTrue(scene.register(SceneEngine.Source.image(1, 1, 1, 42, 1000, 2400, 10, -1, ProtectionScope.lab(3))));
        assertFalse(scene.register(SceneEngine.Source.image(1, 1, 1, 42, 1000, 2400, 10, -1, ProtectionScope.normal(3))));
        assertFalse(scene.register(SceneEngine.Source.image(1, 1, 1, 42, 1000, 2400, 10, -1, ProtectionScope.lab(4))));
        assertEquals(ProtectionScope.UNSPECIFIED, new SceneEngine.Source(1, 2, 42, 1000, 2400, 10, -1).protectionScope);
        assertEquals(ProtectionScope.UNSPECIFIED, SceneEngine.Source.image(1, 3, 3, 42, 1000, 2400, 10, -1).protectionScope);
        assertEquals(ProtectionScope.UNSPECIFIED, SceneEngine.Source.nativeText(1, 4, 42, 1000, 2400, 7, 0, 5, 10).protectionScope);
        assertFalse(ProtectionScope.UNSPECIFIED.isNormal());
        assertThrows(IllegalArgumentException.class, () -> ProtectionScope.normal(-1));
        assertThrows(IllegalArgumentException.class, () -> ProtectionScope.lab(-1));
    }

    private static final class Pixel {
        int closes;
        CensorEngine.Captured<Pixel> payload() {
            return new CensorEngine.Captured<>(this, 1000, 2400, -1,
                    () -> { assertEquals(0, closes); closes++; });
        }
    }
    private static final class Queue implements Executor {
        final List<Runnable> actions = new ArrayList<>();
        @Override public void execute(Runnable action) { actions.add(action); }
        void runAll() { while (!actions.isEmpty()) actions.remove(0).run(); }
    }
    private static final class Timed {
        final long at; final Runnable action; boolean cancelled, ran;
        Timed(long at, Runnable action) { this.at = at; this.action = action; }
    }
    private static final class Harness {
        long now; int drained;
        final Queue workers = new Queue(), ui = new Queue();
        final List<Timed> timers = new ArrayList<>();
        final List<CensorEngine.CaptureRequest> requests = new ArrayList<>();
        final List<CensorEngine.CaptureCallback<Pixel>> callbacks = new ArrayList<>();
        final List<CensorEngine.Input<Pixel>> inputs = new ArrayList<>();
        final List<CensorEngine.Completion> completions = new ArrayList<>();
        final CensorEngine<Pixel> engine;
        Harness(int effects) {
            engine = new CensorEngine<>(new CensorEngine.Policy(334, 0, 8, effects, false),
                    new SceneEngine.Policy(16, 100, 64, 256, 5000, 20000, .5), () -> now,
                    (action, delay) -> { Timed value = new Timed(now + delay, action); timers.add(value); return () -> value.cancelled = true; },
                    (request, callback) -> { assertTrue(request.tryDispatch()); requests.add(request); callbacks.add(callback); },
                    Collections.singletonList(new CensorEngine.LaneWorker<>(SceneEngine.Lane.FAST, workers,
                            (input, completion) -> { inputs.add(input); completions.add(completion); })), ui,
                    new CensorEngine.Observer() { @Override public void drained() { drained++; } });
        }
        void fire(long at) {
            assertTrue(at >= now); now = at;
            boolean found;
            do {
                found = false;
                for (Timed value : new ArrayList<>(timers)) {
                    if (!value.ran && !value.cancelled && value.at <= now) {
                        value.ran = true; value.action.run(); found = true;
                    }
                }
            } while (found);
        }
        Pixel image(long at, boolean detection) {
            fire(at); Pixel value = new Pixel(); callbacks.get(callbacks.size() - 1).success(value.payload());
            workers.runAll(); int index = inputs.size() - 1;
            completions.get(index).success(new SceneEngine.Batch(inputs.get(index).source, SceneEngine.Lane.FAST,
                    detection ? Collections.singletonList(new SceneEngine.Observation("FACE", 1, 0, region(), .9f))
                            : Collections.emptyList(), Collections.emptyList()), new CensorEngine.WorkTiming(1, 2, 1));
            return value;
        }
        void close() {
            engine.close(); for (CensorEngine.CaptureCallback<Pixel> callback : callbacks) callback.failure(0);
            for (CensorEngine.Completion completion : completions) completion.cancelled();
            workers.runAll(); ui.runAll();
        }
    }
}
