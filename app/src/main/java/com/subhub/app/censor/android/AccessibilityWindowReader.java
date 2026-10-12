package com.subhub.app.censor.android;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import androidx.annotation.RequiresApi;

import com.subhub.app.detection.text.NativeTextReader;

import java.util.List;
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
    private boolean closed;
    private int activeReads;

    public AccessibilityWindowReader(AccessibilityService service) { this(service, () -> { }); }
    /** Adverse native control after actual root acquisition, never a substitute platform reader. */
    AccessibilityWindowReader(AccessibilityService service, Runnable acquired) {
        this.service = Objects.requireNonNull(service); this.acquired = Objects.requireNonNull(acquired);
    }

    /** Negative windowId selects one uniquely focused application window of the requested package. */
    public Window observe(String packageName, int windowId) {
        if (!beginRead()) return null;
        try {
            Read read = read(packageName, windowId);
            if (read == null) return null;
            try { return read.window; }
            finally { recycle(read.root); }
        } finally { endRead(); }
    }

    /** Transfers only the actual returned root. NativeTextReader owns it through read/confirmation cleanup. */
    public NativeTextReader.Tree open(Window expected, long contentGeneration) {
        Objects.requireNonNull(expected);
        if (!beginRead()) return null;
        try {
            Read read = read(expected.packageName, expected.id);
            if (read == null) return null;
            boolean transferred = false;
            try {
                if (isClosed() || !expected.same(read.window)) return null;
                NativeTextReader.Tree tree = new NativeTextReader.Tree(read.root,
                        expected.displayWidth, expected.displayHeight, contentGeneration);
                transferred = true;
                return tree;
            } finally { if (!transferred) recycle(read.root); }
        } finally { endRead(); }
    }

    public boolean current(Window expected) {
        return expected != null && expected.same(observe(expected.packageName, expected.id));
    }

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
    private Read read(String packageName, int windowId) {
        if (packageName == null || packageName.isEmpty() || packageName.length() > 256) {
            throw new IllegalArgumentException("Window package");
        }
        WindowManager manager = service.getSystemService(WindowManager.class);
        if (manager == null) return null;
        DisplayMetrics display = new DisplayMetrics();
        manager.getDefaultDisplay().getRealMetrics(display);
        if (display.widthPixels <= 0 || display.heightPixels <= 0) return null;
        List<AccessibilityWindowInfo> windows = service.getWindows();
        if (windows == null) return null;
        AccessibilityNodeInfo selected = null;
        Window descriptor = null;
        boolean transferred = false;
        try {
            if (windows.size() > MAXIMUM_WINDOWS) return null; // No successful subset of a truncated scope read.
            for (AccessibilityWindowInfo window : windows) {
                if (window == null) throw new IllegalStateException("Incomplete window list");
                if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION
                        || !(window.isActive() || window.isFocused())
                        || (windowId >= 0 && window.getId() != windowId)) continue;
                Rect bounds = new Rect(); window.getBoundsInScreen(bounds);
                if (window.getId() < 0 || bounds.isEmpty() || bounds.left < 0 || bounds.top < 0
                        || bounds.right > display.widthPixels || bounds.bottom > display.heightPixels) continue;
                AccessibilityNodeInfo root = window.getRoot();
                if (root == null) continue;
                boolean owned = true;
                try {
                    CharSequence name = root.getPackageName();
                    if (name == null || !packageName.contentEquals(name) || root.getWindowId() != window.getId()) continue;
                    if (selected != null) return null; // Ambiguous application windows never acquire global authority.
                    selected = root; owned = false;
                    descriptor = new Window(window.getId(), packageName, display, bounds);
                } finally { if (owned) recycle(root); }
            }
            if (selected == null) return null;
            acquired.run(); // Full acquisition stays counted through this boundary and cleanup/transfer.
            if (isClosed()) return null;
            Read result = new Read(descriptor, selected);
            transferred = true;
            return result;
        } finally {
            // Complete cleanup even if one disposal fails. Never report that failure as a clean drain.
            Throwable failure = null;
            for (AccessibilityWindowInfo window : windows) {
                if (window == null) continue;
                try { window.recycle(); }
                catch (RuntimeException | Error thrown) { cleanupFailed.set(true); if (failure == null) failure = thrown; }
            }
            if (!transferred || failure != null) {
                try { if (selected != null) recycle(selected); }
                catch (RuntimeException | Error thrown) { if (failure == null) failure = thrown; }
            }
            if (failure instanceof RuntimeException) throw (RuntimeException) failure;
            if (failure instanceof Error) throw (Error) failure;
        }
    }

    @SuppressWarnings("deprecation")
    private void recycle(AccessibilityNodeInfo root) {
        try { root.recycle(); }
        catch (RuntimeException | Error failure) { cleanupFailed.set(true); throw failure; }
    }
}
