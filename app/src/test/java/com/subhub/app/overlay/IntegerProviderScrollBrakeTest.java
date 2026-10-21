package com.subhub.app.overlay;

import org.junit.Test;
import static org.junit.Assert.*;

/** Literal integer provider deltas exercise the real ViewportMotion entry point. */
public final class IntegerProviderScrollBrakeTest {
    @Test public void integerProviderEqualityDoesNotExceedPeakOrReverseBeforeOriginalReturn() {
        for(int sign:new int[]{-1,1}) {
            ViewportMotion value=new ViewportMotion();value.reset(0,0,1000);
            value.addReportedDelta(null,sign*456f,1120,1344,2992,1120);
            value.addReportedDelta(null,sign*96f,1160,1344,2992,1160);
            assertEquals(sign*580,value.position(1195).y,0);
            value.addReportedDelta(null,sign*28f,1195,1344,2992,1183);
            float peak=value.predictionAmplitude().y,previous=value.position(1195).y;
            assertTrue(sign*peak>0&&Math.abs(peak)<2);
            for(int age=1;age<=72;age++) {
                float position=value.position(1195+age).y;
                assertTrue("integer provider pulse exceeded the unchanged cap at age="+age,
                        sign*(position-sign*580)<=sign*peak+Math.ulp(580));
                assertTrue("integer provider pulse reversed before original return at age="+age,
                        sign*(position-previous)>=-Math.ulp(position));
                previous=position;
            }
            assertEquals(sign*580+peak,value.position(1267).y,Math.ulp(580));
            assertEquals(sign*580,value.position(1363).y,0);
            assertFalse(value.isAnimating(1363));
        }
    }
}
