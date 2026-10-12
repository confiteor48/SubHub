package com.subhub.app.censor.android;

import static org.junit.Assert.*;

import android.Manifest;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.appmode.AppTimerManager;
import com.subhub.app.censor.engine.CensorEngine;
import com.subhub.app.censor.engine.ProtectionScope;
import com.subhub.app.censor.engine.SceneEngine;
import com.subhub.app.detection.BBox;
import com.subhub.app.detection.Detection;
import com.subhub.app.detection.DetectionEngine;
import com.subhub.app.detection.DetectorConfig;
import com.subhub.app.diagnostics.CensorLabCensorSession;
import com.subhub.app.penance.CensorTapTracker;
import com.subhub.app.security.HardcoreModeManager;
import com.subhub.app.service.ScreenshotAccessibilityService;
import com.subhub.app.settings.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Actual production service and product selection. Models only are controlled; this is not Pixel performance acceptance. */
@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion = 34)
public final class ProductCensorSessionAndroidTest {
    private static final String TARGET = "com.subhub.app.test";
    private static final int COVER = 0xffff0080;
    private Instrumentation instrumentation;
    private UiAutomation ui;
    private Context context;
    private SharedPreferences preferences, qualification;
    private Map<String, ?> savedPreferences, savedQualification;
    private String previousServices;
    private int previousEnabled;
    private boolean settingsOwned;
    private CensorProductSession session;
    private final AtomicInteger models = new AtomicInteger(), closes = new AtomicInteger(), readCount = new AtomicInteger();
    private final AtomicReference<Hold> readHold = new AtomicReference<>(), modelHold = new AtomicReference<>();
    private final AtomicBoolean inferRunning = new AtomicBoolean(), earlyClose = new AtomicBoolean(), stopForeground = new AtomicBoolean();
    private final CountDownLatch foregroundStopped = new CountDownLatch(1);
    private final List<CensorEngine.Trace> traces = new CopyOnWriteArrayList<>();
    private final List<SceneEngine.Batch> batches = new CopyOnWriteArrayList<>();
    private final List<SnapshotCensorSurface.Post> posts = new CopyOnWriteArrayList<>();
    private final List<PixelFrame.Kind> preparations = new CopyOnWriteArrayList<>();
    private final List<SceneEngine.Lane> unavailable = new CopyOnWriteArrayList<>();
    private final List<Throwable> faults = new CopyOnWriteArrayList<>();
    private final List<Hold> ownedHolds = new CopyOnWriteArrayList<>();

