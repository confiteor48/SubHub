package com.subhub.app.censor.android;

import static org.junit.Assert.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Map;
import java.util.List;
import org.junit.Test;

public final class FixtureServiceConnectionTest {
    @Test public void failedReadinessCleanupKeepsOldAndNewLeasesSeparateWithoutQualifyingEither() {
        FixtureServiceConnection<Object> connections = new FixtureServiceConnection<>(value -> { });
        FixtureServiceConnection.Lease<Object> old = connections.connected(new Object());
        connections.retire(old, () -> { }, () -> { });
        FixtureServiceConnection.Lease<Object> newer = connections.connected(new Object());
        List<FixtureServiceConnection.Lease<Object>> targets = connections.closureTargets(old);
        assertEquals(2, targets.size()); assertSame(old, targets.get(0)); assertSame(newer, targets.get(1));
        assertTrue(old.completedSuccessfully()); assertFalse(newer.completedSuccessfully());
        assertTrue(connections.isCurrent(newer)); assertEquals(0, newer.completedCallbacks);
        connections.connected(new Object());
        assertEquals(2, targets.size()); assertSame(newer, targets.get(1));
    }
    @Test public void diagnosticInspectionCannotRetireOrBorrowAReplacementLease() {
        FixtureServiceConnection<Object> connections = new FixtureServiceConnection<>(value -> { });
        FixtureServiceConnection.Lease<Object> old = connections.connected(new Object());
        Map<String, Object> initial = connections.inspect(old);
        assertEquals(true, initial.get("currentIsOwn")); assertEquals(0, initial.get("ownCallbacks"));
        connections.retire(old, () -> {
            Map<String, Object> retiring = connections.inspect(old);
            assertEquals(true, retiring.get("ownRetiring")); assertEquals(false, retiring.get("ownRetired"));
            connections.connected(new Object());
        }, () -> { });
        Map<String, Object> retired = connections.inspect(old);
        assertEquals(true, retired.get("ownRetired")); assertEquals(false, retired.get("currentIsOwn"));
        assertEquals(2L, retired.get("currentGeneration")); assertEquals(1, retired.get("ownCallbacks"));
        assertTrue(connections.isCurrent(connections.currentLease()));
    }
    @Test public void rememberedInactiveSelectionStillRequiresRetirement() {
        assertFalse(FixtureServiceConnection.activeSelection("0", "pkg/Fixture", "pkg/Fixture"));
        assertFalse(FixtureServiceConnection.activeSelection(null, "pkg/Fixture", "pkg/Fixture"));
        assertFalse(FixtureServiceConnection.activeSelection("1", null, "pkg/Fixture"));
        assertFalse(FixtureServiceConnection.activeSelection("1", "pkg/FixtureOther", "pkg/Fixture"));
        assertTrue(FixtureServiceConnection.activeSelection("1", "other/Service:pkg/Fixture", "pkg/Fixture"));
    }
    @Test public void publishedObjectWithNullInfoCannotBecomeConnected() {
        FixtureServiceConnection<Object> connections = new FixtureServiceConnection<>(value -> { });
        connections.connected(new Object());
        assertNull(connections.ready(value -> null, (value, info) -> { fail("Null info was validated"); return true; }));
    }
    @Test public void oneValidInfoSnapshotBelongsToTheSameExactLease() {
        Object service = new Object(), info = new Object(); AtomicInteger reads = new AtomicInteger();
        FixtureServiceConnection<Object> connections = new FixtureServiceConnection<>(value -> assertSame(service, value));
        FixtureServiceConnection.Lease<Object> lease = connections.connected(service);
        FixtureServiceConnection.Snapshot<Object, Object> ready = connections.ready(value -> { reads.incrementAndGet(); return info; },
                (value, observed) -> value == service && observed == info);
        assertNotNull(ready); assertSame(lease, ready.lease); assertSame(info, ready.info); assertEquals(1, reads.get());
        assertNull(connections.ready(value -> info, (value, observed) -> false));
    }
    @Test public void replacementGenerationCannotBorrowAnEarlierSnapshot() {
        Object service = new Object(); FixtureServiceConnection<Object> connections = new FixtureServiceConnection<>(value -> { });
        FixtureServiceConnection.Lease<Object> old = connections.connected(service);
        assertNull(connections.ready(value -> { connections.connected(new Object()); return new Object(); }, (value, info) -> true));
        assertFalse(connections.isCurrent(old)); assertTrue(connections.currentLease().generation > old.generation);
    }
    @Test public void duplicateOldInstanceConnectionAndCallbacksCannotRetireReplacement() {
        AtomicReference<Object> published = new AtomicReference<>();
        FixtureServiceConnection<Object> connections = new FixtureServiceConnection<>(published::set);
        Object oldService = new Object(), replacement = new Object();
        FixtureServiceConnection.Lease<Object> old = connections.connected(oldService);
        assertThrows(IllegalStateException.class, () -> connections.connected(oldService));
        connections.retire(old, () -> { }, () -> { });
        FixtureServiceConnection.Lease<Object> current = connections.connected(replacement);
        long generation = connections.lastGeneration();
        assertThrows(IllegalStateException.class, () -> connections.connected(oldService));
        connections.retire(old, () -> fail("Retired instance cleaned up twice"), () -> { });
        assertSame(current, connections.currentLease()); assertSame(replacement, published.get());
        assertEquals(generation, connections.lastGeneration()); assertTrue(connections.isCurrent(current));
    }
    @Test public void oldCallbackCannotEraseAReplacementAndRetiringLeaseIsNotReady() {
        AtomicReference<Object> published = new AtomicReference<>(); Object oldService = new Object(), newService = new Object();
        FixtureServiceConnection<Object> connections = new FixtureServiceConnection<>(published::set);
        FixtureServiceConnection.Lease<Object> old = connections.connected(oldService);
        connections.retire(old, () -> {
            assertNull(connections.ready(value -> new Object(), (value, info) -> true)); connections.connected(newService);
        }, () -> { });
        assertSame(newService, published.get()); assertSame(newService, connections.currentLease().service);
        assertTrue(old.completedSuccessfully());
    }
    @Test public void cleanupFailureStillRunsSuperclassAndCannotClaimCallbackSuccess() {
        AtomicReference<Object> published = new AtomicReference<>(); AtomicInteger callbacks = new AtomicInteger();
        FixtureServiceConnection<Object> connections = new FixtureServiceConnection<>(published::set);
        FixtureServiceConnection.Lease<Object> lease = connections.connected(new Object());
        RuntimeException failure = new IllegalStateException("cleanup");
        assertSame(failure, assertThrows(RuntimeException.class, () -> connections.retire(lease, () -> { throw failure; }, callbacks::incrementAndGet)));
        assertEquals(1, callbacks.get()); assertTrue(lease.retired); assertFalse(lease.completedSuccessfully());
        assertSame(failure, lease.failure); assertNull(published.get()); assertNull(connections.currentLease());
    }
    @Test public void firstFailureSurvivesSuperclassFailureAndNewLeasePublication() {
        AtomicReference<Object> published = new AtomicReference<>(); Object replacement = new Object();
        FixtureServiceConnection<Object> connections = new FixtureServiceConnection<>(published::set);
        FixtureServiceConnection.Lease<Object> old = connections.connected(new Object());
        RuntimeException first = new IllegalStateException("cleanup"), second = new IllegalArgumentException("superclass");
        assertSame(first, assertThrows(RuntimeException.class, () -> connections.retire(old,
                () -> { connections.connected(replacement); throw first; }, () -> { throw second; })));
        assertArrayEquals(new Throwable[]{second}, first.getSuppressed()); assertSame(replacement, published.get());
        assertFalse(old.completedSuccessfully());
    }
    @Test public void repeatedLifecycleCallbacksDoNotRepeatControllerCleanup() {
        AtomicInteger cleanup = new AtomicInteger(), callbacks = new AtomicInteger();
        FixtureServiceConnection<Object> connections = new FixtureServiceConnection<>(value -> { });
        FixtureServiceConnection.Lease<Object> lease = connections.connected(new Object());
        connections.retire(lease, cleanup::incrementAndGet, callbacks::incrementAndGet);
        connections.retire(lease, cleanup::incrementAndGet, callbacks::incrementAndGet);
        assertEquals(1, cleanup.get()); assertEquals(2, callbacks.get()); assertEquals(2, lease.completedCallbacks);
        assertTrue(lease.completedSuccessfully());
    }
    @Test public void foreignLeaseCannotRunCleanupOrChangeTheCurrentOwner() {
        FixtureServiceConnection<Object> first = new FixtureServiceConnection<>(value -> { });
        FixtureServiceConnection<Object> second = new FixtureServiceConnection<>(value -> { });
        FixtureServiceConnection.Lease<Object> lease = first.connected(new Object());
        second.connected(new Object());
        assertThrows(IllegalArgumentException.class, () -> second.retire(lease, () -> fail("Foreign cleanup"), () -> fail("Foreign callback")));
        assertTrue(first.isCurrent(lease)); assertNotNull(second.currentLease());
    }
}
