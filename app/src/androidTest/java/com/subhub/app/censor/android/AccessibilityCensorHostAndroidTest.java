package com.subhub.app.censor.android;

import static org.junit.Assert.*;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Looper;
import android.os.SystemClock;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;

import com.subhub.app.censor.engine.CensorEngine;
import com.subhub.app.censor.engine.PixelGeometry;
import com.subhub.app.censor.engine.SceneEngine;
import com.subhub.app.censor.engine.ProtectionScope;
import com.subhub.app.detection.BBox;
import com.subhub.app.detection.Detection;
import com.subhub.app.detection.DetectionEngine;
import com.subhub.app.detection.DetectorConfig;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Real Android executors/pixels and lifecycle, with an explicit fake API/model boundary. */
@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion = 30)
public final class AccessibilityCensorHostAndroidTest {
    private static final CensorEngine.WorkTiming TIMING = new CensorEngine.WorkTiming(1, 2, 1);
    /** Only the three explicitly synthetic resource-failure fixtures; cleared by process exit, never a fake drain. */
    private static final List<Harness> EXPECTED_QUARANTINES = new CopyOnWriteArrayList<>();

    @Test public void fastReadinessCompletesBeforeTheFirstCaptureCallbackWithoutUsingPixels() throws Exception {
        try (Harness h = new Harness()) {
            h.start(10, config(2, .3f)); Call first = h.next();
            assertTrue(h.fastReady.await(5, TimeUnit.SECONDS));
            assertTrue(h.host.captureInFlight()); assertEquals(1, h.fastInitializations.get());
            assertEquals(1, h.models.size()); assertEquals(0, h.models.get(0).runs.get());
            assertEquals(0, h.acceptedFast.get()); assertFalse(h.initializedOnMain.get());
            first.deliver(); h.awaitView(10);
            assertEquals(1, h.models.get(0).runs.get()); assertEquals(1, h.fastReadyNotifications.get());
        }
    }

    @Test public void captureDispatchAndOwnedPixelsSurviveHeldFastReadiness() throws Exception {
        try (Harness h = new Harness()) {
            h.blockInitialization = true;
            h.start(10, config(2, .3f)); Call first = h.next();
            assertTrue(h.initializationEntered.await(5, TimeUnit.SECONDS));
            Bitmap source = first.deliver(); main(() -> { });
            assertFalse(source.isRecycled()); assertTrue(h.models.isEmpty());
            assertEquals(0, h.acceptedFast.get()); assertEquals(1, h.initializationRelease.getCount());
            h.initializationRelease.countDown(); h.awaitView(10);
            assertEquals(1, h.fastInitializations.get()); assertEquals(1, h.models.get(0).runs.get());
        }
    }

    @Test public void closeWaitsForSourceFreeReadinessAndNeverAnnouncesItAfterClose() throws Exception {
        try (Harness h = new Harness()) {
            h.blockInitialization = true;
            h.start(10, config(2, .3f)); h.next();
            assertTrue(h.initializationEntered.await(5, TimeUnit.SECONDS));
            main(h.host::close); h.failUndelivered();
            assertFalse(h.terminated.await(80, TimeUnit.MILLISECONDS));
            h.initializationRelease.countDown();
            assertTrue(h.terminated.await(5, TimeUnit.SECONDS));
            assertEquals(1, h.models.size()); assertEquals(1, h.models.get(0).closes.get());
            assertEquals(0, h.models.get(0).runs.get()); assertEquals(0, h.fastReadyNotifications.get());
            assertTrue(h.disposed.get());
        }
    }

    @Test public void failedReadinessIsNotRetriedByQueuedPixelsButExplicitRestartCanRetry() throws Exception {
        try (Harness h = new Harness()) {
            h.blockInitialization = true; h.failFastInitialization = true;
            h.start(10, config(2, .3f)); Bitmap rejected = h.next().deliver();
            assertTrue(h.initializationEntered.await(5, TimeUnit.SECONDS));
            h.initializationRelease.countDown(); assertTrue(h.fastUnavailable.await(5, TimeUnit.SECONDS));
            main(() -> { h.host.requestCapture(); h.host.requestCapture(); });
            assertNull(h.requests.poll(80, TimeUnit.MILLISECONDS));
            assertEquals(1, h.fastInitializations.get()); assertTrue(h.models.isEmpty());
            long deadline = SystemClock.uptimeMillis() + 5000;
            while (!rejected.isRecycled() && SystemClock.uptimeMillis() < deadline) Thread.sleep(10);
            assertTrue(rejected.isRecycled());
            h.failFastInitialization = false;
            main(h.host::stop); h.start(10, config(2, .3f)); h.next().deliver(); h.awaitView(10);
            assertEquals(2, h.fastInitializations.get()); assertEquals(1, h.models.size());
            assertEquals(1, h.models.get(0).runs.get());
        }
    }

    @Test public void oldReadinessFailureCannotDisableANewSessionKey() throws Exception {
        try (Harness h = new Harness()) {
            h.blockInitialization = true; h.failFirstFastInitialization = true;
            h.start(10, config(2, .3f)); Bitmap old = h.next().deliver();
            assertTrue(h.initializationEntered.await(5, TimeUnit.SECONDS));
            main(() -> h.host.changeScope(11, config(3, .7f), false));
            h.next().deliver(); h.initializationRelease.countDown(); h.awaitView(11);
            assertEquals(2, h.fastInitializations.get()); assertEquals(1, h.models.size());
            assertEquals(3, h.models.get(0).config.getInferenceThreads());
            assertTrue(old.isRecycled()); assertEquals(0, h.fastUnavailableNotifications.get());
            assertTrue(h.presentedTokens.stream().allMatch(token -> token == 11));
        }
    }

    @Test public void unknownReadinessErrorIsAResourceFailureNotModelUnavailableOrDisposed() throws Exception {
        try (Harness h = new Harness(true)) {
            h.blockInitialization = true; h.errorFastInitialization = true;
            h.start(10, config(2, .3f)); h.next();
            assertTrue(h.initializationEntered.await(5, TimeUnit.SECONDS));
            h.initializationRelease.countDown(); assertTrue(h.resourceFailed.await(5, TimeUnit.SECONDS));
            assertEquals(0, h.fastUnavailableNotifications.get()); assertEquals(1, h.fastInitializations.get());
            main(h.host::close); h.failUndelivered();
            h.assertQuarantined();
        }
    }

    @Test public void unknownQualityReadinessErrorQuarantinesAfterFastWithoutAnnouncingUnavailableOrDrain() throws Exception {
        try (Harness h = new Harness(true)) {
            h.qualityEnabled = true; h.blockQualityInitialization = true; h.errorQualityInitialization = true;
            h.expectedFastAtQuarantine = 1;
            h.start(10, config(2, .3f)); Bitmap first = h.next().deliver();
            assertTrue(h.qualityInitializationEntered.await(5, TimeUnit.SECONDS));
            assertTrue(h.firstFastBatch.await(5, TimeUnit.SECONDS));
            assertEquals(1, h.acceptedFast.get());
            h.next(); // Leave the next fake callback outstanding while readiness fails.
            h.qualityInitializationRelease.countDown();
            assertTrue("Unknown QUALITY startup Error must reach resource quarantine", h.resourceFailed.await(5, TimeUnit.SECONDS));
            assertNull("The Error must not escape its owned executor", h.uncaughtQualityError.get());
            assertEquals(0, h.qualityUnavailableNotifications.get());
            assertEquals(1, h.qualityInitializations.get()); assertEquals(1, h.qualityReady.getCount());
            main(h.host::close); h.failUndelivered(); h.assertQuarantined();
            awaitRecycled(first);
        }
    }

