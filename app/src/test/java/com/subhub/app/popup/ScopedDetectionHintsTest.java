package com.subhub.app.popup;

import static org.junit.Assert.*;
import org.junit.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class ScopedDetectionHintsTest {
    @Test public void eachPlacementQueriesCurrentMetadataRatherThanRetainingACopy() {
        ScopedDetectionHints<Integer> hints = new ScopedDetectionHints<>();
        List<Integer> live = new ArrayList<>(Collections.singletonList(1));
        ScopedDetectionHints<Integer>.Binding binding = hints.bind(() -> live);
        assertEquals(Collections.singletonList(1), hints.read(Collections.singletonList(99)));
        live.clear(); assertTrue(hints.read(Collections.singletonList(99)).isEmpty());
        binding.close(); assertEquals(Collections.singletonList(99), hints.read(Collections.singletonList(99)));
    }
    @Test public void oldClosureCannotClearTheSuccessorAndReadIsAnImmutableSnapshot() {
        ScopedDetectionHints<Integer> hints = new ScopedDetectionHints<>();
        ScopedDetectionHints<Integer>.Binding old = hints.bind(() -> Collections.singletonList(1));
        ScopedDetectionHints<Integer>.Binding current = hints.bind(() -> Collections.singletonList(2));
        old.close(); List<Integer> result = hints.read(Collections.emptyList());
        assertEquals(Collections.singletonList(2), result);
        assertThrows(UnsupportedOperationException.class, () -> result.clear());
        current.close(); assertTrue(hints.read(Collections.emptyList()).isEmpty());
    }
    @Test public void reentrantReplacementCannotReturnOldHintsOrBorrowLegacyFallback() {
        ScopedDetectionHints<Integer> hints = new ScopedDetectionHints<>();
        hints.bind(() -> { hints.bind(() -> Collections.singletonList(2)); return Collections.singletonList(1); });
        assertTrue(hints.read(Collections.singletonList(99)).isEmpty());
        assertEquals(Collections.singletonList(2), hints.read(Collections.emptyList()));
    }
    @Test public void unavailableMetadataDoesNotMutateParticipationOrFallBackToAnOldWindow() {
        ScopedDetectionHints<Integer> hints = new ScopedDetectionHints<>(); AtomicInteger calls = new AtomicInteger();
        hints.bind(() -> { calls.incrementAndGet(); throw new IllegalStateException("Unavailable"); });
        assertTrue(hints.read(Collections.singletonList(99)).isEmpty());
        assertTrue(hints.read(Collections.singletonList(99)).isEmpty()); assertEquals(2, calls.get());
    }
}
