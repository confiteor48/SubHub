package com.subhub.app.diagnostics;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/** Process-local recording ownership; never arms or persists a normal service session. */
public final class CensorLabCensorSession {
    private static final AtomicReference<Lease> ACTIVE = new AtomicReference<>();
    private static final CopyOnWriteArrayList<Runnable> OBSERVERS = new CopyOnWriteArrayList<>();

    private CensorLabCensorSession() { }

    public static boolean isActive() { return ACTIVE.get() != null; }

    /** A Lab-owned captured frame cannot become billable when its recording ends mid-inference. */
    public static boolean allowsNormalCensorEffects(boolean labOwnedSource) {
        return !labOwnedSource && !isActive();
    }

    public static Lease acquire() {
        Lease lease = new Lease();
        if (!ACTIVE.compareAndSet(null, lease)) {
            throw new IllegalStateException("A Censor Lab session already owns recognition");
        }
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
        private Lease() { }

        @Override public void close() {
            if (ACTIVE.compareAndSet(this, null)) notifyObservers();
        }
    }

    public static final class Subscription implements AutoCloseable {
        private final Runnable observer;

        private Subscription(Runnable observer) { this.observer = observer; }

        @Override public void close() { OBSERVERS.remove(observer); }
    }
}
