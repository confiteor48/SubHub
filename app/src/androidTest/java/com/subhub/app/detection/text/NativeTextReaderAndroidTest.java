package com.subhub.app.detection.text;

import static org.junit.Assert.*;

import android.app.UiAutomation;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.filters.SdkSuppress;
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
    @Test @SdkSuppress(minSdkVersion = 33)
    public void softMutationDuringFinalProviderCheckCannotPublishCachedFallbackLiteralAsFreshEvidence() throws Exception {
        heldProviderAdverse(false);
    }

    @Test @SdkSuppress(minSdkVersion = 33)
    public void cancelledTraversalCannotSucceedWhenCustomProviderReturnsTrueAfterHold() throws Exception {
        heldProviderAdverse(true);
    }

    @Test @SdkSuppress(minSdkVersion = 33)
    public void slowFinalScopeCheckCannotRedateFreshFallbackOrAdmitExpiredEvidence() throws Exception {
        heldProviderAdverse(Adverse.LATE_SCOPE);
    }

    @Test @SdkSuppress(minSdkVersion = 33)
    public void newlyAdvertisedFinalGlyphSupportCannotSilentlyUseEstimatedFallback() throws Exception {
        heldProviderAdverse(Adverse.NEW_GLYPH_SUPPORT);
    }

    @Test @SdkSuppress(minSdkVersion = 33)
    public void finalGlyphResponseMutationCannotPublishAnEarlierPositiveLiteral() throws Exception {
        heldProviderAdverse(Adverse.GLYPH_MUTATION);
    }

    @Test @SdkSuppress(minSdkVersion = 33)
    public void incompleteFinalGlyphResponseCannotBecomeEstimatedOrPartialCoverage() throws Exception {
        heldProviderAdverse(Adverse.MISSING_GLYPHS);
    }

    private enum Adverse { MUTATION, CANCEL_TRAVERSAL, LATE_SCOPE, NEW_GLYPH_SUPPORT, GLYPH_MUTATION, MISSING_GLYPHS }

    private void heldProviderAdverse(boolean cancelTraversal) throws Exception {
        heldProviderAdverse(cancelTraversal ? Adverse.CANCEL_TRAVERSAL : Adverse.MUTATION);
    }

    private void heldProviderAdverse(Adverse adverse) throws Exception {
        boolean cancelTraversal = adverse == Adverse.CANCEL_TRAVERSAL;
        boolean lateScope = adverse == Adverse.LATE_SCOPE;
        android.content.Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String savedServices = android.provider.Settings.Secure.getString(context.getContentResolver(), "enabled_accessibility_services");
        String savedEnabled = android.provider.Settings.Secure.getString(context.getContentResolver(), "accessibility_enabled");
        assertTrue(savedServices == null || savedServices.isEmpty()
                || savedServices.matches("[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+(?::[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+)*"));
        assertTrue(savedEnabled == null || savedEnabled.equals("0") || savedEnabled.equals("1"));
        UiAutomation automation = InstrumentationRegistry.getInstrumentation().getUiAutomation(
                UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        java.util.concurrent.atomic.AtomicBoolean confirming = new java.util.concurrent.atomic.AtomicBoolean();
        CountDownLatch held = new CountDownLatch(1), release = new CountDownLatch(1), terminal = new CountDownLatch(1);
        AtomicInteger finalChecks = new AtomicInteger(), failures = new AtomicInteger(), successes = new AtomicInteger(), drained = new AtomicInteger();
        AtomicInteger cancellations = new AtomicInteger(), terminals = new AtomicInteger();
        AtomicInteger admitted = new AtomicInteger();
        java.util.concurrent.atomic.AtomicLong heldAt = new java.util.concurrent.atomic.AtomicLong(-1);
        AtomicReference<NativeTextResult> attempted = new AtomicReference<>();
        ScheduledExecutorService executor = new java.util.concurrent.ScheduledThreadPoolExecutor(1) {
            @Override public java.util.concurrent.ScheduledFuture<?> schedule(Runnable operation, long delay, TimeUnit unit) {
                return super.schedule(() -> { if (unit.toMillis(delay) == 48) confirming.set(true); operation.run(); }, delay, unit);
            }
        };
        CensorEngine<String> engine = null; NativeTextReader reader = null; Throwable primaryFailure = null;
        try (ActivityScenario<NativeTextFixtureActivity> scenario = ActivityScenario.launch(NativeTextFixtureActivity.class)) {
            AtomicReference<NativeTextFixtureActivity> activity = new AtomicReference<>();
            scenario.onActivity(value -> {
                activity.set(value); value.first.noGeometry = true; value.first.setText("");
                value.first.setMinimumHeight(120); value.first.setContentDescription("porn"); value.second.setText("ordinary safe caption");
                if (adverse == Adverse.NEW_GLYPH_SUPPORT) { value.first.setText("porn"); value.first.setContentDescription(null); }
                if (adverse == Adverse.GLYPH_MUTATION || adverse == Adverse.MISSING_GLYPHS) {
                    value.first.noGeometry = false; value.first.setContentDescription(null);
                    value.first.setText(adverse == Adverse.MISSING_GLYPHS ? "ordinary caption\nporn" : "porn ap");
                }
            });
            reader = new NativeTextReader(new NativeTextReader.Provider() {
                @Override public NativeTextReader.Tree open(CensorEngine.NativeTextInput input) throws Exception {
                    long until = SystemClock.uptimeMillis() + 5000;
                    while (SystemClock.uptimeMillis() < until) {
                        AccessibilityNodeInfo root = automation.getRootInActiveWindow();
                        if (root != null) {
                            if ("com.subhub.app".contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) {
                                DisplayMetrics metrics = activity.get().getResources().getDisplayMetrics();
                                return new NativeTextReader.Tree(root, metrics.widthPixels, metrics.heightPixels, 0);
                            }
                            root.recycle();
                        }
                        Thread.sleep(10);
                    }
                    throw new IllegalStateException("Fixture root unavailable");
                }
                @Override public boolean current(CensorEngine.NativeTextInput input, NativeTextReader.Tree tree) {
                    return !input.cancelled();
                }
                @Override public boolean locallyCurrent(CensorEngine.NativeTextInput input, NativeTextReader.Tree tree) {
                    if (!cancelTraversal) return !input.cancelled();
                    held.countDown();
                    try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Held traversal not released"); }
                    catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
                    return true; // The reader must independently recheck cancellation after the callback.
                }
                @Override public boolean current(CensorEngine.NativeTextInput input, NativeTextReader.Tree tree, NativeTextReader.Boundary boundary) {
                    if (confirming.get() && boundary == (lateScope ? NativeTextReader.Boundary.RESULT_END
                            : NativeTextReader.Boundary.BEFORE_FINAL_NODE_REFRESH) && finalChecks.incrementAndGet() == 1) {
                        heldAt.set(SystemClock.uptimeMillis());
                        held.countDown();
                        try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Held provider not released"); }
                        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
                    }
                    return !input.cancelled(); // Deliberately no soft-revision rejection: exact-node validation owns this proof.
                }
            }, new SmutTextClassifier(context), new TextSmutConfig(true, TextSmutConfig.SENSITIVITY_BALANCED,
                    TextSmutConfig.DEFAULT_CATEGORIES), false, false, executor, 48);
            NativeTextReader selectedReader = reader;
            CensorEngine.NativeTextWorker worker = (input, completion) -> selectedReader.start(input, new CensorEngine.NativeTextCompletion() {
                @Override public void costStarted(com.subhub.app.censor.engine.NativeReadCost.Start value) { completion.costStarted(value); }
                @Override public void cost(com.subhub.app.censor.engine.NativeReadCost value) {
                    terminals.incrementAndGet();
                    assertEquals(cancelTraversal ? com.subhub.app.censor.engine.NativeReadCost.Terminal.CANCELLED
                            : lateScope ? com.subhub.app.censor.engine.NativeReadCost.Terminal.SUCCESS
                            : com.subhub.app.censor.engine.NativeReadCost.Terminal.FAILURE, value.terminal);
                    completion.cost(value);
                }
                @Override public void success(NativeTextResult result) { attempted.set(result); successes.incrementAndGet(); completion.success(result); terminal.countDown(); }
                @Override public void failure() { failures.incrementAndGet(); completion.failure(); terminal.countDown(); }
                @Override public void cancelled() { cancellations.incrementAndGet(); completion.cancelled(); terminal.countDown(); }
                @Override public void resourceFailure() { completion.resourceFailure(); terminal.countDown(); }
            });
            engine = new CensorEngine<>(new CensorEngine.Policy(334, 500, 4, 0, false),
                    new SceneEngine.Policy(16, 100, 64, 256, 5000, 20000, .5), SystemClock::uptimeMillis,
                    (operation, delay) -> { java.util.concurrent.Future<?> future = executor.schedule(operation, delay, TimeUnit.MILLISECONDS); return () -> future.cancel(false); },
                    (request, callback) -> fail("Native-only adverse control requested capture"),
                    Collections.singletonList(new CensorEngine.LaneWorker<>(SceneEngine.Lane.FAST, Runnable::run,
                            (input, completion) -> fail("Native-only adverse control requested image inference"))), Runnable::run,
                    new CensorEngine.Observer() {
                        @Override public void drained() { drained.incrementAndGet(); }
                        @Override public void nativeTextAccepted(com.subhub.app.censor.engine.NativeTextFrame frame) { admitted.incrementAndGet(); }
                    },
                    new CensorEngine.NativeTextLane(99, 120, 1000, executor, worker));
            engine.executionControls(334, false, EnumSet.noneOf(SceneEngine.Lane.class)); engine.start(42);
            assertTrue("Actual selected provider boundary was not held", held.await(8, TimeUnit.SECONDS));
            if (cancelTraversal) engine.close();
            else if (lateScope) {
                // This is a deliberate platform-check hold, not a changed product deadline.
                while (SystemClock.uptimeMillis() <= heldAt.get() + 1100) SystemClock.sleep(10);
                assertEquals(0, admitted.get());
            } else if (adverse == Adverse.NEW_GLYPH_SUPPORT) {
                scenario.onActivity(value -> value.first.noGeometry = false);
            } else if (adverse == Adverse.GLYPH_MUTATION) {
                scenario.onActivity(value -> value.first.swapDuringGeometry = true);
            } else if (adverse == Adverse.MISSING_GLYPHS) {
                scenario.onActivity(value -> value.first.omitMatchedLastLine = true);
            } else {
                scenario.onActivity(value -> value.first.setContentDescription("ordinary safe caption"));
                engine.requestNativeTextAt(SystemClock.uptimeMillis(), CensorEngine.NativeTextChange.TREE_DIRTY);
                assertTrue("Soft mutation must not cancel the operation being tested", engine.nativeTextInFlight());
            }
            release.countDown();
            assertTrue("Actual reader terminal missing", terminal.await(8, TimeUnit.SECONDS)); engine.close();
            assertEquals("Only the deliberately aged observation may finish a read", lateScope ? 1 : 0, successes.get());
            if (lateScope) {
                NativeTextResult result = attempted.get(); assertNotNull(result); assertEquals(1, result.candidates.size());
                assertFalse(result.candidates.get(0).exactGeometry);
                assertTrue("Node clock precedes the slow final scope check", result.candidates.get(0).validatedAtMillis <= heldAt.get());
                assertTrue("Actual observation expired before final scope returned", result.readFinishedAtMillis
                        - result.candidates.get(0).validatedAtMillis > 1000);
            } else assertNull(attempted.get());
            assertEquals("No expired or mutated frame may be admitted", 0, admitted.get());
            assertEquals(cancelTraversal || lateScope ? 0 : 1, failures.get());
            assertEquals(cancelTraversal ? 1 : 0, cancellations.get()); assertEquals(1, terminals.get()); assertEquals(1, drained.get());
        } catch (Exception | Error failure) {
            primaryFailure = failure; throw failure;
        } finally {
            try {
                release.countDown();
                try {
                    if (engine != null) engine.close();
                    AtomicReference<Throwable> closeFailure = new AtomicReference<>(); NativeTextReader selectedReader = reader;
                    if (selectedReader != null) executor.execute(() -> { try { selectedReader.close(); } catch (Throwable failure) { closeFailure.set(failure); } });
                    executor.shutdown(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)); assertNull(closeFailure.get());
                } finally {
                    UiAutomation inactive = InstrumentationRegistry.getInstrumentation().getUiAutomation(UiAutomation.FLAG_DONT_USE_ACCESSIBILITY);
                    inactive.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS");
                    try {
                        try { assertTrue(android.provider.Settings.Secure.putString(context.getContentResolver(), "enabled_accessibility_services", savedServices)); }
                        finally { assertTrue(android.provider.Settings.Secure.putString(context.getContentResolver(), "accessibility_enabled", savedEnabled)); }
                    } finally { inactive.dropShellPermissionIdentity(); }
                    assertEquals(savedServices, android.provider.Settings.Secure.getString(context.getContentResolver(), "enabled_accessibility_services"));
                    assertEquals(savedEnabled, android.provider.Settings.Secure.getString(context.getContentResolver(), "accessibility_enabled"));
                }
            } catch (Exception | Error cleanupFailure) {
                if (primaryFailure == null) throw cleanupFailure;
                primaryFailure.addSuppressed(cleanupFailure);
            }
        }
    }

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
