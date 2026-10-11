package com.subhub.app.detection;

import static org.junit.Assert.*;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import ai.onnxruntime.OrtException;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Actual CPU/ONNX calls establish token and terminal-result contracts, not screen-detection quality. */
@RunWith(AndroidJUnit4.class)
public final class ControlledDetectionAndroidTest {
    @Test public void strictSessionCleanupExposesCheckedNativeCloseFailureExactlyOnce() {
        AtomicInteger closes = new AtomicInteger();
        try {
            DetectionEngine.closeNativeSession(() -> {
                closes.incrementAndGet(); throw new OrtException("controlled native close failure");
            }, true);
            fail("Native session cleanup was silently accepted");
        } catch (RuntimeException failure) {
            assertTrue(DetectionEngine.resourceCleanupFailed(failure));
            assertTrue(failure.getCause() instanceof OrtException);
        }
        assertEquals(1, closes.get());
    }
    @Test public void uninitializedModelCannotClaimInspectedNegativeEvidence() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        Bitmap pixels = blank(); AtomicInteger bindings = new AtomicInteger();
        try (DetectionEngine detector = new DetectionEngine(context, DetectorConfig.builder().inferenceThreads(1).build(), true)) {
            detector.requireStrictResourceCleanup();
            DetectionEngine.ControlledResult result = detector.detectControlled(pixels, 1080, 2400, 320, 320,
                    () -> false, handle -> bindings.incrementAndGet());
            assertFalse(result.complete); assertFalse(result.cancelled); assertTrue(result.detections.isEmpty());
            assertEquals(0, bindings.get()); assertFalse(pixels.isRecycled());
        } finally { pixels.recycle(); }
    }

    @Test public void actualNativeRunHandlesCannotCancelTheirSuccessor() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        Bitmap pixels = blank();
        AtomicReference<DetectionEngine.RunCancellation> cancelled = new AtomicReference<>();
        AtomicReference<DetectionEngine.RunCancellation> completed = new AtomicReference<>();
        try (DetectionEngine detector = new DetectionEngine(context, DetectorConfig.builder().inferenceThreads(1).build(), true)) {
            detector.requireStrictResourceCleanup();
            detector.initializeForProvider("CPU");
            DetectionEngine.ControlledResult stopped = detector.detectControlled(pixels, 1080, 2400, 320, 320,
                    () -> false, handle -> { cancelled.set(handle); assertTrue(handle.cancel()); });
            assertTrue(stopped.cancelled); assertFalse(stopped.complete); assertFalse(cancelled.get().cancel());
            DetectionEngine.ControlledResult first = detector.detectControlled(pixels, 1080, 2400, 320, 320,
                    () -> false, completed::set);
            assertTrue(first.complete); assertFalse(first.cancelled); assertFalse(completed.get().cancel());
            DetectionEngine.ControlledResult successor = detector.detectControlled(pixels, 1080, 2400, 320, 320,
                    () -> false, handle -> { assertFalse(completed.get().cancel()); assertFalse(cancelled.get().cancel()); });
            assertTrue(successor.complete); assertFalse(successor.cancelled); assertTrue(successor.runtimeMillis > 0);
            assertFalse(pixels.isRecycled());
        } finally { pixels.recycle(); }
    }

    private static Bitmap blank() {
        Bitmap pixels = Bitmap.createBitmap(144, 320, Bitmap.Config.ARGB_8888);
        new Canvas(pixels).drawColor(Color.rgb(37, 91, 143)); return pixels;
    }
}
