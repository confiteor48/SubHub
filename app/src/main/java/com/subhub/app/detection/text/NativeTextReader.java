package com.subhub.app.detection.text;

import android.graphics.Rect;
import android.os.Build;
import android.view.accessibility.AccessibilityNodeInfo;

import com.subhub.app.censor.engine.CensorEngine;
import com.subhub.app.censor.engine.NativeTextResult;
import com.subhub.app.censor.engine.NativeReadCost;
import com.subhub.app.censor.engine.SceneEngine;
import com.subhub.app.detection.BBox;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Platform/classification adapter only. Scene, confirmation publication, retention and pixels live in the owner. */
public final class NativeTextReader implements CensorEngine.NativeTextWorker, AutoCloseable {
    public static final int MAXIMUM_NODES = 900;
    public static final int MAXIMUM_TEXT_LENGTH = 2000;

    public interface Provider {
        /** Transfers root ownership only on successful return; dispose unreturned handles on failure. */
        Tree open(CensorEngine.NativeTextInput input) throws Exception;
        /** Hard foreground/window/config validity; soft tree revision is recorded independently. */
        boolean current(CensorEngine.NativeTextInput input, Tree tree);
        /** Named semantic boundary; implementations may expose a precise adverse-control hook. */
        default boolean current(CensorEngine.NativeTextInput input, Tree tree, Boundary boundary) { return current(input, tree); }
        /** Hard/local scope only. Unaware providers retain their stricter actual current() check. */
        default boolean locallyCurrent(CensorEngine.NativeTextInput input, Tree tree) { return current(input, tree, Boundary.TRAVERSAL); }
        /** Explicit root-free scope acquisition for an already-admitted exact address. */
        default boolean addressRefreshSupported() { return false; }
        default Scope openScope(CensorEngine.NativeTextInput input) throws Exception { return null; }
        default boolean current(CensorEngine.NativeTextInput input, Scope scope, Boundary boundary) {
            return scope instanceof Tree && current(input, (Tree) scope, boundary);
        }
        default boolean locallyCurrent(CensorEngine.NativeTextInput input, Scope scope) {
            return scope instanceof Tree && locallyCurrent(input, (Tree) scope);
        }
        /** Optional controlled lifecycle boundary; never substitutes for a scope/evidence check. */
        default void beforeConfirmationScheduled(CensorEngine.NativeTextInput input, Scope scope) { }
    }
    public enum Boundary {
        OPENED, TRAVERSAL, SCAN_END, CONFIRMATION_ENTRY, BEFORE_FINAL_NODE_REFRESH,
        /** One actual scope query: the previous complete group's end and this group's pre-refresh. */
        GROUP_END_BEFORE_FINAL_NODE_REFRESH, RESULT_END
    }

    public static class Scope {
        public final int width, height, windowId;
        public final String packageName;
        public final long contentGeneration;
        public Scope(int width, int height, int windowId, String packageName, long contentGeneration) {
            if (width <= 0 || height <= 0 || windowId < 0) throw new IllegalArgumentException("native scope");
            if (packageName == null || packageName.isEmpty() || packageName.length() > 256) throw new IllegalArgumentException("native package");
            this.width = width; this.height = height; this.windowId = windowId;
            this.packageName = packageName; this.contentGeneration = contentGeneration;
        }
    }
    public static final class Tree extends Scope {
        public final AccessibilityNodeInfo root;
        public Tree(AccessibilityNodeInfo root, int width, int height, long contentGeneration) {
            super(width, height, Objects.requireNonNull(root).getWindowId(), packageOf(root), contentGeneration);
            this.root = root;
        }
        private static String packageOf(AccessibilityNodeInfo root) {
            CharSequence name = root.getPackageName(); return name == null ? null : name.toString();
        }
    }

