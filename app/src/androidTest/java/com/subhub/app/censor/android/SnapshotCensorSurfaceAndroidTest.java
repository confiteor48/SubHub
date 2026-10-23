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
import com.subhub.app.censor.engine.ScrollMotionLedger;
import com.subhub.app.service.ReplacementScrollPresentation;
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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import org.json.JSONArray;
import org.json.JSONObject;

/** Actual Surface buffers AND composed synthetic screen pixels, not a Pixel performance claim. */
@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion = 30)
public class SnapshotCensorSurfaceAndroidTest {
    private String servicesBefore, enabledBefore;
    private boolean settingsCaptured;

    @org.junit.Before public void preserveAutomationSettings() {
        android.content.Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        servicesBefore = android.provider.Settings.Secure.getString(context.getContentResolver(), android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        enabledBefore = android.provider.Settings.Secure.getString(context.getContentResolver(), android.provider.Settings.Secure.ACCESSIBILITY_ENABLED);
        settingsCaptured = true;
    }
    @org.junit.After public void restoreAutomationSettings() {
        if (!settingsCaptured) return;
        android.content.Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        android.app.UiAutomation automation = InstrumentationRegistry.getInstrumentation().getUiAutomation(android.app.UiAutomation.FLAG_DONT_USE_ACCESSIBILITY);
        automation.adoptShellPermissionIdentity(android.Manifest.permission.WRITE_SECURE_SETTINGS);
        try {
            try { assertTrue(android.provider.Settings.Secure.putString(context.getContentResolver(), android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, servicesBefore)); }
            finally { assertTrue(android.provider.Settings.Secure.putString(context.getContentResolver(), android.provider.Settings.Secure.ACCESSIBILITY_ENABLED, enabledBefore)); }
        } finally { automation.dropShellPermissionIdentity(); }
        assertEquals(servicesBefore, android.provider.Settings.Secure.getString(context.getContentResolver(), android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES));
        assertEquals(enabledBefore, android.provider.Settings.Secure.getString(context.getContentResolver(), android.provider.Settings.Secure.ACCESSIBILITY_ENABLED));
    }
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
        return appearance(type, reverse, 0);
    }
    private static CensorAppearance appearance(CensorAppearance.Type type, boolean reverse, float padding) {
        return new CensorAppearance(type, 70, padding, false, false, CensorAppearance.BorderEffect.CLASSIC,
                false, Color.WHITE, new EffectPalette(Color.GREEN, Color.BLUE, Color.WHITE),
                Collections.singletonList("BLOCKED"), reverse, 100, "rectangle", "SubHub", "Access blocked.");
    }

    @Test @SdkSuppress(minSdkVersion = 33)
    public void preferredVsyncTimeMovesActualSurfaceMaskWithoutChangingSourceOrReceiptClock() throws Exception {
        CountDownLatch posted = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<long[]> selected = new AtomicReference<>();
        AtomicLong selectedAttempt = new AtomicLong();
        try (Fixture f = new Fixture(true)) {
            f.submissionLabel = "expected-frame-moving-mask";
            try {
                // One initial image, farther inside the owner so cold first draws do not
                // lose the distinguishing top strip above the actual Surface clip.
                main(() -> {
                    f.surface.getLocationOnScreen(f.origin);
                    f.h.engine.start(42, ProtectionScope.normal(f.taps.scope()));
                    f.h.image(1, new int[]{f.origin[0], f.origin[1] + 120}, Color.RED);
                    f.configure(CensorAppearance.Type.BOX, false, 0); f.surface.requestObservedFrame();
                });
                f.quiet();
                CensorEngine.PaintReceipt baseline = f.lastAccepted().acceptedPaint;
                assertNotNull(baseline); assertTrue(baseline.scope.same(f.h.engine.activeScope()));
                assertNotNull(baseline.canvas); assertEquals(1, baseline.canvas.receivedAtMillis); assertFalse(baseline.members.isEmpty());
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                ReplacementScrollPresentation presentation = new ReplacementScrollPresentation(); presentation.reset(42, 7);
                long at = SystemClock.uptimeMillis(), epoch = f.h.engine.currentEpoch();
                SceneEngine.Mask mask;
                try (CensorEngine<PixelFrame>.RenderView view = f.h.engine.acquireRenderView()) {
                    mask = view.snapshot.groups.get(0).members.get(0);
                }
                SceneEngine.Region owner = new SceneEngine.Region(f.origin[0], f.origin[1], f.origin[0] + 320, f.origin[1] + 320);
                try (ScrollMotionLedger ledger = new ScrollMotionLedger(8, 4, 16)) {
                    ScrollMotionLedger.Scope scope = new ScrollMotionLedger.Scope(epoch, 42, 7, ProtectionScope.normal(f.taps.scope())); ledger.reset(scope);
                    for (int i = 0; i < 3; i++) {
                        long source = at - 200 + i * 100;
                        presentation.observe(ledger.observe(new ScrollMotionLedger.Sample(scope, i + 1, 10, 1,
                                source, source, source, source, ScrollMotionLedger.Coordinate.absolute(0),
                                ScrollMotionLedger.Coordinate.absolute(i * 50), owner), source));
                    }
                    presentation.captured(epoch, 42, mask.geometrySource.imageRequestIdentity, at, at);
                    JSONArray queryRows = new JSONArray(); AtomicInteger queryCount = new AtomicInteger();
                    JSONObject diagnostics = new JSONObject().put("schema", 2).put("setupAtMillis", at)
                            .put("controlMember", mask.identity).put("controlRequest", mask.geometrySource.imageRequestIdentity)
                            .put("seedYOffset", 120).put("capacity", 128).put("queryCount", 0).put("overflow", 0)
                            .put("actualProviderMemberKnown", false).put("queries", queryRows);
                    f.expectedFrameProbe = new JSONObject().put("schema", 2).put("fixtureDiagnostics", diagnostics);
                    f.presentationSample.set(sampleAt -> {
                        long actual = SystemClock.uptimeMillis();
                        ReplacementScrollPresentation.Pose expected = presentation.pose(mask, sampleAt);
                        ReplacementScrollPresentation.Pose control = presentation.pose(mask, actual);
                        boolean upward = expected != null && control != null && control.region.top > expected.region.top;
                        double interiorStart = expected == null || control == null ? Double.NaN
                                : upward ? expected.region.top : Math.max(control.region.bottom, expected.region.top);
                        double interiorEnd = expected == null || control == null ? Double.NaN
                                : upward ? Math.min(control.region.top, expected.region.bottom) : expected.region.bottom;
                        int patchY = Double.isNaN(interiorEnd) ? -1 : (int) Math.floor((interiorStart + interiorEnd) * .5 - f.origin[1]);
                        String reason = sampleAt <= actual ? "NO_FUTURE_AT_PROVIDER" : expected == null || control == null ? "NO_POSE"
                                : Math.abs(control.region.top - expected.region.top) < 2 ? "INSUFFICIENT_SEPARATION"
                                : patchY < 0 || patchY >= 320 || patchY < Math.ceil(expected.region.top - f.origin[1] + 1)
                                || patchY + 1 > expected.region.bottom - f.origin[1] - 1
                                || (upward ? patchY + 1 > control.region.top - f.origin[1] : patchY < control.region.bottom - f.origin[1])
                                ? "PATCH_OUTSIDE_CLIP" : "ELIGIBLE";
                        int query = queryCount.incrementAndGet();
                        try {
                            synchronized (diagnostics) {
                                diagnostics.put("queryCount", query).put("overflow", Math.max(0, query - 128));
                                if (query <= 128) queryRows.put(new JSONObject().put("query", query)
                                        .put("prospectiveAttempt", f.surface.postAttempts() + 1).put("sampleAtMillis", sampleAt)
                                        .put("actualAtMillis", actual).put("expectedRegion", expected == null ? JSONObject.NULL : regionJson(expected.region))
                                        .put("controlRegion", control == null ? JSONObject.NULL : regionJson(control.region))
                                        .put("patchY", patchY).put("reason", reason));
                            }
                        } catch (Exception failure) { throw new IllegalStateException("Fixture query export", failure); }
                        if (!"ELIGIBLE".equals(reason)) return;
                        if (selected.compareAndSet(null, new long[]{sampleAt, actual})) {
                            f.workerPostHook.set(() -> {
                                selectedAttempt.set(f.surface.postAttempts());
                                posted.countDown();
                                try { assertTrue("Bounded posted moving-mask inspection", release.await(8, TimeUnit.SECONDS)); }
                                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
                            });
                        }
                    });
                    f.presentation.set(presentation); main(f.surface::presentationChanged);
                    assertTrue("Actual preferred frame lead must reach a hardware-locked Surface draw", posted.await(5, TimeUnit.SECONDS));
                    long[] clocks = selected.get(); assertNotNull(clocks);
                    assertTrue(queryCount.get() <= 128);
                    ReplacementScrollPresentation.Pose expected = presentation.pose(mask, clocks[0]);
                    ReplacementScrollPresentation.Pose control = presentation.pose(mask, clocks[1]);
                    int x = (int) Math.floor((expected.region.left + expected.region.right) * .5 - f.origin[0]);
                    boolean upward = control.region.top > expected.region.top;
                    double interiorStart = upward ? expected.region.top : Math.max(control.region.bottom, expected.region.top);
                    double interiorEnd = upward ? Math.min(control.region.top, expected.region.bottom) : expected.region.bottom;
                    int y = (int) Math.floor((interiorStart + interiorEnd) * .5 - f.origin[1]);
                    f.expectedFrameProbe = new JSONObject().put("schema", 2).put("fixtureDiagnostics", diagnostics).put("attempt", selectedAttempt.get())
                            .put("member", mask.identity).put("poseSampleUptimeMillis", clocks[0])
                            .put("providerCallbackUptimeMillis", clocks[1]).put("expectedRegion", regionJson(expected.region))
                            .put("actualTimeControlRegion", regionJson(control.region)).put("patchX", x).put("patchY", y)
                            .put("compositorUptimeMillis", -1).put("physicalAcceptance", false);
                    assertTrue("Opaque center patch distinguishes preferred-time pose from actual-time control", x >= 0 && x < 320
                            && y >= 0 && y < 320 && y >= Math.ceil(expected.region.top - f.origin[1] + 1)
                            && y + 1 <= expected.region.bottom - f.origin[1] - 1
                            && (upward ? y + 1 <= control.region.top - f.origin[1] : y >= control.region.bottom - f.origin[1]));
                    Bitmap buffer = f.buffer();
                    try {
                        export(buffer, "expected-frame-moving-mask-buffer");
                        f.expectedFrameProbe.put("bufferArgb", buffer.getPixel(x, y));
                        assertEquals(Color.GREEN, buffer.getPixel(x, y));
                    }
                    finally { buffer.recycle(); }
                    f.composed("expected-frame-moving-mask-composed", x, y, Color.GREEN);
                    f.expectedFrameProbe.put("composedPatchAsserted", true);
                    release.countDown();
                    await(() -> f.surface.executingRenderCount() == 0 && f.surface.completionNoticeCount() == 0);
                    SurfaceSubmission submission;
                    synchronized (f.submissions) {
                        submission = f.submissions.stream().filter(value -> value.attempt == selectedAttempt.get()).findFirst().orElseThrow(AssertionError::new);
                    }
                    assertNotNull(submission.paint);
                    assertTrue(submission.holderReturned && submission.canvasClipValidated && submission.recordingFinished && submission.cleanupSucceeded);
                    assertTrue(submission.paint.sampledAtMillis <= clocks[1]);
                    assertTrue(submission.paint.sampledAtMillis < clocks[0]);
                    assertTrue(submission.paint.sampledAtMillis <= submission.postEndedAtMillis);
                    SurfaceSubmission.Member drawn = submission.paint.members.stream().filter(value -> value.identity == mask.identity).findFirst().orElseThrow(AssertionError::new);
                    assertEquals(expected.region.top, drawn.destination.top, .001);
                    f.expectedFrameProbe.put("postSerial", submission.postSerial)
                            .put("recordedUptimeMillis", submission.paint.sampledAtMillis)
                            .put("postEndedUptimeMillis", submission.postEndedAtMillis);
                    assertEquals(-1, mask.geometrySource.pixelAtMillis);
                    assertEquals(1, f.h.now); f.assertSubmissionInventory();
                }
            } finally { f.presentationSample.set(null); release.countDown(); }
        }
    }

