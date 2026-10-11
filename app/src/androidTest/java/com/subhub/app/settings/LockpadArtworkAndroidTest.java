package com.subhub.app.settings;

import static org.junit.Assert.*;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class LockpadArtworkAndroidTest {
    @Test public void allSizesAndAnimationPhasesCoverEveryProtectedPixel() {
        LockpadArtwork artwork = new LockpadArtwork();
        EffectPalette palette = EffectPalette.defaultsFor(CensorAppearance.Type.ERROR_POPUP);
        for (int[] size : new int[][]{{3, 3}, {480, 195}, {120, 80}, {20, 240}, {700, 18}, {400, 600}}) {
            Bitmap bitmap = Bitmap.createBitmap(size[0] + 4, size[1] + 4, Bitmap.Config.ARGB_8888);
            try {
                RectF destination = new RectF(2, 2, size[0] + 2, size[1] + 2);
                for (long time : new long[]{0, 160, 800, 1800, 2999}) {
                    bitmap.eraseColor(Color.TRANSPARENT);
                    artwork.draw(new Canvas(bitmap), destination, palette, time);
                    for (int y = 2; y < size[1] + 2; y++) for (int x = 2; x < size[0] + 2; x++)
                        assertEquals("Coverage at " + x + "," + y, 255, Color.alpha(bitmap.getPixel(x, y)));
                    assertEquals("Artwork stays within owner geometry", 0, Color.alpha(bitmap.getPixel(0, 0)));
                }
            } finally { bitmap.recycle(); }
        }
    }

    @Test public void compactLayoutOmitsLabelsAndFullLayoutFitsLongPhrases() {
        RectF destination = new RectF(10, 20, 490, 215), label = new RectF();
        LockpadArtwork.labelBounds(destination, label);
        assertTrue(destination.contains(label));
        Paint paint = new Paint(); paint.setTextSize(7);
        String fitted = com.subhub.app.overlay.CensorLabelLayout.ellipsize(
                "THIS IS A VERY LONG DENIAL PHRASE WITH MULTIPLE WORDS", label.width() - 10, paint::measureText);
        assertTrue(paint.measureText(fitted) <= label.width() - 10);
        LockpadArtwork.labelBounds(new RectF(0, 0, 48, 240), label);
        assertTrue(label.isEmpty());
        LockpadArtwork.labelBounds(new RectF(Float.NaN, 0, 100, 100), label);
        assertTrue(label.isEmpty());
    }

    @Test public void motionChangesKeysWithoutChangingTheBackingOrSourceDependence() {
        Bitmap first = Bitmap.createBitmap(480, 195, Bitmap.Config.ARGB_8888);
        Bitmap second = Bitmap.createBitmap(480, 195, Bitmap.Config.ARGB_8888);
        try {
            LockpadArtwork artwork = new LockpadArtwork();
            EffectPalette palette = EffectPalette.defaultsFor(CensorAppearance.Type.ERROR_POPUP);
            RectF destination = new RectF(0, 0, 480, 195);
            first.eraseColor(Color.RED); second.eraseColor(Color.GREEN);
            artwork.draw(new Canvas(first), destination, palette, 1800);
            artwork.draw(new Canvas(second), destination, palette, 1800);
            assertTrue("Opaque artwork ignores the underlying scene", first.sameAs(second));
            artwork.draw(new Canvas(second), destination, palette, 0);
            assertFalse("Key presses use the export clock", first.sameAs(second));
        } finally { first.recycle(); second.recycle(); }
    }
}
