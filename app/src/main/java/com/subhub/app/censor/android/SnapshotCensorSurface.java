package com.subhub.app.censor.android;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewParent;
import android.widget.FrameLayout;

import androidx.annotation.RequiresApi;

import com.subhub.app.capture.CustomImageManager;
import com.subhub.app.censor.engine.CensorEngine;
import com.subhub.app.censor.engine.LatestResourceCoordinator;
import com.subhub.app.censor.engine.SceneEngine;
import com.subhub.app.detection.BBox;
import com.subhub.app.penance.CensorTapTracker;
import com.subhub.app.settings.CensorAppearance;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One actual Surface endpoint, one worker-owned painter. Main observes View state and admits
 * metadata only; no worker reads mutable View state or waits for main to paint/post. Surface
 * destruction must quiesce real use, so rare lifecycle waits are NOT promised nonblocking.
 * Successful post means submission, not compositor arrival or immediate GPU retirement.
 */
public final class SnapshotCensorSurface extends FrameLayout implements AutoCloseable {
    public interface Observer {
        /** Frozen at construction. Default product path collects no submission geometry. */
        default boolean collectSubmissions() { return false; }
        /** Render worker, AFTER holder use and Drawing leases close; never block or call main synchronously. */
        default void submitted(SurfaceSubmission submission) { }
        default void recorded(CensorEngine.PaintReceipt receipt) { }
        default void drawDemand() { }
        default void tapScopeInvalidated(long tapScope) { }
        default void resourceFailure() { }
        default void posted(Post receipt) { }
        default void endpointFailure() { }
    }
    public static final class Post {
        public final long endpointGeneration, postSerial, recordedAtMillis, postReturnedAtMillis, uiAdmittedAtMillis;
        public final long queuedNanos, lockNanos, recordingNanos, postNanos, renderThreadId;
        public final boolean mainThread;
        public final CensorEngine.PaintReceipt acceptedPaint;
        public final long compositorAtMillis = -1;
        private Post(Completion value, CensorEngine.PaintReceipt accepted) {
            endpointGeneration = value.pose.generation; postSerial = value.serial;
            recordedAtMillis = value.recorded; postReturnedAtMillis = value.returned;
            uiAdmittedAtMillis = SystemClock.uptimeMillis(); queuedNanos = value.queued;
            lockNanos = value.lock; recordingNanos = value.recording; postNanos = value.post;
            renderThreadId = value.thread; mainThread = value.mainThread; acceptedPaint = accepted;
        }
    }
    /** Adverse-control seam. Both operations run only on the render worker, never main. */
    interface Endpoint {
        Canvas lock(SurfaceHolder holder);
        void post(SurfaceHolder holder, Canvas canvas);
    }
    private static final Endpoint HOLDER = new Endpoint() {
        @Override public Canvas lock(SurfaceHolder holder) { return holder.lockHardwareCanvas(); }
        @Override public void post(SurfaceHolder holder, Canvas canvas) { holder.unlockCanvasAndPost(canvas); }
    };
    /** No scene read, bitmap, prepared output, or mutable View reference. */
    private static final class Pose {
        final long generation, configuration, enqueued;
        final int x, y, width, height;
        final Rect visible;
        final boolean paint;
        final CensorEngine.ActiveScope scope;
        final PresentationFrame frame;
        Pose(long generation, long configuration, int x, int y, int width, int height,
                Rect visible, boolean paint, CensorEngine.ActiveScope scope, PresentationFrame frame) {
            this.generation = generation; this.configuration = configuration; this.x = x; this.y = y;
            this.width = width; this.height = height; this.visible = new Rect(visible); this.paint = paint;
            this.scope = scope; enqueued = SystemClock.elapsedRealtimeNanos();
            this.frame = frame;
        }
    }
    /** Metadata only: actual reads have already closed after the real post returned. */
    private static final class Completion {
        final Pose pose;
        final SnapshotRenderSession.Paint paint;
        final long serial, recorded, returned, queued, lock, recording, post, thread;
        final boolean mainThread, failure;
        Completion(Pose pose, SnapshotRenderSession.Paint paint, long serial, long recorded,
                long returned, long queued, long lock, long recording, long post, boolean failure) {
            this.pose = pose; this.paint = paint; this.serial = serial; this.recorded = recorded;
            this.returned = returned; this.queued = queued; this.lock = lock; this.recording = recording;
            this.post = post; this.failure = failure; thread = Thread.currentThread().getId();
            mainThread = Looper.myLooper() == Looper.getMainLooper();
        }
    }
    private static final class RenderTask { final AtomicBoolean started = new AtomicBoolean(); }
    private static final class TapMap {
        final long scope;
        final List<BBox> boxes;
        final BBox viewport;
        TapMap(long scope, List<BBox> boxes, BBox viewport) { this.scope = scope; this.boxes = boxes; this.viewport = viewport; }
    }
    private final class Notice {
        final AtomicBoolean consumed = new AtomicBoolean();
        final Runnable action = () -> deliverCompletion(this);
    }
    private final class SurfaceUse implements AutoCloseable {
        private boolean released;
        @Override public void close() {
            synchronized (gate) {
                if (released) return; released = true; surfaceUsers--; gate.notifyAll();
            }
        }
    }

