package com.subhub.app.censor.android;

/** One platform frame's advisory pose clock, never a source, expiry or compositor clock. */
final class PresentationFrame {
    final long callbackAtMillis, deadlineAtMillis, expectedAtMillis, revision;

    PresentationFrame(long callbackAtMillis, long deadlineAtMillis, long expectedAtMillis, long revision) {
        this.callbackAtMillis = callbackAtMillis;
        this.deadlineAtMillis = deadlineAtMillis;
        this.expectedAtMillis = expectedAtMillis;
        this.revision = revision;
    }

    long sampleAt(long actualNow, long currentRevision) {
        // A late worker cannot invent a replacement timeline, nor apply a callback's estimate
        // to movement observed after that callback. Revision tokens have no sign/time ordering.
        return callbackAtMillis >= 0 && callbackAtMillis <= actualNow
                && actualNow <= deadlineAtMillis && deadlineAtMillis <= expectedAtMillis
                && revision == currentRevision ? expectedAtMillis : actualNow;
    }
}
