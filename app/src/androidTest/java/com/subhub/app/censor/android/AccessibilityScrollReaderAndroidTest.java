package com.subhub.app.censor.android;

import static org.junit.Assert.*;

import android.Manifest;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;

import com.subhub.app.censor.engine.ProtectionScope;
import com.subhub.app.censor.engine.SceneEngine;
import com.subhub.app.censor.engine.ScrollMotionLedger;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Real framework event/provider/ownership controls, not a Pixel or image-member motion claim. */
@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion = 34)
public final class AccessibilityScrollReaderAndroidTest {
    @Rule public final TestName testName = new TestName();
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context context = instrumentation.getTargetContext();
    private UiAutomation ui;
    private String servicesBefore, enabledBefore;
    private AccessibilityScrollReader reader;
    private ScrollMotionLedger.Scope scope;
    private File evidence;
    private final AtomicBoolean current = new AtomicBoolean(true);
    private final AtomicInteger reads = new AtomicInteger();
    private final List<ScrollMotionLedger.Sample> samples = Collections.synchronizedList(new ArrayList<>());
    private final List<ScrollMotionLedger.Sample> gaps = Collections.synchronizedList(new ArrayList<>());
    private final AtomicBoolean resourceFailure = new AtomicBoolean();
    private CountDownLatch held, release;
    private List<Runnable> heldDeliveries;
    private RuntimeException compoundGapFailure;
    private final AtomicInteger compoundGapCallbacks = new AtomicInteger();
    private boolean nativeAssertionPassed;
    private long frameCommit;

