package com.subhub.app.censor.android;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** Passive test-only references. This class never creates, closes or waits for native work. */
final class ParentBoundResourceObserver<O> {
    static final int MAXIMUM_PARENTS = 512;

    static final class Ticket<O> {
        final long epoch, request, identity, admittedAtMillis;
        private final Thread thread;
        private O owner;
        private boolean terminalSelected;
        private Ticket(long epoch, long request, long identity, long at, O owner, Thread thread) {
            this.epoch = epoch; this.request = request; this.identity = identity;
            admittedAtMillis = at; this.owner = owner; this.thread = thread;
        }
        O owner() {
            if (Thread.currentThread() != thread || owner == null)
                throw new IllegalStateException("Diagnostic owner absent or wrong thread");
            return owner;
        }
    }

    static final class State {
        final long enabledAtMillis, cutoffAtMillis;
        final int sampledParents, terminalParents, pendingParents;
        final boolean admissionsClosed;
        State(long enabled, long cutoff, int sampled, int terminal, int pending, boolean closed) {
            enabledAtMillis = enabled; cutoffAtMillis = cutoff; sampledParents = sampled;
            terminalParents = terminal; pendingParents = pending; admissionsClosed = closed;
        }
    }

    private long enabledAtMillis = -1, cutoffAtMillis = -1;
    private int sampledParents, terminalParents;
    private Thread thread;
    private Ticket<O> pending;
    private final Set<String> observed = new HashSet<>();

    synchronized void enable(long at) {
        if (at < 0 || enabledAtMillis >= 0) throw new IllegalStateException("Diagnostic admission already opened or invalid");
        enabledAtMillis = at;
    }

    synchronized boolean accepting() { return enabledAtMillis >= 0 && cutoffAtMillis < 0; }

    synchronized void cutoff(long at) {
        if (enabledAtMillis < 0) return;
        if (at < enabledAtMillis) throw new IllegalStateException("Diagnostic cutoff clock reversed");
        if (cutoffAtMillis < 0) cutoffAtMillis = at;
    }

    synchronized Ticket<O> reserve(long epoch, long request, long at) {
        if (!accepting()) return null;
        if (epoch < 0 || request <= 0 || at < enabledAtMillis) throw new IllegalArgumentException("Diagnostic parent");
        checkThread();
        String key = key(epoch, request);
        if (pending != null || observed.contains(key)) throw new IllegalStateException("Overlapping or duplicate diagnostic parent");
        if (sampledParents >= MAXIMUM_PARENTS) throw new IllegalStateException("Diagnostic parent inventory bound");
        observed.add(key);
        pending = new Ticket<>(epoch, request, ++sampledParents, at, null, thread);
        return pending;
    }

    synchronized void bind(Ticket<O> ticket, O owner) {
        checkThread();
        if (ticket == null || ticket != pending || ticket.owner != null)
            throw new IllegalStateException("Diagnostic owner binding does not own pending parent");
        ticket.owner = Objects.requireNonNull(owner);
    }

    synchronized Ticket<O> terminal(long epoch, long request) {
        if (pending == null && !observed.contains(key(epoch, request))) return null;
        checkThread();
        if (pending == null || pending.epoch != epoch || pending.request != request || pending.terminalSelected)
            throw new IllegalStateException("Missing, duplicate or wrong diagnostic terminal");
        pending.terminalSelected = true;
        return pending;
    }

    synchronized void finish(Ticket<O> ticket, long at) {
        checkThread();
        if (ticket == null || ticket != pending || !ticket.terminalSelected || at < ticket.admittedAtMillis)
            throw new IllegalStateException("Diagnostic terminal does not own pending parent");
        ticket.owner = null;
        pending = null;
        terminalParents++;
    }

    synchronized State state() {
        return new State(enabledAtMillis, cutoffAtMillis, sampledParents, terminalParents,
                pending == null ? 0 : 1, cutoffAtMillis >= 0);
    }

    private void checkThread() {
        if (thread == null) thread = Thread.currentThread();
        if (thread != Thread.currentThread()) throw new IllegalStateException("Diagnostic serial owner thread changed");
    }
    private static String key(long epoch, long request) { return epoch + ":" + request; }
}