    @Before public void ownOnlySyntheticGuestSettingsAndUnsuppressedAutomation() {
        instrumentation = InstrumentationRegistry.getInstrumentation(); context = instrumentation.getTargetContext();
        assertEquals("Owned guest opt-in required", "true", InstrumentationRegistry.getArguments().getString("bound_censor_fixture"));
        assertTrue("Never authorized on a phone", ProductCensorQualification.supported());
        ui = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        preferences = new SettingsRepository(context).preferences();
        qualification = context.getSharedPreferences(ProductCensorQualification.PREFERENCES, Context.MODE_PRIVATE);
        savedPreferences = copy(preferences.getAll()); savedQualification = copy(qualification.getAll());
        previousServices = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        previousEnabled = Settings.Secure.getInt(context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0);
        assertFalse("No existing production capture owner may be borrowed", previousServices != null
                && (previousServices.contains("ScreenshotAccessibilityService") || previousServices.contains("ScreenCapture")));
        settingsOwned = true;
        assertTrue(preferences.edit().putBoolean(AppModeManager.KEY_ARMED, true)
                .putStringSet(AppModeManager.KEY_INCLUDED_PACKAGES, Collections.singleton(TARGET))
                .putBoolean(AppModeManager.KEY_ALL_APPS, false).putBoolean(FeatureModuleManager.KEY_CENSOR_ENABLED, true)
                .putString(SettingsRepository.KEY_CAPTURE_METHOD, CaptureMethod.APP_MODE.preferenceValue())
                .putString(SettingsRepository.KEY_DETECTION_PRESET, "low").putStringSet(SettingsRepository.KEY_ENABLED_CATEGORIES, Collections.singleton("face"))
                .putBoolean(SettingsRepository.KEY_TEXT_SMUT_ENABLED, false).putString(SettingsRepository.KEY_CENSOR_TYPE, "box")
                .putInt(SettingsRepository.KEY_CENSOR_INTENSITY, 100).putFloat(SettingsRepository.KEY_CENSOR_SIZE_PADDING, 0)
                .putBoolean(SettingsRepository.KEY_SHOW_BORDER, false).putBoolean(SettingsRepository.KEY_SHOW_TEXT, false)
                .putBoolean(SettingsRepository.KEY_REVERSE_MODE, false).putString(SettingsRepository.paletteKey(CensorAppearance.Type.BOX, 1), "#FFFF0080")
                .putBoolean(AppTimerManager.KEY_PER_APP_ENABLED, false).putBoolean(AppTimerManager.KEY_TOTAL_ENABLED, false)
                .putBoolean(HardcoreModeManager.KEY_REQUESTED, false).putBoolean(FeatureModuleManager.KEY_SUBLIMINAL_ENABLED, false)
                .putBoolean(com.subhub.app.popup.PopupStormSettings.K_ENABLED, false).commit());
        assertTrue(qualification.edit().putBoolean("enabled", true).commit());
        ProductCensorQualification.fixtureModels(this::model);
        ProductCensorQualification.fixtureSelectionObserved(() -> {
            assertNotEquals(Looper.getMainLooper(), Looper.myLooper()); readCount.incrementAndGet();
            Hold held = readHold.getAndSet(null); if (held != null) held.run();
        });
        ProductCensorQualification.fixtureObserver(new CensorProductSession.Observer() {
            @Override public void trace(CensorEngine.Trace value) { traces.add(value); }
            @Override public void prepared(long request, PixelFrame.Kind kind, long nanos, int bytes) { preparations.add(kind); }
            @Override public void batchAccepted(SceneEngine.Batch batch) { batches.add(batch); }
            @Override public void posted(SnapshotCensorSurface.Post receipt) { posts.add(receipt); }
            @Override public void modelUnavailable(SceneEngine.Lane lane) { unavailable.add(lane); }
            @Override public void sessionFailure(Throwable fault) { faults.add(fault); }
            @Override public void foreground(AccessibilityWindowReader.Window window) {
                if (window != null && TARGET.equals(window.packageName) && stopForeground.compareAndSet(true, false)) {
                    ProductCensorQualification.current().stop(); foregroundStopped.countDown();
                }
            }
        });
        target(false);
    }
    @After public void closeActualOwnerBeforeRestoringExactGuestState() throws Exception {
        if (!settingsOwned) return;
        for (Hold owned : ownedHolds) owned.release.countDown();
        Hold held = readHold.getAndSet(null); if (held != null) held.release.countDown();
        held = modelHold.getAndSet(null); if (held != null) held.release.countDown();
        try {
            CensorProductSession current = session == null ? ProductCensorQualification.current() : session;
            if (current != null) {
                main(current::close); await("Product lookup/controller/Surface actual managed drain", current::managedResourcesDrained);
                assertFalse("Native context never closes over executing inference", earlyClose.get());
                ProductCensorQualification.disconnected(current);
            }
            writeMetrics();
        } finally {
            secure(previousServices, previousEnabled);
            await("Actual production service unbinding", () -> !ScreenshotAccessibilityService.isRunning());
            target(true);
            restore(preferences, savedPreferences); restore(qualification, savedQualification);
            ProductCensorQualification.fixtureModels(null); ProductCensorQualification.fixtureSelectionObserved(() -> { });
            ProductCensorQualification.fixtureObserver(null);
        }
        assertEquals(savedPreferences, preferences.getAll()); assertEquals(savedQualification, qualification.getAll());
        assertEquals(previousServices, Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES));
        assertEquals(previousEnabled, Settings.Secure.getInt(context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0));
    }

    @Test public void realProductionServiceUsesOneReplacementAndComposedSurfaceWithoutLegacyWorkers() throws Exception {
        connect(); awaitPaint(); assertBinding(); composed("product-covered", COVER);
        assertTrue(preparations.contains(PixelFrame.Kind.ACCESSIBILITY_WINDOW)); assertEquals(1, models.get());
        assertFalse(batches.isEmpty());
        assertTrue(batches.stream().allMatch(b -> b.source.kind == SceneEngine.SourceKind.IMAGE && b.source.windowId == -1
                && b.source.geometry == session.controller().activeScope().geometry));
        assertTrue(posts.stream().anyMatch(p -> p.acceptedPaint != null && !p.mainThread));
        assertSpacing(); assertTrue(faults.isEmpty());
    }
    @Test public void appearanceAndArtworkRevisionPreserveScopeGeometryAndNativeModel() throws Exception {
        connect(); awaitPaint(); CensorEngine.ActiveScope original = session.controller().activeScope(); int initialized = models.get();
        long beforePost = session.controller().surface().postCount();
        assertTrue(preferences.edit().putString(SettingsRepository.paletteKey(CensorAppearance.Type.BOX, 1), "#FF00A0FF")
                .putLong(com.subhub.app.capture.CustomImageManager.REVISION_KEY, 1).commit());
        composed("product-style-covered", 0xff00a0ff);
        assertTrue(session.controller().surface().postCount() > beforePost);
        assertTrue(original.same(session.controller().activeScope())); assertEquals(initialized, models.get());
        assertTrue(preferences.edit().putString(SettingsRepository.KEY_CENSOR_TYPE, "pixelate").commit());
        await("Same scope sampled-source demand", () -> {
            try (CensorEngine<PixelFrame>.RenderView view = session.controller().host().acquireRenderView()) {
                return view != null && view.canvasPixels() != null;
            }
        });
        assertTrue(original.same(session.controller().activeScope())); assertEquals(initialized, models.get());
        try (CensorEngine<PixelFrame>.RenderView view = session.controller().host().acquireRenderView()) {
            assertNotNull(view); assertNotNull(view.canvasPixels()); PixelFrame pixels = view.canvasPixels();
            assertEquals(PixelFrame.Kind.ACCESSIBILITY_WINDOW, pixels.kind);
            assertEquals(ProductTargetFixtureActivity.UNDERLYING, pixels.bitmap.getPixel(pixels.bitmap.getWidth() / 2, pixels.bitmap.getHeight() / 2));
            save(pixels.bitmap, "product-uncensored-window-source");
        }
        assertSpacing();
    }
    @Test public void disarmWithdrawsAuthorityWhileActualWindowResultIsHeld() throws Exception {
        connect(); awaitPaint(); Hold hold = hold(); readHold.set(hold); main(session::request);
        assertTrue(hold.entered.await(8, TimeUnit.SECONDS));
        try {
            assertTrue(preferences.edit().putBoolean(AppModeManager.KEY_ARMED, false).commit());
            await("Disarm immediately stops held product reconciliation", () -> !session.isActive());
            main(() -> { assertFalse(session.isActive()); assertNull(session.controller().activeScope()); assertFalse(tapsCenter()); });
        } finally { hold.release.countDown(); }
        barrier(); assertFalse(session.isActive());
        assertTrue(preferences.edit().putBoolean(AppModeManager.KEY_ARMED, true).commit()); awaitPaint();
        assertEquals(1, models.get()); assertSpacing();
    }
    @Test public void staleLabAbaCannotUseOldNormalScopeOrPersistArmingAndCapture() throws Exception {
        connect(); awaitPaint();
        assertTrue(preferences.edit().putBoolean(AppModeManager.KEY_ARMED, false)
                .putBoolean(FeatureModuleManager.KEY_CENSOR_ENABLED, false)
                .putString(SettingsRepository.KEY_CAPTURE_METHOD, CaptureMethod.SCREEN_RECORDING.preferenceValue()).commit());
        await("Ordinary settings disarmed", () -> !session.isActive());
        Map<String, ?> configuration = copy(preferences.getAll());
        Hold hold = hold(); readHold.set(hold); main(session::request); assertTrue(hold.entered.await(8, TimeUnit.SECONDS));
        CensorLabCensorSession.Lease first = CensorLabCensorSession.acquire(); CensorLabCensorSession.Stamp old = CensorLabCensorSession.stamp();
        first.close(); CensorLabCensorSession.Lease second = CensorLabCensorSession.acquire();
        try {
            assertFalse(old.isCurrent()); hold.release.countDown();
            await("Current recording owns replacement scope", () -> session.isActive() && session.controller().activeScope() != null
                    && session.controller().activeScope().protectionScope.origin == ProtectionScope.Origin.LAB);
            await("Current LAB source accepted", () -> batches.stream().anyMatch(b -> b.source.protectionScope.origin == ProtectionScope.Origin.LAB));
            assertFalse(tapsCenter()); assertEquals(configuration, preferences.getAll());
        } finally { hold.release.countDown(); second.close(); }
        await("Lab exit respects original disarmed method/module", () -> !session.isActive());
        assertEquals(configuration, preferences.getAll()); assertSpacing();
    }
    @Test public void lateInferenceAfterDisarmCannotDrawAndNativeContextRemainsOwnedUntilReturn() throws Exception {
        Hold hold = hold(); modelHold.set(hold); connect(); assertTrue(hold.entered.await(8, TimeUnit.SECONDS));
        CensorEngine.ActiveScope original = session.controller().activeScope();
        try {
            assertTrue(preferences.edit().putBoolean(FeatureModuleManager.KEY_CENSOR_ENABLED, false).commit());
            await("Disabled while real pixel model input held", () -> !session.isActive());
            main(() -> { assertFalse(session.isActive()); assertNull(session.controller().activeScope()); });
            assertEquals(0, closes.get()); assertFalse(earlyClose.get());
            assertTrue(posts.stream().noneMatch(p -> p.acceptedPaint != null));
        } finally { hold.release.countDown(); }
        await("Held inference actually returned", () -> !inferRunning.get());
        assertTrue(batches.stream().noneMatch(b -> b.source.epoch == original.epoch)); assertFalse(tapsCenter());
        assertTrue(preferences.edit().putBoolean(FeatureModuleManager.KEY_CENSOR_ENABLED, true).commit()); awaitPaint();
        assertNotEquals(original.epoch, session.controller().activeScope().epoch); assertEquals(1, models.get()); assertSpacing();
    }
    @Test public void ownActivityAndExcludedSelectionStopThenRecoverUsingSameOwner() throws Exception {
        connect(); awaitPaint(); CensorProductSession owner = session;
        try (ActivityScenario<BoundCensorFixtureActivity> own = ActivityScenario.launch(BoundCensorFixtureActivity.class)) {
            await("Own Activity excluded from recognition", () -> !session.isActive()); assertFalse(tapsCenter());
        }
        target(false); awaitPaint(); assertSame(owner, ProductCensorQualification.current()); assertEquals(1, models.get());
        assertTrue(preferences.edit().putStringSet(AppModeManager.KEY_INCLUDED_PACKAGES, Collections.emptySet()).commit());
        await("Removed app selection stops", () -> !session.isActive()); assertFalse(tapsCenter());
        assertTrue(preferences.edit().putStringSet(AppModeManager.KEY_INCLUDED_PACKAGES, Collections.singleton(TARGET)).commit());
        awaitPaint(); assertSame(owner, ProductCensorQualification.current()); assertEquals(1, models.get()); assertSpacing();
    }
    @Test public void unchangedUnavailableFastModelDoesNotRetryOnTickOrStyleChanges() throws Exception {
        ProductCensorQualification.fixtureModels((app, lane, config, partition, fallback) -> {
            assertNotEquals(Looper.getMainLooper(), Looper.myLooper()); models.incrementAndGet(); throw new java.io.IOException("Controlled ordinary initialization failure");
        });
        connect(); await("FAST unavailable status", () -> unavailable.contains(SceneEngine.Lane.FAST));
        assertFalse(session.isActive()); int reads = readCount.get();
        assertTrue(preferences.edit().putInt(SettingsRepository.KEY_CENSOR_INTENSITY, 70).commit());
        await("Later periodic lookup is completed", () -> readCount.get() >= reads + 3);
        barrier(); assertEquals(1, models.get()); assertFalse(session.isActive());
        assertTrue(posts.stream().noneMatch(p -> p.acceptedPaint != null)); assertTrue(faults.isEmpty());
        assertTrue(preferences.edit().putInt(SettingsRepository.KEY_CONFIDENCE, 31).commit());
        await("Changed inference config may retry once", () -> models.get() == 2); barrier(); assertEquals(2, models.get());
    }
    @Test public void reentrantProductStopDuringForegroundCommitCannotBeUndoneByThatCommit() throws Exception {
        stopForeground.set(true); connect(); assertTrue(foregroundStopped.await(8, TimeUnit.SECONDS));
        barrier(); assertFalse(session.isActive()); assertNull(session.controller().activeScope()); assertEquals(0, models.get());
        assertTrue(posts.stream().noneMatch(p -> p.acceptedPaint != null));
        main(session::request); awaitPaint(); assertEquals(1, models.get());
    }
    @Test public void stoppedQualityInitializationCannotReportUnavailableOrResumeProduct() throws Exception {
        Hold hold = hold();
        ProductCensorQualification.fixtureModels((app, lane, config, partition, fallback) -> {
            if (lane == SceneEngine.Lane.QUALITY) {
                assertNotEquals(Looper.getMainLooper(), Looper.myLooper()); models.incrementAndGet(); hold.run();
                throw new java.io.IOException("Controlled stopped QUALITY initialization");
            }
            return model(app, lane, config, partition, fallback);
        });
        assertTrue(preferences.edit().putString(SettingsRepository.KEY_DETECTION_PRESET, "medium").commit());
        connect(); awaitPaint(); assertTrue(hold.entered.await(8, TimeUnit.SECONDS));
        try {
            assertTrue(preferences.edit().putBoolean(AppModeManager.KEY_ARMED, false).commit());
            await("Product stopped with QUALITY context initialization held", () -> !session.isActive());
        } finally { hold.release.countDown(); }
        java.lang.reflect.Field pending = AccessibilityCensorHost.class.getDeclaredField("qualityInitializing"); pending.setAccessible(true);
        AtomicBoolean settled = new AtomicBoolean();
        await("Actual stopped initialization terminal callback", () -> {
            main(() -> {
                try { settled.set(!pending.getBoolean(session.controller().host())); }
                catch (IllegalAccessException error) { throw new AssertionError(error); }
            });
            return settled.get();
        });
        barrier(); assertFalse(unavailable.contains(SceneEngine.Lane.QUALITY)); assertFalse(session.isActive());
        assertTrue(faults.isEmpty()); assertSpacing();
    }
    @Test public void closureOfHeldWindowLookupIsSeparateFromControllerClosure() throws Exception {
        connect(); awaitPaint(); Hold hold = hold(); readHold.set(hold); main(session::request);
        assertTrue(hold.entered.await(8, TimeUnit.SECONDS));
        try {
            main(session::close); assertFalse(session.managedResourcesDrained());
            assertThrows(IllegalStateException.class, () -> ProductCensorQualification.fixtureModels(this::model));
            assertFalse(tapsCenter());
        } finally { hold.release.countDown(); }
        await("Actual lookup terminal delivery plus controller/Surface close", session::managedResourcesDrained);
        assertSame(session, ProductCensorQualification.current()); assertEquals(1, closes.get()); assertFalse(earlyClose.get());
    }

    private VisualDetectorWorker model(Context app, SceneEngine.Lane lane, DetectorConfig config, long partition, String fallback) {
        assertNotEquals(Looper.getMainLooper(), Looper.myLooper()); models.incrementAndGet();
        return new VisualDetectorWorker(lane, config, partition, new VisualDetectorWorker.Model() {
            public String provider() { return "CPU"; }
            public VisualDetectorWorker.ModelOutput infer(Bitmap bitmap, int width, int height, int inputWidth, int inputHeight,
                    BooleanSupplier cancelled, Consumer<DetectionEngine.RunCancellation> binding) {
                inferRunning.set(true);
                try {
                    Hold held = modelHold.getAndSet(null); if (held != null) held.run();
                    assertFalse("Actual model input remains live", bitmap.isRecycled());
                    Detection mask = new Detection("FACE_FEMALE", "face", .9f, new BBox(width / 3, height / 3, width / 3, height / 3), false, true);
                    return new VisualDetectorWorker.ModelOutput(Collections.singletonList(mask), true, cancelled.getAsBoolean(), new CensorEngine.WorkTiming(0, 0, 0));
                } finally { inferRunning.set(false); }
            }
            public void close() { if (inferRunning.get()) earlyClose.set(true); assertNotEquals(Looper.getMainLooper(), Looper.myLooper()); closes.incrementAndGet(); }
        });
    }
    private void connect() throws Exception {
        ComponentName component = new ComponentName(context, ScreenshotAccessibilityService.class);
        secure(previousServices == null || previousServices.isEmpty() ? component.flattenToString() : previousServices + ":" + component.flattenToString(), 1);
        await("Actual production service connected", () -> ProductCensorQualification.current() != null && ScreenshotAccessibilityService.isRunning());
        session = ProductCensorQualification.current(); assertBinding();
    }
    private void assertBinding() {
        ScreenshotAccessibilityService actual = (ScreenshotAccessibilityService) session.service();
        assertEquals(Process.myPid(), actual.qualificationBoundPid());
        assertEquals(ScreenshotAccessibilityService.class.getName(), actual.getServiceInfo().getResolveInfo().serviceInfo.name);
        assertTrue(actual.legacyCensorRuntimeAbsentForQualification());
        assertTrue(actual.getServiceInfo().getCanRetrieveWindowContent());
        assertTrue((actual.getServiceInfo().getCapabilities() & android.accessibilityservice.AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT) != 0);
        assertTrue(Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                .contains(new ComponentName(context, ScreenshotAccessibilityService.class).flattenToString()));
    }
    private void awaitPaint() throws Exception {
        await("Current source accepted and native Surface paint", () -> session.isActive() && session.window() != null && TARGET.equals(session.window().packageName)
                && posts.stream().anyMatch(p -> p.acceptedPaint != null && p.acceptedPaint.scope.same(session.controller().activeScope())));
    }
    private boolean tapsCenter() {
        android.util.DisplayMetrics display = context.getResources().getDisplayMetrics();
        return CensorTapTracker.shared().matchesPoint(display.widthPixels / 2f, display.heightPixels / 2f,
                display.widthPixels, display.heightPixels, SystemClock.uptimeMillis());
    }
    private void target(boolean finish) {
        context.startActivity(new Intent().setComponent(new ComponentName(TARGET, ProductTargetFixtureActivity.class.getName()))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION).putExtra("finish", finish));
    }
    private void secure(String services, int enabled) {
        ui.adoptShellPermissionIdentity(Manifest.permission.WRITE_SECURE_SETTINGS);
        try {
            assertTrue(Settings.Secure.putString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, services));
            assertTrue(Settings.Secure.putInt(context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, enabled));
        } finally { ui.dropShellPermissionIdentity(); }
    }
    private static final class Hold {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        void run() {
            entered.countDown();
            try { if (!release.await(12, TimeUnit.SECONDS)) throw new IllegalStateException("Native held-stage deadline"); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
        }
    }
    private Hold hold() { Hold value = new Hold(); ownedHolds.add(value); return value; }
    private static void main(Runnable action) {
        AtomicReference<Throwable> failed = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> { try { action.run(); } catch (Throwable error) { failed.set(error); } });
        if (failed.get() instanceof RuntimeException) throw (RuntimeException) failed.get();
        if (failed.get() instanceof Error) throw (Error) failed.get();
        if (failed.get() != null) throw new AssertionError(failed.get());
    }
    private void barrier() { main(() -> { }); }
    private static void await(String label, BooleanSupplier condition) throws Exception {
        long until = SystemClock.uptimeMillis() + 12000;
        while (!condition.getAsBoolean()) { assertTrue(label, SystemClock.uptimeMillis() < until); Thread.sleep(10); }
    }
    private void assertSpacing() {
        long last = -1; for (CensorEngine.Trace trace : traces) if (trace.stage == CensorEngine.Stage.CAPTURE_DISPATCHED) {
            if (last >= 0) assertTrue("Actual API dispatch interval", trace.atMillis - last >= 334); last = trace.atMillis;
        }
    }
    private void composed(String label, int expected) throws Exception {
        Bitmap latest = null; long until = SystemClock.uptimeMillis() + 12000; int consecutive = 0;
        try {
            while (SystemClock.uptimeMillis() < until) {
                if (latest != null) latest.recycle(); latest = ui.takeScreenshot();
                if (latest == null) continue;
                int color = latest.getPixel(latest.getWidth() / 2, latest.getHeight() / 2);
                if (Math.abs(Color.red(color) - Color.red(expected)) <= 2 && Math.abs(Color.green(color) - Color.green(expected)) <= 2
                        && Math.abs(Color.blue(color) - Color.blue(expected)) <= 2) consecutive++; else consecutive = 0;
                if (consecutive >= 2) { save(latest, label); return; }
                Thread.sleep(16);
            }
            if (latest != null) save(latest, label + "-failed-pixels");
            fail("Settled native compositor pixels " + label);
        } finally { if (latest != null) latest.recycle(); }
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
    private void writeMetrics() throws Exception {
        JSONArray rows = new JSONArray(); for (CensorEngine.Trace t : traces) rows.put(new JSONObject().put("stage", t.stage.name())
                .put("epoch", t.epoch).put("source", t.source).put("lane", t.lane == null ? "NONE" : t.lane.name())
                .put("atMillis", t.atMillis).put("queueMillis", t.queueMillis).put("pixelAgeMillis", t.pixelAgeMillis));
        JSONArray submitted = new JSONArray(); for (SnapshotCensorSurface.Post p : posts) {
            JSONObject row = new JSONObject().put("endpointGeneration", p.endpointGeneration).put("postSerial", p.postSerial)
                    .put("recordedAtMillis", p.recordedAtMillis).put("postReturnedAtMillis", p.postReturnedAtMillis)
                    .put("uiAdmittedAtMillis", p.uiAdmittedAtMillis).put("compositorAtMillis", p.compositorAtMillis).put("mainThread", p.mainThread)
                    .put("acceptedPaint", p.acceptedPaint != null);
            if (p.acceptedPaint != null) {
                JSONArray identities = new JSONArray();
                for (SceneEngine.Mask member : p.acceptedPaint.members) identities.put(member.identity);
                assertEquals(p.acceptedPaint.members.size(), identities.length());
                row.put("epoch", p.acceptedPaint.scope.epoch).put("geometry", p.acceptedPaint.scope.geometry)
                        .put("memberIdentities", identities);
            }
            submitted.put(row);
        }
        JSONObject metrics = new JSONObject().put("schemaVersion", 2).put("actualProductionService", session != null)
                .put("sameInstrumentationPid", session != null && ((ScreenshotAccessibilityService) session.service()).qualificationBoundPid() == Process.myPid())
                .put("pid", Process.myPid()).put("rawTraceCount", traces.size()).put("traces", rows).put("posts", submitted)
                .put("rawPostCount", posts.size()).put("models", models.get()).put("closes", closes.get()).put("managedDrain", session != null && session.managedResourcesDrained())
                .put("legacyCensorRuntimeAbsent", session != null && ((ScreenshotAccessibilityService) session.service()).legacyCensorRuntimeAbsentForQualification())
                .put("controlledModelsOnly", true).put("physicalMotionAcceptance", false).put("pixelPerformanceAcceptance", false);
        try (FileOutputStream stream = new FileOutputStream(new File(directory(), "native-surface-metrics-" + System.nanoTime() + ".json"))) {
            stream.write(metrics.toString().getBytes(StandardCharsets.UTF_8));
        }
    }
    private static Map<String, ?> copy(Map<String, ?> values) {
        Map<String, Object> result = new HashMap<>(); for (Map.Entry<String, ?> e : values.entrySet())
            result.put(e.getKey(), e.getValue() instanceof Set<?> ? new HashSet<>((Set<?>) e.getValue()) : e.getValue()); return result;
    }
    @SuppressWarnings("unchecked") private static void restore(SharedPreferences preferences, Map<String, ?> saved) {
        SharedPreferences.Editor edit = preferences.edit().clear();
        for (Map.Entry<String, ?> e : saved.entrySet()) {
            Object v = e.getValue(); String k = e.getKey();
            if (v instanceof String) edit.putString(k, (String) v); else if (v instanceof Set<?>) edit.putStringSet(k, (Set<String>) v);
            else if (v instanceof Boolean) edit.putBoolean(k, (Boolean) v); else if (v instanceof Integer) edit.putInt(k, (Integer) v);
            else if (v instanceof Long) edit.putLong(k, (Long) v); else if (v instanceof Float) edit.putFloat(k, (Float) v);
            else throw new IllegalStateException("Unsupported fixture preference type");
        }
        assertTrue(edit.commit());
    }
}