    @Test public void ordinaryQualityReadinessFailureLeavesFastWorkingWithoutResourceQuarantine() throws Exception {
        try (Harness h = new Harness()) {
            h.qualityEnabled = true; h.failQualityInitialization = true;
            h.start(10, config(2, .3f)); h.next().deliver();
            assertTrue(h.qualityUnavailable.await(5, TimeUnit.SECONDS));
            h.next().deliver(); assertTrue(h.secondFastBatch.await(5, TimeUnit.SECONDS));
            main(h.host::close); h.failUndelivered();
            assertTrue(h.terminated.await(5, TimeUnit.SECONDS)); assertTrue(h.disposed.get());
            assertEquals(2, h.acceptedFast.get()); assertEquals(1, h.qualityInitializations.get());
            assertEquals(1, h.qualityUnavailableNotifications.get());
            assertEquals(0, h.resourceFailureNotifications.get()); assertEquals(1, h.qualityReady.getCount());
            assertNull(h.uncaughtQualityError.get());
        }
    }

    @Test public void warmedOnlyModelCloseErrorCannotReportDisposedResources() throws Exception {
        try (Harness h = new Harness()) {
            h.start(10, config(2, .3f)); h.next();
            assertTrue(h.fastReady.await(5, TimeUnit.SECONDS));
            assertEquals(0, h.models.get(0).runs.get()); h.models.get(0).errorClose = true;
            main(h.host::close); h.failUndelivered();
            assertTrue(h.terminated.await(5, TimeUnit.SECONDS));
            assertFalse(h.disposed.get()); assertEquals(1, h.models.get(0).closes.get());
        }
    }

    @Test public void eagerChangedKeyRetirementFailureIsNotOrdinaryModelUnavailable() throws Exception {
        try (Harness h = new Harness(true)) {
            h.start(10, config(2, .3f)); Call old = h.next();
            assertTrue(h.fastReady.await(5, TimeUnit.SECONDS)); h.models.get(0).failClose = true;
            main(() -> h.host.changeScope(11, config(3, .3f), false));
            assertTrue(h.resourceFailed.await(5, TimeUnit.SECONDS));
            old.fail(); assertEquals(1, h.models.size());
            assertEquals(1, h.models.get(0).closes.get()); assertEquals(0, h.fastUnavailableNotifications.get());
            main(h.host::close); h.failUndelivered();
            h.assertQuarantined();
            assertEquals(1, h.models.get(0).closes.get());
        }
    }

    @Test public void completeEmptyFastStillInitializesQualityForQualityOnlyDetections() throws Exception {
        try (Harness h = new Harness()) {
            h.emptyFast = true; h.qualityEnabled = true;
            h.start(10, config(2, .3f)); h.next().deliver();
            assertTrue(h.completedFastJob.await(5, TimeUnit.SECONDS));
            assertTrue(h.qualityReady.await(5, TimeUnit.SECONDS));
            assertEquals(1, h.acceptedFast.get());
            h.next().deliver(); assertTrue(h.qualityEntered.await(5, TimeUnit.SECONDS));
            assertTrue("quality-only positive did not finish its actual batch",
                    h.completedQualityBatch.await(5, TimeUnit.SECONDS));
            assertEquals(1, h.acceptedQualityPositives.get());
            assertEquals(1, h.models.stream().filter(model -> model.lane == SceneEngine.Lane.QUALITY).count());
        }
    }

    @Test public void protectionScopeFencesSameTokenPreservesSpacingAndDoesNotReinitializeTheModel() throws Exception {
        try (Harness h = new Harness()) {
            main(() -> h.host.start(10, config(2, .3f), false, null, false, false, ProtectionScope.lab(7)));
            Call old = h.next(); long epoch = h.host.epoch();
            assertEquals(ProtectionScope.lab(7), old.request.protectionScope);
            main(() -> h.host.changeProtectionScope(ProtectionScope.normal(8)));
            assertEquals(epoch + 1, h.host.epoch()); assertTrue(h.host.captureInFlight());
            assertNull(h.requests.poll(80, TimeUnit.MILLISECONDS));
            Bitmap discarded = old.deliver(); Call current = h.next(); assertTrue(discarded.isRecycled());
            assertEquals(10, current.request.geometry); assertEquals(ProtectionScope.normal(8), current.request.protectionScope);
            assertTrue(current.request.requestedAtMillis - old.request.requestedAtMillis >= 334);
            main(() -> h.host.canvasSamplingControls(true)); Bitmap pixels = current.deliver(); h.awaitView(10);
            try (CensorEngine<PixelFrame>.RenderView view = h.host.acquireRenderView()) {
                assertEquals(ProtectionScope.normal(8), view.snapshot.destination.protectionScope);
                assertSame(view.snapshot.destination, view.canvasSource); assertSame(pixels, view.canvasPixels().bitmap);
            }
            assertEquals(1, h.models.size()); long currentEpoch = h.host.epoch();
            main(() -> h.host.changeProtectionScope(ProtectionScope.normal(8)));
            assertEquals(currentEpoch, h.host.epoch()); // Equal value is not a new protection session.
        }
    }

    @Test public void changedScopeWaitsForTheOldCallbackAndPreservesPlatformSpacing() throws Exception {
        try (Harness h = new Harness()) {
            h.start(10, config(2, .3f)); Call old = h.next();
            main(() -> h.host.changeScope(11, config(2, .3f), false));
            assertTrue(h.host.captureInFlight()); assertNull(h.requests.poll(80, TimeUnit.MILLISECONDS));
            Bitmap discarded = old.deliver();
            Call current = h.next(); assertTrue(discarded.isRecycled());
            assertTrue(current.request.requestedAtMillis - old.request.requestedAtMillis >= 334);
            assertEquals(11, current.request.geometry); current.deliver();
            h.awaitView(11); assertEquals(1, h.models.size());
        }
    }

    @Test public void sameSessionSurvivesForegroundChangesAndUpdatesPostprocessing() throws Exception {
        try (Harness h = new Harness()) {
            h.start(10, config(2, .3f)); h.next().deliver(); h.awaitView(10);
            Model first = h.models.get(0);
            main(() -> h.host.changeScope(11, config(2, .7f), false));
            h.next().deliver(); h.awaitView(11);
            assertEquals(1, h.models.size()); assertEquals(.7f, first.config.getConfidenceThreshold(), 0);
            assertEquals(0, first.closes.get());
            assertFalse("model initialization ran on main", h.initializedOnMain.get());
        }
    }

    @Test public void changedThreadCountDoesNotCloseTheSessionOrPixelsDuringInference() throws Exception {
        try (Harness h = new Harness()) {
            h.blockInference = true;
            h.start(10, config(2, .3f)); Bitmap oldPixels = h.next().deliver();
            assertTrue(h.inferenceEntered.await(5, TimeUnit.SECONDS)); Model first = h.models.get(0);
            main(() -> h.host.changeScope(11, config(3, .3f), false));
            assertFalse(oldPixels.isRecycled()); assertEquals(0, first.closes.get());
            h.next().deliver(); h.inferenceRelease.countDown(); h.awaitView(11);
            assertEquals(2, h.models.size()); assertEquals(1, first.closes.get());
            assertTrue(oldPixels.isRecycled());
            assertEquals(3, h.models.get(1).config.getInferenceThreads());
        }
    }

