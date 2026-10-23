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
import android.graphics.RectF;
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
import com.subhub.app.censor.engine.NativeTextFrame;
import com.subhub.app.censor.engine.NativeTextResult;
import com.subhub.app.censor.engine.ScrollMotionLedger;
import com.subhub.app.censor.engine.ProtectionScope;
import com.subhub.app.censor.engine.SceneEngine;
import com.subhub.app.detection.BBox;
import com.subhub.app.detection.Detection;
import com.subhub.app.detection.DetectionEngine;
import com.subhub.app.detection.DetectorConfig;
import com.subhub.app.diagnostics.CensorLabCensorSession;
import com.subhub.app.diagnostics.CensorLabRecorder;
import com.subhub.app.diagnostics.ReplacementEngineTelemetry;
import com.subhub.app.penance.CensorTapTracker;
import com.subhub.app.penance.PenanceManager;
import com.subhub.app.penance.PenanceInfraction;
import com.subhub.app.penance.HardcoreAutoPayManager;
import com.subhub.app.penance.PayPalCredentialStore;
import com.subhub.app.popup.PopupStormManager;
import com.subhub.app.popup.PopupStormSettings;
import com.subhub.app.popup.ScopedDetectionHints;
import com.subhub.app.stats.StatsRepository;
import com.subhub.app.stats.AchievementManager;
import com.subhub.app.security.HardcoreModeManager;
import com.subhub.app.service.ScreenshotAccessibilityService;
import com.subhub.app.settings.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.*;
import org.junit.rules.TestName;
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
    @Rule public final TestName testName = new TestName();
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
    private final List<CensorProductSession.DwellResult> dwellResults = new CopyOnWriteArrayList<>();
    private final List<ScrollMotionLedger.Receipt> scrollReceipts = new CopyOnWriteArrayList<>();
    private final List<NativeTextFrame> nativeFrames = new CopyOnWriteArrayList<>();
    private final List<com.subhub.app.censor.engine.NativeReadCost.Start> nativeCostStarts = new CopyOnWriteArrayList<>();
    private final List<com.subhub.app.censor.engine.NativeReadCost> nativeCosts = new CopyOnWriteArrayList<>();
    private final List<JSONObject> productInputBoundaries = new CopyOnWriteArrayList<>();
    private final AtomicReference<Hold> nativeReadHold = new AtomicReference<>();
    private final AtomicReference<Hold> nativeHoldAfterAccepted = new AtomicReference<>();
    private final AtomicInteger nativeProviderEntries = new AtomicInteger();
    private final JSONArray productScrollSteps = new JSONArray();
    private Map<String, RectF> productGlyphBaseline;
    private File productScrollEvidence;
    private File cadenceEvidence;
    private boolean productScrollPassed, ownedLabStarted;
    private boolean sustainedNativeGate, sustainedAddedVisible;
    private boolean windowCostGate;
    private boolean naturalCohortRenewalGate;
    private final List<NativeArrivalObservation> naturalNativeArrivals = new CopyOnWriteArrayList<>();
    private final AtomicReference<Thread> naturalNativeOwner = new AtomicReference<>();
    private final List<NativeOwnerObservation> naturalNativeOwners = new CopyOnWriteArrayList<>();
    private final JSONArray naturalRenewalCommands = new JSONArray();
    private long naturalRenewalStartedAt = -1, naturalRenewalEndedAt = -1;
    private long naturalCallbackBoundaryAt = -1;
    private JSONObject naturalFinalCommand;
    private boolean nativeCutoffGate;
    private long nativeCutoffEpoch = -1, nativeCutoffRequest = -1, nativeCutoffTicket = -1;
    private final JSONArray nativeCutoffEvents = new JSONArray();
    private final CountDownLatch nativeCutoffTerminal = new CountDownLatch(1);
    private Object nativeCutoffHost;
    private boolean nativeCutoffReaderRetiredAtTerminal;
    private AccessibilityWindowReader windowCostReader;
    private final List<WindowReadCost.Call> windowCostStarts = new CopyOnWriteArrayList<>();
    private final List<WindowReadCost.Sample> windowCostSamples = new CopyOnWriteArrayList<>();
    private final JSONArray windowCostCommands = new JSONArray();
    private com.subhub.app.censor.engine.NativeReadCost windowCostSelectedDirect;
    private long windowCostAttachedAt = -1, windowCostDemandStartedAt = -1, windowCostDemandEndedAt = -1;
    private final ParentBoundResourceObserver<SustainedResourceOwner> sustainedResourceObserver = new ParentBoundResourceObserver<>();
    private final List<JSONObject> sustainedResources = new CopyOnWriteArrayList<>();
    private final List<String> sustainedResourceFailures = new CopyOnWriteArrayList<>();
    private JSONObject addedMutation, removedMutation;
    private SceneEngine.Source sustainedDiscovery, sustainedRemoval;
    private NativePaintObservation sustainedForwardPaint;
    private long sustainedWithdrawalDeadline = -1;
    private final Set<Long> sustainedInitialParents = new LinkedHashSet<>();
    private final List<Long> sustainedCommandTimes = new ArrayList<>();
    private int sustainedCommands;
    private long sustainedStartedAt = -1, sustainedEndedAt = -1;
    private int acceptedDuringSustainedDemand;
    private int acceptedPositiveNodesOnlyDuringSustainedDemand;
    private long stationaryStartedAt = -1, stationaryEndedAt = -1;
    private int acceptedDuringStationaryRenewal;
    private boolean productInputCaptureEnabled;
    private final AtomicInteger productInputBoundaryFailures = new AtomicInteger();
    private Map<String, ?> savedLabPreferences;
    private final AtomicReference<Runnable> afterCommittedCount = new AtomicReference<>();
    private final AtomicInteger storageFaultCalls = new AtomicInteger();
    private boolean controlledStorageResult;
    private boolean explicitNativeDemand;
    private boolean controlledObservationRedelivery;
    private int naturalAccountingNodes;
    private boolean naturalAccountingPassed, naturalAccountingScopeStable, naturalAccountingReplayEmpty;
    private long naturalAccountingStartedAt = -1, naturalAccountingCountedAt = -1, naturalAccountingFinishedAt = -1;
    private long naturalAccountingReplaySource = -1, naturalAccountingFirstUnlock, naturalAccountingAppearanceAt = -1;
    private long naturalAccountingPostAppearancePartialSource = -1;
    private StatsRepository.SessionStamp naturalAccountingStamp;

    @Before public void ownOnlySyntheticGuestSettingsAndUnsuppressedAutomation() {
        instrumentation = InstrumentationRegistry.getInstrumentation(); context = instrumentation.getTargetContext();
        assertEquals("Owned guest opt-in required", "true", InstrumentationRegistry.getArguments().getString("bound_censor_fixture"));
        assertTrue("Never authorized on a phone", ProductCensorQualification.supported());
        preferences = new SettingsRepository(context).preferences();
        qualification = context.getSharedPreferences(ProductCensorQualification.PREFERENCES, Context.MODE_PRIVATE);
        savedPreferences = copy(preferences.getAll()); savedQualification = copy(qualification.getAll());
        previousServices = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        previousEnabled = Settings.Secure.getInt(context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0);
        assertFalse("No enabled production capture intent may be borrowed", previousEnabled != 0 && previousServices != null
                && (previousServices.contains("ScreenshotAccessibilityService") || previousServices.contains("ScreenCapture")));
        assertFalse("No live capture owner may be borrowed", ScreenshotAccessibilityService.isRunning());
        String cadenceRun = InstrumentationRegistry.getArguments().getString("cadence_fixture_session");
        if (cadenceRun != null) {
            assertEquals("Only the cadence method owns this namespace", "inactiveReplacementAppliesPresetAccessibilityCadenceOnStartupAndReload", testName.getMethodName());
            assertTrue(cadenceRun.matches("[a-f0-9]{32}"));
            cadenceEvidence = new File(context.getFilesDir(), "censor-lab/native-cadence-fixtures/" + cadenceRun);
            assertFalse("Retain existing cadence evidence", cadenceEvidence.exists()); assertTrue(cadenceEvidence.mkdirs());
        }
        String scrollRun = InstrumentationRegistry.getArguments().getString("product_scroll_run");
        if (scrollRun != null) {
            assertTrue(scrollRun.matches("[A-Za-z0-9_-]{1,80}"));
            File base = new File(context.getFilesDir(), "replacement-product-scroll/" + scrollRun);
            assertTrue(base.isDirectory() || base.mkdirs()); productScrollEvidence = new File(base, testName.getMethodName());
            assertFalse("Exclusive current product evidence", productScrollEvidence.exists()); assertTrue(productScrollEvidence.mkdir());
        }
        settingsOwned = true;
        // Save only in test memory. No provider authorization or guest wallet may participate.
        for (String name : OWNED_STORES) savedStores.put(name, copy(context.getSharedPreferences(name, Context.MODE_PRIVATE).getAll()));
        new HardcoreAutoPayManager(context).disable();
        new StatsRepository(context).endSession();
        for (String name : OWNED_STORES) assertTrue(context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit());
        assertTrue(preferences.edit().putBoolean(AppModeManager.KEY_ARMED, cadenceEvidence == null)
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
        ProductCensorQualification.fixturePenance(PenanceManager::new);
        ProductCensorQualification.fixtureSelectionObserved(() -> {
            assertNotEquals(Looper.getMainLooper(), Looper.myLooper()); readCount.incrementAndGet();
            Hold held = readHold.getAndSet(null); if (held != null) held.run();
        });
        ProductCensorQualification.fixtureNativeReadSelected(() -> {
            assertNotEquals(Looper.getMainLooper(), Looper.myLooper()); nativeProviderEntries.incrementAndGet();
            Hold held = nativeReadHold.getAndSet(null); if (held != null) held.run();
        });
        ProductCensorQualification.fixtureNativeConfirmationPending(this::cancelSampledNativeBeforeConfirmation);
        ProductCensorQualification.fixtureInputObserver((type, contentTypes, windowId, sourceAt, receivedAt, route, completed) -> {
            if (productScrollEvidence == null || session == null || !productInputCaptureEnabled) return;
            try {
                CensorEngine<PixelFrame> engine = actualEngineForControl();
                java.lang.reflect.Field generation = CensorEngine.class.getDeclaredField("nativeGeneration"); generation.setAccessible(true);
                synchronized (engine) {
                    ScrollMotionLedger.Scope scope = engine.scrollScope();
                    productInputBoundaries.add(new JSONObject().put("type", type).put("contentTypes", contentTypes)
                            .put("windowId", windowId).put("sourceAtMillis", sourceAt).put("receivedAtMillis", receivedAt)
                            .put("route", route).put("completed", completed).put("epoch", engine.currentEpoch())
                            .put("nativeGeneration", generation.getLong(engine)).put("nativeTreeGeneration", engine.nativeTreeGeneration())
                            .put("scopeWindowId", scope == null ? -1 : scope.windowId));
                }
            } catch (Exception failure) { productInputBoundaryFailures.incrementAndGet(); faults.add(failure); }
        });
        ProductCensorQualification.fixtureObserver(new CensorProductSession.Observer() {
            @Override public void trace(CensorEngine.Trace value) { traces.add(value); }
            @Override public void prepared(long request, PixelFrame.Kind kind, long nanos, int bytes) { preparations.add(kind); }
            @Override public void batchAccepted(SceneEngine.Batch batch) { batches.add(batch); }
            @Override public void posted(SnapshotCensorSurface.Post receipt) { posts.add(receipt); }
            @Override public void modelUnavailable(SceneEngine.Lane lane) { unavailable.add(lane); }
            @Override public void sessionFailure(Throwable fault) { faults.add(fault); }
            @Override public void evidenceAccepted(CensorEngine.EvidenceReceipt receipt) { evidence.add(receipt); }
            @Override public void nativeTextAccepted(NativeTextFrame frame) {
                if (naturalCohortRenewalGate) naturalNativeArrivals.add(new NativeArrivalObservation(frame, SystemClock.uptimeMillis()));
                nativeFrames.add(frame); Hold held = nativeHoldAfterAccepted.getAndSet(null);
                if (held != null) nativeReadHold.set(held);
            }
            @Override public void nativeReadCostStarted(com.subhub.app.censor.engine.NativeReadCost.Start value) {
                nativeCostStarts.add(value);
                if (naturalCohortRenewalGate) {
                    naturalNativeOwner.compareAndSet(null, Thread.currentThread());
                    naturalNativeOwners.add(new NativeOwnerObservation(value));
                }
                sampleSustainedResources("START", value.epoch, value.request);
            }
            @Override public void nativeReadCost(com.subhub.app.censor.engine.NativeReadCost value) {
                nativeCosts.add(value); sampleSustainedResources("TERMINAL", value.epoch, value.request);
            }
            @Override public void scrollReceived(ScrollMotionLedger.Receipt receipt) { scrollReceipts.add(receipt); }
            @Override public void accounting(CensorProductSession.AccountingResult result) {
                assertNotEquals("No accounting disk work on main", Looper.getMainLooper(), Looper.myLooper());
                accountResults.add(result);
                if (result.phase == CensorProductSession.AccountingPhase.COUNT && result.status == CensorProductSession.AccountingStatus.COMMITTED) {
                    Runnable action = afterCommittedCount.getAndSet(null);
                    if (action != null) { try { action.run(); } catch (Throwable failure) { faults.add(failure); } }
                }
            }
            @Override public void dwell(CensorProductSession.DwellResult result) {
                assertNotEquals("No dwell ledger disk work on main", Looper.getMainLooper(), Looper.myLooper());
                dwellResults.add(result);
            }
            @Override public void foreground(AccessibilityWindowReader.Window window) {
                if (window != null && TARGET.equals(window.packageName) && stopForeground.compareAndSet(true, false)) {
                    ProductCensorQualification.current().stop(); foregroundStopped.countDown();
                }
            }
        });
        // Registration can enable Accessibility and bind a remembered component. Save and
        // reject pre-existing authority first; prepare this owned route before registration.
        ui = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        if (productScrollEvidence != null) {
            android.accessibilityservice.AccessibilityServiceInfo info = ui.getServiceInfo();
            info.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS; ui.setServiceInfo(info);
        }
        target(false);
    }
    @After public void closeActualOwnerBeforeRestoringExactGuestState() throws Exception {
        if (!settingsOwned) return;
        // Close only new sample admission. Already sampled native parents retain passive owner
        // references until their real terminal, even after retirement clears the Host field.
        sustainedResourceObserver.cutoff(SystemClock.uptimeMillis());
        if (ui == null) ui = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_USE_ACCESSIBILITY);
        for (Hold owned : ownedHolds) owned.release.countDown();
        Hold held = readHold.getAndSet(null); if (held != null) held.release.countDown();
        held = modelHold.getAndSet(null); if (held != null) held.release.countDown();
        Throwable primaryTeardownFailure = null;
        Throwable evidenceFailure = null;
        try {
            CensorProductSession current = session == null ? ProductCensorQualification.current() : session;
            if (current != null) {
                main(current::close); await("Product lookup/controller/Surface actual managed drain", current::managedResourcesDrained);
                assertFalse("Native context never closes over executing inference", earlyClose.get());
                ProductCensorQualification.disconnected(current);
            }
            writeMetrics();
        } catch (Exception | Error failure) {
            primaryTeardownFailure = failure; throw failure;
        } finally {
            if (productScrollEvidence != null) {
                try { finishProductScrollEvidence(); }
                catch (Exception | Error failure) {
                    if (primaryTeardownFailure == null) evidenceFailure = failure;
                    else primaryTeardownFailure.addSuppressed(failure);
                }
                // Preserve this new falsifier even if an older serializer/assertion failed.
                if (naturalCohortRenewalGate) try { finishNaturalCohortEvidence(); }
                catch (Exception | Error failure) {
                    if (primaryTeardownFailure != null) primaryTeardownFailure.addSuppressed(failure);
                    else if (evidenceFailure != null) evidenceFailure.addSuppressed(failure);
                    else evidenceFailure = failure;
                }
                if (naturalAccountingNodes != 0) try { finishNaturalAccountingEvidence(); }
                catch (Exception | Error failure) {
                    if (primaryTeardownFailure != null) primaryTeardownFailure.addSuppressed(failure);
                    else if (evidenceFailure != null) evidenceFailure.addSuppressed(failure);
                    else evidenceFailure = failure;
                }
            }
            if (ownedLabStarted && CensorLabRecorder.isActive()) CensorLabRecorder.stop(context);
            if (savedLabPreferences != null) restore(context.getSharedPreferences("censor_lab_sessions", Context.MODE_PRIVATE), savedLabPreferences);
            // The test's Accessibility registration otherwise forces the global enabled state.
            // All capture and metadata work is already drained before this supported switch.
            ui = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_USE_ACCESSIBILITY);
            secure(previousServices, previousEnabled);
            await("Actual production service unbinding", () -> !ScreenshotAccessibilityService.isRunning());
            target(true);
            new StatsRepository(context).endSession();
            for (Map.Entry<String, Map<String, ?>> saved : savedStores.entrySet())
                restore(context.getSharedPreferences(saved.getKey(), Context.MODE_PRIVATE), saved.getValue());
            new StatsRepository(context).sessionStamp();
            restore(preferences, savedPreferences); restore(qualification, savedQualification);
            ProductCensorQualification.fixtureModels(null); ProductCensorQualification.fixtureSelectionObserved(() -> { });
            ProductCensorQualification.fixtureNativeReadSelected(() -> { }); ProductCensorQualification.fixtureNativeConfirmationPending(null);
            ProductCensorQualification.fixtureInputObserver(null);
            ProductCensorQualification.fixtureObserver(null);
            ProductCensorQualification.fixtureStatistics(StatsRepository::new);
            ProductCensorQualification.fixturePenance(PenanceManager::new);
        }
        assertEquals(savedPreferences, preferences.getAll()); assertEquals(savedQualification, qualification.getAll());
        for (Map.Entry<String, Map<String, ?>> saved : savedStores.entrySet())
            assertEquals(saved.getValue(), context.getSharedPreferences(saved.getKey(), Context.MODE_PRIVATE).getAll());
        assertEquals(previousServices, Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES));
        assertEquals(previousEnabled, Settings.Secure.getInt(context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0));
        if (evidenceFailure instanceof Exception) throw (Exception) evidenceFailure;
        if (evidenceFailure instanceof Error) throw (Error) evidenceFailure;
    }

    @Test public void inactiveReplacementAppliesPresetAccessibilityCadenceOnStartupAndReload() throws Exception {
        org.junit.Assume.assumeTrue("Explicit private cadence fixture namespace", cadenceEvidence != null);
        String policy = InstrumentationRegistry.getArguments().getString("cadence_policy");
        assertTrue("Exact control or restored policy", "omitted".equals(policy) || "restored".equals(policy));
        JSONObject result = new JSONObject().put("schema", 1).put("policy", policy)
                .put("actualBoundServiceInfo", true).put("physicalAcceptance", false).put("scrollPerformanceAcceptance", false);
        JSONArray observations = new JSONArray(); boolean passed = false;
        result.put("observations", observations);
        try {
            assertFalse(preferences.getBoolean(AppModeManager.KEY_ARMED, true));
            assertTrue(preferences.edit().putString(SettingsRepository.KEY_DETECTION_PRESET, "high").commit());
            connect();
            JSONObject initial = awaitCadencePolicy("startup-high", "restored".equals(policy) ? 0 : 16,
                    "restored".equals(policy), observations);
            assertEquals(4, initial.getInt("configuredThreads"));
            if ("restored".equals(policy)) {
                assertTrue(preferences.edit().putString(SettingsRepository.KEY_DETECTION_PRESET, "medium").commit());
                JSONObject medium = awaitCadencePolicy("reload-medium", 16, false, observations);
                assertEquals(3, medium.getInt("configuredThreads")); assertCadenceIdentity(initial, medium);
                assertTrue(preferences.edit().putString(SettingsRepository.KEY_DETECTION_PRESET, "high").commit());
                JSONObject high = awaitCadencePolicy("reload-high", 0, true, observations);
                assertEquals(4, high.getInt("configuredThreads")); assertCadenceIdentity(initial, high);
            }
            assertFalse(session.isActive()); assertEquals(0, models.get()); assertEquals(0, closes.get());
            assertEquals(0, nativeProviderEntries.get()); assertTrue(batches.isEmpty()); assertTrue(preparations.isEmpty());
            assertTrue(nativeFrames.isEmpty()); assertTrue(nativeCostStarts.isEmpty()); assertTrue(faults.isEmpty());
            assertEquals(0, traces.stream().filter(t -> t.stage == CensorEngine.Stage.CAPTURE_DISPATCHED).count());
            passed = true;
        } finally {
            result.put("bodyPassed", passed).put("models", models.get()).put("closes", closes.get())
                    .put("selectionReads", readCount.get()).put("nativeProviderEntries", nativeProviderEntries.get())
                    .put("captureDispatches", traces.stream().filter(t -> t.stage == CensorEngine.Stage.CAPTURE_DISPATCHED).count())
                    .put("batches", batches.size()).put("preparations", preparations.size()).put("nativeFrames", nativeFrames.size())
                    .put("nativeCostStarts", nativeCostStarts.size()).put("faults", faults.size());
            File output = new File(cadenceEvidence, "cadence-policy.json");
            assertTrue("Retain previous cadence evidence", output.createNewFile());
            try (FileOutputStream stream = new FileOutputStream(output)) { stream.write(result.toString().getBytes(StandardCharsets.UTF_8)); }
        }
    }
    private JSONObject awaitCadencePolicy(String stage, long timeout, boolean include, JSONArray retained) throws Exception {
        long started = SystemClock.uptimeMillis(); AtomicReference<JSONObject> latest = new AtomicReference<>(); AtomicInteger polls = new AtomicInteger();
        try {
            await("Actual bound service cadence " + stage, () -> {
                main(() -> latest.set(observeCadencePolicy(stage))); polls.incrementAndGet();
                JSONObject value = latest.get();
                return value.optLong("notificationTimeoutMillis", -1) == timeout && value.optBoolean("includeNotImportant") == include;
            });
            return latest.get();
        } finally {
            if (latest.get() != null) retained.put(latest.get().put("phaseStartedAtMillis", started)
                    .put("actualQueries", polls.get()).put("phaseFinishedAtMillis", SystemClock.uptimeMillis()));
        }
    }
    private JSONObject observeCadencePolicy(String stage) {
        assertEquals(Looper.getMainLooper(), Looper.myLooper()); assertSame(session, ProductCensorQualification.current());
        ScreenshotAccessibilityService actual = (ScreenshotAccessibilityService) session.service();
        android.accessibilityservice.AccessibilityServiceInfo info = actual.getServiceInfo();
        assertNotNull("Actual bound service info, not UiAutomation info", info); assertBinding();
        try {
            return new JSONObject().put("stage", stage).put("queriedAtMillis", SystemClock.uptimeMillis())
                    .put("mainThread", true).put("serviceIdentity", System.identityHashCode(actual))
                    .put("ownerIdentity", System.identityHashCode(session)).put("pid", actual.qualificationBoundPid())
                    .put("instrumentationPid", Process.myPid()).put("serviceClass", info.getResolveInfo().serviceInfo.name)
                    .put("legacyAbsent", actual.legacyCensorRuntimeAbsentForQualification()).put("active", session.isActive())
                    .put("preset", preferences.getString(SettingsRepository.KEY_DETECTION_PRESET, ""))
                    .put("configuredThreads", new SettingsRepository(context).loadDetectorConfig().getInferenceThreads())
                    .put("notificationTimeoutMillis", info.notificationTimeout).put("flags", info.flags)
                    .put("nonCadenceFlags", info.flags & ~android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS)
                    .put("includeNotImportant", (info.flags & android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS) != 0)
                    .put("eventTypes", info.eventTypes).put("feedbackType", info.feedbackType).put("capabilities", info.getCapabilities())
                    .put("packageNames", info.packageNames == null ? JSONObject.NULL : new JSONArray(Arrays.asList(info.packageNames)));
        } catch (org.json.JSONException failure) { throw new AssertionError("Cadence metadata", failure); }
    }
    private static void assertCadenceIdentity(JSONObject initial, JSONObject current) throws Exception {
        for (String key : Arrays.asList("serviceIdentity", "ownerIdentity", "pid", "instrumentationPid", "serviceClass",
                "nonCadenceFlags", "eventTypes", "feedbackType", "capabilities", "packageNames")) {
            assertEquals("Unrelated service field " + key, initial.get(key).toString(), current.get(key).toString());
        }
        assertTrue(current.getBoolean("mainThread")); assertTrue(current.getBoolean("legacyAbsent")); assertFalse(current.getBoolean("active"));
    }

    @Test public void actualServiceScrollRoutePreservesOrdinaryNativeGroupsAndIndependentGlyphPoses() throws Exception {
        requireProductScrollGate();
        assertTrue(preferences.edit().putStringSet(SettingsRepository.KEY_ENABLED_CATEGORIES, Collections.emptySet())
                .putBoolean(SettingsRepository.KEY_TEXT_SMUT_ENABLED, true).commit());
        context.startActivity(targetIntent().putExtra("nativeScrollFixture", true).putExtra("label", "porn"));
        startOwnedScrollLab(); connect();
        main(() -> productInputCaptureEnabled = true);
        Map<String, Long> baseline = captureProductScrollPose("baseline", 0, null);
        CensorEngine.ActiveScope original = session.controller().activeScope();
        long stationaryGeneration = actualNativeGeneration();
        stationaryStartedAt = SystemClock.uptimeMillis(); long stationaryUntil = stationaryStartedAt + 1600;
        while (SystemClock.uptimeMillis() < stationaryUntil) Thread.sleep(10);
        stationaryEndedAt = SystemClock.uptimeMillis();
        for (NativeTextFrame frame : nativeFrames)
            if (positiveCompleteNativeFrame(frame) && frame.source.readStartedAtMillis >= stationaryStartedAt
                    && frame.source.receivedAtMillis <= stationaryEndedAt)
                acceptedDuringStationaryRenewal++;
        assertTrue("Actual native-only stationary renewals without query/event demand", acceptedDuringStationaryRenewal >= 3);
        assertEquals("Stationary producer proof has no independent semantic event demand", stationaryGeneration, actualNativeGeneration());
        for (JSONObject boundary : productInputBoundaries)
            if (boundary.getLong("receivedAtMillis") >= stationaryStartedAt && boundary.getLong("receivedAtMillis") <= stationaryEndedAt)
                assertFalse("No eligible event-driven work inside the autonomous renewal interval",
                        boundary.getString("route").equals("SCROLL") || boundary.getString("route").equals("CONTENT")
                                || boundary.getString("route").equals("FOREGROUND") || boundary.getString("route").equals("REQUEST"));
        int previous = 0;
        for (int position : new int[]{100, 260, 80, 300, 0}) {
            ScrollMotionLedger.Receipt receipt = actualProductScroll(position, position - previous, original);
            assertTrue(receipt.admission == ScrollMotionLedger.Admission.BASELINE
                    || receipt.admission == ScrollMotionLedger.Admission.RECORDED || receipt.admission == ScrollMotionLedger.Admission.UNKNOWN);
            assertScrollBranchPreservesNativeGeneration(receipt.sample.sourceAtMillis);
            captureProductScrollPose("scroll-" + position, position, baseline); previous = position;
        }
        sustainedStartedAt = SystemClock.uptimeMillis(); long until = sustainedStartedAt + 2200;
        int index = 1, lastPosition = 0; int[] reversal = {0, 160, 320, 160};
        while (SystemClock.uptimeMillis() < until) {
            lastPosition = reversal[index++ % reversal.length];
            context.startActivity(targetIntent().putExtra("scrollToY", lastPosition)); Thread.sleep(15);
        }
        sustainedEndedAt = SystemClock.uptimeMillis();
        for (NativeTextFrame frame : nativeFrames) {
            if (frame.read.candidates.isEmpty() || frame.source.readStartedAtMillis < sustainedStartedAt
                    || frame.source.receivedAtMillis > sustainedEndedAt) continue;
            if (frame.read.coverage == NativeTextResult.Coverage.COMPLETE_TREE) acceptedDuringSustainedDemand++;
            else acceptedPositiveNodesOnlyDuringSustainedDemand++;
        }
        assertTrue("Full confirmations actually accepted inside continuing service-scroll interval", acceptedDuringSustainedDemand >= 5);
        if (lastPosition != 0) actualProductScroll(0, -lastPosition, original);
        for (ScrollMotionLedger.Receipt receipt : scrollReceipts) assertScrollBranchPreservesNativeGeneration(receipt.sample.sourceAtMillis);
        captureProductScrollPose("sustained-final", 0, baseline);
        assertFalse("No bookkeeping helper creates native demand in this service-route gate", explicitNativeDemand);
        assertTrue("Several actual complete-tree confirmed frames", nativeFrames.stream().filter(
                ProductCensorSessionAndroidTest::positiveCompleteNativeFrame).count() >= 6);
        assertTrue(evidence.stream().allMatch(row -> row.source.kind == SceneEngine.SourceKind.NATIVE_TEXT));
        assertTrue(batches.isEmpty()); assertTrue(original.same(session.controller().activeScope()));
        assertTrue(faults.isEmpty()); assertBinding(); productScrollPassed = true;
    }

    @Test public void actualServiceScrollReceiptsKeepImageAdmissionSeparateAndCannotInventEvidence() throws Exception {
        requireProductScrollGate();
        context.startActivity(targetIntent().putExtra("motionFixture", true)); startOwnedScrollLab(); connect(); awaitPaint();
        await("Actual acquired IMAGE batch", () -> !batches.isEmpty());
        SceneEngine.Source origin = batches.get(0).source; assertEquals(SceneEngine.SourceKind.IMAGE, origin.kind);
        CensorEngine<PixelFrame> engine = actualEngineForControl();
        CensorEngine.ActiveScope original = session.controller().activeScope();
        long receivedAt = origin.receivedAtMillis, pixelAt = origin.pixelAtMillis;
        JSONObject baselinePresentation = actualImagePresentation(engine);
        assertTrue("Actual nonempty image member baseline before held inference", baselinePresentation.getJSONArray("members").length() > 0);
        Hold held = hold(); modelHold.set(held); main(session::demandCapture);
        assertTrue("Next actual visual inference is held, not replaced", held.entered.await(8, TimeUnit.SECONDS));
        int evidenceBefore = evidence.size(), batchesBefore = batches.size();
        long admission = -1; int previous = 0;
        int visibleImageComparisons = 0;
        for (int position : new int[]{100, 260, 80}) {
            ScrollMotionLedger.Receipt receipt = actualProductScroll(position, position - previous, original);
            assertTrue(receipt.sample.owner > 0); assertTrue(receipt.sample.producer > 0);
            ScrollMotionLedger.Continuation continuation = engine.scrollContinuation(origin, receipt.sample.owner, receipt.sample.producer);
            assertSame("Original acquired source, never receipt-rebased", origin, continuation.origin);
            assertTrue(continuation.originAdmittedAtMillis >= receivedAt);
            if (admission == -1) admission = continuation.originAdmittedAtMillis;
            else assertEquals("Immutable IMAGE administrative checkpoint across reversal", admission, continuation.originAdmittedAtMillis);
            assertFalse(continuation.originPixelPhaseKnown); assertFalse(continuation.initialBiasKnown);
            assertFalse(continuation.physicalErrorKnown); assertFalse(continuation.memberApplicabilityKnown);
            assertEquals(receivedAt, origin.receivedAtMillis); assertEquals(pixelAt, origin.pixelAtMillis);
            assertEquals("Scroll ingestion is not another detector result", batchesBefore, batches.size());
            assertEquals("Scroll ingestion is not positive evidence or confirmation", evidenceBefore, evidence.size());
            JSONObject currentPresentation = actualImagePresentation(engine);
            visibleImageComparisons += assertImagePresentationUnchanged(baselinePresentation, currentPresentation);
            JSONObject step = new JSONObject().put("label", "image-scroll-" + position).put("position", position)
                    .put("origin", viewportSource(origin)).put("originAdmittedAtMillis", admission)
                    .put("continuationRefusal", continuation.refusal.name()).put("intervals", continuation.intervals)
                    .put("omittedStraddlingIntervals", continuation.omittedStraddlingIntervals)
                    .put("xAvailable", continuation.x.available).put("yAvailable", continuation.y.available)
                    .put("yDisplacement", continuation.y.available ? continuation.y.displacement() : JSONObject.NULL)
                    .put("yMinimum", continuation.y.available ? continuation.y.minimumObservedDisplacement() : JSONObject.NULL)
                    .put("yMaximum", continuation.y.available ? continuation.y.maximumObservedDisplacement() : JSONObject.NULL)
                    .put("evidenceBefore", evidenceBefore).put("evidenceAfter", evidence.size())
                    .put("batchesBefore", batchesBefore).put("batchesAfter", batches.size())
                    .put("baselinePresentation", baselinePresentation).put("currentPresentation", currentPresentation);
            productScrollSteps.put(step); previous = position;
        }
        assertTrue("At least one retained visible IMAGE member compared through an actual scroll receipt", visibleImageComparisons > 0);
        held.release.countDown(); assertTrue(original.same(session.controller().activeScope()));
        assertTrue(nativeFrames.isEmpty()); assertTrue(faults.isEmpty()); assertBinding(); productScrollPassed = true;
    }

    @Test public void actualServicePositiveNodePaintsKeepOwnSourcesThroughRapidScrollAndReversal() throws Exception {
        requireProductScrollGate();
        assertTrue(preferences.edit().putStringSet(SettingsRepository.KEY_ENABLED_CATEGORIES, Collections.emptySet())
                .putBoolean(SettingsRepository.KEY_TEXT_SMUT_ENABLED, true).commit());
        context.startActivity(targetIntent().putExtra("nativeScrollFixture", true).putExtra("label", "porn"));
        startOwnedScrollLab(); connect(); main(() -> productInputCaptureEnabled = true);
        Map<String, Long> baseline = capturePositiveNodePaintPose("baseline", 0, null, false, null);
        CensorEngine.ActiveScope original = session.controller().activeScope();
        sustainedStartedAt = SystemClock.uptimeMillis(); rapidProductScroll(2200, 160);
        sustainedEndedAt = SystemClock.uptimeMillis();
        for (NativeTextFrame frame : nativeFrames) {
            if (frame.read.candidates.isEmpty() || frame.source.readStartedAtMillis < sustainedStartedAt
                    || frame.source.receivedAtMillis > sustainedEndedAt) continue;
            if (frame.read.coverage == NativeTextResult.Coverage.COMPLETE_TREE) acceptedDuringSustainedDemand++;
            else acceptedPositiveNodesOnlyDuringSustainedDemand++;
        }
        captureControlledPositiveNodePose("positive-forward", 320, 160, baseline);
        rapidProductScroll(1200, 320);
        captureControlledPositiveNodePose("positive-reversal", 80, -240, baseline);
        assertTrue("Actual positive-node reads contained inside sustained service demand, not renamed full scans",
                acceptedPositiveNodesOnlyDuringSustainedDemand >= 5);
        for (ScrollMotionLedger.Receipt receipt : scrollReceipts) assertScrollBranchPreservesNativeGeneration(receipt.sample.sourceAtMillis);
        assertFalse(explicitNativeDemand); assertTrue(batches.isEmpty());
        assertTrue(evidence.stream().allMatch(row -> row.source.kind == SceneEngine.SourceKind.NATIVE_TEXT));
        assertTrue(original.same(session.controller().activeScope())); assertTrue(faults.isEmpty()); assertBinding();
        productScrollPassed = true;
    }

    /** Fixed short acquisition diagnostic, not a retry or replacement for the failed sustainability gate. */
    @Test public void actualServiceWindowCostsKeepFullAndDirectParentBoundaries() throws Exception {
        requireProductScrollGate(); windowCostGate = true;
        assertTrue(preferences.edit().putStringSet(SettingsRepository.KEY_ENABLED_CATEGORIES, Collections.emptySet())
                .putBoolean(SettingsRepository.KEY_TEXT_SMUT_ENABLED, true).commit());
        context.startActivity(targetIntent().putExtra("nativeScrollFixture", true).putExtra("label", "porn"));
        Hold firstProvider = hold(); nativeReadHold.set(firstProvider);
        startOwnedScrollLab(); connect();
        assertTrue("Attach diagnostic before first actual WindowReader call", firstProvider.entered.await(8, TimeUnit.SECONDS));
        windowCostReader = (AccessibilityWindowReader) reflected(session.controller(), "windows");
        windowCostAttachedAt = SystemClock.uptimeMillis();
        windowCostReader.costDiagnostics().observer(new WindowReadCost.Observer() {
            @Override public void started(WindowReadCost.Call call) {
                assertEquals("Censor-native-text", Thread.currentThread().getName());
                if (windowCostStarts.size() >= 512) throw new IllegalStateException("Bounded diagnostic call inventory");
                windowCostStarts.add(call);
            }
            @Override public void completed(WindowReadCost.Sample sample) {
                if (windowCostSamples.size() >= 512) throw new IllegalStateException("Bounded diagnostic terminal inventory");
                windowCostSamples.add(sample);
            }
        });
        // The already-started first parent is intentionally outside resource-sample admission.
        // Future parent terminals remain observed through real close/cancellation and retirement.
        sustainedResourceObserver.enable(SystemClock.uptimeMillis());
        main(() -> productInputCaptureEnabled = true); firstProvider.release.countDown();
        await("Ordinary acquired FULL result before short scroll sequence", () -> nativeFrames.stream().anyMatch(frame ->
                frame.read.readMode == NativeTextResult.ReadMode.FULL_DISCOVERY && !frame.read.candidates.isEmpty()));
        CensorEngine.ActiveScope original = session.controller().activeScope();
        windowCostDemandStartedAt = SystemClock.uptimeMillis();
        int previous = 0; long finalReceiptAt = -1;
        for (int position : new int[]{160, 80}) {
            long requestedAt = SystemClock.uptimeMillis();
            ScrollMotionLedger.Receipt receipt = actualProductScroll(position, position - previous, original);
            windowCostCommands.put(new JSONObject().put("position", position).put("delta", position - previous)
                    .put("requestedAtMillis", requestedAt).put("sourceAtMillis", receipt.sample.sourceAtMillis)
                    .put("receivedAtMillis", receipt.sample.receivedAtMillis).put("admittedAtMillis", receipt.admittedAtMillis));
            previous = position; finalReceiptAt = receipt.admittedAtMillis;
        }
        final long afterFinalReceipt = finalReceiptAt;
        await("Actual DIRECT read begins after final observed scroll, no manufactured demand", () -> {
            for (com.subhub.app.censor.engine.NativeReadCost cost : nativeCosts)
                if (cost.readMode == NativeTextResult.ReadMode.ADMITTED_NODE_RECLASSIFY
                        && cost.readStartedAtMillis >= afterFinalReceipt
                        && cost.terminal == com.subhub.app.censor.engine.NativeReadCost.Terminal.SUCCESS) {
                    windowCostSelectedDirect = cost; return true;
                }
            return false;
        });
        windowCostDemandEndedAt = SystemClock.uptimeMillis();
        assertTrue(windowCostSamples.stream().anyMatch(sample -> sample.call.operation == WindowReadCost.Operation.OPEN_TREE));
        assertTrue(windowCostSamples.stream().anyMatch(sample -> sample.call.operation == WindowReadCost.Operation.OPEN_SCOPE));
        assertTrue(windowCostSamples.stream().anyMatch(sample -> sample.call.operation == WindowReadCost.Operation.CURRENT));
        assertFalse(explicitNativeDemand); assertTrue(batches.isEmpty()); assertTrue(faults.isEmpty());
        assertTrue(original.same(session.controller().activeScope())); assertBinding(); productScrollPassed = true;
    }

    /** Lifecycle-only adversary: no throughput, continuous-pixel or root-substitution qualification. */
    @Test public void actualServiceSampledCancellationSurvivesCutoffAndReaderRetirement() throws Exception {
        requireProductScrollGate(); nativeCutoffGate = true;
        assertTrue(preferences.edit().putStringSet(SettingsRepository.KEY_ENABLED_CATEGORIES, Collections.emptySet())
                .putBoolean(SettingsRepository.KEY_TEXT_SMUT_ENABLED, true).commit());
        context.startActivity(targetIntent().putExtra("nativeScrollFixture", true).putExtra("label", "porn"));
        Hold firstProvider = hold(); nativeReadHold.set(firstProvider);
        startOwnedScrollLab(); connect();
        assertTrue("Initial actual provider entry precedes observer attachment", firstProvider.entered.await(8, TimeUnit.SECONDS));
        sustainedResourceObserver.enable(SystemClock.uptimeMillis()); firstProvider.release.countDown();
        assertTrue("Future sampled parent has a real cancelled terminal after retirement", nativeCutoffTerminal.await(12, TimeUnit.SECONDS));
        await("Actual closed owner and all executors drain independently of diagnostic pending", session::managedResourcesDrained);
        assertTrue(nativeCutoffEpoch > 0); assertTrue(nativeCutoffRequest > 0); assertTrue(nativeCutoffTicket > 0);
        assertTrue(nativeCutoffReaderRetiredAtTerminal);
        ParentBoundResourceObserver.State state = sustainedResourceObserver.state();
        assertTrue(state.admissionsClosed); assertTrue(state.sampledParents > 0);
        assertEquals(state.sampledParents, state.terminalParents); assertEquals(0, state.pendingParents);
        assertEquals(5, nativeCutoffEvents.length());
        assertTrue(sustainedResourceFailures.isEmpty()); assertTrue(faults.isEmpty());
        assertEquals(1, nativeCosts.stream().filter(cost -> cost.epoch == nativeCutoffEpoch && cost.request == nativeCutoffRequest
                && cost.terminal == com.subhub.app.censor.engine.NativeReadCost.Terminal.CANCELLED).count());
        assertFalse(nativeFrames.stream().anyMatch(frame -> frame.source.epoch == nativeCutoffEpoch && frame.requestIdentity == nativeCutoffRequest));
        assertFalse(explicitNativeDemand); assertTrue(batches.isEmpty()); productScrollPassed = true;
    }

    private void nativeCutoffEvent(String phase) throws Exception {
        ParentBoundResourceObserver.State state = sustainedResourceObserver.state();
        nativeCutoffEvents.put(new JSONObject().put("sequence", nativeCutoffEvents.length() + 1).put("phase", phase)
                .put("atMillis", SystemClock.uptimeMillis()).put("epoch", nativeCutoffEpoch)
                .put("request", nativeCutoffRequest).put("observerParent", nativeCutoffTicket)
                .put("pendingParents", state.pendingParents).put("sampledParents", state.sampledParents)
                .put("terminalParents", state.terminalParents).put("admissionsClosed", state.admissionsClosed)
                .put("hostReaderRetired", reflected(nativeCutoffHost, "textReader") == null)
                .put("nativeOwnerThread", Thread.currentThread().getName().equals("Censor-native-text")));
    }

    /** Actual SCAN_END has completed; the unchanged delayed confirmation has not been scheduled yet. */
    private void cancelSampledNativeBeforeConfirmation(CensorEngine.NativeTextInput input) {
        if (!nativeCutoffGate || nativeCutoffRequest >= 0) return;
        try {
            JSONObject start = null;
            for (JSONObject row : sustainedResources)
                if (row.getString("phase").equals("START") && row.getLong("epoch") == input.epoch
                        && row.getLong("request") == input.identity) {
                    assertNull("One actual resource START per selected parent", start); start = row;
                }
            if (start == null) return; // The initially held parent began before observer admission.
            assertEquals("Censor-native-text", Thread.currentThread().getName());
            nativeCutoffEpoch = input.epoch; nativeCutoffRequest = input.identity;
            nativeCutoffTicket = start.getLong("observerParent");
            nativeCutoffHost = reflected(session.controller(), "host");
            assertNotNull(reflected(nativeCutoffHost, "textReader")); nativeCutoffEvent("SELECTED");
            sustainedResourceObserver.cutoff(SystemClock.uptimeMillis());
            assertEquals(1, sustainedResourceObserver.state().pendingParents); nativeCutoffEvent("CUTOFF");
            main(session::close); // Queues real serial retirement before the existing confirm future is created.
            assertTrue("Real close cancels this exact active input", input.cancelled());
            assertNotNull("Retirement cannot run concurrently with this native callback", reflected(nativeCutoffHost, "textReader"));
            nativeCutoffEvent("CLOSE_RETURN");
        } catch (Exception | Error failure) { faults.add(failure); throw new AssertionError("Native cutoff control failed", failure); }
    }

    /** Natural acquisition falsifier only: early Source publications are never independent parents. */
    @Test public void actualServiceNaturalCohortRenewalPreservesOriginalParentsAndNodeDeadlines() throws Exception {
        requireProductScrollGate(); naturalCohortRenewalGate = true;
        assertTrue(preferences.edit().putStringSet(SettingsRepository.KEY_ENABLED_CATEGORIES, Collections.emptySet())
                .putBoolean(SettingsRepository.KEY_TEXT_SMUT_ENABLED, true).commit());
        context.startActivity(targetIntent().putExtra("nativeScrollFixture", true).putExtra("label", "porn"));
        startOwnedScrollLab(); connect();
        main(() -> productInputCaptureEnabled = true); sustainedResourceObserver.enable(SystemClock.uptimeMillis());
        capturePositiveNodePaintPose("baseline", 0, null, false, null);
        CensorEngine.ActiveScope original = session.controller().activeScope();
        naturalRenewalStartedAt = SystemClock.uptimeMillis();
        long until = naturalRenewalStartedAt + 2200; int index = 1, lastPosition = 0;
        int[] reversal = {0, 160, 320, 160};
        while (SystemClock.uptimeMillis() < until) {
            lastPosition = reversal[index++ % reversal.length]; long issuedAt = SystemClock.uptimeMillis();
            context.startActivity(targetIntent().putExtra("scrollToY", lastPosition));
            naturalRenewalCommands.put(new JSONObject().put("ordinal", naturalRenewalCommands.length() + 1)
                    .put("position", lastPosition).put("issuedAtMillis", issuedAt).put("returnedAtMillis", SystemClock.uptimeMillis()));
            Thread.sleep(15);
        }
        naturalRenewalEndedAt = SystemClock.uptimeMillis();
        // A passive serial barrier finishes already executing callbacks. It creates no read,
        // does not await a delayed confirmation and never extends the fixed source cutoff.
        java.util.concurrent.ScheduledExecutorService nativeExecutor = (java.util.concurrent.ScheduledExecutorService)
                reflected(reflected(session.controller(), "host"), "nativeReads");
        naturalCallbackBoundaryAt = nativeExecutor.submit(SystemClock::uptimeMillis).get(12, TimeUnit.SECONDS);
        Set<String> partialParents = new LinkedHashSet<>(); long firstCohortRequest = -1; boolean subsequentFull = false;
        for (NativeTextFrame frame : nativeFrames) {
            if (frame.observationOrdinal != 0 || frame.read.candidates.isEmpty()
                    || frame.source.readStartedAtMillis < naturalRenewalStartedAt || frame.source.receivedAtMillis > until) continue;
            com.subhub.app.censor.engine.NativeReadCost terminal = null;
            for (com.subhub.app.censor.engine.NativeReadCost cost : nativeCosts)
                if (cost.epoch == frame.source.epoch && cost.request == frame.requestIdentity) {
                    assertNull("One actual terminal per native parent", terminal); terminal = cost;
                }
            assertNotNull("Admitted terminal Source has its actual parent terminal", terminal);
            assertEquals(com.subhub.app.censor.engine.NativeReadCost.Terminal.SUCCESS, terminal.terminal);
            if (frame.read.coverage == NativeTextResult.Coverage.POSITIVE_NODES_ONLY) {
                assertTrue("One partial terminal per actual parent", partialParents.add(frame.source.epoch + ":" + frame.requestIdentity));
                if (frame.read.readMode == NativeTextResult.ReadMode.ADMITTED_NODE_COHORT_RECLASSIFY) {
                    assertEquals("Natural cohort really contains two independent nodes", 2, frame.read.candidates.size());
                    if (firstCohortRequest < 0) firstCohortRequest = frame.requestIdentity;
                }
            }
            if (firstCohortRequest > 0 && frame.requestIdentity > firstCohortRequest
                    && frame.read.readMode == NativeTextResult.ReadMode.FULL_DISCOVERY) subsequentFull = true;
        }
        assertTrue("Unchanged first2200ms >=5 independent successful terminal PARTIAL parents", partialParents.size() >= 5);
        assertTrue("Natural demand actually exercises the new two-address cohort", firstCohortRequest > 0);
        assertTrue("A successful actual FULL follows the finite cohort pass", subsequentFull);
        assertOriginalNodeRenewalSupported(false); assertOriginalNodeRenewalSupported(true);
        long finalCommand = ++nextTargetCommand, finalIssuedAt = SystemClock.uptimeMillis();
        context.startActivity(targetIntent().putExtra("scrollToY", 0).putExtra("nativeCommand", finalCommand));
        await("Real tagged final target command is applied independently of queued scroll deltas", () -> targetCommandApplied(finalCommand, 0));
        naturalFinalCommand = new JSONObject().put("command", finalCommand).put("issuedAtMillis", finalIssuedAt)
                .put("providerObservedAtMillis", SystemClock.uptimeMillis()).put("position", 0);
        capturePositiveNodePaintPose("natural-final", 0, null, false, null);
        assertFalse("No hold or manual native request manufactures natural demand", explicitNativeDemand);
        assertTrue(ownedHolds.isEmpty()); assertTrue(batches.isEmpty()); assertTrue(faults.isEmpty());
        assertEquals(0, productInputBoundaryFailures.get()); assertTrue(original.same(session.controller().activeScope()));
        assertFalse("Actual native owner observations are required", naturalNativeOwners.isEmpty());
        for (NativeOwnerObservation observation : naturalNativeOwners) {
            assertSame("One captured serial native owner", naturalNativeOwner.get(), observation.thread);
            assertFalse("Native priority is observed on its owner, not main", observation.mainThread);
            assertEquals("Actual kernel priority, not requested Java Thread priority", Process.THREAD_PRIORITY_DEFAULT, observation.priority);
        }
        assertBinding(); productScrollPassed = true;
    }

    private static final class NativeOwnerObservation {
        final com.subhub.app.censor.engine.NativeReadCost.Start start;
        final Thread thread;
        final int tid, priority;
        final long sampledAtMillis;
        final boolean mainThread;
        NativeOwnerObservation(com.subhub.app.censor.engine.NativeReadCost.Start start) {
            this.start = start; thread = Thread.currentThread(); tid = Process.myTid();
            priority = Process.getThreadPriority(tid); sampledAtMillis = SystemClock.uptimeMillis();
            mainThread = Looper.myLooper() == Looper.getMainLooper();
        }
    }

    private static final class NativeArrivalObservation {
        final NativeTextFrame frame; final long callbackAtMillis;
        NativeArrivalObservation(NativeTextFrame frame, long at) { this.frame = frame; callbackAtMillis = at; }
    }

    private void assertOriginalNodeRenewalSupported(boolean callbackClock) throws Exception {
        JSONArray members = productScrollSteps.getJSONObject(0).getJSONObject("currentPaint").getJSONArray("members");
        assertEquals("Two exact original baseline nodes", 2, members.length());
        for (int member = 0; member < members.length(); member++) {
            JSONObject original = members.getJSONObject(member); long deadline = -1;
            for (NativeArrivalObservation observation : naturalNativeArrivals) {
                NativeTextFrame frame = observation.frame;
                long available = callbackClock ? observation.callbackAtMillis : frame.source.receivedAtMillis;
                if (available > naturalRenewalEndedAt) break;
                if (available >= naturalRenewalStartedAt)
                    assertTrue("Original node has unsupported " + (callbackClock ? "callback-available" : "source-receipt")
                            + " interval: " + original.getString("fixtureMember") + " " + Math.max(naturalRenewalStartedAt, deadline) + ".." + available,
                            deadline >= available);
                NativeTextResult.Candidate selected = null;
                for (NativeTextResult.Candidate candidate : frame.read.candidates)
                    if (original.getString("nodeKey").equals(candidate.nodeKey)) {
                        assertNull("One exact original node per Source", selected); selected = candidate;
                        assertEquals("Original node content never changed", original.getString("contentKey"), candidate.contentKey);
                    }
                if (selected != null) deadline = Math.max(deadline, frame.candidateValidUntilMillis(selected));
                else if (frame.read.coverage == NativeTextResult.Coverage.COMPLETE_TREE) deadline = Math.min(deadline, available);
            }
            assertTrue("Original candidate support reaches actual demand end without renewed age", deadline >= naturalRenewalEndedAt);
        }
    }

    @Test public void actualServiceNativeSustainabilityDiscoversAndWithdrawsNewNodeThroughThirtySeconds() throws Exception {
        requireProductScrollGate(); sustainedNativeGate = true;
        assertTrue(preferences.edit().putStringSet(SettingsRepository.KEY_ENABLED_CATEGORIES, Collections.emptySet())
                .putBoolean(SettingsRepository.KEY_TEXT_SMUT_ENABLED, true).commit());
        context.startActivity(targetIntent().putExtra("nativeScrollFixture", true).putExtra("label", "porn"));
        startOwnedScrollLab(); connect();
        main(() -> productInputCaptureEnabled = true); sustainedResourceObserver.enable(SystemClock.uptimeMillis());
        capturePositiveNodePaintPose("baseline", 0, null, false, null);
        CensorEngine.ActiveScope original = session.controller().activeScope();
        sustainedStartedAt = SystemClock.uptimeMillis(); int index = 1;
        int[] reversal = {0, 160, 320, 160};
        while (SystemClock.uptimeMillis() - sustainedStartedAt < 30000) {
            if (addedMutation == null && SystemClock.uptimeMillis() - sustainedStartedAt >= 15000) {
                sustainedAddedVisible = true;
                addedMutation = mutateSustainedNode(true);
                AtomicReference<Map<String, RectF>> independent = new AtomicReference<>();
                await("Actual added View has usable provider glyphs", () -> {
                    Map<String, RectF> value = productProviderGlyphs(); if (value == null) return false;
                    independent.set(value); return true;
                });
                productGlyphBaseline.put("added", new RectF(independent.get().get("added")));
                addedMutation.put("independentAddedGlyphs", glyphArray(independent.get().get("added")));
                addedMutation.put("glyphsObservedAtMillis", SystemClock.uptimeMillis());
            }
            sustainedCommandTimes.add(SystemClock.uptimeMillis());
            context.startActivity(targetIntent().putExtra("scrollToY", reversal[index++ % reversal.length]));
            sustainedCommands++; Thread.sleep(15);
        }
        sustainedEndedAt = SystemClock.uptimeMillis();
        for (NativeTextFrame frame : nativeFrames) {
            if (frame.read.candidates.isEmpty() || frame.source.readStartedAtMillis < sustainedStartedAt
                    || frame.source.receivedAtMillis > sustainedEndedAt) continue;
            if (frame.read.coverage == NativeTextResult.Coverage.COMPLETE_TREE) acceptedDuringSustainedDemand++;
            else {
                acceptedPositiveNodesOnlyDuringSustainedDemand++;
                if (frame.observationOrdinal == 0 && frame.source.receivedAtMillis <= sustainedStartedAt + 2200)
                    assertTrue("One terminal partial per actual independent parent", sustainedInitialParents.add(frame.requestIdentity));
            }
        }
        assertTrue("Original first2200ms independent acquisition control", sustainedInitialParents.size() >= 5);
        assertNotNull("Actual new View introduced during demand", addedMutation);
        SceneEngine.Region addedLine = expectedProductLine(productGlyphBaseline.get("added"));
        for (NativeTextFrame frame : nativeFrames) {
            if (frame.read.readMode != NativeTextResult.ReadMode.FULL_DISCOVERY
                    || frame.source.readStartedAtMillis < addedMutation.getLong("providerObservedAtMillis")
                    || frame.source.receivedAtMillis > sustainedEndedAt) continue;
            for (NativeTextResult.Candidate candidate : frame.read.candidates)
                if (candidate.exactGeometry && candidate.regions.size() == 1 && sameProductLine(candidate.regions.get(0), addedLine)) {
                    sustainedDiscovery = frame.source; break;
                }
            if (sustainedDiscovery != null) break;
        }
        assertNotNull("New positive discovered by an actual FULL during ongoing scroll, not only after it stops", sustainedDiscovery);
        lastRapidCommand = ++nextTargetCommand;
        context.startActivity(targetIntent().putExtra("scrollToY", 320).putExtra("nativeCommand", lastRapidCommand));
        Map<String, Long> forward = capturePositiveNodePaintPose("sustained-forward", 320, null, false, null);
        assertEquals(3, new HashSet<>(forward.values()).size());
        assertNotNull(sustainedForwardPaint);
        NativeTextFrame paintedOrigin = sustainedForwardPaint.frames.get("added");
        sustainedWithdrawalDeadline = paintedOrigin.candidateValidUntilMillis(sustainedForwardPaint.candidates.get("added"));
        String removedKey = sustainedForwardPaint.candidates.get("added").nodeKey;
        Set<String> originals = new HashSet<>(Arrays.asList(sustainedForwardPaint.candidates.get("moving").nodeKey,
                sustainedForwardPaint.candidates.get("fixed").nodeKey));
        removedMutation = mutateSustainedNode(false); sustainedAddedVisible = false;
        await("Fresh complete-tree removal, not TTL-only or partial negative authority", () -> {
            for (NativeTextFrame frame : nativeFrames) {
                if (frame.read.readStartedAtMillis >= removedMutation.optLong("providerObservedAtMillis", Long.MAX_VALUE)
                        && frame.observationOrdinal == 0 && frame.read.coverage == NativeTextResult.Coverage.COMPLETE_TREE
                        && frame.read.candidates.size() == 2) {
                    Set<String> actual = new HashSet<>(); for (NativeTextResult.Candidate candidate : frame.read.candidates) actual.add(candidate.nodeKey);
                    if (!actual.contains(removedKey) && actual.equals(originals)) { sustainedRemoval = frame.source; return true; }
                }
            }
            return false;
        });
        capturePositiveNodePaintPose("sustained-withdrawal", 320, null, false, null);
        lastRapidCommand = ++nextTargetCommand;
        context.startActivity(targetIntent().putExtra("scrollToY", 80).putExtra("nativeCommand", lastRapidCommand));
        capturePositiveNodePaintPose("sustained-reversal", 80, null, false, null);
        assertFalse(explicitNativeDemand); assertTrue(batches.isEmpty()); assertTrue(sustainedResourceFailures.toString(), sustainedResourceFailures.isEmpty());
        assertFalse(sustainedResources.isEmpty()); assertTrue(original.same(session.controller().activeScope()));
        assertTrue(faults.isEmpty()); assertBinding(); productScrollPassed = true;
    }

    private static JSONArray glyphArray(RectF glyphs) throws org.json.JSONException {
        return new JSONArray().put(glyphs.left).put(glyphs.top).put(glyphs.right).put(glyphs.bottom);
    }

    private JSONObject mutateSustainedNode(boolean present) throws Exception {
        long command = ++nextTargetCommand, requested = SystemClock.uptimeMillis();
        context.startActivity(targetIntent().putExtra("nativeCommand", command).putExtra("nativeAdded", present));
        AtomicLong observedAt = new AtomicLong(-1);
        await("Literal actual target mutation and child inventory", () -> {
            android.view.accessibility.AccessibilityNodeInfo root = ui.getRootInActiveWindow(); if (root == null) return false;
            try {
                if (!TARGET.contentEquals(root.getPackageName()) || root.getWindowId() != session.window().id) return false;
                List<android.view.accessibility.AccessibilityNodeInfo> controls = root.findAccessibilityNodeInfosByViewId("android:id/custom");
                List<android.view.accessibility.AccessibilityNodeInfo> children = root.findAccessibilityNodeInfosByViewId("android:id/message");
                try {
                    if (controls.size() != 1 || !controls.get(0).refresh()
                            || controls.get(0).getExtras().getLong(ProductTargetFixtureActivity.NATIVE_MUTATION, -1) != command
                            || controls.get(0).getExtras().getBoolean(ProductTargetFixtureActivity.NATIVE_ADDED, !present) != present
                            || children.size() != (present ? 1 : 0)) return false;
                    if (present && (!children.get(0).refresh() || !"porn".contentEquals(children.get(0).getText()))) return false;
                    observedAt.set(SystemClock.uptimeMillis()); return true;
                } finally {
                    for (android.view.accessibility.AccessibilityNodeInfo node : controls) node.recycle();
                    for (android.view.accessibility.AccessibilityNodeInfo node : children) node.recycle();
                }
            } finally { root.recycle(); }
        });
        return new JSONObject().put("command", command).put("requestedAtMillis", requested)
                .put("providerObservedAtMillis", observedAt.get()).put("present", present);
    }

    private static Object reflected(Object owner, String name) throws Exception {
        java.lang.reflect.Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }
    private static int inventory(Object owner, String name) throws Exception {
        Object value = reflected(owner, name);
        return value instanceof Map ? ((Map<?, ?>) value).size() : ((Collection<?>) value).size();
    }
    private static final class SustainedResourceOwner {
        final Object addresses;
        final CensorEngine<PixelFrame> engine;
        final AccessibilityScrollReader scroll;
        SustainedResourceOwner(Object addresses, CensorEngine<PixelFrame> engine, AccessibilityScrollReader scroll) {
            this.addresses = Objects.requireNonNull(addresses); this.engine = Objects.requireNonNull(engine);
            this.scroll = Objects.requireNonNull(scroll);
        }
    }
    /** Reader data is inspected only on its real serial executor, never from main or the test thread. */
    private void sampleSustainedResources(String phase, long epoch, long request) {
        ParentBoundResourceObserver.Ticket<SustainedResourceOwner> ticket = null;
        try {
            if (phase.equals("START") && !sustainedResourceObserver.accepting()) return;
            if (phase.equals("START")) {
                if (!Thread.currentThread().getName().equals("Censor-native-text")) throw new IllegalStateException("Native reader owner thread differs");
                ticket = sustainedResourceObserver.reserve(epoch, request, SystemClock.uptimeMillis());
                if (ticket == null) return;
                AccessibilityCensorController controller = session.controller();
                Object host = reflected(controller, "host"), reader = reflected(host, "textReader");
                // This callback runs before read begins on the same executor as Reader retirement.
                // These are passive references, not cloned nodes or a lease on native work.
                SustainedResourceOwner owner = new SustainedResourceOwner(reflected(reader, "identities"),
                        actualEngineForControl(), (AccessibilityScrollReader) reflected(controller, "scrollReader"));
                sustainedResourceObserver.bind(ticket, owner);
            } else if (phase.equals("TERMINAL")) ticket = sustainedResourceObserver.terminal(epoch, request);
            else throw new IllegalArgumentException("Unknown resource callback phase");
            if (ticket == null) return;
            SustainedResourceOwner owner = ticket.owner(); CensorEngine<PixelFrame> engine = owner.engine;
            JSONObject row = new JSONObject().put("atMillis", SystemClock.uptimeMillis()).put("phase", phase).put("request", request)
                    .put("epoch", epoch).put("observerParent", ticket.identity)
                    .put("observerAdmittedAtMillis", ticket.admittedAtMillis)
                    .put("readerOwnerThread", true).put("addressCount", inventory(owner.addresses, "entries"))
                    .put("addressLimit", NativeTextResult.MAXIMUM_CANDIDATES);
            synchronized (engine) {
                SceneEngine scene = (SceneEngine) reflected(engine, "scene");
                synchronized (scene) {
                    SceneEngine.Policy policy = (SceneEngine.Policy) reflected(scene, "policy");
                    CensorEngine.Policy runtime = (CensorEngine.Policy) reflected(engine, "policy");
                    row.put("sourceCount", inventory(scene, "sources")).put("sourceLimit", policy.maximumSources)
                            .put("trackCount", inventory(scene, "tracks")).put("trackLimit", policy.maximumTracks)
                            .put("nativeOriginCount", inventory(scene, "nativeOrigins")).put("nativeOriginLimit", policy.maximumSources)
                            .put("renderBasisCount", inventory(engine, "renderBases")).put("renderBasisLimit", reflected(engine, "maximumRenderBases"))
                            .put("pixelFrameCount", inventory(engine, "frames")).put("pixelFrameLimit", runtime.maximumPixelFrames);
                }
            }
            row.put("scrollCopiedCount", owner.scroll.outstanding())
                    .put("scrollCopiedLimit", AccessibilityScrollReader.MAXIMUM_OUTSTANDING)
                    .put("scrollIdentityCount", reflected(owner.scroll, "identityCount")).put("scrollIdentityLimit", AccessibilityScrollReader.MAXIMUM_IDENTITIES);
            sustainedResources.add(row);
            if (nativeCutoffGate && phase.equals("TERMINAL") && epoch == nativeCutoffEpoch && request == nativeCutoffRequest) {
                nativeCutoffReaderRetiredAtTerminal = reflected(nativeCutoffHost, "textReader") == null;
                assertTrue("Terminal observes actual reader-field retirement, not merely a close request", nativeCutoffReaderRetiredAtTerminal);
                nativeCutoffEvent("TERMINAL");
            }
        } catch (Exception | Error failure) { sustainedResourceFailures.add(failure.getClass().getSimpleName()); }
        finally {
            if (phase.equals("TERMINAL") && ticket != null) {
                try { sustainedResourceObserver.finish(ticket, SystemClock.uptimeMillis()); }
                catch (Exception | Error failure) { sustainedResourceFailures.add(failure.getClass().getSimpleName()); }
                if (nativeCutoffGate && epoch == nativeCutoffEpoch && request == nativeCutoffRequest) {
                    try { nativeCutoffEvent("TICKET_FINISHED"); }
                    catch (Exception | Error failure) { sustainedResourceFailures.add(failure.getClass().getSimpleName()); }
                    nativeCutoffTerminal.countDown();
                }
            }
        }
    }

    private void rapidProductScroll(long durationMillis, int finalPosition) throws Exception {
        long until = SystemClock.uptimeMillis() + durationMillis; int index = 1;
        int[] reversal = {0, 160, 320, 160};
        while (SystemClock.uptimeMillis() < until) {
            context.startActivity(targetIntent().putExtra("scrollToY", reversal[index++ % reversal.length])); Thread.sleep(15);
        }
        lastRapidCommand = ++nextTargetCommand;
        context.startActivity(targetIntent().putExtra("scrollToY", finalPosition).putExtra("nativeCommand", lastRapidCommand));
    }

    private long nextTargetCommand, lastRapidCommand;
    private boolean targetCommandApplied(long command, int position) {
        android.view.accessibility.AccessibilityNodeInfo root = ui.getRootInActiveWindow(); if (root == null) return false;
        try {
            if (!TARGET.contentEquals(root.getPackageName()) || root.getWindowId() != session.window().id) return false;
            List<android.view.accessibility.AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId("android:id/custom");
            try {
                return nodes.size() == 1 && nodes.get(0).refresh()
                        && nodes.get(0).getExtras().getLong(ProductTargetFixtureActivity.NATIVE_COMMAND, -1) == command
                        && nodes.get(0).getExtras().getInt(ProductTargetFixtureActivity.NATIVE_SCROLL_Y, -1) == position;
            } finally { for (android.view.accessibility.AccessibilityNodeInfo node : nodes) node.recycle(); }
        } finally { root.recycle(); }
    }
    private long awaitControlledStartingPose(int position) throws Exception {
        AtomicLong serviceSource = new AtomicLong(-1);
        await("Final target command and real starting glyph/service scroll pose precede the endpoint hold", () -> {
            if (!targetCommandApplied(lastRapidCommand, position)) return false;
            Map<String, RectF> glyphs = productProviderGlyphs(); if (glyphs == null) return false;
            for (String name : glyphs.keySet()) {
                RectF expected = new RectF(productGlyphBaseline.get(name)); if (name.equals("moving")) expected.offset(0, -position);
                if (!expected.equals(glyphs.get(name))) return false;
            }
            if (scrollReceipts.isEmpty()) return false;
            ScrollMotionLedger.Receipt latest = scrollReceipts.get(scrollReceipts.size() - 1);
            if (!latest.sample.y.absoluteKnown || latest.sample.y.absolute != position
                    || latest.sample.scope.windowId != session.window().id) return false;
            serviceSource.set(latest.sample.sourceAtMillis); return true;
        });
        return serviceSource.get();
    }

    /** Controlled endpoint only: these settled screenshots do not qualify continuous motion. */
    private void captureControlledPositiveNodePose(String label, int position, int delta,
            Map<String, Long> identities) throws Exception {
        long startingServiceSource = awaitControlledStartingPose(position - delta);
        long startingObservedAt = SystemClock.uptimeMillis();
        Hold selected = hold(), successor = hold();
        assertTrue(nativeReadHold.compareAndSet(null, selected));
        try {
            assertTrue("Autonomous serial provider entry is held after beginRead", selected.entered.await(8, TimeUnit.SECONDS));
            CensorEngine<PixelFrame> engine = actualEngineForControl(); JSONObject control = new JSONObject();
            synchronized (engine) {
                ScrollMotionLedger.Scope scope = engine.scrollScope(); assertNotNull(scope);
                control.put("heldObservedAtMillis", SystemClock.uptimeMillis()).put("epoch", scope.epoch)
                        .put("windowId", scope.windowId).put("hardGeneration", actualNativeGeneration())
                        .put("heldDirtyGeneration", engine.nativeTreeGeneration())
                        .put("providerEntryOrdinal", nativeProviderEntries.get());
            }
            control.put("settledBeforeCommandId", lastRapidCommand).put("settledBeforePosition", position - delta)
                    .put("settledServiceScrollSourceAtMillis", startingServiceSource).put("settledBeforeObservedAtMillis", startingObservedAt);
            // Keep the first admitted endpoint available long enough to independently sample it.
            // This does not extend its original candidate TTL or count as sustained demand.
            assertTrue(nativeHoldAfterAccepted.compareAndSet(null, successor));
            control.put("commandAtMillis", SystemClock.uptimeMillis());
            ScrollMotionLedger.Receipt receipt = actualProductScroll(position, delta, session.controller().activeScope(), control.getLong("commandAtMillis"));
            await("Real independent SUBTREE revision reaches the service while the serial read is held",
                    () -> engine.nativeTreeGeneration() > control.optLong("heldDirtyGeneration"));
            synchronized (engine) {
                assertEquals("Soft endpoint change cannot cancel the real native operation",
                        control.getLong("hardGeneration"), actualNativeGeneration());
                control.put("revisedDirtyGeneration", engine.nativeTreeGeneration());
            }
            control.put("scrollSourceAtMillis", receipt.sample.sourceAtMillis)
                    .put("scrollReceivedAtMillis", receipt.sample.receivedAtMillis)
                    .put("releasedAtMillis", SystemClock.uptimeMillis());
            selected.release.countDown();
            capturePositiveNodePaintPose(label, position, identities, true, control);
        } finally { selected.release.countDown(); successor.release.countDown(); }
    }

    private Intent targetIntent() {
        return new Intent().setComponent(new ComponentName(TARGET, ProductTargetFixtureActivity.class.getName()))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
    }
    private static boolean positiveCompleteNativeFrame(NativeTextFrame frame) {
        return frame.read.coverage == NativeTextResult.Coverage.COMPLETE_TREE && !frame.read.candidates.isEmpty();
    }
    private void assertScrollBranchPreservesNativeGeneration(long sourceAt) throws Exception {
        JSONObject before = null, after = null;
        for (JSONObject row : productInputBoundaries) {
            if (!row.getString("route").equals("SCROLL") || row.getLong("sourceAtMillis") != sourceAt) continue;
            if (row.getBoolean("completed")) { assertNull("One literal terminal route", after); after = row; }
            else { assertNull("One literal entry route", before); before = row; }
        }
        assertNotNull("Actual synchronous service scroll entry", before); assertNotNull("Actual synchronous service scroll terminal", after);
        assertEquals(before.getLong("epoch"), after.getLong("epoch"));
        assertEquals("The SCROLLED branch cannot manufacture semantic cancellation; independent CONTENT events remain fenced",
                before.getLong("nativeGeneration"), after.getLong("nativeGeneration"));
        assertEquals("SCROLLED cannot mint a dirty-tree revision", before.getLong("nativeTreeGeneration"), after.getLong("nativeTreeGeneration"));
        assertEquals("SCROLLED cannot change protected window authority", before.getInt("scopeWindowId"), after.getInt("scopeWindowId"));
    }
    private long actualNativeGeneration() throws Exception {
        CensorEngine<PixelFrame> engine = actualEngineForControl();
        java.lang.reflect.Field field = CensorEngine.class.getDeclaredField("nativeGeneration"); field.setAccessible(true);
        synchronized (engine) { return field.getLong(engine); }
    }
    private JSONObject actualImagePresentation(CensorEngine<PixelFrame> engine) throws Exception {
        JSONArray members = new JSONArray(); long sampledAt = SystemClock.uptimeMillis(), nextChange = -1;
        try (CensorEngine<PixelFrame>.RenderView view = engine.acquireRenderView()) {
            if (view != null) {
                nextChange = view.snapshot.nextChangeAtMillis;
                for (SceneEngine.Group group : view.snapshot.groups) for (SceneEngine.Mask member : group.members) {
                    assertFalse(member.nativeValidated);
                    members.put(new JSONObject().put("identity", member.identity).put("displayRegion", regionMetadata(member.displayRegion))
                            .put("geometrySource", viewportSource(member.geometrySource)).put("evidenceSource", viewportSource(member.evidenceSource))
                            .put("evidenceRegion", regionMetadata(member.evidenceRegion)).put("confirmed", member.confirmed)
                            .put("estimatedMotion", member.estimatedMotion).put("imageEstimated", member.imageEstimated));
                }
            }
        }
        JSONArray subjects = new JSONArray();
        if (!evidence.isEmpty()) for (SceneEngine.AcceptedSubject subject : evidence.get(evidence.size() - 1).subjects) {
            JSONArray witnesses = new JSONArray();
            for (SceneEngine.EvidenceWitness witness : subject.witnesses)
                witnesses.put(new JSONObject().put("kind", witness.kind.name()).put("producerIdentity", witness.producerIdentity)
                        .put("sourceSequence", witness.sourceSequence));
            subjects.put(new JSONObject().put("identity", subject.identity).put("validUntilMillis", subject.validUntilMillis)
                    .put("confirmedForConsumption", subject.confirmedForConsumption).put("witnesses", witnesses));
        }
        return new JSONObject().put("sampledAtMillis", sampledAt).put("nextChangeAtMillis", nextChange)
                .put("rawMemberCount", members.length()).put("members", members).put("rawSubjectCount", subjects.length()).put("subjects", subjects);
    }
    private int assertImagePresentationUnchanged(JSONObject baseline, JSONObject current) throws Exception {
        assertEquals("Scroll receipts cannot renew evidence witnesses/confirmation/deadlines", baseline.getJSONArray("subjects").toString(), current.getJSONArray("subjects").toString());
        JSONArray old = baseline.getJSONArray("members"), fresh = current.getJSONArray("members");
        for (int index = 0; index < fresh.length(); index++) {
            JSONObject member = fresh.getJSONObject(index); JSONObject expected = null;
            for (int previous = 0; previous < old.length(); previous++)
                if (old.getJSONObject(previous).getLong("identity") == member.getLong("identity")) expected = old.getJSONObject(previous);
            assertNotNull("Scroll receipts cannot manufacture another visible member", expected);
            assertEquals("No viewport-wide translation or changed source provenance", expected.toString(), member.toString());
        }
        // Ordinary expiry/withdrawal may remove a displayed member; it cannot translate or renew one.
        return fresh.length();
    }
    private void requireProductScrollGate() { assertNotNull("Owned product_scroll_run namespace required", productScrollEvidence); }
    private void startOwnedScrollLab() throws Exception {
        assertFalse("Never borrow an active Lab session", CensorLabRecorder.isActive());
        SharedPreferences lab = context.getSharedPreferences("censor_lab_sessions", Context.MODE_PRIVATE);
        savedLabPreferences = copy(lab.getAll()); assertFalse("No abandoned owned session may be deleted", lab.contains("active_session"));
        File sessions = new File(context.getFilesDir(), "censor-lab/sessions");
        File[] children = sessions.listFiles(); assertTrue("This gate never prunes earlier Lab evidence", children == null || children.length < 3);
        CensorLabRecorder.start(context); ownedLabStarted = true;
    }
    private ScrollMotionLedger.Receipt actualProductScroll(int position, int delta, CensorEngine.ActiveScope scope) throws Exception {
        return actualProductScroll(position, delta, scope, -1);
    }
    private ScrollMotionLedger.Receipt actualProductScroll(int position, int delta, CensorEngine.ActiveScope scope, long endpointCommandAt) throws Exception {
        int before = scrollReceipts.size();
        android.view.accessibility.AccessibilityEvent event = ui.executeAndWaitForEvent(
                () -> context.startActivity(targetIntent().putExtra("scrollToY", position)),
                value -> value.getEventType() == android.view.accessibility.AccessibilityEvent.TYPE_VIEW_SCROLLED
                        && TARGET.contentEquals(value.getPackageName()) && value.getWindowId() == session.window().id
                        && (endpointCommandAt < 0 || (value.getEventTime() >= endpointCommandAt && value.getScrollY() == position
                            && (delta > 0 ? value.getScrollDeltaY() > 0 : value.getScrollDeltaY() < 0))), 12000);
        final long eventAt;
        try {
            assertEquals("android.widget.ScrollView", event.getClassName().toString());
            assertEquals("Actual View scroll position", position, event.getScrollY());
            if (endpointCommandAt < 0) assertEquals("Actual View scroll displacement", delta, event.getScrollDeltaY());
            else assertTrue("A coalesced endpoint displacement keeps actual signed direction",
                    delta > 0 ? event.getScrollDeltaY() > 0 : event.getScrollDeltaY() < 0);
            eventAt = event.getEventTime();
        } finally { event.recycle(); }
        AtomicReference<ScrollMotionLedger.Receipt> selected = new AtomicReference<>();
        await("Framework event reached actual product ledger observer", () -> {
            List<ScrollMotionLedger.Receipt> current = new ArrayList<>(scrollReceipts);
            for (ScrollMotionLedger.Receipt receipt : current.subList(before, current.size()))
                if (receipt.sample.sourceAtMillis == eventAt && receipt.sample.y.absoluteKnown && receipt.sample.y.absolute == position
                        && receipt.sample.y.deltaKnown && (endpointCommandAt < 0 ? receipt.sample.y.delta == delta
                            : delta > 0 ? receipt.sample.y.delta > 0 : receipt.sample.y.delta < 0)) { selected.set(receipt); return true; }
            return false;
        });
        ScrollMotionLedger.Receipt result = selected.get();
        assertEquals(scope.epoch, result.sample.scope.epoch); assertEquals(scope.geometry, result.sample.scope.geometry);
        assertEquals(scope.protectionScope, result.sample.scope.protection); assertEquals(session.window().id, result.sample.scope.windowId);
        assertTrue(result.sample.sourceAtMillis <= result.sample.receivedAtMillis);
        assertTrue(result.sample.receivedAtMillis <= result.sample.readStartedAtMillis);
        assertTrue(result.sample.readStartedAtMillis <= result.sample.readFinishedAtMillis);
        assertTrue(result.sample.readFinishedAtMillis <= result.admittedAtMillis);
        assertTrue(result.sample.owner > 0); assertTrue(result.sample.producer > 0); return result;
    }

    /** Independent provider glyph locations, not node rectangles or screenshot-derived offsets. */
    private Map<String, RectF> productProviderGlyphs() {
        AccessibilityWindowReader.Window active = session.window();
        if (active == null || !session.isActive()) return null; // Binding precedes asynchronous target selection.
        android.view.accessibility.AccessibilityNodeInfo root = ui.getRootInActiveWindow(); if (root == null) return null;
        Map<String, RectF> result = new LinkedHashMap<>();
        try {
            if (!TARGET.contentEquals(root.getPackageName()) || root.getWindowId() != active.id) return null;
            for (String name : sustainedAddedVisible ? new String[]{"moving", "fixed", "added"} : new String[]{"moving", "fixed"}) {
                List<android.view.accessibility.AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(
                        name.equals("moving") ? "android:id/text1" : name.equals("fixed") ? "android:id/text2" : "android:id/message");
                try {
                    if (nodes.size() != 1) return null;
                    android.view.accessibility.AccessibilityNodeInfo node = nodes.get(0);
                    if (!node.refresh() || !"porn".contentEquals(node.getText())) return null;
                    assertNull("Ordinary View lacks a supplied persistent unique ID", node.getUniqueId());
                    String key = android.view.accessibility.AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY;
                    android.os.Bundle args = new android.os.Bundle();
                    args.putInt(android.view.accessibility.AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, 0);
                    args.putInt(android.view.accessibility.AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, 4);
                    if (!node.refreshWithExtraData(key, args) || !"porn".contentEquals(node.getText())) return null;
                    android.os.Parcelable[] locations = node.getExtras().getParcelableArray(key);
                    if (locations == null || locations.length != 4) return null;
                    RectF union = null;
                    for (android.os.Parcelable location : locations) {
                        if (!(location instanceof RectF)) return null;
                        RectF glyph = (RectF) location;
                        if (glyph.isEmpty() || !Float.isFinite(glyph.left) || !Float.isFinite(glyph.top)
                                || !Float.isFinite(glyph.right) || !Float.isFinite(glyph.bottom)) return null;
                        if (union == null) union = new RectF(glyph); else union.union(glyph);
                    }
                    result.put(name, union);
                } finally { for (android.view.accessibility.AccessibilityNodeInfo node : nodes) if (node != null && node != root) node.recycle(); }
            }
            return result;
        } finally { root.recycle(); }
    }
    private static SceneEngine.Region expectedProductLine(RectF glyphs) {
        // Exact production line contract: floor/ceil glyph union plus two px edge padding.
        return new SceneEngine.Region(Math.floor(glyphs.left) - 2, Math.floor(glyphs.top) - 2,
                Math.ceil(glyphs.right) + 2, Math.ceil(glyphs.bottom) + 2);
    }
    private static boolean sameProductLine(SceneEngine.Region a, SceneEngine.Region b) {
        return a.left == b.left && a.top == b.top && a.right == b.right && a.bottom == b.bottom;
    }
    private static boolean productColor(int actual, int expected) {
        return Math.abs(Color.red(actual) - Color.red(expected)) <= 2 && Math.abs(Color.green(actual) - Color.green(expected)) <= 2
                && Math.abs(Color.blue(actual) - Color.blue(expected)) <= 2;
    }
    private static boolean productLinePixels(Bitmap image, SceneEngine.Region line, float density) {
        int left = (int) line.left, top = (int) line.top, right = (int) line.right, bottom = (int) line.bottom;
        if (left < 8 || top < 8 || right + 8 >= image.getWidth() || bottom + 8 >= image.getHeight()) return false;
        // The existing Box appearance is an 8dp round-rect, not a filled square rectangle.
        int radius = (int) Math.ceil(Math.min(8 * density, Math.min(right - left, bottom - top) / 2f));
        if (right - left <= 2 * radius + 6 || radius < 8) return false;
        for (int y : new int[]{top + 2, (top + bottom) / 2, bottom - 3})
            for (int x : new int[]{left + radius + 2, (left + right) / 2, right - radius - 3})
                if (!productColor(image.getPixel(x, y), COVER)) return false;
        for (int[] outside : new int[][]{{left - 6, (top + bottom) / 2}, {right + 6, (top + bottom) / 2},
                {(left + right) / 2, top - 6}, {(left + right) / 2, bottom + 6}})
            if (!productColor(image.getPixel(outside[0], outside[1]), ProductTargetFixtureActivity.UNDERLYING)) return false;
        for (int[] corner : new int[][]{{left + 2, top + 2}, {right - 3, top + 2}, {left + 2, bottom - 3}, {right - 3, bottom - 3}})
            if (!productColor(image.getPixel(corner[0], corner[1]), ProductTargetFixtureActivity.UNDERLYING)) return false;
        return true;
    }
    private Map<String, Long> captureProductScrollPose(String label, int position, Map<String, Long> identities) throws Exception {
        Bitmap latest = null; long deadline = SystemClock.uptimeMillis() + 12000;
        try {
            while (SystemClock.uptimeMillis() < deadline) {
                Map<String, RectF> provider = productProviderGlyphs(); if (provider == null) { Thread.sleep(10); continue; }
                AtomicReference<AccessibilityCensorController.WindowCoverage> observed = new AtomicReference<>();
                main(() -> observed.set(session.controller().viewportCoverage()));
                AccessibilityCensorController.WindowCoverage coverage = observed.get();
                if (!coverage.windowQualified || coverage.regions.size() != 2 || !session.controller().coverageCurrent(coverage)) {
                    Thread.sleep(10); continue;
                }
                Map<String, Long> groups = new LinkedHashMap<>(); Map<String, SceneEngine.Region> lines = new LinkedHashMap<>();
                for (Map.Entry<String, RectF> entry : provider.entrySet()) {
                    SceneEngine.Region expected = expectedProductLine(entry.getValue());
                    for (AccessibilityCensorController.WindowRegion region : coverage.regions)
                        if (region.member.nativeValidated && sameProductLine(expected, region.displayRegion)) {
                            assertNull("Exactly one classified member for each independent provider line", groups.put(entry.getKey(), region.member.textGroup));
                            lines.put(entry.getKey(), region.displayRegion);
                        }
                }
                if (groups.size() != 2) { Thread.sleep(10); continue; }
                if (latest != null) latest.recycle(); latest = ui.takeScreenshot();
                float density = context.getResources().getDisplayMetrics().density;
                if (latest == null || !productLinePixels(latest, lines.get("moving"), density) || !productLinePixels(latest, lines.get("fixed"), density)
                        || !provider.equals(productProviderGlyphs())) { Thread.sleep(10); continue; }
                if (productGlyphBaseline == null) productGlyphBaseline = new LinkedHashMap<>(provider);
                for (String name : provider.keySet()) {
                    RectF expected = new RectF(productGlyphBaseline.get(name)); if (name.equals("moving")) expected.offset(0, -position);
                    assertEquals("Independent glyph displacement, never frozen mask-derived geometry", expected, provider.get(name));
                    SceneEngine.Region line = lines.get(name);
                    assertTrue("Literal independently located line has a live displayed hit map", CensorTapTracker.shared().matchesPoint(
                            (float) ((line.left + line.right) / 2), (float) ((line.top + line.bottom) / 2),
                            latest.getWidth(), latest.getHeight(), SystemClock.uptimeMillis()));
                }
                if (identities != null) assertEquals("Unchanged exact nodes retain separate group IDs through actual service events", identities, groups);
                assertNotEquals(groups.get("moving"), groups.get("fixed"));
                JSONArray regions = new JSONArray();
                for (String name : provider.keySet()) {
                    RectF glyph = provider.get(name);
                    regions.put(new JSONObject().put("fixtureMember", name).put("textGroup", groups.get(name))
                            .put("glyphs", new JSONArray().put(glyph.left).put(glyph.top).put(glyph.right).put(glyph.bottom))
                            .put("line", regionMetadata(lines.get(name))));
                }
                JSONObject step = new JSONObject().put("label", label).put("position", position).put("rawRegionCount", regions.length())
                        .put("regions", regions).put("canvas", viewportSource(coverage.receipt.canvas))
                        .put("validBeforeMillis", coverage.receipt.validBeforeMillis).put("queriedAtMillis", coverage.receipt.queriedAtMillis)
                        .put("nativeSemanticGeneration", actualNativeGeneration())
                        .put("independentGlyphsStableAcrossScreenshot", true).put("literalPixelsPassed", true).put("literalHitMapPassed", true);
                productScrollSteps.put(step); saveProductPixels(label + ".png", latest); return groups;
            }
            if (latest != null) saveProductPixels(label + "-failed.png", latest);
            fail("Current product masks/pixels agree with independent provider glyphs: " + label); return null;
        } finally { if (latest != null) latest.recycle(); }
    }
    private void saveProductPixels(String name, Bitmap image) throws Exception {
        File target = new File(productScrollEvidence, name); assertFalse("Preserve old pixels", target.exists());
        try (FileOutputStream stream = new FileOutputStream(target)) { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, stream)); }
    }

    private static final class NativePaintObservation {
        final SnapshotCensorSurface.Post post;
        final long sampledAtMillis;
        final Map<String, SceneEngine.Mask> members = new LinkedHashMap<>();
        final Map<String, NativeTextFrame> frames = new LinkedHashMap<>();
        final Map<String, NativeTextResult.Candidate> candidates = new LinkedHashMap<>();
        NativePaintObservation(SnapshotCensorSurface.Post post) { this.post = post; sampledAtMillis = SystemClock.uptimeMillis(); }
    }
    /** Actual accepted Surface paint, not complete-tree viewport/dwell authority. */
    private NativePaintObservation currentNativePaint(Map<String, RectF> glyphs, boolean requirePartial) {
        if (posts.isEmpty()) return null;
        SnapshotCensorSurface.Post post = posts.get(posts.size() - 1); CensorEngine.PaintReceipt paint = post.acceptedPaint;
        if (paint == null || !paint.scope.same(session.controller().activeScope()) || paint.members.size() != glyphs.size()) return null;
        NativePaintObservation result = new NativePaintObservation(post); boolean partial = false;
        long now = result.sampledAtMillis;
        for (Map.Entry<String, RectF> entry : glyphs.entrySet()) {
            SceneEngine.Region expected = expectedProductLine(entry.getValue()); SceneEngine.Mask selected = null;
            for (SceneEngine.Mask member : paint.members) {
                if (!member.nativeValidated || !member.nativeNodeIdentityKnown || !sameProductLine(expected, member.displayRegion)) continue;
                if (selected != null) return null; selected = member;
            }
            if (selected == null || selected.estimatedMotion || selected.imageEstimated || selected.heldGeometry
                    || selected.geometrySource != selected.evidenceSource) return null;
            NativeTextFrame origin = null;
            for (NativeTextFrame frame : nativeFrames) if (frame.source == selected.evidenceSource) {
                if (origin != null) return null; origin = frame;
            }
            if (origin == null || origin.read.coverage != selected.nativeCoverage) return null;
            NativeTextResult.Candidate candidate = null;
            for (NativeTextResult.Candidate value : origin.read.candidates)
                if (value.exactGeometry && value.regions.size() == 1 && sameProductLine(value.regions.get(0), selected.evidenceRegion)) {
                    if (candidate != null) return null; candidate = value;
                }
            if (candidate == null || !sameProductLine(selected.evidenceRegion, selected.displayRegion)
                    || paint.atMillis >= origin.candidateValidUntilMillis(candidate) || now >= origin.candidateValidUntilMillis(candidate)) return null;
            partial |= selected.nativeCoverage == NativeTextResult.Coverage.POSITIVE_NODES_ONLY;
            result.members.put(entry.getKey(), selected); result.frames.put(entry.getKey(), origin); result.candidates.put(entry.getKey(), candidate);
        }
        return !requirePartial || partial ? result : null;
    }
    private JSONObject nativePaintMetadata(NativePaintObservation observed) throws Exception {
        JSONObject post = new JSONObject(ReplacementEngineTelemetry.post(1, observed.post).substring("REPLACEMENT_POST ".length()));
        post.remove("connection"); JSONArray members = new JSONArray();
        for (String name : observed.members.keySet()) {
            SceneEngine.Mask member = observed.members.get(name); NativeTextFrame frame = observed.frames.get(name);
            NativeTextResult.Candidate candidate = observed.candidates.get(name);
            members.put(new JSONObject().put("fixtureMember", name).put("identity", member.identity).put("textGroup", member.textGroup)
                    .put("nativeCoverage", member.nativeCoverage.name()).put("dwellPositive", member.dwellPositive)
                    .put("nodeKey", candidate.nodeKey).put("contentKey", candidate.contentKey)
                    .put("evidenceSource", viewportSource(member.evidenceSource)).put("geometrySource", viewportSource(member.geometrySource))
                    .put("displayRegion", regionMetadata(member.displayRegion)).put("evidenceRegion", regionMetadata(member.evidenceRegion))
                    .put("validatedAtMillis", candidate.validatedAtMillis).put("validUntilMillis", frame.candidateValidUntilMillis(candidate)));
        }
        return new JSONObject().put("post", post).put("paintAtMillis", observed.post.acceptedPaint.atMillis)
                .put("sampledAtMillis", observed.sampledAtMillis).put("rawMemberCount", members.length()).put("members", members);
    }
    private Map<String, Long> capturePositiveNodePaintPose(String label, int position, Map<String, Long> identities,
            boolean requirePartial, JSONObject controlledRead) throws Exception {
        Bitmap latest = null; long deadline = SystemClock.uptimeMillis() + 12000;
        if (sustainedNativeGate && label.equals("sustained-withdrawal")) deadline = Math.min(deadline, sustainedWithdrawalDeadline);
        try {
            while (SystemClock.uptimeMillis() < deadline) {
                Map<String, RectF> glyphs = productProviderGlyphs(); if (glyphs == null) { Thread.sleep(10); continue; }
                NativePaintObservation before = currentNativePaint(glyphs, requirePartial);
                if (before == null) { Thread.sleep(10); continue; }
                if (controlledRead != null) {
                    NativeTextFrame held = before.frames.get("moving");
                    if (held.source.readStartedAtMillis > controlledRead.getLong("heldObservedAtMillis")
                            || held.source.receivedAtMillis < controlledRead.getLong("releasedAtMillis")) {
                        Thread.sleep(10); continue;
                    }
                    assertEquals(NativeTextResult.Coverage.POSITIVE_NODES_ONLY, held.read.coverage);
                    assertEquals(controlledRead.getLong("hardGeneration"), held.read.hardGeneration);
                    assertTrue(held.read.scanGeneration <= controlledRead.getLong("heldDirtyGeneration"));
                    assertTrue(held.read.confirmationGeneration >= controlledRead.getLong("revisedDirtyGeneration"));
                }
                if (latest != null) latest.recycle(); latest = ui.takeScreenshot();
                Map<String, RectF> afterGlyphs = productProviderGlyphs();
                if (latest == null || !glyphs.equals(afterGlyphs)) { Thread.sleep(10); continue; }
                NativePaintObservation after = currentNativePaint(afterGlyphs, false);
                if (after == null) { Thread.sleep(10); continue; }
                if (sustainedNativeGate && label.equals("sustained-withdrawal")
                        && (sustainedRemoval == null || after.post.acceptedPaint.canvas == null
                            || after.post.acceptedPaint.canvas.sequence < sustainedRemoval.sequence
                            || after.post.recordedAtMillis < sustainedRemoval.receivedAtMillis
                            || after.sampledAtMillis >= sustainedWithdrawalDeadline)) { Thread.sleep(10); continue; }
                Map<String, Long> groups = new LinkedHashMap<>(); boolean pixels = true;
                float density = context.getResources().getDisplayMetrics().density;
                for (String name : glyphs.keySet()) {
                    SceneEngine.Mask first = before.members.get(name), current = after.members.get(name);
                    if ((!sustainedNativeGate && !naturalCohortRenewalGate) || before.frames.get(name).candidateValidUntilMillis(before.candidates.get(name))
                            >= current.evidenceSource.receivedAtMillis)
                        assertEquals("A live same-pose Source renewal must not reassign the exact native group", first.textGroup, current.textGroup);
                    assertEquals("Renewal retains exact node identity", before.candidates.get(name).nodeKey, after.candidates.get(name).nodeKey);
                    assertEquals(before.candidates.get(name).contentKey, after.candidates.get(name).contentKey);
                    groups.put(name, current.textGroup);
                    pixels &= productLinePixels(latest, current.displayRegion, density);
                }
                boolean withdrawalPixels = false;
                if (sustainedNativeGate && removedMutation != null) {
                    SceneEngine.Region removed = expectedProductLine(productGlyphBaseline.get("added"));
                    int x = (int) ((removed.left + removed.right) / 2), y = (int) ((removed.top + removed.bottom) / 2);
                    withdrawalPixels = productColor(latest.getPixel(x, y), ProductTargetFixtureActivity.UNDERLYING)
                            && !CensorTapTracker.shared().matchesPoint(x, y, latest.getWidth(), latest.getHeight(), SystemClock.uptimeMillis());
                    pixels &= withdrawalPixels;
                }
                if (!pixels) { Thread.sleep(10); continue; }
                if (productGlyphBaseline == null) productGlyphBaseline = new LinkedHashMap<>(glyphs);
                JSONArray independent = new JSONArray();
                for (String name : glyphs.keySet()) {
                    RectF expected = new RectF(productGlyphBaseline.get(name)); if (name.equals("moving")) expected.offset(0, -position);
                    assertEquals("Independent glyph displacement rejects a frozen line", expected, glyphs.get(name));
                    SceneEngine.Region line = after.members.get(name).displayRegion;
                    assertTrue("Existing displayed hit map agrees independently; no fee or dwell authority inferred", CensorTapTracker.shared().matchesPoint(
                            (float) ((line.left + line.right) / 2), (float) ((line.top + line.bottom) / 2),
                            latest.getWidth(), latest.getHeight(), SystemClock.uptimeMillis()));
                    RectF glyph = glyphs.get(name);
                    independent.put(new JSONObject().put("fixtureMember", name).put("glyphs",
                            new JSONArray().put(glyph.left).put(glyph.top).put(glyph.right).put(glyph.bottom)));
                }
                assertNotEquals(groups.get("moving"), groups.get("fixed"));
                JSONObject step = new JSONObject().put("label", label).put("position", position).put("requirePartial", requirePartial)
                        .put("settledPoseSample", true).put("controlledRead", controlledRead == null ? JSONObject.NULL : controlledRead)
                        .put("beforePaint", nativePaintMetadata(before)).put("currentPaint", nativePaintMetadata(after))
                        .put("independentGlyphs", independent).put("independentGlyphsStableAcrossScreenshot", true)
                        .put("literalPixelsPassed", true).put("literalHitMapPassed", true).put("physicalPaintSourceClockKnown", false);
                if (sustainedNativeGate) step.put("withdrawalPixelsPassed", withdrawalPixels);
                productScrollSteps.put(step);
                saveProductPixels(label + ".png", latest);
                if (sustainedNativeGate && label.equals("sustained-forward")) sustainedForwardPaint = after;
                if (identities != null) assertEquals("Separate exact-node groups survive forward/reversal", identities, groups);
                return groups;
            }
            if (latest == null && sustainedNativeGate && label.equals("sustained-withdrawal")) latest = ui.takeScreenshot();
            if (latest != null) saveProductPixels(label + "-failed.png", latest);
            fail("Accepted native paint agrees with independently refreshed glyphs/pixels: " + label); return null;
        } catch (Exception | Error failure) {
            if (latest != null && !new File(productScrollEvidence, label + ".png").exists()
                    && !new File(productScrollEvidence, label + "-failed.png").exists()) {
                try { saveProductPixels(label + "-failed.png", latest); }
                catch (Exception | Error retentionFailure) { failure.addSuppressed(retentionFailure); }
            }
            throw failure;
        } finally { if (latest != null) latest.recycle(); }
    }

    private void finishNaturalCohortEvidence() throws Exception {
        ParentBoundResourceObserver.State state = sustainedResourceObserver.state();
        JSONArray arrivals = new JSONArray(), frames = new JSONArray(), starts = new JSONArray(), costs = new JSONArray();
        JSONArray owners = new JSONArray();
        for (NativeOwnerObservation observation : naturalNativeOwners) owners.put(new JSONObject()
                .put("epoch", observation.start.epoch).put("request", observation.start.request)
                .put("invokedAtMillis", observation.start.invokedAtMillis).put("sampledAtMillis", observation.sampledAtMillis)
                .put("tid", observation.tid).put("kernelPriority", observation.priority).put("threadName", observation.thread.getName())
                .put("sameCapturedOwner", observation.thread == naturalNativeOwner.get()).put("mainThread", observation.mainThread));
        for (NativeArrivalObservation observation : naturalNativeArrivals) arrivals.put(new JSONObject()
                .put("epoch", observation.frame.source.epoch).put("sequence", observation.frame.source.sequence)
                .put("request", observation.frame.requestIdentity).put("observationOrdinal", observation.frame.observationOrdinal)
                .put("callbackAtMillis", observation.callbackAtMillis));
        for (NativeTextFrame frame : nativeFrames) frames.put(nativeFrameMetadata(frame));
        for (com.subhub.app.censor.engine.NativeReadCost.Start start : nativeCostStarts) {
            JSONObject row = new JSONObject(ReplacementEngineTelemetry.nativeCostStart(1, start).substring("REPLACEMENT_NATIVE_COST_START ".length()));
            row.remove("connection"); starts.put(row);
        }
        for (com.subhub.app.censor.engine.NativeReadCost cost : nativeCosts) {
            JSONObject row = new JSONObject(ReplacementEngineTelemetry.nativeCost(1, cost).substring("REPLACEMENT_NATIVE_COST ".length()));
            row.remove("connection"); costs.put(row);
        }
        JSONObject result = new JSONObject().put("schemaVersion", 2).put("method", testName.getMethodName())
                .put("rawNativeOwnerSampleCount", owners.length()).put("nativeOwnerSamples", owners)
                .put("nativeOwnerCaptured", naturalNativeOwner.get() != null)
                .put("clockDomain", "UPTIME_MILLIS").put("nativeAssertionPassed", productScrollPassed)
                .put("renewalStartedAtMillis", naturalRenewalStartedAt).put("renewalEndedAtMillis", naturalRenewalEndedAt)
                .put("callbackBoundaryAtMillis", naturalCallbackBoundaryAt)
                .put("initialWindowMillis", 2200).put("rawCommandCount", naturalRenewalCommands.length()).put("commands", naturalRenewalCommands)
                .put("rawArrivalCount", arrivals.length()).put("arrivals", arrivals).put("retainedSteps", productScrollSteps)
                .put("rawNativeFrameCount", frames.length()).put("nativeFrames", frames)
                .put("rawNativeCostStartCount", starts.length()).put("nativeCostStarts", starts)
                .put("rawNativeCostCount", costs.length()).put("nativeCosts", costs)
                .put("finalCommand", naturalFinalCommand == null ? JSONObject.NULL : naturalFinalCommand)
                .put("resourceObserver", new JSONObject().put("enabledAtMillis", state.enabledAtMillis).put("cutoffAtMillis", state.cutoffAtMillis)
                        .put("sampledParents", state.sampledParents).put("terminalParents", state.terminalParents)
                        .put("pendingParents", state.pendingParents).put("admissionsClosed", state.admissionsClosed)
                        .put("maximumParents", ParentBoundResourceObserver.MAXIMUM_PARENTS))
                .put("rawResourceSampleCount", sustainedResources.size()).put("resourceSamples", new JSONArray(sustainedResources))
                .put("rawResourceFailureCount", sustainedResourceFailures.size()).put("resourceFailures", new JSONArray(sustainedResourceFailures))
                .put("resourcesExportedAtMillis", SystemClock.uptimeMillis()).put("providerHoldsUsed", !ownedHolds.isEmpty())
                .put("explicitNativeDemand", explicitNativeDemand).put("physicalPixelClockKnown", false)
                .put("managedDrain", session != null && session.managedResourcesDrained());
        File target = new File(productScrollEvidence, "native-cohort-renewal.json"); assertFalse(target.exists());
        try (FileOutputStream stream = new FileOutputStream(target)) { stream.write(result.toString(2).getBytes(StandardCharsets.UTF_8)); }
    }

    private static JSONObject nativeFrameMetadata(NativeTextFrame frame) throws Exception {
        JSONArray candidates = new JSONArray();
        for (NativeTextResult.Candidate candidate : frame.read.candidates) {
            JSONArray lines = new JSONArray(); for (SceneEngine.Region line : candidate.regions) lines.put(regionMetadata(line));
            candidates.put(new JSONObject().put("nodeKey", candidate.nodeKey == null ? JSONObject.NULL : candidate.nodeKey)
                    .put("contentKey", candidate.contentKey).put("exactGeometry", candidate.exactGeometry)
                    .put("rawLineCount", candidate.regions.size()).put("lines", lines));
        }
        JSONObject read = new JSONObject(ReplacementEngineTelemetry.nativeRead(1, frame).substring("REPLACEMENT_NATIVE ".length()));
        read.remove("connection");
        return new JSONObject().put("source", viewportSource(frame.source)).put("validUntilMillis", frame.validUntilMillis)
                .put("rawCandidateCount", frame.read.candidates.size()).put("candidates", candidates).put("read", read);
    }

    private void finishProductScrollEvidence() throws Exception {
        CensorLabRecorder.CompletedSession completed = ownedLabStarted ? CensorLabRecorder.stop(context) : null;
        if (completed != null) {
            java.nio.file.Files.copy(completed.trace.toPath(), new File(productScrollEvidence, "trace.ndjson").toPath());
            java.nio.file.Files.copy(completed.manifest.toPath(), new File(productScrollEvidence, "manifest.json").toPath());
        }
        JSONArray receipts = new JSONArray();
        for (ScrollMotionLedger.Receipt receipt : scrollReceipts) {
            JSONObject serialized = new JSONObject(ReplacementEngineTelemetry.scroll(1, receipt).substring("REPLACEMENT_SCROLL ".length()));
            serialized.remove("connection"); receipts.put(serialized); // Local serializer, not an invented service connection ID.
        }
        JSONArray frames = new JSONArray(); int completeFrames = 0, partialFrames = 0;
        for (NativeTextFrame frame : nativeFrames) {
            if (frame.read.coverage == NativeTextResult.Coverage.COMPLETE_TREE) completeFrames++;
            else partialFrames++;
            frames.put(nativeFrameMetadata(frame));
        }
        assertEquals(scrollReceipts.size(), receipts.length()); assertEquals(nativeFrames.size(), frames.length());
        if (productScrollPassed && !nativeFrames.isEmpty()) {
            if (sustainedNativeGate || windowCostGate || nativeCutoffGate || naturalCohortRenewalGate || naturalAccountingNodes != 0
                    || testName.getMethodName().equals("actualServicePositiveNodePaintsKeepOwnSourcesThroughRapidScrollAndReversal")) {
                assertTrue("A soft fence may omit a complete evidence callback, never invent one", evidence.size() <= nativeFrames.size());
                java.util.Set<SceneEngine.Source> seen = new java.util.HashSet<>();
                for (CensorEngine.EvidenceReceipt receipt : evidence) {
                    assertTrue("Unique actual native evidence Source", seen.add(receipt.source));
                    assertTrue("Evidence is an exact callback-source subset", nativeFrames.stream().anyMatch(frame -> frame.source == receipt.source));
                }
            } else assertEquals(nativeFrames.size(), evidence.size());
        }
        JSONArray costStarts = new JSONArray(), costs = new JSONArray();
        for (com.subhub.app.censor.engine.NativeReadCost.Start start : nativeCostStarts) {
            JSONObject row = new JSONObject(ReplacementEngineTelemetry.nativeCostStart(1, start).substring("REPLACEMENT_NATIVE_COST_START ".length()));
            row.remove("connection"); costStarts.put(row);
        }
        for (com.subhub.app.censor.engine.NativeReadCost cost : nativeCosts) {
            JSONObject row = new JSONObject(ReplacementEngineTelemetry.nativeCost(1, cost).substring("REPLACEMENT_NATIVE_COST ".length()));
            row.remove("connection"); costs.put(row);
        }
        JSONObject result = new JSONObject().put("schemaVersion", 3).put("method", testName.getMethodName())
                .put("rawNativeCostStartCount", nativeCostStarts.size()).put("nativeCostStarts", costStarts)
                .put("rawNativeCostCount", nativeCosts.size()).put("nativeCosts", costs)
                .put("nativeCostDiagnosticFailures", session == null ? JSONObject.NULL : Long.valueOf(actualEngineForControl().nativeCostDiagnosticFailures()))
                .put("nativeAssertionPassed", productScrollPassed).put("actualProductionService", session != null)
                .put("sameInstrumentationPid", session != null && ((ScreenshotAccessibilityService) session.service()).qualificationBoundPid() == Process.myPid())
                .put("legacyCensorRuntimeAbsent", session != null && ((ScreenshotAccessibilityService) session.service()).legacyCensorRuntimeAbsentForQualification())
                .put("managedDrain", session != null && session.managedResourcesDrained()).put("explicitNativeDemand", explicitNativeDemand)
                .put("rawScrollReceiptCount", scrollReceipts.size()).put("fixtureSerializedReceipts", receipts)
                .put("rawNativeFrameCount", nativeFrames.size()).put("nativeFrames", frames).put("rawEvidenceCount", evidence.size())
                .put("rawCompleteNativeFrameCount", completeFrames).put("rawPositiveNodeOnlyFrameCount", partialFrames)
                .put("sustainedStartedAtMillis", sustainedStartedAt).put("sustainedEndedAtMillis", sustainedEndedAt)
                .put("acceptedDuringSustainedDemand", acceptedDuringSustainedDemand)
                .put("acceptedPositiveNodesOnlyDuringSustainedDemand", acceptedPositiveNodesOnlyDuringSustainedDemand)
                .put("stationaryStartedAtMillis", stationaryStartedAt).put("stationaryEndedAtMillis", stationaryEndedAt)
                .put("acceptedDuringStationaryRenewal", acceptedDuringStationaryRenewal)
                .put("rawInputBoundaryCount", productInputBoundaries.size()).put("inputBoundaries", new JSONArray(productInputBoundaries))
                .put("rawInputBoundaryFailureCount", productInputBoundaryFailures.get())
                .put("steps", productScrollSteps).put("ownedLabCompleted", completed != null)
                .put("physicalMotionQualified", false).put("imageMemberApplicabilityQualified", false).put("pixelPerformanceAcceptance", false);
        File target = new File(productScrollEvidence, "gate-result.json"); assertFalse(target.exists());
        try (FileOutputStream stream = new FileOutputStream(target)) { stream.write(result.toString(2).getBytes(StandardCharsets.UTF_8)); }
        if (windowCostGate) finishWindowCostEvidence();
        if (sustainedNativeGate || windowCostGate || nativeCutoffGate) {
            ParentBoundResourceObserver.State resourceState = sustainedResourceObserver.state();
            JSONObject resourceObserver = new JSONObject().put("enabledAtMillis", resourceState.enabledAtMillis)
                    .put("cutoffAtMillis", resourceState.cutoffAtMillis).put("sampledParents", resourceState.sampledParents)
                    .put("terminalParents", resourceState.terminalParents).put("pendingParents", resourceState.pendingParents)
                    .put("admissionsClosed", resourceState.admissionsClosed).put("maximumParents", ParentBoundResourceObserver.MAXIMUM_PARENTS);
            JSONArray parents = new JSONArray();
            for (NativeTextFrame frame : nativeFrames) parents.put(new JSONObject().put("epoch", frame.source.epoch)
                    .put("sequence", frame.source.sequence).put("request", frame.requestIdentity).put("observationOrdinal", frame.observationOrdinal));
            JSONObject sustained = new JSONObject().put("schemaVersion", 2).put("method", testName.getMethodName())
                    .put("resourceObserver", resourceObserver)
                    .put("nativeAssertionPassed", productScrollPassed).put("rawResourceSampleCount", sustainedResources.size())
                    .put("resourceSamples", new JSONArray(sustainedResources)).put("rawResourceFailureCount", sustainedResourceFailures.size())
                    .put("resourceFailures", new JSONArray(sustainedResourceFailures)).put("addedMutation", addedMutation == null ? JSONObject.NULL : addedMutation)
                    .put("removedMutation", removedMutation == null ? JSONObject.NULL : removedMutation)
                    .put("discoverySource", sustainedDiscovery == null ? JSONObject.NULL : viewportSource(sustainedDiscovery))
                    .put("removalSource", sustainedRemoval == null ? JSONObject.NULL : viewportSource(sustainedRemoval))
                    .put("withdrawalValidUntilMillis", sustainedWithdrawalDeadline)
                    .put("initialIndependentParents", new JSONArray(sustainedInitialParents)).put("issuedScrollCommands", sustainedCommands)
                    .put("scrollCommandIssueTimesMillis", new JSONArray(sustainedCommandTimes))
                    .put("nativeFrameParents", parents)
                    .put("resourcesExportedAtMillis", SystemClock.uptimeMillis())
                    .put("continuousPhysicalMotionQualified", false);
            if (nativeCutoffGate) sustained.put("controlEpoch", nativeCutoffEpoch).put("controlRequest", nativeCutoffRequest)
                    .put("controlObserverParent", nativeCutoffTicket).put("readerRetiredAtTerminal", nativeCutoffReaderRetiredAtTerminal)
                    .put("rawControlEventCount", nativeCutoffEvents.length()).put("controlEvents", nativeCutoffEvents)
                    .put("runtimeRemedyQualified", false).put("rootSubstitutionQualified", false);
            File extra = new File(productScrollEvidence, nativeCutoffGate ? "native-cutoff-control.json"
                    : windowCostGate ? "native-resource-samples.json" : "native-sustainability.json"); assertFalse(extra.exists());
            try (FileOutputStream stream = new FileOutputStream(extra)) { stream.write(sustained.toString(2).getBytes(StandardCharsets.UTF_8)); }
        }
    }

    private JSONObject windowCostCall(WindowReadCost.Call call) throws Exception {
        int parentMatches = 0, ordinal = 0; String mode = null;
        for (com.subhub.app.censor.engine.NativeReadCost.Start parent : nativeCostStarts)
            if (parent.epoch == call.epoch && parent.request == call.request) { parentMatches++; mode = parent.readMode.name(); }
        for (WindowReadCost.Call start : windowCostStarts)
            if (start.epoch == call.epoch && start.request == call.request && start.sequence <= call.sequence) ordinal++;
        return new JSONObject().put("epoch", call.epoch).put("request", call.request).put("callSequence", call.sequence)
                .put("parentCallOrdinal", ordinal).put("parentStartMatches", parentMatches).put("readMode", mode == null ? JSONObject.NULL : mode)
                .put("operation", call.operation.name()).put("boundary", call.boundary).put("threadId", call.threadId)
                .put("threadName", call.threadName).put("startedAtMillis", call.startedAtMillis);
    }
    private void finishWindowCostEvidence() throws Exception {
        JSONArray starts = new JSONArray(), samples = new JSONArray();
        for (WindowReadCost.Call call : windowCostStarts) starts.put(windowCostCall(call));
        for (WindowReadCost.Sample sample : windowCostSamples) {
            JSONArray stages = new JSONArray();
            for (WindowReadCost.Cost cost : sample.result.costs) stages.put(new JSONObject().put("stage", cost.stage.name())
                    .put("calls", cost.calls).put("exclusiveNanos", cost.exclusiveNanos)
                    .put("maximumExclusiveNanos", cost.maximumExclusiveNanos).put("thrownCalls", cost.thrownCalls));
            samples.put(new JSONObject().put("call", windowCostCall(sample.call)).put("finishedAtMillis", sample.finishedAtMillis)
                    .put("returnedNormally", sample.returnedNormally).put("rootTransferred", sample.rootTransferred)
                    .put("startedNanos", sample.result.startedNanos).put("finishedNanos", sample.result.finishedNanos)
                    .put("elapsedNanos", sample.result.elapsedNanos).put("residualNanos", sample.result.residualNanos)
                    .put("rawStageCount", sample.result.costs.size()).put("stages", stages));
        }
        WindowReadCost.Diagnostics diagnostic = windowCostReader == null ? null : windowCostReader.costDiagnostics();
        JSONObject summary = diagnostic == null ? null : new JSONObject().put("entries", diagnostic.entries())
                .put("refusedEntries", diagnostic.refusedEntries()).put("throwingEntries", diagnostic.throwingEntries())
                .put("attempts", diagnostic.attempts()).put("failures", diagnostic.failures())
                .put("startCallbacks", diagnostic.startCallbacks()).put("endCallbacks", diagnostic.endCallbacks())
                .put("startCallbackNanos", diagnostic.startCallbackNanos()).put("endCallbackNanos", diagnostic.endCallbackNanos());
        JSONObject result = new JSONObject().put("schemaVersion", 1).put("method", testName.getMethodName())
                .put("attachedAtMillis", windowCostAttachedAt).put("demandStartedAtMillis", windowCostDemandStartedAt)
                .put("demandEndedAtMillis", windowCostDemandEndedAt).put("exportedAtMillis", SystemClock.uptimeMillis())
                .put("uptimeClockDomain", "UPTIME_MILLIS").put("durationClockDomain", "SYSTEM_NANO_TIME")
                .put("rawWindowStartCount", windowCostStarts.size()).put("starts", starts)
                .put("rawWindowSampleCount", windowCostSamples.size()).put("samples", samples)
                .put("rawCommandCount", windowCostCommands.length()).put("commands", windowCostCommands)
                .put("selectedDirectParent", windowCostSelectedDirect == null ? JSONObject.NULL : new JSONObject()
                        .put("epoch", windowCostSelectedDirect.epoch).put("request", windowCostSelectedDirect.request))
                .put("diagnostics", summary == null ? JSONObject.NULL : summary)
                .put("nativeAssertionPassed", productScrollPassed).put("managedDrain", session != null && session.managedResourcesDrained())
                .put("physicalMotionQualified", false).put("visualAlignmentQualified", false).put("pixelPerformanceAcceptance", false);
        File target = new File(productScrollEvidence, "window-cost.json"); assertFalse(target.exists());
        try (FileOutputStream stream = new FileOutputStream(target)) { stream.write(result.toString(2).getBytes(StandardCharsets.UTF_8)); }
    }

    @Test public void currentNativeObservationsChargeOneViewportEpisodeWithoutDetectionBatching() throws Exception {
        nativeDwell("porn"); connect(); awaitDwell(1); assertOnlyDwell(1);
        long until = SystemClock.uptimeMillis() + 3500;
        awaitNative("Further real native observations keep the same charged episode", () -> SystemClock.uptimeMillis() >= until);
        assertOnlyDwell(1); assertEquals(1, committedDwell()); assertBinding();
        assertTrue(dwellResults.stream().filter(r -> r.status == CensorProductSession.AccountingStatus.COMMITTED)
                .allMatch(r -> r.chargedCents == 7 && r.observation.positiveInput && r.coverage.windowQualified));
    }

    @Test public void queuedPreScrollImageReceiptCannotSeedTheSuccessorDwellEpisode() throws Exception {
        configureDwellRule(); connect(); awaitPaint(); controlledObservationRedelivery = true;
        CensorEngine<PixelFrame> engine = actualEngineForControl();
        main(() -> engine.executionControls(10000, false, EnumSet.of(SceneEngine.Lane.FAST)));
        await("Actual image callback returned before the delayed delivery control", () -> !session.controller().host().captureInFlight());
        AtomicReference<CensorEngine.EvidenceReceipt> selected = new AtomicReference<>();
        await("Quiet actual latest image and drained main admission", () -> {
            main(() -> {
                CensorEngine.EvidenceReceipt receipt = evidence.stream().filter(r -> r.source.kind == SceneEngine.SourceKind.IMAGE && r.triggerLane == SceneEngine.Lane.FAST)
                        .max(Comparator.comparingLong(r -> r.source.sequence)).orElse(null);
                AccessibilityCensorController.WindowCoverage coverage = session.controller().viewportCoverage();
                if (receipt != null && actualEvidenceAdmission().retainedItems() == 0
                        && session.controller().observationCurrent(coverage, receipt)) selected.set(receipt);
            });
            return selected.get() != null;
        });
        CensorEngine.EvidenceReceipt old = selected.get();
        long until = SystemClock.uptimeMillis() + 1000;
        await("Bounded pre-scroll queue delay without new captures", () -> SystemClock.uptimeMillis() >= until);
        AtomicLong scrollAt = new AtomicLong();
        main(() -> {
            AccessibilityCensorController.WindowCoverage coverage = session.controller().viewportCoverage();
            assertTrue("Original real image still current at adverse queue admission", session.controller().observationCurrent(coverage, old));
            actualEvidenceAdmission().offer(old); // Original capability, not a new producer/witness.
            scrollAt.set(SystemClock.uptimeMillis()); session.scrolled(session.window().id);
            assertFalse(session.controller().observationCurrent(coverage, old));
            assertTrue(session.controller().coverageCurrent(coverage));
            assertNotNull(session.controller().claimEvidence(old, StatsRepository.currentSessionStamp()));
        });
        barrier(); main(() -> engine.executionControls(334, false, EnumSet.of(SceneEngine.Lane.FAST)));
        await("Fresh real image observations charge only the new stationary interval", () -> committedDwell() == 1);
        CensorProductSession.DwellResult result = dwellResults.stream().filter(r -> r.status == CensorProductSession.AccountingStatus.COMMITTED)
                .findFirst().get();
        assertTrue(result.observation.source.sequence > old.source.sequence);
        assertTrue(result.observation.acceptedAtMillis - scrollAt.get() >= 3000); assertOnlyDwell(1); assertBinding();
    }

    @Test public void nativeEmptyGapKeepsElapsedDwellAndActualWindowScrollEventStartsAnotherEpisode() throws Exception {
        nativeDwell("porn");
        context.startActivity(new Intent().setComponent(new ComponentName(TARGET, ProductTargetFixtureActivity.class.getName()))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION).putExtra("label", "porn").putExtra("scrollFixture", true));
        connect(); AccessibilityCensorController.WindowCoverage first = awaitNativeViewport(); barrier();
        CensorEngine.ActiveScope scope = session.controller().activeScope(); long firstAt = first.receipt.queriedAtMillis;
        literalTarget("Hello");
        awaitNative("Real native empty replacement, not a missed callback", () -> evidence.stream().anyMatch(r ->
                r.source.kind == SceneEngine.SourceKind.NATIVE_TEXT && r.source.sequence > first.receipt.canvas.sequence && !r.positiveInput));
        assertTrue(scope.same(session.controller().activeScope()));
        waitWithoutDwell(firstAt + 3200); long restoredAt = SystemClock.uptimeMillis();
        literalTarget("porn"); awaitDwell(1); assertOnlyDwell(1);
        assertTrue("Empty gap did not start a new three-second dwell threshold", dwellResults.stream().filter(r ->
                r.status == CensorProductSession.AccountingStatus.COMMITTED).findFirst().get().observation.acceptedAtMillis - firstAt >= 3000);
        assertTrue("Restored positive charges the elapsed episode, not a restarted threshold", dwellResults.stream().filter(r ->
                r.status == CensorProductSession.AccountingStatus.COMMITTED).findFirst().get().observation.acceptedAtMillis - restoredAt < 3000);
        android.view.accessibility.AccessibilityNodeInfo root = ui.getRootInActiveWindow();
        assertNotNull("Actual qualified target root", root);
        assertEquals(TARGET, root.getPackageName().toString()); assertEquals(first.window.id, root.getWindowId());
        android.view.accessibility.AccessibilityNodeInfo scrollNode = null;
        ArrayDeque<android.view.accessibility.AccessibilityNodeInfo> nodes = new ArrayDeque<>(); nodes.add(root);
        try {
            for (int visited = 0; !nodes.isEmpty(); visited++) {
                assertTrue("Bounded synthetic target node inventory", visited < 128);
                android.view.accessibility.AccessibilityNodeInfo node = nodes.remove();
                if ("android.widget.ScrollView".contentEquals(node.getClassName())) { scrollNode = node; break; }
                try {
                    for (int child = 0; child < node.getChildCount(); child++) {
                        android.view.accessibility.AccessibilityNodeInfo value = node.getChild(child);
                        if (value != null) nodes.add(value);
                    }
                } finally { node.recycle(); }
            }
        } finally { for (android.view.accessibility.AccessibilityNodeInfo node : nodes) node.recycle(); }
        assertNotNull("Actual target scroll node", scrollNode);
        assertEquals("android.widget.ScrollView", scrollNode.getClassName().toString());
        assertTrue("Actual provider reports a scrollable range", scrollNode.isScrollable());
        scrollNode.recycle();
        long scrollAt = SystemClock.uptimeMillis();
        // Android 15's animated ScrollView path accumulates the same displacement twice.
        // Drive the owned View directly; preserve framework-produced event and exact delta checks.
        android.view.accessibility.AccessibilityEvent scroll = ui.executeAndWaitForEvent(() ->
                context.startActivity(new Intent().setComponent(new ComponentName(TARGET, ProductTargetFixtureActivity.class.getName()))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION)
                        .putExtra("label", "porn").putExtra("scrollToY", 1)),
                event -> event.getEventType() == android.view.accessibility.AccessibilityEvent.TYPE_VIEW_SCROLLED
                        && TARGET.contentEquals(event.getPackageName()) && event.getWindowId() == first.window.id, 12000);
        try {
            assertEquals("android.widget.ScrollView", scroll.getClassName().toString());
            assertEquals("Actual framework scroll position: " + scroll, 1, scroll.getScrollY());
            assertEquals("Actual framework scroll displacement: " + scroll, 1, scroll.getScrollDeltaY());
            assertEquals("Actual framework scroll range: " + scroll, 1, scroll.getMaxScrollY());
        } finally { scroll.recycle(); }
        awaitDwell(2); assertOnlyDwell(2);
        CensorProductSession.DwellResult second = dwellResults.stream().filter(r -> r.status == CensorProductSession.AccountingStatus.COMMITTED)
                .skip(1).findFirst().get();
        assertTrue("Framework-delivered window scroll resets the elapsed threshold", second.observation.acceptedAtMillis - scrollAt >= 3000);
        assertTrue(scope.same(session.controller().activeScope())); assertBinding();
    }

    @Test public void queriesStyleAndExpiryCannotInventDwellObservationsOrNewNativeReads() throws Exception {
        nativeDwell("porn"); connect(); AccessibilityCensorController.WindowCoverage first = awaitNativeViewport(); barrier();
        int observed = evidence.size();
        main(() -> {
            SettingsRepository settings = new SettingsRepository(context);
            session.controller().appearanceChanged(settings.loadAppearance(), SnapshotPaintPolicy.from(settings.loadDetectionPreset()), 1);
            for (int i = 0; i < 50; i++) session.controller().viewportCoverage();
        });
        waitWithoutDwell(first.receipt.queriedAtMillis + 3200);
        assertEquals(observed, evidence.size()); assertFalse(session.controller().coverageCurrent(first));
        assertTrue(dwellResults.isEmpty()); assertOnlyDwell(0);
        awaitDwell(1); assertOnlyDwell(1); assertTrue(evidence.size() > observed); assertBinding();
    }

    @Test public void reverseNativeDwellUsesDetectedSubjectsNotSourceFreeBackgroundPaint() throws Exception {
        nativeDwell("porn"); assertTrue(preferences.edit().putBoolean(SettingsRepository.KEY_REVERSE_MODE, true).commit());
        connect(); awaitDwell(1); assertOnlyDwell(1);
        assertTrue(posts.stream().anyMatch(p -> p.acceptedPaint != null && p.acceptedPaint.reverseBackground && p.acceptedPaint.members.isEmpty()));
        assertTrue(dwellResults.stream().filter(r -> r.status == CensorProductSession.AccountingStatus.COMMITTED)
                .allMatch(r -> !r.coverage.regions.isEmpty() && r.coverage.regions.stream().allMatch(m -> m.member.nativeValidated)));
        assertBinding();
    }

    @Test public void labObservationsAndOldStatisticsStampCannotReachDwellLedger() throws Exception {
        nativeDwell("porn"); StatsRepository stats = new StatsRepository(context); StatsRepository.SessionStamp original = stats.sessionStamp();
        try (CensorLabCensorSession.Lease lab = CensorLabCensorSession.acquire()) {
            connect(); long until = SystemClock.uptimeMillis() + 3500;
            awaitNative("Real Lab observations span the dwell threshold", () -> SystemClock.uptimeMillis() >= until);
            assertTrue(evidence.stream().anyMatch(r -> r.scope.protectionScope.origin == ProtectionScope.Origin.LAB));
            assertTrue(dwellResults.isEmpty()); assertOnlyDwell(0);
        }
        main(session::stop); stats.endSession(); stats.startSession();
        assertFalse(original.isCurrent());
        PenanceManager.SourceDetectionResult rejected = new PenanceManager(context).recordSourceDwellResult(System.currentTimeMillis(), original);
        assertEquals(PenanceManager.SourceDetectionStatus.REJECTED, rejected.status); assertEquals(0, rejected.chargedCents);
        assertOnlyDwell(0); assertEquals(0, stats.load().getCurrentSessionTributeEvents()); assertBinding();
    }

    @Test public void admittedDwellWriteMayFinishCapturedLedgerButNeverAttributesToANewSession() throws Exception {
        nativeDwell("porn"); Hold held = hold(); AtomicInteger writes = new AtomicInteger();
        dwellStorageControl(true, true, false, held, writes); connect();
        explicitNativeDemand = true;
        awaitNative("Actual admitted dwell ledger write enters its commit boundary", () -> held.entered.getCount() == 0);
        StatsRepository stats = new StatsRepository(context); StatsRepository.SessionStamp original = stats.sessionStamp();
        stats.endSession(); stats.startSession(); main(session::close);
        assertFalse(original.isCurrent()); assertFalse(session.managedResourcesDrained()); held.release.countDown();
        await("Admitted dwell terminal and actual worker drain", session::managedResourcesDrained);
        assertOnlyDwell(1); assertEquals(1, writes.get()); assertEquals(1, committedDwell());
        assertEquals(1, stats.load().getTributeEvents()); assertEquals(7, stats.load().getTributeCents());
        assertEquals(0, stats.load().getCurrentSessionTributeEvents()); assertEquals(0, stats.load().getCurrentSessionTributeCents());
    }

    @Test public void unacknowledgedDwellStoreIsQuarantinedWithoutReplayOrStoppingDetectedGeometry() throws Exception {
        nativeDwell("porn"); AtomicInteger writes = new AtomicInteger(); dwellStorageControl(false, true, false, null, writes); connect();
        awaitNative("Explicit controlled unacknowledged dwell store", () -> dwellResults.stream().anyMatch(r ->
                r.status == CensorProductSession.AccountingStatus.UNCERTAIN));
        long until = SystemClock.uptimeMillis() + 3500;
        awaitNative("More actual observations cannot replay uncertain dwell accounting", () -> SystemClock.uptimeMillis() >= until);
        assertEquals(1, writes.get()); assertEquals(0, committedDwell()); assertOnlyDwell(1);
        assertEquals(0, new StatsRepository(context).load().getTributeEvents());
        assertFalse(actualPopupHints().isEmpty()); assertTrue(session.isActive()); assertBinding();
    }

    @Test public void actualPopupCoverAndAvoidUseCurrentWindowHintsWithoutStatisticsOrParticipationMutation() throws Exception {
        nativeOnly("porn"); connect(); AccessibilityCensorController.WindowCoverage first = awaitNativeViewport();
        assertNull(StatsRepository.currentSessionStamp()); PopupStormManager manager = PopupStormManager.get();
        main(() -> manager.reloadSettings(context)); assertFalse(manager.isRunning());
        List<RectF> hints = actualPopupHints(); assertEquals(first.regions.size(), hints.size());
        for (int i = 0; i < hints.size(); i++) {
            SceneEngine.Region region = first.regions.get(i).displayRegion; RectF actual = hints.get(i);
            assertEquals((float) region.left, actual.left, 0); assertEquals((float) region.top, actual.top, 0);
            assertEquals((float) region.right, actual.right, 0); assertEquals((float) region.bottom, actual.bottom, 0);
        }
        assertTrue(preferences.edit().putString(PopupStormSettings.K_DETECTION_MODE, "cover").commit());
        float[] cover = popupPosition(40, 40); RectF box = hints.get(0);
        assertEquals(box.centerX() - 20, cover[0], 0);
        assertEquals(Math.max(popupReservedTop(), box.centerY() - 20), cover[1], 0);
        assertTrue(preferences.edit().putString(PopupStormSettings.K_DETECTION_MODE, "avoid").commit());
        float[] avoid = popupPosition(40, 40);
        RectF candidate = new RectF(avoid[0], avoid[1], avoid[0] + 40, avoid[1] + 40);
        for (RectF hint : hints) assertFalse(RectF.intersects(candidate, hint));
        int reads = evidence.size();
        await("Literal source expiry before another accepted observation", () -> SystemClock.uptimeMillis() >= first.receipt.validBeforeMillis);
        assertTrue(actualPopupHints().isEmpty()); assertEquals(reads, evidence.size());
        main(() -> session.controller().host().requestNativeText(false)); awaitNativeViewport();
        main(() -> session.controller().contentChanged(first.window.id)); assertTrue(actualPopupHints().isEmpty());
        awaitNativeViewport(); assertFalse(actualPopupHints().isEmpty()); main(session::stop); assertTrue(actualPopupHints().isEmpty());
        assertTrue(preferences.edit().putString(PopupStormSettings.K_DETECTION_MODE, "cover").commit());
        main(() -> manager.updateDetections(Collections.singletonList(new RectF(400, 800, 500, 900))));
        float[] legacy = popupPosition(40, 40); assertArrayEquals(new float[]{430, 830}, legacy, 0);
        main(() -> manager.updateDetections(Collections.emptyList()));
        assertFalse(manager.isRunning()); assertFalse(preferences.getBoolean(PopupStormSettings.K_ENABLED, false));
        assertTrue(dwellResults.isEmpty()); assertOnlyDwell(0); assertBinding();
    }

    @Test public void detectedViewportUsesActualInsetWindowAndNativeDisplayCoordinatesOnce() throws Exception {
        nativeOnly("porn");
        context.startActivity(new Intent().setComponent(new ComponentName(TARGET, ProductTargetFixtureActivity.class.getName()))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .putExtra("label", "porn").putExtra("insetWindow", true));
        connect();
        AccessibilityCensorController.WindowCoverage coverage = awaitNativeViewport();
        ProviderGeometry glyphs = providerGeometry("porn"); writeViewport("native-inset", coverage, glyphs);
        assertTrue("Actual nonzero-origin window, not a synthetic coordinate expectation", coverage.window.left > 0 && coverage.window.top > 0);
        assertNotNull("Independent provider character locations, not widget bounds", glyphs);
        assertEquals(coverage.window.id, glyphs.windowId);
        android.graphics.RectF line = new android.graphics.RectF(glyphs.characters.get(0));
        for (int i = 1; i < glyphs.characters.size(); i++) line.union(glyphs.characters.get(i));
        // Existing AccessibilityTextGeometry uses floor/ceil and exactly two edge pixels.
        SceneEngine.Region independent = new SceneEngine.Region(Math.max(0, Math.floor(line.left) - 2),
                Math.max(0, Math.floor(line.top) - 2), Math.min(coverage.window.displayWidth, Math.ceil(line.right) + 2),
                Math.min(coverage.window.displayHeight, Math.ceil(line.bottom) + 2));
        SceneEngine.Region window = windowRegion(coverage.window);
        assertEquals(CensorEngine.CoverageState.AVAILABLE, coverage.receipt.state);
        assertEquals(coverage.receipt.regions.size(), coverage.regions.size());
        for (AccessibilityCensorController.WindowRegion region : coverage.regions) {
            assertTrue(region.member.nativeValidated); assertFalse(region.member.physicalErrorKnown);
            assertEquals(SceneEngine.SourceKind.NATIVE_TEXT, region.member.evidenceSource.kind);
            assertSame(region.member.evidenceSource, region.member.geometrySource);
            assertRegion(independent.clippedTo(window), region.displayRegion); assertTrue(window.contains(region.displayRegion));
            assertRegion(region.member.displayRegion.clippedTo(window), region.displayRegion);
        }
        assertEquals(0, new StatsRepository(context).load().getTotalBlocks()); assertNoDownstream(); assertBinding();
    }

    @Test public void nativeContentChangeWithdrawsDetectedMetadataBeforeReplacementRead() throws Exception {
        nativeOnly("porn"); connect(); AccessibilityCensorController.WindowCoverage old = awaitNativeViewport();
        AtomicReference<AccessibilityCensorController.WindowCoverage> gap = new AtomicReference<>();
        main(() -> {
            session.controller().contentChanged(old.window.id);
            assertFalse(session.controller().coverageCurrent(old));
            gap.set(session.controller().viewportCoverage());
            assertTrue(session.controller().coverageCurrent(gap.get()));
            assertTrue(gap.get().regions.isEmpty());
        });
        writeViewport("native-content-gap", gap.get());
        assertEquals(CensorEngine.CoverageState.NO_ELIGIBLE_REGIONS, gap.get().receipt.state);
        assertEquals(1, gap.get().receipt.ineligibleMembers);
        AccessibilityCensorController.WindowCoverage restored = awaitNativeViewport(); writeViewport("native-content-restored", restored);
        assertTrue(restored.receipt.regions.get(0).evidenceSource.sequence > old.receipt.regions.get(0).evidenceSource.sequence);
        assertFalse(session.controller().coverageCurrent(old)); assertNoDownstream(); assertBinding();
    }

    @Test public void detectedViewportStopAndRestartRejectsOldWithdrawalWithoutAnotherOwner() throws Exception {
        nativeOnly("porn"); connect(); AccessibilityCensorController.WindowCoverage old = awaitNativeViewport();
        AtomicReference<AccessibilityCensorController.WindowCoverage> stopped = new AtomicReference<>();
        main(() -> {
            session.stop(); assertFalse(session.controller().coverageCurrent(old));
            stopped.set(session.controller().viewportCoverage());
            assertTrue(session.controller().coverageCurrent(stopped.get()));
        });
        writeViewport("native-stopped", stopped.get());
        assertEquals(CensorEngine.CoverageState.WITHDRAWN, stopped.get().receipt.state);
        assertFalse(stopped.get().windowQualified); assertTrue(stopped.get().regions.isEmpty());
        main(session::request); AccessibilityCensorController.WindowCoverage restarted = awaitNativeViewport();
        writeViewport("native-restarted", restarted);
        assertFalse(session.controller().coverageCurrent(stopped.get())); assertFalse(session.controller().coverageCurrent(old));
        assertFalse(old.receipt.scope.same(restarted.receipt.scope)); assertBinding(); assertNoDownstream();
    }

    @Test public void reversePaintKeepsDetectedNativeRegionsAndDoesNotRenewTheirSourceDeadline() throws Exception {
        nativeOnly("porn"); connect(); AccessibilityCensorController.WindowCoverage original = awaitNativeViewport();
        AtomicReference<AccessibilityCensorController.WindowCoverage> reverse = new AtomicReference<>();
        main(() -> {
            assertTrue(preferences.edit().putBoolean(SettingsRepository.KEY_REVERSE_MODE, true).commit());
            SettingsRepository settings = new SettingsRepository(context);
            session.controller().appearanceChanged(settings.loadAppearance(), SnapshotPaintPolicy.from(settings.loadDetectionPreset()), 0);
            reverse.set(session.controller().viewportCoverage());
            assertTrue(session.controller().coverageCurrent(reverse.get()));
        });
        writeViewport("native-reverse", reverse.get());
        assertEquals(original.receipt.validBeforeMillis, reverse.get().receipt.validBeforeMillis);
        assertEquals(original.receipt.regions.get(0).identity, reverse.get().receipt.regions.get(0).identity);
        assertSame(original.receipt.regions.get(0).evidenceSource, reverse.get().receipt.regions.get(0).evidenceSource);
        assertEquals(1, reverse.get().regions.size());
        await("Actual reverse Surface post", () -> posts.stream().anyMatch(p -> p.acceptedPaint != null
                && p.acceptedPaint.scope.same(original.receipt.scope) && p.acceptedPaint.reverseBackground
                && p.acceptedPaint.members.isEmpty()));
        assertNoDownstream(); assertBinding();
    }

    @Test public void nativeViewportExpiresWithoutQueriesStartingFreshProviderReads() throws Exception {
        nativeOnly("porn"); Hold renewal = hold();
        // Arm the next real provider entry synchronously after the first actual accepted read.
        nativeHoldAfterAccepted.set(renewal);
        connect(); AccessibilityCensorController.WindowCoverage original = awaitNativeViewport();
        assertTrue("Real producer-driven renewal reaches held provider", renewal.entered.await(8, TimeUnit.SECONDS));
        long source = original.receipt.regions.get(0).evidenceSource.sequence;
        int reads = evidence.size();
        assertEquals(1, reads); assertEquals(2, nativeProviderEntries.get());
        assertTrue(original.receipt.validBeforeMillis < Long.MAX_VALUE);
        for (int i = 0; i < 20; i++) {
            AccessibilityCensorController.WindowCoverage queried = session.controller().viewportCoverage();
            assertEquals(original.receipt.validBeforeMillis, queried.receipt.validBeforeMillis);
            assertEquals(source, queried.receipt.regions.get(0).evidenceSource.sequence);
        }
        await("Exact finite native viewport expiry", () -> SystemClock.uptimeMillis() >= original.receipt.validBeforeMillis);
        AccessibilityCensorController.WindowCoverage expired = session.controller().viewportCoverage(); writeViewport("native-expired", expired);
        assertFalse(session.controller().coverageCurrent(original)); assertTrue(expired.regions.isEmpty());
        assertTrue(expired.receipt.state == CensorEngine.CoverageState.EXPIRED
                || expired.receipt.state == CensorEngine.CoverageState.NO_ELIGIBLE_REGIONS);
        assertEquals(reads, evidence.size()); assertEquals(2, nativeProviderEntries.get()); assertNoDownstream(); assertBinding();
    }

    @Test public void realProductionServiceUsesOneReplacementAndComposedSurfaceWithoutLegacyWorkers() throws Exception {
        connect(); awaitPaint(); assertBinding(); composed("product-covered", COVER);
        assertTrue(preparations.contains(PixelFrame.Kind.ACCESSIBILITY_WINDOW)); assertEquals(1, models.get());
        assertFalse(batches.isEmpty());
        assertTrue(batches.stream().allMatch(b -> b.source.kind == SceneEngine.SourceKind.IMAGE && b.source.windowId == -1));
        AtomicBoolean currentSource = new AtomicBoolean();
        await("Actual current-scope image source and accepted paint, not historical batch scopes", () -> {
            currentSource.set(false);
            main(() -> {
                CensorEngine.ActiveScope scope = session.controller().activeScope();
                if (scope == null) return;
                for (SnapshotCensorSurface.Post post : posts) {
                    CensorEngine.PaintReceipt paint = post.acceptedPaint;
                    if (paint == null || !paint.scope.same(scope) || paint.canvas == null) continue;
                    SceneEngine.Source source = paint.canvas;
                    assertEquals(SceneEngine.SourceKind.IMAGE, source.kind); assertEquals(-1, source.windowId);
                    assertEquals(scope.epoch, source.epoch); assertEquals(scope.geometry, source.geometry);
                    for (SceneEngine.Mask member : paint.members) {
                        assertEquals(scope.epoch, member.evidenceSource.epoch); assertEquals(scope.geometry, member.evidenceSource.geometry);
                        assertEquals(scope.epoch, member.geometrySource.epoch); assertEquals(scope.geometry, member.geometrySource.geometry);
                    }
                    if (batches.stream().anyMatch(batch -> batch.source == source) && session.controller().host().isCurrent(scope)) {
                        currentSource.set(true); return;
                    }
                }
            });
            return currentSource.get();
        });
        assertTrue(posts.stream().anyMatch(p -> p.acceptedPaint != null && !p.mainThread));
        assertSpacing(); assertTrue(faults.isEmpty());
    }
    @Test public void candidateSelectionNeverHotSwapsTheConnectedOwner() throws Exception {
        Assume.assumeTrue(com.subhub.app.BuildConfig.REPLACEMENT_LAB_CANDIDATE);
        assertTrue(qualification.edit().putBoolean("enabled", false)
                .putBoolean(ProductCensorQualification.CANDIDATE_SELECTION, true).commit());
        connect();
        CensorProductSession connected = ProductCensorQualification.current();
        ScreenshotAccessibilityService bound = (ScreenshotAccessibilityService) connected.service();
        int pid = bound.qualificationBoundPid();
        assertTrue(bound.legacyCensorRuntimeAbsentForQualification());
        assertTrue(ProductCensorQualification.enabled(context));
        main(() -> assertSame(connected, ProductCensorQualification.current()));
        assertEquals(pid, bound.qualificationBoundPid());
        assertTrue(bound.legacyCensorRuntimeAbsentForQualification());
        assertTrue(qualification.edit().putBoolean(ProductCensorQualification.CANDIDATE_SELECTION, false).commit());
        assertFalse("The next connection no longer selects replacement", ProductCensorQualification.enabled(context));
        main(() -> assertSame("Current startup-selected owner is not swapped", connected, ProductCensorQualification.current()));
        assertEquals(pid, bound.qualificationBoundPid());
        assertTrue(bound.legacyCensorRuntimeAbsentForQualification());
        disconnectCandidate(connected);
        assertTrue(qualification.edit().putBoolean(ProductCensorQualification.CANDIDATE_SELECTION, true).commit());
        assertFalse(qualification.getBoolean("enabled", true));
        ui = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        connect(); awaitPaint();
        assertNotSame("Actual reconnection creates a new single owner", connected, session);
        assertNotSame("Actual framework service instance was replaced", bound, session.service());
        assertTrue(connected.managedResourcesDrained()); assertBinding();
        assertFalse(earlyClose.get());
    }

    @Test public void unselectedCandidateConnectionCannotCreateReplacementOwner() throws Exception {
        assertTrue(qualification.edit().putBoolean("enabled", false)
                .putBoolean(ProductCensorQualification.CANDIDATE_SELECTION, false).commit());
        assertTrue(preferences.edit().putBoolean(AppModeManager.KEY_ARMED, false).commit());
        ComponentName component = new ComponentName(context, ScreenshotAccessibilityService.class);
        secure(previousServices == null || previousServices.isEmpty() ? component.flattenToString()
                : previousServices + ":" + component.flattenToString(), 1);
        try { await("Actual non-candidate service binding", ScreenshotAccessibilityService::isRunning); }
        catch (AssertionError failure) { retainBindingFailure(); throw failure; }
        main(() -> assertNull("Neither candidate nor fixture selected", ProductCensorQualification.current()));
        assertEquals(0, models.get()); assertFalse(ProductCensorQualification.enabled(context));
    }

    @Test public void replacementTraceReachesTheExplicitLabBufferWithoutCompositorClaims() throws Exception {
        assertFalse(com.subhub.app.diagnostics.CensorLabRecorder.isActive());
        com.subhub.app.diagnostics.CensorLabRecorder.start(context);
        com.subhub.app.diagnostics.CensorLabRecorder.CompletedSession completed;
        try { connect(); awaitPaint(); assertBinding(); }
        finally { completed = com.subhub.app.diagnostics.CensorLabRecorder.stop(context); }
        assertNotNull(completed);
        String raw = new String(java.nio.file.Files.readAllBytes(completed.trace.toPath()), StandardCharsets.UTF_8);
        int rows = 0, engineRows = 0, postRows = 0;
        for (String line : raw.split("\n")) {
            if (line.trim().isEmpty()) continue;
            rows++; JSONObject event = new JSONObject(line);String message = event.getString("message");
            if (message.startsWith("REPLACEMENT_TRACE ")) {
                engineRows++;JSONObject value = new JSONObject(message.substring("REPLACEMENT_TRACE ".length()));
                assertEquals(1,value.getInt("schemaVersion"));assertTrue(value.getLong("connection")>0);
                assertEquals("UPTIME_MILLIS",value.getString("clockDomain"));
                assertTrue(value.getLong("atMillis")<=event.getLong("observedUptimeMillis"));
            } else if (message.startsWith("REPLACEMENT_POST ")) {
                postRows++;JSONObject value = new JSONObject(message.substring("REPLACEMENT_POST ".length()));
                assertEquals(-1,value.getLong("compositorAtMillis"));assertFalse(value.getBoolean("mainThread"));
                assertEquals(1,value.getInt("schemaVersion"));assertTrue(value.getLong("connection")>0);
            }
        }
        assertEquals(completed.eventCount,rows);assertEquals(0,completed.droppedEvents);
        assertTrue("Actual engine callbacks reached recorder",engineRows>0);
        assertTrue("Actual Surface receipts reached recorder",postRows>0);
        File directory = new File(context.getExternalFilesDir(null),"replacement-lab-proof");assertTrue(directory.mkdirs());
        java.nio.file.Files.copy(completed.trace.toPath(),new File(directory,"trace.ndjson").toPath());
        java.nio.file.Files.copy(completed.manifest.toPath(),new File(directory,"manifest.json").toPath());
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

    @Test public void actualServiceNaturalSingletonCountsOnlyCompletedNativeParents() throws Exception {
        naturalNativeAccounting(1);
    }

    @Test public void actualServiceNaturalCohortCountsOnlyCompletedNativeParents() throws Exception {
        naturalNativeAccounting(2);
    }

    /** Passive bookkeeping composition, not a throughput, quality or physical-motion gate. */
    private void naturalNativeAccounting(int nodes) throws Exception {
        requireProductScrollGate(); naturalAccountingNodes = nodes;
        nativeCounting("porn", 1, 500); // Preferences/stores/ordinary View only; no native demand.
        if (nodes == 2) {
            context.startActivity(targetIntent().putExtra("nativeScrollFixture", true).putExtra("label", "porn"));
            await("Two real ordinary provider captions", () -> {
                // Fixture readiness precedes connect; session-scoped glyph helpers cannot run yet.
                android.view.accessibility.AccessibilityNodeInfo root = ui.getRootInActiveWindow();
                if (root == null) return false;
                try {
                    CharSequence packageName = root.getPackageName();
                    if (packageName == null || !TARGET.contentEquals(packageName)) return false;
                    for (String id : new String[]{"android:id/text1", "android:id/text2"}) {
                        List<android.view.accessibility.AccessibilityNodeInfo> views = root.findAccessibilityNodeInfosByViewId(id);
                        try {
                            android.view.accessibility.AccessibilityNodeInfo view = views.size() == 1 ? views.get(0) : null;
                            if (view == null || !view.refresh() || !view.isVisibleToUser()) return false;
                            CharSequence text = view.getText();
                            if (text == null || !"porn".contentEquals(text)) return false;
                        } finally {
                            for (android.view.accessibility.AccessibilityNodeInfo view : views) if (view != null && view != root) view.recycle();
                        }
                    }
                    return true;
                } finally { root.recycle(); }
            });
        }
        naturalAccountingStamp = new StatsRepository(context).sessionStamp();
        naturalAccountingStartedAt = SystemClock.uptimeMillis(); connect();
        await("Actual current normal owner", () -> session.controller().activeScope() != null);
        CensorEngine.ActiveScope scope = session.controller().activeScope();
        assertEquals(ProtectionScope.Origin.NORMAL, scope.protectionScope.origin);
        await("Natural completed native sources reach the real Stats store", () ->
                new StatsRepository(context).load().getTotalBlocks() == nodes && committedNaturalCount() == nodes);
        await("Real aggregate native ledger and applied first-block achievement", () ->
                committedNaturalCents() == nodes * 7 && new AchievementManager(context).isUnlocked("first_block"));
        naturalAccountingCountedAt = SystemClock.uptimeMillis();
        naturalAccountingFirstUnlock = new AchievementManager(context).getUnlockedAt("first_block");
        assertTrue(naturalAccountingFirstUnlock > 0);
        Map<String, NativeTextResult.Candidate> countedFacts = new LinkedHashMap<>();
        for (CensorProductSession.AccountingResult result : accountResults) {
            if (result.phase != CensorProductSession.AccountingPhase.COUNT || result.status != CensorProductSession.AccountingStatus.COMMITTED) continue;
            for (SceneEngine.AcceptedSubject subject : result.receipt.subjects) if (subject.confirmedForConsumption && !subject.alreadyConsumed) {
                NativeTextResult.Candidate candidate = naturalCandidate(naturalFrame(result.receipt.source.sequence), subject);
                countedFacts.put(candidate.nodeKey, candidate);
            }
        }
        assertEquals(nodes, countedFacts.size());
        assertTrue(preferences.edit().putInt(SettingsRepository.KEY_CENSOR_INTENSITY, 80).commit());
        naturalAccountingAppearanceAt = SystemClock.uptimeMillis();
        await("Further natural COMPLETE parents after appearance-only change", () -> naturalCompleteParentsAfter(naturalAccountingAppearanceAt) >= 2);
        NativeTextResult.ReadMode partialMode = nodes == 1 ? NativeTextResult.ReadMode.ADMITTED_NODE_RECLASSIFY
                : NativeTextResult.ReadMode.ADMITTED_NODE_COHORT_RECLASSIFY;
        await("The same counted nodes retain natural partial renewal after appearance", () -> {
            for (NativeTextFrame frame : nativeFrames) {
                if (frame.source.readStartedAtMillis < naturalAccountingAppearanceAt || frame.observationOrdinal != 0
                        || frame.read.coverage != NativeTextResult.Coverage.POSITIVE_NODES_ONLY || frame.read.readMode != partialMode
                        || frame.read.candidates.size() != nodes) continue;
                if (frame.read.candidates.stream().anyMatch(candidate -> countedFacts.get(candidate.nodeKey) == null
                        || !sameNaturalFact(candidate, countedFacts.get(candidate.nodeKey)))) continue;
                if (nativeCosts.stream().noneMatch(cost -> cost.epoch == frame.source.epoch && cost.request == frame.requestIdentity
                        && cost.terminal == com.subhub.app.censor.engine.NativeReadCost.Terminal.SUCCESS)) continue;
                naturalAccountingPostAppearancePartialSource = frame.source.sequence; return true;
            }
            return false;
        });
        CensorEngine<PixelFrame> engine = actualEngineForControl();
        await("Current already-consumed source has a nonnull empty replay", () -> {
            synchronized (engine) {
                for (int i = evidence.size() - 1; i >= 0; i--) {
                    CensorEngine.EvidenceReceipt receipt = evidence.get(i);
                    if (receipt.nativeCoverage != NativeTextResult.Coverage.COMPLETE_TREE || receipt.subjects.isEmpty()
                            || receipt.subjects.stream().anyMatch(subject -> !subject.alreadyConsumed || !subject.confirmedForConsumption)) continue;
                    CensorEngine.EvidenceClaim replay = session.controller().claimEvidence(receipt, naturalAccountingStamp);
                    if (replay == null) continue; // Stale is inconclusive, never a replay pass.
                    assertTrue(replay.subjects.isEmpty()); naturalAccountingReplaySource = receipt.source.sequence;
                    naturalAccountingReplayEmpty = true; return true;
                }
                return false;
            }
        });
        assertNaturalNativeCredit(nodes);
        assertNaturalAccountingStores(nodes);
        naturalAccountingScopeStable = scope.same(session.controller().activeScope());
        assertTrue(naturalAccountingScopeStable); assertTrue(naturalAccountingStamp.isCurrent());
        assertEquals(naturalAccountingFirstUnlock, new AchievementManager(context).getUnlockedAt("first_block"));
        assertFalse(explicitNativeDemand); assertTrue(ownedHolds.isEmpty()); assertFalse(ownedLabStarted);
        assertFalse(controlledStorageResult); assertFalse(controlledObservationRedelivery);
        assertEquals(0, models.get()); assertTrue(faults.isEmpty()); assertBinding();
        naturalAccountingFinishedAt = SystemClock.uptimeMillis(); productScrollPassed = naturalAccountingPassed = true;
    }

    private int committedNaturalCount() {
        return accountResults.stream().filter(result -> result.phase == CensorProductSession.AccountingPhase.COUNT
                && result.status == CensorProductSession.AccountingStatus.COMMITTED).mapToInt(result -> result.count).sum();
    }
    private int committedNaturalCents() {
        return accountResults.stream().filter(result -> result.phase == CensorProductSession.AccountingPhase.LEDGER
                && result.status == CensorProductSession.AccountingStatus.COMMITTED).mapToInt(result -> result.chargedCents).sum();
    }
    private int naturalCompleteParentsAfter(long after) {
        return (int) nativeFrames.stream().filter(frame -> frame.observationOrdinal == 0
                && frame.source.readStartedAtMillis >= after
                && frame.read.readMode == NativeTextResult.ReadMode.FULL_DISCOVERY
                && frame.read.coverage == NativeTextResult.Coverage.COMPLETE_TREE).count();
    }
    private void assertNaturalAccountingStores(int nodes) {
        com.subhub.app.stats.StatsSnapshot stats = new StatsRepository(context).load();
        assertEquals(nodes, stats.getTotalBlocks()); assertEquals(nodes, stats.getCurrentSessionBlocks());
        assertEquals(nodes * 7, stats.getTributeCents()); assertEquals(nodes * 7, stats.getCurrentSessionTributeCents());
        assertEquals(0, stats.getAllCategoryCensors()); assertEquals(nodes, committedNaturalCount());
        assertEquals(nodes * 7, committedNaturalCents());
        List<com.subhub.app.penance.PenanceEvent> events = new PenanceManager(context).snapshot(System.currentTimeMillis()).getEvents();
        assertEquals(nodes, events.stream().mapToInt(com.subhub.app.penance.PenanceEvent::getStrikeCount).sum());
        assertEquals(nodes * 7, events.stream().mapToInt(com.subhub.app.penance.PenanceEvent::getAmountCents).sum());
        for (com.subhub.app.penance.PenanceEvent event : events) assertEquals(PenanceInfraction.NEW_DETECTION, event.getInfraction());
        assertFalse(new HardcoreAutoPayManager(context).isConfigured());
        assertTrue(context.getSharedPreferences(PayPalCredentialStore.PREFS_NAME, Context.MODE_PRIVATE).getAll().isEmpty());
    }
    private NativeTextFrame naturalFrame(long sequence) {
        NativeTextFrame selected = null;
        for (NativeTextFrame frame : nativeFrames) if (frame.source.sequence == sequence) {
            assertNull("One actual native Source", selected); selected = frame;
        }
        assertNotNull("Source has an actual native admission", selected); return selected;
    }
    private static boolean sameNaturalFact(NativeTextResult.Candidate a, NativeTextResult.Candidate b) {
        return java.util.Objects.equals(a.nodeKey, b.nodeKey) && a.contentKey.equals(b.contentKey) && a.category.equals(b.category)
                && a.textSource == b.textSource && a.matchStart == b.matchStart && a.matchEnd == b.matchEnd
                && a.normalizedLength == b.normalizedLength;
    }
    private NativeTextResult.Candidate naturalCandidate(NativeTextFrame frame, SceneEngine.AcceptedSubject subject) {
        NativeTextResult.Candidate selected = null;
        for (NativeTextResult.Candidate candidate : frame.read.candidates) for (SceneEngine.Region line : candidate.regions) {
            if (line.left == subject.region.left && line.top == subject.region.top && line.right == subject.region.right
                    && line.bottom == subject.region.bottom && candidate.category.equals(subject.category)) {
                assertNull("Exact candidate-to-subject region", selected); selected = candidate;
            }
        }
        assertNotNull(selected); assertNotNull(selected.nodeKey); assertTrue(selected.matchProvenanceKnown()); return selected;
    }
    private void assertNaturalNativeCredit(int nodes) {
        Set<String> countedNodes = new LinkedHashSet<>(); int count = 0;
        NativeTextResult.ReadMode partialMode = nodes == 1 ? NativeTextResult.ReadMode.ADMITTED_NODE_RECLASSIFY
                : NativeTextResult.ReadMode.ADMITTED_NODE_COHORT_RECLASSIFY;
        for (CensorEngine.EvidenceReceipt receipt : evidence) if (receipt.nativeCoverage == NativeTextResult.Coverage.POSITIVE_NODES_ONLY)
            for (SceneEngine.AcceptedSubject subject : receipt.subjects) assertFalse(subject.confirmedForConsumption);
        for (CensorProductSession.AccountingResult result : accountResults) {
            if (result.phase != CensorProductSession.AccountingPhase.COUNT || result.status != CensorProductSession.AccountingStatus.COMMITTED) continue;
            CensorEngine.EvidenceReceipt receipt = result.receipt; assertNotNull(receipt);
            NativeTextFrame current = naturalFrame(receipt.source.sequence);
            assertSame(current.source, receipt.source); assertEquals(0, current.observationOrdinal);
            assertEquals(NativeTextResult.Coverage.COMPLETE_TREE, receipt.nativeCoverage);
            assertEquals(NativeTextResult.Coverage.COMPLETE_TREE, current.read.coverage);
            assertEquals(NativeTextResult.ReadMode.FULL_DISCOVERY, current.read.readMode);
            assertEquals(ProtectionScope.Origin.NORMAL, receipt.source.protectionScope.origin);
            int selected = 0;
            for (SceneEngine.AcceptedSubject subject : receipt.subjects) {
                if (!subject.confirmedForConsumption || subject.alreadyConsumed) continue;
                assertSame(receipt.source, subject.source);
                NativeTextResult.Candidate candidate = naturalCandidate(current, subject);
                assertTrue("A canonical node is charged once", countedNodes.add(candidate.nodeKey));
                assertEquals(5, subject.witnesses.size()); assertEquals(current.candidateValidUntilMillis(candidate), subject.validUntilMillis);
                Set<Long> parents = new LinkedHashSet<>(); long first = Long.MAX_VALUE, last = -1;
                for (SceneEngine.EvidenceWitness witness : subject.witnesses) {
                    NativeTextFrame full = naturalFrame(witness.sourceSequence);
                    assertEquals(SceneEngine.SourceKind.NATIVE_TEXT, witness.kind);
                    assertEquals(full.requestIdentity, witness.producerIdentity); assertTrue(parents.add(witness.producerIdentity));
                    assertEquals(0, full.observationOrdinal); assertEquals(NativeTextResult.Coverage.COMPLETE_TREE, full.read.coverage);
                    assertEquals(NativeTextResult.ReadMode.FULL_DISCOVERY, full.read.readMode);
                    assertTrue(full.read.candidates.stream().anyMatch(value -> sameNaturalFact(candidate, value)));
                    assertTrue(nativeCosts.stream().anyMatch(cost -> cost.epoch == full.source.epoch && cost.request == full.requestIdentity
                            && cost.terminal == com.subhub.app.censor.engine.NativeReadCost.Terminal.SUCCESS));
                    assertEquals(receipt.source.epoch, full.source.epoch); assertEquals(receipt.source.geometry, full.source.geometry);
                    assertEquals(receipt.source.protectionScope, full.source.protectionScope);
                    assertTrue(full.source.sequence <= current.source.sequence);
                    first = Math.min(first, full.source.sequence); last = Math.max(last, full.source.sequence);
                }
                assertEquals(current.source.sequence, last);
                boolean partialBetween = false; long deadline = -1, validated = -1;
                long continuityEnd = Math.max(last, naturalAccountingPostAppearancePartialSource);
                for (NativeTextFrame frame : nativeFrames) {
                    if (frame.source.sequence < first || frame.source.sequence > continuityEnd) continue;
                    if (deadline >= 0) assertTrue("Only this node's original current deadline can bridge", frame.source.receivedAtMillis <= deadline);
                    NativeTextResult.Candidate fact = null;
                    for (NativeTextResult.Candidate value : frame.read.candidates) if (sameNaturalFact(candidate, value)) fact = value;
                    if (fact != null) {
                        assertTrue(fact.exactGeometry); assertTrue(fact.validatedAtMillis >= frame.source.readStartedAtMillis);
                        assertTrue(fact.validatedAtMillis <= frame.source.readFinishedAtMillis);
                        assertTrue("Own-node validation never rolls backward", fact.validatedAtMillis >= validated);
                        validated = fact.validatedAtMillis; deadline = frame.candidateValidUntilMillis(fact);
                        assertTrue(frame.source.receivedAtMillis <= deadline);
                    }
                    else assertNotEquals("A COMPLETE absence cannot preserve a node", NativeTextResult.Coverage.COMPLETE_TREE, frame.read.coverage);
                    if (fact != null && frame.source.sequence > first && frame.source.sequence < last && frame.observationOrdinal == 0
                            && frame.read.readMode == partialMode && frame.read.coverage == NativeTextResult.Coverage.POSITIVE_NODES_ONLY
                            && nativeCosts.stream().anyMatch(cost -> cost.epoch == frame.source.epoch && cost.request == frame.requestIdentity
                                && cost.terminal == com.subhub.app.censor.engine.NativeReadCost.Terminal.SUCCESS)) partialBetween = true;
                }
                assertTrue("The counted node really crossed a natural selected-node terminal", partialBetween);
                selected++;
            }
            assertEquals(result.count, selected); count += result.count;
        }
        assertEquals(nodes, count); assertEquals(nodes, countedNodes.size());
    }

    private void finishNaturalAccountingEvidence() throws Exception {
        com.subhub.app.stats.StatsSnapshot stats = new StatsRepository(context).load();
        JSONArray subjects = new JSONArray(), events = new JSONArray();
        for (CensorEngine.EvidenceReceipt receipt : evidence) {
            JSONArray members = new JSONArray();
            for (SceneEngine.AcceptedSubject subject : receipt.subjects) members.put(new JSONObject()
                    .put("identity", subject.identity).put("partition", subject.partition).put("textGroup", subject.textGroup)
                    .put("category", subject.category).put("region", regionMetadata(subject.region))
                    .put("nativeCoverage", subject.nativeCoverage == null ? JSONObject.NULL : subject.nativeCoverage.name()));
            subjects.put(new JSONObject().put("sequence", receipt.source.sequence).put("epoch", receipt.source.epoch)
                    .put("nativeCoverage", receipt.nativeCoverage == null ? JSONObject.NULL : receipt.nativeCoverage.name())
                    .put("rawSubjectCount", members.length()).put("subjects", members));
        }
        for (com.subhub.app.penance.PenanceEvent event : new PenanceManager(context).snapshot(System.currentTimeMillis()).getEvents())
            events.put(new JSONObject().put("strikeCount", event.getStrikeCount()).put("amountCents", event.getAmountCents())
                    .put("infraction", event.getInfraction().name()).put("status", event.getStatus().name()));
        AchievementManager achievements = new AchievementManager(context);
        JSONObject result = new JSONObject().put("schemaVersion", 1).put("method", testName.getMethodName())
                .put("expectedNodes", naturalAccountingNodes).put("nativeAssertionPassed", naturalAccountingPassed)
                .put("clockDomain", "UPTIME_MILLIS").put("startedAtMillis", naturalAccountingStartedAt)
                .put("countedAtMillis", naturalAccountingCountedAt).put("finishedAtMillis", naturalAccountingFinishedAt)
                .put("appearanceChangedAtMillis", naturalAccountingAppearanceAt)
                .put("postAppearancePartialSource", naturalAccountingPostAppearancePartialSource)
                .put("scopeStableBeforeClose", naturalAccountingScopeStable)
                .put("originalStatsStampCurrent", naturalAccountingStamp != null && naturalAccountingStamp.isCurrent())
                .put("currentReplayNonNullEmpty", naturalAccountingReplayEmpty).put("currentReplaySource", naturalAccountingReplaySource)
                .put("stats", new JSONObject().put("totalBlocks", stats.getTotalBlocks()).put("currentSessionBlocks", stats.getCurrentSessionBlocks())
                        .put("tributeCents", stats.getTributeCents()).put("currentSessionTributeCents", stats.getCurrentSessionTributeCents())
                        .put("allCategoryCensors", stats.getAllCategoryCensors()))
                .put("rawLedgerEventCount", events.length()).put("ledgerEvents", events)
                .put("firstBlockUnlocked", achievements.isUnlocked("first_block"))
                .put("firstBlockUnlockedAt", achievements.getUnlockedAt("first_block"))
                .put("firstBlockOriginalUnlockedAt", naturalAccountingFirstUnlock).put("achievementDiskCommitAcknowledged", false)
                .put("rawEvidenceBindingCount", subjects.length()).put("evidenceBindings", subjects)
                .put("providerHoldsUsed", !ownedHolds.isEmpty()).put("explicitNativeDemand", explicitNativeDemand)
                .put("recorderUsed", ownedLabStarted).put("autopayConfigured", new HardcoreAutoPayManager(context).isConfigured())
                .put("paymentCredentialsPresent", !context.getSharedPreferences(PayPalCredentialStore.PREFS_NAME, Context.MODE_PRIVATE).getAll().isEmpty())
                .put("managedDrain", session != null && session.managedResourcesDrained())
                .put("physicalMotionAcceptance", false).put("pixelPerformanceAcceptance", false);
        File target = new File(productScrollEvidence, "native-natural-accounting.json"); assertFalse(target.exists());
        try (FileOutputStream stream = new FileOutputStream(target)) { stream.write(result.toString(2).getBytes(StandardCharsets.UTF_8)); }
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

    private AccessibilityCensorController.WindowCoverage awaitNativeViewport() throws Exception {
        AtomicReference<AccessibilityCensorController.WindowCoverage> result = new AtomicReference<>();
        await("Current detected native viewport", () -> {
            main(() -> {
                AccessibilityCensorController.WindowCoverage candidate = session.controller().viewportCoverage();
                if (candidate.windowQualified && !candidate.regions.isEmpty() && session.controller().coverageCurrent(candidate)
                        && candidate.regions.stream().allMatch(region -> region.member.nativeValidated)
                        && evidence.stream().anyMatch(receipt -> receipt.source == candidate.regions.get(0).member.evidenceSource)) result.set(candidate);
            });
            return result.get() != null;
        });
        return result.get();
    }
    private static final class ProviderGeometry {
        final int windowId;
        final long startedAtMillis, finishedAtMillis;
        final android.graphics.Rect nodeBounds;
        final List<android.graphics.RectF> characters;
        ProviderGeometry(int windowId, long start, android.graphics.Rect nodeBounds, List<android.graphics.RectF> characters) {
            this.windowId = windowId; startedAtMillis = start; finishedAtMillis = SystemClock.uptimeMillis();
            this.nodeBounds = nodeBounds; this.characters = characters;
        }
    }
    private ProviderGeometry providerGeometry(String literal) {
        long start = SystemClock.uptimeMillis();
        android.view.accessibility.AccessibilityNodeInfo root = ui.getRootInActiveWindow();
        if (root == null) return null;
        List<android.view.accessibility.AccessibilityNodeInfo> nodes = Collections.emptyList();
        try {
            root.refresh(); nodes = root.findAccessibilityNodeInfosByText(literal);
            for (android.view.accessibility.AccessibilityNodeInfo node : nodes) {
                if (node != null && node.refresh() && literal.contentEquals(node.getText() == null ? "" : node.getText())) {
                    android.graphics.Rect bounds = new android.graphics.Rect(); node.getBoundsInScreen(bounds);
                    String key = android.view.accessibility.AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY;
                    List<String> extraData = node.getAvailableExtraData();
                    if (bounds.isEmpty() || extraData == null || !extraData.contains(key)) return null;
                    android.os.Bundle arguments = new android.os.Bundle();
                    arguments.putInt(android.view.accessibility.AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, 0);
                    arguments.putInt(android.view.accessibility.AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, literal.length());
                    if (!node.refreshWithExtraData(key, arguments)
                            || !literal.contentEquals(node.getText() == null ? "" : node.getText())) return null;
                    android.os.Parcelable[] locations = node.getExtras().getParcelableArray(key);
                    if (locations == null || locations.length != literal.length()) return null;
                    List<android.graphics.RectF> characters = new ArrayList<>();
                    for (android.os.Parcelable location : locations) {
                        if (!(location instanceof android.graphics.RectF)) return null;
                        android.graphics.RectF character = new android.graphics.RectF((android.graphics.RectF) location);
                        if (!Float.isFinite(character.left) || !Float.isFinite(character.top) || !Float.isFinite(character.right)
                                || !Float.isFinite(character.bottom) || character.isEmpty()) return null;
                        characters.add(character);
                    }
                    return new ProviderGeometry(node.getWindowId(), start, bounds, characters);
                }
            }
            return null;
        } finally { for (android.view.accessibility.AccessibilityNodeInfo node : nodes) if (node != null && node != root) node.recycle(); root.recycle(); }
    }
    private static SceneEngine.Region windowRegion(AccessibilityWindowReader.Window window) {
        return new SceneEngine.Region(window.left, window.top, (double) window.left + window.width, (double) window.top + window.height);
    }
    private static void assertRegion(SceneEngine.Region expected, SceneEngine.Region actual) {
        assertNotNull(expected); assertEquals(expected.left, actual.left, 0); assertEquals(expected.top, actual.top, 0);
        assertEquals(expected.right, actual.right, 0); assertEquals(expected.bottom, actual.bottom, 0);
    }
    private void writeViewport(String label, AccessibilityCensorController.WindowCoverage coverage) throws Exception {
        writeViewport(label, coverage, null);
    }
    private void writeViewport(String label, AccessibilityCensorController.WindowCoverage coverage, ProviderGeometry independent) throws Exception {
        JSONArray detected = new JSONArray(), clipped = new JSONArray();
        for (CensorEngine.DetectedRegion region : coverage.receipt.regions) {
            detected.put(new JSONObject().put("identity", region.identity).put("partition", region.partition).put("textGroup", region.textGroup)
                    .put("category", region.category).put("nativeValidated", region.nativeValidated).put("physicalErrorKnown", region.physicalErrorKnown)
                    .put("displayRegion", regionMetadata(region.displayRegion)).put("evidenceSource", viewportSource(region.evidenceSource))
                    .put("geometrySource", viewportSource(region.geometrySource)));
        }
        for (AccessibilityCensorController.WindowRegion region : coverage.regions)
            clipped.put(new JSONObject().put("identity", region.member.identity).put("displayRegion", regionMetadata(region.displayRegion)));
        assertEquals(coverage.receipt.regions.size(), detected.length()); assertEquals(coverage.regions.size(), clipped.length());
        JSONObject window = coverage.window == null ? null : new JSONObject().put("id", coverage.window.id)
                .put("left", coverage.window.left).put("top", coverage.window.top).put("width", coverage.window.width).put("height", coverage.window.height)
                .put("displayWidth", coverage.window.displayWidth).put("displayHeight", coverage.window.displayHeight);
        JSONObject row = new JSONObject().put("schemaVersion", 1).put("label", label).put("pid", Process.myPid())
                .put("state", coverage.receipt.state.name()).put("queriedAtMillis", coverage.receipt.queriedAtMillis)
                .put("sampledAtMillis", SystemClock.uptimeMillis()).put("validBeforeMillis", coverage.receipt.validBeforeMillis)
                .put("currentAtSample", session.controller().coverageCurrent(coverage)).put("windowQualified", coverage.windowQualified)
                .put("window", window == null ? JSONObject.NULL : window)
                .put("canvas", coverage.receipt.canvas == null ? JSONObject.NULL : viewportSource(coverage.receipt.canvas))
                .put("rawDetectedCount", coverage.receipt.regions.size()).put("detectedRegions", detected)
                .put("rawWindowRegionCount", coverage.regions.size()).put("windowRegions", clipped)
                .put("unresolvedTracks", coverage.receipt.unresolvedTracks).put("tentativeTracks", coverage.receipt.tentativeTracks)
                .put("ineligibleMembers", coverage.receipt.ineligibleMembers).put("compositorAtMillis", coverage.receipt.compositorAtMillis)
                .put("physicalMotionAcceptance", false).put("pixelPerformanceAcceptance", false);
        if (label.equals("native-inset")) {
            JSONObject provider = null;
            if (independent != null) {
                JSONArray characters = new JSONArray();
                for (android.graphics.RectF character : independent.characters)
                    characters.put(new JSONArray().put(character.left).put(character.top).put(character.right).put(character.bottom));
                android.graphics.Rect node = independent.nodeBounds;
                provider = new JSONObject().put("windowId", independent.windowId).put("readStartedAtMillis", independent.startedAtMillis)
                        .put("readFinishedAtMillis", independent.finishedAtMillis).put("rawCharacterCount", independent.characters.size())
                        .put("characters", characters).put("nodeBounds", new JSONArray().put(node.left).put(node.top).put(node.right).put(node.bottom));
                assertEquals(independent.characters.size(), characters.length());
            }
            row.put("schemaVersion", 2).put("independentProviderGeometry", provider == null ? JSONObject.NULL : provider);
        }
        try (FileOutputStream stream = new FileOutputStream(new File(directory(), "native-mask-viewport-" + label + "-" + System.nanoTime() + ".json"))) {
            stream.write(row.toString().getBytes(StandardCharsets.UTF_8));
        }
    }
    private static JSONArray regionMetadata(SceneEngine.Region region) throws Exception {
        return new JSONArray().put(region.left).put(region.top).put(region.right).put(region.bottom);
    }
    private static JSONObject viewportSource(SceneEngine.Source source) throws Exception {
        return new JSONObject().put("epoch", source.epoch).put("sequence", source.sequence).put("geometry", source.geometry)
                .put("width", source.width).put("height", source.height).put("kind", source.kind.name())
                .put("imageRequestIdentity", source.imageRequestIdentity).put("receivedAtMillis", source.receivedAtMillis)
                .put("pixelAtMillis", source.pixelAtMillis).put("readStartedAtMillis", source.readStartedAtMillis)
                .put("readFinishedAtMillis", source.readFinishedAtMillis).put("windowId", source.windowId)
                .put("origin", source.protectionScope.origin.name());
    }

    private void nativeOnly(String literal) throws Exception {
        assertTrue(preferences.edit().putStringSet(SettingsRepository.KEY_ENABLED_CATEGORIES, Collections.emptySet())
                .putBoolean(SettingsRepository.KEY_TEXT_SMUT_ENABLED, true).commit());
        literalTarget(literal);
    }
    private void nativeDwell(String literal) throws Exception {
        nativeOnly(literal); configureDwellRule();
    }
    private void configureDwellRule() {
        new StatsRepository(context).startSession();
        Map<PenanceInfraction, Integer> rules = new EnumMap<>(PenanceInfraction.class); rules.put(PenanceInfraction.CENSORED_DWELL, 7);
        new PenanceManager(context).configure(true, rules, 500, 2000, 10, 3, 50, 5);
        assertFalse(new HardcoreAutoPayManager(context).isConfigured());
        assertTrue(context.getSharedPreferences(PayPalCredentialStore.PREFS_NAME, Context.MODE_PRIVATE).getAll().isEmpty());
    }
    @SuppressWarnings("unchecked") private CensorEngine<PixelFrame> actualEngineForControl() throws Exception {
        java.lang.reflect.Field field = AccessibilityCensorHost.class.getDeclaredField("engine"); field.setAccessible(true);
        return (CensorEngine<PixelFrame>) field.get(session.controller().host());
    }
    @SuppressWarnings("unchecked") private com.subhub.app.censor.engine.BoundedLatestWork<CensorEngine.EvidenceReceipt> actualEvidenceAdmission() {
        try {
            java.lang.reflect.Field field = CensorProductSession.class.getDeclaredField("evidenceAdmission"); field.setAccessible(true);
            return (com.subhub.app.censor.engine.BoundedLatestWork<CensorEngine.EvidenceReceipt>) field.get(session);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private long committedDwell() {
        return dwellResults.stream().filter(r -> r.status == CensorProductSession.AccountingStatus.COMMITTED).count();
    }
    private void awaitDwell(int count) throws Exception { awaitNative("Acknowledged real viewport dwell " + count, () -> committedDwell() >= count); }
    private void assertOnlyDwell(int count) {
        List<com.subhub.app.penance.PenanceEvent> events = new PenanceManager(context).snapshot(System.currentTimeMillis()).getEvents();
        assertEquals(count, events.size());
        for (com.subhub.app.penance.PenanceEvent event : events) {
            assertEquals(PenanceInfraction.CENSORED_DWELL, event.getInfraction()); assertEquals(7, event.getAmountCents());
            assertEquals(1, event.getStrikeCount());
        }
    }
    private void waitWithoutDwell(long until) throws Exception {
        while (SystemClock.uptimeMillis() < until) { assertEquals(0, committedDwell()); Thread.sleep(10); }
        assertEquals(0, committedDwell());
    }
    private void dwellStorageControl(boolean acknowledged, boolean applyUnderlying, boolean throwAfter, Hold hold, AtomicInteger writes) {
        controlledStorageResult = true;
        ProductCensorQualification.fixturePenance(app -> new PenanceManager(new ContextWrapper(app) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                SharedPreferences actual = super.getSharedPreferences(name, mode);
                if (!PenanceManager.PREFS_NAME.equals(name)) return actual;
                return (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class},
                        (proxy, method, args) -> {
                            Object result = method.invoke(actual, args); if (!method.getName().equals("edit")) return result;
                            SharedPreferences.Editor edit = (SharedPreferences.Editor) result; AtomicBoolean ledger = new AtomicBoolean();
                            return Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(), new Class<?>[]{SharedPreferences.Editor.class},
                                    (editorProxy, operation, values) -> {
                                        if (operation.getName().equals("putString") && "events_v1".equals(values[0])) ledger.set(true);
                                        if (operation.getName().equals("commit") && ledger.get()) {
                                            assertNotEquals(Looper.getMainLooper(), Looper.myLooper()); writes.incrementAndGet(); storageFaultCalls.incrementAndGet();
                                            if (hold != null) hold.run();
                                            if (applyUnderlying) assertTrue(edit.commit());
                                            if (throwAfter) throw new IllegalStateException("Controlled ambiguous dwell acknowledgement");
                                            return acknowledged;
                                        }
                                        Object value = operation.invoke(edit, values); return value instanceof SharedPreferences.Editor ? editorProxy : value;
                                    });
                        });
            }
        }));
    }
    @SuppressWarnings("unchecked") private List<RectF> actualPopupHints() throws Exception {
        AtomicReference<List<RectF>> result = new AtomicReference<>();
        main(() -> {
            try {
                java.lang.reflect.Field field = PopupStormManager.class.getDeclaredField("detectionHints"); field.setAccessible(true);
                java.lang.reflect.Field fallback = PopupStormManager.class.getDeclaredField("detections"); fallback.setAccessible(true);
                result.set(((ScopedDetectionHints<RectF>) field.get(PopupStormManager.get()))
                        .read((List<RectF>) fallback.get(PopupStormManager.get())));
            } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        });
        return result.get();
    }
    private float[] popupPosition(int width, int height) {
        AtomicReference<float[]> result = new AtomicReference<>();
        main(() -> {
            try {
                java.lang.reflect.Method method = PopupStormManager.class.getDeclaredMethod("choosePosition", PopupStormSettings.class, int.class, int.class);
                method.setAccessible(true); result.set((float[]) method.invoke(PopupStormManager.get(), PopupStormSettings.load(context), width, height));
            } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        });
        return result.get();
    }
    private int popupReservedTop() throws Exception {
        java.lang.reflect.Field field = PopupStormManager.class.getDeclaredField("reservedTop"); field.setAccessible(true);
        return field.getInt(PopupStormManager.get());
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
    private void disconnectCandidate(CensorProductSession connected) throws Exception {
        ui = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_USE_ACCESSIBILITY);
        secure(previousServices, previousEnabled);
        await("Actual candidate service unbinding", () -> !ScreenshotAccessibilityService.isRunning());
        await("Disconnected owner fully releases resources", connected::managedResourcesDrained);
        main(() -> ProductCensorQualification.disconnected(connected));
        assertNull(ProductCensorQualification.current());
        assertEquals(models.get(), closes.get()); assertFalse(earlyClose.get());
    }
    private void retainBindingFailure() throws Exception {
        File target = new File(context.getExternalFilesDir(null), "candidate-binding-failure.txt");
        assertFalse("Retain previous binding diagnostics", target.exists());
        try (android.os.ParcelFileDescriptor descriptor = ui.executeShellCommand("dumpsys accessibility");
                java.io.InputStream input = new android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor);
                FileOutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[8192]; int total = 0, length;
            while ((length = input.read(buffer)) != -1) {
                total += length; if (total > 262144) throw new IllegalStateException("Bounded owned binding diagnostic");
                output.write(buffer, 0, length);
            }
        }
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
        if (cadenceEvidence != null) return cadenceEvidence;
        if (productScrollEvidence != null) return productScrollEvidence;
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
                    .put("positiveInput", receipt.positiveInput)
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
        JSONArray dwellRows = new JSONArray();
        for (CensorProductSession.DwellResult result : dwellResults) {
            JSONObject row = new JSONObject().put("status", result.status.name()).put("chargedCents", result.chargedCents)
                    .put("pendingDrops", result.pendingDrops).put("observationProduced", result.observation != null);
            if (result.observation != null) {
                CensorEngine.EvidenceReceipt observation = result.observation;
                row.put("observation", sourceMetadata(observation).put("triggerLane", observation.triggerLane.name())
                        .put("positiveInput", observation.positiveInput).put("acceptedAtMillis", observation.acceptedAtMillis));
                JSONArray regions = new JSONArray();
                for (AccessibilityCensorController.WindowRegion region : result.coverage.regions)
                    regions.put(new JSONObject().put("identity", region.member.identity).put("nativeValidated", region.member.nativeValidated)
                            .put("dwellPositive", region.member.dwellPositive).put("displayRegion", regionMetadata(region.displayRegion))
                            .put("evidenceSource", viewportSource(region.member.evidenceSource)).put("geometrySource", viewportSource(region.member.geometrySource)));
                assertEquals(result.coverage.regions.size(), regions.length());
                row.put("coverage", new JSONObject().put("state", result.coverage.receipt.state.name())
                        .put("queriedAtMillis", result.coverage.receipt.queriedAtMillis).put("validBeforeMillis", result.coverage.receipt.validBeforeMillis)
                        .put("windowQualified", result.coverage.windowQualified).put("rawRegionCount", result.coverage.regions.size()).put("regions", regions));
            }
            dwellRows.put(row);
        }
        assertEquals(dwellResults.size(), dwellRows.length());
        JSONObject metrics = new JSONObject().put("schemaVersion", 5).put("actualProductionService", session != null)
                .put("sameInstrumentationPid", session != null && ((ScreenshotAccessibilityService) session.service()).qualificationBoundPid() == Process.myPid())
                .put("pid", Process.myPid()).put("rawTraceCount", traces.size()).put("traces", rows).put("posts", submitted)
                .put("rawPostCount", posts.size()).put("models", models.get()).put("closes", closes.get()).put("managedDrain", session != null && session.managedResourcesDrained())
                .put("legacyCensorRuntimeAbsent", session != null && ((ScreenshotAccessibilityService) session.service()).legacyCensorRuntimeAbsentForQualification())
                .put("controlledModelsOnly", !controlledStorageResult && !controlledObservationRedelivery)
                .put("physicalMotionAcceptance", false).put("pixelPerformanceAcceptance", false);
        metrics.put("rawEvidenceCount", evidence.size()).put("evidence", evidenceRows)
                .put("rawAccountingCount", accountResults.size()).put("accounting", accountingRows)
                .put("rawDwellCount", dwellResults.size()).put("dwell", dwellRows)
                .put("controlledStorageResult", controlledStorageResult).put("storageFaultCalls", storageFaultCalls.get())
                .put("explicitNativeDemand", explicitNativeDemand).put("controlledObservationRedelivery", controlledObservationRedelivery);
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
