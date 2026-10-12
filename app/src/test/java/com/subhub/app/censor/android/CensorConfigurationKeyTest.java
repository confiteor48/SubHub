package com.subhub.app.censor.android;

import static org.junit.Assert.*;
import org.junit.Test;
import com.subhub.app.detection.*;
import com.subhub.app.detection.text.TextSmutConfig;
import java.util.Collections;

public final class CensorConfigurationKeyTest {
    private static TextSmutConfig text() { return new TextSmutConfig(true, 1, TextSmutConfig.DEFAULT_CATEGORIES); }
    @Test public void identicalIndependentConfigurationsHaveEqualKeys() {
        assertEquals(CensorConfigurationKey.inference(DetectorConfig.builder().build(), text()),
                CensorConfigurationKey.inference(DetectorConfig.builder().build(), text()));
    }
    @Test public void allNineteenDetectorFieldsAreStructural() {
        DetectorConfig d = DetectorConfig.builder().build();
        DetectorConfig[] differences = {
                d.toBuilder().enabledCategories(Collections.singleton("FACE_FEMALE")).build(), d.toBuilder().confidenceThreshold(.4f).build(),
                d.toBuilder().trackingSmoothing(.9f).build(), d.toBuilder().trackMaxAgeSeconds(1f).build(),
                d.toBuilder().minRemoveFrames(4).build(), d.toBuilder().motionPrediction(false).build(),
                d.toBuilder().detectionIntervalMs(500).build(), d.toBuilder().boxPadding(.1f).build(),
                d.toBuilder().minDetectionSize(20).build(), d.toBuilder().captureScale(.8f).build(),
                d.toBuilder().inferenceResolution(512).build(), d.toBuilder().modelFilename("512n_fp16.onnx").build(),
                d.toBuilder().idleThresholdFrames(30).build(), d.toBuilder().renderAlpha(.8f).build(),
                d.toBuilder().velocitySmoothing(.6f).build(), d.toBuilder().maxExtrapolationMs(100f).build(),
                d.toBuilder().detectCovered(true).build(), d.toBuilder().inferenceThreads(3).build(),
                d.toBuilder().censorCoverage(CensorCoverage.WHOLE_PERSON).build()
        };
        assertEquals(19, differences.length);
        for (DetectorConfig other : differences) assertNotEquals(CensorConfigurationKey.inference(d, text()), CensorConfigurationKey.inference(other, text()));
    }
    @Test public void textEnableSensitivityAndCategoriesAreStructural() {
        DetectorConfig d = DetectorConfig.builder().build();
        for (TextSmutConfig t : new TextSmutConfig[] { new TextSmutConfig(false, 1, TextSmutConfig.DEFAULT_CATEGORIES),
                new TextSmutConfig(true, 2, TextSmutConfig.DEFAULT_CATEGORIES), new TextSmutConfig(true, 1, Collections.emptySet()) }) {
            assertNotEquals(CensorConfigurationKey.inference(d, text()), CensorConfigurationKey.inference(d, t));
        }
    }
}
