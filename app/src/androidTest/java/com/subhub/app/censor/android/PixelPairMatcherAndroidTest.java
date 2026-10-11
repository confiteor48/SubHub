package com.subhub.app.censor.android;

import static org.junit.Assert.*;

import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.censor.engine.PixelGeometry;
import com.subhub.app.censor.engine.PixelPairMatcher;
import com.subhub.app.censor.engine.SceneEngine;
import java.util.Arrays;
import java.util.Random;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Actual bitmap/ART raster controls, not a bound Accessibility pipeline or physical motion proof. */
@RunWith(AndroidJUnit4.class)
public final class PixelPairMatcherAndroidTest {
    @Test public void nativeWindowRasterKeepsDisplaySourceAndBufferFootprintDistinct() {
        Bitmap original = texture(64, 128);
        Bitmap a = original.copy(Bitmap.Config.ARGB_8888, true);
        original.recycle();
        assertNotNull(a);
        Bitmap b = a.copy(Bitmap.Config.ARGB_8888, false);
        try {
            PixelGeometry geometry = PixelGeometry.window(128, 256, 8, 12, 64, 128, 64, 128);
            PixelPairMatcher.Image first = image(a, geometry, 1, PixelPairMatcher.Provenance.UNCENSORED_ACCESSIBILITY_WINDOW);
            PixelPairMatcher.Image second = image(b, geometry, 2, PixelPairMatcher.Provenance.UNCENSORED_ACCESSIBILITY_WINDOW);
            assertNotNull(first); assertNotNull(second);
            a.eraseColor(0xff000000);
            PixelPairMatcher.Result result = matcher().match(first, second, () -> false);
            assertEquals(PixelPairMatcher.Status.COMPLETE, result.status); assertEquals(1, result.evidence.size());
            assertEquals(128, result.from.width); assertEquals(256, result.from.height);
            assertEquals(8, result.evidence.get(0).sourceFootprint.left, 0);
            assertEquals(12, result.evidence.get(0).sourceFootprint.top, 0);
            assertFalse(result.evidence.get(0).physicalErrorKnown);
            assertFalse(a.isRecycled()); assertFalse(b.isRecycled());
        } finally { a.recycle(); b.recycle(); }
    }

    @Test public void captureKindsDoNotBecomeAnUnstatedCommonSourceScope() {
        Bitmap a = texture(64, 128);
        try {
            PixelGeometry geometry = PixelGeometry.display(64, 128, 64, 128);
            PixelPairMatcher.Image first = image(a, geometry, 1, PixelPairMatcher.Provenance.UNCENSORED_ACCESSIBILITY_WINDOW);
            PixelPairMatcher.Image second = image(a, geometry, 2, PixelPairMatcher.Provenance.UNCENSORED_ACCESSIBILITY_DISPLAY);
            assertNotNull(first); assertNotNull(second);
            assertEquals(PixelPairMatcher.Status.INVALID_INPUT, matcher().match(first, second, () -> false).status);
        } finally { a.recycle(); }
    }

    @Test public void nativeFiniteDomainDoesNotMakeDiagonalApertureStationary() {
        Bitmap a = Bitmap.createBitmap(64, 128, Bitmap.Config.ARGB_8888);
        try {
            for (int y = 0; y < 128; y++) for (int x = 0; x < 64; x++) {
                int v = ((x + y) * 17) & 255;
                a.setPixel(x, y, 0xff000000 | v << 16 | v << 8 | v);
            }
            PixelGeometry geometry = PixelGeometry.display(64, 128, 64, 128);
            PixelPairMatcher.Result result = new PixelPairMatcher(new PixelPairMatcher.Policy(1, 1, 0, 32, 10_000_000))
                    .match(image(a, geometry, 1, PixelPairMatcher.Provenance.UNCENSORED_ACCESSIBILITY_DISPLAY),
                            image(a, geometry, 2, PixelPairMatcher.Provenance.UNCENSORED_ACCESSIBILITY_DISPLAY), () -> false);
            assertEquals(PixelPairMatcher.Status.COMPLETE, result.status); assertEquals(0, result.texturedTiles);
            assertTrue(result.evidence.isEmpty());
        } finally { a.recycle(); }
    }

