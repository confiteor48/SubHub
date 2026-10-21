package com.subhub.app.overlay;

import org.junit.Test;
import static org.junit.Assert.*;

/** Exact provider-coordinate equality is not permission for a forward correction to reverse. */
public final class ZeroResidualScrollTest {
    private static EventScrollTrajectory acquired(int sign) {
        EventScrollTrajectory value = new EventScrollTrajectory();
        value.reset(0, 0, 1000);
        value.measure(sign*456, sign*456, 1120, 1120, 2992);
        value.measure(sign*936, sign*480, 1240, 1240, 2992);
        return value;
    }

    @Test public void zeroResidualShortGapRetainsAdmissionVelocityWithoutReversingBeforeExpiry() {
        for (int sign : new int[]{-1, 1}) {
            EventScrollTrajectory value = acquired(sign);
            float at = value.position(1256), velocity = value.velocity(1256);
            float delta = at-sign*936;
            value.measure(at, delta, 1244, 1256, 2992);
            assertEquals(at, value.position(1256), Math.ulp(at));
            assertEquals(velocity, value.velocity(1256), 2*Math.ulp(velocity));
            float peak = value.predictionAmplitude();
            assertTrue(sign*peak > 0 && Math.abs(peak) <= Math.abs(delta)*1.25f);
            float previous = at;
            for (int age=1; age<=32; age++) {
                float position = value.position(1256+age), speed = value.velocity(1256+age);
                assertTrue("zero-residual live movement reversed: age="+age+" velocity="+speed,
                        sign*(position-previous) >= -Math.ulp(position) && sign*speed >= 0);
                assertTrue("no extra distance beyond original forecast cap",
                        sign*(position-at) <= sign*peak+Math.ulp(at));
                previous = position;
            }
            assertEquals(at+peak, value.position(1288), 2*Math.ulp(at));
            float early = value.position(1258);
            assertEquals(at, value.position(1500), 0);
            assertFalse(value.isAnimating(1500));
            assertEquals(early, value.position(1258), 0);
        }
    }

    @Test public void zeroResidualCapBrakeDoesNotDelaySubsequentMeasuredStopReversalOrReset() {
        for (int sign : new int[]{-1, 1}) for (int delta : new int[]{0, -60}) {
            EventScrollTrajectory value = acquired(sign);
            float at = value.position(1256);
            value.measure(at, at-sign*936, 1244, 1256, 2992);
            float before = value.position(1260);
            value.measure(at+sign*delta, sign*delta, 1260, 1260, 2992);
            assertEquals(before, value.position(1260), 2*Math.ulp(before));
            assertEquals(at+sign*delta, value.position(1292), 2*Math.ulp(at));
            assertEquals(0, value.velocity(1292), 0);
            assertFalse(value.isAnimating(1292));
            value.reset(sign*12, 0, 1300);
            assertEquals(sign*12, value.position(1301), 0);
            assertFalse(value.isAnimating(1301));
        }
    }
}
