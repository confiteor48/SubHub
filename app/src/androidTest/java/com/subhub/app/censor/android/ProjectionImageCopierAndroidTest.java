package com.subhub.app.censor.android;

import static org.junit.Assert.*;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.hardware.DataSpace;
import android.hardware.HardwareBuffer;
import android.media.Image;
import android.media.ImageReader;
import android.media.ImageWriter;
import android.os.SystemClock;

import androidx.test.filters.SdkSuppress;

import com.subhub.app.censor.engine.CensorEngine;
import com.subhub.app.censor.engine.PixelGeometry;
import com.subhub.app.censor.engine.ProjectionFrameMailbox;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.Test;

/** Real Bitmap/ImageReader controls, not MediaProjection consent, display-origin or performance proof. */
@SdkSuppress(minSdkVersion = 33)
public class ProjectionImageCopierAndroidTest {
    @Test public void nativeAlphaTemporariesAreIncludedInTheAcceptedWorkingBudget() {
        withImage(64, false, read -> {
            int packed = 64 * 2 * 4;
            assertThrows(IllegalArgumentException.class,
                    () -> new ProjectionImageCopier(65536, packed).copy(geometry(64), read));
            CensorEngine.Captured<PixelFrame> copied = new ProjectionImageCopier(65536, packed * 3L).copy(geometry(64), read);
            try { assertEquals(Color.RED, copied.pixels.bitmap.getPixel(0, 0)); }
            finally { copied.close(); }
        });
    }

    @Test public void everyReceivedAlphaPixelIncludingLastRowMustBeOpaque() {
        for (int pixel = 0; pixel < 6; pixel++) {
            int selected = pixel;
            withWrittenImage(DataSpace.DATASPACE_SRGB, selected, read -> {
                Image image = read.raw(); Image.Plane plane = image.getPlanes()[0];
                assertEquals(DataSpace.DATASPACE_SRGB, image.getDataSpace());
                int at = plane.getBuffer().position() + (selected / 3) * plane.getRowStride() + (selected % 3) * plane.getPixelStride() + 3;
                assertEquals("actual received alpha", 127, plane.getBuffer().get(at) & 255);
                assertThrows(IllegalArgumentException.class, () -> new ProjectionImageCopier(65536, 131072).copy(geometry(3), read));
            });
        }
    }

    @Test public void nativeRowsPreserveRgbMetadataAndSourceBufferState() {
        for (int width : new int[] {3, 64}) withImage(width, false, read -> {
            Image image = read.raw(); ByteBuffer input = image.getPlanes()[0].getBuffer();
            int position = input.position(), limit = input.limit(); input.mark();
            CensorEngine.Captured<PixelFrame> copied = new ProjectionImageCopier(65536, 131072).copy(geometry(width), read);
            Bitmap bitmap = copied.pixels.bitmap;
            try {
                assertEquals(Color.RED, bitmap.getPixel(0, 0));
                assertEquals(Color.BLUE, bitmap.getPixel(width - 1, 1));
                assertFalse(bitmap.hasAlpha()); assertEquals(Bitmap.Config.ARGB_8888, bitmap.getConfig());
                assertEquals(-1, copied.pixelAtMillis); assertEquals(-1, copied.pixels.reportedUptimeMillis);
                assertEquals(PixelFrame.Kind.PROJECTION_DISPLAY, copied.pixels.kind);
                assertEquals(read.frame.acquisitionSequence, copied.pixels.acquisitionSequence);
                assertEquals(image.getTimestamp(), copied.pixels.reportedTimestampNanos);
                assertEquals(read.frame.arrivalUptimeMillis, copied.pixels.callbackUptimeMillis);
                assertEquals(position, input.position()); assertEquals(limit, input.limit());
                input.reset(); assertEquals(position, input.position()); assertSame(image, read.raw());
            } finally { copied.close(); }
            assertTrue(bitmap.isRecycled());
        });
    }

