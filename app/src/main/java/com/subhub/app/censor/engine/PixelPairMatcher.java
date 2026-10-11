package com.subhub.app.censor.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Detector-independent, bounded RGB raster correspondence producer.
 *
 * <p>A result qualifies sampled-pixel similarity and uniqueness, not physical motion. In
 * particular, sampling footprints and RGB residuals do not establish a displacement error bound.
 * There is deliberately no conversion to the scene's exact MotionPair authority.</p>
 */
public final class PixelPairMatcher {
    public static final int MAXIMUM_WIDTH = 256;
    public static final int MAXIMUM_HEIGHT = 512;
    public static final int MAXIMUM_TILES = 64;

    public enum Provenance { UNCENSORED_ACCESSIBILITY_WINDOW, UNCENSORED_ACCESSIBILITY_DISPLAY, UNCENSORED_LAB, CENSORED }
    public enum Sampling { EXACT_RASTER, BOX_AVERAGE }
    public enum Status { COMPLETE, INVALID_INPUT, WORK_BUDGET, CANCELLED }

    /** The platform separately establishes provenance and original pixel geometry. */
    public static final class Image {
        public final SceneEngine.Source source;
        public final PixelGeometry geometry;
        public final int width, height;
        public final Provenance provenance;
        public final Sampling sampling;
        private final int[] rgb;

        private Image(SceneEngine.Source source, PixelGeometry geometry, int width, int height,
                Provenance provenance, Sampling sampling, int[] rgb) {
            this.source = source;
            this.geometry = geometry;
            this.width = width;
            this.height = height;
            this.provenance = provenance;
            this.sampling = sampling;
            this.rgb = rgb;
        }

        /** Returns null before allocation on unsupported dimensions or inconsistent source mapping. */
        public static Image copyOf(SceneEngine.Source source, PixelGeometry geometry, int width, int height,
                Provenance provenance, Sampling sampling, int[] argb) {
            if (source == null || geometry == null || argb == null || provenance == null || sampling == null
                    || source.kind != SceneEngine.SourceKind.IMAGE
                    || provenance == Provenance.CENSORED || width < 8 || height < 8
                    || width > MAXIMUM_WIDTH || height > MAXIMUM_HEIGHT
                    || (long) width * height != argb.length
                    || source.width != geometry.displayWidth || source.height != geometry.displayHeight
                    || width > geometry.width || height > geometry.height
                    || (sampling == Sampling.EXACT_RASTER
                        && (width != geometry.width || height != geometry.height))) return null;
            int[] copy = new int[argb.length];
            for (int i = 0; i < copy.length; i++) {
                // Transparent/composited colors are not an uncensored opaque capture raster.
                if ((argb[i] >>> 24) != 255) return null;
                copy[i] = argb[i] & 0xffffff;
            }
            return new Image(source, geometry, width, height, provenance, sampling, copy);
        }
    }

    /** Resource and image-qualification limits; these never change detector thresholds. */
    public static final class Policy {
        public final int columns, rows, maximumChannelError, minimumTexture;
        public final long maximumPixelComparisons;

        public Policy(int columns, int rows, int maximumChannelError, int minimumTexture,
                long maximumPixelComparisons) {
            if (columns < 1 || rows < 1 || columns > 8 || rows > 8
                    || columns * rows > MAXIMUM_TILES || maximumChannelError < 0 || maximumChannelError > 32
                    || minimumTexture <= maximumChannelError * 2 || minimumTexture > 255
                    || maximumPixelComparisons < 1 || maximumPixelComparisons > 100_000_000) {
                throw new IllegalArgumentException("pixel matcher policy");
            }
            this.columns = columns;
            this.rows = rows;
            this.maximumChannelError = maximumChannelError;
            this.minimumTexture = minimumTexture;
            this.maximumPixelComparisons = maximumPixelComparisons;
        }
    }

