package com.subhub.app.censor.android;

import static org.junit.Assert.*;

import android.Manifest;
import android.app.Instrumentation;
import android.app.ActivityOptions;
import android.app.UiAutomation;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.ParcelFileDescriptor;
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
import com.subhub.app.censor.engine.NativeTextResult;
import com.subhub.app.censor.engine.NativeReadCost;
import com.subhub.app.censor.engine.ProtectionScope;
import com.subhub.app.censor.engine.SceneEngine;
import com.subhub.app.censor.engine.ScrollMotionLedger;
import com.subhub.app.detection.BBox;
import com.subhub.app.detection.Detection;
import com.subhub.app.detection.DetectionEngine;
import com.subhub.app.detection.DetectorConfig;
import com.subhub.app.detection.text.TextSmutConfig;
import com.subhub.app.detection.text.NativeTextReader;
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
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
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
    @org.junit.Rule public final org.junit.rules.TestName testName = new org.junit.rules.TestName();
    private Instrumentation instrumentation;
    private UiAutomation ui;
    private Context context;
    private ComponentName component;
    private String previousServices, previousEnabled;
    private int previousComponent;
    private boolean settingsOwned;
    private FixtureServiceConnection.Lease<BoundCensorFixtureService> boundLease;
    private File groupEvidenceDirectory;

    @Before public void bindActualNonSuppressedService() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation(); context = instrumentation.getTargetContext();
        assertEquals("Owned-emulator opt-in required", "true",
                InstrumentationRegistry.getArguments().getString("bound_censor_fixture"));
        assertTrue("Not authorized on a phone", android.os.Build.FINGERPRINT.contains("generic")
                || android.os.Build.MODEL.contains("sdk_gphone"));
        component = new ComponentName(context, BoundCensorFixtureService.class);
        String namespace = InstrumentationRegistry.getArguments().getString("native_group_namespace");
        if (namespace != null) {
            assertTrue("Exclusive owned evidence namespace", namespace.matches("[a-z][a-z0-9-]{5,63}"));
            File root = context.getExternalFilesDir("native-group-publication"); assertNotNull(root);
            File run = new File(root, namespace); assertTrue(run.isDirectory() || run.mkdirs());
            assertTrue(testName.getMethodName().matches("[A-Za-z][A-Za-z0-9_]{0,159}"));
            groupEvidenceDirectory = new File(run, testName.getMethodName());
            assertEquals(run.getCanonicalPath(), groupEvidenceDirectory.getCanonicalFile().getParent());
            assertFalse("Never reuse retained case evidence", groupEvidenceDirectory.exists());
            assertTrue(groupEvidenceDirectory.mkdir());
        }
        previousServices = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        previousEnabled = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED);
        previousComponent = context.getPackageManager().getComponentEnabledSetting(component);
        assertFalse("Legacy service must remain inactive", previousServices != null && previousServices.contains("ScreenshotAccessibilityService"));
        assertFalse("Product continuous capture is not in scope", previousServices != null && previousServices.contains("ScreenCapture"));
        assertNull("A prior callback lease must already be retired", BoundCensorFixtureService.CONNECTIONS.currentLease());
        long previousGeneration = BoundCensorFixtureService.CONNECTIONS.lastGeneration();
        ui = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        settingsOwned = true;
        context.getPackageManager().setComponentEnabledSetting(component, PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP);
        android.view.accessibility.AccessibilityManager manager = context.getSystemService(android.view.accessibility.AccessibilityManager.class);
        await("Actual installed fixture discovery before enabled-list mutation", () -> manager.getInstalledAccessibilityServiceList().stream()
                .anyMatch(info -> component.equals(new ComponentName(info.getResolveInfo().serviceInfo.packageName, info.getResolveInfo().serviceInfo.name))));
        secure(previousServices == null || previousServices.isEmpty() ? component.flattenToString()
                : previousServices + ":" + component.flattenToString(), 1);
        awaitConnectedFixture(previousGeneration);
        assertBinding();
    }
    @After public void restoreExactOwnedGuestSettings() throws Exception {
        if (!settingsOwned) return;
        FixtureServiceConnection.Lease<BoundCensorFixtureService> closing = boundLease != null
                ? boundLease : BoundCensorFixtureService.CONNECTIONS.currentLease();
        boolean originallyActive = previousComponent == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                && FixtureServiceConnection.activeSelection(previousEnabled, previousServices, component.flattenToString());
        Throwable primaryFailure = null;
        try {
            BoundCensorFixtureService service = closing == null ? null : closing.service;
            if (service != null && service.controller != null) {
                main(service.controller::close);
                await("Controller actual managed closure before settings restore", service.controller::managedResourcesDrained);
            }
            if (!originallyActive) {
                // Removing the component first can disconnect the protocol without lifecycle callbacks.
                List<FixtureServiceConnection.Lease<BoundCensorFixtureService>> targets = BoundCensorFixtureService.CONNECTIONS.closureTargets(closing);
                for (FixtureServiceConnection.Lease<BoundCensorFixtureService> target : targets) {
                    assertEquals("Cleanup remains in the owned fixture process", Process.myPid(), target.service.boundPid);
                    if (target != closing && target.service.controller != null) {
                        main(target.service.controller::close);
                        await("Replacement controller actual managed closure", target.service.controller::managedResourcesDrained);
                    }
                    if (!target.completedSuccessfully()) main(target.service::disableSelf);
                }
                if (targets.isEmpty()) awaitRetiredFixture(closing);
                else for (FixtureServiceConnection.Lease<BoundCensorFixtureService> target : targets) awaitRetiredFixture(target);
            }
        } catch (Exception | Error failure) {
            primaryFailure = failure; throw failure;
        } finally {
            try { restoreFixtureSettingsAndComponent(); }
            catch (Exception | Error restoreFailure) {
                if (primaryFailure == null) throw restoreFailure;
                if (primaryFailure != restoreFailure) primaryFailure.addSuppressed(restoreFailure);
            }
        }
        assertEquals(previousServices, Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES));
        assertEquals(previousEnabled, Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED));
        assertEquals(previousComponent, context.getPackageManager().getComponentEnabledSetting(component));
        if (!originallyActive) {
            awaitRetiredFixture(closing);
        }
        settingsOwned = false;
    }
    private void restoreFixtureSettingsAndComponent() throws Exception {
        Throwable primaryFailure = null;
        try {
            ui = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_USE_ACCESSIBILITY);
            secure(previousServices, previousEnabled);
        } catch (Exception | Error failure) {
            primaryFailure = failure; throw failure;
        } finally {
            try { context.getPackageManager().setComponentEnabledSetting(component, previousComponent, PackageManager.DONT_KILL_APP); }
            catch (Exception | Error componentFailure) {
                if (primaryFailure == null) throw componentFailure;
                if (primaryFailure != componentFailure) primaryFailure.addSuppressed(componentFailure);
            }
        }
    }
    private void secure(String services, int enabled) {
        secure(services, Integer.toString(enabled));
    }
    private void secure(String services, String enabled) {
        ui.adoptShellPermissionIdentity(Manifest.permission.WRITE_SECURE_SETTINGS);
        try {
            try { assertTrue(Settings.Secure.putString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, services)); }
            finally { assertTrue(Settings.Secure.putString(context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, enabled)); }
        } finally { ui.dropShellPermissionIdentity(); }
    }
    private void assertBinding() {
        FixtureServiceConnection.Snapshot<BoundCensorFixtureService, android.accessibilityservice.AccessibilityServiceInfo> snapshot = connectedSnapshot();
        assertNotNull("Actual registered fixture connection snapshot", snapshot);
        assertSame("A replacement connection cannot satisfy an older fixture", boundLease, snapshot.lease);
    }
    private FixtureServiceConnection.Snapshot<BoundCensorFixtureService, android.accessibilityservice.AccessibilityServiceInfo> connectedSnapshot() {
        return BoundCensorFixtureService.CONNECTIONS.ready(BoundCensorFixtureService::getServiceInfo, (service, info) -> {
            android.content.pm.ResolveInfo resolved = info.getResolveInfo();
            String enabled = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return service.boundPid == Process.myPid() && resolved != null && resolved.serviceInfo != null
                    && component.equals(new ComponentName(resolved.serviceInfo.packageName, resolved.serviceInfo.name))
                    && info.getCanRetrieveWindowContent()
                    && (info.getCapabilities() & android.accessibilityservice.AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT) != 0
                    && (info.flags & android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS) != 0
                    && FixtureServiceConnection.activeSelection(
                            Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED),
                            enabled, component.flattenToString())
                    && context.getPackageManager().getComponentEnabledSetting(component) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
        });
    }
    private void awaitConnectedFixture(long previousGeneration) throws Exception {
        await("Actual complete service connection snapshot", () -> {
            FixtureServiceConnection.Snapshot<BoundCensorFixtureService, android.accessibilityservice.AccessibilityServiceInfo> snapshot = connectedSnapshot();
            if (snapshot == null || snapshot.lease.generation <= previousGeneration) return false;
            boundLease = snapshot.lease; return true;
        });
    }
    private void awaitRetiredFixture(FixtureServiceConnection.Lease<BoundCensorFixtureService> lease) throws Exception {
        await("Actual exact-lease callback and protocol unbinding", () -> {
            if (lease != null) assertNull("Fixture lifecycle cleanup failed", lease.failure);
            return (lease == null || (lease.completedSuccessfully() && lease.service.getServiceInfo() == null))
                    && BoundCensorFixtureService.CONNECTIONS.currentLease() == null && BoundCensorFixtureService.current == null;
        });
    }

    @Test public void actualFixtureConnectionLeaseCyclesWithoutRunningCensorWork() throws Exception {
        JSONArray steps = new JSONArray(); int completed = 0; boolean passed = false;
        JSONArray observations = new JSONArray(); int cycle = -1; String phase = "START";
        Throwable primaryFailure = null;
        try {
            for (cycle = 0; cycle < 8; cycle++) {
                phase = "BINDING";
                assertBinding(); FixtureServiceConnection.Lease<BoundCensorFixtureService> lease = boundLease;
                assertNull("Lifecycle-only probe must never own a Censor controller", lease.service.controller);
                lifecycleStep(steps, cycle, "CONNECTED", lease);
                phase = "DISABLE";
                main(lease.service::disableSelf);
                phase = "AWAIT_RETIRE";
                awaitRetiredFixture(lease);
                phase = "RETIRED_RECEIPT";
                assertNull(lease.service.controller); lifecycleStep(steps, cycle, "RETIRED", lease); completed++;
                if (cycle < 7) {
                    phase = "ENABLE";
                    secure(previousServices == null || previousServices.isEmpty() ? component.flattenToString()
                            : previousServices + ":" + component.flattenToString(), 1);
                    phase = "AWAIT_CONNECT";
                    awaitConnectedFixture(lease.generation);
                }
            }
            passed = true;
        } catch (Exception | Error failure) {
            primaryFailure = failure;
            try { observations.put(lifecycleFailureObservation(cycle, phase, failure)); }
            catch (Exception | Error diagnosticFailure) { failure.addSuppressed(diagnosticFailure); }
            throw failure;
        } finally {
            try {
                JSONObject receipt = new JSONObject().put("schemaVersion", 3).put("requestedCycles", 8).put("completedCycles", completed)
                        .put("pid", Process.myPid()).put("controllerWorkRun", false).put("nativeBodyPassed", passed)
                        .put("rawStepCount", steps.length()).put("steps", steps).put("pixelPerformanceAcceptance", false)
                        .put("rawFailureObservationCount", observations.length()).put("failureObservations", observations)
                        .put("disableTrigger", "SERVICE_DISABLE_SELF").put("enableTrigger", "SECURE_SELECTION_ONLY")
                        .put("componentInstallationRequired", true);
                try (FileOutputStream stream = new FileOutputStream(new File(directory(), "native-fixture-lifecycle-" + System.nanoTime() + ".json"))) {
                    stream.write(receipt.toString().getBytes(StandardCharsets.UTF_8));
                }
            } catch (Exception | Error exportFailure) {
                if (primaryFailure == null) throw exportFailure;
                primaryFailure.addSuppressed(exportFailure);
            }
        }
    }
    private JSONObject lifecycleFailureObservation(int cycle, String phase, Throwable failure) throws Exception {
        long startedAt = SystemClock.uptimeMillis();
        JSONObject before = lifecycleMemoryObservation();
        CountDownLatch heartbeat = new CountDownLatch(1); AtomicLong acknowledgedAt = new AtomicLong(-1);
        AtomicLong acknowledgedSequence = new AtomicLong(); long postedAt = SystemClock.uptimeMillis();
        boolean posted = new Handler(Looper.getMainLooper()).post(() -> {
            acknowledgedAt.set(SystemClock.uptimeMillis()); acknowledgedSequence.set(1); heartbeat.countDown();
        });
        if (posted) heartbeat.await(250, TimeUnit.MILLISECONDS);
        long sequence = acknowledgedSequence.get(); long ackAt = sequence == 1 ? acknowledgedAt.get() : -1;
        boolean responded = sequence == 1 && ackAt >= postedAt && ackAt - postedAt <= 250;
        JSONObject mainProgress = new JSONObject().put("postedAtMillis", postedAt).put("postAccepted", posted)
                .put("postedSequence", 1).put("responseWithinPostDeadline", responded).put("maximumWaitMillis", 250)
                .put("acknowledgedSequence", sequence).put("acknowledgedAtMillis", ackAt)
                .put("observedAtMillis", SystemClock.uptimeMillis());
        JSONArray queries = new JSONArray();
        queries.put(diagnosticQuery("SERVICE_INFO", () -> boundLease.service.getServiceInfo() != null));
        queries.put(diagnosticQuery("SETTINGS", () -> new JSONObject()
                .put("services", nullable(Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)))
                .put("enabled", nullable(Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED)))
                .put("componentState", context.getPackageManager().getComponentEnabledSetting(component))));
        queries.put(diagnosticQuery("ACCESSIBILITY_DUMP", () -> {
            try (ParcelFileDescriptor descriptor = ui.executeShellCommand("dumpsys accessibility");
                    InputStream stream = new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int count;
                while ((count = stream.read(buffer)) != -1) {
                    if (bytes.size() + count > 262144) throw new IllegalStateException("Diagnostic dump size bound");
                    bytes.write(buffer, 0, count);
                }
                return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
            }
        }));
        JSONObject after = lifecycleMemoryObservation();
        return new JSONObject().put("cycle", cycle).put("phase", phase).put("failureType", failure.getClass().getName())
                .put("startedAtMillis", startedAt).put("finishedAtMillis", SystemClock.uptimeMillis())
                .put("beforeTeardown", true).put("atomicFrameworkSnapshot", false).put("clockDomain", "UPTIME_MILLIS")
                .put("before", before).put("after", after).put("heartbeat", mainProgress)
                .put("rawQueryCount", queries.length()).put("queries", queries);
    }
    private JSONObject lifecycleMemoryObservation() throws Exception {
        JSONObject memory = new JSONObject(BoundCensorFixtureService.CONNECTIONS.inspect(boundLease));
        BoundCensorFixtureService published = BoundCensorFixtureService.current;
        return memory.put("publishedPresent", published != null).put("publishedIsOwn", published == boundLease.service)
                .put("publishedGeneration", published == null || published.connection == null ? -1 : published.connection.generation)
                .put("controllerPresent", boundLease.service.controller != null).put("observedAtMillis", SystemClock.uptimeMillis());
    }
    private JSONObject diagnosticQuery(String name, java.util.concurrent.Callable<Object> query) throws Exception {
        FixtureDiagnosticQuery.Result<Object> result = FixtureDiagnosticQuery.read(name, 1000, SystemClock::uptimeMillis, query);
        return new JSONObject().put("name", name).put("status", result.status).put("maximumMillis", result.maximumMillis)
                .put("startedAtMillis", result.startedAtMillis).put("returnedAtMillis", result.returnedAtMillis)
                .put("responseObservedAtMillis", result.responseObservedAtMillis).put("snapshotAtMillis", result.snapshotAtMillis)
                .put("workerFinished", result.workerFinished).put("value", nullable(result.value)).put("failureType", nullable(result.failureType));
    }
    private static Object nullable(Object value) { return value == null ? JSONObject.NULL : value; }

    private JSONObject windowFailureObservation(Harness harness, int waitOrdinal, Throwable failure) throws Exception {
        long startedAt = SystemClock.uptimeMillis();
        JSONObject before = new JSONObject(BoundCensorFixtureService.CONNECTIONS.inspect(harness.lease))
                .put("observedAtMillis", SystemClock.uptimeMillis());
        CountDownLatch heartbeat = new CountDownLatch(1); AtomicLong acknowledgedAt = new AtomicLong(-1);
        long postedAt = SystemClock.uptimeMillis();
        boolean posted = new Handler(Looper.getMainLooper()).post(() -> {
            acknowledgedAt.set(SystemClock.uptimeMillis()); heartbeat.countDown();
        });
        if (posted) heartbeat.await(250, TimeUnit.MILLISECONDS);
        long ackAt = acknowledgedAt.get(), observedAt = SystemClock.uptimeMillis();
        JSONObject mainProgress = new JSONObject().put("postedAtMillis", postedAt).put("postAccepted", posted)
                .put("acknowledgedAtMillis", ackAt).put("observedAtMillis", observedAt).put("maximumWaitMillis", 250)
                .put("responseWithinPostDeadline", ackAt >= postedAt && ackAt - postedAt <= 250);
        JSONArray queries = new JSONArray();
        queries.put(diagnosticQuery("SERVICE_INFO", () -> harness.service.getServiceInfo() != null));
        queries.put(diagnosticQuery("SETTINGS", () -> new JSONObject()
                .put("services", nullable(Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)))
                .put("enabled", nullable(Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED)))
                .put("componentState", context.getPackageManager().getComponentEnabledSetting(component))));
        queries.put(diagnosticQuery("ACCESSIBILITY_WINDOWS", () -> windowInventory(harness.service)));
        AtomicLong activityStartedAt = new AtomicLong(-1), activityFinishedAt = new AtomicLong(-1);
        AtomicBoolean activityPosted = new AtomicBoolean(), activityCancellationAttempted = new AtomicBoolean();
        queries.put(diagnosticQuery("ACTIVITY_GEOMETRY", () -> {
            java.util.concurrent.FutureTask<Object> task = new java.util.concurrent.FutureTask<>(() -> {
                activityStartedAt.set(SystemClock.uptimeMillis());
                try {
                    JSONArray activities = new JSONArray();
                    for (android.app.Activity activity : androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                            .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)) {
                        if (activities.length() >= 8) throw new IllegalStateException("Activity diagnostic inventory bound");
                        View decor = activity.getWindow().getDecorView(), content = activity.findViewById(android.R.id.content);
                        int[] decorAt = new int[2], contentAt = new int[2]; decor.getLocationOnScreen(decorAt);
                        if (content != null) content.getLocationOnScreen(contentAt);
                        android.view.WindowInsets insets = decor.getRootWindowInsets();
                        android.graphics.Insets bars = insets == null ? null : insets.getInsets(android.view.WindowInsets.Type.systemBars());
                        android.view.WindowManager.LayoutParams attributes = activity.getWindow().getAttributes();
                        activities.put(new JSONObject().put("className", activity.getClass().getName())
                                .put("packageName", activity.getPackageName()).put("hasWindowFocus", decor.hasWindowFocus())
                                .put("decorLeft", decorAt[0]).put("decorTop", decorAt[1]).put("decorWidth", decor.getWidth()).put("decorHeight", decor.getHeight())
                                .put("contentPresent", content != null).put("contentLeft", content == null ? -1 : contentAt[0])
                                .put("contentTop", content == null ? -1 : contentAt[1]).put("contentWidth", content == null ? -1 : content.getWidth())
                                .put("contentHeight", content == null ? -1 : content.getHeight()).put("flags", attributes.flags)
                                .put("requestedWidth", attributes.width).put("requestedHeight", attributes.height)
                                .put("systemBars", bars == null ? JSONObject.NULL : new JSONObject().put("left", bars.left).put("top", bars.top)
                                        .put("right", bars.right).put("bottom", bars.bottom)).put("observedAtMillis", SystemClock.uptimeMillis()));
                    }
                    return new JSONObject().put("rawActivityCount", activities.length()).put("activities", activities);
                } finally { activityFinishedAt.set(SystemClock.uptimeMillis()); }
            });
            activityPosted.set(new Handler(Looper.getMainLooper()).post(task));
            if (!activityPosted.get()) throw new IllegalStateException("Activity diagnostic main post rejected");
            try { return task.get(750, TimeUnit.MILLISECONDS); }
            finally { activityCancellationAttempted.set(true); task.cancel(false); }
        }));
        long activityFinished = activityFinishedAt.get(), activityStarted = activityStartedAt.get();
        JSONObject activityTask = new JSONObject().put("postAccepted", activityPosted.get()).put("startedAtMillis", activityStarted)
                .put("finishedAtMillis", activityFinished).put("completionObserved", activityFinished >= 0)
                .put("cancellationAttempted", activityCancellationAttempted.get()).put("observedAtMillis", SystemClock.uptimeMillis());
        return new JSONObject().put("waitOrdinal", waitOrdinal).put("leaseGeneration", harness.lease.generation)
                .put("phase", "WINDOW_READINESS").put("failureType", failure.getClass().getName())
                .put("startedAtMillis", startedAt).put("finishedAtMillis", SystemClock.uptimeMillis())
                .put("beforeTeardown", true).put("atomicFrameworkSnapshot", false)
                .put("before", before).put("after", new JSONObject(BoundCensorFixtureService.CONNECTIONS.inspect(harness.lease))
                        .put("observedAtMillis", SystemClock.uptimeMillis()))
                .put("heartbeat", mainProgress).put("activityTask", activityTask).put("rawQueryCount", queries.length()).put("queries", queries);
    }

    private static JSONObject windowInventory(BoundCensorFixtureService service) throws Exception {
        List<android.view.accessibility.AccessibilityWindowInfo> windows = service.getWindows();
        JSONArray rows = new JSONArray(); int rootsObtained = 0, rootsRecycled = 0, windowsRecycled = 0;
        Throwable primary = null;
        if (windows == null) return new JSONObject().put("listPresent", false).put("rawWindowCount", 0)
                .put("storedWindowCount", 0).put("truncated", false).put("windows", rows)
                .put("rootsObtained", 0).put("rootsRecycled", 0).put("windowsRecycled", 0);
        try {
            for (int index = 0; index < Math.min(windows.size(), AccessibilityWindowReader.MAXIMUM_WINDOWS); index++) {
                android.view.accessibility.AccessibilityWindowInfo window = windows.get(index);
                if (window == null) throw new IllegalStateException("Incomplete diagnostic window inventory");
                android.graphics.Rect bounds = new android.graphics.Rect(); window.getBoundsInScreen(bounds);
                android.view.accessibility.AccessibilityNodeInfo root = window.getRoot();
                if (root != null) rootsObtained++;
                Throwable rootFailure = null;
                try {
                    CharSequence name = root == null ? null : root.getPackageName();
                    rows.put(new JSONObject().put("index", index).put("id", window.getId()).put("type", window.getType())
                            .put("active", window.isActive()).put("focused", window.isFocused())
                            .put("left", bounds.left).put("top", bounds.top).put("right", bounds.right).put("bottom", bounds.bottom)
                            .put("rootPresent", root != null).put("rootWindowId", root == null ? -1 : root.getWindowId())
                            .put("rootPackage", nullable(name == null ? null : name.toString())));
                } catch (Exception | Error failure) { rootFailure = failure; throw failure; }
                finally {
                    if (root != null) try { root.recycle(); rootsRecycled++; }
                    catch (RuntimeException | Error cleanup) {
                        if (rootFailure == null) throw cleanup;
                        if (rootFailure != cleanup) rootFailure.addSuppressed(cleanup);
                    }
                }
            }
        } catch (Exception | Error failure) { primary = failure; throw failure; }
        finally {
            Throwable cleanupFailure = null;
            for (android.view.accessibility.AccessibilityWindowInfo window : windows) if (window != null) try { window.recycle(); windowsRecycled++; }
            catch (RuntimeException | Error cleanup) {
                if (cleanupFailure == null) cleanupFailure = cleanup;
                else if (cleanupFailure != cleanup) cleanupFailure.addSuppressed(cleanup);
            }
            if (cleanupFailure != null) {
                if (primary != null) { if (primary != cleanupFailure) primary.addSuppressed(cleanupFailure); }
                else if (cleanupFailure instanceof Error) throw (Error) cleanupFailure;
                else throw (RuntimeException) cleanupFailure;
            }
        }
        return new JSONObject().put("listPresent", true).put("rawWindowCount", windows.size()).put("storedWindowCount", rows.length())
                .put("truncated", windows.size() > rows.length()).put("windows", rows)
                .put("rootsObtained", rootsObtained).put("rootsRecycled", rootsRecycled).put("windowsRecycled", windowsRecycled);
    }
    private void lifecycleStep(JSONArray steps, int cycle, String phase,
            FixtureServiceConnection.Lease<BoundCensorFixtureService> lease) throws Exception {
        int componentState = context.getPackageManager().getComponentEnabledSetting(component);
        assertEquals("Orderly lifecycle keeps the component installed", PackageManager.COMPONENT_ENABLED_STATE_ENABLED, componentState);
        steps.put(new JSONObject().put("cycle", cycle).put("phase", phase).put("generation", lease.generation)
                .put("atMillis", SystemClock.uptimeMillis()).put("boundPid", lease.service.boundPid)
                .put("infoPresent", lease.service.getServiceInfo() != null)
                .put("exactCurrent", BoundCensorFixtureService.CONNECTIONS.isCurrent(lease))
                .put("retired", lease.retired).put("completedCallbacks", lease.completedCallbacks)
                .put("callbackFailed", lease.failure != null).put("controllerPresent", lease.service.controller != null)
                .put("componentState", componentState));
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

    @Test public void actualScrollCaptureAndSurfaceCollectSameFrameWitnessAlignmentWithControlledColorModel() throws Exception {
        try (ActivityScenario<BoundCensorFixtureActivity> activity = ActivityScenario.launch(BoundCensorFixtureActivity.class)) {
            AtomicReference<ScrollWitnessFeed> feed = new AtomicReference<>();
            AtomicReference<ScrollWitnessRun> run = new AtomicReference<>();
            activity.onActivity(actual -> {
                android.widget.ScrollView scroll = new android.widget.ScrollView(actual);
                scroll.setId(android.R.id.list); scroll.setOverScrollMode(View.OVER_SCROLL_NEVER); scroll.setVerticalScrollBarEnabled(false);
                ScrollWitnessFeed source = new ScrollWitnessFeed(actual); feed.set(source);
                scroll.addView(source, new android.widget.ScrollView.LayoutParams(-1, source.contentHeight));
                actual.setContentView(scroll); run.set(new ScrollWitnessRun(scroll));
            });
            prepareScrollWitness(activity, feed.get());
            main(() -> {
                run.get().view.scrollTo(0, 1000);
                run.get().view.getViewTreeObserver().registerFrameCommitCallback(feed.get().commits::incrementAndGet);
                run.get().view.invalidate();
            });
            await("New hardware frame at initial scroll position", () -> feed.get().commits.get() >= 2);
            Harness h = new Harness("bound-scroll-witness", false, () -> { }); JSONArray samples = new JSONArray();
            Throwable primary = null;
            try (Harness owned = h) {
                h.markerPixels.set(true); h.start(h.window(), 10, config(2, true, .3f), null, false); h.awaitVisual(10);
                try {
                    await("Stationary positive source and composed mask baseline", () -> {
                        try {
                            JSONObject sample = witnessSample(feed.get(), "baseline", samples.length() + 1);
                            samples.put(sample);
                            return sample.getInt("fullWitnesses") >= 2 && sample.getInt("missingCenters") == 0;
                        } catch (Exception failure) { throw new IllegalStateException(failure); }
                    });
                    main(run.get()::start);
                    long deadline = SystemClock.uptimeMillis() + 10000;
                    while (!run.get().completed && SystemClock.uptimeMillis() < deadline) {
                        long started = SystemClock.uptimeMillis();
                        samples.put(witnessSample(feed.get(), run.get().phase(started), samples.length() + 1));
                        Thread.sleep(Math.max(1, 90 - (SystemClock.uptimeMillis() - started)));
                    }
                    assertTrue("The real-time source path completed, not a controlled presentation clock", run.get().completed);
                    samples.put(witnessSample(feed.get(), "after", samples.length() + 1));
                    assertTrue("Natural framework scroll callbacks", h.service.scrollCallbacks.get() > 8);
                    assertTrue("Actual scoped provider receipts", h.scrollReceipts.stream().anyMatch(r -> r.sample != null && r.sample.owner > 0
                            && (r.admission == ScrollMotionLedger.Admission.RECORDED || r.admission == ScrollMotionLedger.Admission.UNKNOWN)));
                    assertTrue("Repeated real platform acquisitions", h.captureMetadata.size() >= 8);
                    assertTrue("Actual worker Surface submissions", h.posts.size() >= 8);
                    assertSpacing(h.traces); assertBinding();
                } finally { main(run.get()::stop); }
            } catch (Exception | Error failure) { primary = failure; throw failure; }
            finally {
                try { saveWitnessReport(h, run.get(), samples); }
                catch (Exception | Error failure) { if (primary != null) primary.addSuppressed(failure); else throw failure; }
            }
        }
    }

    private void prepareScrollWitness(ActivityScenario<BoundCensorFixtureActivity> activity, ScrollWitnessFeed feed) throws Exception {
        CountDownLatch committed = new CountDownLatch(1); AtomicBoolean registered = new AtomicBoolean();
        AtomicReference<android.view.ViewTreeObserver.OnDrawListener> listener = new AtomicReference<>();
        activity.onActivity(value -> {
            View decor = value.getWindow().getDecorView();
            android.view.ViewTreeObserver.OnDrawListener draw = () -> {
                if (feed.isAttachedToWindow() && feed.isLaidOut() && feed.getWidth() > 0 && feed.getHeight() == feed.contentHeight
                        && registered.compareAndSet(false, true)) {
                    decor.getViewTreeObserver().registerFrameCommitCallback(() -> { feed.commits.incrementAndGet(); committed.countDown(); });
                    decor.postInvalidateOnAnimation();
                }
            };
            listener.set(draw); decor.getViewTreeObserver().addOnDrawListener(draw); decor.invalidate();
        });
        Throwable primary = null;
        try { assertTrue("Actual attached fixture layout and a new hardware-frame commit", committed.await(8, TimeUnit.SECONDS)); }
        catch (Exception | Error failure) { primary = failure; throw failure; }
        finally {
            try {
                JSONObject observation = new JSONObject().put("schemaVersion", 1).put("kind", "scroll-witness-setup")
                        .put("registered", registered.get()).put("commits", feed.commits.get()).put("offsetX", feed.offsetX)
                        .put("physicalPixelClockKnown", false).put("outcome", primary == null ? "SUCCESS" : "FAILURE");
                activity.onActivity(value -> {
                    View decor = value.getWindow().getDecorView();
                    try { observation.put("attached", feed.isAttachedToWindow()).put("laidOut", feed.isLaidOut()).put("shown", feed.isShown())
                            .put("width", feed.getWidth()).put("height", feed.getHeight()).put("hardware", decor.isHardwareAccelerated())
                            .put("windowFocus", decor.hasWindowFocus()).put("windowVisibility", decor.getWindowVisibility())
                            .put("interactive", context.getSystemService(android.os.PowerManager.class).isInteractive())
                            .put("keyguardLocked", context.getSystemService(android.app.KeyguardManager.class).isKeyguardLocked()); }
                    catch (Exception failure) { throw new IllegalStateException(failure); }
                    decor.getViewTreeObserver().removeOnDrawListener(listener.get());
                });
                if (primary != null) {
                    Bitmap pixels = ui.takeScreenshot();
                    if (pixels != null) try {
                        String name = "native-mask-bound-scroll-setup-failed-" + System.nanoTime() + ".png";
                        try (FileOutputStream stream = new FileOutputStream(new File(directory(), name))) { assertTrue(pixels.compress(Bitmap.CompressFormat.PNG, 100, stream)); }
                        observation.put("failureImage", name);
                    } finally { pixels.recycle(); }
                }
                try (FileOutputStream stream = new FileOutputStream(new File(directory(), "native-window-readiness-" + System.nanoTime() + ".json"))) {
                    stream.write(observation.toString().getBytes(StandardCharsets.UTF_8));
                }
            } catch (Exception | Error failure) { if (primary != null) primary.addSuppressed(failure); else throw failure; }
        }
        assertTrue(feed.offsetX > 0 && feed.commits.get() > 0 && feed.getHeight() == feed.contentHeight);
    }

    /** Both twins are recorded in the SAME Canvas node; no host/source timestamp supplies truth. */
    private static final class ScrollWitnessFeed extends View {
        static final int MARKER_HEIGHT = 180;
        final int contentHeight;
        final android.graphics.Paint paint = new android.graphics.Paint(); final AtomicInteger commits = new AtomicInteger();
        volatile int offsetX;
        ScrollWitnessFeed(Context context) {
            super(context); contentHeight = context.getResources().getDisplayMetrics().heightPixels * 5;
            // ScrollView measures its content with UNSPECIFIED height; a plain View otherwise desires zero.
            setMinimumHeight(contentHeight); setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        @Override protected void onDraw(android.graphics.Canvas canvas) {
            canvas.drawColor(BoundCensorFixtureActivity.UNDERLYING); offsetX = getWidth() / 3;
            int left = getWidth() / 6, width = getWidth() / 7;
            for (int top = 200; top + MARKER_HEIGHT < getHeight(); top += 480) {
                paint.setColor(ScrollWitnessPixels.WITNESS); canvas.drawRect(left, top, left + width, top + MARKER_HEIGHT, paint);
                paint.setColor(ScrollWitnessPixels.TARGET); canvas.drawRect(left + offsetX, top, left + offsetX + width, top + MARKER_HEIGHT, paint);
            }
        }
    }
    private static final class ScrollWitnessRun implements android.view.Choreographer.FrameCallback {
        final android.widget.ScrollView view;
        volatile long startedAt = -1; volatile boolean completed, stopped; volatile int frames;
        ScrollWitnessRun(android.widget.ScrollView view) { this.view = view; }
        void start() { startedAt = SystemClock.uptimeMillis(); android.view.Choreographer.getInstance().postFrameCallback(this); }
        String phase(long now) {
            long age = now - startedAt;
            return age < 3000 ? "forward" : age < 4000 ? "pause" : age < 7000 ? "reverse" : "settle";
        }
        @Override public void doFrame(long frameTimeNanos) {
            if (stopped) return;
            long age = Math.max(0, SystemClock.uptimeMillis() - startedAt); frames++;
            int y = age < 3000 ? 1000 + (int) age * 2 : age < 4000 ? 7000
                    : age < 7000 ? 7000 - (int) (age - 4000) * 2 : 1000;
            view.scrollTo(0, y);
            if (age >= 8000) completed = true;
            else android.view.Choreographer.getInstance().postFrameCallback(this);
        }
        void stop() { stopped = true; android.view.Choreographer.getInstance().removeFrameCallback(this); }
    }
    private JSONObject witnessSample(ScrollWitnessFeed feed, String phase, int ordinal) throws Exception {
        if (ordinal > 256) throw new IllegalStateException("Bounded witness sample inventory");
        long started = SystemClock.uptimeMillis(); Bitmap pixels = ui.takeScreenshot(); long returned = SystemClock.uptimeMillis();
        assertNotNull("Actual composed screenshot", pixels);
        String name = "native-mask-bound-scroll-" + ordinal + "-" + System.nanoTime() + ".png";
        try {
            try (FileOutputStream out = new FileOutputStream(new File(directory(), name))) { assertTrue(pixels.compress(Bitmap.CompressFormat.PNG, 100, out)); }
            int width = pixels.getWidth(), height = pixels.getHeight(); int[] colors = new int[width * height];
            pixels.getPixels(colors, 0, width, 0, 0, width, height);
            List<ScrollWitnessPixels.Box> witnesses = ScrollWitnessPixels.bands(colors, width, height, ScrollWitnessPixels.WITNESS);
            List<ScrollWitnessPixels.Box> masks = ScrollWitnessPixels.bands(colors, width, height, ScrollWitnessPixels.COVER);
            ScrollWitnessPixels.Sample value = ScrollWitnessPixels.measure(witnesses, masks, feed.offsetX, ScrollWitnessFeed.MARKER_HEIGHT);
            return new JSONObject().put("ordinal", ordinal).put("phaseAtSampling", phase).put("imageFile", name)
                    .put("acquisitionStartedMillis", started).put("acquisitionReturnedMillis", returned).put("width", width).put("height", height)
                    .put("offsetX", feed.offsetX).put("markerHeight", ScrollWitnessFeed.MARKER_HEIGHT)
                    .put("rawWitnessBandCount", witnesses.size()).put("witnessBands", witnessBoxes(witnesses))
                    .put("rawMaskBandCount", masks.size()).put("maskBands", witnessBoxes(masks))
                    .put("fullWitnesses", value.witnesses).put("paired", value.paired).put("missingCenters", value.missingCenters)
                    .put("unpairedMaskBands", value.unpairedMaskBands).put("tallMaskBands", value.tallMaskBands)
                    .put("absoluteCenterErrorSum", value.paired == 0 ? JSONObject.NULL : value.absoluteCenterErrorSum)
                    .put("maximumCenterError", value.paired == 0 ? JSONObject.NULL : value.maximumCenterError);
        } finally { pixels.recycle(); }
    }
    private static JSONArray witnessBoxes(List<ScrollWitnessPixels.Box> boxes) throws Exception {
        JSONArray rows = new JSONArray();
        for (ScrollWitnessPixels.Box box : boxes) rows.put(new JSONArray().put(box.left).put(box.top).put(box.right).put(box.bottom));
        return rows;
    }
    private void saveWitnessReport(Harness h, ScrollWitnessRun run, JSONArray samples) throws Exception {
        JSONArray receipts = new JSONArray(), captures = new JSONArray(new java.util.ArrayList<>(h.captureMetadata));
        for (ScrollMotionLedger.Receipt receipt : new java.util.ArrayList<>(h.scrollReceipts)) {
            ScrollMotionLedger.Sample sample = receipt.sample;
            receipts.put(new JSONObject().put("admission", receipt.admission.name()).put("refusal", receipt.refusal.name())
                    .put("admittedAtMillis", receipt.admittedAtMillis).put("generation", receipt.generation)
                    .put("epoch", sample.scope.epoch).put("geometry", sample.scope.geometry).put("window", sample.scope.windowId)
                    .put("owner", sample.owner).put("producer", sample.producer).put("delivery", sample.deliveryIdentity)
                    .put("sourceAtMillis", sample.sourceAtMillis).put("receivedAtMillis", sample.receivedAtMillis)
                    .put("readStartedAtMillis", sample.readStartedAtMillis).put("readFinishedAtMillis", sample.readFinishedAtMillis)
                    .put("yAbsolute", sample.y.absoluteKnown ? sample.y.absolute : JSONObject.NULL)
                    .put("yDelta", sample.y.deltaKnown ? sample.y.delta : JSONObject.NULL));
        }
        JSONArray traces = new JSONArray(), posts = new JSONArray();
        for (CensorEngine.Trace trace : new java.util.ArrayList<>(h.traces)) traces.put(new JSONObject()
                .put("stage", trace.stage.name()).put("epoch", trace.epoch).put("source", trace.source)
                .put("lane", trace.lane == null ? JSONObject.NULL : trace.lane.name()).put("atMillis", trace.atMillis)
                .put("queueMillis", trace.queueMillis).put("pixelAgeMillis", trace.pixelAgeMillis)
                .put("work", trace.work == null ? JSONObject.NULL : new JSONObject().put("preprocessMillis", trace.work.preprocessMillis)
                        .put("runtimeMillis", trace.work.runtimeMillis).put("postprocessMillis", trace.work.postprocessMillis)));
        for (SnapshotCensorSurface.Post post : new java.util.ArrayList<>(h.posts)) posts.put(new JSONObject()
                .put("serial", post.postSerial).put("recordedAtMillis", post.recordedAtMillis).put("returnedAtMillis", post.postReturnedAtMillis)
                .put("admittedAtMillis", post.uiAdmittedAtMillis).put("accepted", post.acceptedPaint != null).put("mainThread", post.mainThread));
        JSONObject report = new JSONObject().put("schemaVersion", 1).put("kind", "bound-scroll-witness")
                .put("actualService", true).put("deterministicColorModel", true).put("alignmentAcceptance", false)
                .put("physicalAcceptance", false).put("pixelClockKnown", false).put("semanticIdentityKnown", false)
                .put("motionDurationMillis", 8000).put("startedAtMillis", run.startedAt).put("motionCompleted", run.completed)
                .put("frameCallbacks", run.frames).put("frameworkScrollCallbacks", h.service.scrollCallbacks.get())
                .put("managedDrain", h.controller.managedResourcesDrained()).put("rawSampleCount", samples.length()).put("samples", samples)
                .put("rawScrollReceiptCount", receipts.length()).put("scrollReceipts", receipts)
                .put("rawCaptureMetadataCount", captures.length()).put("captureMetadata", captures)
                .put("rawTraceCount", traces.length()).put("traces", traces).put("rawPostCount", posts.length()).put("posts", posts);
        try (FileOutputStream stream = new FileOutputStream(new File(directory(), "native-scroll-witness-" + System.nanoTime() + ".json"))) {
            stream.write(report.toString().getBytes(StandardCharsets.UTF_8));
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

    @Test public void actualAdmittedNodeReclassificationUsesFreshPartialFactsWithoutTreeTraversal() throws Exception {
        admittedNodeBoundary(DirectChange.NONE);
    }
    @Test public void softLiteralMutationAtDirectFinalCheckCannotCreateFreshNativeEvidence() throws Exception {
        admittedNodeBoundary(DirectChange.LITERAL);
    }
    @Test public void stopAtDirectFinalCheckCannotAdmitNativeFacts() throws Exception {
        admittedNodeBoundary(DirectChange.STOP);
    }
    @Test public void closeAtDirectFinalCheckWaitsForActualAddressDisposal() throws Exception {
        admittedNodeBoundary(DirectChange.CLOSE);
    }
    @Test public void actualWindowResizeAtDirectFinalCheckRejectsRootFreeScope() throws Exception {
        admittedNodeBoundary(DirectChange.RESIZE);
    }
    @Test public void heldDirectReadCannotKeepPriorEvidenceAlivePastItsOriginalDeadline() throws Exception {
        admittedNodeBoundary(DirectChange.EXPIRE);
    }
    private enum DirectChange { NONE, LITERAL, STOP, CLOSE, RESIZE, EXPIRE }
    private void admittedNodeBoundary(DirectChange change) throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<CensorEngine.NativeTextInput> heldInput = new AtomicReference<>();
        try (Harness h = new Harness("native-direct-" + change.name().toLowerCase(java.util.Locale.ROOT), false,
                () -> { }, () -> { }, (input, boundary) -> {
            assertNotEquals(Looper.getMainLooper(), Looper.myLooper());
            if (input.readMode() == NativeTextResult.ReadMode.ADMITTED_NODE_RECLASSIFY
                    && boundary == NativeTextReader.Boundary.BEFORE_FINAL_NODE_REFRESH
                    && heldInput.compareAndSet(null, input)) { entered.countDown(); hold(release); }
        }); ActivityScenario<BoundCensorFixtureActivity> activity = ActivityScenario.launch(BoundCensorFixtureActivity.class)) {
            AccessibilityWindowReader.Window original = h.window();
            h.start(original, 10, config(2, false, .3f), text(), false);
            try {
                await("Real full discovery accepted before address selection", () -> h.nativeFrames.stream()
                        .anyMatch(f -> f.read.coverage == NativeTextResult.Coverage.COMPLETE_TREE && !f.read.candidates.isEmpty()));
                NativeTextFrame baseline = h.nativeFrames.stream().filter(f -> f.read.coverage == NativeTextResult.Coverage.COMPLETE_TREE
                        && !f.read.candidates.isEmpty()).findFirst().get();
                java.util.Set<String> baselineKeys = Collections.singleton(baseline.read.candidates.get(0).nodeKey);
                java.util.Map<String, Long> beforeGroups = change == DirectChange.EXPIRE
                        ? nativeGroups(h, baselineKeys, baseline.read.readStartedAtMillis) : null;
                if (change == DirectChange.EXPIRE) {
                    SceneEngine.Region region = baseline.read.candidates.get(0).regions.get(0);
                    composed("direct-expire-baseline", (int) ((region.left + region.right) / 2),
                            (int) ((region.top + region.bottom) / 2), COVER);
                }
                // This adverse fixture explicitly owns soft revocation; do not let an automatic
                // hard content event replace the final literal/window check being exercised.
                main(() -> { h.service.controller = null; h.controller.contentChanged(original.id, false); });
                assertTrue("Actual admitted-node final boundary was not held", entered.await(8, TimeUnit.SECONDS));
                CensorEngine.NativeTextInput input = heldInput.get(); assertNotNull(input); assertFalse(input.cancelled());
                assertEquals(NativeTextResult.ReadMode.ADMITTED_NODE_RECLASSIFY, input.readMode());
                if (change == DirectChange.EXPIRE) {
                    NativeTextFrame previous = h.nativeFrames.get(h.nativeFrames.size() - 1);
                    long originalDeadline = previous.read.candidates.get(0).validatedAtMillis + previous.maximumAgeMillis;
                    int acceptedBeforeHold = h.nativeFrames.size();
                    assertTrue("Controlled hold must begin before the old evidence expires", SystemClock.uptimeMillis() <= originalDeadline);
                    await("Held direct read cannot extend original scene evidence", () -> {
                        if (SystemClock.uptimeMillis() <= originalDeadline) return false;
                        try (CensorEngine<PixelFrame>.RenderView view = h.controller.host().acquireRenderView()) {
                            return view != null && view.snapshot.groups.isEmpty();
                        }
                    });
                    assertEquals("No source can be published by an unfinished direct operation", acceptedBeforeHold, h.nativeFrames.size());
                    await("Actual surface submits a withdrawal after the original deadline", 3000, () -> !h.posts.isEmpty()
                            && h.posts.get(h.posts.size() - 1).recordedAtMillis > originalDeadline
                            && (h.posts.get(h.posts.size() - 1).acceptedPaint == null
                                || h.posts.get(h.posts.size() - 1).acceptedPaint.members.isEmpty()));
                    absentMaskPixels("direct-expire-withdrawn", previous.read.candidates.get(0).regions.get(0));
                    assertTrue("Pixel sampling cannot outlive the controlled held operation", h.nativeCosts.stream().noneMatch(c -> c.request == input.identity));
                    assertEquals(acceptedBeforeHold, h.nativeFrames.size());
                }
                if (change == DirectChange.STOP || change == DirectChange.CLOSE) {
                    if (change == DirectChange.CLOSE) {
                        main(h.controller::close);
                        SystemClock.sleep(150);
                        assertFalse("Held exact address cannot report managed drain", h.controller.managedResourcesDrained());
                    } else main(h.controller::stop);
                    assertTrue(input.cancelled());
                }
                else if (change == DirectChange.LITERAL) {
                    activity.onActivity(value -> value.nativeText.setText("ordinary safe caption"));
                    main(() -> h.controller.contentChanged(original.id, false));
                    assertFalse("Soft revocation must leave final exact-node validation active", input.cancelled());
                } else if (change == DirectChange.RESIZE) {
                    long revision = input.treeGeneration();
                    activity.onActivity(value -> value.getWindow().setLayout(original.displayWidth, original.displayHeight / 2));
                    await("Actual direct-scope same-id window resized", () -> {
                        AccessibilityWindowReader.Window observed = h.controller.observeWindow(context.getPackageName(), original.id);
                        return observed != null && observed.id == original.id && !observed.same(original);
                    });
                    assertFalse(input.cancelled()); assertEquals(revision, input.treeGeneration());
                }
                release.countDown();
                await("One actual direct reader terminal", () -> h.nativeCosts.stream().anyMatch(c -> c.request == input.identity));
                if (change == DirectChange.NONE || change == DirectChange.EXPIRE) await("Direct positive admission", () -> h.nativeFrames.stream()
                        .anyMatch(f -> f.read.readMode == NativeTextResult.ReadMode.ADMITTED_NODE_RECLASSIFY));
                NativeReadCost cost = h.nativeCosts.stream().filter(c -> c.request == input.identity).findFirst().get();
                assertEquals(NativeTextResult.ReadMode.ADMITTED_NODE_RECLASSIFY, cost.readMode);
                assertEquals(1, h.nativeCosts.stream().filter(c -> c.request == input.identity).count());
                assertEquals(1, h.nativeCostStarts.stream().filter(c -> c.request == input.identity).count());
                assertEquals(0, cost.costs.get(NativeReadCost.Stage.CHILD_FETCH.ordinal()).calls);
                assertEquals(1, cost.costs.get(NativeReadCost.Stage.DISPOSE.ordinal()).calls);
                if (change == DirectChange.NONE || change == DirectChange.EXPIRE) {
                    assertEquals(NativeReadCost.Terminal.SUCCESS, cost.terminal);
                    assertEquals(5, cost.costs.get(NativeReadCost.Stage.PROVIDER_CURRENT.ordinal()).calls);
                    assertEquals(2, cost.costs.get(NativeReadCost.Stage.TRAVERSAL_CURRENT.ordinal()).calls);
                    assertEquals(1, cost.costs.get(NativeReadCost.Stage.NODE_REFRESH.ordinal()).calls);
                    assertEquals(1, cost.costs.get(NativeReadCost.Stage.GLYPH_QUERY.ordinal()).calls);
                    assertEquals(1, cost.costs.get(NativeReadCost.Stage.GEOMETRY_EXCLUSIVE.ordinal()).calls);
                    NativeTextFrame direct = h.nativeFrames.stream().filter(f -> f.read.readMode == NativeTextResult.ReadMode.ADMITTED_NODE_RECLASSIFY
                            && f.read.readStartedAtMillis == cost.readStartedAtMillis).findFirst().get();
                    assertEquals(NativeTextResult.Coverage.POSITIVE_NODES_ONLY, direct.read.coverage);
                    assertEquals(1, direct.read.visitedNodes); assertEquals(1, direct.read.candidates.size());
                    assertEquals(baseline.read.candidates.get(0).nodeKey, direct.read.candidates.get(0).nodeKey);
                    assertEquals(baseline.read.candidates.get(0).contentKey, direct.read.candidates.get(0).contentKey);
                    assertTrue(direct.read.candidates.get(0).validatedAtMillis > baseline.read.candidates.get(0).validatedAtMillis);
                    assertTrue(direct.read.confirmationStartedAtMillis >= direct.read.classificationCompletedAtMillis + 48);
                    if (change == DirectChange.EXPIRE) {
                        java.util.Map<String, Long> afterGroups = nativeGroups(h, baselineKeys, cost.readStartedAtMillis);
                        assertNotEquals("True expiry cannot be concealed as group continuity", beforeGroups, afterGroups);
                        SceneEngine.Region region = direct.read.candidates.get(0).regions.get(0);
                        composed("direct-expire-reclassified", (int) ((region.left + region.right) / 2),
                                (int) ((region.top + region.bottom) / 2), COVER);
                    }
                } else {
                    if (change == DirectChange.RESIZE) {
                        assertTrue(cost.terminal == NativeReadCost.Terminal.FAILURE || cost.terminal == NativeReadCost.Terminal.CANCELLED);
                        assertTrue(cost.costs.get(NativeReadCost.Stage.PROVIDER_CURRENT.ordinal()).failedCalls > 0);
                    } else assertEquals(change == DirectChange.STOP || change == DirectChange.CLOSE
                            ? NativeReadCost.Terminal.CANCELLED : NativeReadCost.Terminal.FAILURE, cost.terminal);
                    assertTrue(h.nativeFrames.stream().noneMatch(f -> f.read.readStartedAtMillis == cost.readStartedAtMillis));
                    assertTrue(h.nativeEvidence.stream().noneMatch(r -> r.source.readStartedAtMillis == cost.readStartedAtMillis));
                }
                assertEquals(0, h.models.get());
                assertEquals(0, h.traces.stream().filter(t -> t.stage == CensorEngine.Stage.CAPTURE_DISPATCHED).count());
                main(h.controller::stop);
                assertBinding();
            } finally { release.countDown(); main(() -> h.service.controller = h.controller); }
        }
    }

    @Test public void actualWindowReadinessDiagnosticSeparatesFullWindowFromControlledNonFullRefusalWithoutStartingEngine() throws Exception {
        try (Harness h = new Harness("native-window-readiness", false, () -> { });
                ActivityScenario<BoundCensorFixtureActivity> activity = ActivityScenario.launch(BoundCensorFixtureActivity.class)) {
            AccessibilityWindowReader.Window baseline = h.window();
            assertEquals(1, h.windowWaits.size()); assertEquals("SUCCESS", h.windowWaits.get(0).getString("outcome"));
            activity.onActivity(value -> value.getWindow().setLayout(baseline.displayWidth, baseline.displayHeight / 2));
            await("Actual Reader observes the controlled non-full window before testing its refusal", () -> {
                AccessibilityWindowReader.Window read = h.controller.observeWindow(context.getPackageName(), -1);
                return read != null && read.id == baseline.id && (read.left != 0 || read.top != 0
                        || read.width != read.displayWidth || read.height != read.displayHeight);
            });
            try { h.window(); fail("A controlled non-full window must not pass the unchanged readiness predicate"); }
            catch (AssertionError expected) {
                if (expected.getSuppressed().length != 0) throw expected;
                assertEquals("Actual focused application window", expected.getMessage());
            }
            assertEquals(2, h.windowWaits.size()); JSONObject refusal = h.windowWaits.get(1);
            assertEquals("FAILURE", refusal.getString("outcome")); assertEquals(0, refusal.getInt("droppedPollCount"));
            JSONArray polls = refusal.getJSONArray("polls"); JSONObject descriptor = polls.getJSONObject(polls.length() - 1).getJSONObject("descriptor");
            assertEquals(baseline.id, descriptor.getInt("id")); assertTrue(descriptor.getInt("left") != 0 || descriptor.getInt("top") != 0
                    || descriptor.getInt("width") != descriptor.getInt("displayWidth") || descriptor.getInt("height") != descriptor.getInt("displayHeight"));
            JSONObject diagnostic = refusal.getJSONObject("failureObservation");
            assertEquals(h.lease.generation, diagnostic.getLong("leaseGeneration"));
            assertTrue("Waiting worker termination cannot replace actual nested main-task completion",
                    diagnostic.getJSONObject("activityTask").getBoolean("completionObserved"));
            JSONArray queries = diagnostic.getJSONArray("queries"); assertEquals(4, queries.length());
            for (int index = 0; index < queries.length(); index++) {
                assertEquals("SUCCESS", queries.getJSONObject(index).getString("status"));
                assertTrue("Diagnostic worker must actually terminate", queries.getJSONObject(index).getBoolean("workerFinished"));
            }
            assertTrue(h.nativeCostStarts.isEmpty()); assertTrue(h.nativeCosts.isEmpty()); assertTrue(h.nativeFrames.isEmpty());
            assertTrue(h.nativeEvidence.isEmpty()); assertTrue(h.batches.isEmpty()); assertTrue(h.posts.isEmpty());
            assertEquals(0, h.prepared.get()); assertEquals(0, h.models.get()); assertBinding();
        }
    }

    @Test public void admittedNativeSuccessHandsOffSealedAddressesWithoutAnotherTerminalCooldown() throws Exception {
        actualNativePacing(false);
    }

    @Test public void failedNativeSuccessorClearsPriorSuccessPacingAndKeepsTerminalBackoff() throws Exception {
        actualNativePacing(true);
    }

    private void actualNativePacing(boolean failSecond) throws Exception {
        CountDownLatch firstEntered = new CountDownLatch(1), secondEntered = new CountDownLatch(1), thirdEntered = new CountDownLatch(1);
        CountDownLatch firstRelease = new CountDownLatch(1), secondRelease = new CountDownLatch(1), thirdRelease = new CountDownLatch(1);
        AtomicReference<CensorEngine.NativeTextInput> first = new AtomicReference<>(), second = new AtomicReference<>(), third = new AtomicReference<>();
        AtomicLong firstHeldAt = new AtomicLong(-1), secondHeldAt = new AtomicLong(-1);
        List<JSONObject> boundaries = new CopyOnWriteArrayList<>();
        String label = failSecond ? "native-success-pacing-failure" : "native-success-pacing-handoff";
        Harness h = new Harness(label, false, () -> { }, () -> { }, (input, boundary) -> {
            try { boundaries.add(new JSONObject().put("request", input.identity).put("boundary", boundary.name())
                    .put("atMillis", SystemClock.uptimeMillis()).put("readMode", input.readMode().name())
                    .put("epoch", input.epoch).put("geometry", input.geometry).put("hardGeneration", input.hardGeneration)); }
            catch (org.json.JSONException failure) { throw new IllegalStateException(failure); }
            if (boundary == NativeTextReader.Boundary.OPENED && second.get() != null
                    && input.identity != second.get().identity && third.compareAndSet(null, input)) {
                thirdEntered.countDown(); hold(thirdRelease);
            }
            if (boundary == NativeTextReader.Boundary.SCAN_END) {
                if (first.compareAndSet(null, input)) {
                    firstHeldAt.set(SystemClock.uptimeMillis()); firstEntered.countDown(); hold(firstRelease);
                } else if (input.identity != first.get().identity && second.compareAndSet(null, input)) {
                    secondHeldAt.set(SystemClock.uptimeMillis()); secondEntered.countDown(); hold(secondRelease);
                    if (failSecond) throw new IllegalStateException("Controlled native pacing failure before SCAN_END scope query");
                }
            }
        });
        long firstReleasedAt = -1, secondReleasedAt = -1; boolean passed = false; Throwable primaryFailure = null;
        try (Harness owned = h;
                ActivityScenario<NativeTextMotionFixtureActivity> activity = ActivityScenario.launch(NativeTextMotionFixtureActivity.class)) {
            activity.onActivity(value -> {
                value.getWindow().setFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN,
                        android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);
                value.getWindow().setDecorFitsSystemWindows(false);
            });
            AccessibilityWindowReader.Window window = h.window();
            main(() -> h.service.controller = null);
            Throwable bodyFailure = null;
            try {
                h.start(window, 10, config(2, false, .3f), text(), false);
                assertTrue("Actual first FULL reaches its held scan-end boundary", firstEntered.await(8, TimeUnit.SECONDS));
                assertEquals(NativeTextResult.ReadMode.FULL_DISCOVERY, first.get().readMode());
                await("Held first read passes the actual start-spacing budget", () -> SystemClock.uptimeMillis() - firstHeldAt.get() >= 300);
                assertEquals(1, h.nativeCostStarts.size()); assertTrue(h.nativeFrames.isEmpty()); assertTrue(h.nativeCosts.isEmpty());
                firstReleasedAt = SystemClock.uptimeMillis(); firstRelease.countDown();
                assertTrue("Actual successor reaches fresh same-node classification", secondEntered.await(8, TimeUnit.SECONDS));
                assertEquals(2, h.nativeCostStarts.size()); assertEquals(1, h.nativeFrames.size());
                NativeTextFrame baseline = h.nativeFrames.get(0);
                assertEquals(first.get().identity, baseline.requestIdentity); assertEquals(2, baseline.read.candidates.size());
                assertEquals(NativeTextResult.Coverage.COMPLETE_TREE, baseline.read.coverage);
                assertEquals(NativeTextResult.ReadMode.ADMITTED_NODE_RECLASSIFY, second.get().readMode());
                NativeReadCost.Start next = h.nativeCostStarts.stream().filter(value -> value.request == second.get().identity).findFirst().get();
                assertTrue(next.invokedAtMillis >= baseline.source.receivedAtMillis);
                assertTrue("Actual next invocation does not pay another120ms after admitted success",
                        next.invokedAtMillis - baseline.source.receivedAtMillis < 120);
                if (failSecond) await("Held failed successor passes its start-spacing budget",
                        () -> SystemClock.uptimeMillis() - secondHeldAt.get() >= 300);
                assertEquals(1, h.nativeCosts.size()); assertEquals(1, h.nativeEvidence.size());
                secondReleasedAt = SystemClock.uptimeMillis(); secondRelease.countDown();
                assertTrue("Exactly one real third read follows terminal processing", thirdEntered.await(8, TimeUnit.SECONDS));
                assertEquals(3, h.nativeCostStarts.size()); assertEquals(2, h.nativeCosts.size());
                NativeReadCost ended = h.nativeCosts.stream().filter(value -> value.request == second.get().identity).findFirst().get();
                NativeReadCost.Start following = h.nativeCostStarts.stream().filter(value -> value.request == third.get().identity).findFirst().get();
                assertTrue("Actual read starts retain120ms spacing", ended.readStartedAtMillis - baseline.source.readStartedAtMillis >= 120);
                if (failSecond) {
                    assertEquals(NativeReadCost.Terminal.FAILURE, ended.terminal);
                    assertTrue("A diagnostic terminal precedes actual Engine terminal, so this is a lower bound",
                            following.invokedAtMillis - ended.terminalAtMillis >= 120);
                    assertEquals(1, h.nativeFrames.size()); assertEquals(1, h.nativeEvidence.size());
                    assertEquals(1, ended.costs.get(NativeReadCost.Stage.DISPOSE.ordinal()).calls);
                } else {
                    assertEquals(NativeReadCost.Terminal.SUCCESS, ended.terminal);
                    assertEquals(2, h.nativeFrames.size()); assertEquals(2, h.nativeEvidence.size());
                    NativeTextFrame admitted = h.nativeFrames.get(1);
                    assertEquals(second.get().identity, admitted.requestIdentity);
                    assertEquals(NativeTextResult.Coverage.POSITIVE_NODES_ONLY, admitted.read.coverage);
                    assertEquals(1, admitted.read.candidates.size());
                    NativeTextResult.Candidate selected = admitted.read.candidates.get(0);
                    assertTrue(baseline.read.candidates.stream().anyMatch(candidate -> candidate.nodeKey.equals(selected.nodeKey)
                            && candidate.contentKey.equals(selected.contentKey) && candidate.category.equals(selected.category)
                            && candidate.textSource == selected.textSource && candidate.matchStart == selected.matchStart
                            && candidate.matchEnd == selected.matchEnd && candidate.normalizedLength == selected.normalizedLength));
                    assertEquals(0, ended.costs.get(NativeReadCost.Stage.CHILD_FETCH.ordinal()).calls);
                    assertEquals(5, ended.costs.get(NativeReadCost.Stage.PROVIDER_CURRENT.ordinal()).calls);
                    assertEquals(1, ended.costs.get(NativeReadCost.Stage.NODE_REFRESH.ordinal()).calls);
                    assertEquals(1, ended.costs.get(NativeReadCost.Stage.GLYPH_QUERY.ordinal()).calls);
                }
                main(h.controller::stop); thirdRelease.countDown();
                await("Held third parent has exactly one actual cancellation terminal", () -> h.nativeCosts.size() == 3);
                assertEquals(NativeReadCost.Terminal.CANCELLED, h.nativeCosts.stream()
                        .filter(value -> value.request == third.get().identity).findFirst().get().terminal);
                main(h.controller::close); await("Actual pacing controller managed closure", h.controller::managedResourcesDrained);
                assertEquals(3, h.nativeCostStarts.size()); assertEquals(3, h.nativeCosts.size());
                assertEquals(3, h.nativeCostStarts.stream().map(value -> value.request).distinct().count());
                assertEquals(h.nativeCostStarts.stream().map(value -> value.request).collect(java.util.stream.Collectors.toSet()),
                        h.nativeCosts.stream().map(value -> value.request).collect(java.util.stream.Collectors.toSet()));
                assertEquals(0, h.models.get()); assertEquals(0, h.prepared.get());
                assertEquals(0, h.controller.host().nativeCostDiagnosticFailures()); assertBinding(); passed = true;
            } catch (Exception | Error failure) { bodyFailure = failure; throw failure; }
            finally {
                firstRelease.countDown(); secondRelease.countDown(); thirdRelease.countDown();
                try { main(() -> h.service.controller = h.controller); }
                catch (Exception | Error restoreFailure) {
                    if (bodyFailure == null) throw restoreFailure;
                    if (bodyFailure != restoreFailure) bodyFailure.addSuppressed(restoreFailure);
                }
            }
        } catch (Exception | Error failure) { primaryFailure = failure; throw failure; }
        finally {
            try {
                JSONArray frames = new JSONArray();
                for (NativeTextFrame frame : h.allNativeFrames()) {
                    JSONArray identities = new JSONArray();
                    for (NativeTextResult.Candidate candidate : frame.read.candidates) identities.put(new JSONObject()
                            .put("nodeKey", candidate.nodeKey).put("contentKey", candidate.contentKey).put("category", candidate.category));
                    frames.put(new JSONObject()
                            .put("telemetry", new JSONObject(com.subhub.app.diagnostics.ReplacementEngineTelemetry.nativeRead(1, frame)
                                .substring("REPLACEMENT_NATIVE ".length())))
                            .put("request", frame.requestIdentity).put("source", frame.source.sequence)
                            .put("geometry", frame.source.geometry)
                            .put("receivedAtMillis", frame.source.receivedAtMillis).put("identities", identities));
                }
                JSONObject receipt = new JSONObject().put("schemaVersion", 1).put("label", label).put("pid", Process.myPid())
                        .put("eventDeliveryIsolation", "CONTROLLER_POINTER_DETACHED").put("injectedSecondFailure", failSecond)
                        .put("firstRequest", first.get() == null ? -1 : first.get().identity)
                        .put("secondRequest", second.get() == null ? -1 : second.get().identity)
                        .put("thirdRequest", third.get() == null ? -1 : third.get().identity)
                        .put("firstHeldAtMillis", firstHeldAt.get()).put("firstReleasedAtMillis", firstReleasedAt)
                        .put("secondHeldAtMillis", secondHeldAt.get()).put("secondReleasedAtMillis", secondReleasedAt)
                        .put("bodyPassed", passed).put("managedDrain", h.controller.managedResourcesDrained())
                        .put("rawBoundaryCount", boundaries.size()).put("boundaries", new JSONArray(boundaries))
                        .put("rawFrameCount", frames.length()).put("frames", frames).put("pixelPerformanceAcceptance", false);
                try (FileOutputStream stream = new FileOutputStream(new File(directory(), "native-success-pacing-" + System.nanoTime() + ".json"))) {
                    stream.write(receipt.toString().getBytes(StandardCharsets.UTF_8));
                }
            } catch (Exception | Error exportFailure) {
                if (primaryFailure == null) throw exportFailure;
                if (primaryFailure != exportFailure) primaryFailure.addSuppressed(exportFailure);
            }
        }
    }

    @Test public void stableGenerationRotatesKnownAddressesThenDiscoversAnUnseenNode() throws Exception {
        stableReadSelection(false);
    }

    @Test public void failedStableDiscoveryRefillsOnlyOneRetainedAddressPass() throws Exception {
        stableReadSelection(true);
    }

    private void stableReadSelection(boolean failFirstRediscovery) throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<CensorEngine.NativeTextInput> firstDirect = new AtomicReference<>();
        AtomicInteger fulls = new AtomicInteger(); AtomicLong failedFull = new AtomicLong(-1);
        List<JSONObject> boundaries = new CopyOnWriteArrayList<>();
        String label = failFirstRediscovery ? "native-stable-selection-failed-full" : "native-stable-selection";
        Harness h = new Harness(label, false, () -> { }, () -> { }, (input, boundary) -> {
            try {
                boundaries.add(new JSONObject().put("request", input.identity).put("epoch", input.epoch)
                        .put("geometry", input.geometry).put("hardGeneration", input.hardGeneration)
                        .put("scanGeneration", input.scanGeneration()).put("treeGeneration", input.treeGeneration())
                        .put("readMode", input.readMode().name()).put("boundary", boundary.name())
                        .put("atMillis", SystemClock.uptimeMillis()).put("cancelled", input.cancelled()));
            } catch (org.json.JSONException failure) { throw new IllegalStateException(failure); }
            if (boundary == NativeTextReader.Boundary.OPENED) {
                if (input.readMode() == NativeTextResult.ReadMode.ADMITTED_NODE_COHORT_RECLASSIFY
                        && firstDirect.compareAndSet(null, input)) { entered.countDown(); hold(release); }
                if (input.readMode() == NativeTextResult.ReadMode.FULL_DISCOVERY && fulls.incrementAndGet() == 2
                        && failFirstRediscovery) {
                    failedFull.set(input.identity);
                    throw new IllegalStateException("Controlled stable FULL failure before provider current check");
                }
            }
        });
        long addedStart = -1, addedEnd = -1; boolean passed = false; Throwable primaryFailure = null;
        try (Harness owned = h;
                ActivityScenario<NativeTextMotionFixtureActivity> activity = ActivityScenario.launch(NativeTextMotionFixtureActivity.class)) {
            activity.onActivity(value -> {
                value.getWindow().setFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN,
                        android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);
                value.getWindow().setDecorFitsSystemWindows(false);
            });
            AccessibilityWindowReader.Window window = h.window();
            // Explicit adverse event isolation, not production behavior. Framework cache invalidation
            // remains active; only forwarding to this controller is detached before the first read.
            main(() -> h.service.controller = null);
            try {
                h.start(window, 10, config(2, false, .3f), text(), false);
                assertTrue("Stable-generation cohort must follow the initial full admission", entered.await(8, TimeUnit.SECONDS));
                assertEquals(1, h.nativeFrames.size()); NativeTextFrame baseline = h.nativeFrames.get(0);
                assertEquals(2, baseline.read.candidates.size()); assertEquals(NativeTextResult.Coverage.COMPLETE_TREE, baseline.read.coverage);
                addedStart = SystemClock.uptimeMillis();
                activity.onActivity(value -> {
                    androidx.appcompat.widget.AppCompatTextView unseen = new androidx.appcompat.widget.AppCompatTextView(value);
                    unseen.setId(android.R.id.custom); unseen.setText(NativeTextMotionFixtureActivity.CAPTION);
                    unseen.setTextSize(20); unseen.setTextColor(Color.BLACK); unseen.setPadding(0, 0, 0, 0);
                    unseen.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
                    android.widget.FrameLayout.LayoutParams params = new android.widget.FrameLayout.LayoutParams(800, -2);
                    params.leftMargin = 32; params.topMargin = 300; value.surface.addView(unseen, params); value.demand();
                });
                addedEnd = SystemClock.uptimeMillis(); release.countDown();
                int expectedReads = failFirstRediscovery ? 5 : 3;
                await("The next bounded successful FULL discovers the unseen ordinary node", () -> h.nativeFrames.stream().anyMatch(frame ->
                        frame.requestIdentity == expectedReads && frame.read.readMode == NativeTextResult.ReadMode.FULL_DISCOVERY
                        && frame.read.candidates.size() == 3));
                main(h.controller::stop);
                assertEquals(failFirstRediscovery ? 4 : 3, h.nativeFrames.size());
                assertTrue(h.nativeCostStarts.size() >= expectedReads);
                java.util.Set<String> known = new java.util.HashSet<>();
                for (NativeTextResult.Candidate candidate : baseline.read.candidates) known.add(candidate.nodeKey);
                assertEquals(2, known.size());
                for (int offset : failFirstRediscovery ? new int[]{1, 3} : new int[]{1}) {
                    java.util.Set<String> rotated = new java.util.HashSet<>();
                    long request = h.nativeCostStarts.get(offset).request;
                    NativeTextFrame frame = h.nativeFrames.stream().filter(value -> value.requestIdentity == request).findFirst().get();
                    assertEquals(NativeTextResult.ReadMode.ADMITTED_NODE_COHORT_RECLASSIFY, frame.read.readMode);
                    assertEquals(NativeTextResult.Coverage.POSITIVE_NODES_ONLY, frame.read.coverage);
                    assertEquals(2, frame.read.candidates.size());
                    for (NativeTextResult.Candidate candidate : frame.read.candidates) rotated.add(candidate.nodeKey);
                    assertEquals(known, rotated);
                }
                for (NativeTextFrame frame : h.allNativeFrames()) {
                    assertEquals(baseline.read.scanGeneration, frame.read.scanGeneration);
                    assertEquals(baseline.read.scanGeneration, frame.read.confirmationGeneration);
                    assertEquals(baseline.read.hardGeneration, frame.read.hardGeneration);
                }
                if (failFirstRediscovery) {
                    assertEquals(3, failedFull.get());
                    assertTrue(h.nativeFrames.stream().noneMatch(frame -> frame.requestIdentity == failedFull.get()));
                    assertEquals(NativeReadCost.Terminal.FAILURE, h.nativeCosts.stream()
                            .filter(cost -> cost.request == failedFull.get()).findFirst().get().terminal);
                }
                assertEquals(0, h.models.get()); assertEquals(0, h.prepared.get());
                assertEquals(0, h.traces.stream().filter(trace -> trace.stage == CensorEngine.Stage.CAPTURE_DISPATCHED).count());
                assertBinding(); passed = true;
            } finally { release.countDown(); main(() -> h.service.controller = h.controller); }
        } catch (Exception | Error failure) { primaryFailure = failure; throw failure; }
        finally {
            try {
                JSONArray frames = new JSONArray();
                for (NativeTextFrame frame : h.allNativeFrames()) {
                    JSONObject nativeRead = new JSONObject(com.subhub.app.diagnostics.ReplacementEngineTelemetry.nativeRead(1, frame)
                            .substring("REPLACEMENT_NATIVE ".length()));
                    JSONArray identities = new JSONArray();
                    for (NativeTextResult.Candidate candidate : frame.read.candidates) {
                        JSONArray regions = new JSONArray();
                        for (SceneEngine.Region region : candidate.regions) regions.put(new JSONObject().put("left", region.left)
                                .put("top", region.top).put("right", region.right).put("bottom", region.bottom));
                        identities.put(new JSONObject().put("nodeKey", candidate.nodeKey).put("contentKey", candidate.contentKey)
                                .put("category", candidate.category).put("regions", regions));
                    }
                    frames.put(new JSONObject().put("telemetry", nativeRead).put("identities", identities));
                }
                JSONObject receipt = new JSONObject().put("schemaVersion", 2).put("label", label).put("pid", Process.myPid())
                        .put("eventDeliveryIsolation", "CONTROLLER_POINTER_DETACHED").put("injectedFullFailure", failFirstRediscovery)
                        .put("failedFullRequest", failedFull.get()).put("firstAddressRequest", firstDirect.get() == null ? -1 : firstDirect.get().identity)
                        .put("addedStartedAtMillis", addedStart).put("addedFinishedAtMillis", addedEnd).put("bodyPassed", passed)
                        .put("managedDrain", h.controller.managedResourcesDrained()).put("rawBoundaryCount", boundaries.size())
                        .put("boundaries", new JSONArray(boundaries)).put("rawFrameCount", frames.length()).put("frames", frames)
                        .put("pixelPerformanceAcceptance", false);
                try (FileOutputStream stream = new FileOutputStream(new File(directory(), "native-stable-selection-" + System.nanoTime() + ".json"))) {
                    stream.write(receipt.toString().getBytes(StandardCharsets.UTF_8));
                }
            } catch (Exception | Error exportFailure) {
                if (primaryFailure == null) throw exportFailure;
                if (primaryFailure != exportFailure) primaryFailure.addSuppressed(exportFailure);
            }
        }
    }

    @Test public void suppliedDuplicateIdentityDoesNotAliasDistinctViewsOrRebindReplacement() throws Exception {
        try (Harness h = new Harness("native-supplied-duplicate-lineage", false, () -> { });
                ActivityScenario<NativeTextFixtureActivity> activity = ActivityScenario.launch(NativeTextFixtureActivity.class)) {
            activity.onActivity(value -> {
                value.getWindow().setFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN,
                        android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);
                value.getWindow().setDecorFitsSystemWindows(false);
                if (value.getActionBar() != null) value.getActionBar().hide();
                value.first.providerIdentity = value.second.providerIdentity = "same-supplied-id";
                value.first.setText("send nudes"); value.second.setText("send nudes");
            });
            AccessibilityWindowReader.Window window = h.window();
            main(() -> h.service.controller = null);
            h.start(window, 10, config(2, false, .3f), text(), false);
            await("Two actual equal-supplied-ID Views classified", () -> h.nativeFrames.stream().anyMatch(f -> f.read.candidates.size() == 2));
            NativeTextFrame initial = h.nativeFrames.stream().filter(f -> f.read.candidates.size() == 2).findFirst().get();
            NativeTextResult.Candidate upper = initial.read.candidates.get(0).regions.get(0).top < initial.read.candidates.get(1).regions.get(0).top
                    ? initial.read.candidates.get(0) : initial.read.candidates.get(1);
            NativeTextResult.Candidate lower = initial.read.candidates.get(0) == upper ? initial.read.candidates.get(1) : initial.read.candidates.get(0);
            assertEquals(upper.contentKey, lower.contentKey); assertNotEquals(upper.nodeKey, lower.nodeKey);
            java.util.Set<String> initialKeys = new java.util.HashSet<>(java.util.Arrays.asList(upper.nodeKey, lower.nodeKey));
            java.util.Map<String, Long> groups = nativeGroups(h, initialKeys, initial.read.readStartedAtMillis); assertEquals(2, groups.size());
            long commandAt = SystemClock.uptimeMillis();
            main(() -> h.controller.contentChanged(window.id, false));
            await("Both exact admitted addresses reclassified independently", () -> h.nativeFrames.stream()
                    .filter(f -> f.read.readMode == NativeTextResult.ReadMode.ADMITTED_NODE_COHORT_RECLASSIFY && f.read.readStartedAtMillis >= commandAt)
                    .flatMap(f -> f.read.candidates.stream()).map(candidate -> candidate.nodeKey).distinct().count() == 2);
            assertEquals("Unchanged real Views keep distinct continuing groups", groups, nativeGroups(h, initialKeys, commandAt));
            long firstDirect = h.nativeCostStarts.stream().filter(c -> c.readMode == NativeTextResult.ReadMode.ADMITTED_NODE_COHORT_RECLASSIFY
                    && c.invokedAtMillis >= commandAt).mapToLong(c -> c.request).min().getAsLong();
            await("Full discovery cannot be starved by endless soft event demand", () -> {
                main(() -> h.controller.contentChanged(window.id, false));
                return h.nativeCostStarts.stream().anyMatch(c -> c.request > firstDirect && c.readMode == NativeTextResult.ReadMode.FULL_DISCOVERY);
            });
            long nextFull = h.nativeCostStarts.stream().filter(c -> c.request > firstDirect && c.readMode == NativeTextResult.ReadMode.FULL_DISCOVERY)
                    .mapToLong(c -> c.request).min().getAsLong();
            assertTrue("At most one two-address rotation can precede the next full attempt", h.nativeCostStarts.stream()
                    .filter(c -> c.request >= firstDirect && c.request < nextFull && c.readMode == NativeTextResult.ReadMode.ADMITTED_NODE_COHORT_RECLASSIFY).count() <= 1);
            long replacedAt = SystemClock.uptimeMillis();
            activity.onActivity(value -> {
                android.widget.LinearLayout parent = (android.widget.LinearLayout) value.first.getParent();
                android.view.ViewGroup.LayoutParams layout = value.first.getLayoutParams();
                int index = parent.indexOfChild(value.first); parent.removeView(value.first);
                value.first = value.new ProviderText("same-supplied-id"); value.first.setText("send nudes"); parent.addView(value.first, index, layout);
            });
            main(() -> h.controller.contentChanged(window.id, false));
            await("A new actual View cannot inherit the old supplied-ID lineage", () -> h.nativeFrames.stream().anyMatch(f ->
                    f.read.readStartedAtMillis >= replacedAt && f.read.candidates.size() == 2
                    && f.read.candidates.stream().noneMatch(c -> upper.nodeKey.equals(c.nodeKey))
                    && f.read.candidates.stream().anyMatch(c -> lower.nodeKey.equals(c.nodeKey))));
            NativeTextFrame replacedFrame = h.nativeFrames.stream().filter(f -> f.read.readStartedAtMillis >= replacedAt
                    && f.read.candidates.size() == 2 && f.read.candidates.stream().noneMatch(c -> upper.nodeKey.equals(c.nodeKey)))
                    .findFirst().get();
            java.util.Set<String> replacementKeys = new java.util.HashSet<>();
            for (NativeTextResult.Candidate candidate : replacedFrame.read.candidates) replacementKeys.add(candidate.nodeKey);
            java.util.Map<String, Long> replacement = nativeGroups(h, replacementKeys, replacedAt);
            assertEquals(2, replacement.size()); assertFalse(replacement.containsKey(upper.nodeKey));
            String newKey = replacement.keySet().stream().filter(k -> !lower.nodeKey.equals(k)).findFirst().get();
            assertNotEquals(groups.get(upper.nodeKey), replacement.get(newKey));
            main(h.controller::stop); assertBinding();
        }
    }
    private static java.util.Map<String, Long> nativeGroups(Harness h, java.util.Set<String> expectedKeys, long sourceStartedNotBefore) throws Exception {
        AtomicReference<java.util.Map<String, Long>> selected = new AtomicReference<>();
        await("Each rendered native group has its actual admitted Source/candidate", () -> {
            java.util.Map<String, Long> result = new java.util.HashMap<>();
            try (CensorEngine<PixelFrame>.RenderView view = h.controller.host().acquireRenderView()) {
                if (view == null) return false;
                for (SceneEngine.Group group : view.snapshot.groups) for (SceneEngine.Mask member : group.members) {
                    if (!member.nativeValidated || !member.nativeNodeIdentityKnown) return false;
                    NativeTextFrame source = h.allNativeFrames().stream().filter(f -> f.source.epoch == member.evidenceSource.epoch
                            && f.source.sequence == member.evidenceSource.sequence).findFirst().orElse(null);
                    if (source == null || source.read.readStartedAtMillis < sourceStartedNotBefore) return false;
                    NativeTextResult.Candidate candidate = source.read.candidates.stream()
                            .filter(c -> c.regions.contains(member.evidenceRegion)).findFirst().orElse(null);
                    if (candidate == null) return false;
                    Long before = result.put(candidate.nodeKey, member.textGroup);
                    if (before != null && before != member.textGroup) return false;
                }
            }
            if (!result.keySet().equals(expectedKeys)) return false; selected.set(result); return true;
        });
        return selected.get();
    }

    @Test public void cancellationDuringActualLocalTraversalCannotAdmitNativeFacts() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<CensorEngine.NativeTextInput> heldInput = new AtomicReference<>();
        try (Harness h = new Harness("native-local-traversal-cancel", false, () -> { }, () -> { }, (input, boundary) -> {
            assertNotEquals(Looper.getMainLooper(), Looper.myLooper());
            if (boundary == NativeTextReader.Boundary.TRAVERSAL && heldInput.compareAndSet(null, input)) {
                entered.countDown(); hold(release);
            }
        }); ActivityScenario<BoundCensorFixtureActivity> activity = ActivityScenario.launch(BoundCensorFixtureActivity.class)) {
            h.start(h.window(), 10, config(2, false, .3f), text(), false);
            try {
                assertTrue("Actual local traversal never entered", entered.await(8, TimeUnit.SECONDS));
                CensorEngine.NativeTextInput input = heldInput.get(); assertNotNull(input); assertFalse(input.cancelled());
                main(h.controller::stop); assertTrue(input.cancelled()); release.countDown();
                await("One actual cancelled reader terminal", () -> h.nativeCosts.stream().anyMatch(c -> c.request == input.identity));
                NativeReadCost cost = h.nativeCosts.stream().filter(c -> c.request == input.identity).findFirst().get();
                assertEquals(NativeReadCost.Terminal.CANCELLED, cost.terminal);
                assertEquals(1, h.nativeCosts.stream().filter(c -> c.request == input.identity).count());
                assertEquals(1, h.nativeCostStarts.stream().filter(c -> c.request == input.identity).count());
                assertTrue(cost.costs.get(NativeReadCost.Stage.TRAVERSAL_CURRENT.ordinal()).failedCalls > 0);
                assertTrue(cost.costs.get(NativeReadCost.Stage.DISPOSE.ordinal()).calls > 0);
                assertNoNativeAdmission(h); assertBinding();
            } finally { release.countDown(); }
        }
    }

    @Test public void actualWindowResizeBeforeScanEndRejectsTreeWithoutAContentFence() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<CensorEngine.NativeTextInput> heldInput = new AtomicReference<>();
        try (Harness h = new Harness("native-scan-end-real-window-resize", false, () -> { }, () -> { }, (input, boundary) -> {
            assertNotEquals(Looper.getMainLooper(), Looper.myLooper());
            if (boundary == NativeTextReader.Boundary.SCAN_END && heldInput.compareAndSet(null, input)) {
                entered.countDown(); hold(release);
            }
        }); ActivityScenario<BoundCensorFixtureActivity> activity = ActivityScenario.launch(BoundCensorFixtureActivity.class)) {
            AccessibilityWindowReader.Window original = h.window();
            h.start(original, 10, config(2, false, .3f), text(), false);
            try {
                assertTrue("Actual scan-end boundary never entered", entered.await(8, TimeUnit.SECONDS));
                CensorEngine.NativeTextInput input = heldInput.get(); assertNotNull(input); assertFalse(input.cancelled());
                long revision = input.treeGeneration();
                // Isolate fresh WindowReader validity from content-event cancellation in this debug service only.
                main(() -> h.service.controller = null);
                activity.onActivity(value -> value.getWindow().setLayout(original.displayWidth, original.displayHeight / 2));
                AtomicReference<AccessibilityWindowReader.Window> resized = new AtomicReference<>();
                await("Actual same-id application window has new bounds", () -> {
                    AccessibilityWindowReader.Window observed = h.controller.observeWindow(context.getPackageName(), original.id);
                    if (observed == null || observed.id != original.id || observed.same(original)) return false;
                    resized.set(observed); return true;
                });
                assertNotNull(resized.get()); assertFalse(input.cancelled()); assertEquals(revision, input.treeGeneration());
                release.countDown();
                await("Held tree reaches its actual rejecting terminal", () -> h.nativeCosts.stream().anyMatch(c -> c.request == input.identity));
                main(h.controller::stop);
                NativeReadCost cost = h.nativeCosts.stream().filter(c -> c.request == input.identity).findFirst().get();
                assertNotEquals(NativeReadCost.Terminal.SUCCESS, cost.terminal);
                assertNotEquals(NativeReadCost.Terminal.RESOURCE_FAILURE, cost.terminal);
                assertEquals(1, h.nativeCosts.stream().filter(c -> c.request == input.identity).count());
                assertEquals(1, h.nativeCostStarts.stream().filter(c -> c.request == input.identity).count());
                assertTrue(cost.costs.get(NativeReadCost.Stage.PROVIDER_CURRENT.ordinal()).failedCalls > 0);
                assertTrue(cost.costs.get(NativeReadCost.Stage.TRAVERSAL_CURRENT.ordinal()).calls > 0);
                assertTrue(cost.costs.get(NativeReadCost.Stage.DISPOSE.ordinal()).calls > 0);
                assertNoNativeAdmission(h); assertBinding();
            } finally { release.countDown(); main(() -> h.service.controller = h.controller); }
        }
    }

    @Test public void nativeCohortSharedQueryRejectsActualResizeBeforeObservation() throws Exception { nativeCohort(CohortChange.SHARED_RESIZE); }
    @Test public void nativeCohortSharedQueryRejectsStopBeforeObservation() throws Exception { nativeCohort(CohortChange.SHARED_STOP); }
    @Test public void nativeCohortFailedSecondLiteralKeepsOnlyFiniteFirstObservation() throws Exception { nativeCohort(CohortChange.FAILED_B); }
    @Test public void nativeCohortReentrantStopPreventsSecondGeometryAndFalseDrain() throws Exception { nativeCohort(CohortChange.REENTRANT_STOP); }
    @Test public void nativeCohortReclassifiesTwoAddressesWithOneParentAndNextFull() throws Exception { nativeCohort(CohortChange.NONE); }

    private enum CohortChange { SHARED_RESIZE, SHARED_STOP, FAILED_B, REENTRANT_STOP, NONE }
    private void nativeCohort(CohortChange change) throws Exception {
        assertNotNull("Exclusive current native_group_namespace is required", groupEvidenceDirectory);
        CountDownLatch opened = new CountDownLatch(1), openRelease = new CountDownLatch(1);
        CountDownLatch shared = new CountDownLatch(1), sharedRelease = new CountDownLatch(1);
        CountDownLatch observed = new CountDownLatch(1), observationRelease = new CountDownLatch(1);
        CountDownLatch nextFull = new CountDownLatch(1), nextRelease = new CountDownLatch(1);
        CountDownLatch terminalObserved = new CountDownLatch(1);
        AtomicReference<CensorEngine.NativeTextInput> selected = new AtomicReference<>(), successor = new AtomicReference<>();
        AtomicReference<NativeTextFrame> first = new AtomicReference<>(); AtomicReference<Throwable> observerFailure = new AtomicReference<>();
        AtomicReference<JSONArray> terminalRegistry = new AtomicReference<>();
        AtomicInteger roots = new AtomicInteger(), rootsBeforeCohort = new AtomicInteger(-1), rootsAtTerminal = new AtomicInteger(-1);
        JSONObject detail = new JSONObject(); boolean passed = false; Throwable primary = null;
        Harness h = new Harness("native-cohort-" + change.name().toLowerCase(java.util.Locale.ROOT), false,
                roots::incrementAndGet, () -> { }, (input, boundary) -> {
                    if (input.readMode() == NativeTextResult.ReadMode.ADMITTED_NODE_COHORT_RECLASSIFY) {
                        if (boundary == NativeTextReader.Boundary.OPENED && selected.compareAndSet(null, input)) {
                            rootsBeforeCohort.set(roots.get() - 1); // Its actual OPEN_SCOPE root has already returned.
                            opened.countDown(); hold(openRelease);
                        }
                        if ((change == CohortChange.SHARED_RESIZE || change == CohortChange.SHARED_STOP)
                                && input == selected.get() && boundary == NativeTextReader.Boundary.GROUP_END_BEFORE_FINAL_NODE_REFRESH) {
                            shared.countDown(); hold(sharedRelease);
                        }
                    } else if (selected.get() != null && input.readMode() == NativeTextResult.ReadMode.FULL_DISCOVERY
                            && input.identity > selected.get().identity && boundary == NativeTextReader.Boundary.OPENED
                            && successor.compareAndSet(null, input)) { nextFull.countDown(); hold(nextRelease); }
                });
        h.nativeCostControl.set(cost -> {
            if (cost.readMode != NativeTextResult.ReadMode.ADMITTED_NODE_COHORT_RECLASSIFY) return;
            rootsAtTerminal.set(roots.get());
            try {
                JSONArray registry = new JSONArray(); Object reader = groupField(h.controller.host(), "textReader");
                if (reader != null) for (Object entry : (List<?>) groupField(groupField(reader, "identities"), "entries")) {
                    Object basis = groupField(entry, "basis");
                    registry.put(new JSONObject().put("token", groupField(entry, "token")).put("refreshable", groupField(entry, "refreshable"))
                            .put("nodeKey", basis == null ? JSONObject.NULL : groupField(basis, "nodeKey"))
                            .put("sourceSequence", basis == null ? -1 : ((SceneEngine.Source) groupField(basis, "source")).sequence)
                            .put("originalValidUntilMillis", basis == null ? -1 : groupField(basis, "originalValidUntilMillis")));
                }
                terminalRegistry.set(registry);
            } catch (Exception | Error failure) { observerFailure.set(failure); }
            finally { terminalObserved.countDown(); }
        });
        h.nativeEvidenceControl.set(receipt -> {
            NativeTextFrame frame = h.nativeObservations.stream().filter(value -> value.source == receipt.source
                    && value.read.readMode == NativeTextResult.ReadMode.ADMITTED_NODE_COHORT_RECLASSIFY).findFirst().orElse(null);
            if (frame == null || !first.compareAndSet(null, frame)) return;
            try {
                detail.put("heldReader", heldCohortReader(h, frame));
                assertEquals(NativeTextResult.Coverage.POSITIVE_NODES_ONLY, receipt.nativeCoverage);
                assertEquals(1, receipt.subjects.size()); assertTrue(receipt.subjects.get(0).witnesses.isEmpty());
                assertFalse(receipt.subjects.get(0).confirmedForConsumption);
                CensorEngine.EvidenceClaim claim = h.controller.claimEvidence(receipt, new Object());
                assertNotNull(claim); assertTrue(claim.subjects.isEmpty());
                assertFalse(h.controller.dwellPresent(h.controller.viewportCoverage(), receipt));
                assertFalse(((CensorEngine<?>) groupField(h.controller.host(), "engine")).imageTextSuppressed());
                detail.put("partialAuthorityUnchanged", true);
                if (change == CohortChange.REENTRANT_STOP) main(h.controller::stop);
            } catch (Exception | Error failure) { observerFailure.set(failure); observed.countDown(); return; }
            observed.countDown(); if (change != CohortChange.REENTRANT_STOP) hold(observationRelease);
        });
        try (Harness owned = h; ActivityScenario<NativeTextFixtureActivity> activity = launchGroupWindow()) {
            detail.put("fixtureReadiness", prepareGroupWindow(activity)); AccessibilityWindowReader.Window window = h.window();
            // Isolate literal and fresh-window falsifiers from event-driven hard cancellation.
            main(() -> h.service.controller = null);
            AccessibilityCensorController.Configuration base = h.configuration(window, 10, config(2, false, .3f), text(), false);
            main(() -> h.controller.start(new AccessibilityCensorController.Configuration(base.token, base.window, base.detector,
                    base.quality, base.text, base.semanticText, base.exactText, ProtectionScope.normal(h.taps.scope()),
                    base.appearance, base.paintPolicy, base.artworkRevision)));
            try {
                assertTrue("Real cohort selected after actual admitted discovery", opened.await(8, TimeUnit.SECONDS));
                assertEquals(1, h.nativeFrames.size()); NativeTextFrame baseline = h.nativeFrames.get(0);
                assertEquals(2, baseline.read.candidates.size()); assertEquals(NativeTextResult.Coverage.COMPLETE_TREE, baseline.read.coverage);
                CensorEngine.NativeTextInput input = selected.get(); assertNotNull(input);
                long oldDeadline = Math.max(baseline.candidateValidUntilMillis(baseline.read.candidates.get(0)),
                        baseline.candidateValidUntilMillis(baseline.read.candidates.get(1)));
                await("A retained address is not an evidence lease during held OPENED", () -> SystemClock.uptimeMillis() > oldDeadline);
                try (CensorEngine<PixelFrame>.RenderView view = h.controller.host().acquireRenderView()) {
                    assertNotNull(view); assertTrue(view.snapshot.groups.isEmpty());
                }
                absentMaskPixels("cohort-old-evidence-expired", baseline.read.candidates.get(0).regions.get(0));
                absentMaskPixels("cohort-old-sibling-expired", baseline.read.candidates.get(1).regions.get(0));
                detail.put("selectedRequest", input.identity).put("oldEvidenceDeadline", oldDeadline)
                        .put("oldEvidenceExpiredBeforeInitialNodeWork", true).put("eventForwardingDetached", true)
                        .put("initialNodeWorkReleasedAtMillis", SystemClock.uptimeMillis());
                openRelease.countDown();
                if (change == CohortChange.SHARED_RESIZE || change == CohortChange.SHARED_STOP) {
                    assertTrue("Cohort held before actual shared post-A query", shared.await(8, TimeUnit.SECONDS));
                    assertTrue(h.nativeObservations.stream().noneMatch(frame -> frame.requestIdentity == input.identity));
                    detail.put("noCohortObservationBeforeActualSharedQuery", true);
                    if (change == CohortChange.SHARED_RESIZE) {
                        long generation = input.treeGeneration();
                        activity.onActivity(value -> value.getWindow().setLayout(window.displayWidth, window.displayHeight / 2));
                        await("Actual same-id cohort window resized", () -> {
                            AccessibilityWindowReader.Window actual = h.controller.observeWindow(context.getPackageName(), window.id);
                            return actual != null && actual.id == window.id && !actual.same(window);
                        });
                        assertFalse(input.cancelled()); assertEquals(generation, input.treeGeneration());
                        detail.put("actualSameIdResizeObserved", true).put("treeGenerationUnchanged", true);
                    } else { main(h.controller::stop); assertTrue(input.cancelled()); }
                    sharedRelease.countDown();
                } else {
                    assertTrue("Cohort publishes fully confirmed A while B remains unfinished", observed.await(8, TimeUnit.SECONDS));
                    if (observerFailure.get() != null) throw new AssertionError("Actual cohort observer failed", observerFailure.get());
                    NativeTextFrame early = first.get(); assertNotNull(early); assertEquals(input.identity, early.requestIdentity);
                    if (change != CohortChange.REENTRANT_STOP)
                        assertTrue(h.nativeCosts.stream().noneMatch(cost -> cost.request == input.identity));
                    detail.put("earlyNodeKey", early.read.candidates.get(0).nodeKey)
                            .put("earlyCandidateDeadline", early.candidateValidUntilMillis(early.read.candidates.get(0)));
                    if (change == CohortChange.FAILED_B) {
                        activity.onActivity(value -> value.second.setText("ordinary safe caption"));
                        assertFalse(input.cancelled()); detail.put("secondLiteralChangedAfterAObservation", true);
                    }
                    observationRelease.countDown();
                }
                await("One real cohort terminal", () -> h.nativeCosts.stream().anyMatch(cost -> cost.request == input.identity));
                NativeReadCost cost = h.nativeCosts.stream().filter(value -> value.request == input.identity).findFirst().get();
                assertTrue("Actual terminal observer has returned its serial registry snapshot", terminalObserved.await(8, TimeUnit.SECONDS));
                if (observerFailure.get() != null) throw new AssertionError("Actual cohort observer failed", observerFailure.get());
                assertNotNull(terminalRegistry.get()); detail.put("registryAtActualTerminalBeforeAdmission", terminalRegistry.get());
                assertEquals(1, h.nativeCosts.stream().filter(value -> value.request == input.identity).count());
                assertEquals(1, h.nativeCostStarts.stream().filter(value -> value.request == input.identity).count());
                assertEquals(0, cost.costs.get(NativeReadCost.Stage.CHILD_FETCH.ordinal()).calls);
                assertEquals(1, cost.costs.get(NativeReadCost.Stage.DISPOSE.ordinal()).calls);
                if (change == CohortChange.NONE || change == CohortChange.FAILED_B) {
                    assertTrue("Finite cohort pass must next attempt FULL, including failed B", nextFull.await(8, TimeUnit.SECONDS));
                    assertEquals(input.identity + 1, successor.get().identity);
                    detail.put("nextFullRequest", successor.get().identity).put("nextFullNotDirectRefill", true);
                }
                if (change == CohortChange.NONE) {
                    assertEquals(NativeReadCost.Terminal.SUCCESS, cost.terminal);
                    await("Exactly two selected facts admitted by the genuine terminal", () -> h.nativeFrames.stream().anyMatch(frame -> frame.requestIdentity == input.identity));
                    NativeTextFrame terminal = h.nativeFrames.stream().filter(frame -> frame.requestIdentity == input.identity).findFirst().get();
                    assertEquals(NativeTextResult.Coverage.POSITIVE_NODES_ONLY, terminal.read.coverage); assertEquals(2, terminal.read.candidates.size());
                    NativeTextResult.Candidate a = first.get().read.candidates.get(0);
                    assertSame(a, terminal.read.candidates.stream().filter(candidate -> candidate.nodeKey.equals(a.nodeKey)).findFirst().get());
                    assertEquals(first.get().candidateValidUntilMillis(a), terminal.candidateValidUntilMillis(a));
                    assertTrue(terminal.read.confirmationStartedAtMillis >= terminal.read.classificationCompletedAtMillis + 48);
                    assertEquals(1, cost.costs.get(NativeReadCost.Stage.PROVIDER_OPEN.ordinal()).calls);
                    assertEquals(6, cost.costs.get(NativeReadCost.Stage.PROVIDER_CURRENT.ordinal()).calls);
                    assertEquals(2, cost.costs.get(NativeReadCost.Stage.NODE_REFRESH.ordinal()).calls);
                    assertEquals(2, cost.costs.get(NativeReadCost.Stage.GLYPH_QUERY.ordinal()).calls);
                    assertEquals(7, rootsAtTerminal.get() - rootsBeforeCohort.get());
                    detail.put("selectedRootsBeforeCohort", rootsBeforeCohort.get()).put("selectedRootsAtActualTerminal", rootsAtTerminal.get())
                            .put("actualSelectedRootAcquisitions", rootsAtTerminal.get() - rootsBeforeCohort.get())
                            .put("bothFactsKeepIndependentAges", true);
                    for (NativeTextResult.Candidate candidate : terminal.read.candidates) {
                        SceneEngine.Region region = candidate.regions.get(0);
                        composed("cohort-confirmed-node", (int) ((region.left + region.right) / 2), (int) ((region.top + region.bottom) / 2), COVER);
                    }
                    assertFalse(((CensorEngine<?>) groupField(h.controller.host(), "engine")).imageTextSuppressed());
                } else {
                    assertNotEquals(NativeReadCost.Terminal.SUCCESS, cost.terminal); assertNotEquals(NativeReadCost.Terminal.RESOURCE_FAILURE, cost.terminal);
                    assertTrue(h.nativeFrames.stream().noneMatch(frame -> frame.requestIdentity == input.identity));
                    if (change == CohortChange.FAILED_B) {
                        assertEquals(NativeReadCost.Terminal.FAILURE, cost.terminal);
                        NativeTextFrame early = first.get(); NativeTextResult.Candidate a = early.read.candidates.get(0);
                        assertEquals(1, h.nativeObservations.stream().filter(frame -> frame.requestIdentity == input.identity).count());
                        JSONArray registry = detail.getJSONArray("registryAtActualTerminalBeforeAdmission"); assertEquals(2, registry.length());
                        int live = 0, retired = 0;
                        for (int i = 0; i < registry.length(); i++) {
                            JSONObject entry = registry.getJSONObject(i);
                            assertEquals(baseline.source.sequence, entry.getLong("sourceSequence"));
                            assertEquals(entry.getString("nodeKey").equals(a.nodeKey), entry.getBoolean("refreshable"));
                            if (entry.getBoolean("refreshable")) live++; else retired++;
                        }
                        assertEquals(1, live); assertEquals(1, retired); detail.put("onlyFailedBAddressRetired", true);
                        await("Failed B cannot renew A beyond its original deadline", () -> SystemClock.uptimeMillis() > early.candidateValidUntilMillis(a));
                        try (CensorEngine<PixelFrame>.RenderView view = h.controller.host().acquireRenderView()) {
                            assertNotNull(view); assertTrue(view.snapshot.groups.isEmpty());
                        }
                        absentMaskPixels("cohort-failed-b-expired-a", a.regions.get(0));
                        detail.put("failedParentLeavesOnlyOriginalFiniteA", true).put("failedAWithdrawalObservedAtMillis", SystemClock.uptimeMillis());
                    } else if (change == CohortChange.REENTRANT_STOP) {
                        assertEquals(NativeReadCost.Terminal.CANCELLED, cost.terminal);
                        assertEquals(1, cost.costs.get(NativeReadCost.Stage.GLYPH_QUERY.ordinal()).calls);
                        detail.put("reentrantStopPreventsBGlyph", true);
                    } else assertTrue(h.nativeObservations.stream().noneMatch(frame -> frame.requestIdentity == input.identity));
                }
                main(h.controller::stop); nextRelease.countDown(); assertBinding(); passed = true;
            } finally {
                openRelease.countDown(); sharedRelease.countDown(); observationRelease.countDown(); nextRelease.countDown();
                main(() -> h.service.controller = h.controller);
            }
        } catch (Exception | Error failure) { primary = failure; throw failure; }
        finally { writeNativeControl(h, "COHORT_" + change.name(), passed, detail, primary, "bounded-native-address-cohort-control"); }
    }

    private static JSONObject heldCohortReader(Harness h, NativeTextFrame frame) throws Exception {
        Object reader = groupField(h.controller.host(), "textReader"), operation = groupField(reader, "activeOperation");
        assertEquals(true, groupField(reader, "reading")); assertNull(groupField(operation, "treeRoot"));
        assertEquals(frame.requestIdentity, ((CensorEngine.NativeTextInput) groupField(operation, "input")).identity);
        java.util.Set<?> owned = (java.util.Set<?>) groupField(operation, "owned"); assertEquals(2, owned.size());
        List<?> entries = (List<?>) groupField(groupField(reader, "identities"), "entries"); assertEquals(2, entries.size());
        JSONArray basis = new JSONArray();
        for (Object entry : entries) {
            Object node = groupField(entry, "node"), historical = groupField(entry, "basis");
            assertNotNull(historical); assertFalse("Registry handles remain borrowed, never operation-owned", owned.contains(node));
            assertEquals(true, groupField(entry, "refreshable"));
            basis.put(new JSONObject().put("token", groupField(entry, "token"))
                    .put("sourceSequence", ((SceneEngine.Source) groupField(historical, "source")).sequence)
                    .put("originalValidUntilMillis", groupField(historical, "originalValidUntilMillis")));
        }
        assertFalse(h.controller.managedResourcesDrained());
        return new JSONObject().put("request", frame.requestIdentity).put("observedAtMillis", SystemClock.uptimeMillis())
                .put("reading", true).put("treeRootAbsent", true).put("ownedNodeHandles", owned.size())
                .put("borrowedRegistryHandlesPreserved", true).put("basis", basis);
    }

    @Test public void confirmedNativeGroupWaitsForActualSharedWindowQueryAndRejectsResize() throws Exception {
        sharedGroupQueryAdverse(true);
    }

    @Test public void confirmedNativeGroupWaitsForActualSharedWindowQueryAndRejectsStop() throws Exception {
        sharedGroupQueryAdverse(false);
    }

    private void sharedGroupQueryAdverse(boolean resize) throws Exception {
        assertNotNull("Exclusive current native_group_namespace is required", groupEvidenceDirectory);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<CensorEngine.NativeTextInput> held = new AtomicReference<>();
        JSONObject detail = new JSONObject(); boolean passed = false; Throwable primary = null;
        Harness h = new Harness(resize ? "confirmed-group-shared-resize" : "confirmed-group-shared-stop", false,
                () -> { }, () -> { }, (input, boundary) -> {
                    if (boundary == NativeTextReader.Boundary.GROUP_END_BEFORE_FINAL_NODE_REFRESH
                            && held.compareAndSet(null, input)) { entered.countDown(); hold(release); }
                });
        try (Harness owned = h; ActivityScenario<NativeTextFixtureActivity> activity = launchGroupWindow()) {
            detail.put("fixtureReadiness", prepareGroupWindow(activity)); AccessibilityWindowReader.Window original = h.window();
            h.start(original, 10, config(2, false, .3f), text(), false);
            try {
                assertTrue("Held BEFORE the actual shared post-A scope query", entered.await(8, TimeUnit.SECONDS));
                CensorEngine.NativeTextInput input = held.get(); assertNotNull(input);
                assertTrue(h.nativeObservations.isEmpty()); assertTrue(h.nativeFrames.isEmpty());
                detail.put("selectedRequest", input.identity).put("heldBeforeActualSharedQuery", true)
                        .put("sourcesBeforeRelease", 0).put("inputCancelledBeforeAction", input.cancelled());
                if (resize) {
                    long generation = input.treeGeneration();
                    // Isolate the real query from event-driven cancellation in this debug guest only.
                    main(() -> h.service.controller = null);
                    activity.onActivity(value -> value.getWindow().setLayout(original.displayWidth, original.displayHeight / 2));
                    await("Actual same-id bounds changed before the shared query", () -> {
                        AccessibilityWindowReader.Window actual = h.controller.observeWindow(context.getPackageName(), original.id);
                        return actual != null && actual.id == original.id && !actual.same(original);
                    });
                    assertFalse(input.cancelled()); assertEquals(generation, input.treeGeneration());
                    detail.put("actualSameIdResizeObserved", true).put("eventForwardingDetached", true)
                            .put("treeGenerationUnchanged", true).put("inputNotCancelledBeforeQuery", true);
                } else {
                    main(h.controller::stop); assertTrue(input.cancelled()); detail.put("actualStopBeforeQuery", true);
                }
                release.countDown();
                await("Actual selected parent terminal after the rejecting shared boundary", () -> h.nativeCosts.stream().anyMatch(c -> c.request == input.identity));
                main(h.controller::stop);
                NativeReadCost cost = h.nativeCosts.stream().filter(c -> c.request == input.identity).findFirst().get();
                assertNotEquals(NativeReadCost.Terminal.SUCCESS, cost.terminal);
                assertNotEquals(NativeReadCost.Terminal.RESOURCE_FAILURE, cost.terminal);
                assertEquals(1, h.nativeCosts.stream().filter(c -> c.request == input.identity).count());
                assertEquals(1, h.nativeCostStarts.stream().filter(c -> c.request == input.identity).count());
                assertNoNativeAdmission(h); detail.put("noObservationAfterRejectedQuery", true); passed = true;
            } finally { release.countDown(); main(() -> h.service.controller = h.controller); }
        } catch (Exception | Error failure) { primary = failure; throw failure; }
        finally { writeGroupControl(h, resize ? "SHARED_RESIZE" : "SHARED_STOP", passed, detail, primary); }
    }

    @Test public void confirmedNativeGroupPaintsBeforeRemainingParentWorkAndKeepsItsOwnAge() throws Exception {
        assertNotNull("Exclusive current native_group_namespace is required", groupEvidenceDirectory);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<NativeTextFrame> first = new AtomicReference<>();
        AtomicReference<Throwable> observerFailure = new AtomicReference<>();
        JSONObject detail = new JSONObject(); boolean passed = false; Throwable primary = null;
        Harness h = new Harness("confirmed-group-first-paint", false, () -> { });
        h.nativeEvidenceControl.set(receipt -> {
            NativeTextFrame frame = h.nativeObservations.stream().filter(value -> value.source == receipt.source).findFirst().orElse(null);
            if (frame != null && first.compareAndSet(null, frame)) {
                try { detail.put("heldReader", heldGroupReader(h, frame)); }
                catch (Exception | Error failure) { observerFailure.set(failure); entered.countDown(); return; }
                entered.countDown(); hold(release);
            }
        });
        try (Harness owned = h; ActivityScenario<NativeTextFixtureActivity> activity = launchGroupWindow()) {
            detail.put("fixtureReadiness", prepareGroupWindow(activity)); AccessibilityWindowReader.Window window = h.window();
            AtomicReference<int[]> points = new AtomicReference<>();
            activity.onActivity(value -> {
                int[] a = independentTextPoint(value.first), b = independentTextPoint(value.second);
                points.set(new int[]{a[0], a[1], b[0], b[1]});
            });
            AccessibilityCensorController.Configuration base = h.configuration(window, 10, config(2, false, .3f), text(), false);
            main(() -> h.controller.start(new AccessibilityCensorController.Configuration(base.token, base.window, base.detector,
                    base.quality, base.text, base.semanticText, base.exactText, ProtectionScope.normal(h.taps.scope()),
                    base.appearance, base.paintPolicy, base.artworkRevision)));
            try {
                assertTrue("Real confirmed-A observation before remaining geometry/terminal work", entered.await(8, TimeUnit.SECONDS));
                if (observerFailure.get() != null) throw new AssertionError("Actual held-reader snapshot failed", observerFailure.get());
                NativeTextFrame early = first.get(); assertNotNull(early); assertEquals(1, early.observationOrdinal);
                assertEquals(1, early.read.candidates.size()); assertEquals(NativeTextResult.Coverage.POSITIVE_NODES_ONLY, early.read.coverage);
                assertEquals(1, h.nativeObservations.size()); assertTrue(h.nativeFrames.isEmpty()); assertTrue(h.nativeCosts.isEmpty());
                assertEquals(1, h.nativeCostStarts.size()); assertFalse(h.controller.managedResourcesDrained());
                NativeTextResult.Candidate a = early.read.candidates.get(0); assertTrue(a.exactGeometry);
                long deadline = early.candidateValidUntilMillis(a);
                int[] point = points.get(); assertNotNull(point);
                AtomicReference<Bitmap> retained = new AtomicReference<>();
                try {
                    await("Actual early-A pixels and hit geometry before its unchanged deadline", Math.max(1, deadline - SystemClock.uptimeMillis()), () -> {
                        Bitmap pixels = ui.takeScreenshot(); if (pixels == null) return false;
                        Bitmap previous = retained.getAndSet(pixels); if (previous != null) previous.recycle();
                        long now = SystemClock.uptimeMillis();
                        return now < deadline && pixels.getPixel(point[0], point[1]) == COVER
                                && pixels.getPixel(point[2], point[3]) != COVER
                                && h.taps.matchesPoint(point[0], point[1], window.displayWidth, window.displayHeight, now)
                                && !h.taps.matchesPoint(point[2], point[3], window.displayWidth, window.displayHeight, now)
                                && h.posts.stream().anyMatch(post -> post.acceptedPaint != null && post.acceptedPaint.members.size() == 1
                                    && post.acceptedPaint.members.get(0).evidenceSource == early.source
                                    && post.acceptedPaint.members.get(0).nativeCoverage == NativeTextResult.Coverage.POSITIVE_NODES_ONLY);
                    });
                    detail.put("literalPixelsPassedBeforeRelease", true).put("literalHitMapPassedBeforeRelease", true)
                            .put("independentPoints", new JSONArray().put(point[0]).put(point[1]).put(point[2]).put(point[3]))
                            .put("pixelsObservedAtMillis", SystemClock.uptimeMillis()).put("originalCandidateDeadline", deadline);
                } finally {
                    Bitmap pixels = retained.get(); if (pixels != null) try { save(pixels, "confirmed-group-held-a"); } finally { pixels.recycle(); }
                }
                assertTrue(h.nativeCosts.isEmpty()); assertTrue(h.nativeFrames.isEmpty()); assertEquals(1, release.getCount());
                assertEquals(1, h.nativeEvidence.size()); CensorEngine.EvidenceReceipt receipt = h.nativeEvidence.get(0);
                assertEquals(NativeTextResult.Coverage.POSITIVE_NODES_ONLY, receipt.nativeCoverage);
                assertEquals(1, receipt.subjects.size()); assertTrue(receipt.subjects.get(0).witnesses.isEmpty());
                assertFalse(receipt.subjects.get(0).confirmedForConsumption);
                CensorEngine.EvidenceClaim claim = h.controller.claimEvidence(receipt, new Object()); assertNotNull(claim); assertTrue(claim.subjects.isEmpty());
                assertFalse(h.controller.dwellPresent(h.controller.viewportCoverage(), receipt));
                assertFalse(((CensorEngine<?>) groupField(h.controller.host(), "engine")).imageTextSuppressed());
                detail.put("noParentTerminalBeforeRelease", true).put("remainingParentWorkHeldNotProviderInflight", true)
                        .put("noConsumptionWitnessDwellOrOcrAuthority", true)
                        .put("selectedRequest", early.requestIdentity).put("releasedAtMillis", SystemClock.uptimeMillis());
                release.countDown();
                await("Genuine same-parent final admission with both groups", () -> h.nativeFrames.stream().anyMatch(frame -> frame.requestIdentity == early.requestIdentity));
                NativeTextFrame complete = h.nativeFrames.stream().filter(frame -> frame.requestIdentity == early.requestIdentity).findFirst().get();
                assertEquals(NativeTextResult.Coverage.COMPLETE_TREE, complete.read.coverage); assertEquals(2, complete.read.candidates.size());
                NativeTextResult.Candidate finalA = complete.read.candidates.stream().filter(candidate -> candidate.nodeKey.equals(a.nodeKey)).findFirst().get();
                assertSame("Parent completion reuses immutable A facts, never later B's age", a, finalA);
                assertEquals(deadline, complete.candidateValidUntilMillis(finalA));
                NativeReadCost cost = h.nativeCosts.stream().filter(value -> value.request == early.requestIdentity).findFirst().get();
                assertEquals(NativeReadCost.Terminal.SUCCESS, cost.terminal);
                assertEquals("Two groups retain six existing production current-scope queries", 6,
                        cost.costs.get(NativeReadCost.Stage.PROVIDER_CURRENT.ordinal()).calls);
                assertEquals(1, h.nativeCosts.stream().filter(value -> value.request == early.requestIdentity).count());
                main(h.controller::stop); detail.put("sameParentFinalKeepsAIdentityAndDeadline", true).put("unchangedProductionCurrentQueries", 6); passed = true;
            } finally { release.countDown(); }
        } catch (Exception | Error failure) { primary = failure; throw failure; }
        finally { writeGroupControl(h, "EARLY_PAINT", passed, detail, primary); }
    }

    @Test public void confirmedNativeGroupReentrantStopPreventsLaterGeometryAndFalseDrain() throws Exception {
        assertNotNull("Exclusive current native_group_namespace is required", groupEvidenceDirectory);
        CountDownLatch stopped = new CountDownLatch(1); AtomicReference<NativeTextFrame> first = new AtomicReference<>();
        AtomicReference<Throwable> observerFailure = new AtomicReference<>();
        JSONObject detail = new JSONObject(); boolean passed = false; Throwable primary = null;
        Harness h = new Harness("confirmed-group-reentrant-stop", false, () -> { });
        h.nativeObservationControl.set(frame -> {
            if (frame.observationOrdinal > 0 && first.compareAndSet(null, frame)) {
                try { detail.put("heldReader", heldGroupReader(h, frame)); }
                catch (Exception | Error failure) { observerFailure.set(failure); stopped.countDown(); return; }
                main(h.controller::stop); stopped.countDown();
            }
        });
        try (Harness owned = h; ActivityScenario<NativeTextFixtureActivity> activity = launchGroupWindow()) {
            detail.put("fixtureReadiness", prepareGroupWindow(activity)); h.start(h.window(), 10, config(2, false, .3f), text(), false);
            assertTrue("Stop from the real A observer", stopped.await(8, TimeUnit.SECONDS));
            if (observerFailure.get() != null) throw new AssertionError("Actual held-reader snapshot failed", observerFailure.get());
            NativeTextFrame early = first.get(); assertNotNull(early);
            await("Actual cancellation terminal after observation reentrancy", () -> h.nativeCosts.stream().anyMatch(cost -> cost.request == early.requestIdentity));
            NativeReadCost cost = h.nativeCosts.stream().filter(value -> value.request == early.requestIdentity).findFirst().get();
            assertEquals(NativeReadCost.Terminal.CANCELLED, cost.terminal);
            assertEquals("B's final glyph query must not run after the observer stops the owner", 1,
                    cost.costs.get(NativeReadCost.Stage.GLYPH_QUERY.ordinal()).calls);
            assertEquals(1, h.nativeObservations.size()); assertTrue(h.nativeFrames.isEmpty());
            assertEquals(1, h.nativeCosts.stream().filter(value -> value.request == early.requestIdentity).count());
            detail.put("selectedRequest", early.requestIdentity).put("reentrantStopPassed", true).put("laterGlyphQueryPrevented", true); passed = true;
        } catch (Exception | Error failure) { primary = failure; throw failure; }
        finally { writeGroupControl(h, "REENTRANT_STOP", passed, detail, primary); }
    }

    private ActivityScenario<NativeTextFixtureActivity> launchGroupWindow() {
        return ActivityScenario.launch(NativeTextFixtureActivity.class,
                ActivityOptions.makeCustomAnimation(context, 0, 0).toBundle());
    }

    private JSONObject prepareGroupWindow(ActivityScenario<NativeTextFixtureActivity> activity) throws Exception {
        CountDownLatch committed = new CountDownLatch(1);
        AtomicBoolean registered = new AtomicBoolean(); AtomicLong layoutAt = new AtomicLong(), committedAt = new AtomicLong();
        AtomicReference<android.view.ViewTreeObserver.OnDrawListener> listener = new AtomicReference<>();
        activity.onActivity(value -> {
            value.getWindow().setFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN, android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);
            value.getWindow().setDecorFitsSystemWindows(false); if (value.getActionBar() != null) value.getActionBar().hide();
            value.first.setText("send nudes"); value.second.setText("touch yourself");
            View decor = value.getWindow().getDecorView(); assertTrue("Owned fixture uses actual hardware frames", decor.isHardwareAccelerated());
            android.view.ViewTreeObserver.OnDrawListener draw = () -> {
                if (value.first.isLaidOut() && value.second.isLaidOut() && value.first.getLayout() != null && value.second.getLayout() != null
                        && value.first.getWidth() > 0 && value.first.getHeight() > 0 && value.second.getWidth() > 0 && value.second.getHeight() > 0
                        && "send nudes".contentEquals(value.first.getText()) && "touch yourself".contentEquals(value.second.getText())
                        && registered.compareAndSet(false, true)) {
                    layoutAt.set(SystemClock.uptimeMillis());
                    decor.getViewTreeObserver().registerFrameCommitCallback(() -> {
                        committedAt.set(SystemClock.uptimeMillis()); committed.countDown();
                    });
                    decor.postInvalidateOnAnimation();
                }
            };
            listener.set(draw); decor.getViewTreeObserver().addOnDrawListener(draw); decor.invalidate();
        });
        try { assertTrue("Actual literal layout followed by a new hardware-frame commit", committed.await(8, TimeUnit.SECONDS)); }
        finally {
            activity.onActivity(value -> {
                android.view.ViewTreeObserver.OnDrawListener draw = listener.get();
                if (draw != null) value.getWindow().getDecorView().getViewTreeObserver().removeOnDrawListener(draw);
            });
        }
        instrumentation.waitForIdleSync();
        long drained = SystemClock.uptimeMillis(); assertTrue(layoutAt.get() > 0 && committedAt.get() >= layoutAt.get() && drained >= committedAt.get());
        return new JSONObject().put("explicitNoAnimationLaunch", true).put("literalLayoutAtMillis", layoutAt.get())
                .put("hardwareFrameCommittedAtMillis", committedAt.get()).put("targetMainQueueDrainedAtMillis", drained)
                .put("physicalPixelClockKnown", false).put("atomicWindowOrEventSnapshot", false);
    }

    private static int[] independentTextPoint(android.widget.TextView view) {
        int[] origin = new int[2]; view.getLocationOnScreen(origin); android.text.Layout layout = view.getLayout(); assertNotNull(layout);
        android.graphics.Paint.FontMetrics metrics = view.getPaint().getFontMetrics();
        return new int[]{Math.round(origin[0] + view.getTotalPaddingLeft() + layout.getPrimaryHorizontal(0)
                    + view.getPaint().measureText(view.getText().toString(), 0, 1) / 2),
                Math.round(origin[1] + view.getTotalPaddingTop() + layout.getLineBaseline(0) + (metrics.ascent + metrics.descent) / 2)};
    }

    private static Object groupField(Object owner, String name) throws Exception {
        java.lang.reflect.Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }

    /** Called on the real reader executor while its observation callback still owns the parent. */
    private static JSONObject heldGroupReader(Harness h, NativeTextFrame frame) throws Exception {
        Object reader = groupField(h.controller.host(), "textReader"); assertNotNull(reader);
        Object operation = groupField(reader, "activeOperation"); assertNotNull(operation); assertEquals(true, groupField(reader, "reading"));
        CensorEngine.NativeTextInput input = (CensorEngine.NativeTextInput) groupField(operation, "input");
        assertEquals(frame.requestIdentity, input.identity); assertFalse(input.cancelled());
        java.util.Set<?> owned = (java.util.Set<?>) groupField(operation, "owned");
        assertTrue(owned.contains(groupField(operation, "treeRoot"))); assertTrue(owned.size() >= 3);
        Object cache = groupField(reader, "identities"); List<?> entries = (List<?>) groupField(cache, "entries");
        int admitted = 0; for (Object entry : entries) if (groupField(entry, "basis") != null || Boolean.TRUE.equals(groupField(entry, "refreshable"))) admitted++;
        assertEquals(2, entries.size()); assertEquals(0, admitted);
        long[] calls = (long[]) groupField(operation, "costCalls"); assertEquals(1, calls[NativeReadCost.Stage.GLYPH_QUERY.ordinal()]);
        return new JSONObject().put("request", input.identity).put("observedAtMillis", SystemClock.uptimeMillis())
                .put("reading", true).put("rootStillOwned", true).put("ownedNodeHandles", owned.size())
                .put("identityEntries", entries.size()).put("admittedAddresses", admitted).put("finalGlyphQueries", 1);
    }

    private void writeGroupControl(Harness h, String control, boolean passed, JSONObject detail, Throwable primary) throws Exception {
        writeNativeControl(h, control, passed, detail, primary, "confirmed-native-group-control");
    }

    private void writeNativeControl(Harness h, String control, boolean passed, JSONObject detail, Throwable primary, String kind) throws Exception {
        try {
            JSONArray frames = new JSONArray(), starts = new JSONArray(), costs = new JSONArray();
            for (NativeTextFrame frame : h.allNativeFrames()) frames.put(new JSONObject(com.subhub.app.diagnostics.ReplacementEngineTelemetry.nativeRead(1, frame).substring("REPLACEMENT_NATIVE ".length())));
            if (kind.equals("bounded-native-address-cohort-control")) {
                JSONArray identities = new JSONArray();
                for (NativeTextFrame frame : h.allNativeFrames()) {
                    JSONArray nodes = new JSONArray();
                    for (NativeTextResult.Candidate candidate : frame.read.candidates) nodes.put(candidate.nodeKey);
                    identities.put(new JSONObject().put("sequence", frame.source.sequence).put("request", frame.requestIdentity)
                            .put("observationOrdinal", frame.observationOrdinal).put("nodeKeys", nodes));
                }
                detail.put("frameIdentities", identities);
            }
            for (NativeReadCost.Start value : h.nativeCostStarts) starts.put(new JSONObject(com.subhub.app.diagnostics.ReplacementEngineTelemetry.nativeCostStart(1, value).substring("REPLACEMENT_NATIVE_COST_START ".length())));
            for (NativeReadCost value : h.nativeCosts) costs.put(new JSONObject(com.subhub.app.diagnostics.ReplacementEngineTelemetry.nativeCost(1, value).substring("REPLACEMENT_NATIVE_COST ".length())));
            JSONObject receipt = new JSONObject().put("schemaVersion", 1).put("kind", kind)
                    .put("runIdentity", InstrumentationRegistry.getArguments().getString("native_group_namespace"))
                    .put("case", testName.getMethodName()).put("control", control).put("pid", Process.myPid())
                    .put("clockDomain", "UPTIME_MILLIS").put("recordedAtMillis", SystemClock.uptimeMillis())
                    .put("bodyPassed", passed).put("managedDrain", h.controller.managedResourcesDrained())
                    .put("rawFrameCount", frames.length()).put("frames", frames).put("rawStartCount", starts.length()).put("starts", starts)
                    .put("rawTerminalCount", costs.length()).put("terminals", costs).put("detail", detail)
                    .put("physicalWindowAtomicityKnown", false).put("pixelClockKnown", false).put("naturalLatencyBenefitQualified", false)
                    .put("pixelPerformanceAcceptance", false);
            try (FileOutputStream stream = new FileOutputStream(new File(directory(), "native-surface-metrics-" + System.nanoTime() + ".json"))) {
                stream.write(receipt.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception | Error failure) {
            if (primary == null) throw failure;
            if (primary != failure) primary.addSuppressed(failure);
        }
    }

    private static void assertNoNativeAdmission(Harness h) {
        assertTrue(h.nativeFrames.isEmpty()); assertTrue(h.nativeObservations.isEmpty());
        assertTrue(h.nativeEvidence.isEmpty()); assertTrue(h.batches.isEmpty());
        assertTrue(h.posts.stream().noneMatch(p -> p.acceptedPaint != null && !p.acceptedPaint.members.isEmpty()));
        assertEquals(0, h.models.get());
        assertEquals(0, h.traces.stream().filter(t -> t.stage == CensorEngine.Stage.CAPTURE_DISPATCHED).count());
        AccessibilityCensorController.WindowCoverage coverage = h.controller.viewportCoverage();
        assertFalse(coverage.windowQualified); assertTrue(coverage.regions.isEmpty());
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
        // Existing acquisition assertions use terminal admissions; never count observations as reads.
        final List<NativeTextFrame> nativeObservations = new CopyOnWriteArrayList<>();
        final AtomicReference<Consumer<NativeTextFrame>> nativeObservationControl = new AtomicReference<>();
        final AtomicReference<Consumer<CensorEngine.EvidenceReceipt>> nativeEvidenceControl = new AtomicReference<>();
        final AtomicReference<Consumer<NativeReadCost>> nativeCostControl = new AtomicReference<>();
        final CensorTapTracker taps = new CensorTapTracker();
        final List<NativeReadCost.Start> nativeCostStarts = new CopyOnWriteArrayList<>();
        final List<NativeReadCost> nativeCosts = new CopyOnWriteArrayList<>();
        final List<CensorEngine.EvidenceReceipt> nativeEvidence = new CopyOnWriteArrayList<>();
        final List<SnapshotCensorSurface.Post> posts = new CopyOnWriteArrayList<>();
        final AtomicInteger models = new AtomicInteger();
        final AtomicBoolean markerPixels = new AtomicBoolean();
        final List<JSONObject> captureMetadata = new CopyOnWriteArrayList<>();
        final List<ScrollMotionLedger.Receipt> scrollReceipts = new CopyOnWriteArrayList<>();
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
        final FixtureServiceConnection.Lease<BoundCensorFixtureService> lease;
        final List<JSONObject> windowWaits = new java.util.ArrayList<>();
        AccessibilityCensorController controller;
        Harness(String label, boolean actualModel, Runnable acquired) {
            this(label, actualModel, acquired, () -> { });
        }
        Harness(String label, boolean actualModel, Runnable acquired, Runnable nativeSelected) {
            this(label, actualModel, acquired, nativeSelected, (input, boundary) -> { });
        }
        Harness(String label, boolean actualModel, Runnable acquired, Runnable nativeSelected,
                AccessibilityCensorController.NativeBoundaryControl nativeBoundary) {
            this.label = label; assertBinding(); lease = boundLease; service = lease.service;
            AccessibilityCensorController.Observer observer = new AccessibilityCensorController.Observer() {
                @Override public void sourcePrepared(CensorEngine.CaptureRequest request, PixelFrame frame) {
                    try { captureMetadata.add(new JSONObject().put("request", request.identity).put("epoch", request.epoch)
                            .put("geometry", request.geometry).put("reportedAtMillis", frame.reportedUptimeMillis)
                            .put("callbackAtMillis", frame.callbackUptimeMillis).put("kind", frame.kind.name())); }
                    catch (org.json.JSONException failure) { throw new IllegalStateException(failure); }
                }
                @Override public void scrollReceived(ScrollMotionLedger.Receipt receipt) { scrollReceipts.add(receipt); }
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
                @Override public void nativeTextAccepted(NativeTextFrame frame) {
                    if (frame.observationOrdinal == 0) nativeFrames.add(frame); else nativeObservations.add(frame);
                    Consumer<NativeTextFrame> control = nativeObservationControl.get();
                    if (control != null) control.accept(frame);
                }
                @Override public void nativeReadCostStarted(NativeReadCost.Start value) { nativeCostStarts.add(value); }
                @Override public void nativeReadCost(NativeReadCost value) {
                    nativeCosts.add(value); Consumer<NativeReadCost> control = nativeCostControl.get(); if (control != null) control.accept(value);
                }
                @Override public void evidenceAccepted(CensorEngine.EvidenceReceipt receipt) {
                    nativeEvidence.add(receipt); Consumer<CensorEngine.EvidenceReceipt> control = nativeEvidenceControl.get();
                    if (control != null) control.accept(receipt);
                }
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
                        taps, observer, (app, lane, config, partition, fallback) -> {
                    modelOnMain.set(Looper.myLooper() == Looper.getMainLooper()); models.incrementAndGet();
                    if (actualModel) return VisualDetectorWorker.initialize(app, lane, config, partition, fallback);
                    modelThreshold.set(config.getConfidenceThreshold());
                    return new VisualDetectorWorker(lane, config, partition, new VisualDetectorWorker.Model() {
                        @Override public String provider() { return "CPU"; }
                        @Override public void configure(DetectorConfig value) { modelThreshold.set(value.getConfidenceThreshold()); }
                        @Override public VisualDetectorWorker.ModelOutput infer(Bitmap bitmap, int width, int height, int inputWidth,
                                int inputHeight, BooleanSupplier cancelled, Consumer<DetectionEngine.RunCancellation> binding) {
                            if (markerPixels.get()) {
                                long began = System.nanoTime(); int bw = bitmap.getWidth(), bh = bitmap.getHeight(); int[] colors = new int[bw * bh];
                                bitmap.getPixels(colors, 0, bw, 0, 0, bw, bh); List<Detection> detections = new java.util.ArrayList<>();
                                for (ScrollWitnessPixels.Box box : ScrollWitnessPixels.bands(colors, bw, bh, ScrollWitnessPixels.TARGET)) {
                                    int left = box.left * width / bw, top = box.top * height / bh;
                                    int right = (int) Math.ceil(box.right * width / (double) bw), bottom = (int) Math.ceil(box.bottom * height / (double) bh);
                                    detections.add(new Detection("FACE_FEMALE", "face", .9f, new BBox(left, top, right - left, bottom - top), false, true));
                                }
                                return new VisualDetectorWorker.ModelOutput(detections, true, cancelled.getAsBoolean(),
                                        new CensorEngine.WorkTiming(0, (System.nanoTime() - began) / 1_000_000L, 0));
                            }
                            Detection mask = new Detection("FACE_FEMALE", "face", .9f,
                                    new BBox(width / 3, height / 3, width / 3, height / 3), false, true);
                            return new VisualDetectorWorker.ModelOutput(Collections.singletonList(mask), true, cancelled.getAsBoolean(),
                                    new CensorEngine.WorkTiming(0, 0, 0));
                        }
                        @Override public void close() { assertNotEquals(Looper.getMainLooper(), Looper.myLooper()); }
                    });
                }, acquired, nativeSelected, nativeBoundary);
                service.controller = controller;
            });
        }
        AccessibilityWindowReader.Window window() throws Exception {
            return window(context.getPackageName());
        }
        AccessibilityWindowReader.Window window(String packageName) throws Exception {
            AtomicReference<AccessibilityWindowReader.Window> value = new AtomicReference<>();
            List<JSONObject> polls = new java.util.ArrayList<>(); AtomicInteger count = new AtomicInteger();
            int ordinal = windowWaits.size() + 1; long started = SystemClock.uptimeMillis();
            long waitEndedAt = -1;
            Throwable primary = null; JSONObject failureObservation = null;
            try {
                await("Actual focused application window", () -> {
                    long begin = SystemClock.uptimeMillis(); boolean currentBefore = BoundCensorFixtureService.CONNECTIONS.isCurrent(lease);
                    AccessibilityWindowReader.Window read = null; Throwable readFailure = null;
                    try {
                        read = controller.observeWindow(packageName, -1);
                    } catch (RuntimeException | Error failure) { readFailure = failure; throw failure; }
                    finally {
                        long end = SystemClock.uptimeMillis(); boolean currentAfter = BoundCensorFixtureService.CONNECTIONS.isCurrent(lease);
                        try {
                            JSONObject descriptor = read == null ? null : new JSONObject().put("id", read.id).put("packageName", read.packageName)
                                    .put("displayWidth", read.displayWidth).put("displayHeight", read.displayHeight).put("left", read.left).put("top", read.top)
                                    .put("width", read.width).put("height", read.height);
                            JSONObject row = new JSONObject().put("ordinal", count.incrementAndGet()).put("leaseGeneration", lease.generation)
                                    .put("startedAtMillis", begin).put("finishedAtMillis", end).put("leaseCurrentBefore", currentBefore)
                                    .put("leaseCurrentAfter", currentAfter).put("descriptor", nullable(descriptor))
                                    .put("readFailureType", nullable(readFailure == null ? null : readFailure.getClass().getName()));
                            if (polls.size() < 2048) polls.add(row); else polls.set(2047, row);
                        } catch (org.json.JSONException observationFailure) {
                            if (readFailure == null) throw new IllegalStateException(observationFailure);
                            readFailure.addSuppressed(observationFailure);
                        }
                    }
                    // Unchanged actual Reader/full-display predicate and15s deadline. Observations
                    // never supply a replacement descriptor or turn a later inventory into success.
                    if (read == null || read.left != 0 || read.top != 0 || read.width != read.displayWidth
                            || read.height != read.displayHeight) return false;
                    value.set(read); return true;
                });
                waitEndedAt = SystemClock.uptimeMillis();
                return value.get();
            } catch (Exception | Error failure) {
                waitEndedAt = SystemClock.uptimeMillis(); primary = failure;
                try { failureObservation = windowFailureObservation(this, ordinal, failure); }
                catch (Exception | Error diagnosticFailure) {
                    if (failure != diagnosticFailure) failure.addSuppressed(diagnosticFailure);
                }
                throw failure;
            } finally {
                try {
                    JSONObject receipt = new JSONObject().put("schemaVersion", 1).put("clockDomain", "UPTIME_MILLIS")
                            .put("label", label).put("pid", Process.myPid()).put("waitOrdinal", ordinal).put("leaseGeneration", lease.generation)
                            .put("targetPackage", packageName).put("startedAtMillis", started).put("maximumWaitMillis", 15000)
                            .put("waitEndedAtMillis", waitEndedAt)
                            .put("finishedAtMillis", SystemClock.uptimeMillis()).put("beforeTeardown", true).put("atomicFrameworkSnapshot", false)
                            .put("outcome", primary == null ? "SUCCESS" : "FAILURE").put("failureType", nullable(primary == null ? null : primary.getClass().getName()))
                            .put("rawPollCount", count.get()).put("storedPollCount", polls.size()).put("droppedPollCount", count.get() - polls.size())
                            .put("polls", new JSONArray(polls)).put("failureObservation", nullable(failureObservation)).put("pixelPerformanceAcceptance", false);
                    windowWaits.add(receipt);
                    try (FileOutputStream stream = new FileOutputStream(new File(directory(), "native-window-readiness-" + System.nanoTime() + ".json"))) {
                        stream.write(receipt.toString().getBytes(StandardCharsets.UTF_8));
                    }
                } catch (Exception | Error exportFailure) {
                    if (primary == null) throw exportFailure;
                    if (primary != exportFailure) primary.addSuppressed(exportFailure);
                }
            }
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
        List<NativeTextFrame> allNativeFrames() {
            List<NativeTextFrame> result = new java.util.ArrayList<>(nativeFrames); result.addAll(nativeObservations);
            result.sort(java.util.Comparator.comparingLong(frame -> frame.source.sequence)); return result;
        }
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
            JSONObject receipt = new JSONObject().put("schemaVersion", 2).put("label", label).put("pid", Process.myPid()).put("actualService", true)
                    .put("suppressionDisabled", true).put("legacyInactive", true).put("managedDrain", controller.managedResourcesDrained())
                    .put("rawTraceCount", traces.size()).put("traces", raw).put("rawPostCount", posts.size()).put("posts", posted)
                    .put("nativeAccepted", nativeFrames.size() + nativeObservations.size()).put("nativeTerminalAccepted", nativeFrames.size())
                    .put("rawNativeObservationCount", nativeObservations.size()).put("batches", batches.size()).put("pixelTimeKnown", false)
                    .put("prepared", prepared.get()).put("captureOnMain", captureOnMain.get())
                    .put("preparations", new JSONArray(preparations))
                    .put("windowRejections", new JSONArray(windowRejections))
                    .put("modelQualityAcceptance", false).put("pixelPerformanceAcceptance", false);
            JSONArray starts = new JSONArray(), costs = new JSONArray();
            for (NativeReadCost.Start value : nativeCostStarts) starts.put(new JSONObject(com.subhub.app.diagnostics.ReplacementEngineTelemetry.nativeCostStart(1, value).substring("REPLACEMENT_NATIVE_COST_START ".length())));
            for (NativeReadCost value : nativeCosts) costs.put(new JSONObject(com.subhub.app.diagnostics.ReplacementEngineTelemetry.nativeCost(1, value).substring("REPLACEMENT_NATIVE_COST ".length())));
            JSONArray observations = new JSONArray();
            for (NativeTextFrame value : nativeObservations) observations.put(new JSONObject(com.subhub.app.diagnostics.ReplacementEngineTelemetry.nativeRead(1, value).substring("REPLACEMENT_NATIVE ".length())));
            receipt.put("rawNativeCostStartCount", starts.length()).put("nativeCostStarts", starts)
                    .put("rawNativeCostCount", costs.length()).put("nativeCosts", costs).put("rawNativeEvidenceCount", nativeEvidence.size())
                    .put("nativeObservations", observations)
                    .put("nativeCostDiagnosticFailures", controller.host().nativeCostDiagnosticFailures());
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
        if (groupEvidenceDirectory != null) return groupEvidenceDirectory;
        File directory = context.getExternalFilesDir("censor-tap-fixtures-20261011"); assertNotNull(directory);
        assertTrue(directory.isDirectory() || directory.mkdirs()); return directory;
    }
    private void absentMaskPixels(String label, SceneEngine.Region region) throws Exception {
        AtomicReference<Bitmap> retained = new AtomicReference<>();
        try {
            await("Literal composed mask withdrawal " + label, 3000, () -> {
                Bitmap pixels = ui.takeScreenshot(); if (pixels == null) return false;
                Bitmap before = retained.getAndSet(pixels); if (before != null) before.recycle();
                int left = Math.max(0, (int) Math.floor(region.left) - 4), top = Math.max(0, (int) Math.floor(region.top) - 4);
                int right = Math.min(pixels.getWidth(), (int) Math.ceil(region.right) + 4);
                int bottom = Math.min(pixels.getHeight(), (int) Math.ceil(region.bottom) + 4);
                assertTrue(left < right && top < bottom);
                for (int y = top; y < bottom; y++) for (int x = left; x < right; x++)
                    if (pixels.getPixel(x, y) == COVER) return false;
                return true;
            });
        } finally {
            Bitmap pixels = retained.get(); if (pixels != null) try { save(pixels, label); } finally { pixels.recycle(); }
        }
    }
    private void save(Bitmap pixels, String label) throws Exception {
        try (FileOutputStream stream = new FileOutputStream(new File(directory(), "native-mask-" + label + "-" + System.nanoTime() + ".png"))) {
            assertTrue(pixels.compress(Bitmap.CompressFormat.PNG, 100, stream));
        }
    }
}
