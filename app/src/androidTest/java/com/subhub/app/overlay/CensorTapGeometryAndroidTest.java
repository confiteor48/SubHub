package com.subhub.app.overlay;

import static org.junit.Assert.*;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.View;
import android.widget.FrameLayout;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.detection.BBox;
import com.subhub.app.detection.Detection;
import com.subhub.app.detection.DetectorConfig;
import com.subhub.app.detection.ObjectTracker;
import com.subhub.app.detection.TrackedObject;
import com.subhub.app.detection.PersonBox;
import com.subhub.app.detection.RenderSourceReference;
import com.subhub.app.diagnostics.CensorLabCensorSession;
import com.subhub.app.penance.CensorTapTracker;
import com.subhub.app.settings.CensorAppearance;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion = 29)
public final class CensorTapGeometryAndroidTest {
    private static final int WIDTH = 320, HEIGHT = 480;

    @Test public void visibleTextOnlyCensorIsATapTarget() {
        withView(0f, view -> {
            view.setTextDetections(List.of(region(new BBox(100, 160, 120, 100), "text_smut", null)),
                    WIDTH, HEIGHT, 0, 0);
        }, new Rect(130, 190, 170, 230));
    }

    @Test public void visiblePaddingIsATapTargetOutsideTheRawDetection() {
        withView(.5f, view -> {
            List<TrackedObject> tracks = tracks(new BBox(100, 160, 120, 100));
            view.setTracks(tracks, WIDTH, HEIGHT, null);
        }, new Rect(65, 185, 85, 215));
    }

    @Test public void visibleCachedCensorIsATapTargetWithoutALiveTrack() {
        withView(0f, view -> {
            view.setWorldTracksAndCache(List.of(),
                    List.of(region(new BBox(100, 160, 120, 100), "face", "world-cache:fixture")),
                    WIDTH, HEIGHT, null, 0, 0, 0, 0, WIDTH, HEIGHT, null);
        }, new Rect(130, 190, 170, 230));
    }

    @Test public void visibleWholePersonCoverageIsATapTargetOutsideRawParts() {
        withView(0f, view -> {
            Detection chest = region(new BBox(65, 80, 70, 45), "breasts", null);
            Detection head = region(new BBox(80, 20, 40, 40), "face", null);
            view.setTracks(new ObjectTracker(DetectorConfig.builder().build()).update(List.of(chest)),
                    WIDTH, HEIGHT, null);
            long token = view.beginPersonCoverage(List.of(chest), List.of(head, chest),
                    SystemClock.uptimeMillis(), 0, 0, WIDTH, HEIGHT, RenderSourceReference.UNKNOWN);
            assertTrue(view.refinePersonCoverage(token,
                    List.of(new PersonBox(new BBox(60, 10, 90, 190), .9f))));
        }, new Rect(90, 155, 110, 180));
    }

    @Test public void predictedStillDisplayedMaskRemainsTappable() {
        withView(0f, view -> {
            ObjectTracker tracker = new ObjectTracker(DetectorConfig.builder().build());
            tracker.update(List.of(region(new BBox(100, 160, 120, 100), "face", null)));
            List<TrackedObject> predicted = tracker.update(List.of());
            assertTrue(predicted.get(0).getFramesMissing() > 0);
            view.setTracks(predicted, WIDTH, HEIGHT, null);
        }, new Rect(130, 190, 170, 230));
    }

    @Test public void stationaryVisibleDrawRefreshesWithoutAnotherInference() {
        withView(0f, view -> view.setTracks(tracks(new BBox(100, 160, 120, 100)),
                WIDTH, HEIGHT, null), new Rect(130, 190, 170, 230), true,
                view -> SystemClock.sleep(CensorTapTracker.MAX_DISPLAY_AGE_MILLIS + 250L), false);
    }

    @Test public void displayedMotionMatchesNewPixelsAndRejectsOldLocation() {
        withView(0f, view -> view.setTracks(tracks(new BBox(100, 160, 120, 100)),
                WIDTH, HEIGHT, null), new Rect(130, 110, 170, 150), true,
                view -> view.offsetContent(0, -80, true), true);
    }

    @Test public void labOwnedDisplayDoesNotBecomeBillableAfterRecordingStops() {
        withView(0f, view -> {
            view.setTapSourceLabOwned(true);
            view.setTracks(tracks(new BBox(100, 160, 120, 100)), WIDTH, HEIGHT, null);
            view.setTapSourceLabOwned(false); // Closing a recording cannot relabel retained pixels.
        }, new Rect(130, 190, 170, 230), false, null, false);
    }

    @Test public void activeLabCannotBillANormalScene() {
        try (CensorLabCensorSession.Lease ignored = CensorLabCensorSession.acquire()) {
            withView(0f, view -> {
                view.setTapSourceLabOwned(false);
                view.setTracks(tracks(new BBox(100, 160, 120, 100)), WIDTH, HEIGHT, null);
            }, new Rect(130, 190, 170, 230), false, null, false);
        }
    }

