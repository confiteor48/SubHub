package com.subhub.app.detection.text;

import static org.junit.Assert.*;
import static com.subhub.app.censor.engine.NativeTextResult.ReadMode.*;

import org.junit.Test;

public final class NativeReadScheduleTest {
    @Test public void stableAddressInventoryRotatesOnceThenRequiresFullDiscovery() {
        NativeReadSchedule schedule = new NativeReadSchedule();
        assertEquals(FULL_DISCOVERY, schedule.next(true, 0)); schedule.terminal(FULL_DISCOVERY, 2);
        assertEquals(ADMITTED_NODE_COHORT_RECLASSIFY, schedule.next(true, 2)); schedule.terminal(ADMITTED_NODE_COHORT_RECLASSIFY, 2);
        assertEquals(FULL_DISCOVERY, schedule.next(true, 2));
    }
    @Test public void failedDirectAttemptsConsumeQuotaWithoutRefundOrNewAdmission() {
        NativeReadSchedule schedule = new NativeReadSchedule(); schedule.terminal(FULL_DISCOVERY, 1);
        assertEquals(ADMITTED_NODE_RECLASSIFY, schedule.next(true, 1)); schedule.terminal(ADMITTED_NODE_RECLASSIFY, 0);
        assertEquals(FULL_DISCOVERY, schedule.next(true, 0));
    }
    @Test public void partialTerminalsCannotExpandThePassWhenMoreAddressesAppear() {
        NativeReadSchedule schedule = new NativeReadSchedule(); schedule.terminal(FULL_DISCOVERY, 1);
        assertEquals(ADMITTED_NODE_RECLASSIFY, schedule.next(true, 1)); schedule.terminal(ADMITTED_NODE_RECLASSIFY, 64);
        assertEquals(FULL_DISCOVERY, schedule.next(true, 64));
    }
    @Test public void fullTerminalPreservesRefillFromRetainedAddressesAfterFailedDiscovery() {
        NativeReadSchedule schedule = new NativeReadSchedule();
        assertEquals(FULL_DISCOVERY, schedule.next(true, 2)); schedule.terminal(FULL_DISCOVERY, 2);
        assertEquals(ADMITTED_NODE_COHORT_RECLASSIFY, schedule.next(true, 2));
        assertEquals(FULL_DISCOVERY, schedule.next(true, 2));
    }
    @Test public void hardFencedEmptyInventoryCannotBorrowThePreviousScopesQuota() {
        NativeReadSchedule schedule = new NativeReadSchedule(); schedule.terminal(FULL_DISCOVERY, 4);
        assertEquals(FULL_DISCOVERY, schedule.next(true, 0)); schedule.terminal(FULL_DISCOVERY, 0);
        assertEquals(FULL_DISCOVERY, schedule.next(true, 2));
    }
    @Test public void unsupportedProviderAlwaysRequiresDiscovery() {
        NativeReadSchedule schedule = new NativeReadSchedule(); schedule.terminal(FULL_DISCOVERY, 2);
        for (int i = 0; i < 5; i++) assertEquals(FULL_DISCOVERY, schedule.next(false, 2));
    }
    @Test public void maximumInventoryStillForcesAFullAttemptAfterExactlyOneBoundedPass() {
        NativeReadSchedule schedule = new NativeReadSchedule(); schedule.terminal(FULL_DISCOVERY, 64);
        for (int i = 0; i < 32; i++) {
            assertEquals(ADMITTED_NODE_COHORT_RECLASSIFY, schedule.next(true, 64)); schedule.terminal(ADMITTED_NODE_COHORT_RECLASSIFY, 64);
        }
        assertEquals(FULL_DISCOVERY, schedule.next(true, 64));
    }
    @Test public void malformedInventoryOrModeCannotCreateSelectionAuthority() {
        NativeReadSchedule schedule = new NativeReadSchedule();
        for (int count : new int[]{-1, 65, Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> schedule.next(true, count));
            assertThrows(IllegalArgumentException.class, () -> schedule.terminal(FULL_DISCOVERY, count));
        }
        assertThrows(NullPointerException.class, () -> schedule.terminal(null, 2));
    }

    @Test public void oddPassHasOneSingletonRemainderAndCannotGrowFromPartialAdmissions() {
        NativeReadSchedule schedule = new NativeReadSchedule(); schedule.terminal(FULL_DISCOVERY, 5);
        for (int i = 0; i < 2; i++) {
            assertEquals(ADMITTED_NODE_COHORT_RECLASSIFY, schedule.next(true, 5));
            schedule.terminal(ADMITTED_NODE_COHORT_RECLASSIFY, 64);
        }
        assertEquals(ADMITTED_NODE_RECLASSIFY, schedule.next(true, 64)); schedule.terminal(ADMITTED_NODE_RECLASSIFY, 64);
        assertEquals(FULL_DISCOVERY, schedule.next(true, 64));
    }

    @Test public void failedCohortCannotRefundItsAddressesOrAvoidNextFull() {
        NativeReadSchedule schedule = new NativeReadSchedule(); schedule.terminal(FULL_DISCOVERY, 2);
        assertEquals(ADMITTED_NODE_COHORT_RECLASSIFY, schedule.next(true, 2));
        schedule.terminal(ADMITTED_NODE_COHORT_RECLASSIFY, 1);
        assertEquals(FULL_DISCOVERY, schedule.next(true, 1));
    }

    @Test public void shrinkingInventoryNeverSelectsADuplicatePair() {
        NativeReadSchedule schedule = new NativeReadSchedule(); schedule.terminal(FULL_DISCOVERY, 4);
        assertEquals(ADMITTED_NODE_RECLASSIFY, schedule.next(true, 1));
        assertEquals(ADMITTED_NODE_COHORT_RECLASSIFY, schedule.next(true, 2));
        assertEquals(ADMITTED_NODE_RECLASSIFY, schedule.next(true, 2));
        assertEquals(FULL_DISCOVERY, schedule.next(true, 2));
    }
}
