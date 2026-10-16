package com.subhub.app.censor.android;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.SystemClock;
import android.view.Display;

import androidx.annotation.RequiresApi;

import com.subhub.app.censor.engine.CensorEngine;
import com.subhub.app.censor.engine.PixelGeometry;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Android API/copy adapter only. The engine owns deadlines, callback slots, jobs and copied pixels. */
@RequiresApi(Build.VERSION_CODES.R)
public final class AccessibilityCapturer implements CensorEngine.Capturer<PixelFrame> {
    public interface TargetResolver {
        /** Null rejects an ineligible/changed scope. Resolve again at callback; never mutate the scene. */
        Target resolve(CensorEngine.CaptureRequest request);
    }

    public interface Observer {
        default void sourcePrepared(CensorEngine.CaptureRequest request, PixelFrame frame) { }
        default void prepared(long requestId, PixelFrame.Kind kind, long nanos, int bytes) { }
        default void failed(long requestId, int platformCode) { }
    }

    public static final class Target {
        public final long epoch;
        public final long geometry;
        public final int windowId;
        public final String packageName;
        public final int displayWidth;
        public final int displayHeight;
        public final int left;
        public final int top;
        public final int width;
        public final int height;

        /** Window bounds must come from the chosen application window, not an event's text node. */
        public Target(long epoch, long geometry, int windowId, String packageName, int displayWidth,
                int displayHeight, int left, int top, int width, int height) {
            if (epoch < 0 || displayWidth <= 0 || displayHeight <= 0 || width <= 0 || height <= 0) {
                throw new IllegalArgumentException("capture target");
            }
            this.epoch = epoch;
            this.geometry = geometry;
            this.windowId = windowId;
            this.packageName = Objects.requireNonNull(packageName);
            this.displayWidth = displayWidth;
            this.displayHeight = displayHeight;
            this.left = left;
            this.top = top;
            this.width = width;
            this.height = height;
        }

        private boolean sameScope(Target other) {
            return other != null && epoch == other.epoch && geometry == other.geometry
                    && windowId == other.windowId && packageName.equals(other.packageName)
                    && displayWidth == other.displayWidth && displayHeight == other.displayHeight
                    && left == other.left && top == other.top && width == other.width && height == other.height;
        }
    }

    /** An API result owns its wrapped Bitmap and buffer separately. Neither is the copied source. */
    public static final class RawFrame implements AutoCloseable {
        public final Bitmap bitmap;
        public final long reportedUptimeMillis;
        private final Runnable releaseBuffer;
        private final AtomicInteger ownership = new AtomicInteger();

        public RawFrame(Bitmap bitmap, long reportedUptimeMillis, Runnable releaseBuffer) {
            this.bitmap = Objects.requireNonNull(bitmap);
            this.reportedUptimeMillis = reportedUptimeMillis;
            this.releaseBuffer = Objects.requireNonNull(releaseBuffer);
        }

        private boolean claim() { return ownership.compareAndSet(0, 1); }
        private void releaseOwned() { if (ownership.compareAndSet(1, 2)) dispose(); }
        private void dispose() {
            try { if (!bitmap.isRecycled()) bitmap.recycle(); }
            finally { releaseBuffer.run(); }
        }
        @Override public void close() { if (ownership.compareAndSet(0, 2)) dispose(); }
    }

    public interface RawCallback {
        void success(RawFrame frame);
        void failure(int errorCode);
        void resourceFailure();
    }

    public interface Platform {
        void window(int windowId, Executor executor, RawCallback callback);
        void display(Executor executor, RawCallback callback);
    }

    private final Platform platform;
    private final int sdkInt;
    private final Executor callbackExecutor;
    private final TargetResolver targets;
    private final Observer observer;

    public AccessibilityCapturer(AccessibilityService service, Executor callbackExecutor,
            TargetResolver targets, Observer observer) {
        this(new ServicePlatform(service), Build.VERSION.SDK_INT, callbackExecutor, targets, observer);
    }

    /** Explicit platform boundary for native contract fixtures, not a claim of bound-service proof. */
    public AccessibilityCapturer(Platform platform, int sdkInt, Executor callbackExecutor,
            TargetResolver targets, Observer observer) {
        this.platform = Objects.requireNonNull(platform);
        this.sdkInt = sdkInt;
        this.callbackExecutor = Objects.requireNonNull(callbackExecutor);
        this.targets = Objects.requireNonNull(targets);
        this.observer = Objects.requireNonNull(observer);
    }

