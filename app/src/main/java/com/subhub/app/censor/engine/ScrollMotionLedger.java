package com.subhub.app.censor.engine;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Bounded event-coordinate observations, not a global image camera or a member-motion binding.
 * Acquired IMAGE admission is an administrative cutoff, never a pixel clock/bound. A continuation
 * omits its straddling interval and retains unknown initial bias; it cannot authorize painting,
 * identity, confirmation, negative evidence, retention renewal or accounting by itself.
 */
public final class ScrollMotionLedger implements AutoCloseable {
    public enum Admission { BASELINE, RECORDED, DUPLICATE, UNKNOWN, GAP, STALE, CLOSED }
    public enum Refusal { NONE, SCOPE, CLOCK, OWNER, CAPACITY, ORDER, CONFLICT, OVERFLOW,
        INVALID_COORDINATE, EXPLICIT_GAP, SOURCE, HISTORY, UNKNOWN_AXIS, CLOSED }

    /** Advisory provider class only, not proof that an image member moves with this owner. */
    public enum OwnerKind {
        UNKNOWN, RECYCLER_VIEW, OTHER;
        public static OwnerKind fromProviderClass(CharSequence name) {
            if (name == null || name.length() == 0) return UNKNOWN;
            return "androidx.recyclerview.widget.RecyclerView".contentEquals(name) ? RECYCLER_VIEW : OTHER;
        }
    }

    public static final class Scope {
        public final long epoch, geometry;
        public final int windowId;
        public final ProtectionScope protection;

        public Scope(long epoch, long geometry, int windowId, ProtectionScope protection) {
            if (epoch < 0 || windowId < -1) throw new IllegalArgumentException("scroll scope");
            this.epoch = epoch; this.geometry = geometry; this.windowId = windowId;
            this.protection = Objects.requireNonNull(protection);
        }

        public boolean same(Scope other) {
            return other != null && epoch == other.epoch && geometry == other.geometry
                    && windowId == other.windowId && protection.equals(other.protection);
        }
    }

    /** Reported scalar units only. Missing/default metadata never becomes observed zero movement. */
    public static final class Coordinate {
        public final boolean valid, absoluteKnown, deltaKnown;
        public final long absolute, delta;

        private Coordinate(boolean valid, boolean absoluteKnown, long absolute, boolean deltaKnown, long delta) {
            this.valid = valid; this.absoluteKnown = absoluteKnown; this.absolute = absolute;
            this.deltaKnown = deltaKnown; this.delta = delta;
        }

        public static Coordinate unknown() { return new Coordinate(true, false, 0, false, 0); }
        public static Coordinate absolute(long value) {
            if (value < 0) throw new IllegalArgumentException("negative reported position");
            return new Coordinate(true, true, value, false, 0);
        }
        public static Coordinate delta(long value) { return new Coordinate(true, false, 0, true, value); }
        public static Coordinate absoluteAndDelta(long absolute, long delta) {
            if (absolute < 0) throw new IllegalArgumentException("negative reported position");
            return new Coordinate(true, true, absolute, true, delta);
        }

        public static Coordinate accessibility(int offset, int maximum, int delta) {
            // AccessibilityRecord's omitted delta is -1. It is not safely distinguishable from
            // a real -1 px delta. Unlike the legacy resolver, +1 and literal 0 are not discarded.
            boolean valid = offset >= -1 && maximum >= -1 && !(maximum >= 0 && offset > maximum);
            boolean position = valid && offset >= 0 && maximum > 0;
            return new Coordinate(valid, position, position ? offset : 0, delta != -1, delta != -1 ? delta : 0);
        }

        private boolean same(Coordinate other) {
            return valid == other.valid && absoluteKnown == other.absoluteKnown && deltaKnown == other.deltaKnown
                    && (!absoluteKnown || absolute == other.absolute) && (!deltaKnown || delta == other.delta);
        }
    }

    /** Copied primitives. Advisory provider bounds are deliberately not a motion-support field. */
    public static final class Sample {
        public final Scope scope;
        /** Owner-issued copied-delivery identity, not millisecond time or semantic payload equality. */
        public final long deliveryIdentity;
        public final long owner, producer, sourceAtMillis, receivedAtMillis, readStartedAtMillis, readFinishedAtMillis;
        public final Coordinate x, y;
        public final SceneEngine.Region reportedViewport;
        public final OwnerKind ownerKind;

