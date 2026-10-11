package com.subhub.app.detection;

import static org.junit.Assert.*;

import java.nio.FloatBuffer;
import org.junit.Test;

public final class TensorInspectionTest {
    @Test public void completeFiniteTensorPreservesBufferPosition() {
        FloatBuffer tensor = FloatBuffer.wrap(new float[48]); tensor.position(4);
        assertTrue(TensorInspection.complete(tensor, 22, 2)); assertEquals(4, tensor.position());
    }
    @Test public void missingAndTruncatedOrOversizedBuffersAreNotSuccessfulNegatives() {
        assertFalse(TensorInspection.complete(null, 22, 2));
        assertFalse(TensorInspection.complete(FloatBuffer.allocate(43), 22, 2));
        assertFalse(TensorInspection.complete(FloatBuffer.allocate(45), 22, 2));
    }
    @Test public void nonfiniteValuesCannotAuthorizeCropInspection() {
        for (float invalid : new float[]{Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            float[] data = new float[44]; data[43] = invalid;
            assertFalse(TensorInspection.complete(FloatBuffer.wrap(data), 22, 2));
        }
    }
    @Test public void unsupportedShapeAndCountsRemainIncomplete() {
        assertFalse(TensorInspection.complete(FloatBuffer.allocate(44), 21, 2));
        assertFalse(TensorInspection.complete(FloatBuffer.allocate(0), 22, 0));
        assertFalse(TensorInspection.complete(FloatBuffer.allocate(44), 22, Long.MAX_VALUE));
        assertFalse(TensorInspection.complete(FloatBuffer.allocate(44), Long.MAX_VALUE, 2));
    }
}