    private final Provider provider;
    private final SmutTextClassifier classifier;
    private final TextSmutConfig config;
    private final boolean semanticEnabled, exactGeometryPreferred;
    private final ScheduledExecutorService executor;
    private final long confirmationMillis;
    interface NodeDisposer { void release(AccessibilityNodeInfo node); }
    private final NodeDisposer disposer;
    private final NativeNodeIdentityCache<AccessibilityNodeInfo> identities;
    private final String identityNamespace = java.util.UUID.randomUUID().toString();
    private boolean reading, closed;
    private Operation activeOperation;
    private final NativeReadSchedule readSchedule = new NativeReadSchedule();

    public NativeTextReader(Provider provider, SmutTextClassifier classifier, TextSmutConfig config,
            boolean semanticEnabled, boolean exactGeometryPreferred,
            ScheduledExecutorService executor, long confirmationMillis) {
        this(provider, classifier, config, semanticEnabled, exactGeometryPreferred, executor,
                confirmationMillis, AccessibilityNodeInfo::recycle);
    }

    NativeTextReader(Provider provider, SmutTextClassifier classifier, TextSmutConfig config,
            boolean semanticEnabled, boolean exactGeometryPreferred,
            ScheduledExecutorService executor, long confirmationMillis, NodeDisposer disposer) {
        if (confirmationMillis < 0) throw new IllegalArgumentException("native confirmation delay");
        this.provider = Objects.requireNonNull(provider); this.classifier = Objects.requireNonNull(classifier);
        this.config = Objects.requireNonNull(config); this.semanticEnabled = semanticEnabled;
        this.exactGeometryPreferred = exactGeometryPreferred; this.executor = Objects.requireNonNull(executor);
        this.confirmationMillis = confirmationMillis;
        this.disposer = Objects.requireNonNull(disposer);
        identities = new NativeNodeIdentityCache<>(NativeTextResult.MAXIMUM_CANDIDATES,
                new NativeNodeIdentityCache.Handles<AccessibilityNodeInfo>() {
                    @Override public boolean same(AccessibilityNodeInfo a, AccessibilityNodeInfo b) { return a.equals(b); }
                    @Override public AccessibilityNodeInfo copy(AccessibilityNodeInfo node) { return AccessibilityNodeInfo.obtain(node); }
                    @Override public void release(AccessibilityNodeInfo node) { NativeTextReader.this.disposer.release(node); }
                });
    }

    @Override public void start(CensorEngine.NativeTextInput input, CensorEngine.NativeTextCompletion completion) {
        if (closed) { completion.cancelled(); return; }
        if (reading) throw new IllegalStateException("native reader operation still active");
        reading = true;
        int addresses = identities.admittedAddresses(input.epoch, input.geometry, input.hardGeneration, input.protectionScope);
        NativeTextResult.ReadMode mode = readSchedule.next(provider.addressRefreshSupported(), addresses);
        Operation operation = new Operation(input, completion, mode);
        activeOperation = operation;
        completion.costStarted(new NativeReadCost.Start(input.identity, input.epoch, input.geometry, input.hardGeneration, operation.costInvokedAt, mode));
        // Cancellation does not free a root concurrently with Binder work, nor cancel its cleanup runnable.
        input.onCancellation(operation::cancel);
        operation.scan();
    }

    /** Request serial retirement; an active operation releases its cache only at its real terminal. */
    @Override public void close() {
        closed = true;
        if (reading) activeOperation.cancel(); else identities.close();
    }

    private static final class Incomplete extends IllegalStateException {
        Incomplete() { super("Native tree read is incomplete"); }
    }
    private static final class Probe {
        final AccessibilityNodeInfo node;
        final String text, nodeKey;
        final NativeTextResult.TextSource textSource;
        final long token;
        Probe(AccessibilityNodeInfo node, TextValue value, String nodeKey, long token) {
            this.node = node; text = value.text; textSource = value.source; this.nodeKey = nodeKey; this.token = token;
        }
        boolean matches(TextValue value) { return text.equals(value.text) && textSource == value.source; }
    }
    private static final class TextValue {
        final String text;
        final NativeTextResult.TextSource source;
        TextValue(String text, NativeTextResult.TextSource source) { this.text = text; this.source = source; }
    }
    private static final class Geometry {
        final List<Rect> parts; final boolean exact; final long observedAtMillis;
        Geometry(List<Rect> parts, boolean exact, long observedAtMillis) {
            this.parts = parts; this.exact = exact; this.observedAtMillis = observedAtMillis;
        }
    }