    private final SurfaceView surface;
    private final SurfaceHolder holder;
    private final SnapshotRenderSession session;
    private final CensorTapTracker taps;
    private final Observer observer;
    private final Endpoint endpoint;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService rendering;
    private final LatestResourceCoordinator.Delivery completionDelivery;
    // Only counters/immutable metadata and pure tap-map mutation under this gate. NO holder, View,
    // owner, session, observer, disposal or dispatch call while holding it.
    private final Object gate = new Object();
    private final AtomicBoolean changedQueued = new AtomicBoolean();
    private final AtomicBoolean drawingFinished = new AtomicBoolean();
    private final int[] origin = new int[2];
    private final Rect visible = new Rect();
    private boolean created, frameQueued, allowed, rectangular, completionExecuting, renderingReserved;
    private Notice completionReserved;
    private boolean surfaceAdmission;
    private volatile boolean closed, faulted;
    private int surfaceWidth, surfaceHeight, surfaceUsers;
    private long generation, frameGeneration, postSerial, replacedPoses, replacedCompletions, droppedSuccessfulCompletions;
    private volatile long postCount, lockCount, postAttempts, postReturns;
    private volatile long destructionsStarted, destructionsReturned;
    private final boolean collectSubmissions;
    private volatile long submissionObserverFailures;
    private volatile long submissionObservationNanos, maximumSubmissionObservationNanos;
    private Pose pending;
    private Completion completed;
    private CensorEngine.ActiveScope lastScope;
    private CensorEngine.ActiveScope admittedScope; // Immutable pose identity, guarded by gate.
    private final Runnable refresh = () -> { if (!closed) demand(); };
    private final Choreographer.FrameCallback frame = time -> {
        frameQueued = false; if (!closed && !faulted) enqueueObservedPose(null);
    };
    private final SurfaceHolder.Callback lifecycle = new SurfaceHolder.Callback() {
        @Override public void surfaceCreated(SurfaceHolder value) {
            onMain(); created = true; surfaceWidth = surfaceHeight = 0;
            synchronized (gate) { generation++; surfaceAdmission = !closed; faulted = false; pending = null; }
        }
        @Override public void surfaceChanged(SurfaceHolder value, int format, int width, int height) {
            onMain(); surfaceWidth = width; surfaceHeight = height;
            invalidateEndpoint(false); demand();
        }
        @Override public void surfaceDestroyed(SurfaceHolder value) {
            onMain(); destructionsStarted++; created = false; surfaceWidth = surfaceHeight = 0; cancelDemand();
            invalidateEndpoint(true);
            // SurfaceHolder.Callback requires NO real Surface use after return. wait releases gate;
            // the worker finishes holder use without any main/observer round trip. No fake timeout.
            boolean interrupted = false;
            synchronized (gate) {
                while (surfaceUsers != 0) try { gate.wait(); }
                catch (InterruptedException failure) { interrupted = true; }
            }
            if (interrupted) Thread.currentThread().interrupt();
            session.invalidate();
            destructionsReturned++;
        }
    };