    /**
     * Entire qualified sampled rectangle, not a hull around landmarks. Native coordinates describe
     * its sampling footprint only: unobserved physical displacement uncertainty remains UNKNOWN.
     */
    public static final class Evidence {
        public final SceneEngine.Region sampledSupport;
        public final SceneEngine.Region sourceFootprint;
        public final int sampledDx, sampledDy, maximumObservedChannelError;
        public final double nominalDx, nominalDy, cellWidth, cellHeight;
        public final boolean physicalErrorKnown = false;

        private Evidence(Image image, Tile tile) {
            sampledSupport = new SceneEngine.Region(tile.left, tile.top, tile.right, tile.bottom);
            cellWidth = image.geometry.width / (double) image.width;
            cellHeight = image.geometry.height / (double) image.height;
            sourceFootprint = new SceneEngine.Region(image.geometry.left + tile.left * cellWidth,
                    image.geometry.top + tile.top * cellHeight, image.geometry.left + tile.right * cellWidth,
                    image.geometry.top + tile.bottom * cellHeight);
            sampledDx = tile.dx;
            sampledDy = tile.dy;
            nominalDx = tile.dx * cellWidth;
            nominalDy = tile.dy * cellHeight;
            maximumObservedChannelError = tile.residual;
        }
    }

    /** Complete is allowed to contain no evidence: rejection is never substituted with zero. */
    public static final class Result {
        public final Status status;
        public final SceneEngine.Source from, to;
        public final PixelGeometry fromGeometry, toGeometry;
        public final Provenance provenance;
        public final Sampling sampling;
        public final int sampledWidth, sampledHeight;
        public final List<Evidence> evidence;
        public final long pixelComparisons;
        public final int texturedTiles, ambiguousTiles, unmatchedTiles, destinationConflicts;

        private Result(Status status, Image from, Image to, List<Tile> tiles, Work work) {
            this.status = status;
            this.from = from == null ? null : from.source;
            this.to = to == null ? null : to.source;
            fromGeometry = from == null ? null : from.geometry;
            toGeometry = to == null ? null : to.geometry;
            provenance = from == null ? null : from.provenance;
            sampling = from == null ? null : from.sampling;
            sampledWidth = from == null ? 0 : from.width;
            sampledHeight = from == null ? 0 : from.height;
            List<Evidence> values = new ArrayList<>(tiles.size());
            for (Tile tile : tiles) values.add(new Evidence(from, tile));
            evidence = Collections.unmodifiableList(values);
            pixelComparisons = work == null ? 0 : work.comparisons;
            texturedTiles = work == null ? 0 : work.textured;
            ambiguousTiles = work == null ? 0 : work.ambiguous;
            unmatchedTiles = work == null ? 0 : work.unmatched;
            destinationConflicts = work == null ? 0 : work.conflicts;
        }
    }

    private final Policy policy;

    public PixelPairMatcher(Policy policy) { this.policy = Objects.requireNonNull(policy); }

    public Result match(Image from, Image to, BooleanSupplier cancellation) {
        Objects.requireNonNull(cancellation);
        if (!compatible(from, to) || from.width / policy.columns < 8 || from.height / policy.rows < 8) {
            return new Result(Status.INVALID_INPUT, from, to, Collections.emptyList(), null);
        }
        Work work = new Work(policy.maximumPixelComparisons, cancellation);
        List<Tile> tiles = new ArrayList<>(policy.columns * policy.rows);
        try {
            work.checkCancellation();
            for (int row = 0; row < policy.rows; row++) {
                for (int column = 0; column < policy.columns; column++) {
                    Tile tile = new Tile(column * from.width / policy.columns, row * from.height / policy.rows,
                            (column + 1) * from.width / policy.columns, (row + 1) * from.height / policy.rows);
                    int anchor = anchor(from, tile, work);
                    if (anchor < 0) continue;
                    work.textured++;
                    Location forward = search(from, tile, to, anchor, work);
                    if (forward.ambiguous) { work.ambiguous++; continue; }
                    if (!forward.found) { work.unmatched++; continue; }
                    Tile target = tile.at(forward.x, forward.y);
                    Location reverse = search(to, target, from, anchor, work);
                    if (reverse.ambiguous) { work.ambiguous++; continue; }
                    if (!reverse.found || reverse.x != tile.left || reverse.y != tile.top) {
                        work.unmatched++;
                        continue;
                    }
                    tile.dx = forward.x - tile.left;
                    tile.dy = forward.y - tile.top;
                    tile.residual = forward.residual;
                    tiles.add(tile);
                }
            }
            rejectDestinationConflicts(tiles, work);
            mergeAdjacent(tiles);
            work.checkCancellation();
            return new Result(Status.COMPLETE, from, to, tiles, work);
        } catch (Stopped stopped) {
            // Never expose an unfinished search as partial authority or a complete unknown result.
            return new Result(stopped.status, from, to, Collections.emptyList(), work);
        }
    }

