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

    public NativeTextFrame(SceneEngine.Source source, long partition, NativeTextResult read, long maximumAgeMillis) {
        this(source, partition, read, maximumAgeMillis, -1, -1, -1);
    }

    public NativeTextFrame(SceneEngine.Source source, long partition, NativeTextResult read, long maximumAgeMillis,
            long requestIdentity, long queuedAtMillis, long startedAtMillis) {
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
        this.partition = partition;
        this.requestIdentity = requestIdentity; this.queuedAtMillis = queuedAtMillis; this.startedAtMillis = startedAtMillis;
        validUntilMillis = source.readFinishedAtMillis >= Long.MAX_VALUE - maximumAgeMillis
                ? Long.MAX_VALUE : source.readFinishedAtMillis + maximumAgeMillis;
    }
}
