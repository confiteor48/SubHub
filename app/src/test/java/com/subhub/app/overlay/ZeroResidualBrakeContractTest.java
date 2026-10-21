package com.subhub.app.overlay;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.junit.Test;
import static org.junit.Assert.*;

/** Admission/gate/finite-return contracts, independent from the unchanged jitter matrix. */
public final class ZeroResidualBrakeContractTest {
    private static Object field(Object owner,String name) throws Exception {
        Field value=owner.getClass().getDeclaredField(name);value.setAccessible(true);return value.get(owner);
    }
    private static float number(Object owner,String name) throws Exception {return ((Number)field(owner,name)).floatValue();}
    private static boolean active(EventScrollTrajectory value) throws Exception {return (Boolean)field(value,"zeroResidualBrake");}
    private static float call(EventScrollTrajectory owner,String name,float age) throws Exception {
        Method value=owner.getClass().getDeclaredMethod(name,float.class);value.setAccessible(true);
        return (Float)value.invoke(owner,age);
    }
    private static EventScrollTrajectory acquired(int sign) {
        EventScrollTrajectory value=new EventScrollTrajectory();value.reset(0,0,1000);
        value.measure(sign*456,sign*456,1120,1120,2992);
        value.measure(sign*936,sign*480,1240,1240,2992);return value;
    }
    private static EventScrollTrajectory admitted(int sign) throws Exception {
        EventScrollTrajectory value=acquired(sign);float at=value.position(1256);
        value.measure(at,at-sign*936,1244,1256,2992);assertTrue(active(value));return value;
    }
    @Test public void actualPeakTimeAndPublicQueriesMatchConservedBrakeHoldAndOriginalReturn() throws Exception {
        for(int sign:new int[]{-1,1}) {
            EventScrollTrajectory value=admitted(sign);
            float exact=number(value,"exact"),peak=number(value,"peak"),velocity=number(value,"zeroResidualVelocity");
            float duration=number(value,"zeroResidualDuration"),returnStart=number(value,"returnStartMs");
            assertEquals(2*Math.abs(peak)/Math.abs(velocity),duration,0);
            assertTrue(duration>0&&duration<32&&duration<=number(value,"coastMs")+32);
            assertEquals(32,returnStart,0);assertEquals(Math.round(duration),value.predictionPeakMillis());
            assertEquals(peak,value.predictionAmplitude(),0);
            assertEquals(1244,((Long)field(value,"lastSourceTime")).longValue());
            assertFalse((Boolean)field(value,"constrainLagCorrection"));
            for(int age=0;age<=180;age++) {
                float offset,speed;
                if(age<duration){offset=velocity*age*(1-age/(2*duration));speed=velocity*(1-age/duration);}
                else {
                    float t=Math.max(0,Math.min(1,(age-returnStart)/96));
                    offset=peak*(1-t*t*t*(t*(t*6-15)+10));
                    speed=-peak*(30*t*t*(t*(t-2)+1))/96;
                }
                assertEquals(exact+offset,value.position(1256+age),2*Math.ulp(exact));
                assertEquals(speed,value.velocity(1256+age),2*Math.ulp(Math.max(1,Math.abs(speed))));
            }
            assertEquals(exact+peak,value.position(1256+(long)Math.ceil(duration)),2*Math.ulp(exact));
            assertEquals(0,value.velocity(1288),0);
            assertEquals(exact,value.position(1384),0);assertEquals(0,value.velocity(1384),0);
            assertFalse(value.isAnimating(1416));
        }
    }
    @Test public void newMeasurementSamplesPreviousActiveBranchBeforeClearingIt() throws Exception {
        for(int sign:new int[]{-1,1}) {
            EventScrollTrajectory value=admitted(sign);
            float previousExact=number(value,"exact"),at=value.position(1257),velocity=value.velocity(1257);
            assertTrue(sign*velocity>0);
            value.measure(at,at-previousExact,1245,1257,2992);
            assertTrue(active(value));
            assertEquals(at,value.position(1257),Math.ulp(at));
            assertEquals(velocity,value.velocity(1257),2*Math.ulp(velocity));
            assertEquals(0,number(value,"error"),0);
        }
    }
    @Test public void discontinuityCannotSynthesizeAnEqualityAdmission() throws Exception {
        for(int sign:new int[]{-1,1}) {
            EventScrollTrajectory value=admitted(sign);
            value.measure(sign*4000,sign*3000,1248,1258,2992);
            assertFalse(active(value));assertEquals(0,number(value,"error"),0);
            assertEquals(sign*4000,value.position(1258),0);
        }
    }
    @Test public void stopReverseStaleDuplicateGapAndResetClearPriorBranch() throws Exception {
        for(int sign:new int[]{-1,1})for(int condition=0;condition<6;condition++) {
            EventScrollTrajectory value=admitted(sign);float exact=number(value,"exact");
            if(condition==0)value.measure(exact,0,1248,1258,2992);
            if(condition==1)value.measure(exact-sign*10,-sign*10,1248,1258,2992);
            if(condition==2)value.measure(exact+sign*10,sign*10,1248,1276,2992);
            if(condition==3)value.measure(exact+sign*10,sign*10,1244,1258,2992);
            if(condition==4)value.measure(exact+sign*10,sign*10,1600,1600,2992);
            if(condition==5)value.reset(sign*12,0,1258);
            assertFalse("condition="+condition,active(value));
        }
    }
    @Test public void nonzeroResidualNeverUsesEqualityBranch() throws Exception {
        for(int sign:new int[]{-1,1})for(int offset:new int[]{-1,1}) {
            EventScrollTrajectory value=acquired(sign);float at=value.position(1256);
            value.measure(at+offset,at+offset-sign*936,1244,1256,2992);
            assertFalse(active(value));assertNotEquals(0,number(value,"error"),0);
        }
    }
    @Test public void infeasiblePublicInputKeepsOldContributionWithoutExtendingBrakeTime() throws Exception {
        boolean found=false;
        // Fixed bounded public-input corpus, not a changed production threshold or a fitted constant.
        for(int age=1;age<=100&&!found;age++)for(int sourceGap=1;sourceGap<=age&&!found;sourceGap++) {
            EventScrollTrajectory value=acquired(1);long now=1240+age;
            float at=value.position(now),incoming=value.velocity(now),delta=at-936;
            value.measure(at,delta,1240+sourceGap,now,2992);
            float speed=number(value,"speed"),peak=number(value,"peak");
            if(delta<=0||incoming<=speed||speed<=.001f||age-sourceGap>=sourceGap+24)continue;
            float duration=2*Math.abs(peak)/Math.abs(incoming);
            if(duration<=number(value,"coastMs")+32)continue;
            found=true;assertFalse(active(value));
            for(int query=0;query<=160;query++) {
                float expected=at+call(value,"forecast",query)+call(value,"correction",query);
                assertEquals(expected,value.position(now+query),0);
            }
        }
        assertTrue("fixed corpus must exercise an actually infeasible public admission",found);
    }
}
