package com.subhub.app.censor.android;

import android.hardware.HardwareBuffer;
import android.hardware.DataSpace;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;

import com.subhub.app.capture.MediaProjectionLeaseRegistry;
import com.subhub.app.censor.engine.CensorEngine;
import com.subhub.app.censor.engine.PixelGeometry;
import com.subhub.app.censor.engine.ProjectionCaptureAdapter;
import com.subhub.app.censor.engine.ProjectionSourceSession;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * Unwired fixed-geometry API34+ source. The foreground host supplies its already-approved projection,
 * qualified full-display mapping, current exact engine scope and existing process-local lease.
 * Neither dimensions nor the registry enum authenticates consent, physical origin or a foreign host.
 * No consent request, registry acquisition/release, timer, old capture manager or cached-image fallback.
 */
public final class ProjectionCaptureSession implements ProjectionCaptureAdapter.TargetResolver<Image>, AutoCloseable {
    private final CensorEngine.ActiveScope scope;
    private final BooleanSupplier scopeCurrent;
    private final ProjectionSourceSession<Image> source;
    private final ProjectionCaptureAdapter.Target<Image> target;

    /**
     * Keep this SAME serial Handler alive until successful drain, or reconcile a failed owner before
     * releasing its host lease. Scope controls are exclusive to this engine and generation: current
     * must call its real engine authority; invalidate must not stop a replacement generation.
     * Call close on scope retirement even if no further engine capture request is expected.
     */
    public ProjectionCaptureSession(MediaProjection projection, MediaProjectionLeaseRegistry.Owner hostOwner,
            Handler producer, CensorEngine.ActiveScope scope, PixelGeometry geometry, int densityDpi,
            BooleanSupplier scopeCurrent, Runnable invalidateScope) {
        if (Build.VERSION.SDK_INT < 34) throw new UnsupportedOperationException("projection resize requires API34");
        Objects.requireNonNull(geometry); Objects.requireNonNull(producer);
        if (densityDpi <= 0 || geometry.left != 0 || geometry.top != 0 || geometry.width != geometry.displayWidth
                || geometry.height != geometry.displayHeight) throw new IllegalArgumentException("native projection geometry");
        this.scope = Objects.requireNonNull(scope); this.scopeCurrent = Objects.requireNonNull(scopeCurrent);
        AndroidEndpoint endpoint = new AndroidEndpoint(Objects.requireNonNull(projection), Objects.requireNonNull(hostOwner),
                producer, geometry, densityDpi);
        source = new ProjectionSourceSession<>(geometry.width, geometry.height, producer::post, endpoint,
                SystemClock::uptimeMillis, invalidateScope);
        target = new ProjectionCaptureAdapter.Target<>(scope, geometry, source.mailbox());
    }

    public void start() {
        if (!scopeCurrent.getAsBoolean()) throw new IllegalStateException("inactive projection scope");
        source.start();
    }
    @Override public void close() { source.close(); }
    public ProjectionSourceSession.State state() { return source.state(); }
    public boolean isDrained() { return source.isDrained(); }

    @Override public ProjectionCaptureAdapter.Target<Image> resolve(CensorEngine.CaptureRequest request) {
        if (request.epoch != scope.epoch || request.geometry != scope.geometry
                || !request.protectionScope.equals(scope.protectionScope) || !scopeCurrent.getAsBoolean()) return null;
        if (source.state().failed()) throw new ProjectionCaptureAdapter.ResourceFailure(new IllegalStateException("projection source failed"));
        return source.available() ? target : null;
    }

    private static final class AndroidEndpoint implements ProjectionSourceSession.Endpoint<Image> {
        private final MediaProjection projection;
        private final MediaProjectionLeaseRegistry.Owner hostOwner;
        private final Handler handler;
        private final PixelGeometry geometry;
        private final int densityDpi;
        private final AtomicBoolean projectionStopped = new AtomicBoolean();
        private final AtomicBoolean startRequested = new AtomicBoolean();
        private MediaProjection.Callback callback;
        private ImageReader reader;
        private VirtualDisplay display;

        AndroidEndpoint(MediaProjection projection, MediaProjectionLeaseRegistry.Owner owner,
                Handler handler, PixelGeometry geometry, int densityDpi) {
            this.projection = projection; hostOwner = owner; this.handler = handler;
            this.geometry = geometry; this.densityDpi = densityDpi;
        }
        private void requireHostLease() {
            if (MediaProjectionLeaseRegistry.owner() != hostOwner) throw new IllegalStateException("projection host lease changed");
        }
        @Override public void start(ProjectionSourceSession.Callbacks events) {
            if (Build.VERSION.SDK_INT < 34 || !startRequested.compareAndSet(false, true)) throw new IllegalStateException("single-use projection endpoint");
            requireHostLease();
            callback = new MediaProjection.Callback() {
                @Override public void onStop() { projectionStopped.set(true); events.revoked(); }
                @Override public void onCapturedContentResize(int width, int height) { events.resized(width, height); }
            };
            projection.registerCallback(callback, handler);
            reader = new ImageReader.Builder(geometry.width, geometry.height).setMaxImages(4)
                    .setDefaultHardwareBufferFormat(HardwareBuffer.RGBA_8888).setDefaultDataSpace(DataSpace.DATASPACE_SRGB)
                    .setUsage(HardwareBuffer.USAGE_CPU_READ_OFTEN | HardwareBuffer.USAGE_GPU_COLOR_OUTPUT).build();
            reader.setOnImageAvailableListener(ignored -> events.imageAvailable(), handler);
            if (projectionStopped.get()) throw new IllegalStateException("projection already revoked");
            display = projection.createVirtualDisplay("SubHub capture", geometry.width, geometry.height, densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, handler);
            if (display == null) throw new IllegalStateException("projection display absent");
        }
        @Override public ProjectionSourceSession.Acquired<Image> acquireLatest() {
            requireHostLease();
            Image image = reader.acquireLatestImage();
            if (image == null) return null;
            try { return new ProjectionSourceSession.Acquired<>(image, image.getTimestamp(), image::close); }
            catch (RuntimeException | Error failure) {
                try { image.close(); }
                catch (RuntimeException | Error cleanupFailure) { failure.addSuppressed(cleanupFailure); throw new ProjectionCaptureAdapter.ResourceFailure(failure); }
                throw failure;
            }
        }
        @Override public void detachImages() { if (reader != null) reader.setOnImageAvailableListener(null, null); }
        @Override public void releaseDisplay() { if (display != null) display.release(); }
        @Override public void closeReader() { if (reader != null) reader.close(); }
        @Override public void unregisterCallback() {
            // A failed registration call must not hide a partially installed exact callback.
            if (callback != null) { projection.unregisterCallback(callback); callback = null; }
        }
        @Override public void stopProjection() { if (projectionStopped.compareAndSet(false, true)) projection.stop(); }
    }
}
