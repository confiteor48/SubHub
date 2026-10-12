package com.subhub.app.censor.android;

import static org.junit.Assert.*;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.os.Looper;
import android.os.Process;
import android.os.Debug;
import android.os.Trace;
import android.provider.Settings;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.google.android.gms.tasks.Task;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import com.subhub.app.service.InferenceBitmapPreparer;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** SDK cost control only: no Activity, Accessibility producer, model lane or live presentation. */
@RunWith(AndroidJUnit4.class)
public final class OcrRuntimeIsolationAndroidTest {
    @Test public void threeSerialSdkTasksOnRetainedPixels() throws Exception {
        assertEquals("Explicit owned-emulator opt-in required", "true",
                InstrumentationRegistry.getArguments().getString("ocr_isolation"));
        assertTrue("This control is not authorized on a phone", android.os.Build.FINGERPRINT.contains("generic")
                || android.os.Build.MODEL.contains("sdk_gphone"));
        assertNotSame(Looper.getMainLooper(), Looper.myLooper());
        String sdkMode = InstrumentationRegistry.getArguments().getString("ocr_sdk_executor", "default");
        assertTrue("Only the planned executor boundary may change", sdkMode.equals("default") || sdkMode.equals("owned-normal"));
        int schemaVersion = sdkMode.equals("default") ? 1 : 2;
        boolean profiling = "true".equals(InstrumentationRegistry.getArguments().getString("ocr_trace"));
        assertTrue("Profiling uses the tagged executor only; not a production placement change", !profiling || sdkMode.equals("owned-normal"));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("No live Accessibility pipeline during isolation", 0,
                Settings.Secure.getInt(context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0));
        String profileId = InstrumentationRegistry.getArguments().getString("ocr_profile_id", "first");
        assertTrue(profileId.matches("[a-z0-9][a-z0-9-]{0,47}"));
        File directory = new File(context.getFilesDir(), profiling ? "ocr-runtime-profile-" + profileId : "ocr-runtime-isolation");
        assertTrue(directory.isDirectory() || directory.mkdir());
        File sourceFile = new File(new File(context.getFilesDir(), "ocr-runtime-isolation"), "input.png");
        File records = new File(directory, profiling ? "measurements-profile.jsonl"
                : (sdkMode.equals("default") ? "measurements.jsonl" : "measurements-owned-normal.jsonl"));
        String expectedHash = InstrumentationRegistry.getArguments().getString("ocr_input_sha256");
        assertNotNull(expectedHash);
        assertTrue(expectedHash.matches("[0-9a-f]{64}"));
        assertTrue(sourceFile.isFile());
        assertEquals(expectedHash, digest(sourceFile));
        assertTrue("Never overwrite an earlier raw measurement", records.createNewFile());
        int previousPriority = Process.getThreadPriority(Process.myTid());
        Bitmap source = null, prepared = null;
        TextRecognizer recognizer = null;
        RecordedSdkExecutor sdkExecutor = null;
        Task<Text> current = null;
        boolean launchAttempted = false, completed = false, allPositive = true;
        int terminalRecords = 0;
        try (FileOutputStream output = new FileOutputStream(records)) {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                long decodedAt = System.nanoTime();
                source = BitmapFactory.decodeFile(sourceFile.getAbsolutePath());
                assertNotNull(source);
                assertTrue((long) source.getWidth() * source.getHeight() <= ImageTextWorker.MAXIMUM_INPUT_PIXELS);
                long preparationEntered = System.nanoTime();
                InferenceBitmapPreparer.Prepared value = InferenceBitmapPreparer.prepare(
                        source, ImageTextWorker.MAXIMUM_DIMENSION, false);
                assertNotNull(value);
                prepared = value.bitmap;
                if (prepared == source) prepared = source.copy(Bitmap.Config.ARGB_8888, false);
                assertNotNull(prepared);
                assertNotSame(source, prepared);
                assertNotEquals(Bitmap.Config.HARDWARE, prepared.getConfig());
                long preparationReturned = System.nanoTime();
                String rasterHash = rasterDigest(prepared);
                long clientEntered = System.nanoTime();
                TextRecognizerOptions options = TextRecognizerOptions.DEFAULT_OPTIONS;
                if (sdkMode.equals("owned-normal")) {
                    sdkExecutor = new RecordedSdkExecutor(profiling);
                    options = new TextRecognizerOptions.Builder().setExecutor(sdkExecutor).build();
                }
                recognizer = TextRecognition.getClient(options);
                long clientReturned = System.nanoTime();
                append(output, new JSONObject().put("record", "START").put("inputSha256", expectedHash)
                        .put("sourceWidth", source.getWidth()).put("sourceHeight", source.getHeight())
                        .put("rasterWidth", prepared.getWidth()).put("rasterHeight", prepared.getHeight())
                        .put("rasterSha256", rasterHash).put("digestFormat", "ARGB-big-endian-rows-v1")
                        .put("maximumDimension", ImageTextWorker.MAXIMUM_DIMENSION)
                        .put("decodedAtNanos", decodedAt).put("preparationEnteredNanos", preparationEntered)
                        .put("preparationReturnedNanos", preparationReturned).put("clientEnteredNanos", clientEntered)
                        .put("clientReturnedNanos", clientReturned).put("callerPriority", Process.getThreadPriority(Process.myTid()))
                        .put("sdkOptions", sdkMode.equals("default") ? "DEFAULT_OPTIONS" : "LATIN_OWNED_EXECUTOR")
                        .put("warmup", false).put("serialTasks", 3)
                        .put("sourceDescription", profiling ? "Probe-perturbed diagnostic; not a latency benchmark; retained synthetic window image"
                                : "Retained synthetic window image; includes native text unlike failed Canvas-only fixture")
                        .put("pixelPerformanceAcceptance", false), schemaVersion);
                for (int index = 0; index < 3; index++) {
                    assertEquals(rasterHash, rasterDigest(prepared));
                    if (profiling && index == 1) {
                        assertNotNull(sdkExecutor);
                        assertFalse(sdkExecutor.executions.isEmpty());
                        int sdkTid = sdkExecutor.executions.get(0).threadId;
                        marker(directory, "profile-ready.json", new JSONObject().put("phase", "READY")
                                .put("appPid", Process.myPid()).put("sdkTid", sdkTid).put("warmIndex", index)
                                .put("observedNanos", System.nanoTime()).put("inputSha256", expectedHash).put("rasterSha256", rasterHash));
                        File release = new File(directory, "profile-release");
                        long readyDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                        while (!release.exists() && System.nanoTime() < readyDeadline) Thread.sleep(10);
                        assertTrue("Tracer-ready release missing; no unobserved warm diagnostic", release.exists());
                        marker(directory, "profile-active.json", new JSONObject().put("phase", "ACTIVE")
                                .put("appPid", Process.myPid()).put("sdkTid", sdkTid).put("warmIndex", index)
                                .put("observedNanos", System.nanoTime()).put("inputSha256", expectedHash).put("rasterSha256", rasterHash));
                    }
                    CountDownLatch terminal = new CountDownLatch(1);
                    AtomicLong listenerAt = new AtomicLong();
                    AtomicReference<String> listenerThread = new AtomicReference<>();
                    AtomicLong listenerPriority = new AtomicLong();
                    long processEntered = System.nanoTime();
                    launchAttempted = true;
                    current = recognizer.process(InputImage.fromBitmap(prepared, 0));
                    assertNotNull(current);
                    long processReturned = System.nanoTime();
                    current.addOnCompleteListener(Runnable::run, ignored -> {
                        listenerAt.set(System.nanoTime());
                        listenerThread.set(Thread.currentThread().getName());
                        listenerPriority.set(Process.getThreadPriority(Process.myTid()));
                        terminal.countDown();
                    });
                    long deadline = processEntered + TimeUnit.SECONDS.toNanos(60);
                    while (!current.isComplete() && System.nanoTime() < deadline) terminal.await(50, TimeUnit.MILLISECONDS);
                    boolean taskTerminal = current.isComplete();
                    long observedAt = System.nanoTime();
                    boolean listenerObserved = taskTerminal && terminal.await(5, TimeUnit.SECONDS);
                    JSONArray lines = new JSONArray();
                    boolean positive = false, validBounds = true;
                    if (taskTerminal && current.isSuccessful()) {
                        for (Text.TextBlock block : current.getResult().getTextBlocks()) {
                            for (Text.Line line : block.getLines()) {
                                Rect bounds = line.getBoundingBox();
                                boolean valid = bounds != null && !bounds.isEmpty() && bounds.left >= 0 && bounds.top >= 0
                                        && bounds.right <= prepared.getWidth() && bounds.bottom <= prepared.getHeight();
                                validBounds &= valid;
                                positive |= "send nudes".equals(line.getText()) && valid;
                                lines.put(new JSONObject().put("text", line.getText()).put("validBounds", valid)
                                        .put("left", bounds == null ? JSONObject.NULL : bounds.left)
                                        .put("top", bounds == null ? JSONObject.NULL : bounds.top)
                                        .put("right", bounds == null ? JSONObject.NULL : bounds.right)
                                        .put("bottom", bounds == null ? JSONObject.NULL : bounds.bottom));
                            }
                        }
                    }
                    String afterHash = rasterDigest(prepared);
                    append(output, new JSONObject().put("record", "SDK_TASK").put("index", index)
                            .put("processEnteredNanos", processEntered).put("processReturnedNanos", processReturned)
                            .put("ownerObservedNanos", observedAt).put("terminalObserved", taskTerminal)
                            .put("listenerObserved", listenerObserved).put("listenerAtNanos", listenerAt.get())
                            .put("listenerThread", listenerThread.get() == null ? JSONObject.NULL : listenerThread.get())
                            .put("listenerPriority", listenerObserved ? listenerPriority.get() : JSONObject.NULL)
                            .put("successful", taskTerminal && current.isSuccessful())
                            .put("cancelled", taskTerminal && current.isCanceled())
                            .put("failureClass", taskTerminal && current.getException() != null
                                    ? current.getException().getClass().getName() : JSONObject.NULL)
                            .put("literalPositive", positive).put("allBoundsValid", validBounds).put("lines", lines)
                            .put("rasterSha256After", afterHash).put("rasterUnchanged", rasterHash.equals(afterHash)), schemaVersion);
                    assertTrue("SDK still owns pixels; no release or next task", taskTerminal);
                    terminalRecords++;
                    assertTrue("Direct terminal listener was not observed", listenerObserved);
                    assertEquals(rasterHash, afterHash);
                    allPositive &= current.isSuccessful() && positive && validBounds;
                    launchAttempted = false;
                    current = null;
                }
                completed = true;
            } finally {
                boolean safelyDrained = !launchAttempted || (current != null && current.isComplete());
                boolean clientClosed = false, rasterReleased = false, sourceReleased = false;
                if (safelyDrained) {
                    if (recognizer != null) { recognizer.close(); clientClosed = true; }
                    if (sdkExecutor != null) sdkExecutor.shutdown();
                    if (prepared != null) { prepared.recycle(); rasterReleased = true; }
                    if (source != null) { source.recycle(); sourceReleased = true; }
                }
                Process.setThreadPriority(previousPriority);
                if (sdkExecutor != null) append(output, sdkExecutor.record(), schemaVersion);
                append(output, new JSONObject().put("record", "FINAL").put("allTasksCompleted", completed)
                        .put("terminalRecords", terminalRecords).put("allLiteralPositives", allPositive && completed)
                        .put("ownershipDrained", safelyDrained).put("clientClosed", clientClosed)
                        .put("rasterReleased", rasterReleased).put("sourceReleased", sourceReleased)
                        .put("priorityRestored", Process.getThreadPriority(Process.myTid()) == previousPriority), schemaVersion);
                if (sdkExecutor != null) assertTrue("SDK executor must actually execute and fully drain", sdkExecutor.qualified());
            }
        }
        assertTrue("All three real SDK tasks must recognize the literal Canvas phrase with valid bounds", allPositive);
    }

    private static void append(FileOutputStream output, JSONObject record, int schemaVersion) throws Exception {
        record.put("schemaVersion", schemaVersion);
        output.write((record.toString() + "\n").getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private static void marker(File directory, String name, JSONObject value) throws Exception {
        File temporary = new File(directory, name + ".tmp"), finalFile = new File(directory, name);
        assertFalse("Do not reuse an earlier profiler marker", finalFile.exists());
        assertTrue(temporary.createNewFile());
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write((value.put("schemaVersion", 1).toString() + "\n").getBytes(StandardCharsets.UTF_8));
            output.flush();
        }
        assertTrue("Profiler marker must publish atomically after its writer closes", temporary.renameTo(finalFile));
    }

    private static final class RecordedSdkExecutor implements Executor {
        final AtomicInteger submitted = new AtomicInteger();
        final AtomicInteger dropped = new AtomicInteger();
        final CopyOnWriteArrayList<Execution> executions = new CopyOnWriteArrayList<>();
        final ExecutorService executor = Executors.newSingleThreadExecutor(action -> new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT);
            action.run();
        }, "Censor-Isolated-SDK"));
        boolean terminated;
        final boolean profiling;
        RecordedSdkExecutor(boolean profiling) { this.profiling = profiling; }
        @Override public void execute(Runnable command) {
            int id = submitted.incrementAndGet();
            executor.execute(() -> {
                Execution execution = new Execution(id);
                if (profiling) Trace.beginSection("SubHubOcrSdkCommand-" + id);
                try { command.run(); }
                finally {
                    if (profiling) Trace.endSection();
                    execution.cpuFinished = Debug.threadCpuTimeNanos();
                    execution.finished = System.nanoTime();
                    if (executions.size() < 64) executions.add(execution); else dropped.incrementAndGet();
                }
            });
        }
        void shutdown() throws InterruptedException {
            executor.shutdown();
            terminated = executor.awaitTermination(15, TimeUnit.SECONDS);
        }
        boolean qualified() {
            if (!terminated || dropped.get() != 0 || executions.size() != submitted.get() || executions.size() < 3) return false;
            for (Execution execution : executions) if (execution.priority != 0 || execution.finished < execution.entered) return false;
            return true;
        }
        JSONObject record() throws Exception {
            JSONArray rows = new JSONArray();
            for (Execution execution : executions) rows.put(new JSONObject().put("id", execution.id)
                    .put("threadId", execution.threadId).put("threadName", execution.threadName)
                    .put("entryPriority", execution.priority).put("enteredNanos", execution.entered)
                    .put("finishedNanos", execution.finished).put("cpuEnteredNanos", execution.cpuEntered)
                    .put("cpuFinishedNanos", execution.cpuFinished));
            return new JSONObject().put("record", "SDK_EXECUTOR").put("mode", "owned-normal")
                    .put("submittedCommands", submitted.get()).put("completedCommands", executions.size())
                    .put("droppedCommands", dropped.get()).put("terminated", terminated).put("executions", rows);
        }
    }

    private static final class Execution {
        final int id, threadId, priority;
        final String threadName;
        final long entered, cpuEntered;
        long finished, cpuFinished;
        Execution(int id) {
            this.id = id; threadId = Process.myTid(); priority = Process.getThreadPriority(threadId);
            threadName = Thread.currentThread().getName(); entered = System.nanoTime(); cpuEntered = Debug.threadCpuTimeNanos();
        }
    }

    private static String digest(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[65536];
        try (FileInputStream input = new FileInputStream(file)) {
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return hex(digest.digest());
    }

    private static String rasterDigest(Bitmap bitmap) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        int[] row = new int[bitmap.getWidth()];
        byte[] bytes = new byte[row.length * 4];
        for (int y = 0; y < bitmap.getHeight(); y++) {
            bitmap.getPixels(row, 0, row.length, 0, y, row.length, 1);
            for (int x = 0; x < row.length; x++) {
                int pixel = row[x], offset = x * 4;
                bytes[offset] = (byte) (pixel >>> 24); bytes[offset + 1] = (byte) (pixel >>> 16);
                bytes[offset + 2] = (byte) (pixel >>> 8); bytes[offset + 3] = (byte) pixel;
            }
            digest.update(bytes);
        }
        return hex(digest.digest());
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
        return result.toString();
    }
}
