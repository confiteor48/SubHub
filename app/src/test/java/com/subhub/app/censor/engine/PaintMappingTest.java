package com.subhub.app.censor.engine;

import static org.junit.Assert.*;
import org.junit.Test;

public class PaintMappingTest {
    @Test public void partialWindowMapsOnlyItsCorrespondingDestinationInsteadOfStretching() {
        PixelGeometry pixels = PixelGeometry.window(1000, 2000, 100, 500, 200, 400, 200, 400);
        PaintMapping value = PaintMapping.sample(pixels, new SceneEngine.Region(50, 400, 250, 800),
                new SceneEngine.Region(500, 100, 900, 300));
        assertEquals(0, value.buffer.left, 0); assertEquals(0, value.buffer.top, 0);
        assertEquals(150, value.buffer.right, 0); assertEquals(300, value.buffer.bottom, 0);
        assertEquals(600, value.destination.left, 0); assertEquals(150, value.destination.top, 0);
        assertEquals(900, value.destination.right, 0); assertEquals(300, value.destination.bottom, 0);
        assertEquals(100, value.sourceSupport.left, 0); assertEquals(500, value.sourceSupport.top, 0);
    }
    @Test public void unsupportedAndExactlyTouchingWindowEvidenceStayAbsent() {
        PixelGeometry pixels = PixelGeometry.window(1000, 2000, 100, 500, 200, 400, 200, 400);
        SceneEngine.Region display = new SceneEngine.Region(500, 100, 900, 300);
        assertNull(PaintMapping.sample(pixels, new SceneEngine.Region(0, 0, 100, 500), display));
        assertNull(PaintMapping.sample(pixels, new SceneEngine.Region(300, 600, 400, 700), display));
    }
    @Test public void textPaddingCapsAtTwoPointFivePercentAndDoesNotClipOffscreenState() {
        SceneEngine.Region region = new SceneEngine.Region(-100, 10, 100, 50);
        SceneEngine.Region text = PaintMapping.padded(region, .8f, true);
        assertEquals(-105, text.left, 0); assertEquals(105, text.right, 0);
        assertEquals(9, text.top, 0); assertEquals(51, text.bottom, 0);
        assertEquals(-260, PaintMapping.padded(region, .8f, false).left, .00001);
        assertEquals(-100, PaintMapping.padded(region, Float.NaN, false).left, 0);
    }
}
