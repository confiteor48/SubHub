package com.subhub.app.overlay;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;
import android.widget.FrameLayout;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.detection.BBox;
import com.subhub.app.detection.Detection;
import com.subhub.app.detection.DetectorConfig;
import com.subhub.app.detection.ObjectTracker;
import com.subhub.app.penance.CensorTapTracker;
import com.subhub.app.settings.CensorAppearance;
import com.subhub.app.settings.EffectPalette;
import com.subhub.app.settings.LockpadArtwork;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Legacy host parity only; does not exercise capture, detector speed or physical scroll. */
@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion = 29)
public final class LockpadOverlayAndroidTest {
    private static final int WIDTH = 560, HEIGHT = 360;
    private static final RectF DESTINATION = new RectF(40, 40, 520, 235);
    private static final String PHRASE = "THIS IS A LONG PERSONAL DENIAL PHRASE THAT MUST FIT";

    @Test public void liveArtworkUsesThePresentationClockAndExactOwnerGeometry() throws Exception {
        AtomicReference<Bitmap> previous = new AtomicReference<>();
        try {
            for (long time : new long[]{0, 1800}) {
                Bitmap rendered = render(context(), DESTINATION, false, time);
                Bitmap expected = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
                try {
                    new LockpadArtwork().draw(new Canvas(expected), DESTINATION, palette(), time);
                    assertTrue("Live and shared artwork differ at time " + time, expected.sameAs(rendered));
                    assertEquals(255, Color.alpha(rendered.getPixel(40, 40)));
                    assertEquals(Color.TRANSPARENT, rendered.getPixel(20, 20));
                    if (previous.get() != null) {
                        assertFalse("The live clock must animate the keys", previous.get().sameAs(rendered));
                        previous.getAndSet(null).recycle();
                    }
                    previous.set(rendered);
                    rendered = null;
                } finally {
                    expected.recycle();
                    if (rendered != null) rendered.recycle();
                }
            }
        } finally { if (previous.get() != null) previous.get().recycle(); }
    }

    @Test public void finalPhraseFitsThePlateAndDoesNotPaintOverTheKeysOrLock() throws Exception {
        Bitmap plain = render(context(), DESTINATION, false, 1800);
        Bitmap labelled = render(context(), DESTINATION, true, 1800);
        RectF plate = new RectF();
        LockpadArtwork.labelBounds(DESTINATION, plate);
        try {
            int changed = 0;
            for (int y = 0; y < HEIGHT; y++) for (int x = 0; x < WIDTH; x++) {
                if (plain.getPixel(x, y) == labelled.getPixel(x, y)) continue;
                assertTrue("A phrase escaped its artwork plate at " + x + "," + y,
                        x >= Math.floor(plate.left) && x < Math.ceil(plate.right)
                                && y >= Math.floor(plate.top) && y < Math.ceil(plate.bottom));
                changed++;
            }
            assertTrue("The configured phrase must be visible", changed > 20);
            retain(labelled, "lockpad-live-labelled.png");
        } finally { plain.recycle(); labelled.recycle(); }
        RectF compact = new RectF(40, 40, 88, 280);
        Bitmap without = render(context(), compact, false, 1800);
        Bitmap with = render(context(), compact, true, 1800);
        try { assertTrue("Compact Lockpad must omit phrases", without.sameAs(with)); }
        finally { without.recycle(); with.recycle(); }
    }

    @Test public void unreadableHighDensityPlateOmitsTextWithoutLosingCoverage() throws Exception {
        Configuration configuration = new Configuration(context().getResources().getConfiguration());
        configuration.densityDpi = 480;
        Context dense = context().createConfigurationContext(configuration);
        assertEquals(3f, dense.getResources().getDisplayMetrics().density, .001f);
        RectF small = new RectF(40, 40, 140, 104);
        RectF plate = new RectF();
        LockpadArtwork.labelBounds(small, plate);
        assertFalse("This is full artwork but has an unreadable dp-sized label", plate.isEmpty());
        Bitmap plain = render(dense, small, false, 1800);
        Bitmap labelled = render(dense, small, true, 1800);
        try {
            assertTrue("Do not clip or shrink a phrase below the live minimum", plain.sameAs(labelled));
            assertEquals(255, Color.alpha(labelled.getPixel(40, 40)));
        } finally { plain.recycle(); labelled.recycle(); }
    }

