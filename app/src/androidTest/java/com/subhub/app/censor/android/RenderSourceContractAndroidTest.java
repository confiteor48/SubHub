package com.subhub.app.censor.android;

import static org.junit.Assert.*;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.subhub.app.censor.engine.CensorEngine;
import com.subhub.app.censor.engine.ProtectionScope;
import com.subhub.app.censor.engine.SceneEngine;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;

/** Actual Android bitmap leases/software sampling; not hardware retirement or live motion proof. */
@RunWith(AndroidJUnit4.class)
public class RenderSourceContractAndroidTest {
    private static SceneEngine.Region region() { return new SceneEngine.Region(10, 100, 30, 120); }

    @Test public void canvasAndMovedMemberSampleTheirOwnColorsAndLeaseUntilActualRelease() {
        try (Harness h = new Harness(2)) {
            h.engine.canvasSamplingControls(true); h.engine.start(42, ProtectionScope.normal(7));
            Bitmap a = h.image(0, Color.RED, true); h.ui.runAll();
            Bitmap b = h.image(334, Color.BLUE, false); h.map(); h.ui.runAll();
            CensorEngine<Bitmap>.RenderView held = h.engine.acquireRenderView();
            try {
                SceneEngine.Mask mask = held.snapshot.groups.get(0).members.get(0);
                assertEquals(40, mask.displayRegion.top, 0); assertEquals(100, mask.evidenceRegion.top, 0);
                assertSame(a, held.sourcePixels(mask.evidenceSource.sequence));
                assertSame(b, held.canvasPixels()); assertSame(held.snapshot.destination, held.canvasSource);
                Bitmap output = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888);
                try {
                    Canvas canvas = new Canvas(output); Paint paint = new Paint();
                    canvas.drawBitmap(held.canvasPixels(), 0, 0, paint);
                    canvas.drawBitmap(held.sourcePixels(mask.evidenceSource.sequence),
                            new Rect(10, 100, 30, 120), new Rect(10, 40, 30, 60), paint);
                    assertEquals(Color.RED, output.getPixel(20, 50));
                    assertEquals(Color.BLUE, output.getPixel(20, 110));
                } finally { output.recycle(); }
                h.engine.close(); h.ui.runAll(); assertEquals(0, h.drained);
                assertFalse(a.isRecycled()); assertFalse(b.isRecycled());
                assertEquals(Color.RED, held.sourcePixels(1).getPixel(20, 110));
                assertEquals(Color.BLUE, held.canvasPixels().getPixel(20, 110));
            } finally { held.close(); }
            assertTrue(a.isRecycled()); assertTrue(b.isRecycled()); assertEquals(1, h.drained);
        }
    }

    @Test public void canvasPriorityUsesOneSlotAndMissingPixelsNeverSubstituteTheRedMember() {
        try (Harness h = new Harness(1)) {
            h.engine.canvasSamplingControls(true); h.engine.start(42);
            Bitmap a = h.image(0, Color.RED, true); h.ui.runAll();
            Bitmap b = h.image(334, Color.BLUE, false); h.map(); h.ui.runAll();
            try (CensorEngine<Bitmap>.RenderView view = h.engine.acquireRenderView()) {
                assertSame(b, view.canvasPixels()); assertNull(view.sourcePixels(1));
                assertEquals(1, view.snapshot.groups.size()); assertTrue(a.isRecycled());
            }
            h.engine.canvasSamplingControls(false); h.ui.runAll(); assertTrue(b.isRecycled());
            h.engine.canvasSamplingControls(true); h.ui.runAll();
            try (CensorEngine<Bitmap>.RenderView view = h.engine.acquireRenderView()) {
                assertEquals(2, view.canvasSource.sequence); assertNull(view.canvasPixels());
                assertNull(view.sourcePixels(1)); assertEquals(1, view.snapshot.groups.size());
            }
        }
    }

    @Test public void capturedLabScopeCannotBecomeNormalWhenOldPixelsFinishAfterScopeChange() {
        try (Harness h = new Harness(1)) {
            h.engine.start(42, ProtectionScope.lab(4)); Bitmap a = h.image(0, Color.RED, true); h.ui.runAll();
            CensorEngine<Bitmap>.RenderView held = h.engine.acquireRenderView();
            try {
                h.engine.changeScope(42, ProtectionScope.normal(5)); assertNull(h.engine.acquireRenderView());
                assertEquals(ProtectionScope.lab(4), held.snapshot.destination.protectionScope);
                assertFalse(held.snapshot.groups.get(0).members.get(0).evidenceSource.protectionScope.isNormal());
                assertEquals(Color.RED, held.sourcePixels(1).getPixel(20, 110));
                h.engine.close(); h.ui.runAll(); assertFalse(a.isRecycled()); assertEquals(0, h.drained);
            } finally { held.close(); }
            assertTrue(a.isRecycled()); assertEquals(1, h.drained);
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
    private static final class Harness implements AutoCloseable {
        long now; int drained;
        final Queue workers = new Queue(), ui = new Queue(); final List<Timed> timers = new ArrayList<>();
        final List<CensorEngine.CaptureCallback<Bitmap>> callbacks = new ArrayList<>();
        final List<CensorEngine.Input<Bitmap>> inputs = new ArrayList<>();
        final List<CensorEngine.Completion> completions = new ArrayList<>(); final CensorEngine<Bitmap> engine;
        Harness(int cap) {
            engine = new CensorEngine<>(new CensorEngine.Policy(334, 0, 8, cap, false),
                    new SceneEngine.Policy(16, 100, 64, 256, 5000, 20000, .5), () -> now,
                    (action, delay) -> { Timed value = new Timed(now + delay, action); timers.add(value); return () -> value.cancelled = true; },
                    (request, callback) -> { assertTrue(request.tryDispatch()); callbacks.add(callback); },
                    Collections.singletonList(new CensorEngine.LaneWorker<>(SceneEngine.Lane.FAST, workers,
                            (input, completion) -> { inputs.add(input); completions.add(completion); })), ui,
                    new CensorEngine.Observer() { @Override public void drained() { drained++; } });
        }
        Bitmap image(long at, int color, boolean detection) {
            now = at;
            for (Timed value : new ArrayList<>(timers)) if (!value.ran && !value.cancelled && value.at <= now) {
                value.ran = true; value.action.run();
            }
            Bitmap value = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888); value.eraseColor(color);
            callbacks.get(callbacks.size() - 1).success(new CensorEngine.Captured<>(value, 100, 200, -1, value::recycle));
            workers.runAll(); int index = inputs.size() - 1;
            completions.get(index).success(new SceneEngine.Batch(inputs.get(index).source, SceneEngine.Lane.FAST,
                    detection ? Collections.singletonList(new SceneEngine.Observation("FACE", 1, 0, region(), .9f))
                            : Collections.emptyList(), Collections.emptyList()), new CensorEngine.WorkTiming(1, 2, 1));
            return value;
        }
        void map() {
            assertTrue(engine.motion(new SceneEngine.Motion(engine.currentEpoch(), 1, 2, 1, 1,
                    SceneEngine.MotionAuthority.IMAGE_CORRESPONDENCE, region(), 0, -60)));
        }
        @Override public void close() {
            engine.close(); for (CensorEngine.CaptureCallback<Bitmap> callback : callbacks) callback.failure(0);
            for (CensorEngine.Completion completion : completions) completion.cancelled(); workers.runAll(); ui.runAll();
            assertEquals(1, drained);
        }
    }
}