    @Test public void unrelatedPreAdmissionOriginDuringDrawKeepsActualPostedMask() throws Exception {
        originInsertionDuringDraw(false);
    }
    @Test public void conservativeRegistrationDuringDrawReproducesTransparentPostControl() throws Exception {
        originInsertionDuringDraw(true);
    }
    private void originInsertionDuringDraw(boolean conservative) throws Exception {
        CountDownLatch submitted = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicBoolean inserted = new AtomicBoolean();
        try (Fixture f = new Fixture(true)) {
            f.submissionLabel = conservative ? "conservative-clear" : "pre-admission-kept";
            try {
                f.image(CensorAppearance.Type.BOX, false);
                f.composed("origin-insertion-baseline-" + conservative, 70, 70, Color.GREEN);
                ReplacementScrollPresentation presentation = new ReplacementScrollPresentation(); presentation.reset(42, 7);
                long epoch = f.h.engine.currentEpoch(), at = SystemClock.uptimeMillis();
                try (CensorEngine<PixelFrame>.RenderView view = f.h.engine.acquireRenderView()) {
                    SceneEngine.Source source = view.snapshot.groups.get(0).members.get(0).geometrySource;
                    presentation.captured(epoch, 42, source.imageRequestIdentity, at, at);
                }
                int acceptedBefore = f.acceptedCount();
                f.presentation.set(presentation); main(f.surface::presentationChanged); f.redraw();
                // BOX records inline; there is deliberately no prepared image resource to await.
                await(() -> {
                    AtomicBoolean ready = new AtomicBoolean();
                    main(() -> ready.set(f.acceptedCount() > acceptedBefore && f.surface.executingRenderCount() == 0
                            && f.surface.pendingPoseCount() == 0 && f.surface.completionNoticeCount() == 0
                            && !f.surface.demandPending()));
                    return ready.get();
                });
                int captures = f.h.callbacks.size(); long revision = presentation.revision();
                f.workerReadHook.set(() -> {
                    // draw() has sampled presentationRevision, but has not yet recorded this member.
                    if (conservative) presentation.captured(epoch, 42, 1001, at, at);
                    else presentation.preparedBeforeAdmission(epoch, 42, 1001, at, at);
                    inserted.set(true);
                });
                f.workerPostHook.set(() -> {
                    // The actual holder post has returned; hold this worker BEFORE completion delivery
                    // so an automatic replacement cannot conceal a transparent submitted buffer.
                    submitted.countDown();
                    try { assertTrue("Bounded posted-buffer inspection", release.await(8, TimeUnit.SECONDS)); }
                    catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
                });
                main(f.surface::requestFrame); assertTrue("Actual post after controlled origin insertion", submitted.await(5, TimeUnit.SECONDS));
                assertTrue(inserted.get());
                assertEquals(conservative, presentation.revision() != revision);
                Bitmap buffer = f.buffer();
                try {
                    export(buffer, "origin-insertion-posted-buffer-" + conservative);
                    assertEquals(conservative ? Color.TRANSPARENT : Color.GREEN, buffer.getPixel(70, 70));
                } finally { buffer.recycle(); }
                if (conservative) f.composedChecker("origin-insertion-cleared-composed-control", 70, 70);
                else f.composed("origin-insertion-kept-composed-mask", 70, 70, Color.GREEN);
                assertEquals("Origin metadata does not admit a detection or demand a capture", captures, f.h.callbacks.size());
            } finally { release.countDown(); }
            await(() -> f.surface.executingRenderCount() == 0 && f.surface.completionNoticeCount() == 0);
            SurfaceSubmission last = f.lastSubmission();
            assertTrue(last.holderReturned); assertNotNull(last.paint); assertTrue(last.paint.geometryComplete);
            assertEquals(conservative ? SurfaceSubmission.Mode.CLEAR : SurfaceSubmission.Mode.MEMBERS, last.paint.mode);
            assertEquals(conservative ? 0 : 2, last.paint.members.size());
            f.assertSubmissionInventory();
        }
    }