    private static boolean compatible(Image a, Image b) {
        if (a == null || b == null || a.width != b.width || a.height != b.height
                || a.provenance != b.provenance || a.sampling != b.sampling
                || a.source.epoch != b.source.epoch || a.source.geometry != b.source.geometry
                || a.source.sequence >= b.source.sequence) return false;
        PixelGeometry x = a.geometry, y = b.geometry;
        return x.displayWidth == y.displayWidth && x.displayHeight == y.displayHeight && x.left == y.left
                && x.top == y.top && x.width == y.width && x.height == y.height;
    }

    /** Select a source-pixel anchor independently of detectors/events; require both-axis texture. */
    private int anchor(Image image, Tile tile, Work work) {
        int best = -1, strength = -1;
        boolean horizontal = false, vertical = false;
        double xx = 0, yy = 0, xy = 0;
        for (int y = tile.top + 1; y < tile.bottom; y++) {
            for (int x = tile.left + 1; x < tile.right; x++) {
                int at = y * image.width + x;
                int dx = difference(image.rgb[at], image.rgb[at - 1], work);
                int dy = difference(image.rgb[at], image.rgb[at - image.width], work);
                horizontal |= dx >= policy.minimumTexture;
                vertical |= dy >= policy.minimumTexture;
                for (int channel = 0; channel < 24; channel += 8) {
                    int value = (image.rgb[at] >>> channel) & 255;
                    int gx = value - ((image.rgb[at - 1] >>> channel) & 255);
                    int gy = value - ((image.rgb[at - image.width] >>> channel) & 255);
                    xx += gx * gx; yy += gy * gy; xy += gx * gy;
                }
                if (dx + dy > strength) {
                    strength = dx + dy;
                    best = (y - tile.top) * tile.width() + x - tile.left;
                }
            }
        }
        // Per-axis variation alone admits diagonal aperture I(x,y)=h(x+y).
        double determinant = xx * yy - xy * xy;
        return horizontal && vertical && determinant > (xx + yy) * (xx + yy) * .01 ? best : -1;
    }

    /** Search every in-bounds lattice position. An eligible second location proves ambiguity. */
    private Location search(Image reference, Tile tile, Image candidate, int anchor, Work work) {
        Location found = new Location();
        int ax = anchor % tile.width(), ay = anchor / tile.width();
        int anchorColor = reference.rgb[(tile.top + ay) * reference.width + tile.left + ax];
        for (int y = 0; y <= candidate.height - tile.height(); y++) {
            for (int x = 0; x <= candidate.width - tile.width(); x++) {
                if (difference(anchorColor, candidate.rgb[(y + ay) * candidate.width + x + ax], work)
                        > policy.maximumChannelError) continue;
                int residual = compare(reference, tile, candidate, x, y, work);
                if (residual < 0) continue;
                if (found.found) { found.ambiguous = true; return found; }
                found.found = true;
                found.x = x;
                found.y = y;
                found.residual = residual;
            }
        }
        return found;
    }