    @Before public void setUp() throws Exception {
        // Save raw settings BEFORE registering or reconfiguring UiAutomation.
        servicesBefore = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        enabledBefore = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED);
        String run = InstrumentationRegistry.getArguments().getString("scroll_reader_run", UUID.randomUUID().toString());
        assertTrue("Bounded internal evidence namespace", run.matches("[A-Za-z0-9_-]{1,80}"));
        File base = new File(context.getFilesDir(), "replacement-scroll-reader/" + run);
        assertTrue(base.isDirectory() || base.mkdirs()); evidence = new File(base, testName.getMethodName());
        assertFalse("Do not overwrite an earlier native control", evidence.exists()); assertTrue(evidence.mkdir());
        ui = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        android.accessibilityservice.AccessibilityServiceInfo info = ui.getServiceInfo();
        info.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        info.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        ui.setServiceInfo(info);
        target(new Bundle());
        await("Actual synthetic target root", () -> {
            AccessibilityNodeInfo root = ui.getRootInActiveWindow(); if (root == null) return false;
            try { return "com.subhub.app.test".contentEquals(root.getPackageName()); } finally { root.recycle(); }
        });
        AccessibilityNodeInfo root = ui.getRootInActiveWindow();
        assertNotNull(root);
        try { scope = new ScrollMotionLedger.Scope(1, 42, root.getWindowId(), ProtectionScope.UNSPECIFIED); }
        finally { root.recycle(); }
        await("Initial actual target hardware frame committed", () -> { bounds(); return frameCommit > 0; });
        requireScrollableOwners();
    }

    @After public void tearDown() throws Exception {
        current.set(false); if (release != null) release.countDown();
        try {
            if (reader != null) {
                instrumentation.runOnMainSync(reader::close);
                if (heldDeliveries != null) instrumentation.runOnMainSync(this::releaseHeldDeliveries);
                await("Actual scroll worker, copied events and cached identity handles drained", reader::managedResourcesDrained);
            }
        } finally {
            if (ui != null) {
                Bundle finish = new Bundle(); finish.putBoolean("finish", true); target(finish);
                UiAutomation control = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_USE_ACCESSIBILITY);
                control.adoptShellPermissionIdentity(Manifest.permission.WRITE_SECURE_SETTINGS);
                try {
                    assertTrue(Settings.Secure.putString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, servicesBefore));
                    assertTrue(Settings.Secure.putString(context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, enabledBefore));
                } finally { control.dropShellPermissionIdentity(); }
            }
            String servicesAfter = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            String enabledAfter = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED);
            boolean restored = java.util.Objects.equals(servicesBefore, servicesAfter) && java.util.Objects.equals(enabledBefore, enabledAfter);
            JSONObject result = new JSONObject(); result.put("schemaVersion", 1); result.put("method", testName.getMethodName());
            result.put("nativeAssertionPassed", nativeAssertionPassed); result.put("providerAcquisitions", reads.get());
            result.put("observedPackets", samples.size()); result.put("gapPackets", gaps.size());
            result.put("resourceFailure", resourceFailure.get()); result.put("readerDrained", reader == null || reader.managedResourcesDrained());
            result.put("rawSettingsRestored", restored); result.put("physicalMotionQualified", false);
            result.put("pixelPerformanceAcceptance", false); result.put("syntheticTargetOnly", true);
            result.put("imageAcquisitionQualified", false); result.put("memberToProducerBindingQualified", false);
            result.put("targetFrameCommitSerial", frameCommit);
            JSONArray rows = new JSONArray();
            synchronized (samples) {
                for (ScrollMotionLedger.Sample sample : samples) {
                    JSONObject row = new JSONObject(); row.put("delivery", sample.deliveryIdentity); row.put("owner", sample.owner);
                    row.put("producer", sample.producer); row.put("sourceAt", sample.sourceAtMillis);
                    row.put("receivedAt", sample.receivedAtMillis); row.put("readStartedAt", sample.readStartedAtMillis);
                    row.put("readFinishedAt", sample.readFinishedAtMillis); row.put("yAbsoluteKnown", sample.y.absoluteKnown);
                    row.put("yAbsolute", sample.y.absoluteKnown ? sample.y.absolute : JSONObject.NULL);
                    row.put("yDeltaKnown", sample.y.deltaKnown); row.put("yDelta", sample.y.deltaKnown ? sample.y.delta : JSONObject.NULL);
                    rows.put(row);
                }
            }
            result.put("packets", rows); save("result.json", result.toString(2).getBytes(StandardCharsets.UTF_8));
            assertTrue("Exact pre-registration Accessibility settings restored", restored);
        }
    }

    @Test public void validatedOwnerKindIgnoresDifferentAndSpoofedFrameworkEventLabels() throws Exception {
        createReader();
        JSONArray rows = new JSONArray();
        for (int index = 0; index < 2; index++) {
            final int selected = index;
            final int y = (index + 1) * 50;
            Bundle command = new Bundle(); command.putInt("scrollToY", y);
            command.putBoolean("motionOwnerRecyclerLabel", index == 0);
            command.putBoolean("motionEventRecyclerLabel", index == 1);
            AccessibilityEvent event = ui.executeAndWaitForEvent(() -> target(command), candidate ->
                    candidate.getEventType() == AccessibilityEvent.TYPE_VIEW_SCROLLED
                            && "com.subhub.app.test".contentEquals(candidate.getPackageName())
                            && candidate.getWindowId() == scope.windowId && candidate.getScrollY() == y, 6000);
            try {
                String eventClass = index == 0 ? "android.widget.ScrollView" : "androidx.recyclerview.widget.RecyclerView";
                String ownerClass = index == 0 ? "androidx.recyclerview.widget.RecyclerView" : "android.widget.ScrollView";
                assertEquals("Literal framework callback label", eventClass, event.getClassName().toString());
                AccessibilityNodeInfo source = event.getSource(0); assertNotNull(source);
                try {
                    assertTrue("Actual nearest owner is this fixture source", source.isScrollable());
                    assertEquals(scope.windowId, source.getWindowId());
                    assertEquals("Actual provider label differs from event", ownerClass, source.getClassName().toString());
                } finally { source.recycle(); }
                instrumentation.runOnMainSync(() -> reader.offer(event, scope, "com.subhub.app.test"));
                await("Copied validated owner-kind delivery", () -> samples.size() == selected + 1);
                ScrollMotionLedger.Sample sample = samples.get(index);
                ScrollMotionLedger.OwnerKind expected = index == 0 ? ScrollMotionLedger.OwnerKind.RECYCLER_VIEW : ScrollMotionLedger.OwnerKind.OTHER;
                assertEquals(expected, sample.ownerKind); assertTrue(sample.owner > 0); assertEquals(sample.owner, sample.producer);
                JSONObject row = new JSONObject(); row.put("eventClass", eventClass); row.put("providerClass", ownerClass);
                row.put("ownerKind", sample.ownerKind.name()); row.put("delivery", sample.deliveryIdentity);
                row.put("owner", sample.owner); row.put("producer", sample.producer); row.put("window", sample.scope.windowId);
                row.put("sourceAt", sample.sourceAtMillis); row.put("receivedAt", sample.receivedAtMillis);
                row.put("readStartedAt", sample.readStartedAtMillis); row.put("readFinishedAt", sample.readFinishedAtMillis);
                row.put("y", sample.y.absolute); rows.put(row);
                save("owner-kind-" + index + ".json", row.toString(2).getBytes(StandardCharsets.UTF_8));
            } finally { event.recycle(); }
        }
        assertEquals("Provider identity survives a genuine class-label change", samples.get(0).owner, samples.get(1).owner);
        assertEquals(2, reads.get()); assertTrue(gaps.isEmpty()); assertFalse(resourceFailure.get());
        JSONObject proof = new JSONObject(); proof.put("schemaVersion", 1); proof.put("rows", rows);
        proof.put("syntheticProviderLabels", true); proof.put("actualXImplementationTested", false);
        proof.put("physicalAcceptance", false); proof.put("curvePhysicsChanged", false);
        save("owner-kind-proof.json", proof.toString(2).getBytes(StandardCharsets.UTF_8));
        nativeAssertionPassed = true;
    }

    @Test public void frameworkEventsUseIndependentProducersAndNeverGrantViewportMemberSupport() throws Exception {
        createReader(); offerScroll("scrollToY", 50); offerScroll("scrollToY", 100);
        offerScroll("independentScrollToY", 60); offerScroll("independentScrollToY", 100);
        await("Every declared framework event delivered once", () -> samples.size() == 4);
        assertTrue(gaps.isEmpty()); assertFalse(resourceFailure.get()); assertEquals(4, reads.get());
        ScrollMotionLedger.Sample a = samples.get(0), b = samples.get(1), c = samples.get(2), d = samples.get(3);
        assertTrue(a.owner > 0); assertEquals(a.owner, a.producer); assertEquals(a.owner, b.owner); assertEquals(a.producer, b.producer);
        assertTrue(c.owner > 0); assertEquals(c.owner, c.producer); assertEquals(c.owner, d.owner); assertNotEquals(a.owner, c.owner);
        assertEquals(50, a.y.absolute); assertEquals(100, b.y.absolute); assertEquals(60, c.y.absolute); assertEquals(100, d.y.absolute);
        for (int i = 0; i < samples.size(); i++) {
            ScrollMotionLedger.Sample sample = samples.get(i);
            assertEquals(i + 1, sample.deliveryIdentity); assertTrue(sample.y.absoluteKnown);
            assertTrue(sample.sourceAtMillis <= sample.receivedAtMillis && sample.receivedAtMillis <= sample.readStartedAtMillis
                    && sample.readStartedAtMillis <= sample.readFinishedAtMillis);
        }
        // Explicitly synthetic administrative origin. The native proof is the event/provider data,
        // not an IMAGE acquisition or a physical capture-time correspondence.
        ScrollMotionLedger ledger = new ScrollMotionLedger(8, 8, 16); ledger.reset(scope);
        SceneEngine.Source origin = SceneEngine.Source.image(1, 1, 1, 42, 1000, 2400, a.sourceAtMillis, -1);
        assertTrue(ledger.registerImage(origin));
        for (ScrollMotionLedger.Sample sample : samples) ledger.observe(sample, SystemClock.uptimeMillis());
        ScrollMotionLedger.Continuation continuation = ledger.continuation(origin, a.owner, a.producer);
        assertEquals(50, continuation.y.displacement()); assertFalse(continuation.memberApplicabilityKnown);
        assertFalse(continuation.originPixelPhaseKnown); assertFalse(continuation.initialBiasKnown);
        ledger.close(); nativeAssertionPassed = true;
    }

    @Test public void heldActualProviderInputSurvivesCancellationUntilItsTerminalRelease() throws Exception {
        held = new CountDownLatch(1); release = new CountDownLatch(1); createReader();
        offerScroll("scrollToY", 50); assertTrue("Actual event source acquired before holding", held.await(6, TimeUnit.SECONDS));
        offerScroll("scrollToY", 100); assertEquals(2, reader.outstanding());
        current.set(false); instrumentation.runOnMainSync(reader::invalidate);
        assertEquals("Active Binder input stays owned; only queued copy closes", 1, reader.outstanding());
        instrumentation.runOnMainSync(reader::close); assertFalse(reader.managedResourcesDrained());
        release.countDown(); await("Held native input and worker actually terminated", reader::managedResourcesDrained);
        assertEquals(0, reader.outstanding()); assertTrue(samples.isEmpty()); assertTrue(gaps.isEmpty());
        assertFalse(resourceFailure.get()); assertEquals(1, reads.get()); nativeAssertionPassed = true;
    }

    @Test public void postedQueueExpiryGapStaysOwnedAndCannotEscapeSameScopeInvalidation() throws Exception {
        held = new CountDownLatch(1); release = new CountDownLatch(1);
        heldDeliveries = Collections.synchronizedList(new ArrayList<>()); createReader();
        offerScroll("scrollToY", 50); assertTrue("Actual source read held before queue aging", held.await(6, TimeUnit.SECONDS));
        offerScroll("scrollToY", 100); assertEquals(2, reader.outstanding());
        // Controlled queue age, not a compositor wait or a fabricated image/pixel timestamp.
        long expire = SystemClock.uptimeMillis() + 1600;
        while (SystemClock.uptimeMillis() < expire) SystemClock.sleep(10);
        release.countDown(); await("Both actual UI terminal posts held", () -> heldDeliveries.size() == 2);
        assertEquals("Missing-input gap remains inside the same bounded input lease", 2, reader.outstanding());
        instrumentation.runOnMainSync(reader::invalidate); // Scope deliberately unchanged.
        assertEquals("Invalidated UI-posted inputs release only at their actual terminals", 2, reader.outstanding());
        instrumentation.runOnMainSync(this::releaseHeldDeliveries);
        assertEquals(0, reader.outstanding()); assertTrue(samples.isEmpty()); assertTrue(gaps.isEmpty());
        assertEquals("Expired queued input did not acquire another provider", 1, reads.get());
        assertFalse(resourceFailure.get()); nativeAssertionPassed = true;
    }

    @Test public void compoundGapObserverFailureStillReleasesItsCopiedInputBeforeClaimingDrain() throws Exception {
        compoundGapFailure = new IllegalStateException("Owned synchronous gap observer control"); createReader();
        Bundle extras = new Bundle(); extras.putInt("scrollToY", 50); extras.putBoolean("compoundScrollRecord", true);
        AccessibilityEvent event = ui.executeAndWaitForEvent(() -> target(extras), candidate ->
                candidate.getEventType() == AccessibilityEvent.TYPE_VIEW_SCROLLED
                        && "com.subhub.app.test".contentEquals(candidate.getPackageName())
                        && candidate.getWindowId() == scope.windowId && candidate.getScrollY() == 50
                        && candidate.getRecordCount() == 1, 6000);
        try {
            assertEquals("Actual framework delivery carries the fixture's pre-seal compound record", 1, event.getRecordCount());
            java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
            instrumentation.runOnMainSync(() -> {
                try { reader.offer(event, scope, "com.subhub.app.test"); }
                catch (RuntimeException | Error observed) { failure.set(observed); }
            });
            assertSame("Actual main callback exception stays on the test thread", compoundGapFailure, failure.get());
        } finally { event.recycle(); }
        assertEquals(1, compoundGapCallbacks.get()); assertEquals(0, reader.outstanding());
        await("Copy disposed after synchronous observer failure before drain", reader::managedResourcesDrained);
        assertEquals(0, reads.get()); assertTrue(samples.isEmpty()); assertTrue(gaps.isEmpty());
        assertFalse(resourceFailure.get()); nativeAssertionPassed = true;
    }

    @Test public void settledPixelsAndIndependentChildBoundsDisproveUniformViewportTranslation() throws Exception {
        Map<String, Rect> before = capture("before.png"); long beforeCommit = frameCommit;
        createReader(); offerScroll("scrollToY", 100); await("Actual primary source read", () -> samples.size() == 1);
        await("New scrolling hardware frame commit", () -> { bounds(); return frameCommit > beforeCommit; });
        Map<String, Rect> scrolled = capture("scrolled.png");
        assertEquals(-100, scrolled.get("scroll-body").top - before.get("scroll-body").top);
        assertEquals(0, scrolled.get("fixed-overlay").top - before.get("fixed-overlay").top);
        assertEquals(-100, scrolled.get("local-media").top - before.get("local-media").top);
        assertEquals(0, scrolled.get("second-body").top - before.get("second-body").top);
        ScrollMotionLedger.Sample sample = samples.get(0);
        assertNotNull(sample.reportedViewport);
        for (String name : new String[]{"scroll-body", "fixed-overlay", "local-media", "second-body"}) {
            Rect region = scrolled.get(name);
            assertTrue("Containment is identical while causal movement differs", sample.reportedViewport.contains(
                    new SceneEngine.Region(region.left, region.top, region.right, region.bottom)));
        }
        long scrolledCommit = frameCommit;
        Bundle local = new Bundle(); local.putInt("mediaTranslationY", 40); target(local);
        await("Independent media transform reflected by actual provider", () -> {
            try { return bounds().get("local-media").top == scrolled.get("local-media").top + 40; }
            catch (Exception failure) { return false; }
        });
        await("New independently moving hardware frame commit", () -> { bounds(); return frameCommit > scrolledCommit; });
        Map<String, Rect> moved = capture("local.png");
        assertEquals(0, moved.get("scroll-body").top - scrolled.get("scroll-body").top);
        assertEquals(0, moved.get("fixed-overlay").top - scrolled.get("fixed-overlay").top);
        assertEquals(40, moved.get("local-media").top - scrolled.get("local-media").top);
        assertEquals(1, samples.size()); assertTrue(gaps.isEmpty()); nativeAssertionPassed = true;
    }

    private void createReader() {
        instrumentation.runOnMainSync(() -> reader = new AccessibilityScrollReader(
                value -> current.get() && scope.same(value), new AccessibilityScrollReader.Observer() {
                    @Override public void observed(ScrollMotionLedger.Sample sample) {
                        assertEquals(android.os.Looper.getMainLooper(), android.os.Looper.myLooper()); samples.add(sample);
                    }
                    @Override public void gap(ScrollMotionLedger.Sample sample) {
                        if (compoundGapFailure != null) {
                            assertEquals("Unqueued copied input remains owned through its synchronous gap", 1, reader.outstanding());
                            compoundGapCallbacks.incrementAndGet(); reader.close();
                            assertFalse("Reentrant close cannot claim drain before input disposal", reader.managedResourcesDrained());
                            throw compoundGapFailure;
                        }
                        gaps.add(sample);
                    }
                    @Override public void resourceFailure() { resourceFailure.set(true); }
                }, () -> {
                    assertNotEquals(android.os.Looper.getMainLooper(), android.os.Looper.myLooper()); reads.incrementAndGet();
                    if (held != null && reads.get() == 1) {
                        held.countDown(); try { assertTrue("Release actual held provider read", release.await(10, TimeUnit.SECONDS)); }
                        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
                    }
                }, heldDeliveries == null ? null : action -> { heldDeliveries.add(action); return true; }));
    }

    private void releaseHeldDeliveries() {
        List<Runnable> pending;
        synchronized (heldDeliveries) { pending = new ArrayList<>(heldDeliveries); heldDeliveries.clear(); }
        for (Runnable delivery : pending) delivery.run();
    }

    private void offerScroll(String key, int value) throws Exception {
        int expected = samples.size() + 1;
        Bundle extras = new Bundle(); extras.putInt(key, value);
        AccessibilityEvent event = ui.executeAndWaitForEvent(() -> target(extras), candidate ->
                candidate.getEventType() == AccessibilityEvent.TYPE_VIEW_SCROLLED
                        && "com.subhub.app.test".contentEquals(candidate.getPackageName())
                        && candidate.getWindowId() == scope.windowId && candidate.getScrollY() == value, 6000);
        try {
            assertEquals("android.widget.ScrollView", event.getClassName().toString());
            instrumentation.runOnMainSync(() -> reader.offer(event, scope, "com.subhub.app.test"));
        } finally { event.recycle(); }
        if (held == null) await("Copied packet actual native source read", () -> samples.size() == expected);
    }

    private void target(Bundle extras) {
        context.startActivity(new Intent().setComponent(new ComponentName("com.subhub.app.test", ProductTargetFixtureActivity.class.getName()))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION).putExtra("motionFixture", true).putExtras(extras));
    }

    /** Require the realized provider range, not the requested child LayoutParams height. */
    @SuppressWarnings("deprecation")
    private void requireScrollableOwners() throws Exception {
        JSONArray owners = new JSONArray(); ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        AccessibilityNodeInfo root = ui.getRootInActiveWindow(); assertNotNull(root); queue.add(root);
        int visited = 0;
        try {
            while (!queue.isEmpty()) {
                AccessibilityNodeInfo node = queue.removeFirst();
                try {
                    assertTrue("Bounded actual scroll-owner readiness tree", ++visited <= 64); assertTrue(node.refresh());
                    if ("android.widget.ScrollView".contentEquals(node.getClassName())) {
                        JSONObject owner = new JSONObject(); owner.put("window", node.getWindowId());
                        owner.put("viewId", node.getViewIdResourceName()); owner.put("scrollable", node.isScrollable());
                        owners.put(owner);
                    }
                    assertTrue(node.getChildCount() <= 16);
                    for (int i = 0; i < node.getChildCount(); i++) {
                        AccessibilityNodeInfo child = node.getChild(i, 0); assertNotNull(child); queue.addLast(child);
                    }
                } finally { node.recycle(); }
            }
        } finally { for (AccessibilityNodeInfo node : queue) node.recycle(); }
        JSONObject result = new JSONObject(); result.put("owners", owners); result.put("nodesVisited", visited);
        save("readiness.json", result.toString(2).getBytes(StandardCharsets.UTF_8));
        assertEquals("Both declared actual ScrollView owners", 2, owners.length());
        for (int i = 0; i < owners.length(); i++) {
            assertTrue("Actual provider has the requested scrollable range", owners.getJSONObject(i).getBoolean("scrollable"));
            assertEquals("Actual owner remains in the qualified window", scope.windowId, owners.getJSONObject(i).getInt("window"));
        }
        assertNotEquals("Independent actual View identities", owners.getJSONObject(0).getString("viewId"), owners.getJSONObject(1).getString("viewId"));
    }

    @SuppressWarnings("deprecation")
    private Map<String, Rect> bounds() {
        Map<String, Rect> result = new HashMap<>(); ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        AccessibilityNodeInfo root = ui.getRootInActiveWindow(); assertNotNull(root); queue.add(root);
        int visited = 0;
        try {
            while (!queue.isEmpty()) {
                AccessibilityNodeInfo node = queue.removeFirst();
                try {
                    assertTrue("Complete bounded synthetic native tree", ++visited <= 64); assertTrue(node.refresh());
                    if (node.getExtras().containsKey(ProductTargetFixtureActivity.MOTION_FRAME_COMMIT))
                        frameCommit = node.getExtras().getLong(ProductTargetFixtureActivity.MOTION_FRAME_COMMIT);
                    CharSequence name = node.getContentDescription();
                    if (name != null && (name.toString().equals("scroll-body") || name.toString().equals("fixed-overlay")
                            || name.toString().equals("local-media") || name.toString().equals("second-body"))) {
                        Rect region = new Rect(); node.getBoundsInScreen(region); assertFalse(region.isEmpty());
                        assertNull("Unique synthetic child", result.put(name.toString(), region));
                    }
                    assertTrue(node.getChildCount() <= 16);
                    for (int i = 0; i < node.getChildCount(); i++) {
                        AccessibilityNodeInfo child = node.getChild(i, 0); assertNotNull(child); queue.addLast(child);
                    }
                } finally { node.recycle(); }
            }
        } finally { for (AccessibilityNodeInfo node : queue) node.recycle(); }
        assertEquals("Whole declared four-child inventory", 4, result.size()); return result;
    }

    private Map<String, Rect> capture(String name) throws Exception {
        long until = SystemClock.uptimeMillis() + 6000; boolean correct = false;
        Map<String, Rect> positions;
        do {
            // Initial layout/insets can settle after the first frame commit. Refresh this
            // independent provider observation each poll; never keep a stale rectangle while
            // only newer screenshot pixels advance. No offset or pixel-derived bounds repair.
            positions = bounds();
            JSONObject provenance = new JSONObject(); provenance.put("schemaVersion", 1);
            provenance.put("frameCommitSerial", frameCommit); provenance.put("window", scope.windowId);
            JSONObject rectangles = new JSONObject();
            for (Map.Entry<String, Rect> entry : positions.entrySet()) {
                Rect rectangle = entry.getValue(); rectangles.put(entry.getKey(), new JSONArray(new int[]{
                        rectangle.left, rectangle.top, rectangle.right, rectangle.bottom}));
            }
            provenance.put("providerBounds", rectangles);
            save(name.replace(".png", "-bounds.json"), provenance.toString(2).getBytes(StandardCharsets.UTF_8));
            Bitmap pixels = ui.takeScreenshot(); assertNotNull(pixels);
            try {
                // Retain actual failed pixels BEFORE asserting; overwrite only this case's current
                // polling frame, never evidence from another test/run or a previously sealed result.
                File target = new File(evidence, name);
                try (FileOutputStream stream = new FileOutputStream(target)) { assertTrue(pixels.compress(Bitmap.CompressFormat.PNG, 100, stream)); }
                correct = pixel(pixels, positions.get("scroll-body"), 0xffe72942)
                        && pixel(pixels, positions.get("fixed-overlay"), 0xfff0c526)
                        && pixel(pixels, positions.get("local-media"), 0xff2460df)
                        && pixel(pixels, positions.get("second-body"), 0xff17c9a2);
                correct = correct && positions.equals(bounds());
            } finally { pixels.recycle(); }
            if (!correct) SystemClock.sleep(25);
        } while (!correct && SystemClock.uptimeMillis() < until);
        assertTrue("Actual settled child pixels independently match stable refreshed native bounds: " + positions, correct);
        return positions;
    }
    private static boolean pixel(Bitmap pixels, Rect bounds, int color) {
        int[] xs = {bounds.left + 5, bounds.centerX(), bounds.right - 6};
        int[] ys = {bounds.top + 5, bounds.centerY(), bounds.bottom - 6};
        for (int x : xs) for (int y : ys)
            if (x < 0 || y < 0 || x >= pixels.getWidth() || y >= pixels.getHeight() || pixels.getPixel(x, y) != color) return false;
        int[][] outside = {{bounds.left - 4, bounds.centerY()}, {bounds.right + 3, bounds.centerY()},
                {bounds.centerX(), bounds.top - 4}, {bounds.centerX(), bounds.bottom + 3}};
        for (int[] point : outside)
            if (point[0] >= 0 && point[1] >= 0 && point[0] < pixels.getWidth() && point[1] < pixels.getHeight()
                    && pixels.getPixel(point[0], point[1]) == color) return false;
        return true;
    }
    private void save(String name, byte[] bytes) throws Exception {
        try (FileOutputStream stream = new FileOutputStream(new File(evidence, name))) { stream.write(bytes); }
    }
    private static void await(String description, BooleanSupplier condition) {
        long until = SystemClock.uptimeMillis() + 6000;
        while (true) {
            if (condition.getAsBoolean()) return;
            assertTrue(description, SystemClock.uptimeMillis() < until); SystemClock.sleep(10);
        }
    }
}
