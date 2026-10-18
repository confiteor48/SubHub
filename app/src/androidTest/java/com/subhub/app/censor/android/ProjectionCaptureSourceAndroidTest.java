package com.subhub.app.censor.android;

import static org.junit.Assert.*;

import android.app.Instrumentation;
import android.app.ActivityOptions;
import android.app.UiAutomation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.DataSpace;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.SurfaceHolder;
import android.content.pm.PackageManager;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;

import com.subhub.app.capture.MediaProjectionLeaseRegistry;
import com.subhub.app.censor.engine.ProjectionSourceSession;
import com.subhub.app.censor.engine.CensorEngine;
import com.subhub.app.censor.engine.SceneEngine;
import com.subhub.app.penance.CensorTapTracker;
import com.subhub.app.settings.CensorAppearance;
import com.subhub.app.settings.EffectPalette;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TestName;

/** Actual consent/foreground/ImageReader source, not inference, pixel-clock or phone-performance proof. */
@SdkSuppress(minSdkVersion = 34)
public final class ProjectionCaptureSourceAndroidTest {
    private static final String FEEDBACK_CASE = "realAccessibilityOverlayKeepsFreshUnderlyingProjectionPixels";
    @Rule public final TestName testName = new TestName();
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context target = instrumentation.getTargetContext();
    private final Context test = instrumentation.getContext();
    private UiAutomation automation;
    private String beforeServices, beforeEnabled, runId;
    private ProjectionCaptureFixtureService.Session host;
    private ProjectionCaptureFixtureService.Hold hold;
    private ProjectionCaptureFixtureActivity activity;
    private boolean connected, otherStarted;
    private ComponentName fixtureComponent;
    private int beforeComponent;
    private FixtureServiceConnection.Lease<BoundCensorFixtureService> fixtureLease;
    private SnapshotCensorSurface censor;
    private WindowManager overlayManager;
    private final AtomicInteger overlayFailures = new AtomicInteger(), overlayPosts = new AtomicInteger();
    private File feedbackEvidence;
    private final FrozenEndpoint frozenEndpoint = new FrozenEndpoint();
    private final AtomicReference<SurfaceSubmission> lastSubmission = new AtomicReference<>();
    private long heldPosts, heldDestructions;
    private long feedbackDeadline;

    @Before public void prepare() {
        beforeServices = Settings.Secure.getString(target.getContentResolver(), "enabled_accessibility_services");
        beforeEnabled = Settings.Secure.getString(target.getContentResolver(), "accessibility_enabled");
        validSetting(beforeServices); validSetting(beforeEnabled);
        assertNull("another projection owner", MediaProjectionLeaseRegistry.owner());
        runId = UUID.randomUUID().toString();
        // Only the explicit feedback case binds a service; the other source contracts stay unchanged.
        boolean feedback = FEEDBACK_CASE.equals(testName.getMethodName());
        if (feedback) {
            org.junit.Assume.assumeTrue("Explicit owned-emulator source-eligibility probe only",
                    "true".equals(InstrumentationRegistry.getArguments().getString("projection_feedback_fixture")));
            assertTrue("Not authorized on a phone", android.os.Build.FINGERPRINT.contains("generic")
                    || android.os.Build.MODEL.contains("sdk_gphone"));
            assertTrue("No other selected service may be activated", beforeServices == null || beforeServices.isEmpty());
            assertTrue("Inactive initial Accessibility", beforeEnabled == null || beforeEnabled.equals("0"));
        }
        automation = feedback ? instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
                : instrumentation.getUiAutomation(); connected = true;
        instrumentation.waitForIdleSync();
    }

    @After public void cleanup() throws Exception {
        Throwable primary = null;
        for (Cleanup action : new Cleanup[] { this::closeFeedbackSurface, this::closeProjectionFixture,
                this::retireFeedbackFixture, this::restoreFeedbackSettings }) {
            try { action.run(); }
            catch (Exception | Error failure) {
                if (primary == null) primary = failure;
                else if (primary != failure) primary.addSuppressed(failure);
            }
        }
        if (primary instanceof Exception) throw (Exception) primary;
        if (primary instanceof Error) throw (Error) primary;
    }
    private interface Cleanup { void run() throws Exception; }

    private void closeProjectionFixture() {
        if (hold != null) hold.release();
        if (host == null) {
            ProjectionCaptureFixtureService.Session latest = ProjectionCaptureFixtureService.latest;
            if (latest != null && latest.runId.equals(runId)) host = latest;
        }
        if (host != null) {
            host.stop(); await("host closure", () -> host.closed, 8_000); assertHealthyClosure();
            Bundle clocks = new Bundle();
            clocks.putString("projectionFixtureCopy", "entry=" + host.copyStartedAt + " return=" + host.copyReturnedAt
                    + " delivered=" + host.delivered + " refusal=" + host.copyRefusal);
            instrumentation.sendStatus(0, clocks);
        }
        if (otherStarted) other(false, true);
        if (activity != null) instrumentation.runOnMainSync(activity::finish);
    }