    private int compare(Image reference, Tile tile, Image candidate, int x, int y, Work work) {
        int maximum = 0;
        for (int row = 0; row < tile.height(); row++) {
            for (int column = 0; column < tile.width(); column++) {
                int error = difference(reference.rgb[(tile.top + row) * reference.width + tile.left + column],
                        candidate.rgb[(y + row) * candidate.width + x + column], work);
                if (error > policy.maximumChannelError) return -1;
                maximum = Math.max(maximum, error);
            }
        }
        return maximum;
    }

    private static int difference(int a, int b, Work work) {
        work.compare();
        return Math.max(Math.abs((a & 255) - (b & 255)), Math.max(
                Math.abs(((a >>> 8) & 255) - ((b >>> 8) & 255)),
                Math.abs(((a >>> 16) & 255) - ((b >>> 16) & 255))));
    }

    private static void rejectDestinationConflicts(List<Tile> tiles, Work work) {
        boolean[] invalid = new boolean[tiles.size()];
        for (int i = 0; i < tiles.size(); i++) {
            Tile a = tiles.get(i);
            for (int j = i + 1; j < tiles.size(); j++) {
                Tile b = tiles.get(j);
                if (a.left + a.dx < b.right + b.dx && b.left + b.dx < a.right + a.dx
                        && a.top + a.dy < b.bottom + b.dy && b.top + b.dy < a.bottom + a.dy) {
                    invalid[i] = invalid[j] = true;
                }
            }
        }
        for (int i = tiles.size() - 1; i >= 0; i--) {
            if (invalid[i]) { tiles.remove(i); work.conflicts++; }
        }
    }

    /** At most64 rectangles; merges never cover an unqualified gap or enlarge either domain. */
    private static void mergeAdjacent(List<Tile> tiles) {
        boolean changed;
        do {
            changed = false;
            for (int i = 0; i < tiles.size() && !changed; i++) {
                Tile a = tiles.get(i);
                for (int j = i + 1; j < tiles.size(); j++) {
                    Tile b = tiles.get(j);
                    if (a.dx != b.dx || a.dy != b.dy) continue;
                    boolean horizontal = a.top == b.top && a.bottom == b.bottom
                            && (a.right == b.left || b.right == a.left);
                    boolean vertical = a.left == b.left && a.right == b.right
                            && (a.bottom == b.top || b.bottom == a.top);
                    if (!horizontal && !vertical) continue;
                    Tile merged = new Tile(Math.min(a.left, b.left), Math.min(a.top, b.top),
                            Math.max(a.right, b.right), Math.max(a.bottom, b.bottom));
                    merged.dx = a.dx; merged.dy = a.dy;
                    merged.residual = Math.max(a.residual, b.residual);
                    tiles.set(i, merged);
                    tiles.remove(j);
                    changed = true;
                    break;
                }
            }
        } while (changed);
    }

    private static final class Tile {
        final int left, top, right, bottom;
        int dx, dy, residual;
        Tile(int left, int top, int right, int bottom) {
            this.left = left; this.top = top; this.right = right; this.bottom = bottom;
        }
        int width() { return right - left; }
        int height() { return bottom - top; }
        Tile at(int x, int y) { return new Tile(x, y, x + width(), y + height()); }
    }

    private static final class Location {
        boolean found, ambiguous;
        int x, y, residual;
    }

    private static final class Work {
        final long maximum;
        final BooleanSupplier cancellation;
        long comparisons;
        int textured, ambiguous, unmatched, conflicts;
        Work(long maximum, BooleanSupplier cancellation) { this.maximum = maximum; this.cancellation = cancellation; }
        void checkCancellation() { if (cancellation.getAsBoolean()) throw new Stopped(Status.CANCELLED); }
        void compare() {
            if ((comparisons & 255) == 0) checkCancellation();
            if (comparisons >= maximum) throw new Stopped(Status.WORK_BUDGET);
            comparisons++;
        }
    }

    private static final class Stopped extends RuntimeException {
        final Status status;
        Stopped(Status status) { this.status = status; }
    }
}