    public SnapshotCensorSurface(Context context, SnapshotRenderSession.Owner owner, CensorTapTracker taps, Observer observer) {
        this(context, owner, (policy, revision, obsolete) -> new CustomImageManager(context).loadOwnedBitmaps(
                policy.artworkDimension, policy.artworkCount, policy.maximumArtworkBytes, revision, obsolete), taps, observer, HOLDER);
    }
    public SnapshotCensorSurface(Context context, SnapshotRenderSession.Owner owner, SnapshotRenderSession.ArtworkLoader artwork,
            CensorTapTracker taps, Observer observer) {
        this(context, owner, artwork, taps, observer, HOLDER);
    }
    SnapshotCensorSurface(Context context, SnapshotRenderSession.Owner owner, SnapshotRenderSession.ArtworkLoader artwork,
            CensorTapTracker taps, Observer observer, Endpoint endpoint) {
        this(context, owner, artwork, taps, observer, endpoint, null, null);
    }
    SnapshotCensorSurface(Context context, SnapshotRenderSession.Owner owner, SnapshotRenderSession.ArtworkLoader artwork,
            CensorTapTracker taps, Observer observer, Endpoint endpoint,
            LatestResourceCoordinator.Delivery completionDelivery, ExecutorService rendering) {
        super(context); onMain(); this.observer = Objects.requireNonNull(observer);
        collectSubmissions = observer.collectSubmissions();
        this.taps = Objects.requireNonNull(taps); this.endpoint = Objects.requireNonNull(endpoint);
        this.completionDelivery = completionDelivery == null ? main::post : completionDelivery;
        this.rendering = rendering == null ? Executors.newSingleThreadExecutor(action -> new Thread(action, "Censor-surface-render")) : rendering;
        session = new SnapshotRenderSession(owner, artwork, getResources().getDisplayMetrics().density, main::post, this::presentationChanged, collectSubmissions);
        surface = new SurfaceView(context); holder = surface.getHolder();
        surface.setZOrderOnTop(true); holder.setFormat(PixelFormat.TRANSLUCENT);
        addView(surface, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        holder.addCallback(lifecycle);
    }
    public void configure(CensorAppearance appearance, SnapshotPaintPolicy policy, long artworkRevision,
            boolean ownsRectangularSurfaceChain) {
        onMain(); if (closed) throw new IllegalStateException("closed surface");
        session.configure(appearance, policy, artworkRevision); rectangular = ownsRectangularSurfaceChain;
        invalidateEndpoint(false); demand();
    }
    public void presentationAllowed(boolean value) {
        onMain(); if (closed || allowed == value) return; allowed = value;
        invalidateEndpoint(false); if (!value) session.invalidate(); demand();
    }
    public void presentationChanged() {
        if (closed || !changedQueued.compareAndSet(false, true)) return;
        try {
            if (!main.post(() -> { changedQueued.set(false); if (!closed) demand(); })) changedQueued.set(false);
        } catch (RuntimeException uncertain) {
            // Possibly accepted: keep reservation until the actual action returns or close.
        }
    }
    /** Immediate metadata/tap fence after an off-main scope observation; no View/holder access. */
    public void scopeInvalidated(CensorEngine.ActiveScope scope) {
        synchronized (gate) {
            if (scope == null || !scope.same(admittedScope)) return;
            generation++; pending = null; admittedScope = null; taps.withdrawDisplayed(this);
        }
        presentationChanged();
    }
    private void demand() {
        onMain(); if (closed || faulted) return;
        // Known rejected completion delivery is pollable, accepted/uncertain delivery is not stolen.
        Notice poll = null;
        synchronized (gate) {
            if (completed != null && completionReserved == null && !completionExecuting) {
                poll = new Notice(); completionReserved = poll;
            }
        }
        if (poll != null) deliverCompletion(poll);
        if (!frameQueued && ready()) {
            frameQueued = true;
            if (Build.VERSION.SDK_INT >= 33) postExpectedFrame(++frameGeneration);
            else Choreographer.getInstance().postFrameCallback(frame);
            observer.drawDemand();
        }
    }
    @RequiresApi(33)
    private void postExpectedFrame(long requestedGeneration) {
        Choreographer.getInstance().postVsyncCallback(data -> {
            if (!frameQueued || requestedGeneration != frameGeneration) return;
            frameQueued = false;
            if (closed || faulted) return;
            Choreographer.FrameTimeline timeline = data.getPreferredFrameTimeline();
            PresentationFrame expected = new PresentationFrame(SystemClock.uptimeMillis(),
                    timeline.getDeadlineNanos() / 1_000_000L,
                    timeline.getExpectedPresentationTimeNanos() / 1_000_000L,
                    session.presentationRevision());
            enqueueObservedPose(expected);
        });
    }
    private void cancelDemand() {
        main.removeCallbacks(refresh);
        frameGeneration++;
        if (frameQueued) { Choreographer.getInstance().removeFrameCallback(frame); frameQueued = false; }
    }
    private boolean ready() {
        return created && surfaceWidth > 0 && surfaceHeight > 0 && isAttachedToWindow() && isShown()
                && getWindowVisibility() == VISIBLE && surface.isShown() && holder.getSurface().isValid();
    }
    private boolean rectangularPose() {
        if (surface.getScrollX() != 0 || surface.getScrollY() != 0 || getScrollX() != 0 || getScrollY() != 0) return false;
        View node = surface;
        while (true) {
            if (!node.getMatrix().isIdentity() || node.getAlpha() != 1 || node.getClipToOutline()) return false;
            ViewParent parent = node.getParent(); if (!(parent instanceof View)) break; node = (View) parent;
        }
        Rect bounds = holder.getSurfaceFrame();
        return surface.getWidth() == getWidth() && surface.getHeight() == getHeight()
                && surfaceWidth == surface.getWidth() && surfaceHeight == surface.getHeight()
                && bounds.equals(new Rect(0, 0, surfaceWidth, surfaceHeight))
                && surface.getLocalVisibleRect(visible) && visible.equals(bounds);
    }
    private boolean poseCurrent(Pose pose) {
        if (!ready() || !rectangularPose() || !allowed || !rectangular
                || session.configurationGeneration() != pose.configuration) return false;
        surface.getLocationOnScreen(origin);
        return origin[0] == pose.x && origin[1] == pose.y && surfaceWidth == pose.width
                && surfaceHeight == pose.height && visible.equals(pose.visible);
    }
    private boolean lifetime(Pose pose) {
        synchronized (gate) { return !closed && !faulted && surfaceAdmission && generation == pose.generation; }
    }
    private boolean latest(Completion value) {
        synchronized (gate) { return !closed && !faulted && surfaceAdmission
                && generation == value.pose.generation && postSerial == value.serial; }
    }
    private void enqueueObservedPose(PresentationFrame frame) {
        onMain(); if (closed || faulted || !ready()) return;
        boolean geometry = rectangularPose(); surface.getLocationOnScreen(origin);
        long epoch; synchronized (gate) { epoch = generation; }
        long configuration = session.configurationGeneration();
        CensorEngine.ActiveScope scope = session.activeScope();
        if (scope == null || !scope.same(lastScope)) {
            lastScope = scope; session.invalidate(); clearOwnMap();
        }
        Pose pose = new Pose(epoch, configuration, origin[0], origin[1], surfaceWidth, surfaceHeight,
                geometry ? visible : new Rect(0, 0, surfaceWidth, surfaceHeight),
                geometry && allowed && rectangular, scope, frame);
        // Owner reads/observers above may reenter: do not submit their superseded geometry.
        if (closed || faulted || !ready() || (pose.paint && !poseCurrent(pose))
                || configuration != session.configurationGeneration()) { demand(); return; }
        boolean launch = false;
        synchronized (gate) {
            if (closed || faulted || !surfaceAdmission || epoch != generation) return;
            if (pending != null) replacedPoses++;
            pending = pose; admittedScope = scope;
            if (!renderingReserved) { renderingReserved = true; launch = true; }
        }
        if (launch) {
            RenderTask task = new RenderTask();
            try { rendering.execute(() -> { if (task.started.compareAndSet(false, true)) renderLoop(); }); }
            catch (RuntimeException failure) {
                // If accepted-then-thrown, a late queued action must not acquire a second Surface.
                if (task.started.compareAndSet(false, true)) failSubmission();
            }
        }
        // Scroll sampling follows display callbacks, not the previous post's UI round trip.
        // Every callback still observes fresh main-thread geometry and coalesces one latest pose.
        demandMotionFrame(pose);
    }
    private void demandMotionFrame(Pose pose) {
        if (!pose.paint || pose.scope == null || !lifetime(pose) || !poseCurrent(pose)) return;
        boolean moving;
        try { moving = session.presentationAnimating(pose.scope, pose.configuration, SystemClock.uptimeMillis()); }
        catch (RuntimeException | Error unavailable) { return; } // No extra demand from a failed advisory query.
        // Owner queries may synchronously close, reconfigure, move or revoke this endpoint.
        if (moving && lifetime(pose) && poseCurrent(pose)) demand();
    }
    private void renderLoop() {
        while (true) {
            Pose pose;
            synchronized (gate) {
                pose = pending; pending = null;
                if (pose == null || closed || faulted) { renderingReserved = false; gate.notifyAll(); return; }
                if (!surfaceAdmission || pose.generation != generation) continue;
                surfaceUsers++; // Reserve BEFORE holder acquisition; destruction must wait for it.
            }
            Completion result;
            try (SurfaceUse use = new SurfaceUse()) { result = recordAndPost(pose, use); }
            if (result != null) offerCompletion(result);
        }
    }
    private Completion recordAndPost(Pose pose, SurfaceUse use) {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("worker surface required");
        long began = SystemClock.elapsedRealtimeNanos(), locked, recorded = SystemClock.uptimeMillis();
        Canvas canvas;
        if (!lifetime(pose)) return null;
        try { lockCount++; canvas = endpoint.lock(holder); locked = SystemClock.elapsedRealtimeNanos(); }
        catch (RuntimeException failure) { return lifetime(pose) ? failed(pose, recorded, began) : null; }
        if (canvas == null) return lifetime(pose) ? failed(pose, recorded, began) : null;
        SnapshotRenderSession.Drawing drawing = null;
        SnapshotRenderSession.Paint paint = null;
        SurfaceSubmission.Paint submittedPaint = null;
        long recordingEnd, postingStarted, postEnd, returned, serial = 0;
        boolean safe = false, recordedSafely = false, posted = false, cleanup = true;
        // EVERYTHING after a returned Canvas belongs to this finally, including Error and tap-map
        // preparation paths. A use count must never end while its actual Canvas is still held.
        try {
            safe = canvas.getClipBounds().equals(new Rect(0, 0, canvas.getWidth(), canvas.getHeight()));
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
            boolean geometry = safe && canvas.isHardwareAccelerated() && canvas.getWidth() == pose.width
                    && canvas.getHeight() == pose.height;
            if (geometry && pose.paint && pose.scope != null && lifetime(pose)
                    && session.configurationGeneration() == pose.configuration) {
                drawing = session.draw(canvas, pose.x, pose.y, pose.visible, true, true, true, pose.scope, pose.frame);
            }
            boolean painted = drawing != null && lifetime(pose) && session.configurationGeneration() == pose.configuration;
            if (!painted) canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
            paint = painted ? drawing.paint : null;
            if (collectSubmissions) submittedPaint = recordedPaint(paint);
            TapMap nextMap = paint == null ? null : tapMap(paint);
            synchronized (gate) {
                serial = ++postSerial;
                // Native pixels may queue before post returns. Identical coverage is ineligible
                // until UI admission, but may preserve stationary geometry age when re-admitted.
                if (nextMap != null && !closed && !faulted && surfaceAdmission && generation == pose.generation)
                    taps.suspendDisplayed(this, nextMap.scope, nextMap.boxes, nextMap.viewport);
                else taps.withdrawDisplayed(this);
            }
            recordedSafely = true;
        } catch (RuntimeException | Error failure) {
            paint = null;
            try { canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR); submittedPaint = collectSubmissions ? SurfaceSubmission.Paint.clear() : null; }
            catch (RuntimeException | Error clearFailure) { safe = false; }
            synchronized (gate) { serial = ++postSerial; taps.withdrawDisplayed(this); }
        } finally {
            recordingEnd = SystemClock.elapsedRealtimeNanos();
            postingStarted = SystemClock.elapsedRealtimeNanos(); postAttempts++;
            try { endpoint.post(holder, canvas); posted = true; postReturns++; }
            catch (RuntimeException | Error failure) { /* Uncertain: remove endpoint, never retry. */ }
            finally {
                postEnd = SystemClock.elapsedRealtimeNanos(); returned = SystemClock.uptimeMillis();
                use.close(); // Actual holder post attempt ended; no more holder access in this job.
                try { if (drawing != null) drawing.close(); }
                catch (RuntimeException | Error failure) { cleanup = false; }
            }
        }
        if (collectSubmissions) {
            long observationBegan = SystemClock.elapsedRealtimeNanos();
            try {
                observer.submitted(new SurfaceSubmission(postAttempts, serial, pose.generation, returned,
                        Thread.currentThread().getId(), Math.max(0, began - pose.enqueued), locked - began,
                        recordingEnd - locked, postEnd - postingStarted, pose.x, pose.y, pose.width, pose.height,
                        posted, safe, recordedSafely, cleanup, safe ? submittedPaint : null));
            } catch (RuntimeException | Error unavailable) { submissionObserverFailures++; }
            finally {
                long elapsed = SystemClock.elapsedRealtimeNanos() - observationBegan;
                submissionObservationNanos += elapsed; maximumSubmissionObservationNanos = Math.max(maximumSubmissionObservationNanos, elapsed);
            }
        }
        if (!posted || !safe || !recordedSafely || !cleanup) return failed(pose, recorded, began);
        postCount++;
        return new Completion(pose, lifetime(pose) ? paint : null, serial,
                paint == null ? recorded : paint.recordedAtMillis, returned, began - pose.enqueued,
                locked - began, recordingEnd - locked, postEnd - postingStarted, false);
    }
    private Completion failed(Pose pose, long recorded, long began) {
        long serial;
        synchronized (gate) {
            faulted = true; surfaceAdmission = false; generation++; pending = null;
            serial = ++postSerial; taps.withdrawDisplayed(this);
        }
        return new Completion(pose, null, serial, recorded, SystemClock.uptimeMillis(),
                Math.max(0, began - pose.enqueued), 0, 0, 0, true);
    }
    private SurfaceSubmission.Paint recordedPaint(SnapshotRenderSession.Paint paint) {
        if (paint == null) return SurfaceSubmission.Paint.clear();
        try {
            List<SurfaceSubmission.Member> members = new ArrayList<>();
            if (paint.result != null) for (SnapshotCensorPainter.PaintedMember member : paint.result.members)
                if (member.recording != null) members.add(member.recording);
            return SurfaceSubmission.Paint.drawn(paint.scope.epoch, paint.scope.geometry, paint.revision,
                    paint.presentationRevision, paint.recordedAtMillis, paint.result == null ? 0 : paint.result.members.size(), members, paint.reverse);
        } catch (RuntimeException | Error unavailable) { return null; }
    }
    private void failSubmission() {
        long epoch; synchronized (gate) { renderingReserved = false; epoch = generation; }
        Completion value = failed(new Pose(epoch, 0, 0, 0, 0, 0, new Rect(), false, null, null),
                SystemClock.uptimeMillis(), SystemClock.elapsedRealtimeNanos());
        offerCompletion(value);
    }
    private void offerCompletion(Completion value) {
        Notice dispatch = null;
        synchronized (gate) {
            if (closed) { if (!value.failure) droppedSuccessfulCompletions++; return; }
            if (completed != null) { replacedCompletions++; if (!completed.failure) droppedSuccessfulCompletions++; }
            completed = value;
            if (completionReserved == null) { dispatch = new Notice(); completionReserved = dispatch; }
        }
        if (dispatch != null) postCompletionNotice(dispatch);
    }
    private void postCompletionNotice(Notice notice) {
        try {
            if (!completionDelivery.post(notice.action)) synchronized (gate) {
                if (completionReserved == notice && !completionExecuting) completionReserved = null;
            }
        } catch (RuntimeException uncertain) { /* Keep possibly accepted reservation. */ }
    }
    private void deliverCompletion(Notice notice) {
        onMain(); Completion value;
        synchronized (gate) {
            if (completionExecuting || completionReserved != notice || !notice.consumed.compareAndSet(false, true)) return;
            completionExecuting = true; value = completed; completed = null;
        }
        try { if (value != null && !closed) admit(value); }
        finally {
            Notice again = null;
            synchronized (gate) {
                completionExecuting = false; completionReserved = null;
                if (!closed && completed != null) { again = new Notice(); completionReserved = again; }
            }
            if (again != null) postCompletionNotice(again);
        }
    }
    private void admit(Completion value) {
        if (value.failure) {
            boolean current; synchronized (gate) { current = faulted && postSerial == value.serial; }
            if (current && !closed) {
                cancelDemand(); surface.setVisibility(INVISIBLE); observer.endpointFailure();
            }
            return;
        }
        SnapshotRenderSession.Paint paint = value.paint;
        CensorEngine.PaintReceipt accepted = null;
        if (paint != null && intervalCurrent(value) && session.isCurrent(paint) && intervalCurrent(value)) {
            accepted = session.acknowledge(paint);
            if (accepted != null) observer.recorded(accepted);
            if (!intervalCurrent(value) || !session.isCurrent(paint) || !intervalCurrent(value)) accepted = null;
        }
        if (accepted != null && !publishTaps(value)) accepted = null;
        if (accepted == null) withdrawIfLatest(value);
        // Rejected nonempty posted pixels need a real current/transparent replacement, not map-only clearing.
        boolean nonempty = paint != null && (paint.reverse || (paint.result != null && !paint.result.members.isEmpty()));
        if (nonempty && accepted == null && latest(value)) demand();
        if (accepted != null && paint.animated) demand();
        // drawDemand is public and may change pose/scope, just like recorded/posted observers.
        if (accepted != null && !currentAfterObserver(value)) {
            accepted = null; withdrawIfLatest(value); if (nonempty && latest(value)) demand();
        }
        observer.posted(new Post(value, accepted));
        if (paint != null && !closed && !currentAfterObserver(value)) {
            withdrawIfLatest(value); if (nonempty && latest(value)) demand();
        }
    }
    private boolean currentAfterObserver(Completion value) {
        return intervalCurrent(value) && session.isCurrent(value.paint) && intervalCurrent(value);
    }
    private boolean intervalCurrent(Completion value) { return latest(value) && poseCurrent(value.pose) && latest(value); }
    private TapMap tapMap(SnapshotRenderSession.Paint paint) {
        long scope = taps.scope(); List<BBox> boxes = new ArrayList<>(); Rect clip = paint.visible();
        if (paint.result != null && paint.result.rectangularTapCoverageQualified) {
            for (SnapshotCensorPainter.PaintedMember member : paint.result.members) {
                if (!member.protectionScope.isNormal() || member.protectionScope.tapScope != scope) continue;
                for (SceneEngine.Region rect : member.tapInteriors) {
                    int x = (int) Math.ceil(Math.max(rect.left, clip.left + paint.x)), y = (int) Math.ceil(Math.max(rect.top, clip.top + paint.y));
                    int right = (int) Math.floor(Math.min(rect.right, clip.right + paint.x)), bottom = (int) Math.floor(Math.min(rect.bottom, clip.bottom + paint.y));
                    if (right > x && bottom > y) boxes.add(new BBox(x, y, right - x, bottom - y));
                }
            }
        }
        return new TapMap(scope, boxes, new BBox(clip.left + paint.x, clip.top + paint.y, clip.width(), clip.height()));
    }
    private boolean publishTaps(Completion value) {
        SnapshotRenderSession.Paint paint = value.paint; TapMap map = tapMap(paint);
        if (!intervalCurrent(value) || !session.isCurrent(paint) || !intervalCurrent(value)) { withdrawIfLatest(value); return false; }
        synchronized (gate) {
            if (closed || faulted || !surfaceAdmission || generation != value.pose.generation || postSerial != value.serial) return false;
            if (map.boxes.isEmpty()) taps.withdrawDisplayed(this);
            else taps.publishDisplayed(this, map.scope, map.boxes, map.viewport, paint.recordedAtMillis, false);
        }
        main.removeCallbacks(refresh);
        main.postDelayed(refresh, CensorTapTracker.DISPLAY_REFRESH_MILLIS); return true;
    }
    private void withdrawIfLatest(Completion value) {
        synchronized (gate) { if (postSerial == value.serial) taps.withdrawDisplayed(this); }
    }
    private void clearOwnMap() {
        long before, after;
        synchronized (gate) { before = taps.scope(); taps.clearDisplayed(this); after = taps.scope(); }
        if (after != before) observer.tapScopeInvalidated(after);
    }
    private void invalidateEndpoint(boolean destroy) {
        synchronized (gate) {
            generation++; pending = null; if (destroy) surfaceAdmission = false;
            if (!destroy) taps.withdrawDisplayed(this);
        }
        if (destroy) clearOwnMap();
    }
    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom); if (session != null) demand();
    }
    @Override protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility); if (session == null) return;
        if (visibility != VISIBLE) { invalidateEndpoint(false); cancelDemand(); session.invalidate(); }
        else demand();
    }
    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility); if (session == null) return;
        if (visibility != VISIBLE) { invalidateEndpoint(false); cancelDemand(); session.invalidate(); }
        else demand();
    }
    @Override protected void onDetachedFromWindow() {
        if (session != null) { invalidateEndpoint(true); cancelDemand(); session.invalidate(); }
        super.onDetachedFromWindow();
    }
    @Override public void close() {
        onMain(); if (closed) return; closed = true;
        invalidateEndpoint(true); cancelDemand(); Notice notice;
        synchronized (gate) {
            if (completed != null && !completed.failure) droppedSuccessfulCompletions++;
            completed = null; notice = completionReserved; if (!completionExecuting) completionReserved = null;
        }
        if (notice != null) main.removeCallbacks(notice.action);
        try { surface.setVisibility(INVISIBLE); }
        finally {
            try { session.close(); }
            finally {
                try {
                    // FIFO closes painter on its owning drawing thread, after real jobs return.
                    AtomicBoolean started = new AtomicBoolean();
                    rendering.execute(() -> {
                        if (!started.compareAndSet(false, true)) return;
                        session.finishDrawing(); drawingFinished.set(true);
                    });
                } catch (RuntimeException uncertain) {
                    // Possibly accepted close still owns execution. Never counterfeit painter drain.
                    observer.resourceFailure();
                } finally {
                    rendering.shutdown(); holder.removeCallback(lifecycle); created = false;
                }
            }
        }
    }
    public boolean managedResourcesDrained() {
        if (!session.managedResourcesDrained() || !drawingFinished.get() || !rendering.isTerminated()) return false;
        synchronized (gate) {
            return surfaceUsers == 0 && !renderingReserved && pending == null && completed == null && completionReserved == null;
        }
    }
    public boolean preparationTerminated() { return session.preparationTerminated(); }
    public long postCount() { return postCount; }
    public long lockCount() { return lockCount; }
    long postAttempts() { return postAttempts; }
    long postReturns() { return postReturns; }
    public boolean endpointFailed() { return faulted; }
    SnapshotRenderSession renderer() { return session; }
    SurfaceView endpointView() { return surface; }
    void requestFrame() { demand(); }
    // Native queue controls observe real UI geometry but bypass only the Choreographer wait.
    void requestObservedFrame() { enqueueObservedPose(null); }
    boolean demandPending() { onMain(); return frameQueued || changedQueued.get(); }
    long submissionObserverFailures() { return submissionObserverFailures; }
    long submissionObservationNanos() { return submissionObservationNanos; }
    long maximumSubmissionObservationNanos() { return maximumSubmissionObservationNanos; }
    int executingRenderCount() { synchronized (gate) { return renderingReserved ? 1 : 0; } }
    int pendingPoseCount() { synchronized (gate) { return pending == null ? 0 : 1; } }
    int completionNoticeCount() { synchronized (gate) { return completionReserved != null ? 1 : 0; } }
    long replacedPoseCount() { synchronized (gate) { return replacedPoses; } }
    long replacedCompletionCount() { synchronized (gate) { return replacedCompletions; } }
    long droppedSuccessfulCompletionCount() { synchronized (gate) { return droppedSuccessfulCompletions; } }
    int surfaceUseCount() { synchronized (gate) { return surfaceUsers; } }
    long destructionsStarted() { return destructionsStarted; }
    long destructionsReturned() { return destructionsReturned; }
    private static void onMain() { if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("platform surface main thread required"); }
}