    @Test public void boxAverageEqualityCannotProveOriginalInteriorCorrespondence() {
        Bitmap a = texture(64, 128), b = a.copy(Bitmap.Config.ARGB_8888, true);
        try {
            // Preserve each channel's2x2 sum while changing two actual source pixels.
            int first = b.getPixel(0, 0), second = b.getPixel(1, 0);
            b.setPixel(0, 0, second); b.setPixel(1, 0, first);
            assertNotEquals(a.getPixel(0, 0), b.getPixel(0, 0));
            int[] sampleA = boxAverage2(a), sampleB = boxAverage2(b);
            assertArrayEquals(sampleA, sampleB);
            PixelGeometry geometry = PixelGeometry.display(64, 128, 64, 128);
            PixelPairMatcher.Image preparedA = PixelPairMatcher.Image.copyOf(new SceneEngine.Source(0, 1, 1, 64, 128, 10, -1),
                    geometry, 32, 64, PixelPairMatcher.Provenance.UNCENSORED_LAB, PixelPairMatcher.Sampling.BOX_AVERAGE, sampleA);
            PixelPairMatcher.Image preparedB = PixelPairMatcher.Image.copyOf(new SceneEngine.Source(0, 2, 1, 64, 128, 344, -1),
                    geometry, 32, 64, PixelPairMatcher.Provenance.UNCENSORED_LAB, PixelPairMatcher.Sampling.BOX_AVERAGE, sampleB);
            PixelPairMatcher.Result result = new PixelPairMatcher(new PixelPairMatcher.Policy(2, 4, 0, 16, 10_000_000))
                    .match(preparedA, preparedB, () -> false);
            assertEquals(1, result.evidence.size()); assertEquals(0, result.evidence.get(0).sampledDy);
            assertEquals(2, result.evidence.get(0).cellWidth, 0);
            assertFalse(result.evidence.get(0).physicalErrorKnown);
        } finally { a.recycle(); b.recycle(); }
    }

    @Test public void actualPreparedSizeWorkIsBoundedAndCancellationPublishesNothing() {
        Bitmap a = texture(192, 384);
        try {
            PixelGeometry geometry = PixelGeometry.display(192, 384, 192, 384);
            long started = SystemClock.elapsedRealtimeNanos();
            PixelPairMatcher.Image first = image(a, geometry, 1, PixelPairMatcher.Provenance.UNCENSORED_ACCESSIBILITY_DISPLAY);
            PixelPairMatcher.Image second = image(a, geometry, 2, PixelPairMatcher.Provenance.UNCENSORED_ACCESSIBILITY_DISPLAY);
            long prepared = SystemClock.elapsedRealtimeNanos();
            PixelPairMatcher.Result result = matcher().match(first, second, () -> false);
            long matched = SystemClock.elapsedRealtimeNanos();
            assertEquals(PixelPairMatcher.Status.COMPLETE, result.status); assertEquals(1, result.evidence.size());
            assertTrue(result.pixelComparisons > 0); assertTrue(result.pixelComparisons <= 10_000_000);
            PixelPairMatcher.Result cancelled = matcher().match(first, second, () -> true);
            assertEquals(PixelPairMatcher.Status.CANCELLED, cancelled.status); assertTrue(cancelled.evidence.isEmpty());
            PixelPairMatcher.Result exhausted = new PixelPairMatcher(new PixelPairMatcher.Policy(4, 8, 0, 32, 256))
                    .match(first, second, () -> false);
            assertEquals(PixelPairMatcher.Status.WORK_BUDGET, exhausted.status); assertTrue(exhausted.evidence.isEmpty());
            Bundle measurement = new Bundle();
            measurement.putString("stream", "PIXEL_PAIR_NATIVE_WORK {\"width\":192,\"height\":384,\"preparationNanos\":"
                    + (prepared - started) + ",\"matcherNanos\":" + (matched - prepared)
                    + ",\"pixelComparisons\":" + result.pixelComparisons + ",\"physicalErrorKnown\":false}\n");
            InstrumentationRegistry.getInstrumentation().sendStatus(2, measurement);
        } finally { a.recycle(); }
    }

    private static PixelPairMatcher matcher() { return new PixelPairMatcher(new PixelPairMatcher.Policy(4, 8, 0, 32, 10_000_000)); }
    private static Bitmap texture(int width, int height) {
        Random random = new Random(44);
        int[] rgb = new int[width * height];
        for (int i = 0; i < rgb.length; i++) rgb[i] = 0xff000000 | random.nextInt(1 << 24);
        return Bitmap.createBitmap(rgb, width, height, Bitmap.Config.ARGB_8888);
    }
    private static PixelPairMatcher.Image image(Bitmap bitmap, PixelGeometry geometry, long sequence,
            PixelPairMatcher.Provenance provenance) {
        int[] rgb = new int[bitmap.getWidth() * bitmap.getHeight()];
        bitmap.getPixels(rgb, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
        return PixelPairMatcher.Image.copyOf(new SceneEngine.Source(0, sequence, 1, geometry.displayWidth,
                geometry.displayHeight, sequence * 334, -1), geometry, bitmap.getWidth(), bitmap.getHeight(),
                provenance, PixelPairMatcher.Sampling.EXACT_RASTER, rgb);
    }
    private static int[] boxAverage2(Bitmap bitmap) {
        int[] pixels = new int[32 * 64];
        for (int y = 0; y < 64; y++) for (int x = 0; x < 32; x++) {
            int r = 0, g = 0, b = 0;
            for (int yy = 0; yy < 2; yy++) for (int xx = 0; xx < 2; xx++) {
                int rgb = bitmap.getPixel(x * 2 + xx, y * 2 + yy);
                r += (rgb >>> 16) & 255; g += (rgb >>> 8) & 255; b += rgb & 255;
            }
            pixels[y * 32 + x] = 0xff000000 | (r / 4) << 16 | (g / 4) << 8 | b / 4;
        }
        return pixels;
    }
}