    @Test public void displayedLockpadRetainsOnlyTheOwnerTapRegionAndAnimationScheduler() throws Exception {
        InstrumentationRegistry.getInstrumentation().getUiAutomation();
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        CensorTapTracker.shared().clear();
        AtomicReference<CensorOverlayView> overlay = new AtomicReference<>();
        try (ActivityScenario<CensorTapFixtureActivity> scenario = ActivityScenario.launch(CensorTapFixtureActivity.class)) {
            scenario.onActivity(activity -> {
                CensorOverlayView view = configured(activity, DESTINATION, true);
                view.setTapSource(false, CensorTapTracker.shared().scope());
                view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                ((FrameLayout) activity.findViewById(android.R.id.content)).addView(view,
                        new FrameLayout.LayoutParams(WIDTH, HEIGHT));
                overlay.set(view);
            });
            awaitFrame(scenario);
            AtomicReference<int[]> location = new AtomicReference<>();
            scenario.onActivity(activity -> {
                int[] point = new int[2]; overlay.get().getLocationOnScreen(point); location.set(point);
                assertTrue("Lockpad participates in the existing animation scheduler",
                        (Boolean) field(overlay.get(), "frameCallbackPosted"));
            });
            Bitmap pixels = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
            assertNotNull(pixels);
            try {
                retain(pixels, "lockpad-native-overlay.png");
                int x = location.get()[0], y = location.get()[1];
                assertNotEquals("Owner corner is opaque Lockpad, not the fixture", Color.WHITE,
                        pixels.getPixel(x + 40, y + 40));
                assertTrue("Displayed artwork is billable within the same owner footprint",
                        CensorTapTracker.shared().matchesClick(x + 90, y + 90, x + 120, y + 120,
                                pixels.getWidth(), pixels.getHeight(), SystemClock.uptimeMillis()));
                assertFalse("Artwork cannot enlarge tribute eligibility",
                        CensorTapTracker.shared().matchesClick(x + 10, y + 10, x + 30, y + 30,
                                pixels.getWidth(), pixels.getHeight(), SystemClock.uptimeMillis()));
                scenario.onActivity(activity -> overlay.get().clearContent());
                assertFalse("Clearing artwork clears displayed membership immediately",
                        CensorTapTracker.shared().matchesClick(x + 90, y + 90, x + 120, y + 120,
                                pixels.getWidth(), pixels.getHeight(), SystemClock.uptimeMillis()));
            } finally { pixels.recycle(); }
        } finally { CensorTapTracker.shared().clear(); }
    }

    private static CensorOverlayView configured(Context context, RectF rect, boolean labels) {
        CensorOverlayView view = new CensorOverlayView(context);
        view.setAppearance(new CensorAppearance(CensorAppearance.Type.ERROR_POPUP, 75, 0f,
                false, false, CensorAppearance.BorderEffect.CLASSIC, labels, Color.MAGENTA,
                palette(), List.of(PHRASE), false, 100, "rectangle", "Unused title", "Unused message"));
        ObjectTracker tracker = new ObjectTracker(DetectorConfig.builder().build());
        view.setTracks(tracker.update(List.of(new Detection("TEST", "face", 1f,
                new BBox(Math.round(rect.left), Math.round(rect.top), Math.round(rect.width()),
                        Math.round(rect.height())), true, false))), WIDTH, HEIGHT, null);
        return view;
    }

    private static Bitmap render(Context context, RectF rect, boolean labels, long time) throws Exception {
        AtomicReference<Bitmap> result = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            CensorOverlayView view = configured(context, rect, labels);
            try {
                view.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY));
                view.layout(0, 0, WIDTH, HEIGHT);
                view.setRenderTimeForTest(time);
                Bitmap bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
                view.draw(new Canvas(bitmap));
                if (labels && rect.equals(DESTINATION)) {
                    List<?> placements = (List<?>) field(view, "labelPlacements");
                    assertEquals("One final label after the artwork", 1, placements.size());
                    Object placement = placements.get(0);
                    Paint paint = new Paint((Paint) field(view, "label"));
                    paint.setTextSize((Float) field(placement, "textSize"));
                    RectF band = (RectF) field(placement, "band");
                    assertTrue("Each drawn string is measured against its actual region",
                            paint.measureText((String) field(placement, "text")) <= band.width());
                    assertTrue("The final phrase fits vertically", paint.descent() - paint.ascent() <= band.height());
                }
                result.set(bitmap);
            } finally { view.release(); }
        });
        return result.get();
    }

    private static Object field(Object instance, String name) {
        try {
            Field field = instance.getClass().getDeclaredField(name); field.setAccessible(true);
            return field.get(instance);
        } catch (ReflectiveOperationException failed) { throw new AssertionError(failed); }
    }

    private static void awaitFrame(ActivityScenario<CensorTapFixtureActivity> scenario) throws Exception {
        CountDownLatch committed = new CountDownLatch(1);
        scenario.onActivity(activity -> {
            View root = activity.findViewById(android.R.id.content);
            assertTrue(root.isHardwareAccelerated());
            root.getViewTreeObserver().registerFrameCommitCallback(committed::countDown);
            root.invalidate();
        });
        assertTrue("A new native frame committed", committed.await(5, TimeUnit.SECONDS));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    private static void retain(Bitmap bitmap, String filename) throws Exception {
        File output = new File(context().getExternalFilesDir(null), filename);
        try (FileOutputStream stream = new FileOutputStream(output)) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream));
        }
    }
    private static Context context() { return ApplicationProvider.getApplicationContext(); }
    private static EffectPalette palette() { return EffectPalette.defaultsFor(CensorAppearance.Type.ERROR_POPUP); }
}
