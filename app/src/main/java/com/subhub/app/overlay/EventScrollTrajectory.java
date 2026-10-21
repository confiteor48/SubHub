package com.subhub.app.overlay;

/** Continuous display-only reconstruction of sparse scroll events; never camera authority. */
final class EventScrollTrajectory {
    private static final float CORRECTION_RATE = .14f;
    private static final float ACQUISITION_CORRECTION_RATE = .22f;
    private static final long CORRECTION_END_MS = 160;
    private static final float BRAKE_MS = 32;
    private static final float RETURN_MS = 96;
    private static final float MEASURED_BRAKE_MS = 32;
    private float exact, speed, error, errorVelocity, coastMs, peak, returnStartMs;
    private float initialPosition, initialVelocity;
    private long anchoredAt, lastSourceTime = -1;
    private boolean sampled;
    private boolean braking;
    private boolean holdingOvershoot;
    private boolean constrainLagCorrection;
    private boolean zeroResidualBrake;
    private float zeroResidualVelocity, zeroResidualDuration;
    private float heldPosition, heldDirection;
    private float lastObservedSpeed;
    private int samplesReceived;
    private float correctionRate = CORRECTION_RATE;

    void reset(float position, float velocity, long now) {
        exact = initialPosition = position;
        initialVelocity = velocity;
        speed = error = errorVelocity = coastMs = peak = returnStartMs = 0;
        anchoredAt = now;
        lastSourceTime = -1;
        sampled = false;
        braking = false;
        holdingOvershoot = false;
        constrainLagCorrection = false;
        zeroResidualBrake = false;
        heldPosition = heldDirection = 0;
        lastObservedSpeed = 0;
        samplesReceived = 0;
    }

