package com.subhub.app.censor.android;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import androidx.annotation.RequiresApi;

import com.subhub.app.detection.text.NativeTextReader;
import com.subhub.app.service.ForegroundWindowResolver;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded platform scope reads only. No scene, pixels, motion, retained roots or main-thread Binder reads. */
@RequiresApi(30)
public final class AccessibilityWindowReader implements AutoCloseable {
    public static final int MAXIMUM_WINDOWS = 32;
    public static final int MAXIMUM_READS = 4;

    /** Actual application-window observation; time/geometry do not establish screenshot pixel time. */
    public static final class Window {
        public final int id, displayWidth, displayHeight, left, top, width, height;
        public final String packageName;
        private Window(int id, String name, DisplayMetrics display, Rect bounds) {
            this.id = id; packageName = name;
            displayWidth = display.widthPixels; displayHeight = display.heightPixels;
            left = bounds.left; top = bounds.top; width = bounds.width(); height = bounds.height();
        }
        public boolean same(Window other) {
            return other != null && id == other.id && packageName.equals(other.packageName)
                    && displayWidth == other.displayWidth && displayHeight == other.displayHeight
                    && left == other.left && top == other.top && width == other.width && height == other.height;
        }
    }

    private static final class Read {
        final Window window;
        final AccessibilityNodeInfo root;
        Read(Window window, AccessibilityNodeInfo root) { this.window = window; this.root = root; }
    }

    private final AccessibilityService service;
    private final AtomicBoolean cleanupFailed = new AtomicBoolean();
    private final Runnable acquired;
    private final WindowReadCost.Diagnostics costs = new WindowReadCost.Diagnostics(System::nanoTime, SystemClock::uptimeMillis);
    private boolean closed;
    private int activeReads;

    public AccessibilityWindowReader(AccessibilityService service) { this(service, () -> { }); }
    /** Adverse native control after actual root acquisition, never a substitute platform reader. */
    AccessibilityWindowReader(AccessibilityService service, Runnable acquired) {
        this.service = Objects.requireNonNull(service); this.acquired = Objects.requireNonNull(acquired);
    }

    /** Negative windowId selects one uniquely focused application window of the requested package. */
    public Window observe(String packageName, int windowId) {
        return observe(packageName, windowId, -1, -1, null, null);
    }

    private Window observe(String packageName, int windowId, long epoch, long request,
            WindowReadCost.Operation operation, String boundary) {
        boolean profiled = operation != null && costs.entry();
        try { if (!beginRead()) { if (profiled) costs.refused(); return null; } }
        catch (RuntimeException | Error failure) { if (profiled) costs.beginFailed(); throw failure; }
        WindowReadCost.Session cost = null;
        boolean normal = true;
        try {
            if (profiled) cost = costs.start(epoch, request, operation, boundary);
            Read read = read(packageName, windowId, cost);
            if (read == null) return null;
            try { return read.window; }
            finally { recycle(read.root, cost); }
        } catch (RuntimeException | Error failure) { normal = false; throw failure; }
        finally {
            try { endRead(); }
            finally { costs.finish(cost, normal, false); }
        }
    }

    /** Product selection uses the same standalone foreground policy, with no retained handles. */
    @SuppressWarnings("deprecation")
    public Window observeForeground(String inputMethodPackage, String preferredPackage) {
        if (!beginRead()) return null;
        List<AccessibilityWindowInfo> windows = null;
        Throwable original = null;
        try {
            WindowManager manager = service.getSystemService(WindowManager.class);
            if (manager == null) return null;
            DisplayMetrics display = new DisplayMetrics(); manager.getDefaultDisplay().getRealMetrics(display);
            if (display.widthPixels <= 0 || display.heightPixels <= 0) return null;
            windows = service.getWindows();
            if (windows == null || windows.size() > MAXIMUM_WINDOWS) return null;
            List<ForegroundWindowResolver.Candidate> candidates = new ArrayList<>();
            Map<Integer, Window> descriptors = new HashMap<>();
            for (AccessibilityWindowInfo window : windows) {
                if (window == null) throw new IllegalStateException("Incomplete window list");
                if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                Rect bounds = new Rect(); window.getBoundsInScreen(bounds);
                if (window.getId() < 0 || bounds.isEmpty() || bounds.left < 0 || bounds.top < 0
                        || bounds.right > display.widthPixels || bounds.bottom > display.heightPixels) continue;
                AccessibilityNodeInfo root = window.getRoot();
                if (root == null) continue;
                Throwable rootFailure = null;
                try {
                    CharSequence name = root.getPackageName();
                    if (name == null || name.length() == 0 || name.length() > 256
                            || root.getWindowId() != window.getId()) continue;
                    String packageName = name.toString();
                    if (descriptors.put(window.getId(), new Window(window.getId(), packageName, display, bounds)) != null) {
                        return null; // Duplicate platform window identity is not an eligible subset.
                    }
                    candidates.add(new ForegroundWindowResolver.Candidate(packageName, window.getId(),
                            window.isActive(), window.isFocused(), window.getLayer()));
                } catch (RuntimeException | Error failure) { rootFailure = failure; throw failure; }
                finally { releaseRoot(root, rootFailure); }
            }
            ForegroundWindowResolver.Candidate selected = ForegroundWindowResolver.select(candidates,
                    inputMethodPackage, preferredPackage);
            acquired.run();
            return selected == null || isClosed() ? null : descriptors.get(selected.windowId);
        } catch (RuntimeException | Error failure) { original = failure; throw failure; }
        finally {
            try { finishCleanup(original, recycleWindows(windows)); }
            finally { endRead(); }
        }
    }

