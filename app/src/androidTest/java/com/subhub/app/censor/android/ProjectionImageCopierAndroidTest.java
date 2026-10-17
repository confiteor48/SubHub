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
        withImage(3, false, read -> {
            ProjectionImageCopier copier = new ProjectionImageCopier(65536, 131072);
            for (int space : new int[] {DataSpace.DATASPACE_UNKNOWN, DataSpace.DATASPACE_DISPLAY_P3}) {
                read.raw().setDataSpace(space);
                assertThrows(IllegalArgumentException.class, () -> copier.copy(geometry(3), read));
            }
        });
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
