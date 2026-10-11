package com.subhub.app.detection;

import java.nio.FloatBuffer;

/** Only a complete finite model tensor can authorize successful crop-negative evidence. */
final class TensorInspection {
    private TensorInspection() { }

    static boolean complete(FloatBuffer values, long features, long candidates) {
        long count = features * candidates;
        if (values == null || features != DetectionPostProcessor.OUTPUT_FEATURES || candidates <= 0
                || candidates > Integer.MAX_VALUE || count > Integer.MAX_VALUE || values.remaining() != count) return false;
        int start = values.position();
        for (int i = 0; i < (int) count; i++) if (!Float.isFinite(values.get(start + i))) return false;
        return true;
    }
}
