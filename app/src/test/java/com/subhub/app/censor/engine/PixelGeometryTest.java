package com.subhub.app.censor.engine;

import static org.junit.Assert.*;

import com.subhub.app.detection.BBox;
import org.junit.Test;

public final class PixelGeometryTest {
    @Test public void displayRequiresActualDisplaySizedBuffer() {
        assertNotNull(PixelGeometry.display(1080, 2400, 1080, 2400));
        assertNull(PixelGeometry.display(1080, 2400, 1080, 2300));
        assertNull(PixelGeometry.display(1080, 2400, 2400, 1080));
        assertNull(PixelGeometry.display(0, 2400, 0, 2400));
    }

    @Test public void partialWindowMapsFromItsOwnOriginAndInverse() {
        PixelGeometry geometry = PixelGeometry.window(1080, 2400, 100, 200, 800, 1800, 800, 1800);
        assertNotNull(geometry);
        region(geometry.toDisplay(new BBox(30, 40, 100, 200)), 130, 240, 230, 440);
        region(geometry.toBuffer(new SceneEngine.Region(130, 240, 230, 440)), 30, 40, 130, 240);
        region(geometry.coverage(), 100, 200, 900, 2000);
        assertEquals(1080, geometry.displayWidth); assertEquals(2400, geometry.displayHeight);
    }

    @Test public void mismatchedOrOutOfDisplayWindowIsNotInventedScale() {
        assertNull(PixelGeometry.window(1080, 2400, 100, 200, 800, 1800, 400, 900));
        assertNull(PixelGeometry.window(1080, 2400, -1, 200, 800, 1800, 800, 1800));
        assertNull(PixelGeometry.window(1080, 2400, 400, 200, 800, 1800, 800, 1800));
        assertNull(PixelGeometry.window(1080, 2400, 100, 1000, 800, 1800, 800, 1800));
        assertNull(PixelGeometry.window(Integer.MAX_VALUE, 2400, Integer.MAX_VALUE - 5, 0, 10, 20, 10, 20));
    }

    @Test public void clippingAndLargeBoxArithmeticDoNotWrapOrInventCoverage() {
        PixelGeometry geometry = PixelGeometry.display(100, 200, 100, 200);
        region(geometry.toDisplay(new BBox(-10, -20, 50, 70)), 0, 0, 40, 50);
        region(geometry.toDisplay(new BBox(90, 180, Integer.MAX_VALUE, Integer.MAX_VALUE)), 90, 180, 100, 200);
        assertNull(geometry.toDisplay(new BBox(100, 0, 10, 10)));
        assertNull(geometry.toDisplay(new BBox(-50, 0, 10, 10)));
        assertNull(geometry.toDisplay(new BBox(0, 0, 0, 10)));
        assertNull(geometry.toBuffer(new SceneEngine.Region(200, 300, 250, 350)));
    }

    private static void region(SceneEngine.Region actual, double left, double top, double right, double bottom) {
        assertNotNull(actual); assertEquals(left, actual.left, 0); assertEquals(top, actual.top, 0);
        assertEquals(right, actual.right, 0); assertEquals(bottom, actual.bottom, 0);
    }
}