    @Test public void submissionGeometryMatchesActualPaddedOwnerClippedPixels() throws Exception {
        try (Fixture f = new Fixture(true)) {
            f.submissionLabel = "padded-clipped-control"; f.image(CensorAppearance.Type.BOX, false);
            ReplacementScrollPresentation presentation = new ReplacementScrollPresentation(); presentation.reset(42, 7);
            long epoch = f.h.engine.currentEpoch();
            try (CensorEngine<PixelFrame>.RenderView view = f.h.engine.acquireRenderView()) {
                presentation.captured(epoch, 42, view.snapshot.groups.get(0).members.get(0).geometrySource.imageRequestIdentity, 900, 900);
            }
            SceneEngine.Region owner = new SceneEngine.Region(f.origin[0] + 50, f.origin[1] + 50, f.origin[0] + 110, f.origin[1] + 130);
            try (ScrollMotionLedger ledger = new ScrollMotionLedger(8, 4, 16)) {
                ScrollMotionLedger.Scope scope = new ScrollMotionLedger.Scope(epoch, 42, 7, ProtectionScope.normal(f.taps.scope())); ledger.reset(scope);
                for (int i = 0; i < 2; i++) {
                    long at = 1000 + i * 100;
                    presentation.observe(ledger.observe(new ScrollMotionLedger.Sample(scope, i + 1, 10, 1, at, at, at + 1, at + 2,
                            ScrollMotionLedger.Coordinate.absolute(0), ScrollMotionLedger.Coordinate.absolute(200 - i * 40), owner), at + 3));
                }
                f.presentation.set(presentation); f.presentationTime.set(2000);
                main(() -> f.surface.configure(appearance(CensorAppearance.Type.BOX, false, .25f), SnapshotPaintPolicy.from(DetectionPreset.LOW), 0, true));
                f.redraw(); f.quiet();
                SurfaceSubmission.Paint paint = f.lastSubmission().paint; assertNotNull(paint); assertTrue(paint.geometryComplete);
                SurfaceSubmission.Member member = paint.members.stream().filter(value -> value.ownerClip != null).findFirst().orElseThrow(AssertionError::new);
                assertEquals(f.origin[0] + 50, member.destination.left, .001); assertEquals(f.origin[1] + 90, member.destination.top, .001);
                assertEquals(f.origin[0] + 110, member.destination.right, .001); assertEquals(f.origin[1] + 150, member.destination.bottom, .001);
                assertEquals(f.origin[1] + 130, member.clippedBounds.bottom, .001);
                assertEquals(f.origin[0], member.canvasClip.left, .001); assertEquals(f.origin[1], member.canvasClip.top, .001);
                Bitmap bitmap = f.buffer();
                try { export(bitmap, "submission-padded-clipped"); assertEquals(Color.GREEN, bitmap.getPixel(70, 110)); assertEquals(Color.TRANSPARENT, bitmap.getPixel(70, 140)); }
                finally { bitmap.recycle(); }
                f.composed("submission-padded-clipped-composed", 70, 110, Color.GREEN); f.assertSubmissionInventory();
            }
        }
    }

    @Test public void workerSubmissionRowsSurviveCoalescedUiCompletions() throws Exception {
        HeldDelivery delivery = new HeldDelivery(); BlockingEndpoint endpoint = new BlockingEndpoint(false);
        try (Fixture f = new Fixture((policy, revision, obsolete) -> Collections.emptyList(), endpoint, delivery, null, true)) {
            f.submissionLabel = "coalesced-completions";
            try {
                f.image(CensorAppearance.Type.BOX, false); int admitted = f.posts.size(), before = f.submissions.size();
                long drops = f.surface.droppedSuccessfulCompletionCount();
                delivery.hold.set(true); endpoint.block.set(true);
                main(f.surface::requestObservedFrame); assertTrue(endpoint.entered.await(5, TimeUnit.SECONDS));
                main(() -> { for (int i = 0; i < 100; i++) f.surface.requestObservedFrame(); }); endpoint.release.countDown();
                await(() -> f.submissions.size() >= before + 2 && f.surface.executingRenderCount() == 0 && delivery.size() == 1);
                assertEquals(admitted, f.posts.size()); assertEquals(before + 2, f.submissions.size());
                assertEquals(drops + 1, f.surface.droppedSuccessfulCompletionCount());
                Runnable notice = delivery.only(); delivery.hold.set(false); main(notice);
                await(() -> f.posts.size() == admitted + 1); f.quiet();
                f.composed("submission-coalesced-composed", 70, 70, Color.GREEN); f.assertSubmissionInventory();
            } finally { endpoint.release.countDown(); delivery.hold.set(false); }
        }
    }

    @Test public void nonemptySubmissionRemainsNonemptyAfterItsAcknowledgementIsRejected() throws Exception {
        HeldDelivery delivery = new HeldDelivery();
        try (Fixture f = new Fixture((policy, revision, obsolete) -> Collections.emptyList(), null, delivery, null, true)) {
            f.submissionLabel = "rejected-ack-nonempty";
            try {
                f.image(CensorAppearance.Type.BOX, false); int before = f.submissions.size();
                ReplacementScrollPresentation presentation = new ReplacementScrollPresentation(); presentation.reset(42, 7);
                f.presentation.set(presentation); f.redraw(); f.quiet(); before = f.submissions.size(); final int baseline = before;
                delivery.hold.set(true);
                f.workerPostHook.set(() -> presentation.captured(f.h.engine.currentEpoch(), 42, 1001, 0, 0));
                main(f.surface::requestObservedFrame);
                await(() -> f.submissions.size() > baseline && f.surface.executingRenderCount() == 0 && delivery.size() == 1);
                SurfaceSubmission value = f.lastSubmission(); assertEquals(SurfaceSubmission.Mode.MEMBERS, value.paint.mode);
                assertEquals(2, value.paint.members.size());
                Bitmap bitmap = f.buffer();
                try { export(bitmap, "submission-nonempty-rejected-ack"); assertEquals(Color.GREEN, bitmap.getPixel(70, 70)); }
                finally { bitmap.recycle(); }
                Runnable notice = delivery.only(); delivery.hold.set(false); main(notice);
                await(() -> f.posts.stream().anyMatch(post -> post.postSerial == value.postSerial)); f.quiet();
                assertTrue(f.posts.stream().anyMatch(post -> post.postSerial == value.postSerial && post.acceptedPaint == null));
                assertEquals(SurfaceSubmission.Mode.MEMBERS, value.paint.mode); f.assertSubmissionInventory();
            } finally { delivery.hold.set(false); }
        }
    }

    @Test public void submissionObserverFailureDoesNotChangeRecordedPixels() throws Exception {
        try (Fixture f = new Fixture(true)) {
            f.submissionLabel = "observer-error-control"; f.image(CensorAppearance.Type.BOX, false);
            f.submissionHook.set(() -> { throw new IllegalStateException("Controlled metadata callback failure"); });
            f.redraw(); f.quiet(); assertEquals(1, f.surface.submissionObserverFailures()); assertFalse(f.surface.endpointFailed());
            f.composed("submission-observer-error-composed", 70, 70, Color.GREEN);
            assertEquals(f.surface.postAttempts(), f.submissionCallbacks.get()); assertEquals(0, f.submissionOverflow.get());
        }
    }

