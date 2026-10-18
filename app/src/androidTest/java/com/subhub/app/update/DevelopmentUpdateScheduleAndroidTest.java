package com.subhub.app.update;

import static org.junit.Assert.*;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.provider.Settings;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.work.ListenableWorker;
import androidx.work.NetworkType;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerFactory;
import androidx.work.WorkerParameters;
import androidx.work.impl.WorkManagerImpl;
import androidx.work.testing.SynchronousExecutor;
import androidx.work.testing.WorkManagerTestInitHelper;

import com.subhub.app.R;
import com.subhub.app.censor.android.ProductCensorQualification;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.service.ScreenshotAccessibilityService;
import com.subhub.app.settings.SharedPreferenceTestRestore;

import java.io.File;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

/** Owned emulator only: real in-memory WorkManager, fake workers and no release HTTP requests. */
@SdkSuppress(minSdkVersion = 34)
@SuppressLint("RestrictedApi") // WorkManager's documented testing delegate is restored after its database closes.
public final class DevelopmentUpdateScheduleAndroidTest {
    @Rule public final TestName name = new TestName();
    private Context context;
    private SharedPreferences preferences;
    private Map<String, ?> savedPreferences;
    private UpdateStateStore state;
    private WorkManagerImpl originalManager;
    private WorkManager manager;
    private File output;
    private String previousServices;
    private int previousEnabled, previousReceiver;
    private boolean previousDom;
    private ComponentName receiver;
    private final AtomicInteger workerRuns = new AtomicInteger();
    private final AtomicInteger unexpectedWorkers = new AtomicInteger();

    @Before public void isolateUpdaterWithoutBorrowingCaptureOrNetwork() throws Exception {
        String namespace = InstrumentationRegistry.getArguments().getString("dev_update_followup_run");
        Assume.assumeTrue("Explicit owned-guest evidence namespace", namespace != null);
        assertTrue(namespace.matches("dev_updates_[a-f0-9]{32}"));
        assertEquals("true", InstrumentationRegistry.getArguments().getString("bound_censor_fixture"));
        assertTrue("Not authorized on a phone", ProductCensorQualification.supported());
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        previousServices = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        previousEnabled = Settings.Secure.getInt(context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0);
        assertFalse("No live capture owner", ScreenshotAccessibilityService.isRunning());
        assertFalse("No remembered enabled capture intent", previousEnabled != 0 && previousServices != null
                && previousServices.contains("ScreenshotAccessibilityService"));
        preferences = context.getSharedPreferences("subhub_updates", Context.MODE_PRIVATE);
        savedPreferences = new LinkedHashMap<>(preferences.getAll());
        previousDom = ControllerPinManager.isDomModeActive();
        receiver = new ComponentName(context, "androidx.work.impl.background.systemalarm.RescheduleReceiver");
        previousReceiver = context.getPackageManager().getComponentEnabledSetting(receiver);
        output = new File(context.getFilesDir(), "dev-update-followup/" + namespace + "/" + name.getMethodName());
        assertFalse(output.exists()); assertTrue(output.mkdirs());
        write("started.json", new JSONObject().put("schemaVersion", 1).put("case", name.getMethodName())
                .put("pid", android.os.Process.myPid()).put("ownedEmulator", true));
        assertTrue(preferences.edit().clear().putBoolean("automatic_checks", false).commit());
        state = new UpdateStateStore(context);
        originalManager = (WorkManagerImpl) WorkManager.getInstance(context);
        androidx.work.Configuration configuration = new androidx.work.Configuration.Builder()
                .setExecutor(new SynchronousExecutor())
                .setWorkerFactory(new WorkerFactory() {
                    @Override public ListenableWorker createWorker(Context app, String workerClassName, WorkerParameters parameters) {
                        if (!UpdateCheckWorker.class.getName().equals(workerClassName)) {
                            unexpectedWorkers.incrementAndGet(); throw new IllegalStateException("Unexpected fixture worker");
                        }
                        return new Worker(app, parameters) {
                            @Override public Result doWork() { workerRuns.incrementAndGet(); return Result.success(); }
                        };
                    }
                }).build();
        WorkManagerTestInitHelper.initializeTestWorkManager(context, configuration);
        manager = WorkManager.getInstance(context); assertNotSame(originalManager, manager);
        ControllerPinManager.enterDomMode();
    }

