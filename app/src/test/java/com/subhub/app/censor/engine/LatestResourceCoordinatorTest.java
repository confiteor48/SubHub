package com.subhub.app.censor.engine;

import static org.junit.Assert.*;

import org.junit.Test;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class LatestResourceCoordinatorTest {
    private static final class Queue implements Executor, LatestResourceCoordinator.Delivery {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        boolean reject;
        @Override public void execute(Runnable action) {
            if (reject) throw new RejectedExecutionException();
            tasks.add(action);
        }
        @Override public boolean post(Runnable action) {
            if (reject) return false;
            tasks.add(action); return true;
        }
        void one() { tasks.remove().run(); }
        void all() { while (!tasks.isEmpty()) one(); }
    }
    private static final class Resource implements AutoCloseable {
        final String name; int closes; boolean fail; Runnable onClose = () -> { };
        Resource(String name) { this.name = name; }
        @Override public void close() { closes++; onClose.run(); if (fail) throw new IllegalStateException(); }
    }
    private static final class Harness {
        final Queue worker = new Queue(), ui = new Queue();
        final List<Resource> resources = new ArrayList<>();
        int changed, drained;
        final LatestResourceCoordinator<String, Resource> owner = new LatestResourceCoordinator<>(worker, ui,
                new LatestResourceCoordinator.Observer() {
                    @Override public void changed() { assertFalse(Thread.holdsLock(owner)); changed++; }
                    @Override public void drained() { assertFalse(Thread.holdsLock(owner)); drained++; }
                });
        void request(String key) { owner.request(key, obsolete -> { Resource r = new Resource(key); resources.add(r); return r; }); }
        void install(String key) { request(key); worker.all(); ui.all(); }
        String current() { try (LatestResourceCoordinator<String, Resource>.Read read = owner.acquire()) { return read == null ? null : read.resource().name; } }
    }

    @Test public void latestDescriptorOwnsNoResourcesAndDoesNotQueueEveryRequest() {
        Harness h = new Harness(); h.request("A"); h.request("B"); h.request("C");
        assertEquals(1, h.worker.tasks.size()); assertEquals(1, h.owner.runningCount());
        assertEquals(1, h.owner.pendingCount()); assertEquals(1, h.owner.replacedCount());
        assertEquals(0, h.owner.outputCount());
        h.worker.all(); h.ui.all(); assertEquals("C", h.current()); assertEquals(1, h.resources.size());
        h.owner.close(); h.ui.all(); assertEquals(1, h.resources.get(0).closes); assertTrue(h.owner.isDrained());
    }

    @Test public void currentRemainsWhileNewGenerationPreparesAndUiPostsDoNotAccumulateOutputs() {
        Harness h = new Harness(); h.install("A"); h.request("B"); h.worker.one();
        // Accepted delivery retains the single completed mailbox until that callback actually runs.
        assertEquals(2, h.owner.outputCount()); h.request("C"); h.request("D");
        assertEquals(0, h.worker.tasks.size()); assertEquals(1, h.ui.tasks.size());
        assertEquals(2, h.owner.outputCount());
        h.ui.all(); assertEquals(1, h.resources.get(1).closes); assertEquals("A", h.current());
        h.worker.all(); h.ui.all(); assertEquals("D", h.current());
        assertEquals(1, h.resources.get(0).closes); assertEquals(1, h.owner.outputCount()); h.owner.close(); h.ui.all();
    }

    @Test public void heldReadBlocksThirdOutputAndLeaseReleaseDisposesExactlyOnce() {
        Harness h = new Harness(); h.install("A");
        LatestResourceCoordinator<String, Resource>.Read old = h.owner.acquire();
        h.install("B"); h.request("C"); h.request("D");
        assertEquals("A", old.resource().name); assertEquals(0, h.resources.get(0).closes);
        assertEquals(2, h.owner.outputCount()); assertEquals(0, h.worker.tasks.size());
        assertEquals(1, h.owner.pendingCount()); old.close(); old.close();
        assertEquals(1, h.resources.get(0).closes); assertEquals(1, h.worker.tasks.size());
        h.worker.all(); h.ui.all(); assertEquals("D", h.current()); h.owner.close(); h.ui.all();
        for (Resource resource : h.resources) assertEquals(1, resource.closes);
        assertThrows(IllegalStateException.class, old::resource);
    }

    @Test public void invalidationDoesNotPretendAnUnstartedWorkerHasTerminated() {
        Harness h = new Harness(); h.install("A"); h.request("B"); h.owner.invalidate();
        assertNull(h.current()); assertEquals(1, h.owner.runningCount());
        h.owner.close(); assertFalse(h.owner.isDrained()); assertEquals(0, h.drained);
        h.worker.all(); h.ui.all(); assertTrue(h.owner.isDrained()); assertEquals(1, h.drained);
        assertEquals(1, h.resources.size()); assertEquals(1, h.resources.get(0).closes);
    }

    @Test public void scopeChangeInsidePreparationDiscardsActualLateResourceThenRunsLatest() {
        Harness h = new Harness(); h.install("A"); Resource late = new Resource("old");
        h.owner.request("old", obsolete -> { h.owner.invalidate(); h.request("new"); assertTrue(obsolete.getAsBoolean()); return late; });
        h.worker.all(); h.ui.all(); assertEquals(1, late.closes); assertEquals("new", h.current());
        h.owner.close(); h.ui.all(); assertTrue(h.owner.isDrained());
    }

    @Test public void closeInsidePreparationWaitsForActualResourceCleanupAndHeldReader() {
        Harness h = new Harness(); h.install("A"); LatestResourceCoordinator<String, Resource>.Read read = h.owner.acquire();
        Resource late = new Resource("late"); late.onClose = () -> assertFalse(h.owner.isDrained());
        h.owner.request("late", obsolete -> { h.owner.close(); assertFalse(h.owner.isDrained()); return late; });
        h.worker.all(); h.ui.all(); assertFalse(h.owner.isDrained()); assertEquals(1, late.closes);
        read.close(); h.ui.all(); assertTrue(h.owner.isDrained()); assertEquals(1, h.drained);
        assertThrows(IllegalStateException.class, () -> h.request("invalid"));
    }

    @Test public void rejectedDeliveryIsRecoverableWithoutRepeatingPreparation() {
        Harness h = new Harness(); h.request("A"); h.ui.reject = true; h.worker.all();
        assertEquals(1, h.owner.deliveryRejections()); assertEquals(1, h.owner.outputCount());
        assertEquals(1, h.owner.runningCount()); h.ui.reject = false;
        assertEquals("A", h.current()); h.ui.all(); assertEquals(1, h.resources.size());
        assertEquals(1, h.changed); h.owner.close(); h.ui.all(); assertTrue(h.owner.isDrained());
    }

    @Test public void closeCleansRejectedOrQueuedCompletionWithoutStaleInstallation() {
        for (boolean rejected : new boolean[]{false, true}) {
            Harness h = new Harness(); h.request("A"); h.ui.reject = rejected; h.worker.all();
            h.owner.close(); assertTrue(h.owner.isDrained()); assertEquals(1, h.resources.get(0).closes);
            h.ui.reject = false; h.ui.all(); assertNull(h.current()); assertEquals(0, h.changed);
        }
    }

    @Test public void rejectedWorkerAndThrowingPreparationAreFailuresNotOutputs() {
        Harness h = new Harness(); h.worker.reject = true; h.request("A"); h.ui.all();
        assertEquals(1, h.owner.preparationFailures()); assertEquals(0, h.owner.outputCount());
        h.worker.reject = false;
        h.owner.request("bad", obsolete -> { throw new IllegalStateException(); }); h.worker.all(); h.ui.all();
        assertEquals(2, h.owner.preparationFailures()); h.install("C"); assertEquals("C", h.current());
        h.owner.close(); h.ui.all(); assertTrue(h.owner.isDrained());
    }

    @Test public void cleanupFailureBlocksAdmissionAndNeverClaimsDrained() {
        Harness h = new Harness(); h.install("A"); h.resources.get(0).fail = true;
        h.install("B"); assertEquals(1, h.owner.resourceFailures());
        h.request("C"); assertEquals(0, h.worker.tasks.size());
        h.owner.close(); h.ui.all(); assertFalse(h.owner.isDrained());
        assertEquals(1, h.resources.get(0).closes); assertEquals(1, h.resources.get(1).closes);
    }

    @Test public void liveAliasIsRejectedWithoutClosingTheCurrentOutput() {
        Harness h = new Harness(); h.install("A"); Resource current = h.resources.get(0);
        h.owner.request("alias", obsolete -> current); h.worker.all(); h.ui.all();
        assertEquals(1, h.owner.resourceFailures()); assertEquals(0, current.closes);
        assertEquals("A", h.current()); h.owner.close(); h.ui.all(); assertEquals(1, current.closes);
        assertFalse(h.owner.isDrained());
    }

    @Test public void cleanupAndObserversCanReenterWithoutLockOrPrematureDrain() {
        Harness h = new Harness(); h.install("A");
        h.resources.get(0).onClose = () -> { assertFalse(Thread.holdsLock(h.owner)); assertFalse(h.owner.isDrained()); h.request("C"); };
        h.install("B"); h.worker.all(); h.ui.all(); assertEquals("C", h.current());
        h.resources.get(0).onClose = () -> { }; h.owner.close(); h.ui.all(); assertTrue(h.owner.isDrained());
    }

    @Test public void inlineExecutorsAndPostExecutionThrowCannotDeliverTwice() {
        Resource resource = new Resource("A"); int[] changes = {0};
        Executor runThenThrow = action -> { action.run(); throw new RejectedExecutionException(); };
        LatestResourceCoordinator<String, Resource> owner = new LatestResourceCoordinator<>(runThenThrow, action -> { action.run(); return true; },
                new LatestResourceCoordinator.Observer() { @Override public void changed() { changes[0]++; } });
        owner.request("A", obsolete -> resource); assertEquals(1, changes[0]); assertEquals(0, owner.preparationFailures());
        owner.close(); assertEquals(1, resource.closes); assertTrue(owner.isDrained());
    }

    @Test public void rejectedButQueuedWorkerCannotPrepareAfterReportedTerminal() {
        Queue queue = new Queue(); Resource resource = new Resource("A");
        Executor queuedThenThrow = action -> { queue.execute(action); throw new RejectedExecutionException(); };
        LatestResourceCoordinator<String, Resource> owner = new LatestResourceCoordinator<>(queuedThenThrow, action -> { action.run(); return true; },
                new LatestResourceCoordinator.Observer() { });
        owner.request("A", obsolete -> resource); assertEquals(1, owner.preparationFailures());
        owner.close(); assertTrue(owner.isDrained()); queue.all();
        assertEquals(0, resource.closes); assertEquals(0, owner.outputCount());
    }

    @Test public void repeatedPollCannotStealAcceptedDeliveryOrGrowBlockedUiQueue() {
        Harness h = new Harness(); h.request("A"); h.worker.all();
        for (int index = 0; index < 100; index++) {
            h.owner.poll(); assertNull(h.current()); h.request("pending" + index); h.worker.all();
            assertEquals(1, h.ui.tasks.size()); assertEquals(1, h.owner.runningCount());
            assertEquals(1, h.owner.outputCount()); assertEquals(1, h.owner.pendingCount());
        }
        h.ui.all(); h.worker.all(); h.ui.all(); assertEquals("pending99", h.current());
        assertEquals(2, h.resources.size()); h.owner.close(); h.ui.all(); assertTrue(h.owner.isDrained());
    }

    @Test public void changeNotificationsCoalesceEvenWhenTaskDeliveriesAreSelectivelyDrained() {
        Harness h = new Harness();
        for (int index = 0; index < 100; index++) {
            h.request("A" + index); h.worker.all();
            // Leave the first changed callback blocked while delivering only the next output.
            h.ui.tasks.removeLast().run();
            assertEquals(1, h.ui.tasks.size()); assertEquals(1, h.owner.outputCount());
        }
        h.ui.all(); assertEquals(1, h.changed); assertEquals("A99", h.current());
        h.owner.close(); h.ui.all(); for (Resource resource : h.resources) assertEquals(1, resource.closes);
    }

    @Test public void unavailableOptionalResourcesAreNotMisreportedAsPreparationFailure() {
        Harness h = new Harness(); h.install("A"); h.owner.request("absent", obsolete -> null);
        h.worker.all(); h.ui.all(); assertEquals("A", h.current()); assertEquals(0, h.owner.preparationFailures());
        h.owner.close(); h.ui.all(); assertTrue(h.owner.isDrained());
    }

    @Test public void uncertainAcceptedDeliveryCannotBeStolenByPollOrGrowQueue() {
        Queue worker = new Queue(), ui = new Queue(); List<Resource> resources = new ArrayList<>();
        LatestResourceCoordinator.Delivery uncertain = action -> { ui.tasks.add(action); throw new RejectedExecutionException(); };
        LatestResourceCoordinator<String, Resource> owner = new LatestResourceCoordinator<>(worker, uncertain,
                new LatestResourceCoordinator.Observer() { });
        owner.request("A", obsolete -> { Resource r = new Resource("A"); resources.add(r); return r; }); worker.all();
        for (int index = 0; index < 100; index++) {
            owner.poll(); assertNull(owner.acquire());
            owner.request("B" + index, obsolete -> { Resource r = new Resource("B"); resources.add(r); return r; });
            worker.all(); assertEquals(1, ui.tasks.size()); assertEquals(1, owner.outputCount());
        }
        assertEquals(1, owner.deliveryFailures()); assertEquals(0, owner.deliveryRejections());
        ui.all(); worker.all(); ui.all(); assertEquals(2, resources.size());
        try (LatestResourceCoordinator<String, Resource>.Read read = owner.acquire()) { assertEquals("B99", read.key); }
        owner.close(); ui.all(); for (Resource r : resources) assertEquals(1, r.closes); assertTrue(owner.isDrained());
    }

    @Test public void uncertainAcceptedChangeNoticeKeepsItsQueueReservation() {
        Queue worker = new Queue(), ui = new Queue(); int[] posts = {0}, changes = {0};
        LatestResourceCoordinator.Delivery uncertainNotice = action -> {
            ui.tasks.add(action); if (++posts[0] == 2) throw new RejectedExecutionException(); return true;
        };
        LatestResourceCoordinator<String, Resource> owner = new LatestResourceCoordinator<>(worker, uncertainNotice,
                new LatestResourceCoordinator.Observer() { @Override public void changed() { changes[0]++; } });
        for (int index = 0; index < 100; index++) {
            owner.request("A" + index, obsolete -> new Resource("A")); worker.all(); ui.tasks.removeLast().run();
            assertEquals(1, ui.tasks.size()); assertEquals(1, owner.outputCount());
        }
        assertEquals(1, owner.deliveryFailures()); ui.all(); assertEquals(1, changes[0]); owner.close(); ui.all();
    }

    @Test public void blockedDisposerKeepsDrainFalseAndRunsOutsideLock() throws Exception {
        Queue worker = new Queue(); CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        LatestResourceCoordinator<String, Resource> owner = new LatestResourceCoordinator<>(worker, action -> { action.run(); return true; },
                new LatestResourceCoordinator.Observer() { });
        Resource resource = new Resource("A"); resource.onClose = () -> {
            assertFalse(Thread.holdsLock(owner)); entered.countDown();
            try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test disposer timeout"); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
        };
        owner.request("A", obsolete -> resource); worker.all();
        Thread closing = new Thread(owner::close); closing.start();
        try { assertTrue(entered.await(2, TimeUnit.SECONDS)); assertTrue(closing.isAlive()); assertFalse(owner.isDrained()); }
        finally { release.countDown(); closing.join(2000); }
        assertFalse(closing.isAlive()); assertEquals(1, resource.closes); assertTrue(owner.isDrained());
    }
}
