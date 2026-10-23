package com.subhub.app.censor.android;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Process;
import android.os.SystemClock;

import com.subhub.app.censor.engine.CensorEngine;
import com.subhub.app.censor.engine.LatestResourceCoordinator;
import com.subhub.app.censor.engine.SceneEngine;
import com.subhub.app.settings.CensorAppearance;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/**
 * The new snapshot painter/resource owner, independent of mutable Android Views. Configuration and
 * invalidation may race a draw. The painter itself has exactly one drawing thread, including close.
 * Actual draw leases survive through the caller's post; metadata can then be delivered without pixels.
 */
public final class SnapshotRenderSession implements AutoCloseable {
    public interface Owner {
        // Calls can originate on the drawing/preparation threads. Implementations and any owner
        // callbacks they invoke must be thread-safe and must not wait for the main thread.
        CensorEngine.ActiveScope activeScope();
        boolean isCurrent(CensorEngine.ActiveScope scope);
        CensorEngine<PixelFrame>.RenderView acquireRenderView();
        CensorEngine.PaintReceipt acknowledgePaint(CensorEngine.ActiveScope scope, long revision,
                long generation, List<Long> members, boolean reverseBackground);
        default SnapshotCensorPainter.Pose presentation(SceneEngine.Mask mask, long now) { return null; }
        default long presentationRevision() { return 0; }
        default boolean presentationAnimating(long now) { return false; }
    }
    public interface ArtworkLoader {
        /** Exclusive software-only inputs under policy limits; the session copies then recycles. */
        List<Bitmap> load(SnapshotPaintPolicy policy, long revision, BooleanSupplier obsolete);
    }
    private static final class Binding {
        final CensorAppearance appearance;
        final SnapshotPaintPolicy policy;
        final long generation, artworkRevision;
        Binding(CensorAppearance appearance, SnapshotPaintPolicy policy, long generation, long revision) {
            this.appearance = appearance; this.policy = policy; this.generation = generation; artworkRevision = revision;
        }
    }
    /** Immutable source/config/paint facts only; neither a pixel lease nor destination authority. */
    public static final class Paint {
        public final CensorEngine.ActiveScope scope;
        public final long revision, generation, recordedAtMillis, presentationRevision;
        public final int x, y;
        public final SnapshotCensorPainter.Result result;
        public final boolean reverse, animated;
        private final SnapshotRenderSession authority;
        private final Binding binding;
        private final Rect visible;
        private Paint(SnapshotRenderSession authority, Binding binding, CensorEngine.ActiveScope scope,
                long revision, long presentationRevision, boolean motion, SnapshotCensorPainter.Result result,
                long at, int x, int y, Rect visible) {
            this.authority = authority; this.binding = binding; this.scope = scope; this.revision = revision;
            generation = binding.generation; recordedAtMillis = at; this.result = result; this.x = x; this.y = y;
            this.presentationRevision = presentationRevision;
            this.visible = new Rect(visible); reverse = binding.appearance.isReverseMode();
            CensorAppearance.Type type = binding.appearance.getType();
            boolean coverage = reverse || (result != null && !result.members.isEmpty());
            animated = coverage && (type == CensorAppearance.Type.STATIC || type == CensorAppearance.Type.GLITCH
                    || type == CensorAppearance.Type.TAPE || type == CensorAppearance.Type.ERROR_POPUP
                    || binding.appearance.isAnimateBorder() || !reverse && motion);
        }
        public Rect visible() { return new Rect(visible); }
    }
    /** Caller closes only after its actual recording/post attempt has returned. */
    public static final class Drawing implements AutoCloseable {
        public final Paint paint;
        private final CensorEngine<PixelFrame>.RenderView view;
        private final AutoCloseable prepared;
        private final SnapshotRenderSession session;
        private final AtomicBoolean closed = new AtomicBoolean();
        private Drawing(Paint paint, CensorEngine<PixelFrame>.RenderView view, AutoCloseable prepared, SnapshotRenderSession session) {
            this.paint = paint; this.view = view; this.prepared = prepared; this.session = session;
        }
        @Override public void close() {
            if (!closed.compareAndSet(false, true)) return;
            try {
                try { if (prepared != null) prepared.close(); }
                finally { if (view != null) view.close(); }
            } catch (RuntimeException | Error failure) { session.drawCleanupFailed.set(true); throw failure; }
            catch (Exception failure) { session.drawCleanupFailed.set(true); throw new IllegalStateException("paint read cleanup", failure); }
            finally { session.activeDrawings.decrementAndGet(); }
        }
    }

