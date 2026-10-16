package com.subhub.app.overlay;

/**
 * Display-rate reconstruction of sparse Accessibility viewport measurements.
 *
 * <p>Accessibility scroll deltas describe motion that has already happened, but arrive roughly
 * every 100-120 ms. Rendering every delta as a new offset therefore produces a staircase. This
 * class keeps authoritative coordinates separate from a continuous event trajectory. Ordinary
 * continuing samples preserve displayed position/velocity instead of teleporting on delivery.
 * First observations remain immediate; prediction is bounded and eventually returns to authority.
 * Optional measured-anchor presentation remains a separate, freshness-gated path.</p>
 */
public final class ViewportMotion {
    private static final long MIN_FALLBACK_MS = 8L;
    private static final long MAX_FALLBACK_MS = 16L;
    private static final long PRESENTATION_DEFAULT_INTERVAL_MS = 16L;
    private static final long PRESENTATION_MIN_HORIZON_MS = 12L;
    private static final long PRESENTATION_MAX_HORIZON_MS = 32L;
    private static final long PRESENTATION_MAX_GAP_MS = 56L;
    private static final long PRESENTATION_SETTLE_MS = 16L;
    private static final float MIN_POLLED_LEAD_PX = 96f;
    private static final float MAX_POLLED_LEAD_VIEWPORT_FRACTION = 0.45f;
    private static final int MIN_PHASE_LOCK_SAMPLES = 2;

    private final Axis x = new Axis();
    private final Axis y = new Axis();

    public void setNativeSpline(boolean enabled, float density, float friction, long now) {
        x.setNativeSpline(enabled, density, friction, now);
        y.setNativeSpline(enabled, density, friction, now);
    }

    public void reset(float x, float y, long nowMillis) {
        this.x.reset(x, nowMillis);
        this.y.reset(y, nowMillis);
    }

    /** Changes the detector coordinate origin without forgetting the live motion stream. */
    void rebase(float x, float y, long nowMillis) {
        this.x.rebase(x, nowMillis);
        this.y.rebase(y, nowMillis);
    }

    void addDelta(float dx, float dy, long nowMillis) {
        addDelta(dx, dy, nowMillis, Integer.MAX_VALUE, Integer.MAX_VALUE, true);
    }

    void addDelta(
            float dx,
            float dy,
            long nowMillis,
            int viewportWidth,
            int viewportHeight,
            boolean authoritative) {
        addDelta(dx, dy, nowMillis, viewportWidth, viewportHeight, authoritative, nowMillis);
    }

    public void addDelta(float dx, float dy, long nowMillis, int viewportWidth, int viewportHeight,
            boolean authoritative, long eventSourceMillis) {
        x.addDelta(dx, nowMillis, viewportWidth, authoritative, eventSourceMillis);
        y.addDelta(dy, nowMillis, viewportHeight, authoritative, eventSourceMillis);
    }

    /** Missing provider axes are not stationary measurements and must not brake that axis. */
    public void addReportedDelta(Float dx, Float dy, long nowMillis, int width, int height, long sourceMillis) {
        if (dx != null) x.addDelta(dx, nowMillis, width, true, sourceMillis);
        if (dy != null) y.addDelta(dy, nowMillis, height, true, sourceMillis);
    }

    /** Adds a fast, presentation-only motion sample without changing authoritative coordinates. */
    void addPresentationDelta(
            float dx,
            float dy,
            long nowMillis,
            int viewportWidth,
            int viewportHeight) {
        x.addPresentationDelta(dx, nowMillis, viewportWidth);
        y.addPresentationDelta(dy, nowMillis, viewportHeight);
    }

    /** Brakes presentation prediction at a measured zero-motion sample. */
    void settlePresentation(long nowMillis) {
        x.settlePresentation(nowMillis);
        y.settlePresentation(nowMillis);
    }

    /** Absolute screen-space measurement; never changes the event-authoritative coordinate. */
    boolean measurePresentation(float px, float py, long readStart, long sourceMillis, long nowMillis,
            int width, int height, int frameIntervalMs) {
        if (!Float.isFinite(px) || !Float.isFinite(py) || sourceMillis > nowMillis
                || nowMillis - readStart > 32L || readStart < 0L || sourceMillis < readStart
                || !x.acceptsMeasurement(px, readStart, sourceMillis, width)
                || !y.acceptsMeasurement(py, readStart, sourceMillis, height)) return false;
        int horizon = Math.max(8, Math.min(24, frameIntervalMs));
        x.measurePresentation(px, readStart, sourceMillis, horizon);
        y.measurePresentation(py, readStart, sourceMillis, horizon);
        return true;
    }