    void measure(float measured, float delta, long sourceTime, long now, int viewportSize) {
        float displayed = position(now);
        float displayedVelocity = velocity(now);
        zeroResidualBrake = false;
        float fallbackInterval = 114;
        long gap = lastSourceTime < 0 ? Math.round(fallbackInterval) : sourceTime - lastSourceTime;
        if (gap > 250) samplesReceived = 0;
        boolean firstObservation = samplesReceived == 0;
        correctionRate = samplesReceived < 2 ? ACQUISITION_CORRECTION_RATE : CORRECTION_RATE;
        boolean ordered = gap > 0;
        float interval = gap > 0 && gap <= 250 ? gap : fallbackInterval;
        float deliveryAge = Math.max(0L, now - sourceTime);
        boolean expired = !ordered || deliveryAge >= interval + 24;
        float nextSpeed = ordered ? delta / interval : 0;
        float observedSpeed = nextSpeed;
        boolean sameDirection = sampled && gap > 0 && gap <= 250 && delta != 0
                && Math.signum(observedSpeed) == Math.signum(lastObservedSpeed);
        if (sampled && gap > 0 && gap <= 250 && lastObservedSpeed != 0) {
            if (Math.signum(nextSpeed) != Math.signum(lastObservedSpeed)) nextSpeed = 0;
            else if (Math.abs(nextSpeed) < Math.abs(lastObservedSpeed)) {
                nextSpeed = Math.signum(nextSpeed)
                        * Math.max(0, 2 * Math.abs(nextSpeed) - Math.abs(lastObservedSpeed));
            }
        }
        lastObservedSpeed = expired ? 0 : observedSpeed;
        // Android reports movement which happened at sourceTime, not at delivery. A late
        // callback must not restart an already-expired fling for another whole interval.
        if (expired) nextSpeed = 0;
        // There is no pre-scroll observation to interpolate at acquisition. Keep the
        // first known position immediate. A multi-screen discontinuity is not a fling
        // that should be animated across unrelated content either.
        boolean discontinuity = !sampled || gap > 250
                || Math.abs(measured - displayed) > Math.max(96f, viewportSize * .5f);
        if (discontinuity) {
            displayed = measured;
            displayedVelocity = nextSpeed;
        }
        exact = measured;
        speed = nextSpeed;
        // A full inter-event forecast follows a steady scroll instead of barely moving
        // between bursts. A finite travel budget and brake/return handle missing input.
        // One event alone does not establish sustained motion. Keep its forecast small;
        // the next observation can then acquire velocity without delaying known coverage.
        float budget = Math.min(Math.abs(delta) * (firstObservation ? .25f : 1.25f),
                Math.max(24f, Math.max(1, viewportSize) * .18f));
        float requestedCoast = Math.max(40f, Math.min(180f, interval * 1.15f));
        float returnDelay = interval + 24;
        float travelTime = Math.abs(speed) < .001f ? 0 : budget / Math.abs(speed);
        coastMs = Math.max(0, Math.min(requestedCoast, travelTime - BRAKE_MS / 2));
        if (Math.abs(speed) > .001f && travelTime < BRAKE_MS / 2) {
            speed = Math.copySign(budget / (BRAKE_MS / 2), speed);
        }
        peak = speed * (coastMs + BRAKE_MS / 2);
        // Hitting the travel cap early is not evidence that scrolling reversed. Hold
        // the cap through the expected next sample plus ordinary callback jitter.
        returnStartMs = Math.max(coastMs + BRAKE_MS, returnDelay);
        error = displayed - exact;
        errorVelocity = displayedVelocity - speed;
        constrainLagCorrection = !expired && sameDirection && error * delta < 0;
        // Slower forward input is not reverse input. An old forecast can already be
        // ahead of the newest measured coordinate. Pulling that error back immediately
        // makes masks reverse while the page continues forward. Hold that display-only
        // lead until the bounded forecast catches it, or until fresh input expires.
        // A real stop/reversal, reset, or discontinuity still corrects to authority.
        holdingOvershoot = !expired && sameDirection && error * delta > 0;
        heldPosition = displayed;
        heldDirection = Math.signum(delta);
        braking = Math.abs(speed) < .001f;
        if (braking) {
            // Stop/reversal measurements supersede the old velocity. A monotone,
            // two-frame brake avoids a fresh forecast and a later return bounce.
            float limit = 3 * Math.abs(error) / MEASURED_BRAKE_MS;
            errorVelocity = error * displayedVelocity >= 0 ? 0
                    : Math.copySign(Math.min(Math.abs(displayedVelocity), limit), displayedVelocity);
        }
        if (!expired && sameDirection && !discontinuity && !braking && error == 0
                && Math.signum(displayedVelocity) == Math.signum(speed)
                && Math.abs(displayedVelocity) > Math.abs(speed) && peak * displayedVelocity > 0) {
            float duration = 2 * Math.abs(peak) / Math.abs(displayedVelocity);
            // Preserve the real admission velocity without borrowing more travel or time.
            // This branch replaces both forecast and correction, then rejoins the old cap.
            if (Float.isFinite(duration) && duration > 0 && duration <= coastMs + BRAKE_MS
                    && duration <= returnStartMs) {
                zeroResidualBrake = true;
                zeroResidualVelocity = displayedVelocity;
                zeroResidualDuration = duration;
            }
        }
        anchoredAt = now;
        lastSourceTime = Math.max(lastSourceTime, sourceTime);
        sampled = true;
        samplesReceived = Math.min(2, samplesReceived + 1);
    }

    float position(long now) {
        if (!sampled) return initialPosition;
        float age = Math.max(0L, now - anchoredAt);
        if (zeroResidualBrake) {
            if (age < zeroResidualDuration) {
                return exact + zeroResidualVelocity * age * (1 - age / (2 * zeroResidualDuration));
            }
            float t = Math.max(0, Math.min(1, (age - returnStartMs) / RETURN_MS));
            return exact + peak * (1 - smooth(t));
        }
        if (holdingOvershoot) {
            if (age <= returnStartMs) return furthest(heldPosition, exact + forecast(age));
            float t = Math.min(1, (age - returnStartMs) / RETURN_MS);
            return exact + heldPeakOffset() * (1 - smooth(t));
        }
        return exact + forecast(age) + correction(age);
    }

