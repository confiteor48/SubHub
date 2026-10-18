package com.subhub.app.service;

import com.subhub.app.censor.engine.SceneEngine;
import com.subhub.app.censor.engine.ScrollMotionLedger;
import com.subhub.app.overlay.ViewportMotion;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reuses the old display trajectory, never the old detector or scene owner. All coordinates here
 * are EVENT ESTIMATES, not pixel/member correspondence. Advisory owner bounds only limit the
 * presentation clip; they cannot prove that a fixed overlay inside those bounds scrolls with it.
 * No pixels/Android handles, identity, retention or accounting authority are retained here.
 */
public final class ReplacementScrollPresentation {
    private static final int MAXIMUM_ORIGINS = 32, MAXIMUM_PRODUCERS = 16, MAXIMUM_OWNERS = 16;
    private static final int MAXIMUM_INTERVALS = 192;

    public static final class Pose {
        public final SceneEngine.Region region, clip;
        private Pose(SceneEngine.Region region, SceneEngine.Region clip) { this.region = region; this.clip = clip; }
    }
    private static final class Origin {
        final long epoch, geometry, at;
        final Map<Long, Position> baselines = new LinkedHashMap<>();
        Origin(long epoch, long geometry, long at) { this.epoch = epoch; this.geometry = geometry; this.at = at; }
    }
    private static final class Producer {
        final long owner;
        ScrollMotionLedger.Sample last;
        Producer(ScrollMotionLedger.Sample sample) { owner = sample.owner; last = sample; }
    }
    private static final class Position {
        final long at;
        final float x, y;
        Position(long at, float x, float y) { this.at = at; this.x = x; this.y = y; }
    }
    private static final class Surface {
        final SceneEngine.Region bounds;
        final ViewportMotion motion = new ViewportMotion();
        final ArrayDeque<Position> history = new ArrayDeque<>();
        final ScrollCompanionDeduplicator companions = new ScrollCompanionDeduplicator();
        float x, y;
        long discardedThrough = -1, trustedProducer, lastAdmittedAt;
        Surface(SceneEngine.Region bounds, boolean nativeSpline, float density, float friction, long now) {
            this.bounds = bounds; motion.reset(0, 0, now); motion.setNativeSpline(nativeSpline, density, friction, now);
            lastAdmittedAt = now;
        }
        Position at(long time) {
            if (time <= discardedThrough) return null;
            Position previous = new Position(0, 0, 0);
            for (Position next : history) {
                if (next.at > time) {
                    // Source-clock interpolation is an estimate of the straddling interval, not
                    // evidence that those intermediate pixels or their velocity were observed.
                    if (previous.at == 0 || next.at == previous.at) return previous;
                    float t = (time - previous.at) / (float) (next.at - previous.at);
                    return new Position(time, previous.x + (next.x - previous.x) * t,
                            previous.y + (next.y - previous.y) * t);
                }
                previous = next;
            }
            return previous;
        }
        Position estimateAt(long at) {
            if (history.isEmpty()) return null;
            if (at < lastAdmittedAt) return at(at);
            ViewportMotion.Position predicted = motion.position(at);
            return new Position(at, predicted.x, predicted.y);
        }
        void record(long at, long admittedAt) {
            lastAdmittedAt = admittedAt;
            history.addLast(new Position(at, x, y));
            while (history.size() > MAXIMUM_INTERVALS) discardedThrough = history.removeFirst().at;
        }
    }

    private final Map<Long, Origin> origins = new LinkedHashMap<>();
    private final Map<Long, Producer> producers = new LinkedHashMap<>();
    private final Map<Long, Surface> surfaces = new LinkedHashMap<>();
    private long geometry = -1, generation = -1, revision;
    private int window = -1;
    private boolean nativePackage;
    private float density = 1, friction = .015f;

    public synchronized void reset(long geometry, int window) {
        this.geometry = geometry; this.window = window; generation = -1; nativePackage = false;
        origins.clear(); producers.clear(); surfaces.clear();
        revision++;
    }

    /** API-reported origin only; never substitute completion/publication time for a missing stamp. */
    public synchronized void captured(long epoch, long geometry, long request, long reportedAt, long callbackAt) {
        registerOrigin(epoch, geometry, request, reportedAt, callbackAt, false);
    }

    /**
     * Only inside the source-prepared hook BEFORE its success callback can admit this request's
     * Source. The request cannot yet be an existing scene member. General/late metadata must use
     * captured(), since registering an already-present member may change its previously unknown pose.
     */
    public synchronized void preparedBeforeAdmission(long epoch, long geometry, long request, long reportedAt, long callbackAt) {
        registerOrigin(epoch, geometry, request, reportedAt, callbackAt, true);
    }

