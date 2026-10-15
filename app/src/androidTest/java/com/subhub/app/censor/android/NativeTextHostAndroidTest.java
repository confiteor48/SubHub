package com.subhub.app.censor.android;

import static org.junit.Assert.*;

import android.app.UiAutomation;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.filters.SdkSuppress;
import com.subhub.app.censor.engine.CensorEngine;
import com.subhub.app.censor.engine.NativeTextFrame;
import com.subhub.app.censor.engine.PixelGeometry;
import com.subhub.app.censor.engine.SceneEngine;
import com.subhub.app.detection.BBox;
import com.subhub.app.detection.Detection;
import com.subhub.app.detection.DetectorConfig;
import com.subhub.app.detection.text.NativeTextReader;
import com.subhub.app.detection.text.TextSmutConfig;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion = 33)
public final class NativeTextHostAndroidTest {
    private static void main(Runnable action) { InstrumentationRegistry.getInstrumentation().runOnMainSync(action); }
    private static List<SceneEngine.Mask> masks(CensorEngine<PixelFrame>.RenderView view) {
        List<SceneEngine.Mask> result = new ArrayList<>();
        for (SceneEngine.Group group : view.snapshot.groups) result.addAll(group.members);
        return result;
    }

    @Test public void actualProviderNativeOnlyPublishesWithoutScreenshotsOrVisualModels() throws Exception {
        try (Harness h = new Harness()) {
            h.start(false); NativeTextFrame frame = h.awaitFrame(1);
            assertEquals(2, frame.read.candidates.size()); assertEquals(0, h.modelInitializations.get()); assertEquals(0, h.captures.size());
            assertEquals(-1, frame.source.pixelAtMillis); assertEquals(0, frame.source.imageRequestIdentity);
            assertTrue(frame.source.readFinishedAtMillis >= frame.source.readStartedAtMillis + 48);
            assertTrue(frame.source.receivedAtMillis >= frame.source.readFinishedAtMillis);
            try (CensorEngine<PixelFrame>.RenderView view = h.host.acquireRenderView()) {
                assertNotNull(view); assertEquals(2, masks(view).size());
                for (SceneEngine.Mask mask : masks(view)) {
                    assertTrue(mask.nativeValidated); assertTrue(mask.nativeNodeIdentityKnown);
                    assertEquals(SceneEngine.SourceKind.NATIVE_TEXT, mask.evidenceSource.kind);
                    assertNull(view.sourcePixels(mask.evidenceSource.sequence)); assertFalse(mask.confirmed);
                }
            }
        }
    }

    @Test public void actualProviderMultilineGroupKeepsTheRenderedBlankLineGap() throws Exception {
        try (Harness h = new Harness()) {
            h.scenario.onActivity(activity -> activity.content("send nudes\n\nporn", "ordinary safe caption"));
            h.start(false); NativeTextFrame frame = h.awaitFrame(1);
            assertEquals(1, frame.read.candidates.size()); assertTrue(frame.read.candidates.get(0).exactGeometry);
            assertEquals(2, frame.read.candidates.get(0).regions.size());
            try (CensorEngine<PixelFrame>.RenderView view = h.host.acquireRenderView()) {
                assertEquals(1, view.snapshot.groups.size()); assertEquals(2, masks(view).size());
                assertTrue(masks(view).get(0).displayRegion.bottom < masks(view).get(1).displayRegion.top);
                assertEquals(masks(view).get(0).textGroup, masks(view).get(1).textGroup);
            }
        }
    }