        public Sample(Scope scope, long deliveryIdentity, long owner, long producer, long sourceAtMillis, long receivedAtMillis,
                long readStartedAtMillis, long readFinishedAtMillis, Coordinate x, Coordinate y,
                SceneEngine.Region reportedViewport) {
            this(scope, deliveryIdentity, owner, producer, sourceAtMillis, receivedAtMillis,
                    readStartedAtMillis, readFinishedAtMillis, x, y, reportedViewport, OwnerKind.UNKNOWN);
        }

        public Sample(Scope scope, long deliveryIdentity, long owner, long producer, long sourceAtMillis, long receivedAtMillis,
                long readStartedAtMillis, long readFinishedAtMillis, Coordinate x, Coordinate y,
                SceneEngine.Region reportedViewport, OwnerKind ownerKind) {
            this.scope = Objects.requireNonNull(scope); this.deliveryIdentity = deliveryIdentity;
            this.owner = owner; this.producer = producer;
            this.sourceAtMillis = sourceAtMillis; this.receivedAtMillis = receivedAtMillis;
            this.readStartedAtMillis = readStartedAtMillis; this.readFinishedAtMillis = readFinishedAtMillis;
            this.x = Objects.requireNonNull(x); this.y = Objects.requireNonNull(y);
            this.reportedViewport = reportedViewport;
            this.ownerKind = Objects.requireNonNull(ownerKind);
        }

        private boolean clocksValid(long admittedAt) {
            return sourceAtMillis >= 0 && receivedAtMillis >= sourceAtMillis
                    && readStartedAtMillis >= receivedAtMillis && readFinishedAtMillis >= readStartedAtMillis
                    && admittedAt >= readFinishedAtMillis;
        }

        private boolean sameDelivery(Sample other) {
            return deliveryIdentity == other.deliveryIdentity && scope.same(other.scope)
                    && owner == other.owner && producer == other.producer
                    && sourceAtMillis == other.sourceAtMillis && receivedAtMillis == other.receivedAtMillis
                    && readStartedAtMillis == other.readStartedAtMillis && readFinishedAtMillis == other.readFinishedAtMillis
                    && ownerKind == other.ownerKind
                    && x.same(other.x) && y.same(other.y) && sameViewport(reportedViewport, other.reportedViewport);
        }

        private static boolean sameViewport(SceneEngine.Region a, SceneEngine.Region b) {
            return a == b || a != null && b != null && a.left == b.left && a.top == b.top
                    && a.right == b.right && a.bottom == b.bottom;
        }
    }

    public static final class Receipt {
        public final Sample sample;
        public final Admission admission;
        public final Refusal refusal;
        public final long admittedAtMillis, generation;
        public final int producerCount, intervalCount;

        private Receipt(Sample sample, Admission admission, Refusal refusal, long at, long generation,
                int producers, int intervals) {
            this.sample = sample; this.admission = admission; this.refusal = refusal;
            admittedAtMillis = at; this.generation = generation;
            producerCount = producers; intervalCount = intervals;
        }
    }

    /** Only wholly-after-cutoff reported increments. These are not a complete source/display map. */
    public static final class AxisContinuation {
        public final boolean available;
        private final long displacement, minimumObserved, maximumObserved;

        private AxisContinuation(boolean available, long displacement, long minimum, long maximum) {
            this.available = available; this.displacement = displacement;
            minimumObserved = minimum; maximumObserved = maximum;
        }

        public long displacement() { require(); return displacement; }
        public long minimumObservedDisplacement() { require(); return minimumObserved; }
        public long maximumObservedDisplacement() { require(); return maximumObserved; }
        private void require() { if (!available) throw new IllegalStateException("unknown scroll axis"); }
    }

    public static final class Continuation {
        public final SceneEngine.Source origin;
        /** Owner admission ordering only, distinct from callback receipt and all pixel clocks. */
        public final long originAdmittedAtMillis;
        public final long owner, producer, generation, latestSourceAtMillis, latestReceivedAtMillis;
        public final AxisContinuation x, y;
        public final Refusal refusal;
        public final int intervals, omittedStraddlingIntervals;
        public final boolean originPixelPhaseKnown = false;
        public final boolean initialBiasKnown = false;
        public final boolean physicalErrorKnown = false;
        public final boolean memberApplicabilityKnown = false;

