package com.subhub.app.detection;

import static org.junit.Assert.*;

import com.subhub.app.censor.engine.VisualInferencePolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class CpuExecutionBudgetTest {
    @Test public void allRetirementsRunAndOriginalFailureSurvivesLaterCleanupFailures() {
        List<String> calls = new ArrayList<>();
        RuntimeException first = new IllegalStateException("hint failure");
        Error second = new AssertionError("native disposal failure");
        RuntimeException actual = assertThrows(RuntimeException.class, () -> CpuExecutionBudget.closeAll(
                () -> { calls.add("hints"); throw first; },
                () -> { calls.add("tensor"); throw second; },
                () -> calls.add("session"), () -> calls.add("input")));
        assertSame(first, actual);
        assertArrayEquals(new Throwable[]{second}, actual.getSuppressed());
        assertEquals(Arrays.asList("hints", "tensor", "session", "input"), calls);
    }

    @Test public void repeatedFailureObjectDoesNotMaskTheOriginalError() {
        Error first = new AssertionError("original");
        Error actual = assertThrows(Error.class, () -> CpuExecutionBudget.closeAll(
                () -> { throw first; }, () -> { throw first; }));
        assertSame(first, actual);
        assertEquals(0, actual.getSuppressed().length);
    }

    @Test public void changedSessionKeysRequireNewContextButPostprocessingDoesNot() {
        CpuExecutionBudget budget = CpuExecutionBudget.fast();
        DetectorConfig current = DetectorConfig.builder().inferenceThreads(3).inferenceResolution(320).build();
        budget.requireSameSession(current, current.toBuilder().confidenceThreshold(.18f).build());
        assertThrows(IllegalArgumentException.class,
                () -> budget.requireSameSession(current, current.toBuilder().inferenceThreads(2).build()));
        assertThrows(IllegalArgumentException.class,
                () -> budget.requireSameSession(current, current.toBuilder().inferenceResolution(480).build()));
        assertThrows(IllegalArgumentException.class,
                () -> budget.requireSameSession(current, current.toBuilder().modelFilename("different.onnx").build()));
    }

    @Test public void explicitPairLimitsDoNotConsumeAllFourComputeActors() {
        CpuExecutionBudget fast = CpuExecutionBudget.fast(), quality = CpuExecutionBudget.quality();
        assertEquals(2, fast.nativeThreads(3, 4));
        assertEquals(1, quality.nativeThreads(3, 4));
        assertEquals(2, fast.inputWorkers);
        assertEquals(0, quality.inputWorkers);
        assertFalse(fast.allowSpinning);
        assertFalse(quality.allowSpinning);
        assertTrue(fast.maximumNativeThreads + quality.maximumNativeThreads < 4);
    }

    @Test public void insufficientCapacityAndWrongLaneFailClosed() {
        for (int cores : new int[]{-1, 0, 1, 2, 3}) {
            assertThrows(IllegalStateException.class, () -> CpuExecutionBudget.fast().validate(true, cores));
            assertThrows(IllegalStateException.class, () -> CpuExecutionBudget.quality().validate(false, cores));
        }
        assertThrows(IllegalArgumentException.class, () -> CpuExecutionBudget.fast().validate(false, 4));
        assertThrows(IllegalArgumentException.class, () -> CpuExecutionBudget.quality().validate(true, 4));
    }

    @Test public void providerNamesNeverGrantAnUnboundedBackend() {
        CpuExecutionBudget fast = CpuExecutionBudget.fast();
        fast.requireProvider("CPU");
        for (String provider : new String[]{null, "NNAPI", "XNNPACK", "cpu", "CPU "}) {
            assertThrows(IllegalArgumentException.class, () -> fast.requireProvider(provider));
        }
        assertThrows(IllegalArgumentException.class, () -> fast.nativeThreads(0, 4));
        assertEquals(1, fast.nativeThreads(1, 4));
    }

    @Test public void budgetDoesNotChangePresetShapeModelOrThresholds() {
        DetectorConfig configured = DetectorConfig.builder().inferenceThreads(3).inferenceResolution(480)
                .modelFilename("320n_fp16.onnx").confidenceThreshold(.18f).boxPadding(.14f).build();
        DetectorConfig fast = VisualInferencePolicy.fast(configured), quality = VisualInferencePolicy.quality(configured);
        assertEquals(2, CpuExecutionBudget.fast().nativeThreads(fast.getInferenceThreads(), 4));
        assertEquals(1, CpuExecutionBudget.quality().nativeThreads(quality.getInferenceThreads(), 4));
        assertEquals(3, configured.getInferenceThreads());
        assertEquals(3, fast.getInferenceThreads());
        assertEquals(3, quality.getInferenceThreads());
        assertEquals(320, fast.getInferenceResolution());
        assertEquals(480, quality.getInferenceResolution());
        assertArrayEquals(new int[]{160, 320}, VisualInferencePolicy.rectangularInput(1344, 2992, fast.getInferenceResolution()));
        assertEquals(configured.getModelFilename(), fast.getModelFilename());
        assertEquals(configured.getModelFilename(), quality.getModelFilename());
        assertEquals(.18f, fast.getConfidenceThreshold(), 0);
        assertEquals(.14f, quality.getBoxPadding(), 0);
    }
}