    @Test public void actualRecycledNodeDoesNotInheritAContentHashCollision() throws Exception {
        assertEquals("porn ap".hashCode(), "porn c2".hashCode());
        try (Harness h = new Harness()) {
            h.scenario.onActivity(activity -> activity.content("porn ap", "ordinary safe caption"));
            awaitLiteralProviderText(h.automation, "porn ap");
            h.start(false); NativeTextFrame first = h.awaitFrame(1);
            long identity;
            try (CensorEngine<PixelFrame>.RenderView view = h.host.acquireRenderView()) { identity = masks(view).get(0).identity; }
            h.scenario.onActivity(activity -> activity.content("porn c2", "ordinary safe caption"));
            awaitLiteralProviderText(h.automation, "porn c2");
            main(() -> h.host.requestNativeText(true)); NativeTextFrame second = h.awaitFrame(2);
            assertNotEquals(first.read.candidates.get(0).contentKey, second.read.candidates.get(0).contentKey);
            assertEquals(first.read.candidates.get(0).nodeKey, second.read.candidates.get(0).nodeKey);
            try (CensorEngine<PixelFrame>.RenderView view = h.host.acquireRenderView()) { assertNotEquals(identity, masks(view).get(0).identity); }
        }
    }

    @Test public void contentChangeDuringExtraGeometryRejectsTheWholeReadNotSuccessfulSubset() throws Exception {
        try (Harness h = new Harness()) {
            h.start(false); NativeTextFrame initial = h.awaitFrame(1);
            h.scenario.onActivity(activity -> { activity.first.setText("porn ap"); activity.first.swapDuringGeometry = true; });
            main(() -> h.host.requestNativeText(false));
            assertTrue(h.secondOpen.await(5, TimeUnit.SECONDS));
            Thread.sleep(150);
            assertEquals(1, h.terminalFrames.size());
            try (CensorEngine<PixelFrame>.RenderView view = h.host.acquireRenderView()) {
                assertEquals(2, masks(view).size());
                for (SceneEngine.Mask mask : masks(view)) assertEquals(initial.source.sequence, mask.evidenceSource.sequence);
            }
        }
    }

    @Test public void missingSignificantGeometryCannotPublishOneMatchedLineAsACompleteRead() throws Exception {
        incompleteGeometry(false);
    }

    @Test public void whollyOffscreenGeometryCannotInventOnePixelEdgeCoverage() throws Exception {
        incompleteGeometry(true);
    }

    private static void incompleteGeometry(boolean offscreen) throws Exception {
        try (Harness h = new Harness()) {
            h.scenario.onActivity(activity -> activity.content("send nudes\n\nporn", "ordinary safe caption"));
            h.start(false); NativeTextFrame initial = h.awaitFrame(1);
            h.scenario.onActivity(activity -> {
                activity.first.omitMatchedLastLine = !offscreen; activity.first.offscreenGeometry = offscreen;
            });
            main(() -> h.host.requestNativeText(false)); assertTrue(h.secondOpen.await(5, TimeUnit.SECONDS));
            Thread.sleep(150); assertEquals("Unavailable geometry is not a complete subset or edge rectangle", 1, h.terminalFrames.size());
            try (CensorEngine<PixelFrame>.RenderView view = h.host.acquireRenderView()) {
                assertEquals(2, masks(view).size());
                for (SceneEngine.Mask mask : masks(view)) assertEquals(initial.source.sequence, mask.evidenceSource.sequence);
            }
        }
    }

    @Test public void estimatedMultilineFallbackKeepsKnownBlankLineGapAsSeparateMembers() throws Exception {
        try (Harness h = new Harness()) {
            h.scenario.onActivity(activity -> {
                activity.content("send nudes\n\nporn", "ordinary safe caption"); activity.first.noGeometry = true;
            });
            h.start(false); NativeTextFrame frame = h.awaitFrame(1);
            assertEquals(1, frame.read.candidates.size()); assertFalse(frame.read.candidates.get(0).exactGeometry);
            assertEquals(2, frame.read.candidates.get(0).regions.size());
            try (CensorEngine<PixelFrame>.RenderView view = h.awaitPresented(frame)) {
                assertTrue(SystemClock.uptimeMillis() < frame.candidateValidUntilMillis(frame.read.candidates.get(0)));
                assertEquals(2, masks(view).size());
                for (SceneEngine.Mask mask : masks(view)) assertSame(frame.source, mask.evidenceSource);
                assertTrue(masks(view).get(0).displayRegion.bottom < masks(view).get(1).displayRegion.top);
            }
        }
    }

