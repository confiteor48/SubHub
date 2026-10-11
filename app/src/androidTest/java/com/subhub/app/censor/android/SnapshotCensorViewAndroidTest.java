package com.subhub.app.censor.android;

import static org.junit.Assert.*;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.RenderNode;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.PixelCopy;
import android.view.View;
import android.view.ViewTreeObserver;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;

import com.subhub.app.censor.engine.CensorEngine;
import com.subhub.app.censor.engine.PixelGeometry;
import com.subhub.app.censor.engine.ProtectionScope;
import com.subhub.app.censor.engine.SceneEngine;
import com.subhub.app.detection.BBox;
import com.subhub.app.detection.DetectionPreset;
import com.subhub.app.penance.CensorTapTracker;
import com.subhub.app.settings.CensorAppearance;
import com.subhub.app.settings.EffectPalette;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Synthetic attached-surface controls, not a live Accessibility/Pixel performance qualification. */
@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion = 30)
public class SnapshotCensorViewAndroidTest {
    private static CensorAppearance appearance(CensorAppearance.Type type, boolean reverse) {
        return new CensorAppearance(type, 70, 0, false, false, CensorAppearance.BorderEffect.CLASSIC,
                false, Color.WHITE, new EffectPalette(type == CensorAppearance.Type.CUSTOM ? Color.MAGENTA : Color.GREEN, Color.BLUE, Color.WHITE),
                Collections.singletonList("BLOCKED"), reverse, 100, "rectangle", "SubHub", "Access blocked.");
    }
    private static void main(Runnable action) { InstrumentationRegistry.getInstrumentation().runOnMainSync(action); }
    private static void await(BooleanSupplier condition) throws Exception {
        long end = SystemClock.uptimeMillis() + 5000;
        while (!condition.getAsBoolean() && SystemClock.uptimeMillis() < end) Thread.sleep(10);
        assertTrue("Bounded native condition", condition.getAsBoolean());
    }

    @Test public void actualOwnedParentPaintsPhysicalCoordinatesAndOnlyMemberInteriorsAreAttributed() throws Exception {
        try (Fixture f = new Fixture()) {
            f.image(CensorAppearance.Type.BOX, false, ProtectionScope.normal(f.taps.scope()), 0);
            Bitmap output = f.capture();
            try {
                assertNotEquals(f.diagnostic(), Color.BLACK, output.getPixel(70, 70)); assertEquals(Color.BLACK, output.getPixel(150, 70));
                assertTrue(f.hit(70, 70)); assertFalse(f.hit(150, 70)); assertFalse(f.hit(5, 5));
                assertEquals(Color.BLACK, output.getPixel(60, 60)); assertFalse(f.hit(60, 60));
                CensorEngine.PaintReceipt receipt = f.receipts.get(f.receipts.size() - 1);
                assertEquals(2, receipt.members.size()); assertEquals(SceneEngine.SourceKind.IMAGE, receipt.canvas.kind);
                assertEquals(-1, receipt.canvas.pixelAtMillis); assertEquals(-1, receipt.compositorAtMillis);
                assertEquals(ProtectionScope.normal(f.taps.scope()), receipt.members.get(0).evidenceSource.protectionScope);
                assertEquals(0, f.view.preparationCount()); export(output, "owned-position-and-gap");
            } finally { output.recycle(); }
        }
    }