    @Test public void realCropAndNonNativeGeometryAreRejected() {
        withImage(3, false, read -> {
            ProjectionImageCopier copier = new ProjectionImageCopier(65536, 131072);
            read.raw().setCropRect(new Rect(1, 0, 3, 2));
            assertThrows(IllegalArgumentException.class, () -> copier.copy(geometry(3), read));
            read.raw().setCropRect(new Rect(0, 0, 3, 2));
            assertThrows(IllegalArgumentException.class, () -> copier.copy(PixelGeometry.window(4, 2, 1, 0, 3, 2, 3, 2), read));
            assertThrows(IllegalArgumentException.class, () -> copier.copy(geometry(4), read));
        });
    }

    @Test public void unknownAndWideGamutDataspacesAreRejectedRatherThanRelabeled() {
        ProjectionImageCopier copier = new ProjectionImageCopier(65536, 131072);
        for (int space : new int[] {DataSpace.DATASPACE_UNKNOWN, DataSpace.DATASPACE_DISPLAY_P3}) {
            withWrittenImage(space, read -> {
                assertEquals("actual producer dataspace", space, read.raw().getDataSpace());
                assertThrows(IllegalArgumentException.class, () -> copier.copy(geometry(3), read));
            });
        }
    }

    @Test public void realTransparentReaderPixelsAreRejectedWithoutPremultiplicationGuess() {
        withImage(3, true, read -> assertThrows(IllegalArgumentException.class,
                () -> new ProjectionImageCopier(65536, 131072).copy(geometry(3), read)));
    }

    @Test public void explicitFrameAndWorkingBudgetsRejectOversizeCopies() {
        withImage(3, false, read -> {
            assertThrows(IllegalArgumentException.class, () -> new ProjectionImageCopier(8, 131072).copy(geometry(3), read));
            assertThrows(IllegalArgumentException.class, () -> new ProjectionImageCopier(65536, 1).copy(geometry(3), read));
        });
        assertThrows(IllegalArgumentException.class, () -> new ProjectionImageCopier(0, 10));
        assertThrows(IllegalArgumentException.class, () -> new ProjectionImageCopier(10, 0));
    }

    @Test public void repeatedCopiesHaveIndependentBitmapOwnership() {
        ProjectionImageCopier copier = new ProjectionImageCopier(65536, 131072);
        withImage(3, false, read -> {
            CensorEngine.Captured<PixelFrame> one = copier.copy(geometry(3), read);
            CensorEngine.Captured<PixelFrame> two = null;
            try {
                two = copier.copy(geometry(3), read); assertNotSame(one.pixels.bitmap, two.pixels.bitmap);
                one.close(); assertTrue(one.pixels.bitmap.isRecycled()); assertFalse(two.pixels.bitmap.isRecycled());
                assertEquals(Color.RED, two.pixels.bitmap.getPixel(0, 0));
            } finally { one.close(); if (two != null) two.close(); }
        });
    }

    private static PixelGeometry geometry(int width) { return PixelGeometry.display(width, 2, width, 2); }

    private static void withWrittenImage(int space, Consumer<ProjectionFrameMailbox<Image>.Read> action) {
        withWrittenImage(space, -1, action);
    }

