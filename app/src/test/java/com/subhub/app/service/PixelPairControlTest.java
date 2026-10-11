package com.subhub.app.service;

import static org.junit.Assert.*;

import com.subhub.app.censor.engine.PixelGeometry;
import com.subhub.app.censor.engine.PixelPairMatcher;
import com.subhub.app.censor.engine.SceneEngine;
import java.util.Random;
import org.junit.Test;

/** Retained control for the detector-dependent legacy pixel primitive. */
public final class PixelPairControlTest {
    private static final int WIDTH = 64, HEIGHT = 128;

    @Test public void firstPairCannotRequireAnyDetectorOutput() {
        int[] rgb = texture();
        SourcePatchMatcher.Image a = SourcePatchMatcher.Image.copyOf(rgb, WIDTH, HEIGHT);
        SourcePatchMatcher.Image b = SourcePatchMatcher.Image.copyOf(rgb, WIDTH, HEIGHT);
        assertNull("Retain the original detector-dependent failure as the control",
                SourcePatchMatcher.match(a, b, null, null, WIDTH, HEIGHT));
        assertEquals(1, matcher(rgb, rgb).evidence.size());
        assertEquals(0, matcher(rgb, rgb).evidence.get(0).sampledDy);
    }

    @Test public void sparseLargeScrollCannotRequireBDetection() {
        int[] rgb = texture(), shifted = new int[rgb.length];
        java.util.Arrays.fill(shifted, 0xff000000);
        for (int y = 80; y < HEIGHT; y++) {
            System.arraycopy(rgb, y * WIDTH, shifted, (y - 80) * WIDTH, WIDTH);
        }
        assertNull("Retain the original detector-dependent failure as the control",
                SourcePatchMatcher.match(SourcePatchMatcher.Image.copyOf(rgb, WIDTH, HEIGHT),
                        SourcePatchMatcher.Image.copyOf(shifted, WIDTH, HEIGHT), null, null, WIDTH, HEIGHT));
        PixelPairMatcher.Result result = matcher(rgb, shifted);
        assertEquals(PixelPairMatcher.Status.COMPLETE, result.status);
        assertFalse(result.evidence.isEmpty());
        for (PixelPairMatcher.Evidence piece : result.evidence) assertEquals(-80, piece.sampledDy);
    }

    private static PixelPairMatcher.Result matcher(int[] a, int[] b) {
        PixelGeometry geometry = PixelGeometry.display(WIDTH, HEIGHT, WIDTH, HEIGHT);
        PixelPairMatcher.Image first = PixelPairMatcher.Image.copyOf(new SceneEngine.Source(0, 1, 1,
                WIDTH, HEIGHT, 10, -1), geometry, WIDTH, HEIGHT,
                PixelPairMatcher.Provenance.UNCENSORED_ACCESSIBILITY_DISPLAY, PixelPairMatcher.Sampling.EXACT_RASTER, a);
        PixelPairMatcher.Image second = PixelPairMatcher.Image.copyOf(new SceneEngine.Source(0, 2, 1,
                WIDTH, HEIGHT, 344, -1), geometry, WIDTH, HEIGHT,
                PixelPairMatcher.Provenance.UNCENSORED_ACCESSIBILITY_DISPLAY, PixelPairMatcher.Sampling.EXACT_RASTER, b);
        return new PixelPairMatcher(new PixelPairMatcher.Policy(4, 8, 0, 32, 10_000_000))
                .match(first, second, () -> false);
    }

    private static int[] texture() {
        Random random = new Random(93);
        int[] pixels = new int[WIDTH * HEIGHT];
        for (int i = 0; i < pixels.length; i++) pixels[i] = 0xff000000 | random.nextInt(1 << 24);
        return pixels;
    }
}
