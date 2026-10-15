package com.subhub.app.censor.android;

import static org.junit.Assert.*;
import org.junit.Test;

public final class NativeReaderRetirementTest {
    @Test public void exactReaderEpochIsRetired() {
        Object config = new Object(); assertTrue(AccessibilityCensorHost.nativeReaderRetirementMatches(config, 3, config, 3));
    }
    @Test public void independentFaultEpochCannotStrandAnOlderReadersHandles() {
        Object config = new Object(); assertTrue(AccessibilityCensorHost.nativeReaderRetirementMatches(config, 3, config, 4));
    }
    @Test public void queuedOldRetirementCannotCloseANewerSameConfigurationReader() {
        Object config = new Object(); assertFalse(AccessibilityCensorHost.nativeReaderRetirementMatches(config, 4, config, 3));
    }
    @Test public void anUnrelatedConfigurationIsNeverRetired() {
        assertFalse(AccessibilityCensorHost.nativeReaderRetirementMatches(new Object(), 3, new Object(), 4));
    }
    @Test public void absentConfigurationAndUnestablishedEpochDoNotNameAReader() {
        Object config = new Object(); assertFalse(AccessibilityCensorHost.nativeReaderRetirementMatches(null, 3, null, 4));
        assertFalse(AccessibilityCensorHost.nativeReaderRetirementMatches(config, 0, config, 4));
    }
}
