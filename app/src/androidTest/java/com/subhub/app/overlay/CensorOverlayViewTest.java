package com.subhub.app.overlay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.View;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.subhub.app.detection.BBox;
import com.subhub.app.detection.Detection;
import com.subhub.app.detection.DetectorConfig;
import com.subhub.app.detection.ObjectTracker;
import com.subhub.app.detection.TrackedObject;
import com.subhub.app.settings.CensorAppearance;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(AndroidJUnit4.class)
public final class CensorOverlayViewTest {
    @Test public void boxPixelsAreFullyOpaqueBlackAndExplicitClearHidesTheView() {
        Context context = ApplicationProvider.getApplicationContext();
        CensorOverlayView view = new CensorOverlayView(context);
        view.setAppearance(new CensorAppearance(
                CensorAppearance.Type.BOX, 1, 0f, false, false,
                CensorAppearance.BorderEffect.CLASSIC, false, Color.MAGENTA,
                List.of(), false, 100, "rectangle", "SubHub", "Blocked"));
        ObjectTracker tracker = new ObjectTracker(DetectorConfig.builder().build());
        List<TrackedObject> tracks = tracker.update(List.of(new Detection(
                "EXPOSED_TEST", "EXPOSED", 1f, new BBox(20, 20, 60, 60), true, true)));
        view.setTracks(tracks, 100, 100, null);
        view.measure(exactly(100), exactly(100));
        view.layout(0, 0, 100, 100);

        Bitmap rendered = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
        rendered.eraseColor(Color.TRANSPARENT);
        view.draw(new Canvas(rendered));

        assertEquals(Color.BLACK, rendered.getPixel(50, 50));
        view.clearContent();
        assertEquals(View.INVISIBLE, view.getVisibility());
        rendered.recycle();
        view.release();
    }

    @Test public void everyLiveEffectRendersContinuouslyAndProducesVisualEvidence()
            throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        List<TrackedObject> tracks = confirmedTracks();
        int tileWidth = 240;
        int tileHeight = 180;
        int labelHeight = 28;
        Bitmap gallery = Bitmap.createBitmap(
                tileWidth * 3, (tileHeight + labelHeight) * 3, Bitmap.Config.ARGB_8888);
        gallery.eraseColor(Color.rgb(12, 7, 18));
        Canvas galleryCanvas = new Canvas(gallery);
        Paint caption = new Paint(Paint.ANTI_ALIAS_FLAG);
        caption.setColor(Color.WHITE);
        caption.setTextSize(16f);

        long started = SystemClock.elapsedRealtimeNanos();
        int index = 0;
        for (CensorAppearance.Type type : CensorAppearance.Type.values()) {
            CensorOverlayView view = new CensorOverlayView(context);
            view.setAppearance(appearance(type, true));
            Bitmap frame = gradient(tileWidth, tileHeight, index * 19);
            view.setTracks(tracks, tileWidth, tileHeight, frame);
            view.measure(exactly(tileWidth), exactly(tileHeight));
            view.layout(0, 0, tileWidth, tileHeight);

            Bitmap rendered = Bitmap.createBitmap(
                    tileWidth, tileHeight, Bitmap.Config.ARGB_8888);
            rendered.eraseColor(Color.TRANSPARENT);
            view.draw(new Canvas(rendered));
            assertTrue(type + " should draw a stable censor region",
                    opaquePixels(rendered, 45, 30, 195, 150) > 400);
            assertEquals(type + " should leave a distant corner clear",
                    Color.TRANSPARENT, rendered.getPixel(4, 4));

            int column = index % 3;
            int row = index / 3;
            float left = column * tileWidth;
            float top = row * (tileHeight + labelHeight);
            galleryCanvas.drawBitmap(rendered, left, top, null);
            galleryCanvas.drawText(type.name(), left + 8, top + tileHeight + 20, caption);
            rendered.recycle();
            view.release();
            index++;
        }
        long elapsedMs = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000L;
        assertTrue("Synthetic live renderer pass took " + elapsedMs + " ms", elapsedMs < 4_000L);

