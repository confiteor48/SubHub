package com.subhub.app.overlay;

import org.junit.Test;

import static org.junit.Assert.*;

/** A delayed callback is historical movement, not permission for a fresh fling. */
public final class ScrollDeliveryAgeTest {
    @Test public void expiredFirstEventAppliesKnownMovementWithoutStartingPrediction() {
        for (int direction : new int[]{-1, 1}) {
            EventScrollTrajectory motion = new EventScrollTrajectory();
            motion.reset(0, 0, 900);
            motion.measure(direction * 240, direction * 240, 1000, 1400, 2992);
            assertEquals(direction * 240, motion.position(1400), .001f);
            assertEquals(0, motion.predictionAmplitude(), .001f);
            for (long time : new long[]{1408, 1416, 1432, 1600}) {
                assertEquals(direction * 240, motion.position(time), .001f);
                assertFalse(motion.isAnimating(time));
            }
        }
    }

    @Test public void expiredContinuationSettlesToKnownPositionWithoutAnotherForecast() {
        for (int direction : new int[]{-1, 1}) {
            EventScrollTrajectory motion = new EventScrollTrajectory();
            motion.reset(0, 0, 900);
            motion.measure(direction * 240, direction * 240, 1000, 1000, 2992);
            motion.measure(direction * 480, direction * 240, 1120, 1120, 2992);
            float before = motion.position(1500);
            motion.measure(direction * 720, direction * 240, 1240, 1500, 2992);
            assertEquals(before, motion.position(1500), .001f);
            assertEquals(0, motion.predictionAmplitude(), .001f);
            assertEquals(direction * 720, motion.position(1532), .001f);
            assertEquals(direction * 720, motion.position(1800), .001f);
            assertFalse(motion.isAnimating(1532));
        }
    }

    @Test public void sourceLifetimeBoundaryAndOutOfOrderEventsCannotStartForecasts() {
        for (long source : new long[]{1120, 1000, 990}) {
            EventScrollTrajectory motion = new EventScrollTrajectory();
            motion.reset(0, 0, 900);
            motion.measure(-240, -240, 1000, 1000, 2992);
            motion.measure(-480, -240, source, 1264, 2992);
            assertEquals(0, motion.predictionAmplitude(), .001f);
            assertEquals(-480, motion.position(1296), .001f);
            assertFalse(motion.isAnimating(1296));
        }
    }

    @Test public void lateReverseDoesNotContinueTheOldDirection() {
        for (int direction : new int[]{-1, 1}) {
            ViewportMotion motion = new ViewportMotion();
            motion.reset(0, 0, 900);
            motion.addDelta(0, direction * 240, 1000, 1344, 2992, true, 1000);
            motion.addDelta(0, direction * 240, 1120, 1344, 2992, true, 1120);
            motion.addDelta(0, -direction * 120, 1500, 1344, 2992, true, 1240);
            assertEquals(direction * 360, motion.position(1532).y, .001f);
            assertEquals(0, motion.predictionAmplitude().y, .001f);
            assertFalse(motion.isAnimating(1532));
        }
    }

}
