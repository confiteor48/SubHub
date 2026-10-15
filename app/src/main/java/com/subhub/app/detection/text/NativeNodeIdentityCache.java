package com.subhub.app.detection.text;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import com.subhub.app.censor.engine.NativeTextFrame;
import com.subhub.app.censor.engine.NativeTextResult;
import com.subhub.app.censor.engine.SceneEngine;

/** Bounded exact-node handle ownership, confined to the reader's serial executor. No text/pose matching. */
final class NativeNodeIdentityCache<N> implements AutoCloseable {
    interface Handles<N> {
        boolean same(N first, N second);
        N copy(N node);
        void release(N node);
    }
    private static final class Entry<N> {
        final N node;
        final long token;
        long usedRead, usedOrder;
        boolean releaseFailed;
        Basis basis;
        boolean refreshable;
        Entry(N node, long token) { this.node = node; this.token = token; }
    }
    /** Historical admitted facts identify an address; they grant no current evidence or pose lease. */
    static final class Basis {
        final SceneEngine.Source source;
        final String nodeKey, contentKey, category;
        final NativeTextResult.TextSource textSource;
        final int matchStart, matchEnd, normalizedLength;
        final long hardGeneration, originalValidUntilMillis;
        Basis(NativeTextFrame frame, NativeTextResult.Candidate candidate) {
            source = frame.source; nodeKey = candidate.nodeKey; contentKey = candidate.contentKey; category = candidate.category;
            textSource = candidate.textSource; matchStart = candidate.matchStart; matchEnd = candidate.matchEnd;
            normalizedLength = candidate.normalizedLength; hardGeneration = frame.read.hardGeneration;
            originalValidUntilMillis = candidate.validatedAtMillis > Long.MAX_VALUE - frame.maximumAgeMillis
                    ? Long.MAX_VALUE : candidate.validatedAtMillis + frame.maximumAgeMillis;
        }
    }
    static final class Address<N> {
        final long token;
        /** Borrowed exact handle. An operation must clone it before refreshing or disposing it. */
        final N node;
        final Basis basis;
        Address(Entry<N> entry) { token = entry.token; node = entry.node; basis = entry.basis; }
    }

    private final int maximum;
    private final Handles<N> handles;
    private final List<Entry<N>> entries = new ArrayList<>();
    private String scope;
    private long nextToken, read, order;
    private long nowMillis, hardGeneration = -1, roundRobinAfter;
    private long settledRead = -1;
    private boolean active, closed, resourceFailure;

    NativeNodeIdentityCache(int maximum, Handles<N> handles) {
        if (maximum <= 0 || maximum > 64) throw new IllegalArgumentException("native identity capacity");
        this.maximum = maximum; this.handles = Objects.requireNonNull(handles);
    }

    void begin(String selectedScope) {
        begin(selectedScope, 0, -1);
    }

    void begin(String selectedScope, long nowMillis, long hardGeneration) {
        Objects.requireNonNull(selectedScope);
        if (nowMillis < 0 || hardGeneration < -1) throw new IllegalArgumentException("native identity authority");
        if (active || closed || resourceFailure) throw new IllegalStateException("native identity lifecycle");
        if (selectedScope.equals(scope) && nowMillis < this.nowMillis) throw new IllegalStateException("native address clock reversal");
        if (!selectedScope.equals(scope)) { clear(); scope = selectedScope; roundRobinAfter = 0; }
        this.nowMillis = nowMillis; this.hardGeneration = hardGeneration;
        for (Entry<N> entry : entries) if (entry.basis != null && entry.basis.hardGeneration != hardGeneration) entry.refreshable = false;
        if (read == Long.MAX_VALUE) throw new IllegalStateException("native identity read exhausted");
        read++; active = true;
    }

    long resolve(N node) {
        requireRead(); Objects.requireNonNull(node);
        for (Entry<N> entry : entries) {
            if (handles.same(entry.node, node)) { touch(entry); return entry.token; }
        }
        if (nextToken == Long.MAX_VALUE || order == Long.MAX_VALUE) throw new IllegalStateException("native identity exhausted");
        if (entries.size() == maximum) {
            Entry<N> victim = null;
            for (Entry<N> entry : entries) {
                boolean admitted = entry.basis != null && nowMillis <= entry.basis.originalValidUntilMillis;
                if (entry.usedRead != read && !admitted && (victim == null || entry.usedOrder < victim.usedOrder)) victim = entry;
            }
            if (victim == null) throw new IllegalStateException("native identity read exceeds capacity");
            release(victim); entries.remove(victim);
        }
        N copy = Objects.requireNonNull(handles.copy(node));
        if (copy == node) throw new IllegalStateException("native identity copy aliases borrowed input");
        Entry<N> created = new Entry<>(copy, ++nextToken);
        entries.add(created); // Own the new handle before any further adapter operation can throw.
        if (!handles.same(copy, node)) {
            release(created); entries.remove(created);
            throw new IllegalStateException("native identity copy differs");
        }
        touch(created); return created.token;
    }

    /** Confirmation can only use the identity already pinned by this read, never mint a replacement. */
    long known(N node) {
        requireRead();
        for (Entry<N> entry : entries) if (entry.usedRead == read && handles.same(entry.node, node)) return entry.token;
        return 0;
    }

