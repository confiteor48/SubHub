package com.subhub.app.censor.android;

import static org.junit.Assert.*;

import android.Manifest;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.censor.engine.CensorEngine;
import com.subhub.app.censor.engine.NativeTextFrame;
import com.subhub.app.censor.engine.ProtectionScope;
import com.subhub.app.censor.engine.SceneEngine;
import com.subhub.app.detection.DetectorConfig;
import com.subhub.app.detection.text.NativeTextReader;
import com.subhub.app.detection.text.TextSmutConfig;
import com.subhub.app.penance.CensorTapTracker;
import com.subhub.app.settings.CensorAppearance;
import com.subhub.app.settings.EffectPalette;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;

/** Real ordinary provider/Host/paint controls; not IMAGE tracking, physical clocks or Pixel acceptance. */
@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion = 33)
public final class NativeTextMotionAndroidTest {
    private static final int MASK_COLOR = 0xff44cc99;
    @Rule public final TestName testName = new TestName();
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context context = instrumentation.getTargetContext();
    private UiAutomation ui;
    private String servicesBefore, enabledBefore;
    private File evidence;
    private Harness h;
    private boolean passed, restored, hostDrained;
    private final JSONArray steps = new JSONArray();

    @Before public void setUp() throws Exception {
        servicesBefore = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        enabledBefore = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED);
        String run = InstrumentationRegistry.getArguments().getString("native_text_motion_run", UUID.randomUUID().toString());
        assertTrue(run.matches("[A-Za-z0-9_-]{1,80}")); File base = new File(context.getFilesDir(), "replacement-native-motion/" + run);
        assertTrue(base.isDirectory() || base.mkdirs()); evidence = new File(base, testName.getMethodName());
        assertFalse("Exclusive current native evidence namespace", evidence.exists()); assertTrue(evidence.mkdir());
        ui = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        android.accessibilityservice.AccessibilityServiceInfo info = ui.getServiceInfo();
        info.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS; ui.setServiceInfo(info);
        h = new Harness(); await("Ordinary null-ID provider nodes ready", () -> providerBounds(false) != null);
    }
    @After public void tearDown() throws Exception {
        try { if (h != null) { h.close(); hostDrained = true; } }
        finally {
            if (ui != null) {
                UiAutomation control = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_USE_ACCESSIBILITY);
                control.adoptShellPermissionIdentity(Manifest.permission.WRITE_SECURE_SETTINGS);
                try {
                    assertTrue(Settings.Secure.putString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, servicesBefore));
                    assertTrue(Settings.Secure.putString(context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, enabledBefore));
                } finally { control.dropShellPermissionIdentity(); }
            }
            restored = Objects.equals(servicesBefore, Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES))
                    && Objects.equals(enabledBefore, Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED));
            JSONObject result = new JSONObject(); result.put("schemaVersion", 1); result.put("method", testName.getMethodName());
            result.put("nativeAssertionPassed", passed); result.put("hostDrained", hostDrained); result.put("rawSettingsRestored", restored);
            result.put("imageAcquisitionQualified", false); result.put("physicalPixelClockKnown", false); result.put("pixelPerformanceAcceptance", false);
            result.put("directNativeTextOnly", true); result.put("steps", steps);
            result.put("productionScrollRoutingQualified", false); // These controls drive the real Host, not the product service.
            JSONArray frames = new JSONArray();
            if (h != null) for (NativeTextFrame frame : h.frames) {
                JSONObject row = new JSONObject(); row.put("source", frame.source.sequence); row.put("request", frame.requestIdentity);
                row.put("readStartedAt", frame.read.readStartedAtMillis); row.put("readFinishedAt", frame.read.readFinishedAtMillis);
                row.put("acceptedAt", frame.source.receivedAtMillis); row.put("validUntil", frame.validUntilMillis);
                JSONArray candidates = new JSONArray();
                for (com.subhub.app.censor.engine.NativeTextResult.Candidate candidate : frame.read.candidates) {
                    JSONObject member = new JSONObject(); member.put("nodeKey", candidate.nodeKey); member.put("contentKey", candidate.contentKey);
                    member.put("exactGeometry", candidate.exactGeometry); JSONArray lines = new JSONArray();
                    for (SceneEngine.Region region : candidate.regions) lines.put(rectangle(region));
                    member.put("lines", lines); candidates.put(member);
                }
                row.put("candidates", candidates); frames.put(row);
            }
            result.put("nativeFramePublications", h == null ? 0 : h.nativePublications.get()); result.put("frames", frames);
            result.put("evidencePublications", h == null ? 0 : h.evidencePublications.get());
            result.put("actualPaintPublications", h == null ? 0 : h.paintPublications.get());
            result.put("actualRootAcquisitions", h == null ? 0 : h.opens.get());
            save("result.json", result.toString(2).getBytes(StandardCharsets.UTF_8)); assertTrue("Exact raw settings restoration", restored);
            if (hostDrained) {
                assertEquals("Raw native frame publications match serialized inventory", h.nativePublications.get(), frames.length());
                assertEquals("Only actual classified reads publish evidence", h.nativePublications.get(), h.evidencePublications.get());
            }
        }
    }

    @Test public void ordinaryIdenticalCaptionsKeepStableMasksAcrossScrollReversalAndNewViewReplacement() throws Exception {
        h.start(); NativeTextFrame first = h.frame(1); Map<String, Long> initial = identities();
        assertEquals(2, initial.size()); capture("baseline.png", first);
        String movingKey = keyAtTop(first); String fixedKey = first.read.candidates.get(0).nodeKey.equals(movingKey)
                ? first.read.candidates.get(1).nodeKey : first.read.candidates.get(0).nodeKey;
        Map<String, Rect> baseline = providerBounds(true);
        int[] positions = {100, 260, 80, 300, 0};
        for (int position : positions) {
            long commandedAt = SystemClock.uptimeMillis(); h.scroll(position);
            NativeTextFrame frame = h.freshFrame(commandedAt, value -> hasDisplacement(first, value, movingKey, 0, -position)
                    && hasDisplacement(first, value, fixedKey, 0, 0));
            assertEquals("Exact continuing nodes retain distinct line identities", initial, identities());
            Map<String, Rect> current = providerBounds(true);
            assertEquals(-position, current.get("moving").top - baseline.get("moving").top);
            assertEquals(baseline.get("fixed"), current.get("fixed"));
            assertLineDisplacement(first, frame, movingKey, current.get("moving").left - baseline.get("moving").left,
                    current.get("moving").top - baseline.get("moving").top);
            assertLineDisplacement(first, frame, fixedKey, 0, 0);
            assertTrue(frame.read.candidates.stream().anyMatch(candidate -> candidate.nodeKey.equals(movingKey)));
            assertTrue(frame.read.candidates.stream().anyMatch(candidate -> candidate.nodeKey.equals(fixedKey)));
            capture("scroll-" + position + ".png", frame);
        }
        long replacedAt = SystemClock.uptimeMillis();
        main(() -> { h.activity.replaceMoving(); h.host.requestNativeText(true); });
        await("Replacement ordinary View has same literal public metadata", () -> providerBounds(false) != null);
        NativeTextFrame replacement = h.freshFrame(replacedAt, frame -> frame.read.candidates.stream().noneMatch(c -> movingKey.equals(c.nodeKey))
                && frame.read.candidates.stream().anyMatch(c -> fixedKey.equals(c.nodeKey)));
        Map<String, Long> replaced = identities();
        assertTrue(replaced.containsKey(fixedKey)); assertEquals(initial.get(fixedKey), replaced.get(fixedKey));
        assertFalse("Same resource ID/text/bounds does not rebind a newly created View", replaced.containsKey(movingKey));
        assertEquals(2, replaced.size()); capture("replacement.png", replacement); passed = true;
    }

    @Test public void unchangedTextConfirmationsContinueDuringSustainedActualScrollDemand() throws Exception {
        h.start(); h.frame(1); Map<String, Long> initial = identities();
        long before = h.terminalFrames.stream().filter(frame -> frame.read.coverage == com.subhub.app.censor.engine.NativeTextResult.Coverage.COMPLETE_TREE).count();
        int[] reversal = {0, 160, 320, 160};
        long until = SystemClock.uptimeMillis() + 2200; int index = 1;
        while (SystemClock.uptimeMillis() < until) {
            h.scroll(reversal[index++ % reversal.length]);
            assertEquals(initial, identities()); SystemClock.sleep(15);
        }
        await("Several genuine full confirmations accepted during continuing scroll", () -> h.terminalFrames.stream()
                .filter(frame -> frame.read.coverage == com.subhub.app.censor.engine.NativeTextResult.Coverage.COMPLETE_TREE).count() >= before + 5);
        int count = h.terminalFrames.size(); h.scroll(0); await("Final literal native pose accepted", () -> h.terminalFrames.size() > count);
        capture("sustained-final.png", h.terminalFrames.get(h.terminalFrames.size() - 1)); passed = true;
    }

    @Test public void heldActualRootKeepsRetirementPendingUntilItsRealTerminal() throws Exception {
        h.start(); h.frame(1); int before = h.frames.size(); h.holdRoot = true; main(() -> h.host.requestNativeText(false));
        assertTrue("Actual provider root acquired before hold", h.rootHeld.await(5, TimeUnit.SECONDS));
        main(h.host::close); assertFalse("No executor drain underneath actual held provider read", h.terminated.await(150, TimeUnit.MILLISECONDS));
        h.rootRelease.countDown(); assertTrue(h.terminated.await(5, TimeUnit.SECONDS)); assertNull(h.failure.get());
        assertEquals("Cancelled read cannot publish another source, including early groups", before, h.frames.size()); passed = true;
    }

    @Test public void frozenMovingLineGeometryCannotPassIndependentProviderDisplacement() throws Exception {
        h.start(); NativeTextFrame first = h.frame(1); String movingKey = keyAtTop(first);
        Map<String, Rect> before = providerBounds(true); h.scroll(100); NativeTextFrame moved = h.frame(2);
        Map<String, Rect> after = providerBounds(true); int delta = after.get("moving").top - before.get("moving").top;
        assertEquals(-100, delta);
        assertLineDisplacement(first, moved, movingKey, 0, delta);
        boolean rejected = false;
        try { assertLineDisplacement(first, first, movingKey, 0, delta); }
        catch (AssertionError expected) { rejected = true; }
        assertTrue("Frozen boxes are falsified even if their own pixels and tap centers agree", rejected); passed = true;
    }

    private static void assertLineDisplacement(NativeTextFrame baseline, NativeTextFrame current, String key, int dx, int dy) {
        List<SceneEngine.Region> old = null, fresh = null;
        for (com.subhub.app.censor.engine.NativeTextResult.Candidate candidate : baseline.read.candidates)
            if (candidate.nodeKey.equals(key)) old = candidate.regions;
        for (com.subhub.app.censor.engine.NativeTextResult.Candidate candidate : current.read.candidates)
            if (candidate.nodeKey.equals(key)) fresh = candidate.regions;
        assertNotNull(old); assertNotNull(fresh); assertEquals(old.size(), fresh.size());
        for (int i = 0; i < old.size(); i++) {
            SceneEngine.Region a = old.get(i), b = fresh.get(i);
            assertEquals(a.left + dx, b.left, 0); assertEquals(a.right + dx, b.right, 0);
            assertEquals(a.top + dy, b.top, 0); assertEquals(a.bottom + dy, b.bottom, 0);
        }
    }
    private static boolean hasDisplacement(NativeTextFrame baseline, NativeTextFrame current, String key, int dx, int dy) {
        try { assertLineDisplacement(baseline, current, key, dx, dy); return true; }
        catch (AssertionError wrongPose) { return false; }
    }
    private static boolean sameCaptionPose(NativeTextFrame a, NativeTextFrame b) {
        if (a.read.candidates.size() != b.read.candidates.size()) return false;
        for (com.subhub.app.censor.engine.NativeTextResult.Candidate candidate : a.read.candidates) {
            if (!hasDisplacement(a, b, candidate.nodeKey, 0, 0)) return false;
            if (b.read.candidates.stream().noneMatch(c -> candidate.nodeKey.equals(c.nodeKey) && candidate.contentKey.equals(c.contentKey))) return false;
        }
        return true;
    }

    private Map<String, Long> identities() {
        long deadline = SystemClock.uptimeMillis() + 5000;
        while (true) {
            Map<String, Long> result = new HashMap<>(); boolean complete = true;
            try (CensorEngine<PixelFrame>.RenderView view = h.host.acquireRenderView()) {
                assertNotNull(view);
                for (SceneEngine.Group group : view.snapshot.groups) for (SceneEngine.Mask mask : group.members) {
                    assertTrue(mask.nativeValidated); assertTrue(mask.nativeNodeIdentityKnown);
                    NativeTextFrame source = null;
                    for (NativeTextFrame frame : h.frames) if (frame.source.sequence == mask.evidenceSource.sequence) source = frame;
                    if (source == null) { complete = false; break; } // Admission can precede its observer callback.
                    String key = null;
                    for (com.subhub.app.censor.engine.NativeTextResult.Candidate candidate : source.read.candidates)
                        if (candidate.regions.contains(mask.evidenceRegion)) { assertNull(key); key = candidate.nodeKey; }
                    assertNotNull("Mask remains attached to its exact classified candidate", key);
                    Long previous = result.put(key, mask.textGroup); if (previous != null) assertEquals(previous.longValue(), mask.textGroup);
                }
            }
            if (complete) return result;
            assertTrue("Current admitted frame observer delivered", SystemClock.uptimeMillis() < deadline); SystemClock.sleep(10);
        }
    }
    private static String keyAtTop(NativeTextFrame frame) {
        return frame.read.candidates.get(0).regions.get(0).top < frame.read.candidates.get(1).regions.get(0).top
                ? frame.read.candidates.get(0).nodeKey : frame.read.candidates.get(1).nodeKey;
    }
    private Map<String, Rect> providerBounds(boolean required) {
        AccessibilityNodeInfo root = ui.getRootInActiveWindow(); if (root == null) { if (required) fail("Actual root absent"); return null; }
        Map<String, Rect> result = new HashMap<>();
        try {
            if (!"com.subhub.app".contentEquals(root.getPackageName())) return null;
            for (String name : new String[]{"moving", "fixed"}) {
                List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(name.equals("moving") ? "android:id/text1" : "android:id/text2");
                try {
                    if (nodes.size() != 1) return null; AccessibilityNodeInfo node = nodes.get(0);
                    if (!node.refresh() || !NativeTextMotionFixtureActivity.CAPTION.contentEquals(node.getText())) return null;
                    assertNull("Ordinary Views did not inject a persistent unique ID", node.getUniqueId());
                    Rect bounds = new Rect(); node.getBoundsInScreen(bounds); if (bounds.isEmpty()) return null; result.put(name, bounds);
                } finally { for (AccessibilityNodeInfo node : nodes) node.recycle(); }
            }
        } finally { root.recycle(); }
        if (required) assertEquals(2, result.size()); return result;
    }
    private void capture(String name, NativeTextFrame frame) throws Exception {
        assertEquals(2, frame.read.candidates.size());
        NativeTextFrame painted = null;
        boolean correct = false; long deadline = SystemClock.uptimeMillis() + 5000;
        boolean interiorPixels = false, hitCenters = false, gapPixels = false, gapHitsAbsent = false, providerStable = false, sourceStable = false;
        long observedPaintBefore = -1, observedPaintAfter = -1;
        JSONArray samples = new JSONArray(); int firstFailedSample = -1;
        do {
            painted = null; long paintSource = h.lastPaintSource;
            for (NativeTextFrame candidate : h.frames)
                if (candidate.source.sequence == paintSource && candidate.source.readStartedAtMillis >= frame.source.readStartedAtMillis
                        && sameCaptionPose(frame, candidate) && SystemClock.uptimeMillis() <= candidate.validUntilMillis) painted = candidate;
            if (painted == null) { SystemClock.sleep(10); continue; }
            long began = SystemClock.uptimeMillis();
            Map<String, Rect> provider = providerBounds(true); Bitmap pixels = ui.takeScreenshot(); assertNotNull(pixels);
            long screenshotReceipt = SystemClock.uptimeMillis();
            try {
                observedPaintBefore = painted.source.sequence;
                interiorPixels = hitCenters = gapPixels = gapHitsAbsent = true;
                for (com.subhub.app.censor.engine.NativeTextResult.Candidate candidate : painted.read.candidates) {
                    assertTrue(candidate.exactGeometry); assertEquals("Separate lines retain the uncensored blank gap", 2, candidate.regions.size());
                    for (SceneEngine.Region region : candidate.regions) {
                        int x = (int) ((region.left + region.right) / 2), y = (int) ((region.top + region.bottom) / 2);
                        interiorPixels &= pixels.getPixel(x, y) == MASK_COLOR;
                        hitCenters &= h.taps.matchesPoint(x, y, h.width, h.height, SystemClock.uptimeMillis());
                    }
                    SceneEngine.Region a = candidate.regions.get(0), b = candidate.regions.get(1);
                    int x = (int) Math.max(a.left + 8, b.left + 8), y = (int) ((a.bottom + b.top) / 2);
                    gapPixels &= pixels.getPixel(x, y) != MASK_COLOR;
                    gapHitsAbsent &= !h.taps.matchesPoint(x, y, h.width, h.height, SystemClock.uptimeMillis());
                }
                providerStable = provider.equals(providerBounds(true)); observedPaintAfter = h.lastPaintSource;
                sourceStable = observedPaintAfter == painted.source.sequence;
                correct = interiorPixels && hitCenters && gapPixels && gapHitsAbsent && providerStable && sourceStable;
                long checked = SystemClock.uptimeMillis();
                JSONObject sample = new JSONObject().put("ordinal", samples.length() + 1).put("observationBeganAtMillis", began)
                        .put("screenshotReceiptAtMillis", screenshotReceipt).put("checksFinishedAtMillis", checked)
                        .put("paintBefore", observedPaintBefore).put("paintAfter", observedPaintAfter)
                        .put("interiorPixels", interiorPixels).put("hitCenters", hitCenters).put("gapPixels", gapPixels)
                        .put("gapHitsAbsent", gapHitsAbsent).put("providerStable", providerStable).put("sourceStable", sourceStable)
                        .put("passed", correct);
                // Persistence is outside the unchanged source/pixel/hit/provider enclosure.
                // Retain the first failing screenshot too, rather than overwriting it on retry.
                if (correct || firstFailedSample < 0) {
                    long persistenceBegan = SystemClock.uptimeMillis();
                    String image = correct ? name : "failed-" + name;
                    try (FileOutputStream stream = new FileOutputStream(new File(evidence, image))) {
                        assertTrue(pixels.compress(Bitmap.CompressFormat.PNG, 100, stream));
                    }
                    sample.put("retainedImage", image).put("persistenceBeganAtMillis", persistenceBegan)
                            .put("persistenceFinishedAtMillis", SystemClock.uptimeMillis());
                    if (!correct) firstFailedSample = samples.length() + 1;
                }
                samples.put(sample);
            } finally { pixels.recycle(); }
            if (!correct) SystemClock.sleep(10);
        } while (!correct && SystemClock.uptimeMillis() < deadline);
        JSONObject capture = new JSONObject().put("schemaVersion", 1).put("kind", "native-motion-capture-enclosure")
                .put("method", testName.getMethodName()).put("pid", android.os.Process.myPid())
                .put("clockDomain", "UPTIME_MILLIS").put("recordedAtMillis", SystemClock.uptimeMillis())
                .put("requestedImage", name).put("passed", correct).put("rawSampleCount", samples.length()).put("samples", samples)
                .put("firstFailedSample", firstFailedSample).put("physicalPixelClockKnown", false)
                .put("pixelPerformanceAcceptance", false);
        save("capture-" + name + ".json", capture.toString(2).getBytes(StandardCharsets.UTF_8));
        assertTrue("Independent current mask pixels and member hit-map preserve movement and line gap: interior=" + interiorPixels
                + " hitCenters=" + hitCenters + " gapPixels=" + gapPixels + " gapHitsAbsent=" + gapHitsAbsent
                + " providerStable=" + providerStable + " sourceStable=" + sourceStable + " paintBefore=" + observedPaintBefore
                + " paintAfter=" + observedPaintAfter, correct);
        JSONObject step = new JSONObject(); step.put("file", name); step.put("source", painted.source.sequence);
        step.put("hardwareFrameCommits", h.activity.commits); step.put("actualPaintPublications", h.paintPublications.get());
        steps.put(step);
    }
    private static JSONArray rectangle(SceneEngine.Region region) throws org.json.JSONException {
        return new JSONArray(new double[]{region.left, region.top, region.right, region.bottom});
    }
    private void save(String name, byte[] bytes) throws Exception {
        try (FileOutputStream stream = new FileOutputStream(new File(evidence, name))) { stream.write(bytes); }
    }
    private void main(Runnable action) { instrumentation.runOnMainSync(action); }
    private static void await(String message, BooleanSupplier condition) {
        long deadline = SystemClock.uptimeMillis() + 5000;
        while (true) { if (condition.getAsBoolean()) return; assertTrue(message, SystemClock.uptimeMillis() < deadline); SystemClock.sleep(10); }
    }

    private final class Harness implements AutoCloseable {
        final ActivityScenario<NativeTextMotionFixtureActivity> scenario = ActivityScenario.launch(NativeTextMotionFixtureActivity.class);
        final CensorTapTracker taps = new CensorTapTracker();
        final List<NativeTextFrame> frames = new CopyOnWriteArrayList<>();
        final List<NativeTextFrame> terminalFrames = new CopyOnWriteArrayList<>();
        final AtomicInteger opens = new AtomicInteger(), nativePublications = new AtomicInteger(), evidencePublications = new AtomicInteger(), paintPublications = new AtomicInteger();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final CountDownLatch rootHeld = new CountDownLatch(1), rootRelease = new CountDownLatch(1), terminated = new CountDownLatch(1);
        NativeTextMotionFixtureActivity activity; AccessibilityCensorHost host; SnapshotCensorView view;
        int width, height; volatile boolean holdRoot; volatile long lastPaintSource;
        Harness() {
            scenario.onActivity(value -> {
                activity = value; DisplayMetrics metrics = new DisplayMetrics(); value.getWindowManager().getDefaultDisplay().getRealMetrics(metrics);
                width = metrics.widthPixels; height = metrics.heightPixels;
                host = new AccessibilityCensorHost(value, new SceneEngine.Policy(16, 100, 64, 256, 5000, 20000, .5),
                        executor -> (request, callback) -> { throw new AssertionError("Native-only fixture requested an IMAGE capture"); },
                        (c, lane, config, partition, fallback) -> { throw new AssertionError("Native-only fixture initialized an IMAGE model"); },
                        new AccessibilityCensorHost.Observer() {
                            @Override public void nativeTextAccepted(NativeTextFrame frame) {
                                frames.add(frame); if (frame.observationOrdinal == 0) terminalFrames.add(frame);
                                nativePublications.incrementAndGet();
                            }
                            @Override public void evidenceAccepted(CensorEngine.EvidenceReceipt receipt) { evidencePublications.incrementAndGet(); }
                            @Override public void presentationChanged() { if (view != null) view.presentationChanged(); }
                            @Override public void executorsTerminated(boolean disposed) {
                                if (!disposed) failure.compareAndSet(null, new AssertionError("Native cache retirement unproved")); terminated.countDown();
                            }
                        }, new NativeTextReader.Provider() {
                            @Override public NativeTextReader.Tree open(CensorEngine.NativeTextInput input) throws Exception {
                                long generation = activity.contentGeneration.get(); AccessibilityNodeInfo root = ui.getRootInActiveWindow();
                                if (root == null) return null; boolean transferred = false;
                                try {
                                    if (!"com.subhub.app".contentEquals(root.getPackageName())) return null;
                                    opens.incrementAndGet();
                                    if (holdRoot) { rootHeld.countDown(); assertTrue("Release actual held root", rootRelease.await(5, TimeUnit.SECONDS)); }
                                    NativeTextReader.Tree tree = new NativeTextReader.Tree(root, width, height, generation); transferred = true; return tree;
                                } finally { if (!transferred) root.recycle(); }
                            }
                            @Override public boolean current(CensorEngine.NativeTextInput input, NativeTextReader.Tree tree) {
                                return input.epoch == host.epoch() && input.geometry == 42 && tree.contentGeneration == activity.contentGeneration.get();
                            }
                        });
                view = new SnapshotCensorView(value, new SnapshotCensorView.Owner() {
                    @Override public CensorEngine.ActiveScope activeScope() { return host.activeScope(); }
                    @Override public boolean isCurrent(CensorEngine.ActiveScope scope) { return host.isCurrent(scope); }
                    @Override public CensorEngine<PixelFrame>.RenderView acquireRenderView() { return host.acquireRenderView(); }
                    @Override public CensorEngine.PaintReceipt acknowledgePaint(CensorEngine.ActiveScope scope, long revision, long generation, List<Long> members, boolean reverse) {
                        return host.acknowledgePaint(scope, revision, generation, members, reverse);
                    }
                }, (policy, revision, obsolete) -> Collections.emptyList(), taps, new SnapshotCensorView.Observer() {
                    @Override public void recorded(CensorEngine.PaintReceipt receipt) {
                        paintPublications.incrementAndGet(); if (receipt.canvas != null) lastPaintSource = receipt.canvas.sequence;
                    }
                    @Override public void drawDemand() { value.demand(); }
                    @Override public void resourceFailure() { failure.compareAndSet(null, new AssertionError("Native paint resource failure")); }
                });
                view.configure(new CensorAppearance(CensorAppearance.Type.BOX, 70, 0, false, false, CensorAppearance.BorderEffect.CLASSIC,
                        false, Color.WHITE, new EffectPalette(MASK_COLOR, Color.BLUE, Color.WHITE), Collections.singletonList("BLOCKED"),
                        false, 100, "rectangle", "SubHub", "Access blocked."), new SnapshotPaintPolicy(256, 1), 0, true);
                view.presentationAllowed(true); value.install(view);
            });
        }
        void start() {
            DetectorConfig config = DetectorConfig.builder().enabledCategories(Collections.emptySet()).inferenceResolution(320).inferenceThreads(2).build();
            main(() -> host.start(42, config, false, new TextSmutConfig(true, TextSmutConfig.SENSITIVITY_BALANCED, TextSmutConfig.DEFAULT_CATEGORIES),
                    false, true, ProtectionScope.normal(taps.scope())));
        }
        NativeTextFrame frame(int expected) {
            await("Actual terminal ordinary native frame " + expected, () -> terminalFrames.size() >= expected);
            assertNull(failure.get()); return terminalFrames.get(expected - 1);
        }
        NativeTextFrame freshFrame(long commandedAt, java.util.function.Predicate<NativeTextFrame> pose) {
            AtomicReference<NativeTextFrame> accepted = new AtomicReference<>();
            await("Fresh actual provider read at the commanded independent pose", () -> {
                for (NativeTextFrame value : terminalFrames)
                    if (value.source.readStartedAtMillis >= commandedAt && pose.test(value)) { accepted.set(value); return true; }
                return false;
            });
            assertNull(failure.get()); return accepted.get();
        }
        void scroll(int position) throws Exception {
            int previous = activity.scroll.getScrollY(); if (previous == position) { main(() -> host.requestNativeText(false)); return; }
            AccessibilityEvent event = ui.executeAndWaitForEvent(() -> main(() -> activity.scrollTo(position)), candidate ->
                    candidate.getEventType() == AccessibilityEvent.TYPE_VIEW_SCROLLED && candidate.getScrollY() == position
                            && "com.subhub.app".contentEquals(candidate.getPackageName()), 5000);
            try { main(() -> host.requestNativeText(false)); } finally { event.recycle(); }
        }
        @Override public void close() throws Exception {
            rootRelease.countDown(); main(() -> { view.close(); host.close(); });
            assertTrue("Actual native host/cache executor retirement", terminated.await(5, TimeUnit.SECONDS)); assertNull(failure.get());
            await("Paint owner resources terminated", () -> view.managedResourcesDrained() && view.preparationTerminated()); scenario.close();
        }
    }
}