        private Continuation(SceneEngine.Source origin, long admittedAt, long owner, long producer, long generation,
                Producer state, AxisContinuation x, AxisContinuation y, Refusal refusal, int intervals, int omitted) {
            this.origin = origin; this.owner = owner; this.producer = producer; this.generation = generation;
            originAdmittedAtMillis = admittedAt;
            latestSourceAtMillis = state == null ? -1 : state.latest.sourceAtMillis;
            latestReceivedAtMillis = state == null ? -1 : state.latest.receivedAtMillis;
            this.x = x; this.y = y; this.refusal = refusal;
            this.intervals = intervals; omittedStraddlingIntervals = omitted;
        }
    }

    private static final class Axis {
        boolean absoluteKnown;
        long absolute;
        void baseline(Coordinate coordinate) {
            absoluteKnown = coordinate.absoluteKnown; absolute = coordinate.absolute;
        }
        Long advance(Coordinate coordinate) {
            Long difference = coordinate.absoluteKnown && absoluteKnown
                    ? Math.subtractExact(coordinate.absolute, absolute) : null;
            if (difference != null && coordinate.deltaKnown && difference != coordinate.delta) {
                throw new CoordinateConflict();
            }
            Long displacement = difference != null ? difference : coordinate.deltaKnown ? coordinate.delta : null;
            if (coordinate.absoluteKnown) { absoluteKnown = true; absolute = coordinate.absolute; }
            else if (absoluteKnown && displacement != null) {
                long updated = Math.addExact(absolute, displacement);
                if (updated < 0) throw new InvalidPosition();
                absolute = updated;
            }
            else absoluteKnown = false;
            return displacement;
        }
    }
    private static final class CoordinateConflict extends RuntimeException { }
    private static final class InvalidPosition extends RuntimeException { }
    private static final class Interval {
        final long start, end;
        final Long dx, dy;
        Interval(long start, long end, Long dx, Long dy) { this.start = start; this.end = end; this.dx = dx; this.dy = dy; }
    }
    private static final class Producer {
        final long owner, identity;
        final Axis x = new Axis(), y = new Axis();
        final ArrayDeque<Interval> intervals = new ArrayDeque<>();
        Sample latest;
        long evictedThrough = -1;
        Producer(Sample sample) {
            owner = sample.owner; identity = sample.producer; latest = sample;
            x.baseline(sample.x); y.baseline(sample.y);
        }
    }
    private static final class Origin {
        final SceneEngine.Source source;
        final long generation, admittedAt;
        Origin(SceneEngine.Source source, long generation, long admittedAt) {
            this.source = source; this.generation = generation; this.admittedAt = admittedAt;
        }
    }

    private final int maximumSources, maximumProducers, maximumIntervals;
    private final Map<Long, Producer> producers = new LinkedHashMap<>();
    private final Map<Long, Origin> origins = new LinkedHashMap<>();
    private Scope scope;
    private long generation, lastDeliveryIdentity, lastSourceSequence, lastSourceAdmission;
    private boolean closed;

    public ScrollMotionLedger(int maximumSources, int maximumProducers, int maximumIntervals) {
        if (maximumSources < 1 || maximumProducers < 1 || maximumIntervals < 1)
            throw new IllegalArgumentException("scroll ledger bounds");
        this.maximumSources = maximumSources; this.maximumProducers = maximumProducers; this.maximumIntervals = maximumIntervals;
    }

    public synchronized void reset(Scope next) {
        if (closed) throw new IllegalStateException("closed scroll ledger");
        scope = Objects.requireNonNull(next); advanceGeneration(); producers.clear(); origins.clear();
        lastDeliveryIdentity = 0; lastSourceSequence = 0; lastSourceAdmission = 0;
    }

    public synchronized boolean registerImage(SceneEngine.Source source) {
        return registerImage(source, Objects.requireNonNull(source).receivedAtMillis);
    }