    /** Transfers only the actual returned root. NativeTextReader owns it through read/confirmation cleanup. */
    public NativeTextReader.Tree open(Window expected, long contentGeneration) {
        return open(expected, contentGeneration, -1, -1, null, null);
    }

    NativeTextReader.Tree open(Window expected, long contentGeneration, long epoch, long request,
            WindowReadCost.Operation operation, String boundary) {
        Objects.requireNonNull(expected);
        boolean profiled = operation != null && costs.entry();
        try { if (!beginRead()) { if (profiled) costs.refused(); return null; } }
        catch (RuntimeException | Error failure) { if (profiled) costs.beginFailed(); throw failure; }
        WindowReadCost.Session cost = null;
        boolean normal = true, rootTransferred = false;
        try {
            if (profiled) cost = costs.start(epoch, request, operation, boundary);
            Read read = read(expected.packageName, expected.id, cost);
            if (read == null) return null;
            boolean transferred = false;
            Throwable original = null;
            try {
                if (isClosed() || !expected.same(read.window)) return null;
                NativeTextReader.Tree tree = new NativeTextReader.Tree(read.root,
                        expected.displayWidth, expected.displayHeight, contentGeneration);
                transferred = true;
                rootTransferred = true;
                return tree;
            } catch (RuntimeException | Error failure) { original = failure; throw failure; }
            finally { if (!transferred) releaseRoot(read.root, original, cost); }
        } catch (RuntimeException | Error failure) { normal = false; throw failure; }
        finally {
            try { endRead(); }
            finally { costs.finish(cost, normal, rootTransferred); }
        }
    }

    public boolean current(Window expected) {
        return expected != null && expected.same(observe(expected.packageName, expected.id));
    }

    boolean current(Window expected, long epoch, long request, WindowReadCost.Operation operation, String boundary) {
        return expected != null && expected.same(observe(expected.packageName, expected.id, epoch, request, operation, boundary));
    }

    WindowReadCost.Diagnostics costDiagnostics() { return costs; }

    public boolean cleanupFailed() { return cleanupFailed.get(); }
    @Override public synchronized void close() { closed = true; }
    public synchronized boolean managedResourcesDrained() { return closed && activeReads == 0 && !cleanupFailed.get(); }
    synchronized int activeReadCount() { return activeReads; }
    private synchronized boolean isClosed() { return closed; }
    private synchronized boolean beginRead() {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("Window Binder read on main");
        if (cleanupFailed.get()) throw new IllegalStateException("Window handles have not drained");
        if (closed || activeReads >= MAXIMUM_READS) return false;
        activeReads++; return true;
    }
    private synchronized void endRead() { activeReads--; }
    void release(NativeTextReader.Tree tree) { recycle(tree.root); }