    private final class Operation {
        final CensorEngine.NativeTextInput input;
        final CensorEngine.NativeTextCompletion completion;
        final Set<AccessibilityNodeInfo> owned = Collections.newSetFromMap(new IdentityHashMap<>());
        final Set<AccessibilityNodeInfo> failedReleases = Collections.newSetFromMap(new IdentityHashMap<>());
        final List<Probe> probes = new ArrayList<>();
        volatile boolean cancelled;
        boolean done;
        boolean identityRead;
        Scope scope;
        AccessibilityNodeInfo treeRoot;
        final NativeTextResult.ReadMode mode;
        long identityReadId, selectedAddress;
        long started = -1, scanGeneration = -1, classificationCompletedAt, confirmationStartedAt = -1, confirmationGeneration;
        final long costStartedNanos = System.nanoTime();
        final long costInvokedAt;
        final long[] costCalls = new long[NativeReadCost.Stage.values().length], costNanos = new long[costCalls.length];
        final long[] costMaximumNanos = new long[costCalls.length], costFailures = new long[costCalls.length];
        int visited, classified;
        Operation(CensorEngine.NativeTextInput input, CensorEngine.NativeTextCompletion completion, NativeTextResult.ReadMode mode) {
            this.input = input; this.completion = completion;
            this.mode = mode;
            costInvokedAt = input.nowMillis();
        }
        void cancel() { cancelled = true; }
        void valid(Boundary boundary) {
            if (cancelled || input.cancelled() || scope == null) throw new Incomplete();
            NativeReadCost.Stage stage = boundary == Boundary.TRAVERSAL ? NativeReadCost.Stage.TRAVERSAL_CURRENT : NativeReadCost.Stage.PROVIDER_CURRENT;
            long at = costBegin(stage);
            boolean successful = false;
            try {
                successful = boundary == Boundary.TRAVERSAL ? provider.locallyCurrent(input, scope) : provider.current(input, scope, boundary);
                if (!successful || cancelled || input.cancelled()) throw new Incomplete();
            } finally { costEnd(stage, at, successful); }
        }
        long costBegin(NativeReadCost.Stage stage) { costCalls[stage.ordinal()]++; return System.nanoTime(); }
        void costEnd(NativeReadCost.Stage stage, long at, boolean successful) { costDuration(stage, System.nanoTime() - at, successful); }
        void costDuration(NativeReadCost.Stage stage, long nanos, boolean successful) {
            int index = stage.ordinal(); costNanos[index] += nanos;
            costMaximumNanos[index] = Math.max(costMaximumNanos[index], nanos); if (!successful) costFailures[index]++;
        }
        SmutTextClassifier.Match classify(String text) {
            long at = costBegin(NativeReadCost.Stage.CLASSIFY);
            boolean successful = false;
            try { SmutTextClassifier.Match result = classifier.classify(text, config, semanticEnabled); successful = true; return result; }
            finally { costEnd(NativeReadCost.Stage.CLASSIFY, at, successful); }
        }
        boolean refresh(AccessibilityNodeInfo node) {
            long at = costBegin(NativeReadCost.Stage.NODE_REFRESH);
            boolean successful = false;
            try { successful = node.refresh(); return successful; }
            finally { costEnd(NativeReadCost.Stage.NODE_REFRESH, at, successful); }
        }
        AccessibilityNodeInfo child(AccessibilityNodeInfo node, int index) {
            long at = costBegin(NativeReadCost.Stage.CHILD_FETCH);
            boolean successful = false;
            try {
                AccessibilityNodeInfo result = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                        ? node.getChild(index, AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_BREADTH_FIRST) : node.getChild(index);
                successful = result != null; return result;
            } finally { costEnd(NativeReadCost.Stage.CHILD_FETCH, at, successful); }
        }
        void reportCost(NativeReadCost.Terminal terminal, long finished) {
            List<NativeReadCost.Cost> costs = new ArrayList<>();
            for (NativeReadCost.Stage stage : NativeReadCost.Stage.values())
                costs.add(new NativeReadCost.Cost(stage, costCalls[stage.ordinal()], costNanos[stage.ordinal()],
                        costMaximumNanos[stage.ordinal()], costFailures[stage.ordinal()]));
            completion.cost(new NativeReadCost(input.identity, input.epoch, input.geometry, input.hardGeneration, scanGeneration,
                    costInvokedAt, started, finished, input.nowMillis(), System.nanoTime() - costStartedNanos, terminal, costs, mode));
        }
        void scan() {
            try {
                started = input.beginRead(mode);
                scanGeneration = input.scanGeneration();
                if (started < 0 || !config.isEnabled()) throw new Incomplete();
                long openAt = costBegin(NativeReadCost.Stage.PROVIDER_OPEN);
                try {
                    if (mode == NativeTextResult.ReadMode.FULL_DISCOVERY) {
                        Tree opened = provider.open(input); scope = opened;
                        if (opened != null) { treeRoot = opened.root; owned.add(treeRoot); }
                    } else scope = provider.openScope(input);
                } finally { costEnd(NativeReadCost.Stage.PROVIDER_OPEN, openAt, scope != null); }
                if (scope == null) throw new Incomplete();
                valid(Boundary.OPENED);
                identities.begin(digest(input.epoch + "\n" + input.geometry + "\n" + input.protectionScope.origin
                        + "\n" + input.protectionScope.tapScope + "\n" + scope.packageName + "\n" + scope.windowId
                        + "\n" + scope.width + "\n" + scope.height), input.nowMillis(), input.hardGeneration);
                identityRead = true; identityReadId = identities.readIdentity();
                if (mode == NativeTextResult.ReadMode.ADMITTED_NODE_COHORT_RECLASSIFY) {
                    List<NativeNodeIdentityCache.Address<AccessibilityNodeInfo>> addresses = identities.nextAddresses(NativeTextResult.COHORT_CANDIDATES);
                    for (NativeNodeIdentityCache.Address<AccessibilityNodeInfo> address : addresses) scanAddress(address);
                    scanFinished(); return;
                }
                if (mode == NativeTextResult.ReadMode.ADMITTED_NODE_RECLASSIFY) {
                    scanAddress(); scanFinished(); return;
                }
                ArrayDeque<AccessibilityNodeInfo> pending = new ArrayDeque<>(); pending.add(treeRoot);
                while (!pending.isEmpty()) {
                    valid(Boundary.TRAVERSAL);
                    if (visited >= MAXIMUM_NODES) throw new Incomplete();
                    AccessibilityNodeInfo node = pending.removeFirst(); visited++;
                    if (!node.isVisibleToUser()) { releaseUnlessRoot(node); continue; }
                    if (node.getWindowId() != scope.windowId) throw new Incomplete();
                    int children = node.getChildCount();
                    if (children < 0 || children > MAXIMUM_NODES - visited - pending.size()) throw new Incomplete();
                    Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
                    boolean useful = !bounds.isEmpty() && (children == 0
                            || (long) bounds.width() * bounds.height() * 100L < (long) scope.width * scope.height * 58L);
                    TextValue value = useful ? textValue(node) : new TextValue("", NativeTextResult.TextSource.UNKNOWN);
                    String text = value.text;
                    boolean kept = false;
                    if (!text.isEmpty()) {
                        classified++;
                        SmutTextClassifier.Match match = classify(text);
                        if (match.isMatched()) {
                            // Child traversal can use Android's cache. Retain a probe only after
                            // a fresh literal observation; initial geometry is not published.
                            if (!refresh(node) || !node.isVisibleToUser() || !sameWindow(node)
                                    || !text.equals(textOf(node)) || value.source != textValue(node).source
                                    || node.getChildCount() != children)
                                throw new Incomplete();
                            if (probes.size() >= NativeTextResult.MAXIMUM_CANDIDATES) throw new Incomplete();
                            String key = identityKey(node, true);
                            probes.add(new Probe(node, value, key, identities.known(node))); kept = true;
                        }
                    }
                    for (int index = 0; index < children; index++) {
                        valid(Boundary.TRAVERSAL);
                        AccessibilityNodeInfo child = child(node, index);
                        if (child == null) throw new Incomplete();
                        owned.add(child); pending.addLast(child);
                    }
                    if (!kept) releaseUnlessRoot(node);
                }
                scanFinished();
            } catch (Exception | Error failure) { fail(); }
        }
        void scanAddress() {
            NativeNodeIdentityCache.Address<AccessibilityNodeInfo> address = identities.nextAddress();
            if (address == null) throw new Incomplete(); selectedAddress = address.token;
            scanAddress(address);
        }
        void scanAddress(NativeNodeIdentityCache.Address<AccessibilityNodeInfo> address) {
            valid(Boundary.TRAVERSAL);
            try {
                AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain(address.node); owned.add(node);
                if (!node.equals(address.node) || !refresh(node) || !node.isVisibleToUser()
                        || !sameWindow(node)) throw new Incomplete();
                TextValue value = textValue(node); visited++; classified++;
                SmutTextClassifier.Match match = classify(value.text);
                NativeNodeIdentityCache.Basis basis = address.basis;
                String key = identityKey(node, false);
                if (!match.isMatched() || !key.equals(basis.nodeKey) || value.source != basis.textSource
                        || !digest(value.text).equals(basis.contentKey)
                        || !category(match).equals(basis.category) || match.getStartIndex() != basis.matchStart
                        || match.getEndIndex() != basis.matchEnd || match.getNormalizedLength() != basis.normalizedLength)
                    throw new Incomplete();
                probes.add(new Probe(node, value, key, address.token));
            } catch (Exception | Error failure) {
                retireFailedCohortAddress(address.token); throw failure;
            }
        }
        void scanFinished() {
            valid(Boundary.SCAN_END);
            classificationCompletedAt = input.nowMillis(); confirmationGeneration = input.treeGeneration();
            if (probes.isEmpty()) finish(Collections.emptyList(), 0);
            else {
                provider.beforeConfirmationScheduled(input, scope);
                executor.schedule(this::confirm, confirmationMillis, TimeUnit.MILLISECONDS);
            }
        }
        void confirm() {
            try {
                confirmationStartedAt = input.nowMillis();
                valid(Boundary.CONFIRMATION_ENTRY); List<NativeTextResult.Candidate> confirmed = new ArrayList<>(); int regionCount = 0;
                for (Probe probe : probes) {
                    valid(Boundary.TRAVERSAL);
                    SmutTextClassifier.Match match = classify(probe.text);
                    if (!match.isMatched()) throw new Incomplete();
                    boolean previousGroup = mode != NativeTextResult.ReadMode.ADMITTED_NODE_RECLASSIFY && !confirmed.isEmpty();
                    valid(previousGroup ? Boundary.GROUP_END_BEFORE_FINAL_NODE_REFRESH : Boundary.BEFORE_FINAL_NODE_REFRESH);
                    if (previousGroup) {
                        // The actual post-group scope witness just succeeded. Do not borrow the
                        // later parent's completion time, or publish an unconfirmed line/sibling.
                        observe(confirmed.get(confirmed.size() - 1), confirmed.size());
                        // Observation callbacks can stop/reconfigure the owner. This local fence
                        // grants no additional physical-window authority or atomic scope promise.
                        valid(Boundary.TRAVERSAL);
                    }
                    // A supported glyph query refreshes the complete exact node itself. Otherwise
                    // the fallback performs a real refresh, never projection from cached bounds.
                    Geometry geometry;
                    try { geometry = regions(probe, match); if (geometry == null) throw new Incomplete(); requireMatchingNode(probe); }
                    catch (Exception | Error failure) { retireFailedCohortAddress(probe.token); throw failure; }
                    if (geometry.parts.size() > NativeTextResult.MAXIMUM_REGIONS - regionCount) throw new Incomplete();
                    regionCount += geometry.parts.size();
                    List<SceneEngine.Region> regions = new ArrayList<>(geometry.parts.size());
                    for (Rect part : geometry.parts) regions.add(new SceneEngine.Region(part.left, part.top, part.right, part.bottom));
                    confirmed.add(new NativeTextResult.Candidate(category(match),
                            digest(probe.text), probe.nodeKey, match.getConfidence(),
                            geometry.exact, regions, probe.textSource, match.getStartIndex(), match.getEndIndex(),
                            match.getNormalizedLength(), geometry.observedAtMillis));
                }
                valid(Boundary.RESULT_END); confirmationGeneration = input.treeGeneration(); finish(confirmed, probes.size());
            } catch (Exception | Error failure) { fail(); }
        }
        void observe(NativeTextResult.Candidate candidate, int ordinal) {
            long groupEnd = input.nowMillis(), generation = input.treeGeneration();
            NativeTextResult group = new NativeTextResult(scope.width, scope.height, scope.windowId, started, groupEnd,
                    Collections.singletonList(candidate), visited, classified, ordinal,
                    NativeTextResult.Coverage.POSITIVE_NODES_ONLY, scanGeneration, generation, input.hardGeneration,
                    classificationCompletedAt, confirmationStartedAt, mode);
            // Optional publication refusal is not a parent failure. Actual cancellation/scope is
            // rechecked by the caller; only the genuine terminal can release roots/cache/lane.
            completion.observe(ordinal, group);
        }
        void retireFailedCohortAddress(long token) {
            if (mode == NativeTextResult.ReadMode.ADMITTED_NODE_COHORT_RECLASSIFY && !cancelled && !input.cancelled())
                identities.retireAddress(token);
        }
        boolean sameWindow(AccessibilityNodeInfo node) {
            CharSequence name = node.getPackageName();
            return node.getWindowId() == scope.windowId && name != null && scope.packageName.contentEquals(name);
        }
        void requireMatchingNode(Probe probe) {
            if (!probe.node.isVisibleToUser() || !sameWindow(probe.node) || !probe.matches(textValue(probe.node))
                    || !Objects.equals(probe.nodeKey, identityKey(probe.node, false))) throw new Incomplete();
        }
        Geometry regions(Probe probe, SmutTextClassifier.Match match) {
            long at = costBegin(NativeReadCost.Stage.GEOMETRY_EXCLUSIVE), glyphAt = costNanos[NativeReadCost.Stage.GLYPH_QUERY.ordinal()];
            long refreshAt = costNanos[NativeReadCost.Stage.NODE_REFRESH.ordinal()];
            boolean successful = false;
            try { Geometry result = resolveRegions(probe, match); successful = result != null; return result; }
            finally {
                // Nested platform-query time is measured once, not again as geometry work.
                costDuration(NativeReadCost.Stage.GEOMETRY_EXCLUSIVE,
                        System.nanoTime() - at - (costNanos[NativeReadCost.Stage.GLYPH_QUERY.ordinal()] - glyphAt)
                                - (costNanos[NativeReadCost.Stage.NODE_REFRESH.ordinal()] - refreshAt), successful);
            }
        }
        Geometry resolveRegions(Probe probe, SmutTextClassifier.Match match) {
            AccessibilityNodeInfo node = probe.node; String text = probe.text;
            long[] observedAt = {-1};
            List<Rect> exact = AccessibilityTextGeometry.resolveLines(node, text, match, scope.width, scope.height, (nanos, successful) -> {
                if (successful) observedAt[0] = input.nowMillis();
                costCalls[NativeReadCost.Stage.GLYPH_QUERY.ordinal()]++;
                costDuration(NativeReadCost.Stage.GLYPH_QUERY, nanos, successful);
            });
            if (exact != null) {
                requireMatchingNode(probe);
                if (observedAt[0] < confirmationStartedAt) throw new Incomplete();
                return new Geometry(exact, true, observedAt[0]);
            }
            if (!refresh(node)) throw new Incomplete();
            observedAt[0] = input.nowMillis();
            requireMatchingNode(probe);
            List<String> available = node.getAvailableExtraData();
            if (probe.textSource == NativeTextResult.TextSource.TEXT && available != null
                    && available.contains(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY))
                throw new Incomplete(); // A newly supported exact path is not permission to project stale eligibility.
            Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
            int normalizedLength = SmutTextClassifier.normalize(text).length();
            int lineHeight = Math.max(24, Math.round(scope.width * .035f));
            int lines = Math.max(1, (bounds.height() + lineHeight - 1) / lineHeight);
            int characters = Math.max(4, Math.round(bounds.width() / Math.max(8f, lineHeight * .48f)));
            if ((long) normalizedLength > (long) lines * characters * 2
                    || (exactGeometryPreferred && bounds.height() > Math.max(120, Math.round(scope.width * .24f)))) return null;
            List<BBox> projected = TextRegionProjector.projectLines(text, match, bounds.left, bounds.top, bounds.right, bounds.bottom,
                    scope.width, scope.height);
            if (projected == null) return null;
            List<Rect> parts = new ArrayList<>(projected.size());
            for (BBox part : projected) parts.add(new Rect((int) part.getX(), (int) part.getY(),
                    (int) part.getRight(), (int) part.getBottom()));
            return new Geometry(parts, false, observedAt[0]);
        }
        void releaseUnlessRoot(AccessibilityNodeInfo node) {
            if (node != treeRoot && owned.contains(node)) {
                try { disposer.release(node); owned.remove(node); }
                catch (RuntimeException | Error failure) { failedReleases.add(node); throw failure; }
            }
        }
        boolean dispose() {
            long at = costBegin(NativeReadCost.Stage.DISPOSE);
            boolean successful = false;
            try { successful = disposeResources(); return successful; }
            finally { costEnd(NativeReadCost.Stage.DISPOSE, at, successful); }
        }
        boolean disposeResources() {
            boolean safe = failedReleases.isEmpty() && !identities.resourceFailure();
            for (AccessibilityNodeInfo node : new ArrayList<>(owned)) {
                // An uncertain release is not blindly retried, nor erased into ordinary failure.
                if (failedReleases.contains(node)) continue;
                try { disposer.release(node); } catch (RuntimeException | Error failure) { safe = false; }
            }
            owned.clear(); failedReleases.clear(); probes.clear();
            if (identityRead) { identities.end(); identityRead = false; }
            reading = false; activeOperation = null;
            if (closed || !safe) {
                closed = true;
                try { identities.close(); } catch (RuntimeException | Error failure) { safe = false; }
            }
            return safe;
        }
        String identityKey(AccessibilityNodeInfo node, boolean admit) {
            String supplied = nodeKey(node, scope);
            long token = admit ? identities.resolve(node) : identities.known(node);
            if (token <= 0) throw new Incomplete();
            return digest(identityNamespace + "\n" + token + "\n" + (supplied == null ? "" : supplied));
        }
        void finish(List<NativeTextResult.Candidate> candidates, int confirmations) {
            if (done) return; done = true;
            long end = input.nowMillis();
            List<Long> tokens = new ArrayList<>(); for (Probe probe : probes) tokens.add(probe.token);
            boolean safe = dispose();
            if (!safe) { reportCost(NativeReadCost.Terminal.RESOURCE_FAILURE, end); completion.resourceFailure(); return; }
            NativeTextResult result;
            try {
                result = new NativeTextResult(scope.width, scope.height, scope.windowId, started, end,
                        candidates, visited, classified, confirmations,
                        mode == NativeTextResult.ReadMode.FULL_DISCOVERY && scanGeneration == confirmationGeneration ? NativeTextResult.Coverage.COMPLETE_TREE
                            : NativeTextResult.Coverage.POSITIVE_NODES_ONLY,
                        scanGeneration, confirmationGeneration, input.hardGeneration,
                        classificationCompletedAt, confirmationStartedAt, mode);
            } catch (RuntimeException invalid) { reportCost(NativeReadCost.Terminal.FAILURE, end); completion.failure(); discoveryTerminal(); return; }
            reportCost(NativeReadCost.Terminal.SUCCESS, end);
            CensorEngine.NativeTextAdmission admission = completion.admit(result);
            if (admission != null && admission.matches(input, result) && !closed)
                identities.admit(identityReadId, admission.frame, tokens);
            discoveryTerminal();
        }
        void fail() {
            if (done) return; done = true;
            if (selectedAddress > 0) identities.retireAddress(selectedAddress);
            long end = started < 0 ? -1 : input.nowMillis();
            if (!dispose()) { reportCost(NativeReadCost.Terminal.RESOURCE_FAILURE, end); completion.resourceFailure(); }
            else if (cancelled || input.cancelled()) { reportCost(NativeReadCost.Terminal.CANCELLED, end); completion.cancelled(); }
            else { reportCost(NativeReadCost.Terminal.FAILURE, end); completion.failure(); }
            discoveryTerminal();
        }
        void discoveryTerminal() {
            readSchedule.terminal(mode, mode == NativeTextResult.ReadMode.FULL_DISCOVERY
                    ? identities.admittedAddresses(input.epoch, input.geometry, input.hardGeneration, input.protectionScope) : 0);
        }
    }

