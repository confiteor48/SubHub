package com.subhub.app.censor.engine;

import java.util.Objects;

/** Owner-bound, finite native coverage. Only a new accepted read may renew its deadline. */
public final class NativeTextFrame {
    public final SceneEngine.Source source;
    public final long partition;
    public final NativeTextResult read;
    public final long validUntilMillis;
    /** Independent native job clocks; -1 means no runtime job provenance was supplied. */
    public final long requestIdentity, queuedAtMillis, startedAtMillis;
    public final long maximumAgeMillis;
    /** Zero is a terminal result. Positive ordinals are nonterminal whole-node observations. */
    public final int observationOrdinal;
    /** Runtime-minted accounting segment, never a public numeric provenance claim. */
    private final Object accountingChain;

    public NativeTextFrame(SceneEngine.Source source, long partition, NativeTextResult read, long maximumAgeMillis) {
        this(source, partition, read, maximumAgeMillis, -1, -1, -1);
    }

    public NativeTextFrame(SceneEngine.Source source, long partition, NativeTextResult read, long maximumAgeMillis,
            long requestIdentity, long queuedAtMillis, long startedAtMillis) {
        this(source, partition, read, maximumAgeMillis, requestIdentity, queuedAtMillis, startedAtMillis, 0);
    }

    public NativeTextFrame(SceneEngine.Source source, long partition, NativeTextResult read, long maximumAgeMillis,
            long requestIdentity, long queuedAtMillis, long startedAtMillis, int observationOrdinal) {
        this(source, partition, read, maximumAgeMillis, requestIdentity, queuedAtMillis, startedAtMillis,
                observationOrdinal, null);
    }

    NativeTextFrame(SceneEngine.Source source, long partition, NativeTextResult read, long maximumAgeMillis,
            long requestIdentity, long queuedAtMillis, long startedAtMillis, int observationOrdinal, Object accountingChain) {
        this.source = Objects.requireNonNull(source); this.read = Objects.requireNonNull(read);
        if (source.kind != SceneEngine.SourceKind.NATIVE_TEXT || maximumAgeMillis <= 0
                || source.width != read.width || source.height != read.height || source.windowId != read.windowId
                || source.readStartedAtMillis != read.readStartedAtMillis
                || source.readFinishedAtMillis != read.readFinishedAtMillis
                || (requestIdentity == -1 && (queuedAtMillis != -1 || startedAtMillis != -1))
                || (requestIdentity != -1 && (requestIdentity <= 0 || queuedAtMillis < 0
                || startedAtMillis < queuedAtMillis || startedAtMillis > source.readStartedAtMillis))) {
            throw new IllegalArgumentException("native frame provenance");
        }
        if (observationOrdinal < 0 || observationOrdinal > NativeTextResult.MAXIMUM_CANDIDATES
                || (observationOrdinal == 0 && !read.terminalInventoryValid())
                || (observationOrdinal > 0 && (requestIdentity <= 0 || !read.observationInventoryValid(observationOrdinal))))
            throw new IllegalArgumentException("native observation provenance");
        this.partition = partition;
        this.maximumAgeMillis = maximumAgeMillis;
        this.requestIdentity = requestIdentity; this.queuedAtMillis = queuedAtMillis; this.startedAtMillis = startedAtMillis;
        this.observationOrdinal = observationOrdinal;
        this.accountingChain = accountingChain;
        validUntilMillis = source.readFinishedAtMillis >= Long.MAX_VALUE - maximumAgeMillis
                ? Long.MAX_VALUE : source.readFinishedAtMillis + maximumAgeMillis;
    }

    /** Trusted-core capability access only; generic public frames cannot mint an engine's token. */
    Object accountingChainFor(NativeTextResult.Candidate candidate) {
        if (!read.candidates.contains(candidate)) throw new IllegalArgumentException("foreign native candidate");
        return accountingChain != null && requestIdentity > 0 && read.scanGeneration >= 0 && read.hardGeneration >= 0
                && candidate.nodeKey != null && candidate.exactGeometry && candidate.matchProvenanceKnown()
                && candidate.validatedAtMillis >= read.readStartedAtMillis
                && candidate.validatedAtMillis <= read.readFinishedAtMillis ? accountingChain : null;
    }

    boolean sharesAccountingChain(NativeTextFrame previous) {
        return previous != null && accountingChain != null && accountingChain == previous.accountingChain;
    }

    /** Parent intervals may overlap only within this exact acquisition, never by clock retagging. */
    boolean followsObservation(NativeTextFrame previous) {
        return previous != null && previous.observationOrdinal > 0
                && (observationOrdinal == 0 || observationOrdinal > previous.observationOrdinal)
                && requestIdentity > 0 && requestIdentity == previous.requestIdentity
                && queuedAtMillis == previous.queuedAtMillis && startedAtMillis == previous.startedAtMillis
                && partition == previous.partition && maximumAgeMillis == previous.maximumAgeMillis
                && source.epoch == previous.source.epoch && source.geometry == previous.source.geometry
                && source.width == previous.source.width && source.height == previous.source.height
                && source.windowId == previous.source.windowId && source.protectionScope.equals(previous.source.protectionScope)
                && source.readStartedAtMillis == previous.source.readStartedAtMillis
                && source.readFinishedAtMillis >= previous.source.readFinishedAtMillis
                && read.hardGeneration == previous.read.hardGeneration && read.scanGeneration == previous.read.scanGeneration
                && read.readMode == previous.read.readMode
                && (read.readMode == NativeTextResult.ReadMode.FULL_DISCOVERY
                    || read.readMode == NativeTextResult.ReadMode.ADMITTED_NODE_COHORT_RECLASSIFY);
    }

    /** A sibling finishing later cannot renew this independently validated partial node. */
    public long candidateValidUntilMillis(NativeTextResult.Candidate candidate) {
        if (!read.candidates.contains(Objects.requireNonNull(candidate))) throw new IllegalArgumentException("foreign native candidate");
        long at = candidate.validatedAtMillis == -1 ? source.readFinishedAtMillis : candidate.validatedAtMillis;
        return at >= Long.MAX_VALUE - maximumAgeMillis ? Long.MAX_VALUE : at + maximumAgeMillis;
    }
}