    @Test public void reusedScrollMovesActualSurfaceBetweenDetectionsThenStopsWithoutTapAuthority() throws Exception {
        try (Fixture f = new Fixture()) {
            f.image(CensorAppearance.Type.BOX, false); f.composed("hybrid-before", 70, 70, Color.GREEN);
            int captures = f.h.callbacks.size(), accepted = f.acceptedCount();
            ReplacementScrollPresentation presentation = new ReplacementScrollPresentation();
            long epoch = f.h.engine.currentEpoch(), at = SystemClock.uptimeMillis();
            presentation.reset(42, 7);
            try (CensorEngine<PixelFrame>.RenderView view = f.h.engine.acquireRenderView()) {
                SceneEngine.Source source = view.snapshot.groups.get(0).members.get(0).geometrySource;
                presentation.captured(epoch, 42, source.imageRequestIdentity, at - 210, at - 200);
            }
            ScrollMotionLedger.Scope scope = new ScrollMotionLedger.Scope(epoch, 42, 7, ProtectionScope.normal(f.taps.scope()));
            ScrollMotionLedger ledger = new ScrollMotionLedger(8, 4, 16); ledger.reset(scope);
            for (int index = 0; index < 3; index++) {
                long time = at - 200 + index * 100;
                ScrollMotionLedger.Sample sample = new ScrollMotionLedger.Sample(scope, index + 1, 10, 1,
                        time, time, time + 1, time + 2, ScrollMotionLedger.Coordinate.absolute(0),
                        ScrollMotionLedger.Coordinate.absolute(200 - index * 20),
                        new SceneEngine.Region(f.origin[0], f.origin[1], f.origin[0] + 320, f.origin[1] + 320));
                presentation.observe(ledger.observe(sample, time + 3));
            }
            f.presentation.set(presentation); main(f.surface::presentationChanged);
            await(() -> f.acceptedCount() >= accepted + 2);
            await(() -> {
                AtomicBoolean quiet = new AtomicBoolean();
                main(() -> quiet.set(!presentation.isAnimating(SystemClock.uptimeMillis()) && !f.surface.demandPending()
                        && f.surface.executingRenderCount() == 0 && f.surface.completionNoticeCount() == 0));
                return quiet.get();
            });
            Bitmap moved = f.buffer();
            try {
                export(moved, "hybrid-after"); assertEquals(Color.GREEN, moved.getPixel(70, 120));
                assertEquals(Color.TRANSPARENT, moved.getPixel(70, 70));
            } finally { moved.recycle(); }
            f.composed("hybrid-composed-after", 70, 120, Color.GREEN);
            assertFalse(f.hit(70, 120)); assertEquals(captures, f.h.callbacks.size());
            long posts = f.surface.postCount(); Thread.sleep(80); assertEquals(posts, f.surface.postCount());
            main(() -> { f.h.now = 1 + Harness.TRANSIENT_MILLIS + 1; f.surface.presentationChanged(); });
            await(() -> f.surface.postCount() > posts);
            Bitmap expired = f.buffer();
            try { export(expired, "hybrid-expired"); assertEquals(Color.TRANSPARENT, expired.getPixel(70, 120)); }
            finally { expired.recycle(); }
            assertEquals(captures, f.h.callbacks.size()); ledger.close();
        }
    }

    @Test public void naturalScrollFrameDemandDoesNotWaitForHeldUiAcknowledgement() throws Exception {
        HeldDelivery delivery = new HeldDelivery();
        try (Fixture f = new Fixture((policy, revision, obsolete) -> Collections.emptyList(), null, delivery, null, true)) {
            f.submissionLabel = "natural-motion-held-ack";
            f.image(CensorAppearance.Type.BOX, false); f.quiet();
            ReplacementScrollPresentation presentation = new ReplacementScrollPresentation();
            ScrollMotionLedger ledger = new ScrollMotionLedger(8, 4, 16);
            CountDownLatch observed = new CountDownLatch(1);
            List<long[]> samples = new ArrayList<>();
            AtomicInteger admittedBefore = new AtomicInteger(), demandBefore = new AtomicInteger();
            AtomicLong began = new AtomicLong();
            AtomicBoolean cancelled = new AtomicBoolean();
            android.view.Choreographer.FrameCallback ticks = new android.view.Choreographer.FrameCallback() {
                @Override public void doFrame(long frameTimeNanos) {
                    if (cancelled.get()) return;
                    long now = SystemClock.uptimeMillis();
                    samples.add(new long[]{now, frameTimeNanos, f.demands.get(), f.surface.postAttempts(),
                            f.posts.size(), f.surface.executingRenderCount(), f.surface.pendingPoseCount(),
                            f.surface.completionNoticeCount(), delivery.size(), presentation.isAnimating(now) ? 1 : 0});
                    if (samples.size() == 6) observed.countDown();
                    else android.view.Choreographer.getInstance().postFrameCallback(this);
                }
            };
            try {
                main(() -> {
                    long epoch = f.h.engine.currentEpoch(), at = SystemClock.uptimeMillis(); began.set(at);
                    presentation.reset(42, 7);
                    try (CensorEngine<PixelFrame>.RenderView view = f.h.engine.acquireRenderView()) {
                        SceneEngine.Source source = view.snapshot.groups.get(0).members.get(0).geometrySource;
                        presentation.captured(epoch, 42, source.imageRequestIdentity, at - 210, at - 200);
                    }
                    ScrollMotionLedger.Scope scope = new ScrollMotionLedger.Scope(epoch, 42, 7, ProtectionScope.normal(f.taps.scope()));
                    ledger.reset(scope);
                    for (int index = 0; index < 3; index++) {
                        long time = at - 200 + index * 100;
                        presentation.observe(ledger.observe(new ScrollMotionLedger.Sample(scope, index + 1, 10, 1,
                                time, time, time + 1, time + 2, ScrollMotionLedger.Coordinate.absolute(0),
                                ScrollMotionLedger.Coordinate.absolute(200 - index * 20),
                                new SceneEngine.Region(f.origin[0], f.origin[1], f.origin[0] + 320, f.origin[1] + 320)), time + 3));
                    }
                    f.presentation.set(presentation); delivery.hold.set(true);
                    admittedBefore.set(f.posts.size()); demandBefore.set(f.demands.get());
                    f.surface.requestFrame();
                    android.view.Choreographer.getInstance().postFrameCallback(ticks);
                });
                assertTrue("Six independently observed natural callbacks", observed.await(5, TimeUnit.SECONDS));
                await(() -> delivery.size() == 1);
                JSONArray rows = new JSONArray();
                for (long[] sample : samples) rows.put(new JSONObject().put("entryUptimeMillis", sample[0])
                        .put("suppliedFrameTimeNanos", sample[1]).put("drawDemands", sample[2]).put("postAttempts", sample[3])
                        .put("uiPosts", sample[4]).put("renderWorkers", sample[5]).put("pendingPoses", sample[6])
                        .put("completionNotices", sample[7]).put("heldActions", sample[8]).put("motionLive", sample[9] == 1));
                f.demandProbe = new JSONObject().put("schema", 1).put("beganUptimeMillis", began.get())
                        .put("drawDemandsBefore", demandBefore.get()).put("uiPostsBefore", admittedBefore.get())
                        .put("oneInitialRequest", true).put("freshInputsDuringObservation", 0).put("manualObservedRequests", 0)
                        .put("callbacks", rows);
                Bitmap buffer = f.buffer();
                try { export(buffer, "natural-motion-held-ack-buffer"); } finally { buffer.recycle(); }
                for (long[] sample : samples) {
                    assertTrue("Observation must stay inside actual finite motion", sample[9] == 1);
                    assertTrue("Exclude unrelated750ms tap refresh", sample[0] - began.get() < CensorTapTracker.DISPLAY_REFRESH_MILLIS);
                    assertEquals("No UI acknowledgement or stolen notice", admittedBefore.get(), sample[4]);
                    assertTrue(sample[5] <= 1 && sample[6] <= 1 && sample[7] <= 1 && sample[8] <= 1);
                }
                assertTrue("live scroll frame demand must not wait for UI acknowledgement",
                        samples.get(samples.size() - 1)[2] - demandBefore.get() >= 4);
                assertEquals(1, delivery.size());
            } finally {
                main(() -> {
                    cancelled.set(true); android.view.Choreographer.getInstance().removeFrameCallback(ticks);
                    f.surface.presentationAllowed(false); f.presentation.set(null);
                });
                delivery.hold.set(false);
                if (delivery.size() == 1) main(delivery.only());
                ledger.close();
            }
            f.quiet(); f.assertSubmissionInventory();
        }
    }