    /** The runtime passes its actual adoption checkpoint; receipt may precede API return or a gap. */
    public synchronized boolean registerImage(SceneEngine.Source source, long admittedAtMillis) {
        Objects.requireNonNull(source);
        if (closed || scope == null || source.kind != SceneEngine.SourceKind.IMAGE || source.epoch != scope.epoch
                || source.geometry != scope.geometry || !source.protectionScope.equals(scope.protection)
                || admittedAtMillis < source.receivedAtMillis) return false;
        Origin previous = origins.get(source.sequence);
        if (previous != null) return previous.source == source && previous.admittedAt == admittedAtMillis;
        if (source.sequence <= lastSourceSequence || admittedAtMillis < lastSourceAdmission) return false;
        while (origins.size() >= maximumSources) origins.remove(origins.keySet().iterator().next());
        origins.put(source.sequence, new Origin(source, generation, admittedAtMillis));
        lastSourceSequence = source.sequence; lastSourceAdmission = admittedAtMillis;
        return true;
    }

    public synchronized Receipt observe(Sample sample, long admittedAtMillis) {
        Objects.requireNonNull(sample);
        if (closed) return receipt(sample, Admission.CLOSED, Refusal.CLOSED, admittedAtMillis);
        if (scope == null || scope.windowId < 0 || !scope.same(sample.scope))
            return receipt(sample, Admission.STALE, Refusal.SCOPE, admittedAtMillis);
        if (sample.deliveryIdentity <= 0) return failed(sample, Refusal.ORDER, admittedAtMillis);
        if (!sample.clocksValid(admittedAtMillis)) return failed(sample, Refusal.CLOCK, admittedAtMillis);
        if (sample.owner <= 0 || sample.producer <= 0) return failed(sample, Refusal.OWNER, admittedAtMillis);
        if (!sample.x.valid || !sample.y.valid) return failed(sample, Refusal.INVALID_COORDINATE, admittedAtMillis);
        Producer previous = producers.get(sample.producer);
        if (previous != null && sample.deliveryIdentity == previous.latest.deliveryIdentity) {
            return previous.latest.sameDelivery(sample) ? receipt(sample, Admission.DUPLICATE, Refusal.NONE, admittedAtMillis)
                    : failed(sample, Refusal.CONFLICT, admittedAtMillis);
        }
        if (sample.deliveryIdentity <= lastDeliveryIdentity) return failed(sample, Refusal.ORDER, admittedAtMillis);
        if (previous == null) {
            if (producers.size() >= maximumProducers) return failed(sample, Refusal.CAPACITY, admittedAtMillis);
            producers.put(sample.producer, new Producer(sample));
            lastDeliveryIdentity = sample.deliveryIdentity;
            return receipt(sample, Admission.BASELINE, Refusal.NONE, admittedAtMillis);
        }
        if (previous.owner != sample.owner) return failed(sample, Refusal.OWNER, admittedAtMillis);
        if (sample.sourceAtMillis < previous.latest.sourceAtMillis
                || sample.receivedAtMillis < previous.latest.receivedAtMillis)
            return failed(sample, Refusal.ORDER, admittedAtMillis);
        Long dx, dy;
        try { dx = previous.x.advance(sample.x); dy = previous.y.advance(sample.y); }
        catch (CoordinateConflict failure) { return failed(sample, Refusal.CONFLICT, admittedAtMillis); }
        catch (InvalidPosition failure) { return failed(sample, Refusal.INVALID_COORDINATE, admittedAtMillis); }
        catch (ArithmeticException failure) { return failed(sample, Refusal.OVERFLOW, admittedAtMillis); }
        previous.intervals.addLast(new Interval(previous.latest.sourceAtMillis, sample.sourceAtMillis, dx, dy));
        previous.latest = sample;
        lastDeliveryIdentity = sample.deliveryIdentity;
        while (previous.intervals.size() > maximumIntervals) previous.evictedThrough = previous.intervals.removeFirst().end;
        return receipt(sample, dx == null || dy == null ? Admission.UNKNOWN : Admission.RECORDED,
                dx == null || dy == null ? Refusal.UNKNOWN_AXIS : Refusal.NONE, admittedAtMillis);
    }

    /** Missing observations invalidate earlier origins; resumed samples do not bridge that gap. */
    public synchronized void gap() {
        if (!closed) { advanceGeneration(); producers.clear(); }
    }