    @Override public void capture(CensorEngine.CaptureRequest request, CensorEngine.CaptureCallback<PixelFrame> callback) {
        Target target = targets.resolve(request);
        if (target == null || target.epoch != request.epoch || target.geometry != request.geometry
                || sdkInt < 30) { callback.failure(0); return; }
        boolean window = sdkInt >= 34 && target.windowId >= 0;
        AtomicBoolean terminal = new AtomicBoolean();
        RawCallback rawCallback = new RawCallback() {
            @Override public void success(RawFrame raw) {
                long callbackTime = SystemClock.uptimeMillis();
                if (raw == null) { failure(-1); return; }
                if (!raw.claim()) return; // A duplicate object cannot release another callback's copy input.
                if (!terminal.compareAndSet(false, true)) {
                    try { raw.releaseOwned(); }
                    catch (RuntimeException cleanupFailure) { callback.resourceFailure(); }
                    return;
                }
                Bitmap readable = null;
                PixelFrame frame = null;
                boolean cleanupFailed = false;
                try {
                    if (target.sameScope(targets.resolve(request))) {
                        PixelGeometry geometry = window
                                ? PixelGeometry.window(target.displayWidth, target.displayHeight, target.left,
                                        target.top, target.width, target.height, raw.bitmap.getWidth(), raw.bitmap.getHeight())
                                : PixelGeometry.display(target.displayWidth, target.displayHeight,
                                        raw.bitmap.getWidth(), raw.bitmap.getHeight());
                        if (geometry != null) {
                            long started = System.nanoTime();
                            readable = raw.bitmap.copy(Bitmap.Config.ARGB_8888, false);
                            if (readable != null) frame = new PixelFrame(readable, geometry,
                                    window ? PixelFrame.Kind.ACCESSIBILITY_WINDOW : PixelFrame.Kind.ACCESSIBILITY_DISPLAY,
                                    raw.reportedUptimeMillis, callbackTime, System.nanoTime() - started);
                        }
                    }
                } catch (RuntimeException preparationFailure) {
                    frame = null;
                } finally {
                    try { raw.releaseOwned(); }
                    catch (RuntimeException cleanupFailure) { cleanupFailed = true; }
                }
                // Equal dimensions do not fence same-sized window movement during a copy/cleanup.
                try { if (frame != null && !target.sameScope(targets.resolve(request))) frame = null; }
                catch (RuntimeException scopeFailure) { frame = null; }
                if (cleanupFailed || frame == null) {
                    try { if (readable != null && !readable.isRecycled()) readable.recycle(); }
                    catch (RuntimeException cleanupFailure) { cleanupFailed = true; }
                    if (cleanupFailed) callback.resourceFailure(); else callback.failure(0);
                    return;
                }
                PixelFrame accepted = frame;
                CensorEngine.Captured<PixelFrame> payload = new CensorEngine.Captured<>(accepted,
                        accepted.geometry.displayWidth, accepted.geometry.displayHeight, -1, accepted.bitmap::recycle);
                try {
                    try { observer.sourcePrepared(request, accepted); }
                    catch (RuntimeException observerFailure) { /* Presentation metadata cannot strand pixels. */ }
                    try { observer.prepared(request.identity, accepted.kind, accepted.preparationNanos,
                            accepted.bitmap.getAllocationByteCount()); }
                    catch (RuntimeException observerFailure) { /* Diagnostics cannot strand capture ownership. */ }
                    callback.success(payload);
                } finally { payload.close(); } // Only untransferred copies can be discarded here.
            }

            @Override public void failure(int errorCode) {
                if (!terminal.compareAndSet(false, true)) return;
                try { observer.failed(request.identity, errorCode); }
                catch (RuntimeException observerFailure) { /* Keep the terminal callback unconditional. */ }
                callback.failure(0);
            }

            @Override public void resourceFailure() {
                terminal.set(true);
                callback.resourceFailure();
            }
        };
        if (!request.tryDispatch()) return;
        if (window) platform.window(target.windowId, callbackExecutor, rawCallback);
        else platform.display(callbackExecutor, rawCallback);
    }

    private static final class ServicePlatform implements Platform {
        private final AccessibilityService service;
        ServicePlatform(AccessibilityService service) { this.service = Objects.requireNonNull(service); }

        @Override public void window(int windowId, Executor executor, RawCallback callback) {
            if (Build.VERSION.SDK_INT < 34) throw new IllegalStateException("window screenshots require Android 14");
            service.takeScreenshotOfWindow(windowId, executor, wrap(callback));
        }

        @Override public void display(Executor executor, RawCallback callback) {
            service.takeScreenshot(Display.DEFAULT_DISPLAY, executor, wrap(callback));
        }

        private AccessibilityService.TakeScreenshotCallback wrap(RawCallback callback) {
            return new AccessibilityService.TakeScreenshotCallback() {
                @Override public void onSuccess(AccessibilityService.ScreenshotResult result) {
                    HardwareBuffer buffer = result.getHardwareBuffer();
                    Bitmap wrapped = null;
                    try { wrapped = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace()); }
                    catch (RuntimeException wrappingFailure) { /* Release buffer before terminal failure. */ }
                    if (wrapped == null) {
                        try { buffer.close(); }
                        catch (RuntimeException cleanupFailure) { callback.resourceFailure(); return; }
                        callback.failure(-1);
                    } else callback.success(new RawFrame(wrapped, result.getTimestamp(), buffer::close));
                }
                @Override public void onFailure(int errorCode) { callback.failure(errorCode); }
            };
        }
    }
}