    @Test public void motionDemandOwnerReentryCannotContinueDisabledPresentation() throws Exception { motionDemandRefusal(0); }
    @Test public void motionDemandOwnerReentryCannotContinueReplacedScope() throws Exception { motionDemandRefusal(1); }
    @Test public void motionDemandOwnerReentryCannotContinueReverseConfiguration() throws Exception { motionDemandRefusal(2); }
    @Test public void failedAdvisoryMotionQueryCannotEscapeOrSpinDisplayCallbacks() throws Exception { motionDemandRefusal(3); }
    @Test public void motionDemandOwnerReentryCannotContinueClosedEndpoint() throws Exception { motionDemandRefusal(4); }
    @Test public void motionDemandOwnerReentryCannotContinueChangedGeometry() throws Exception { motionDemandRefusal(5); }

    private void motionDemandRefusal(int mode) throws Exception {
        HeldDelivery delivery = new HeldDelivery();
        try (Fixture f = new Fixture((policy, revision, obsolete) -> Collections.emptyList(), null, delivery, null, true);
                ScrollMotionLedger ledger = new ScrollMotionLedger(8, 4, 16)) {
            f.submissionLabel = "motion-demand-refusal-" + mode;
            f.image(CensorAppearance.Type.BOX, false); f.quiet();
            ReplacementScrollPresentation presentation = new ReplacementScrollPresentation();
            CountDownLatch observed = new CountDownLatch(1);
            AtomicInteger callbacks = new AtomicInteger(), beforeDemands = new AtomicInteger(), beforeQueries = new AtomicInteger();
            AtomicLong began = new AtomicLong(), ended = new AtomicLong(), queryAt = new AtomicLong();
            AtomicBoolean cancelled = new AtomicBoolean(), queryMotionLive = new AtomicBoolean();
            AtomicInteger endDemands = new AtomicInteger(), endQueries = new AtomicInteger();
            AtomicInteger beforePreparation = new AtomicInteger(), beforeUiPosts = new AtomicInteger();
            AtomicInteger endPreparation = new AtomicInteger(), endUiPosts = new AtomicInteger(), heldAtEnd = new AtomicInteger();
            int[] ownership = new int[3];
            android.view.Choreographer.FrameCallback ticks = new android.view.Choreographer.FrameCallback() {
                @Override public void doFrame(long frameTimeNanos) {
                    if (cancelled.get()) return;
                    if (callbacks.incrementAndGet() == 6) { ended.set(SystemClock.uptimeMillis()); observed.countDown(); }
                    else android.view.Choreographer.getInstance().postFrameCallback(this);
                }
            };
            try {
                main(() -> {
                    long at = SystemClock.uptimeMillis(), epoch = f.h.engine.currentEpoch(); began.set(at);
                    presentation.reset(42, 7);
                    try (CensorEngine<PixelFrame>.RenderView view = f.h.engine.acquireRenderView()) {
                        SceneEngine.Source source = view.snapshot.groups.get(0).members.get(0).geometrySource;
                        presentation.captured(epoch, 42, source.imageRequestIdentity, at - 210, at - 200);
                    }
                    ScrollMotionLedger.Scope scope = new ScrollMotionLedger.Scope(epoch, 42, 7, ProtectionScope.normal(f.taps.scope())); ledger.reset(scope);
                    for (int index = 0; index < 3; index++) {
                        long time = at - 200 + index * 100;
                        presentation.observe(ledger.observe(new ScrollMotionLedger.Sample(scope, index + 1, 10, 1,
                                time, time, time + 1, time + 2, ScrollMotionLedger.Coordinate.absolute(0),
                                ScrollMotionLedger.Coordinate.absolute(200 - index * 20),
                                new SceneEngine.Region(f.origin[0], f.origin[1], f.origin[0] + 320, f.origin[1] + 320)), time + 3));
                    }
                    f.presentation.set(presentation); delivery.hold.set(true);
                    beforeDemands.set(f.demands.get()); beforeQueries.set(f.mainMotionQueries.get());
                    beforePreparation.set(f.renderer().preparationCount()); beforeUiPosts.set(f.posts.size());
                    f.motionQueryHook.set(() -> {
                        long queried = SystemClock.uptimeMillis(); queryAt.set(queried);
                        queryMotionLive.set(presentation.isAnimating(queried));
                        if (mode == 0) f.surface.presentationAllowed(false);
                        else if (mode == 1) f.h.engine.stop();
                        // Reverse BOX becomes PIXELATE and independently wakes on preparation readiness.
                        else if (mode == 2) f.surface.configure(appearance(CensorAppearance.Type.ERROR_POPUP, true), SnapshotPaintPolicy.from(DetectionPreset.LOW), 0, true);
                        else if (mode == 3) throw new IllegalStateException("Controlled advisory query failure");
                        else if (mode == 4) f.surface.close();
                        else f.surface.endpointView().setScaleX(.9f); // No requestFrame or configuration callback.
                    });
                    f.surface.requestFrame(); android.view.Choreographer.getInstance().postFrameCallback(ticks);
                });
                assertTrue(observed.await(5, TimeUnit.SECONDS));
                main(() -> {
                    endQueries.set(f.mainMotionQueries.get()); endDemands.set(f.demands.get());
                    endPreparation.set(f.renderer().preparationCount()); endUiPosts.set(f.posts.size()); heldAtEnd.set(delivery.size());
                    ownership[0] = f.surface.pendingPoseCount(); ownership[1] = f.surface.executingRenderCount();
                    ownership[2] = f.surface.completionNoticeCount();
                });
                f.motionRefusalProbe = new JSONObject().put("schema", 1).put("mode", mode)
                        .put("beganUptimeMillis", began.get()).put("endedUptimeMillis", ended.get()).put("queryUptimeMillis", queryAt.get())
                        .put("queryMotionLive", queryMotionLive.get()).put("naturalCallbacks", callbacks.get())
                        .put("drawDemandsBefore", beforeDemands.get()).put("drawDemandsAtEnd", endDemands.get())
                        .put("mainQueriesBefore", beforeQueries.get()).put("mainQueriesAtEnd", endQueries.get())
                        .put("initialRequests", 1).put("manualObservedRequests", 0);
                if (mode == 2) {
                    f.reverseIsolationProbe = new JSONObject().put("schema", 1).put("style", "ERROR_POPUP")
                            .put("reverseRequested", true).put("preparationBefore", beforePreparation.get())
                            .put("preparationAtEnd", endPreparation.get()).put("uiPostsBefore", beforeUiPosts.get())
                            .put("uiPostsAtEnd", endUiPosts.get()).put("heldActionsAtEnd", heldAtEnd.get())
                            .put("completionHolding", delivery.hold.get());
                    assertEquals("Reverse fixture must not enqueue asynchronous preparation", beforePreparation.get(), endPreparation.get());
                    assertEquals("Reverse fixture must not admit held completion", beforeUiPosts.get(), endUiPosts.get());
                    assertEquals("One bounded completion remains held", 1, heldAtEnd.get());
                    assertTrue(delivery.hold.get());
                }
                assertTrue("Refusal must be exercised during actual live motion", queryMotionLive.get());
                assertEquals("Actual main-owner reentry was exercised once", beforeQueries.get() + 1, endQueries.get());
                if (mode == 5) assertEquals("Geometry query must not renew beyond the one initial request", beforeDemands.get() + 1, endDemands.get());
                else assertTrue("No self-renewing demand after query refusal/reentry", endDemands.get() - beforeDemands.get() <= 2);
                assertTrue(ownership[0] <= 1 && ownership[1] <= 1 && ownership[2] <= 1);
                assertTrue("Exclude unrelated tap-refresh wake", ended.get() - began.get() < CensorTapTracker.DISPLAY_REFRESH_MILLIS);
            } finally {
                main(() -> {
                    cancelled.set(true); android.view.Choreographer.getInstance().removeFrameCallback(ticks);
                    f.motionQueryHook.set(null); f.surface.presentationAllowed(false); f.presentation.set(null);
                });
                delivery.hold.set(false); if (delivery.size() == 1) main(delivery.only());
            }
            f.quiet(); f.assertSubmissionInventory();
        }
    }

