package com.subhub.app.detection;

import static org.junit.Assert.*;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.censor.engine.VisualInferencePolicy;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Backend capability only: no capture service, UI automation, scene retention or pixel clock. */
@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion = 35)
public final class BoundedVisualOverlapAndroidTest {
    private static final String PHOTO_SHA = "88431cd9653ccd539741b555fb0a46b61558b301d4110412b5bc28b5e3ea6cb5";
    private static final String MODEL_SHA = "e41c73a468045acd9f52c33430c5c5dc16aad948782a10b4f55d2ae3cd7905b7";
    private final JSONArray rows = new JSONArray();

    @Test public void wholeQualityAndFreshFastUseIdenticalBoundedCpuContexts() throws Throwable {
        Bundle args = InstrumentationRegistry.getArguments();
        Assume.assumeTrue("Explicit native capability opt-in", "true".equals(args.getString("boundedCpu")));
        String session = args.getString("boundedCpuSession", "");
        assertTrue("Literal session required", session.matches("[a-f0-9]{32}"));
        assertEquals("Owned four-CPU guest only", 4, Runtime.getRuntime().availableProcessors());
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File directory = new File(context.getFilesDir(), "censor-lab/bounded-cpu/" + session);
        assertTrue(directory.isDirectory());
        File output = new File(directory, "result.json");
        assertTrue("Preserve prior native evidence", output.createNewFile());
        JSONObject report = new JSONObject().put("schemaVersion", 1).put("session", session)
                .put("pid", Process.myPid()).put("clockDomain", "elapsedRealtimeNanos")
                .put("actualAccessibility", false).put("pixelAcceptance", false)
                .put("nativeCallOverlapIndependentlyVerified", false);
        ExecutorService fastExecutor = Executors.newSingleThreadExecutor(task -> new Thread(task, "Cen-F-call"));
        ExecutorService qualityExecutor = Executors.newSingleThreadExecutor(task -> new Thread(task, "Cen-Q-call"));
        AtomicReference<DetectionEngine> fastOwned = new AtomicReference<>(), qualityOwned = new AtomicReference<>();
        Bitmap source = null;
        Throwable failure = null;
        boolean callersTerminated = false;
        try {
            File photo = new File(directory, "positive.png");
            assertEquals(791555L, photo.length());
            try (InputStream input = new FileInputStream(photo)) { assertEquals(PHOTO_SHA, digest(input)); }
            try (InputStream input = context.getAssets().open("320n_fp16.onnx")) { assertEquals(MODEL_SHA, digest(input)); }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.ARGB_8888; options.inScaled = false;
            source = BitmapFactory.decodeFile(photo.getAbsolutePath(), options);
            assertNotNull(source); assertEquals(512, source.getWidth()); assertEquals(512, source.getHeight());
            assertEquals(Bitmap.Config.ARGB_8888, source.getConfig());
            Bitmap selected = source;
            String originalPixels = pixels(source);
            DetectorConfig configured = configuration();
            DetectorConfig fastConfig = VisualInferencePolicy.fast(configured);
            DetectorConfig qualityConfig = VisualInferencePolicy.quality(configured);
            assertEquals(3, fastConfig.getInferenceThreads()); assertEquals(3, qualityConfig.getInferenceThreads());
            report.put("photoSha256", PHOTO_SHA).put("modelSha256", MODEL_SHA).put("pixelSha256", originalPixels)
                    .put("sourceSize", new JSONArray(new int[]{512, 512}))
                    .put("crop", new JSONArray(new int[]{0, 0, 512, 512}))
                    .put("fastTensor", new JSONArray(new int[]{320, 320}))
                    .put("qualityTensor", new JSONArray(new int[]{480, 480}))
                    .put("configuredThreadsPerLane", 3).put("effectiveNativeThreads", new JSONArray(new int[]{2, 1}))
                    .put("inputWorkers", new JSONArray(new int[]{2, 0})).put("intraOpSpinning", false)
                    .put("enabledCategories", new JSONArray(configured.getEnabledCategories()))
                    .put("confidence", configured.getConfidenceThreshold()).put("minSize", configured.getMinDetectionSize())
                    .put("padding", configured.getBoxPadding());
            // Constructors load the environment before this baseline; only initialize on each owner.
            get(fastExecutor.submit(() -> { fastOwned.set(new DetectionEngine(context, fastConfig, true, false, CpuExecutionBudget.fast())); return null; }));
            get(qualityExecutor.submit(() -> { qualityOwned.set(new DetectionEngine(context, qualityConfig, false, false, CpuExecutionBudget.quality())); return null; }));
            inventory("before-session-initialization");
            get(fastExecutor.submit(() -> { fastOwned.get().initializeForProvider("CPU"); return null; }));
            inventory("after-fast-session-initialization");
            get(qualityExecutor.submit(() -> { qualityOwned.get().initializeForProvider("CPU"); return null; }));
            inventory("after-both-session-initialization");
            assertEquals("CPU", fastOwned.get().getActiveProvider()); assertEquals("CPU", qualityOwned.get().getActiveProvider());
            get(fastExecutor.submit(() -> call("warm", "FAST", fastOwned.get(), selected, 320, null)));
            get(qualityExecutor.submit(() -> call("warm", "QUALITY", qualityOwned.get(), selected, 480, null)));
            inventory("warmed");
            // Rejected reconfiguration must not close/rebuild a live context or change its preset.
            get(fastExecutor.submit(() -> {
                assertThrows(IllegalArgumentException.class, () -> fastOwned.get().setConfig(fastConfig.toBuilder().inferenceThreads(2).build()));
                assertThrows(IllegalArgumentException.class, () -> fastOwned.get().initializeForProvider("NNAPI"));
                assertThrows(IllegalStateException.class, () -> fastOwned.get().initialize());
                assertThrows(IllegalStateException.class, () -> fastOwned.get().initializeWithoutBenchmark("CPU"));
                assertEquals("CPU", fastOwned.get().getActiveProvider());
                return null;
            }));
            Call serialQuality = get(qualityExecutor.submit(() -> call("serial", "QUALITY", qualityOwned.get(), selected, 480, null)));
            Call serialFast = get(fastExecutor.submit(() -> call("serial", "FAST", fastOwned.get(), selected, 320, null)));
            assertTrue(serialQuality.ended <= serialFast.began);
            CountDownLatch qualityEntered = new CountDownLatch(1);
            Future<Call> qualityFuture = qualityExecutor.submit(() -> call("overlap", "QUALITY", qualityOwned.get(), selected, 480, qualityEntered));
            assertTrue("Quality owner entered its real controlled call", qualityEntered.await(10, TimeUnit.SECONDS));
            Call overlapFast = get(fastExecutor.submit(() -> call("overlap", "FAST", fastOwned.get(), selected, 320, null)));
            inventory("after-overlap-fast-return");
            Call overlapQuality = get(qualityFuture);
            assertTrue("No post-run hold may manufacture useful FAST progress", overlapFast.ended < overlapQuality.ended);
            assertEquals("Preserve the complete QUALITY output including float bits and order", serialQuality.detections.toString(), overlapQuality.detections.toString());
            assertEquals("Preserve the complete FAST output", serialFast.detections.toString(), overlapFast.detections.toString());
            assertTrue("Positive quality source must remain useful", serialQuality.detections.length() > 0);
            assertTrue("Positive FAST source cannot pass through two empty outputs", serialFast.detections.length() > 0);
            assertEquals("Shared source unchanged", originalPixels, pixels(selected));
            report.put("softwareTaskOverlap", true).put("sameQualityOutput", true).put("sameFastOutput", true)
                    .put("serialQuality", serialQuality.json()).put("serialFast", serialFast.json())
                    .put("overlapQuality", overlapQuality.json()).put("overlapFast", overlapFast.json());
        } catch (Throwable error) {
            failure = error;
        } finally {
            // Close on each same serial owner, behind any actual inference; never recycle early.
            Future<?> fastClose = null, qualityClose = null;
            boolean fastReturned = false, qualityReturned = false, fastStopped = false, qualityStopped = false;
            try { fastClose = fastExecutor.submit(() -> closeAndRefuseRenewal(fastOwned.get())); }
            catch (Throwable error) { failure = merge(failure, error); }
            try { qualityClose = qualityExecutor.submit(() -> closeAndRefuseRenewal(qualityOwned.get())); }
            catch (Throwable error) { failure = merge(failure, error); }
            for (Future<?> future : Arrays.asList(fastClose, qualityClose)) {
                if (future == null) continue;
                try {
                    get(future);
                    if (future == fastClose) fastReturned = true;
                    else qualityReturned = true;
                }
                catch (Throwable error) { failure = merge(failure, error); }
            }
            try { fastExecutor.shutdown(); } catch (Throwable error) { failure = merge(failure, error); }
            try { qualityExecutor.shutdown(); } catch (Throwable error) { failure = merge(failure, error); }
            try { fastStopped = fastExecutor.awaitTermination(20, TimeUnit.SECONDS); }
            catch (Throwable error) { failure = merge(failure, error); }
            try { qualityStopped = qualityExecutor.awaitTermination(20, TimeUnit.SECONDS); }
            catch (Throwable error) { failure = merge(failure, error); }
            callersTerminated = fastStopped && qualityStopped;
            if (!callersTerminated) failure = merge(failure, new AssertionError("Actual caller termination incomplete"));
            try { inventory("after-detector-and-caller-retirement"); }
            catch (Throwable error) { failure = merge(failure, error); }
            boolean recycled = false;
            if (callersTerminated && source != null) {
                try { source.recycle(); recycled = true; }
                catch (Throwable error) { failure = merge(failure, error); }
            }
            try {
                report.put("rows", rows).put("rawRowCount", rows.length()).put("callersTerminated", callersTerminated)
                        .put("fastCloseSubmitted", fastClose != null).put("qualityCloseSubmitted", qualityClose != null)
                        .put("fastRetirementReturned", fastReturned).put("qualityRetirementReturned", qualityReturned)
                        .put("fastExecutorTerminated", fastStopped).put("qualityExecutorTerminated", qualityStopped)
                        .put("sourceRecycledAfterCallerTermination", recycled).put("bodyAndCleanupPassed", failure == null)
                        .put("failureType", failure == null ? JSONObject.NULL : failure.getClass().getName());
                try (FileOutputStream stream = new FileOutputStream(output)) {
                    stream.write(report.toString().getBytes(StandardCharsets.UTF_8)); stream.getFD().sync();
                }
            } catch (Throwable error) { failure = merge(failure, error); }
        }
        if (failure != null) throw failure;
    }