    @Test public void hiddenOrDetachedViewImmediatelyInvalidatesDisplayedHits() {
        withView(0f, view -> view.setTracks(tracks(new BBox(100, 160, 120, 100)),
                WIDTH, HEIGHT, null), new Rect(130, 190, 170, 230), false,
                view -> view.setVisibility(View.INVISIBLE), true);
    }

    @Test public void offscreenRenderedMaskCannotBillItsFormerLocation() {
        withView(0f, view -> view.setTracks(tracks(new BBox(100, 160, 120, 100)),
                WIDTH, HEIGHT, null), new Rect(130, 190, 170, 230), false,
                view -> view.offsetContent(0, -600, true), true, false);
    }

    @Test public void removingTheNativeViewInvalidatesHitsBeforeAnotherFrame() {
        withView(0f, view -> view.setTracks(tracks(new BBox(100, 160, 120, 100)),
                WIDTH, HEIGHT, null), new Rect(130, 190, 170, 230), false,
                view -> {
                    int[] location = new int[2];
                    view.getLocationOnScreen(location);
                    ((FrameLayout) view.getParent()).removeView(view);
                    assertFalse(view.isAttachedToWindow());
                    assertFalse("Detach must invalidate hits synchronously, without another draw",
                            CensorTapTracker.shared().matchesClick(130 + location[0], 190 + location[1],
                                    170 + location[0], 230 + location[1], WIDTH, HEIGHT,
                                    SystemClock.uptimeMillis()));
                }, true, false);
    }

    @Test public void replacedScopeCannotRearmFromALateDisplayedPacket() {
        withView(0f, view -> view.setTracks(tracks(new BBox(100, 160, 120, 100)),
                WIDTH, HEIGHT, null), new Rect(130, 190, 170, 230), false, view -> {
            long oldScope = CensorTapTracker.shared().scope();
            CensorTapTracker.shared().clear();
            view.setTapSource(false, oldScope);
            view.setTracks(tracks(new BBox(100, 160, 120, 100)), WIDTH, HEIGHT, null);
        }, true);
    }

