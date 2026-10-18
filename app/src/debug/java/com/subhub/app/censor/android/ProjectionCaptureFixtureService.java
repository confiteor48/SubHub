package com.subhub.app.censor.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.media.Image;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.view.WindowManager;

import com.subhub.app.capture.MediaProjectionLeaseRegistry;
import com.subhub.app.censor.engine.CensorEngine;
import com.subhub.app.censor.engine.PixelGeometry;
import com.subhub.app.censor.engine.ProjectionCaptureAdapter;
import com.subhub.app.censor.engine.ProjectionSourceSession;
import com.subhub.app.censor.engine.ProtectionScope;
import com.subhub.app.censor.engine.SceneEngine;

import java.util.Collections;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Debug-only, bounded full-display source host. No recorder, legacy capture or inference model. */
public final class ProjectionCaptureFixtureService extends Service {
    private static final String CHANNEL = "projection-source-fixture";
    static volatile Session latest;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Session owned;
    private boolean attempted;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        // A second command cannot consume a grant or release the first source's lease.
        if (attempted) return START_NOT_STICKY;
        attempted = true;
        if (android.os.Build.VERSION.SDK_INT < 34 || intent == null) { stopSelf(); return START_NOT_STICKY; }
        String runId = intent.getStringExtra("runId");
        Intent grant = intent.getParcelableExtra("grant", Intent.class);
        int result = intent.getIntExtra("resultCode", 0);
        if (runId == null || grant == null || result != android.app.Activity.RESULT_OK) {
            stopSelf(); return START_NOT_STICKY;
        }
        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel(CHANNEL, "Source test", NotificationManager.IMPORTANCE_LOW));
        startForeground(971, new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_lock_lock).setContentTitle("Screen capture test")
                .setOngoing(true).build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        if (!MediaProjectionLeaseRegistry.acquire(MediaProjectionLeaseRegistry.Owner.CENSOR_LAB)) {
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY;
        }
        owned = new Session(runId);
        latest = owned;
        try {
            // Foreground type and exclusive lease are established BEFORE getMediaProjection.
            owned.projection = getSystemService(MediaProjectionManager.class).getMediaProjection(result, grant);
            if (owned.projection == null) throw new IllegalStateException("projection absent");
            owned.start();
        } catch (RuntimeException failure) {
            owned.failure = failure.getClass().getSimpleName();
            owned.stop();
        }
        main.postDelayed(owned::stop, 25_000);
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        if (android.os.Build.VERSION.SDK_INT >= 34 && owned != null) owned.stop();
        super.onDestroy();
    }

    public static final class Witness {
        private final Rect bounds;
        final int uid;
        private final int[] x = new int[3], y = new int[3];
        Rect bounds() { return new Rect(bounds); }
        Witness(Rect bounds, int uid, int displayWidth, int displayHeight) {
            if (bounds.left < 0 || bounds.top < 0 || bounds.right > displayWidth || bounds.bottom > displayHeight
                    || bounds.width() <= 32 || bounds.height() <= 32) throw new IllegalArgumentException("witness bounds");
            this.bounds = new Rect(bounds); this.uid = uid;
            int[] parts = {1,3,2};
            for (int i = 0; i < 3; i++) {
                x[i] = bounds.left + bounds.width() * parts[i] / 4;
                y[i] = bounds.top + bounds.height() * parts[i] / 4;
            }
        }
    }

    @androidx.annotation.RequiresApi(34)
    public static final class Sample {
        final long sequence, timestamp, arrival, pixelAt, copyEntry, copyReturn;
        final int width, height, top, bottom, overlay, format, dataspace, copyAttempt, bufferLeft, bufferTop;
        final Witness witness;
        final PixelFrame.Kind kind;
        Sample(CensorEngine.Captured<PixelFrame> copied, Image raw, int attempt, long entry, long returned, Witness witness) {
            PixelFrame frame = copied.pixels; Bitmap bitmap = frame.bitmap;
            sequence = frame.acquisitionSequence; timestamp = frame.reportedTimestampNanos;
            copyAttempt = attempt; copyEntry = entry; copyReturn = returned;
            arrival = frame.callbackUptimeMillis; pixelAt = copied.pixelAtMillis;
            width = bitmap.getWidth(); height = bitmap.getHeight(); kind = frame.kind;
            bufferLeft = frame.geometry.left; bufferTop = frame.geometry.top; this.witness = witness;
            format = raw.getFormat(); dataspace = raw.getDataSpace();
            top = bitmap.getPixel(witness == null ? width / 4 : witness.x[0], witness == null ? height / 4 : witness.y[0]);
            bottom = bitmap.getPixel(witness == null ? width * 3 / 4 : witness.x[1], witness == null ? height * 3 / 4 : witness.y[1]);
            overlay = bitmap.getPixel(witness == null ? width / 2 : witness.x[2], witness == null ? height / 2 : witness.y[2]);
        }
    }

    public static final class Hold {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        volatile boolean readableAfterFence;
        public void release() { release.countDown(); }
    }

    /** Debug observer borrows the copied bitmap synchronously; no Image/Bitmap may escape. */
    interface CopiedObserver { void copied(Sample sample, Bitmap pixels); }

    @androidx.annotation.RequiresApi(34)
    public final class Session {
        final String runId;
        final HandlerThread producer = new HandlerThread("projection-fixture-source");
        final ExecutorService copies = Executors.newSingleThreadExecutor(r -> new Thread(r, "projection-fixture-copy"));
        final AtomicReference<Hold> nextHold = new AtomicReference<>();
        volatile MediaProjection projection;
        volatile ProjectionCaptureSession source;
        volatile CensorEngine<PixelFrame> engine;
        volatile CensorEngine.ActiveScope scope;
        volatile Sample sample, firstSample;
        volatile Witness witness;
        volatile SceneEngine.Region syntheticMask;
        volatile Runnable presentationChanged = () -> { };
        volatile CopiedObserver copiedObserver;
        volatile String rawObservation, copyRefusal;
        volatile long copyStartedAt = -1, copyReturnedAt = -1;
        volatile int requests, dispatches;
        private int copyAttempts;
        volatile String failure;
        volatile boolean stopped, closed, engineDrained;
        volatile int delivered;
        volatile long invalidatedAt = -1;
        private boolean shutdown;
        private ProjectionImageCopier copier;
        private long drainDeadline;
        int width, height;

        Session(String runId) { this.runId = runId; }

        void start() {
            Rect bounds = getSystemService(WindowManager.class).getMaximumWindowMetrics().getBounds();
            if (bounds.left != 0 || bounds.top != 0) throw new IllegalStateException("display origin");
            width = bounds.width(); height = bounds.height();
            PixelGeometry geometry = PixelGeometry.display(width, height, width, height);
            producer.start();
            copier = new ProjectionImageCopier(32 * 1024 * 1024, 64L * 1024 * 1024);
            ProjectionCaptureAdapter<Image, PixelFrame> adapter = new ProjectionCaptureAdapter<>(
                    request -> source == null ? null : source.resolve(request), (target, raw) -> {
                observeRaw(raw.raw());
                Hold hold = nextHold.getAndSet(null);
                if (hold != null) {
                    hold.entered.countDown();
                    try {
                        if (!hold.release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("hold expired");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted);
                    }
                    // Actual raw is still readable after the producer fence; this is not a fake Image.
                    hold.readableAfterFence = raw.raw().getWidth() == width && raw.raw().getPlanes().length == 1;
                }
                CensorEngine.Captured<PixelFrame> copied;
                int attempt = ++copyAttempts;
                copyStartedAt = SystemClock.uptimeMillis(); copyReturnedAt = -1;
                try { copied = copier.copy(target, raw); }
                catch (RuntimeException failure) {
                    copyRefusal = failure.getClass().getSimpleName() + ":" + failure.getMessage();
                    throw failure;
                } finally { copyReturnedAt = SystemClock.uptimeMillis(); }
                try {
                    Sample observed = new Sample(copied, raw.raw(), attempt, copyStartedAt, copyReturnedAt, witness);
                    CopiedObserver probe = copiedObserver;
                    if (probe != null) probe.copied(observed, copied.pixels.bitmap);
                    if (firstSample == null) firstSample = observed;
                    sample = observed;
                }
                catch (RuntimeException | Error failure) { copied.close(); throw failure; }
                return copied;
            });
            engine = new CensorEngine<>(new CensorEngine.Policy(80, 40, 3, 0, false),
                    new SceneEngine.Policy(16, 100, 64, 256, 5000, 20000, .5), SystemClock::uptimeMillis,
                    (action, delay) -> {
                        // Dispatch/admission happens inside the capturer call, on this worker.
                        // Queuing the adapter after that call returned violates the omission fence.
                        Runnable due = () -> copies.execute(action);
                        if (!main.postDelayed(due, delay)) throw new IllegalStateException("timer rejected");
                        return () -> main.removeCallbacks(due);
                    }, adapter,
                    Collections.singletonList(new CensorEngine.LaneWorker<>(SceneEngine.Lane.FAST, Runnable::run,
                            (input, completion) -> {
                                delivered++;
                                SceneEngine.Region region = syntheticMask;
                                if (region == null) completion.cancelled();
                                else completion.success(new SceneEngine.Batch(input.source, SceneEngine.Lane.FAST,
                                        Collections.singletonList(new SceneEngine.Observation("FIXTURE", 1, 0, region, .99f)),
                                        Collections.emptyList()), new CensorEngine.WorkTiming(0, 0, 0));
                            })),
                    Runnable::run, new CensorEngine.Observer() {
                        @Override public void trace(CensorEngine.Trace trace) {
                            if (trace.stage == CensorEngine.Stage.CAPTURE_REQUESTED) requests++;
                            if (trace.stage == CensorEngine.Stage.CAPTURE_DISPATCHED) dispatches++;
                        }
                        @Override public void presentationChanged() { presentationChanged.run(); }
                        @Override public void drained() { engineDrained = true; }
                    });
            engine.start(1, ProtectionScope.lab(1));
            scope = engine.activeScope();
            source = new ProjectionCaptureSession(projection, MediaProjectionLeaseRegistry.Owner.CENSOR_LAB,
                    new Handler(producer.getLooper()), scope, geometry, getResources().getDisplayMetrics().densityDpi,
                    () -> engine.isCurrent(scope), () -> {
                        if (engine.isCurrent(scope)) engine.stop();
                        invalidatedAt = SystemClock.uptimeMillis();
                        main.post(this::stop);
                    });
            source.start();
        }

        private void observeRaw(Image image) {
            String observed = "width=" + image.getWidth() + " height=" + image.getHeight()
                    + " format=" + image.getFormat() + " dataspace=" + image.getDataSpace()
                    + " crop=" + image.getCropRect();
            try {
                Image.Plane[] planes = image.getPlanes();
                observed += " planes=" + planes.length;
                if (planes.length == 1) {
                    Image.Plane plane = planes[0];
                    ByteBuffer bytes = plane.getBuffer().duplicate();
                    int row = plane.getRowStride(), pixel = plane.getPixelStride(), origin = bytes.position();
                    observed += " rowStride=" + row + " pixelStride=" + pixel + " remaining=" + bytes.remaining();
                    int[] fractions = {1, 3, 2};
                    for (int fraction : fractions) {
                        long offset = origin + (long) (image.getHeight() * fraction / 4) * row
                                + (long) (image.getWidth() * fraction / 4) * pixel;
                        if (pixel != 4 || offset < origin || offset + 4 > bytes.limit()) {
                            observed += " rgba=unavailable"; break;
                        }
                        int position = (int) offset;
                        int rgba = ((bytes.get(position) & 255) << 24) | ((bytes.get(position + 1) & 255) << 16)
                                | ((bytes.get(position + 2) & 255) << 8) | (bytes.get(position + 3) & 255);
                        observed += " rgba=" + Integer.toHexString(rgba);
                    }
                }
            } catch (RuntimeException diagnosticFailure) {
                observed += " diagnosticFailure=" + diagnosticFailure.getClass().getSimpleName();
            }
            rawObservation = observed; // Scalars only; no raw resource or buffer is retained.
        }

        public Hold holdNextCopy() {
            Hold hold = new Hold();
            if (!nextHold.compareAndSet(null, hold)) throw new IllegalStateException("hold already armed");
            return hold;
        }

        public void stop() {
            if (Looper.myLooper() != Looper.getMainLooper()) { main.post(this::stop); return; }
            if (stopped) return;
            stopped = true; drainDeadline = SystemClock.uptimeMillis() + 7_000;
            if (engine != null) engine.close();
            if (source != null) source.close();
            else {
                if (projection != null) projection.stop();
                engineDrained = engine == null || engineDrained;
            }
            main.post(this::drain);
        }

        private void drain() {
            boolean sourceDrained = source == null || source.isDrained();
            if (sourceDrained && engineDrained && !shutdown) {
                shutdown = true;
                copies.shutdown();
                producer.quitSafely();
            }
            if (shutdown && copies.isTerminated() && !producer.isAlive()) {
                copier = null; // Drop the generation's optional row scratch after every copy is terminal.
                closed = true;
                MediaProjectionLeaseRegistry.release(MediaProjectionLeaseRegistry.Owner.CENSOR_LAB);
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
                return;
            }
            if (SystemClock.uptimeMillis() >= drainDeadline) {
                if (failure == null) failure = "source-drain-incomplete";
                // Keep ownership/lease: a posted close or failed cleanup is not successful drain.
                return;
            }
            main.postDelayed(this::drain, 20);
        }

        ProjectionSourceSession.State state() { return source == null ? null : source.state(); }
    }
}
