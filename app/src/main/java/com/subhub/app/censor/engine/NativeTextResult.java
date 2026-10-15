package com.subhub.app.censor.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Typed provider-revalidated facts. Partial positives do not claim an observed whole tree. */
public final class NativeTextResult {
    public enum Coverage { COMPLETE_TREE, POSITIVE_NODES_ONLY }
    public enum ReadMode { FULL_DISCOVERY, ADMITTED_NODE_RECLASSIFY, ADMITTED_NODE_COHORT_RECLASSIFY }
    public enum TextSource { UNKNOWN, TEXT, CONTENT_DESCRIPTION }
    public static final int MAXIMUM_CANDIDATES = 64;
    /** Smallest multi-address pass; larger cohorts need separate renewal and discovery qualification. */
    public static final int COHORT_CANDIDATES = 2;
    public static final int MAXIMUM_REGIONS = 256;
    public static final int MAXIMUM_LINES = 12;
    public final int width, height, windowId;
    public final long readStartedAtMillis, readFinishedAtMillis;
    public final List<Candidate> candidates;
    public final int visitedNodes, classifiedNodes, confirmationReads;
    public final Coverage coverage;
    public final ReadMode readMode;
    /** -1 is unknown legacy complete-read provenance, never an admissible partial-read token. */
    public final long scanGeneration, confirmationGeneration, hardGeneration;
    public final long classificationCompletedAtMillis, confirmationStartedAtMillis;

    public static final class Candidate {
        public final String category;
        /** Opaque content digest, not a retained private caption. */
        public final String contentKey;
        /** Null means persistent provider-node identity is unknown. */
        public final String nodeKey;
        public final float confidence;
        public final boolean exactGeometry;
        public final List<SceneEngine.Region> regions;
        public final TextSource textSource;
        public final int matchStart, matchEnd, normalizedLength;
        public final long validatedAtMillis;

        public Candidate(String category, String contentKey, String nodeKey, float confidence,
                boolean exactGeometry, List<SceneEngine.Region> regions) {
            this(category, contentKey, nodeKey, confidence, exactGeometry, regions,
                    TextSource.UNKNOWN, -1, -1, -1, -1);
        }

        public Candidate(String category, String contentKey, String nodeKey, float confidence,
                boolean exactGeometry, List<SceneEngine.Region> regions, TextSource textSource,
                int matchStart, int matchEnd, int normalizedLength, long validatedAtMillis) {
            if (category == null || category.isEmpty() || category.length() > 80
                    || !digest(contentKey) || (nodeKey != null && !digest(nodeKey))
                    || !Float.isFinite(confidence) || confidence < 0 || confidence > 1) {
                throw new IllegalArgumentException("native candidate");
            }
            this.category = category; this.contentKey = contentKey; this.nodeKey = nodeKey;
            this.confidence = confidence; this.exactGeometry = exactGeometry;
            this.regions = bounded(regions, MAXIMUM_LINES, false);
            this.textSource = Objects.requireNonNull(textSource);
            boolean unknownSpan = matchStart == -1 && matchEnd == -1 && normalizedLength == -1;
            if ((!unknownSpan && (matchStart < 0 || matchEnd <= matchStart || matchEnd > normalizedLength
                    || normalizedLength > 2000)) || validatedAtMillis < -1) throw new IllegalArgumentException("native match provenance");
            this.matchStart = matchStart; this.matchEnd = matchEnd; this.normalizedLength = normalizedLength;
            this.validatedAtMillis = validatedAtMillis;
        }

        public boolean matchProvenanceKnown() { return textSource != TextSource.UNKNOWN && matchStart >= 0; }

        boolean sameContent(Candidate other) {
            return category.equals(other.category) && contentKey.equals(other.contentKey)
                    && textSource == other.textSource && matchStart == other.matchStart
                    && matchEnd == other.matchEnd && normalizedLength == other.normalizedLength;
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
        this(width, height, windowId, readStartedAtMillis, readFinishedAtMillis, candidates,
                visitedNodes, classifiedNodes, confirmationReads, Coverage.COMPLETE_TREE, -1, -1, -1, -1, -1);
    }

    public NativeTextResult(int width, int height, int windowId, long readStartedAtMillis,
            long readFinishedAtMillis, List<Candidate> candidates, int visitedNodes, int classifiedNodes,
            int confirmationReads, Coverage coverage, long scanGeneration, long confirmationGeneration,
            long hardGeneration, long classificationCompletedAtMillis, long confirmationStartedAtMillis) {
        this(width, height, windowId, readStartedAtMillis, readFinishedAtMillis, candidates, visitedNodes,
                classifiedNodes, confirmationReads, coverage, scanGeneration, confirmationGeneration, hardGeneration,
                classificationCompletedAtMillis, confirmationStartedAtMillis, ReadMode.FULL_DISCOVERY);
    }

