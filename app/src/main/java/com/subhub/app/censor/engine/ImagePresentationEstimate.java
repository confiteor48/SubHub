package com.subhub.app.censor.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Direct appearance-localization hints, never exact geometry or identity authority. Intervals
 * describe the producer's image search, not a physical error bound or current display clock.
 * No piece grants support outside its original tested region; no hull is constructed here.
 */
public final class ImagePresentationEstimate {
    public static final int MAXIMUM_PIECES = 64;
    public final long epoch, from, to, partition, revision;
    public final List<Piece> pieces;
    public final boolean physicalErrorKnown = false;

    public static final class Piece {
        public final SceneEngine.Region support;
        public final double dxMinimum, dxMaximum, dyMinimum, dyMaximum;
        public final SceneEngine.Region destinationEnvelope;

        public Piece(SceneEngine.Region support, double dxMinimum, double dxMaximum,
                double dyMinimum, double dyMaximum) {
            this.support = Objects.requireNonNull(support);
            if (!Double.isFinite(dxMinimum) || !Double.isFinite(dxMaximum)
                    || !Double.isFinite(dyMinimum) || !Double.isFinite(dyMaximum)
                    || dxMinimum > dxMaximum || dyMinimum > dyMaximum) {
                throw new IllegalArgumentException("localization interval");
            }
            this.dxMinimum = zero(dxMinimum); this.dxMaximum = zero(dxMaximum);
            this.dyMinimum = zero(dyMinimum); this.dyMaximum = zero(dyMaximum);
            destinationEnvelope = envelope(support);
        }

        public SceneEngine.Region envelope(SceneEngine.Region region) {
            return new SceneEngine.Region(region.left + dxMinimum, region.top + dyMinimum,
                    region.right + dxMaximum, region.bottom + dyMaximum);
        }

        private boolean same(Piece other) {
            return support.left == other.support.left && support.top == other.support.top
                    && support.right == other.support.right && support.bottom == other.support.bottom
                    && dxMinimum == other.dxMinimum && dxMaximum == other.dxMaximum
                    && dyMinimum == other.dyMinimum && dyMaximum == other.dyMaximum;
        }
    }

    public ImagePresentationEstimate(long epoch, long from, long to, long partition, long revision,
            List<Piece> pieces) {
        if (epoch < 0 || from <= 0 || to <= from || revision < 0) throw new IllegalArgumentException("image hint identity");
        Objects.requireNonNull(pieces);
        int count = pieces.size();
        if (count < 1 || count > MAXIMUM_PIECES) throw new IllegalArgumentException("image hint size");
        List<Piece> copied = new ArrayList<>(count);
        for (int i = 0; i < count; i++) copied.add(Objects.requireNonNull(pieces.get(i)));
        if (pieces.size() != count) throw new IllegalArgumentException("image hint changed during copy");
        copied.sort(Comparator.comparingDouble((Piece value) -> zero(value.support.left))
                .thenComparingDouble(value -> zero(value.support.top))
                .thenComparingDouble(value -> zero(value.support.right))
                .thenComparingDouble(value -> zero(value.support.bottom))
                .thenComparingDouble(value -> value.dxMinimum).thenComparingDouble(value -> value.dxMaximum)
                .thenComparingDouble(value -> value.dyMinimum).thenComparingDouble(value -> value.dyMaximum));
        this.epoch = epoch; this.from = from; this.to = to;
        this.partition = partition; this.revision = revision;
        this.pieces = Collections.unmodifiableList(copied);
    }

    boolean sameKey(ImagePresentationEstimate other) {
        return epoch == other.epoch && from == other.from && to == other.to && partition == other.partition;
    }

    boolean samePieces(ImagePresentationEstimate other) {
        if (pieces.size() != other.pieces.size()) return false;
        for (int i = 0; i < pieces.size(); i++) if (!pieces.get(i).same(other.pieces.get(i))) return false;
        return true;
    }

    private static double zero(double value) { return value == 0 ? 0 : value; }
}
