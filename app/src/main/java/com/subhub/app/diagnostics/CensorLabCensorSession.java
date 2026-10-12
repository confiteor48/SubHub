package com.subhub.app.diagnostics;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** Process-local recording ownership; never arms or persists a normal service session. */
public final class CensorLabCensorSession {
    private static final AtomicReference<Stamp> STATE = new AtomicReference<>(new Stamp(false));
    private static final CopyOnWriteArrayList<Runnable> OBSERVERS = new CopyOnWriteArrayList<>();
    private static final Object TRANSITION_LOCK = new Object();
    private static Thread admittingThread;

    private CensorLabCensorSession() { }

    public static boolean isActive() { return STATE.get().active; }

    /** Read-only ownership identity; an end/restart (including inactive ABA) invalidates it. */
    public static Stamp stamp() { return STATE.get(); }
    public static final class Stamp {
        public final boolean active;
        private Stamp(boolean active) { this.active = active; }
        public boolean isCurrent() { return STATE.get() == this; }
    }

    /** A Lab-owned captured frame cannot become billable when its recording ends mid-inference. */
    public static boolean allowsNormalCensorEffects(boolean labOwnedSource) {
        return !labOwnedSource && !isActive();
    }

    /**
     * Linearizes a short authority admission against Lab start/stop. Never pass disk work,
     * platform, UI or observer callbacks; those must not block a permission transition here.
     */
    public static <T> T withCurrentNormal(Stamp expected, Supplier<T> mutation, T rejected) {
        Objects.requireNonNull(mutation);
        synchronized (TRANSITION_LOCK) {
            if (expected == null || expected.active || STATE.get() != expected) return rejected;
            if (admittingThread != null) throw new IllegalStateException("Nested normal-effect admission");
            admittingThread = Thread.currentThread();
            try { return mutation.get(); } finally { admittingThread = null; }
        }
    }

    public static Lease acquire() {
        Stamp selected;
        synchronized (TRANSITION_LOCK) {
            if (admittingThread == Thread.currentThread()) throw new IllegalStateException("Lab transition during normal-effect admission");
            Stamp previous = STATE.get(); selected = new Stamp(true);
            if (previous.active || !STATE.compareAndSet(previous, selected)) {
                throw new IllegalStateException("A Censor Lab session already owns recognition");
            }
        }
        Lease lease = new Lease(selected);
        notifyObservers();
        return lease;
    }

    /** A late Accessibility connection reads current ownership independently of notifications. */
    public static Subscription observe(Runnable observer) {
        Objects.requireNonNull(observer);
        OBSERVERS.add(observer);
        return new Subscription(observer);
    }

    private static void notifyObservers() {
        for (Runnable observer : OBSERVERS) {
            try {
                observer.run();
            } catch (RuntimeException disconnected) {
                // An observer cannot strand recorder ownership. Accessibility also reconciles
                // the current state on its normal tick and when it reconnects.
                OBSERVERS.remove(observer);
            }
        }
    }

    public static final class Lease implements AutoCloseable {
        private final Stamp stamp;
        private Lease(Stamp stamp) { this.stamp = stamp; }

        @Override public void close() {
            boolean changed;
            synchronized (TRANSITION_LOCK) { changed = STATE.compareAndSet(stamp, new Stamp(false)); }
            if (changed) notifyObservers();
        }
    }

    public static final class Subscription implements AutoCloseable {
        private final Runnable observer;

        private Subscription(Runnable observer) { this.observer = observer; }

        @Override public void close() { OBSERVERS.remove(observer); }
    }
}