    private static void closeAndRefuseRenewal(DetectionEngine engine) {
        if (engine == null) return;
        engine.close();
        assertThrows(IllegalStateException.class, () -> engine.initializeForProvider("CPU"));
    }

    private Call call(String phase, String lane, DetectionEngine engine, Bitmap source, int tensor, CountDownLatch entered) throws Exception {
        long began = SystemClock.elapsedRealtimeNanos();
        AtomicInteger bindings = new AtomicInteger();
        if (entered != null) entered.countDown();
        DetectionEngine.ControlledResult result = engine.detectControlled(source, 512, 512, tensor, tensor,
                () -> false, cancellation -> bindings.incrementAndGet());
        long ended = SystemClock.elapsedRealtimeNanos();
        assertTrue(result.complete); assertFalse(result.cancelled); assertEquals(1, bindings.get());
        JSONArray detections = new JSONArray();
        for (Detection detection : result.detections) {
            BBox box = detection.getBox();
            detections.put(new JSONObject().put("class", detection.getClassName()).put("category", detection.getCategory())
                    .put("confidenceBits", Float.floatToRawIntBits(detection.getConfidence()))
                    .put("box", new JSONArray(new int[]{box.getX(), box.getY(), box.getWidth(), box.getHeight()})));
        }
        Call value = new Call(phase, lane, began, ended, Process.myTid(), detections, result);
        record("CALL", value.json());
        return value;
    }

