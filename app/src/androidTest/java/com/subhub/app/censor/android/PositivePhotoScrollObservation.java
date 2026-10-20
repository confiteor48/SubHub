package com.subhub.app.censor.android;

import static org.junit.Assert.*;
import android.app.UiAutomation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/** Bounded observer only; PNG encoding occurs after motion and managed engine drain. */
final class PositivePhotoScrollObservation {
    private static final long[] OFFSETS = {430,1790,3320,4510,6740,8390,9480,11380,12860,15710,18830,22570};
    private static final long MAX_RETAINED_BYTES = 192L * 1024 * 1024;
    private final Context context;
    private final UiAutomation ui;
    private final ProductFirstObservedCensor recorder;
    private final CensorProductSession owner;
    private final List<Bitmap> pixels = new ArrayList<>();
    private final JSONArray samples = new JSONArray();
    private long retainedBytes, commandAt = -1, start = -1;
    private JSONObject initial, finished;
    private boolean completed;

    PositivePhotoScrollObservation(Context context, UiAutomation ui, ProductFirstObservedCensor recorder, CensorProductSession owner) {
        this.context = context; this.ui = ui; this.recorder = recorder; this.owner = owner;
    }

    void run() throws Exception {
        assertTrue(recorder.visible()); assertNotNull(owner);
        commandAt = ProductFirstObservedCensor.now();
        context.startActivity(new Intent().setComponent(new ComponentName(ProductFirstObservedCensor.TARGET, ProductTargetFixtureActivity.class.getName()))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION).putExtra("positiveScrollStart", true));
        while (start < 0 && ProductFirstObservedCensor.now() < commandAt + 3000) {
            initial = inspect();
            if (initial != null) start = initial.getLong("startedAtMillis");
            if (start < 0) Thread.sleep(10);
        }
        assertTrue("One actual target start within fixed command budget", start >= commandAt && start < commandAt + 3000);
        for (int index = 0; index < OFFSETS.length; index++) {
            long requested = start + OFFSETS[index];
            while (ProductFirstObservedCensor.now() < requested) Thread.sleep(5);
            JSONObject sample = new JSONObject().put("index", index).put("requestedAtMillis", requested)
                    .put("filename", String.format(java.util.Locale.ROOT, "scroll-%02d.png", index));
            samples.put(sample); pixels.add(null);
            long attempt = ProductFirstObservedCensor.now();
            sample.put("attemptAtMillis", attempt).put("latenessMillis", attempt - requested);
            if (attempt >= start + PositivePhotoScrollMotion.DURATION_MILLIS) { sample.put("missed", true); continue; }
            sample.put("missed", false).put("targetBefore", nullable(inspect())).put("contextBefore", recorder.scrollContext(owner));
            Bitmap captured = null;
            try {
                sample.put("readStartedAtMillis", ProductFirstObservedCensor.now());
                captured = ui.takeScreenshot();
                sample.put("readEndedAtMillis", ProductFirstObservedCensor.now()).put("screenshot", captured != null);
                if (captured != null) {
                    long bytes = captured.getAllocationByteCount();
                    sample.put("width", captured.getWidth()).put("height", captured.getHeight()).put("allocationBytes", bytes);
                    assertTrue("Bounded retained screenshot allocation", bytes > 0 && retainedBytes + bytes <= MAX_RETAINED_BYTES);
                    retainedBytes += bytes; pixels.set(index, captured); captured = null;
                }
                sample.put("targetAfter", nullable(inspect())).put("contextAfter", recorder.scrollContext(owner));
            } catch (Exception | Error failure) {
                sample.put("failureType", failure.getClass().getName()); throw failure;
            } finally { if (captured != null) captured.recycle(); }
        }
        long deadline = start + PositivePhotoScrollMotion.DURATION_MILLIS + 3000;
        while (ProductFirstObservedCensor.now() < deadline) {
            finished = inspect();
            if (finished != null && finished.getLong("endedAtMillis") >= 0) break;
            Thread.sleep(10);
        }
        assertNotNull(finished); assertTrue("Actual bounded motion finish", finished.getLong("endedAtMillis") >= start + 24000);
        assertFalse(finished.getBoolean("overflow"));
        assertEquals(initial.getInt("pid"), finished.getInt("pid"));
        assertEquals(start, finished.getLong("startedAtMillis"));
        assertEquals(4 * finished.getInt("viewportHeight"), finished.getInt("maximum"));
        assertEquals(0, finished.getInt("scrollY"));
        assertEquals(7, finished.getInt("reversals"));
        completed = true;
    }

    private JSONObject inspect() throws Exception {
        AccessibilityNodeInfo root = ui.getRootInActiveWindow();
        if (root == null) return null;
        List<AccessibilityNodeInfo> frames = new ArrayList<>();
        try {
            if (!ProductFirstObservedCensor.TARGET.contentEquals(root.getPackageName())) return null;
            frames = root.findAccessibilityNodeInfosByViewId("android:id/custom");
            if (frames.size() != 1 || !frames.get(0).refresh()) return null;
            Bundle bundle = frames.get(0).getExtras().getBundle(ProductTargetFixtureActivity.VISUAL_SCROLL);
            if (bundle == null) return null;
            JSONObject value = new JSONObject().put("windowId", root.getWindowId());
            for (String key : bundle.keySet()) {
                Object item = bundle.get(key);
                value.put(key, item instanceof long[] || item instanceof int[] ? new JSONArray(item) : item);
            }
            return value;
        } finally {
            for (AccessibilityNodeInfo frame : frames) if (frame != root) frame.recycle();
            root.recycle();
        }
    }

    void exportAndRecycle() throws Exception {
        Throwable failure = null; int exported = 0, recycled = 0;
        try {
            for (int index = 0; index < pixels.size(); index++) {
                Bitmap bitmap = pixels.get(index); if (bitmap == null) continue;
                JSONObject sample = samples.getJSONObject(index);
                try {
                    File path = new File(recorder.outputDirectory(), sample.getString("filename"));
                    assertTrue(path.createNewFile());
                    try (FileOutputStream stream = new FileOutputStream(path)) {
                        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream));
                    }
                    sample.put("exported", true); exported++;
                } catch (Exception | Error error) {
                    sample.put("exported", false).put("exportFailureType", error.getClass().getName());
                    if (failure == null) failure = error; else failure.addSuppressed(error);
                } finally { bitmap.recycle(); recycled++; pixels.set(index, null); }
            }
        } finally {
            // Outer cleanup is independent of per-image JSON/IO failures.
            for (Bitmap bitmap : pixels) if (bitmap != null && !bitmap.isRecycled()) { bitmap.recycle(); recycled++; }
            pixels.clear();
            JSONObject result = new JSONObject().put("schemaVersion", 1).put("commandAtMillis", commandAt)
                    .put("startedAtMillis", start).put("offsetsMillis", new JSONArray(OFFSETS))
                    .put("initial", nullable(initial)).put("finished", nullable(finished)).put("samples", samples)
                    .put("completed", completed).put("exported", exported).put("recycled", recycled)
                    .put("retainedBytes", retainedBytes).put("retainedByteLimit", MAX_RETAINED_BYTES)
                    .put("capturedFramePixelsKnown", false).put("compositorPostAttributionKnown", false)
                    .put("copyIdentityKnown", false).put("physicalAcceptance", false);
            Files.write(new File(recorder.outputDirectory(), "scroll-observations.json").toPath(), result.toString().getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW);
        }
        if (failure instanceof Error) throw (Error) failure;
        if (failure != null) throw (Exception) failure;
    }

    private static Object nullable(Object value) { return value == null ? JSONObject.NULL : value; }
}
