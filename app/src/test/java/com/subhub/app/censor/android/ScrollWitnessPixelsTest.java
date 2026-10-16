package com.subhub.app.censor.android;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ScrollWitnessPixelsTest {
    private static final int W = 160, H = 240;
    private static void rectangle(int[] pixels, int width, int left, int top, int right, int bottom, int color) {
        for (int y = top; y < bottom; y++) Arrays.fill(pixels, y * width + left, y * width + right, color);
    }
    private static ScrollWitnessPixels.Box box(int top, int bottom) { return new ScrollWitnessPixels.Box(80, top, 120, bottom); }
    @Test public void completePositiveBandsPreserveGapsAndExclusiveCoordinates() {
        int[] pixels = new int[W * H];
        rectangle(pixels, W, 80, 20, 120, 60, ScrollWitnessPixels.TARGET);
        rectangle(pixels, W, 80, 100, 120, 140, ScrollWitnessPixels.TARGET);
        java.util.List<ScrollWitnessPixels.Box> boxes = ScrollWitnessPixels.bands(pixels, W, H, ScrollWitnessPixels.TARGET);
        assertEquals(2, boxes.size()); assertEquals(20, boxes.get(0).top); assertEquals(60, boxes.get(0).bottom);
        assertEquals(100, boxes.get(1).top); assertEquals(120, boxes.get(1).right);
    }
    @Test public void alignedWitnessAndCoverHaveKnownZeroError() {
        ScrollWitnessPixels.Sample value = ScrollWitnessPixels.measure(Collections.singletonList(new ScrollWitnessPixels.Box(20, 20, 60, 60)),
                Collections.singletonList(box(20, 60)), 60, 40);
        assertEquals(1, value.paired); assertEquals(0, value.missingCenters); assertEquals(0, value.absoluteCenterErrorSum, 0);
    }
    @Test public void absentCoverIsMissingWithNoKnownErrorRatherThanNumericZeroAccuracy() {
        ScrollWitnessPixels.Sample value = ScrollWitnessPixels.measure(Collections.singletonList(box(20, 60)), Collections.emptyList(), 0, 40);
        assertEquals(0, value.paired); assertEquals(1, value.missingCenters);
        assertTrue(ScrollWitnessPixels.bands(new int[W * H], W, H, ScrollWitnessPixels.COVER).isEmpty());
    }
    @Test public void shiftedCoverExposesItsErrorAndMissingCenter() {
        ScrollWitnessPixels.Sample value = ScrollWitnessPixels.measure(Collections.singletonList(box(20, 60)), Collections.singletonList(box(50, 90)), 0, 40);
        assertEquals(30, value.maximumCenterError, 0); assertEquals(1, value.missingCenters);
    }
    @Test public void surplusAndBridgedBandsAreNotSilentlyPerfectCoverage() {
        ScrollWitnessPixels.Sample duplicate = ScrollWitnessPixels.measure(Collections.singletonList(box(20, 60)), Arrays.asList(box(20, 60), box(70, 110)), 0, 40);
        assertEquals(1, duplicate.unpairedMaskBands);
        ScrollWitnessPixels.Sample bridge = ScrollWitnessPixels.measure(Arrays.asList(box(20, 60), box(100, 140)), Collections.singletonList(box(20, 140)), 0, 40);
        assertEquals(1, bridge.tallMaskBands);
    }
    @Test public void malformedAndAmbiguousRowsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> ScrollWitnessPixels.bands(new int[5], W, H, ScrollWitnessPixels.TARGET));
        int[] pixels = new int[W * H];
        rectangle(pixels, W, 20, 20, 60, 60, ScrollWitnessPixels.TARGET);
        rectangle(pixels, W, 80, 20, 120, 60, ScrollWitnessPixels.TARGET);
        assertThrows(IllegalArgumentException.class, () -> ScrollWitnessPixels.bands(pixels, W, H, ScrollWitnessPixels.TARGET));
    }
    @Test public void capacityRefusalIsNotAnEmptySuccessfulParse() {
        int[] pixels = new int[80 * 520];
        for (int i = 0; i < 65; i++) rectangle(pixels, 80, 20, i * 8, 60, i * 8 + 4, ScrollWitnessPixels.TARGET);
        assertThrows(IllegalArgumentException.class, () -> ScrollWitnessPixels.bands(pixels, 80, 520, ScrollWitnessPixels.TARGET));
    }
}