    float velocity(long now) {
        if (!sampled) return initialVelocity;
        float age = Math.max(0L, now - anchoredAt);
        if (zeroResidualBrake) {
            if (age < zeroResidualDuration) return zeroResidualVelocity * (1 - age / zeroResidualDuration);
            float t = Math.max(0, Math.min(1, (age - returnStartMs) / RETURN_MS));
            return -peak * (30 * t * t * (t * (t - 2) + 1)) / RETURN_MS;
        }
        if (holdingOvershoot) {
            if (age <= returnStartMs) {
                return heldDirection * (exact + forecast(age) - heldPosition) > 0
                        ? forecastVelocity(age) : 0;
            }
            float t = Math.min(1, (age - returnStartMs) / RETURN_MS);
            return -heldPeakOffset() * (30 * t * t * (t * (t - 2) + 1)) / RETURN_MS;
        }
        if (braking) return brakeVelocity(age);
        float correction = age >= CORRECTION_END_MS || hasCaughtForecast(age) ? 0
                : (float) ((errorVelocity - correctionRate
                * (errorVelocity + correctionRate * error) * age)
                * Math.exp(-correctionRate * age));
        return forecastVelocity(age) + correction;
    }

    private float correction(float age) {
        if (braking) {
            float t = Math.min(1, age / MEASURED_BRAKE_MS);
            return error * (2 * t * t * t - 3 * t * t + 1)
                    + errorVelocity * MEASURED_BRAKE_MS * (t * t * t - 2 * t * t + t);
        }
        if (age >= CORRECTION_END_MS || hasCaughtForecast(age)) return 0;
        return (float) ((error + (errorVelocity + correctionRate * error) * age)
                * Math.exp(-correctionRate * age));
    }

    private float forecast(float age) {
        if (age <= coastMs) return speed * age;
        float brakeAge = age - coastMs;
        if (brakeAge <= BRAKE_MS) {
            return speed * (coastMs + brakeAge - brakeAge * brakeAge / (2 * BRAKE_MS));
        }
        float t = Math.max(0, Math.min(1f, (age - returnStartMs) / RETURN_MS));
        return peak * (1 - smooth(t));
    }

    private float forecastVelocity(float age) {
        if (age <= coastMs) return speed;
        float brakeAge = age - coastMs;
        if (brakeAge <= BRAKE_MS) return speed * (1 - brakeAge / BRAKE_MS);
        float t = Math.max(0, Math.min(1f, (age - returnStartMs) / RETURN_MS));
        return -peak * (30 * t * t * (t * (t - 2) + 1)) / RETURN_MS;
    }

    boolean isAnimating(long now) {
        if (!sampled) return false;
        if (holdingOvershoot) return now - anchoredAt < returnStartMs + RETURN_MS;
        if (braking) return Math.abs(error) > .001f && now - anchoredAt < MEASURED_BRAKE_MS;
        return now - anchoredAt < Math.max(CORRECTION_END_MS, returnStartMs + RETURN_MS)
                && (Math.abs(error) > .001f || Math.abs(errorVelocity) > .001f
                || Math.abs(peak) > .001f);
    }

    float predictionAmplitude() { return holdingOvershoot ? heldPeakOffset() : peak; }
    long predictionPeakMillis() {
        if (zeroResidualBrake) return Math.round(zeroResidualDuration);
        return holdingOvershoot && heldDirection * (heldPosition - exact - peak) >= 0
                ? 0 : Math.round(coastMs + BRAKE_MS);
    }

    private float furthest(float a, float b) { return heldDirection > 0 ? Math.max(a, b) : Math.min(a, b); }
    private float heldPeakOffset() { return furthest(heldPosition, exact + peak) - exact; }

    private float brakeVelocity(float age) {
        float t = Math.min(1, age / MEASURED_BRAKE_MS);
        return error * (6 * t * t - 6 * t) / MEASURED_BRAKE_MS
                + errorVelocity * (3 * t * t - 4 * t + 1);
    }

    private boolean hasCaughtForecast(float age) {
        if (!constrainLagCorrection) return false;
        float remaining = error + (errorVelocity + correctionRate * error) * age;
        // A lagging continuity error may catch the forecast, but must not cross
        // ahead and then decay backwards while the provider still moves forward.
        return error < 0 ? remaining >= 0 : remaining <= 0;
    }

    private static float smooth(float t) { return t * t * t * (t * (t * 6 - 15) + 10); }
}