    private static final class Call {
        final String phase, lane;
        final long began, ended;
        final int tid;
        final JSONArray detections;
        final DetectionEngine.ControlledResult result;
        Call(String phase, String lane, long began, long ended, int tid, JSONArray detections, DetectionEngine.ControlledResult result) {
            this.phase = phase; this.lane = lane; this.began = began; this.ended = ended; this.tid = tid;
            this.detections = detections; this.result = result;
        }
        JSONObject json() throws Exception {
            return new JSONObject().put("phase", phase).put("lane", lane).put("beganElapsedNanos", began)
                    .put("endedElapsedNanos", ended).put("tid", tid).put("detections", detections)
                    .put("preprocessMillis", result.preprocessMillis).put("runtimeMillis", result.runtimeMillis)
                    .put("postprocessMillis", result.postprocessMillis).put("complete", result.complete).put("cancelled", result.cancelled);
        }
    }

    private void inventory(String phase) throws Exception {
        long began = SystemClock.elapsedRealtimeNanos();
        File[] tasks = new File("/proc/self/task").listFiles();
        assertNotNull(tasks); assertTrue(tasks.length > 0 && tasks.length <= 512);
        Arrays.sort(tasks, (first, second) -> Integer.compare(Integer.parseInt(first.getName()), Integer.parseInt(second.getName())));
        JSONArray entries = new JSONArray();
        for (File task : tasks) {
            JSONObject row = new JSONObject().put("tid", Integer.parseInt(task.getName()));
            try {
                String status = text(new File(task, "status"), 65536);
                String allowed = null;
                for (String line : status.split("\\r?\\n")) if (line.startsWith("Cpus_allowed_list:")) {
                    assertNull("Unique allowed-CPU field", allowed); allowed = line.substring(line.indexOf(':') + 1).trim();
                }
                assertNotNull("Actual allowed CPUs required", allowed);
                row.put("name", text(new File(task, "comm"), 4096).trim()).put("allowedCpus", allowed)
                        .put("cpuset", text(new File(task, "cpuset"), 4096).trim()).put("disappearedDuringRead", false);
            } catch (IOException error) {
                if (task.exists()) throw error;
                row.put("disappearedDuringRead", true);
            }
            entries.put(row);
        }
        record("INVENTORY", new JSONObject().put("phase", phase).put("beganElapsedNanos", began)
                .put("endedElapsedNanos", SystemClock.elapsedRealtimeNanos()).put("listedThreads", tasks.length)
                .put("threads", entries).put("onlineCpus", text(new File("/sys/devices/system/cpu/online"), 4096).trim()));
    }

