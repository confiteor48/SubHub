package com.subhub.app.censor.engine;

import static org.junit.Assert.*;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;

public class PaintReceiptContractTest {
    @Test public void activeScopeHasNoSourceAndIsAuthenticatedToOneOwner() {
        Harness a = new Harness(), b = new Harness(); assertNull(a.engine.activeScope());
        a.start(); b.start(); CensorEngine.ActiveScope scope = a.engine.activeScope();
        assertEquals(42, scope.geometry); assertEquals(ProtectionScope.lab(7), scope.protectionScope);
        assertTrue(scope.same(a.engine.activeScope())); assertTrue(a.engine.isCurrent(scope));
        assertFalse(scope.same(b.engine.activeScope())); assertFalse(b.engine.isCurrent(scope));
        assertNull(a.engine.acquireRenderView()); assertEquals(0, a.callbacks.size());
        a.engine.changeScope(42, ProtectionScope.normal(8)); assertFalse(a.engine.isCurrent(scope));
        CensorEngine.ActiveScope changed = a.engine.activeScope(); assertEquals(ProtectionScope.normal(8), changed.protectionScope);
        a.engine.stop(); assertNull(a.engine.activeScope()); assertFalse(a.engine.isCurrent(changed));
        a.close(); b.close();
    }

    @Test public void coldReverseReceiptDoesNotInventSourceOrAcquisitionCompositorClocks() {
        Harness h = new Harness(); h.start(); h.now = 17;
        CensorEngine.PaintReceipt receipt = h.engine.acknowledgePaint(h.engine.activeScope(), -1, 9, Collections.emptyList(), true);
        assertNotNull(receipt); assertNull(receipt.canvas); assertEquals(-1, receipt.revision);
        assertEquals(17, receipt.atMillis); assertEquals(9, receipt.appearanceGeneration);
        assertEquals(-1, receipt.compositorAtMillis); assertTrue(receipt.reverseBackground); assertTrue(receipt.members.isEmpty());
        assertEquals(0, h.callbacks.size()); assertFalse(h.engine.captureInFlight());
        assertEquals(0, h.traces.stream().filter(t -> t.stage == CensorEngine.Stage.DRAWN).count());
        assertEquals(1, h.receipts.size()); h.close();
    }

    @Test public void emptyOrdinaryColdAndForeignPaintCannotBecomeFirstCensorReceipt() {
        Harness h = new Harness(), other = new Harness(); h.start(); other.start();
        assertNull(h.engine.acknowledgePaint(h.engine.activeScope(), -1, 0, Collections.emptyList(), false));
        assertNull(h.engine.acknowledgePaint(other.engine.activeScope(), -1, 0, Collections.emptyList(), true));
        assertNull(h.engine.acknowledgePaint(h.engine.activeScope(), -1, 0, Collections.singletonList(1L), true));
        assertTrue(h.receipts.isEmpty()); h.close(); other.close();
    }

    @Test public void actualSelectedMasksKeepOriginalProvenanceWithoutRenewingSceneOrSources() {
        Harness h = new Harness(); h.start(); h.image();
        long revision; SceneEngine.Mask mask;
        try (CensorEngine<String>.RenderView view = h.engine.acquireRenderView()) {
            revision = view.snapshot.revision; mask = view.snapshot.groups.get(0).members.get(0);
        }
        int requests = h.callbacks.size(); long epoch = h.engine.currentEpoch(); h.now = 31;
        CensorEngine.PaintReceipt receipt = h.engine.acknowledgePaint(h.engine.activeScope(), revision, 3, Collections.singletonList(mask.identity), false);
        assertNotNull(receipt); assertSame(mask, receipt.members.get(0)); assertEquals(ProtectionScope.lab(7), mask.evidenceSource.protectionScope);
        assertEquals(SceneEngine.SourceKind.IMAGE, receipt.canvas.kind); assertEquals(-1, receipt.canvas.pixelAtMillis);
        assertEquals(31, receipt.atMillis); assertEquals(-1, receipt.compositorAtMillis);
        assertThrows(UnsupportedOperationException.class, () -> receipt.members.clear());
        try (CensorEngine<String>.RenderView view = h.engine.acquireRenderView()) { assertEquals(revision, view.snapshot.revision); }
        assertEquals(epoch, h.engine.currentEpoch()); assertEquals(requests, h.callbacks.size()); assertEquals(0, h.closes);
        h.close(); assertEquals(1, h.closes);
    }

    @Test public void obsoleteScopeRevisionUnknownDuplicateAndReverseMemberIdsAreRejected() {
        Harness h = new Harness(); h.start(); CensorEngine.ActiveScope old = h.engine.activeScope(); h.image();
        long revision, id;
        try (CensorEngine<String>.RenderView view = h.engine.acquireRenderView()) {
            revision = view.snapshot.revision; id = view.snapshot.groups.get(0).members.get(0).identity;
        }
        assertNull(h.engine.acknowledgePaint(old, revision + 1, 0, Collections.singletonList(id), false));
        assertNull(h.engine.acknowledgePaint(old, revision, 0, Collections.singletonList(id + 100), false));
        assertNull(h.engine.acknowledgePaint(old, revision, 0, java.util.Arrays.asList(id, id), false));
        assertNull(h.engine.acknowledgePaint(old, revision, 0, Collections.singletonList(id), true));
        assertNull(h.engine.acknowledgePaint(old, -1, 0, Collections.emptyList(), true));
        h.engine.changeScope(42, ProtectionScope.normal(8));
        assertNull(h.engine.acknowledgePaint(old, revision, 0, Collections.singletonList(id), false));
        assertTrue(h.receipts.isEmpty()); h.close();
    }

