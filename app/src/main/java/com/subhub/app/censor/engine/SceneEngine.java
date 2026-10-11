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

        public Policy(int maximumSources, int maximumTracks, int maximumCorrespondences,
                int maximumBatchItems, long transientMillis,
                long retainedMillis, double associationIou) {
            if (maximumSources < 2 || maximumTracks < 1 || transientMillis <= 0
                    || maximumCorrespondences < 1 || maximumBatchItems < 1
                    || retainedMillis < transientMillis || !Double.isFinite(associationIou)
                    || associationIou <= 0 || associationIou > 1) {
                throw new IllegalArgumentException("policy");
            }
            this.maximumSources = maximumSources;
            this.maximumTracks = maximumTracks;
            this.maximumCorrespondences = maximumCorrespondences;
            this.maximumBatchItems = maximumBatchItems;
            this.transientMillis = transientMillis;
            this.retainedMillis = retainedMillis;
            this.associationIou = associationIou;
        }
    }

    public static final class Mask {
        public final long identity;
        public final String category;
        public final long partition;
        public final long textGroup;
        public final boolean confirmed;
        public final boolean estimatedMotion;
        public final Region displayRegion;
        /** Sampling uses this source and region, never the enclosing packet's newest source. */
        public final Source evidenceSource;
        public final Region evidenceRegion;

        private Mask(Track track, Projection projected) {
            identity = track.identity;
            category = track.category;
            partition = track.partition;
            textGroup = track.textGroup;
            confirmed = track.confirmed;
            estimatedMotion = projected.estimated;
            displayRegion = projected.region;
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

        private Snapshot(Source destination, List<Group> groups, int unresolved, int tentative,
                long revision, long capacityDrops, long sourceEvictions) {
            this.destination = destination;
            this.groups = immutable(groups);
            unresolvedTracks = unresolved;
            tentativeTracks = tentative;
            this.revision = revision;
            this.capacityDrops = capacityDrops;
            this.sourceEvictions = sourceEvictions;
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

    private static final class Track {
        final long identity;
        final String category;
        final long partition;
        final Map<String, Integer> witnesses = new HashMap<>();
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

        Track(long identity, Batch batch, Observation observation, int index) {
            this.identity = identity;
            category = observation.category;
            partition = observation.partition;
            update(batch, observation, index);
        }

        void update(Batch batch, Observation observation, int index) {
            witnesses.put(batch.source.sequence + ":" + batch.lane, index);
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
        capacityDrops = 0;
        sourceEvictions = 0;
        revision++;
    }

    /** False means bounded admission is unavailable, not that executing work should be cancelled. */
    public synchronized boolean register(Source source) {
        if (source.epoch != epoch) return false;
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
        Source from = sources.get(motion.from);
        Source to = sources.get(motion.to);
        if (motion.epoch != epoch || from == null || to == null || from.geometry != to.geometry
                || from.width != to.width || from.height != to.height) return false;
        for (int i = 0; i < motions.size(); i++) {
            Motion old = motions.get(i);
            if (old.from != motion.from || old.to != motion.to || old.partition != motion.partition) continue;
            if (old.authority == MotionAuthority.IMAGE_CORRESPONDENCE
                    || old.authority.ordinal() > motion.authority.ordinal()
                    || (old.authority == motion.authority && old.revision >= motion.revision)) return false;
            motions.set(i, motion);
            revision++;
            mergeCorrespondingTracks();
            replayNegativeEvidence();
            return true;
        }
        if (motions.size() >= policy.maximumCorrespondences) { capacityDrops++; return false; }
        motions.add(motion);
        revision++;
        mergeCorrespondingTracks();
        replayNegativeEvidence();
        return true;
    }

    /** Old-epoch, evicted-source and duplicate results are rejected without mutating evidence. */
    public synchronized boolean observe(Batch batch) {
        Source source = sources.get(batch.source.sequence);
        if (batch.source.epoch != epoch || source == null || !source.sameDescriptor(batch.source)) return false;
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
                best = new Track(++nextIdentity, batch, observation, index);
                tracks.add(best);
            } else {
                best.update(batch, observation, index);
            }
            matched.add(best.identity);
        }
        if (batch.lane == Lane.QUALITY) {
            qualityHistory.add(batch);
        }
        mergeCorrespondingTracks();
        replayNegativeEvidence();
        trimTracks();
        revision++;
        return true;
    }

    /** Only this method constructs render geometry. Unknown correspondence stays explicit. */
    public synchronized Snapshot snapshot(long destination, long nowMillis, boolean allowEventEstimates) {
        Source source = sources.get(destination);
        if (source == null) throw new IllegalArgumentException("unknown destination");
        List<Mask> masks = new ArrayList<>();
        int unresolved = 0;
        int tentative = 0;
        for (Iterator<Track> iterator = tracks.iterator(); iterator.hasNext();) {
            Track track = iterator.next();
            long hold = track.confirmed ? policy.retainedMillis : policy.transientMillis;
            if (nowMillis > track.evidenceSource.receivedAtMillis
                    && nowMillis - track.evidenceSource.receivedAtMillis > hold) {
                iterator.remove();
                revision++;
                continue;
            }
            if (!track.fastVisible && !track.confirmed) { tentative++; continue; }
            Projection projected = project(track.basis, destination, track.partition, track.region, allowEventEstimates);
            if (projected == null) { unresolved++; continue; }
            if (projected.region.visible(source)) masks.add(new Mask(track, projected));
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
        ready = new Snapshot(source, groups, unresolved, tentative, revision, capacityDrops, sourceEvictions);
        return ready;
    }

    /** No-new-image differs from a new unmatched image. Immutable ready coverage is not cleared. */
    public synchronized Snapshot ready() { return ready; }
    public synchronized int sourceCount() { return sources.size(); }
    public synchronized int trackCount() { return tracks.size(); }

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
                Region moved = current.region.translated(forward ? motion.dx : -motion.dx, forward ? motion.dy : -motion.dy);
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
                        || witnessConflict(kept, other)) { j++; continue; }
                Projection projected = project(other.basis, kept.basis, kept.partition, other.region, false);
                if (projected == null || projected.region.iou(kept.region) < policy.associationIou) { j++; continue; }
                kept.witnesses.putAll(other.witnesses);
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
    }

    private static boolean witnessConflict(Track left, Track right) {
        for (Map.Entry<String, Integer> witness : left.witnesses.entrySet()) {
            Integer other = right.witnesses.get(witness.getKey());
            if (other != null && !other.equals(witness.getValue())) return true;
        }
        return false;
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
            track.witnesses.keySet().removeIf(key -> key.startsWith(sequence + ":"));
            track.negativeSources.remove(sequence);
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