    @Test public void newSourceUsesItsOwnScrollBaselineAndOriginalSourcePixels() throws Exception {
        try (Fixture f = new Fixture()) {
            f.image(CensorAppearance.Type.PIXELATE, false); f.prepared();
            ReplacementScrollPresentation presentation = new ReplacementScrollPresentation(); presentation.reset(42, 7);
            long epoch = f.h.engine.currentEpoch(), at = SystemClock.uptimeMillis();
            try (CensorEngine<PixelFrame>.RenderView view = f.h.engine.acquireRenderView()) {
                presentation.captured(epoch, 42, view.snapshot.groups.get(0).members.get(0).geometrySource.imageRequestIdentity, at - 210, at - 200);
            }
            ScrollMotionLedger.Scope scope = new ScrollMotionLedger.Scope(epoch, 42, 7, ProtectionScope.normal(f.taps.scope()));
            ScrollMotionLedger ledger = new ScrollMotionLedger(8, 4, 16); ledger.reset(scope);
            for (int index = 0; index < 2; index++) {
                long time = at - 200 + index * 100;
                ScrollMotionLedger.Sample sample = new ScrollMotionLedger.Sample(scope, index + 1, 10, 1, time, time, time + 1, time + 2,
                        ScrollMotionLedger.Coordinate.absolute(0), ScrollMotionLedger.Coordinate.absolute(200 - index * 40),
                        new SceneEngine.Region(f.origin[0], f.origin[1], f.origin[0] + 320, f.origin[1] + 320));
                presentation.observe(ledger.observe(sample, time + 3));
            }
            f.presentation.set(presentation); main(f.surface::presentationChanged);
            await(() -> !presentation.isAnimating(SystemClock.uptimeMillis()));
            f.composed("hybrid-old-red", 70, 120, Color.RED);
            main(() -> {
                f.h.image(335, f.origin, Color.BLUE);
                try (CensorEngine<PixelFrame>.RenderView view = f.h.engine.acquireRenderView()) {
                    for (SceneEngine.Group group : view.snapshot.groups) for (SceneEngine.Mask mask : group.members)
                        if (mask.geometrySource.sequence == 2) presentation.captured(epoch, 42,
                                mask.geometrySource.imageRequestIdentity, SystemClock.uptimeMillis(), SystemClock.uptimeMillis());
                }
                f.surface.presentationChanged();
            });
            f.prepared(); f.composed("hybrid-fresh-blue", 70, 70, Color.BLUE);
            assertEquals(2, f.h.callbacks.size()); ledger.close();
        }
    }