    private static String textOf(AccessibilityNodeInfo node) {
        return textValue(node).text;
    }
    private static TextValue textValue(AccessibilityNodeInfo node) {
        CharSequence value = node.getText();
        NativeTextResult.TextSource source = NativeTextResult.TextSource.TEXT;
        if (value == null || value.length() == 0) { value = node.getContentDescription(); source = NativeTextResult.TextSource.CONTENT_DESCRIPTION; }
        if (value == null) return new TextValue("", NativeTextResult.TextSource.UNKNOWN);
        if (value.length() > MAXIMUM_TEXT_LENGTH) throw new Incomplete();
        return new TextValue(value.toString().trim(), source);
    }
    private static String category(SmutTextClassifier.Match match) { return "TEXT_SMUT_" + match.getCategory().toUpperCase(java.util.Locale.ROOT); }
    private static String nodeKey(AccessibilityNodeInfo node, Scope scope) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null;
        String unique = node.getUniqueId();
        if (unique == null || unique.isEmpty() || unique.length() > MAXIMUM_TEXT_LENGTH) return null;
        return digest(digest(scope.packageName) + '\n' + scope.windowId + '\n' + unique);
    }
    static String digest(String value) {
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            // Hash exact bounded UTF-16 units, including malformed units, without lossy encoding.
            for (int i = 0; i < value.length(); i++) {
                char unit = value.charAt(i); hash.update((byte) (unit >>> 8)); hash.update((byte) unit);
            }
            byte[] bytes = hash.digest();
            char[] output = new char[64];
            for (int i = 0; i < bytes.length; i++) {
                output[i * 2] = Character.forDigit((bytes[i] >>> 4) & 15, 16);
                output[i * 2 + 1] = Character.forDigit(bytes[i] & 15, 16);
            }
            return new String(output);
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