    @After public void restoreOriginalManagerAndExactUpdaterState() throws Exception {
        if (savedPreferences == null) return;
        Throwable failure = null;
        try {
            if (manager != null) {
                manager.cancelAllWork().getResult().get(5, TimeUnit.SECONDS);
                WorkManagerTestInitHelper.closeWorkDatabase();
            }
        } catch (Exception | Error error) { failure = error; }
        finally {
            if (originalManager != null) WorkManagerImpl.setDelegate(originalManager);
            SharedPreferenceTestRestore.restore(preferences, savedPreferences);
            if (receiver != null) context.getPackageManager().setComponentEnabledSetting(
                    receiver, previousReceiver, android.content.pm.PackageManager.DONT_KILL_APP);
            if (previousDom) ControllerPinManager.enterDomMode(); else ControllerPinManager.enterSubMode();
        }
        boolean managerRestored = originalManager == null || WorkManager.getInstance(context) == originalManager;
        boolean preferencesMatch = savedPreferences.equals(preferences.getAll());
        boolean secureMatch = java.util.Objects.equals(previousServices, Settings.Secure.getString(
                context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES))
                && previousEnabled == Settings.Secure.getInt(context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0);
        boolean receiverMatch = context.getPackageManager().getComponentEnabledSetting(receiver) == previousReceiver;
        if (output != null) write("closed.json", new JSONObject().put("schemaVersion", 1)
                .put("originalManagerRestored", managerRestored).put("preferencesMatch", preferencesMatch)
                .put("secureValuesMatch", secureMatch).put("receiverMatch", receiverMatch)
                .put("fakeWorkerRuns", workerRuns.get()).put("unexpectedWorkers", unexpectedWorkers.get())
                .put("workerFactoryUsesReleaseHttp", false).put("captureServiceRunning", ScreenshotAccessibilityService.isRunning())
                .put("cleanupFailure", failure == null ? JSONObject.NULL : failure.getClass().getName()));
        assertTrue(managerRestored); assertTrue(preferencesMatch); assertTrue(secureMatch); assertTrue(receiverMatch);
        assertFalse(ScreenshotAccessibilityService.isRunning()); assertEquals(0, unexpectedWorkers.get());
        if (failure instanceof Exception) throw (Exception) failure;
        if (failure instanceof Error) throw (Error) failure;
    }

    @Test public void channelChangesUpdateOneExistingPeriodicJobInBothDirections() throws Exception {
        state.setAutomaticChecks(true);
        UpdateScheduler.synchronize(context, false);
        WorkInfo stable = periodic(); UUID original = stable.getId();
        assertInterval(stable, TimeUnit.HOURS.toMillis(6));
        state.setDevUpdates(true);
        UpdateScheduler.synchronize(context, false);
        WorkInfo dev = periodic(); assertEquals(original, dev.getId()); assertInterval(dev, TimeUnit.MINUTES.toMillis(15));
        for (int count = 0; count < 3; count++) UpdateScheduler.synchronize(context, false);
        assertEquals(original, periodic().getId());
        state.setDevUpdates(false); UpdateScheduler.synchronize(context, false);
        assertEquals(original, periodic().getId()); assertInterval(periodic(), TimeUnit.HOURS.toMillis(6));
        assertEquals(1, manager.getWorkInfosForUniqueWork(UpdateScheduler.PERIODIC_NAME).get(5, TimeUnit.SECONDS).size());
        assertTrue(manager.getWorkInfosForUniqueWork(UpdateScheduler.IMMEDIATE_NAME).get(5, TimeUnit.SECONDS).isEmpty());
        write("result.json", new JSONObject().put("uniquePeriodicRows", 1).put("sameJobIdentity", true)
                .put("stableMillis", TimeUnit.HOURS.toMillis(6)).put("devMillis", TimeUnit.MINUTES.toMillis(15)));
    }

    @Test public void automaticOffCancelsAndReenableUsesTheSelectedChannel() throws Exception {
        state.setDevUpdates(true); state.setAutomaticChecks(true); UpdateScheduler.synchronize(context, false);
        UUID first = periodic().getId();
        state.setAutomaticChecks(false); UpdateScheduler.synchronize(context, false);
        assertEquals(WorkInfo.State.CANCELLED, manager.getWorkInfoById(first).get(5, TimeUnit.SECONDS).getState());
        UpdateScheduler.synchronize(context, false);
        assertEquals(1, manager.getWorkInfosForUniqueWork(UpdateScheduler.PERIODIC_NAME).get(5, TimeUnit.SECONDS).size());
        state.setAutomaticChecks(true); UpdateScheduler.synchronize(context, false);
        assertInterval(periodic(), TimeUnit.MINUTES.toMillis(15));
        assertNotEquals(first, periodic().getId());
        write("result.json", new JSONObject().put("disabledJobCancelled", true).put("reenabledDevMillis", TimeUnit.MINUTES.toMillis(15)));
    }