    private void registerOrigin(long epoch, long geometry, long request, long reportedAt, long callbackAt, boolean beforeAdmission) {
        if (this.geometry != geometry || request <= 0 || reportedAt < 0 || reportedAt > callbackAt) return;
        if (origins.containsKey(request)) return;
        boolean evicted = false;
        while (origins.size() >= MAXIMUM_ORIGINS) { origins.remove(origins.keySet().iterator().next()); evicted = true; }
        Origin origin = new Origin(epoch, geometry, reportedAt);
        for (Map.Entry<Long, Surface> entry : surfaces.entrySet()) {
            Position baseline = entry.getValue().estimateAt(reportedAt);
            if (baseline != null) origin.baselines.put(entry.getKey(), baseline);
        }
        // Freeze the source's available presentation estimate. A fresh frame must not
        // inherit pre-capture forecast travel, nor be rebased when a later event arrives.
        // This remains advisory event phase, never a measured pixel/camera timestamp.
        origins.put(request, origin);
        // Before admission no current member can refer to this fresh request. Its isolated
        // metadata cannot invalidate an unrelated draw. Eviction and late/general registration
        // can change an existing member's pose, and retain the conservative revision fence.
        if (!beforeAdmission || evicted) revision++;
    }

    /** Scope configuration, never an unvalidated callback hint or a mode switch for other owners. */
    public synchronized void configureNativeContext(String packageName, float density, float friction) {
        if (!surfaces.isEmpty()) throw new IllegalStateException("Native context requires a fresh scope");
        if (!Float.isFinite(density) || density <= 0 || !Float.isFinite(friction) || friction <= 0)
            throw new IllegalArgumentException("Native curve configuration");
        this.density = density; this.friction = friction;
        nativePackage = NativeScrollCurvePolicy.supports(packageName, "androidx.recyclerview.widget.RecyclerView");
    }

    public synchronized boolean observe(ScrollMotionLedger.Receipt receipt) {
        ScrollMotionLedger.Sample sample = receipt.sample;
        if (sample == null || sample.scope.geometry != geometry || sample.scope.windowId != window
                || receipt.admission == ScrollMotionLedger.Admission.STALE
                || receipt.admission == ScrollMotionLedger.Admission.CLOSED) return false;
        if (receipt.admission == ScrollMotionLedger.Admission.GAP
                || generation != -1 && generation != receipt.generation) {
            origins.clear(); producers.clear(); surfaces.clear();
            revision++;
        }
        generation = receipt.generation;
        if (receipt.admission == ScrollMotionLedger.Admission.GAP) return true;
        if (receipt.admission == ScrollMotionLedger.Admission.DUPLICATE || sample.reportedViewport == null
                || sample.owner <= 0 || sample.producer <= 0) return false;
        Producer producer = producers.get(sample.producer);
        if (producer != null && (producer.owner != sample.owner || sample.sourceAtMillis < producer.last.sourceAtMillis)) return false;
        Long dx = delta(sample.x, producer == null ? null : producer.last.x);
        Long dy = delta(sample.y, producer == null ? null : producer.last.y);
        boolean absolute = producer != null
                && (sample.x.absoluteKnown && producer.last.x.absoluteKnown
                    || sample.y.absoluteKnown && producer.last.y.absoluteKnown);
        if (producer == null) {
            if (producers.size() >= MAXIMUM_PRODUCERS) return false;
            producer = new Producer(sample); producers.put(sample.producer, producer);
        } else producer.last = sample;
        int width = (int) Math.ceil(sample.reportedViewport.right - sample.reportedViewport.left);
        int height = (int) Math.ceil(sample.reportedViewport.bottom - sample.reportedViewport.top);
        if (width <= 0 || height <= 0 || dx != null && Math.abs((double) dx) > width * 2d
                || dy != null && Math.abs((double) dy) > height * 2d) return false;
        Surface surface = surfaces.get(sample.owner);
        if (surface != null && !same(surface.bounds, sample.reportedViewport)) {
            // A moved/resized provider is not the same image-to-surface bridge.
            surfaces.remove(sample.owner); origins.clear(); revision++; return true;
        }
        if (surface == null) {
            if (surfaces.size() >= MAXIMUM_OWNERS) return false;
            surface = new Surface(sample.reportedViewport, nativePackage && sample.ownerKind == ScrollMotionLedger.OwnerKind.RECYCLER_VIEW,
                    density, friction, receipt.admittedAtMillis);
            surfaces.put(sample.owner, surface);
        }
        if (!surface.history.isEmpty() && sample.sourceAtMillis < surface.history.getLast().at) {
            surfaces.remove(sample.owner); origins.clear(); revision++; return true;
        }
        if (dx == null && dy == null) {
            if (!surface.history.isEmpty()) { surfaces.remove(sample.owner); origins.clear(); }
            else surface.record(sample.sourceAtMillis, receipt.admittedAtMillis);
            revision++;
            return true;
        }
        boolean companion = surface.trustedProducer != 0 && surface.trustedProducer != sample.producer;
        boolean duplicate = surface.companions.observe(geometry, sample.owner, sample.producer, width, height,
                absolute, companion, absolute ? AccessibilityScrollMotionResolver.Motion.Evidence.ABSOLUTE
                        : AccessibilityScrollMotionResolver.Motion.Evidence.EXPLICIT,
                dx == null ? 0 : (int) -dx.longValue(), dy == null ? 0 : (int) -dy.longValue(),
                sample.sourceAtMillis, sample.receivedAtMillis);
        if (absolute) surface.trustedProducer = sample.producer;
        if (duplicate) return false;
        // Only this admitted owner's validated provider kind selects its trajectory. Existing
        // setNativeSpline preserves the current displayed position on a genuine kind change.
        surface.motion.setNativeSpline(nativePackage && sample.ownerKind == ScrollMotionLedger.OwnerKind.RECYCLER_VIEW,
                density, friction, receipt.admittedAtMillis);
        if (dx != null) surface.x -= dx;
        if (dy != null) surface.y -= dy;
        surface.motion.addReportedDelta(dx == null ? null : -dx.floatValue(), dy == null ? null : -dy.floatValue(),
                receipt.admittedAtMillis, width, height, sample.sourceAtMillis);
        surface.record(sample.sourceAtMillis, receipt.admittedAtMillis);
        revision++;
        return true;
    }