        File directory = new File(context.getExternalFilesDir(null), "renderer-map");
        assertTrue(directory.exists() || directory.mkdirs());
        try (FileOutputStream output = new FileOutputStream(
                new File(directory, "live-renderers.png"))) {
            assertTrue(gallery.compress(Bitmap.CompressFormat.PNG, 100, output));
        }
        gallery.recycle();
    }

    @Test public void customPhraseIsVisibleAndFrameRefreshNeverBlanksTheCensor() {
        Context context = ApplicationProvider.getApplicationContext();
        List<TrackedObject> tracks = confirmedTracks();
        CensorOverlayView view = new CensorOverlayView(context);
        view.setAppearance(appearance(CensorAppearance.Type.PIXELATE, true));
        view.measure(exactly(240), exactly(180));
        view.layout(0, 0, 240, 180);

        view.setTracks(tracks, 240, 180, gradient(240, 180, 0));
        Bitmap first = draw(view, 240, 180);
        view.setTracks(tracks, 240, 180, gradient(240, 180, 91));
        Bitmap second = draw(view, 240, 180);

        assertTrue(opaquePixels(first, 45, 30, 195, 150) > 400);
        assertTrue(opaquePixels(second, 45, 30, 195, 150) > 400);
        assertNotEquals(first.getPixel(90, 70), second.getPixel(90, 70));
        assertTrue("The configured phrase should draw light glyph pixels",
                lightPixels(second, 45, 55, 195, 125) > 8);
        first.recycle();
        second.recycle();
        view.release();
    }

    @Test public void scrollMotionMovesExistingCensorBeforeNextDetectorFrame() {
        Context context = ApplicationProvider.getApplicationContext();
        CensorOverlayView view = new CensorOverlayView(context);
        view.setAppearance(new CensorAppearance(
                CensorAppearance.Type.BOX, 1, 0f, false, false,
                CensorAppearance.BorderEffect.CLASSIC, false, Color.MAGENTA,
                List.of(), false, 100, "rectangle", "SubHub", "Blocked"));
        ObjectTracker tracker = new ObjectTracker(DetectorConfig.builder().build());
        List<TrackedObject> tracks = tracker.update(List.of(new Detection(
                "EXPOSED_TEST", "EXPOSED", 1f, new BBox(20, 20, 60, 60), true, true)));
        view.setTracks(tracks, 100, 100, null);
        view.measure(exactly(100), exactly(100));
        view.layout(0, 0, 100, 100);

        view.offsetContent(0, -20);
        // Authoritative Accessibility geometry is exact immediately; established sparse streams
        // may then retain a bounded prediction pulse between events.
        view.setRenderTimeForTest(SystemClock.uptimeMillis() + 100L);
        Bitmap moved = draw(view, 100, 100);

        assertEquals(Color.BLACK, moved.getPixel(50, 30));
        assertEquals(Color.TRANSPARENT, moved.getPixel(50, 70));
        moved.recycle();
        view.release();
    }

    @Test public void expiredScrollMovesCensorWithoutStartingAnotherFling() {
        Context context = ApplicationProvider.getApplicationContext();
        CensorOverlayView view = new CensorOverlayView(context);
        view.setAppearance(new CensorAppearance(
                CensorAppearance.Type.BOX, 1, 0f, false, false,
                CensorAppearance.BorderEffect.CLASSIC, false, Color.MAGENTA,
                List.of(), false, 100, "rectangle", "SubHub", "Blocked"));
        ObjectTracker tracker = new ObjectTracker(DetectorConfig.builder().build());
        List<TrackedObject> tracks = tracker.update(List.of(new Detection(
                "EXPOSED_TEST", "EXPOSED", 1f, new BBox(20, 20, 60, 60), true, true)));
        view.setTracks(tracks, 100, 100, null);
        view.measure(exactly(100), exactly(100));
        view.layout(0, 0, 100, 100);
        try {
            long now = SystemClock.uptimeMillis();
            view.offsetContent(0, -20, true, now - 1000L);
            view.setRenderTimeForTest(now + 16L);
            Bitmap first = draw(view, 100, 100);
            view.setRenderTimeForTest(now + 96L);
            Bitmap later = draw(view, 100, 100);
            try {
                assertEquals(Color.BLACK, first.getPixel(50, 30));
                assertEquals(Color.TRANSPARENT, first.getPixel(50, 70));
                assertTrue("An expired callback must not move the censor again", first.sameAs(later));
            } catch (AssertionError failure) {
                try {
                    File directory = new File(context.getExternalFilesDir(null),
                            "scroll-delivery-failure-" + System.nanoTime());
                    if (!directory.mkdir()) throw new java.io.IOException("Cannot retain failed scroll pixels");
                    for (int i = 0; i < 2; i++) {
                        try (FileOutputStream output = new FileOutputStream(new File(directory, i + ".png"))) {
                            if (!(i == 0 ? first : later).compress(Bitmap.CompressFormat.PNG, 100, output)) {
                                throw new java.io.IOException("Cannot encode failed scroll pixels");
                            }
                        }
                    }
                } catch (Exception retentionFailure) {
                    failure.addSuppressed(retentionFailure);
                }
                throw failure;
            } finally {
                first.recycle();
                later.recycle();
            }
        } finally {
            view.release();
        }
    }

    @Test public void animatedRainbowBorderNeverEscapesItsWideTrackedRectangle() {
        Context context = ApplicationProvider.getApplicationContext();
        CensorOverlayView view = new CensorOverlayView(context);
        view.setAppearance(new CensorAppearance(
                CensorAppearance.Type.BOX, 1, 0f, true, true,
                CensorAppearance.BorderEffect.RAINBOW, false, Color.MAGENTA,
                List.of(), false, 100, "rectangle", "SubHub", "Blocked"));
        view.setBorderAnimationTimeForTest(1_000L);
        ObjectTracker tracker = new ObjectTracker(DetectorConfig.builder().build());
        List<Detection> detections = List.of(new Detection(
                "EXPOSED_TEST", "EXPOSED", 1f,
                new BBox(20, 70, 200, 20), true, true));
        List<TrackedObject> tracks = List.of();
        for (int frame = 0; frame < 5; frame++) tracks = tracker.update(detections);
        view.setTracks(tracks, 240, 180, null);
        view.measure(exactly(240), exactly(180));
        view.layout(0, 0, 240, 180);

        Bitmap rendered = draw(view, 240, 180);

        assertTrue("The tracked censor should still render", opaquePixels(
                rendered, 20, 70, 220, 90) > 500);
        assertEquals("An animated border must not rotate into a display-spanning line",
                0, opaquePixelsOutside(rendered, 20, 70, 220, 90));
        rendered.recycle();
        view.release();
    }

    @Test public void worldSpaceBirthSharesTheEstablishedTrackCamera() {
        Context context = ApplicationProvider.getApplicationContext();
        CensorOverlayView view = new CensorOverlayView(context);
        view.setAppearance(new CensorAppearance(
                CensorAppearance.Type.BOX, 1, 0f, false, false,
                CensorAppearance.BorderEffect.CLASSIC, false, Color.MAGENTA,
                List.of(), false, 100, "rectangle", "SubHub", "Blocked"));
        view.measure(exactly(100), exactly(100));
        view.layout(0, 0, 100, 100);

        ObjectTracker tracker = new ObjectTracker(DetectorConfig.builder().build());
        List<TrackedObject> tracks = tracker.update(List.of(new Detection(
                "EXPOSED_A", "EXPOSED", 1f,
                new BBox(10, 40, 20, 20), true, true)));
        view.setWorldTracks(tracks, 100, 100, null,
                0L, 0L, 0L, 0L, 100, 100, null);

        long eventTime = SystemClock.uptimeMillis();
        view.offsetContent(0, -20, true, eventTime);
        tracker.offsetActiveTracks(0, -20, 100, 100);
        tracks = tracker.update(List.of(
                new Detection("EXPOSED_A", "EXPOSED", 1f,
                        new BBox(10, 20, 20, 20), true, true),
                new Detection("EXPOSED_B", "EXPOSED", 1f,
                        new BBox(60, 60, 20, 20), true, true)));
        view.setWorldTracks(tracks, 100, 100, null,
                0L, 20L, 0L, 20L, 100, 100, null);

        for (long frameOffset : new long[] {0L, 8L, 17L}) {
            view.setRenderTimeForTest(eventTime + frameOffset);
            Bitmap firstFrame = draw(view, 100, 100);
            int establishedTop = firstOpaqueY(firstFrame, 20);
            int bornTop = firstOpaqueY(firstFrame, 70);
            assertTrue("Established track is visible on frame " + frameOffset,
                    establishedTop >= 0);
            assertTrue("Mid-scroll birth is visible on frame " + frameOffset, bornTop >= 0);
            assertEquals("Both tracks must share one camera on frame " + frameOffset,
                    40, bornTop - establishedTop);
            firstFrame.recycle();
        }
        view.setRenderTimeForTest(eventTime + 100L);

        Bitmap rendered = draw(view, 100, 100);

        assertEquals("Established track follows the canonical camera",
                Color.BLACK, rendered.getPixel(20, 30));
        assertEquals("A track born during motion uses that same camera",
                Color.BLACK, rendered.getPixel(70, 70));
        assertEquals("The established box is not left at its pre-scroll phase",
                Color.TRANSPARENT, rendered.getPixel(20, 50));
        rendered.recycle();
        view.release();
    }

    @Test public void delayedEventTimestampDoesNotStartPresentationInThePast() {
        Context context = ApplicationProvider.getApplicationContext();
        CensorOverlayView view = new CensorOverlayView(context);
        view.setAppearance(new CensorAppearance(
                CensorAppearance.Type.BOX, 1, 0f, false, false,
                CensorAppearance.BorderEffect.CLASSIC, false, Color.MAGENTA,
                List.of(), false, 100, "rectangle", "SubHub", "Blocked"));
        view.measure(exactly(100), exactly(100));
        view.layout(0, 0, 100, 100);
        ObjectTracker tracker = new ObjectTracker(DetectorConfig.builder().build());
        List<TrackedObject> tracks = tracker.update(List.of(new Detection(
                "EXPOSED_A", "EXPOSED", 1f,
                new BBox(20, 40, 20, 20), true, true)));
        view.setWorldTracks(tracks, 100, 100, null,
                0L, 0L, 0L, 0L, 100, 100, null);

        long received = SystemClock.uptimeMillis();
        view.offsetContent(0, -20, true, received - 100L);
        view.setRenderTimeForTest(received);
        Bitmap firstDrawableFrame = draw(view, 100, 100);

        int firstTop = firstOpaqueY(firstDrawableFrame, 30);
        assertTrue("Presentation begins when the callback can first be drawn: " + firstTop,
                firstTop >= 20 && firstTop <= 21);
        firstDrawableFrame.recycle();
        view.release();
    }

    @Test public void worldSourceFrameUsesItsOwnCameraAtNonUnitScale() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        CensorOverlayView view = new CensorOverlayView(context);
        view.setAppearance(new CensorAppearance(
                CensorAppearance.Type.PIXELATE, 1, 0f, false, false,
                CensorAppearance.BorderEffect.CLASSIC, false, Color.MAGENTA,
                List.of(), false, 100, "rectangle", "SubHub", "Blocked"));
        int sourceWidth = 320;
        int sourceHeight = 714;
        int viewportWidth = 1344;
        int viewportHeight = 2992;
        view.measure(exactly(viewportWidth), exactly(viewportHeight));
        view.layout(0, 0, viewportWidth, viewportHeight);

        long now = SystemClock.uptimeMillis();
        view.offsetContent(0, -900, true, now);
        ObjectTracker tracker = new ObjectTracker(DetectorConfig.builder().build());
        List<TrackedObject> tracks = tracker.update(List.of(new Detection(
                "EXPOSED_A", "EXPOSED", 1f,
                new BBox(100, 63, 80, 20), true, true)));
        view.setWorldTracks(tracks, sourceWidth, sourceHeight,
                gradient(sourceWidth, sourceHeight, 0),
                0L, 900L, 0L, 600L,
                viewportWidth, viewportHeight, null);
        view.setRenderTimeForTest(now + 400L);

        Bitmap rendered = draw(view, viewportWidth, viewportHeight);
        Field sourceRectField = CensorOverlayView.class.getDeclaredField("sourceRect");
        sourceRectField.setAccessible(true);
        Rect sampled = new Rect((Rect) sourceRectField.get(view));

        assertEquals("World geometry should sample the object in the older source frame",
                135, sampled.top);
        assertEquals(155, sampled.bottom);
        assertTrue("The effect should be drawn at the current camera phase",
                opaquePixels(rendered, 410, 260, 770, 355) > 100);
        rendered.recycle();
        view.release();
    }

    @Test public void worldTrackCanLeaveAndReenterWithoutLosingGeometry() {
        Context context = ApplicationProvider.getApplicationContext();
        CensorOverlayView view = new CensorOverlayView(context);
        view.setAppearance(new CensorAppearance(
                CensorAppearance.Type.BOX, 1, 0f, false, false,
                CensorAppearance.BorderEffect.CLASSIC, false, Color.MAGENTA,
                List.of(), false, 100, "rectangle", "SubHub", "Blocked"));
        view.measure(exactly(100), exactly(100));
        view.layout(0, 0, 100, 100);
        ObjectTracker tracker = new ObjectTracker(DetectorConfig.builder().build());
        List<TrackedObject> tracks = tracker.update(List.of(new Detection(
                "EXPOSED_A", "EXPOSED", 1f,
                new BBox(20, 20, 30, 20), true, true)));
        view.setWorldTracks(tracks, 100, 100, null,
                0L, 0L, 0L, 0L, 100, 100, null);

        long now = SystemClock.uptimeMillis();
        view.offsetContent(0, -60, true, now);
        view.setRenderTimeForTest(now + 400L);
        Bitmap outside = draw(view, 100, 100);
        assertEquals(0, opaquePixels(outside, 0, 0, 100, 100));

        view.offsetContent(0, 50, true, now + 401L);
        view.setRenderTimeForTest(now + 800L);
        Bitmap returned = draw(view, 100, 100);
        assertEquals(Color.BLACK, returned.getPixel(30, 20));

        outside.recycle();
        returned.recycle();
        view.release();
    }

    @Test public void cachedWorldRegionSurvivesLivePublisherReplacementAndReenters()
            throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        CensorOverlayView view = new CensorOverlayView(context);
        view.setAppearance(new CensorAppearance(
                CensorAppearance.Type.BOX, 1, 0f, false, false,
                CensorAppearance.BorderEffect.CLASSIC, false, Color.MAGENTA,
                List.of(), false, 100, "rectangle", "SubHub", "Blocked"));
        view.measure(exactly(100), exactly(100));
        view.layout(0, 0, 100, 100);
        ObjectTracker tracker = new ObjectTracker(DetectorConfig.builder().build());
        List<TrackedObject> live = tracker.update(List.of(new Detection(
                "FACE_FEMALE", "face_female", 1f,
                new BBox(20, 20, 30, 20), true, true)));
        view.setWorldTracks(live, 100, 100, null,
                0L, 0L, 0L, 0L, 100, 100, null);

        long now = SystemClock.uptimeMillis();
        view.offsetContent(0, -60, true, now);
        Detection cached = new Detection(
                "FACE_FEMALE", "face_female", 1f,
                new BBox(20, -40, 30, 20), true, true,
                Detection.ObservationSource.EXACT_GEOMETRY,
                Detection.GeometryQuality.EXACT, "world-cache:7");
        view.setWorldTracksAndCache(Collections.emptyList(), List.of(cached),
                100, 100, null, 0L, 60L, 0L, 60L,
                100, 100, null);

        Field tracksField = CensorOverlayView.class.getDeclaredField("tracks");
        tracksField.setAccessible(true);
        assertEquals(1, ((List<?>) tracksField.get(view)).size());

        view.offsetContent(0, 50, true, now + 1L);
        view.setRenderTimeForTest(now + 400L);
        Bitmap returned = draw(view, 100, 100);
        assertEquals(Color.BLACK, returned.getPixel(30, 20));

        returned.recycle();
        view.release();
    }

    @Test public void liveWorldRegionSuppressesOverlappingCachedCopy() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        CensorOverlayView view = new CensorOverlayView(context);
        view.measure(exactly(100), exactly(100));
        view.layout(0, 0, 100, 100);
        ObjectTracker tracker = new ObjectTracker(DetectorConfig.builder().build());
        List<TrackedObject> live = tracker.update(List.of(new Detection(
                "FACE_FEMALE", "face_female", 1f,
                new BBox(20, 20, 30, 20), true, true)));
        Detection cached = new Detection(
                "FACE_FEMALE", "face_female", 1f,
                new BBox(20, 20, 30, 20), true, true,
                Detection.ObservationSource.EXACT_GEOMETRY,
                Detection.GeometryQuality.EXACT, "world-cache:7");

        view.setWorldTracksAndCache(live, List.of(cached),
                100, 100, null, 0L, 0L, 0L, 0L,
                100, 100, null);

        Field tracksField = CensorOverlayView.class.getDeclaredField("tracks");
        tracksField.setAccessible(true);
        assertEquals(1, ((List<?>) tracksField.get(view)).size());
        view.release();
    }

    @Test public void cachedWorldRegionPreservesGeometryWhenCaptureAndViewportDiffer()
            throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        CensorOverlayView view = new CensorOverlayView(context);
        view.setAppearance(new CensorAppearance(
                CensorAppearance.Type.BOX, 1, 0f, false, false,
                CensorAppearance.BorderEffect.CLASSIC, false, Color.MAGENTA,
                List.of(), false, 100, "rectangle", "SubHub", "Blocked"));
        view.measure(exactly(100), exactly(100));
        view.layout(0, 0, 100, 100);
        view.setWorldTracks(Collections.emptyList(), 200, 200, null,
                0L, 0L, 0L, 0L, 100, 100, null);

        long now = SystemClock.uptimeMillis();
        view.offsetContent(0, -50, true, now);
        // Cache query converts display-world y=70 at camera y=50 into source-screen y=40.
        Detection cached = new Detection(
                "FACE_FEMALE", "face_female", 1f,
                new BBox(40, 40, 60, 40), true, true,
                Detection.ObservationSource.EXACT_GEOMETRY,
                Detection.GeometryQuality.EXACT, "world-cache:scaled");
        view.setWorldCacheRegions(List.of(cached), 200, 200,
                0L, 50L, 100, 100);
        view.setRenderTimeForTest(now + 400L);

        Bitmap rendered = draw(view, 100, 100);
        assertEquals(Color.BLACK, rendered.getPixel(30, 25));
        assertTrue(Color.alpha(rendered.getPixel(30, 5)) == 0);

        rendered.recycle();
        view.release();
    }

    @Test public void worldTextPublicationDoesNotApplyTheCameraTwice() {
        Context context = ApplicationProvider.getApplicationContext();
        CensorOverlayView view = new CensorOverlayView(context);
        view.setAppearance(new CensorAppearance(
                CensorAppearance.Type.BOX, 1, 0f, false, false,
                CensorAppearance.BorderEffect.CLASSIC, false, Color.MAGENTA,
                List.of(), false, 100, "rectangle", "SubHub", "Blocked"));
        view.measure(exactly(100), exactly(100));
        view.layout(0, 0, 100, 100);
        Detection initial = new Detection(
                "TEXT_EXPLICIT", "text_smut", 0.95f,
                new BBox(20, 40, 60, 20), true, true,
                Detection.ObservationSource.ACCESSIBILITY,
                Detection.GeometryQuality.EXACT, "node:stable");
        view.setWorldTextDetections(List.of(initial), 100, 100,
                0L, 0L, 100, 100);

        long now = SystemClock.uptimeMillis();
        view.offsetContent(0, -20, true, now);
        Detection currentScreen = new Detection(
                "TEXT_EXPLICIT", "text_smut", 0.95f,
                new BBox(20, 20, 60, 20), true, true,
                Detection.ObservationSource.ACCESSIBILITY,
                Detection.GeometryQuality.EXACT, "node:stable");
        view.setWorldTextDetections(List.of(currentScreen), 100, 100,
                0L, 20L, 100, 100);
        view.setRenderTimeForTest(now + 400L);
        Bitmap rendered = draw(view, 100, 100);

        assertEquals(Color.BLACK, rendered.getPixel(50, 30));
        assertEquals(Color.TRANSPARENT, rendered.getPixel(50, 50));
        rendered.recycle();
        view.release();
    }

    @Test public void replacingPooledSourceFrameReturnsOwnershipExactlyOnce() {
        Context context = ApplicationProvider.getApplicationContext();
        CensorOverlayView view = new CensorOverlayView(context);
        Bitmap pooled = gradient(100, 100, 0);
        AtomicInteger releases = new AtomicInteger();
        List<TrackedObject> tracks = confirmedTracks();

        view.setTracks(tracks, 100, 100, pooled, 0, 0, 0, 0,
                releases::incrementAndGet);
        view.setTracks(tracks, 100, 100, null);

        assertEquals(1, releases.get());
        assertTrue(!pooled.isRecycled());
        pooled.recycle();
        view.release();
    }

    private static CensorAppearance appearance(CensorAppearance.Type type, boolean text) {
        return new CensorAppearance(type, 82, .08f, true, false,
                CensorAppearance.BorderEffect.CLASSIC, text, Color.MAGENTA,
                Arrays.asList("CUSTOM PHRASE"), false, 88, "rectangle",
                "SubHub", "Access blocked");
    }

    private static List<TrackedObject> confirmedTracks() {
        ObjectTracker tracker = new ObjectTracker(DetectorConfig.builder().build());
        List<Detection> detections = new ArrayList<>();
        detections.add(new Detection("EXPOSED_TEST", "EXPOSED", 1f,
                new BBox(55, 40, 130, 100), true, true));
        List<TrackedObject> tracks = List.of();
        for (int frame = 0; frame < 5; frame++) tracks = tracker.update(detections);
        return tracks;
    }

    private static Bitmap gradient(int width, int height, int shift) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint();
        for (int y = 0; y < height; y++) {
            paint.setColor(Color.rgb((y + shift) % 256, 50, (255 - y + shift) & 0xff));
            canvas.drawRect(0, y, width, y + 1, paint);
        }
        return bitmap;
    }

    private static Bitmap draw(View view, int width, int height) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.TRANSPARENT);
        view.draw(new Canvas(bitmap));
        return bitmap;
    }

    private static int opaquePixels(Bitmap bitmap, int left, int top, int right, int bottom) {
        int count = 0;
        for (int y = top; y < bottom; y += 2) {
            for (int x = left; x < right; x += 2) {
                if (Color.alpha(bitmap.getPixel(x, y)) > 240) count++;
            }
        }
        return count;
    }

    private static int lightPixels(Bitmap bitmap, int left, int top, int right, int bottom) {
        int count = 0;
        for (int y = top; y < bottom; y++) {
            for (int x = left; x < right; x++) {
                int pixel = bitmap.getPixel(x, y);
                if (Color.red(pixel) > 190 && Color.green(pixel) > 190
                        && Color.blue(pixel) > 190) count++;
            }
        }
        return count;
    }

    private static int opaquePixelsOutside(
            Bitmap bitmap, int left, int top, int right, int bottom) {
        int count = 0;
        for (int y = 0; y < bitmap.getHeight(); y++) {
            for (int x = 0; x < bitmap.getWidth(); x++) {
                if (x >= left && x < right && y >= top && y < bottom) continue;
                if (Color.alpha(bitmap.getPixel(x, y)) > 0) count++;
            }
        }
        return count;
    }

    private static int firstOpaqueY(Bitmap bitmap, int x) {
        for (int y = 0; y < bitmap.getHeight(); y++) {
            if (Color.alpha(bitmap.getPixel(x, y)) > 240) return y;
        }
        return -1;
    }

    private static int exactly(int pixels) {
        return View.MeasureSpec.makeMeasureSpec(pixels, View.MeasureSpec.EXACTLY);
    }
}