    @Test public void renderLeaseDelaysSessionAndExecutorDisposalUntilItsRealRelease() throws Exception {
        try (Harness h = new Harness()) {
            h.start(10, config(2, .3f)); Bitmap source = h.next().deliver(); h.awaitView(10);
            CensorEngine<PixelFrame>.RenderView view = h.host.acquireRenderView(); assertNotNull(view);
            main(h.host::close); h.failUndelivered();
            assertFalse(h.terminated.await(80, TimeUnit.MILLISECONDS));
            assertFalse(source.isRecycled()); assertEquals(0, h.models.get(0).closes.get());
            view.close(); assertTrue(h.terminated.await(5, TimeUnit.SECONDS));
            assertTrue(source.isRecycled()); assertEquals(1, h.models.get(0).closes.get());
            assertFalse(h.models.get(0).closedOnMain.get()); assertTrue(h.disposed.get());
        }
    }

    @Test public void scopeChangeDuringLazyInitializationCannotPublishTheOldSource() throws Exception {
        try (Harness h = new Harness()) {
            h.blockInitialization = true;
            h.start(10, config(2, .3f)); Bitmap oldPixels = h.next().deliver();
            assertTrue(h.initializationEntered.await(5, TimeUnit.SECONDS));
            main(() -> h.host.changeScope(11, config(2, .6f), false)); h.next().deliver();
            h.initializationRelease.countDown(); h.awaitView(11);
            assertTrue(oldPixels.isRecycled()); assertEquals(1, h.models.size());
            assertEquals(.6f, h.models.get(0).config.getConfidenceThreshold(), 0);
            assertEquals(1, h.models.get(0).runs.get());
            assertTrue(h.presentedTokens.stream().allMatch(token -> token == 11));
        }
    }

    @Test public void providerLabelsCannotGrantVisualOverlapOrCancelAdmittedQualityOnRefresh() throws Exception {
        for (String provider : new String[]{"CPU", "NNAPI", "UNKNOWN"}) {
            try (Harness h = new Harness()) {
                h.qualityEnabled = true; h.blockQuality = true; h.qualityProvider = provider;
                h.start(10, config(2, .3f)); h.next().deliver(); h.awaitView(10);
                assertTrue(h.qualityReady.await(5, TimeUnit.SECONDS));
                Call qualitySource = h.next(); qualitySource.deliver();
                assertTrue(h.qualityEntered.await(5, TimeUnit.SECONDS));
                // Drain provider admission posts before the next physical capture.
                main(() -> { });
                Call superseded = h.next(); superseded.deliver();
                Call latest = h.next(); latest.deliver(); h.next(); // Actual capture lane processed both refreshes.
                Model fast = h.models.stream().filter(model -> model.lane == SceneEngine.Lane.FAST)
                        .findFirst().orElseThrow(AssertionError::new);
                assertEquals("provider label permitted visual overlap", 2, fast.runs.get());
                assertFalse(h.visualOverlap.get());
                h.qualityRelease.countDown();
                assertTrue(h.completedQualityBatch.await(5, TimeUnit.SECONDS));
                long deadline = SystemClock.uptimeMillis() + 5000;
                while (h.acceptedFast.get() < 3 && SystemClock.uptimeMillis() < deadline) Thread.sleep(10);
                assertEquals(3, fast.runs.get());
                assertEquals(3, h.acceptedFast.get());
                assertTrue("latest pending FAST request did not complete",
                        h.acceptedFastSources.contains(latest.request.identity));
                assertFalse("superseded pending FAST request ran",
                        h.acceptedFastSources.contains(superseded.request.identity));
                assertTrue("capture refresh discarded the admitted QUALITY source",
                        h.acceptedQualitySources.contains(qualitySource.request.identity));
                assertFalse(h.firstQualityWasCancelled.get()); assertFalse(h.visualOverlap.get());
            }
        }
    }

    @Test public void stoppingHeldSerializedQualityKeepsPixelsUntilActualTerminalAndRejectsItsResult() throws Exception {
        try (Harness h = new Harness()) {
            h.qualityEnabled = true; h.blockQuality = true; h.qualityProvider = "NNAPI";
            h.start(10, config(2, .3f)); h.next().deliver(); h.awaitView(10);
            assertTrue(h.qualityReady.await(5, TimeUnit.SECONDS));
            Bitmap source = h.next().deliver(); assertTrue(h.qualityEntered.await(5, TimeUnit.SECONDS));
            main(h.host::stop); main(h.host::close); h.failUndelivered();
            assertFalse(source.isRecycled()); assertFalse(h.terminated.await(80, TimeUnit.MILLISECONDS));
            Model quality = h.models.stream().filter(model -> model.lane == SceneEngine.Lane.QUALITY)
                    .findFirst().orElseThrow(AssertionError::new);
            assertEquals(0, quality.closes.get()); h.qualityRelease.countDown();
            assertTrue(h.terminated.await(5, TimeUnit.SECONDS)); assertTrue(h.disposed.get());
            assertTrue(source.isRecycled()); assertEquals(1, quality.closes.get());
            assertTrue(h.firstQualityWasCancelled.get()); assertEquals(0, h.acceptedQualityPositives.get());
            assertFalse(h.visualOverlap.get());
        }
    }

    @Test public void nativeCloseFailureIsNotReportedAsDisposedResources() throws Exception {
        try (Harness h = new Harness()) {
            h.start(10, config(2, .3f)); h.next().deliver(); h.awaitView(10);
            h.models.get(0).failClose = true;
            main(h.host::close); h.failUndelivered();
            assertTrue(h.terminated.await(5, TimeUnit.SECONDS)); assertFalse(h.disposed.get());
        }
    }

    @Test public void optionalQualityInitializationCannotStallSubsequentFastFrames() throws Exception {
        try (Harness h = new Harness()) {
            h.qualityEnabled = true; h.blockQualityInitialization = true;
            h.start(10, config(2, .3f)); h.next().deliver(); h.awaitView(10);
            assertTrue(h.qualityInitializationEntered.await(5, TimeUnit.SECONDS));
            h.next().deliver();
            Model fast = h.models.stream().filter(model -> model.lane == SceneEngine.Lane.FAST)
                    .findFirst().orElseThrow(AssertionError::new);
            long deadline = SystemClock.uptimeMillis() + 5000;
            while (fast.runs.get() < 2 && SystemClock.uptimeMillis() < deadline) Thread.sleep(10);
            assertEquals("optional model initialization blocked fast", 2, fast.runs.get());
            assertEquals(1, h.qualityInitializationRelease.getCount());
        }
    }

    @Test public void androidExpiryWakeClearsWhileTheNextScreenshotCallbackIsOutstanding() throws Exception {
        try (Harness h = new Harness(1000)) {
            h.start(10, config(2, .3f)); Bitmap source = h.next().deliver(); h.awaitView(10);
            CensorEngine<PixelFrame>.RenderView held = h.host.acquireRenderView(); assertNotNull(held);
            try {
                h.next(); // Do not deliver this callback or poll a draw to provoke expiration.
                assertTrue(h.emptyPresentation.await(5, TimeUnit.SECONDS));
                assertTrue(h.host.captureInFlight()); assertFalse(source.isRecycled());
                assertEquals(1, h.models.size()); assertEquals(1, h.models.get(0).runs.get());
                try (CensorEngine<PixelFrame>.RenderView current = h.host.acquireRenderView()) {
                    assertNotNull(current); assertTrue(current.snapshot.groups.isEmpty());
                    assertNull(current.sourcePixels(held.snapshot.destination.sequence));
                }
            } finally { held.close(); }
            assertTrue(source.isRecycled());
        }
    }

