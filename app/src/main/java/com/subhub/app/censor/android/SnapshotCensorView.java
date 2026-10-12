package com.subhub.app.censor.android;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.Choreographer;
import android.view.View;
import android.view.ViewParent;

import com.subhub.app.capture.CustomImageManager;
import com.subhub.app.censor.engine.CensorEngine;
import com.subhub.app.censor.engine.SceneEngine;
import com.subhub.app.detection.BBox;
import com.subhub.app.penance.CensorTapTracker;
import com.subhub.app.settings.CensorAppearance;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Caller-owned View recording adapter for the shared new session; never a scene/tracking owner. */
public final class SnapshotCensorView extends View implements AutoCloseable {
    public interface Owner extends SnapshotRenderSession.Owner { }
    public interface ArtworkLoader extends SnapshotRenderSession.ArtworkLoader { }
    public interface Observer {
        default void recorded(CensorEngine.PaintReceipt receipt) { }
        default void drawDemand() { }
        default void tapScopeInvalidated(long tapScope) { }
        default void resourceFailure() { }
    }
    private final SnapshotRenderSession session;
    private final Observer observer;
    private final CensorTapTracker taps;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean demandQueued = new AtomicBoolean();
    private final Rect visible = new Rect();
    private final int[] screen = new int[2];
    private boolean closed, allowed, rectangularSurface, frameQueued;
    private Canvas authorizedCanvas;
    private CensorEngine.ActiveScope lastScope;
    private final Runnable refresh = () -> { if (!closed && liveSurface()) requestDraw(); };
    private final Choreographer.FrameCallback frame = time -> {
        frameQueued = false; if (!closed && liveSurface()) requestDraw();
    };

