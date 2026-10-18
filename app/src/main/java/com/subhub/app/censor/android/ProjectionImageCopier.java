package com.subhub.app.censor.android;

import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.DataSpace;
import android.media.Image;
import android.os.Build;

import com.subhub.app.censor.engine.CensorEngine;
import com.subhub.app.censor.engine.PixelGeometry;
import com.subhub.app.censor.engine.ProjectionCaptureAdapter;
import com.subhub.app.censor.engine.ProjectionFrameMailbox;
import com.subhub.app.censor.engine.Rgba8888Plane;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.util.Objects;

/** Synchronous, source-owned opaque sRGB copy; no raw borrow survives this call. Unwired control. */
public final class ProjectionImageCopier implements ProjectionCaptureAdapter.Copier<Image, PixelFrame> {
    private final int maximumFrameBytes;
    private final long maximumWorkingBytes;
    private byte[] scratch;

    /** Bitmap, retained rows and native alpha temporaries; not allocator/reader/engine-retention limits. */
    public ProjectionImageCopier(int maximumFrameBytes, long maximumWorkingBytes) {
        if (maximumFrameBytes <= 0 || maximumWorkingBytes <= 0) throw new IllegalArgumentException("copy budget");
        this.maximumFrameBytes = maximumFrameBytes; this.maximumWorkingBytes = maximumWorkingBytes;
    }

    @Override public CensorEngine.Captured<PixelFrame> copy(ProjectionCaptureAdapter.Target<Image> target,
            ProjectionFrameMailbox<Image>.Read raw) {
        return copy(target.geometry, raw);
    }

