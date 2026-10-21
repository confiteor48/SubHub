package com.subhub.app.overlay;

import org.junit.Test;
import static org.junit.Assert.*;

/** Positive provider movement must not reverse the live display before stale return. */
public final class ShortGapSlowdownTest {
    @Test public void shortGapSlowdownDoesNotReversePositivePresentationBeforeExpiry() {
        for (int sign : new int[]{-1, 1}) {
            EventScrollTrajectory value = new EventScrollTrajectory();
            value.reset(0, 0, 1000);
            value.measure(sign*456, sign*456, 1120, 1120, 2992);
            value.measure(sign*936, sign*480, 1240, 1240, 2992);
            value.measure(sign*960, sign*24, 1248, 1256, 2992);
            float previous = value.position(1256);
            float maximumReverse = 0, minimumVelocity = Float.POSITIVE_INFINITY;
            // The original return boundary is at least source interval8+24 and brake32.
            // Observe only the measured coast/brake window, never intentional stale return.
            for (int age=1; age<=32; age++) {
                float current = value.position(1256+age);
                maximumReverse = Math.max(maximumReverse, -sign*(current-previous));
                minimumVelocity = Math.min(minimumVelocity, sign*value.velocity(1256+age));
                previous = current;
            }
            assertTrue("live positive movement reversed: maximumReverse="+maximumReverse
                    +" minimumVelocity="+minimumVelocity, maximumReverse <= Math.ulp(previous) && minimumVelocity >= 0);
        }
    }
}
