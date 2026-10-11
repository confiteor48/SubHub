package com.subhub.app.censor.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.View;
import android.view.ViewParent;

import com.subhub.app.capture.CustomImageManager;
import com.subhub.app.censor.engine.CensorEngine;
import com.subhub.app.censor.engine.LatestResourceCoordinator;
import com.subhub.app.censor.engine.SceneEngine;
import com.subhub.app.detection.BBox;
import com.subhub.app.penance.CensorTapTracker;
import com.subhub.app.settings.CensorAppearance;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Snapshot-only surface. Contains no tracker, camera, association, capture or retention owner. */
public final class SnapshotCensorView extends View implements AutoCloseable {
    public interface Owner {
        CensorEngine.ActiveScope activeScope();
        boolean isCurrent(CensorEngine.ActiveScope scope);
        CensorEngine<PixelFrame>.RenderView acquireRenderView();
        CensorEngine.PaintReceipt acknowledgePaint(CensorEngine.ActiveScope scope, long revision,
                long generation, List<Long> members, boolean reverseBackground);
    }
    public interface ArtworkLoader {
        /** Exclusive software-only inputs under policy limits; caller copies then recycles them. */
        List<Bitmap> load(SnapshotPaintPolicy policy, long revision, BooleanSupplier obsolete);
    }
    public interface Observer {
        default void recorded(CensorEngine.PaintReceipt receipt) { }
        /** The explicit surface controller must schedule a new owned surface recording. */
        default void drawDemand() { }
        /** Controller must refresh captured source provenance after its tap scope advances. */
        default void tapScopeInvalidated(long tapScope) { }
        default void resourceFailure() { }
    }
    private static final class Binding {
        final CensorAppearance appearance;
        final SnapshotPaintPolicy policy;
        final long generation, artworkRevision;
        Binding(CensorAppearance appearance, SnapshotPaintPolicy policy, long generation, long revision) {
            this.appearance = appearance; this.policy = policy; this.generation = generation; artworkRevision = revision;
        }
    }
    private final Owner owner;
    private final ArtworkLoader artwork;
    private final Observer observer;
    private final CensorTapTracker taps;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService preparation = Executors.newSingleThreadExecutor(action -> new Thread(() -> {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); action.run();
    }, "Censor-paint-preparation"));
    private final LatestResourceCoordinator<PaintResourceKey, PreparedCensorPaint> resources;
    private final AtomicBoolean demandQueued = new AtomicBoolean();
    private final AtomicInteger preparations = new AtomicInteger();
    private final Rect visible = new Rect();
    private final int[] screen = new int[2];
    private volatile Binding binding;
    private volatile boolean closed;
    private boolean allowed, rectangularSurface, frameQueued;
    private Canvas authorizedCanvas;
    private CensorEngine.ActiveScope lastScope;
    private PaintResourceKey requested;
    private SnapshotCensorPainter painter;
    private long drawCount;
    private final Runnable refresh = () -> { if (!closed && liveSurface()) requestDraw(); };
    private final Choreographer.FrameCallback frame = time -> {
        frameQueued = false;
        if (!closed && liveSurface()) requestDraw(); // Artwork only; geometry always comes from the owner.
    };

    public SnapshotCensorView(Context context, Owner owner, CensorTapTracker taps, Observer observer) {
        this(context, owner, (policy, revision, obsolete) -> new CustomImageManager(context).loadOwnedBitmaps(
                policy.artworkDimension, policy.artworkCount, policy.maximumArtworkBytes, revision, obsolete), taps, observer);
    }
    public SnapshotCensorView(Context context, Owner owner, ArtworkLoader artwork, CensorTapTracker taps, Observer observer) {
        super(context); onMain(); this.owner = Objects.requireNonNull(owner); this.artwork = Objects.requireNonNull(artwork);
        this.taps = Objects.requireNonNull(taps); this.observer = Objects.requireNonNull(observer); setWillNotDraw(false);
        painter = new SnapshotCensorPainter(getResources().getDisplayMetrics().density);
        resources = new LatestResourceCoordinator<>(preparation, main::post, new LatestResourceCoordinator.Observer() {
            @Override public void changed() { if (!closed) presentationChanged(); }
        });
    }
    /** Controller-owned appearance/resource policy, never called as a side effect of drawing. */
    public void configure(CensorAppearance appearance, SnapshotPaintPolicy policy, long artworkRevision,
            boolean ownsRectangularSurfaceChain) {
        onMain(); Objects.requireNonNull(appearance); Objects.requireNonNull(policy);
        if (closed || artworkRevision < 0) throw new IllegalStateException("paint configuration");
        long generation = binding == null ? 1 : binding.generation + 1;
        if (generation <= 0) throw new IllegalStateException("paint generation exhausted");
        binding = new Binding(appearance, policy, generation, artworkRevision); rectangularSurface = ownsRectangularSurfaceChain;
        resources.invalidate(); requested = null; clearOwnMap(); requestDraw();
    }
    /** Enable only for a currently eligible protection target; default is not authorized. */
    public void presentationAllowed(boolean value) {
        onMain(); if (closed || allowed == value) return;
        allowed = value;
        if (!value) suspend();
        requestDraw(); // Disabling must withdraw the recorded pixels, not only their hit map.
    }
    /** Safe for runtime callbacks from any thread; one pending UI demand, no held snapshot. */
    public void presentationChanged() {
        if (closed || !demandQueued.compareAndSet(false, true)) return;
        if (!main.post(() -> {
            demandQueued.set(false); if (closed) return;
            fence(owner.activeScope()); requestDraw();
        })) demandQueued.set(false);
    }
    private void requestDraw() { invalidate(); observer.drawDemand(); }

    /**
     * Explicit controller contract: canvas is this attached surface's actual recording destination,
     * already translated to its unscrolled local origin. The controller owns the rectangular clip
     * chain. This is NOT an Android compositor acknowledgement or a Canvas-inspection proof.
     * Generic View.draw, including hardware exports during a traversal, cannot grant this authority.
     */
    public void recordOwnedSurface(Canvas canvas) {
        onMain(); Objects.requireNonNull(canvas);
        if (!liveSurface() || !canvas.isHardwareAccelerated() || authorizedCanvas != null) return;
        authorizedCanvas = canvas;
        try { super.draw(canvas); } finally { authorizedCanvas = null; }
    }

    @Override public void draw(Canvas canvas) {
        // Even reentrant exports from an observer cannot borrow the controller's interval.
        Canvas previous = authorizedCanvas; authorizedCanvas = null;
        try { super.draw(canvas); } finally { authorizedCanvas = previous; }
    }
    private void fence(CensorEngine.ActiveScope scope) {
        if (scope == null || !scope.same(lastScope)) {
            lastScope = scope; requested = null; resources.invalidate(); clearOwnMap();
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas); drawCount++;
        boolean actualTraversal = authorizedCanvas == canvas && canvas.isHardwareAccelerated(); authorizedCanvas = null;
        Binding config = binding;
        if (closed || !allowed || config == null || painter == null) return;
        CensorEngine.ActiveScope scope = owner.activeScope();
        if (actualTraversal) fence(scope);
        if (scope == null || !owner.isCurrent(scope) || !getLocalVisibleRect(visible)) {
            if (actualTraversal) withdrawOwnMap(); return;
        }
        getLocationOnScreen(screen);
        boolean geometry = untransformedChain();
        boolean live = actualTraversal && liveSurface();
        boolean qualified = live && rectangularSurface && geometry;
        long time = SystemClock.uptimeMillis();
        SnapshotCensorPainter.Result result = null; long revision = -1;
        try (CensorEngine<PixelFrame>.RenderView view = owner.acquireRenderView();
                LatestResourceCoordinator<PaintResourceKey, PreparedCensorPaint>.Read prepared = resources.acquire()) {
            if (view != null && view.snapshot.destination.epoch == scope.epoch
                    && view.snapshot.destination.geometry == scope.geometry && geometry) {
                revision = view.snapshot.revision;
                if (live) requestPreparation(view, config);
                PreparedCensorPaint paint = prepared == null ? null : prepared.resource();
                int saved = canvas.save();
                try {
                    // Snapshot coordinates are physical display pixels; observe origin, never infer scale.
                    canvas.translate(-screen[0], -screen[1]);
                    canvas.clipRect(0, 0, view.snapshot.destination.width, view.snapshot.destination.height);
                    result = qualified ? painter.drawRectangularClip(canvas, view, paint, config.appearance, config.generation, time)
                            : painter.draw(canvas, view, paint, config.appearance, config.generation, time);
                } finally { canvas.restoreToCount(saved); }
            } else if (view == null && config.appearance.isReverseMode()) {
                painter.drawColdReverse(canvas, new RectF(visible), config.appearance, time);
            }
            if (qualified && owner.isCurrent(scope) && binding == config) publish(scope, revision, config, result, time);
            else if (live) withdrawOwnMap(); // Software/export draws never publish OR clear the live map.
        } catch (RuntimeException failure) {
            if (live) { withdrawOwnMap(); observer.resourceFailure(); }
        }
        if (live) schedule(config, result);
    }

    private void requestPreparation(CensorEngine<PixelFrame>.RenderView view, Binding config) {
        CensorAppearance.Type type = PreparedCensorPaint.effectiveType(config.appearance);
        if (type == CensorAppearance.Type.BOX || type == CensorAppearance.Type.ERROR_POPUP) return;
        PaintResourceKey key = PaintResourceKey.of(view, config.appearance, config.generation, config.artworkRevision);
        if (key.equals(requested)) return;
        requested = key;
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
                } finally { for (int index = 0; index < inputs.size(); index++) {
                    Bitmap input = inputs.get(index); if (input != null && !input.isRecycled()) input.recycle();
                } }
            }
        });
    }
    private static void validateInputs(List<Bitmap> inputs, SnapshotPaintPolicy policy) {
        if (inputs == null || inputs.size() > policy.artworkCount) throw new IllegalArgumentException("artwork loader count");
        long bytes = 0;
        for (Bitmap image : inputs) {
            if (image == null || image.isRecycled() || image.getConfig() == Bitmap.Config.HARDWARE
                    || image.getWidth() > policy.artworkDimension || image.getHeight() > policy.artworkDimension) throw new IllegalArgumentException("artwork loader pixels");
            bytes += image.getAllocationByteCount();
            if (bytes > policy.maximumArtworkBytes) throw new IllegalArgumentException("artwork loader bytes");
        }
    }
    private void publish(CensorEngine.ActiveScope scope, long revision, Binding config,
            SnapshotCensorPainter.Result result, long time) {
        List<Long> ids = new ArrayList<>();
        if (result != null) for (SnapshotCensorPainter.PaintedMember member : result.members) ids.add(member.identity);
        boolean reverse = config.appearance.isReverseMode() && (result == null || result.reverseBackground);
        CensorEngine.PaintReceipt receipt = owner.acknowledgePaint(scope, revision, config.generation, ids, reverse);
        if (receipt != null) observer.recorded(receipt);
        if (receipt == null || closed || binding != config || !owner.isCurrent(scope) || !liveSurface()
                || result == null || !result.rectangularTapCoverageQualified) { withdrawOwnMap(); return; }
        List<BBox> boxes = new ArrayList<>(); long tapScope = taps.scope();
        for (SnapshotCensorPainter.PaintedMember member : result.members) {
            if (!member.protectionScope.isNormal() || member.protectionScope.tapScope != tapScope) continue;
            for (SceneEngine.Region rect : member.tapInteriors) {
                int x = (int) Math.ceil(Math.max(rect.left, visible.left + screen[0]));
                int y = (int) Math.ceil(Math.max(rect.top, visible.top + screen[1]));
                int right = (int) Math.floor(Math.min(rect.right, visible.right + screen[0]));
                int bottom = (int) Math.floor(Math.min(rect.bottom, visible.bottom + screen[1]));
                if (right > x && bottom > y) boxes.add(new BBox(x, y, right - x, bottom - y));
            }
        }
        if (boxes.isEmpty()) { withdrawOwnMap(); return; }
        BBox viewport = new BBox(visible.left + screen[0], visible.top + screen[1], visible.width(), visible.height());
        taps.publishDisplayed(this, tapScope, boxes, viewport, time, false);
        main.removeCallbacks(refresh); main.postDelayed(refresh, CensorTapTracker.DISPLAY_REFRESH_MILLIS);
    }
    private void schedule(Binding config, SnapshotCensorPainter.Result result) {
        if (!liveSurface()) return;
        boolean painted = config.appearance.isReverseMode() || (result != null && !result.members.isEmpty());
        CensorAppearance.Type type = config.appearance.getType();
        boolean animate = painted && (type == CensorAppearance.Type.STATIC || type == CensorAppearance.Type.GLITCH
                || type == CensorAppearance.Type.TAPE || type == CensorAppearance.Type.ERROR_POPUP || config.appearance.isAnimateBorder());
        if (animate && !frameQueued) { frameQueued = true; Choreographer.getInstance().postFrameCallback(frame); }
    }
    private boolean liveSurface() { return !closed && allowed && isAttachedToWindow() && isShown() && getWindowVisibility() == VISIBLE; }
    private boolean untransformedChain() {
        if (getScrollX() != 0 || getScrollY() != 0) return false;
        View node = this;
        while (true) {
            if (!node.getMatrix().isIdentity() || node.getAlpha() != 1 || node.getClipToOutline()) return false;
            ViewParent parent = node.getParent();
            if (!(parent instanceof View)) return true;
            node = (View) parent;
        }
    }
    private void clearOwnMap() {
        long before = taps.scope(); taps.clearDisplayed(this);
        if (taps.scope() != before) observer.tapScopeInvalidated(taps.scope());
    }
    private void withdrawOwnMap() { taps.withdrawDisplayed(this); }
    private void suspend() {
        requested = null; resources.invalidate(); clearOwnMap(); main.removeCallbacks(refresh); authorizedCanvas = null;
        if (frameQueued) { Choreographer.getInstance().removeFrameCallback(frame); frameQueued = false; }
    }
    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (closed) return;
        if (painter == null) painter = new SnapshotCensorPainter(getResources().getDisplayMetrics().density);
        requestDraw();
    }
    @Override protected void onDetachedFromWindow() {
        suspend();
        if (painter != null) { painter.close(); painter = null; }
        super.onDetachedFromWindow();
    }
    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (resources != null && visibility != VISIBLE) suspend();
    }
    @Override protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (resources != null && visibility != VISIBLE) suspend();
    }
    @Override public void close() {
        onMain(); if (closed) return; closed = true; suspend(); resources.close(); preparation.shutdown();
        if (painter != null) { painter.close(); painter = null; }
        requestDraw(); // The owning controller must clear any still-attached surface recording.
    }
    public boolean managedResourcesDrained() { return resources.isDrained(); }
    public boolean preparationTerminated() { return preparation.isTerminated(); }
    public int preparationCount() { return preparations.get(); }
    public int preparedOutputCount() { return resources.outputCount(); }
    public long preparationFailures() { return resources.preparationFailures(); }
    /** Read-only preparation readiness, never scene authority or a displayed-pixel acknowledgement. */
    boolean resourcesMatchCurrent() {
        Binding config = binding; if (closed || config == null) return false;
        try (CensorEngine<PixelFrame>.RenderView view = owner.acquireRenderView();
                LatestResourceCoordinator<PaintResourceKey, PreparedCensorPaint>.Read prepared = resources.acquire()) {
            return view != null && prepared != null && prepared.key.equals(PaintResourceKey.of(view,
                    config.appearance, config.generation, config.artworkRevision));
        }
    }
    public long drawCount() { return drawCount; }
    private static void onMain() { if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("paint surface main thread required"); }
}