    @Test public void imageExportCannotArmLiveTapBilling() {
        CensorTapTracker.shared().clear();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            CensorOverlayView view = new CensorOverlayView(
                    InstrumentationRegistry.getInstrumentation().getTargetContext());
            Bitmap image = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
            try {
                view.setAppearance(new CensorAppearance(CensorAppearance.Type.BOX, 100, 0f,
                        false, false, CensorAppearance.BorderEffect.CLASSIC, false, Color.MAGENTA,
                        List.of(), false, 100, "rectangle", "SubHub", "Blocked"));
                view.setTracks(tracks(new BBox(100, 160, 120, 100)), WIDTH, HEIGHT, null);
                view.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY));
                view.layout(0, 0, WIDTH, HEIGHT);
                view.draw(new Canvas(image));
                assertEquals(Color.BLACK, image.getPixel(150, 210));
                assertFalse(CensorTapTracker.shared().matchesClick(130, 190, 170, 230,
                        WIDTH, HEIGHT, SystemClock.uptimeMillis()));
            } finally { view.release(); image.recycle(); }
        });
    }

    private static void withView(float padding, Setup setup, Rect target) {
        withView(padding, setup, target, true, null, false);
    }

    private static void withView(float padding, Setup setup, Rect target, boolean expected,
            Setup afterDraw, boolean changeOnMain) {
        withView(padding, setup, target, expected, afterDraw, changeOnMain, true);
    }

    private static void withView(float padding, Setup setup, Rect target, boolean expected,
            Setup afterDraw, boolean changeOnMain, boolean expectCensorPixel) {
        CensorTapTracker.shared().clear();
        AtomicReference<CensorOverlayView> reference = new AtomicReference<>();
        try (ActivityScenario<CensorTapFixtureActivity> scenario =
                     ActivityScenario.launch(CensorTapFixtureActivity.class)) {
            scenario.onActivity(activity -> {
                CensorOverlayView view = new CensorOverlayView(activity);
                view.setAppearance(new CensorAppearance(CensorAppearance.Type.BOX, 100, padding,
                        false, false, CensorAppearance.BorderEffect.CLASSIC, false, Color.MAGENTA,
                        List.of(), false, 100, "rectangle", "SubHub", "Blocked"));
                ((FrameLayout) activity.findViewById(android.R.id.content)).addView(view,
                        new FrameLayout.LayoutParams(WIDTH, HEIGHT));
                reference.set(view);
                setup.accept(view);
                assertFalse("Undrawn content must never arm a tap target",
                        CensorTapTracker.shared().matchesClick(0, 0, WIDTH, HEIGHT,
                                WIDTH, HEIGHT, SystemClock.uptimeMillis()));
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            awaitCommittedFrame(scenario);
            SystemClock.sleep(150L);
            int[] displayedLocation = new int[2];
            scenario.onActivity(activity -> reference.get().getLocationOnScreen(displayedLocation));
            if (afterDraw != null) {
                if (changeOnMain) scenario.onActivity(activity -> afterDraw.accept(reference.get()));
                else afterDraw.accept(reference.get());
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                awaitCommittedFrame(scenario);
                SystemClock.sleep(150L);
            }
            AtomicReference<Rect> screenTarget = new AtomicReference<>();
            scenario.onActivity(activity -> {
                CensorOverlayView view = reference.get();
                if (expected || expectCensorPixel) assertTrue(view.isAttachedToWindow());
                if (expected) assertTrue(view.isShown());
                assertEquals(WIDTH, view.getWidth());
                int[] location = new int[2];
                if (view.isAttachedToWindow()) view.getLocationOnScreen(location);
                else { location[0] = displayedLocation[0]; location[1] = displayedLocation[1]; }
                Rect translated = new Rect(target);
                translated.offset(location[0], location[1]);
                screenTarget.set(translated);
            });
            Bitmap nativePixels = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
            try {
                Rect clicked = screenTarget.get();
                if ("true".equals(InstrumentationRegistry.getArguments().getString("tap_fixture_capture"))) {
                    java.io.File directory = new java.io.File(
                            InstrumentationRegistry.getInstrumentation().getTargetContext()
                                    .getExternalFilesDir(null), "censor-tap-fixtures-20261011");
                    assertTrue(directory.isDirectory() || directory.mkdirs());
                    try (java.io.FileOutputStream output = new java.io.FileOutputStream(
                            new java.io.File(directory, "native-mask-" + SystemClock.elapsedRealtimeNanos() + ".png"))) {
                        assertTrue(nativePixels.compress(Bitmap.CompressFormat.PNG, 100, output));
                    } catch (java.io.IOException failed) { throw new AssertionError(failed); }
                }
                if (expectCensorPixel && reference.get().getVisibility() == View.VISIBLE) {
                    assertEquals("The fixture must prove the target is actually censored", Color.BLACK,
                            nativePixels.getPixel(clicked.centerX(), clicked.centerY()));
                } else if (!expectCensorPixel) {
                    int background = nativePixels.getPixel(clicked.centerX(), clicked.centerY());
                    // Native composition can quantize this white fixture by one channel level.
                    assertTrue("Offscreen/detached coverage must not be displayed: pixel="
                                    + Integer.toHexString(background) + " target=" + clicked
                                    + " hit=" + CensorTapTracker.shared().matchesClick(clicked.left, clicked.top,
                                    clicked.right, clicked.bottom, nativePixels.getWidth(), nativePixels.getHeight(),
                                    SystemClock.uptimeMillis()),
                            Color.alpha(background) == 255 && Color.red(background) >= 254
                                    && Color.green(background) >= 254 && Color.blue(background) >= 254);
                }
                assertEquals("Displayed censor attribution differs from its eligibility", expected,
                        CensorTapTracker.shared().matchesClick(clicked.left, clicked.top,
                                clicked.right, clicked.bottom, nativePixels.getWidth(), nativePixels.getHeight(),
                                SystemClock.uptimeMillis()));
                if (target.top == 110) assertFalse("Motion must not retain the previous hit region",
                        CensorTapTracker.shared().matchesClick(clicked.left, clicked.top + 80,
                                clicked.right, clicked.bottom + 80, nativePixels.getWidth(),
                                nativePixels.getHeight(), SystemClock.uptimeMillis()));
            } finally { nativePixels.recycle(); }
        } finally { CensorTapTracker.shared().clear(); }
    }

    private static void awaitCommittedFrame(ActivityScenario<CensorTapFixtureActivity> scenario) {
        java.util.concurrent.CountDownLatch committed = new java.util.concurrent.CountDownLatch(1);
        scenario.onActivity(activity -> {
            View content = activity.findViewById(android.R.id.content);
            assertTrue("Native pixel fixtures require hardware rendering", content.isHardwareAccelerated());
            content.getViewTreeObserver().registerFrameCommitCallback(committed::countDown);
            content.invalidate();
        });
        try { assertTrue("A new hardware frame was not committed",
                committed.await(5, java.util.concurrent.TimeUnit.SECONDS)); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new AssertionError(interrupted);
        }
        // Commit is an actual hardware-frame fence, not a claim of compositor/pixel-clock identity.
    }

    private static List<TrackedObject> tracks(BBox box) {
        return new ObjectTracker(DetectorConfig.builder().build()).update(
                List.of(region(box, "face", null)));
    }

    private static Detection region(BBox box, String category, String anchor) {
        return new Detection("TEST", category, 1f, box, true, false,
                Detection.ObservationSource.VISUAL, Detection.GeometryQuality.MODEL, anchor);
    }

    private interface Setup { void accept(CensorOverlayView view); }
}