    @Test public void normalizedMatchCannotClaimRemovedSeparatorLineAsExactText() throws Exception {
        try (Harness h = new Harness()) {
            h.scenario.onActivity(activity -> activity.content("porn\n\n----------------", "ordinary safe caption"));
            h.start(false); NativeTextFrame frame = h.awaitFrame(1);
            assertEquals(1, frame.read.candidates.size()); assertTrue(frame.read.candidates.get(0).exactGeometry);
            assertEquals("The classifier did not match the separator line", 1, frame.read.candidates.get(0).regions.size());
        }
    }

    @Test public void originalOffsetsSurviveLetterSpacingAndUnavailableNonGlyphUnits() throws Exception {
        try (Harness h = new Harness()) {
            h.scenario.onActivity(activity -> {
                activity.content("  p . \u00f6 . r . n\n\n-------------", "ordinary safe caption");
                activity.first.omitNonGlyphUnits = true;
            });
            h.start(false); NativeTextFrame frame = h.awaitFrame(1);
            assertEquals(1, frame.read.candidates.size()); assertTrue(frame.read.candidates.get(0).exactGeometry);
            assertEquals(1, frame.read.candidates.get(0).regions.size());
        }
    }

    @Test public void blockedActualRootReadKeepsExecutorsUntilRealTerminalAfterClose() throws Exception {
        try (Harness h = new Harness()) {
            h.blockRoot = true; h.start(false); assertTrue(h.rootEntered.await(5, TimeUnit.SECONDS));
            main(h.host::close); assertFalse(h.terminated.await(80, TimeUnit.MILLISECONDS));
            h.rootRelease.countDown(); assertTrue(h.terminated.await(5, TimeUnit.SECONDS));
            assertTrue(h.frames.isEmpty()); assertEquals(0, h.modelInitializations.get());
        }
    }

    @Test public void nativeReadSurvivesAnOutstandingImageAndFirstFastKeepsBothOrigins() throws Exception {
        try (Harness h = new Harness()) {
            h.start(true); NativeTextFrame nativeFrame = h.awaitFrame(1);
            long deadline = SystemClock.uptimeMillis() + 5000;
            while (h.captures.isEmpty() && SystemClock.uptimeMillis() < deadline) Thread.sleep(10);
            assertEquals(1, h.captures.size());
            // FAST readiness is eager; only inference requires the still-outstanding pixels.
            assertEquals(0, h.modelInferences.get());
            Call call = h.captures.get(0); Bitmap bitmap = Bitmap.createBitmap(h.width, h.height, Bitmap.Config.ARGB_8888);
            PixelFrame pixels = new PixelFrame(bitmap, PixelGeometry.display(h.width, h.height, h.width, h.height),
                    PixelFrame.Kind.ACCESSIBILITY_DISPLAY, -1, SystemClock.uptimeMillis(), 0);
            CensorEngine.Captured<PixelFrame> payload = new CensorEngine.Captured<>(pixels, h.width, h.height, -1, bitmap::recycle);
            try { call.callback.success(payload); } finally { payload.close(); }
            assertTrue(h.imageAccepted.await(5, TimeUnit.SECONDS));
            assertEquals(1, h.modelInferences.get());
            // Acceptance posts UI delivery first; it is not itself a delivered render snapshot.
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            try (CensorEngine<PixelFrame>.RenderView view = h.host.acquireRenderView()) {
                assertEquals(SceneEngine.SourceKind.IMAGE, view.snapshot.destination.kind);
                assertEquals(call.request.identity, view.snapshot.destination.imageRequestIdentity);
                assertNotEquals(call.request.identity, view.snapshot.destination.sequence);
                assertEquals(3, masks(view).size()); assertNull(view.sourcePixels(nativeFrame.source.sequence));
                assertSame(bitmap, view.sourcePixels(view.snapshot.destination.sequence).bitmap);
            }
        }
    }