    public SnapshotCensorView(Context context, Owner owner, CensorTapTracker taps, Observer observer) {
        this(context, owner, (policy, revision, obsolete) -> new CustomImageManager(context).loadOwnedBitmaps(
                policy.artworkDimension, policy.artworkCount, policy.maximumArtworkBytes, revision, obsolete), taps, observer);
    }
    public SnapshotCensorView(Context context, Owner owner, ArtworkLoader artwork, CensorTapTracker taps, Observer observer) {
        super(context); onMain(); this.taps = Objects.requireNonNull(taps); this.observer = Objects.requireNonNull(observer);
        setWillNotDraw(false);
        session = new SnapshotRenderSession(owner, artwork, getResources().getDisplayMetrics().density, main::post, this::presentationChanged);
    }
    public void configure(CensorAppearance appearance, SnapshotPaintPolicy policy, long artworkRevision, boolean ownsRectangularSurfaceChain) {
        onMain(); if (closed) throw new IllegalStateException("closed view");
        session.configure(appearance, policy, artworkRevision); rectangularSurface = ownsRectangularSurfaceChain;
        clearOwnMap(); requestDraw();
    }
    public void presentationAllowed(boolean value) {
        onMain(); if (closed || allowed == value) return; allowed = value;
        if (!value) suspend(); requestDraw();
    }
    public void presentationChanged() {
        if (closed || !demandQueued.compareAndSet(false, true)) return;
        if (!main.post(() -> { demandQueued.set(false); if (!closed) { fence(session.activeScope()); requestDraw(); } })) demandQueued.set(false);
    }
    private void requestDraw() { invalidate(); observer.drawDemand(); }
    /** Explicit exact-Canvas caller contract. Generic hardware/software exports cannot grant it. */
    public void recordOwnedSurface(Canvas canvas) {
        onMain(); Objects.requireNonNull(canvas);
        if (!liveSurface() || !canvas.isHardwareAccelerated() || authorizedCanvas != null) return;
        authorizedCanvas = canvas;
        try { super.draw(canvas); } finally { authorizedCanvas = null; }
    }
    @Override public void draw(Canvas canvas) {
        Canvas previous = authorizedCanvas; authorizedCanvas = null;
        try { super.draw(canvas); } finally { authorizedCanvas = previous; }
    }
    private void fence(CensorEngine.ActiveScope scope) {
        if (scope == null || !scope.same(lastScope)) {
            lastScope = scope; session.invalidate(); clearOwnMap();
        }
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        boolean actual = authorizedCanvas == canvas && canvas.isHardwareAccelerated(); authorizedCanvas = null;
        try (SnapshotRenderSession.Drawing drawing = drawCurrent(canvas, actual)) {
            if (drawing == null) { if (actual) withdrawOwnMap(); return; }
            if (actual && rectangularSurface && liveSurface()) { publish(drawing.paint, () -> true); schedule(drawing.paint); }
            else if (actual) withdrawOwnMap();
        } catch (RuntimeException failure) { if (actual) { withdrawOwnMap(); observer.resourceFailure(); } }
    }
    private SnapshotRenderSession.Drawing drawCurrent(Canvas canvas, boolean actual) {
        if (closed || !allowed || !getLocalVisibleRect(visible)) return null;
        if (actual) fence(session.activeScope());
        getLocationOnScreen(screen);
        return session.draw(canvas, screen[0], screen[1], visible, actual && liveSurface(), untransformedChain(),
                actual && rectangularSurface && liveSurface(), null);
    }
    private CensorEngine.PaintReceipt publish(SnapshotRenderSession.Paint paint, BooleanSupplier endpointCurrent) {
        CensorEngine.PaintReceipt receipt = session.acknowledge(paint);
        if (receipt != null) observer.recorded(receipt);
        if (receipt == null || !endpointCurrent.getAsBoolean() || !poseCurrent(paint.x, paint.y, paint.visible())
                || !liveSurface() || !session.isCurrent(paint) || paint.result == null || !paint.result.rectangularTapCoverageQualified) {
            withdrawOwnMap(); return receipt;
        }
        List<BBox> boxes = new ArrayList<>(); long tapScope = taps.scope(); Rect clip = paint.visible();
        for (SnapshotCensorPainter.PaintedMember member : paint.result.members) {
            if (!member.protectionScope.isNormal() || member.protectionScope.tapScope != tapScope) continue;
            for (SceneEngine.Region rect : member.tapInteriors) {
                int x = (int) Math.ceil(Math.max(rect.left, clip.left + paint.x)), y = (int) Math.ceil(Math.max(rect.top, clip.top + paint.y));
                int right = (int) Math.floor(Math.min(rect.right, clip.right + paint.x)), bottom = (int) Math.floor(Math.min(rect.bottom, clip.bottom + paint.y));
                if (right > x && bottom > y) boxes.add(new BBox(x, y, right - x, bottom - y));
            }
        }
        if (boxes.isEmpty()) { withdrawOwnMap(); return receipt; }
        taps.publishDisplayed(this, tapScope, boxes, new BBox(clip.left + paint.x, clip.top + paint.y, clip.width(), clip.height()), paint.recordedAtMillis, false);
        main.removeCallbacks(refresh); main.postDelayed(refresh, CensorTapTracker.DISPLAY_REFRESH_MILLIS); return receipt;
    }
    private void schedule(SnapshotRenderSession.Paint paint) {
        if (liveSurface() && paint.animated && !frameQueued) { frameQueued = true; Choreographer.getInstance().postFrameCallback(frame); }
    }
    private boolean poseCurrent(int x, int y, Rect clip) {
        if (!untransformedChain() || !getLocalVisibleRect(visible)) return false;
        getLocationOnScreen(screen); return screen[0] == x && screen[1] == y && visible.equals(clip);
    }
    private boolean liveSurface() { return !closed && allowed && isAttachedToWindow() && isShown() && getWindowVisibility() == VISIBLE; }
    private boolean untransformedChain() {
        if (getScrollX() != 0 || getScrollY() != 0) return false;
        View node = this;
        while (true) {
            if (!node.getMatrix().isIdentity() || node.getAlpha() != 1 || node.getClipToOutline()) return false;
            ViewParent parent = node.getParent(); if (!(parent instanceof View)) return true; node = (View) parent;
        }
    }
    private void clearOwnMap() { long before = taps.scope(); taps.clearDisplayed(this); if (taps.scope() != before) observer.tapScopeInvalidated(taps.scope()); }
    private void withdrawOwnMap() { taps.withdrawDisplayed(this); }
    private void suspend() {
        session.invalidate(); clearOwnMap(); main.removeCallbacks(refresh); authorizedCanvas = null;
        if (frameQueued) { Choreographer.getInstance().removeFrameCallback(frame); frameQueued = false; }
    }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); if (!closed) requestDraw(); }
    @Override protected void onDetachedFromWindow() { suspend(); session.finishDrawing(); super.onDetachedFromWindow(); }
    @Override protected void onWindowVisibilityChanged(int visibility) { super.onWindowVisibilityChanged(visibility); if (session != null && visibility != VISIBLE) suspend(); }
    @Override protected void onVisibilityChanged(View changedView, int visibility) { super.onVisibilityChanged(changedView, visibility); if (session != null && visibility != VISIBLE) suspend(); }
    @Override public void close() { onMain(); if (closed) return; closed = true; suspend(); session.close(); session.finishDrawing(); requestDraw(); }
    public boolean managedResourcesDrained() { return session.managedResourcesDrained(); }
    public boolean preparationTerminated() { return session.preparationTerminated(); }
    public int preparationCount() { return session.preparationCount(); }
    public int preparedOutputCount() { return session.preparedOutputCount(); }
    public long preparationFailures() { return session.preparationFailures(); }
    boolean resourcesMatchCurrent() { return session.resourcesMatchCurrent(); }
    public long drawCount() { return session.drawCount(); }
    private static void onMain() { if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("paint view main thread required"); }
}
