package com.subhub.app.censor.android;

import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.annotation.RequiresApi;

import com.subhub.app.censor.engine.SceneEngine;
import com.subhub.app.censor.engine.ScrollMotionLedger;
import com.subhub.app.service.SerialOwnedLookup;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/**
 * Copied-event/platform-handle ownership only. No camera, trajectory, classifier or scene state.
 * Exact producer and nearest scrollable-owner identities stay separate. Provider bounds are
 * advisory and cannot qualify which image members move, including overlays inside those bounds.
 */
@RequiresApi(33)
public final class AccessibilityScrollReader implements AutoCloseable {
    public static final int MAXIMUM_OUTSTANDING = 32;
    public static final int MAXIMUM_IDENTITIES = 16;
    public static final int MAXIMUM_PARENT_HOPS = 6;
    private static final long MAXIMUM_QUEUE_MILLIS = 1500;

    public interface Observer {
        void observed(ScrollMotionLedger.Sample sample);
        void gap(ScrollMotionLedger.Sample sample);
        void resourceFailure();
    }

    private static final class Pending {
        final AccessibilityEvent event;
        final ScrollMotionLedger.Scope scope;
        final String packageName;
        final long identity, sourceTime, receiptTime;
        final ScrollMotionLedger.Coordinate x, y;
        Pending(AccessibilityEvent event, ScrollMotionLedger.Scope scope, String packageName, long identity, long receipt) {
            this.event = event; this.scope = scope; this.packageName = packageName; this.identity = identity;
            receiptTime = receipt; sourceTime = event.getEventTime();
            x = ScrollMotionLedger.Coordinate.accessibility(event.getScrollX(), event.getMaxScrollX(), event.getScrollDeltaX());
            y = ScrollMotionLedger.Coordinate.accessibility(event.getScrollY(), event.getMaxScrollY(), event.getScrollDeltaY());
        }
        ScrollMotionLedger.Sample sample(long owner, long producer, long started, long finished, SceneEngine.Region viewport) {
            return sample(owner, producer, started, finished, viewport, ScrollMotionLedger.OwnerKind.UNKNOWN);
        }
        ScrollMotionLedger.Sample sample(long owner, long producer, long started, long finished, SceneEngine.Region viewport,
                ScrollMotionLedger.OwnerKind ownerKind) {
            return new ScrollMotionLedger.Sample(scope, identity, owner, producer, sourceTime, receiptTime,
                    started, finished, x, y, viewport, ownerKind);
        }
    }
    private static final class Identity {
        final AccessibilityNodeInfo node;
        final long token;
        long used;
        Identity(AccessibilityNodeInfo node, long token, long used) { this.node = node; this.token = token; this.used = used; }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Predicate<ScrollMotionLedger.Scope> current;
    private final Observer observer;
    private final ExecutorService worker;
    private final SerialOwnedLookup<Pending> queue;
    private final List<Identity> identities = new ArrayList<>(); // Worker-owned, bounded exact handle identities.
    private final AtomicBoolean cleanupFailed = new AtomicBoolean();
    private final AtomicInteger copiedInputs = new AtomicInteger();
    private final Runnable rootAcquired;
    private final Predicate<Runnable> deliveryPost;
    private volatile boolean closed;
    private volatile int identityCount;
    private long deliveryIdentity;
    private long nodeIdentity; // Worker-owned; never recycled with cache eviction or scope changes.
    private ScrollMotionLedger.Scope identityScope; // Worker-owned.

    public AccessibilityScrollReader(Predicate<ScrollMotionLedger.Scope> current, Observer observer) {
        this(current, observer, () -> { });
    }

    /** A held-read native control follows actual provider acquisition; it does not replace the provider. */
    AccessibilityScrollReader(Predicate<ScrollMotionLedger.Scope> current, Observer observer, Runnable rootAcquired) {
        this(current, observer, rootAcquired, null);
    }