    private static void withWrittenImage(int space, int nonopaquePixel, Consumer<ProjectionFrameMailbox<Image>.Read> action) {
        // SurfaceImage overrides the getter but inherits Image's setter with a different backing field.
        // Set metadata on the actual producer instead, then verify the acquired image before copying.
        ImageReader reader = new ImageReader.Builder(3, 2).setMaxImages(4)
                .setDefaultHardwareBufferFormat(HardwareBuffer.RGBA_8888).setDefaultDataSpace(space)
                .setUsage(HardwareBuffer.USAGE_CPU_READ_OFTEN).build();
        ImageWriter writer = null;
        ProjectionFrameMailbox<Image> mailbox = new ProjectionFrameMailbox<>();
        ProjectionFrameMailbox<Image>.Read read = null;
        Image writable = null, acquired = null;
        try {
            writer = ImageWriter.newInstance(reader.getSurface(), 2);
            writable = writer.dequeueInputImage();
            assertEquals(android.graphics.PixelFormat.RGBA_8888, writable.getFormat());
            assertEquals(1, writable.getPlanes().length);
            Image.Plane plane = writable.getPlanes()[0];
            assertEquals(4, plane.getPixelStride());
            ByteBuffer pixels = plane.getBuffer();
            int origin = pixels.position();
            for (int y = 0; y < 2; y++) {
                for (int x = 0; x < 3; x++) {
                    int pixel = origin + y * plane.getRowStride() + x * plane.getPixelStride();
                    pixels.put(pixel, (byte) 255); pixels.put(pixel + 1, (byte) 0);
                    pixels.put(pixel + 2, (byte) 0);
                    pixels.put(pixel + 3, (byte) (y * 3 + x == nonopaquePixel ? 127 : 255));
                }
            }
            writable.setDataSpace(space);
            assertEquals(space, writable.getDataSpace());
            writer.queueInputImage(writable); writable = null;
            long deadline = SystemClock.uptimeMillis() + 2000;
            while (acquired == null && SystemClock.uptimeMillis() < deadline) {
                acquired = reader.acquireLatestImage(); if (acquired == null) SystemClock.sleep(10);
            }
            assertNotNull("real producer ImageReader frame", acquired);
            assertEquals(android.graphics.PixelFormat.RGBA_8888, acquired.getFormat());
            Image owned = acquired;
            ProjectionFrameMailbox.Frame<Image> frame = mailbox.frame(owned, 1, owned.getTimestamp(),
                    SystemClock.uptimeMillis(), owned::close);
            acquired = null;
            assertEquals(ProjectionFrameMailbox.Offer.ACCEPTED, mailbox.offer(frame));
            read = mailbox.take(); assertNotNull(read); action.accept(read);
        } finally {
            try { if (read != null) read.close(); }
            finally {
                try { mailbox.close(); if (acquired != null) acquired.close(); if (writable != null) writable.close(); }
                finally { try { if (writer != null) writer.close(); } finally { reader.close(); } }
            }
        }
        assertTrue(mailbox.isDrained());
    }

    private static void withImage(int width, boolean transparent, Consumer<ProjectionFrameMailbox<Image>.Read> action) {
        ImageReader reader = new ImageReader.Builder(width, 2).setMaxImages(4)
                .setDefaultHardwareBufferFormat(HardwareBuffer.RGBA_8888).setDefaultDataSpace(DataSpace.DATASPACE_SRGB)
                .setUsage(HardwareBuffer.USAGE_CPU_READ_OFTEN | HardwareBuffer.USAGE_GPU_COLOR_OUTPUT).build();
        ProjectionFrameMailbox<Image> mailbox = new ProjectionFrameMailbox<>();
        ProjectionFrameMailbox<Image>.Read read = null; Image acquired = null; AtomicInteger closes = new AtomicInteger();
        try {
            Canvas canvas = reader.getSurface().lockCanvas(null);
            try {
                canvas.drawColor(transparent ? Color.TRANSPARENT : Color.RED, PorterDuff.Mode.SRC);
                if (!transparent) { Paint paint = new Paint(); paint.setColor(Color.BLUE); canvas.drawRect(width - 1, 1, width, 2, paint); }
            } finally { reader.getSurface().unlockCanvasAndPost(canvas); }
            long deadline = SystemClock.uptimeMillis() + 2000;
            while (acquired == null && SystemClock.uptimeMillis() < deadline) {
                acquired = reader.acquireLatestImage(); if (acquired == null) SystemClock.sleep(10);
            }
            assertNotNull("real ImageReader frame", acquired); Image owned = acquired;
            ProjectionFrameMailbox.Frame<Image> frame = mailbox.frame(owned, 7, owned.getTimestamp(), SystemClock.uptimeMillis(), () -> {
                closes.incrementAndGet(); owned.close();
            });
            acquired = null; assertEquals(ProjectionFrameMailbox.Offer.ACCEPTED, mailbox.offer(frame)); read = mailbox.take();
            assertNotNull(read); action.accept(read); assertEquals(0, closes.get());
        } finally {
            try { if (read != null) read.close(); }
            finally { try { mailbox.close(); if (acquired != null) acquired.close(); } finally { reader.close(); } }
        }
        assertTrue(mailbox.isDrained()); assertEquals(1, closes.get());
    }
}
