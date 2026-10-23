package com.subhub.app.censor.android;

import static org.junit.Assert.*;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.PixelCopy;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;

import com.subhub.app.censor.engine.CensorEngine;
import com.subhub.app.censor.engine.PixelGeometry;
import com.subhub.app.censor.engine.NativeTextResult;
import com.subhub.app.censor.engine.ProtectionScope;
import com.subhub.app.censor.engine.SceneEngine;
import com.subhub.app.settings.CensorAppearance;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.AbstractList;
import java.util.Iterator;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion = 30)
public class SnapshotCensorPainterAndroidTest {
    private static SceneEngine.Region region(double x, double y, double right, double bottom) { return new SceneEngine.Region(x, y, right, bottom); }
    private static CensorAppearance appearance(CensorAppearance.Type type, boolean reverse) {
        return new CensorAppearance(type, 70, 0, false, false, CensorAppearance.BorderEffect.CLASSIC,
                false, Color.WHITE, Collections.singletonList("BLOCKED"), reverse, 100, "rectangle", "SubHub", "Access blocked.");
    }
    private static PreparedCensorPaint prepare(Harness h, CensorAppearance appearance, long generation, List<Bitmap> images, int budget) {
        try (CensorEngine<PixelFrame>.RenderView view = h.engine.acquireRenderView()) {
            return PreparedCensorPaint.prepare(view, appearance, generation, images, budget);
        }
    }
    private static SnapshotCensorPainter.Result paint(Harness h, Bitmap target, SnapshotCensorPainter painter,
            PreparedCensorPaint prepared, CensorAppearance appearance, long generation) {
        try (CensorEngine<PixelFrame>.RenderView view = h.engine.acquireRenderView()) {
            return painter.drawRectangularClip(new Canvas(target), view, prepared, appearance, generation, 120);
        }
    }
    private static Bitmap output() { return Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888); }

    @Test public void estimatedTranslationClipsFullSourceWithoutStretchOrTapAuthority() throws Exception {
        try (Harness h = new Harness(); SnapshotCensorPainter painter = new SnapshotCensorPainter(1)) {
            h.engine.start(42);
            h.image(0, Color.RED, PixelGeometry.display(320, 320, 320, 320), true, region(20, 40, 220, 240));
            CensorAppearance appearance = appearance(CensorAppearance.Type.PIXELATE, false);
            try (PreparedCensorPaint prepared = prepare(h, appearance, 1, Collections.emptyList(), PreparedCensorPaint.DEFAULT_MAXIMUM_BYTES)) {
                Bitmap target = output();
                try (CensorEngine<PixelFrame>.RenderView view = h.engine.acquireRenderView()) {
                    SnapshotCensorPainter.Result result = painter.draw(new Canvas(target), view, prepared, appearance, 1, 120, true,
                            (mask, at) -> new SnapshotCensorPainter.Pose(mask.displayRegion.translated(0, -80), region(0, 80, 320, 300)));
                    export(target, "estimated-scroll-clipping");
                    assertEquals(1, result.members.size()); assertTrue(result.members.get(0).tapInteriors.isEmpty());
                    assertEquals(Color.TRANSPARENT, target.getPixel(100, 60));
                    assertEquals(Color.BLUE, target.getPixel(100, 100));
                    assertEquals(Color.TRANSPARENT, target.getPixel(100, 180));
                    assertEquals(40, view.snapshot.groups.get(0).members.get(0).displayRegion.top, 0);
                } finally { target.recycle(); }
            }
        }
    }

    @Test public void reverseNeverRevealsEstimatedTranslatedHoles() throws Exception {
        try (Harness h = new Harness(); SnapshotCensorPainter painter = new SnapshotCensorPainter(1)) {
            h.engine.start(42); h.image(0, Color.RED, PixelGeometry.display(320, 320, 320, 320), region(40, 40, 140, 140));
            Bitmap target = output(); target.eraseColor(Color.YELLOW); AtomicInteger poseCalls = new AtomicInteger();
            try (CensorEngine<PixelFrame>.RenderView view = h.engine.acquireRenderView()) {
                painter.draw(new Canvas(target), view, null, appearance(CensorAppearance.Type.BOX, true), 1, 120, true,
                        (mask, at) -> { poseCalls.incrementAndGet(); return new SnapshotCensorPainter.Pose(mask.displayRegion.translated(100, 100), region(0, 0, 320, 320)); });
                export(target, "estimated-scroll-reverse-refused");
                assertEquals(0, poseCalls.get()); assertEquals(Color.YELLOW, target.getPixel(90, 90));
                assertNotEquals(Color.YELLOW, target.getPixel(190, 190));
            } finally { target.recycle(); }
        }
    }

    @Test public void expectedFrameRoutesOnlyPoseWhilePaintClocksAndSourceStayActual() throws Exception {
        try (Harness h = new Harness()) {
            h.engine.start(42); h.image(0, Color.RED, PixelGeometry.display(320, 320, 320, 320), region(40, 40, 140, 140));
            java.util.concurrent.atomic.AtomicLong revision = new java.util.concurrent.atomic.AtomicLong(-7);
            java.util.concurrent.atomic.AtomicLong sampled = new java.util.concurrent.atomic.AtomicLong(-1);
            SnapshotRenderSession session = new SnapshotRenderSession(new SnapshotRenderSession.Owner() {
                @Override public CensorEngine.ActiveScope activeScope() { return h.engine.activeScope(); }
                @Override public boolean isCurrent(CensorEngine.ActiveScope scope) { return h.engine.isCurrent(scope); }
                @Override public CensorEngine<PixelFrame>.RenderView acquireRenderView() { return h.engine.acquireRenderView(); }
                @Override public CensorEngine.PaintReceipt acknowledgePaint(CensorEngine.ActiveScope scope, long r,
                        long g, List<Long> members, boolean reverse) { return h.engine.acknowledgePaint(scope, r, g, members, reverse); }
                @Override public long presentationRevision() { return revision.get(); }
                @Override public SnapshotCensorPainter.Pose presentation(SceneEngine.Mask mask, long now) {
                    sampled.set(now);
                    return new SnapshotCensorPainter.Pose(mask.displayRegion.translated(0, 40), region(0, 0, 320, 320));
                }
            }, (policy, r, obsolete) -> Collections.emptyList(), 1, action -> { action.run(); return true; }, () -> { });
            Bitmap target = output();
            try {
                for (int mode = 0; mode < 6; mode++) {
                    boolean live = mode != 4, reverse = mode == 5;
                    session.configure(appearance(CensorAppearance.Type.BOX, reverse),
                            SnapshotPaintPolicy.from(com.subhub.app.detection.DetectionPreset.LOW), 0);
                    long began = SystemClock.uptimeMillis(), expected = began + 1000;
                    PresentationFrame frame = new PresentationFrame(mode == 3 ? expected : began,
                            mode == 2 ? began - 1 : expected - 100, expected, mode == 1 ? 8 : -7);
                    sampled.set(-1);
                    try (SnapshotRenderSession.Drawing drawing = session.draw(new Canvas(target), 0, 0,
                            new android.graphics.Rect(0, 0, 320, 320), live, true, true, h.engine.activeScope(), frame)) {
                        assertNotNull(drawing);
                        long ended = SystemClock.uptimeMillis();
                        assertTrue(drawing.paint.recordedAtMillis >= began && drawing.paint.recordedAtMillis <= ended);
                        assertTrue("Fixture's future target is distinct from the actual recording clock", ended < expected - 100);
                        assertEquals(live && !reverse ? (mode == 0 ? expected : drawing.paint.recordedAtMillis) : -1, sampled.get());
                        assertTrue(session.isCurrent(drawing.paint));
                        try (CensorEngine<PixelFrame>.RenderView view = h.engine.acquireRenderView()) {
                            assertEquals(-1, view.snapshot.destination.pixelAtMillis);
                            assertEquals(0, h.now);
                            assertEquals(40, view.snapshot.groups.get(0).members.get(0).displayRegion.top, 0);
                        }
                    }
                    assertEquals(0, session.activeDrawingCount());
                }
            } finally { target.recycle(); session.close(); session.finishDrawing(); }
            long finishBy = SystemClock.uptimeMillis() + 5000;
            while (!session.preparationTerminated() && SystemClock.uptimeMillis() < finishBy) Thread.sleep(5);
            assertTrue(session.preparationTerminated());
            assertTrue(session.managedResourcesDrained());
        }
    }

    @Test public void changedPresentationCannotMintOrReuseACurrentPaint() {
        try (Harness h = new Harness()) {
            h.engine.start(42); h.image(0, Color.RED, PixelGeometry.display(320, 320, 320, 320), region(40, 40, 140, 140));
            java.util.concurrent.atomic.AtomicLong revision = new java.util.concurrent.atomic.AtomicLong();
            java.util.concurrent.atomic.AtomicBoolean changeDuringDraw = new java.util.concurrent.atomic.AtomicBoolean(true);
            SnapshotRenderSession session = new SnapshotRenderSession(new SnapshotRenderSession.Owner() {
                @Override public CensorEngine.ActiveScope activeScope() { return h.engine.activeScope(); }
                @Override public boolean isCurrent(CensorEngine.ActiveScope scope) { return h.engine.isCurrent(scope); }
                @Override public CensorEngine<PixelFrame>.RenderView acquireRenderView() { return h.engine.acquireRenderView(); }
                @Override public CensorEngine.PaintReceipt acknowledgePaint(CensorEngine.ActiveScope scope, long r,
                        long g, List<Long> members, boolean reverse) { return h.engine.acknowledgePaint(scope, r, g, members, reverse); }
                @Override public long presentationRevision() { return revision.get(); }
                @Override public SnapshotCensorPainter.Pose presentation(SceneEngine.Mask mask, long now) {
                    if (changeDuringDraw.getAndSet(false)) revision.incrementAndGet();
                    return new SnapshotCensorPainter.Pose(mask.displayRegion.translated(0, 40), region(0, 0, 320, 320));
                }
            }, (policy, r, obsolete) -> Collections.emptyList(), 1, action -> { action.run(); return true; }, () -> { });
            Bitmap target = output();
            try {
                session.configure(appearance(CensorAppearance.Type.BOX, false),
                        SnapshotPaintPolicy.from(com.subhub.app.detection.DetectionPreset.LOW), 0);
                long callback = SystemClock.uptimeMillis();
                PresentationFrame frame = new PresentationFrame(callback, callback + 900, callback + 1000, revision.get());
                assertNull(session.draw(new Canvas(target), 0, 0, new android.graphics.Rect(0, 0, 320, 320), true, true, true, h.engine.activeScope(), frame));
                try (SnapshotRenderSession.Drawing drawing = session.draw(new Canvas(target), 0, 0,
                        new android.graphics.Rect(0, 0, 320, 320), true, true, true, h.engine.activeScope())) {
                    assertNotNull(drawing); assertTrue(session.isCurrent(drawing.paint)); revision.incrementAndGet();
                    assertFalse(session.isCurrent(drawing.paint)); assertNull(session.acknowledge(drawing.paint));
                }
            } finally { target.recycle(); session.close(); session.finishDrawing(); }
            assertTrue(session.managedResourcesDrained());
        }
    }

    @Test public void movedMembersUseTheirOwnEvidenceAndBlankGroupGapsRemainUnpainted() {
        try (Harness h = new Harness(); SnapshotCensorPainter painter = new SnapshotCensorPainter(1)) {
            h.engine.start(42, ProtectionScope.normal(7));
            h.image(0, Color.RED, PixelGeometry.display(320, 320, 320, 320), region(20, 180, 100, 260));
            try (CensorEngine<PixelFrame>.RenderView originalEvidence = h.engine.acquireRenderView()) {
                assertNotNull(originalEvidence.sourcePixels(1));
                h.image(334, Color.BLUE, PixelGeometry.display(320, 320, 320, 320), region(180, 180, 280, 280));
                h.map(region(20, 180, 100, 260), 0, -160);
            }
            CensorAppearance appearance = appearance(CensorAppearance.Type.PIXELATE, false);
            try (PreparedCensorPaint prepared = prepare(h, appearance, 1, Collections.emptyList(), PreparedCensorPaint.DEFAULT_MAXIMUM_BYTES)) {
                Bitmap target = output();
                try {
                    SnapshotCensorPainter.Result result = paint(h, target, painter, prepared, appearance, 1);
                    assertEquals(Color.RED, target.getPixel(50, 50)); assertEquals(Color.BLUE, target.getPixel(220, 220));
                    assertEquals(Color.TRANSPARENT, target.getPixel(140, 140)); assertEquals(2, result.members.size());
                    for (SnapshotCensorPainter.PaintedMember member : result.members) assertEquals(ProtectionScope.normal(7), member.protectionScope);
                } finally { target.recycle(); }
            }
        }
    }

    @Test public void partialWindowPixelsDoNotStretchIntoUnsupportedMaskCoverage() {
        try (Harness h = new Harness(); SnapshotCensorPainter painter = new SnapshotCensorPainter(1)) {
            h.engine.start(42); h.image(0, Color.RED, PixelGeometry.window(320, 320, 50, 100, 100, 100, 100, 100), region(0, 50, 200, 250));
            CensorAppearance appearance = appearance(CensorAppearance.Type.PIXELATE, false);
            try (PreparedCensorPaint prepared = prepare(h, appearance, 1, Collections.emptyList(), PreparedCensorPaint.DEFAULT_MAXIMUM_BYTES)) {
                Bitmap target = output();
                try {
                    paint(h, target, painter, prepared, appearance, 1);
                    assertEquals(Color.RED, target.getPixel(100, 150));
                    assertEquals(appearance.getEffectPalette().first() | 0xff000000, target.getPixel(20, 70));
                    assertEquals(appearance.getEffectPalette().first() | 0xff000000, target.getPixel(180, 230));
                } finally { target.recycle(); }
            }
        }
    }

    @Test public void everyEffectHasSourceIndependentCoverageAndCustomArtworkIsCopied() throws Exception {
        for (CensorAppearance.Type type : CensorAppearance.Type.values()) {
            try (Harness h = new Harness(); SnapshotCensorPainter painter = new SnapshotCensorPainter(1)) {
                h.engine.start(42); h.image(0, Color.RED, PixelGeometry.display(320, 320, 320, 320), region(20, 20, 300, 200));
                Bitmap custom = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888); custom.eraseColor(Color.GREEN);
                CensorAppearance appearance = appearance(type, false);
                try (PreparedCensorPaint prepared = prepare(h, appearance, 1, Collections.singletonList(custom), PreparedCensorPaint.DEFAULT_MAXIMUM_BYTES)) {
                    custom.recycle(); Bitmap target = output();
                    try {
                        SnapshotCensorPainter.Result result = paint(h, target, painter, prepared, appearance, 1);
                        assertEquals(1, result.members.size()); assertEquals(255, Color.alpha(target.getPixel(160, 110)));
                        if (type == CensorAppearance.Type.CUSTOM) assertEquals(Color.GREEN, target.getPixel(160, 110));
                        export(target, type.name());
                        // A missing/mismatched preparation cannot reuse last generation pixels.
                        target.eraseColor(Color.TRANSPARENT); paint(h, target, painter, prepared, appearance, 2);
                        assertEquals(255, Color.alpha(target.getPixel(160, 110)));
                        if (type == CensorAppearance.Type.PIXELATE || type == CensorAppearance.Type.BLUR) {
                            assertEquals("Wrong-generation source cannot be painted", appearance.getEffectPalette().first() | 0xff000000,
                                    target.getPixel(160, 110));
                        }
                        if (type == CensorAppearance.Type.CUSTOM) assertNotEquals(Color.GREEN, target.getPixel(160, 110));
                    } finally { target.recycle(); }
                }
            }
        }
    }

    @Test public void reverseRevealsUnderlyingPixelsNotOldSourcePixelsAndNeverAttributesHoles() throws Exception {
        for (CensorAppearance.Type type : CensorAppearance.Type.values()) {
            try (Harness h = new Harness(); SnapshotCensorPainter painter = new SnapshotCensorPainter(1)) {
                h.engine.canvasSamplingControls(true); h.engine.start(42);
                h.image(0, Color.BLUE, PixelGeometry.display(320, 320, 320, 320), region(40, 40, 140, 140));
                CensorAppearance appearance = appearance(type, true);
                try (PreparedCensorPaint prepared = prepare(h, appearance, 1, Collections.emptyList(), PreparedCensorPaint.DEFAULT_MAXIMUM_BYTES)) {
                    Bitmap target = output(); target.eraseColor(Color.YELLOW);
                    try {
                        SnapshotCensorPainter.Result result = paint(h, target, painter, prepared, appearance, 1);
                        assertTrue(result.reverseBackground); assertTrue(result.members.isEmpty());
                        assertEquals(Color.YELLOW, target.getPixel(90, 90)); assertEquals(255, Color.alpha(target.getPixel(200, 200)));
                        assertNotEquals(Color.YELLOW, target.getPixel(200, 200));
                        export(target, "reverse-" + type.name());
                    } finally { target.recycle(); }
                }
            }
        }
    }

    @Test public void exhaustedPreparationBudgetUsesOpaqueGlitchTextureAndNeverMutatesInput() {
        try (Harness h = new Harness(); SnapshotCensorPainter painter = new SnapshotCensorPainter(1)) {
            h.engine.start(42); Bitmap original = h.image(0, Color.RED, PixelGeometry.display(320, 320, 320, 320), region(20, 20, 300, 300));
            CensorAppearance appearance = appearance(CensorAppearance.Type.GLITCH, false);
            try (PreparedCensorPaint prepared = prepare(h, appearance, 1, Collections.emptyList(), 16384)) {
                assertEquals("Noise input and bitmap cannot coexist within the minimum budget", 0, prepared.allocationBytes()); Bitmap target = output();
                try {
                    paint(h, target, painter, prepared, appearance, 1); assertEquals(Color.RED, original.getPixel(100, 100));
                    assertEquals(255, Color.alpha(target.getPixel(100, 100))); assertNotEquals(target.getPixel(100, 100), target.getPixel(110, 120));
                } finally { target.recycle(); }
            }
        }
    }

    @Test public void nativeTextGroupsUseOnlyMembersKeepBlankRowsAndCapPaddingWithFittedLabels() throws Exception {
        NativeTextResult.Candidate candidate = new NativeTextResult.Candidate("TEXT_SMUT",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", .9f, true,
                Arrays.asList(region(20, 40, 160, 65), region(20, 120, 160, 145)));
        try (Harness h = new Harness(candidate); SnapshotCensorPainter painter = new SnapshotCensorPainter(1)) {
            h.engine.executionControls(334, false, java.util.EnumSet.noneOf(SceneEngine.Lane.class));
            h.engine.start(42, ProtectionScope.lab(7)); h.fire(0); h.worker.all(); h.ui.all();
            CensorAppearance appearance = new CensorAppearance(CensorAppearance.Type.BOX, 70, .8f,
                    false, false, CensorAppearance.BorderEffect.CLASSIC, true, Color.WHITE,
                    Arrays.asList("A VERY LONG PHRASE THAT MUST NOT OVERFLOW", "BLOCKED"), false, 100, "rectangle", "SubHub", "Access blocked.");
            try (PreparedCensorPaint prepared = prepare(h, appearance, 1, Collections.emptyList(), PreparedCensorPaint.DEFAULT_MAXIMUM_BYTES)) {
                Bitmap target = output();
                try {
                    SnapshotCensorPainter.Result result = paint(h, target, painter, prepared, appearance, 1);
                    assertEquals(2, result.members.size()); assertEquals(Color.TRANSPARENT, target.getPixel(90, 90));
                    assertEquals(Color.TRANSPARENT, target.getPixel(15, 50));
                    for (SnapshotCensorPainter.PaintedMember member : result.members) {
                        assertEquals(ProtectionScope.lab(7), member.protectionScope);
                        for (SceneEngine.Region interior : member.tapInteriors) {
                            assertTrue(interior.left >= 16.5 && interior.right <= 163.5);
                            assertTrue(interior.bottom < 90 || interior.top > 90);
                        }
                    }
                    export(target, "native-text-labels");
                } finally { target.recycle(); }
            }
        }
    }

    @Test public void actualAttachedHardwareReplaysCopiedMembersAfterSourceAndManagedPreparationClose() throws Exception {
        try (Harness h = new Harness(); ActivityScenario<SnapshotPaintFixtureActivity> scenario = ActivityScenario.launch(SnapshotPaintFixtureActivity.class)) {
            h.engine.start(42); Bitmap a = h.image(0, Color.RED, PixelGeometry.display(320, 320, 320, 320), region(20, 180, 100, 260));
            Bitmap b;
            try (CensorEngine<PixelFrame>.RenderView originalEvidence = h.engine.acquireRenderView()) {
                assertNotNull(originalEvidence.sourcePixels(1));
                b = h.image(334, Color.BLUE, PixelGeometry.display(320, 320, 320, 320), region(180, 180, 280, 280));
                h.map(region(20, 180, 100, 260), 0, -160);
            }
            CensorAppearance appearance = appearance(CensorAppearance.Type.PIXELATE, false);
            PreparedCensorPaint prepared = prepare(h, appearance, 1, Collections.emptyList(), PreparedCensorPaint.DEFAULT_MAXIMUM_BYTES);
            AtomicReference<SnapshotPaintFixtureActivity> activity = new AtomicReference<>();
            scenario.onActivity(value -> { activity.set(value); value.probe.install(h.engine, prepared, appearance, 1); });
            awaitDraw(activity.get()); assertTrue(activity.get().probe.hardware);
            Bitmap first = capture(activity.get());
            try { assertEquals(Color.RED, first.getPixel(50, 50)); assertEquals(Color.BLUE, first.getPixel(220, 220)); export(first, "hardware-before"); }
            finally { first.recycle(); }
            int draws = activity.get().probe.draws;
            // Do not invalidate: a transform replays the already recorded hardware display list.
            h.engine.close(); assertTrue(a.isRecycled()); assertTrue(b.isRecycled());
            int commits = activity.get().probe.commits;
            scenario.onActivity(value -> {
                prepared.close(); value.probe.painter.close();
                value.probe.getViewTreeObserver().registerFrameCommitCallback(() -> value.probe.commits++);
                value.probe.setTranslationX(4);
            });
            long replayDeadline = SystemClock.uptimeMillis() + 5000;
            while (activity.get().probe.commits <= commits && SystemClock.uptimeMillis() < replayDeadline) Thread.sleep(10);
            assertTrue(activity.get().probe.commits > commits);
            Bitmap replay = capture(activity.get());
            try { assertEquals(Color.RED, replay.getPixel(50, 50)); assertEquals(Color.BLUE, replay.getPixel(220, 220)); export(replay, "hardware-replay"); }
            finally { replay.recycle(); }
            assertEquals("This must be cached hardware replay, not an accidental source redraw", draws, activity.get().probe.draws);
        }
    }

    @Test public void smallHighDensityLockpadOmitsUnreadableLabelsAndOrdinaryTextFitsItsBand() {
        try (SnapshotCensorPainter high = new SnapshotCensorPainter(3); SnapshotCensorPainter normal = new SnapshotCensorPainter(1)) {
            CensorAppearance lockpad = appearance(CensorAppearance.Type.ERROR_POPUP, false);
            RectF small = new RectF(0, 0, 150, 64);
            assertNull(high.labelLayout(small, 1, lockpad));
            assertNotNull(normal.labelLayout(small, 1, lockpad));
            for (CensorAppearance.Type type : CensorAppearance.Type.values()) {
                SnapshotCensorPainter.LabelLayout label = high.labelLayout(new RectF(0, 0, 600, 200), 1, appearance(type, false));
                if (label == null) { assertEquals(CensorAppearance.Type.ERROR_POPUP, type); continue; }
                assertTrue(label.size <= 42); assertTrue(label.measuredWidth <= label.maximumWidth);
                assertTrue(label.glyphTop >= label.band.top); assertTrue(label.glyphBottom <= label.band.bottom);
                assertTrue(label.x - label.measuredWidth / 2 >= label.band.left);
                assertTrue(label.x + label.measuredWidth / 2 <= label.band.right);
            }
        }
    }

    @Test public void artworkCardinalityBoundsWorkWithoutTrustingItsIterator() {
        try (Harness h = new Harness(); SnapshotCensorPainter painter = new SnapshotCensorPainter(1)) {
            h.engine.start(42); h.image(0, Color.RED, PixelGeometry.display(320, 320, 320, 320), region(20, 20, 300, 300));
            Bitmap image = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888); image.eraseColor(Color.GREEN);
            AtomicInteger reads = new AtomicInteger();
            List<Bitmap> values = new AbstractList<Bitmap>() {
                @Override public int size() { return 1; }
                @Override public Bitmap get(int index) { assertEquals(0, index); reads.incrementAndGet(); return image; }
                @Override public Iterator<Bitmap> iterator() { throw new AssertionError("Unbounded iterator must not be used"); }
            };
            CensorAppearance appearance = appearance(CensorAppearance.Type.CUSTOM, false);
            try (PreparedCensorPaint prepared = prepare(h, appearance, 1, values, PreparedCensorPaint.DEFAULT_MAXIMUM_BYTES)) {
                assertEquals(1, reads.get()); image.recycle(); Bitmap target = output();
                try { paint(h, target, painter, prepared, appearance, 1); assertEquals(Color.GREEN, target.getPixel(160, 160)); }
                finally { target.recycle(); }
            }
        }
    }

    @Test public void anUnknownShapedClipCannotPublishItsBoundingRectangleAsTapCoverage() {
        try (Harness h = new Harness(); SnapshotCensorPainter painter = new SnapshotCensorPainter(1)) {
            h.engine.start(42); h.image(0, Color.RED, PixelGeometry.display(320, 320, 320, 320), region(20, 20, 300, 300));
            CensorAppearance appearance = appearance(CensorAppearance.Type.BOX, false);
            try (PreparedCensorPaint prepared = prepare(h, appearance, 1, Collections.emptyList(), PreparedCensorPaint.DEFAULT_MAXIMUM_BYTES)) {
                Bitmap target = output();
                try (CensorEngine<PixelFrame>.RenderView view = h.engine.acquireRenderView()) {
                    Canvas canvas = new Canvas(target); Path circle = new Path(); circle.addCircle(160, 160, 100, Path.Direction.CW); canvas.clipPath(circle);
                    SnapshotCensorPainter.Result result = painter.draw(canvas, view, prepared, appearance, 1, 0);
                    assertEquals(1, result.members.size()); assertFalse(result.rectangularTapCoverageQualified);
                    assertTrue(result.members.get(0).tapInteriors.isEmpty()); assertEquals(Color.TRANSPARENT, target.getPixel(61, 61));
                } finally { target.recycle(); }
            }
        }
    }

    private static void awaitDraw(SnapshotPaintFixtureActivity activity) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 5000;
        while ((activity.probe.painted == 0 || activity.probe.commits == 0) && SystemClock.uptimeMillis() < deadline) Thread.sleep(10);
        assertTrue(activity.probe.painted > 0); assertTrue(activity.probe.commits > 0);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }
    private static Bitmap capture(SnapshotPaintFixtureActivity activity) throws Exception {
        Bitmap bitmap = output(); CountDownLatch copied = new CountDownLatch(1); AtomicInteger result = new AtomicInteger(-1);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            int[] xy = new int[2]; activity.probe.getLocationInWindow(xy);
            PixelCopy.request(activity.getWindow(), new Rect(xy[0], xy[1], xy[0] + 320, xy[1] + 320), bitmap,
                    value -> { result.set(value); copied.countDown(); }, new Handler(Looper.getMainLooper()));
        });
        assertTrue(copied.await(5, TimeUnit.SECONDS)); assertEquals(PixelCopy.SUCCESS, result.get()); return bitmap;
    }
    private static void export(Bitmap bitmap, String label) throws Exception {
        if (!"true".equals(InstrumentationRegistry.getArguments().getString("tap_fixture_capture"))) return;
        File directory = new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getExternalFilesDir(null), "censor-tap-fixtures-20261011");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        File file = new File(directory, "native-mask-" + System.nanoTime() + ".png");
        try (FileOutputStream stream = new FileOutputStream(file)) { assertTrue(label, bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)); }
    }
    private static final class Queue implements Executor {
        final List<Runnable> values = new ArrayList<>();
        @Override public void execute(Runnable action) { values.add(action); }
        void all() { while (!values.isEmpty()) values.remove(0).run(); }
    }
    private static final class Timed {
        long at; Runnable action; boolean cancelled, ran;
        Timed(long at, Runnable action) { this.at = at; this.action = action; }
    }
    private static final class Harness implements AutoCloseable {
        long now; int drained;
        final Queue worker = new Queue(), ui = new Queue(); final List<Timed> timers = new ArrayList<>();
        final List<CensorEngine.CaptureCallback<PixelFrame>> callbacks = new ArrayList<>();
        final List<CensorEngine.Input<PixelFrame>> inputs = new ArrayList<>();
        final List<CensorEngine.Completion> completions = new ArrayList<>(); final CensorEngine<PixelFrame> engine;
        Harness() { this(null); }
        Harness(NativeTextResult.Candidate nativeCandidate) {
            engine = new CensorEngine<>(new CensorEngine.Policy(334, 0, 8, 3, false),
                    new SceneEngine.Policy(16, 100, 64, 256, 5000, 20000, .5), () -> now,
                    (action, delay) -> { Timed value = new Timed(now + delay, action); timers.add(value); return () -> value.cancelled = true; },
                    (request, callback) -> { assertTrue(request.tryDispatch()); callbacks.add(callback); },
                    Collections.singletonList(new CensorEngine.LaneWorker<>(SceneEngine.Lane.FAST, worker,
                            (input, completion) -> { inputs.add(input); completions.add(completion); })), ui,
                    new CensorEngine.Observer() { @Override public void drained() { drained++; } },
                    nativeCandidate == null ? null : new CensorEngine.NativeTextLane(2, 120, 1000, worker,
                            (input, completion) -> {
                                long started = input.beginRead(); assertTrue(started >= 0);
                                completion.success(new NativeTextResult(320, 320, 7, started, now,
                                        Collections.singletonList(nativeCandidate), 1, 1, 1));
                            }));
        }
        void fire(long at) {
            now = at;
            for (Timed value : new ArrayList<>(timers)) if (!value.ran && !value.cancelled && value.at <= now) { value.ran = true; value.action.run(); }
        }
        Bitmap image(long at, int color, PixelGeometry geometry, SceneEngine.Region... regions) {
            return image(at, color, geometry, false, regions);
        }
        Bitmap image(long at, int color, PixelGeometry geometry, boolean twoColor, SceneEngine.Region... regions) {
            fire(at);
            Bitmap image = Bitmap.createBitmap(geometry.width, geometry.height, Bitmap.Config.ARGB_8888); image.eraseColor(color);
            if (twoColor) {
                android.graphics.Paint blue = new android.graphics.Paint(); blue.setColor(Color.BLUE);
                new Canvas(image).drawRect(20, 140, 220, 240, blue);
            }
            PixelFrame frame = new PixelFrame(image, geometry, PixelFrame.Kind.ACCESSIBILITY_WINDOW, -1, at, 0);
            callbacks.get(callbacks.size() - 1).success(new CensorEngine.Captured<>(frame, 320, 320, -1, image::recycle)); worker.all();
            List<SceneEngine.Observation> observations = new ArrayList<>();
            for (SceneEngine.Region region : regions) observations.add(new SceneEngine.Observation("FACE", 1, 0, region, .9f));
            int index = inputs.size() - 1; completions.get(index).success(new SceneEngine.Batch(inputs.get(index).source,
                    SceneEngine.Lane.FAST, observations, Collections.emptyList()), new CensorEngine.WorkTiming(1, 2, 1)); ui.all(); return image;
        }
        void map(SceneEngine.Region support, double dx, double dy) {
            assertTrue(engine.motion(new SceneEngine.Motion(engine.currentEpoch(), 1, 2, 1, 1,
                    SceneEngine.MotionAuthority.IMAGE_CORRESPONDENCE, support, dx, dy))); ui.all();
        }
        @Override public void close() {
            engine.close(); for (CensorEngine.CaptureCallback<PixelFrame> callback : callbacks) callback.failure(0);
            for (CensorEngine.Completion completion : completions) completion.cancelled(); worker.all(); ui.all(); assertEquals(1, drained);
        }
    }
}