    void clearMeasuredPresentation(long nowMillis) {
        x.clearMeasuredPresentation(nowMillis);
        y.clearMeasuredPresentation(nowMillis);
    }

    public Position position(long nowMillis) {
        return new Position(x.position(nowMillis), y.position(nowMillis));
    }

    boolean hasMeasuredPresentation(long nowMillis) {
        return x.absoluteMeasurement && y.absoluteMeasurement
                && nowMillis >= x.absoluteSourceMillis && nowMillis >= y.absoluteSourceMillis
                && nowMillis - x.absoluteSourceMillis <= 48L
                && nowMillis - y.absoluteSourceMillis <= 48L;
    }

    /** Includes the stale measured fade; that fade is not an event-camera prediction. */
    boolean isMeasuredPresentationMode() {
        return x.absoluteMeasurement || y.absoluteMeasurement;
    }

    public boolean isAnimating(long nowMillis) {
        return x.isAnimating(nowMillis) || y.isAnimating(nowMillis);
    }

    Position predictionAmplitude() {
        return new Position(x.predictionAmplitude(), y.predictionAmplitude());
    }

    long predictionPeakMillis() {
        return Math.max(x.predictionPeakMillis(), y.predictionPeakMillis());
    }

    private static final class Axis {
        private final EventScrollTrajectory eventTrajectory = new EventScrollTrajectory();
        private NativeScrollTrajectory nativeTrajectory;
        private boolean eventMode;
        private float exact;
        private float segmentStart;
        private float segmentTarget;
        private float startVelocity;
        private long anchorTime;
        private long trajectoryDuration;
        private long lastEventTime;
        private long lastPresentationSampleTime;
        private float pollMeasured;
        private float pollVelocity;
        private int consecutivePollSamples;
        private boolean pollMoving;
        private boolean absoluteMeasurement;
        private long absoluteSourceMillis = -1L;
        private long absoluteReadStartMillis, measurementOriginMillis, lastAuthoritySourceMillis;

        void setNativeSpline(boolean enabled, float density, float friction, long now) {
            if (enabled == (nativeTrajectory != null)) return;
            float displayed = position(now);
            eventTrajectory.reset(displayed, 0, now);
            nativeTrajectory = enabled ? new NativeScrollTrajectory(density, friction) : null;
            if (nativeTrajectory != null) nativeTrajectory.reset(displayed, now);
            // Keep authoritative camera and current displayed position intact on a mode switch.
        }

        boolean acceptsMeasurement(float value, long readStart, long sourceMillis, int viewportSize) {
            return sourceMillis > absoluteSourceMillis
                    && readStart >= measurementOriginMillis && readStart >= lastAuthoritySourceMillis
                    && Math.abs(value - exact) <= Math.max(96f, viewportSize * .5f);
        }

        void measurePresentation(float value, long readStart, long sourceMillis, int horizon) {
            eventMode = false;
            long gap = sourceMillis - absoluteSourceMillis;
            float velocity = absoluteMeasurement && gap > 0L && gap <= 64L
                    ? (value - pollMeasured) / gap : 0f;
            pollMeasured = value;
            pollVelocity = velocity;
            consecutivePollSamples = MIN_PHASE_LOCK_SAMPLES;
            lastPresentationSampleTime = sourceMillis;
            absoluteSourceMillis = sourceMillis;
            absoluteReadStartMillis = readStart;
            absoluteMeasurement = true;
            pollMoving = Math.abs(velocity) > .001f;
            segmentStart = value;
            segmentTarget = value + clamp(velocity * horizon, -32f, 32f);
            startVelocity = (segmentTarget - value) / horizon;
            trajectoryDuration = horizon;
            anchorTime = sourceMillis;
        }

        void clearMeasuredPresentation(long nowMillis) {
            if (!absoluteMeasurement) return;
            float displayed = position(nowMillis);
            absoluteMeasurement = false;
            absoluteSourceMillis = -1L;
            lastPresentationSampleTime = 0L;
            consecutivePollSamples = 0;
            pollMoving = false;
            segmentStart = displayed;
            segmentTarget = exact;
            startVelocity = 0f;
            anchorTime = nowMillis;
            trajectoryDuration = 16L;
        }