    @Test public void foregroundStaleCheckUsesDevIntervalWithoutRunningReleaseHttp() throws Exception {
        state.setAutomaticChecks(true); state.setLastCheck(System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(16));
        UpdateScheduler.synchronize(context);
        assertTrue(manager.getWorkInfosForUniqueWork(UpdateScheduler.IMMEDIATE_NAME).get(5, TimeUnit.SECONDS).isEmpty());
        state.setDevUpdates(true); state.setLastCheck(System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(16));
        UpdateScheduler.synchronize(context);
        List<WorkInfo> immediate = manager.getWorkInfosForUniqueWork(UpdateScheduler.IMMEDIATE_NAME).get(5, TimeUnit.SECONDS);
        assertEquals(1, immediate.size()); assertEquals(NetworkType.CONNECTED, immediate.get(0).getConstraints().getRequiredNetworkType());
        assertEquals(0, workerRuns.get());
        assertNotNull(WorkManagerTestInitHelper.getTestDriver(context));
        WorkManagerTestInitHelper.getTestDriver(context).setAllConstraintsMet(immediate.get(0).getId());
        awaitState(immediate.get(0).getId(), WorkInfo.State.SUCCEEDED);
        assertEquals(1, workerRuns.get());
        write("result.json", new JSONObject().put("stableSixteenMinutesStillFresh", true)
                .put("devSixteenMinutesStale", true).put("fakeWorkerRuns", 1));
    }

    @Test public void nativeHistoryShowsPreviewTimeAndPreservesStableAndLegacyDates() throws Exception {
        state.setDevUpdates(true);
        state.setAutomaticChecks(true);
        state.setReleaseHistory(Arrays.asList(
                item("0.6.5-dev.42", "2026-10-18T08:31:42Z", true),
                item("0.6.4", "2026-10-07T23:11:12Z", false),
                item("0.6.5-dev.41", "2026-10-08", true)));
        try (ActivityScenario<UpdatesActivity> scenario = ActivityScenario.launch(UpdatesActivity.class)) {
            scenario.onActivity(activity -> {
                LinearLayout rows = activity.findViewById(R.id.release_history_list); assertEquals(3, rows.getChildCount());
                Locale locale = activity.getResources().getConfiguration().getLocales().get(0);
                String timestamp = ReleaseTimestampFormatter.preview("2026-10-18T08:31:42Z", locale, ZoneId.systemDefault());
                assertEquals(activity.getString(R.string.update_history_preview_date, timestamp), meta(rows, 0).getText().toString());
                assertTrue(meta(rows, 0).getText().toString().contains("UTC"));
                assertEquals("2026-10-07", meta(rows, 1).getText().toString());
                assertEquals(activity.getString(R.string.update_history_preview_date, "2026-10-08"), meta(rows, 2).getText().toString());
                assertEquals(activity.getString(R.string.update_automatic_dev_toggle),
                        ((TextView) activity.findViewById(R.id.automatic_checks)).getText().toString());
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                LinearLayout rows = activity.findViewById(R.id.release_history_list);
                assertFits(meta(rows, 0));
                Configuration large = new Configuration(activity.getResources().getConfiguration()); large.fontScale = 1.3f;
                Context themed = new ContextThemeWrapper(activity.createConfigurationContext(large), activity.getTheme());
                View row = LayoutInflater.from(themed).inflate(R.layout.item_release_history, null, false);
                ((TextView) row.findViewById(R.id.release_version)).setText("SubHub 0.6.5-dev.152");
                TextView caption = row.findViewById(R.id.release_meta);
                caption.setText(activity.getString(R.string.update_history_preview_date,
                        ReleaseTimestampFormatter.preview("2026-10-18T08:31:42Z", Locale.GERMANY, ZoneId.of("Europe/Berlin"))));
                int width = Math.round(280 * themed.getResources().getDisplayMetrics().density);
                row.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                row.layout(0, 0, width, row.getMeasuredHeight()); assertFits(caption);
                export(row, "preview-timestamp-280dp-font130.png");
            });
            state.setDevUpdates(false); state.setAutomaticChecks(false); scenario.recreate();
            scenario.onActivity(activity -> {
                assertFalse(state.devUpdates()); assertFalse(state.automaticChecks());
                assertEquals(activity.getString(R.string.update_automatic_toggle),
                        ((TextView) activity.findViewById(R.id.automatic_checks)).getText().toString());
                assertFalse(((android.widget.CompoundButton) activity.findViewById(R.id.dev_updates)).isChecked());
                assertFalse(((android.widget.CompoundButton) activity.findViewById(R.id.automatic_checks)).isChecked());
            });
            state.setDevUpdates(true); state.setAutomaticChecks(true); scenario.recreate();
            scenario.onActivity(activity -> {
                assertTrue(state.devUpdates()); assertTrue(state.automaticChecks());
                assertEquals(activity.getString(R.string.update_automatic_dev_toggle),
                        ((TextView) activity.findViewById(R.id.automatic_checks)).getText().toString());
                assertTrue(((android.widget.CompoundButton) activity.findViewById(R.id.dev_updates)).isChecked());
                assertTrue(((android.widget.CompoundButton) activity.findViewById(R.id.automatic_checks)).isChecked());
            });
        }
        write("result.json", new JSONObject().put("localizedPreviewTime", true).put("explicitUtcOffset", true)
                .put("stableDateUnchanged", true).put("legacyDateDoesNotInventTime", true).put("narrowLargeTextFit", true)
                .put("durableChannelAndAutomaticStateSurviveRecreation", true));
    }