    @Test public void softwareAndHardwareExportsDuringTraversalCannotPublishClearOrPrepare() throws Exception {
        try (Fixture f = new Fixture()) {
            f.image(CensorAppearance.Type.PIXELATE, false, ProtectionScope.normal(f.taps.scope()), 0);
            await(f.view::resourcesMatchCurrent); f.redraw(); int prepared = f.view.preparationCount();
            AtomicInteger attempts = new AtomicInteger(); AtomicReference<Throwable> failure = new AtomicReference<>();
            ViewTreeObserver.OnDrawListener listener = () -> {
                try {
                    int receipts = f.receipts.size(); long scope = f.taps.scope();
                    Bitmap software = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888);
                    try { f.view.draw(new Canvas(software)); } finally { software.recycle(); }
                    RenderNode node = new RenderNode("export-not-surface"); node.setPosition(0, 0, 320, 320);
                    Canvas hardware = node.beginRecording(320, 320);
                    try { hardware.clipRect(0, 0, 1, 1); f.view.draw(hardware); } finally { node.endRecording(); node.discardDisplayList(); }
                    assertEquals(receipts, f.receipts.size()); assertEquals(scope, f.taps.scope());
                    assertEquals(prepared, f.view.preparationCount()); assertTrue(f.hit(70, 70)); attempts.incrementAndGet();
                } catch (Throwable problem) { failure.set(problem); }
            };
            main(() -> f.activity.surface.getViewTreeObserver().addOnDrawListener(listener)); f.redraw();
            main(() -> f.activity.surface.getViewTreeObserver().removeOnDrawListener(listener));
            assertNull(failure.get()); assertTrue(attempts.get() > 0); assertTrue(f.hit(70, 70));
        }
    }

    @Test public void drawReacquiresExpiredOwnerStateInsteadOfReplayingPreparedMasks() throws Exception {
        try (Fixture f = new Fixture()) {
            f.image(CensorAppearance.Type.PIXELATE, false, ProtectionScope.normal(f.taps.scope()), 0);
            await(f.view::resourcesMatchCurrent); f.redraw(); assertTrue(f.hit(70, 70));
            long deadline;
            try (CensorEngine<PixelFrame>.RenderView view = f.h.engine.acquireRenderView()) { deadline = view.snapshot.nextChangeAtMillis; }
            assertNotEquals(Long.MAX_VALUE, deadline); f.h.now = deadline; f.redraw();
            assertFalse(f.hit(70, 70)); Bitmap output = f.capture();
            try { assertEquals(Color.BLACK, output.getPixel(70, 70)); export(output, "expired-fresh-owner"); } finally { output.recycle(); }
        }
    }

    @Test public void coldReverseHasActualBackgroundWithoutSourceOrCaptureAndOrdinaryColdDoesNotAck() throws Exception {
        try (Fixture f = new Fixture()) {
            main(() -> { f.h.engine.start(42, ProtectionScope.normal(f.taps.scope())); f.configure(CensorAppearance.Type.BOX, true, 0, true); });
            f.redraw(); assertTrue(f.receipts.size() > 0);
            CensorEngine.PaintReceipt receipt = f.receipts.get(f.receipts.size() - 1);
            assertNull(receipt.canvas); assertEquals(-1, receipt.revision); assertEquals(-1, receipt.compositorAtMillis);
            assertTrue(receipt.reverseBackground); assertTrue(receipt.members.isEmpty()); assertTrue(f.h.callbacks.isEmpty());
            assertFalse(f.hit(70, 70)); Bitmap output = f.capture();
            try { assertNotEquals(Color.BLACK, output.getPixel(70, 70)); export(output, "source-free-cold-reverse"); } finally { output.recycle(); }
            int before = f.receipts.size(); main(() -> f.configure(CensorAppearance.Type.BOX, false, 1, true)); f.redraw();
            assertEquals(before, f.receipts.size()); assertTrue(f.h.callbacks.isEmpty());
        }
    }

    @Test public void latePreparationCannotInstallOldAppearanceAndSoftwareInputsAreRecycled() throws Exception {
        CountDownLatch began = new CountDownLatch(1), finish = new CountDownLatch(1); AtomicReference<Bitmap> old = new AtomicReference<>();
        SnapshotCensorView.ArtworkLoader loader = (policy, revision, obsolete) -> {
            assertNotEquals(Looper.getMainLooper(), Looper.myLooper()); Bitmap bitmap = artwork(revision == 0 ? Color.GREEN : Color.BLUE);
            if (revision == 0) { old.set(bitmap); began.countDown(); waitLatch(finish); }
            return Collections.singletonList(bitmap);
        };
        try (Fixture f = new Fixture(loader)) {
            try {
                f.image(CensorAppearance.Type.CUSTOM, false, ProtectionScope.normal(f.taps.scope()), 0);
                assertTrue(began.await(5, TimeUnit.SECONDS)); main(() -> f.configure(CensorAppearance.Type.CUSTOM, false, 1, true));
                f.redraw(); assertTrue(f.view.preparedOutputCount() <= 2); finish.countDown();
                await(() -> f.view.preparationCount() == 2 && f.view.resourcesMatchCurrent() && old.get().isRecycled()); f.redraw();
                Bitmap output = f.capture(); try { assertEquals(Color.BLUE, output.getPixel(70, 70)); export(output, "new-art-generation"); } finally { output.recycle(); }
            } finally { finish.countDown(); }
        }
    }

    @Test public void detachAndCloseWaitForRealPreparationCleanupAndPreventLateInstallation() throws Exception {
        CountDownLatch began = new CountDownLatch(1), finish = new CountDownLatch(1); AtomicReference<Bitmap> input = new AtomicReference<>();
        try (Fixture f = new Fixture((policy, revision, obsolete) -> {
            Bitmap bitmap = artwork(Color.GREEN); input.set(bitmap); began.countDown(); waitLatch(finish); return Collections.singletonList(bitmap);
        })) {
            try {
                f.image(CensorAppearance.Type.CUSTOM, false, ProtectionScope.normal(f.taps.scope()), 0); assertTrue(began.await(5, TimeUnit.SECONDS));
                main(() -> { f.activity.surface.removeView(f.view); f.view.close(); f.h.engine.close(); });
                assertFalse(f.view.managedResourcesDrained()); assertFalse(f.view.preparationTerminated()); assertFalse(f.hit(70, 70));
                assertFalse("The executing preparation still owns its source read", f.h.lastImage.isRecycled());
                finish.countDown(); await(() -> f.view.managedResourcesDrained() && f.view.preparationTerminated());
                assertTrue(input.get().isRecycled()); assertTrue(f.h.lastImage.isRecycled()); assertEquals(0, f.view.preparedOutputCount()); assertFalse(f.hit(70, 70));
            } finally { finish.countDown(); }
        }
    }

    @Test public void boundedIndexedArtworkSeamIgnoresArbitraryIteratorAndCopiesBeforeRecycle() throws Exception {
        AtomicReference<Bitmap> input = new AtomicReference<>();
        try (Fixture f = new Fixture((policy, revision, obsolete) -> {
            Bitmap bitmap = artwork(Color.GREEN); input.set(bitmap);
            return new AbstractList<Bitmap>() {
                @Override public int size() { return 1; }
                @Override public Bitmap get(int index) { if (index != 0) throw new IndexOutOfBoundsException(); return bitmap; }
                @Override public Iterator<Bitmap> iterator() { throw new AssertionError("Unbounded loader iteration"); }
            };
        })) {
            f.image(CensorAppearance.Type.CUSTOM, false, ProtectionScope.normal(f.taps.scope()), 0);
            await(() -> input.get() != null && input.get().isRecycled() && f.view.resourcesMatchCurrent()); f.redraw();
            Bitmap output = f.capture(); try { assertEquals(Color.GREEN, output.getPixel(70, 70)); export(output, "owned-custom-copy"); } finally { output.recycle(); }
            assertEquals(1, f.view.preparationCount()); assertEquals(0, f.view.preparationFailures());
        }
    }

    @Test public void labOldTapScopeAndForeignPublisherCannotBeRelabelledOrCleared() throws Exception {
        try (Fixture f = new Fixture()) {
            f.image(CensorAppearance.Type.BOX, false, ProtectionScope.lab(7), 0); assertFalse(f.hit(70, 70));
            main(() -> { f.h.engine.changeScope(42, ProtectionScope.normal(f.taps.scope())); f.h.image(f.h.now + 334, f.origin); });
            f.redraw(); assertTrue(f.hit(70, 70)); long original = f.taps.scope(); f.taps.clear(); f.redraw();
            assertEquals(original + 1, f.taps.scope()); assertFalse(f.hit(70, 70));
            Object other = new Object(); long at = SystemClock.uptimeMillis();
            f.taps.publishDisplayed(other, f.taps.scope(), Collections.singletonList(new BBox(f.origin[0] + 60, f.origin[1] + 60, 40, 40)),
                    new BBox(0, 0, f.h.width, f.h.height), at, false);
            main(() -> f.activity.surface.removeView(f.view)); assertTrue(f.hit(70, 70));
        }
    }

    @Test public void ownScrollUnknownClipAndAffineSurfaceAreIneligibleButObservedParentScrollMapsCorrectly() throws Exception {
        try (Fixture f = new Fixture()) {
            f.image(CensorAppearance.Type.BOX, false, ProtectionScope.normal(f.taps.scope()), 0); assertTrue(f.hit(70, 70));
            main(() -> f.view.scrollTo(20, 0)); f.redraw(); assertFalse(f.hit(70, 70)); assertFalse(f.hit(95, 70));
            Bitmap invalid = f.capture(); try { assertEquals(Color.BLACK, invalid.getPixel(95, 70)); } finally { invalid.recycle(); }
            main(() -> { f.view.scrollTo(0, 0); f.activity.surface.scrollTo(20, 0); }); f.redraw();
            assertTrue(f.taps.matchesPoint(f.origin[0] + 70, f.origin[1] + 70, f.h.width, f.h.height, SystemClock.uptimeMillis()));
            Bitmap scrolled = f.capture(); try { assertNotEquals(Color.BLACK, scrolled.getPixel(90, 70)); export(scrolled, "observed-parent-scroll"); } finally { scrolled.recycle(); }
            main(() -> f.view.setScaleX(.9f)); f.redraw(); assertFalse(f.hit(90, 70));
            main(() -> { f.view.setScaleX(1); f.configure(CensorAppearance.Type.BOX, false, 1, false); }); f.redraw(); assertFalse(f.hit(90, 70));
            main(() -> { f.configure(CensorAppearance.Type.BOX, false, 2, true); f.activity.getWindow().getDecorView().setClipToOutline(true); });
            f.redraw(); assertFalse(f.hit(90, 70));
        }
    }

    @Test public void changedSourceReplacesPixelsAndStopRetiresSourcesWithoutReplayingOldCoverage() throws Exception {
        try (Fixture f = new Fixture()) {
            f.image(CensorAppearance.Type.PIXELATE, false, ProtectionScope.normal(f.taps.scope()), 0);
            await(f.view::resourcesMatchCurrent); f.redraw(); Bitmap firstSource = f.h.lastImage;
            Bitmap first = f.capture(); try { assertEquals(Color.RED, first.getPixel(70, 70)); } finally { first.recycle(); }
            int prepared = f.view.preparationCount(); main(() -> f.h.image(335, f.origin, Color.BLUE)); f.redraw();
            await(() -> f.view.preparationCount() > prepared && f.view.resourcesMatchCurrent()); f.redraw();
            Bitmap second = f.capture(); try { assertEquals(Color.BLUE, second.getPixel(70, 70)); export(second, "replacement-source-blue"); } finally { second.recycle(); }
            main(() -> f.h.engine.stop()); f.redraw(); assertFalse(f.hit(70, 70));
            assertTrue(firstSource.isRecycled()); assertTrue(f.h.lastImage.isRecycled());
            Bitmap stopped = f.capture(); try { assertEquals(Color.BLACK, stopped.getPixel(70, 70)); export(stopped, "stopped-fresh-owner"); } finally { stopped.recycle(); }
        }
    }

    @Test public void reverseWithdrawalAndStationaryRefreshDoNotRenewSourceScopeOrCapture() throws Exception {
        try (Fixture f = new Fixture()) {
            f.image(CensorAppearance.Type.BOX, false, ProtectionScope.normal(f.taps.scope()), 0);
            int captures = f.h.callbacks.size(); long scope = f.taps.scope(), epoch = f.h.engine.currentEpoch();
            long draws = f.view.drawCount(); await(() -> f.view.drawCount() > draws); assertTrue(f.hit(70, 70));
            assertEquals(captures, f.h.callbacks.size()); assertEquals(scope, f.taps.scope()); assertEquals(epoch, f.h.engine.currentEpoch());
            main(() -> f.configure(CensorAppearance.Type.BOX, true, 1, true)); f.redraw();
            assertFalse(f.hit(70, 70)); assertEquals(scope + 1, f.taps.scope()); // Configuration invalidates lifecycle provenance once.
            long reverseScope = f.taps.scope(); f.redraw(); assertEquals(reverseScope, f.taps.scope());
            assertEquals(captures, f.h.callbacks.size());
        }
    }

    @Test public void disablingAndClosingAnAttachedSurfaceDemandClearPixelsWithoutWaitingForCapture() throws Exception {
        try (Fixture f = new Fixture()) {
            f.image(CensorAppearance.Type.PIXELATE, false, ProtectionScope.normal(f.taps.scope()), 0);
            await(f.view::resourcesMatchCurrent); f.redraw(); assertTrue(f.hit(70, 70));
            int captures = f.h.callbacks.size(), before = f.activity.commits, recordings = f.activity.recordings;
            main(() -> f.view.presentationAllowed(false));
            await(() -> f.activity.commits > before && f.activity.recordings > recordings);
            Bitmap disabled = f.capture();
            try { assertEquals(Color.BLACK, disabled.getPixel(70, 70)); export(disabled, "disabled-surface-cleared"); } finally { disabled.recycle(); }
            assertFalse(f.hit(70, 70)); assertEquals(captures, f.h.callbacks.size());
            main(() -> { f.h.engine.changeScope(42, ProtectionScope.normal(f.taps.scope())); f.h.image(335, f.origin); f.view.presentationAllowed(true); });
            f.redraw(); await(f.view::resourcesMatchCurrent); f.redraw(); assertTrue(f.hit(70, 70));
            int beforeClose = f.activity.commits, recordingsBeforeClose = f.activity.recordings;
            main(f.view::close); await(() -> f.activity.commits > beforeClose && f.activity.recordings > recordingsBeforeClose);
            assertFalse(f.hit(70, 70)); Bitmap closed = f.capture();
            try { assertEquals(Color.BLACK, closed.getPixel(70, 70)); export(closed, "closed-attached-surface-cleared"); } finally { closed.recycle(); }
            await(() -> f.view.managedResourcesDrained() && f.view.preparationTerminated());
        }
    }

    private static Bitmap artwork(int color) { Bitmap bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888); bitmap.eraseColor(color); return bitmap; }
    private static void waitLatch(CountDownLatch latch) { try { if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Controlled loader timeout"); } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); } }
    private static void export(Bitmap bitmap, String label) throws Exception {
        if (!"true".equals(InstrumentationRegistry.getArguments().getString("tap_fixture_capture"))) return;
        File directory = new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getExternalFilesDir(null), "censor-tap-fixtures-20261011");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        try (FileOutputStream stream = new FileOutputStream(new File(directory, "native-mask-" + System.nanoTime() + ".png"))) {
            assertTrue(label, bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream));
        }
    }
    private static final class Fixture implements AutoCloseable {
        final ActivityScenario<SnapshotViewFixtureActivity> scenario = ActivityScenario.launch(SnapshotViewFixtureActivity.class);
        final CensorTapTracker taps = new CensorTapTracker();
        final List<CensorEngine.PaintReceipt> receipts = Collections.synchronizedList(new ArrayList<>());
        final int[] origin = new int[2]; SnapshotViewFixtureActivity activity; SnapshotCensorView view; Harness h;
        Fixture() throws Exception { this((policy, revision, obsolete) -> Collections.emptyList()); }
        Fixture(SnapshotCensorView.ArtworkLoader loader) throws Exception {
            scenario.onActivity(value -> {
                activity = value; android.util.DisplayMetrics metrics = new android.util.DisplayMetrics(); value.getWindowManager().getDefaultDisplay().getRealMetrics(metrics);
                h = new Harness(metrics.widthPixels, metrics.heightPixels);
                view = new SnapshotCensorView(value, new SnapshotCensorView.Owner() {
                    @Override public CensorEngine.ActiveScope activeScope() { return h.engine.activeScope(); }
                    @Override public boolean isCurrent(CensorEngine.ActiveScope scope) { return h.engine.isCurrent(scope); }
                    @Override public CensorEngine<PixelFrame>.RenderView acquireRenderView() { return h.engine.acquireRenderView(); }
                    @Override public CensorEngine.PaintReceipt acknowledgePaint(CensorEngine.ActiveScope scope, long revision, long generation, List<Long> ids, boolean reverse) {
                        return h.engine.acknowledgePaint(scope, revision, generation, ids, reverse);
                    }
                }, loader, taps, new SnapshotCensorView.Observer() {
                    @Override public void recorded(CensorEngine.PaintReceipt receipt) { receipts.add(receipt); }
                    @Override public void drawDemand() { value.demand(); }
                }); value.install(view);
            });
            await(() -> view.getWidth() == 320 && view.isAttachedToWindow()); main(() -> view.getLocationOnScreen(origin));
        }
        void configure(CensorAppearance.Type type, boolean reverse, long revision, boolean rectangular) {
            view.configure(appearance(type, reverse), SnapshotPaintPolicy.from(DetectionPreset.LOW), revision, rectangular); view.presentationAllowed(true);
        }
        void image(CensorAppearance.Type type, boolean reverse, ProtectionScope scope, long revision) throws Exception {
            main(() -> { h.engine.start(42, scope); h.image(1, origin); configure(type, reverse, revision, true); }); redraw();
        }
        void redraw() throws Exception {
            int before = activity.commits, records = activity.recordings; main(activity::demand);
            await(() -> activity.commits > before && activity.recordings > records);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertTrue(diagnostic(), view.drawCount() > 0);
        }
        String diagnostic() {
            AtomicReference<String> result = new AtomicReference<>();
            main(() -> {
                StringBuilder value = new StringBuilder("draws=").append(view.drawCount()).append(" recordings=").append(activity.recordings)
                        .append(" attached=").append(view.isAttachedToWindow()).append(" shown=").append(view.isShown())
                        .append(" window=").append(view.getWindowVisibility()).append(" hardware=").append(view.isHardwareAccelerated())
                        .append(" canvasHardware=").append(activity.hardware)
                        .append(" scope=").append(h.engine.activeScope() != null).append(" visible=").append(view.getLocalVisibleRect(new Rect()));
                View node = view;
                while (true) {
                    value.append(" chain[").append(node.getClass().getSimpleName()).append(" matrix=").append(node.getMatrix().isIdentity())
                            .append(" alpha=").append(node.getAlpha()).append(" outline=").append(node.getClipToOutline()).append(']');
                    if (!(node.getParent() instanceof View)) break; node = (View) node.getParent();
                }
                try (CensorEngine<PixelFrame>.RenderView current = h.engine.acquireRenderView()) {
                    value.append(" render=").append(current != null); if (current != null) value.append(" groups=").append(current.snapshot.groups.size());
                }
                result.set(value.toString());
            }); return result.get();
        }
        boolean hit(int x, int y) { return taps.matchesPoint(origin[0] + x, origin[1] + y, h.width, h.height, SystemClock.uptimeMillis()); }
        Bitmap capture() throws Exception {
            Bitmap bitmap = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888); CountDownLatch copied = new CountDownLatch(1); AtomicInteger result = new AtomicInteger(-1);
            main(() -> { int[] window = new int[2]; view.getLocationInWindow(window);
                PixelCopy.request(activity.getWindow(), new Rect(window[0], window[1], window[0] + 320, window[1] + 320), bitmap,
                        status -> { result.set(status); copied.countDown(); }, new Handler(Looper.getMainLooper())); });
            assertTrue(copied.await(5, TimeUnit.SECONDS)); assertEquals(PixelCopy.SUCCESS, result.get()); return bitmap;
        }
        @Override public void close() throws Exception {
            main(() -> { view.close(); h.close(); }); await(() -> view.managedResourcesDrained() && view.preparationTerminated()); scenario.close();
        }
    }
    private static final class Queue implements Executor {
        final List<Runnable> actions = new ArrayList<>();
        @Override public void execute(Runnable action) { actions.add(action); }
        void all() { while (!actions.isEmpty()) actions.remove(0).run(); }
    }
    private static final class Harness implements AutoCloseable {
        volatile long now; final int width, height;
        final Queue worker = new Queue(), ui = new Queue(); final List<Runnable> timers = new ArrayList<>();
        final List<CensorEngine.CaptureCallback<PixelFrame>> callbacks = new ArrayList<>();
        final CensorEngine<PixelFrame> engine; SceneEngine.Region[] regions; Bitmap lastImage;
        Harness(int width, int height) {
            this.width = width; this.height = height;
            engine = new CensorEngine<>(new CensorEngine.Policy(334, 0, 8, 3, false),
                    new SceneEngine.Policy(16, 100, 64, 256, 5000, 20000, .5), () -> now,
                    (action, delay) -> { timers.add(action); return () -> timers.remove(action); },
                    (request, callback) -> { assertTrue(request.tryDispatch()); callbacks.add(callback); },
                    Collections.singletonList(new CensorEngine.LaneWorker<>(SceneEngine.Lane.FAST, worker,
                            (input, completion) -> { List<SceneEngine.Observation> observations = new ArrayList<>();
                                for (SceneEngine.Region region : regions) observations.add(new SceneEngine.Observation("FACE", 1, 0, region, .9f));
                                completion.success(new SceneEngine.Batch(input.source, SceneEngine.Lane.FAST, observations, Collections.emptyList()),
                                        new CensorEngine.WorkTiming(1, 2, 1)); })), ui, new CensorEngine.Observer() { });
        }
        void image(long at, int[] xy) {
            image(at, xy, Color.RED);
        }
        void image(long at, int[] xy, int color) {
            now = at; regions = new SceneEngine.Region[]{new SceneEngine.Region(xy[0] + 60, xy[1] + 60, xy[0] + 100, xy[1] + 100),
                    new SceneEngine.Region(xy[0] + 200, xy[1] + 60, xy[0] + 240, xy[1] + 100)};
            timers.remove(0).run(); Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888); bitmap.eraseColor(color); lastImage = bitmap;
            PixelFrame frame = new PixelFrame(bitmap, PixelGeometry.display(width, height, width, height), PixelFrame.Kind.ACCESSIBILITY_DISPLAY, -1, at, 0);
            callbacks.get(callbacks.size() - 1).success(new CensorEngine.Captured<>(frame, width, height, -1, bitmap::recycle)); worker.all(); ui.all();
        }
        @Override public void close() { engine.close(); for (CensorEngine.CaptureCallback<PixelFrame> callback : callbacks) callback.failure(0); worker.all(); ui.all(); }
    }
}