        void addPresentationDelta(float delta, long nowMillis, int viewportSize) {
            if (absoluteMeasurement) clearMeasuredPresentation(nowMillis);
            if (Math.abs(delta) < 0.5f) return;
            eventMode = false;
            long gap = lastPresentationSampleTime <= 0L
                    ? Long.MAX_VALUE : nowMillis - lastPresentationSampleTime;
            boolean continuing = gap > 0L && gap <= PRESENTATION_MAX_GAP_MS;
            long interval = continuing ? gap : PRESENTATION_DEFAULT_INTERVAL_MS;
            if (continuing) {
                consecutivePollSamples++;
                pollMeasured += delta;
            } else {
                consecutivePollSamples = 1;
                // A new anchor baseline begins at the last coordinate-authoritative position,
                // not at a speculative event trajectory that may already be ahead of it.
                pollMeasured = exact + delta;
            }
            float maximumLead = Math.max(MIN_POLLED_LEAD_PX,
                    Math.max(1, viewportSize) * MAX_POLLED_LEAD_VIEWPORT_FRACTION);
            pollMeasured = clamp(pollMeasured, exact - maximumLead, exact + maximumLead);
            pollVelocity = delta / Math.max(1f, interval);

            // The page has already reached pollMeasured. Correct the estimator at mutation time;
            // the correction is first visible on the next vsync, so there is no extra 32 ms glide
            // behind a measurement. Subsequent frames continue the observed velocity until the
            // next 16 ms sample instead of standing still between samples.
            // This is a measured compositor phase, not a prediction. Showing a bounded fraction
            // of it recreates the very mid-transit lag the poller exists to remove. The robust
            // multi-anchor median owns presentation immediately; interpolation is only used to
            // continue its measured velocity until the next display-rate sample.
            segmentStart = pollMeasured;
            trajectoryDuration = Math.max(PRESENTATION_MIN_HORIZON_MS,
                    Math.min(PRESENTATION_MAX_HORIZON_MS, interval));
            segmentTarget = clamp(
                    pollMeasured + pollVelocity * trajectoryDuration,
                    exact - maximumLead, exact + maximumLead);
            startVelocity = pollVelocity;
            anchorTime = nowMillis;
            lastPresentationSampleTime = nowMillis;
            pollMoving = true;
        }

        void settlePresentation(long nowMillis) {
            if (lastPresentationSampleTime <= 0L) return;
            float displayed = position(nowMillis);
            segmentStart = displayed;
            segmentTarget = pollMeasured;
            startVelocity = 0f;
            anchorTime = nowMillis;
            trajectoryDuration = Math.abs(segmentTarget - segmentStart) < 0.5f
                    ? 0L : PRESENTATION_SETTLE_MS;
            lastPresentationSampleTime = nowMillis;
            pollVelocity = 0f;
            pollMoving = false;
        }

        void reset(float value, long nowMillis) {
            eventMode = false;
            eventTrajectory.reset(value, 0, nowMillis);
            if (nativeTrajectory != null) nativeTrajectory.reset(value, nowMillis);
            absoluteMeasurement = false;
            absoluteSourceMillis = -1L;
            measurementOriginMillis = nowMillis;
            lastAuthoritySourceMillis = nowMillis;
            exact = value;
            segmentStart = value;
            segmentTarget = value;
            startVelocity = 0f;
            anchorTime = nowMillis;
            trajectoryDuration = 0L;
            lastEventTime = 0L;
            lastPresentationSampleTime = 0L;
            pollMeasured = value;
            pollVelocity = 0f;
            consecutivePollSamples = 0;
            pollMoving = false;
        }

