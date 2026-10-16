package com.subhub.app.censor.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Source-local state owner for the replacement censor runtime.
 *
 * <p>All state transitions linearize here. Workers supply immutable observations and measured
 * correspondences; they never mutate identities, confirmation, retention or presentation. This
 * core does not infer image motion or claim that a callback timestamp is a pixel timestamp.</p>
 */
public final class SceneEngine {
    public enum Lane { FAST, QUALITY, TEXT }
    public enum SourceKind { IMAGE, NATIVE_TEXT }
    public enum MotionAuthority { EVENT_ESTIMATE, IMAGE_CORRESPONDENCE }
    public enum MotionAdmission { ADMITTED, DUPLICATE, STALE, INVALID, CAPACITY, CHECK_BUDGET, UNREPRESENTABLE, CONFLICT }
    public enum PresentationAdmission { ADMITTED, DUPLICATE, STALE, INVALID, CAPACITY }

    /** Immutable origin. Sequence orders owner admission, never physical pixel/read time. */
    public static final class Source {
        public final long epoch;
        public final long sequence;
        public final long geometry;
        public final int width;
        public final int height;
        public final long receivedAtMillis;
        /** -1 means unknown. Receipt time is never substituted for this value. */
        public final long pixelAtMillis;
        public final SourceKind kind;
        /** IMAGE request lineage; zero for a native read with no image request. */
        public final long imageRequestIdentity;
        public final long readStartedAtMillis;
        public final long readFinishedAtMillis;
        public final int windowId;
        public final ProtectionScope protectionScope;

        public Source(long epoch, long sequence, long geometry, int width, int height,
                long receivedAtMillis, long pixelAtMillis) {
            this(epoch, sequence, geometry, width, height, receivedAtMillis, pixelAtMillis,
                    SourceKind.IMAGE, sequence, -1, -1, -1, ProtectionScope.UNSPECIFIED);
        }

        private Source(long epoch, long sequence, long geometry, int width, int height,
                long receivedAtMillis, long pixelAtMillis, SourceKind kind, long imageRequestIdentity,
                long readStartedAtMillis, long readFinishedAtMillis, int windowId, ProtectionScope protectionScope) {
            if (epoch < 0 || sequence <= 0 || width <= 0 || height <= 0
                    || receivedAtMillis < 0 || pixelAtMillis < -1
                    || (kind == SourceKind.IMAGE && (imageRequestIdentity <= 0
                    || readStartedAtMillis != -1 || readFinishedAtMillis != -1 || windowId != -1))
                    || (kind == SourceKind.NATIVE_TEXT && (pixelAtMillis != -1 || imageRequestIdentity != 0
                    || readStartedAtMillis < 0 || readFinishedAtMillis < readStartedAtMillis
                    || readFinishedAtMillis > receivedAtMillis || windowId < 0))) {
                throw new IllegalArgumentException("source");
            }
            this.epoch = epoch;
            this.sequence = sequence;
            this.geometry = geometry;
            this.width = width;
            this.height = height;
            this.receivedAtMillis = receivedAtMillis;
            this.pixelAtMillis = pixelAtMillis;
            this.kind = Objects.requireNonNull(kind);
            this.imageRequestIdentity = imageRequestIdentity;
            this.readStartedAtMillis = readStartedAtMillis;
            this.readFinishedAtMillis = readFinishedAtMillis;
            this.windowId = windowId;
            this.protectionScope = Objects.requireNonNull(protectionScope);
        }

        public static Source image(long epoch, long sequence, long requestIdentity, long geometry,
                int width, int height, long receivedAtMillis, long pixelAtMillis) {
            return image(epoch, sequence, requestIdentity, geometry, width, height, receivedAtMillis,
                    pixelAtMillis, ProtectionScope.UNSPECIFIED);
        }

        public static Source image(long epoch, long sequence, long requestIdentity, long geometry,
                int width, int height, long receivedAtMillis, long pixelAtMillis, ProtectionScope protectionScope) {
            return new Source(epoch, sequence, geometry, width, height, receivedAtMillis, pixelAtMillis,
                    SourceKind.IMAGE, requestIdentity, -1, -1, -1, protectionScope);
        }

        public static Source nativeText(long epoch, long sequence, long geometry, int width, int height,
                int windowId, long readStartedAtMillis, long readFinishedAtMillis, long receivedAtMillis) {
            return nativeText(epoch, sequence, geometry, width, height, windowId, readStartedAtMillis,
                    readFinishedAtMillis, receivedAtMillis, ProtectionScope.UNSPECIFIED);
        }

        public static Source nativeText(long epoch, long sequence, long geometry, int width, int height,
                int windowId, long readStartedAtMillis, long readFinishedAtMillis, long receivedAtMillis,
                ProtectionScope protectionScope) {
            return new Source(epoch, sequence, geometry, width, height, receivedAtMillis, -1,
                    SourceKind.NATIVE_TEXT, 0, readStartedAtMillis, readFinishedAtMillis, windowId, protectionScope);
        }

        private boolean sameDescriptor(Source other) {
            return epoch == other.epoch && sequence == other.sequence && geometry == other.geometry
                    && width == other.width && height == other.height
                    && receivedAtMillis == other.receivedAtMillis && pixelAtMillis == other.pixelAtMillis
                    && kind == other.kind && imageRequestIdentity == other.imageRequestIdentity
                    && readStartedAtMillis == other.readStartedAtMillis
                    && readFinishedAtMillis == other.readFinishedAtMillis && windowId == other.windowId
                    && protectionScope.equals(other.protectionScope);
        }
    }

    /** Unclipped geometry, so offscreen tracks can re-enter without accumulating clipping error. */
    public static final class Region {
        public final double left;
        public final double top;
        public final double right;
        public final double bottom;

        public Region(double left, double top, double right, double bottom) {
            if (!Double.isFinite(left) || !Double.isFinite(top) || !Double.isFinite(right)
                    || !Double.isFinite(bottom) || right <= left || bottom <= top) {
                throw new IllegalArgumentException("region");
            }
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        public Region translated(double dx, double dy) {
            return new Region(left + dx, top + dy, right + dx, bottom + dy);
        }

        /** Half-open intersection in the same coordinate system; no origin or scale conversion. */
        public Region clippedTo(Region viewport) {
            double x1 = Math.max(left, viewport.left), y1 = Math.max(top, viewport.top);
            double x2 = Math.min(right, viewport.right), y2 = Math.min(bottom, viewport.bottom);
            return x2 > x1 && y2 > y1 ? new Region(x1, y1, x2, y2) : null;
        }

        public boolean contains(Region other) {
            return left <= other.left && top <= other.top && right >= other.right && bottom >= other.bottom;
        }

        public double iou(Region other) {
            double width = Math.max(0, Math.min(right, other.right) - Math.max(left, other.left));
            double height = Math.max(0, Math.min(bottom, other.bottom) - Math.max(top, other.top));
            double intersection = width * height;
            double union = (right - left) * (bottom - top)
                    + (other.right - other.left) * (other.bottom - other.top) - intersection;
            return union > 0 ? intersection / union : 0;
        }

        private Region union(Region other) {
            return new Region(Math.min(left, other.left), Math.min(top, other.top),
                    Math.max(right, other.right), Math.max(bottom, other.bottom));
        }

        private boolean visible(Source source) {
            return right > 0 && bottom > 0 && left < source.width && top < source.height;
        }
    }

    /**
     * One source-to-source correspondence, valid only within its partition and support. Admitted
     * image correspondence is immutable: correcting it requires a new scene epoch, rather than
     * retaining identities/confirmations derived from the replaced correspondence.
     */
    public static final class Motion {
        public final long epoch;
        public final long from;
        public final long to;
        public final long partition;
        public final long revision;
        public final MotionAuthority authority;
        public final Region support;
        public final double dx;
        public final double dy;

        public Motion(long epoch, long from, long to, long partition, long revision,
                MotionAuthority authority, Region support, double dx, double dy) {
            if (epoch < 0 || from <= 0 || to <= from || revision < 0 || !Double.isFinite(dx) || !Double.isFinite(dy)) {
                throw new IllegalArgumentException("motion");
            }
            this.epoch = epoch;
            this.from = from;
            this.to = to;
            this.partition = partition;
            this.revision = revision;
            this.authority = Objects.requireNonNull(authority);
            this.support = Objects.requireNonNull(support);
            // Validate the inverse support as well; finite deltas alone can still overflow.
            support.translated(dx, dy);
            this.dx = dx;
            this.dy = dy;
        }
    }

    /**
     * One atomic source-pair result. Every piece shares its actual source/epoch/partition and
     * revision. Outside those supports is UNKNOWN, not zero motion. Admission bounds all pieces
     * and rejects overlapping source or destination domains before consuming any of them.
     */
    public static final class MotionPair {
        /** Hard result-construction ceiling; scene admission may enforce a smaller total budget. */
        public static final int MAXIMUM_PIECES = 64;
        public final long epoch, from, to, partition, revision;
        public final MotionAuthority authority;
        public final List<Motion> pieces;

        public MotionPair(List<Motion> pieces) {
            Objects.requireNonNull(pieces);
            int count = pieces.size();
            if (count < 1 || count > MAXIMUM_PIECES) throw new IllegalArgumentException("motion pair size");
            List<Motion> copied = new ArrayList<>(count);
            // A collection's toArray/iterator could allocate or traverse far beyond that count.
            for (int i = 0; i < count; i++) copied.add(Objects.requireNonNull(pieces.get(i)));
            if (pieces.size() != count) throw new IllegalArgumentException("motion pair changed during construction");
            Motion first = Objects.requireNonNull(copied.get(0));
            epoch = first.epoch; from = first.from; to = first.to;
            partition = first.partition; revision = first.revision; authority = first.authority;
            if (authority == MotionAuthority.EVENT_ESTIMATE && copied.size() != 1) {
                throw new IllegalArgumentException("event estimate must remain a singleton");
            }
            for (Motion piece : copied) {
                Objects.requireNonNull(piece);
                if (piece.epoch != epoch || piece.from != from || piece.to != to || piece.partition != partition
                        || piece.revision != revision || piece.authority != authority) {
                    throw new IllegalArgumentException("mixed motion pair identity");
                }
            }
            // Semantic duplicates are independent of producer list order and signed zero.
            copied.sort(Comparator.comparingDouble((Motion value) -> canonical(value.support.left))
                    .thenComparingDouble(value -> canonical(value.support.top))
                    .thenComparingDouble(value -> canonical(value.support.right))
                    .thenComparingDouble(value -> canonical(value.support.bottom))
                    .thenComparingDouble(value -> canonical(value.dx))
                    .thenComparingDouble(value -> canonical(value.dy)));
            this.pieces = Collections.unmodifiableList(copied);
        }