    public NativeTextResult(int width, int height, int windowId, long readStartedAtMillis,
            long readFinishedAtMillis, List<Candidate> candidates, int visitedNodes, int classifiedNodes,
            int confirmationReads, Coverage coverage, long scanGeneration, long confirmationGeneration,
            long hardGeneration, long classificationCompletedAtMillis, long confirmationStartedAtMillis, ReadMode readMode) {
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
        this.coverage = Objects.requireNonNull(coverage);
        this.readMode = Objects.requireNonNull(readMode);
        if (readMode == ReadMode.ADMITTED_NODE_RECLASSIFY && (coverage != Coverage.POSITIVE_NODES_ONLY
                || candidates.size() != 1 || visitedNodes != 1 || classifiedNodes != 1 || confirmationReads != 1))
            throw new IllegalArgumentException("direct native read cannot claim a tree or an unknown selected node");
        if (readMode == ReadMode.ADMITTED_NODE_COHORT_RECLASSIFY && (coverage != Coverage.POSITIVE_NODES_ONLY
                || visitedNodes != COHORT_CANDIDATES || classifiedNodes != COHORT_CANDIDATES
                || confirmationReads < 1 || confirmationReads > COHORT_CANDIDATES || candidates.size() != confirmationReads))
            throw new IllegalArgumentException("native cohort selected/confirmed inventory");
        boolean unknownRevision = scanGeneration == -1 && confirmationGeneration == -1 && hardGeneration == -1;
        boolean unknownClocks = classificationCompletedAtMillis == -1 && confirmationStartedAtMillis == -1;
        if (unknownRevision != unknownClocks
                || (!unknownRevision && (scanGeneration < 0 || confirmationGeneration < scanGeneration || hardGeneration < 0))
                || (coverage == Coverage.COMPLETE_TREE && !unknownRevision && scanGeneration != confirmationGeneration)
                || (!unknownClocks && (classificationCompletedAtMillis < readStartedAtMillis
                    || classificationCompletedAtMillis > readFinishedAtMillis
                    || (this.candidates.isEmpty() ? confirmationStartedAtMillis != -1
                        : confirmationStartedAtMillis < classificationCompletedAtMillis
                            || confirmationStartedAtMillis - classificationCompletedAtMillis < 48
                            || confirmationStartedAtMillis > readFinishedAtMillis)))
                || (coverage == Coverage.POSITIVE_NODES_ONLY && (unknownRevision || unknownClocks))) {
            throw new IllegalArgumentException("native read coverage provenance");
        }
        this.scanGeneration = scanGeneration; this.confirmationGeneration = confirmationGeneration;
        this.hardGeneration = hardGeneration; this.classificationCompletedAtMillis = classificationCompletedAtMillis;
        this.confirmationStartedAtMillis = confirmationStartedAtMillis;
        int count = 0;
        SceneEngine.Region bounds = new SceneEngine.Region(0, 0, width, height);
        for (Candidate candidate : this.candidates) {
            if (coverage == Coverage.POSITIVE_NODES_ONLY && (candidate.nodeKey == null || !candidate.matchProvenanceKnown()
                    || candidate.validatedAtMillis < confirmationStartedAtMillis || candidate.validatedAtMillis > readFinishedAtMillis))
                throw new IllegalArgumentException("unbound partial native candidate");
            if (!unknownClocks && (candidate.validatedAtMillis < confirmationStartedAtMillis
                    || !candidate.matchProvenanceKnown())) throw new IllegalArgumentException("unconfirmed typed native candidate");
            if (candidate.validatedAtMillis != -1 && (candidate.validatedAtMillis < readStartedAtMillis
                    || candidate.validatedAtMillis > readFinishedAtMillis)) throw new IllegalArgumentException("native candidate clock");
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

    /** A one-member cohort is a nonterminal observation, never successful completion by omission. */
    public boolean terminalInventoryValid() {
        return readMode != ReadMode.ADMITTED_NODE_COHORT_RECLASSIFY || candidates.size() == COHORT_CANDIDATES;
    }

    public boolean observationInventoryValid(int ordinal) {
        return coverage == Coverage.POSITIVE_NODES_ONLY && candidates.size() == 1 && ordinal > 0
                && ordinal <= MAXIMUM_CANDIDATES && (readMode == ReadMode.FULL_DISCOVERY
                    || (readMode == ReadMode.ADMITTED_NODE_COHORT_RECLASSIFY && ordinal == 1 && confirmationReads == 1));
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