        void rebase(float value, long nowMillis) {
            absoluteMeasurement = false;
            absoluteSourceMillis = -1L;
            measurementOriginMillis = nowMillis;
            float velocityBefore = velocity(nowMillis);
            eventMode = false;
            eventTrajectory.reset(value, 0, nowMillis);
            if (nativeTrajectory != null) nativeTrajectory.reset(value, nowMillis);
            boolean pollingLive = lastPresentationSampleTime > 0L
                    && nowMillis - lastPresentationSampleTime <= PRESENTATION_MAX_GAP_MS;
            exact = value;
            segmentStart = value;
            pollMeasured = value;
            if (pollingLive && pollMoving) {
                startVelocity = velocityBefore != 0f ? velocityBefore : pollVelocity;
                trajectoryDuration = PRESENTATION_DEFAULT_INTERVAL_MS;
                segmentTarget = value + startVelocity * trajectoryDuration;
            } else {
                segmentTarget = value;
                startVelocity = 0f;
                trajectoryDuration = 0L;
            }
            anchorTime = nowMillis;
            // Keep lastEventTime and lastPresentationSampleTime. Detector publication
            // is a coordinate rebase, not the end of the user's scroll gesture.
        }

        void addDelta(
                float delta,
                long nowMillis,
                int viewportSize,
                boolean authoritative,
                long eventSourceMillis) {
            // Animation starts at delivery, never retroactively at the source timestamp.
            // Source time still estimates event velocity and fences stale anchor reads.
            long sampleMillis = lastEventTime > 0L
                    ? Math.max(lastEventTime, nowMillis) : nowMillis;
            float velocityBefore = velocity(sampleMillis);
            float displayedBefore = position(sampleMillis);
            exact += delta;
            long sourceTime = Math.max(0L, Math.min(nowMillis, eventSourceMillis));
            lastAuthoritySourceMillis = Math.max(lastAuthoritySourceMillis, sourceTime);
            if (absoluteMeasurement && sampleMillis - absoluteSourceMillis <= 48L
                    && sourceTime <= absoluteReadStartMillis) {
                // This read already measured the page, including some or all of this event.
                // Record authority, but do not add its displacement to measured presentation.
                lastEventTime = sampleMillis;
                return;
            }
            if (absoluteMeasurement) {
                // A newer event (or one overlapping the read interval) supersedes the sample.
                // Use the known absolute event position, never add its entire interval twice.
                displayedBefore = exact;
                consecutivePollSamples = 0;
                lastPresentationSampleTime = 0L;
                pollMoving = false;
                absoluteMeasurement = false;
            }
            long gap = lastEventTime <= 0L
                    ? Long.MAX_VALUE : sampleMillis - lastEventTime;

            if (!authoritative || Math.abs(delta) < 0.5f) {
                eventMode = false;
                segmentStart = displayedBefore;
                segmentTarget = exact;
                startVelocity = 0f;
                anchorTime = sampleMillis;
                trajectoryDuration = Math.max(MIN_FALLBACK_MS,
                        Math.min(MAX_FALLBACK_MS,
                                gap == Long.MAX_VALUE ? MAX_FALLBACK_MS
                                        : Math.round(gap * 0.15f)));
                lastEventTime = sampleMillis;
                return;
            }

            boolean livePolling = consecutivePollSamples >= MIN_PHASE_LOCK_SAMPLES
                    && lastPresentationSampleTime > 0L
                    && nowMillis - lastPresentationSampleTime <= PRESENTATION_MAX_GAP_MS;
            if (livePolling) {
                // The anchor ensemble has already presented this interval. Accessibility remains
                // coordinate authority for trackers/captures, but visually applying the same
                // displacement again creates the observed overshoot. Preserve the poll trajectory
                // while moving; once it has braked, converge any final measurement error in one
                // display frame.
                if (!pollMoving) {
                    segmentStart = displayedBefore;
                    segmentTarget = exact;
                    startVelocity = 0f;
                    anchorTime = sampleMillis;
                    trajectoryDuration = Math.abs(segmentTarget - segmentStart) < 0.5f
                            ? 0L : PRESENTATION_SETTLE_MS;
                    pollMeasured = exact;
                }
                lastEventTime = sampleMillis;
                return;
            }

            if (!eventMode) {
                eventTrajectory.reset(displayedBefore, velocityBefore, sampleMillis);
                if (nativeTrajectory != null) nativeTrajectory.reset(displayedBefore, sampleMillis);
            }
            eventMode = true;
            if (nativeTrajectory != null) nativeTrajectory.measure(exact, delta, sourceTime, sampleMillis, viewportSize);
            else eventTrajectory.measure(exact, delta, sourceTime, sampleMillis, viewportSize);
            lastEventTime = sampleMillis;
            // This event, rather than the tentative single poll sample, owned presentation.
            // Re-anchor the poll estimator so later samples cannot continue from the pre-event
            // coordinate space and pull the overlay backward.
            pollMeasured = nativeTrajectory != null ? nativeTrajectory.position(sampleMillis)
                    : eventTrajectory.position(sampleMillis);
            pollVelocity = 0f;
            consecutivePollSamples = 0;
            lastPresentationSampleTime = 0L;
            pollMoving = false;
        }