        private static double canonical(double value) { return value == 0 ? 0 : value; }
    }

    /** Detector or classified text evidence. No mutable legacy Detection is stored. */
    public static final class Observation {
        public final String category;
        public final long partition;
        public final long textGroup;
        public final Region region;
        public final float confidence;

        public Observation(String category, long partition, long textGroup, Region region, float confidence) {
            if (!Float.isFinite(confidence) || confidence < 0 || confidence > 1) {
                throw new IllegalArgumentException("confidence");
            }
            this.category = Objects.requireNonNull(category);
            this.partition = partition;
            this.textGroup = textGroup;
            this.region = Objects.requireNonNull(region);
            this.confidence = confidence;
        }
    }

    /** Quality may contradict only this explicitly inspected source-local category and region. */
    public static final class Inspection {
        public final String category;
        public final long partition;
        public final Region region;

        public Inspection(String category, long partition, Region region) {
            this.category = Objects.requireNonNull(category);
            this.partition = partition;
            this.region = Objects.requireNonNull(region);
        }
    }

    public static final class Batch {
        public final Source source;
        public final Lane lane;
        public final List<Observation> observations;
        public final List<Inspection> inspections;
        /** Complete latest OCR set for this partition, not quality-negative image evidence. */
        public final Long replacementTextPartition;

        public Batch(Source source, Lane lane, List<Observation> observations, List<Inspection> inspections) {
            this(source, lane, observations, inspections, null);
        }

        private Batch(Source source, Lane lane, List<Observation> observations,
                List<Inspection> inspections, Long replacementTextPartition) {
            this.source = Objects.requireNonNull(source);
            this.lane = Objects.requireNonNull(lane);
            this.observations = immutable(observations);
            this.inspections = immutable(inspections);
            this.replacementTextPartition = replacementTextPartition;
            if (lane != Lane.QUALITY && !inspections.isEmpty()) {
                throw new IllegalArgumentException("only quality can contradict");
            }
            if (replacementTextPartition != null) {
                if (lane != Lane.TEXT || source.kind != SourceKind.IMAGE) throw new IllegalArgumentException("image OCR source");
                Region bounds = new Region(0, 0, source.width, source.height);
                for (Observation observation : this.observations) {
                    if (observation.partition != replacementTextPartition || observation.textGroup == 0
                            || !bounds.contains(observation.region)) throw new IllegalArgumentException("image OCR geometry");
                }
            }
        }

        /** A failed, cancelled or truncated read must never use this complete-set factory. */
        public static Batch imageText(Source source, long partition, List<Observation> observations) {
            return new Batch(source, Lane.TEXT, observations, Collections.emptyList(), partition);
        }
    }

    /** Policies must be supplied by the runtime; this core does not retune product thresholds. */
    public static final class Policy {
        public final int maximumSources;
        public final int maximumTracks;
        public final int maximumCorrespondences;
        public final int maximumBatchItems;
        public final long transientMillis;
        public final long retainedMillis;
        public final double associationIou;
        public final int maximumMotionPathStates;
        public final int maximumMotionEdgeChecks;

        public Policy(int maximumSources, int maximumTracks, int maximumCorrespondences,
                int maximumBatchItems, long transientMillis,
                long retainedMillis, double associationIou) {
            this(maximumSources, maximumTracks, maximumCorrespondences, maximumBatchItems,
                    transientMillis, retainedMillis, associationIou,
                    defaultMotionStates(maximumSources, maximumCorrespondences),
                    defaultMotionChecks(maximumSources, maximumCorrespondences));
        }

        public Policy(int maximumSources, int maximumTracks, int maximumCorrespondences,
                int maximumBatchItems, long transientMillis, long retainedMillis, double associationIou,
                int maximumMotionPathStates, int maximumMotionEdgeChecks) {
            if (maximumSources < 2 || maximumTracks < 1 || transientMillis <= 0
                    || maximumCorrespondences < 1 || maximumBatchItems < 1
                    || retainedMillis < transientMillis || !Double.isFinite(associationIou)
                    || associationIou <= 0 || associationIou > 1
                    || maximumMotionPathStates < 1 || maximumMotionEdgeChecks < 1) {
                throw new IllegalArgumentException("policy");
            }
            this.maximumSources = maximumSources;
            this.maximumTracks = maximumTracks;
            this.maximumCorrespondences = maximumCorrespondences;
            this.maximumBatchItems = maximumBatchItems;
            this.transientMillis = transientMillis;
            this.retainedMillis = retainedMillis;
            this.associationIou = associationIou;
            this.maximumMotionPathStates = maximumMotionPathStates;
            this.maximumMotionEdgeChecks = maximumMotionEdgeChecks;
        }

        private static int defaultMotionStates(int sources, int edges) {
            return (int) Math.min(4096, Math.max(64, (long) sources * edges * 2));
        }

        private static int defaultMotionChecks(int sources, int edges) {
            return (int) Math.min(32768, Math.max(256, (long) defaultMotionStates(sources, edges) * edges));
        }
    }

    public static final class Mask {
        public final long identity;
        public final String category;
        public final long partition;
        public final long textGroup;
        public final boolean confirmed;
        public final boolean estimatedMotion;
        /** IMAGE appearance hint, distinct from event continuation and exact correspondence. */
        public final boolean imageEstimated;
        /** No physical localization bound is supplied by this scene's current input contracts. */
        public final boolean physicalErrorKnown = false;
        public final boolean nativeValidated;
        /** Null for IMAGE members; individually confirmed partial facts are not whole-tree authority. */
        public final NativeTextResult.Coverage nativeCoverage;
        public final boolean nativeNodeIdentityKnown;
        /** Accepted positive, not merely retained geometry. Does not itself admit a dwell charge. */
        public final boolean dwellPositive;
        /** A bounded hold at an older supported source, never correspondence to the requested basis. */
        public final boolean heldGeometry;
        public final Region displayRegion;
        /** Actual source whose coordinate basis produced displayRegion; not the sampling source. */
        public final Source geometrySource;
        /** Sampling uses this source and region, never the enclosing packet's newest source. */
        public final Source evidenceSource;
        public final Region evidenceRegion;

        private Mask(Track track, Projection projected, Source geometrySource, boolean heldGeometry,
                long latestFastSequence) {
            identity = track.identity;
            category = track.category;
            partition = track.partition;
            textGroup = track.textGroup;
            confirmed = track.confirmed;
            estimatedMotion = projected.estimated;
            imageEstimated = projected.imageEstimated;
            nativeValidated = track.nativeCandidate != null;
            nativeCoverage = nativeValidated ? track.nativeCoverage : null;
            nativeNodeIdentityKnown = nativeValidated && track.nativeCandidate.nodeKey != null;
            dwellPositive = track.negativeSources.isEmpty()
                    && (!nativeValidated || nativeCoverage == NativeTextResult.Coverage.COMPLETE_TREE)
                    && (track.latestLane != Lane.FAST || track.evidenceSource.sequence >= latestFastSequence);
            this.heldGeometry = heldGeometry;
            displayRegion = projected.region;
            this.geometrySource = geometrySource;
            evidenceSource = track.evidenceSource;
            evidenceRegion = track.evidenceRegion;
        }
    }

    /** A distinct admitted producer operation, not a lane result, redraw or image sequence alias. */
    public static final class EvidenceWitness {
        public final SourceKind kind;
        public final long producerIdentity;
        public final long sourceSequence;
        /** Only a typed owner-bound terminal FULL can qualify for a partial bridge. */
        private final Object nativeAccountingChain;

        private EvidenceWitness(SourceKind kind, long producerIdentity, long sourceSequence) {
            this(kind, producerIdentity, sourceSequence, null);
        }

        private EvidenceWitness(SourceKind kind, long producerIdentity, long sourceSequence, Object nativeAccountingChain) {
            this.kind = kind;
            this.producerIdentity = producerIdentity;
            this.sourceSequence = sourceSequence;
            this.nativeAccountingChain = nativeAccountingChain;
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof EvidenceWitness)) return false;
            EvidenceWitness value = (EvidenceWitness) other;
            return kind == value.kind && producerIdentity == value.producerIdentity;
        }