    private synchronized void record(String kind, JSONObject value) throws Exception {
        rows.put(new JSONObject().put("ordinal", rows.length() + 1).put("kind", kind)
                .put("observedElapsedNanos", SystemClock.elapsedRealtimeNanos()).put("value", value));
    }

    private static DetectorConfig configuration() {
        Set<String> categories = new LinkedHashSet<>();
        for (int index = 0; index < NudeNetClassCatalog.CLASS_COUNT; index++) categories.addAll(NudeNetClassCatalog.byIndex(index).getCategories());
        categories.remove("face_female"); categories.remove("face_male");
        return DetectorConfig.builder().enabledCategories(categories).confidenceThreshold(.18f).minDetectionSize(6)
                .boxPadding(.14f).inferenceThreads(3).inferenceResolution(480).modelFilename("320n_fp16.onnx").build();
    }

    private static <T> T get(Future<T> future) throws Exception { return future.get(30, TimeUnit.SECONDS); }

    private static Throwable merge(Throwable first, Throwable next) {
        if (first == null) return next;
        if (first != next) first.addSuppressed(next);
        return first;
    }

    private static String digest(InputStream stream) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256"); byte[] buffer = new byte[8192]; int count;
        while ((count = stream.read(buffer)) != -1) digest.update(buffer, 0, count);
        return hex(digest.digest());
    }

    private static String pixels(Bitmap bitmap) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256"); int[] row = new int[512]; byte[] bytes = new byte[2048];
        for (int y = 0; y < 512; y++) {
            bitmap.getPixels(row, 0, 512, 0, y, 512, 1);
            for (int x = 0; x < 512; x++) {
                int value = row[x];
                for (int shift = 0; shift < 4; shift++) bytes[x * 4 + shift] = (byte) (value >>> (24 - shift * 8));
            }
            digest.update(bytes);
        }
        return hex(digest.digest());
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) { result.append(Character.forDigit((value >>> 4) & 15, 16)); result.append(Character.forDigit(value & 15, 16)); }
        return result.toString();
    }

    private static String text(File file, int limit) throws IOException {
        try (InputStream stream = new FileInputStream(file); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024]; int count;
            while ((count = stream.read(buffer)) != -1) {
                if (bytes.size() + count > limit) throw new IOException("Bounded native metadata exceeded limit");
                bytes.write(buffer, 0, count);
            }
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