        float position(long nowMillis) {
            if (eventMode) return nativeTrajectory != null ? nativeTrajectory.position(nowMillis)
                    : eventTrajectory.position(nowMillis);
            if (absoluteMeasurement && nowMillis - absoluteSourceMillis > 48L) {
                long expiredAge = nowMillis - absoluteSourceMillis - 48L;
                if (expiredAge >= 16L) return exact;
                return segmentTarget + (exact - segmentTarget) * smootherStep(expiredAge / 16f);
            }
            if (trajectoryDuration <= 0L) return segmentTarget;
            long age = Math.max(0L, nowMillis - anchorTime);
            if (age <= trajectoryDuration) {
                float progress = age / (float) trajectoryDuration;
                float endTangent = pollMoving
                        ? startVelocity * trajectoryDuration : 0f;
                float value = hermite(segmentStart, segmentTarget,
                        startVelocity * trajectoryDuration, endTangent, progress);
                return clampBetween(value, segmentStart, segmentTarget);
            }
            return segmentTarget;
        }

        float velocity(long nowMillis) {
            if (eventMode) return nativeTrajectory != null ? nativeTrajectory.velocity(nowMillis)
                    : eventTrajectory.velocity(nowMillis);
            if (trajectoryDuration <= 0L) return 0f;
            long age = Math.max(0L, nowMillis - anchorTime);
            if (age < trajectoryDuration) {
                float progress = age / (float) trajectoryDuration;
                float endTangent = pollMoving
                        ? startVelocity * trajectoryDuration : 0f;
                float tangent = hermiteDerivative(segmentStart, segmentTarget,
                        startVelocity * trajectoryDuration, endTangent, progress);
                return tangent / trajectoryDuration;
            }
            return 0f;
        }

        boolean isAnimating(long nowMillis) {
            if (eventMode) return nativeTrajectory != null ? nativeTrajectory.isAnimating(nowMillis)
                    : eventTrajectory.isAnimating(nowMillis);
            if (absoluteMeasurement) return nowMillis - absoluteSourceMillis < 64L
                    && (Math.abs(segmentTarget - segmentStart) > .01f
                    || Math.abs(segmentTarget - exact) > .01f);
            if (trajectoryDuration <= 0L) return false;
            return nowMillis - anchorTime < trajectoryDuration;
        }

        float predictionAmplitude() {
            return eventMode ? nativeTrajectory != null ? nativeTrajectory.predictionAmplitude()
                    : eventTrajectory.predictionAmplitude() : segmentTarget - exact;
        }

        long predictionPeakMillis() {
            return eventMode ? nativeTrajectory != null ? nativeTrajectory.predictionPeakMillis()
                    : eventTrajectory.predictionPeakMillis() : trajectoryDuration;
        }

    }

    private static float hermite(
            float start,
            float end,
            float startTangent,
            float endTangent,
            float t) {
        float t2 = t * t;
        float t3 = t2 * t;
        return (2f * t3 - 3f * t2 + 1f) * start
                + (t3 - 2f * t2 + t) * startTangent
                + (-2f * t3 + 3f * t2) * end
                + (t3 - t2) * endTangent;
    }

    private static float hermiteDerivative(
            float start,
            float end,
            float startTangent,
            float endTangent,
            float t) {
        float t2 = t * t;
        return (6f * t2 - 6f * t) * start
                + (3f * t2 - 4f * t + 1f) * startTangent
                + (-6f * t2 + 6f * t) * end
                + (3f * t2 - 2f * t) * endTangent;
    }

    private static float smootherStep(float value) {
        float t = clamp(value, 0f, 1f);
        return t * t * t * (t * (t * 6f - 15f) + 10f);
    }

    private static float clampBetween(float value, float first, float second) {
        return clamp(value, Math.min(first, second), Math.max(first, second));
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    public static final class Position {
        public final float x;
        public final float y;

        Position(float x, float y) {
            this.x = x;
            this.y = y;
        }
    }
}
