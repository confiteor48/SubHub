package com.subhub.app.detection;

import static org.junit.Assert.*;

import org.junit.Test;

public final class ResourceCleanupFailureTest {
    @Test public void suppressedNativeCleanupFaultCannotHideBehindDecodeFailure() {
        RuntimeException decode = new IllegalArgumentException("decode fixture");
        decode.addSuppressed(new DetectionEngine.ResourceCleanupFailure(new IllegalStateException("close fixture")));
        assertTrue(DetectionEngine.resourceCleanupFailed(decode));
        assertTrue(DetectionEngine.resourceCleanupFailed(new RuntimeException(decode)));
    }

    @Test public void ordinaryAndCyclicFailureChainsDoNotInventNativeCleanupFaults() {
        RuntimeException first = new RuntimeException("first"), second = new RuntimeException("second");
        first.initCause(second); second.initCause(first);
        assertFalse(DetectionEngine.resourceCleanupFailed(first));
        assertFalse(DetectionEngine.resourceCleanupFailed(new IllegalStateException("inference fixture")));
        assertFalse(DetectionEngine.resourceCleanupFailed(null));
    }

    @Test public void UnsupportedFailureDepthCannotClaimCleanResources() {
        RuntimeException failure = new RuntimeException("root");
        for (int depth = 0; depth < 33; depth++) failure = new RuntimeException(failure);
        assertTrue(DetectionEngine.resourceCleanupFailed(failure));
    }
}
