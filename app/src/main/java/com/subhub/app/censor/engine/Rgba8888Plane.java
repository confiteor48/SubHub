package com.subhub.app.censor.engine;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.util.Objects;

/** Checked byte-preserving rows, not a color-space/premultiplication conversion or image owner. */
public final class Rgba8888Plane {
    private static final byte[] ZERO = new byte[256];
    private Rgba8888Plane() { }

    /** No access to padding after the last source pixel is required or permitted. */
    public static int sourceBytes(int width, int height, int pixelStride, int rowStride, int maximumBytes) {
        int row = rowBytes(width, height, rowStride, maximumBytes);
        if (pixelStride != 4) throw new IllegalArgumentException("RGBA pixel stride");
        return bounded((long) (height - 1) * rowStride + row, maximumBytes);
    }

    /** Bitmap destinations contain complete rows, including initialized destination padding. */
    public static int destinationBytes(int width, int height, int rowStride, int maximumBytes) {
        rowBytes(width, height, rowStride, maximumBytes);
        return bounded((long) height * rowStride, maximumBytes);
    }

    /**
     * Source must be a direct Image-plane buffer; output must be mutable array-backed heap storage.
     * These disjoint storage types rule out aliased slices without guessing their native addresses.
     * Positions, limits and marks of BOTH supplied buffers are preserved. Validate everything before
     * the first write; byte order is irrelevant. The caller keeps the Image alive for this call.
     */
    public static void copy(ByteBuffer source, ByteBuffer destination, int width, int height,
            int pixelStride, int sourceRowStride, int destinationRowStride, int maximumBytes) {
        Objects.requireNonNull(source); Objects.requireNonNull(destination);
        int sourceBytes = sourceBytes(width, height, pixelStride, sourceRowStride, maximumBytes);
        int destinationBytes = destinationBytes(width, height, destinationRowStride, maximumBytes);
        if (!source.isDirect() || destination.isDirect() || destination.isReadOnly() || !destination.hasArray()
                || source.remaining() < sourceBytes || destination.remaining() < destinationBytes) {
            throw new IllegalArgumentException("RGBA buffer range/storage");
        }
        int row = width * 4, origin = source.position();
        ByteBuffer input = source.duplicate(), output = destination.duplicate();
        ((Buffer) output).limit(destination.position() + destinationBytes);
        for (int y = 0; y < height; y++) {
            int start = (int) (origin + (long) y * sourceRowStride);
            ((Buffer) input).limit(start + row);
            ((Buffer) input).position(start);
            output.put(input);
            int padding = destinationRowStride - row;
            while (padding != 0) {
                int count = Math.min(padding, ZERO.length);
                output.put(ZERO, 0, count); padding -= count;
            }
        }
    }

    private static int rowBytes(int width, int height, int stride, int maximumBytes) {
        if (width <= 0 || height <= 0 || maximumBytes <= 0 || stride <= 0) {
            throw new IllegalArgumentException("RGBA dimensions/budget");
        }
        int row = bounded((long) width * 4, maximumBytes);
        if (stride < row) throw new IllegalArgumentException("RGBA row stride");
        return row;
    }

    private static int bounded(long value, int maximumBytes) {
        if (value <= 0 || value > maximumBytes) throw new IllegalArgumentException("RGBA byte budget");
        return (int) value;
    }
}