    // Same production path for native ImageReader fixtures, without manufacturing an engine request.
    synchronized CensorEngine.Captured<PixelFrame> copy(PixelGeometry geometry, ProjectionFrameMailbox<Image>.Read raw) {
        Objects.requireNonNull(geometry); Objects.requireNonNull(raw);
        if (Build.VERSION.SDK_INT < 33) throw new UnsupportedOperationException("image dataspace requires API33");
        long started = System.nanoTime();
        Image image = raw.raw();
        int width = image.getWidth(), height = image.getHeight();
        if (geometry.left != 0 || geometry.top != 0 || geometry.width != geometry.displayWidth
                || geometry.height != geometry.displayHeight || width != geometry.width || height != geometry.height
                || image.getFormat() != PixelFormat.RGBA_8888 || image.getDataSpace() != DataSpace.DATASPACE_SRGB) {
            throw new IllegalArgumentException("unsupported projection pixels");
        }
        Rect crop = image.getCropRect();
        if (crop == null || crop.left != 0 || crop.top != 0 || crop.right != width || crop.bottom != height) {
            throw new IllegalArgumentException("projection crop");
        }
        Image.Plane[] planes = image.getPlanes();
        if (planes == null || planes.length != 1 || planes[0] == null) throw new IllegalArgumentException("projection planes");
        Image.Plane plane = planes[0];
        int stride = plane.getRowStride(), pixelStride = plane.getPixelStride();
        int sourceBytes = Rgba8888Plane.sourceBytes(width, height, pixelStride, stride, maximumFrameBytes);
        int packedBytes = Rgba8888Plane.destinationBytes(width, height, width * 4, maximumFrameBytes);
        ByteBuffer input = plane.getBuffer();
        if (input == null || !input.isDirect() || input.remaining() < sourceBytes) throw new IllegalArgumentException("projection buffer");
        int origin = input.position();
        working(packedBytes, retainedScratchBytes());
        Bitmap bitmap = null;
        try {
            // Raw bytes are not interpreted as RGB or declared opaque before alpha verification.
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888, true,
                    ColorSpace.get(ColorSpace.Named.SRGB));
            int destinationStride = bitmap.getRowBytes();
            int destinationBytes = Rgba8888Plane.destinationBytes(width, height, destinationStride, maximumFrameBytes);
            int allocation = bitmap.getAllocationByteCount();
            if (bitmap.getByteCount() != destinationBytes || allocation < destinationBytes || allocation > maximumFrameBytes
                    || bitmap.getConfig() != Bitmap.Config.ARGB_8888 || !bitmap.isMutable()
                    || !ColorSpace.get(ColorSpace.Named.SRGB).equals(bitmap.getColorSpace())) {
                throw new IllegalArgumentException("projection bitmap layout");
            }
            working(allocation, retainedScratchBytes());
            ByteBuffer copied;
            if (stride == width * 4 && destinationStride == stride) {
                copied = input.duplicate();
                ((Buffer) copied).limit(origin + destinationBytes);
            } else {
                if (scratch != null && scratch.length < destinationBytes) throw new IllegalArgumentException("copy generation grew");
                if (scratch == null) {
                    working(allocation, destinationBytes);
                    scratch = new byte[destinationBytes];
                }
                copied = ByteBuffer.wrap(scratch, 0, destinationBytes);
                Rgba8888Plane.copy(input, copied, width, height, pixelStride, stride, destinationStride, maximumFrameBytes);
            }
            bitmap.copyPixelsFromBuffer(copied);
            requireOpaque(bitmap);
            bitmap.setHasAlpha(false); // Every actual alpha byte is now proven 255.
            PixelFrame frame = PixelFrame.projection(bitmap, geometry, raw.frame.acquisitionSequence,
                    raw.frame.reportedTimestampNanos, raw.frame.arrivalUptimeMillis, System.nanoTime() - started);
            Bitmap owned = bitmap;
            CensorEngine.Captured<PixelFrame> captured = new CensorEngine.Captured<>(frame,
                    geometry.displayWidth, geometry.displayHeight, -1, owned::recycle);
            bitmap = null; // Captured now owns the exclusive output; the adapter still owns raw.
            return captured;
        } catch (RuntimeException | Error failure) {
            if (bitmap != null) {
                try { bitmap.recycle(); }
                catch (RuntimeException | Error cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                    throw new ProjectionCaptureAdapter.ResourceFailure(failure);
                }
            }
            throw failure;
        }
    }

    private long retainedScratchBytes() {
        return scratch == null ? 0L : scratch.length;
    }

    private void requireOpaque(Bitmap bitmap) {
        Bitmap alpha = null, opaque = null;
        Throwable failure = null;
        try {
            int allocation = bitmap.getAllocationByteCount();
            long minimumAlpha = (long) bitmap.getWidth() * bitmap.getHeight();
            working(allocation, retainedScratchBytes() + minimumAlpha);
            alpha = bitmap.extractAlpha();
            int alphaBytes = alphaBytes(alpha, bitmap);
            working(allocation, retainedScratchBytes() + alphaBytes + minimumAlpha);
            opaque = Bitmap.createBitmap(bitmap.getWidth(), bitmap.getHeight(), Bitmap.Config.ALPHA_8);
            int opaqueBytes = alphaBytes(opaque, bitmap);
            working(allocation, retainedScratchBytes() + alphaBytes + (long) opaqueBytes);
            opaque.eraseColor(Color.WHITE);
            if (!alpha.sameAs(opaque)) throw new IllegalArgumentException("projection alpha");
        } catch (RuntimeException | Error cause) { failure = cause; throw cause; }
        finally {
            Throwable cleanup = recycle(opaque, null);
            cleanup = recycle(alpha, cleanup);
            if (cleanup != null) {
                if (failure != null && failure != cleanup) failure.addSuppressed(cleanup);
                throw new ProjectionCaptureAdapter.ResourceFailure(failure == null ? cleanup : failure);
            }
        }
    }

    private int alphaBytes(Bitmap alpha, Bitmap source) {
        if (alpha == null || alpha.isRecycled() || alpha.getConfig() != Bitmap.Config.ALPHA_8
                || alpha.getWidth() != source.getWidth() || alpha.getHeight() != source.getHeight()
                || alpha.getByteCount() <= 0 || alpha.getAllocationByteCount() < alpha.getByteCount()
                || alpha.getAllocationByteCount() > maximumFrameBytes) throw new IllegalArgumentException("alpha bitmap layout");
        return alpha.getAllocationByteCount();
    }

    private static Throwable recycle(Bitmap bitmap, Throwable previous) {
        if (bitmap == null) return previous;
        try { bitmap.recycle(); }
        catch (RuntimeException | Error failure) {
            if (previous == null) return failure;
            if (previous != failure) previous.addSuppressed(failure);
        }
        return previous;
    }

    private void working(int bitmapBytes, long scratchBytes) {
        if ((long) bitmapBytes + scratchBytes > maximumWorkingBytes) throw new IllegalArgumentException("copy working budget");
    }
}
