package com.subhub.app.censor.engine;

import java.util.ArrayList;
import java.util.Collections;
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
    public enum MotionAuthority { EVENT_ESTIMATE, IMAGE_CORRESPONDENCE }
    public enum MotionAdmission { ADMITTED, DUPLICATE, STALE, INVALID, CAPACITY, CHECK_BUDGET, UNREPRESENTABLE, CONFLICT }

    /** Immutable source descriptor. Sequence orders acquisition, not result delivery. */
    public static final class Source {
        public final long epoch;
        public final long sequence;
        public final long geometry;
        public final int width;
        public final int height;
        public final long receivedAtMillis;
        /** -1 means unknown. Receipt time is never substituted for this value. */
        public final long pixelAtMillis;

        public Source(long epoch, long sequence, long geometry, int width, int height,
                long receivedAtMillis, long pixelAtMillis) {
            if (epoch < 0 || sequence <= 0 || width <= 0 || height <= 0
                    || receivedAtMillis < 0 || pixelAtMillis < -1) {
                throw new IllegalArgumentException("source");
            }
            this.epoch = epoch;
            this.sequence = sequence;
            this.geometry = geometry;
            this.width = width;
            this.height = height;
            this.receivedAtMillis = receivedAtMillis;
            this.pixelAtMillis = pixelAtMillis;
        }

        private boolean sameDescriptor(Source other) {
            return epoch == other.epoch && sequence == other.sequence && geometry == other.geometry
                    && width == other.width && height == other.height
                    && receivedAtMillis == other.receivedAtMillis && pixelAtMillis == other.pixelAtMillis;
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

        public Batch(Source source, Lane lane, List<Observation> observations, List<Inspection> inspections) {
            this.source = Objects.requireNonNull(source);
            this.lane = Objects.requireNonNull(lane);
            this.observations = immutable(observations);
            this.inspections = immutable(inspections);
            if (lane != Lane.QUALITY && !inspections.isEmpty()) {
                throw new IllegalArgumentException("only quality can contradict");
            }
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
        /** A bounded hold at an older supported source, never correspondence to the requested basis. */
        public final boolean heldGeometry;
        public final Region displayRegion;
        /** Actual source whose coordinate basis produced displayRegion; not the sampling source. */
        public final Source geometrySource;
        /** Sampling uses this source and region, never the enclosing packet's newest source. */
        public final Source evidenceSource;
        public final Region evidenceRegion;

        private Mask(Track track, Projection projected, Source geometrySource) {
            this(track, projected, geometrySource, false);
        }

        private Mask(Track track, Projection projected, Source geometrySource, boolean heldGeometry) {
            identity = track.identity;
            category = track.category;
            partition = track.partition;
            textGroup = track.textGroup;
            confirmed = track.confirmed;
            estimatedMotion = projected.estimated;
            this.heldGeometry = heldGeometry;
            displayRegion = projected.region;
            this.geometrySource = geometrySource;
            evidenceSource = track.evidenceSource;
            evidenceRegion = track.evidenceRegion;
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

        Projection(long source, Region region, boolean estimated) {
            this.source = source;
            this.region = region;
            this.estimated = estimated;
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

    private static final class Track {
        final long identity;
        final String category;
        final long partition;
        /** Co-occurring identities stay distinct even after their original frame is evicted. */
        final Set<Long> distinctFrom = new HashSet<>();
        final Set<Long> qualitySources = new HashSet<>();
        final Set<Long> negativeSources = new HashSet<>();
        long basis;
        Region region;
        Source evidenceSource;
        Region evidenceRegion;
        Lane latestLane;
        long textGroup;
        boolean fastVisible;
        boolean confirmed;

        Track(long identity, Batch batch, Observation observation) {
            this.identity = identity;
            category = observation.category;
            partition = observation.partition;
            update(batch, observation);
        }

        void update(Batch batch, Observation observation) {
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

    private final Policy policy;
    private final LinkedHashMap<Long, Source> sources = new LinkedHashMap<>();
    private final Map<Long, Integer> pins = new HashMap<>();
    private final List<Motion> motions = new ArrayList<>();
    private final List<Track> tracks = new ArrayList<>();
    private final List<Batch> qualityHistory = new ArrayList<>();
    private final Set<String> delivered = new HashSet<>();
    private long epoch;
    private long highestSource;
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
        sources.clear();
        pins.clear();
        motions.clear();
        tracks.clear();
        qualityHistory.clear();
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

    /** No geometry/identity derived from conflicting authority survives until an explicit reset. */
    public synchronized MotionAdmission admitMotion(Motion motion) {
        Objects.requireNonNull(motion);
        Source from = sources.get(motion.from);
        Source to = sources.get(motion.to);
        if (motionConflict || motion.epoch != epoch || from == null || to == null || from.geometry != to.geometry
                || from.width != to.width || from.height != to.height) return MotionAdmission.INVALID;
        for (int i = 0; i < motions.size(); i++) {
            Motion old = motions.get(i);
            if (old.from != motion.from || old.to != motion.to || old.partition != motion.partition) continue;
            if (old.authority == MotionAuthority.IMAGE_CORRESPONDENCE) {
                if (motion.authority != MotionAuthority.IMAGE_CORRESPONDENCE) return MotionAdmission.INVALID;
                if (motion.revision < old.revision) return MotionAdmission.STALE;
                if (sameGeometry(old, motion)) {
                    // Advance only the stale-result watermark, never geometry, evidence age or identity.
                    if (motion.revision > old.revision) motions.set(i, motion);
                    return MotionAdmission.DUPLICATE;
                }
                // A changed support alone does not contradict the measured translation. The
                // immutable single-domain key cannot consume it; reject without destroying the
                // valid scene. Atomic piecewise support requires a separate admission contract.
                if (old.dx == motion.dx && old.dy == motion.dy) return MotionAdmission.INVALID;
                // The single-edge API cannot add a disjoint second domain. Do not confuse this
                // unsupported representation with a correction to overlapping image authority.
                if (intersection(old.support, motion.support) == null) return MotionAdmission.INVALID;
                return invalidateMotionAuthority();
            }
            if (old.authority == motion.authority && old.revision >= motion.revision) return MotionAdmission.STALE;
            MotionAdmission checked = checkMeasuredPaths(motion);
            if (checked != MotionAdmission.ADMITTED) return checked;
            motions.set(i, motion);
            revision++;
            mergeCorrespondingTracks();
            replayNegativeEvidence();
            return MotionAdmission.ADMITTED;
        }
        if (motions.size() >= policy.maximumCorrespondences) { capacityDrops++; return MotionAdmission.CAPACITY; }
        MotionAdmission checked = checkMeasuredPaths(motion);
        if (checked != MotionAdmission.ADMITTED) return checked;
        motions.add(motion);
        revision++;
        mergeCorrespondingTracks();
        replayNegativeEvidence();
        return MotionAdmission.ADMITTED;
    }

    public synchronized boolean hasMotionConflict() { return motionConflict; }

    private MotionAdmission invalidateMotionAuthority() {
        motionConflict = true;
        tracks.clear(); motions.clear(); qualityHistory.clear(); delivered.clear(); ready = null;
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

    private MotionAdmission checkMeasuredPaths(Motion candidate) {
        if (candidate.authority != MotionAuthority.IMAGE_CORRESPONDENCE) return MotionAdmission.ADMITTED;
        List<MotionPath> queue = new ArrayList<>();
        queue.add(new MotionPath(candidate.from, candidate.support, 0, 0, 0, 0,
                Collections.singleton(candidate.from)));
        int edgeChecks = 0;
        for (int index = 0; index < queue.size(); index++) {
            MotionPath current = queue.get(index);
            if (current.source == candidate.to) {
                // This bound covers floating-point addition only, not physical matcher uncertainty.
                if (Math.abs(current.dx - candidate.dx) > current.roundoffX + 2 * Math.ulp(candidate.dx)
                        || Math.abs(current.dy - candidate.dy) > current.roundoffY + 2 * Math.ulp(candidate.dy)) {
                    return invalidateMotionAuthority();
                }
                continue;
            }
            for (Motion edge : motions) {
                if (++edgeChecks > policy.maximumMotionEdgeChecks) return rejectedMotionCheck(MotionAdmission.CHECK_BUDGET);
                if (edge.partition != candidate.partition || edge.authority != MotionAuthority.IMAGE_CORRESPONDENCE) continue;
                boolean forward = edge.from == current.source;
                if (!forward && edge.to != current.source) continue;
                long next = forward ? edge.to : edge.from;
                if (current.visited.contains(next)) continue;
                double dx = forward ? edge.dx : -edge.dx, dy = forward ? edge.dy : -edge.dy;
                Region common;
                try {
                    Region support = forward ? edge.support : edge.support.translated(edge.dx, edge.dy);
                    common = intersection(current.support, support.translated(-current.dx, -current.dy));
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
                if (queue.size() >= policy.maximumMotionPathStates
                        || current.visited.size() >= policy.maximumSources) {
                    return rejectedMotionCheck(MotionAdmission.CHECK_BUDGET);
                }
                Set<Long> visited = new HashSet<>(current.visited); visited.add(next);
                queue.add(new MotionPath(next, common, nextX, nextY, errorX, errorY, visited));
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
        if (motionConflict || batch.source.epoch != epoch || source == null || !source.sameDescriptor(batch.source)) return false;
        if ((long) batch.observations.size() + batch.inspections.size() > policy.maximumBatchItems) {
            capacityDrops++;
            return false;
        }
        String delivery = batch.source.sequence + ":" + batch.lane;
        if (!delivered.add(delivery)) return false;
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
        trimTracks();
        pruneDistinctRelations();
        revision++;
        return true;
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
        Source source = sources.get(destination);
        if (source == null) throw new IllegalArgumentException("unknown destination");
        if (motionConflict) {
            ready = new Snapshot(source, Collections.emptyList(), 0, 0, revision, capacityDrops,
                    sourceEvictions, Long.MAX_VALUE, 0);
            return ready;
        }
        Map<Long, Long> bases = new HashMap<>(Objects.requireNonNull(partitionBases));
        Map<Long, Source> selectedSources = new HashMap<>();
        if (bases.size() > policy.maximumTracks) throw new IllegalArgumentException("too many render bases");
        for (Long selected : bases.values()) {
            Source basis = sources.get(selected);
            if (basis == null || basis.epoch != source.epoch || basis.geometry != source.geometry
                    || basis.width != source.width || basis.height != source.height) {
                throw new IllegalArgumentException("incompatible render basis");
            }
        }
        for (Map.Entry<Long, Long> entry : bases.entrySet()) selectedSources.put(entry.getKey(), sources.get(entry.getValue()));
        // An explicit carried basis cannot force fresher visible geometry backward, even when
        // an inverse estimate/image path exists. Tentative optional quality cannot move FAST.
        for (Track track : tracks) {
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
            long deadline = received >= Long.MAX_VALUE - hold
                    ? Long.MAX_VALUE : received + hold + 1;
            nextChangeAt = Math.min(nextChangeAt, deadline);
            if (!track.fastVisible && !track.confirmed) { tentative++; continue; }
            Source selected = selectedSources.getOrDefault(track.partition, source);
            // Direct geometry survives graph-node eviction. Reusing that immutable observation
            // is not an invented zero-motion edge from the track's eviction-rebased coordinates.
            boolean optionalImageBasis = selectedSources.containsKey(track.partition) && selected.sequence != source.sequence;
            Mask mask = projectedMask(track, selected, allowEventEstimates && !optionalImageBasis, false);
            if (optionalImageBasis) {
                mask = supportedOrHeldMask(track, source, selected, mask);
            }
            if (mask == null) { unresolved++; continue; }
            if (mask.displayRegion.visible(source)) masks.add(mask);
        }
        int redundantEstimated = 0;
        List<Mask> supported = new ArrayList<>(masks);
        for (Iterator<Mask> iterator = masks.iterator(); iterator.hasNext();) {
            Mask estimate = iterator.next();
            if (!estimate.estimatedMotion) continue;
            for (Mask direct : supported) {
                if (!direct.estimatedMotion && direct.evidenceSource.sequence > estimate.evidenceSource.sequence
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
    public synchronized int sourceCount() { return sources.size(); }
    public synchronized int trackCount() { return tracks.size(); }

    private Mask projectedMask(Track track, Source destination, boolean estimates, boolean held) {
        Projection projected = destination.sequence == track.evidenceSource.sequence
                ? new Projection(destination.sequence, track.evidenceRegion, false)
                : project(track.basis, destination.sequence, track.partition, track.region, estimates);
        return projected == null ? null : new Mask(track, projected, destination, held);
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
                new Projection(previous.geometrySource.sequence, previous.displayRegion, false), previous.geometrySource, true);
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
        long hold = track.confirmed ? policy.retainedMillis : policy.transientMillis;
        return nowMillis > track.evidenceSource.receivedAtMillis
                && nowMillis - track.evidenceSource.receivedAtMillis > hold;
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
            for (Motion motion : motions) {
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
        return track.partition == observation.partition && track.category.equals(observation.category)
                && ((track.latestLane == Lane.TEXT) == (lane == Lane.TEXT));
    }

    private void mergeCorrespondingTracks() {
        for (int i = 0; i < tracks.size(); i++) {
            Track kept = tracks.get(i);
            for (int j = i + 1; j < tracks.size();) {
                Track other = tracks.get(j);
                if (kept.partition != other.partition || !kept.category.equals(other.category)
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
        motions.removeIf(motion -> motion.from == sequence || motion.to == sequence);
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
