package com.subhub.app.censor.android;

import com.subhub.app.detection.DetectionPreset;
import com.subhub.app.settings.CensorAppearance;
import java.util.Objects;

/** Separate managed input/output limits, not a whole-engine or codec/native/GPU memory promise. */
public final class SnapshotPaintPolicy {
    public final int artworkDimension, artworkCount;
    public final long maximumArtworkBytes;
    public SnapshotPaintPolicy(int dimension, int count) {
        if (dimension < 1 || dimension > 1024 || count < 1 || count > 16) throw new IllegalArgumentException("artwork policy");
        artworkDimension = dimension; artworkCount = count; maximumArtworkBytes = 4L * dimension * dimension * count;
    }
    public static SnapshotPaintPolicy from(DetectionPreset preset) {
        Objects.requireNonNull(preset); return new SnapshotPaintPolicy(preset.getCustomImageDimension(), preset.getCustomImageCount());
    }
    int preparedBytes(CensorAppearance appearance) {
        return !appearance.isReverseMode() && appearance.getType() == CensorAppearance.Type.CUSTOM
                ? (int) Math.max(PreparedCensorPaint.DEFAULT_MAXIMUM_BYTES, maximumArtworkBytes)
                : PreparedCensorPaint.DEFAULT_MAXIMUM_BYTES;
    }
}
