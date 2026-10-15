package com.subhub.app.censor.android;

import java.util.Objects;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;

/** Debug-only callback leases. Publication alone never establishes protocol readiness. */
final class FixtureServiceConnection<T> {
    static final class Lease<T> {
        final Object registry;
        final T service;
        final long generation;
        volatile boolean retiring, retired;
        volatile Throwable failure;
        volatile int completedCallbacks;
        private Lease(Object registry, T service, long generation) {
            this.registry = registry; this.service = service; this.generation = generation;
        }
        boolean completedSuccessfully() { return retired && !retiring && failure == null; }
    }
    static final class Snapshot<T, I> {
        final Lease<T> lease;
        final I info;
        private Snapshot(Lease<T> lease, I info) { this.lease = lease; this.info = info; }
    }

    private final Object identity = new Object();
    private final Consumer<T> publication;
    private final IdentityHashMap<T, Boolean> publishedInstances = new IdentityHashMap<>();
    private Lease<T> current;
    private long generation;

    FixtureServiceConnection(Consumer<T> publication) { this.publication = Objects.requireNonNull(publication); }

    synchronized Lease<T> connected(T service) {
        Objects.requireNonNull(service);
        if (publishedInstances.containsKey(service)) throw new IllegalStateException("Ambiguous fixture instance reconnection");
        long next = Math.addExact(generation, 1);
        Lease<T> lease = new Lease<>(identity, service, next);
        publishedInstances.put(service, Boolean.TRUE);
        generation = next; current = lease; publication.accept(service); return lease;
    }
    synchronized boolean isCurrent(Lease<T> lease) {
        return lease != null && lease.registry == identity && current == lease
                && !lease.retiring && !lease.retired && lease.failure == null;
    }
    synchronized Lease<T> currentLease() { return current; }
    synchronized long lastGeneration() { return generation; }

    /** Bounded cleanup ownership only. A new lease never qualifies the originally selected one. */
    synchronized List<Lease<T>> closureTargets(Lease<T> own) {
        if (own != null && own.registry != identity) throw new IllegalArgumentException("Foreign fixture cleanup");
        List<Lease<T>> targets = new ArrayList<>(2);
        if (own != null) targets.add(own);
        if (current != null && current != own) targets.add(current);
        return targets;
    }

    /** Registry memory only: never call Binder, settings, shell or the main queue under this lock. */
    synchronized Map<String, Object> inspect(Lease<T> own) {
        if (own == null || own.registry != identity) throw new IllegalArgumentException("Foreign fixture observation");
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("ownGeneration", own.generation); values.put("ownRetiring", own.retiring);
        values.put("ownRetired", own.retired); values.put("ownCallbacks", own.completedCallbacks);
        values.put("ownFailureType", own.failure == null ? null : own.failure.getClass().getName());
        values.put("currentPresent", current != null); values.put("currentIsOwn", current == own);
        values.put("currentGeneration", current == null ? -1L : current.generation);
        values.put("currentServiceIsOwn", current != null && current.service == own.service);
        values.put("lastGeneration", generation); return values;
    }

    static boolean activeSelection(String enabled, String services, String component) {
        return "1".equals(enabled) && services != null && Arrays.asList(services.split(":")).contains(component);
    }

    <I> Snapshot<T, I> ready(Function<T, I> query, BiPredicate<T, I> validate) {
        Lease<T> selected = currentLease();
        if (!isCurrent(selected)) return null;
        I info = query.apply(selected.service);
        if (info == null || !validate.test(selected.service, info) || !isCurrent(selected)) return null;
        return new Snapshot<>(selected, info);
    }

    /** Called only by actual service lifecycle callbacks, never to satisfy a test's marker. */
    void retire(Lease<T> lease, Runnable cleanup, Runnable superclassCallback) {
        boolean first;
        synchronized (this) {
            if (lease == null || lease.registry != identity) throw new IllegalArgumentException("Foreign fixture lease");
            if (lease.retiring) throw new IllegalStateException("Concurrent fixture lifecycle callback");
            first = !lease.retired; lease.retiring = true;
        }
        Throwable failure = lease.failure;
        try {
            if (first) try { cleanup.run(); }
            catch (RuntimeException | Error error) { failure = combine(failure, error); }
            try { superclassCallback.run(); }
            catch (RuntimeException | Error error) { failure = combine(failure, error); }
        } finally {
            synchronized (this) {
                lease.failure = failure; lease.completedCallbacks++; lease.retired = true; lease.retiring = false;
                if (current == lease) { current = null; publication.accept(null); }
            }
        }
        if (failure instanceof RuntimeException) throw (RuntimeException) failure;
        if (failure instanceof Error) throw (Error) failure;
    }
    private static Throwable combine(Throwable first, Throwable next) {
        if (first == null) return next;
        if (first != next) first.addSuppressed(next);
        return first;
    }
}
