package com.subhub.app.detection.text;

import static org.junit.Assert.*;

import android.app.UiAutomation;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.censor.android.NativeTextFixtureActivity;
import com.subhub.app.censor.engine.CensorEngine;
import com.subhub.app.censor.engine.NativeTextResult;
import com.subhub.app.censor.engine.SceneEngine;
import java.util.Collections;
import java.util.EnumSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class NativeTextReaderAndroidTest {
    @Test public void earlyNoncandidateNodeReleaseFailureCannotClaimSafeDrain() throws Exception {
        closeFailure(false);
    }

    @Test public void terminalRootReleaseFailureStillDisposesOtherOwnedNodesAndCannotDrain() throws Exception {
        closeFailure(true);
    }

    private static void closeFailure(boolean rootFailure) throws Exception {
        UiAutomation automation = InstrumentationRegistry.getInstrumentation().getUiAutomation(
                UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        AtomicReference<AccessibilityNodeInfo> deliberatelyUnreleased = new AtomicReference<>();
        AtomicReference<AccessibilityNodeInfo> ownedRoot = new AtomicReference<>();
        AtomicInteger resourceFailures = new AtomicInteger(), drained = new AtomicInteger(), releases = new AtomicInteger();
        CountDownLatch terminal = new CountDownLatch(1);
        CensorEngine<String> engine = null;
        try (ActivityScenario<NativeTextFixtureActivity> scenario = ActivityScenario.launch(NativeTextFixtureActivity.class)) {
            AtomicReference<NativeTextFixtureActivity> activity = new AtomicReference<>();
            scenario.onActivity(value -> { activity.set(value); value.content("send nudes", "ordinary safe caption"); });
            NativeTextReader reader = new NativeTextReader(new NativeTextReader.Provider() {
                @Override public NativeTextReader.Tree open(CensorEngine.NativeTextInput input) throws Exception {
                    long deadline = SystemClock.uptimeMillis() + 5000;
                    while (SystemClock.uptimeMillis() < deadline) {
                        AccessibilityNodeInfo root = automation.getRootInActiveWindow();
                        if (root != null) {
                            if ("com.subhub.app".contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) {
                                ownedRoot.set(root);
                                DisplayMetrics metrics = activity.get().getResources().getDisplayMetrics();
                                return new NativeTextReader.Tree(root, metrics.widthPixels, metrics.heightPixels, 0);
                            }
                            root.recycle();
                        }
                        Thread.sleep(10);
                    }
                    throw new IllegalStateException("Fixture root unavailable");
                }
                @Override public boolean current(CensorEngine.NativeTextInput input, NativeTextReader.Tree tree) { return true; }
            }, new SmutTextClassifier(InstrumentationRegistry.getInstrumentation().getTargetContext()),
                    new TextSmutConfig(true, TextSmutConfig.SENSITIVITY_BALANCED, TextSmutConfig.DEFAULT_CATEGORIES),
                    false, false, executor, 48, node -> {
                        CharSequence text = node.getText();
                        boolean selected = rootFailure ? node == ownedRoot.get()
                                : "ordinary safe caption".contentEquals(text == null ? "" : text);
                        if (selected && deliberatelyUnreleased.compareAndSet(null, node)) {
                            // Inject BEFORE the real native operation; finally can safely reclaim this fixture handle.
                            throw new IllegalStateException("Injected native release failure");
                        }
                        node.recycle(); releases.incrementAndGet();
                    });
            CensorEngine.NativeTextWorker worker = (input, completion) -> reader.start(input, new CensorEngine.NativeTextCompletion() {
                @Override public void success(NativeTextResult result) { completion.success(result); terminal.countDown(); }
                @Override public void failure() { completion.failure(); terminal.countDown(); }
                @Override public void cancelled() { completion.cancelled(); terminal.countDown(); }
                @Override public void resourceFailure() { resourceFailures.incrementAndGet(); completion.resourceFailure(); terminal.countDown(); }
            });
            engine = new CensorEngine<>(new CensorEngine.Policy(334, 500, 4, 0, false),
                    new SceneEngine.Policy(16, 100, 64, 256, 5000, 20000, .5), SystemClock::uptimeMillis,
                    (action, delay) -> { java.util.concurrent.Future<?> future = executor.schedule(action, delay, TimeUnit.MILLISECONDS); return () -> future.cancel(false); },
                    (request, callback) -> fail("Text-only read requested image capture"),
                    Collections.singletonList(new CensorEngine.LaneWorker<>(SceneEngine.Lane.FAST, Runnable::run,
                            (input, completion) -> fail("Text-only read requested inference"))), Runnable::run,
                    new CensorEngine.Observer() { @Override public void drained() { drained.incrementAndGet(); } },
                    new CensorEngine.NativeTextLane(99, 120, 5000, executor, worker));
            engine.executionControls(334, false, EnumSet.noneOf(SceneEngine.Lane.class)); engine.start(42);
            assertTrue("Reader terminal missing", terminal.await(5, TimeUnit.SECONDS));
            assertNotNull("Injected close was not reached", deliberatelyUnreleased.get());
            engine.close();
            assertEquals("Failed native close must not become ordinary failure", 1, resourceFailures.get());
            assertEquals("Unproved disposal must not drain", 0, drained.get());
            assertTrue("Other owned nodes must still be disposed", releases.get() > 0);
        } finally {
            if (engine != null) engine.close();
            executor.shutdown(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            AccessibilityNodeInfo fixture = deliberatelyUnreleased.get();
            if (fixture != null) fixture.recycle();
        }
    }
}
