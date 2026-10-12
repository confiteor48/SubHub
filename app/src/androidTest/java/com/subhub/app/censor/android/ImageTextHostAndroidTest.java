package com.subhub.app.censor.android;

import static org.junit.Assert.*;

import android.content.Context;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Looper;
import android.os.Handler;
import android.os.SystemClock;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.TaskCompletionSource;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.subhub.app.censor.engine.CensorEngine;
import com.subhub.app.censor.engine.PixelGeometry;
import com.subhub.app.censor.engine.ProtectionScope;
import com.subhub.app.censor.engine.SceneEngine;
import com.subhub.app.detection.DetectorConfig;
import com.subhub.app.detection.text.ImageTextLayout;
import com.subhub.app.detection.text.SmutTextClassifier;
import com.subhub.app.detection.text.TextSmutConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.EnumSet;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;
import org.junit.runner.RunWith;

/** Actual host/SDK and explicit adverse SDK boundaries. Not a bound service or Pixel benchmark. */
@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion = 30)
public final class ImageTextHostAndroidTest {
    private static void main(Runnable action) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try { action.run(); } catch (Throwable thrown) { failure.set(thrown); }
        });
        if (failure.get() instanceof RuntimeException) throw (RuntimeException) failure.get();
        if (failure.get() instanceof Error) throw (Error) failure.get();
        if (failure.get() != null) throw new AssertionError("Main fixture action failed", failure.get());
    }
    private static List<SceneEngine.Mask> masks(CensorEngine<PixelFrame>.RenderView view) {
        List<SceneEngine.Mask> output = new ArrayList<>();
        for (SceneEngine.Group group : view.snapshot.groups) output.addAll(group.members);
        return output;
    }

    @Test public void bundledSdkPublishesTextOnlyOnItsOwnWindowSourceWithoutVisualInitialization() throws Exception {
        try (Harness h = new Harness(null)) {
            h.start(4, false); Call call = h.next();
            Bitmap source = raster("porn", 800, 320, 72);
            PixelGeometry geometry = PixelGeometry.window(1200, 1800, 150, 400, 800, 320, 800, 320);
            call.deliver(source, geometry);
            SceneEngine.Batch batch = h.accepted();
            assertEquals(SceneEngine.Lane.TEXT, batch.lane); assertEquals(Long.valueOf(3), batch.replacementTextPartition);
            assertFalse(batch.observations.isEmpty()); assertEquals(0, h.visualInitializations.get());
            assertEquals(1, h.ocrInitializations.get()); assertFalse(h.initializedOnMain.get());
            try (CensorEngine<PixelFrame>.RenderView view = h.view(batch.source.sequence)) {
                assertEquals(batch.source.sequence, view.snapshot.destination.sequence);
                assertSame(source, view.sourcePixels(batch.source.sequence).bitmap);
                for (SceneEngine.Mask mask : masks(view)) {
                    assertEquals(3, mask.partition); assertFalse(mask.nativeValidated); assertFalse(mask.estimatedMotion);
                    assertTrue("Actual OCR masks must use text padding/effect policy", PreparedCensorPaint.isText(mask));
                    assertTrue(mask.category.startsWith("TEXT_SMUT_OCR_"));
                    assertSame(batch.source, mask.evidenceSource); assertSame(batch.source, mask.geometrySource);
                    assertTrue(geometry.coverage().contains(mask.displayRegion));
                    assertTrue(mask.displayRegion.left >= 150); assertTrue(mask.displayRegion.top >= 400);
                }
                save(h.context, source, "image-ocr-source");
                Bitmap composed = Bitmap.createBitmap(1200, 1800, Bitmap.Config.ARGB_8888);
                try {
                    Canvas canvas = new Canvas(composed); canvas.drawColor(Color.WHITE); canvas.drawBitmap(source, 150, 400, null);
                    Paint maskPaint = new Paint(); maskPaint.setColor(Color.BLACK);
                    for (SceneEngine.Mask mask : masks(view)) canvas.drawRect(new RectF((float) mask.displayRegion.left,
                            (float) mask.displayRegion.top, (float) mask.displayRegion.right, (float) mask.displayRegion.bottom), maskPaint);
                    save(h.context, composed, "image-ocr-regions");
                } finally { composed.recycle(); }
            }
        }
    }

    @Test public void bundledSdkResizedSemanticReadUsesSelectedSettingAndOriginalBufferMapping() throws Exception {
        try (Harness h = new Harness(null)) {
            String caption = "I want to feel your body pressed against mine tonight";
            PixelGeometry geometry = PixelGeometry.window(1600, 2000, 100, 500, 1400, 280, 1400, 280);
            h.start(4, false); h.next().deliver(raster(caption, 1400, 280, 48), geometry);
            assertTrue("semantic disabled must retain lexical-only classifier behavior", h.accepted().observations.isEmpty());
            main(() -> h.host.changeScope(11, config(4), false, text(), true, false));
            h.next(11).deliver(raster(caption, 1400, 280, 48), geometry);
            SceneEngine.Batch batch = h.accepted(); assertEquals(11, batch.source.geometry);
            assertFalse("actual bundled OCR must feed the selected semantic classifier", batch.observations.isEmpty());
            assertEquals(1, h.ocrInitializations.get()); assertEquals(0, h.visualInitializations.get());
            try (CensorEngine<PixelFrame>.RenderView view = h.view(batch.source.sequence)) {
                for (SceneEngine.Mask mask : masks(view)) {
                    assertTrue(geometry.coverage().contains(mask.displayRegion)); assertTrue(mask.displayRegion.top >= 500);
                    assertFalse(mask.estimatedMotion); assertEquals(batch.source.sequence, mask.geometrySource.sequence);
                }
            }
        }
    }

    @Test public void lowerPresetWithoutNativeProviderRejectsBeforeAcquisitionOrScreenshotOcr() throws Exception {
        try (Harness h = new Harness(new FakeRecognizer())) {
            assertThrows(IllegalStateException.class, () -> h.start(3, false));
            assertNull(h.requests.poll(150, TimeUnit.MILLISECONDS));
            assertEquals(0, h.ocrInitializations.get()); assertEquals(0, h.visualInitializations.get());
        }
    }

    @Test public void cancellationAndCloseWaitForActualSdkTerminalWithoutFreeingEitherBitmap() throws Exception {
        FakeRecognizer recognizer = new FakeRecognizer();
        try (Harness h = new Harness(recognizer)) {
            h.start(4, false); Bitmap original = raster("porn", 400, 240, 64);
            h.next().deliver(original, PixelGeometry.display(400, 240, 400, 240));
            Operation operation = recognizer.next(); assertNotSame(original, operation.raster);
            assertFalse(operation.raster.isRecycled());
            main(h.host::close); h.failOutstanding();
            assertFalse("cancel signal is not SDK terminal", h.terminated.await(120, TimeUnit.MILLISECONDS));
            assertFalse(original.isRecycled()); assertFalse(operation.raster.isRecycled());
            operation.task.setResult(rows("porn", 10, 30));
            assertTrue(h.terminated.await(5, TimeUnit.SECONDS));
            assertTrue(original.isRecycled()); assertTrue(operation.raster.isRecycled()); assertTrue(h.batches.isEmpty());
            assertEquals(1, recognizer.closes.get()); assertFalse(recognizer.closedOnMain.get());
        }
    }

    @Test public void sdkTerminalWhileMappingHeldStillOwnsInputAndRejectsLateProtectedScopeResult() throws Exception {
        FakeRecognizer recognizer = new FakeRecognizer(); recognizer.blockMapping = true;
        try (Harness h = new Harness(recognizer)) {
            h.start(4, false); Bitmap original = raster("porn", 400, 240, 64);
            h.next().deliver(original, PixelGeometry.display(400, 240, 400, 240));
            Operation operation = recognizer.next(); operation.task.setResult(rows("porn", 10, 30));
            assertTrue(recognizer.mappingEntered.await(5, TimeUnit.SECONDS));
            main(() -> h.host.changeProtectionScope(ProtectionScope.normal(77)));
            main(h.host::close); h.failOutstanding();
            assertFalse(h.terminated.await(120, TimeUnit.MILLISECONDS));
            assertFalse(original.isRecycled()); assertFalse(operation.raster.isRecycled());
            recognizer.mappingRelease.countDown(); assertTrue(h.terminated.await(5, TimeUnit.SECONDS));
            assertTrue(original.isRecycled()); assertTrue(operation.raster.isRecycled()); assertTrue(h.batches.isEmpty());
        }
    }

    @Test public void interruptCannotRetireAnExecutingSdkJobOrAddAnotherInput() throws Exception {
        FakeRecognizer recognizer = new FakeRecognizer();
        try (Harness h = new Harness(recognizer)) {
            h.start(4, false); Bitmap original = raster("porn", 400, 240, 64);
            h.next().deliver(original, PixelGeometry.display(400, 240, 400, 240)); Operation operation = recognizer.next();
            operation.thread.interrupt();
            Bitmap pending = raster("ordinary", 400, 240, 48);
            h.next().deliver(pending, PixelGeometry.display(400, 240, 400, 240)); h.next();
            assertEquals(1, recognizer.processes.get()); assertFalse(original.isRecycled()); assertFalse(operation.raster.isRecycled());
            main(h.host::close); h.failOutstanding(); operation.task.setResult(Collections.emptyList());
            assertTrue(h.terminated.await(5, TimeUnit.SECONDS)); assertTrue(original.isRecycled()); assertTrue(pending.isRecycled());
            assertTrue(operation.raster.isRecycled()); assertEquals(1, recognizer.processes.get());
        }
    }

    @Test public void failedAndMalformedReadsPreserveOldSetWhileCompleteEmptyReplacesIt() throws Exception {
        FakeRecognizer recognizer = new FakeRecognizer();
        try (Harness h = new Harness(recognizer)) {
            h.start(4, false); h.next().deliver(raster("porn", 400, 240, 64), PixelGeometry.display(400, 240, 400, 240));
            recognizer.next().task.setResult(rows("porn", 10, 30)); SceneEngine.Batch a = h.accepted();
            h.next().deliver(raster("ordinary", 400, 240, 48), PixelGeometry.display(400, 240, 400, 240));
            Operation failed = recognizer.next(); failed.task.setException(new IllegalStateException("synthetic SDK failure"));
            h.awaitTerminal(a.source.sequence + 1); assertTrue(h.batches.isEmpty());
            try (CensorEngine<PixelFrame>.RenderView view = h.view(a.source.sequence)) { assertEquals(1, masks(view).size()); }
            h.next().deliver(raster("ordinary", 400, 240, 48), PixelGeometry.display(400, 240, 400, 240));
            Operation malformed = recognizer.next(); malformed.task.setResult(Collections.singletonList(
                    Collections.nCopies(257, new ImageTextLayout.Line("ordinary", new SceneEngine.Region(0, 0, 20, 20)))));
            h.awaitTerminal(a.source.sequence + 2); assertTrue(h.batches.isEmpty());
            try (CensorEngine<PixelFrame>.RenderView view = h.view(a.source.sequence)) { assertEquals(1, masks(view).size()); }
            h.next().deliver(raster("ordinary", 400, 240, 48), PixelGeometry.display(400, 240, 400, 240));
            recognizer.next().task.setResult(Collections.emptyList()); SceneEngine.Batch empty = h.accepted();
            assertTrue(empty.observations.isEmpty());
            try (CensorEngine<PixelFrame>.RenderView view = h.view(empty.source.sequence)) { assertTrue(masks(view).isEmpty()); }
            assertTrue(failed.raster.isRecycled()); assertTrue(malformed.raster.isRecycled());
        }
    }

    @Test public void whollyOffscreenGeometryCannotInventEdgeCoverageAndResizedRowsKeepWindowOrigin() {
        PixelGeometry geometry = PixelGeometry.window(1600, 2000, 100, 500, 1400, 280, 1400, 280);
        SceneEngine.Region[] outside = {new SceneEngine.Region(-20, 10, -1, 30), new SceneEngine.Region(1025, 10, 1050, 30),
                new SceneEngine.Region(10, -20, 30, -1), new SceneEngine.Region(10, 206, 30, 220)};
        for (SceneEngine.Region region : outside) assertThrows(IllegalArgumentException.class, () -> ImageTextWorker.map(
                Collections.singletonList(Collections.singletonList(new ImageTextLayout.Line("porn", region))), geometry, 1024, 205));
        List<List<ImageTextLayout.Line>> result = ImageTextWorker.map(Collections.singletonList(Collections.singletonList(
                new ImageTextLayout.Line("porn", new SceneEngine.Region(128, 41, 512, 82)))), geometry, 1024, 205);
        SceneEngine.Region region = result.get(0).get(0).region;
        assertEquals(275, region.left, 0); assertEquals(556, region.top, 0);
        assertEquals(800, region.right, 0); assertEquals(612, region.bottom, 0);
    }

    @Test public void rejectedAndAcceptedThenThrowTerminalRegistrationKeepTheExactTaskAndSingleListener() throws Exception {
        for (boolean acceptedBeforeThrow : new boolean[]{false, true}) {
            FakeRecognizer recognizer = new FakeRecognizer(); recognizer.uncertainRegistration = true;
            recognizer.acceptBeforeThrow = acceptedBeforeThrow;
            try (Harness h = new Harness(recognizer)) {
                h.start(4, false); Bitmap original = raster("porn", 400, 240, 64);
                h.next().deliver(original, PixelGeometry.display(400, 240, 400, 240)); Operation operation = recognizer.next();
                h.next(); // Literal API return/capture demand does not terminate the held SDK task.
                assertEquals(1, recognizer.registrations.get()); assertFalse(original.isRecycled());
                assertFalse(operation.raster.isRecycled()); assertEquals(1, recognizer.processes.get());
                operation.thread.interrupt(); operation.task.setResult(rows("porn", 10, 30));
                assertFalse(h.accepted().observations.isEmpty()); assertEquals(1, recognizer.registrations.get());
                assertTrue(operation.raster.isRecycled());
            }
        }
    }

    @Test public void failedAliasCopyCannotRecyclePixelsStillOwnedByTheExecutingVisualLane() throws Exception {
        FakeRecognizer recognizer = new FakeRecognizer();
        AtomicInteger copyAttempts = new AtomicInteger();
        try (CoreHarness h = new CoreHarness(recognizer, source -> { copyAttempts.incrementAndGet(); throw new OutOfMemoryError("controlled copy boundary"); })) {
            Bitmap original = raster("porn", 400, 240, 64); assertFalse(original.isMutable());
            h.next().deliver(original, PixelGeometry.display(400, 240, 400, 240));
            assertTrue(h.fastEntered.await(5, TimeUnit.SECONDS)); h.awaitFailedText();
            assertEquals("The actual no-scale alias copy boundary must have executed", 1, copyAttempts.get());
            assertEquals(0, recognizer.processes.get()); assertEquals(0, h.engine.resourceFailures());
            assertFalse("OCR cleanup cannot recycle the borrowed no-scale alias", original.isRecycled());
            assertFalse(h.fastInput.cancelled()); h.engine.close(); h.failOutstanding();
            assertFalse(original.isRecycled()); h.fastCompletion.cancelled();
            assertTrue(original.isRecycled()); assertEquals(1, h.originalDisposals.get());
            assertTrue(h.drained.await(5, TimeUnit.SECONDS));
        }
    }

    @Test public void fatalUncertainSdkLaunchQuarantinesOnlyOwnedPixelsAndNeverClaimsDrain() throws Exception {
        FakeRecognizer recognizer = new FakeRecognizer(); recognizer.throwAfterLaunch = true;
        try (CoreHarness h = new CoreHarness(recognizer, null)) {
            Bitmap original = raster("porn", 400, 240, 64); h.next().deliver(original, PixelGeometry.display(400, 240, 400, 240));
            Operation operation = recognizer.next(); h.awaitFailedText();
            assertNotSame(original, operation.raster); assertFalse(operation.raster.isRecycled());
            assertEquals(1, h.engine.resourceFailures()); assertTrue(h.fastInput.cancelled());
            assertFalse(original.isRecycled()); h.fastCompletion.cancelled();
            assertTrue(original.isRecycled()); assertEquals(1, h.originalDisposals.get());
            h.engine.close(); h.failOutstanding(); assertFalse(h.drained.await(120, TimeUnit.MILLISECONDS));
            assertFalse(operation.raster.isRecycled()); assertEquals(0, recognizer.closes.get());
            h.executor.submit(() -> assertThrows(IllegalStateException.class, h.worker::close)).get(5, TimeUnit.SECONDS);
            // Only this controlled mock knows the unreturned SDK task. Its eventual terminal
            // does NOT repair or drain the poisoned production owner.
            operation.task.setResult(Collections.emptyList()); assertFalse(operation.raster.isRecycled());
            assertFalse(h.drained.await(120, TimeUnit.MILLISECONDS));
        }
    }

    private static List<List<ImageTextLayout.Line>> rows(String caption, int x, int y) {
        return Collections.singletonList(Collections.singletonList(new ImageTextLayout.Line(caption,
                new SceneEngine.Region(x, y, x + 180, y + 40))));
    }
    private static DetectorConfig config(int threads) {
        return DetectorConfig.builder().enabledCategories(Collections.emptySet()).inferenceThreads(threads).build();
    }
    private static TextSmutConfig text() { return new TextSmutConfig(true, TextSmutConfig.SENSITIVITY_BALANCED, null); }
    private static Bitmap raster(String caption, int width, int height, int textSize) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap); canvas.drawColor(Color.WHITE);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG); paint.setColor(Color.BLACK); paint.setTextSize(textSize);
        assertTrue("Synthetic SDK caption exceeds its native Canvas region", paint.measureText(caption) <= width - 40);
        canvas.drawText(caption, 20, height / 2f + textSize / 2f, paint);
        // Actual AccessibilityCapturer software readback is immutable. Mutable fixtures can make
        // Android allocate a no-scale image and accidentally skip the borrowed-alias copy branch.
        Bitmap immutable = bitmap.copy(Bitmap.Config.ARGB_8888, false); bitmap.recycle(); assertNotNull(immutable); return immutable;
    }
    private static void save(Context context, Bitmap bitmap, String label) throws Exception {
        if (!"true".equals(InstrumentationRegistry.getArguments().getString("tap_fixture_capture"))) return;
        File directory = context.getExternalFilesDir("censor-tap-fixtures-20261011");
        assertNotNull(directory); assertTrue(directory.isDirectory() || directory.mkdirs());
        try (FileOutputStream stream = new FileOutputStream(new File(directory, "native-mask-" + label + "-" + System.nanoTime() + ".png"))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream));
        }
    }

    private static final class Operation {
        final Bitmap raster;
        final Thread thread = Thread.currentThread();
        final TaskCompletionSource<List<List<ImageTextLayout.Line>>> task = new TaskCompletionSource<>();
        Operation(Bitmap raster) { this.raster = raster; }
    }
    private static final class FakeRecognizer implements ImageTextWorker.Recognizer {
        final BlockingQueue<Operation> operations = new LinkedBlockingQueue<>();
        final List<Operation> all = new CopyOnWriteArrayList<>();
        final CountDownLatch mappingEntered = new CountDownLatch(1), mappingRelease = new CountDownLatch(1);
        final AtomicInteger processes = new AtomicInteger(), closes = new AtomicInteger();
        final AtomicBoolean closedOnMain = new AtomicBoolean();
        final AtomicInteger registrations = new AtomicInteger();
        volatile boolean blockMapping, uncertainRegistration, acceptBeforeThrow, throwAfterLaunch;
        @Override public Task<?> process(Bitmap bitmap) {
            assertNotEquals(Looper.getMainLooper(), Looper.myLooper());
            Operation operation = new Operation(bitmap); all.add(operation); operations.add(operation); processes.incrementAndGet();
            if (throwAfterLaunch) throw new OutOfMemoryError("controlled accepted SDK launch boundary");
            return uncertainRegistration ? new UncertainTask<>(operation.task.getTask(), acceptBeforeThrow, registrations)
                    : operation.task.getTask();
        }
        @Override public List<List<ImageTextLayout.Line>> lines(Task<?> terminal) {
            assertTrue(terminal.isComplete()); mappingEntered.countDown();
            if (blockMapping) {
                try { assertTrue("Synthetic mapping gate deadline", mappingRelease.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException failure) { throw new IllegalStateException(failure); }
            }
            @SuppressWarnings("unchecked") List<List<ImageTextLayout.Line>> result = (List<List<ImageTextLayout.Line>>) terminal.getResult();
            return result;
        }
        @Override public void close() { closedOnMain.set(Looper.myLooper() == Looper.getMainLooper()); closes.incrementAndGet(); }
        Operation next() throws InterruptedException { Operation value = operations.poll(5, TimeUnit.SECONDS); assertNotNull("OCR SDK job missing", value); return value; }
    }

    /** Real Task delegate with only terminal-listener admission made explicitly uncertain. */
    private static final class UncertainTask<T> extends Task<T> {
        final Task<T> delegate;
        final boolean acceptBeforeThrow;
        final AtomicInteger registrations;
        UncertainTask(Task<T> delegate, boolean acceptBeforeThrow, AtomicInteger registrations) {
            this.delegate = delegate; this.acceptBeforeThrow = acceptBeforeThrow; this.registrations = registrations;
        }
        @Override public boolean isComplete() { return delegate.isComplete(); }
        @Override public boolean isSuccessful() { return delegate.isSuccessful(); }
        @Override public boolean isCanceled() { return delegate.isCanceled(); }
        @Override public T getResult() { return delegate.getResult(); }
        @Override public <X extends Throwable> T getResult(Class<X> type) throws X { return delegate.getResult(type); }
        @Override public Exception getException() { return delegate.getException(); }
        @Override public Task<T> addOnSuccessListener(OnSuccessListener<? super T> listener) { delegate.addOnSuccessListener(listener); return this; }
        @Override public Task<T> addOnSuccessListener(Executor executor, OnSuccessListener<? super T> listener) { delegate.addOnSuccessListener(executor, listener); return this; }
        @Override public Task<T> addOnSuccessListener(Activity activity, OnSuccessListener<? super T> listener) { delegate.addOnSuccessListener(activity, listener); return this; }
        @Override public Task<T> addOnFailureListener(OnFailureListener listener) { delegate.addOnFailureListener(listener); return this; }
        @Override public Task<T> addOnFailureListener(Executor executor, OnFailureListener listener) { delegate.addOnFailureListener(executor, listener); return this; }
        @Override public Task<T> addOnFailureListener(Activity activity, OnFailureListener listener) { delegate.addOnFailureListener(activity, listener); return this; }
        @Override public Task<T> addOnCompleteListener(Executor executor, OnCompleteListener<T> listener) {
            registrations.incrementAndGet(); if (acceptBeforeThrow) delegate.addOnCompleteListener(executor, listener);
            throw new RejectedExecutionException("controlled uncertain terminal listener admission");
        }
    }
    private static final class Call {
        final CensorEngine.CaptureRequest request;
        final CensorEngine.CaptureCallback<PixelFrame> callback;
        final Executor executor;
        final AtomicInteger disposalCounter;
        final AtomicBoolean delivered = new AtomicBoolean();
        Call(CensorEngine.CaptureRequest request, CensorEngine.CaptureCallback<PixelFrame> callback, Executor executor) {
            this(request, callback, executor, null);
        }
        Call(CensorEngine.CaptureRequest request, CensorEngine.CaptureCallback<PixelFrame> callback, Executor executor, AtomicInteger disposalCounter) {
            this.request = request; this.callback = callback; this.executor = executor;
            this.disposalCounter = disposalCounter;
        }
        void deliver(Bitmap bitmap, PixelGeometry geometry) {
            assertTrue(delivered.compareAndSet(false, true));
            executor.execute(() -> callback.success(new CensorEngine.Captured<>(new PixelFrame(bitmap, geometry,
                    PixelFrame.Kind.ACCESSIBILITY_WINDOW, -1, SystemClock.uptimeMillis(), 0), geometry.displayWidth,
                    geometry.displayHeight, -1, () -> { if (disposalCounter != null) disposalCounter.incrementAndGet(); bitmap.recycle(); })));
        }
        void fail() { if (delivered.compareAndSet(false, true)) executor.execute(() -> callback.failure(0)); }
    }

    /** Real core/serial OCR job plus held FAST input; no host executor is stranded by poison. */
    private static final class CoreHarness implements AutoCloseable {
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        final Handler main = new Handler(Looper.getMainLooper());
        final BlockingQueue<Call> requests = new LinkedBlockingQueue<>();
        final List<Call> all = new CopyOnWriteArrayList<>();
        final List<CensorEngine.Trace> traces = new CopyOnWriteArrayList<>();
        final CountDownLatch fastEntered = new CountDownLatch(1), drained = new CountDownLatch(1);
        final AtomicInteger originalDisposals = new AtomicInteger();
        final FakeRecognizer recognizer;
        final ImageTextWorker worker;
        final CensorEngine<PixelFrame> engine;
        volatile CensorEngine.Input<PixelFrame> fastInput;
        volatile CensorEngine.Completion fastCompletion;
        CoreHarness(FakeRecognizer recognizer, ImageTextWorker.BitmapCopier copier) {
            this.recognizer = recognizer;
            worker = copier == null ? new ImageTextWorker(recognizer, new SmutTextClassifier(), 3)
                    : new ImageTextWorker(recognizer, new SmutTextClassifier(), 3, copier);
            worker.configure(text(), false);
            engine = new CensorEngine<>(new CensorEngine.Policy(334, 500, 8, 3, false),
                    new SceneEngine.Policy(16, 100, 64, 256, 10_000, 20_000, .5), SystemClock::uptimeMillis,
                    (action, delay) -> { assertTrue(main.postDelayed(action, delay)); return () -> main.removeCallbacks(action); },
                    (request, callback) -> {
                        assertTrue(request.tryDispatch()); Call call = new Call(request, callback, Runnable::run, originalDisposals);
                        all.add(call); requests.add(call);
                    }, java.util.Arrays.asList(new CensorEngine.LaneWorker<>(SceneEngine.Lane.FAST, Runnable::run,
                            (input, completion) -> { fastInput = input; fastCompletion = completion; fastEntered.countDown(); }),
                            new CensorEngine.LaneWorker<>(SceneEngine.Lane.TEXT, executor, worker)), Runnable::run,
                    new CensorEngine.Observer() {
                        @Override public void trace(CensorEngine.Trace trace) { traces.add(trace); }
                        @Override public void drained() { drained.countDown(); }
                    });
            engine.executionControls(334, false, EnumSet.of(SceneEngine.Lane.FAST, SceneEngine.Lane.TEXT)); engine.start(10);
        }
        Call next() throws InterruptedException { Call call = requests.poll(5, TimeUnit.SECONDS); assertNotNull(call); return call; }
        void awaitFailedText() throws InterruptedException {
            long deadline = SystemClock.uptimeMillis() + 5000;
            while (SystemClock.uptimeMillis() < deadline) {
                for (CensorEngine.Trace trace : traces) if (trace.lane == SceneEngine.Lane.TEXT && trace.stage == CensorEngine.Stage.JOB_FAILED) return;
                Thread.sleep(10);
            }
            throw new AssertionError("Expected actual failed OCR terminal");
        }
        void failOutstanding() { for (Call call : all) call.fail(); }
        @Override public void close() throws Exception {
            engine.close(); failOutstanding(); recognizer.mappingRelease.countDown();
            for (Operation operation : recognizer.all) operation.task.trySetResult(Collections.emptyList());
            if (fastCompletion != null) fastCompletion.cancelled();
            if (engine.resourceFailures() == 0) executor.submit(worker::close).get(5, TimeUnit.SECONDS);
            executor.shutdown(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            // Mock-only cleanup after its known task terminal; no production drain/disposal claim.
            for (Operation operation : recognizer.all) if (operation.task.getTask().isComplete() && !operation.raster.isRecycled()) operation.raster.recycle();
        }
    }
    private static final class Harness implements AutoCloseable {
        final Context context = ApplicationProvider.getApplicationContext();
        final BlockingQueue<Call> requests = new LinkedBlockingQueue<>();
        final BlockingQueue<SceneEngine.Batch> batches = new LinkedBlockingQueue<>();
        final List<Call> all = new CopyOnWriteArrayList<>();
        final List<CensorEngine.Trace> traces = new CopyOnWriteArrayList<>();
        final AtomicInteger visualInitializations = new AtomicInteger(), ocrInitializations = new AtomicInteger();
        final AtomicBoolean initializedOnMain = new AtomicBoolean();
        final CountDownLatch terminated = new CountDownLatch(1);
        final FakeRecognizer recognizer;
        AccessibilityCensorHost host;
        Harness(FakeRecognizer recognizer) {
            this.recognizer = recognizer;
            main(() -> host = new AccessibilityCensorHost(context, new SceneEngine.Policy(16, 100, 64, 256, 10_000, 20_000, .5),
                    executor -> (request, callback) -> {
                        assertTrue(request.tryDispatch()); Call call = new Call(request, callback, executor); all.add(call); requests.add(call);
                    }, (unusedContext, lane, unusedConfig, partition, fallback) -> {
                        visualInitializations.incrementAndGet(); throw new IllegalStateException("Text-only fixture must not initialize visual models");
                    }, new AccessibilityCensorHost.Observer() {
                        @Override public void trace(CensorEngine.Trace trace) { traces.add(trace); }
                        @Override public void batchAccepted(SceneEngine.Batch batch) { batches.add(batch); }
                        @Override public void executorsTerminated(boolean disposed) { assertTrue(disposed); terminated.countDown(); }
                    }, null, (selectedContext, partition) -> {
                        ocrInitializations.incrementAndGet(); initializedOnMain.set(Looper.myLooper() == Looper.getMainLooper());
                        return recognizer == null ? ImageTextWorker.initialize(selectedContext, partition)
                                : new ImageTextWorker(recognizer, new SmutTextClassifier(), partition);
                    }));
        }
        void start(int threads, boolean semantic) { main(() -> host.start(10, config(threads), false, text(), semantic, false)); }
        Call next() throws InterruptedException { Call call = requests.poll(5, TimeUnit.SECONDS); assertNotNull("Image capture missing", call); return call; }
        Call next(long token) throws InterruptedException {
            long deadline = SystemClock.uptimeMillis() + 5000;
            while (SystemClock.uptimeMillis() < deadline) {
                Call call = next();
                if (call.request.geometry == token && call.request.epoch == host.epoch()) return call;
                call.fail(); // Reconfiguration cannot cancel an outstanding platform callback.
            }
            throw new AssertionError("Expected fresh scoped capture request");
        }
        SceneEngine.Batch accepted() throws InterruptedException {
            SceneEngine.Batch batch = batches.poll(15, TimeUnit.SECONDS); assertNotNull("Complete OCR batch missing", batch); return batch;
        }
        CensorEngine<PixelFrame>.RenderView view(long source) throws InterruptedException {
            long deadline = SystemClock.uptimeMillis() + 5000;
            while (SystemClock.uptimeMillis() < deadline) {
                CensorEngine<PixelFrame>.RenderView view = host.acquireRenderView();
                if (view != null && view.snapshot.destination.sequence == source) return view;
                if (view != null) view.close(); Thread.sleep(10);
            }
            throw new AssertionError("Expected current OCR presentation");
        }
        void awaitTerminal(long source) throws InterruptedException {
            long deadline = SystemClock.uptimeMillis() + 5000;
            while (SystemClock.uptimeMillis() < deadline) {
                for (CensorEngine.Trace trace : traces) if (trace.source == source && trace.lane == SceneEngine.Lane.TEXT
                        && (trace.stage == CensorEngine.Stage.JOB_FAILED || trace.stage == CensorEngine.Stage.JOB_COMPLETED)) return;
                Thread.sleep(10);
            }
            throw new AssertionError("Expected actual OCR terminal trace");
        }
        void failOutstanding() { for (Call call : all) call.fail(); }
        @Override public void close() throws Exception {
            main(host::close); failOutstanding();
            if (recognizer != null) {
                recognizer.mappingRelease.countDown();
                for (Operation operation : recognizer.all) operation.task.trySetResult(Collections.emptyList());
            }
            assertTrue("Actual image-text host ownership did not terminate", terminated.await(5, TimeUnit.SECONDS));
        }
    }
}
