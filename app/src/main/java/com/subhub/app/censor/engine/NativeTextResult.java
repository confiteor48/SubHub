package com.subhub.app.censor.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** A complete, provider-revalidated read. It contains no pixels, text strings or native nodes. */
public final class NativeTextResult {
    public static final int MAXIMUM_CANDIDATES = 64;
    public static final int MAXIMUM_REGIONS = 256;
    public static final int MAXIMUM_LINES = 12;
    public final int width, height, windowId;
    public final long readStartedAtMillis, readFinishedAtMillis;
    public final List<Candidate> candidates;
    public final int visitedNodes, classifiedNodes, confirmationReads;

    public static final class Candidate {
        public final String category;
        /** Opaque content digest, not a retained private caption. */
        public final String contentKey;
        /** Null means persistent provider-node identity is unknown. */
        public final String nodeKey;
        public final float confidence;
        public final boolean exactGeometry;
        public final List<SceneEngine.Region> regions;

        public Candidate(String category, String contentKey, String nodeKey, float confidence,
                boolean exactGeometry, List<SceneEngine.Region> regions) {
            if (category == null || category.isEmpty() || category.length() > 80
                    || !digest(contentKey) || (nodeKey != null && !digest(nodeKey))
                    || !Float.isFinite(confidence) || confidence < 0 || confidence > 1) {
                throw new IllegalArgumentException("native candidate");
            }
            this.category = category; this.contentKey = contentKey; this.nodeKey = nodeKey;
            this.confidence = confidence; this.exactGeometry = exactGeometry;
            this.regions = bounded(regions, MAXIMUM_LINES, false);
        }

        boolean sameContent(Candidate other) {
            return category.equals(other.category) && contentKey.equals(other.contentKey);
        }

        boolean samePose(Candidate other) {
            if (regions.size() != other.regions.size()) return false;
            for (int i = 0; i < regions.size(); i++) {
                SceneEngine.Region a = regions.get(i), b = other.regions.get(i);
                if (a.left != b.left || a.top != b.top || a.right != b.right || a.bottom != b.bottom) return false;
            }
            return true;
        }
    }

    public NativeTextResult(int width, int height, int windowId, long readStartedAtMillis,
            long readFinishedAtMillis, List<Candidate> candidates,
            int visitedNodes, int classifiedNodes, int confirmationReads) {
        if (width <= 0 || height <= 0 || windowId < 0 || readStartedAtMillis < 0
                || readFinishedAtMillis < readStartedAtMillis || visitedNodes < 0 || visitedNodes > 900
                || classifiedNodes < 0 || classifiedNodes > visitedNodes
                || confirmationReads < 0 || confirmationReads > MAXIMUM_CANDIDATES) {
            throw new IllegalArgumentException("native read");
        }
        this.width = width; this.height = height; this.windowId = windowId;
        this.readStartedAtMillis = readStartedAtMillis; this.readFinishedAtMillis = readFinishedAtMillis;
        this.candidates = bounded(candidates, MAXIMUM_CANDIDATES, true);
        if (this.candidates.size() > classifiedNodes || this.candidates.size() > confirmationReads) {
            throw new IllegalArgumentException("unconfirmed native candidates");
        }
        this.visitedNodes = visitedNodes; this.classifiedNodes = classifiedNodes;
        this.confirmationReads = confirmationReads;
        int count = 0;
        SceneEngine.Region bounds = new SceneEngine.Region(0, 0, width, height);
        for (Candidate candidate : this.candidates) {
            count += candidate.regions.size();
            if (count > MAXIMUM_REGIONS) throw new IllegalArgumentException("native region capacity");
            for (SceneEngine.Region region : candidate.regions) {
                if (!bounds.contains(region)) throw new IllegalArgumentException("native region outside display");
            }
        }
        for (int i = 0; i < this.candidates.size(); i++) {
            Candidate first = this.candidates.get(i);
            for (int j = i + 1; j < this.candidates.size(); j++) {
                Candidate second = this.candidates.get(j);
                if ((first.nodeKey != null && first.nodeKey.equals(second.nodeKey))
                        || (first.nodeKey == null && second.nodeKey == null
                        && first.sameContent(second) && first.samePose(second))) {
                    throw new IllegalArgumentException("ambiguous native candidate identity");
                }
            }
        }
    }

    public int regionCount() {
        int count = 0; for (Candidate candidate : candidates) count += candidate.regions.size(); return count;
    }

    private static boolean digest(String value) {
        if (value == null || value.length() != 64) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i); if ((c < '0' || c > '9') && (c < 'a' || c > 'f')) return false;
        }
        return true;
    }

    private static <T> List<T> bounded(List<T> input, int maximum, boolean allowEmpty) {
        Objects.requireNonNull(input);
        int size = input.size();
        if (size > maximum || (!allowEmpty && size == 0)) throw new IllegalArgumentException("native list size");
        List<T> copy = new ArrayList<>(size);
        for (int i = 0; i < size; i++) copy.add(Objects.requireNonNull(input.get(i)));
        if (input.size() != size) throw new IllegalArgumentException("native list changed during copy");
        return Collections.unmodifiableList(copy);
    }
}
