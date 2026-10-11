package com.subhub.app.censor.engine;

import com.subhub.app.detection.CensorCoverage;
import com.subhub.app.detection.DetectorConfig;
import com.subhub.app.detection.NudeNetClassCatalog;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** Standalone existing detector/crop controls, with no service/scene/tracker ownership. */
public final class VisualInferencePolicy {
    public static final int FAST_RESOLUTION = 320;

    private VisualInferencePolicy() { }

    public static DetectorConfig fast(DetectorConfig source) {
        return source.toBuilder().inferenceResolution(Math.min(FAST_RESOLUTION, source.getInferenceResolution()))
                .detectionIntervalMs(0).build();
    }

    public static DetectorConfig quality(DetectorConfig source) {
        return source.toBuilder().censorCoverage(CensorCoverage.DETECTED_AREAS).build();
    }

    public static int[] rectangularInput(int width, int height, int longEdge) {
        if (width <= 0 || height <= 0 || longEdge < 32) return null;
        int longest = Math.max(width, height);
        int contentWidth = Math.max(1, Math.round(width * (longEdge / (float) longest)));
        int contentHeight = Math.max(1, Math.round(height * (longEdge / (float) longest)));
        int tensorWidth = Math.min(longEdge, ((contentWidth + 31) / 32) * 32);
        int tensorHeight = Math.min(longEdge, ((contentHeight + 31) / 32) * 32);
        if ((long) tensorWidth * tensorHeight >= (long) longEdge * longEdge * 4 / 5) return null;
        return new int[]{tensorWidth, tensorHeight};
    }

    /** Whole-frame, then alternating 82% detail crops; each job inspects only its own crop. */
    public static Crop qualityCrop(int width, int height, long pass) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("crop dimensions");
        int phase = (int) Math.floorMod(pass, 3L);
        if (phase == 0 || height < Math.round(width * 1.35f) && width < Math.round(height * 1.35f)) {
            return new Crop(0, 0, width, height);
        }
        if (height >= Math.round(width * 1.35f)) {
            int span = Math.max(1, Math.min(height, Math.round(height * .82f)));
            return new Crop(0, phase == 1 ? 0 : height - span, width, span);
        }
        int span = Math.max(1, Math.min(width, Math.round(width * .82f)));
        return new Crop(phase == 1 ? 0 : width - span, 0, span, height);
    }

    public static Set<String> inspectedCategories(DetectorConfig config) {
        Set<String> supported = new LinkedHashSet<>();
        for (int i = 0; i < NudeNetClassCatalog.CLASS_COUNT; i++) {
            supported.addAll(NudeNetClassCatalog.byIndex(i).getCategories());
        }
        if (config.getEnabledCategories().contains("face") || config.getEnabledCategories().contains("face_female")
                || config.getEnabledCategories().contains("face_male")) supported.add("eyes");
        supported.retainAll(config.getEnabledCategories());
        return Collections.unmodifiableSet(supported);
    }

    public static final class Crop {
        public final int left;
        public final int top;
        public final int width;
        public final int height;
        public Crop(int left, int top, int width, int height) {
            if (left < 0 || top < 0 || width <= 0 || height <= 0) throw new IllegalArgumentException("crop");
            this.left = left; this.top = top; this.width = width; this.height = height;
        }
    }
}
