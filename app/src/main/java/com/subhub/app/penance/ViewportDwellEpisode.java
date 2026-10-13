package com.subhub.app.penance;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/** One stationary viewport episode; missing observations preserve legacy elapsed-gap timing. */
public final class ViewportDwellEpisode {
    public static final class Ticket {
        private final Object episode;
        private Ticket(Object episode) { this.episode = episode; }
    }
    private Object episode = new Object();
    private long startedAt = -1, lastObservationAt = -1;
    private boolean spent;
    private Ticket pending;

    /** Only an authenticated accepted observation may call this, not a render or timer. */
    public synchronized Ticket observe(boolean positive, long atMillis, long thresholdMillis) {
        if (atMillis < 0) throw new IllegalArgumentException("Observation clock");
        if (atMillis < lastObservationAt) return null;
        lastObservationAt = atMillis;
        if (!positive) return null;
        if (startedAt < 0) startedAt = atMillis;
        if (spent || pending != null || atMillis - startedAt < Math.max(1, thresholdMillis)) return null;
        return pending = new Ticket(episode);
    }

    public synchronized boolean current(Ticket ticket) {
        return ticket != null && ticket == pending && ticket.episode == episode && !spent;
    }

    /** Short source/session admission only. Never execute a disk/UI operation in this guard. */
    public synchronized boolean claim(Ticket ticket, BooleanSupplier admission) {
        Objects.requireNonNull(admission);
        if (!current(ticket) || !admission.getAsBoolean()) return false;
        // An admission callback can revoke the episode reentrantly.
        if (!current(ticket)) return false;
        pending = null; spent = true;
        return true;
    }

    /** A definitely rejected, not-yet-admitted request may be retried on another positive. */
    public synchronized void release(Ticket ticket) {
        if (current(ticket)) pending = null;
    }

    /** Scroll, foreground/window/configuration/session and owner changes start a new episode. */
    public synchronized void reset() {
        episode = new Object(); startedAt = -1; lastObservationAt = -1; spent = false; pending = null;
    }
}