    @Test public void scriptedFastPauseReverseKeepsTwentyFourFreshSourcesAtTheirOwnOrigin() throws Exception {
        try (Fixture f = new Fixture()) {
            f.image(CensorAppearance.Type.PIXELATE, false); f.prepared();
            ReplacementScrollPresentation presentation = new ReplacementScrollPresentation(); presentation.reset(42, 7);
            long epoch = f.h.engine.currentEpoch();
            ScrollMotionLedger.Scope scope = new ScrollMotionLedger.Scope(epoch, 42, 7, ProtectionScope.normal(f.taps.scope()));
            try (ScrollMotionLedger ledger = new ScrollMotionLedger(32, 4, 128)) {
                ledger.reset(scope); f.presentation.set(presentation);
                long nextEvent = 1000, delivery = 0;
                for (int frame = 1; frame <= 24; frame++) {
                    long sourceAt = 1025 + frame * 350;
                    while (nextEvent + 3 <= sourceAt) {
                        long elapsed = nextEvent - 1000;
                        long y = elapsed < 2800 ? 10000 + elapsed * 3 : elapsed < 3600 ? 18400
                                : elapsed < 6400 ? 18400 - (elapsed - 3600) * 3 : 10000;
                        ScrollMotionLedger.Sample sample = new ScrollMotionLedger.Sample(scope, ++delivery, 10, 1,
                                nextEvent, nextEvent, nextEvent + 1, nextEvent + 2, ScrollMotionLedger.Coordinate.absolute(0),
                                ScrollMotionLedger.Coordinate.absolute(y), new SceneEngine.Region(f.origin[0], f.origin[1], f.origin[0] + 320, f.origin[1] + 320));
                        presentation.observe(ledger.observe(sample, nextEvent + 3)); nextEvent += 100;
                    }
                    final int index = frame, color = Color.rgb(7 * frame, 80, 230);
                    main(() -> {
                        // A controlled event-presentation clock, NOT a physical pixel clock or
                        // a claim that the whole product followed this scripted eight seconds.
                        f.presentationTime.set(sourceAt); f.h.image(1 + index * 335, f.origin, color);
                        try (CensorEngine<PixelFrame>.RenderView view = f.h.engine.acquireRenderView()) {
                            boolean registered = false;
                            for (SceneEngine.Group group : view.snapshot.groups) for (SceneEngine.Mask mask : group.members)
                                if (mask.geometrySource.sequence == index + 1) {
                                    presentation.captured(epoch, 42, mask.geometrySource.imageRequestIdentity, sourceAt, sourceAt + 10);
                                    registered = true;
                                }
                            assertTrue("Actual newest render source, not an older held mask", registered);
                        }
                        f.surface.presentationChanged();
                    });
                    f.prepared(); f.composed("phase-" + frame, 70, 70, color);
                }
                assertEquals("No capture demand from presentation-only rendering", 25, f.h.callbacks.size());
            }
        }
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

    @Test public void offMainScopeInvalidationWithdrawsNormalMapBeforeAnyMainWithdrawalCallback() throws Exception {
        CountDownLatch held = new CountDownLatch(1), release = new CountDownLatch(1);
        try (Fixture f = new Fixture()) {
            f.image(CensorAppearance.Type.BOX, false); assertTrue(f.hit(70, 70));
            CensorEngine.ActiveScope scope = f.h.engine.activeScope();
            assertTrue(new Handler(Looper.getMainLooper()).post(() -> {
                held.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
            }));
            try {
                assertTrue(held.await(5, TimeUnit.SECONDS));
                f.surface.scopeInvalidated(scope);
                assertFalse(f.taps.matchesPoint(f.origin[0] + 70, f.origin[1] + 70, f.h.width, f.h.height, SystemClock.uptimeMillis()));
                assertEquals(1, release.getCount());
            } finally { release.countDown(); }
        } finally { release.countDown(); }
    }

    @Test public void staleOffMainInvalidationCannotWithdrawANewerNormalScopeMap() throws Exception {
        try (Fixture f = new Fixture()) {
            f.image(CensorAppearance.Type.BOX, false); CensorEngine.ActiveScope old = f.h.engine.activeScope();
            main(() -> { f.taps.clear(); f.h.engine.changeScope(43, ProtectionScope.normal(f.taps.scope()));
                f.h.image(335, f.origin, Color.BLUE); f.surface.presentationChanged(); });
            await(() -> f.lastAccepted().acceptedPaint.scope.geometry == 43);
            f.composed("new-scope-before-stale-invalidation", 70, 70, Color.GREEN);
            assertTrue(f.hit(70, 70));
            f.surface.scopeInvalidated(old); assertTrue(f.hit(70, 70));
        }
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

    private static File fixtureDirectory(android.content.Context context) {
        String session = InstrumentationRegistry.getArguments().getString("surface_fixture_session");
        if (session == null) return new File(context.getExternalFilesDir(null), "censor-tap-fixtures-20261011");
        if (!session.matches("[a-f0-9]{32}")) throw new IllegalArgumentException("fixture session");
        return new File(context.getFilesDir(), "censor-lab/native-surface-fixtures/" + session);
    }
    private static void export(Bitmap bitmap, String label) throws Exception {
        if (!"true".equals(InstrumentationRegistry.getArguments().getString("tap_fixture_capture"))) return;
        File directory = fixtureDirectory(InstrumentationRegistry.getInstrumentation().getTargetContext());
        assertTrue(directory.isDirectory() || directory.mkdirs());
        try (FileOutputStream stream = new FileOutputStream(new File(directory, "native-mask-" + label + "-" + System.nanoTime() + ".png"))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream));
        }
    }
    private static Object regionJson(SceneEngine.Region region) throws Exception {
        return region == null ? JSONObject.NULL : new JSONArray().put(region.left).put(region.top).put(region.right).put(region.bottom);
    }
    private static JSONObject sourceJson(long epoch, long geometry, long sequence, long request, SceneEngine.SourceKind kind) throws Exception {
        return new JSONObject().put("epoch", epoch).put("geometry", geometry).put("sequence", sequence).put("request", request).put("kind", kind.name());
    }
    private static JSONObject submissionJson(SurfaceSubmission value) throws Exception {
        Object paintJson = JSONObject.NULL;
        if (value.paint != null) {
            SurfaceSubmission.Paint paint = value.paint; JSONArray members = new JSONArray();
            for (SurfaceSubmission.Member member : paint.members) members.put(new JSONObject().put("identity", member.identity)
                    .put("geometrySource", sourceJson(member.geometryEpoch, member.geometry, member.geometrySequence, member.geometryRequest, member.geometryKind))
                    .put("evidenceSource", sourceJson(member.evidenceEpoch, member.evidenceGeometry, member.evidenceSequence, member.evidenceRequest, member.evidenceKind))
                    .put("destination", regionJson(member.destination)).put("canvasClip", regionJson(member.canvasClip))
                    .put("ownerClip", regionJson(member.ownerClip)).put("clippedBounds", regionJson(member.clippedBounds)));
            paintJson = new JSONObject().put("mode", paint.mode.name()).put("epoch", paint.epoch).put("geometry", paint.geometry)
                    .put("sceneRevision", paint.sceneRevision).put("presentationRevision", paint.presentationRevision).put("sampledUptimeMillis", paint.sampledAtMillis)
                    .put("rawMembers", paint.rawMembers).put("geometryComplete", paint.geometryComplete).put("members", members);
        }
        return new JSONObject().put("attempt", value.attempt).put("postSerial", value.postSerial).put("generation", value.endpointGeneration)
                .put("postEndedUptimeMillis", value.postEndedAtMillis).put("renderThreadId", value.renderThreadId)
                .put("queuedElapsedNanos", value.queuedNanos).put("lockElapsedNanos", value.lockNanos).put("recordingElapsedNanos", value.recordingNanos).put("postElapsedNanos", value.postNanos)
                .put("surface", new JSONArray().put(value.surfaceX).put(value.surfaceY).put(value.surfaceWidth).put(value.surfaceHeight))
                .put("holderReturned", value.holderReturned).put("canvasClipValidated", value.canvasClipValidated).put("recordingFinished", value.recordingFinished)
                .put("cleanupSucceeded", value.cleanupSucceeded).put("compositorUptimeMillis", value.compositorAtMillis).put("paint", paintJson);
    }
    private static final class Fixture implements AutoCloseable {
        final CensorTapTracker taps = new CensorTapTracker(); final int[] origin = new int[2];
        final List<SnapshotCensorSurface.Post> posts = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger failures = new AtomicInteger(); final ActivityScenario<SnapshotSurfaceFixtureActivity> scenario;
        final AtomicInteger demands = new AtomicInteger();
        final AtomicInteger mainMotionQueries = new AtomicInteger();
        final AtomicReference<Runnable> motionQueryHook = new AtomicReference<>();
        JSONObject demandProbe;
        JSONObject motionRefusalProbe;
        JSONObject reverseIsolationProbe;
        JSONObject expectedFrameProbe;
        final List<SurfaceSubmission> submissions = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger submissionCallbacks = new AtomicInteger(), submissionOverflow = new AtomicInteger();
        final AtomicReference<Runnable> submissionHook = new AtomicReference<>();
        final AtomicBoolean allSubmissionLeasesReleased = new AtomicBoolean(true);
        final boolean collectSubmissions;
        String submissionLabel = "unlabelled";
        final AtomicReference<Runnable> recordHook = new AtomicReference<>(), postHook = new AtomicReference<>();
        final AtomicBoolean failNextOwnerRead = new AtomicBoolean();
        final AtomicReference<Runnable> workerReadHook = new AtomicReference<>(), workerPostHook = new AtomicReference<>();
        final AtomicReference<Thread> lockedCanvasThread = new AtomicReference<>();
        final AtomicReference<ReplacementScrollPresentation> presentation = new AtomicReference<>();
        final AtomicLong presentationTime = new AtomicLong(-1);
        final AtomicReference<java.util.function.LongConsumer> presentationSample = new AtomicReference<>();
        SnapshotSurfaceFixtureActivity activity; SnapshotCensorSurface surface; Harness h;
        Fixture() throws Exception { this((policy, revision, obsolete) -> Collections.emptyList(), null); }
        Fixture(boolean collect) throws Exception { this((policy, revision, obsolete) -> Collections.emptyList(), null, null, null, collect); }
        Fixture(SnapshotCensorView.ArtworkLoader loader, SnapshotCensorSurface.Endpoint endpoint) throws Exception {
            this(loader, endpoint, null);
        }
        Fixture(SnapshotCensorView.ArtworkLoader loader, SnapshotCensorSurface.Endpoint endpoint, LatestResourceCoordinator.Delivery delivery) throws Exception {
            this(loader, endpoint, delivery, null);
        }
        Fixture(SnapshotCensorView.ArtworkLoader loader, SnapshotCensorSurface.Endpoint endpoint,
                LatestResourceCoordinator.Delivery delivery, ExecutorService renderExecutor) throws Exception {
            this(loader, endpoint, delivery, renderExecutor, false);
        }
        Fixture(SnapshotCensorView.ArtworkLoader loader, SnapshotCensorSurface.Endpoint endpoint,
                LatestResourceCoordinator.Delivery delivery, ExecutorService renderExecutor, boolean collect) throws Exception {
            collectSubmissions = collect;
            InstrumentationRegistry.getInstrumentation().getUiAutomation();
            scenario = ActivityScenario.launch(SnapshotSurfaceFixtureActivity.class);
            scenario.onActivity(value -> {
                activity = value; android.util.DisplayMetrics metrics = new android.util.DisplayMetrics(); value.getWindowManager().getDefaultDisplay().getRealMetrics(metrics);
                h = new Harness(metrics.widthPixels, metrics.heightPixels);
                SnapshotCensorView.Owner owner = new SnapshotCensorView.Owner() {
                    @Override public CensorEngine.ActiveScope activeScope() { return h.engine.activeScope(); }
                    @Override public boolean isCurrent(CensorEngine.ActiveScope scope) { return h.engine.isCurrent(scope); }
                    @Override public SnapshotCensorPainter.Pose presentation(SceneEngine.Mask mask, long now) {
                        java.util.function.LongConsumer sample = presentationSample.get(); if (sample != null) sample.accept(now);
                        ReplacementScrollPresentation value = presentation.get();
                        long fixed = presentationTime.get();
                        ReplacementScrollPresentation.Pose pose = value == null ? null : value.pose(mask, fixed < 0 ? now : fixed);
                        return pose == null ? null : new SnapshotCensorPainter.Pose(pose.region, pose.clip);
                    }
                    @Override public long presentationRevision() {
                        ReplacementScrollPresentation value = presentation.get();
                        return (value == null ? 0 : value.revision()) ^ (presentationTime.get() << 16);
                    }
                    @Override public boolean presentationAnimating(long now) {
                        if (Looper.myLooper() == Looper.getMainLooper()) {
                            mainMotionQueries.incrementAndGet(); Runnable hook = motionQueryHook.getAndSet(null);
                            if (hook != null) hook.run();
                        }
                        ReplacementScrollPresentation value = presentation.get();
                        return presentationTime.get() < 0 && value != null && value.isAnimating(now);
                    }
                    @Override public CensorEngine<PixelFrame>.RenderView acquireRenderView() {
                        if (lockedCanvasThread.get() == Thread.currentThread()) {
                            Runnable hook = workerReadHook.getAndSet(null); if (hook != null) hook.run();
                        }
                        if (lockedCanvasThread.get() == Thread.currentThread() && failNextOwnerRead.compareAndSet(true, false)) {
                            assertNotEquals(Looper.getMainLooper(), Looper.myLooper());
                            throw new OutOfMemoryError("controlled owner failure after hardware lock");
                        }
                        return h.engine.acquireRenderView();
                    }
                    @Override public CensorEngine.PaintReceipt acknowledgePaint(CensorEngine.ActiveScope scope, long revision, long generation, List<Long> ids, boolean reverse) { return h.engine.acknowledgePaint(scope, revision, generation, ids, reverse); }
                };
                SnapshotCensorSurface.Observer observer = new SnapshotCensorSurface.Observer() {
                    @Override public void drawDemand() { demands.incrementAndGet(); }
                    @Override public boolean collectSubmissions() { return collectSubmissions; }
                    @Override public void submitted(SurfaceSubmission value) {
                        if (surface.surfaceUseCount() != 0 || surface.renderer().activeDrawingCount() != 0) allSubmissionLeasesReleased.set(false);
                        if (submissionCallbacks.incrementAndGet() <= 4096) submissions.add(value); else submissionOverflow.incrementAndGet();
                        Runnable hook = submissionHook.getAndSet(null); if (hook != null) hook.run();
                    }
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
                        Runnable hook = workerPostHook.getAndSet(null); if (hook != null) hook.run();
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
        void quiet() throws Exception { await(() -> {
            AtomicBoolean idle = new AtomicBoolean(); main(() -> idle.set(surface.executingRenderCount() == 0 && surface.pendingPoseCount() == 0
                    && surface.completionNoticeCount() == 0 && !surface.demandPending())); return idle.get();
        }); }
        void prepared() throws Exception { redraw(); await(renderer()::resourcesMatchCurrent); redraw(); }
        SnapshotCensorSurface.Post lastAccepted() {
            synchronized (posts) { for (int i = posts.size() - 1; i >= 0; i--) if (posts.get(i).acceptedPaint != null) return posts.get(i); }
            throw new AssertionError("No accepted actual post");
        }
        int acceptedCount() { int count = 0; synchronized (posts) { for (SnapshotCensorSurface.Post p : posts) if (p.acceptedPaint != null) count++; } return count; }
        SurfaceSubmission lastSubmission() { synchronized (submissions) { assertFalse(submissions.isEmpty()); return submissions.get(submissions.size() - 1); } }
        void assertSubmissionInventory() {
            assertTrue(collectSubmissions); assertEquals(surface.postAttempts(), submissionCallbacks.get());
            assertEquals(submissionCallbacks.get(), submissions.size()); assertEquals(0, submissionOverflow.get());
            assertEquals(0, surface.submissionObserverFailures()); assertTrue(allSubmissionLeasesReleased.get());
            long returns = 0, attempt = 0;
            synchronized (submissions) { for (SurfaceSubmission value : submissions) {
                assertEquals(++attempt, value.attempt); assertNotEquals(Looper.getMainLooper().getThread().getId(), value.renderThreadId);
                if (value.holderReturned) returns++;
            } }
            assertEquals(surface.postReturns(), returns);
        }
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
                File file = new File(fixtureDirectory(activity), "native-surface-metrics-" + System.nanoTime() + ".json");
                JSONObject metrics = new JSONObject().put("schema", expectedFrameProbe != null ? 7 : reverseIsolationProbe != null ? 6 : motionRefusalProbe != null ? 5 : demandProbe != null ? 4 : collectSubmissions ? 3 : 2)
                        .put("mainThread", false).put("actualAccessibility", false).put("pixelAcceptance", false)
                        .put("lockAttempts", surface.lockCount()).put("successfulPosts", surface.postCount())
                        .put("postAttempts", surface.postAttempts()).put("postReturns", surface.postReturns())
                        .put("droppedSuccessfulCompletions", surface.droppedSuccessfulCompletionCount())
                        .put("endpointFailures", failures.get()).put("posts", rows);
                if (demandProbe != null) metrics.put("frameDemandProbe", demandProbe);
                if (reverseIsolationProbe != null) metrics.put("reverseIsolationProbe", reverseIsolationProbe);
                if (motionRefusalProbe != null) metrics.put("motionRefusalProbe", motionRefusalProbe);
                if (expectedFrameProbe != null) metrics.put("expectedFrameProbe", expectedFrameProbe);
                if (collectSubmissions) {
                    JSONArray attempts = new JSONArray(); synchronized (submissions) { for (SurfaceSubmission value : submissions) attempts.put(submissionJson(value)); }
                    metrics.put("submissionCallbacks", submissionCallbacks.get()).put("submissionOverflow", submissionOverflow.get())
                            .put("submissionObserverFailures", surface.submissionObserverFailures()).put("allSubmissionLeasesReleased", allSubmissionLeasesReleased.get())
                            .put("submissionObservationNanos", surface.submissionObservationNanos()).put("maximumSubmissionObservationNanos", surface.maximumSubmissionObservationNanos())
                            .put("submissionLabel", submissionLabel).put("controlledPresentationTimeMillis", presentationTime.get())
                            .put("submissions", attempts);
                }
                try (FileOutputStream out = new FileOutputStream(file)) { out.write(metrics.toString().getBytes(StandardCharsets.UTF_8)); }
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
        private static final class Timer {
            final long at; final Runnable action;
            Timer(long at, Runnable action) { this.at = at; this.action = action; }
        }
        static final long TRANSIENT_MILLIS = 5000;
        volatile long now; final int width, height; final Queue worker = new Queue(), ui = new Queue(); final List<Timer> timers = new ArrayList<>();
        final List<CensorEngine.CaptureCallback<PixelFrame>> callbacks = new ArrayList<>(); final CensorEngine<PixelFrame> engine; SceneEngine.Region[] regions; Bitmap lastImage;
        Harness(int width, int height) {
            this.width = width; this.height = height;
            engine = new CensorEngine<>(new CensorEngine.Policy(334, 0, 8, 3, false), new SceneEngine.Policy(16, 100, 64, 256, TRANSIENT_MILLIS, 20000, .5), () -> now,
                    (action, delay) -> { Timer timer = new Timer(now + delay, action); timers.add(timer); return () -> timers.remove(timer); }, (request, callback) -> { assertTrue(request.tryDispatch()); callbacks.add(callback); },
                    Collections.singletonList(new CensorEngine.LaneWorker<>(SceneEngine.Lane.FAST, worker, (input, completion) -> {
                        List<SceneEngine.Observation> observations = new ArrayList<>(); for (SceneEngine.Region region : regions) observations.add(new SceneEngine.Observation("FACE", 1, 0, region, .9f));
                        completion.success(new SceneEngine.Batch(input.source, SceneEngine.Lane.FAST, observations, Collections.emptyList()), new CensorEngine.WorkTiming(1, 2, 1));
                    })), ui, new CensorEngine.Observer() { });
        }
        void image(long at, int[] xy, int color) {
            now = at; regions = new SceneEngine.Region[]{new SceneEngine.Region(xy[0] + 60, xy[1] + 60, xy[0] + 100, xy[1] + 100), new SceneEngine.Region(xy[0] + 200, xy[1] + 60, xy[0] + 240, xy[1] + 100)};
            int before = callbacks.size();
            while (true) {
                Timer due = null;
                for (Timer timer : timers) if (timer.at <= now && (due == null || timer.at < due.at)) due = timer;
                if (due == null) break;
                timers.remove(due); due.action.run();
            }
            assertEquals("One due capture; expiry wakes are not captures", before + 1, callbacks.size());
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888); bitmap.eraseColor(color); lastImage = bitmap;
            callbacks.get(callbacks.size() - 1).success(new CensorEngine.Captured<>(new PixelFrame(bitmap, PixelGeometry.display(width, height, width, height), PixelFrame.Kind.ACCESSIBILITY_DISPLAY, -1, at, 0), width, height, -1, bitmap::recycle)); worker.all(); ui.all();
        }
        @Override public void close() { engine.close(); for (CensorEngine.CaptureCallback<PixelFrame> callback : callbacks) callback.failure(0); worker.all(); ui.all(); }
    }
}
