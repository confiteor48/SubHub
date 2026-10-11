package com.subhub.app.detection.text;

import android.graphics.Rect;
import android.os.Build;
import android.view.accessibility.AccessibilityNodeInfo;

import com.subhub.app.censor.engine.CensorEngine;
import com.subhub.app.censor.engine.NativeTextResult;
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
public final class NativeTextReader implements CensorEngine.NativeTextWorker {
    public static final int MAXIMUM_NODES = 900;
    public static final int MAXIMUM_TEXT_LENGTH = 2000;

    public interface Provider {
        /** Transfers root ownership only on successful return; dispose unreturned handles on failure. */
        Tree open(CensorEngine.NativeTextInput input) throws Exception;
        /** Compare actual foreground/window/content/config scope, never mutate engine state. */
        boolean current(CensorEngine.NativeTextInput input, Tree tree);
    }

    public static final class Tree {
        public final AccessibilityNodeInfo root;
        public final int width, height, windowId;
        public final String packageName;
        public final long contentGeneration;
        public Tree(AccessibilityNodeInfo root, int width, int height, long contentGeneration) {
            this.root = Objects.requireNonNull(root);
            if (width <= 0 || height <= 0 || root.getWindowId() < 0) throw new IllegalArgumentException("native tree scope");
            CharSequence name = root.getPackageName();
            if (name == null || name.length() == 0 || name.length() > 256) throw new IllegalArgumentException("native package");
            this.width = width; this.height = height; windowId = root.getWindowId();
            packageName = name.toString(); this.contentGeneration = contentGeneration;
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
    }

    @Override public void start(CensorEngine.NativeTextInput input, CensorEngine.NativeTextCompletion completion) {
        Operation operation = new Operation(input, completion);
        // Cancellation does not free a root concurrently with Binder work, nor cancel its cleanup runnable.
        input.onCancellation(operation::cancel);
        operation.scan();
    }

    private static final class Incomplete extends IllegalStateException {
        Incomplete() { super("Native tree read is incomplete"); }
    }
    private static final class Probe {
        final AccessibilityNodeInfo node;
        final String text, nodeKey;
        Probe(AccessibilityNodeInfo node, String text, String nodeKey) { this.node = node; this.text = text; this.nodeKey = nodeKey; }
    }
    private static final class Geometry {
        final List<Rect> parts; final boolean exact;
        Geometry(List<Rect> parts, boolean exact) { this.parts = parts; this.exact = exact; }
    }

