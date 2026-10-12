package com.subhub.app.censor.android;

import static org.junit.Assert.*;

import android.Manifest;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.View;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;

import com.subhub.app.censor.engine.CensorEngine;
import com.subhub.app.censor.engine.NativeTextFrame;
import com.subhub.app.censor.engine.ProtectionScope;
import com.subhub.app.censor.engine.SceneEngine;
import com.subhub.app.detection.BBox;
import com.subhub.app.detection.Detection;
import com.subhub.app.detection.DetectionEngine;
import com.subhub.app.detection.DetectorConfig;
import com.subhub.app.detection.text.TextSmutConfig;
import com.subhub.app.penance.CensorTapTracker;
import com.subhub.app.settings.CensorAppearance;
import com.subhub.app.settings.EffectPalette;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Actual SDK service/window/screenshots/provider/overlay. Fake models only in explicitly named adverse controls. */
@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion = 34)
public final class BoundCensorControllerAndroidTest {
    private static final int COVER = 0xffff0080;
    private Instrumentation instrumentation;
    private UiAutomation ui;
    private Context context;
    private ComponentName component;
    private String previousServices;
    private int previousEnabled, previousComponent;
    private boolean settingsOwned;

    @Before public void bindActualNonSuppressedService() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation(); context = instrumentation.getTargetContext();
        assertEquals("Owned-emulator opt-in required", "true",
                InstrumentationRegistry.getArguments().getString("bound_censor_fixture"));
        assertTrue("Not authorized on a phone", android.os.Build.FINGERPRINT.contains("generic")
                || android.os.Build.MODEL.contains("sdk_gphone"));
        ui = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        component = new ComponentName(context, BoundCensorFixtureService.class);
        previousServices = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        previousEnabled = Settings.Secure.getInt(context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0);
        previousComponent = context.getPackageManager().getComponentEnabledSetting(component);
        assertFalse("Legacy service must remain inactive", previousServices != null && previousServices.contains("ScreenshotAccessibilityService"));
        assertFalse("Product continuous capture is not in scope", previousServices != null && previousServices.contains("ScreenCapture"));
        settingsOwned = true;
        context.getPackageManager().setComponentEnabledSetting(component, PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP);
        android.view.accessibility.AccessibilityManager manager = context.getSystemService(android.view.accessibility.AccessibilityManager.class);
        await("Actual installed fixture discovery before enabled-list mutation", () -> manager.getInstalledAccessibilityServiceList().stream()
                .anyMatch(info -> component.equals(new ComponentName(info.getResolveInfo().serviceInfo.packageName, info.getResolveInfo().serviceInfo.name))));
        secure(previousServices == null || previousServices.isEmpty() ? component.flattenToString()
                : previousServices + ":" + component.flattenToString(), 1);
        await("Actual service connection", () -> BoundCensorFixtureService.current != null);
        assertBinding();
    }
    @After public void restoreExactOwnedGuestSettings() throws Exception {
        if (!settingsOwned) return;
        try {
            BoundCensorFixtureService service = BoundCensorFixtureService.current;
            if (service != null && service.controller != null) {
                main(service.controller::close);
                await("Controller actual managed closure before settings restore", service.controller::managedResourcesDrained);
            }
        } finally {
            secure(previousServices, previousEnabled);
            context.getPackageManager().setComponentEnabledSetting(component, previousComponent, PackageManager.DONT_KILL_APP);
        }
        assertEquals(previousServices, Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES));
        assertEquals(previousEnabled, Settings.Secure.getInt(context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0));
        assertEquals(previousComponent, context.getPackageManager().getComponentEnabledSetting(component));
        if (previousServices == null || !previousServices.contains(component.flattenToString())) {
            await("Actual fixture unbinding", () -> BoundCensorFixtureService.current == null);
        }
    }
    private void secure(String services, int enabled) {
        ui.adoptShellPermissionIdentity(Manifest.permission.WRITE_SECURE_SETTINGS);
        try {
            assertTrue(Settings.Secure.putString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, services));
            assertTrue(Settings.Secure.putInt(context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, enabled));
        } finally { ui.dropShellPermissionIdentity(); }
    }
    private void assertBinding() {
        BoundCensorFixtureService service = BoundCensorFixtureService.current;
        assertNotNull(service); assertEquals(Process.myPid(), service.boundPid);
        assertEquals(component, new ComponentName(service.getServiceInfo().getResolveInfo().serviceInfo.packageName,
                service.getServiceInfo().getResolveInfo().serviceInfo.name));
        assertTrue(service.getServiceInfo().getCanRetrieveWindowContent());
        assertTrue((service.getServiceInfo().getCapabilities()
                & android.accessibilityservice.AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT) != 0);
        assertTrue(Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                .contains(component.flattenToString()));
    }

    @Test public void realWindowCaptureStaysUncensoredUnderComposedMaskAndTouchesPassThrough() throws Exception {
        try (Harness h = new Harness("window-no-feedback-touch", false, () -> { });
                ActivityScenario<BoundCensorFixtureActivity> activity = ActivityScenario.launch(BoundCensorFixtureActivity.class)) {
            AccessibilityWindowReader.Window window = h.window();
            h.start(window, 10, config(2, true, .3f), null, false);
            main(() -> h.controller.host().canvasSamplingControls(true));
            h.awaitVisual(10);
            int x = window.displayWidth / 2, y = window.displayHeight / 2;
            composed("window-covered", x, y, COVER);
            long first = h.latestSource();
            await("A new actual screenshot under the existing overlay", () -> h.latestSource() > first);
            try (CensorEngine<PixelFrame>.RenderView view = h.controller.host().acquireRenderView()) {
                assertNotNull(view); PixelFrame pixels = view.canvasPixels(); assertNotNull(pixels);
                assertEquals(PixelFrame.Kind.ACCESSIBILITY_WINDOW, pixels.kind);
                assertEquals(BoundCensorFixtureActivity.UNDERLYING,
                        pixels.bitmap.getPixel(pixels.bitmap.getWidth() / 2, pixels.bitmap.getHeight() / 2));
                save(pixels.bitmap, "window-uncensored-source");
            }
            AtomicReference<BoundCensorFixtureActivity> target = new AtomicReference<>(); activity.onActivity(target::set);
            long now = SystemClock.uptimeMillis();
            MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
            MotionEvent up = MotionEvent.obtain(now, now + 30, MotionEvent.ACTION_UP, x, y, 0);
            try { assertTrue(ui.injectInputEvent(down, true)); assertTrue(ui.injectInputEvent(up, true)); }
            finally { down.recycle(); up.recycle(); }
            await("Underlying actual activity received touch", () -> target.get().touches.get() == 1);
            assertTrue(h.posts.stream().anyMatch(p -> p.acceptedPaint != null && !p.mainThread));
            assertEquals(-1, h.posts.get(h.posts.size() - 1).compositorAtMillis);
            assertSpacing(h.traces); assertBinding();
        }
    }

    @Test public void actualNativeProviderWorksWithoutAnyVisualOrOcrLane() throws Exception {
        try (Harness h = new Harness("native-only", false, () -> { });
                ActivityScenario<BoundCensorFixtureActivity> activity = ActivityScenario.launch(BoundCensorFixtureActivity.class)) {
            h.start(h.window(), 10, config(2, false, .3f), text(), false);
            await("Actual native text accepted", () -> !h.nativeFrames.isEmpty());
            NativeTextFrame frame = h.nativeFrames.get(0);
            assertEquals(10, frame.source.geometry); assertFalse(frame.read.candidates.isEmpty());
            assertEquals(0, h.models.get()); assertEquals(0, h.traces.stream().filter(t -> t.stage == CensorEngine.Stage.CAPTURE_DISPATCHED).count());
            await("Actual native coverage painted", () -> h.posts.stream().anyMatch(p -> p.acceptedPaint != null && !p.acceptedPaint.members.isEmpty()));
            SceneEngine.Region box = frame.read.candidates.get(0).regions.get(0);
            composed("native-covered", (int) ((box.left + box.right) / 2), (int) ((box.top + box.bottom) / 2), COVER);
            assertBinding();
        }
    }

    @Test public void actualCanvasOcrRunsThroughRealWindowAdapterAndWorkerSurface() throws Exception {
        try (Harness h = new Harness("actual-canvas-ocr", false, () -> { });
                ActivityScenario<BoundCensorFixtureActivity> activity = ActivityScenario.launch(BoundCensorFixtureActivity.class)) {
            activity.onActivity(a -> a.nativeText.setVisibility(View.GONE));
            h.start(h.window(), 10, config(4, false, .3f), text(), false);
            await("Actual SDK OCR batch accepted", () -> h.batches.stream().anyMatch(b -> b.lane == SceneEngine.Lane.TEXT && !b.observations.isEmpty()));
            SceneEngine.Batch batch = h.batches.stream().filter(b -> b.lane == SceneEngine.Lane.TEXT && !b.observations.isEmpty()).findFirst().get();
            assertTrue(batch.observations.stream().allMatch(o -> o.partition == AccessibilityCensorHost.IMAGE_TEXT_PARTITION));
            SceneEngine.Region box = batch.observations.get(0).region;
            composed("canvas-ocr-covered", (int) ((box.left + box.right) / 2), (int) ((box.top + box.bottom) / 2), COVER);
            assertTrue(h.posts.stream().anyMatch(p -> p.acceptedPaint != null && !p.mainThread));
            assertEquals(0, h.models.get()); assertSpacing(h.traces); assertBinding();
        }
    }

    @Test public void actualCpuModelCanCompleteOnCapturedWindowWithoutLegacyRuntime() throws Exception {
        try (Harness h = new Harness("actual-cpu-liveness", true, () -> { });
                ActivityScenario<BoundCensorFixtureActivity> activity = ActivityScenario.launch(BoundCensorFixtureActivity.class)) {
            h.start(h.window(), 10, config(4, true, .3f).toBuilder().inferenceResolution(512).build(), null, false);
            await("Actual CPU FAST completed batch", () -> h.batches.stream().anyMatch(b -> b.lane == SceneEngine.Lane.FAST));
            assertEquals("CPU", h.provider.get());
            assertTrue(h.traces.stream().anyMatch(t -> t.stage == CensorEngine.Stage.JOB_COMPLETED && t.lane == SceneEngine.Lane.FAST));
            assertFalse(h.modelOnMain.get()); assertSpacing(h.traces); assertBinding();
            // Synthetic green/text is a liveness input, not a positive model-quality sample.
        }
    }

    @Test public void rejectedStaleWindowCannotPaintColdReverseOverAReplacementWindow() throws Exception {
        try (Harness h = new Harness("stale-window-cold-reverse", false, () -> { })) {
            AccessibilityWindowReader.Window stale;
            try (ActivityScenario<BoundCensorFixtureActivity> first = ActivityScenario.launch(BoundCensorFixtureActivity.class)) { stale = h.window(); }
            try (ActivityScenario<BoundCensorFixtureActivity> second = ActivityScenario.launch(BoundCensorFixtureActivity.class)) {
                AccessibilityWindowReader.Window actual = h.window(); assertNotEquals(stale.id, actual.id);
                h.start(stale, 10, config(2, true, .3f), null, true);
                await("Observed stale scope withdrawn", () -> h.controller.activeScope() == null);
                assertTrue(h.posts.stream().noneMatch(p -> p.acceptedPaint != null));
                composed("stale-window-uncovered", actual.displayWidth / 2, actual.displayHeight / 2,
                        BoundCensorFixtureActivity.UNDERLYING);
                h.start(actual, 11, config(2, true, .3f), null, false); h.awaitVisual(11);
                composed("new-window-covered", actual.displayWidth / 2, actual.displayHeight / 2, COVER);
                assertBinding();
            }
        }
    }

    @Test public void nestedScopeObserverWinsAtomicallyAndOuterTailCannotRestoreItsConfiguration() throws Exception {
        try (Harness h = new Harness("nested-scope", false, () -> { });
                ActivityScenario<BoundCensorFixtureActivity> activity = ActivityScenario.launch(BoundCensorFixtureActivity.class)) {
            AccessibilityWindowReader.Window window = h.window(); h.start(window, 10, config(2, true, .3f), null, false); h.awaitVisual(10);
            long epoch = h.controller.host().epoch();
            h.nested.set(() -> h.controller.start(h.configuration(window, 12, config(2, true, .7f), null, false)));
            h.start(window, 11, config(2, true, .6f), null, false);
            assertEquals(12, h.controller.activeScope().geometry);
            assertEquals(epoch + 2, h.controller.host().epoch()); h.awaitVisual(12);
            assertEquals(.7f, h.modelThreshold.get(), 0);
            assertEquals(ProtectionScope.lab(12), h.controller.activeScope().protectionScope);
            assertTrue(h.posts.stream().noneMatch(p -> p.acceptedPaint != null && p.acceptedPaint.scope.geometry == 11));
            assertSpacing(h.traces); assertBinding();
        }
    }

    @Test public void closeCannotClaimDrainWhileAnIndependentActualRootReadIsHeld() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicBoolean armed = new AtomicBoolean();
        try (Harness h = new Harness("held-root-drain", false, () -> {
            if (armed.get()) { entered.countDown(); hold(release); }
        }); ActivityScenario<BoundCensorFixtureActivity> activity = ActivityScenario.launch(BoundCensorFixtureActivity.class)) {
            AccessibilityWindowReader.Window window = h.window(); armed.set(true);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread reader = new Thread(() -> {
                try { assertNull(h.controller.observeWindow(context.getPackageName(), window.id)); }
                catch (Throwable thrown) { failure.set(thrown); }
            }, "Bound-held-independent-read");
            reader.start();
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS)); assertEquals(1, h.controller.windows().activeReadCount());
                main(h.controller::close);
                assertNull(h.controller.observeWindow(context.getPackageName(), window.id));
                assertFalse(h.controller.managedResourcesDrained());
            } finally { release.countDown(); reader.join(5000); }
            assertFalse(reader.isAlive()); if (failure.get() != null) throw new AssertionError(failure.get());
            await("Real root release and all managed resources", h.controller::managedResourcesDrained);
            assertEquals(0, h.controller.windows().activeReadCount());
        } finally { release.countDown(); }
    }

    @Test public void oldNativeSelectionFailureCannotInvalidateANewerRealWindow() throws Exception {
        CountDownLatch selected = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicBoolean once = new AtomicBoolean();
        try (Harness h = new Harness("old-native-selection", false, () -> { }, () -> {
            if (once.compareAndSet(false, true)) { selected.countDown(); hold(release); }
        })) {
            try {
            AccessibilityWindowReader.Window old;
            try (ActivityScenario<BoundCensorFixtureActivity> first = ActivityScenario.launch(BoundCensorFixtureActivity.class)) {
                old = h.window(); h.start(old, 10, config(2, false, .3f), text(), false);
                assertTrue(selected.await(5, TimeUnit.SECONDS));
            }
            try (ActivityScenario<BoundCensorFixtureActivity> second = ActivityScenario.launch(BoundCensorFixtureActivity.class)) {
                AccessibilityWindowReader.Window next = h.window(); assertNotEquals(old.id, next.id);
                h.start(next, 11, config(2, false, .3f), text(), false); release.countDown();
                await("New native window accepted after literal old selection failure", () -> h.nativeFrames.stream().anyMatch(f -> f.source.geometry == 11));
                assertEquals(11, h.controller.activeScope().geometry);
                assertTrue(h.nativeFrames.stream().noneMatch(f -> f.source.geometry == 10)); assertBinding();
            }
            } finally { release.countDown(); } // Release before automatic Harness.close waits for native drain.
        } finally { release.countDown(); }
    }

    @Test public void actualScreenshotCallbacksAndCaptureClockAreNotScheduledOnHeldMain() throws Exception {
        CountDownLatch held = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicLong heldAt = new AtomicLong(), beforeIdentity = new AtomicLong();
        try (Harness h = new Harness("actual-main-held", false, () -> { });
                ActivityScenario<BoundCensorFixtureActivity> activity = ActivityScenario.launch(BoundCensorFixtureActivity.class)) {
            android.content.ComponentName target = new android.content.ComponentName("com.subhub.app.test", RemoteBoundSourceActivity.class.getName());
            context.startActivity(new android.content.Intent().setComponent(target).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
            h.start(h.window("com.subhub.app.test"), 10, config(2, true, .3f), null, false); h.awaitVisual(10);
            String targetPid;
            try (android.os.ParcelFileDescriptor descriptor = ui.executeShellCommand("pidof com.subhub.app.test");
                    java.io.InputStream stream = new android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
                java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                byte[] chunk = new byte[128]; int count;
                while ((count = stream.read(chunk)) != -1) { assertTrue(bytes.size() + count < 256); bytes.write(chunk, 0, count); }
                targetPid = bytes.toString("UTF-8").trim();
            }
            assertTrue(targetPid.matches("[1-9][0-9]*")); assertNotEquals(Process.myPid(), Integer.parseInt(targetPid));
            assertTrue(new Handler(Looper.getMainLooper()).post(() -> {
                heldAt.set(SystemClock.uptimeMillis());
                beforeIdentity.set(h.traces.stream().filter(t -> t.stage == CensorEngine.Stage.CAPTURE_REQUESTED)
                        .mapToLong(t -> t.source).max().orElse(0));
                held.countDown(); hold(release);
            }));
            try {
                assertTrue(held.await(5, TimeUnit.SECONDS));
                await("A new requested/dispatched/returned/prepared identity while main remains held", 5000, () ->
                        h.traces.stream().filter(t -> t.stage == CensorEngine.Stage.CAPTURE_REQUESTED
                                && t.source > beforeIdentity.get() && t.atMillis > heldAt.get()).anyMatch(request ->
                                h.traces.stream().anyMatch(t -> t.source == request.source && t.stage == CensorEngine.Stage.CAPTURE_DISPATCHED)
                                && h.traces.stream().anyMatch(t -> t.source == request.source && t.stage == CensorEngine.Stage.CAPTURE_RETURNED)
                                && h.preparedRequests.contains(request.source)));
                assertEquals(1, release.getCount()); assertFalse(h.captureOnMain.get());
            } finally { release.countDown(); }
            main(() -> { }); assertSpacing(h.traces); assertBinding();
            await("Separate source's actual bounded lifecycle finished", () -> h.controller.observeWindow("com.subhub.app.test", -1) == null);
        } finally { release.countDown(); }
    }

    @Test public void stopStartPreservesActualPlatformSpacingAndSingleHost() throws Exception {
        try (Harness h = new Harness("bound-stop-start", false, () -> { });
                ActivityScenario<BoundCensorFixtureActivity> activity = ActivityScenario.launch(BoundCensorFixtureActivity.class)) {
            AccessibilityWindowReader.Window window = h.window(); h.start(window, 10, config(2, true, .3f), null, false); h.awaitVisual(10);
            AccessibilityCensorHost owner = h.controller.host(); main(h.controller::stop);
            composed("stopped-uncovered", window.displayWidth / 2, window.displayHeight / 2, BoundCensorFixtureActivity.UNDERLYING);
            h.start(window, 11, config(2, true, .3f), null, false); h.awaitVisual(11);
            assertSame(owner, h.controller.host()); assertEquals(1, h.models.get()); assertSpacing(h.traces); assertBinding();
        }
    }

    private final class Harness implements AutoCloseable {
        final String label;
        final List<CensorEngine.Trace> traces = new CopyOnWriteArrayList<>();
        final List<SceneEngine.Batch> batches = new CopyOnWriteArrayList<>();
        final List<NativeTextFrame> nativeFrames = new CopyOnWriteArrayList<>();
        final List<SnapshotCensorSurface.Post> posts = new CopyOnWriteArrayList<>();
        final AtomicInteger models = new AtomicInteger();
        final AtomicInteger prepared = new AtomicInteger();
        final List<Long> preparedRequests = new CopyOnWriteArrayList<>();
        final List<JSONObject> preparations = new CopyOnWriteArrayList<>();
        final AtomicBoolean captureOnMain = new AtomicBoolean();
        final AtomicBoolean modelOnMain = new AtomicBoolean();
        final AtomicReference<String> provider = new AtomicReference<>();
        final AtomicReference<Float> modelThreshold = new AtomicReference<>();
        final AtomicReference<Runnable> nested = new AtomicReference<>();
        final List<JSONObject> windowRejections = new CopyOnWriteArrayList<>();
        final AtomicBoolean resourceFailure = new AtomicBoolean();
        final BoundCensorFixtureService service;
        AccessibilityCensorController controller;
        Harness(String label, boolean actualModel, Runnable acquired) {
            this(label, actualModel, acquired, () -> { });
        }
        Harness(String label, boolean actualModel, Runnable acquired, Runnable nativeSelected) {
            this.label = label; service = BoundCensorFixtureService.current; assertNotNull(service);
            AccessibilityCensorController.Observer observer = new AccessibilityCensorController.Observer() {
                @Override public void trace(CensorEngine.Trace trace) {
                    traces.add(trace);
                    if (trace.stage == CensorEngine.Stage.CAPTURE_DISPATCHED && Looper.myLooper() == Looper.getMainLooper()) captureOnMain.set(true);
                    if (trace.stage == CensorEngine.Stage.SCOPE_CHANGED) {
                        Runnable change = nested.getAndSet(null); if (change != null) change.run();
                    }
                }
                @Override public void prepared(long request, PixelFrame.Kind kind, long nanos, int bytes) {
                    assertEquals(PixelFrame.Kind.ACCESSIBILITY_WINDOW, kind); preparedRequests.add(request); prepared.incrementAndGet();
                    try { preparations.add(new JSONObject().put("request", request).put("kind", kind.name())
                            .put("nanos", nanos).put("bytes", bytes).put("main", Looper.myLooper() == Looper.getMainLooper())); }
                    catch (org.json.JSONException failure) { throw new IllegalStateException(failure); }
                }
                @Override public void batchAccepted(SceneEngine.Batch batch) { batches.add(batch); }
                @Override public void nativeTextAccepted(NativeTextFrame frame) { nativeFrames.add(frame); }
                @Override public void providerReady(SceneEngine.Lane lane, String name, long millis) { if (lane == SceneEngine.Lane.FAST) provider.set(name); }
                @Override public void resourceFailure() { resourceFailure.set(true); }
                @Override public void posted(SnapshotCensorSurface.Post post) { posts.add(post); }
                @Override public void windowRejected(long token, AccessibilityWindowReader.Window expected, AccessibilityWindowReader.Window actual) {
                    try {
                        JSONArray observed = new JSONArray();
                        List<android.view.accessibility.AccessibilityWindowInfo> windows = service.getWindows();
                        if (windows != null) try {
                            for (android.view.accessibility.AccessibilityWindowInfo window : windows) {
                                android.graphics.Rect bounds = new android.graphics.Rect(); window.getBoundsInScreen(bounds);
                                observed.put(new JSONObject().put("id", window.getId()).put("type", window.getType())
                                        .put("active", window.isActive()).put("focused", window.isFocused()).put("bounds", bounds.toShortString()));
                            }
                        } finally { for (android.view.accessibility.AccessibilityWindowInfo window : windows) window.recycle(); }
                        windowRejections.add(new JSONObject().put("token", token).put("expectedId", expected.id)
                                .put("expectedBounds", expected.left + "," + expected.top + "," + expected.width + "," + expected.height)
                                .put("actualId", actual == null ? -1 : actual.id).put("actualUnavailable", actual == null)
                                .put("followingPlatformWindowReadNotSameObservation", observed));
                    } catch (Exception failure) { throw new AssertionError(failure); }
                }
            };
            main(() -> {
                controller = new AccessibilityCensorController(service, new SceneEngine.Policy(16, 100, 64, 256, 2000, 20000, .5),
                        new CensorTapTracker(), observer, (app, lane, config, partition, fallback) -> {
                    modelOnMain.set(Looper.myLooper() == Looper.getMainLooper()); models.incrementAndGet();
                    if (actualModel) return VisualDetectorWorker.initialize(app, lane, config, partition, fallback);
                    modelThreshold.set(config.getConfidenceThreshold());
                    return new VisualDetectorWorker(lane, config, partition, new VisualDetectorWorker.Model() {
                        @Override public String provider() { return "CPU"; }
                        @Override public void configure(DetectorConfig value) { modelThreshold.set(value.getConfidenceThreshold()); }
                        @Override public VisualDetectorWorker.ModelOutput infer(Bitmap bitmap, int width, int height, int inputWidth,
                                int inputHeight, BooleanSupplier cancelled, Consumer<DetectionEngine.RunCancellation> binding) {
                            Detection mask = new Detection("FACE_FEMALE", "face", .9f,
                                    new BBox(width / 3, height / 3, width / 3, height / 3), false, true);
                            return new VisualDetectorWorker.ModelOutput(Collections.singletonList(mask), true, cancelled.getAsBoolean(),
                                    new CensorEngine.WorkTiming(0, 0, 0));
                        }
                        @Override public void close() { assertNotEquals(Looper.getMainLooper(), Looper.myLooper()); }
                    });
                }, acquired, nativeSelected);
                service.controller = controller;
            });
        }
        AccessibilityWindowReader.Window window() throws Exception {
            return window(context.getPackageName());
        }
        AccessibilityWindowReader.Window window(String packageName) throws Exception {
            AtomicReference<AccessibilityWindowReader.Window> value = new AtomicReference<>();
            await("Actual focused application window", () -> {
                AccessibilityWindowReader.Window read = controller.observeWindow(packageName, -1);
                // The fixture owns a full-display application window. Activity RESUMED alone can
                // precede the platform's launch-animation bounds; never pass those moving bounds
                // to a strict controller and then weaken its mismatch fence when it rejects them.
                if (read == null || read.left != 0 || read.top != 0 || read.width != read.displayWidth
                        || read.height != read.displayHeight) return false;
                value.set(read); return true;
            }); return value.get();
        }
        AccessibilityCensorController.Configuration configuration(AccessibilityWindowReader.Window window, long token,
                DetectorConfig detector, TextSmutConfig text, boolean reverse) {
            CensorAppearance appearance = new CensorAppearance(CensorAppearance.Type.BOX, 100, 0, false, false,
                    CensorAppearance.BorderEffect.CLASSIC, false, COVER, new EffectPalette(COVER, COVER, COVER),
                    Collections.emptyList(), reverse, 100, "rectangle", "SubHub", "Access blocked.");
            return new AccessibilityCensorController.Configuration(token, window, detector, false, text, false, false,
                    ProtectionScope.lab(token), appearance, new SnapshotPaintPolicy(128, 1), token);
        }
        void start(AccessibilityWindowReader.Window window, long token, DetectorConfig detector, TextSmutConfig text, boolean reverse) {
            main(() -> controller.start(configuration(window, token, detector, text, reverse)));
        }
        void awaitVisual(long token) throws Exception {
            await("Current actual captured visual batch", () -> batches.stream().anyMatch(b -> b.source.geometry == token && b.lane == SceneEngine.Lane.FAST));
        }
        long latestSource() { return batches.stream().mapToLong(b -> b.source.sequence).max().orElse(0); }
        @Override public void close() throws Exception {
            main(controller::close); await("Full actual controller managed drain", controller::managedResourcesDrained);
            assertFalse("Native resource failure", resourceFailure.get());
            JSONArray raw = new JSONArray();
            for (CensorEngine.Trace trace : traces) raw.put(new JSONObject().put("stage", trace.stage.name()).put("epoch", trace.epoch)
                    .put("source", trace.source).put("lane", trace.lane == null ? JSONObject.NULL : trace.lane.name())
                    .put("atMillis", trace.atMillis).put("queueMillis", trace.queueMillis).put("pixelAgeMillis", trace.pixelAgeMillis)
                    .put("work", trace.work == null ? JSONObject.NULL : new JSONObject()
                            .put("preprocessMillis", trace.work.preprocessMillis).put("runtimeMillis", trace.work.runtimeMillis)
                            .put("postprocessMillis", trace.work.postprocessMillis)));
            JSONArray posted = new JSONArray();
            for (SnapshotCensorSurface.Post post : posts) posted.put(new JSONObject().put("serial", post.postSerial)
                    .put("main", post.mainThread).put("recordedAtMillis", post.recordedAtMillis).put("returnedAtMillis", post.postReturnedAtMillis)
                    .put("uiAdmittedAtMillis", post.uiAdmittedAtMillis).put("compositorAtMillis", post.compositorAtMillis)
                    .put("token", post.acceptedPaint == null ? -1 : post.acceptedPaint.scope.geometry));
            JSONObject receipt = new JSONObject().put("schemaVersion", 1).put("label", label).put("pid", Process.myPid()).put("actualService", true)
                    .put("suppressionDisabled", true).put("legacyInactive", true).put("managedDrain", controller.managedResourcesDrained())
                    .put("rawTraceCount", traces.size()).put("traces", raw).put("rawPostCount", posts.size()).put("posts", posted)
                    .put("nativeAccepted", nativeFrames.size()).put("batches", batches.size()).put("pixelTimeKnown", false)
                    .put("prepared", prepared.get()).put("captureOnMain", captureOnMain.get())
                    .put("preparations", new JSONArray(preparations))
                    .put("windowRejections", new JSONArray(windowRejections))
                    .put("modelQualityAcceptance", false).put("pixelPerformanceAcceptance", false);
            try (FileOutputStream stream = new FileOutputStream(new File(directory(), "native-surface-metrics-" + System.nanoTime() + ".json"))) {
                stream.write(receipt.toString().getBytes(StandardCharsets.UTF_8));
            }
        }
    }
    private static DetectorConfig config(int threads, boolean visual, float threshold) {
        return DetectorConfig.builder().enabledCategories(visual ? Collections.singleton("face") : Collections.emptySet())
                .inferenceThreads(threads).confidenceThreshold(threshold).minDetectionSize(6).inferenceResolution(320).build();
    }
    private static TextSmutConfig text() { return new TextSmutConfig(true, TextSmutConfig.SENSITIVITY_BALANCED, null); }
    private static void main(Runnable action) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> { try { action.run(); } catch (Throwable thrown) { failure.set(thrown); } });
        if (failure.get() instanceof RuntimeException) throw (RuntimeException) failure.get();
        if (failure.get() instanceof Error) throw (Error) failure.get();
        if (failure.get() != null) throw new AssertionError(failure.get());
    }
    private static void await(String label, BooleanSupplier condition) throws Exception {
        await(label, 15000, condition);
    }
    private static void await(String label, long maximumMillis, BooleanSupplier condition) throws Exception {
        long until = SystemClock.uptimeMillis() + maximumMillis;
        while (true) {
            if (condition.getAsBoolean()) return;
            assertTrue(label, SystemClock.uptimeMillis() < until); Thread.sleep(10);
        }
    }
    private static void hold(CountDownLatch release) {
        try { if (!release.await(8, TimeUnit.SECONDS)) throw new IllegalStateException("Held-read native deadline"); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
    }
    private static void assertSpacing(List<CensorEngine.Trace> traces) {
        long last = -1;
        for (CensorEngine.Trace trace : traces) if (trace.stage == CensorEngine.Stage.CAPTURE_DISPATCHED) {
            if (last >= 0) assertTrue("Actual dispatch separation", trace.atMillis - last >= 334);
            last = trace.atMillis;
        }
    }
    private void composed(String label, int x, int y, int color) throws Exception {
        AtomicReference<Bitmap> matched = new AtomicReference<>();
        await("Literal composed pixel " + label, () -> {
            Bitmap screen = ui.takeScreenshot(); if (screen == null) return false;
            if (screen.getPixel(x, y) == color) { matched.set(screen); return true; }
            screen.recycle(); return false;
        });
        try { save(matched.get(), label); } finally { matched.get().recycle(); }
    }
    private File directory() {
        File directory = context.getExternalFilesDir("censor-tap-fixtures-20261011"); assertNotNull(directory);
        assertTrue(directory.isDirectory() || directory.mkdirs()); return directory;
    }
    private void save(Bitmap pixels, String label) throws Exception {
        try (FileOutputStream stream = new FileOutputStream(new File(directory(), "native-mask-" + label + "-" + System.nanoTime() + ".png"))) {
            assertTrue(pixels.compress(Bitmap.CompressFormat.PNG, 100, stream));
        }
    }
}