    private final Owner owner;
    private final boolean recordGeometry;
    private final ArtworkLoader artwork;
    private final float density;
    private final ExecutorService preparation = Executors.newSingleThreadExecutor(action -> new Thread(() -> {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); action.run();
    }, "Censor-paint-preparation"));
    private final LatestResourceCoordinator<PaintResourceKey, PreparedCensorPaint> resources;
    private final AtomicInteger preparations = new AtomicInteger();
    private final AtomicLong draws = new AtomicLong();
    private final AtomicInteger activeDrawings = new AtomicInteger();
    private final AtomicBoolean drawCleanupFailed = new AtomicBoolean();
    private final AtomicLong resourceFences = new AtomicLong();
    private volatile Binding binding;
    private volatile boolean closed;
    private CensorEngine.ActiveScope lastScope;
    private PaintResourceKey requested;
    private long requestedFence;
    private Thread drawingThread;
    private SnapshotCensorPainter painter;

    public SnapshotRenderSession(Owner owner, ArtworkLoader artwork, float density,
            LatestResourceCoordinator.Delivery delivery, Runnable changed) {
        this(owner, artwork, density, delivery, changed, false);
    }
    SnapshotRenderSession(Owner owner, ArtworkLoader artwork, float density,
            LatestResourceCoordinator.Delivery delivery, Runnable changed, boolean recordGeometry) {
        this.owner = Objects.requireNonNull(owner); this.artwork = Objects.requireNonNull(artwork);
        this.recordGeometry = recordGeometry;
        if (!Float.isFinite(density) || density <= 0) throw new IllegalArgumentException("paint density");
        this.density = density; Objects.requireNonNull(changed);
        resources = new LatestResourceCoordinator<>(preparation, delivery, new LatestResourceCoordinator.Observer() {
            @Override public void changed() { if (!closed) changed.run(); }
        });
    }
    public void configure(CensorAppearance appearance, SnapshotPaintPolicy policy, long artworkRevision) {
        Objects.requireNonNull(appearance); Objects.requireNonNull(policy);
        synchronized (this) {
            if (closed || artworkRevision < 0) throw new IllegalStateException("paint configuration");
            long generation = binding == null ? 1 : binding.generation + 1;
            if (generation <= 0) throw new IllegalStateException("paint generation exhausted");
            binding = new Binding(appearance, policy, generation, artworkRevision); requested = null;
        }
        invalidateResources();
    }
    public long configurationGeneration() { Binding value = binding; return value == null ? 0 : value.generation; }
    long presentationRevision() { return owner.presentationRevision(); }
    public CensorEngine.ActiveScope activeScope() { return owner.activeScope(); }
    public boolean isScopeCurrent(CensorEngine.ActiveScope scope) { return !closed && owner.isCurrent(scope); }

    /** Advisory scroll demand only; neither a paint acknowledgement nor destination authority. */
    boolean presentationAnimating(CensorEngine.ActiveScope scope, long configuration, long now) {
        Binding value = binding;
        if (closed || value == null || value.generation != configuration || scope == null
                || value.appearance.isReverseMode() || !owner.isCurrent(scope)) return false;
        boolean moving = owner.presentationAnimating(now);
        return moving && !closed && binding == value && owner.isCurrent(scope)
                && !closed && binding == value;
    }

    /**
     * Fresh owner read at EVERY actual drawing call. No held/pending scene is replayed. The caller
     * supplies observed physical origin and its explicit clip contract; exports set live=false.
     * A worker supplies the main-observed expected scope to reject a replaced endpoint descriptor.
     */
    public Drawing draw(Canvas canvas, int x, int y, Rect visible, boolean live, boolean geometry,
            boolean rectangular, CensorEngine.ActiveScope expectedScope) {
        return draw(canvas, x, y, visible, live, geometry, rectangular, expectedScope, null);
    }

    Drawing draw(Canvas canvas, int x, int y, Rect visible, boolean live, boolean geometry,
            boolean rectangular, CensorEngine.ActiveScope expectedScope, PresentationFrame frame) {
        onDrawingThread(); draws.incrementAndGet(); Binding config;
        synchronized (this) {
            config = binding; if (closed || config == null || !geometry) return null;
            activeDrawings.incrementAndGet();
        }
        CensorEngine<PixelFrame>.RenderView view = null;
        LatestResourceCoordinator<PaintResourceKey, PreparedCensorPaint>.Read prepared = null;
        boolean transferred = false;
        try {
            CensorEngine.ActiveScope scope = owner.activeScope();
            long presentationRevision = owner.presentationRevision();
            if (scope == null || !owner.isCurrent(scope) || (expectedScope != null && !scope.same(expectedScope))) return null;
            if (live) fence(scope);
            if (painter == null) painter = new SnapshotCensorPainter(density);
            view = owner.acquireRenderView(); prepared = resources.acquire();
            SnapshotCensorPainter.Result result = null; long revision = -1, time = SystemClock.uptimeMillis();
            long presentationTime = frame == null ? time : frame.sampleAt(time, presentationRevision);
            if (view != null && view.snapshot.destination.epoch == scope.epoch && view.snapshot.destination.geometry == scope.geometry) {
                revision = view.snapshot.revision;
                if (live) requestPreparation(view, config);
                PreparedCensorPaint paint = prepared == null ? null : prepared.resource();
                int saved = canvas.save();
                try {
                    canvas.translate(-x, -y); canvas.clipRect(0, 0, view.snapshot.destination.width, view.snapshot.destination.height);
                    result = painter.draw(canvas, view, paint, config.appearance, config.generation, time,
                            rectangular, live && !config.appearance.isReverseMode()
                                    ? (mask, ignored) -> owner.presentation(mask, presentationTime) : null, recordGeometry);
                } finally { canvas.restoreToCount(saved); }
            } else if (view == null && config.appearance.isReverseMode()) {
                painter.drawColdReverse(canvas, new RectF(visible), config.appearance, time);
            } else return null;
            if (owner.presentationRevision() != presentationRevision || !owner.isCurrent(scope)) return null;
            Paint paint = new Paint(this, config, scope, revision, presentationRevision,
                    live && owner.presentationAnimating(time), result, time, x, y, visible);
            Drawing drawing = new Drawing(paint, view, prepared, this); view = null; prepared = null; transferred = true;
            return drawing;
        } finally {
            try {
                try { if (prepared != null) prepared.close(); }
                finally { if (view != null) view.close(); }
            } catch (RuntimeException | Error failure) { drawCleanupFailed.set(true); throw failure; }
            finally { if (!transferred) activeDrawings.decrementAndGet(); }
        }
    }
    private void onDrawingThread() {
        synchronized (this) {
            if (drawingThread == null) drawingThread = Thread.currentThread();
            if (drawingThread != Thread.currentThread()) throw new IllegalStateException("exclusive paint thread required");
        }
    }
    private void fence(CensorEngine.ActiveScope scope) {
        boolean changed;
        synchronized (this) {
            changed = !scope.same(lastScope);
            if (changed) { lastScope = scope; requested = null; }
        }
        if (changed) invalidateResources();
    }
    private void requestPreparation(CensorEngine<PixelFrame>.RenderView view, Binding config) {
        CensorAppearance.Type type = PreparedCensorPaint.effectiveType(config.appearance);
        if (type == CensorAppearance.Type.BOX || type == CensorAppearance.Type.ERROR_POPUP) return;
        PaintResourceKey key = PaintResourceKey.of(view, config.appearance, config.generation, config.artworkRevision);
        synchronized (this) {
            long fence = resourceFences.get();
            if (closed || binding != config || (key.equals(requested) && requestedFence == fence)) return;
            requested = key; requestedFence = fence;
        }
        resources.request(key, obsolete -> {
            if (obsolete.getAsBoolean() || closed || binding != config) return null;
            try (CensorEngine<PixelFrame>.RenderView current = owner.acquireRenderView()) {
                if (current == null || !key.equals(PaintResourceKey.of(current, config.appearance, config.generation, config.artworkRevision))) return null;
                preparations.incrementAndGet(); List<Bitmap> inputs = new ArrayList<>();
                try {
                    if (type == CensorAppearance.Type.CUSTOM && !config.appearance.isReverseMode()) {
                        List<Bitmap> loaded = artwork.load(config.policy, config.artworkRevision, obsolete);
                        if (loaded == null) throw new IllegalArgumentException("artwork loader null");
                        int count = loaded.size();
                        if (count < 0 || count > config.policy.artworkCount) throw new IllegalArgumentException("artwork loader count");
                        for (int index = 0; index < count; index++) inputs.add(loaded.get(index));
                        if (loaded.size() != count) throw new IllegalArgumentException("artwork loader changed count");
                        validateInputs(inputs, config.policy);
                    }
                    if (obsolete.getAsBoolean() || closed || binding != config) return null;
                    return PreparedCensorPaint.prepare(current, config.appearance, config.generation, inputs, config.policy.preparedBytes(config.appearance));
                } finally {
                    for (int index = 0; index < inputs.size(); index++) {
                        Bitmap input = inputs.get(index); if (input != null && !input.isRecycled()) input.recycle();
                    }
                }
            }
        });
    }
    private static void validateInputs(List<Bitmap> inputs, SnapshotPaintPolicy policy) {
        if (inputs.size() > policy.artworkCount) throw new IllegalArgumentException("artwork loader count");
        long bytes = 0;
        for (Bitmap image : inputs) {
            if (image == null || image.isRecycled() || image.getConfig() == Bitmap.Config.HARDWARE
                    || image.getWidth() > policy.artworkDimension || image.getHeight() > policy.artworkDimension) throw new IllegalArgumentException("artwork loader pixels");
            bytes += image.getAllocationByteCount();
            if (bytes > policy.maximumArtworkBytes) throw new IllegalArgumentException("artwork loader bytes");
        }
    }
    public boolean isCurrent(Paint paint) {
        if (paint == null || paint.authority != this || closed || binding != paint.binding || !owner.isCurrent(paint.scope)
                || owner.presentationRevision() != paint.presentationRevision) return false;
        try (CensorEngine<PixelFrame>.RenderView current = owner.acquireRenderView()) {
            return owner.presentationRevision() == paint.presentationRevision && (paint.revision == -1 ? current == null
                    : current != null && current.snapshot.revision == paint.revision
                    && current.snapshot.destination.epoch == paint.scope.epoch
                    && current.snapshot.destination.geometry == paint.scope.geometry);
        }
    }
    /** Controller must independently prove exact destination/post/pose and fence observer reentry. */
    public CensorEngine.PaintReceipt acknowledge(Paint paint) {
        if (!isCurrent(paint)) return null;
        List<Long> ids = new ArrayList<>();
        if (paint.result != null) for (SnapshotCensorPainter.PaintedMember member : paint.result.members) ids.add(member.identity);
        boolean reverse = paint.reverse && (paint.result == null || paint.result.reverseBackground);
        return owner.acknowledgePaint(paint.scope, paint.revision, paint.generation, ids, reverse);
    }
    private void invalidateResources() {
        // An invalidation can finish AFTER a concurrent request. Its terminal fence makes the next
        // draw request again rather than mistaking an invalidated descriptor for prepared work.
        try { resources.invalidate(); } finally { resourceFences.incrementAndGet(); }
    }
    public void invalidate() { synchronized (this) { requested = null; } invalidateResources(); }
    @Override public void close() {
        synchronized (this) { if (closed) return; closed = true; requested = null; }
        resources.close(); preparation.shutdown();
    }
    /** Draw thread only, after no drawing is executing. Not a claim of immediate GPU retirement. */
    public void finishDrawing() {
        onDrawingThread(); if (painter != null) { painter.close(); painter = null; }
    }
    public boolean managedResourcesDrained() { return resources.isDrained() && activeDrawings.get() == 0 && !drawCleanupFailed.get(); }
    int activeDrawingCount() { return activeDrawings.get(); }
    public boolean preparationTerminated() { return preparation.isTerminated(); }
    public int preparationCount() { return preparations.get(); }
    public int preparedOutputCount() { return resources.outputCount(); }
    public long preparationFailures() { return resources.preparationFailures(); }
    public long drawCount() { return draws.get(); }
    public boolean resourcesMatchCurrent() {
        Binding config = binding; if (closed || config == null) return false;
        try (CensorEngine<PixelFrame>.RenderView view = owner.acquireRenderView();
                LatestResourceCoordinator<PaintResourceKey, PreparedCensorPaint>.Read prepared = resources.acquire()) {
            return view != null && prepared != null && prepared.key.equals(PaintResourceKey.of(view,
                    config.appearance, config.generation, config.artworkRevision));
        }
    }
}