    @Test public void androidUiDeliveryDoesNotFlashEvidenceExpiredWhileTheMainLooperWasHeld() throws Exception {
        try (Harness h = new Harness(200)) {
            h.blockInference = true;
            h.start(10, config(2, .3f)); h.next().deliver();
            assertTrue(h.inferenceEntered.await(5, TimeUnit.SECONDS));
            main(() -> {
                h.inferenceRelease.countDown();
                try { assertTrue(h.completedFastJob.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
                // Hold only this controlled fixture's UI past the source-local deadline.
                SystemClock.sleep(201);
            });
            assertTrue(h.emptyPresentation.await(5, TimeUnit.SECONDS));
            assertEquals("expired coverage reached Android presentation", 0, h.visiblePresentations.get());
            assertEquals(1, h.models.get(0).runs.get());
        }
    }

    @Test public void imageBasisMovesReadyEvidenceDuringAndroidInferenceAndSurvivesBitmapRetirement() throws Exception {
        try (Harness h = new Harness()) {
            h.start(10, config(2, .3f)); Call first = h.next(); Bitmap evidence = first.deliver(); h.awaitView(10);
            h.holdSecondInference = true; h.failSecondInference = true;
            Call second = h.next(); Bitmap later = second.deliver();
            assertTrue(h.secondInferenceEntered.await(5, TimeUnit.SECONDS));
            assertFalse(later.isRecycled());
            assertTrue(h.host.motion(imageMap(first, second, -10)));
            h.awaitMappedEvidence(first.request.identity, second.request.identity, 10);
            try (CensorEngine<PixelFrame>.RenderView view = h.host.acquireRenderView()) {
                SceneEngine.Mask mask = onlyMask(view);
                assertEquals(first.request.identity, mask.evidenceSource.sequence);
                assertSame(evidence, view.sourcePixels(first.request.identity).bitmap);
                assertNull("A newer geometry basis is not the effect sampling source",
                        view.sourcePixels(second.request.identity));
                assertEquals("Only A has an accepted detector batch", 1, h.acceptedFast.get());
                assertEquals(1, h.secondInferenceRelease.getCount());
            }
            h.secondInferenceRelease.countDown();
            long deadline = SystemClock.uptimeMillis() + 5000;
            while (!later.isRecycled() && SystemClock.uptimeMillis() < deadline) Thread.sleep(10);
            assertTrue("Failed B model work releases its exact bitmap", later.isRecycled());
            assertFalse("A stays owned by the actual presentation", evidence.isRecycled());
            h.awaitMappedEvidence(first.request.identity, second.request.identity, 10);
            assertEquals("The metadata pin does not manufacture B evidence", 1, h.acceptedFast.get());
        }
    }

    @Test public void imageBasisBeforeTheFirstAndroidResultDoesNotWaitForTheNextDetector() throws Exception {
        try (Harness h = new Harness()) {
            h.blockInference = true; h.holdSecondInference = true;
            h.start(10, config(2, .3f)); Call first = h.next(); Bitmap evidence = first.deliver();
            assertTrue(h.inferenceEntered.await(5, TimeUnit.SECONDS));
            Call second = h.next(); second.deliver();
            // The actual API callback executor must finish B adoption before its source can map.
            long deadline = SystemClock.uptimeMillis() + 5000;
            boolean mapped = false;
            while (!mapped && SystemClock.uptimeMillis() < deadline) {
                mapped = h.host.motion(imageMap(first, second, -10));
                if (!mapped) Thread.sleep(10);
            }
            assertTrue("Both exact capture sources were admitted", mapped);
            assertEquals(0, h.acceptedFast.get()); assertEquals(0, h.visiblePresentations.get());
            h.inferenceRelease.countDown();
            assertTrue(h.secondInferenceEntered.await(5, TimeUnit.SECONDS));
            h.awaitMappedEvidence(first.request.identity, second.request.identity, 10);
            try (CensorEngine<PixelFrame>.RenderView view = h.host.acquireRenderView()) {
                assertEquals(first.request.identity, onlyMask(view).evidenceSource.sequence);
                assertSame(evidence, view.sourcePixels(first.request.identity).bitmap);
                assertNull(view.sourcePixels(second.request.identity));
            }
            assertEquals("First FAST becomes visible while B model work remains held", 1, h.acceptedFast.get());
            assertEquals(1, h.secondInferenceRelease.getCount());
        }
    }

    @Test public void atomicAndroidPairQualifiesHeaderAndBodyBeforeFirstFastAndKeepsOriginalSampling() throws Exception {
        try (Harness h = new Harness()) {
            h.mixedEvidence = true; h.blockInference = true;
            h.holdSecondInference = true; h.failSecondInference = true;
            h.start(10, config(2, .3f)); Call first = h.next(); Bitmap evidence = first.deliver();
            assertTrue(h.inferenceEntered.await(5, TimeUnit.SECONDS));
            Call second = h.next(); Bitmap later = second.deliver(); h.next(); // Fence B adoption.
            SceneEngine.MotionPair pair = new SceneEngine.MotionPair(Arrays.asList(
                    new SceneEngine.Motion(first.request.epoch, first.request.identity, second.request.identity,
                            AccessibilityCensorHost.VISUAL_PARTITION, 1, SceneEngine.MotionAuthority.IMAGE_CORRESPONDENCE,
                            new SceneEngine.Region(0, 0, 64, 16), 0, 0),
                    new SceneEngine.Motion(first.request.epoch, first.request.identity, second.request.identity,
                            AccessibilityCensorHost.VISUAL_PARTITION, 1, SceneEngine.MotionAuthority.IMAGE_CORRESPONDENCE,
                            new SceneEngine.Region(0, 50, 64, 128), 0, -32)));
            assertTrue(h.host.motion(pair)); assertEquals(0, h.acceptedFast.get());
            assertNull(h.host.acquireRenderView());
            h.inferenceRelease.countDown(); assertTrue(h.secondInferenceEntered.await(5, TimeUnit.SECONDS));
            h.awaitAtomicMixedEvidence(first.request.identity, second.request.identity);
            try (CensorEngine<PixelFrame>.RenderView view = h.host.acquireRenderView()) {
                assertEquals(first.request.identity, view.snapshot.destination.sequence);
                assertSame(evidence, view.sourcePixels(first.request.identity).bitmap);
                assertNull("B geometry is not B sampling", view.sourcePixels(second.request.identity));
                assertEquals(1, h.acceptedFast.get()); assertEquals(1, h.secondInferenceRelease.getCount());
            }
            h.secondInferenceRelease.countDown(); awaitRecycled(later);
            assertFalse(evidence.isRecycled());
            h.awaitAtomicMixedEvidence(first.request.identity, second.request.identity);
        }
    }

    @Test public void uncertainAndroidPairBeforeFirstFastStaysPerMemberAndOwnsOnlyOriginalPixels() throws Exception {
        try (Harness h = new Harness()) {
            h.mixedEvidence = true; h.blockInference = true;
            h.holdSecondInference = true; h.failSecondInference = true;
            h.start(10, config(2, .3f)); Call first = h.next(); Bitmap evidence = first.deliver();
            assertTrue(h.inferenceEntered.await(5, TimeUnit.SECONDS));
            Call second = h.next(); Bitmap later = second.deliver(); h.next();
            com.subhub.app.censor.engine.ImagePresentationEstimate estimate =
                    new com.subhub.app.censor.engine.ImagePresentationEstimate(first.request.epoch,
                            first.request.identity, second.request.identity, AccessibilityCensorHost.VISUAL_PARTITION, 1,
                            Arrays.asList(
                                    new com.subhub.app.censor.engine.ImagePresentationEstimate.Piece(
                                            new SceneEngine.Region(0, 0, 64, 16), 0, 0, 0, 0),
                                    new com.subhub.app.censor.engine.ImagePresentationEstimate.Piece(
                                            new SceneEngine.Region(8, 50, 40, 100), 0, 0, -33, -31)));
            assertTrue(h.host.presentationEstimate(estimate)); assertEquals(0, h.acceptedFast.get());
            assertNull(h.host.acquireRenderView());
            h.inferenceRelease.countDown(); assertTrue(h.secondInferenceEntered.await(5, TimeUnit.SECONDS));
            awaitUncertainMixed(h, first.request.identity, second.request.identity, evidence);
            assertEquals(1, h.acceptedFast.get()); assertEquals(1, h.secondInferenceRelease.getCount());
            h.secondInferenceRelease.countDown(); awaitRecycled(later);
            assertFalse(evidence.isRecycled());
            awaitUncertainMixed(h, first.request.identity, second.request.identity, evidence);
        }
    }

    private static void awaitUncertainMixed(Harness h, long evidenceSource, long geometry, Bitmap evidence)
            throws InterruptedException {
        long deadline = SystemClock.uptimeMillis() + 5000;
        while (SystemClock.uptimeMillis() < deadline) {
            try (CensorEngine<PixelFrame>.RenderView view = h.host.acquireRenderView()) {
                if (view != null) {
                    int count = 0; boolean header = false, body = false, typed = true;
                    for (SceneEngine.Group group : view.snapshot.groups) for (SceneEngine.Mask mask : group.members) {
                        count++;
                        typed &= mask.imageEstimated && mask.estimatedMotion && !mask.physicalErrorKnown
                                && !mask.heldGeometry && mask.evidenceSource.sequence == evidenceSource
                                && mask.geometrySource.sequence == geometry;
                        header |= mask.evidenceRegion.top == 5 && mask.displayRegion.top == 5;
                        body |= mask.evidenceRegion.top == 50 && mask.displayRegion.top == 17 && mask.displayRegion.bottom == 29;
                    }
                    if (count == 2 && typed && header && body) {
                        assertEquals(evidenceSource, view.snapshot.destination.sequence);
                        assertSame(evidence, view.sourcePixels(evidenceSource).bitmap);
                        assertNull(view.sourcePixels(geometry));
                        return;
                    }
                }
            }
            Thread.sleep(10);
        }
        fail("Uncertain Android pair lost typed per-member geometry or original sampling");
    }

    @Test public void changedAndroidDisplayDimensionsResetImagePoseWithoutRecyclingActiveReaders() throws Exception {
        try (Harness h = new Harness()) {
            h.holdSecondInference = true;
            h.start(10, config(2, .3f)); Call first = h.next(); Bitmap evidence = first.deliver(); h.awaitView(10);
            Call second = h.next(); Bitmap running = second.deliver();
            assertTrue(h.secondInferenceEntered.await(5, TimeUnit.SECONDS));
            assertTrue(h.host.presentationEstimate(new com.subhub.app.censor.engine.ImagePresentationEstimate(
                    first.request.epoch, first.request.identity, second.request.identity,
                    AccessibilityCensorHost.VISUAL_PARTITION, 1, Collections.singletonList(
                            new com.subhub.app.censor.engine.ImagePresentationEstimate.Piece(
                                    new SceneEngine.Region(0, 0, 64, 128), 0, 0, 0, 0)))));
            h.awaitMappedEvidence(first.request.identity, second.request.identity, 20);
            CensorEngine<PixelFrame>.RenderView held = h.host.acquireRenderView();
            try {
                Call changed = h.next(); Bitmap discarded = changed.deliver(128, 64); awaitRecycled(discarded);
                assertNull(h.host.acquireRenderView());
                assertFalse(evidence.isRecycled()); assertFalse(running.isRecycled());
                assertSame(evidence, held.sourcePixels(first.request.identity).bitmap);
                Call fresh = h.next(); assertEquals(changed.request.epoch + 1, fresh.request.epoch);
                assertEquals(10, fresh.request.geometry); Bitmap current = fresh.deliver(128, 64);
                h.secondInferenceRelease.countDown(); awaitRecycled(running); h.awaitView(10);
                assertTrue(h.secondWasCancelled.get());
                try (CensorEngine<PixelFrame>.RenderView view = h.host.acquireRenderView()) {
                    assertEquals(fresh.request.epoch, view.snapshot.destination.epoch);
                    assertEquals(128, view.snapshot.destination.width); assertEquals(64, view.snapshot.destination.height);
                    assertFalse(onlyMask(view).imageEstimated);
                    assertSame(current, view.sourcePixels(fresh.request.identity).bitmap);
                }
                assertFalse(evidence.isRecycled());
            } finally { held.close(); }
            awaitRecycled(evidence);
        }
    }

    @Test public void partialAndroidBasisHoldsBothHeaderAndPreviouslyMovedBodyOnOriginalPixels() throws Exception {
        try (Harness h = new Harness()) {
            h.mixedEvidence = true;
            h.start(10, config(2, .3f)); Call first = h.next(); Bitmap evidence = first.deliver(); h.awaitView(10);
            h.holdSecondInference = true; h.failSecondInference = true;
            Call second = h.next(); second.deliver();
            assertTrue(h.secondInferenceEntered.await(5, TimeUnit.SECONDS));
            assertTrue(h.host.motion(new SceneEngine.Motion(first.request.epoch, first.request.identity,
                    second.request.identity, AccessibilityCensorHost.VISUAL_PARTITION, 1,
                    SceneEngine.MotionAuthority.IMAGE_CORRESPONDENCE,
                    new SceneEngine.Region(0, 40, 64, 128), 0, -40)));
            h.awaitMixedEvidence(first.request.identity, second.request.identity, false);
            Call third = h.next(); third.deliver(); h.next(); // Fence actual callback adoption of C.
            assertTrue(h.host.motion(new SceneEngine.Motion(first.request.epoch, second.request.identity,
                    third.request.identity, AccessibilityCensorHost.VISUAL_PARTITION, 1,
                    SceneEngine.MotionAuthority.IMAGE_CORRESPONDENCE,
                    new SceneEngine.Region(0, 80, 64, 128), 0, -20)));
            h.awaitMixedEvidence(first.request.identity, second.request.identity, true);
            try (CensorEngine<PixelFrame>.RenderView view = h.host.acquireRenderView()) {
                assertEquals(first.request.identity, view.snapshot.destination.sequence);
                assertSame(evidence, view.sourcePixels(first.request.identity).bitmap);
                assertNull(view.sourcePixels(second.request.identity)); assertNull(view.sourcePixels(third.request.identity));
                assertEquals(1, h.acceptedFast.get()); assertEquals(1, h.secondInferenceRelease.getCount());
            }
        }
    }

    @Test public void androidMeasuredConflictKeepsHeldAndRunningBitmapsUntilTheirRealTerminalRelease() throws Exception {
        try (Harness h = new Harness()) {
            h.start(10, config(2, .3f)); Call first = h.next(); Bitmap evidence = first.deliver(); h.awaitView(10);
            try (CensorEngine<PixelFrame>.RenderView held = h.host.acquireRenderView()) {
                assertNotNull(held); h.holdSecondInference = true;
                Call second = h.next(); Bitmap running = second.deliver();
                assertTrue(h.secondInferenceEntered.await(5, TimeUnit.SECONDS));
                Call third = h.next(); Bitmap pending = third.deliver(); Call outstanding = h.next();
                assertTrue(h.host.motion(imageMap(first, second, -10)));
                h.awaitMappedEvidence(first.request.identity, second.request.identity, 10);
                long epoch = h.host.epoch();
                SceneEngine.Motion conflict = new SceneEngine.Motion(epoch, first.request.identity, second.request.identity,
                        AccessibilityCensorHost.VISUAL_PARTITION, 2, SceneEngine.MotionAuthority.IMAGE_CORRESPONDENCE,
                        new SceneEngine.Region(0, 0, 64, 128), 0, -20);
                assertFalse(h.host.motion(conflict)); assertEquals(epoch + 1, h.host.epoch());
                assertFalse(h.host.motion(conflict)); assertEquals(epoch + 1, h.host.epoch());
                assertNull(h.host.acquireRenderView()); assertTrue(h.host.captureInFlight());
                assertFalse(evidence.isRecycled()); assertFalse(running.isRecycled()); assertTrue(pending.isRecycled());
                assertSame(evidence, held.sourcePixels(first.request.identity).bitmap);
                h.secondInferenceRelease.countDown(); awaitRecycled(running);
                assertTrue(h.secondWasCancelled.get()); assertEquals(1, h.acceptedFast.get());
                Bitmap discarded = outstanding.deliver(); Call fresh = h.next(); awaitRecycled(discarded);
                assertEquals(epoch + 1, fresh.request.epoch);
                assertTrue(fresh.request.requestedAtMillis - outstanding.request.requestedAtMillis >= 334);
                fresh.deliver(); h.awaitView(10);
                try (CensorEngine<PixelFrame>.RenderView current = h.host.acquireRenderView()) {
                    assertEquals(epoch + 1, current.snapshot.destination.epoch);
                    assertEquals(fresh.request.identity, onlyMask(current).evidenceSource.sequence);
                }
                assertFalse(evidence.isRecycled());
            }
            awaitRecycled(evidence);
        }
    }

    private static void awaitRecycled(Bitmap bitmap) throws InterruptedException {
        long deadline = SystemClock.uptimeMillis() + 5000;
        while (!bitmap.isRecycled() && SystemClock.uptimeMillis() < deadline) Thread.sleep(10);
        assertTrue("Exact Android bitmap owner did not release after its terminal boundary", bitmap.isRecycled());
    }

    private static SceneEngine.Motion imageMap(Call first, Call second, double dy) {
        assertEquals(first.request.epoch, second.request.epoch);
        return new SceneEngine.Motion(first.request.epoch, first.request.identity, second.request.identity,
                AccessibilityCensorHost.VISUAL_PARTITION, 1, SceneEngine.MotionAuthority.IMAGE_CORRESPONDENCE,
                new SceneEngine.Region(0, 0, 64, 128), 0, dy);
    }

    private static SceneEngine.Mask onlyMask(CensorEngine<PixelFrame>.RenderView view) {
        assertNotNull(view); assertEquals(1, view.snapshot.groups.size());
        assertEquals(1, view.snapshot.groups.get(0).members.size());
        return view.snapshot.groups.get(0).members.get(0);
    }

    private static DetectorConfig config(int threads, float threshold) {
        return DetectorConfig.builder().enabledCategories(Collections.singleton("face"))
                .inferenceResolution(320).inferenceThreads(threads).confidenceThreshold(threshold).build();
    }
    private static void main(Runnable action) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(action);
    }

    private static final class Call {
        final CensorEngine.CaptureRequest request;
        final CensorEngine.CaptureCallback<PixelFrame> callback;
        final Executor callbacks;
        final AtomicBoolean delivered = new AtomicBoolean();
        Call(CensorEngine.CaptureRequest request, CensorEngine.CaptureCallback<PixelFrame> callback,
                Executor callbacks) { this.request = request; this.callback = callback; this.callbacks = callbacks; }
        Bitmap deliver() { return deliver(64, 128); }
        Bitmap deliver(int width, int height) {
            assertTrue(delivered.compareAndSet(false, true));
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            PixelFrame frame = new PixelFrame(bitmap, PixelGeometry.display(width, height, width, height),
                    PixelFrame.Kind.ACCESSIBILITY_DISPLAY, -1, SystemClock.uptimeMillis(), 0);
            callbacks.execute(() -> {
                CensorEngine.Captured<PixelFrame> value = new CensorEngine.Captured<>(frame, width, height, -1, bitmap::recycle);
                try { callback.success(value); } finally { value.close(); }
            });
            return bitmap;
        }
        void fail() {
            if (delivered.compareAndSet(false, true)) callbacks.execute(() -> callback.failure(0));
        }
    }

    private static final class Model implements VisualDetectorWorker.Model {
        final Harness harness;
        final SceneEngine.Lane lane;
        final AtomicInteger closes = new AtomicInteger();
        final AtomicInteger runs = new AtomicInteger();
        final AtomicBoolean closedOnMain = new AtomicBoolean();
        volatile DetectorConfig config;
        volatile boolean failClose;
        volatile boolean errorClose;
        Model(Harness harness, SceneEngine.Lane lane, DetectorConfig config) {
            this.harness = harness; this.lane = lane; this.config = config;
        }
        @Override public String provider() { return lane == SceneEngine.Lane.QUALITY ? harness.qualityProvider : "CPU"; }
        @Override public void configure(DetectorConfig value) { config = value; }
        @Override public VisualDetectorWorker.ModelOutput infer(Bitmap bitmap, int width, int height,
                int tensorWidth, int tensorHeight, BooleanSupplier cancelled,
                Consumer<DetectionEngine.RunCancellation> binding) throws Exception {
            assertFalse(bitmap.isRecycled()); int run = runs.incrementAndGet();
            if (lane == SceneEngine.Lane.FAST && harness.qualityExecuting.get()) harness.visualOverlap.set(true);
            harness.inferenceEntered.countDown();
            if (harness.blockInference) assertTrue(harness.inferenceRelease.await(5, TimeUnit.SECONDS));
            if (lane == SceneEngine.Lane.FAST && run == 2 && harness.holdSecondInference) {
                harness.secondInferenceEntered.countDown();
                assertTrue(harness.secondInferenceRelease.await(5, TimeUnit.SECONDS));
                harness.secondWasCancelled.set(cancelled.getAsBoolean());
                if (harness.failSecondInference) throw new IllegalStateException("controlled second inference failure");
            }
            if (lane == SceneEngine.Lane.QUALITY) {
                harness.qualityExecuting.set(true);
                harness.qualityEntered.countDown();
                try {
                    if (harness.blockQuality) assertTrue(harness.qualityRelease.await(5, TimeUnit.SECONDS));
                    if (run == 1) harness.firstQualityWasCancelled.set(cancelled.getAsBoolean());
                } finally { harness.qualityExecuting.set(false); }
            }
            assertFalse(bitmap.isRecycled());
            if (lane == SceneEngine.Lane.FAST && harness.emptyFast) {
                return new VisualDetectorWorker.ModelOutput(Collections.emptyList(), true, false, TIMING);
            }
            if (harness.mixedEvidence) {
                return new VisualDetectorWorker.ModelOutput(Arrays.asList(
                        new Detection("FACE_FEMALE", "face", .9f, new BBox(10, 5, 20, 10), false, true),
                        new Detection("FACE_FEMALE", "face", .9f, new BBox(10, 50, 20, 10), false, true)),
                        true, false, TIMING);
            }
            Detection detection = new Detection("FACE_FEMALE", "face", .9f, new BBox(10, 20, 20, 30), false, true);
            return new VisualDetectorWorker.ModelOutput(Collections.singletonList(detection), true, false, TIMING);
        }
        @Override public void close() {
            closedOnMain.set(Looper.myLooper() == Looper.getMainLooper()); closes.incrementAndGet();
            if (errorClose) throw new AssertionError("controlled uncertain model close Error");
            if (failClose) throw new IllegalStateException("controlled model close failure");
        }
    }

    private static final class Harness implements AutoCloseable {
        final BlockingQueue<Call> requests = new LinkedBlockingQueue<>();
        final List<Call> calls = new CopyOnWriteArrayList<>();
        final List<Model> models = new CopyOnWriteArrayList<>();
        final List<Long> presentedTokens = new CopyOnWriteArrayList<>();
        final List<Long> acceptedQualitySources = new CopyOnWriteArrayList<>();
        final List<Long> acceptedFastSources = new CopyOnWriteArrayList<>();
        final CountDownLatch terminated = new CountDownLatch(1);
        final CountDownLatch inferenceEntered = new CountDownLatch(1);
        final CountDownLatch inferenceRelease = new CountDownLatch(1);
        final CountDownLatch initializationEntered = new CountDownLatch(1);
        final CountDownLatch initializationRelease = new CountDownLatch(1);
        final CountDownLatch qualityEntered = new CountDownLatch(1);
        final CountDownLatch qualityRelease = new CountDownLatch(1);
        final CountDownLatch qualityReady = new CountDownLatch(1);
        final CountDownLatch qualityUnavailable = new CountDownLatch(1);
        final CountDownLatch firstFastBatch = new CountDownLatch(1);
        final CountDownLatch secondFastBatch = new CountDownLatch(1);
        final CountDownLatch fastReady = new CountDownLatch(1);
        final CountDownLatch fastUnavailable = new CountDownLatch(1);
        final CountDownLatch resourceFailed = new CountDownLatch(1);
        final CountDownLatch completedQualityBatch = new CountDownLatch(1);
        final CountDownLatch qualityInitializationEntered = new CountDownLatch(1);
        final CountDownLatch qualityInitializationRelease = new CountDownLatch(1);
        final CountDownLatch emptyPresentation = new CountDownLatch(1);
        final CountDownLatch completedFastJob = new CountDownLatch(1);
        final CountDownLatch secondInferenceEntered = new CountDownLatch(1);
        final CountDownLatch secondInferenceRelease = new CountDownLatch(1);
        final AtomicInteger acceptedFast = new AtomicInteger();
        final AtomicInteger fastInitializations = new AtomicInteger();
        final AtomicInteger qualityInitializations = new AtomicInteger();
        final AtomicInteger qualityUnavailableNotifications = new AtomicInteger();
        final AtomicReference<Throwable> uncaughtQualityError = new AtomicReference<>();
        final AtomicInteger fastReadyNotifications = new AtomicInteger();
        final AtomicInteger fastUnavailableNotifications = new AtomicInteger();
        final AtomicInteger acceptedQualityPositives = new AtomicInteger();
        final AtomicInteger resourceFailureNotifications = new AtomicInteger();
        final AtomicInteger drainNotifications = new AtomicInteger();
        final AtomicInteger visiblePresentations = new AtomicInteger();
        final AtomicBoolean initializedOnMain = new AtomicBoolean();
        final AtomicBoolean disposed = new AtomicBoolean();
        final AtomicBoolean secondWasCancelled = new AtomicBoolean();
        final AtomicBoolean qualityExecuting = new AtomicBoolean(), visualOverlap = new AtomicBoolean();
        final AtomicBoolean firstQualityWasCancelled = new AtomicBoolean();
        volatile boolean blockInference;
        volatile boolean blockInitialization;
        volatile boolean qualityEnabled;
        volatile boolean blockQuality;
        volatile String qualityProvider = "CPU";
        volatile boolean blockQualityInitialization;
        volatile boolean errorQualityInitialization, failQualityInitialization;
        int expectedFastAtQuarantine;
        volatile boolean holdSecondInference;
        volatile boolean failSecondInference;
        volatile boolean mixedEvidence;
        volatile boolean failFastInitialization, failFirstFastInitialization, errorFastInitialization, emptyFast;
        final boolean expectedSyntheticQuarantine;
        AccessibilityCensorHost host;
        Harness() { this(5000, false); }
        Harness(boolean expectedSyntheticQuarantine) { this(5000, expectedSyntheticQuarantine); }
        Harness(long transientMillis) { this(transientMillis, false); }
        Harness(long transientMillis, boolean expectedSyntheticQuarantine) {
            this.expectedSyntheticQuarantine = expectedSyntheticQuarantine;
            Context context = ApplicationProvider.getApplicationContext();
            main(() -> host = new AccessibilityCensorHost(context,
                    new SceneEngine.Policy(16, 100, 64, 256, transientMillis, 20000, .5),
                    callbacks -> (request, callback) -> {
                        assertTrue(request.tryDispatch()); Call call = new Call(request, callback, callbacks);
                        calls.add(call); requests.add(call);
                    }, (ignored, lane, config, partition, fallback) -> {
                        int attempt = lane == SceneEngine.Lane.FAST ? fastInitializations.incrementAndGet() : 0;
                        initializedOnMain.set(Looper.myLooper() == Looper.getMainLooper());
                        initializationEntered.countDown();
                        if (blockInitialization) assertTrue(initializationRelease.await(5, TimeUnit.SECONDS));
                        if (lane == SceneEngine.Lane.FAST) {
                            if (errorFastInitialization) throw new AssertionError("controlled uncertain startup Error");
                            if (failFastInitialization || (failFirstFastInitialization && attempt == 1)) {
                                throw new IllegalStateException("controlled ordinary startup failure");
                            }
                        }
                        if (lane == SceneEngine.Lane.QUALITY) {
                            qualityInitializations.incrementAndGet();
                            qualityInitializationEntered.countDown();
                            if (blockQualityInitialization) assertTrue(qualityInitializationRelease.await(5, TimeUnit.SECONDS));
                            if (errorQualityInitialization) {
                                // Keep a pre-fix failure observable without changing the process-wide handler.
                                Thread.currentThread().setUncaughtExceptionHandler((thread, error) -> uncaughtQualityError.set(error));
                                throw new AssertionError("controlled uncertain QUALITY startup Error");
                            }
                            if (failQualityInitialization) throw new IllegalStateException("controlled ordinary QUALITY startup failure");
                        }
                        Model model = new Model(this, lane, config); models.add(model);
                        return new VisualDetectorWorker(lane, config, partition, model);
                    }, new AccessibilityCensorHost.Observer() {
                        @Override public void trace(CensorEngine.Trace trace) {
                            if (trace.stage == CensorEngine.Stage.JOB_COMPLETED && trace.lane == SceneEngine.Lane.FAST) {
                                completedFastJob.countDown();
                            }
                            if (trace.stage == CensorEngine.Stage.RESOURCE_FAILED) {
                                resourceFailureNotifications.incrementAndGet(); resourceFailed.countDown();
                            }
                        }
                        @Override public void providerReady(SceneEngine.Lane lane, String provider, long millis) {
                            if (lane == SceneEngine.Lane.QUALITY) qualityReady.countDown();
                            if (lane == SceneEngine.Lane.FAST) { fastReadyNotifications.incrementAndGet(); fastReady.countDown(); }
                        }
                        @Override public void modelUnavailable(SceneEngine.Lane lane) {
                            if (lane == SceneEngine.Lane.FAST) { fastUnavailableNotifications.incrementAndGet(); fastUnavailable.countDown(); }
                            if (lane == SceneEngine.Lane.QUALITY) { qualityUnavailableNotifications.incrementAndGet(); qualityUnavailable.countDown(); }
                        }
                        @Override public void batchAccepted(SceneEngine.Batch batch) {
                            if (batch.lane == SceneEngine.Lane.FAST) {
                                acceptedFastSources.add(batch.source.imageRequestIdentity);
                                int accepted = acceptedFast.incrementAndGet();
                                if (accepted == 1) firstFastBatch.countDown();
                                if (accepted == 2) secondFastBatch.countDown();
                            }
                            if (batch.lane == SceneEngine.Lane.QUALITY && !batch.observations.isEmpty()) {
                                acceptedQualitySources.add(batch.source.imageRequestIdentity);
                                acceptedQualityPositives.incrementAndGet(); completedQualityBatch.countDown();
                            }
                        }
                        @Override public void presentationChanged() {
                            try (CensorEngine<PixelFrame>.RenderView view = host.acquireRenderView()) {
                                if (view != null) {
                                    presentedTokens.add(view.snapshot.destination.geometry);
                                    if (view.snapshot.groups.isEmpty()) emptyPresentation.countDown();
                                    else visiblePresentations.incrementAndGet();
                                }
                            }
                        }
                        @Override public void executorsTerminated(boolean resourcesDisposed) {
                            disposed.set(resourcesDisposed); terminated.countDown();
                        }
                        @Override public void drained() { drainNotifications.incrementAndGet(); }
                    }));
            if (expectedSyntheticQuarantine) {
                EXPECTED_QUARANTINES.add(this);
                assertTrue("unexpected synthetic quarantine inventory", EXPECTED_QUARANTINES.size() <= 3);
            }
        }
        void start(long token, DetectorConfig config) { main(() -> host.start(token, config, qualityEnabled)); }
        Call next() throws InterruptedException {
            Call call = requests.poll(5, TimeUnit.SECONDS); assertNotNull("host did not request capture", call); return call;
        }
        void awaitView(long token) throws InterruptedException {
            long deadline = SystemClock.uptimeMillis() + 5000;
            while (SystemClock.uptimeMillis() < deadline) {
                try (CensorEngine<PixelFrame>.RenderView view = host.acquireRenderView()) {
                    if (view != null && view.snapshot.destination.geometry == token && !view.snapshot.groups.isEmpty()) return;
                }
                Thread.sleep(10);
            }
            fail("host did not publish the expected source");
        }
        void awaitMappedEvidence(long evidence, long geometry, double top) throws InterruptedException {
            long deadline = SystemClock.uptimeMillis() + 5000;
            while (SystemClock.uptimeMillis() < deadline) {
                try (CensorEngine<PixelFrame>.RenderView view = host.acquireRenderView()) {
                    if (view != null && view.snapshot.groups.size() == 1 && view.snapshot.groups.get(0).members.size() == 1) {
                        SceneEngine.Mask mask = view.snapshot.groups.get(0).members.get(0);
                        if (mask.evidenceSource.sequence == evidence && mask.geometrySource.sequence == geometry
                                && mask.displayRegion.top == top && acceptedFast.get() == 1) return;
                    }
                }
                Thread.sleep(10);
            }
            fail("Android host did not publish the source-specific mapped evidence");
        }
        void awaitMixedEvidence(long evidence, long bodyGeometry, boolean bodyHeld) throws InterruptedException {
            long deadline = SystemClock.uptimeMillis() + 5000;
            while (SystemClock.uptimeMillis() < deadline) {
                try (CensorEngine<PixelFrame>.RenderView view = host.acquireRenderView()) {
                    int count = 0; boolean header = false, body = false;
                    if (view != null) for (SceneEngine.Group group : view.snapshot.groups) {
                        for (SceneEngine.Mask mask : group.members) {
                            count++;
                            if (mask.evidenceSource.sequence != evidence) continue;
                            header |= mask.evidenceRegion.top == 5 && mask.displayRegion.top == 5
                                    && mask.geometrySource.sequence == evidence && mask.heldGeometry;
                            body |= mask.evidenceRegion.top == 50 && mask.displayRegion.top == 10
                                    && mask.geometrySource.sequence == bodyGeometry && mask.heldGeometry == bodyHeld;
                        }
                    }
                    if (count == 2 && header && body && acceptedFast.get() == 1) return;
                }
                Thread.sleep(10);
            }
            fail("Android partial-support hold lost the header or rolled the moved body back");
        }
        void awaitAtomicMixedEvidence(long evidence, long geometry) throws InterruptedException {
            long deadline = SystemClock.uptimeMillis() + 5000;
            while (SystemClock.uptimeMillis() < deadline) {
                try (CensorEngine<PixelFrame>.RenderView view = host.acquireRenderView()) {
                    int count = 0; boolean header = false, body = false, qualified = true;
                    if (view != null) for (SceneEngine.Group group : view.snapshot.groups) {
                        for (SceneEngine.Mask mask : group.members) {
                            count++;
                            qualified &= mask.evidenceSource.sequence == evidence && mask.geometrySource.sequence == geometry
                                    && !mask.estimatedMotion && !mask.heldGeometry;
                            header |= mask.evidenceRegion.top == 5 && mask.displayRegion.top == 5;
                            body |= mask.evidenceRegion.top == 50 && mask.displayRegion.top == 18;
                        }
                    }
                    if (count == 2 && qualified && header && body && acceptedFast.get() == 1) return;
                }
                Thread.sleep(10);
            }
            fail("Atomic Android source pair did not qualify both actual-partition members together");
        }
        void failUndelivered() { for (Call call : calls) call.fail(); }
        void assertQuarantined() throws InterruptedException {
            assertTrue("this explicit synthetic fixture did not select quarantine", expectedSyntheticQuarantine);
            assertEquals(1, resourceFailureNotifications.get());
            assertFalse("uncertain resources cannot authorize executor drain", terminated.await(80, TimeUnit.MILLISECONDS));
            assertEquals(0, drainNotifications.get()); assertFalse(disposed.get());
            assertNull(host.activeScope());
            try (CensorEngine<PixelFrame>.RenderView view = host.acquireRenderView()) { assertNull(view); }
            assertEquals(expectedFastAtQuarantine, acceptedFast.get()); assertEquals(0, acceptedQualityPositives.get());
            long deadline = SystemClock.uptimeMillis() + 5000;
            while (host.captureInFlight() && SystemClock.uptimeMillis() < deadline) Thread.sleep(10);
            assertFalse("quarantine did not resolve its existing fake callback", host.captureInFlight());
            for (Call call : calls) assertTrue(call.delivered.get());
            main(host::requestCapture);
            assertNull("quarantined owner admitted another capture", requests.poll(80, TimeUnit.MILLISECONDS));
        }
        @Override public void close() throws Exception {
            initializationRelease.countDown(); inferenceRelease.countDown(); qualityRelease.countDown();
            secondInferenceRelease.countDown();
            qualityInitializationRelease.countDown();
            main(host::close); failUndelivered();
            if (expectedSyntheticQuarantine) {
                // These three factories allocate only fake Models. The failed owner intentionally
                // remains quarantined until the owned instrumentation process exits. Never
                // clear the runtime's fault or invent successful native disposal for teardown.
                assertQuarantined();
            } else assertTrue("host executors did not drain", terminated.await(5, TimeUnit.SECONDS));
        }
    }
}
