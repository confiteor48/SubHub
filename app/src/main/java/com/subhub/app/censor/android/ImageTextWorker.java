package com.subhub.app.censor.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Looper;

import com.google.android.gms.tasks.Task;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import com.subhub.app.censor.engine.CensorEngine;
import com.subhub.app.censor.engine.PixelGeometry;
import com.subhub.app.censor.engine.SceneEngine;
import com.subhub.app.detection.text.ImageTextLayout;
import com.subhub.app.detection.text.SmutTextClassifier;
import com.subhub.app.detection.text.TextSmutConfig;
import com.subhub.app.service.InferenceBitmapPreparer;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** One serial owner TEXT job: actual SDK completion, classification and cleanup precede terminal. */
public final class ImageTextWorker implements CensorEngine.Worker<PixelFrame>, AutoCloseable {
    public static final int MAXIMUM_DIMENSION = 1024; // Existing OCR raster policy, not model tuning.
    public static final long MAXIMUM_INPUT_PIXELS = 8L * 1024 * 1024;

    public interface Recognizer extends AutoCloseable {
        Task<?> process(Bitmap bitmap);
        /** Called only after the exact returned task is terminal; rows are in that raster's coordinates. */
        List<? extends List<ImageTextLayout.Line>> lines(Task<?> terminal);
        @Override void close();
    }

    interface BitmapCopier { Bitmap copy(Bitmap source); }

    private final Recognizer recognizer;
    private final SmutTextClassifier classifier;
    private final long partition;
    private final BitmapCopier copier;
    private TextSmutConfig config;
    private boolean semanticEnabled, running, closed;
    /** Process threw without a task: native ownership is uncertain, so never recycle this raster. */
    private Bitmap quarantined;

    public ImageTextWorker(Recognizer recognizer, SmutTextClassifier classifier, long partition) {
        this(recognizer, classifier, partition, source -> source.copy(Bitmap.Config.ARGB_8888, false));
    }

    ImageTextWorker(Recognizer recognizer, SmutTextClassifier classifier, long partition, BitmapCopier copier) {
        this.recognizer = Objects.requireNonNull(recognizer); this.classifier = Objects.requireNonNull(classifier);
        this.partition = partition;
        this.copier = Objects.requireNonNull(copier);
    }

