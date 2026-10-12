package com.subhub.app.censor.android;

import static org.junit.Assert.*;

import android.Manifest;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
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
import com.subhub.app.penance.PenanceManager;
import com.subhub.app.penance.PenanceInfraction;
import com.subhub.app.penance.HardcoreAutoPayManager;
import com.subhub.app.penance.PayPalCredentialStore;
import com.subhub.app.stats.StatsRepository;
import com.subhub.app.stats.AchievementManager;
import com.subhub.app.security.HardcoreModeManager;
import com.subhub.app.service.ScreenshotAccessibilityService;
import com.subhub.app.settings.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Real service/source/accounting; explicit storage-result controls are not real disk failures or Pixel acceptance. */
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
    private final Map<String, Map<String, ?>> savedStores = new LinkedHashMap<>();
    private static final List<String> OWNED_STORES = Arrays.asList(StatsRepository.PREFS_NAME,
            PenanceManager.PREFS_NAME, "betablocker_achievements", "subhub_hardcore_auto_pay", PayPalCredentialStore.PREFS_NAME);
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
    private final List<CensorEngine.EvidenceReceipt> evidence = new CopyOnWriteArrayList<>();
    private final List<CensorProductSession.AccountingResult> accountResults = new CopyOnWriteArrayList<>();
    private final AtomicReference<Runnable> afterCommittedCount = new AtomicReference<>();
    private final AtomicInteger storageFaultCalls = new AtomicInteger();
    private boolean controlledStorageResult;
    private boolean explicitNativeDemand;

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
        // Save only in test memory. No provider authorization or guest wallet may participate.
        for (String name : OWNED_STORES) savedStores.put(name, copy(context.getSharedPreferences(name, Context.MODE_PRIVATE).getAll()));
        new HardcoreAutoPayManager(context).disable();
        new StatsRepository(context).endSession();
        for (String name : OWNED_STORES) assertTrue(context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit());
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
        ProductCensorQualification.fixtureStatistics(StatsRepository::new);
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
            @Override public void evidenceAccepted(CensorEngine.EvidenceReceipt receipt) { evidence.add(receipt); }
            @Override public void accounting(CensorProductSession.AccountingResult result) {
                assertNotEquals("No accounting disk work on main", Looper.getMainLooper(), Looper.myLooper());
                accountResults.add(result);
                if (result.phase == CensorProductSession.AccountingPhase.COUNT && result.status == CensorProductSession.AccountingStatus.COMMITTED) {
                    Runnable action = afterCommittedCount.getAndSet(null);
                    if (action != null) { try { action.run(); } catch (Throwable failure) { faults.add(failure); } }
                }
            }
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
            new StatsRepository(context).endSession();
            for (Map.Entry<String, Map<String, ?>> saved : savedStores.entrySet())
                restore(context.getSharedPreferences(saved.getKey(), Context.MODE_PRIVATE), saved.getValue());
            new StatsRepository(context).sessionStamp();
            restore(preferences, savedPreferences); restore(qualification, savedQualification);
            ProductCensorQualification.fixtureModels(null); ProductCensorQualification.fixtureSelectionObserved(() -> { });
            ProductCensorQualification.fixtureObserver(null);
            ProductCensorQualification.fixtureStatistics(StatsRepository::new);
        }
        assertEquals(savedPreferences, preferences.getAll()); assertEquals(savedQualification, qualification.getAll());
        for (Map.Entry<String, Map<String, ?>> saved : savedStores.entrySet())
            assertEquals(saved.getValue(), context.getSharedPreferences(saved.getKey(), Context.MODE_PRIVATE).getAll());
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

    @Test public void nativeSourceEvidenceCountsOnceAndFeedsActualLedgerAndAchievementSnapshot() throws Exception {
        nativeCounting("porn", 1, 500); connect(); awaitCount(1); awaitLedger(1, 7);
        await("First block achievement", () -> new AchievementManager(context).isUnlocked("first_block"));
        CensorEngine.ActiveScope scope = session.controller().activeScope(); int before = evidence.size();
        assertTrue(preferences.edit().putInt(SettingsRepository.KEY_CENSOR_INTENSITY, 80).commit());
        awaitNative("Fresh native reads after style change", () -> evidence.size() >= before + 6);
        assertTrue(scope.same(session.controller().activeScope())); assertEquals(1, new StatsRepository(context).load().getTotalBlocks());
        assertEquals(1, new PenanceManager(context).snapshot(System.currentTimeMillis()).getEvents().size());
        assertEquals(0, new StatsRepository(context).load().getAllCategoryCensors());
        CensorEngine.EvidenceReceipt receipt = evidence.stream().filter(r -> r.subjects.stream().anyMatch(s -> s.confirmedForConsumption)).findFirst().get();
        assertEquals(SceneEngine.SourceKind.NATIVE_TEXT, receipt.source.kind);
        assertEquals(5, receipt.subjects.get(0).witnesses.size()); assertEquals(0, models.get());
        CensorEngine.EvidenceClaim replay = session.controller().claimEvidence(receipt, StatsRepository.currentSessionStamp());
        assertTrue(replay == null || replay.subjects.isEmpty());
        assertTrue(faults.isEmpty()); assertBinding();
    }
    @Test public void statisticsSessionReplacementCannotRebindHeldSelectionOrOldWitnesses() throws Exception {
        nativeCounting("porn", 1, 500); connect(); awaitCount(1); awaitLedger(1, 7);
        StatsRepository stats = new StatsRepository(context); StatsRepository.SessionStamp old = stats.sessionStamp();
        CensorEngine.ActiveScope oldScope = session.controller().activeScope(); Hold hold = hold(); readHold.set(hold); main(session::request);
        assertTrue(hold.entered.await(8, TimeUnit.SECONDS));
        stats.endSession(); stats.startSession(); StatsRepository.SessionStamp fresh = stats.sessionStamp();
        assertNotSame(old, fresh); assertFalse(old.isCurrent()); assertEquals(0, stats.load().getCurrentSessionBlocks());
        hold.release.countDown();
        await("New session requires a new owner scope", () -> session.controller().activeScope() != null
                && !oldScope.same(session.controller().activeScope()));
        awaitCount(2); awaitLedger(2, 7); assertEquals(1, stats.load().getCurrentSessionBlocks());
        assertEquals(2, new PenanceManager(context).snapshot(System.currentTimeMillis()).getEvents().size()); assertTrue(faults.isEmpty());
    }
    @Test public void sessionReplacementAfterCountCannotChargeOrUnlockForTheNewSession() throws Exception {
        nativeCounting("porn", 1, 500); StatsRepository stats = new StatsRepository(context);
        afterCommittedCount.set(() -> { stats.endSession(); stats.startSession(); main(session::stop); });
        connect(); awaitCount(1);
        await("Ledger phase rejects replaced source session", () -> accountResults.stream().anyMatch(r -> r.phase == CensorProductSession.AccountingPhase.LEDGER
                && r.status == CensorProductSession.AccountingStatus.REJECTED && r.count == 1));
        assertEquals(0, stats.load().getCurrentSessionBlocks());
        assertTrue(new PenanceManager(context).snapshot(System.currentTimeMillis()).getEvents().isEmpty());
        assertFalse(new AchievementManager(context).isUnlocked("first_block")); assertTrue(faults.isEmpty());
    }
    @Test public void acknowledgedZeroCentBatchProgressAndCapsAreNeverReplayed() throws Exception {
        nativeCounting("porn one", 2, 7); connect(); awaitCount(1); awaitLedger(1, 0);
        assertEquals(1, context.getSharedPreferences(PenanceManager.PREFS_NAME, Context.MODE_PRIVATE).getInt("detection_batch_remainder", -1));
        literalTarget("porn two"); awaitCount(2); awaitLedger(2, 7);
        literalTarget("porn three"); awaitCount(3); awaitLedger(3, 0);
        literalTarget("porn four"); awaitCount(4); awaitLedger(4, 0);
        assertEquals(0, context.getSharedPreferences(PenanceManager.PREFS_NAME, Context.MODE_PRIVATE).getInt("detection_batch_remainder", -1));
        assertEquals(1, new PenanceManager(context).snapshot(System.currentTimeMillis()).getEvents().size());
        int before = evidence.size(); awaitNative("Later native redraw is not another charge", () -> evidence.size() >= before + 5);
        assertEquals(4, new StatsRepository(context).load().getTotalBlocks()); assertTrue(faults.isEmpty());
    }
    @Test public void labOriginAndInactiveAbaNeverBecomeNormalCounts() throws Exception {
        nativeCounting("porn", 1, 500);
        CensorLabCensorSession.Lease lab = CensorLabCensorSession.acquire();
        try {
            connect(); awaitNative("Five real Lab native observations", () -> evidence.stream().anyMatch(r -> r.subjects.stream().anyMatch(s -> s.confirmedForConsumption)));
            assertEquals(0, new StatsRepository(context).load().getTotalBlocks()); assertTrue(accountResults.isEmpty());
        } finally { lab.close(); }
        awaitCount(1); awaitLedger(1, 7);
        assertTrue(accountResults.stream().allMatch(r -> r.receipt == null || r.receipt.scope.protectionScope.origin == ProtectionScope.Origin.NORMAL));
        assertTrue(faults.isEmpty());
    }
    @Test public void armedPreferenceWithoutAnActualStatisticsSessionCannotConsumeEvidence() throws Exception {
        nativeOnly("porn"); connect();
        awaitNative("Five native witnesses without a statistics session", () -> evidence.stream().anyMatch(r -> r.subjects.stream().anyMatch(s -> s.confirmedForConsumption)));
        assertNull(StatsRepository.currentSessionStamp()); assertEquals(0, new StatsRepository(context).load().getTotalBlocks());
        assertTrue(accountResults.isEmpty()); assertTrue(new PenanceManager(context).snapshot(System.currentTimeMillis()).getEvents().isEmpty());
    }
    @Test public void failedCountResultBeforeWriteCannotRetryOrReachDownstreamAndLeavesCensorAlive() throws Exception {
        storageControl(false, false, null); nativeCounting("porn", 1, 500); connect(); awaitCountFailure(CensorProductSession.AccountingStatus.UNACKNOWLEDGED);
        int before = evidence.size(); awaitNative("Presentation/acquisition survives accounting quarantine", () -> evidence.size() >= before + 6);
        assertTrue(session.isActive()); assertEquals(1, storageFaultCalls.get()); assertEquals(0, new StatsRepository(context).load().getTotalBlocks());
        assertNoDownstream(); assertTrue(faults.isEmpty());
    }
    @Test public void falseCountResultAfterAppliedWriteKeepsObservedCounterAndHistoryCoherent() throws Exception {
        uncertainAppliedCount(false);
    }
    @Test public void thrownCountResultAfterAppliedWriteCannotReplayOrLoseObservedHistory() throws Exception {
        uncertainAppliedCount(true);
    }
    @Test public void closeAndLabAdmissionDoNotWaitForAHeldCountStoreButDrainDoes() throws Exception {
        Hold disk = hold(); storageControl(true, false, disk); nativeCounting("porn", 1, 500); connect();
        awaitNative("Controlled count store reached after five actual native reads", () -> disk.entered.getCount() == 0);
        main(() -> { try (CensorLabCensorSession.Lease lab = CensorLabCensorSession.acquire()) { assertTrue(CensorLabCensorSession.isActive()); } session.close(); });
        assertFalse(session.managedResourcesDrained()); disk.release.countDown();
        await("Actual count action terminal after close", session::managedResourcesDrained);
        awaitCountFailure(CensorProductSession.AccountingStatus.UNACKNOWLEDGED);
        assertEquals(1, new StatsRepository(context).load().getTotalBlocks()); assertNoDownstream(); assertTrue(faults.isEmpty());
    }
    @Test public void mainThreadCountCommitIsRejectedBeforeAnySourceClaim() throws Exception {
        nativeCounting("porn", 1, 500); connect(); StatsRepository stats = new StatsRepository(context); AtomicInteger claims = new AtomicInteger();
        StatsRepository.SessionStamp stamp = stats.sessionStamp();
        main(() -> assertThrows(IllegalStateException.class, () -> stats.recordEvidence(stamp,
                () -> { claims.incrementAndGet(); return null; }, Collections.emptySet())));
        assertEquals(0, claims.get()); assertTrue(faults.isEmpty());
    }

    @Test public void sessionStampSurvivesReadsButRejectsRestorationWithTheSameReportingTimestamp() throws Exception {
        nativeCounting("porn", 1, 500); StatsRepository stats = new StatsRepository(context);
        StatsRepository.SessionStamp old = stats.sessionStamp();
        SharedPreferences counters = context.getSharedPreferences(StatsRepository.PREFS_NAME, Context.MODE_PRIVATE);
        long started = counters.getLong("active_session_start_ms", 0); assertTrue(started > 0);
        for (int i = 0; i < 5; i++) {
            assertSame(old, new StatsRepository(context).sessionStamp()); stats.load(); stats.startSession(); assertSame(old, stats.sessionStamp());
        }
        stats.endSession(); assertNull(StatsRepository.currentSessionStamp());
        assertTrue(counters.edit().putLong("active_session_start_ms", started).putInt("active_session_blocks", 0).commit());
        StatsRepository.SessionStamp restored = stats.sessionStamp();
        assertNotSame(old, restored); assertFalse(old.isCurrent()); assertTrue(restored.isCurrent());
        assertEquals(started, counters.getLong("active_session_start_ms", 0));
        PenanceManager.SourceDetectionResult rejected = new PenanceManager(context).recordSourceDetectionResult(1, System.currentTimeMillis(), old);
        assertEquals(PenanceManager.SourceDetectionStatus.REJECTED, rejected.status); assertEquals(0, rejected.chargedCents);
        assertEquals(0, context.getSharedPreferences(PenanceManager.PREFS_NAME, Context.MODE_PRIVATE).getInt("detection_batch_remainder", 0));
        connect(); awaitCount(1); awaitLedger(1, 7); assertTrue(faults.isEmpty());
    }
    @Test public void allCategoryMilestoneUsesEnabledDetectorConfigurationNotNativeSubjectLabel() throws Exception {
        nativeCounting("porn", 1, 500);
        Set<String> all = new LinkedHashSet<>(Arrays.asList("genitals_female", "genitals_male", "breasts", "buttocks", "anus",
                "face", "belly", "male_chest", "feet", "armpits", "genitals_covered", "breasts_covered", "buttocks_covered",
                "anus_covered", "belly_covered", "feet_covered", "armpits_covered"));
        assertTrue(preferences.edit().putStringSet(SettingsRepository.KEY_ENABLED_CATEGORIES, all).commit());
        connect(); awaitCount(1); awaitLedger(1, 7);
        assertEquals(1, new StatsRepository(context).load().getAllCategoryCensors());
        assertTrue(accountResults.stream().anyMatch(r -> r.phase == CensorProductSession.AccountingPhase.COUNT
                && r.status == CensorProductSession.AccountingStatus.COMMITTED && r.receipt.source.kind == SceneEngine.SourceKind.NATIVE_TEXT));
        assertTrue(faults.isEmpty());
    }
    @Test public void admittedLedgerCompletionCannotAttributeOldSourceTributeToANewSession() throws Exception {
        nativeCounting("porn", 1, 500); afterCommittedCount.set(() -> main(session::stop)); connect(); awaitCount(1);
        await("Product withdrawal rejects later ledger admission", () -> accountResults.stream().anyMatch(r -> r.phase == CensorProductSession.AccountingPhase.LEDGER
                && r.status == CensorProductSession.AccountingStatus.REJECTED));
        StatsRepository stats = new StatsRepository(context); StatsRepository.SessionStamp old = stats.sessionStamp(); AtomicInteger transitions = new AtomicInteger();
        Context walletContext = new ContextWrapper(context) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                SharedPreferences actual = super.getSharedPreferences(name, mode);
                if (!PenanceManager.PREFS_NAME.equals(name)) return actual;
                return (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class},
                        (proxy, method, args) -> {
                            Object result = method.invoke(actual, args);
                            if (!method.getName().equals("edit")) return result;
                            SharedPreferences.Editor edit = (SharedPreferences.Editor) result; AtomicBoolean ledger = new AtomicBoolean();
                            return Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(), new Class<?>[]{SharedPreferences.Editor.class},
                                    (editorProxy, operation, values) -> {
                                        if (operation.getName().equals("putString") && "events_v1".equals(values[0])) ledger.set(true);
                                        if (operation.getName().equals("commit") && ledger.get()) {
                                            assertNotEquals(Looper.getMainLooper(), Looper.myLooper());
                                            assertTrue(old.isCurrent()); stats.endSession(); stats.startSession(); transitions.incrementAndGet();
                                        }
                                        Object value = operation.invoke(edit, values);
                                        return value instanceof SharedPreferences.Editor ? editorProxy : value;
                                    });
                        });
            }
        };
        PenanceManager.SourceDetectionResult committed = new PenanceManager(walletContext).recordSourceDetectionResult(1, System.currentTimeMillis(), old);
        assertEquals(PenanceManager.SourceDetectionStatus.ACKNOWLEDGED, committed.status); assertEquals(7, committed.chargedCents);
        assertEquals(1, transitions.get()); assertFalse(old.isCurrent());
        assertEquals(0, stats.load().getCurrentSessionTributeEvents()); assertEquals(0, stats.load().getCurrentSessionTributeCents());
        assertEquals(1, stats.load().getTributeEvents()); assertEquals(7, stats.load().getTributeCents());
        assertEquals(0, stats.load().getCurrentSessionBlocks()); assertEquals(1, stats.getSessionHistory().get(0).getBlocks());
        main(session::stop); assertTrue(faults.isEmpty());
    }

    private void nativeOnly(String literal) throws Exception {
        assertTrue(preferences.edit().putStringSet(SettingsRepository.KEY_ENABLED_CATEGORIES, Collections.emptySet())
                .putBoolean(SettingsRepository.KEY_TEXT_SMUT_ENABLED, true).commit());
        literalTarget(literal);
    }
    private void nativeCounting(String literal, int batch, int dailyCap) throws Exception {
        nativeOnly(literal); new StatsRepository(context).startSession();
        Map<PenanceInfraction, Integer> rules = new EnumMap<>(PenanceInfraction.class); rules.put(PenanceInfraction.NEW_DETECTION, 7);
        new PenanceManager(context).configure(true, rules, dailyCap, 2000, 10, 10, batch, 5);
        assertFalse(new HardcoreAutoPayManager(context).isConfigured());
        assertTrue(context.getSharedPreferences(PayPalCredentialStore.PREFS_NAME, Context.MODE_PRIVATE).getAll().isEmpty());
    }
    private void literalTarget(String literal) throws Exception {
        context.startActivity(new Intent().setComponent(new ComponentName(TARGET, ProductTargetFixtureActivity.class.getName()))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION).putExtra("label", literal));
        await("Actual provider literal target ready", () -> {
            android.view.accessibility.AccessibilityNodeInfo root = ui.getRootInActiveWindow();
            if (root == null) return false; List<android.view.accessibility.AccessibilityNodeInfo> nodes = Collections.emptyList();
            try {
                root.refresh(); nodes = root.findAccessibilityNodeInfosByText(literal);
                for (android.view.accessibility.AccessibilityNodeInfo node : nodes)
                    if (node != null && node.refresh() && literal.contentEquals(node.getText() == null ? "" : node.getText())) return true;
                return false;
            } finally { for (android.view.accessibility.AccessibilityNodeInfo node : nodes) if (node != null && node != root) node.recycle(); root.recycle(); }
        });
    }
    private void awaitCount(long total) throws Exception {
        awaitNative("Acknowledged exact native source count " + total, () -> new StatsRepository(context).load().getTotalBlocks() == total
                && accountResults.stream().filter(r -> r.phase == CensorProductSession.AccountingPhase.COUNT
                    && r.status == CensorProductSession.AccountingStatus.COMMITTED).mapToInt(r -> r.count).sum() == total);
    }
    private void awaitLedger(int total, int charged) throws Exception {
        await("Acknowledged source ledger phase " + total, () -> accountResults.stream().filter(r -> r.phase == CensorProductSession.AccountingPhase.LEDGER
                && r.status == CensorProductSession.AccountingStatus.COMMITTED).count() >= total
                && accountResults.stream().filter(r -> r.phase == CensorProductSession.AccountingPhase.LEDGER
                    && r.status == CensorProductSession.AccountingStatus.COMMITTED).skip(total - 1).findFirst().get().chargedCents == charged);
    }
    private void awaitCountFailure(CensorProductSession.AccountingStatus status) throws Exception {
        awaitNative("Explicit controlled count result " + status, () -> accountResults.stream().anyMatch(r -> r.phase == CensorProductSession.AccountingPhase.COUNT && r.status == status));
    }
    private void assertNoDownstream() {
        assertTrue(accountResults.stream().noneMatch(r -> r.phase == CensorProductSession.AccountingPhase.LEDGER
                || r.phase == CensorProductSession.AccountingPhase.ACHIEVEMENTS));
        assertTrue(new PenanceManager(context).snapshot(System.currentTimeMillis()).getEvents().isEmpty());
        assertFalse(new AchievementManager(context).isUnlocked("first_block"));
    }
    private void uncertainAppliedCount(boolean throwsAfterApply) throws Exception {
        storageControl(true, throwsAfterApply, null); nativeCounting("porn", 1, 500); connect();
        awaitCountFailure(throwsAfterApply ? CensorProductSession.AccountingStatus.UNCERTAIN : CensorProductSession.AccountingStatus.UNACKNOWLEDGED);
        StatsRepository stats = new StatsRepository(context); assertEquals(1, stats.load().getTotalBlocks()); assertEquals(1, stats.load().getCurrentSessionBlocks());
        assertEquals(1, storageFaultCalls.get()); assertNoDownstream(); main(session::stop); stats.endSession();
        assertEquals(1, stats.getSessionHistory().get(0).getBlocks()); assertTrue(faults.isEmpty());
    }
    private void storageControl(boolean applyUnderlying, boolean throwAfter, Hold hold) {
        controlledStorageResult = true;
        ProductCensorQualification.fixtureStatistics(app -> new StatsRepository(new ContextWrapper(app) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                SharedPreferences actual = super.getSharedPreferences(name, mode);
                if (!StatsRepository.PREFS_NAME.equals(name)) return actual;
                return (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class},
                        (proxy, method, args) -> {
                            Object result = method.invoke(actual, args);
                            if (!method.getName().equals("edit")) return result;
                            SharedPreferences.Editor edit = (SharedPreferences.Editor) result; AtomicBoolean target = new AtomicBoolean();
                            return Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(), new Class<?>[]{SharedPreferences.Editor.class},
                                    (editorProxy, operation, values) -> {
                                        if (operation.getName().equals("putInt") && "active_session_blocks".equals(values[0])) target.set(true);
                                        if (operation.getName().equals("commit") && target.get()) {
                                            assertNotEquals(Looper.getMainLooper(), Looper.myLooper()); storageFaultCalls.incrementAndGet();
                                            if (hold != null) hold.run();
                                            if (applyUnderlying) assertTrue(edit.commit());
                                            if (throwAfter) throw new IllegalStateException("Controlled ambiguous acknowledgement");
                                            return false;
                                        }
                                        Object value = operation.invoke(edit, values);
                                        return value instanceof SharedPreferences.Editor ? editorProxy : value;
                                    });
                        });
            }
        }));
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
    /** Bookkeeping control: request fresh real provider reads; never invent source witnesses. */
    private void awaitNative(String label, BooleanSupplier condition) throws Exception {
        explicitNativeDemand = true;
        long until = SystemClock.uptimeMillis() + 12000, nextDemand = 0;
        while (!condition.getAsBoolean()) {
            long now = SystemClock.uptimeMillis(); assertTrue(label, now < until);
            if (now >= nextDemand) {
                main(() -> { if (session != null && session.isActive()) session.controller().host().requestNativeText(false); });
                nextDemand = now + 200;
            }
            Thread.sleep(10);
        }
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
        JSONArray evidenceRows = new JSONArray();
        for (CensorEngine.EvidenceReceipt receipt : evidence) {
            JSONArray subjects = new JSONArray();
            for (SceneEngine.AcceptedSubject subject : receipt.subjects) {
                JSONArray witnesses = new JSONArray();
                for (SceneEngine.EvidenceWitness witness : subject.witnesses)
                    witnesses.put(new JSONObject().put("kind", witness.kind.name()).put("producerIdentity", witness.producerIdentity)
                            .put("sourceSequence", witness.sourceSequence));
                subjects.put(new JSONObject().put("identity", subject.identity).put("evidenceLane", subject.evidenceLane.name())
                        .put("confirmedForConsumption", subject.confirmedForConsumption).put("alreadyConsumed", subject.alreadyConsumed)
                        .put("validUntilMillis", subject.validUntilMillis).put("rawWitnessCount", subject.witnesses.size()).put("witnesses", witnesses));
            }
            evidenceRows.put(sourceMetadata(receipt).put("triggerLane", receipt.triggerLane.name())
                    .put("acceptedAtMillis", receipt.acceptedAtMillis).put("rawSubjectCount", receipt.subjects.size()).put("subjects", subjects));
        }
        JSONArray accountingRows = new JSONArray();
        for (CensorProductSession.AccountingResult result : accountResults) {
            JSONObject row = new JSONObject().put("phase", result.phase.name()).put("status", result.status.name())
                    .put("count", result.count).put("chargedCents", result.chargedCents).put("pendingDrops", result.pendingDrops)
                    .put("receiptProduced", result.receipt != null);
            if (result.receipt != null) row.put("source", sourceMetadata(result.receipt));
            accountingRows.put(row);
        }
        JSONObject metrics = new JSONObject().put("schemaVersion", 4).put("actualProductionService", session != null)
                .put("sameInstrumentationPid", session != null && ((ScreenshotAccessibilityService) session.service()).qualificationBoundPid() == Process.myPid())
                .put("pid", Process.myPid()).put("rawTraceCount", traces.size()).put("traces", rows).put("posts", submitted)
                .put("rawPostCount", posts.size()).put("models", models.get()).put("closes", closes.get()).put("managedDrain", session != null && session.managedResourcesDrained())
                .put("legacyCensorRuntimeAbsent", session != null && ((ScreenshotAccessibilityService) session.service()).legacyCensorRuntimeAbsentForQualification())
                .put("controlledModelsOnly", !controlledStorageResult).put("physicalMotionAcceptance", false).put("pixelPerformanceAcceptance", false);
        metrics.put("rawEvidenceCount", evidence.size()).put("evidence", evidenceRows)
                .put("rawAccountingCount", accountResults.size()).put("accounting", accountingRows)
                .put("controlledStorageResult", controlledStorageResult).put("storageFaultCalls", storageFaultCalls.get())
                .put("explicitNativeDemand", explicitNativeDemand);
        try (FileOutputStream stream = new FileOutputStream(new File(directory(), "native-surface-metrics-" + System.nanoTime() + ".json"))) {
            stream.write(metrics.toString().getBytes(StandardCharsets.UTF_8));
        }
    }
    private static JSONObject sourceMetadata(CensorEngine.EvidenceReceipt receipt) throws Exception {
        SceneEngine.Source source = receipt.source;
        return new JSONObject().put("epoch", receipt.scope.epoch).put("geometry", receipt.scope.geometry)
                .put("origin", receipt.scope.protectionScope.origin.name()).put("sequence", source.sequence).put("kind", source.kind.name())
                .put("imageRequestIdentity", source.imageRequestIdentity).put("readStartedAtMillis", source.readStartedAtMillis)
                .put("readFinishedAtMillis", source.readFinishedAtMillis).put("receivedAtMillis", source.receivedAtMillis)
                .put("pixelAtMillis", source.pixelAtMillis);
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
