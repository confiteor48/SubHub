package com.subhub.app.overlay;

import java.lang.reflect.Field;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real mutation/query contracts for the narrow original-code correction clip. */
public final class ShortGapCorrectionContractTest {
    private static Object field(Object owner,String name) throws Exception {
        Field value=owner.getClass().getDeclaredField(name);value.setAccessible(true);return value.get(owner);
    }
    private static float number(Object owner,String name) throws Exception {return ((Number)field(owner,name)).floatValue();}
    private static EventScrollTrajectory acquired(int sign) {
        EventScrollTrajectory value=new EventScrollTrajectory();value.reset(0,0,1000);
        value.measure(sign*456,sign*456,1120,1120,2992);
        value.measure(sign*936,sign*480,1240,1240,2992);
        return value;
    }
    @Test public void correctionZeroCrossingJoinsUnchangedForecastWithoutPositionOrReverseJump() throws Exception {
        for(int sign:new int[]{-1,1}) {
            EventScrollTrajectory value=acquired(sign);
            float before=value.position(1256),velocity=value.velocity(1256);
            value.measure(sign*960,sign*24,1248,1256,2992);
            assertTrue((Boolean)field(value,"constrainLagCorrection"));
            assertEquals(before,value.position(1256),Math.ulp(before));
            assertEquals(velocity,value.velocity(1256),2*Math.ulp(velocity));
            float error=number(value,"error"),errorVelocity=number(value,"errorVelocity"),rate=number(value,"correctionRate");
            float crossing=-error/(errorVelocity+rate*error);
            assertTrue(crossing>0&&crossing<32);
            assertEquals(0,number(value,"coastMs"),0);
            assertEquals(sign*1.875f,number(value,"speed"),0);
            for(int age=(int)Math.ceil(crossing);age<=32;age++) {
                float expected=sign*(960+1.875f*(age-age*age/64f));
                assertEquals("after catch, only original forecast owns the pose",expected,value.position(1256+age),2*Math.ulp(expected));
                assertEquals(sign*1.875f*(1-age/32f),value.velocity(1256+age),Math.ulp(1.875f));
            }
            assertEquals(30,sign*value.predictionAmplitude(),0);
            float early=value.position(1258);
            assertEquals(sign*960,value.position(1500),0);assertFalse(value.isAnimating(1500));
            assertEquals("queries have no history",early,value.position(1258),0);
        }
    }
    @Test public void subsequentStopAndReversalDisableClipAndKeepOriginalMeasuredBrake() throws Exception {
        for(int sign:new int[]{-1,1})for(int delta:new int[]{0,-60}) {
            EventScrollTrajectory value=acquired(sign);value.measure(sign*960,sign*24,1248,1256,2992);
            float before=value.position(1260);
            value.measure(sign*(960+delta),sign*delta,1260,1260,2992);
            assertFalse((Boolean)field(value,"constrainLagCorrection"));
            assertEquals(before,value.position(1260),Math.ulp(before));
            assertEquals(sign*(960+delta),value.position(1292),Math.ulp(960));
            assertEquals(0,value.velocity(1292),0);assertFalse(value.isAnimating(1292));
        }
    }
    @Test public void expiredOutOfOrderGapAndResetDoNotInheritClipAuthority() throws Exception {
        for(int sign:new int[]{-1,1}) {
            EventScrollTrajectory value=acquired(sign);value.measure(sign*960,sign*24,1248,1256,2992);
            value.reset(sign*12,0,1260);
            assertFalse((Boolean)field(value,"constrainLagCorrection"));
            assertEquals(sign*12,value.position(1261),0);assertFalse(value.isAnimating(1261));
            value=acquired(sign);value.measure(sign*960,sign*24,1248,1280,2992);
            assertFalse("age32 equals expiry8+24",(Boolean)field(value,"constrainLagCorrection"));
            assertEquals(sign*960,value.position(1312),Math.ulp(960));
            value=acquired(sign);value.measure(sign*960,sign*24,1240,1256,2992);
            assertFalse("equal source time is unordered",(Boolean)field(value,"constrainLagCorrection"));
            value=acquired(sign);value.measure(sign*960,sign*24,1600,1600,2992);
            assertFalse("new acquisition has no continuing stream",(Boolean)field(value,"constrainLagCorrection"));
        }
    }
    @Test public void zeroDeliveryAgeCanUseClipWithoutRetimingItsCoordinateOrAdmission() throws Exception {
        for(int sign:new int[]{-1,1}) {
            EventScrollTrajectory value=acquired(sign);
            float before=value.position(1248),velocity=value.velocity(1248);
            value.measure(sign*960,sign*24,1248,1248,2992);
            assertEquals(before,value.position(1248),Math.ulp(before));
            assertEquals(velocity,value.velocity(1248),2*Math.ulp(velocity));
            assertEquals(sign*960,number(value,"exact"),0);
            assertEquals(1248,((Long)field(value,"lastSourceTime")).longValue());
            float previous=before;
            for(int age=1;age<=32;age++) {
                float position=value.position(1248+age);
                assertTrue(sign*(position-previous)>=-Math.ulp(position));
                assertTrue(sign*value.velocity(1248+age)>=0);previous=position;
            }
        }
    }
}