    @Test public void expiredSnapshotCannotBeAcknowledgedAndFreshEmptyReverseHasCanvasNotFakeMembers() {
        Harness h = new Harness(); h.start(); h.image(); long revision, id;
        try (CensorEngine<String>.RenderView view = h.engine.acquireRenderView()) {
            revision = view.snapshot.revision; id = view.snapshot.groups.get(0).members.get(0).identity;
            assertNotEquals(Long.MAX_VALUE, view.snapshot.nextChangeAtMillis); h.now = view.snapshot.nextChangeAtMillis;
        }
        assertNull(h.engine.acknowledgePaint(h.engine.activeScope(), revision, 0, Collections.singletonList(id), false));
        try (CensorEngine<String>.RenderView fresh = h.engine.acquireRenderView()) {
            assertTrue(fresh.snapshot.groups.isEmpty());
            CensorEngine.PaintReceipt receipt = h.engine.acknowledgePaint(h.engine.activeScope(), fresh.snapshot.revision, 0, Collections.emptyList(), true);
            assertNotNull(receipt); assertSame(fresh.snapshot.destination, receipt.canvas); assertTrue(receipt.members.isEmpty());
        }
        h.close();
    }

    @Test public void observerScopeChangeCannotAuthorizeFollowingTapPublication() {
        Harness h = new Harness(); h.start(); h.onPaint = () -> h.engine.changeScope(99, ProtectionScope.normal(8));
        assertNull(h.engine.acknowledgePaint(h.engine.activeScope(), -1, 0, Collections.emptyList(), true));
        assertEquals(1, h.receipts.size()); assertEquals(42, h.receipts.get(0).scope.geometry);
        assertEquals(99, h.engine.activeScope().geometry); h.close();
    }

    @Test public void observerClockAdvanceCannotAuthorizeExpiredTapPublication() {
        Harness h = new Harness(); h.start(); h.image(); long revision, id, deadline;
        try (CensorEngine<String>.RenderView view = h.engine.acquireRenderView()) {
            revision = view.snapshot.revision; id = view.snapshot.groups.get(0).members.get(0).identity; deadline = view.snapshot.nextChangeAtMillis;
        }
        h.onPaint = () -> h.now = deadline;
        assertNull(h.engine.acknowledgePaint(h.engine.activeScope(), revision, 0, Collections.singletonList(id), false));
        assertEquals(1, h.receipts.size()); h.close();
    }

    private static final class Queue implements Executor {
        final List<Runnable> actions = new ArrayList<>();
        @Override public void execute(Runnable action) { actions.add(action); }
        void all() { while (!actions.isEmpty()) actions.remove(0).run(); }
    }
    private static final class Harness implements AutoCloseable {
        long now; int closes;
        Runnable onPaint = () -> { };
        final Queue ui = new Queue(), worker = new Queue(); final List<Runnable> timers = new ArrayList<>();
        final List<CensorEngine.CaptureCallback<String>> callbacks = new ArrayList<>();
        final List<CensorEngine.Trace> traces = new ArrayList<>(); final List<CensorEngine.PaintReceipt> receipts = new ArrayList<>();
        final CensorEngine<String> engine = new CensorEngine<>(new CensorEngine.Policy(334, 0, 8, 2, false),
                new SceneEngine.Policy(16, 100, 64, 256, 5000, 20000, .5), () -> now,
                (action, delay) -> { timers.add(action); return () -> timers.remove(action); },
                (request, callback) -> { assertTrue(request.tryDispatch()); callbacks.add(callback); },
                Collections.singletonList(new CensorEngine.LaneWorker<>(SceneEngine.Lane.FAST, worker,
                        (input, completion) -> completion.success(new SceneEngine.Batch(input.source, SceneEngine.Lane.FAST,
                                Collections.singletonList(new SceneEngine.Observation("FACE", 1, 0,
                                        new SceneEngine.Region(100, 500, 200, 600), .9f)), Collections.emptyList()),
                                new CensorEngine.WorkTiming(1, 2, 1)))), ui, new CensorEngine.Observer() {
                    @Override public void trace(CensorEngine.Trace trace) { traces.add(trace); }
                    @Override public void painted(CensorEngine.PaintReceipt receipt) {
                        assertFalse(Thread.holdsLock(engine)); receipts.add(receipt); onPaint.run();
                    }
                });
        void start() { engine.start(42, ProtectionScope.lab(7)); }
        void image() {
            timers.remove(0).run(); callbacks.get(0).success(new CensorEngine.Captured<>("A", 1000, 2400, -1, () -> closes++));
            worker.all(); ui.all();
        }
        @Override public void close() { engine.close(); for (CensorEngine.CaptureCallback<String> callback : callbacks) callback.failure(0); worker.all(); ui.all(); }
    }
}