    private WorkInfo periodic() throws Exception {
        List<WorkInfo> all = manager.getWorkInfosForUniqueWork(UpdateScheduler.PERIODIC_NAME).get(5, TimeUnit.SECONDS);
        List<WorkInfo> active = new java.util.ArrayList<>();
        for (WorkInfo info : all) if (!info.getState().isFinished()) active.add(info);
        assertEquals("One active unique job", 1, active.size()); return active.get(0);
    }

    private void awaitState(UUID id, WorkInfo.State expected) throws Exception {
        long deadline = android.os.SystemClock.uptimeMillis() + 5000;
        WorkInfo.State observed;
        do {
            observed = manager.getWorkInfoById(id).get(5, TimeUnit.SECONDS).getState();
            if (observed == expected) return;
            Thread.sleep(10);
        } while (android.os.SystemClock.uptimeMillis() < deadline);
        assertEquals("Actual fake-worker terminal", expected, observed);
    }

    private static void assertInterval(WorkInfo info, long millis) {
        assertNotNull(info.getPeriodicityInfo()); assertEquals(millis, info.getPeriodicityInfo().getRepeatIntervalMillis());
        assertEquals(NetworkType.CONNECTED, info.getConstraints().getRequiredNetworkType());
    }

    private static ReleaseHistoryItem item(String version, String timestamp, boolean preview) {
        return new ReleaseHistoryItem(version, "v" + version, "Fixture release notes", "", timestamp, preview);
    }

    private static TextView meta(LinearLayout rows, int index) { return rows.getChildAt(index).findViewById(R.id.release_meta); }

    private static void assertFits(TextView text) {
        assertNotNull(text.getLayout()); assertTrue(text.getLayout().getLineCount() > 0);
        int width = text.getWidth() - text.getCompoundPaddingLeft() - text.getCompoundPaddingRight();
        for (int line = 0; line < text.getLayout().getLineCount(); line++) {
            assertEquals(0, text.getLayout().getEllipsisCount(line)); assertTrue(text.getLayout().getLineWidth(line) <= width + 1);
        }
        assertTrue(text.getLayout().getHeight() <= text.getHeight() - text.getCompoundPaddingTop() - text.getCompoundPaddingBottom());
    }

    private void export(View view, String filename) {
        Bitmap pixels = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        try (OutputStream stream = Files.newOutputStream(new File(output, filename).toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            view.draw(new Canvas(pixels)); assertTrue(pixels.compress(Bitmap.CompressFormat.PNG, 100, stream));
        } catch (Exception failure) { throw new AssertionError(failure); }
        finally { pixels.recycle(); }
    }

    private void write(String filename, JSONObject json) throws Exception {
        try (OutputStream stream = Files.newOutputStream(new File(output, filename).toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            stream.write(json.toString().getBytes(StandardCharsets.UTF_8));
        }
    }
}