    private void restoreFeedbackSettings() throws Exception {
        try {
            if (connected) {
                automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_USE_ACCESSIBILITY);
                restore("enabled_accessibility_services", beforeServices);
                restore("accessibility_enabled", beforeEnabled);
                assertEquals(beforeServices, Settings.Secure.getString(target.getContentResolver(), "enabled_accessibility_services"));
                assertEquals(beforeEnabled, Settings.Secure.getString(target.getContentResolver(), "accessibility_enabled"));
            }
        } finally {
            if (fixtureComponent != null) {
                target.getPackageManager().setComponentEnabledSetting(fixtureComponent, beforeComponent, PackageManager.DONT_KILL_APP);
                assertEquals(beforeComponent, target.getPackageManager().getComponentEnabledSetting(fixtureComponent));
            }
        }
    }

    @Test public void realAccessibilityOverlayKeepsFreshUnderlyingProjectionPixels() throws Exception {
        bindFeedbackFixture();
        startGranted();
        ProjectionCaptureFixtureService.Sample baseline = palette(false);
        assertEquals(PixelFrame.Kind.PROJECTION_DISPLAY, baseline.kind);
        assertEquals(PixelFormat.RGBA_8888, baseline.format);
        assertEquals(DataSpace.DATASPACE_SRGB, baseline.dataspace);
        ProjectionCaptureFixtureService.Witness witness = baseline.witness;
        Rect bounds = witness.bounds();
        int x = bounds.centerX(), y = bounds.centerY();
        feedbackEvidence = new File(new File(target.getFilesDir(), "projection-overlay-feedback"), runId);
        assertTrue("Exclusive evidence directory", feedbackEvidence.mkdirs());
        Bundle location = new Bundle(); location.putString("projectionOverlayEvidence", runId);
        instrumentation.sendStatus(0, location);
        composed("uncovered", x, y, Color.GREEN);
        attachFeedbackSurface(x, y);
        await("Actual accepted nonempty censor post", () -> overlayPosts.get() > 0, 4_000);
        frozenEndpoint.armed.set(true);
        instrumentation.runOnMainSync(censor::requestObservedFrame);
        assertTrue("Render worker held before its next actual holder lock", frozenEndpoint.entered.await(4, TimeUnit.SECONDS));
        feedbackDeadline = frozenEndpoint.enteredAt + 10_000;
        heldPosts = frozenEndpoint.posts.get(); heldDestructions = censor.destructionsStarted();
        SurfaceSubmission held = lastSubmission.get();
        assertNotNull(held);
        assertEquals(heldPosts, held.attempt);
        assertTrue(held.holderReturned && held.canvasClipValidated && held.recordingFinished && held.cleanupSucceeded);
        assertNotNull(held.paint);
        assertEquals(SurfaceSubmission.Mode.MEMBERS, held.paint.mode);
        assertTrue(held.paint.geometryComplete && held.paint.members.stream().anyMatch(member -> member.clippedBounds != null
                && member.clippedBounds.left <= x && member.clippedBounds.right > x
                && member.clippedBounds.top <= y && member.clippedBounds.bottom > y));
        composed("covered-before", x, y, Color.MAGENTA);
        assertFrozenBuffer(held);
        assertEquals(0, overlayFailures.get());

        final int challenge = Color.CYAN;
        long previous = host.sample.sequence;
        AtomicReference<ProjectionCaptureFixtureService.Sample> observed = new AtomicReference<>();
        AtomicReference<ProjectionCaptureFixtureService.Sample> firstLater = new AtomicReference<>();
        host.copiedObserver = (sample, pixels) -> {
            if (observed.get() != null || sample.witness != witness || sample.sequence <= previous) return;
            if (firstLater.get() == null) {
                crop("projection-first-later", pixels, x, y);
                firstLater.set(sample);
            }
            if (sample.top != Color.RED || sample.bottom != Color.BLUE
                    || sample.overlay != challenge && sample.overlay != Color.MAGENTA) return;
            crop("projection-probe", pixels, x, y);
            observed.set(sample); // The immutable witness is ready only after its actual pixels are saved.
        };
        CountDownLatch committed = new CountDownLatch(1);
        instrumentation.runOnMainSync(() -> {
            ProjectionCaptureFixtureActivity.MarkerView marker = (ProjectionCaptureFixtureActivity.MarkerView)
                    ((ViewGroup) activity.findViewById(android.R.id.content)).getChildAt(0);
            marker.getViewTreeObserver().registerFrameCommitCallback(committed::countDown);
            marker.centerColor(challenge);
        });
        assertTrue("Underlying challenge hardware commit", committed.await(feedbackRemaining(), TimeUnit.MILLISECONDS));
        try {
            await("A later source contains new underlying challenge or captured censor", () -> observed.get() != null, feedbackRemaining());
        } finally {
            ProjectionCaptureFixtureService.Sample latest = observed.get() == null ? firstLater.get() : observed.get();
            if (latest != null) feedbackStatus(latest, previous, challenge, x, y, observed.get() != null);
        }
        ProjectionCaptureFixtureService.Sample probe = observed.get();
        host.copiedObserver = null;
        composed("covered-after", x, y, Color.MAGENTA);
        assertFrozenBuffer(held);
        Bundle invariant = new Bundle();
        invariant.putString("projectionOverlayInvariant", "postSerial=" + held.postSerial + " generation=" + held.endpointGeneration
                + " heldPosts=" + heldPosts + " actualPosts=" + frozenEndpoint.posts.get()
                + " holdEntered=" + frozenEndpoint.enteredAt + " observedAt=" + SystemClock.uptimeMillis()
                + " x=" + held.surfaceX + " y=" + held.surfaceY + " width=" + held.surfaceWidth + " height=" + held.surfaceHeight
                + " destructions=" + heldDestructions + " holdFailures=" + frozenEndpoint.failures.get()
                + " sourceCurrent=true leaseCurrent=true attached=true visible=true physicalAcceptance=false");
        instrumentation.sendStatus(0, invariant);
        assertEquals("Projection source must contain fresh underlying pixels, not censor feedback", challenge, probe.overlay);
        assertEquals(0, overlayFailures.get());
        assertEquals(-1, probe.pixelAt);
    }

    private void assertFrozenBuffer(SurfaceSubmission held) {
        assertEquals(0, frozenEndpoint.entered.getCount());
        assertTrue("Common frozen-buffer deadline", SystemClock.uptimeMillis() < feedbackDeadline);
        assertEquals("No successor real Surface post during challenge/copy", heldPosts, frozenEndpoint.posts.get());
        assertSame(held, lastSubmission.get());
        assertEquals(0, frozenEndpoint.failures.get());
        assertFalse(censor.endpointFailed());
        assertFalse(host.stopped); assertTrue(host.engine.isCurrent(host.scope));
        assertTrue(BoundCensorFixtureService.CONNECTIONS.isCurrent(fixtureLease));
        assertNotNull(fixtureLease.service.getServiceInfo());
        instrumentation.runOnMainSync(() -> {
            assertEquals(heldDestructions, censor.destructionsStarted());
            assertTrue(censor.isAttachedToWindow() && censor.isShown() && censor.getWindowVisibility() == View.VISIBLE);
            assertTrue(censor.endpointView().isShown() && censor.endpointView().getHolder().getSurface().isValid());
            int[] origin = new int[2]; censor.endpointView().getLocationOnScreen(origin);
            assertEquals(held.surfaceX, origin[0]); assertEquals(held.surfaceY, origin[1]);
            assertEquals(held.surfaceWidth, censor.endpointView().getWidth());
            assertEquals(held.surfaceHeight, censor.endpointView().getHeight());
        });
    }

    private long feedbackRemaining() {
        long remaining = feedbackDeadline - SystemClock.uptimeMillis();
        assertTrue("Common frozen-buffer deadline", remaining > 0);
        return remaining;
    }

    /** A controlled render-worker pause BEFORE native lock, never a main-thread/compositor freeze. */
    private static final class FrozenEndpoint implements SnapshotCensorSurface.Endpoint {
        final AtomicBoolean armed = new AtomicBoolean();
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        final AtomicInteger posts = new AtomicInteger(), failures = new AtomicInteger();
        volatile long enteredAt = -1;
        @Override public Canvas lock(SurfaceHolder holder) {
            if (armed.get()) {
                enteredAt = SystemClock.uptimeMillis(); entered.countDown();
                try {
                    if (!release.await(20, TimeUnit.SECONDS)) {
                        failures.incrementAndGet(); throw new IllegalStateException("fixture render hold expired");
                    }
                } catch (InterruptedException failure) {
                    failures.incrementAndGet(); Thread.currentThread().interrupt(); throw new IllegalStateException(failure);
                }
            }
            return holder.lockHardwareCanvas();
        }
        @Override public void post(SurfaceHolder holder, Canvas canvas) {
            holder.unlockCanvasAndPost(canvas); posts.incrementAndGet();
        }
    }

    private void crop(String name, Bitmap pixels, int x, int y) {
        Bitmap cropped = Bitmap.createBitmap(pixels, x - 24, y - 24, 48, 48);
        try { png(name, cropped); }
        finally { cropped.recycle(); }
    }

    private void feedbackStatus(ProjectionCaptureFixtureService.Sample probe, long previous, int challenge,
            int x, int y, boolean recorded) {
        Bundle result = new Bundle();
        result.putString("projectionOverlayProbe", "recorded=" + recorded + " sourceSequence=" + probe.sequence + " previous=" + previous
                + " sourceColor=" + Integer.toHexString(probe.overlay) + " challenge=" + Integer.toHexString(challenge)
                + " censor=" + Integer.toHexString(Color.MAGENTA) + " x=" + x + " y=" + y
                + " copyEntry=" + probe.copyEntry + " copyReturn=" + probe.copyReturn
                + " sourceTimestamp=" + probe.timestamp + " arrival=" + probe.arrival + " pixelAt=" + probe.pixelAt
                + " posts=" + overlayPosts.get() + " failures=" + overlayFailures.get()
                + " pixelFile=" + (recorded ? "projection-probe.png" : "projection-first-later.png")
                + " heldPosts=" + heldPosts + " actualPosts=" + frozenEndpoint.posts.get()
                + " holdEntered=" + frozenEndpoint.enteredAt + " holdFailures=" + frozenEndpoint.failures.get()
                + " physicalAcceptance=false");
        instrumentation.sendStatus(0, result);
    }

    private void bindFeedbackFixture() throws Exception {
        fixtureComponent = new ComponentName(target, BoundCensorFixtureService.class);
        beforeComponent = target.getPackageManager().getComponentEnabledSetting(fixtureComponent);
        assertNull(BoundCensorFixtureService.CONNECTIONS.currentLease());
        long generation = BoundCensorFixtureService.CONNECTIONS.lastGeneration();
        target.getPackageManager().setComponentEnabledSetting(fixtureComponent, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
        android.view.accessibility.AccessibilityManager manager = target.getSystemService(android.view.accessibility.AccessibilityManager.class);
        await("Installed fixture discovery", () -> manager.getInstalledAccessibilityServiceList().stream().anyMatch(info ->
                fixtureComponent.equals(new ComponentName(info.getResolveInfo().serviceInfo.packageName, info.getResolveInfo().serviceInfo.name))), 4_000);
        restore("enabled_accessibility_services", fixtureComponent.flattenToString());
        restore("accessibility_enabled", "1");
        await("Actual nonsuppressed fixture lease", () -> {
            FixtureServiceConnection.Snapshot<BoundCensorFixtureService, android.accessibilityservice.AccessibilityServiceInfo> snapshot =
                    BoundCensorFixtureService.CONNECTIONS.ready(BoundCensorFixtureService::getServiceInfo, (service, info) ->
                            service.boundPid == android.os.Process.myPid() && info.getResolveInfo() != null
                            && fixtureComponent.equals(new ComponentName(info.getResolveInfo().serviceInfo.packageName, info.getResolveInfo().serviceInfo.name))
                            && (info.getCapabilities() & android.accessibilityservice.AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT) != 0
                            && FixtureServiceConnection.activeSelection(Settings.Secure.getString(target.getContentResolver(), "accessibility_enabled"),
                                    Settings.Secure.getString(target.getContentResolver(), "enabled_accessibility_services"), fixtureComponent.flattenToString()));
            if (snapshot == null || snapshot.lease.generation <= generation) return false;
            fixtureLease = snapshot.lease; return true;
        }, 4_000);
        assertNull("No second capture controller", fixtureLease.service.controller);
    }

    private void attachFeedbackSurface(int x, int y) {
        instrumentation.runOnMainSync(() -> {
            BoundCensorFixtureService service = fixtureLease.service;
            overlayManager = service.getSystemService(WindowManager.class);
            SnapshotRenderSession.Owner owner = new SnapshotRenderSession.Owner() {
                @Override public CensorEngine.ActiveScope activeScope() { return host.engine.activeScope(); }
                @Override public boolean isCurrent(CensorEngine.ActiveScope scope) { return host.engine.isCurrent(scope); }
                @Override public CensorEngine<PixelFrame>.RenderView acquireRenderView() { return host.engine.acquireRenderView(); }
                @Override public CensorEngine.PaintReceipt acknowledgePaint(CensorEngine.ActiveScope scope, long revision,
                        long generation, List<Long> members, boolean reverse) {
                    return host.engine.acknowledgePaint(scope, revision, generation, members, reverse);
                }
            };
            censor = new SnapshotCensorSurface(service, owner, (policy, revision, obsolete) -> Collections.emptyList(),
                    new CensorTapTracker(), new SnapshotCensorSurface.Observer() {
                        @Override public boolean collectSubmissions() { return true; }
                        @Override public void submitted(SurfaceSubmission submission) { lastSubmission.set(submission); }
                        @Override public void resourceFailure() { overlayFailures.incrementAndGet(); }
                        @Override public void endpointFailure() { overlayFailures.incrementAndGet(); }
                        @Override public void posted(SnapshotCensorSurface.Post post) {
                            if (post.acceptedPaint != null && !post.acceptedPaint.members.isEmpty()) overlayPosts.incrementAndGet();
                        }
                    }, frozenEndpoint);
            CensorAppearance appearance = new CensorAppearance(CensorAppearance.Type.BOX, 100, 0, false, false,
                    CensorAppearance.BorderEffect.CLASSIC, false, Color.MAGENTA,
                    new EffectPalette(Color.MAGENTA, Color.MAGENTA, Color.MAGENTA), Collections.emptyList(),
                    false, 100, "rectangle", "SubHub", "Access blocked.");
            censor.configure(appearance, new SnapshotPaintPolicy(128, 1), 1, true);
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(-1, -1,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.START; params.setFitInsetsTypes(0);
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            overlayManager.addView(censor, params);
            host.presentationChanged = censor::presentationChanged;
            host.syntheticMask = new SceneEngine.Region(x - 24, y - 24, x + 24, y + 24);
            censor.presentationAllowed(true); censor.presentationChanged();
        });
    }

    private void composed(String name, int x, int y, int color) {
        long deadline = feedbackDeadline == 0 ? SystemClock.uptimeMillis() + 4_000 : feedbackDeadline;
        int actual = 0; Bitmap last = null;
        try {
            while (SystemClock.uptimeMillis() < deadline) {
                if (last != null) last.recycle();
                last = automation.takeScreenshot();
                assertNotNull("Actual composed screenshot", last);
                assertEquals(host.width, last.getWidth()); assertEquals(host.height, last.getHeight());
                actual = last.getPixel(x, y);
                if (actual == color) break;
                SystemClock.sleep(20);
            }
            assertNotNull(last); png(name, last); // Keep failed pixels before asserting expected coverage.
            assertEquals("Composed witness " + name, color, actual);
        } finally { if (last != null) last.recycle(); }
    }

    private void png(String name, Bitmap pixels) {
        File output = new File(feedbackEvidence, name + ".png");
        try {
            if (!output.createNewFile()) throw new IOException("evidence already exists");
            try (FileOutputStream stream = new FileOutputStream(output)) {
                if (!pixels.compress(Bitmap.CompressFormat.PNG, 100, stream)) throw new IOException("PNG encode failed");
            }
        } catch (IOException failure) { throw new IllegalStateException("feedback evidence", failure); }
    }

    private void closeFeedbackSurface() {
        frozenEndpoint.release.countDown();
        if (host != null) { host.copiedObserver = null; host.presentationChanged = () -> { }; }
        SnapshotCensorSurface closing = censor;
        if (closing == null) return;
        instrumentation.runOnMainSync(() -> {
            Throwable primary = null;
            try { closing.close(); }
            catch (RuntimeException | Error failure) { primary = failure; throw failure; }
            finally {
                try { if (closing.isAttachedToWindow()) overlayManager.removeViewImmediate(closing); }
                catch (RuntimeException | Error failure) {
                    if (primary == null) throw failure;
                    primary.addSuppressed(failure);
                }
            }
        });
        await("Actual Snapshot resource closure", () -> closing.managedResourcesDrained() && closing.preparationTerminated(), 5_000);
        censor = null;
    }

    private void retireFeedbackFixture() {
        if (fixtureComponent == null) return;
        FixtureServiceConnection.Lease<BoundCensorFixtureService> closing = fixtureLease != null
                ? fixtureLease : BoundCensorFixtureService.CONNECTIONS.currentLease();
        List<FixtureServiceConnection.Lease<BoundCensorFixtureService>> targets =
                BoundCensorFixtureService.CONNECTIONS.closureTargets(closing);
        for (FixtureServiceConnection.Lease<BoundCensorFixtureService> lease : targets) {
            assertEquals(android.os.Process.myPid(), lease.service.boundPid);
            assertNull("Feedback fixture must not create a capture controller", lease.service.controller);
            if (!lease.completedSuccessfully()) instrumentation.runOnMainSync(lease.service::disableSelf);
        }
        await("Exact fixture lifecycle and protocol retirement", () -> {
            for (FixtureServiceConnection.Lease<BoundCensorFixtureService> lease : targets) {
                assertNull(lease.failure);
                if (!lease.completedSuccessfully() || lease.service.getServiceInfo() != null) return false;
            }
            return BoundCensorFixtureService.CONNECTIONS.currentLease() == null && BoundCensorFixtureService.current == null;
        }, 5_000);
    }

    @Test public void fullDisplaySourceCrossesUidAndRetainsBorrowUntilFenceReturns() throws Exception {
        startGranted();
        ProjectionCaptureFixtureService.Sample first = palette(false);
        ProjectionCaptureFixtureService.Sample cold = host.firstSample;
        assertNotNull(cold); assertEquals(1, cold.copyAttempt);
        assertTrue(cold.copyEntry > 0); assertTrue(cold.copyReturn >= cold.copyEntry);
        Bundle timing = new Bundle();
        timing.putString("projectionFixtureFirstCopy", "attempt=" + cold.copyAttempt + " entry=" + cold.copyEntry
                + " return=" + cold.copyReturn + " sequence=" + cold.sequence + " width=" + cold.width
                + " height=" + cold.height + " format=" + cold.format + " dataspace=" + cold.dataspace
                + " pixelAt=" + cold.pixelAt + " physicalAcceptance=false");
        instrumentation.sendStatus(0, timing);
        assertEquals(PixelFormat.RGBA_8888, first.format);
        assertEquals(DataSpace.DATASPACE_SRGB, first.dataspace);
        assertEquals(PixelFrame.Kind.PROJECTION_DISPLAY, first.kind);
        assertEquals(-1, first.pixelAt); assertTrue(first.sequence > 0);
        assertEquals(host.width, first.width); assertEquals(host.height, first.height);
        assertEquals(0, first.bufferLeft); assertEquals(0, first.bufferTop);
        assertNotNull(first.witness); assertEquals(target.getApplicationInfo().uid, first.witness.uid);
        assertNotEquals(target.getApplicationInfo().uid, test.getApplicationInfo().uid);
        other(false, false);
        ProjectionCaptureFixtureService.Sample second = palette(true);
        assertTrue(second.sequence > first.sequence);
        // The center is an ordinary drawn overlay. Its visibility is feedback evidence, not exclusion.
        assertEquals(Color.MAGENTA, second.overlay);
        assertThrows(IllegalStateException.class, () -> host.source.start());
        hold = host.holdNextCopy();
        other(false, false); // Queue changed source pixels even if the display was otherwise static.
        assertTrue("actual raw borrow", hold.entered.await(3, TimeUnit.SECONDS));
        ProjectionSourceSession.State borrowed = host.state();
        assertEquals(1, borrowed.mailbox.borrowed);
        long acquired = borrowed.acquisitionSequence;
        // Draw changed pixels while the copier is held; production must not wait on that copier.
        other(false, false);
        await("source acquisition during slow copy", () -> host.state().acquisitionSequence > acquired, 2_000);
        ProjectionSourceSession.State whileHeld = host.state();
        assertTrue(whileHeld.mailbox.pending <= 1); assertEquals(1, whileHeld.mailbox.borrowed);
        int delivered = host.delivered;
        host.stop();
        await("serial producer fence", () -> host.state().producerFenced, 2_000);
        ProjectionSourceSession.State fenced = host.state();
        assertTrue(fenced.invalidationCompleted); assertTrue(host.invalidatedAt >= 0);
        assertFalse(host.engine.isCurrent(host.scope));
        assertFalse(fenced.rawRetired); assertFalse(fenced.cleanupCompleted); assertFalse(host.source.isDrained());
        assertEquals(MediaProjectionLeaseRegistry.Owner.CENSOR_LAB, MediaProjectionLeaseRegistry.owner());
        hold.release();
        await("borrow retirement", () -> host.closed, 5_000);
        assertTrue("reader closed before borrow returned", hold.readableAfterFence);
        assertEquals("stale copied frame reached worker", delivered, host.delivered);
        evidence("full-display-borrow", host.state());
    }

    @Test public void actualProjectionRevocationInvalidatesAndDrainsSource() throws Exception {
        startGranted(); palette(false);
        host.projection.stop(); // Real platform revoke, not a manually invoked source callback.
        await("revocation callback and closure", () -> host.closed, 5_000);
        assertFalse(host.engine.isCurrent(host.scope));
        assertTrue(host.invalidatedAt >= 0);
        evidence("platform-revoke", host.state());
    }

    @Test public void actualResizeWithdrawsFixedGeometryWithoutReusingGrant() throws Exception {
        startGranted(); palette(false);
        other(true, false);
        await("platform resize withdrawal", () -> host.closed, 6_000);
        assertTrue(host.state().stopping); assertTrue(host.invalidatedAt >= 0);
        assertFalse(host.engine.isCurrent(host.scope));
        evidence("platform-resize", host.state());
    }

    @Test public void declinedSystemConsentStartsNoSourceOrProjectionOwner() throws Exception {
        launch(); consent(false);
        await("declined consent result", () -> activity.denied, 2_000);
        ProjectionCaptureFixtureService.Session latest = ProjectionCaptureFixtureService.latest;
        assertTrue(latest == null || !latest.runId.equals(runId));
        assertNull(MediaProjectionLeaseRegistry.owner());
        Bundle result = new Bundle(); result.putString("projectionFixture", "declined-consent sourceStarted=false");
        instrumentation.sendStatus(0, result);
    }

    private void launch() {
        instrumentation.runOnMainSync(() -> startFixture(new Intent(target, ProjectionCaptureFixtureActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("runId", runId)));
        await("consent Activity", () -> ProjectionCaptureFixtureActivity.current != null
                && runId.equals(ProjectionCaptureFixtureActivity.current.runId), 3_000);
        activity = ProjectionCaptureFixtureActivity.current;
    }

    private void startGranted() throws Exception {
        launch(); consent(true);
        await("foreground source host", () -> {
            ProjectionCaptureFixtureService.Session selected = ProjectionCaptureFixtureService.latest;
            return selected != null && selected.runId.equals(runId);
        }, 3_000);
        host = ProjectionCaptureFixtureService.latest;
        await("source startup", () -> host.failure != null || host.state() != null && host.state().started, 3_000);
        assertNull(host.failure); assertFalse(host.state().failed());
        assertEquals(MediaProjectionLeaseRegistry.Owner.CENSOR_LAB, MediaProjectionLeaseRegistry.owner());
    }

    private ProjectionCaptureFixtureService.Sample palette(boolean alternate) {
        long deadline = SystemClock.uptimeMillis() + 4_000;
        try {
            ProjectionCaptureFixtureService.Witness selected = bindWitness(alternate, deadline);
            await("actual source palette " + alternate, () -> {
                ProjectionCaptureFixtureService.Sample sample = host.sample;
                return host.engine.isCurrent(host.scope) && sample != null && sample.witness == selected
                        && sample.top == (alternate ? Color.CYAN : Color.RED)
                        && sample.bottom == (alternate ? Color.YELLOW : Color.BLUE)
                        && sample.overlay == (alternate ? Color.MAGENTA : Color.GREEN);
            }, Math.max(1, deadline - SystemClock.uptimeMillis()));
        } catch (AssertionError missing) {
            ProjectionCaptureFixtureService.Sample sample = host.sample;
            throw new AssertionError("actual source palette " + alternate + " acquired=" + host.state().acquisitionSequence
                    + " raw=" + host.rawObservation + " refusal=" + host.copyRefusal
                    + " requests=" + host.requests + " dispatches=" + host.dispatches
                    + " copyEntered=" + host.copyStartedAt + " copyReturned=" + host.copyReturnedAt
                    + " observedAt=" + SystemClock.uptimeMillis()
                    + " copyStack=" + copyStack()
                    + " copied=" + (sample == null ? "none" : Integer.toHexString(sample.top) + ","
                            + Integer.toHexString(sample.bottom) + "," + Integer.toHexString(sample.overlay)), missing);
        }
        return host.sample;
    }

    private ProjectionCaptureFixtureService.Witness bindWitness(boolean alternate, long deadline) {
        Rect committed = committedBounds(alternate, deadline);
        String description = alternate ? "Projection alternate source" : "Projection initial source";
        String packageName = alternate ? test.getPackageName() : target.getPackageName();
        Rect measured = null;
        while (SystemClock.uptimeMillis() < deadline) {
            AccessibilityNodeInfo root = automation.getRootInActiveWindow();
            if (root != null) {
                try {
                    if (packageName.equals(String.valueOf(root.getPackageName()))) {
                        AccessibilityNodeInfo marker = findMarker(root, description, new int[]{128});
                        if (marker != null) {
                            try { Rect bounds = new Rect(); marker.getBoundsInScreen(bounds); if (!bounds.isEmpty()) measured = bounds; }
                            finally { marker.recycle(); }
                        }
                    }
                } finally { root.recycle(); }
            }
            if (measured != null) break;
            SystemClock.sleep(20);
        }
        assertNotNull("owned native marker bounds " + description, measured);
        Rect physical = measured;
        assertEquals("hardware-committed View and Accessibility pose", committed, physical);
        if (!alternate) instrumentation.runOnMainSync(() -> {
            View marker = ((ViewGroup) activity.findViewById(android.R.id.content)).getChildAt(0);
            int[] location = new int[2]; marker.getLocationOnScreen(location);
            Rect independent = new Rect(location[0], location[1], location[0] + marker.getWidth(), location[1] + marker.getHeight());
            assertEquals("View-local and native on-screen pose", independent, physical);
        });
        int uid = alternate ? test.getApplicationInfo().uid : target.getApplicationInfo().uid;
        ProjectionCaptureFixtureService.Witness selected = new ProjectionCaptureFixtureService.Witness(physical, uid, host.width, host.height);
        assertTrue(host.engine.isCurrent(host.scope)); host.witness = selected;
        Bundle pose = new Bundle(); pose.putString("projectionFixturePose", "uid=" + uid + " bounds=" + selected.bounds() + " frameCommitted=true");
        instrumentation.sendStatus(0, pose);
        if (alternate) {
            startFixture(new Intent().setComponent(new ComponentName(test.getPackageName(), ProjectionTargetFixtureActivity.class.getName()))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("redraw", true));
        } else instrumentation.runOnMainSync(() -> ((ViewGroup) activity.findViewById(android.R.id.content)).getChildAt(0).invalidate());
        return selected;
    }

    private Rect committedBounds(boolean alternate, long deadline) {
        AtomicReference<Rect> bounds = new AtomicReference<>();
        if (!alternate) {
            CountDownLatch committed = new CountDownLatch(1);
            instrumentation.runOnMainSync(() -> {
                View marker = ((ViewGroup) activity.findViewById(android.R.id.content)).getChildAt(0);
                assertTrue("hardware fixture", marker.isHardwareAccelerated());
                marker.getViewTreeObserver().registerFrameCommitCallback(() -> marker.post(() -> {
                    int[] location = new int[2]; marker.getLocationOnScreen(location);
                    bounds.set(new Rect(location[0], location[1], location[0] + marker.getWidth(), location[1] + marker.getHeight()));
                    committed.countDown();
                }));
                marker.invalidate();
            });
            try { assertTrue("native frame commit", committed.await(Math.max(1, deadline - SystemClock.uptimeMillis()), TimeUnit.MILLISECONDS)); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
        } else {
            AtomicInteger replies = new AtomicInteger();
            AtomicInteger replyCode = new AtomicInteger(-99), replyUid = new AtomicInteger(-1);
            ResultReceiver receiver = new ResultReceiver(new Handler(Looper.getMainLooper())) {
                @Override protected void onReceiveResult(int code, Bundle result) {
                    replyCode.set(code); replyUid.set(result.getInt("uid", -1));
                    if (code == 1 && result.getInt("uid", -1) == test.getApplicationInfo().uid)
                        bounds.set(new Rect(result.getInt("left"), result.getInt("top"), result.getInt("right"), result.getInt("bottom")));
                    replies.incrementAndGet(); // Publish readiness only after the whole observation.
                }
            };
            startFixture(new Intent().setComponent(new ComponentName(test.getPackageName(), ProjectionTargetFixtureActivity.class.getName()))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("redraw", true).putExtra("commit", receiver));
            await("other-UID hardware frame commit reply", () -> replies.get() != 0, Math.max(1, deadline - SystemClock.uptimeMillis()));
            assertEquals(1, replies.get());
            assertEquals("other-UID commit code", 1, replyCode.get());
            assertEquals("other-UID commit identity", test.getApplicationInfo().uid, replyUid.get());
            assertNotNull("other-UID committed native bounds", bounds.get());
        }
        return bounds.get();
    }

    private static AccessibilityNodeInfo findMarker(AccessibilityNodeInfo node, String description, int[] budget) {
        if (--budget[0] < 0) return null;
        if (node.isVisibleToUser() && description.contentEquals(node.getContentDescription() == null ? "" : node.getContentDescription()))
            return AccessibilityNodeInfo.obtain(node);
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try { AccessibilityNodeInfo selected = findMarker(child, description, budget); if (selected != null) return selected; }
            finally { child.recycle(); }
        }
        return null;
    }

    private static String copyStack() {
        StringBuilder result = new StringBuilder();
        int matching = 0;
        for (java.util.Map.Entry<Thread, StackTraceElement[]> row : Thread.getAllStackTraces().entrySet()) {
            if (!row.getKey().getName().equals("projection-fixture-copy")) continue;
            matching++;
            StackTraceElement[] frames = row.getValue();
            for (int i = 0; i < Math.min(16, frames.length); i++) result.append(frames[i]).append(';');
        }
        return "threads=" + matching + ":" + result;
    }

    private void other(boolean landscape, boolean finish) {
        Intent intent = new Intent().setComponent(new ComponentName(test.getPackageName(), ProjectionTargetFixtureActivity.class.getName()))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("landscape", landscape).putExtra("finish", finish)
                .putExtra("pulse", otherStarted && !landscape && !finish);
        startFixture(intent); otherStarted = !finish;
    }

    private void startFixture(Intent intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        target.startActivity(intent, ActivityOptions.makeCustomAnimation(target, 0, 0).toBundle());
    }

    private void consent(boolean allow) {
        long deadline = SystemClock.uptimeMillis() + 4_000;
        List<String> observed = new ArrayList<>();
        while (SystemClock.uptimeMillis() < deadline) {
            AccessibilityNodeInfo root = automation.getRootInActiveWindow();
            if (root != null) {
                try {
                    if ("com.android.systemui".equals(String.valueOf(root.getPackageName()))) {
                        AccessibilityNodeInfo button = findConsent(root, allow, observed);
                        if (button != null) {
                            try {
                                assertTrue("default-display system consent", observed.contains("Entire screen")
                                        && observed.stream().anyMatch(text -> text.contains("SubHub")));
                                assertTrue("system consent action", button.performAction(AccessibilityNodeInfo.ACTION_CLICK));
                                return;
                            }
                            finally { button.recycle(); }
                        }
                    }
                } finally { root.recycle(); }
            }
            SystemClock.sleep(30);
        }
        fail("System consent control unavailable; no permission bypass: " + observed);
    }

    private AccessibilityNodeInfo findConsent(AccessibilityNodeInfo node, boolean allow, List<String> observed) {
        CharSequence label = node.getText();
        String text = label == null ? "" : label.toString();
        if (observed.size() < 64 && !text.isEmpty() && !observed.contains(text)) observed.add(text);
        if (node.isVisibleToUser() && node.isClickable() && node.isEnabled()
                && (allow ? text.equals("Start") || text.equals("Start now") || text.equals("Start recording") || text.equals("Start sharing")
                          : text.equals("Cancel"))) return AccessibilityNodeInfo.obtain(node);
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try { AccessibilityNodeInfo result = findConsent(child, allow, observed); if (result != null) return result; }
            finally { child.recycle(); }
        }
        return null;
    }

    private void assertHealthyClosure() {
        assertNull(host.failure); assertTrue(host.source.isDrained()); assertTrue(host.engineDrained);
        assertFalse(host.state().failed()); assertEquals(0, host.engine.livePixelFrames());
        assertEquals(0, host.engine.callbackFailures()); assertEquals(0, host.engine.resourceFailures());
        assertFalse(host.engine.captureInFlight()); assertTrue(host.copies.isTerminated()); assertFalse(host.producer.isAlive());
        assertNull(MediaProjectionLeaseRegistry.owner());
    }

    private void evidence(String contract, ProjectionSourceSession.State state) {
        Bundle result = new Bundle();
        result.putString("projectionFixture", contract + " acquired=" + state.acquisitionSequence
                + " accepted=" + state.mailbox.accepted + " replaced=" + state.mailbox.replaced
                + " fenced=" + state.producerFenced + " rawRetired=" + state.rawRetired
                + " cleanup=" + state.cleanupCompleted + " physicalAcceptance=false");
        instrumentation.sendStatus(0, result);
    }

    private void restore(String key, String value) throws Exception {
        // UiAutomation executes the command directly, not through a quote-interpreting shell.
        String command = value == null ? "settings delete secure " + key : "settings put secure " + key + " " + value;
        try (ParcelFileDescriptor descriptor = automation.executeShellCommand(command);
             InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] bytes = new byte[256]; int count;
            while ((count = input.read(bytes)) != -1) { output.write(bytes, 0, count); assertTrue(output.size() < 4096); }
            String diagnostic = output.toString("UTF-8").trim();
            assertTrue("setting restoration diagnostic", diagnostic.isEmpty()
                    || value == null && diagnostic.matches("Deleted [01] rows"));
        }
    }

    private static void validSetting(String value) {
        if (value != null && !value.matches("[A-Za-z0-9_.:/]+")) throw new IllegalStateException("unhandled original setting syntax");
    }

    private static void await(String message, BooleanSupplier condition, long timeout) {
        long deadline = SystemClock.uptimeMillis() + timeout;
        while (SystemClock.uptimeMillis() < deadline) { if (condition.getAsBoolean()) return; SystemClock.sleep(20); }
        assertTrue(message, condition.getAsBoolean());
    }
}
