package com.subhub.app.overlay;

import static org.junit.Assert.*;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.View;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.detection.BBox;
import com.subhub.app.detection.Detection;
import com.subhub.app.settings.CensorAppearance;
import com.subhub.app.settings.EffectPalette;
import java.io.File;
import java.io.FileOutputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public final class CachedEffectArtworkAndroidTest {
    @Test public void cachedGlitchAndStaticRemainOpaqueAndTextured() {
        for (CensorAppearance.Type type : List.of(CensorAppearance.Type.GLITCH, CensorAppearance.Type.STATIC)) {
            Bitmap rendered = render(type);
            try {
                retain(rendered, "cached-" + type.getPreferenceValue() + ".png");
                Set<Integer> colors = new HashSet<>();
                for (int y = 42; y < 138; y++) for (int x = 42; x < 138; x++) {
                    int pixel = rendered.getPixel(x, y);
                    assertEquals("Cached artwork stays opaque", 255, Color.alpha(pixel));
                    colors.add(pixel);
                }
                assertTrue("Cached " + type + " retains texture instead of a solid box", colors.size() > 20);
                assertEquals("Artwork does not escape cached bounds", 0, rendered.getPixel(10, 10));
            } finally { rendered.recycle(); }
        }
    }

    @Test public void cachedBlurAndPixelateRetainOpaqueSourceFreeCoverage() {
        for (CensorAppearance.Type type : List.of(CensorAppearance.Type.BLUR, CensorAppearance.Type.PIXELATE)) {
            Bitmap rendered = render(type);
            try {
                // Sample the interior; solid masks intentionally have rounded corners.
                for (int y = 50; y < 130; y++) for (int x = 50; x < 130; x++)
                    assertEquals("Unavailable source never reveals content",
                            EffectPalette.defaultsFor(type).first(), rendered.getPixel(x, y));
            } finally { rendered.recycle(); }
        }
    }

    private static Bitmap render(CensorAppearance.Type type) {
        Bitmap output = Bitmap.createBitmap(180, 180, Bitmap.Config.ARGB_8888);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = ApplicationProvider.getApplicationContext();
            CensorOverlayView view = new CensorOverlayView(context);
            try {
                view.setAppearance(new CensorAppearance(type, 75, 0f, false, false,
                        CensorAppearance.BorderEffect.CLASSIC, false, Color.MAGENTA,
                        EffectPalette.defaultsFor(type), List.of("BLOCKED"), false, 100,
                        "rectangle", "", ""));
                Detection cached = new Detection("FACE_FEMALE", "face", .95f,
                        new BBox(40, 40, 100, 100), true, false,
                        Detection.ObservationSource.VISUAL, Detection.GeometryQuality.MODEL,
                        "world-cache:test");
                view.setWorldTracksAndCache(List.of(), List.of(cached), 180, 180, null,
                        0, 0, 0, 0, 180, 180, null);
                assertEquals("The fixture actually admits cached artwork", 1,
                        view.admittedCachedRegionCount(List.of(cached)));
                view.measure(View.MeasureSpec.makeMeasureSpec(180, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(180, View.MeasureSpec.EXACTLY));
                view.layout(0, 0, 180, 180);
                view.draw(new Canvas(output));
            } finally { view.release(); }
        });
        return output;
    }

    private static void retain(Bitmap bitmap, String name) {
        Context context = ApplicationProvider.getApplicationContext();
        try (FileOutputStream stream = new FileOutputStream(new File(context.getExternalFilesDir(null), name))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream));
        } catch (java.io.IOException failure) { throw new AssertionError(failure); }
    }
}