    public static ImageTextWorker initialize(Context context, long partition) {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("OCR initialization on main");
        SmutTextClassifier classifier = new SmutTextClassifier(context);
        return new ImageTextWorker(new SdkRecognizer(TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)),
                classifier, partition);
    }

    /** The same serial lane owns configuration and invocation. */
    public synchronized void configure(TextSmutConfig config, boolean semanticEnabled) {
        if (closed || running || quarantined != null) throw new IllegalStateException("OCR configuration lifetime");
        this.config = Objects.requireNonNull(config); this.semanticEnabled = semanticEnabled;
    }

    @Override public void start(CensorEngine.Input<PixelFrame> input, CensorEngine.Completion completion) {
        boolean admitted;
        synchronized (this) { admitted = !closed && !running && quarantined == null && config != null; if (admitted) running = true; }
        if (!admitted) { completion.failure(); return; }
        Bitmap prepared = null;
        Task<?> task = null;
        boolean launchAttempted = false, failed = false, unsafe = false, interrupted = false;
        SceneEngine.Batch batch = null;
        CensorEngine.WorkTiming timing = null;
        try {
            if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("OCR execution on main");
            PixelFrame frame = input.pixels;
            if (input.source.width != frame.geometry.displayWidth || input.source.height != frame.geometry.displayHeight
                    || (long) frame.geometry.width * frame.geometry.height > MAXIMUM_INPUT_PIXELS) {
                throw new IllegalArgumentException("OCR source geometry/budget");
            }
            if (!input.cancelled() && config.isEnabled()) {
                long began = System.nanoTime();
                InferenceBitmapPreparer.Prepared value = InferenceBitmapPreparer.prepare(frame.bitmap, MAXIMUM_DIMENSION, false);
                if (value == null) throw new IllegalStateException("OCR raster unavailable");
                prepared = value.bitmap;
                // An owned software raster makes uncertain SDK launch independent of captured
                // pixels. Do not quarantine or recycle the runtime's no-scale alias.
                if (prepared == frame.bitmap) {
                    // Clear the borrowed alias BEFORE the fallible copy. Failure cannot leave it
                    // in a worker-owned cleanup/quarantine variable.
                    prepared = null;
                    prepared = copier.copy(frame.bitmap);
                    if (prepared == frame.bitmap) { prepared = null; throw new IllegalStateException("OCR copy returned borrowed pixels"); }
                }
                if (prepared == null || prepared.getConfig() == Bitmap.Config.HARDWARE
                        || prepared.getWidth() > MAXIMUM_DIMENSION || prepared.getHeight() > MAXIMUM_DIMENSION) {
                    throw new IllegalStateException("OCR raster bounds");
                }
                long preparedAt = System.nanoTime();
                if (!input.cancelled()) {
                    launchAttempted = true;
                    task = Objects.requireNonNull(recognizer.process(prepared));
                    // One terminal listener only. An uncertain registration falls back to observing
                    // this exact Task; no retry adds listeners or releases an executing bitmap.
                    CountDownLatch terminal = new CountDownLatch(1);
                    try { task.addOnCompleteListener(Runnable::run, ignored -> terminal.countDown()); }
                    catch (RuntimeException | Error uncertainRegistration) { /* retain exact task until terminal */ }
                    while (!task.isComplete()) {
                        try {
                            if (terminal.getCount() == 0) Thread.sleep(10);
                            else terminal.await(50, TimeUnit.MILLISECONDS);
                        } catch (InterruptedException cancellationIsNotTerminal) { interrupted = true; }
                    }
                    long recognizedAt = System.nanoTime();
                    if (!input.cancelled() && task.isSuccessful()) {
                        List<? extends List<ImageTextLayout.Line>> raw = recognizer.lines(task);
                        List<List<ImageTextLayout.Line>> display = map(raw, frame.geometry,
                                prepared.getWidth(), prepared.getHeight());
                        batch = SceneEngine.Batch.imageText(input.source, partition,
                                ImageTextLayout.classify(display, classifier, config, semanticEnabled, partition));
                        timing = new CensorEngine.WorkTiming(millis(preparedAt - began), millis(recognizedAt - preparedAt),
                                millis(System.nanoTime() - recognizedAt));
                    } else if (!input.cancelled()) failed = true;
                }
            }
        } catch (RuntimeException | Error failure) {
            failed = true;
            // Never free a raster after an uncertain launch or a nonterminal Task API failure.
            unsafe = launchAttempted && !terminal(task);
        } finally {
            if (unsafe) { synchronized (this) { quarantined = prepared; } }
            else try { if (prepared != null && !prepared.isRecycled()) prepared.recycle(); }
            catch (RuntimeException | Error cleanupFailure) {
                unsafe = true; synchronized (this) { quarantined = prepared; }
            }
            synchronized (this) { running = false; }
            if (interrupted) Thread.currentThread().interrupt();
        }
        if (unsafe) completion.resourceFailure();
        else if (input.cancelled()) completion.cancelled();
        else if (failed || batch == null) completion.failure();
        else completion.success(batch, timing);
    }

    private static long millis(long nanos) { return Math.max(0, nanos / 1_000_000L); }

    private static boolean terminal(Task<?> task) {
        try { return task != null && task.isComplete(); }
        catch (RuntimeException | Error unknownTaskState) { return false; }
    }

    static List<List<ImageTextLayout.Line>> map(List<? extends List<ImageTextLayout.Line>> raw,
            PixelGeometry geometry, int rasterWidth, int rasterHeight) {
        Objects.requireNonNull(raw);
        if (raw.size() > ImageTextLayout.MAXIMUM_BLOCKS || rasterWidth <= 0 || rasterHeight <= 0) {
            throw new IllegalArgumentException("OCR raster/blocks");
        }
        int lines = 0, units = 0;
        List<List<ImageTextLayout.Line>> result = new ArrayList<>(raw.size());
        for (List<ImageTextLayout.Line> block : raw) {
            Objects.requireNonNull(block);
            if (block.size() > ImageTextLayout.MAXIMUM_LINES - lines) throw new IllegalArgumentException("OCR line budget");
            lines += block.size();
            List<ImageTextLayout.Line> mapped = new ArrayList<>(block.size());
            for (ImageTextLayout.Line line : block) {
                Objects.requireNonNull(line);
                if (line.text.length() > ImageTextLayout.MAXIMUM_TEXT_UNITS - units) throw new IllegalArgumentException("OCR text budget");
                units += line.text.length();
                SceneEngine.Region region = line.region;
                if (region != null) {
                    double left = Math.max(0, region.left), top = Math.max(0, region.top);
                    double right = Math.min(rasterWidth, region.right), bottom = Math.min(rasterHeight, region.bottom);
                    if (right <= left || bottom <= top) throw new IllegalArgumentException("OCR geometry outside raster");
                    region = new SceneEngine.Region(geometry.left + left * geometry.width / rasterWidth,
                            geometry.top + top * geometry.height / rasterHeight,
                            geometry.left + right * geometry.width / rasterWidth,
                            geometry.top + bottom * geometry.height / rasterHeight);
                }
                mapped.add(new ImageTextLayout.Line(line.text, region));
            }
            result.add(mapped);
        }
        return result;
    }

    @Override public synchronized void close() {
        if (closed) return;
        if (running || quarantined != null) throw new IllegalStateException("OCR native ownership has not drained");
        recognizer.close(); closed = true;
    }

    private static final class SdkRecognizer implements Recognizer {
        final TextRecognizer sdk;
        SdkRecognizer(TextRecognizer sdk) { this.sdk = sdk; }
        @Override public Task<?> process(Bitmap bitmap) { return sdk.process(InputImage.fromBitmap(bitmap, 0)); }
        @Override public List<List<ImageTextLayout.Line>> lines(Task<?> terminal) {
            Object value = terminal.getResult();
            if (!(value instanceof Text)) throw new IllegalArgumentException("OCR SDK result missing");
            List<Text.TextBlock> blocks = ((Text) value).getTextBlocks();
            if (blocks.size() > ImageTextLayout.MAXIMUM_BLOCKS) throw new IllegalArgumentException("OCR SDK block budget");
            List<List<ImageTextLayout.Line>> result = new ArrayList<>(blocks.size());
            int count = 0, units = 0;
            for (Text.TextBlock block : blocks) {
                List<Text.Line> lines = block.getLines();
                if (lines.size() > ImageTextLayout.MAXIMUM_LINES - count) throw new IllegalArgumentException("OCR SDK line budget");
                count += lines.size();
                List<ImageTextLayout.Line> rows = new ArrayList<>(lines.size());
                for (Text.Line line : lines) {
                    String text = line.getText();
                    if (text == null || text.length() > ImageTextLayout.MAXIMUM_LINE_UNITS
                            || text.length() > ImageTextLayout.MAXIMUM_TEXT_UNITS - units) {
                        throw new IllegalArgumentException("OCR SDK text budget");
                    }
                    units += text.length();
                    Rect bounds = line.getBoundingBox();
                    SceneEngine.Region region = bounds == null || bounds.isEmpty() ? null
                            : new SceneEngine.Region(bounds.left, bounds.top, bounds.right, bounds.bottom);
                    rows.add(new ImageTextLayout.Line(text, region));
                }
                result.add(rows);
            }
            return result;
        }
        @Override public void close() { sdk.close(); }
    }
}