    /** A per-origin paint estimate only. Native geometry is refreshed independently and never shifted. */
    public synchronized Pose pose(SceneEngine.Mask mask, long now) {
        if (mask.nativeValidated || mask.geometrySource.kind != SceneEngine.SourceKind.IMAGE) return null;
        Origin origin = origins.get(mask.geometrySource.imageRequestIdentity);
        if (origin == null || origin.at > now || origin.epoch != mask.geometrySource.epoch || origin.geometry != geometry) return null;
        Surface selected = null;
        long selectedOwner = 0;
        for (Map.Entry<Long, Surface> entry : surfaces.entrySet()) if (contains(entry.getValue().bounds, mask.displayRegion)) {
            if (selected != null) return null; // Overlapping/nested producers do not become additive cameras.
            selected = entry.getValue(); selectedOwner = entry.getKey();
        }
        if (selected == null) return null;
        Position baseline = origin.baselines.get(selectedOwner);
        if (baseline == null) baseline = selected.at(origin.at);
        if (baseline == null) return null;
        ViewportMotion.Position displayed = selected.motion.position(now);
        float dx = displayed.x - baseline.x, dy = displayed.y - baseline.y;
        if (!Float.isFinite(dx) || !Float.isFinite(dy) || Math.abs(dx) + Math.abs(dy) < .001f) return null;
        return new Pose(mask.displayRegion.translated(dx, dy), selected.bounds);
    }

    public synchronized boolean isAnimating(long now) {
        for (Surface surface : surfaces.values()) if (surface.motion.isAnimating(now)) return true;
        return false;
    }
    public synchronized long revision() { return revision; }
    private static Long delta(ScrollMotionLedger.Coordinate next, ScrollMotionLedger.Coordinate previous) {
        if (next.absoluteKnown && previous != null && previous.absoluteKnown) {
            try { return Math.subtractExact(next.absolute, previous.absolute); }
            catch (ArithmeticException overflow) { return null; }
        }
        return next.deltaKnown ? next.delta : null;
    }
    private static boolean contains(SceneEngine.Region outer, SceneEngine.Region inner) {
        return outer.left <= inner.left && outer.top <= inner.top && outer.right >= inner.right && outer.bottom >= inner.bottom;
    }
    private static boolean same(SceneEngine.Region a, SceneEngine.Region b) {
        return a.left == b.left && a.top == b.top && a.right == b.right && a.bottom == b.bottom;
    }
}