    /** A missing/failed lookup preserves unknown read clocks rather than fabricating a read. */
    public synchronized Receipt gap(Sample sample, long admittedAtMillis) {
        Objects.requireNonNull(sample);
        if (closed) return receipt(sample, Admission.CLOSED, Refusal.CLOSED, admittedAtMillis);
        if (scope == null || scope.windowId < 0 || !scope.same(sample.scope))
            return receipt(sample, Admission.STALE, Refusal.SCOPE, admittedAtMillis);
        return failed(sample, Refusal.EXPLICIT_GAP, admittedAtMillis);
    }

    public synchronized Continuation continuation(SceneEngine.Source source, long owner, long producer) {
        Objects.requireNonNull(source);
        Origin origin = origins.get(source.sequence);
        Producer state = producers.get(producer);
        Refusal refusal = closed ? Refusal.CLOSED : scope == null || source.epoch != scope.epoch
                || source.geometry != scope.geometry || !source.protectionScope.equals(scope.protection) ? Refusal.SCOPE
                : origin == null || origin.source != source ? Refusal.SOURCE
                : origin.generation != generation ? Refusal.EXPLICIT_GAP
                : state == null || state.owner != owner ? Refusal.OWNER
                : state.evictedThrough > origin.admittedAt ? Refusal.HISTORY : Refusal.NONE;
        if (refusal != Refusal.NONE) return unknown(source, owner, producer, state, refusal);
        long x = 0, y = 0, minX = 0, maxX = 0, minY = 0, maxY = 0;
        boolean knownX = true, knownY = true;
        int intervals = 0, omitted = 0;
        for (Interval interval : state.intervals) {
            if (interval.end <= origin.admittedAt) continue;
            if (interval.start < origin.admittedAt) { omitted++; continue; }
            intervals++;
            knownX &= interval.dx != null; knownY &= interval.dy != null;
            try {
                if (knownX) { x = Math.addExact(x, interval.dx); minX = Math.min(minX, x); maxX = Math.max(maxX, x); }
                if (knownY) { y = Math.addExact(y, interval.dy); minY = Math.min(minY, y); maxY = Math.max(maxY, y); }
            } catch (ArithmeticException failure) { return unknown(source, owner, producer, state, Refusal.OVERFLOW); }
        }
        // A producer first discovered after the cutoff cannot establish its missing prefix.
        if (state.intervals.isEmpty() && state.latest.sourceAtMillis > origin.admittedAt)
            return unknown(source, owner, producer, state, Refusal.HISTORY);
        if (intervals == 0) return unknown(source, owner, producer, state, Refusal.HISTORY);
        return new Continuation(source, origin.admittedAt, owner, producer, generation, state,
                new AxisContinuation(knownX, x, minX, maxX), new AxisContinuation(knownY, y, minY, maxY),
                knownX && knownY ? Refusal.NONE : Refusal.UNKNOWN_AXIS, intervals, omitted);
    }

    public synchronized int producerCount() { return producers.size(); }
    public synchronized int originCount() { return origins.size(); }
    public synchronized int intervalCount() {
        int count = 0; for (Producer producer : producers.values()) count += producer.intervals.size(); return count;
    }
    @Override public synchronized void close() { closed = true; scope = null; producers.clear(); origins.clear(); }

    private Receipt failed(Sample sample, Refusal refusal, long admittedAt) {
        lastDeliveryIdentity = Math.max(lastDeliveryIdentity, sample.deliveryIdentity);
        gap(); return receipt(sample, Admission.GAP, refusal, admittedAt);
    }
    private Receipt receipt(Sample sample, Admission admission, Refusal refusal, long at) {
        return new Receipt(sample, admission, refusal, at, generation, producers.size(), intervalCount());
    }
    private Continuation unknown(SceneEngine.Source source, long owner, long producer, Producer state, Refusal refusal) {
        AxisContinuation unknown = new AxisContinuation(false, 0, 0, 0);
        Origin origin = origins.get(source.sequence);
        long admittedAt = origin != null && origin.source == source ? origin.admittedAt : -1;
        return new Continuation(source, admittedAt, owner, producer, generation, state, unknown, unknown, refusal, 0, 0);
    }
    private void advanceGeneration() {
        if (generation == Long.MAX_VALUE) { close(); throw new IllegalStateException("scroll generation exhausted"); }
        generation++;
    }
}