    private static void awaitLiteralProviderText(UiAutomation automation, String expected) throws InterruptedException {
        long deadline = SystemClock.uptimeMillis() + 5000;
        while (SystemClock.uptimeMillis() < deadline) {
            AccessibilityNodeInfo root = automation.getRootInActiveWindow();
            boolean observed = false;
            if (root != null) {
                List<AccessibilityNodeInfo> nodes = Collections.emptyList();
                try {
                    root.refresh(); nodes = root.findAccessibilityNodeInfosByText(expected);
                    assertNotNull(nodes); assertTrue("Literal fixture node inventory exceeds bound", nodes.size() <= 64);
                    for (AccessibilityNodeInfo node : nodes) {
                        if (node != null && node.refresh() && expected.contentEquals(node.getText() == null ? "" : node.getText())) {
                            observed = true; break;
                        }
                    }
                } finally {
                    for (AccessibilityNodeInfo node : nodes) if (node != null && node != root) node.recycle();
                    root.recycle();
                }
            }
            if (observed) return; // Do not re-evaluate a successful provider-baseline observation.
            Thread.sleep(10);
        }
        fail("Synthetic Accessibility provider has not exposed the requested literal text");
    }

    private static final class Call {
        final CensorEngine.CaptureRequest request; final CensorEngine.CaptureCallback<PixelFrame> callback;
        Call(CensorEngine.CaptureRequest request, CensorEngine.CaptureCallback<PixelFrame> callback) {
            this.request = request; this.callback = callback;
        }
    }
    private static final class Harness implements AutoCloseable {
        final UiAutomation automation = InstrumentationRegistry.getInstrumentation().getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        final ActivityScenario<NativeTextFixtureActivity> scenario = ActivityScenario.launch(NativeTextFixtureActivity.class);
        final List<NativeTextFrame> frames = new CopyOnWriteArrayList<>();
        final List<NativeTextFrame> terminalFrames = new CopyOnWriteArrayList<>();
        final List<Call> captures = new CopyOnWriteArrayList<>();
        final AtomicInteger modelInitializations = new AtomicInteger(), modelInferences = new AtomicInteger(), opens = new AtomicInteger();
        final CountDownLatch terminated = new CountDownLatch(1), imageAccepted = new CountDownLatch(1);
        final CountDownLatch rootEntered = new CountDownLatch(1), rootRelease = new CountDownLatch(1), secondOpen = new CountDownLatch(1);
        volatile boolean blockRoot;
        NativeTextFixtureActivity activity;
        AccessibilityCensorHost host;
        int width, height;
        Harness() {
            scenario.onActivity(value -> {
                activity = value; DisplayMetrics metrics = value.getResources().getDisplayMetrics(); width = metrics.widthPixels; height = metrics.heightPixels;
            });
            main(() -> host = new AccessibilityCensorHost(activity, new SceneEngine.Policy(16, 100, 64, 256, 5000, 20000, .5),
                    executor -> (request, callback) -> { assertTrue(request.tryDispatch()); captures.add(new Call(request, callback)); },
                    (context, lane, config, partition, fallback) -> {
                        modelInitializations.incrementAndGet();
                        return new VisualDetectorWorker(lane, config, partition, new VisualDetectorWorker.Model() {
                            @Override public String provider() { return "CPU"; }
                            @Override public void configure(DetectorConfig value) { }
                            @Override public VisualDetectorWorker.ModelOutput infer(Bitmap bitmap, int width, int height,
                                    int tensorWidth, int tensorHeight, BooleanSupplier cancelled,
                                    Consumer<com.subhub.app.detection.DetectionEngine.RunCancellation> binding) {
                                modelInferences.incrementAndGet();
                                return new VisualDetectorWorker.ModelOutput(Collections.singletonList(
                                        new Detection("FACE_FEMALE", "face", .9f, new BBox(10, 20, 20, 30), false, true)),
                                        true, false, new CensorEngine.WorkTiming(1, 2, 1));
                            }
                            @Override public void close() { }
                        });
                    }, new AccessibilityCensorHost.Observer() {
                        @Override public void nativeTextAccepted(NativeTextFrame frame) {
                            frames.add(frame); if (frame.observationOrdinal == 0) terminalFrames.add(frame);
                        }
                        @Override public void batchAccepted(SceneEngine.Batch batch) { if (batch.lane == SceneEngine.Lane.FAST) imageAccepted.countDown(); }
                        @Override public void executorsTerminated(boolean disposed) { assertTrue(disposed); terminated.countDown(); }
                    }, new NativeTextReader.Provider() {
                        @Override public NativeTextReader.Tree open(CensorEngine.NativeTextInput input) throws Exception {
                            if (opens.incrementAndGet() == 2) secondOpen.countDown(); rootEntered.countDown();
                            if (blockRoot) assertTrue(rootRelease.await(5, TimeUnit.SECONDS));
                            long deadline = SystemClock.uptimeMillis() + 5000;
                            while (SystemClock.uptimeMillis() < deadline) {
                                AccessibilityNodeInfo root = automation.getRootInActiveWindow();
                                if (root != null) {
                                    if ("com.subhub.app".contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) {
                                        return new NativeTextReader.Tree(root, width, height, activity.contentGeneration.get());
                                    }
                                    root.recycle();
                                }
                                Thread.sleep(10);
                            }
                            throw new IllegalStateException("Synthetic fixture root unavailable");
                        }
                        @Override public boolean current(CensorEngine.NativeTextInput input, NativeTextReader.Tree tree) {
                            return input.epoch == host.epoch() && input.geometry == 42 && tree.contentGeneration == activity.contentGeneration.get();
                        }
                    }));
        }
        void start(boolean visual) {
            DetectorConfig config = DetectorConfig.builder().enabledCategories(visual ? Collections.singleton("face") : Collections.emptySet())
                    .inferenceResolution(320).inferenceThreads(2).confidenceThreshold(.3f).build();
            main(() -> host.start(42, config, false, new TextSmutConfig(true, TextSmutConfig.SENSITIVITY_BALANCED,
                    TextSmutConfig.DEFAULT_CATEGORIES), false, false));
        }
        NativeTextFrame awaitFrame(int count) throws InterruptedException {
            long deadline = SystemClock.uptimeMillis() + 5000;
            while (terminalFrames.size() < count && SystemClock.uptimeMillis() < deadline) Thread.sleep(10);
            assertEquals("Expected terminal actual native frame, not early groups", count, terminalFrames.size());
            return terminalFrames.get(count - 1);
        }
        CensorEngine<PixelFrame>.RenderView awaitPresented(NativeTextFrame frame) throws InterruptedException {
            long deadline = frame.validUntilMillis;
            for (com.subhub.app.censor.engine.NativeTextResult.Candidate candidate : frame.read.candidates)
                deadline = Math.min(deadline, frame.candidateValidUntilMillis(candidate));
            // Admission observers run before the asynchronous presentation executor installs a view.
            // Wait for this exact destination without demanding another read or renewing its age.
            while (SystemClock.uptimeMillis() < deadline) {
                CensorEngine<PixelFrame>.RenderView view = host.acquireRenderView();
                if (view != null) {
                    if (view.snapshot.destination == frame.source) return view;
                    view.close();
                }
                Thread.sleep(10);
            }
            fail("Admitted native source was not presented within its original evidence age");
            return null;
        }
        @Override public void close() throws Exception {
            rootRelease.countDown(); main(host::close);
            for (Call call : captures) call.callback.failure(0);
            assertTrue("Native/image host did not terminate", terminated.await(5, TimeUnit.SECONDS)); scenario.close();
        }
    }
}
