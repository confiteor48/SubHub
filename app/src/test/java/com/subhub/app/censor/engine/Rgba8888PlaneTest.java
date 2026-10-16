package com.subhub.app.censor.engine;

import static org.junit.Assert.*;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

import org.junit.Test;

public class Rgba8888PlaneTest {
    private static ByteBuffer direct(byte... bytes) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(bytes.length);
        buffer.put(bytes); ((Buffer) buffer).flip(); return buffer;
    }
    private static byte[] bytes(ByteBuffer value) {
        ByteBuffer copy = value.duplicate(); ((Buffer) copy).clear();
        byte[] output = new byte[copy.remaining()]; copy.get(output); return output;
    }
    private static void copy(ByteBuffer source, ByteBuffer destination, int width, int height, int sourceStride, int destinationStride) {
        Rgba8888Plane.copy(source, destination, width, height, 4, sourceStride, destinationStride, 1024);
    }

    @Test public void packedBytesPreserveRgbaOrderAndAlpha() {
        byte[] rgba = {(byte)255, 0, 0, (byte)255, 0, (byte)255, 0, 127, 0, 0, (byte)255, 0};
        ByteBuffer input = direct(rgba), output = ByteBuffer.allocate(12);
        copy(input, output, 3, 1, 12, 12); assertArrayEquals(rgba, bytes(output));
    }

    @Test public void paddedSourceNeedsOnlyLastPixelNotLastRowPadding() {
        ByteBuffer input = direct((byte)1, (byte)2, (byte)3, (byte)4, (byte)99, (byte)99,
                (byte)5, (byte)6, (byte)7, (byte)8);
        ByteBuffer output = ByteBuffer.allocate(8); copy(input, output, 1, 2, 6, 4);
        assertArrayEquals(new byte[]{1,2,3,4,5,6,7,8}, bytes(output));
        assertEquals(10, Rgba8888Plane.sourceBytes(1, 2, 4, 6, 10));
    }

    @Test public void destinationPaddingIsZeroNotSourcePaddingOrOldBytes() {
        ByteBuffer input = direct((byte)1,(byte)2,(byte)3,(byte)4,(byte)88,(byte)88,(byte)5,(byte)6,(byte)7,(byte)8);
        byte[] initial = new byte[12]; Arrays.fill(initial, (byte)77); ByteBuffer output = ByteBuffer.wrap(initial);
        copy(input, output, 1, 2, 6, 6);
        assertArrayEquals(new byte[]{1,2,3,4,0,0,5,6,7,8,0,0}, initial);
    }

    @Test public void nonzeroPositionsLimitsAndMarksArePreserved() {
        ByteBuffer input = direct((byte)90,(byte)1,(byte)2,(byte)3,(byte)4,(byte)99,(byte)99,
                (byte)5,(byte)6,(byte)7,(byte)8,(byte)91);
        ((Buffer) input).position(1); ((Buffer) input).limit(11); input.mark();
        byte[] original = bytes(input); byte[] target = new byte[12]; Arrays.fill(target, (byte)42);
        ByteBuffer output = ByteBuffer.wrap(target); ((Buffer) output).position(2); ((Buffer) output).limit(10); output.mark();
        copy(input, output, 1, 2, 6, 4);
        assertEquals(1, input.position()); assertEquals(11, input.limit()); input.reset(); assertEquals(1, input.position());
        assertEquals(2, output.position()); assertEquals(10, output.limit()); output.reset(); assertEquals(2, output.position());
        assertArrayEquals(original, bytes(input)); assertArrayEquals(new byte[]{42,42,1,2,3,4,5,6,7,8,42,42}, target);
    }

    @Test public void readonlyDirectInputIsSupported() {
        ByteBuffer input = direct((byte)1,(byte)2,(byte)3,(byte)4).asReadOnlyBuffer(), output = ByteBuffer.allocate(4);
        copy(input, output, 1, 1, 4, 4); assertArrayEquals(new byte[]{1,2,3,4}, bytes(output));
    }

    @Test public void sourceByteOrderDoesNotConvertChannels() {
        ByteBuffer input = direct((byte)1,(byte)2,(byte)3,(byte)4).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer output = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN);
        copy(input, output, 1, 1, 4, 4); assertArrayEquals(new byte[]{1,2,3,4}, bytes(output));
    }

    @Test public void exactBudgetBoundaryPassesAndOneByteBelowFailsBeforeWriting() {
        ByteBuffer input = direct((byte)1,(byte)2,(byte)3,(byte)4), output = ByteBuffer.allocate(4);
        Rgba8888Plane.copy(input, output, 1, 1, 4, 4, 4, 4);
        assertArrayEquals(new byte[]{1,2,3,4}, bytes(output)); Arrays.fill(output.array(), (byte)55);
        assertThrows(IllegalArgumentException.class, () -> Rgba8888Plane.copy(input, output, 1, 1, 4, 4, 4, 3));
        assertArrayEquals(new byte[]{55,55,55,55}, bytes(output));
    }

    @Test public void truncatedLastPixelIsRejectedBeforeWriting() {
        ByteBuffer input = ByteBuffer.allocateDirect(10), output = ByteBuffer.allocate(8);
        ((Buffer) input).limit(9); Arrays.fill(output.array(), (byte)55);
        assertThrows(IllegalArgumentException.class, () -> copy(input, output, 1, 2, 6, 4));
        assertArrayEquals(new byte[]{55,55,55,55,55,55,55,55}, bytes(output));
    }

    @Test public void sourceCapacityBeyondLimitIsNotReadableAuthority() {
        ByteBuffer input = ByteBuffer.allocateDirect(40), output = ByteBuffer.allocate(8);
        ((Buffer) input).position(32); ((Buffer) input).limit(39);
        assertThrows(IllegalArgumentException.class, () -> copy(input, output, 1, 2, 4, 4));
        assertArrayEquals(new byte[8], bytes(output));
    }

    @Test public void shortDestinationAndItsLimitAreRejectedBeforeWriting() {
        ByteBuffer input = ByteBuffer.allocateDirect(8), output = ByteBuffer.allocate(9);
        ((Buffer) output).position(2); ((Buffer) output).limit(9); Arrays.fill(output.array(), (byte)41);
        assertThrows(IllegalArgumentException.class, () -> copy(input, output, 1, 2, 4, 4));
        byte[] expected = new byte[9]; Arrays.fill(expected, (byte)41); assertArrayEquals(expected, bytes(output));
    }

    @Test public void zeroAndNegativeDimensionsAreRejected() {
        for (int value : new int[]{0,-1,Integer.MIN_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> Rgba8888Plane.sourceBytes(value, 1, 4, 4, 1024));
            assertThrows(IllegalArgumentException.class, () -> Rgba8888Plane.sourceBytes(1, value, 4, 4, 1024));
        }
    }

    @Test public void unsupportedPixelStridesAreRejected() {
        for (int stride : new int[]{0,1,2,3,5,8,-4}) {
            assertThrows(IllegalArgumentException.class, () -> Rgba8888Plane.sourceBytes(1, 1, stride, 8, 1024));
        }
    }

    @Test public void shortAndNegativeRowStridesAreRejected() {
        for (int stride : new int[]{0,-1,1,3,7}) {
            assertThrows(IllegalArgumentException.class, () -> Rgba8888Plane.sourceBytes(2, 1, 4, stride, 1024));
            assertThrows(IllegalArgumentException.class, () -> Rgba8888Plane.destinationBytes(2, 1, stride, 1024));
        }
    }

    @Test public void widthMultiplicationCannotWrapIntoSmallAllocation() {
        assertThrows(IllegalArgumentException.class,
                () -> Rgba8888Plane.sourceBytes(Integer.MAX_VALUE / 4 + 1, 1, 4, Integer.MAX_VALUE, Integer.MAX_VALUE));
    }

    @Test public void strideTimesHeightCannotWrapIntoSmallRange() {
        assertThrows(IllegalArgumentException.class,
                () -> Rgba8888Plane.sourceBytes(1, Integer.MAX_VALUE, 4, Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertThrows(IllegalArgumentException.class,
                () -> Rgba8888Plane.destinationBytes(1, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE));
    }

    @Test public void sourceBudgetIncludesStrideGapsButNoFinalPadding() {
        assertEquals(10, Rgba8888Plane.sourceBytes(1, 2, 4, 6, 10));
        assertThrows(IllegalArgumentException.class, () -> Rgba8888Plane.sourceBytes(1, 2, 4, 6, 9));
        assertEquals(12, Rgba8888Plane.destinationBytes(1, 2, 6, 12));
        assertThrows(IllegalArgumentException.class, () -> Rgba8888Plane.destinationBytes(1, 2, 6, 11));
    }

    @Test public void zeroAndNegativeBudgetsAreRejected() {
        for (int budget : new int[]{0,-1,Integer.MIN_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> Rgba8888Plane.sourceBytes(1, 1, 4, 4, budget));
        }
    }

    @Test public void heapSourceIsRejectedRatherThanGuessingSliceAliases() {
        ByteBuffer input = ByteBuffer.allocate(4), output = input.duplicate();
        assertThrows(IllegalArgumentException.class, () -> copy(input, output, 1, 1, 4, 4));
    }

    @Test public void directDestinationIsRejectedRatherThanGuessingNativeAliases() {
        ByteBuffer input = ByteBuffer.allocateDirect(4), output = input.duplicate();
        assertThrows(IllegalArgumentException.class, () -> copy(input, output, 1, 1, 4, 4));
    }

    @Test public void readonlyOutputIsRejectedBeforeAnyWrite() {
        ByteBuffer input = ByteBuffer.allocateDirect(4), output = ByteBuffer.allocate(4).asReadOnlyBuffer();
        assertThrows(IllegalArgumentException.class, () -> copy(input, output, 1, 1, 4, 4));
    }

    @Test public void heapDestinationSliceHonorsItsArrayOffset() {
        byte[] target = new byte[12]; Arrays.fill(target, (byte)31);
        ByteBuffer parent = ByteBuffer.wrap(target); ((Buffer) parent).position(3); ((Buffer) parent).limit(7);
        ByteBuffer output = parent.slice(), input = direct((byte)1,(byte)2,(byte)3,(byte)4);
        copy(input, output, 1, 1, 4, 4);
        assertArrayEquals(new byte[]{31,31,31,1,2,3,4,31,31,31,31,31}, target);
    }

    @Test public void wideDestinationPaddingIsFullyZeroed() {
        ByteBuffer input = direct((byte)1,(byte)2,(byte)3,(byte)4), output = ByteBuffer.allocate(600);
        Arrays.fill(output.array(), (byte)51); copy(input, output, 1, 1, 4, 600);
        assertEquals(1, output.get(0)); assertEquals(4, output.get(3));
        for (int index = 4; index < 600; index++) assertEquals(0, output.get(index));
    }

    @Test public void nullBuffersAreRejectedWithoutChangingOutput() {
        ByteBuffer output = ByteBuffer.allocate(4); Arrays.fill(output.array(), (byte)52);
        assertThrows(NullPointerException.class, () -> copy(null, output, 1, 1, 4, 4));
        assertThrows(NullPointerException.class, () -> copy(ByteBuffer.allocateDirect(4), null, 1, 1, 4, 4));
        assertArrayEquals(new byte[]{52,52,52,52}, bytes(output));
    }
}