    @SuppressWarnings("deprecation") // Owned platform handles, not a cached node/window tree.
    private Read read(String packageName, int windowId, WindowReadCost.Session cost) {
        if (packageName == null || packageName.isEmpty() || packageName.length() > 256) {
            throw new IllegalArgumentException("Window package");
        }
        DisplayMetrics display = display(cost);
        if (display == null) return null;
        List<AccessibilityWindowInfo> windows = windowList(cost);
        if (windows == null) return null;
        AccessibilityNodeInfo selected = null;
        Window descriptor = null;
        boolean transferred = false;
        Throwable original = null;
        try {
            WindowReadCost.Frame filter = costs.begin(cost, WindowReadCost.Stage.FILTER);
            boolean filterNormal = true;
            try {
                if (windows.size() > MAXIMUM_WINDOWS) return null; // No successful subset of a truncated scope read.
                for (AccessibilityWindowInfo window : windows) {
                    if (window == null) throw new IllegalStateException("Incomplete window list");
                    if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION
                            // A foreground-selected exact window can remain visible under an app overlay.
                            // Only unscoped discovery requires focus; exact package/id/bounds still fence it.
                            || (windowId < 0 && !(window.isActive() || window.isFocused()))
                            || (windowId >= 0 && window.getId() != windowId)) continue;
                    Rect bounds = new Rect(); window.getBoundsInScreen(bounds);
                    if (window.getId() < 0 || bounds.isEmpty() || bounds.left < 0 || bounds.top < 0
                            || bounds.right > display.widthPixels || bounds.bottom > display.heightPixels) continue;
                    AccessibilityNodeInfo root = root(window, cost);
                    if (root == null) continue;
                    boolean owned = true;
                    Throwable rootFailure = null;
                    WindowReadCost.Frame identity = costs.begin(cost, WindowReadCost.Stage.ROOT_IDENTITY);
                    try {
                        CharSequence name = root.getPackageName();
                        if (name == null || !packageName.contentEquals(name) || root.getWindowId() != window.getId()) continue;
                        if (selected != null) return null; // Ambiguous application windows never acquire global authority.
                        selected = root; owned = false;
                        descriptor = new Window(window.getId(), packageName, display, bounds);
                    } catch (RuntimeException | Error failure) { rootFailure = failure; throw failure; }
                    finally {
                        // Identity work ends before disposal: a cleanup-only throw belongs to CLEANUP.
                        costs.end(cost, identity, rootFailure == null);
                        if (owned) releaseRoot(root, rootFailure, cost);
                    }
                }
            } catch (RuntimeException | Error failure) { filterNormal = false; throw failure; }
            finally { costs.end(cost, filter, filterNormal); }
            if (selected == null) return null;
            acquired.run(); // Full acquisition stays counted through this boundary and cleanup/transfer.
            if (isClosed()) return null;
            Read result = new Read(descriptor, selected);
            transferred = true;
            return result;
        } catch (RuntimeException | Error failure) { original = failure; throw failure; }
        finally {
            // Complete cleanup even if one disposal fails. Never report that failure as a clean drain.
            Throwable failure = recycleWindows(windows, cost);
            if (!transferred || failure != null) {
                try { if (selected != null) recycle(selected, cost); }
                catch (RuntimeException | Error thrown) { failure = combine(failure, thrown); }
            }
            finishCleanup(original, failure);
        }
    }

    @SuppressWarnings("deprecation")
    private DisplayMetrics display(WindowReadCost.Session cost) {
        WindowReadCost.Frame frame = costs.begin(cost, WindowReadCost.Stage.DISPLAY); boolean normal = true;
        try {
            WindowManager manager = service.getSystemService(WindowManager.class);
            if (manager == null) return null;
            DisplayMetrics display = new DisplayMetrics(); manager.getDefaultDisplay().getRealMetrics(display);
            return display.widthPixels <= 0 || display.heightPixels <= 0 ? null : display;
        } catch (RuntimeException | Error failure) { normal = false; throw failure; }
        finally { costs.end(cost, frame, normal); }
    }

    private List<AccessibilityWindowInfo> windowList(WindowReadCost.Session cost) {
        WindowReadCost.Frame frame = costs.begin(cost, WindowReadCost.Stage.WINDOW_LIST); boolean normal = true;
        try { return service.getWindows(); }
        catch (RuntimeException | Error failure) { normal = false; throw failure; }
        finally { costs.end(cost, frame, normal); }
    }

    private AccessibilityNodeInfo root(AccessibilityWindowInfo window, WindowReadCost.Session cost) {
        WindowReadCost.Frame frame = costs.begin(cost, WindowReadCost.Stage.ROOT); boolean normal = true;
        try { return window.getRoot(); }
        catch (RuntimeException | Error failure) { normal = false; throw failure; }
        finally { costs.end(cost, frame, normal); }
    }

    @SuppressWarnings("deprecation")
    private Throwable recycleWindows(List<AccessibilityWindowInfo> windows) {
        return recycleWindows(windows, null);
    }

    @SuppressWarnings("deprecation")
    private Throwable recycleWindows(List<AccessibilityWindowInfo> windows, WindowReadCost.Session cost) {
        Throwable result = null;
        if (windows != null) for (AccessibilityWindowInfo window : windows) {
            if (window == null) continue;
            WindowReadCost.Frame frame = costs.begin(cost, WindowReadCost.Stage.CLEANUP); boolean normal = true;
            try { window.recycle(); }
            catch (RuntimeException | Error failure) { normal = false; cleanupFailed.set(true); result = combine(result, failure); }
            finally { costs.end(cost, frame, normal); }
        }
        return result;
    }

    private void releaseRoot(AccessibilityNodeInfo root, Throwable original) {
        releaseRoot(root, original, null);
    }
    private void releaseRoot(AccessibilityNodeInfo root, Throwable original, WindowReadCost.Session cost) {
        try { recycle(root, cost); }
        catch (RuntimeException | Error failure) { finishCleanup(original, failure); }
    }
    private static Throwable combine(Throwable original, Throwable later) {
        if (original == null) return later;
        if (later != original) original.addSuppressed(later);
        return original;
    }
    private static void finishCleanup(Throwable original, Throwable cleanup) {
        if (cleanup == null) return;
        if (original != null) { combine(original, cleanup); return; }
        if (cleanup instanceof RuntimeException) throw (RuntimeException) cleanup;
        throw (Error) cleanup;
    }

    @SuppressWarnings("deprecation")
    private void recycle(AccessibilityNodeInfo root) {
        recycle(root, null);
    }

    @SuppressWarnings("deprecation")
    private void recycle(AccessibilityNodeInfo root, WindowReadCost.Session cost) {
        WindowReadCost.Frame frame = costs.begin(cost, WindowReadCost.Stage.CLEANUP); boolean normal = true;
        try { root.recycle(); }
        catch (RuntimeException | Error failure) { normal = false; cleanupFailed.set(true); throw failure; }
        finally { costs.end(cost, frame, normal); }
    }
}
