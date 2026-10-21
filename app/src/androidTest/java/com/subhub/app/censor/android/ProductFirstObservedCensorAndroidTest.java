package com.subhub.app.censor.android;

import static org.junit.Assert.*;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Looper;
import android.os.Process;
import android.provider.Settings;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.appmode.AppTimerManager;
import com.subhub.app.detection.DetectorConfig;
import com.subhub.app.penance.PayPalCredentialStore;
import com.subhub.app.penance.PenanceManager;
import com.subhub.app.security.HardcoreModeManager;
import com.subhub.app.service.ScreenshotAccessibilityService;
import com.subhub.app.settings.*;
import com.subhub.app.stats.StatsRepository;
import com.subhub.app.stats.DailyStatsStore;
import com.subhub.app.stats.DailyDurationRecorder;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;

/** One finite observation; a known API outcome is not first-censor or physical-Pixel acceptance. */
@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion = 34)
public final class ProductFirstObservedCensorAndroidTest {
    @Test public void firstObservedCensorRequiresQualifiedPhotoAndActualPaint() throws Exception {
        run(false);
    }
    @Test public void realPhotoLongFastScrollAndReversalsRetainAllObservations() throws Exception {
        run(true);
    }
    private void run(boolean scrolling) throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        assertEquals("true", InstrumentationRegistry.getArguments().getString("bound_censor_fixture"));
        assertTrue("Owned emulator only", ProductCensorQualification.supported());
        String namespace = InstrumentationRegistry.getArguments().getString("product_first_visible_run");
        assertNotNull(namespace); assertTrue(namespace.matches("first_visible_[a-f0-9]{32}"));
        String setupNamespace = scrolling ? InstrumentationRegistry.getArguments().getString("product_first_visible_setup_run") : namespace;
        assertNotNull(setupNamespace); assertTrue(setupNamespace.matches("first_visible_[a-f0-9]{32}"));
        JSONObject setup=new JSONObject(new String(java.nio.file.Files.readAllBytes(new File(context.getFilesDir(),"first-visible-setup/"+setupNamespace+"/setup.json").toPath()),java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(setup.getBoolean("passed"));assertEquals(setupNamespace,setup.getString("namespace"));
        String services = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        String enabled = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED);
        assertFalse("No enabled capture intent may be borrowed", "1".equals(enabled) && services != null
                && (services.contains("ScreenshotAccessibilityService") || services.contains("ScreenCapture")));
        assertFalse("No live owner", ScreenshotAccessibilityService.isRunning()); assertNull(ProductCensorQualification.current());
        Map<String, Map<String, ?>> saved = new LinkedHashMap<>();
        SettingsRepository settings = new SettingsRepository(context); SharedPreferences preferences = settings.preferences();
        for (String name : Arrays.asList(StatsRepository.PREFS_NAME, PenanceManager.PREFS_NAME, "betablocker_achievements",
                "subhub_hardcore_auto_pay", PayPalCredentialStore.PREFS_NAME, "detector_provider_cache",
                ProductCensorQualification.PREFERENCES)) saved.put(name, copy(context.getSharedPreferences(name, Context.MODE_PRIVATE).getAll()));
        Map<String, ?> savedPreferences = copy(preferences.getAll());
        assertTrue("No provider authorization participates", saved.get(PayPalCredentialStore.PREFS_NAME).isEmpty());
        assertTrue("No automatic provider schedule may be borrowed", saved.get("subhub_hardcore_auto_pay").isEmpty());
        AtomicReference<CensorProductSession> owner = new AtomicReference<>();
        AtomicReference<Throwable> cleanupFailure = new AtomicReference<>();
        UiAutomation ui = null; ProductFirstObservedCensor recorder = null; Throwable bodyFailure = null;
        PositivePhotoScrollObservation scrollObservation = null;
        DailyIsolation daily = new DailyIsolation(context, namespace);
        try {
            daily.isolate();
            new StatsRepository(context).endSession();
            for (String name : Arrays.asList(StatsRepository.PREFS_NAME, PenanceManager.PREFS_NAME, "betablocker_achievements",
                    "subhub_hardcore_auto_pay", PayPalCredentialStore.PREFS_NAME))
                assertTrue(context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit());
            assertTrue(preferences.edit().putBoolean(AppModeManager.KEY_ARMED, true)
                    .putStringSet(AppModeManager.KEY_INCLUDED_PACKAGES, Collections.singleton(ProductFirstObservedCensor.TARGET))
                    .putBoolean(AppModeManager.KEY_ALL_APPS, false).putBoolean(FeatureModuleManager.KEY_CENSOR_ENABLED, true)
                    .putString(SettingsRepository.KEY_CAPTURE_METHOD, CaptureMethod.APP_MODE.preferenceValue())
                    .putString(SettingsRepository.KEY_DETECTION_PRESET, "medium")
                    .putStringSet(SettingsRepository.KEY_ENABLED_CATEGORIES, Collections.singleton("face"))
                    .putInt(SettingsRepository.KEY_CONFIDENCE, 25).putString(SettingsRepository.KEY_CENSOR_COVERAGE, "detected_areas")
                    .putBoolean(SettingsRepository.KEY_TEXT_SMUT_ENABLED, false).putString(SettingsRepository.KEY_CENSOR_TYPE, "box")
                    .putInt(SettingsRepository.KEY_CENSOR_INTENSITY, 100).putFloat(SettingsRepository.KEY_CENSOR_SIZE_PADDING, 0)
                    .putBoolean(SettingsRepository.KEY_SHOW_BORDER, false).putBoolean(SettingsRepository.KEY_SHOW_TEXT, false)
                    .putBoolean(SettingsRepository.KEY_REVERSE_MODE, false)
                    .putString(SettingsRepository.paletteKey(CensorAppearance.Type.BOX, 1), "#FFFF0080")
                    .putBoolean(AppTimerManager.KEY_PER_APP_ENABLED, false).putBoolean(AppTimerManager.KEY_TOTAL_ENABLED, false)
                    .putBoolean(HardcoreModeManager.KEY_REQUESTED, false).putBoolean(FeatureModuleManager.KEY_SUBLIMINAL_ENABLED, false)
                    .putBoolean(com.subhub.app.popup.PopupStormSettings.K_ENABLED, false).commit());
            DetectorConfig configured = settings.loadDetectorConfig();
            assertEquals(3, configured.getInferenceThreads()); assertEquals(480, configured.getInferenceResolution());
            assertEquals(.25f, configured.getConfidenceThreshold(), 0); assertEquals(12, configured.getMinDetectionSize());
            assertEquals(Collections.singleton("face"), configured.getEnabledCategories());
            assertTrue(context.getSharedPreferences(ProductCensorQualification.PREFERENCES, Context.MODE_PRIVATE).edit().putBoolean("enabled", true).commit());
            ProductCensorQualification.fixtureStatistics(StatsRepository::new); ProductCensorQualification.fixturePenance(PenanceManager::new);
            ui = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            AccessibilityServiceInfo info = ui.getServiceInfo(); assertNotNull(info);
            info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS; ui.setServiceInfo(info);
            recorder = new ProductFirstObservedCensor(context, ui, namespace, scrolling);
            recorder.prepareInput(); recorder.launchAndAwaitTarget();
            ProductCensorQualification.fixtureInputObserver(recorder::inputBoundary);
            ProductCensorQualification.fixtureModels(recorder::initialize); ProductCensorQualification.fixtureObserver(recorder);
            ComponentName component = new ComponentName(context, ScreenshotAccessibilityService.class);
            long setupDeadline = ProductFirstObservedCensor.now() + 12000;
            secure(context, ui, services == null || services.isEmpty() ? component.flattenToString() : services + ":" + component.flattenToString(), "1");
            ProductFirstObservedCensor probe = recorder;
            ProductFirstObservedCensor.await("Actual live product service/protocol/first dispatch", setupDeadline, () -> {
                CensorProductSession current = ProductCensorQualification.current(); if (current == null || !ScreenshotAccessibilityService.isRunning()) return false;
                ScreenshotAccessibilityService actual = (ScreenshotAccessibilityService) current.service();
                AccessibilityServiceInfo bound = actual.getServiceInfo(); if (bound == null) return false;
                if (actual.qualificationBoundPid() != Process.myPid() || bound.getResolveInfo() == null
                        || !ScreenshotAccessibilityService.class.getName().equals(bound.getResolveInfo().serviceInfo.name)
                        || !bound.getCanRetrieveWindowContent()
                        || (bound.getCapabilities() & AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT) == 0
                        || !actual.legacyCensorRuntimeAbsentForQualification() || ProductCensorQualification.current() != current) return false;
                if (owner.compareAndSet(null, current)) probe.bound(System.identityHashCode(actual), actual.qualificationBoundPid(),
                        bound.getCapabilities(), actual.legacyCensorRuntimeAbsentForQualification());
                assertSame(owner.get(), current);
                return probe.firstDispatched();
            });
            while (!recorder.outcomeFrozen()) { recorder.observeVisible(owner.get()); Thread.sleep(10); }
            assertTrue("An actual source-supported visible censor within the unchanged observation budget", recorder.visible());
            if (scrolling) {
                scrollObservation = new PositivePhotoScrollObservation(context, ui, recorder, owner.get());
                scrollObservation.run();
            }
        } catch (Exception | Error failure) { bodyFailure = failure; }
        finally {
            ProductFirstObservedCensor probe = recorder;
            PositivePhotoScrollObservation motion = scrollObservation;
            CensorProductSession actual = owner.get() == null ? ProductCensorQualification.current() : owner.get();
            step(cleanupFailure, () -> {
                if (actual != null) {
                    if (probe != null) probe.closeRequested();
                    main(actual::close);
                    ProductFirstObservedCensor.await("Actual managed drain, including executor termination", ProductFirstObservedCensor.now() + 12000, actual::managedResourcesDrained);
                    if (probe != null) probe.managedDrained();
                    main(() -> ProductCensorQualification.disconnected(actual));
                }
            });
            step(cleanupFailure, () -> { if (motion != null) motion.exportAndRecycle(); });
            step(cleanupFailure, () -> { if (probe != null) probe.retireTargetAndInput(); });
            // Every restoration is attempted even when a previous drain, retirement or export failed.
            AtomicReference<UiAutomation> automation = new AtomicReference<>(ui);
            step(cleanupFailure, () -> automation.set(instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_USE_ACCESSIBILITY)));
            step(cleanupFailure, () -> secure(context, automation.get(), services, enabled));
            step(cleanupFailure, () -> ProductFirstObservedCensor.await("Actual service unbinding", ProductFirstObservedCensor.now() + 12000,
                    () -> !ScreenshotAccessibilityService.isRunning()));
            step(cleanupFailure, () -> new StatsRepository(context).endSession());
            for (Map.Entry<String, Map<String, ?>> entry : saved.entrySet())
                step(cleanupFailure, () -> restore(context.getSharedPreferences(entry.getKey(), Context.MODE_PRIVATE), entry.getValue()));
            step(cleanupFailure, () -> restore(preferences, savedPreferences));
            step(cleanupFailure, () -> new StatsRepository(context).sessionStamp());
            step(cleanupFailure, () -> ProductCensorQualification.fixtureModels(null));
            step(cleanupFailure, () -> ProductCensorQualification.fixtureObserver(null));
            step(cleanupFailure, () -> ProductCensorQualification.fixtureInputObserver(null));
            step(cleanupFailure, () -> ProductCensorQualification.fixtureStatistics(StatsRepository::new));
            step(cleanupFailure, () -> ProductCensorQualification.fixturePenance(PenanceManager::new));
            step(cleanupFailure, daily::restore);
            step(cleanupFailure, () -> { if (probe != null) probe.dailyStore(daily.receipt()); });
            AtomicReference<Throwable> restorationFailure = new AtomicReference<>();
            step(restorationFailure, () -> {
                assertEquals(savedPreferences, preferences.getAll());
                for (Map.Entry<String, Map<String, ?>> entry : saved.entrySet())
                    assertEquals(entry.getValue(), context.getSharedPreferences(entry.getKey(), Context.MODE_PRIVATE).getAll());
                assertEquals(services, Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES));
                assertEquals(enabled, Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED));
            });
            step(cleanupFailure, () -> { if (probe != null) probe.restored(restorationFailure.get() == null, restorationFailure.get()); });
            if (restorationFailure.get() != null) append(cleanupFailure, restorationFailure.get());
            step(cleanupFailure, () -> { if (probe != null && scrolling) probe.submissionsClosed(actual); });
            Throwable originalBody = bodyFailure;
            step(cleanupFailure, () -> { if (probe != null) probe.exportClosed(actual, originalBody, cleanupFailure.get()); });
        }
        if (bodyFailure != null) { if (cleanupFailure.get() != null) bodyFailure.addSuppressed(cleanupFailure.get()); rethrow(bodyFailure); }
        if (cleanupFailure.get() != null) rethrow(cleanupFailure.get());
    }

    private interface Action { void run() throws Exception; }
    /** Only test statistics are redirected; capture/model/engine ownership is unchanged. */
    private static final class DailyIsolation {
        final Context context;
        final String name;
        final java.lang.reflect.Field singleton;
        final ExecutorService durationWorker;
        DailyStatsStore original, fixture;
        Map<String, String> before, after;
        boolean switched, restored, closed;
        DailyIsolation(Context context, String namespace) throws Exception {
            this.context = context; name = namespace + ".db";
            singleton = DailyStatsStore.class.getDeclaredField("instance"); singleton.setAccessible(true);
            java.lang.reflect.Field worker = DailyDurationRecorder.class.getDeclaredField("worker"); worker.setAccessible(true);
            durationWorker = (ExecutorService) worker.get(null);
        }
        void barrier() throws Exception {
            durationWorker.submit(() -> { }).get(10, TimeUnit.SECONDS); DailyStatsStore.awaitWrites();
        }
        void isolate() throws Exception {
            assertFalse(context.getDatabasePath(name).exists()); barrier();
            java.lang.reflect.Constructor<DailyStatsStore> constructor = DailyStatsStore.class.getDeclaredConstructor(Context.class, String.class);
            constructor.setAccessible(true); fixture = constructor.newInstance(context, name);
            synchronized (DailyStatsStore.class) { original = (DailyStatsStore) singleton.get(null); singleton.set(null, fixture); switched = true; }
            // Finish users that selected the original before the switch, then bind its inert bytes.
            barrier(); before = inventory();
        }
        void restore() throws Exception {
            if (!switched) return;
            AtomicReference<Throwable> failure = new AtomicReference<>();
            step(failure, this::barrier);
            step(failure, () -> { after = inventory(); assertNotNull(before); assertEquals("Original daily database unchanged", before, after); });
            // Restore the original even when a barrier or evidence comparison failed.
            step(failure, () -> {
                synchronized (DailyStatsStore.class) {
                    assertSame("No other test may replace this isolated store", fixture, singleton.get(null));
                    singleton.set(null, original); restored = true;
                }
            });
            if (restored) step(failure, () -> {
                // Finish users that selected the fixture before restoring the original.
                barrier(); fixture.close(); closed = true;
            });
            if (failure.get() != null) rethrow(failure.get());
        }
        Map<String, String> inventory() throws Exception {
            Map<String, String> values = new TreeMap<>();
            for (String suffix : Arrays.asList("", "-wal", "-shm", "-journal")) {
                File file = context.getDatabasePath("daily_statistics.db" + suffix);
                if (!file.exists()) { values.put(suffix, "ABSENT"); continue; }
                assertTrue(file.isFile()); MessageDigest digest = MessageDigest.getInstance("SHA-256");
                try (FileInputStream stream = new FileInputStream(file)) {
                    byte[] buffer = new byte[8192]; int count; while ((count = stream.read(buffer)) != -1) digest.update(buffer, 0, count);
                }
                StringBuilder text = new StringBuilder();
                for (byte item : digest.digest()) text.append(String.format(Locale.ROOT, "%02x", item & 255));
                values.put(suffix, file.length() + ":" + text);
            }
            return values;
        }
        JSONObject receipt() throws Exception {
            return new JSONObject().put("schemaVersion", 1).put("fixtureDatabase", name).put("switched", switched)
                    .put("originalBefore", before == null ? JSONObject.NULL : new JSONObject(before))
                    .put("originalAfterWhileIsolated", after == null ? JSONObject.NULL : new JSONObject(after))
                    .put("originalBytesUnchangedWhileIsolated", before != null && before.equals(after))
                    .put("originalInstanceRestored", restored).put("fixtureClosedAfterQueueBarriers", closed)
                    .put("normalApplicationHeartbeatResumed", restored);
        }
    }
    private static void step(AtomicReference<Throwable> failure, Action action) {
        try { action.run(); } catch (Exception | Error error) { append(failure, error); }
    }
    private static void append(AtomicReference<Throwable> failure, Throwable error) {
        if (!failure.compareAndSet(null, error)) failure.get().addSuppressed(error);
    }
    private static void rethrow(Throwable failure) throws Exception {
        if (failure instanceof Exception) throw (Exception) failure; if (failure instanceof Error) throw (Error) failure;
        throw new AssertionError(failure);
    }
    private static void main(Runnable action) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> { try { action.run(); } catch (Exception | Error error) { failure.set(error); } });
        if (failure.get() instanceof Error) throw (Error) failure.get();
        if (failure.get() != null) throw new IllegalStateException(failure.get());
    }
    private static void secure(Context context, UiAutomation ui, String services, String enabled) {
        assertNotNull(ui); ui.adoptShellPermissionIdentity(Manifest.permission.WRITE_SECURE_SETTINGS);
        try {
            assertTrue(Settings.Secure.putString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, services));
            assertTrue(Settings.Secure.putString(context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, enabled));
        } finally { ui.dropShellPermissionIdentity(); }
    }
    private static Map<String, ?> copy(Map<String, ?> values) {
        Map<String, Object> result = new HashMap<>();
        for (Map.Entry<String, ?> entry : values.entrySet()) result.put(entry.getKey(), entry.getValue() instanceof Set<?>
                ? new HashSet<>((Set<?>) entry.getValue()) : entry.getValue());
        return result;
    }
    @SuppressWarnings("unchecked") private static void restore(SharedPreferences preferences, Map<String, ?> saved) {
        SharedPreferences.Editor edit = preferences.edit().clear();
        for (Map.Entry<String, ?> entry : saved.entrySet()) {
            String key = entry.getKey(); Object value = entry.getValue();
            if (value instanceof String) edit.putString(key, (String) value);
            else if (value instanceof Set<?>) edit.putStringSet(key, (Set<String>) value);
            else if (value instanceof Boolean) edit.putBoolean(key, (Boolean) value);
            else if (value instanceof Integer) edit.putInt(key, (Integer) value);
            else if (value instanceof Long) edit.putLong(key, (Long) value);
            else if (value instanceof Float) edit.putFloat(key, (Float) value);
            else throw new IllegalStateException("Unsupported saved preference type");
        }
        assertTrue(edit.commit());
    }
}
