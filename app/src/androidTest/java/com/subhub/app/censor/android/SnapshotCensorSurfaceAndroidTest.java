package com.subhub.app.censor.android;

import static org.junit.Assert.*;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.RenderNode;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.PixelCopy;
import android.view.SurfaceHolder;
import android.view.View;
import android.widget.FrameLayout;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;

import com.subhub.app.censor.engine.CensorEngine;
import com.subhub.app.censor.engine.LatestResourceCoordinator;
import com.subhub.app.censor.engine.PixelGeometry;
import com.subhub.app.censor.engine.ProtectionScope;
import com.subhub.app.censor.engine.SceneEngine;
import com.subhub.app.detection.DetectionPreset;
import com.subhub.app.penance.CensorTapTracker;
import com.subhub.app.settings.CensorAppearance;
import com.subhub.app.settings.EffectPalette;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import org.json.JSONArray;
import org.json.JSONObject;

/** Actual Surface buffers AND composed synthetic screen pixels, not a Pixel performance claim. */
@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion = 30)
public class SnapshotCensorSurfaceAndroidTest {
    private static void main(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) action.run();
        else InstrumentationRegistry.getInstrumentation().runOnMainSync(action);
    }
    private static void await(BooleanSupplier condition) throws Exception {
        long end = SystemClock.uptimeMillis() + 5000;
        while (true) {
            if (condition.getAsBoolean()) return;
            assertTrue("Bounded actual-holder condition", SystemClock.uptimeMillis() < end);
            Thread.sleep(10);
        }
    }
    private static CensorAppearance appearance(CensorAppearance.Type type, boolean reverse) {
        return new CensorAppearance(type, 70, 0, false, false, CensorAppearance.BorderEffect.CLASSIC,
                false, Color.WHITE, new EffectPalette(Color.GREEN, Color.BLUE, Color.WHITE),
                Collections.singletonList("BLOCKED"), reverse, 100, "rectangle", "SubHub", "Access blocked.");
    }

    @Test public void freshEndpointsRepeatedlyAdmitLiteralImageBeforeAnyAdverseControl() throws Exception {
        List<String> failures = new ArrayList<>();
        for (int attempt = 0; attempt < 12; attempt++) try (Fixture f = new Fixture()) {
            try { f.image(CensorAppearance.Type.BOX, false); assertTrue(f.hit(70, 70)); }
            catch (AssertionError failure) {
                failures.add("attempt=" + attempt + " " + failure.getMessage());
                Bitmap buffer = f.buffer();
                try { export(buffer, "baseline-failure-" + attempt); } finally { buffer.recycle(); }
            }
        }
        assertTrue("Literal baseline failures, without any armed fault: " + failures, failures.isEmpty());
    }

    @Test public void boundedWaitReturnsAtItsObservedSuccessWithoutResamplingTheCondition() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        // The former helper read a third time after its loop saw true, then failed spuriously.
        // This condition is deliberately true once: waiting observes a point, not future stability.
        await(() -> reads.incrementAndGet() == 2);
        assertEquals(2, reads.get());
    }

    @Test public void actualHolderPixelsComposeAtObservedOriginWithTransparentGapsAndPostClocks() throws Exception {
        try (Fixture f = new Fixture()) {
            f.image(CensorAppearance.Type.PIXELATE, false); f.prepared();
            Bitmap buffer = f.buffer();
            try {
                export(buffer, "source-red-buffer"); assertEquals(Color.RED, buffer.getPixel(70, 70));
                assertEquals(0, Color.alpha(buffer.getPixel(150, 70)));
                assertEquals("Pixelate is rectangular; its conservative corner remains unbillable", Color.RED, buffer.getPixel(60, 60));
            } finally { buffer.recycle(); }
            f.composed("source-red-composed", 70, 70, Color.RED); f.composedChecker("gap-composed", 150, 70);
            assertTrue(f.hit(70, 70)); assertFalse(f.hit(150, 70)); assertFalse(f.hit(60, 60));
            SnapshotCensorSurface.Post post = f.lastAccepted();
            assertEquals(2, post.acceptedPaint.members.size()); assertEquals(-1, post.compositorAtMillis);
            assertEquals(-1, post.acceptedPaint.compositorAtMillis); assertEquals(-1, post.acceptedPaint.canvas.pixelAtMillis);
            assertTrue(post.recordedAtMillis <= post.postReturnedAtMillis);
            assertTrue(post.postReturnedAtMillis <= post.uiAdmittedAtMillis); assertFalse(post.mainThread);
            assertTrue(post.renderThreadId > 0 && post.queuedNanos >= 0);
            assertTrue(post.lockNanos >= 0 && post.recordingNanos >= 0 && post.postNanos >= 0);
            assertEquals(ProtectionScope.normal(f.taps.scope()), post.acceptedPaint.members.get(0).evidenceSource.protectionScope);
            assertEquals(1, f.h.callbacks.size());
        }
    }

    @Test public void frameworkExportsHaveNoCompanionMaskAndRemovingSurfaceExposesNoTwin() throws Exception {
        try (Fixture f = new Fixture()) {
            f.image(CensorAppearance.Type.BOX, false); f.composed("box-before-removal", 70, 70, Color.GREEN);
            main(() -> {
                long scope = f.taps.scope(); int receipts = f.posts.size(), preparation = f.renderer().preparationCount();
                assertEquals(1, f.surface.getChildCount()); assertSame(f.surface.endpointView(), f.surface.getChildAt(0));
                Bitmap bitmap = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888);
                try { f.surface.draw(new Canvas(bitmap)); assertEquals(0, Color.alpha(bitmap.getPixel(70, 70))); }
                finally { bitmap.recycle(); }
                RenderNode node = new RenderNode("foreign-export"); node.setPosition(0, 0, 320, 320);
                try { Canvas canvas = node.beginRecording(); f.surface.draw(canvas); node.endRecording(); }
                finally { node.discardDisplayList(); }
                assertEquals(receipts, f.posts.size()); assertEquals(scope, f.taps.scope());
                assertEquals(preparation, f.renderer().preparationCount()); assertTrue(f.hit(70, 70));
                f.surface.endpointView().setVisibility(View.INVISIBLE); f.surface.invalidate(); f.activity.root.invalidate();
            });
            f.composedChecker("endpoint-removed-no-framework-twin", 70, 70); assertFalse(f.hit(70, 70));
            long locks = f.surface.lockCount(); main(() -> { for (int i = 0; i < 100; i++) f.surface.requestFrame(); });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync(); assertEquals(locks, f.surface.lockCount());
        }
    }

    @Test public void newSourceAndStationaryExpiryFullyReplaceBuffersWithoutRenewingEvidence() throws Exception {
        try (Fixture f = new Fixture()) {
            f.image(CensorAppearance.Type.PIXELATE, false); f.prepared(); Bitmap original = f.h.lastImage;
            main(() -> { f.h.image(335, f.origin, Color.BLUE); f.surface.presentationChanged(); }); f.prepared();
            f.composed("new-source-blue", 70, 70, Color.BLUE); assertTrue(original.isRecycled());
            long deadline;
            try (CensorEngine<PixelFrame>.RenderView view = f.h.engine.acquireRenderView()) { deadline = view.snapshot.nextChangeAtMillis; }
            assertNotEquals(Long.MAX_VALUE, deadline); int captures = f.h.callbacks.size();
            // The first deadline can belong to unresolved A. Expire the actual visible B evidence.
            main(() -> { f.h.now = 335 + Harness.TRANSIENT_MILLIS + 1;
                try (CensorEngine<PixelFrame>.RenderView view = f.h.engine.acquireRenderView()) { assertNotNull(view); assertTrue(view.snapshot.groups.isEmpty()); }
                f.surface.presentationChanged(); }); f.redraw();
            Bitmap expired = f.buffer();
            try { export(expired, "expired-transparent-buffer"); assertEquals(0, Color.alpha(expired.getPixel(70, 70))); }
            finally { expired.recycle(); }
            f.composedChecker("expired-composed", 70, 70); assertFalse(f.hit(70, 70)); assertEquals(captures, f.h.callbacks.size());
        }
    }

    @Test public void coldReverseAndReverseHolesComposeWithoutInventedSourcesOrBillableHoles() throws Exception {
        try (Fixture f = new Fixture()) {
            main(() -> { f.h.engine.start(42, ProtectionScope.normal(f.taps.scope())); f.configure(CensorAppearance.Type.BOX, true, 0); });
            f.redraw(); await(() -> f.acceptedCount() > 0); SnapshotCensorSurface.Post cold = f.lastAccepted();
            assertNull(cold.acceptedPaint.canvas); assertEquals(-1, cold.acceptedPaint.revision);
            assertTrue(cold.acceptedPaint.reverseBackground); assertTrue(f.h.callbacks.isEmpty()); assertFalse(f.hit(70, 70));
            Bitmap background = f.buffer();
            try { export(background, "cold-reverse-buffer"); assertTrue(Color.alpha(background.getPixel(70, 70)) > 0); }
            finally { background.recycle(); }
            main(() -> { f.h.image(1, f.origin, Color.RED); f.surface.presentationChanged(); }); f.redraw();
            Bitmap holes = f.buffer();
            try { export(holes, "reverse-holes-buffer"); assertEquals(0, Color.alpha(holes.getPixel(70, 70))); assertTrue(Color.alpha(holes.getPixel(150, 70)) > 0); }
            finally { holes.recycle(); }
            f.composedChecker("reverse-hole-composed", 70, 70); assertFalse(f.hit(70, 70));
            main(() -> f.configure(CensorAppearance.Type.BOX, false, 1)); f.redraw();
            f.composedChecker("reverse-to-normal-gap", 150, 70);
        }
    }

    @Test public void disableAndAttachedCloseWithdrawActualPixelsWithoutAnotherCapture() throws Exception {
        try (Fixture f = new Fixture()) {
            f.image(CensorAppearance.Type.PIXELATE, false); f.prepared(); assertTrue(f.hit(70, 70)); int captures = f.h.callbacks.size();
            long before = f.surface.postCount(); main(() -> f.surface.presentationAllowed(false)); await(() -> f.surface.postCount() > before);
            Bitmap disabled = f.buffer();
            try { export(disabled, "disabled-transparent-buffer"); assertEquals(0, Color.alpha(disabled.getPixel(70, 70))); }
            finally { disabled.recycle(); }
            f.composedChecker("disabled-composed", 70, 70); assertFalse(f.hit(70, 70)); assertEquals(captures, f.h.callbacks.size());
            main(() -> { f.h.engine.changeScope(42, ProtectionScope.normal(f.taps.scope())); f.h.image(335, f.origin, Color.BLUE); f.surface.presentationAllowed(true); });
            f.prepared(); f.composed("enabled-blue", 70, 70, Color.BLUE); assertTrue(f.hit(70, 70));
            main(f.surface::close); f.composedChecker("attached-close-composed", 70, 70); assertFalse(f.hit(70, 70));
            await(() -> f.surface.managedResourcesDrained() && f.surface.preparationTerminated());
        }
    }

    @Test public void fixedBufferMismatchAndAffineEndpointCannotScalePixelsOrAuthorizeTaps() throws Exception {
        try (Fixture f = new Fixture()) {
            f.image(CensorAppearance.Type.PIXELATE, false); f.prepared();
            long before = f.surface.postCount(); main(() -> f.surface.endpointView().getHolder().setFixedSize(300, 300));
            await(() -> f.surface.postCount() > before && f.surface.endpointView().getHolder().getSurfaceFrame().width() == 300);
            Bitmap mismatch = f.buffer();
            try { export(mismatch, "fixed-mismatch-transparent"); assertEquals(300, mismatch.getWidth()); assertEquals(0, Color.alpha(mismatch.getPixel(70, 70))); }
            finally { mismatch.recycle(); }
            f.composedChecker("mismatch-composed", 70, 70); assertFalse(f.hit(70, 70));
            main(() -> f.surface.endpointView().getHolder().setSizeFromLayout()); f.redraw();
            f.composed("size-restored-red", 70, 70, Color.RED);
            main(() -> f.surface.endpointView().setScaleX(.9f)); f.redraw();
            f.composedChecker("affine-endpoint-empty", 70, 70); assertFalse(f.hit(70, 70));
        }
    }

    @Test public void parentScrollResizeHiddenAndRecreatedEndpointUseObservedGeometry() throws Exception {
        try (Fixture f = new Fixture()) {
            f.image(CensorAppearance.Type.PIXELATE, false); f.prepared(); int x = f.origin[0], y = f.origin[1];
            main(() -> { f.activity.root.scrollTo(20, 0); f.surface.requestFrame(); }); f.redraw();
            Bitmap scrolled = f.buffer();
            try { export(scrolled, "parent-scroll-buffer"); assertEquals(Color.RED, scrolled.getPixel(90, 70)); }
            finally { scrolled.recycle(); }
            assertTrue(f.taps.matchesPoint(x + 70, y + 70, f.h.width, f.h.height, SystemClock.uptimeMillis()));
            main(() -> { FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) f.surface.getLayoutParams(); p.width = 288; p.height = 304; f.surface.setLayoutParams(p); });
            await(() -> f.surface.getWidth() == 288 && f.surface.endpointView().getHolder().getSurfaceFrame().width() == 288); f.redraw();
            f.composed("resized-composed", 90, 70, Color.RED);
            long locks = f.surface.lockCount(); main(() -> f.surface.setVisibility(View.INVISIBLE));
            f.composedChecker("hidden-composed", 90, 70); assertFalse(f.hit(90, 70));
            main(() -> { for (int i = 0; i < 100; i++) f.surface.requestFrame(); }); assertEquals(locks, f.surface.lockCount());
            main(() -> f.surface.setVisibility(View.VISIBLE)); f.redraw(); f.prepared();
            f.composed("recreated-red-composed", 90, 70, Color.RED); assertTrue(f.surface.lockCount() > locks);
        }
    }

    @Test public void nullLockAndUncertainPostRemoveEndpointWithoutRetryOrUnpostedAttribution() throws Exception {
        AtomicInteger behavior = new AtomicInteger(); AtomicInteger attempts = new AtomicInteger();
        SnapshotCensorSurface.Endpoint endpoint = new SnapshotCensorSurface.Endpoint() {
            @Override public Canvas lock(SurfaceHolder holder) { return behavior.get() == 1 ? null : holder.lockHardwareCanvas(); }
            @Override public void post(SurfaceHolder holder, Canvas canvas) {
                attempts.incrementAndGet(); holder.unlockCanvasAndPost(canvas);
                if (behavior.get() == 2) throw new IllegalStateException("controlled uncertain successful post");
            }
        };
        try (Fixture f = new Fixture((policy, revision, obsolete) -> Collections.emptyList(), endpoint)) {
            f.image(CensorAppearance.Type.BOX, false); assertTrue(f.hit(70, 70)); int receipts = f.posts.size();
            main(() -> { behavior.set(2); f.surface.requestFrame(); }); await(f.surface::endpointFailed);
            f.composedChecker("uncertain-post-endpoint-removed", 70, 70); assertEquals(receipts, f.posts.size()); assertFalse(f.hit(70, 70));
            int attempted = attempts.get(); long locks = f.surface.lockCount();
            main(() -> { for (int i = 0; i < 100; i++) f.surface.requestFrame(); });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync(); assertEquals(attempted, attempts.get()); assertEquals(locks, f.surface.lockCount());
        }
        behavior.set(1);
        try (Fixture f = new Fixture((policy, revision, obsolete) -> Collections.emptyList(), endpoint)) {
            await(f.surface::endpointFailed); long locks = f.surface.lockCount();
            main(() -> { for (int i = 0; i < 100; i++) f.surface.requestFrame(); });
            assertEquals(locks, f.surface.lockCount()); assertTrue(f.posts.isEmpty()); assertEquals(1, f.failures.get());
        }
    }

    @Test public void postReentrantScopeChangeRejectsOldFrameAndForeignMapIsNotCleared() throws Exception {
        AtomicReference<Runnable> duringPost = new AtomicReference<>();
        SnapshotCensorSurface.Endpoint endpoint = new SnapshotCensorSurface.Endpoint() {
            @Override public Canvas lock(SurfaceHolder holder) { return holder.lockHardwareCanvas(); }
            @Override public void post(SurfaceHolder holder, Canvas canvas) {
                holder.unlockCanvasAndPost(canvas); Runnable action = duringPost.getAndSet(null);
                if (action != null) assertTrue(new Handler(Looper.getMainLooper()).post(action));
            }
        };
        try (Fixture f = new Fixture((policy, revision, obsolete) -> Collections.emptyList(), endpoint)) {
            f.image(CensorAppearance.Type.BOX, false); AtomicInteger accepted = new AtomicInteger(-1);
            main(() -> { duringPost.set(() -> { accepted.set(f.acceptedCount()); f.h.engine.stop(); f.surface.presentationChanged(); }); f.surface.requestFrame(); }); f.redraw();
            assertTrue(accepted.get() >= 0); assertEquals(accepted.get(), f.acceptedCount()); assertFalse(f.hit(70, 70));
            f.composedChecker("stale-post-followed-by-empty", 70, 70);
            Object other = new Object(); long at = SystemClock.uptimeMillis();
            f.taps.publishDisplayed(other, f.taps.scope(), Collections.singletonList(new com.subhub.app.detection.BBox(f.origin[0] + 60, f.origin[1] + 60, 40, 40)),
                    new com.subhub.app.detection.BBox(0, 0, f.h.width, f.h.height), at, false);
            main(f.surface::close); assertTrue(f.hit(70, 70));
        }
    }

    @Test public void blockedPreparationAndSourceReadSurviveEndpointCloseUntilActualWorkerReturn() throws Exception {
        CountDownLatch began = new CountDownLatch(1), finish = new CountDownLatch(1); AtomicReference<Bitmap> input = new AtomicReference<>();
        try (Fixture f = new Fixture((policy, revision, obsolete) -> {
            assertNotEquals(Looper.getMainLooper(), Looper.myLooper()); Bitmap bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(Color.MAGENTA); input.set(bitmap); began.countDown();
            try { if (!finish.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("controlled preparation deadline"); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
            return Collections.singletonList(bitmap);
        }, null)) {
            try {
                f.image(CensorAppearance.Type.CUSTOM, false); assertTrue(began.await(5, TimeUnit.SECONDS));
                main(() -> { f.surface.close(); f.h.engine.close(); });
                assertFalse(f.surface.managedResourcesDrained()); assertFalse(f.surface.preparationTerminated());
                assertFalse(f.h.lastImage.isRecycled()); assertFalse(f.hit(70, 70)); f.composedChecker("blocked-close-composed", 70, 70);
                finish.countDown(); await(() -> f.surface.managedResourcesDrained() && f.surface.preparationTerminated());
                assertTrue(input.get().isRecycled()); assertTrue(f.h.lastImage.isRecycled()); assertEquals(0, f.renderer().preparedOutputCount());
            } finally { finish.countDown(); }
        }
    }

    @Test public void postedObserverCanReentrantlyCloseIncludingTheFinalClearWithoutRecursion() throws Exception {
        try (Fixture f = new Fixture()) {
            f.image(CensorAppearance.Type.BOX, false); AtomicInteger callbacks = new AtomicInteger(); long before = f.surface.postCount();
            main(() -> { f.postHook.set(() -> { callbacks.incrementAndGet(); f.surface.close(); }); f.surface.requestFrame(); });
            await(() -> f.surface.managedResourcesDrained() && f.surface.preparationTerminated());
            assertTrue(callbacks.get() >= 1 && callbacks.get() <= 2); assertTrue(f.surface.postCount() <= before + 2);
            assertFalse(f.hit(70, 70)); f.composedChecker("reentrant-close-composed", 70, 70);
        }
    }

    @Test public void firstSameScopeStaleBoxPostAutomaticallyReplacesPixelsWithoutExternalDemand() throws Exception {
        AtomicReference<Runnable> duringPost = new AtomicReference<>();
        SnapshotCensorSurface.Endpoint endpoint = new SnapshotCensorSurface.Endpoint() {
            @Override public Canvas lock(SurfaceHolder holder) { return holder.lockHardwareCanvas(); }
            @Override public void post(SurfaceHolder holder, Canvas canvas) { holder.unlockCanvasAndPost(canvas); Runnable action = duringPost.getAndSet(null); if (action != null) assertTrue(new Handler(Looper.getMainLooper()).post(action)); }
        };
        try (Fixture f = new Fixture((policy, revision, obsolete) -> Collections.emptyList(), endpoint)) {
            AtomicInteger accepted = new AtomicInteger(-1); AtomicReference<CensorEngine.ActiveScope> scope = new AtomicReference<>();
            main(() -> {
                f.h.engine.start(42, ProtectionScope.normal(f.taps.scope())); f.h.image(1, f.origin, Color.RED); f.configure(CensorAppearance.Type.BOX, false, 0);
                scope.set(f.h.engine.activeScope()); duringPost.set(() -> {
                    accepted.set(f.acceptedCount()); f.h.now = 1 + Harness.TRANSIENT_MILLIS + 1;
                    try (CensorEngine<PixelFrame>.RenderView view = f.h.engine.acquireRenderView()) { assertTrue(view.snapshot.groups.isEmpty()); }
                    // Deliberately no presentationChanged/requestFrame and no previous accepted refresh.
                });
            });
            await(() -> accepted.get() >= 0); f.composedChecker("same-scope-stale-first-post-cleared", 70, 70);
            assertTrue(scope.get().same(f.h.engine.activeScope())); assertEquals(accepted.get(), f.acceptedCount()); assertFalse(f.hit(70, 70));
        }
    }

    @Test public void geometryChangeDuringPostAutomaticallyClearsTheAlreadyQueuedBuffer() throws Exception {
        AtomicReference<Runnable> duringPost = new AtomicReference<>();
        SnapshotCensorSurface.Endpoint endpoint = new SnapshotCensorSurface.Endpoint() {
            @Override public Canvas lock(SurfaceHolder holder) { return holder.lockHardwareCanvas(); }
            @Override public void post(SurfaceHolder holder, Canvas canvas) { holder.unlockCanvasAndPost(canvas); Runnable action = duringPost.getAndSet(null); if (action != null) assertTrue(new Handler(Looper.getMainLooper()).post(action)); }
        };
        try (Fixture f = new Fixture((policy, revision, obsolete) -> Collections.emptyList(), endpoint)) {
            f.image(CensorAppearance.Type.BOX, false); AtomicInteger changed = new AtomicInteger();
            main(() -> { duringPost.set(() -> { f.surface.endpointView().setScaleX(.9f); changed.incrementAndGet(); }); f.surface.requestFrame(); });
            await(() -> changed.get() == 1); f.composedChecker("post-time-affine-automatically-cleared", 70, 70); assertFalse(f.hit(70, 70));
        }
    }

    @Test public void recordedObserverCannotInstallTapsAfterMutatingTheSeparateEndpoint() throws Exception {
        for (int variant = 0; variant < 3; variant++) try (Fixture f = new Fixture()) {
            f.image(CensorAppearance.Type.BOX, false); AtomicInteger changed = new AtomicInteger();
            int target = variant; AtomicReference<Boolean> staleHit = new AtomicReference<>();
            main(() -> {
                f.postHook.set(() -> { if (changed.get() == 1) staleHit.compareAndSet(null, f.taps.matchesPoint(
                        f.origin[0] + 70, f.origin[1] + 70, f.h.width, f.h.height, SystemClock.uptimeMillis())); });
                f.recordHook.set(() -> {
                    if (target == 0) f.surface.endpointView().setScaleX(.9f);
                    else if (target == 1) f.surface.setTranslationX(20);
                    else f.surface.setScaleX(.9f);
                    changed.incrementAndGet();
                }); f.surface.requestFrame();
            });
            await(() -> changed.get() == 1 && staleHit.get() != null);
            assertFalse("Old fixed physical point must be unbillable before post observer", staleHit.get());
            f.composedChecker("recorded-observer-endpoint-cleared-" + target, 70, 70); assertFalse(f.hit(70, 70));
        }
    }

    @Test public void latchedActualPostLeavesMainAndCaptureResponsiveAndHoldsRealReadsThroughReturn() throws Exception {
        BlockingEndpoint endpoint = new BlockingEndpoint(false);
        try (Fixture f = new Fixture((policy, revision, obsolete) -> Collections.emptyList(), endpoint)) {
            try {
                f.image(CensorAppearance.Type.PIXELATE, false); f.prepared(); Bitmap original = f.h.lastImage;
                endpoint.block.set(true); main(f.surface::requestObservedFrame); assertTrue(endpoint.entered.await(5, TimeUnit.SECONDS));
                assertEquals(1, f.surface.surfaceUseCount()); assertEquals(1, f.surface.executingRenderCount());
                assertFalse(f.surface.managedResourcesDrained()); assertFalse(f.hit(70, 70));
                AtomicInteger heartbeat = new AtomicInteger();
                main(() -> {
                    for (int i = 0; i < 100; i++) heartbeat.incrementAndGet();
                    f.h.image(335, f.origin, Color.BLUE); f.configure(CensorAppearance.Type.BOX, false, 1);
                    for (int i = 0; i < 1000; i++) f.surface.requestObservedFrame();
                });
                assertEquals(100, heartbeat.get()); assertEquals(2, f.h.callbacks.size());
                assertFalse("Executing draw owns its red source until REAL post return", original.isRecycled());
                assertEquals("Executing draw owns its prepared read despite reconfiguration", 1, f.renderer().preparedOutputCount());
                assertEquals(1, f.surface.pendingPoseCount()); assertTrue(f.surface.replacedPoseCount() >= 999);
                endpoint.release.countDown(); f.redraw(); await(original::isRecycled);
                f.composed("worker-latest-box-after-held-red", 70, 70, Color.GREEN);
                assertEquals(335, f.lastAccepted().acceptedPaint.members.get(0).evidenceSource.receivedAtMillis);
                assertFalse(f.lastAccepted().mainThread);
            } finally { endpoint.release.countDown(); }
        }
    }

    @Test public void destructionQuiescesAReservedWorkerBeforeHolderAcquisitionAndRecreates() throws Exception {
        destructionWithBlockedUse(true);
    }

    @Test public void destructionCannotFinishWhileAnAcquiredWorkerPostStillOwnsSurfaceUse() throws Exception {
        destructionWithBlockedUse(false);
    }

    private static void destructionWithBlockedUse(boolean beforeLock) throws Exception {
        BlockingEndpoint endpoint = new BlockingEndpoint(beforeLock);
        try (Fixture f = new Fixture((policy, revision, obsolete) -> Collections.emptyList(), endpoint)) {
            try {
                f.image(CensorAppearance.Type.PIXELATE, false); f.prepared();
                long started = f.surface.destructionsStarted(), returned = f.surface.destructionsReturned();
                endpoint.block.set(true); main(f.surface::requestObservedFrame); assertTrue(endpoint.entered.await(5, TimeUnit.SECONDS));
                CountDownLatch hideIssued = new CountDownLatch(1);
                assertTrue(new Handler(Looper.getMainLooper()).post(() -> {
                    hideIssued.countDown(); f.surface.endpointView().setVisibility(View.INVISIBLE);
                }));
                assertTrue(hideIssued.await(5, TimeUnit.SECONDS));
                if (beforeLock) await(() -> f.surface.destructionsStarted() > started);
                else Thread.sleep(150); // Platform transaction may wait on the already-acquired holder.
                assertEquals(returned, f.surface.destructionsReturned()); assertEquals(1, f.surface.surfaceUseCount());
                assertFalse(f.surface.managedResourcesDrained());
                endpoint.release.countDown(); await(() -> f.surface.destructionsReturned() > returned);
                assertEquals(0, f.surface.surfaceUseCount()); assertFalse(f.hit(70, 70));
                f.composedChecker("worker-destroyed-" + beforeLock, 70, 70);
                long locks = f.surface.lockCount(); main(() -> { for (int i = 0; i < 100; i++) f.surface.requestObservedFrame(); });
                assertEquals(locks, f.surface.lockCount());
                main(() -> {
                    f.h.engine.changeScope(42, ProtectionScope.normal(f.taps.scope())); f.h.image(335, f.origin, Color.BLUE);
                    f.surface.endpointView().setVisibility(View.VISIBLE);
                }); f.prepared(); f.composed("worker-recreated-blue-" + beforeLock, 70, 70, Color.BLUE);
                assertTrue(f.hit(70, 70));
            } finally { endpoint.release.countDown(); }
        }
    }

    @Test public void newerEmptyWorkerPostsWithdrawOldTapsEvenWhileActualMainDeliveryIsBlocked() throws Exception {
        BlockingEndpoint endpoint = new BlockingEndpoint(false);
        CountDownLatch mainEntered = new CountDownLatch(1), releaseMain = new CountDownLatch(1);
        try (Fixture f = new Fixture((policy, revision, obsolete) -> Collections.emptyList(), endpoint)) {
            try {
                f.image(CensorAppearance.Type.BOX, false); assertTrue(f.hit(70, 70)); int admitted = f.posts.size();
                endpoint.block.set(true);
                main(() -> {
                    f.h.now = 1 + Harness.TRANSIENT_MILLIS + 1;
                    try (CensorEngine<PixelFrame>.RenderView view = f.h.engine.acquireRenderView()) { assertTrue(view.snapshot.groups.isEmpty()); }
                    f.surface.requestObservedFrame();
                }); assertTrue(endpoint.entered.await(5, TimeUnit.SECONDS));
                main(() -> { for (int i = 0; i < 1000; i++) f.surface.requestObservedFrame(); });
                assertEquals(1, f.surface.pendingPoseCount());
                assertTrue(new Handler(Looper.getMainLooper()).post(() -> {
                    mainEntered.countDown();
                    try { if (!releaseMain.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("controlled UI-delivery deadline"); }
                    catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
                })); assertTrue(mainEntered.await(5, TimeUnit.SECONDS));
                long before = f.surface.postCount(); endpoint.release.countDown();
                await(() -> f.surface.postCount() >= before + 2 && f.surface.executingRenderCount() == 0);
                assertEquals(admitted, f.posts.size()); assertEquals(1, f.surface.completionNoticeCount());
                assertTrue(f.surface.replacedCompletionCount() >= 1);
                assertFalse("No UI callback has run; fixed A point must already be unbillable", f.taps.matchesPoint(
                        f.origin[0] + 70, f.origin[1] + 70, f.h.width, f.h.height, SystemClock.uptimeMillis()));
                releaseMain.countDown(); await(() -> f.posts.size() > admitted);
                f.composedChecker("actual-ui-blocked-worker-empty", 70, 70); assertFalse(f.hit(70, 70));
            } finally { endpoint.release.countDown(); releaseMain.countDown(); }
        }
    }

    @Test public void acceptedThenThrowDeliveryStaysCoalescedAndDuplicateLateNoticesCannotRearm() throws Exception {
        HeldDelivery delivery = new HeldDelivery(); BlockingEndpoint endpoint = new BlockingEndpoint(false);
        try (Fixture f = new Fixture((policy, revision, obsolete) -> Collections.emptyList(), endpoint, delivery)) {
            try {
                f.image(CensorAppearance.Type.BOX, false); int admitted = f.posts.size();
                delivery.hold.set(true); delivery.uncertain.set(true); endpoint.block.set(true);
                main(f.surface::requestObservedFrame); assertTrue(endpoint.entered.await(5, TimeUnit.SECONDS));
                main(() -> { for (int i = 0; i < 1000; i++) f.surface.requestObservedFrame(); });
                long before = f.surface.postCount(); endpoint.release.countDown();
                await(() -> f.surface.postCount() >= before + 2 && f.surface.executingRenderCount() == 0 && delivery.size() == 1);
                assertEquals(1, f.surface.completionNoticeCount()); assertEquals(admitted, f.posts.size()); assertFalse(f.hit(70, 70));
                Runnable old = delivery.only(); main(old); await(() -> f.posts.size() > admitted); assertTrue(f.hit(70, 70));
                int rows = f.posts.size(); main(old); assertEquals(rows, f.posts.size());
                Object foreign = new Object(); main(f.surface::close);
                f.taps.publishDisplayed(foreign, f.taps.scope(), Collections.singletonList(new com.subhub.app.detection.BBox(
                        f.origin[0] + 60, f.origin[1] + 60, 40, 40)), new com.subhub.app.detection.BBox(0, 0, f.h.width, f.h.height), SystemClock.uptimeMillis(), false);
                main(old); assertTrue(f.hit(70, 70)); assertEquals(rows, f.posts.size());
            } finally { endpoint.release.countDown(); delivery.hold.set(false); }
        }
    }

    @Test public void definitivelyRejectedCompletionIsPollableWithoutInventingAQueuedNotice() throws Exception {
        HeldDelivery delivery = new HeldDelivery();
        try (Fixture f = new Fixture((policy, revision, obsolete) -> Collections.emptyList(), null, delivery)) {
            f.image(CensorAppearance.Type.BOX, false); int admitted = f.posts.size(); long before = f.surface.postCount();
            delivery.reject.set(true); main(f.surface::requestObservedFrame);
            await(() -> f.surface.postCount() > before && f.surface.executingRenderCount() == 0);
            assertEquals(admitted, f.posts.size()); assertEquals(0, delivery.size()); assertEquals(0, f.surface.completionNoticeCount());
            assertFalse(f.hit(70, 70)); delivery.reject.set(false); main(f.surface::requestFrame);
            await(() -> f.posts.size() > admitted); assertTrue(f.hit(70, 70));
            f.composed("rejected-delivery-recovered-current", 70, 70, Color.GREEN);
        }
    }

    @Test public void ownerErrorAfterActualLockStillPostsOnceAndCannotCounterfeitSurfaceQuiescence() throws Exception {
        try (Fixture f = new Fixture()) {
            f.image(CensorAppearance.Type.BOX, false); int admitted = f.posts.size();
            long attempts = f.surface.postAttempts(), returns = f.surface.postReturns();
            main(() -> { f.failNextOwnerRead.set(true); f.surface.requestObservedFrame(); });
            await(f.surface::endpointFailed); f.composedChecker("owner-error-locked-canvas-removed", 70, 70);
            await(() -> f.surface.executingRenderCount() == 0);
            assertFalse(f.failNextOwnerRead.get()); assertEquals(0, f.surface.surfaceUseCount());
            assertEquals(attempts + 1, f.surface.postAttempts()); assertEquals(returns + 1, f.surface.postReturns());
            assertEquals(admitted, f.posts.size()); assertEquals(1, f.failures.get()); assertFalse(f.hit(70, 70));
        }
    }

    @Test public void postedObserverCannotLeaveOldFixedPointBillableBeforeAnyReplacementCanDraw() throws Exception {
        for (int variant = 0; variant < 3; variant++) {
            BlockingEndpoint endpoint = new BlockingEndpoint(true);
            try (Fixture f = new Fixture((policy, revision, obsolete) -> Collections.emptyList(), endpoint)) {
                try {
                    f.image(CensorAppearance.Type.BOX, false); assertTrue(f.hit(70, 70));
                    AtomicReference<Boolean> staleHit = new AtomicReference<>(); int target = variant;
                    main(() -> {
                        f.postHook.set(() -> {
                            f.postHook.set(null); endpoint.block.set(true);
                            if (target == 0) f.surface.endpointView().setScaleX(.9f);
                            else if (target == 1) f.surface.setTranslationX(20);
                            else f.surface.setScaleX(.9f);
                            assertTrue(new Handler(Looper.getMainLooper()).post(() -> staleHit.set(f.taps.matchesPoint(
                                    f.origin[0] + 70, f.origin[1] + 70, f.h.width, f.h.height, SystemClock.uptimeMillis()))));
                        }); f.surface.requestObservedFrame();
                    });
                    await(() -> staleHit.get() != null);
                    assertFalse("Post callback has returned; replacement is blocked before lock", staleHit.get());
                    assertTrue("Rejected nonempty post must automatically demand replacement", endpoint.entered.await(5, TimeUnit.SECONDS));
                    endpoint.release.countDown(); f.composedChecker("posted-observer-current-empty-" + target, 70, 70);
                    assertFalse(f.hit(70, 70));
                } finally { endpoint.release.countDown(); }
            }
        }
    }

    @Test public void partialActualCanvasClipRemovesUnknownPixelsInsteadOfCertifyingFullClear() throws Exception {
        AtomicBoolean clip = new AtomicBoolean();
        SnapshotCensorSurface.Endpoint endpoint = new SnapshotCensorSurface.Endpoint() {
            @Override public Canvas lock(SurfaceHolder holder) {
                Canvas canvas = holder.lockHardwareCanvas();
                if (canvas != null && clip.get()) canvas.clipRect(0, 0, 40, 40);
                return canvas;
            }
            @Override public void post(SurfaceHolder holder, Canvas canvas) { holder.unlockCanvasAndPost(canvas); }
        };
        try (Fixture f = new Fixture((policy, revision, obsolete) -> Collections.emptyList(), endpoint)) {
            f.image(CensorAppearance.Type.BOX, false); int admitted = f.posts.size(); long attempts = f.surface.postAttempts();
            main(() -> { clip.set(true); f.surface.requestObservedFrame(); }); await(f.surface::endpointFailed);
            f.composedChecker("partial-holder-clip-endpoint-removed", 70, 70);
            assertEquals(attempts + 1, f.surface.postAttempts()); assertEquals(admitted, f.posts.size());
            assertEquals(1, f.failures.get()); assertFalse(f.hit(70, 70));
        }
    }

    @Test public void rejectedRenderSubmissionRemovesEndpointWithoutAcquiringOrPostingAgain() throws Exception {
        AdverseExecutor executor = new AdverseExecutor();
        try (Fixture f = new Fixture((policy, revision, obsolete) -> Collections.emptyList(), null, null, executor)) {
            f.image(CensorAppearance.Type.BOX, false); await(() -> f.surface.executingRenderCount() == 0);
            long locks = f.surface.lockCount(), posts = f.surface.postCount();
            main(() -> { executor.behavior.set(1); f.surface.requestObservedFrame(); }); await(f.surface::endpointFailed);
            f.composedChecker("rejected-render-executor-endpoint-removed", 70, 70);
            assertEquals(locks, f.surface.lockCount()); assertEquals(posts, f.surface.postCount()); assertFalse(f.hit(70, 70));
        } finally { executor.release.countDown(); executor.shutdown(); }
    }

    @Test public void acceptedThenThrowRenderSubmissionLateActionOwnsNoPixelsAndCannotPostAfterClose() throws Exception {
        AdverseExecutor executor = new AdverseExecutor();
        try (Fixture f = new Fixture((policy, revision, obsolete) -> Collections.emptyList(), null, null, executor)) {
            try {
                f.image(CensorAppearance.Type.BOX, false); await(() -> f.surface.executingRenderCount() == 0);
                long locks = f.surface.lockCount(), posts = f.surface.postCount(); Bitmap original = f.h.lastImage;
                main(() -> { executor.behavior.set(2); f.surface.requestObservedFrame(); });
                assertTrue(executor.queued.await(5, TimeUnit.SECONDS)); await(f.surface::endpointFailed);
                f.composedChecker("uncertain-render-executor-endpoint-removed", 70, 70);
                main(() -> { f.surface.close(); f.h.engine.close(); });
                assertTrue("Queued descriptor acquired no source lease", original.isRecycled());
                assertFalse(f.surface.managedResourcesDrained()); assertFalse(executor.isTerminated());
                executor.release.countDown(); await(f.surface::managedResourcesDrained);
                assertEquals(locks, f.surface.lockCount()); assertEquals(posts, f.surface.postCount()); assertFalse(f.hit(70, 70));
            } finally { executor.release.countDown(); }
        } finally { executor.shutdown(); }
    }

    private static final class AdverseExecutor extends AbstractExecutorService {
        final ExecutorService actual = Executors.newSingleThreadExecutor(action -> new Thread(action, "controlled-render-executor"));
        final AtomicInteger behavior = new AtomicInteger();
        final CountDownLatch queued = new CountDownLatch(1), release = new CountDownLatch(1);
        @Override public void execute(Runnable action) {
            int mode = behavior.getAndSet(0);
            if (mode == 1) throw new RejectedExecutionException("controlled render rejection");
            if (mode == 2) {
                actual.execute(() -> {
                    queued.countDown();
                    try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("controlled queued-executor deadline"); }
                    catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
                    action.run();
                });
                throw new IllegalStateException("controlled render accepted-then-throw");
            }
            actual.execute(action);
        }
        @Override public void shutdown() { actual.shutdown(); }
        @Override public List<Runnable> shutdownNow() { return actual.shutdownNow(); }
        @Override public boolean isShutdown() { return actual.isShutdown(); }
        @Override public boolean isTerminated() { return actual.isTerminated(); }
        @Override public boolean awaitTermination(long time, TimeUnit unit) throws InterruptedException { return actual.awaitTermination(time, unit); }
    }

    private static final class BlockingEndpoint implements SnapshotCensorSurface.Endpoint {
        final boolean beforeLock; final AtomicBoolean block = new AtomicBoolean();
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        BlockingEndpoint(boolean beforeLock) { this.beforeLock = beforeLock; }
        private void pause() {
            if (!block.compareAndSet(true, false)) return;
            assertNotEquals(Looper.getMainLooper(), Looper.myLooper()); entered.countDown();
            try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("controlled holder deadline"); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
        }
        @Override public Canvas lock(SurfaceHolder holder) { if (beforeLock) pause(); return holder.lockHardwareCanvas(); }
        @Override public void post(SurfaceHolder holder, Canvas canvas) { if (!beforeLock) pause(); holder.unlockCanvasAndPost(canvas); }
    }
    private static final class HeldDelivery implements LatestResourceCoordinator.Delivery {
        final AtomicBoolean hold = new AtomicBoolean(), uncertain = new AtomicBoolean(), reject = new AtomicBoolean();
        final List<Runnable> actions = new ArrayList<>();
        @Override public boolean post(Runnable action) {
            if (reject.get()) return false;
            if (!hold.get()) return new Handler(Looper.getMainLooper()).post(action);
            synchronized (actions) { actions.add(action); }
            if (uncertain.get()) throw new IllegalStateException("controlled accepted-then-throw completion");
            return true;
        }
        int size() { synchronized (actions) { return actions.size(); } }
        Runnable only() { synchronized (actions) { assertEquals(1, actions.size()); return actions.get(0); } }
    }

    private static void export(Bitmap bitmap, String label) throws Exception {
        if (!"true".equals(InstrumentationRegistry.getArguments().getString("tap_fixture_capture"))) return;
        File directory = new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getExternalFilesDir(null), "censor-tap-fixtures-20261011");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        try (FileOutputStream stream = new FileOutputStream(new File(directory, "native-mask-" + label + "-" + System.nanoTime() + ".png"))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream));
        }
    }
    private static final class Fixture implements AutoCloseable {
        final CensorTapTracker taps = new CensorTapTracker(); final int[] origin = new int[2];
        final List<SnapshotCensorSurface.Post> posts = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger failures = new AtomicInteger(); final ActivityScenario<SnapshotSurfaceFixtureActivity> scenario;
        final AtomicReference<Runnable> recordHook = new AtomicReference<>(), postHook = new AtomicReference<>();
        final AtomicBoolean failNextOwnerRead = new AtomicBoolean();
        final AtomicReference<Thread> lockedCanvasThread = new AtomicReference<>();
        SnapshotSurfaceFixtureActivity activity; SnapshotCensorSurface surface; Harness h;
        Fixture() throws Exception { this((policy, revision, obsolete) -> Collections.emptyList(), null); }
        Fixture(SnapshotCensorView.ArtworkLoader loader, SnapshotCensorSurface.Endpoint endpoint) throws Exception {
            this(loader, endpoint, null);
        }
        Fixture(SnapshotCensorView.ArtworkLoader loader, SnapshotCensorSurface.Endpoint endpoint, LatestResourceCoordinator.Delivery delivery) throws Exception {
            this(loader, endpoint, delivery, null);
        }
        Fixture(SnapshotCensorView.ArtworkLoader loader, SnapshotCensorSurface.Endpoint endpoint,
                LatestResourceCoordinator.Delivery delivery, ExecutorService renderExecutor) throws Exception {
            InstrumentationRegistry.getInstrumentation().getUiAutomation();
            scenario = ActivityScenario.launch(SnapshotSurfaceFixtureActivity.class);
            scenario.onActivity(value -> {
                activity = value; android.util.DisplayMetrics metrics = new android.util.DisplayMetrics(); value.getWindowManager().getDefaultDisplay().getRealMetrics(metrics);
                h = new Harness(metrics.widthPixels, metrics.heightPixels);
                SnapshotCensorView.Owner owner = new SnapshotCensorView.Owner() {
                    @Override public CensorEngine.ActiveScope activeScope() { return h.engine.activeScope(); }
                    @Override public boolean isCurrent(CensorEngine.ActiveScope scope) { return h.engine.isCurrent(scope); }
                    @Override public CensorEngine<PixelFrame>.RenderView acquireRenderView() {
                        if (lockedCanvasThread.get() == Thread.currentThread() && failNextOwnerRead.compareAndSet(true, false)) {
                            assertNotEquals(Looper.getMainLooper(), Looper.myLooper());
                            throw new OutOfMemoryError("controlled owner failure after hardware lock");
                        }
                        return h.engine.acquireRenderView();
                    }
                    @Override public CensorEngine.PaintReceipt acknowledgePaint(CensorEngine.ActiveScope scope, long revision, long generation, List<Long> ids, boolean reverse) { return h.engine.acknowledgePaint(scope, revision, generation, ids, reverse); }
                };
                SnapshotCensorSurface.Observer observer = new SnapshotCensorSurface.Observer() {
                    @Override public void recorded(CensorEngine.PaintReceipt receipt) { Runnable hook = recordHook.getAndSet(null); if (hook != null) hook.run(); }
                    @Override public void posted(SnapshotCensorSurface.Post receipt) { posts.add(receipt); Runnable hook = postHook.get(); if (hook != null) hook.run(); }
                    @Override public void endpointFailure() { failures.incrementAndGet(); }
                };
                SnapshotCensorSurface.Endpoint actual = endpoint == null ? new SnapshotCensorSurface.Endpoint() {
                    @Override public Canvas lock(SurfaceHolder holder) {
                        Canvas canvas = holder.lockHardwareCanvas();
                        if (canvas != null) lockedCanvasThread.set(Thread.currentThread());
                        return canvas;
                    }
                    @Override public void post(SurfaceHolder holder, Canvas canvas) {
                        try { holder.unlockCanvasAndPost(canvas); }
                        finally { lockedCanvasThread.compareAndSet(Thread.currentThread(), null); }
                    }
                } : endpoint;
                surface = new SnapshotCensorSurface(value, owner, loader, taps, observer, actual, delivery, renderExecutor);
                value.install(surface);
            }); await(() -> surface.getWidth() == 320 && surface.isAttachedToWindow());
            CountDownLatch firstWindowFrame = new CountDownLatch(1);
            main(() -> {
                assertTrue(activity.root.isHardwareAccelerated());
                activity.root.getViewTreeObserver().registerFrameCommitCallback(firstWindowFrame::countDown);
                activity.root.invalidate();
            });
            assertTrue("Actual first framework frame before coordinate-bound seeding, not compositor proof", firstWindowFrame.await(5, TimeUnit.SECONDS));
            main(() -> surface.getLocationOnScreen(origin));
        }
        SnapshotRenderSession renderer() { return surface.renderer(); }
        void configure(CensorAppearance.Type type, boolean reverse, long revision) {
            surface.configure(appearance(type, reverse), SnapshotPaintPolicy.from(DetectionPreset.LOW), revision, true); surface.presentationAllowed(true);
        }
        void image(CensorAppearance.Type type, boolean reverse) throws Exception {
            main(() -> { surface.getLocationOnScreen(origin); h.engine.start(42, ProtectionScope.normal(taps.scope())); h.image(1, origin, Color.RED);
                configure(type, reverse, 0); surface.requestObservedFrame(); });
            // Initial holder lifecycle can post an EMPTY older descriptor. UI idleness and any
            // post count are not an accepted baseline. Do not arm faults or sample taps until the
            // meaningful current source is admitted and real worker/pending/delivery work is quiet.
            try { await(() -> {
                AtomicBoolean baseline = new AtomicBoolean();
                // A Choreographer action cannot interleave these UI-owned queue/receipt samples.
                // The worker cannot begin another job without a main-admitted descriptor.
                main(() -> {
                    if (surface.executingRenderCount() != 0 || surface.pendingPoseCount() != 0
                            || surface.completionNoticeCount() != 0 || surface.demandPending()) return;
                    CensorEngine.ActiveScope scope = h.engine.activeScope();
                    synchronized (posts) {
                        if (posts.isEmpty()) return;
                        CensorEngine.PaintReceipt paint = posts.get(posts.size() - 1).acceptedPaint;
                        baseline.set(paint != null && paint.scope.same(scope) && paint.canvas != null
                                && paint.canvas.receivedAtMillis == 1 && !paint.members.isEmpty());
                    }
                });
                return baseline.get();
            }); } catch (AssertionError failure) {
                throw new AssertionError("Current literal-image baseline: " + baselineState(), failure);
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        }
        String baselineState() {
            StringBuilder state = new StringBuilder();
            main(() -> {
                state.append("render=").append(surface.executingRenderCount()).append(" pending=").append(surface.pendingPoseCount())
                        .append(" notice=").append(surface.completionNoticeCount()).append(" demand=").append(surface.demandPending())
                        .append(" failed=").append(surface.endpointFailed()).append(" posts=").append(surface.postCount());
                int[] xy = new int[2]; android.graphics.Rect clip = new android.graphics.Rect();
                surface.endpointView().getLocationOnScreen(xy);
                state.append(" seededOrigin=").append(origin[0]).append(',').append(origin[1])
                        .append(" actualOrigin=").append(xy[0]).append(',').append(xy[1])
                        .append(" frame=").append(surface.endpointView().getHolder().getSurfaceFrame())
                        .append(" localVisible=").append(surface.endpointView().getLocalVisibleRect(clip)).append(':').append(clip);
                View node = surface.endpointView();
                while (true) {
                    state.append(" chain[").append(node.getClass().getSimpleName()).append(" size=").append(node.getWidth()).append('x').append(node.getHeight())
                            .append(" shown=").append(node.isShown()).append(" window=").append(node.getWindowVisibility())
                            .append(" alpha=").append(node.getAlpha()).append(" matrix=").append(node.getMatrix())
                            .append(" outline=").append(node.getClipToOutline()).append(" scroll=").append(node.getScrollX()).append(',').append(node.getScrollY()).append(']');
                    if (!(node.getParent() instanceof View)) break; node = (View) node.getParent();
                }
            });
            CensorEngine.ActiveScope scope = h.engine.activeScope();
            state.append(" scope=").append(scope == null ? "null" : scope.epoch + "/" + scope.geometry).append(" ownerNow=").append(h.now);
            try (CensorEngine<PixelFrame>.RenderView view = h.engine.acquireRenderView()) {
                if (view == null) state.append(" scene=null");
                else state.append(" sceneRevision=").append(view.snapshot.revision).append(" sceneSource=").append(view.snapshot.destination.sequence)
                        .append(" received=").append(view.snapshot.destination.receivedAtMillis).append(" groups=").append(view.snapshot.groups.size());
            }
            synchronized (posts) {
                for (SnapshotCensorSurface.Post post : posts) {
                    CensorEngine.PaintReceipt paint = post.acceptedPaint;
                    state.append(" post[").append(post.postSerial).append('/').append(post.endpointGeneration)
                            .append(" accepted=").append(paint != null);
                    if (paint != null) state.append(" source=").append(paint.canvas == null ? "null" : paint.canvas.sequence + "/" + paint.canvas.receivedAtMillis)
                            .append(" members=").append(paint.members.size());
                    state.append(']');
                }
            }
            return state.toString();
        }
        void redraw() throws Exception { int before = posts.size(); main(surface::requestFrame); await(() -> posts.size() > before); InstrumentationRegistry.getInstrumentation().waitForIdleSync(); }
        void prepared() throws Exception { redraw(); await(renderer()::resourcesMatchCurrent); redraw(); }
        SnapshotCensorSurface.Post lastAccepted() {
            synchronized (posts) { for (int i = posts.size() - 1; i >= 0; i--) if (posts.get(i).acceptedPaint != null) return posts.get(i); }
            throw new AssertionError("No accepted actual post");
        }
        int acceptedCount() { int count = 0; synchronized (posts) { for (SnapshotCensorSurface.Post p : posts) if (p.acceptedPaint != null) count++; } return count; }
        boolean hit(int x, int y) { int[] xy = new int[2]; main(() -> surface.getLocationOnScreen(xy)); return taps.matchesPoint(xy[0] + x, xy[1] + y, h.width, h.height, SystemClock.uptimeMillis()); }
        Bitmap buffer() throws Exception {
            AtomicReference<Bitmap> value = new AtomicReference<>(); AtomicInteger status = new AtomicInteger(-1); CountDownLatch copied = new CountDownLatch(1);
            main(() -> {
                android.graphics.Rect bounds = surface.endpointView().getHolder().getSurfaceFrame(); Bitmap bitmap = Bitmap.createBitmap(bounds.width(), bounds.height(), Bitmap.Config.ARGB_8888); value.set(bitmap);
                PixelCopy.request(surface.endpointView(), bitmap, result -> { status.set(result); copied.countDown(); }, new Handler(Looper.getMainLooper()));
            }); assertTrue(copied.await(5, TimeUnit.SECONDS)); assertEquals(PixelCopy.SUCCESS, status.get()); return value.get();
        }
        void composedChecker(String label, int x, int y) throws Exception {
            int[] xy = new int[2], root = new int[2]; main(() -> { surface.getLocationOnScreen(xy); activity.root.getLocationOnScreen(root); });
            composed(label, x, y, SnapshotSurfaceFixtureActivity.checkerAt(xy[0] + x - root[0] + activity.root.getScrollX(), xy[1] + y - root[1] + activity.root.getScrollY()));
        }
        void composed(String label, int x, int y, int color) throws Exception {
            int[] xy = new int[2]; main(() -> surface.getLocationOnScreen(xy)); long deadline = SystemClock.uptimeMillis() + 5000; Bitmap screenshot;
            while (true) {
                screenshot = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot(); assertNotNull("Actual composed screenshot", screenshot);
                assertEquals(h.width, screenshot.getWidth()); assertEquals(h.height, screenshot.getHeight());
                if (screenshot.getPixel(xy[0] + x, xy[1] + y) == color || SystemClock.uptimeMillis() >= deadline) break;
                screenshot.recycle(); Thread.sleep(10);
            }
            try { export(screenshot, label); assertEquals(label, color, screenshot.getPixel(xy[0] + x, xy[1] + y)); }
            finally { screenshot.recycle(); }
        }
        @Override public void close() throws Exception {
            main(() -> { surface.close(); h.close(); }); await(() -> surface.managedResourcesDrained() && surface.preparationTerminated());
            if ("true".equals(InstrumentationRegistry.getArguments().getString("tap_fixture_capture"))) {
                JSONArray rows = new JSONArray();
                synchronized (posts) { for (SnapshotCensorSurface.Post p : posts) rows.put(new JSONObject()
                        .put("generation", p.endpointGeneration).put("recordedUptimeMillis", p.recordedAtMillis)
                        .put("postReturnedUptimeMillis", p.postReturnedAtMillis).put("lockElapsedNanos", p.lockNanos)
                        .put("recordingElapsedNanos", p.recordingNanos).put("postElapsedNanos", p.postNanos)
                        .put("uiAdmittedUptimeMillis", p.uiAdmittedAtMillis).put("queuedElapsedNanos", p.queuedNanos)
                        .put("renderThreadId", p.renderThreadId).put("postSerial", p.postSerial).put("mainThread", p.mainThread)
                        .put("accepted", p.acceptedPaint != null).put("compositorUptimeMillis", p.compositorAtMillis)); }
                File file = new File(activity.getExternalFilesDir(null), "censor-tap-fixtures-20261011/native-surface-metrics-" + System.nanoTime() + ".json");
                try (FileOutputStream out = new FileOutputStream(file)) { out.write(new JSONObject().put("schema", 2)
                        .put("mainThread", false).put("actualAccessibility", false).put("pixelAcceptance", false)
                        .put("lockAttempts", surface.lockCount()).put("successfulPosts", surface.postCount())
                        .put("postAttempts", surface.postAttempts()).put("postReturns", surface.postReturns())
                        .put("droppedSuccessfulCompletions", surface.droppedSuccessfulCompletionCount())
                        .put("endpointFailures", failures.get()).put("posts", rows).toString().getBytes(StandardCharsets.UTF_8)); }
            }
            scenario.close();
        }
    }
    private static final class Queue implements Executor {
        final List<Runnable> actions = new ArrayList<>();
        @Override public void execute(Runnable action) { actions.add(action); }
        void all() { while (!actions.isEmpty()) actions.remove(0).run(); }
    }
    private static final class Harness implements AutoCloseable {
        static final long TRANSIENT_MILLIS = 5000;
        volatile long now; final int width, height; final Queue worker = new Queue(), ui = new Queue(); final List<Runnable> timers = new ArrayList<>();
        final List<CensorEngine.CaptureCallback<PixelFrame>> callbacks = new ArrayList<>(); final CensorEngine<PixelFrame> engine; SceneEngine.Region[] regions; Bitmap lastImage;
        Harness(int width, int height) {
            this.width = width; this.height = height;
            engine = new CensorEngine<>(new CensorEngine.Policy(334, 0, 8, 3, false), new SceneEngine.Policy(16, 100, 64, 256, TRANSIENT_MILLIS, 20000, .5), () -> now,
                    (action, delay) -> { timers.add(action); return () -> timers.remove(action); }, (request, callback) -> { assertTrue(request.tryDispatch()); callbacks.add(callback); },
                    Collections.singletonList(new CensorEngine.LaneWorker<>(SceneEngine.Lane.FAST, worker, (input, completion) -> {
                        List<SceneEngine.Observation> observations = new ArrayList<>(); for (SceneEngine.Region region : regions) observations.add(new SceneEngine.Observation("FACE", 1, 0, region, .9f));
                        completion.success(new SceneEngine.Batch(input.source, SceneEngine.Lane.FAST, observations, Collections.emptyList()), new CensorEngine.WorkTiming(1, 2, 1));
                    })), ui, new CensorEngine.Observer() { });
        }
        void image(long at, int[] xy, int color) {
            now = at; regions = new SceneEngine.Region[]{new SceneEngine.Region(xy[0] + 60, xy[1] + 60, xy[0] + 100, xy[1] + 100), new SceneEngine.Region(xy[0] + 200, xy[1] + 60, xy[0] + 240, xy[1] + 100)};
            timers.remove(0).run(); Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888); bitmap.eraseColor(color); lastImage = bitmap;
            callbacks.get(callbacks.size() - 1).success(new CensorEngine.Captured<>(new PixelFrame(bitmap, PixelGeometry.display(width, height, width, height), PixelFrame.Kind.ACCESSIBILITY_DISPLAY, -1, at, 0), width, height, -1, bitmap::recycle)); worker.all(); ui.all();
        }
        @Override public void close() { engine.close(); for (CensorEngine.CaptureCallback<PixelFrame> callback : callbacks) callback.failure(0); worker.all(); ui.all(); }
    }
}
