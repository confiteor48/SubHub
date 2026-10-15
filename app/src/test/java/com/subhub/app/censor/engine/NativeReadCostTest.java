package com.subhub.app.censor.engine;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public final class NativeReadCostTest {
    @Test public void actualOperationModeIsExplicitWithoutChangingDurationOrClockAuthority() {
        NativeReadCost value = new NativeReadCost(1, 2, 3, 4, 5, 90, 100, 200, 210, 100,
                NativeReadCost.Terminal.SUCCESS, costs(), NativeTextResult.ReadMode.ADMITTED_NODE_RECLASSIFY);
        assertEquals(NativeTextResult.ReadMode.ADMITTED_NODE_RECLASSIFY, value.readMode); assertEquals(10, value.unattributedNanos);
        assertEquals(NativeTextResult.ReadMode.FULL_DISCOVERY, new NativeReadCost.Start(1, 2, 3, 4, 90).readMode);
    }
    private List<NativeReadCost.Cost> costs() {
        List<NativeReadCost.Cost> result = new ArrayList<>();
        for (NativeReadCost.Stage stage : NativeReadCost.Stage.values()) result.add(new NativeReadCost.Cost(stage, 1, 10, 10, 0));
        return result;
    }
    private NativeReadCost value(List<NativeReadCost.Cost> costs, long elapsed) {
        return new NativeReadCost(1, 2, 3, 4, 5, 90, 100, 200, 210, elapsed, NativeReadCost.Terminal.SUCCESS, costs);
    }
    @Test public void independentLeafDurationsLeaveWaitAndUnknownWorkExplicit() {
        NativeReadCost value = value(costs(), 100);
        assertEquals(10, value.unattributedNanos); assertEquals(100, value.elapsedNanos);
        assertEquals(210, value.terminalAtMillis); assertEquals(200, value.readFinishedAtMillis);
    }
    @Test public void immutableInventoryCannotBeChangedAfterTerminal() {
        List<NativeReadCost.Cost> values = costs(); NativeReadCost value = value(values, 100); values.clear();
        assertEquals(9, value.costs.size()); assertThrows(UnsupportedOperationException.class, () -> value.costs.clear());
    }
    @Test public void nestedOrOverlappingTotalsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> value(costs(), 89));
    }
    @Test public void missingDuplicatedOrReorderedStagesAreRejected() {
        List<NativeReadCost.Cost> values = costs(); values.remove(0);
        assertThrows(IllegalArgumentException.class, () -> value(values, 100));
        List<NativeReadCost.Cost> duplicate = costs(); duplicate.set(1, duplicate.get(0));
        assertThrows(IllegalArgumentException.class, () -> value(duplicate, 100));
    }
    @Test public void countMaxFailureAndZeroWorkPrimitivesAreBounded() {
        assertThrows(IllegalArgumentException.class, () -> new NativeReadCost.Cost(NativeReadCost.Stage.CLASSIFY, 0, 1, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new NativeReadCost.Cost(NativeReadCost.Stage.CLASSIFY, 2, 10, 4, 0));
        assertThrows(IllegalArgumentException.class, () -> new NativeReadCost.Cost(NativeReadCost.Stage.CLASSIFY, 2, 10, 6, 3));
        assertThrows(IllegalArgumentException.class, () -> new NativeReadCost.Cost(NativeReadCost.Stage.CLASSIFY, 65537, 0, 0, 0));
    }
    @Test public void failedPreReadAttemptKeepsUnknownReadClocks() {
        NativeReadCost value = new NativeReadCost(1, 2, 3, 4, -1, 90, -1, -1, 100, 100,
                NativeReadCost.Terminal.CANCELLED, costs());
        assertEquals(-1, value.readStartedAtMillis); assertEquals(-1, value.scanGeneration);
        assertThrows(IllegalArgumentException.class, () -> new NativeReadCost(1, 2, 3, 4, -1, 90, -1, -1, 100, 100,
                NativeReadCost.Terminal.SUCCESS, costs()));
    }
    @Test public void sourceAndInvocationClocksCannotBeReversed() {
        assertThrows(IllegalArgumentException.class, () -> new NativeReadCost(1, 2, 3, 4, 5, 110, 100, 200, 210, 100,
                NativeReadCost.Terminal.SUCCESS, costs()));
        assertThrows(IllegalArgumentException.class, () -> new NativeReadCost(1, 2, 3, 4, 5, 90, 100, 200, 199, 100,
                NativeReadCost.Terminal.SUCCESS, costs()));
        assertThrows(IllegalArgumentException.class, () -> new NativeReadCost.Start(0, 2, 3, 4, 90));
    }
}
