package com.subhub.app.censor.engine;

import static org.junit.Assert.*;

import com.subhub.app.detection.CensorCoverage;
import com.subhub.app.detection.DetectorConfig;
import java.util.Arrays;
import java.util.LinkedHashSet;
import org.junit.Test;

public final class VisualInferencePolicyTest {
    @Test public void fastAndQualityPreserveModelAndCategoryThresholdControls() {
        DetectorConfig source = DetectorConfig.builder().inferenceResolution(512).modelFilename("512n_fp16.onnx")
                .confidenceThreshold(.47f).boxPadding(.1f).minDetectionSize(19).detectCovered(true)
                .enabledCategories(new LinkedHashSet<>(Arrays.asList("face", "breasts_covered")))
                .censorCoverage(CensorCoverage.WHOLE_PERSON).inferenceThreads(4).detectionIntervalMs(80).build();
        DetectorConfig fast = VisualInferencePolicy.fast(source), quality = VisualInferencePolicy.quality(source);
        assertEquals(320, fast.getInferenceResolution()); assertEquals(0, fast.getDetectionIntervalMs());
        assertEquals(512, quality.getInferenceResolution()); assertEquals(80, quality.getDetectionIntervalMs());
        for (DetectorConfig config : Arrays.asList(fast, quality)) {
            assertEquals(source.getModelFilename(), config.getModelFilename());
            assertEquals(source.getEnabledCategories(), config.getEnabledCategories());
            assertEquals(.47f, config.getConfidenceThreshold(), 0); assertEquals(.1f, config.getBoxPadding(), 0);
            assertEquals(19, config.getMinDetectionSize()); assertTrue(config.isDetectCovered());
            assertEquals(4, config.getInferenceThreads());
        }
        assertEquals(CensorCoverage.WHOLE_PERSON, fast.getCensorCoverage());
        assertEquals(CensorCoverage.DETECTED_AREAS, quality.getCensorCoverage());
    }

    @Test public void rectangularInputMatchesExistingStrideAndSquareCutoff() {
        assertArrayEquals(new int[]{160, 320}, VisualInferencePolicy.rectangularInput(1080, 2400, 320));
        assertArrayEquals(new int[]{320, 160}, VisualInferencePolicy.rectangularInput(2400, 1080, 320));
        assertNull(VisualInferencePolicy.rectangularInput(1000, 1000, 320));
        assertNull(VisualInferencePolicy.rectangularInput(0, 2400, 320));
        assertNull(VisualInferencePolicy.rectangularInput(1080, 2400, 16));
    }

    @Test public void qualityCropsPreserveWholeTopBottomControlsAndNativeDetail() {
        crop(VisualInferencePolicy.qualityCrop(1080, 2400, 0), 0, 0, 1080, 2400);
        crop(VisualInferencePolicy.qualityCrop(1080, 2400, 1), 0, 0, 1080, 1968);
        crop(VisualInferencePolicy.qualityCrop(1080, 2400, 2), 0, 432, 1080, 1968);
        crop(VisualInferencePolicy.qualityCrop(2400, 1080, 1), 0, 0, 1968, 1080);
        crop(VisualInferencePolicy.qualityCrop(2400, 1080, 2), 432, 0, 1968, 1080);
        crop(VisualInferencePolicy.qualityCrop(1000, 1000, 2), 0, 0, 1000, 1000);
        assertThrows(IllegalArgumentException.class, () -> VisualInferencePolicy.qualityCrop(0, 100, 0));
    }

    @Test public void inspectionInventoryIncludesOnlyEnabledModelCategories() {
        DetectorConfig config = DetectorConfig.builder().enabledCategories(new LinkedHashSet<>(
                Arrays.asList("face", "eyes", "breasts_covered", "smutWords", "unsupported"))).build();
        assertEquals(new LinkedHashSet<>(Arrays.asList("face", "breasts_covered", "eyes")),
                VisualInferencePolicy.inspectedCategories(config));
        assertTrue(VisualInferencePolicy.inspectedCategories(DetectorConfig.builder()
                .enabledCategories(new LinkedHashSet<>(Arrays.asList("eyes"))).build()).isEmpty());
    }

    private static void crop(VisualInferencePolicy.Crop crop, int left, int top, int width, int height) {
        assertEquals(left, crop.left); assertEquals(top, crop.top); assertEquals(width, crop.width); assertEquals(height, crop.height);
    }
}
