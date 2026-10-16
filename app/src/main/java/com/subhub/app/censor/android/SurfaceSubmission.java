package com.subhub.app.censor.android;

import com.subhub.app.censor.engine.SceneEngine;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Copied recording/post facts only. No pixels, render/session handles, tap or compositor authority. */
public final class SurfaceSubmission {
    public static final int MAXIMUM_MEMBERS = 256;
    public enum Mode { CLEAR, MEMBERS, REVERSE }

    public static final class Member {
        public final long identity, geometryEpoch, geometry, geometrySequence, geometryRequest;
        public final long evidenceEpoch, evidenceGeometry, evidenceSequence, evidenceRequest;
        public final SceneEngine.SourceKind geometryKind, evidenceKind;
        /** Actual float destination passed to effect(), AFTER padding, in physical display coordinates. */
        public final SceneEngine.Region destination, canvasClip, ownerClip;
        /** Rectangle intersection only; null is explicitly EMPTY, never observed zero work. */
        public final SceneEngine.Region clippedBounds;
        Member(long identity, SceneEngine.Source origin, SceneEngine.Source evidence,
                SceneEngine.Region destination, SceneEngine.Region canvasClip, SceneEngine.Region ownerClip) {
            if (identity <= 0) throw new IllegalArgumentException("member identity");
            this.identity = identity;
            geometryEpoch = origin.epoch; geometry = origin.geometry; geometrySequence = origin.sequence;
            geometryRequest = origin.imageRequestIdentity; geometryKind = origin.kind;
            evidenceEpoch = evidence.epoch; evidenceGeometry = evidence.geometry; evidenceSequence = evidence.sequence;
            evidenceRequest = evidence.imageRequestIdentity; evidenceKind = evidence.kind;
            this.destination = copy(Objects.requireNonNull(destination)); this.canvasClip = copy(Objects.requireNonNull(canvasClip));
            this.ownerClip = ownerClip == null ? null : copy(ownerClip);
            SceneEngine.Region intersection = destination.clippedTo(canvasClip);
            clippedBounds = intersection == null || ownerClip == null ? intersection : intersection.clippedTo(ownerClip);
        }
    }
    public static final class Paint {
        public final Mode mode;
        public final long epoch, geometry, sceneRevision, presentationRevision, sampledAtMillis;
        public final int rawMembers;
        public final List<Member> members;
        /** Reverse holes are intentionally unsupported; truncation/missing members remains incomplete. */
        public final boolean geometryComplete;
        private Paint(Mode mode, long epoch, long geometry, long sceneRevision, long presentationRevision,
                long sampledAtMillis, int rawMembers, List<Member> members) {
            if (rawMembers < 0 || members.size() > MAXIMUM_MEMBERS || members.size() > rawMembers)
                throw new IllegalArgumentException("submission member inventory");
            this.mode = Objects.requireNonNull(mode); this.epoch = epoch; this.geometry = geometry;
            this.sceneRevision = sceneRevision; this.presentationRevision = presentationRevision;
            this.sampledAtMillis = sampledAtMillis; this.rawMembers = rawMembers;
            this.members = Collections.unmodifiableList(new ArrayList<>(members));
            for (Member member : this.members) Objects.requireNonNull(member);
            geometryComplete = mode != Mode.REVERSE && rawMembers == members.size();
        }
        static Paint clear() { return CLEAR; }
        static Paint drawn(long epoch, long geometry, long sceneRevision, long presentationRevision,
                long sampledAtMillis, int rawMembers, List<Member> members, boolean reverse) {
            if (epoch < 0 || sceneRevision < -1 || sampledAtMillis < 0)
                throw new IllegalArgumentException("submission recording facts");
            return new Paint(reverse ? Mode.REVERSE : Mode.MEMBERS, epoch, geometry, sceneRevision,
                    presentationRevision, sampledAtMillis, rawMembers, members);
        }
        private static final Paint CLEAR = new Paint(Mode.CLEAR, -1, -1, -1, -1, -1, 0, Collections.emptyList());
    }

    public final long attempt, postSerial, endpointGeneration, postEndedAtMillis, renderThreadId;
    public final long compositorAtMillis = -1;
    public final long queuedNanos, lockNanos, recordingNanos, postNanos;
    public final int surfaceX, surfaceY, surfaceWidth, surfaceHeight;
    /** A throwing endpoint has UNKNOWN submission outcome, not proof of an unposted buffer. */
    /** Full Canvas clip validation only, not a scope, Surface-pose or compositor certificate. */
    public final boolean holderReturned, canvasClipValidated, recordingFinished, cleanupSucceeded;
    /** Null after collection failure is explicitly incomplete, never an invented clear. */
    public final Paint paint;
    SurfaceSubmission(long attempt, long postSerial, long endpointGeneration, long postEndedAtMillis,
            long renderThreadId, long queuedNanos, long lockNanos, long recordingNanos, long postNanos,
            int surfaceX, int surfaceY, int surfaceWidth, int surfaceHeight, boolean holderReturned,
            boolean canvasClipValidated, boolean recordingFinished, boolean cleanupSucceeded, Paint paint) {
        if (attempt <= 0 || postSerial <= 0 || endpointGeneration < 0 || postEndedAtMillis < 0
                || renderThreadId <= 0 || queuedNanos < 0 || lockNanos < 0 || recordingNanos < 0 || postNanos < 0
                || surfaceWidth <= 0 || surfaceHeight <= 0) throw new IllegalArgumentException("submission post facts");
        this.attempt = attempt; this.postSerial = postSerial; this.endpointGeneration = endpointGeneration;
        this.postEndedAtMillis = postEndedAtMillis; this.renderThreadId = renderThreadId;
        this.queuedNanos = queuedNanos; this.lockNanos = lockNanos; this.recordingNanos = recordingNanos; this.postNanos = postNanos;
        this.surfaceX = surfaceX; this.surfaceY = surfaceY; this.surfaceWidth = surfaceWidth; this.surfaceHeight = surfaceHeight;
        this.holderReturned = holderReturned; this.canvasClipValidated = canvasClipValidated;
        this.recordingFinished = recordingFinished; this.cleanupSucceeded = cleanupSucceeded; this.paint = paint;
    }
    private static SceneEngine.Region copy(SceneEngine.Region region) {
        return new SceneEngine.Region(region.left, region.top, region.right, region.bottom);
    }
}