        @Override public int hashCode() { return 31 * kind.hashCode() + Long.hashCode(producerIdentity); }
    }

    /** Sanitized accepted evidence. No display projection, pixels, caption or provider node. */
    public static final class AcceptedSubject {
        public final long identity, partition, textGroup;
        public final String category;
        public final Source source;
        public final Region region;
        public final Lane evidenceLane;
        public final List<EvidenceWitness> witnesses;
        /** Separate from the two-QUALITY confirmation used exclusively for retention. */
        public final boolean confirmedForConsumption;
        public final NativeTextResult.Coverage nativeCoverage;
        public final boolean alreadyConsumed;
        public final long validUntilMillis;

        private AcceptedSubject(Track track, long validUntilMillis) {
            identity = track.identity; partition = track.partition; textGroup = track.textGroup;
            category = track.category; source = track.evidenceSource; region = track.evidenceRegion;
            evidenceLane = track.latestLane;
            // Intermediate paint never exposes even a previously completed acquisition's credit.
            List<EvidenceWitness> ordered = track.nativeFrame != null && track.nativeFrame.observationOrdinal > 0
                    ? new ArrayList<>() : new ArrayList<>(track.sourceWitnesses);
            ordered.sort((a, b) -> Long.compare(a.sourceSequence, b.sourceSequence));
            witnesses = immutable(ordered);
            nativeCoverage = track.nativeCandidate == null ? null : track.nativeCoverage;
            confirmedForConsumption = witnesses.size() == CONSUMPTION_SOURCE_COUNT
                    && (nativeCoverage == null || nativeCoverage == NativeTextResult.Coverage.COMPLETE_TREE);
            alreadyConsumed = track.evidenceConsumed;
            this.validUntilMillis = validUntilMillis;
        }
    }

    /** Explicit text grouping retains every member's geometry and effect provenance. */
    public static final class Group {
        public final Region bounds;
        public final List<Mask> members;

        private Group(List<Mask> members) {
            this.members = immutable(members);
            Region union = members.get(0).displayRegion;
            for (int i = 1; i < members.size(); i++) union = union.union(members.get(i).displayRegion);
            bounds = union;
        }
    }

    public static final class Snapshot {
        public final Source destination;
        public final List<Group> groups;
        public final int unresolvedTracks;
        public final int tentativeTracks;
        public final long revision;
        public final long capacityDrops;
        public final long sourceEvictions;
        /** Earliest retention change on the display clock; MAX_VALUE means no finite deadline. */
        public final long nextChangeAtMillis;
        /** Presentation-only duplicates; identities and confirmation are deliberately unchanged. */
        public final int redundantEstimatedMasks;

        private Snapshot(Source destination, List<Group> groups, int unresolved, int tentative,
                long revision, long capacityDrops, long sourceEvictions, long nextChangeAtMillis,
                int redundantEstimatedMasks) {
            this.destination = destination;
            this.groups = immutable(groups);
            unresolvedTracks = unresolved;
            tentativeTracks = tentative;
            this.revision = revision;
            this.capacityDrops = capacityDrops;
            this.sourceEvictions = sourceEvictions;
            this.nextChangeAtMillis = nextChangeAtMillis;
            this.redundantEstimatedMasks = redundantEstimatedMasks;
        }
    }

    private static final class Projection {
        final long source;
        final Region region;
        final boolean estimated;
        final boolean imageEstimated;

        Projection(long source, Region region, boolean estimated) {
            this(source, region, estimated, false);
        }

        Projection(long source, Region region, boolean estimated, boolean imageEstimated) {
            this.source = source;
            this.region = region;
            this.estimated = estimated;
            this.imageEstimated = imageEstimated;
        }
    }

    /** Separate support/path states are necessary: visiting a source once loses disjoint domains. */
    private static final class MotionPath {
        final long source;
        final Region support;
        final double dx, dy, roundoffX, roundoffY;
        final Set<Long> visited;
        MotionPath(long source, Region support, double dx, double dy,
                double roundoffX, double roundoffY, Set<Long> visited) {
            this.source = source; this.support = support; this.dx = dx; this.dy = dy;
            this.roundoffX = roundoffX; this.roundoffY = roundoffY; this.visited = visited;
        }
    }

    /** One aggregate budget for a whole result, including both endpoint domains and comparisons. */
    private final class MotionBudget {
        long states, checks;
        boolean state() { return ++states <= policy.maximumMotionPathStates; }
        boolean check() { return ++checks <= policy.maximumMotionEdgeChecks; }
    }

    private static final class Track {
        final long identity;
        final String category;
        final long partition;
        /** Co-occurring identities stay distinct even after their original frame is evicted. */
        final Set<Long> distinctFrom = new HashSet<>();
        final Set<Long> qualitySources = new HashSet<>();
        final Set<Long> negativeSources = new HashSet<>();
        final Set<EvidenceWitness> sourceWitnesses = new HashSet<>();
        long freshSourceSequence;
        boolean evidenceConsumed;
        long basis;
        Region region;
        Source evidenceSource;
        Region evidenceRegion;
        Lane latestLane;
        long textGroup;
        boolean fastVisible;
        boolean confirmed;
        NativeTextResult.Candidate nativeCandidate;
        NativeTextFrame nativeFrame;
        NativeTextResult.Coverage nativeCoverage;
        int nativeLine;
        long nativeValidUntilMillis;
        NativeCompleteHistory nativeCompleteHistory;
        boolean nativeCompletedHistoryRestored;

        Track(long identity, Batch batch, Observation observation) {
            this(identity, batch, observation, imageWitness(batch.source));
        }

        Track(long identity, Batch batch, Observation observation, EvidenceWitness witness) {
            this.identity = identity;
            category = observation.category;
            partition = observation.partition;
            update(batch, observation, witness);
        }

        void update(Batch batch, Observation observation) {
            update(batch, observation, imageWitness(batch.source));
        }

        void update(Batch batch, Observation observation, EvidenceWitness witness) {
            // Old/out-of-order completion may improve retention, but cannot become fresh input.
            if (batch.source.sequence > freshSourceSequence) {
                freshSourceSequence = batch.source.sequence;
                if (witness != null) addWitness(sourceWitnesses, witness);
            }
            if (batch.lane == Lane.QUALITY) addIndependent(qualitySources, batch.source.sequence);
            if (batch.lane != Lane.QUALITY) fastVisible = true;
            confirmed = confirmed || qualitySources.size() >= 2;
            if (evidenceSource == null || batch.source.sequence > evidenceSource.sequence
                    || (batch.source.sequence == evidenceSource.sequence && batch.lane == Lane.QUALITY
                    && latestLane != Lane.QUALITY)) {
                basis = batch.source.sequence;
                region = observation.region;
                evidenceSource = batch.source;
                evidenceRegion = observation.region;
                latestLane = batch.lane;
                textGroup = batch.lane == Lane.TEXT ? observation.textGroup : 0;
                negativeSources.clear();
            }
        }
    }

    /** Compact historical facts, not old frame/root leases or a second validity clock. */
    private static final class NativeCompleteHistory {
        final Object chain;
        final List<EvidenceWitness> witnesses;
        NativeCompleteHistory(Object chain, List<EvidenceWitness> witnesses) {
            if (witnesses.isEmpty() || witnesses.size() > CONSUMPTION_SOURCE_COUNT)
                throw new IllegalArgumentException("native completed history bound");
            this.chain = chain; this.witnesses = immutable(witnesses);
        }
    }

    public static final int CONSUMPTION_SOURCE_COUNT = 5;

    private static EvidenceWitness imageWitness(Source source) {
        return source.kind == SourceKind.IMAGE
                ? new EvidenceWitness(SourceKind.IMAGE, source.imageRequestIdentity, source.sequence) : null;
    }

    private static void addWitness(Set<EvidenceWitness> witnesses, EvidenceWitness witness) {
        witnesses.add(witness);
        if (witnesses.size() > CONSUMPTION_SOURCE_COUNT) {
            EvidenceWitness oldest = Collections.min(witnesses,
                    (a, b) -> Long.compare(a.sourceSequence, b.sourceSequence));
            witnesses.remove(oldest);
        }
    }

    private final Policy policy;
    private final LinkedHashMap<Long, Source> sources = new LinkedHashMap<>();
    private final Map<Long, Integer> pins = new HashMap<>();
    private final List<MotionPair> motions = new ArrayList<>();
    // Optional metadata has its own finite reservation, so hints cannot starve exact edges.
    // This is not another graph, source pin, pixel owner or canonical track basis.
    private final List<ImagePresentationEstimate> imagePresentations = new ArrayList<>();
    /** Bounded last constructed IMAGE pose, before display-only duplicate suppression. */
    private final Map<Long, Mask> constructedImageMasks = new HashMap<>();
    private long imageEligibilityTarget;
    private long nextNativeGroup;
    private final Map<Long, NativeTextFrame> nativeFrames = new HashMap<>();
    /** One scene-owned metadata pin per still-admitted native origin, separate from the caller's latest basis. */
    private final Map<Long, Source> nativeOrigins = new HashMap<>();
    private final List<Track> tracks = new ArrayList<>();
    private final List<Batch> qualityHistory = new ArrayList<>();
    private final Map<Long, Long> imageTextVersions = new HashMap<>();
    private final Set<String> delivered = new HashSet<>();
    private long epoch;
    private long highestSource;
    /** Complete accepted FAST results, including empty; regional QUALITY is not a viewport miss. */
    private long latestFastSequence;
    private long nextIdentity;
    private long revision;
    private long capacityDrops;
    private long sourceEvictions;
    private Snapshot ready;
    private boolean motionConflict;

    public SceneEngine(long epoch, Policy policy) {
        if (epoch < 0) throw new IllegalArgumentException("epoch");
        this.epoch = epoch;
        this.policy = Objects.requireNonNull(policy);
    }

    public synchronized void reset(long nextEpoch) {
        if (nextEpoch <= epoch) throw new IllegalArgumentException("epoch must advance");
        epoch = nextEpoch;
        highestSource = 0;
        latestFastSequence = 0;
        sources.clear();
        pins.clear();
        motions.clear();
        imagePresentations.clear();
        constructedImageMasks.clear();
        imageEligibilityTarget = 0;
        nativeFrames.clear();
        nativeOrigins.clear();
        tracks.clear();
        qualityHistory.clear();
        imageTextVersions.clear();
        delivered.clear();
        ready = null;
        motionConflict = false;
        capacityDrops = 0;
        sourceEvictions = 0;
        revision++;
    }

    /** False means bounded admission is unavailable, not that executing work should be cancelled. */
    public synchronized boolean register(Source source) {
        if (motionConflict || source.epoch != epoch) return false;
        Source existing = sources.get(source.sequence);
        if (existing != null) return existing.sameDescriptor(source);
        // An evicted sequence cannot be re-registered by an old callback or replayed input.
        if (source.sequence <= highestSource) return false;
        if (sources.size() >= policy.maximumSources) {
            Long candidate = null;
            for (Long sequence : sources.keySet()) {
                if (!pins.containsKey(sequence)) { candidate = sequence; break; }
            }
            if (candidate == null) return false;
            evict(candidate);
        }
        sources.put(source.sequence, source);
        highestSource = source.sequence;
        revision++;
        // Arrival of a raw capture does not replace ready coverage.
        return true;
    }

    public synchronized boolean pin(Source source) {
        Source existing = sources.get(source.sequence);
        if (existing == null || !existing.sameDescriptor(source)) return false;
        pins.put(source.sequence, pins.getOrDefault(source.sequence, 0) + 1);
        return true;
    }

    public synchronized boolean unpin(Source source) {
        Source existing = sources.get(source.sequence);
        // Closing old work after reset must not release a new session's same-numbered source.
        if (existing == null || !existing.sameDescriptor(source)) return false;
        Integer count = pins.get(source.sequence);
        if (count == null) throw new IllegalStateException("unowned source");
        if (count == 1) pins.remove(source.sequence); else pins.put(source.sequence, count - 1);
        return true;
    }

    public synchronized boolean motion(Motion motion) {
        return admitMotion(motion) == MotionAdmission.ADMITTED;
    }

    public synchronized boolean motion(MotionPair pair) {
        return admitMotion(pair) == MotionAdmission.ADMITTED;
    }

    public synchronized MotionAdmission admitMotion(Motion motion) {
        return admitMotion(new MotionPair(Collections.singletonList(Objects.requireNonNull(motion))));
    }

    /** No geometry/identity derived from conflicting authority survives until an explicit reset. */
    public synchronized MotionAdmission admitMotion(MotionPair pair) {
        Objects.requireNonNull(pair);
        Source from = sources.get(pair.from);
        Source to = sources.get(pair.to);
        if (motionConflict || pair.epoch != epoch || from == null || to == null || from.geometry != to.geometry
                || from.width != to.width || from.height != to.height) return MotionAdmission.INVALID;
        // Every existing motion edge describes image/event camera motion, not native-tree poses.
        // A native read must not become a transit node that rebases unrelated visual evidence.
        if (from.kind != SourceKind.IMAGE || to.kind != SourceKind.IMAGE) return MotionAdmission.INVALID;
        int existingIndex = -1;
        long pieces = 0;
        for (int i = 0; i < motions.size(); i++) {
            MotionPair old = motions.get(i);
            pieces += old.pieces.size();
            if (old.from == pair.from && old.to == pair.to && old.partition == pair.partition) existingIndex = i;
        }
        MotionPair old = existingIndex < 0 ? null : motions.get(existingIndex);
        if (old != null && old.authority == MotionAuthority.IMAGE_CORRESPONDENCE) {
            if (pair.authority != MotionAuthority.IMAGE_CORRESPONDENCE) return MotionAdmission.INVALID;
            if (pair.revision < old.revision) return MotionAdmission.STALE;
        } else if (old != null && old.authority == pair.authority && old.revision >= pair.revision) {
            return MotionAdmission.STALE;
        }
        if (pieces - (old == null ? 0 : old.pieces.size()) + pair.pieces.size() > policy.maximumCorrespondences) {
            return rejectedMotionCheck(MotionAdmission.CAPACITY);
        }
        MotionBudget budget = new MotionBudget();
        MotionAdmission checked = checkPairDomains(pair, budget);
        if (checked != MotionAdmission.ADMITTED) return checked;
        if (old != null && old.authority == MotionAuthority.IMAGE_CORRESPONDENCE) {
            boolean duplicate = old.pieces.size() == pair.pieces.size();
            for (int i = 0; duplicate && i < old.pieces.size(); i++) {
                if (!budget.check()) return rejectedMotionCheck(MotionAdmission.CHECK_BUDGET);
                duplicate = sameGeometry(old.pieces.get(i), pair.pieces.get(i));
            }
            if (duplicate) {
                // Advance only the stale-result watermark, never geometry, evidence age or identity.
                if (pair.revision > old.revision) motions.set(existingIndex, pair);
                return MotionAdmission.DUPLICATE;
            }
            for (Motion before : old.pieces) for (Motion after : pair.pieces) {
                if (!budget.check()) return rejectedMotionCheck(MotionAdmission.CHECK_BUDGET);
                if (before.dx == after.dx && before.dy == after.dy) continue;
                if (intersection(before.support, after.support) != null
                        || intersection(before.support.translated(before.dx, before.dy),
                                after.support.translated(after.dx, after.dy)) != null) {
                    return invalidateMotionAuthority();
                }
            }
            // Admitted geometry is immutable. A consistent support change cannot append another
            // domain or incrementally expose a physical result; it also cannot destroy valid state.
            return MotionAdmission.INVALID;
        }
        checked = checkMeasuredPair(pair, budget);
        if (checked != MotionAdmission.ADMITTED) return checked;
        if (existingIndex < 0) motions.add(pair); else motions.set(existingIndex, pair);
        revision++;
        mergeCorrespondingTracks();
        replayNegativeEvidence();
        return MotionAdmission.ADMITTED;
    }

    private MotionAdmission checkPairDomains(MotionPair pair, MotionBudget budget) {
        for (int i = 0; i < pair.pieces.size(); i++) {
            Motion first = pair.pieces.get(i);
            for (int j = i + 1; j < pair.pieces.size(); j++) {
                if (!budget.check()) return rejectedMotionCheck(MotionAdmission.CHECK_BUDGET);
                Motion second = pair.pieces.get(j);
                if (intersection(first.support, second.support) != null
                        || intersection(first.support.translated(first.dx, first.dy),
                                second.support.translated(second.dx, second.dy)) != null) return MotionAdmission.INVALID;
            }
        }
        return MotionAdmission.ADMITTED;
    }

    public synchronized boolean hasMotionConflict() { return motionConflict; }

    public synchronized PresentationAdmission admitPresentation(ImagePresentationEstimate estimate) {
        Objects.requireNonNull(estimate);
        if (motionConflict || estimate.epoch != epoch) return PresentationAdmission.INVALID;
        if (estimate.to < imageEligibilityTarget) return PresentationAdmission.STALE;
        Source from = sources.get(estimate.from), to = sources.get(estimate.to);
        if (motionConflict || estimate.epoch != epoch || from == null || to == null
                || from.kind != SourceKind.IMAGE || to.kind != SourceKind.IMAGE
                || from.geometry != to.geometry || from.width != to.width || from.height != to.height) {
            return PresentationAdmission.INVALID;
        }
        int oldIndex = -1, count = 0;
        for (int i = 0; i < imagePresentations.size(); i++) {
            ImagePresentationEstimate old = imagePresentations.get(i);
            count += old.pieces.size();
            if (old.sameKey(estimate)) oldIndex = i;
        }
        if (oldIndex >= 0) {
            ImagePresentationEstimate old = imagePresentations.get(oldIndex);
            if (estimate.revision < old.revision) return PresentationAdmission.STALE;
            if (!old.samePieces(estimate)) return PresentationAdmission.INVALID;
            if (estimate.revision > old.revision) imagePresentations.set(oldIndex, estimate);
            return PresentationAdmission.DUPLICATE;
        }
        if ((long) count + estimate.pieces.size() > Math.min(ImagePresentationEstimate.MAXIMUM_PIECES,
                policy.maximumCorrespondences)) return PresentationAdmission.CAPACITY;
        Region fromBounds = new Region(0, 0, from.width, from.height);
        Region toBounds = new Region(0, 0, to.width, to.height);
        for (int i = 0; i < estimate.pieces.size(); i++) {
            ImagePresentationEstimate.Piece first = estimate.pieces.get(i);
            if (!fromBounds.contains(first.support) || !toBounds.contains(first.destinationEnvelope)) {
                return PresentationAdmission.INVALID;
            }
            for (int j = i + 1; j < estimate.pieces.size(); j++) {
                ImagePresentationEstimate.Piece second = estimate.pieces.get(j);
                if (intersection(first.support, second.support) != null
                        || intersection(first.destinationEnvelope, second.destinationEnvelope) != null) {
                    return PresentationAdmission.INVALID;
                }
            }
        }
        imagePresentations.add(estimate);
        revision++;
        // Never associate, confirm, replay negatives, rebase Track or invalidate exact authority.
        return PresentationAdmission.ADMITTED;
    }

    public synchronized int presentationPieceCount() {
        int count = 0;
        for (ImagePresentationEstimate value : imagePresentations) count += value.pieces.size();
        return count;
    }

    /** Called only with an actual acquired image, never a generic highest-source/text sequence. */
    public synchronized boolean advanceImageTarget(long sequence) {
        Source selected = sources.get(sequence);
        if (sequence < imageEligibilityTarget || selected == null || selected.kind != SourceKind.IMAGE) return false;
        imageEligibilityTarget = sequence;
        if (imagePresentations.removeIf(estimate -> estimate.to < sequence)) revision++;
        return true;
    }

    public synchronized boolean hasConstructedImagePose() { return !constructedImageMasks.isEmpty(); }

    private MotionAdmission invalidateMotionAuthority() {
        motionConflict = true;
        tracks.clear(); motions.clear(); qualityHistory.clear(); delivered.clear(); ready = null;
        imagePresentations.clear(); constructedImageMasks.clear(); nativeFrames.clear();
        reconcileNativeOrigins();
        // Resource pins/descriptors remain owned until the runtime performs its own epoch reset.
        revision++;
        return MotionAdmission.CONFLICT;
    }

    private static boolean sameGeometry(Motion first, Motion second) {
        return first.dx == second.dx && first.dy == second.dy
                && first.support.left == second.support.left && first.support.top == second.support.top
                && first.support.right == second.support.right && first.support.bottom == second.support.bottom;
    }

    private static Region intersection(Region first, Region second) {
        double left = Math.max(first.left, second.left), top = Math.max(first.top, second.top);
        double right = Math.min(first.right, second.right), bottom = Math.min(first.bottom, second.bottom);
        return right <= left || bottom <= top ? null : new Region(left, top, right, bottom);
    }

    private MotionAdmission checkMeasuredPair(MotionPair candidate, MotionBudget budget) {
        if (candidate.authority != MotionAuthority.IMAGE_CORRESPONDENCE) return MotionAdmission.ADMITTED;
        List<Motion> graph = new ArrayList<>();
        for (MotionPair existing : motions) {
            if (existing.authority == MotionAuthority.IMAGE_CORRESPONDENCE) graph.addAll(existing.pieces);
        }
        graph.addAll(candidate.pieces);
        for (Motion piece : candidate.pieces) {
            MotionAdmission checked = checkMeasuredPaths(piece.from, piece.support, piece.partition, graph, budget);
            if (checked != MotionAdmission.ADMITTED) return checked;
            checked = checkMeasuredPaths(piece.to, piece.support.translated(piece.dx, piece.dy),
                    piece.partition, graph, budget);
            if (checked != MotionAdmission.ADMITTED) return checked;
        }
        return MotionAdmission.ADMITTED;
    }

    private MotionAdmission checkMeasuredPaths(long origin, Region support, long partition,
            List<Motion> graph, MotionBudget budget) {
        List<MotionPath> queue = new ArrayList<>();
        Map<Long, List<MotionPath>> reached = new HashMap<>();
        if (!budget.state()) return rejectedMotionCheck(MotionAdmission.CHECK_BUDGET);
        MotionPath initial = new MotionPath(origin, support, 0, 0, 0, 0, Collections.singleton(origin));
        queue.add(initial); reached.put(origin, new ArrayList<>(Collections.singletonList(initial)));
        for (int index = 0; index < queue.size(); index++) {
            MotionPath current = queue.get(index);
            for (Motion edge : graph) {
                if (!budget.check()) return rejectedMotionCheck(MotionAdmission.CHECK_BUDGET);
                if (edge.partition != partition) continue;
                boolean forward = edge.from == current.source;
                if (!forward && edge.to != current.source) continue;
                long next = forward ? edge.to : edge.from;
                double dx = forward ? edge.dx : -edge.dx, dy = forward ? edge.dy : -edge.dy;
                Region common;
                try {
                    Region edgeSupport = forward ? edge.support : edge.support.translated(edge.dx, edge.dy);
                    common = intersection(current.support, edgeSupport.translated(-current.dx, -current.dy));
                } catch (IllegalArgumentException unrepresentable) {
                    return rejectedMotionCheck(MotionAdmission.UNREPRESENTABLE);
                }
                if (common == null) continue;
                double nextX = current.dx + dx, nextY = current.dy + dy;
                double errorX = current.roundoffX + additionRoundoff(current.dx, dx, nextX);
                double errorY = current.roundoffY + additionRoundoff(current.dy, dy, nextY);
                if (!Double.isFinite(nextX) || !Double.isFinite(nextY)
                        || !Double.isFinite(errorX) || !Double.isFinite(errorY)) {
                    return rejectedMotionCheck(MotionAdmission.UNREPRESENTABLE);
                }
                List<MotionPath> prior = reached.get(next);
                if (prior != null) for (MotionPath other : prior) {
                    if (!budget.check()) return rejectedMotionCheck(MotionAdmission.CHECK_BUDGET);
                    if (intersection(common, other.support) == null) continue;
                    double boundX = errorX + other.roundoffX, boundY = errorY + other.roundoffY;
                    if (!Double.isFinite(boundX) || !Double.isFinite(boundY)) {
                        return rejectedMotionCheck(MotionAdmission.UNREPRESENTABLE);
                    }
                    // Compare every reached node, including inverse convergence and a return to
                    // origin. Numerical addition roundoff is not physical matcher uncertainty.
                    if (Math.abs(nextX - other.dx) > boundX || Math.abs(nextY - other.dy) > boundY) {
                        return invalidateMotionAuthority();
                    }
                }
                // A cyclic derived relation was checked above; revisiting it cannot add support.
                if (current.visited.contains(next)) continue;
                if (current.visited.size() >= policy.maximumSources || !budget.state()) {
                    return rejectedMotionCheck(MotionAdmission.CHECK_BUDGET);
                }
                Set<Long> visited = new HashSet<>(current.visited); visited.add(next);
                MotionPath derived = new MotionPath(next, common, nextX, nextY, errorX, errorY, visited);
                queue.add(derived);
                reached.computeIfAbsent(next, ignored -> new ArrayList<>()).add(derived);
            }
        }
        return MotionAdmission.ADMITTED;
    }

    private static double additionRoundoff(double first, double second, double result) {
        return 2 * (Math.ulp(first) + Math.ulp(second) + Math.ulp(result));
    }

    private MotionAdmission rejectedMotionCheck(MotionAdmission reason) {
        capacityDrops++;
        return reason;
    }

    /** Old-epoch, evicted-source and duplicate results are rejected without mutating evidence. */
    public synchronized boolean observe(Batch batch) {
        Source source = sources.get(batch.source.sequence);
        if (motionConflict || batch.source.epoch != epoch || source == null || source.kind != SourceKind.IMAGE
                || !source.sameDescriptor(batch.source)) return false;
        if ((long) batch.observations.size() + batch.inspections.size() > policy.maximumBatchItems) {
            capacityDrops++;
            return false;
        }
        if (batch.replacementTextPartition != null) {
            long partition = batch.replacementTextPartition;
            Long previous = imageTextVersions.get(partition);
            int otherTracks = 0;
            for (Track track : tracks) {
                if (track.nativeCandidate != null || track.latestLane != Lane.TEXT || track.partition != partition) otherTracks++;
            }
            if ((previous != null && previous >= source.sequence)
                    || (previous == null && imageTextVersions.size() >= policy.maximumTracks)
                    || (long) otherTracks + batch.observations.size() > policy.maximumTracks) {
                capacityDrops++;
                return false;
            }
        }
        String delivery = batch.source.sequence + ":" + batch.lane;
        if (!delivered.add(delivery)) return false;
        if (batch.lane == Lane.FAST) latestFastSequence = Math.max(latestFastSequence, source.sequence);
        if (batch.replacementTextPartition != null) {
            long partition = batch.replacementTextPartition;
            // Supersede the old OCR presentation atomically. This does not prove absence of an
            // old subject, authorize a motion edge, or contradict visual/native evidence.
            tracks.removeIf(track -> track.nativeCandidate == null && track.latestLane == Lane.TEXT
                    && track.partition == partition);
            imageTextVersions.put(partition, source.sequence);
        }
        Set<Long> matched = new HashSet<>();
        for (int index = 0; index < batch.observations.size(); index++) {
            Observation observation = batch.observations.get(index);
            if (contradictedLater(batch, observation)) continue;
            Track best = null;
            double bestIou = policy.associationIou;
            for (Track track : tracks) {
                if (matched.contains(track.identity) || !sameKind(track, observation, batch.lane)) continue;
                Projection projected = project(track.basis, source.sequence, track.partition, track.region, false);
                if (projected == null) continue;
                double overlap = projected.region.iou(observation.region);
                if (overlap >= bestIou) { best = track; bestIou = overlap; }
            }
            if (best == null) {
                best = new Track(++nextIdentity, batch, observation);
                tracks.add(best);
            } else {
                best.update(batch, observation);
            }
            // One batch observed these as separate subjects. Keep the relation between live
            // identities, not ephemeral source/lane array indices or evictable frame metadata.
            for (Track prior : tracks) {
                if (matched.contains(prior.identity)) {
                    best.distinctFrom.add(prior.identity);
                    prior.distinctFrom.add(best.identity);
                }
            }
            matched.add(best.identity);
        }
        if (batch.lane == Lane.QUALITY) {
            qualityHistory.add(batch);
        }
        mergeCorrespondingTracks();
        replayNegativeEvidence();
        if (tracks.size() > policy.maximumTracks && !nativeFrames.isEmpty()) {
            // Native publication is a complete set, never an individually evictable line subset.
            tracks.removeIf(track -> track.nativeCandidate != null);
            nativeFrames.clear();
            reconcileNativeOrigins();
            capacityDrops++;
        }
        trimTracks();
        pruneDistinctRelations();
        revision++;
        return true;
    }

    /** Native fallback preference supersedes OCR display sets, not visual or native evidence. */
    public synchronized void clearImageText() {
        boolean changed = tracks.removeIf(track -> track.nativeCandidate == null && track.latestLane == Lane.TEXT
                && imageTextVersions.containsKey(track.partition));
        if (changed) { pruneDistinctRelations(); revision++; }
    }

    /** Only a still-valid complete read can establish global OCR-suppression authority. */
    public synchronized boolean hasNativeTextCoverage(long partition, long nowMillis) {
        NativeTextFrame frame = nativeFrames.get(partition);
        if (frame == null || frame.read.coverage != NativeTextResult.Coverage.COMPLETE_TREE
                || frame.read.candidates.isEmpty()) return false;
        for (NativeTextResult.Candidate candidate : frame.read.candidates) {
            if (nowMillis > frame.candidateValidUntilMillis(candidate)) return false;
        }
        return true;
    }

    /**
     * Atomic full replacement or exact selected-node upsert. Success transfers one additional
     * caller-owned metadata pin; the scene separately retains each origin of unobserved live nodes.
     */
    public synchronized boolean admitNativeFrame(NativeTextFrame frame) {
        Objects.requireNonNull(frame);
        Source source = frame.source;
        if (motionConflict || source.epoch != epoch || source.kind != SourceKind.NATIVE_TEXT) return false;
        boolean partial = frame.read.coverage == NativeTextResult.Coverage.POSITIVE_NODES_ONLY;
        if (partial && frame.read.candidates.isEmpty()) return false;
        Set<String> selectedNodes = new HashSet<>();
        for (NativeTextResult.Candidate candidate : frame.read.candidates) {
            if (source.receivedAtMillis > frame.candidateValidUntilMillis(candidate)) return false;
            if (candidate.nodeKey != null) selectedNodes.add(candidate.nodeKey);
        }
        NativeTextFrame previous = nativeFrames.get(frame.partition);
        if (previous != null && !nativeFollows(frame, previous)) return false;
        if (previous != null && frame.read.readMode == NativeTextResult.ReadMode.ADMITTED_NODE_COHORT_RECLASSIFY
                && frame.observationOrdinal == 0 && frame.followsObservation(previous)) {
            // Parent inventory survives track expiry. Partial authority is not permission to
            // replace the already-observed selected node with two different addresses.
            for (NativeTextResult.Candidate observed : previous.read.candidates)
                if (!selectedNodes.contains(observed.nodeKey)) return false;
        }
        int retained = 0;
        List<Track> old = new ArrayList<>();
        for (Track track : tracks) {
            if (track.nativeCandidate != null && track.partition == frame.partition) {
                if (!nativeFollows(frame, track.nativeFrame)) return false;
                if (frame.followsObservation(track.nativeFrame)) {
                    if ((!partial || (frame.read.readMode == NativeTextResult.ReadMode.ADMITTED_NODE_COHORT_RECLASSIFY
                            && frame.observationOrdinal == 0)) && !selectedNodes.contains(track.nativeCandidate.nodeKey)) return false;
                    for (NativeTextResult.Candidate candidate : frame.read.candidates) {
                        if (!Objects.equals(candidate.nodeKey, track.nativeCandidate.nodeKey)) continue;
                        if (candidate.validatedAtMillis < track.nativeCandidate.validatedAtMillis
                                || (candidate.validatedAtMillis == track.nativeCandidate.validatedAtMillis
                                    && (!candidate.sameContent(track.nativeCandidate) || !candidate.samePose(track.nativeCandidate))))
                            return false;
                    }
                }
                if (partial && (!compatibleCanvas(track.evidenceSource, source)
                        || track.evidenceSource.windowId != source.windowId
                        || !track.evidenceSource.protectionScope.equals(source.protectionScope))) return false;
                if (!partial || selectedNodes.contains(track.nativeCandidate.nodeKey)) old.add(track);
                else retained++;
            } else retained++;
        }
        if (frame.read.regionCount() > policy.maximumBatchItems
                || (long) retained + frame.read.regionCount() > policy.maximumTracks
                || (previous == null && nativeFrames.size() >= policy.maximumSources)) {
            capacityDrops++; return false;
        }
        // All frame/provenance/shape/track-capacity checks precede registration and possible eviction.
        if (!register(source) || !pin(source)) return false;
        if (!frame.read.candidates.isEmpty() && !nativeOrigins.containsKey(source.sequence)) {
            if (!pin(source)) throw new IllegalStateException("admitted native origin lost");
            nativeOrigins.put(source.sequence, source);
        }
        List<Track> replacement = new ArrayList<>();
        Set<Long> used = new HashSet<>();
        for (NativeTextResult.Candidate candidate : frame.read.candidates) {
            long group = 0;
            boolean consumedGroup = false;
            for (Track track : old) {
                if (nativeMatches(track, candidate, source)) {
                    group = track.textGroup; consumedGroup |= track.evidenceConsumed;
                }
            }
            if (group == 0) group = ++nextNativeGroup;
            for (int line = 0; line < candidate.regions.size(); line++) {
                Observation observation = new Observation(candidate.category, frame.partition, group,
                        candidate.regions.get(line), candidate.confidence);
                Track match = null;
                for (Track track : old) {
                    if (!used.contains(track.identity) && track.nativeLine == line
                            && nativeMatches(track, candidate, source)) { match = track; break; }
                }
                Batch batch = new Batch(source, Lane.TEXT, Collections.singletonList(observation), Collections.emptyList());
                EvidenceWitness witness = frame.observationOrdinal == 0 && frame.requestIdentity > 0
                        ? new EvidenceWitness(SourceKind.NATIVE_TEXT, frame.requestIdentity, source.sequence,
                            frame.read.coverage == NativeTextResult.Coverage.COMPLETE_TREE
                                && frame.read.readMode == NativeTextResult.ReadMode.FULL_DISCOVERY
                                    ? frame.accountingChainFor(candidate) : null) : null;
                NativeCompleteHistory completed = null;
                if (match == null) match = new Track(++nextIdentity, batch, observation, witness);
                else {
                    // Preserve ordinary public FULL IDs, but never qualify credit across an expired gap.
                    if (expired(match, source.receivedAtMillis)) demoteNativeAccounting(match);
                    completed = nativeCompleteContinuation(match, frame, candidate);
                    if (!nativeHistoryContinues(match, frame)) match.sourceWitnesses.clear();
                    if (completed != null && frame.observationOrdinal == 0
                            && frame.read.coverage == NativeTextResult.Coverage.COMPLETE_TREE) {
                        match.sourceWitnesses.clear(); match.sourceWitnesses.addAll(completed.witnesses);
                    }
                    match.update(batch, observation, witness);
                }
                match.nativeCompleteHistory = frame.observationOrdinal == 0
                        && frame.read.coverage == NativeTextResult.Coverage.COMPLETE_TREE ? null : completed;
                match.nativeCompletedHistoryRestored = completed != null && frame.observationOrdinal == 0
                        && frame.read.coverage == NativeTextResult.Coverage.COMPLETE_TREE;
                match.nativeCandidate = candidate; match.nativeLine = line;
                match.nativeFrame = frame;
                match.nativeCoverage = frame.read.coverage;
                match.evidenceConsumed |= consumedGroup;
                match.nativeValidUntilMillis = frame.candidateValidUntilMillis(candidate);
                match.distinctFrom.clear(); match.qualitySources.clear(); match.negativeSources.clear();
                used.add(match.identity); replacement.add(match);
            }
        }
        tracks.removeAll(old);
        tracks.addAll(replacement);
        nativeFrames.put(frame.partition, frame);
        reconcileNativeOrigins();
        pruneDistinctRelations();
        revision++;
        return true;
    }

    private NativeCompleteHistory nativeCompleteContinuation(Track track, NativeTextFrame next,
            NativeTextResult.Candidate candidate) {
        Object chain = next.accountingChainFor(candidate);
        if (chain == null || expired(track, next.source.receivedAtMillis)
                || !next.sharesAccountingChain(track.nativeFrame)
                || next.read.hardGeneration != track.nativeFrame.read.hardGeneration
                || candidate.validatedAtMillis < track.nativeCandidate.validatedAtMillis
                || (track.nativeFrame.observationOrdinal > 0 && !next.followsObservation(track.nativeFrame))
                || (next.observationOrdinal == 0 && next.read.readMode == NativeTextResult.ReadMode.FULL_DISCOVERY
                    && next.read.coverage == NativeTextResult.Coverage.POSITIVE_NODES_ONLY)) return null;
        if (track.nativeCompleteHistory != null)
            return track.nativeCompleteHistory.chain == chain ? track.nativeCompleteHistory : null;
        // Existing in-progress FULL accounting stays on its original path. Only admitted-address
        // renewals need a separate COMPLETE-only snapshot alongside their partial bookkeeping.
        if (next.read.readMode == NativeTextResult.ReadMode.FULL_DISCOVERY || track.nativeFrame.observationOrdinal != 0
                || track.nativeCoverage != NativeTextResult.Coverage.COMPLETE_TREE
                || track.nativeFrame.read.readMode != NativeTextResult.ReadMode.FULL_DISCOVERY) return null;
        List<EvidenceWitness> completed = new ArrayList<>();
        for (EvidenceWitness witness : track.sourceWitnesses)
            if (witness.nativeAccountingChain == chain) completed.add(witness);
        return completed.isEmpty() ? null : new NativeCompleteHistory(chain, completed);
    }

    private static void demoteNativeAccounting(Track track) {
        List<EvidenceWitness> ordinary = new ArrayList<>();
        for (EvidenceWitness witness : track.sourceWitnesses)
            ordinary.add(new EvidenceWitness(witness.kind, witness.producerIdentity, witness.sourceSequence));
        track.sourceWitnesses.clear(); track.sourceWitnesses.addAll(ordinary);
        track.nativeCompleteHistory = null;
    }

    /** A failed owner terminal must not leave restored hidden facts on ordinary FULL history. */
    synchronized void discardRestoredNativeAccounting(long partition, long parentIdentity) {
        boolean changed = false;
        for (Track track : tracks) {
            if (track.partition == partition && track.nativeFrame != null
                    && track.nativeFrame.requestIdentity == parentIdentity && track.nativeCompletedHistoryRestored) {
                track.sourceWitnesses.clear(); track.nativeCompleteHistory = null;
                track.nativeCompletedHistoryRestored = false; changed = true;
            }
        }
        if (changed) revision++;
    }

    /** Preserve only completed history through one exact in-progress FULL, never partial credit. */
    private boolean nativeHistoryContinues(Track track, NativeTextFrame next) {
        if (next.observationOrdinal > 0) {
            return !expired(track, next.source.receivedAtMillis)
                    && (track.nativeCoverage == NativeTextResult.Coverage.COMPLETE_TREE
                        || next.followsObservation(track.nativeFrame));
        }
        if (track.nativeFrame.observationOrdinal > 0) {
            return !expired(track, next.source.receivedAtMillis)
                    && next.read.coverage == NativeTextResult.Coverage.COMPLETE_TREE
                    && next.followsObservation(track.nativeFrame);
        }
        // Ordinary terminal partials retain their conservative, coverage-changing reset rule.
        return track.nativeCoverage == next.read.coverage;
    }

    private static boolean nativeFollows(NativeTextFrame frame, NativeTextFrame previous) {
        if (previous == null || frame.source.sequence <= previous.source.sequence) return false;
        // Preserve nonoverlapping legacy admission, including conservative producer deduplication.
        // Only an overlapping interval needs the new exact-parent observation capability.
        return frame.source.readStartedAtMillis >= previous.source.readFinishedAtMillis || frame.followsObservation(previous);
    }

    private static boolean nativeMatches(Track track, NativeTextResult.Candidate candidate, Source source) {
        NativeTextResult.Candidate old = track.nativeCandidate;
        if (old == null || !old.sameContent(candidate) || track.evidenceSource.windowId != source.windowId
                || !compatibleCanvas(track.evidenceSource, source)
                || !track.evidenceSource.protectionScope.equals(source.protectionScope)) return false;
        if (old.nodeKey != null) return old.nodeKey.equals(candidate.nodeKey);
        return candidate.nodeKey == null && old.samePose(candidate);
    }

    public synchronized void clearNative(long partition) {
        boolean removed = tracks.removeIf(track -> track.nativeCandidate != null && track.partition == partition);
        if (nativeFrames.remove(partition) != null || removed) revision++;
        reconcileNativeOrigins();
        pruneDistinctRelations();
    }

    /** A hard content fence retires unconsumed confirmation authority, not past charges or paint age. */
    public synchronized void fenceNativeEvidence(long partition) {
        boolean changed = false;
        for (Track track : tracks) {
            if (track.nativeCandidate != null && track.partition == partition
                    && (!track.sourceWitnesses.isEmpty() || track.nativeCompleteHistory != null)) {
                track.sourceWitnesses.clear(); track.nativeCompleteHistory = null; changed = true;
            }
        }
        if (changed) revision++;
    }

    private void reconcileNativeOrigins() {
        Set<Long> live = new HashSet<>();
        for (Track track : tracks) if (track.nativeCandidate != null) live.add(track.evidenceSource.sequence);
        for (Iterator<Map.Entry<Long, Source>> iterator = nativeOrigins.entrySet().iterator(); iterator.hasNext();) {
            Map.Entry<Long, Source> entry = iterator.next();
            if (!live.contains(entry.getKey())) { unpin(entry.getValue()); iterator.remove(); }
        }
    }

    /** Metadata only, bounded by source capacity and the still-admitted native set. */
    public synchronized Set<Source> activeNativeOrigins() {
        return Collections.unmodifiableSet(new HashSet<>(nativeOrigins.values()));
    }

    /** Prospective optional-source reservation; refusing a replacement never releases old pins. */
    public synchronized Set<Source> nativeOriginsRetainedBy(NativeTextFrame replacement) {
        Set<String> selected = new HashSet<>();
        for (NativeTextResult.Candidate candidate : replacement.read.candidates) if (candidate.nodeKey != null) selected.add(candidate.nodeKey);
        Set<Source> retained = new HashSet<>();
        for (Track track : tracks) {
            if (track.nativeCandidate == null) continue;
            boolean replaced = track.partition == replacement.partition
                    && (replacement.read.coverage == NativeTextResult.Coverage.COMPLETE_TREE
                        || selected.contains(track.nativeCandidate.nodeKey));
            if (!replaced) retained.add(track.evidenceSource);
        }
        return Collections.unmodifiableSet(retained);
    }

    /** Current canonical positive inventory at one source; the accepted input is only its trigger. */
    synchronized List<AcceptedSubject> acceptedSubjects(Source source, long nowMillis) {
        List<AcceptedSubject> accepted = new ArrayList<>();
        if (motionConflict || source.epoch != epoch) return immutable(accepted);
        for (Track track : tracks) {
            if (!track.evidenceSource.sameDescriptor(source) || !track.negativeSources.isEmpty()
                    || expired(track, nowMillis) || !track.evidenceRegion.visible(source)) continue;
            accepted.add(new AcceptedSubject(track, evidenceValidUntil(track)));
        }
        return immutable(accepted);
    }

    /** Atomic validation then consumption; removed aliases and changed sources never resolve. */
    synchronized List<AcceptedSubject> consumeEvidence(Source source, List<AcceptedSubject> subjects, long nowMillis) {
        if (motionConflict || source.epoch != epoch) return null;
        List<Track> selected = new ArrayList<>();
        List<AcceptedSubject> consumed = new ArrayList<>();
        for (AcceptedSubject subject : subjects) {
            Track current = null;
            for (Track track : tracks) if (track.identity == subject.identity) { current = track; break; }
            if (current == null || !current.evidenceSource.sameDescriptor(source)
                    || subject.source != current.evidenceSource || !current.negativeSources.isEmpty()
                    || current.evidenceRegion != subject.region || current.latestLane != subject.evidenceLane
                    || current.textGroup != subject.textGroup || !current.evidenceRegion.visible(source)
                    || nowMillis > subject.validUntilMillis || expired(current, nowMillis)) return null;
            if (subject.confirmedForConsumption && !subject.alreadyConsumed && !current.evidenceConsumed) {
                if (current.sourceWitnesses.size() != CONSUMPTION_SOURCE_COUNT
                        || (current.nativeCandidate != null
                            && current.nativeCoverage != NativeTextResult.Coverage.COMPLETE_TREE)) return null;
                selected.add(current); consumed.add(subject);
            }
        }
        for (Track track : selected) track.evidenceConsumed = true;
        return immutable(consumed);
    }

    private long evidenceValidUntil(Track track) {
        if (track.nativeCandidate != null) return track.nativeValidUntilMillis;
        long hold = track.confirmed ? policy.retainedMillis : policy.transientMillis;
        long received = track.evidenceSource.receivedAtMillis;
        return received >= Long.MAX_VALUE - hold ? Long.MAX_VALUE : received + hold;
    }

    /** Only this method constructs render geometry. Unknown correspondence stays explicit. */
    public synchronized Snapshot snapshot(long destination, long nowMillis, boolean allowEventEstimates) {
        return snapshot(destination, nowMillis, allowEventEstimates, Collections.emptyMap());
    }

    /**
     * Canvas geometry and per-partition image bases are independent of detector completion.
     * Only the runtime selects these explicit bases; every member still needs its own supported
     * correspondence. An absent edge never becomes a zero-motion map or a sampling substitution.
     */
    public synchronized Snapshot snapshot(long destination, long nowMillis, boolean allowEventEstimates,
            Map<Long, Long> partitionBases) {
        return snapshot(destination, nowMillis, allowEventEstimates, partitionBases, 0);
    }

    /** Actual acquired image target is an eligibility fence, NOT a global rendering basis. */
    public synchronized Snapshot snapshot(long destination, long nowMillis, boolean allowEventEstimates,
            Map<Long, Long> partitionBases, long imageTarget) {
        Source source = sources.get(destination);
        if (source == null) throw new IllegalArgumentException("unknown destination");
        Source target = imageTarget == 0 ? null : sources.get(imageTarget);
        if (target != null && target.kind != SourceKind.IMAGE) {
            throw new IllegalArgumentException("native source is not an image target");
        }
        if (target != null && (target.epoch != source.epoch || target.geometry != source.geometry
                || target.width != source.width || target.height != source.height)) {
            throw new IllegalArgumentException("incompatible image presentation target");
        }
        Map<Long, Long> bases = new HashMap<>(Objects.requireNonNull(partitionBases));
        Map<Long, Source> selectedSources = new HashMap<>();
        if (bases.size() > policy.maximumTracks) throw new IllegalArgumentException("too many render bases");
        for (Long selected : bases.values()) {
            Source basis = sources.get(selected);
            if (basis == null || basis.kind != SourceKind.IMAGE || basis.epoch != source.epoch || basis.geometry != source.geometry
                    || basis.width != source.width || basis.height != source.height) {
                throw new IllegalArgumentException("incompatible render basis");
            }
        }
        for (Map.Entry<Long, Long> entry : bases.entrySet()) selectedSources.put(entry.getKey(), sources.get(entry.getValue()));
        // Reject every invalid input before retiring hints or changing the ready packet.
        if (imageTarget != 0) advanceImageTarget(imageTarget);
        if (target != null && target.sequence < imageEligibilityTarget) target = null;
        if (motionConflict) {
            ready = new Snapshot(source, Collections.emptyList(), 0, 0, revision, capacityDrops,
                    sourceEvictions, Long.MAX_VALUE, 0);
            return ready;
        }
        // An explicit carried basis cannot force fresher visible geometry backward, even when
        // an inverse estimate/image path exists. Tentative optional quality cannot move FAST.
        for (Track track : tracks) {
            if (track.nativeCandidate != null) continue;
            Source carried = selectedSources.get(track.partition);
            Source direct = track.evidenceSource;
            if (carried != null && (track.fastVisible || track.confirmed)
                    && !expired(track, nowMillis) && direct.sequence > carried.sequence
                    && direct.epoch == source.epoch && direct.geometry == source.geometry
                    && direct.width == source.width && direct.height == source.height) {
                selectedSources.put(track.partition, direct);
            }
        }
        List<Mask> masks = new ArrayList<>();
        int unresolved = 0;
        int tentative = 0;
        long nextChangeAt = Long.MAX_VALUE;
        for (Iterator<Track> iterator = tracks.iterator(); iterator.hasNext();) {
            Track track = iterator.next();
            long hold = track.confirmed ? policy.retainedMillis : policy.transientMillis;
            if (expired(track, nowMillis)) {
                iterator.remove();
                revision++;
                continue;
            }
            // Retention includes the exact hold boundary. The next millisecond is the first
            // invalid display time. Saturate instead of wrapping extreme test/source clocks.
            long received = track.evidenceSource.receivedAtMillis;
            long deadline = track.nativeCandidate != null
                    ? (track.nativeValidUntilMillis == Long.MAX_VALUE ? Long.MAX_VALUE : track.nativeValidUntilMillis + 1)
                    : (received >= Long.MAX_VALUE - hold ? Long.MAX_VALUE : received + hold + 1);
            nextChangeAt = Math.min(nextChangeAt, deadline);
            if (!track.fastVisible && !track.confirmed) { tentative++; continue; }
            Source selected = track.nativeCandidate != null ? track.evidenceSource
                    : selectedSources.getOrDefault(track.partition, source);
            // Direct geometry survives graph-node eviction. Reusing that immutable observation
            // is not an invented zero-motion edge from the track's eviction-rebased coordinates.
            boolean optionalImageBasis = track.nativeCandidate == null
                    && selectedSources.containsKey(track.partition) && selected.sequence != source.sequence;
            Mask mask = projectedMask(track, selected, allowEventEstimates && !optionalImageBasis, false);
            if (optionalImageBasis) {
                mask = supportedOrHeldMask(track, source, selected, mask);
            }
            if (track.nativeCandidate == null && imageTarget != 0) mask = imagePresentedMask(track, source, target, mask);
            if (mask == null) { unresolved++; continue; }
            if (mask.imageEstimated) constructedImageMasks.put(mask.identity, mask);
            else constructedImageMasks.remove(mask.identity);
            if (mask.displayRegion.visible(source)) masks.add(mask);
        }
        Set<Long> livingImageMembers = new HashSet<>();
        for (Track track : tracks) livingImageMembers.add(track.identity);
        constructedImageMasks.keySet().retainAll(livingImageMembers);
        int redundantEstimated = 0;
        List<Mask> supported = new ArrayList<>(masks);
        for (Iterator<Mask> iterator = masks.iterator(); iterator.hasNext();) {
            Mask estimate = iterator.next();
            if (!estimate.estimatedMotion) continue;
            for (Mask direct : supported) {
                if (!direct.estimatedMotion && direct.evidenceSource.sequence > estimate.evidenceSource.sequence
                        && direct.evidenceSource.kind == estimate.evidenceSource.kind
                        && direct.geometrySource.sequence == estimate.geometrySource.sequence
                        && direct.partition == estimate.partition && direct.textGroup == estimate.textGroup
                        && direct.category.equals(estimate.category)
                        && direct.displayRegion.contains(estimate.displayRegion)
                        && estimate.displayRegion.contains(direct.displayRegion)) {
                    iterator.remove(); redundantEstimated++; break;
                }
            }
        }
        List<List<Mask>> grouped = new ArrayList<>();
        for (Mask mask : masks) {
            List<Mask> group = null;
            if (mask.textGroup != 0) {
                for (List<Mask> candidate : grouped) {
                    Mask first = candidate.get(0);
                    if (first.textGroup == mask.textGroup && first.partition == mask.partition
                            && first.evidenceSource.kind == mask.evidenceSource.kind
                            && first.category.equals(mask.category)) { group = candidate; break; }
                }
            }
            if (group == null) { group = new ArrayList<>(); grouped.add(group); }
            group.add(mask);
        }
        List<Group> groups = new ArrayList<>();
        for (List<Mask> group : grouped) groups.add(new Group(group));
        ready = new Snapshot(source, groups, unresolved, tentative, revision, capacityDrops,
                sourceEvictions, nextChangeAt, redundantEstimated);
        reconcileNativeOrigins();
        pruneDistinctRelations();
        return ready;
    }

    /** No-new-image differs from a new unmatched image. Immutable ready coverage is not cleared. */
    public synchronized Snapshot ready() { return ready; }
    /** Immutable descriptor only; this does not borrow or expose captured pixel ownership. */
    public synchronized Source source(long sequence) { return sources.get(sequence); }
    public synchronized Set<Long> activePartitions() {
        Set<Long> partitions = new HashSet<>();
        for (Track track : tracks) partitions.add(track.partition);
        return Collections.unmodifiableSet(partitions);
    }
    /** Pixel render bases must not be kept alive by a coincident native partition number. */
    public synchronized Set<Long> activeImagePartitions() {
        Set<Long> partitions = new HashSet<>();
        for (Track track : tracks) if (track.nativeCandidate == null) partitions.add(track.partition);
        return Collections.unmodifiableSet(partitions);
    }
    public synchronized int sourceCount() { return sources.size(); }
    public synchronized int trackCount() { return tracks.size(); }

    private Mask projectedMask(Track track, Source destination, boolean estimates, boolean held) {
        Projection projected = destination.sequence == track.evidenceSource.sequence
                ? new Projection(destination.sequence, track.evidenceRegion, false)
                : project(track.basis, destination.sequence, track.partition, track.region, estimates);
        return projected == null ? null : new Mask(track, projected, destination, held, latestFastSequence);
    }

    private Mask supportedOrHeldMask(Track track, Source canvas, Source requested, Mask mapped) {
        Mask previous = null;
        // ready is the last constructed source-local snapshot, not a compositor-committed frame.
        // Runtime presentation and render/tap leases still have their own admission fences.
        if (ready != null && ready.destination.sameDescriptor(canvas)) {
            for (Group group : ready.groups) for (Mask value : group.members) {
                if (value.identity == track.identity && value.evidenceSource == track.evidenceSource
                        && value.evidenceRegion == track.evidenceRegion && !value.estimatedMotion) previous = value;
            }
        }
        if (mapped != null && (previous == null || mapped.geometrySource.sequence >= previous.geometrySource.sequence)) return mapped;
        Mask best = previous == null ? null : new Mask(track,
                new Projection(previous.geometrySource.sequence, previous.displayRegion, false), previous.geometrySource,
                true, latestFastSequence);
        // A map may precede the first detector result, so no previous snapshot is required.
        // Every newer candidate still needs its own fully supported measured path for this member.
        List<Source> candidates = new ArrayList<>(sources.values());
        candidates.sort((first, second) -> Long.compare(second.sequence, first.sequence));
        for (Source candidate : candidates) {
            if (candidate.sequence > requested.sequence || candidate.sequence < canvas.sequence
                    || (best != null && candidate.sequence <= best.geometrySource.sequence)
                    || candidate.epoch != canvas.epoch || candidate.geometry != canvas.geometry
                    || candidate.width != canvas.width || candidate.height != canvas.height) continue;
            Mask held = projectedMask(track, candidate, false, true);
            if (held != null) { best = held; break; }
        }
        return best;
    }

    private boolean expired(Track track, long nowMillis) {
        if (track.nativeCandidate != null) return nowMillis > track.nativeValidUntilMillis;
        long hold = track.confirmed ? policy.retainedMillis : policy.transientMillis;
        return nowMillis > track.evidenceSource.receivedAtMillis
                && nowMillis - track.evidenceSource.receivedAtMillis > hold;
    }

    private Mask imagePresentedMask(Track track, Source canvas, Source target, Mask existing) {
        Mask previous = constructedImageMasks.get(track.identity);
        if (previous != null && (!compatibleCanvas(previous.evidenceSource, canvas)
                || previous.evidenceSource != track.evidenceSource
                || previous.evidenceRegion != track.evidenceRegion)) {
            constructedImageMasks.remove(track.identity);
            previous = null;
        }
        ImagePresentationEstimate.Piece applicable = null;
        if (target != null && target.sequence >= track.evidenceSource.sequence) {
            for (ImagePresentationEstimate estimate : imagePresentations) {
                if (estimate.from != track.evidenceSource.sequence || estimate.to != target.sequence
                        || estimate.partition != track.partition) continue;
                for (ImagePresentationEstimate.Piece piece : estimate.pieces) {
                    if (piece.support.contains(track.evidenceRegion)) applicable = piece;
                }
            }
            // A raw target alone is not permission to select an unrequested hard basis.
            if (applicable != null || previous != null) {
                Mask exact = projectedMask(track, target, false, false);
                if (exact != null) return exact;
            }
            if (applicable != null) {
                try {
                    return new Mask(track, new Projection(target.sequence,
                            applicable.envelope(track.evidenceRegion), true, true), target, false, latestFastSequence);
                } catch (IllegalArgumentException unrepresentable) { /* Explicit unknown. */ }
            }
        }
        // Hold a constructed IMAGE packet without inventing correspondence to the new canvas.
        // Metadata may outlive graph-node eviction; it owns no extra pixels and renews no age.
        if (previous != null && (existing == null || existing.estimatedMotion
                || existing.geometrySource.sequence <= previous.geometrySource.sequence)) {
            return new Mask(track, new Projection(previous.geometrySource.sequence,
                    previous.displayRegion, true, true), previous.geometrySource, true, latestFastSequence);
        }
        return existing;
    }

    private static boolean compatibleCanvas(Source first, Source second) {
        return first.epoch == second.epoch && first.geometry == second.geometry
                && first.width == second.width && first.height == second.height;
    }

    private Projection project(long from, long to, long partition, Region region, boolean estimates) {
        Projection measured = search(from, to, partition, region, false);
        return measured != null || !estimates ? measured : search(from, to, partition, region, true);
    }

    private Projection search(long from, long to, long partition, Region region, boolean estimates) {
        if (!sources.containsKey(from) || !sources.containsKey(to)) return null;
        List<Projection> queue = new ArrayList<>();
        Set<Long> visited = new HashSet<>();
        queue.add(new Projection(from, region, false));
        visited.add(from);
        for (int index = 0; index < queue.size(); index++) {
            Projection current = queue.get(index);
            if (current.source == to) return current;
            for (MotionPair pair : motions) for (Motion motion : pair.pieces) {
                if (motion.partition != partition
                        || (!estimates && motion.authority != MotionAuthority.IMAGE_CORRESPONDENCE)) continue;
                boolean forward = motion.from == current.source;
                if (!forward && motion.to != current.source) continue;
                long next = forward ? motion.to : motion.from;
                if (visited.contains(next)) continue;
                Region support = forward ? motion.support : motion.support.translated(motion.dx, motion.dy);
                if (!support.contains(current.region)) continue;
                Region moved;
                try {
                    moved = current.region.translated(forward ? motion.dx : -motion.dx,
                            forward ? motion.dy : -motion.dy);
                } catch (IllegalArgumentException unsupportedPrecision) {
                    // An otherwise finite translation can collapse a tiny region at extreme
                    // magnitudes. It is unknown geometry, not a reason to strand a worker lease.
                    continue;
                }
                visited.add(next);
                queue.add(new Projection(next, moved, current.estimated
                        || motion.authority == MotionAuthority.EVENT_ESTIMATE));
            }
        }
        return null;
    }

    private boolean contradictedLater(Batch incoming, Observation observation) {
        for (Batch quality : qualityHistory) {
            if (quality.source.sequence <= incoming.source.sequence) continue;
            Projection projected = project(incoming.source.sequence, quality.source.sequence,
                    observation.partition, observation.region, false);
            if (projected != null && inspected(quality, observation.category, observation.partition, projected.region)
                    && !hasPositive(quality, observation.category, observation.partition, projected.region)) return true;
        }
        return false;
    }

    private void applyNegative(Batch quality) {
        for (Iterator<Track> iterator = tracks.iterator(); iterator.hasNext();) {
            Track track = iterator.next();
            if (quality.source.sequence <= track.evidenceSource.sequence) continue;
            Projection projected = project(track.basis, quality.source.sequence, track.partition, track.region, false);
            if (projected == null || !inspected(quality, track.category, track.partition, projected.region)) continue;
            if (hasPositive(quality, track.category, track.partition, projected.region)) continue;
            track.negativeSources.add(quality.source.sequence);
            if (track.negativeSources.size() >= 2) iterator.remove();
        }
    }

    private void replayNegativeEvidence() {
        for (Track track : tracks) {
            track.negativeSources.removeIf(sequence -> sequence <= track.evidenceSource.sequence);
        }
        // A map may arrive after the quality result. Re-evaluate evidence under its now-known
        // correspondence; delivery order does not grant an old positive a second lifetime.
        for (Batch quality : qualityHistory) applyNegative(quality);
    }

    private boolean inspected(Batch batch, String category, long partition, Region region) {
        for (Inspection inspection : batch.inspections) {
            if (inspection.partition == partition && inspection.category.equals(category)
                    && inspection.region.contains(region)) return true;
        }
        return false;
    }

    private boolean hasPositive(Batch batch, String category, long partition, Region region) {
        for (Observation observation : batch.observations) {
            if (observation.partition == partition && observation.category.equals(category)
                    && observation.region.iou(region) >= policy.associationIou) return true;
        }
        return false;
    }

    private static boolean sameKind(Track track, Observation observation, Lane lane) {
        return track.nativeCandidate == null && track.partition == observation.partition && track.category.equals(observation.category)
                && ((track.latestLane == Lane.TEXT) == (lane == Lane.TEXT));
    }

    private void mergeCorrespondingTracks() {
        for (int i = 0; i < tracks.size(); i++) {
            Track kept = tracks.get(i);
            for (int j = i + 1; j < tracks.size();) {
                Track other = tracks.get(j);
                if (kept.nativeCandidate != null || other.nativeCandidate != null
                        || kept.partition != other.partition || !kept.category.equals(other.category)
                        || ((kept.latestLane == Lane.TEXT) != (other.latestLane == Lane.TEXT))
                        || kept.distinctFrom.contains(other.identity)
                        || other.distinctFrom.contains(kept.identity)) { j++; continue; }
                Projection projected = project(other.basis, kept.basis, kept.partition, other.region, false);
                if (projected == null || projected.region.iou(kept.region) < policy.associationIou) { j++; continue; }
                kept.distinctFrom.addAll(other.distinctFrom);
                for (Track peer : tracks) {
                    if (peer.distinctFrom.remove(other.identity)) peer.distinctFrom.add(kept.identity);
                }
                kept.distinctFrom.remove(kept.identity);
                for (Long sequence : other.qualitySources) addIndependent(kept.qualitySources, sequence);
                for (EvidenceWitness witness : other.sourceWitnesses) addWitness(kept.sourceWitnesses, witness);
                kept.freshSourceSequence = Math.max(kept.freshSourceSequence, other.freshSourceSequence);
                kept.evidenceConsumed |= other.evidenceConsumed;
                kept.negativeSources.addAll(other.negativeSources);
                kept.fastVisible |= other.fastVisible;
                kept.confirmed |= other.confirmed || kept.qualitySources.size() >= 2;
                if (other.evidenceSource.sequence > kept.evidenceSource.sequence
                        || (other.evidenceSource.sequence == kept.evidenceSource.sequence
                        && other.latestLane == Lane.QUALITY)) {
                    kept.basis = other.basis;
                    kept.region = other.region;
                    kept.evidenceSource = other.evidenceSource;
                    kept.evidenceRegion = other.evidenceRegion;
                    kept.latestLane = other.latestLane;
                    kept.textGroup = other.textGroup;
                }
                tracks.remove(j);
            }
        }
        pruneDistinctRelations();
    }

    private void pruneDistinctRelations() {
        Set<Long> living = new HashSet<>();
        for (Track track : tracks) living.add(track.identity);
        for (Track track : tracks) track.distinctFrom.retainAll(living);
    }

    private static void addIndependent(Set<Long> evidence, long sequence) {
        evidence.add(sequence);
        if (evidence.size() > 2) evidence.remove(Collections.min(evidence));
    }

    private void trimTracks() {
        while (tracks.size() > policy.maximumTracks) {
            Track oldest = tracks.get(0);
            for (Track track : tracks) {
                if (track.evidenceSource.sequence < oldest.evidenceSource.sequence) oldest = track;
            }
            tracks.remove(oldest);
            capacityDrops++;
        }
    }

    private void evict(long sequence) {
        sourceEvictions++;
        for (Track track : tracks) {
            if (track.basis == sequence) {
                for (Long candidate : sources.keySet()) {
                    if (candidate == sequence) continue;
                    Projection rebased = project(sequence, candidate, track.partition, track.region, false);
                    if (rebased != null) { track.basis = candidate; track.region = rebased.region; break; }
                }
            }
            // Qualified positive/negative witnesses and co-occurrence are compact facts, not
            // leases on a reference image. Eviction must not erase their meaning.
        }
        sources.remove(sequence);
        nativeFrames.entrySet().removeIf(entry -> entry.getValue().source.sequence == sequence);
        motions.removeIf(motion -> motion.from == sequence || motion.to == sequence);
        imagePresentations.removeIf(estimate -> estimate.from == sequence || estimate.to == sequence);
        qualityHistory.removeIf(batch -> batch.source.sequence == sequence);
        delivered.removeIf(key -> key.startsWith(sequence + ":"));
        // Metadata in ready/effect provenance is immutable and contains no pixel resources.
    }

    private static <T> List<T> immutable(List<T> items) {
        List<T> copied = new ArrayList<>(Objects.requireNonNull(items));
        for (T item : copied) Objects.requireNonNull(item);
        return Collections.unmodifiableList(copied);
    }
}
