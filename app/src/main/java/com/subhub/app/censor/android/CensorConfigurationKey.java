package com.subhub.app.censor.android;

import com.subhub.app.detection.DetectorConfig;
import com.subhub.app.detection.text.TextSmutConfig;
import com.subhub.app.settings.CensorAppearance;
import java.util.Arrays;
import java.util.List;

/** Value keys, not hashes alone: equal settings retain source identities and native contexts. */
final class CensorConfigurationKey {
    private CensorConfigurationKey() { }
    static List<Object> inference(DetectorConfig d, TextSmutConfig t) {
        return Arrays.asList(d.getEnabledCategories(), d.getConfidenceThreshold(), d.getTrackingSmoothing(),
                d.getTrackMaxAgeSeconds(), d.getMinRemoveFrames(), d.isMotionPrediction(), d.getDetectionIntervalMs(),
                d.getBoxPadding(), d.getMinDetectionSize(), d.getCaptureScale(), d.getInferenceResolution(),
                d.getModelFilename(), d.getIdleThresholdFrames(), d.getRenderAlpha(), d.getVelocitySmoothing(),
                d.getMaxExtrapolationMs(), d.isDetectCovered(), d.getInferenceThreads(), d.getCensorCoverage(),
                t.isEnabled(), t.getSensitivity(), t.getEnabledCategories());
    }
    static List<Object> appearance(CensorAppearance a, SnapshotPaintPolicy p, long revision) {
        return Arrays.asList(a.getType(), a.getIntensity(), a.getSizePadding(), a.isShowBorder(), a.isAnimateBorder(),
                a.getBorderEffect(), a.isShowText(), a.getBorderColor(), a.getGradientStart(), a.getGradientEnd(),
                a.getEffectPalette().first(), a.getEffectPalette().second(), a.getEffectPalette().third(),
                a.getPhrases(), a.isReverseMode(), a.getReverseStrength(), a.getReverseCutoutShape(),
                a.getErrorTitle(), a.getErrorMessage(), p.artworkDimension, p.artworkCount, revision);
    }
}