    /** Native control can hold the actual terminal post without replacing event/provider reads. */
    AccessibilityScrollReader(Predicate<ScrollMotionLedger.Scope> current, Observer observer, Runnable rootAcquired,
            Predicate<Runnable> deliveryPost) {
        this.current = Objects.requireNonNull(current); this.observer = Objects.requireNonNull(observer);
        this.rootAcquired = Objects.requireNonNull(rootAcquired);
        this.deliveryPost = deliveryPost == null ? main::post : deliveryPost;
        worker = Executors.newSingleThreadExecutor(action -> new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY); action.run();
        }, "Censor-scroll-source"));
        queue = new SerialOwnedLookup<>(MAXIMUM_OUTSTANDING, worker, this::read, this::dispose,
                (pending, failure) -> failedCleanup());
    }

    /** Copies primitives/parcel on main; no source/root/parent Binder lookup occurs in this call. */
    @SuppressWarnings("deprecation")
    public void offer(AccessibilityEvent event, ScrollMotionLedger.Scope scope, String packageName) {
        onMain(); Objects.requireNonNull(event); Objects.requireNonNull(scope); Objects.requireNonNull(packageName);
        if (closed || !current.test(scope) || event.getEventType() != AccessibilityEvent.TYPE_VIEW_SCROLLED
                || event.getWindowId() != scope.windowId || !packageName.contentEquals(event.getPackageName())) return;
        if (deliveryIdentity == Long.MAX_VALUE) { failedCleanup(); return; }
        long receipt = SystemClock.uptimeMillis();
        long delivery = ++deliveryIdentity;
        if (copiedInputs.get() >= MAXIMUM_OUTSTANDING) {
            queue.invalidate();
            deliverMainGap(new ScrollMotionLedger.Sample(scope, delivery, 0, 0, event.getEventTime(), receipt, -1, -1,
                    ScrollMotionLedger.Coordinate.accessibility(event.getScrollX(), event.getMaxScrollX(), event.getScrollDeltaX()),
                    ScrollMotionLedger.Coordinate.accessibility(event.getScrollY(), event.getMaxScrollY(), event.getScrollDeltaY()), null));
            return;
        }
        AccessibilityEvent copy = AccessibilityEvent.obtain(event);
        copiedInputs.incrementAndGet();
        Pending pending;
        try { pending = new Pending(copy, scope, packageName, delivery, receipt); }
        catch (RuntimeException | Error failure) {
            try { recycle(copy); } finally { copiedInputs.decrementAndGet(); }
            throw failure;
        }
        // Compound records can name other sources. Do not pick a largest foreign delta or claim
        // to have processed the complete event by using only a successful top-record subset.
        if (copy.getRecordCount() != 0) {
            queue.invalidate();
            try { deliverMainGap(pending); } finally { dispose(pending); }
            return;
        }
        SerialOwnedLookup.Offer offered = queue.offer(pending); // Ownership transfers even on rejection.
        if (offered != SerialOwnedLookup.Offer.ACCEPTED) {
            // A hole retires older queued/posted deltas too. Their actual running read still owns
            // its input until terminal; it cannot later invalidate newly acquired origins.
            queue.invalidate(); deliverMainGap(pending);
        }
    }

    public void invalidate() {
        onMain(); queue.invalidate();
        if (!closed) {
            try { worker.execute(() -> {
                // An active drain may already have processed a new scope before this task runs.
                // Never clear that new scope's exact identities with an old retirement request.
                if (identityScope == null || !current.test(identityScope)) clearIdentities();
            }); }
            catch (RejectedExecutionException failure) { failedCleanup(); }
        }
    }

    @Override public void close() {
        onMain(); if (closed) return; closed = true;
        queue.close();
        try { worker.execute(this::clearIdentities); }
        catch (RejectedExecutionException failure) { failedCleanup(); }
        finally { worker.shutdown(); }
    }

    public boolean managedResourcesDrained() {
        return closed && copiedInputs.get() == 0 && queue.outstanding() == 0 && worker.isTerminated()
                && identityCount == 0 && !cleanupFailed.get();
    }
    public int outstanding() { return copiedInputs.get(); }

    @SuppressWarnings("deprecation")
    private void read(Pending pending, SerialOwnedLookup.Lease lease) {
        long started = SystemClock.uptimeMillis();
        if (!eligible(pending, lease)) { lease.release(); return; }
        if (started - pending.receiptTime > MAXIMUM_QUEUE_MILLIS) {
            postOwned(pending, lease, pending.sample(0, 0, -1, -1, null), true); return;
        }
        List<AccessibilityNodeInfo> acquired = new ArrayList<>(MAXIMUM_PARENT_HOPS + 1);
        IdentityHashMap<AccessibilityNodeInfo, Boolean> distinct = new IdentityHashMap<>();
        long producer = 0, owner = 0;
        SceneEngine.Region viewport = null;
        ScrollMotionLedger.OwnerKind ownerKind = ScrollMotionLedger.OwnerKind.UNKNOWN;
        boolean invalid = false;
        try {
            if (identityScope == null || !identityScope.same(pending.scope)) {
                clearIdentities(); identityScope = pending.scope;
            }
            AccessibilityNodeInfo source = pending.event.getSource(0); // Explicitly no implicit prefetch.
            AccessibilityNodeInfo node = source;
            for (int hop = 0; node != null && hop <= MAXIMUM_PARENT_HOPS; hop++) {
                if (distinct.put(node, Boolean.TRUE) != null) { invalid = true; break; }
                acquired.add(node);
                if (!eligible(pending, lease)) { invalid = true; break; }
                CharSequence name = node.getPackageName();
                if (node.getWindowId() != pending.scope.windowId || name == null || !pending.packageName.contentEquals(name)) {
                    invalid = true; break;
                }
                if (hop == 0) { rootAcquired.run(); producer = intern(node, pending.identity); }
                if (node.isScrollable()) {
                    owner = intern(node, pending.identity);
                    Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
                    if (!bounds.isEmpty()) viewport = new SceneEngine.Region(bounds.left, bounds.top, bounds.right, bounds.bottom);
                    ownerKind = ScrollMotionLedger.OwnerKind.fromProviderClass(node.getClassName());
                    break;
                }
                if (hop == MAXIMUM_PARENT_HOPS) break;
                AccessibilityNodeInfo parent = node.getParent(0);
                if (parent != null && parent != node && parent.equals(node)) {
                    acquired.add(parent); distinct.put(parent, Boolean.TRUE); invalid = true; break;
                }
                node = parent;
            }
        } catch (RuntimeException failure) { invalid = true; }
        catch (Error failure) { failedCleanup(); invalid = true; }
        finally { for (AccessibilityNodeInfo node : acquired) recycle(node); }
        long finished = SystemClock.uptimeMillis();
        if (!eligible(pending, lease) || cleanupFailed.get()) { lease.release(); return; }
        ScrollMotionLedger.Sample sample = pending.sample(invalid ? 0 : owner, invalid ? 0 : producer,
                started, finished, invalid ? null : viewport, invalid ? ScrollMotionLedger.OwnerKind.UNKNOWN : ownerKind);
        postOwned(pending, lease, sample, false);
    }

    /** Both ordinary observations and missing-input gaps retain the same bounded generation lease. */
    private void postOwned(Pending pending, SerialOwnedLookup.Lease lease, ScrollMotionLedger.Sample sample, boolean missing) {
        Runnable delivery = () -> {
            try {
                if (eligible(pending, lease)) {
                    if (missing) observer.gap(sample);
                    else observer.observed(sample);
                }
            } finally { lease.release(); }
        };
        try { if (!deliveryPost.test(delivery)) { failedCleanup(); lease.release(); } }
        catch (RuntimeException | Error failure) { failedCleanup(); lease.release(); }
    }

    private boolean eligible(Pending pending, SerialOwnedLookup.Lease lease) {
        return !closed && !cleanupFailed.get() && lease.isCurrent() && current.test(pending.scope);
    }

    private long intern(AccessibilityNodeInfo node, long used) {
        for (Identity identity : identities) {
            if (identity.node.equals(node)) { identity.used = used; return identity.token; }
        }
        if (nodeIdentity == Long.MAX_VALUE) throw new IllegalStateException("scroll node identity exhausted");
        if (identities.size() == MAXIMUM_IDENTITIES) {
            Identity oldest = identities.get(0);
            for (Identity identity : identities) if (identity.used < oldest.used) oldest = identity;
            identities.remove(oldest); identityCount = identities.size(); recycle(oldest.node);
        }
        AccessibilityNodeInfo copy = new AccessibilityNodeInfo(node);
        long token = ++nodeIdentity;
        identities.add(new Identity(copy, token, used)); identityCount = identities.size();
        return token;
    }

    private void deliverMainGap(Pending pending) {
        deliverMainGap(pending.sample(0, 0, -1, -1, null));
    }
    private void deliverMainGap(ScrollMotionLedger.Sample sample) {
        onMain();
        if (!closed && current.test(sample.scope)) observer.gap(sample);
    }

    private void clearIdentities() {
        for (Identity identity : identities) recycle(identity.node);
        identities.clear(); identityCount = 0; identityScope = null;
    }
    private void dispose(Pending pending) {
        try { recycle(pending.event); } finally { copiedInputs.decrementAndGet(); }
    }
    @SuppressWarnings("deprecation")
    private void recycle(AccessibilityEvent event) {
        try { event.recycle(); } catch (RuntimeException | Error failure) { failedCleanup(); }
    }
    @SuppressWarnings("deprecation")
    private void recycle(AccessibilityNodeInfo node) {
        try { node.recycle(); } catch (RuntimeException | Error failure) { failedCleanup(); }
    }
    private void failedCleanup() {
        if (cleanupFailed.compareAndSet(false, true)) {
            try { observer.resourceFailure(); } catch (RuntimeException | Error ignored) { /* Failure stays sticky. */ }
        }
    }
    private static void onMain() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Scroll callback off main");
    }
}