    void end() {
        if (!active) throw new IllegalStateException("native identity read absent");
        active = false;
    }

    long readIdentity() { requireRead(); return read; }

    int admittedAddresses(long epoch, long geometry, long hardGeneration, com.subhub.app.censor.engine.ProtectionScope protection) {
        if (closed || resourceFailure) return 0;
        int result = 0;
        for (Entry<N> entry : entries) {
            Basis basis = entry.basis;
            if (basis != null && entry.refreshable && basis.source.epoch == epoch && basis.source.geometry == geometry
                    && basis.hardGeneration == hardGeneration && basis.source.protectionScope.equals(protection)) result++;
        }
        return result;
    }

    /** Rotation is independent of tree-order/LRU touches and advances even if this attempt fails. */
    Address<N> nextAddress() {
        requireRead(); Entry<N> first = null, following = null;
        for (Entry<N> entry : entries) if (entry.basis != null && entry.refreshable && entry.basis.hardGeneration == hardGeneration) {
            if (first == null || entry.token < first.token) first = entry;
            if (entry.token > roundRobinAfter && (following == null || entry.token < following.token)) following = entry;
        }
        Entry<N> selected = following == null ? first : following;
        if (selected == null) return null;
        roundRobinAfter = selected.token; touch(selected); return new Address<>(selected);
    }

    /** Pin a finite, distinct pass before any selected node's refresh can block or fail. */
    List<Address<N>> nextAddresses(int count) {
        requireRead();
        if (count < 1 || count > NativeTextResult.COHORT_CANDIDATES) throw new IllegalArgumentException("native cohort size");
        int available = 0;
        for (Entry<N> entry : entries)
            if (entry.basis != null && entry.refreshable && entry.basis.hardGeneration == hardGeneration) available++;
        if (available < count) throw new IllegalStateException("native cohort addresses unavailable");
        List<Address<N>> selected = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Address<N> address = Objects.requireNonNull(nextAddress());
            for (Address<N> previous : selected)
                if (previous.token == address.token || handles.same(previous.node, address.node))
                    throw new IllegalStateException("native cohort duplicated address");
            selected.add(address);
        }
        return Collections.unmodifiableList(selected);
    }

    void retireAddress(long token) {
        for (Entry<N> entry : entries) if (entry.token == token) { entry.refreshable = false; return; }
    }

    /**
     * Called only after a sealed exact Engine decision. Settlement changes metadata, never
     * releases native clones after a semantic job has already reached its terminal.
     */
    boolean admit(long completedRead, NativeTextFrame frame, List<Long> tokens) {
        if (active || closed || resourceFailure || read != completedRead || settledRead == completedRead || frame.read.hardGeneration != hardGeneration
                || tokens.size() != frame.read.candidates.size()
                || (frame.read.coverage == NativeTextResult.Coverage.POSITIVE_NODES_ONLY && tokens.isEmpty())) return false;
        List<Entry<N>> selected = new ArrayList<>(); List<Basis> bases = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            Entry<N> match = null;
            for (Entry<N> entry : entries) if (entry.token == tokens.get(i) && entry.usedRead == completedRead) match = entry;
            NativeTextResult.Candidate candidate = frame.read.candidates.get(i);
            if (match == null || selected.contains(match) || candidate.nodeKey == null || !candidate.matchProvenanceKnown()
                    || candidate.validatedAtMillis < 0) return false;
            selected.add(match); bases.add(new Basis(frame, candidate));
        }
        if (frame.read.coverage == NativeTextResult.Coverage.COMPLETE_TREE)
            for (Entry<N> entry : entries) if (!selected.contains(entry)) { entry.basis = null; entry.refreshable = false; }
        for (int i = 0; i < selected.size(); i++) { selected.get(i).basis = bases.get(i); selected.get(i).refreshable = true; }
        settledRead = completedRead;
        return true;
    }

    boolean resourceFailure() { return resourceFailure; }
    int retained() { return entries.size(); }
    boolean drained() { return closed && !active && entries.isEmpty() && !resourceFailure; }

    private void requireRead() {
        if (!active || closed || resourceFailure) throw new IllegalStateException("native identity read unavailable");
    }
    private void touch(Entry<N> entry) {
        if (order == Long.MAX_VALUE) throw new IllegalStateException("native identity order exhausted");
        entry.usedRead = read; entry.usedOrder = ++order;
    }
    private void release(Entry<N> entry) {
        if (entry.releaseFailed) throw new IllegalStateException("native identity release uncertain");
        try { handles.release(entry.node); }
        catch (RuntimeException | Error failure) { entry.releaseFailed = true; resourceFailure = true; throw failure; }
    }
    private void clear() {
        Throwable first = null;
        for (Entry<N> entry : new ArrayList<>(entries)) {
            try { release(entry); entries.remove(entry); }
            catch (RuntimeException | Error failure) { if (first == null) first = failure; }
        }
        if (first != null) throw new IllegalStateException("native identity disposal unproved", first);
    }
    @Override public void close() {
        if (active) throw new IllegalStateException("native identity read still owns handles");
        if (closed) {
            if (resourceFailure) throw new IllegalStateException("native identity disposal unproved");
            return;
        }
        closed = true; clear();
    }
}