    private final class Operation {
        final CensorEngine.NativeTextInput input;
        final CensorEngine.NativeTextCompletion completion;
        final Set<AccessibilityNodeInfo> owned = Collections.newSetFromMap(new IdentityHashMap<>());
        final Set<AccessibilityNodeInfo> failedReleases = Collections.newSetFromMap(new IdentityHashMap<>());
        final List<Probe> probes = new ArrayList<>();
        volatile boolean cancelled;
        boolean done;
        Tree tree;
        long started;
        int visited, classified;
        Operation(CensorEngine.NativeTextInput input, CensorEngine.NativeTextCompletion completion) {
            this.input = input; this.completion = completion;
        }
        void cancel() { cancelled = true; }
        void valid() {
            if (cancelled || input.cancelled() || tree == null || !provider.current(input, tree)) throw new Incomplete();
        }
        void scan() {
            try {
                started = input.beginRead();
                if (started < 0 || !config.isEnabled()) throw new Incomplete();
                tree = provider.open(input);
                if (tree == null) throw new Incomplete();
                owned.add(tree.root); valid();
                ArrayDeque<AccessibilityNodeInfo> pending = new ArrayDeque<>(); pending.add(tree.root);
                while (!pending.isEmpty()) {
                    valid();
                    if (visited >= MAXIMUM_NODES) throw new Incomplete();
                    AccessibilityNodeInfo node = pending.removeFirst(); visited++;
                    if (!node.isVisibleToUser()) { releaseUnlessRoot(node); continue; }
                    if (node.getWindowId() != tree.windowId) throw new Incomplete();
                    int children = node.getChildCount();
                    if (children < 0 || children > MAXIMUM_NODES - visited - pending.size()) throw new Incomplete();
                    Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
                    boolean useful = !bounds.isEmpty() && (children == 0
                            || (long) bounds.width() * bounds.height() * 100L < (long) tree.width * tree.height * 58L);
                    String text = useful ? textOf(node) : "";
                    boolean kept = false;
                    if (!text.isEmpty()) {
                        classified++;
                        SmutTextClassifier.Match match = classifier.classify(text, config, semanticEnabled);
                        if (match.isMatched()) {
                            if (regions(node, text, match, bounds) == null) throw new Incomplete();
                            if (probes.size() >= NativeTextResult.MAXIMUM_CANDIDATES) throw new Incomplete();
                            probes.add(new Probe(node, text, nodeKey(node, tree))); kept = true;
                        }
                    }
                    for (int index = 0; index < children; index++) {
                        valid();
                        AccessibilityNodeInfo child = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                                ? node.getChild(index, AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_BREADTH_FIRST)
                                : node.getChild(index);
                        if (child == null) throw new Incomplete();
                        owned.add(child); pending.addLast(child);
                    }
                    if (!kept) releaseUnlessRoot(node);
                }
                valid();
                if (probes.isEmpty()) finish(Collections.emptyList(), 0);
                else executor.schedule(this::confirm, confirmationMillis, TimeUnit.MILLISECONDS);
            } catch (Exception failure) { fail(); }
        }
        void confirm() {
            try {
                valid(); List<NativeTextResult.Candidate> confirmed = new ArrayList<>(); int regionCount = 0;
                for (Probe probe : probes) {
                    valid();
                    if (!probe.node.refresh() || !probe.node.isVisibleToUser()
                            || probe.node.getWindowId() != tree.windowId || !probe.text.equals(textOf(probe.node))
                            || !Objects.equals(probe.nodeKey, nodeKey(probe.node, tree))) throw new Incomplete();
                    SmutTextClassifier.Match match = classifier.classify(probe.text, config, semanticEnabled);
                    if (!match.isMatched()) throw new Incomplete();
                    Rect bounds = new Rect(); probe.node.getBoundsInScreen(bounds);
                    Geometry geometry = regions(probe.node, probe.text, match, bounds);
                    if (geometry == null || !probe.text.equals(textOf(probe.node))
                            || !Objects.equals(probe.nodeKey, nodeKey(probe.node, tree))) throw new Incomplete();
                    if (geometry.parts.size() > NativeTextResult.MAXIMUM_REGIONS - regionCount) throw new Incomplete();
                    regionCount += geometry.parts.size();
                    List<SceneEngine.Region> regions = new ArrayList<>(geometry.parts.size());
                    for (Rect part : geometry.parts) regions.add(new SceneEngine.Region(part.left, part.top, part.right, part.bottom));
                    confirmed.add(new NativeTextResult.Candidate("TEXT_SMUT_" + match.getCategory().toUpperCase(java.util.Locale.ROOT),
                            digest(probe.text), probe.nodeKey, match.getConfidence(),
                            geometry.exact, regions));
                }
                valid(); finish(confirmed, probes.size());
            } catch (Exception failure) { fail(); }
        }
        Geometry regions(AccessibilityNodeInfo node, String text, SmutTextClassifier.Match match, Rect bounds) {
            List<Rect> exact = AccessibilityTextGeometry.resolveLines(node, text, match, tree.width, tree.height);
            if (!text.equals(textOf(node))) throw new Incomplete();
            if (exact != null) return new Geometry(exact, true);
            int normalizedLength = SmutTextClassifier.normalize(text).length();
            int lineHeight = Math.max(24, Math.round(tree.width * .035f));
            int lines = Math.max(1, (bounds.height() + lineHeight - 1) / lineHeight);
            int characters = Math.max(4, Math.round(bounds.width() / Math.max(8f, lineHeight * .48f)));
            if ((long) normalizedLength > (long) lines * characters * 2
                    || (exactGeometryPreferred && bounds.height() > Math.max(120, Math.round(tree.width * .24f)))) return null;
            List<BBox> projected = TextRegionProjector.projectLines(text, match, bounds.left, bounds.top, bounds.right, bounds.bottom,
                    tree.width, tree.height);
            if (projected == null) return null;
            List<Rect> parts = new ArrayList<>(projected.size());
            for (BBox part : projected) parts.add(new Rect((int) part.getX(), (int) part.getY(),
                    (int) part.getRight(), (int) part.getBottom()));
            return new Geometry(parts, false);
        }
        void releaseUnlessRoot(AccessibilityNodeInfo node) {
            if (node != tree.root && owned.contains(node)) {
                try { disposer.release(node); owned.remove(node); }
                catch (RuntimeException failure) { failedReleases.add(node); throw failure; }
            }
        }
        boolean dispose() {
            boolean safe = failedReleases.isEmpty();
            for (AccessibilityNodeInfo node : new ArrayList<>(owned)) {
                // An uncertain release is not blindly retried, nor erased into ordinary failure.
                if (failedReleases.contains(node)) continue;
                try { disposer.release(node); } catch (RuntimeException failure) { safe = false; }
            }
            owned.clear(); failedReleases.clear(); probes.clear(); return safe;
        }
        void finish(List<NativeTextResult.Candidate> candidates, int confirmations) {
            if (done) return; done = true;
            long end = input.nowMillis();
            boolean safe = dispose();
            if (!safe) { completion.resourceFailure(); return; }
            try {
                completion.success(new NativeTextResult(tree.width, tree.height, tree.windowId, started, end,
                        candidates, visited, classified, confirmations));
            } catch (RuntimeException invalid) { completion.failure(); }
        }
        void fail() {
            if (done) return; done = true;
            if (!dispose()) completion.resourceFailure();
            else if (cancelled || input.cancelled()) completion.cancelled(); else completion.failure();
        }
    }

    private static String textOf(AccessibilityNodeInfo node) {
        CharSequence value = node.getText();
        if (value == null || value.length() == 0) value = node.getContentDescription();
        if (value == null) return "";
        if (value.length() > MAXIMUM_TEXT_LENGTH) throw new Incomplete();
        return value.toString().trim();
    }
    private static String nodeKey(AccessibilityNodeInfo node, Tree tree) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null;
        String unique = node.getUniqueId();
        if (unique == null || unique.isEmpty() || unique.length() > MAXIMUM_TEXT_LENGTH) return null;
        return digest(digest(tree.packageName) + '\n' + tree.windowId + '\n' + unique);
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
