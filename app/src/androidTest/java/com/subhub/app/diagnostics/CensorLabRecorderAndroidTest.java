package com.subhub.app.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import androidx.core.content.FileProvider;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@RunWith(AndroidJUnit4.class)
public final class CensorLabRecorderAndroidTest {
    @Test public void recordsSanitizedTraceAndExportsPortableBundle() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        if (CensorLabRecorder.isActive()) CensorLabRecorder.stop(context);

        CensorLabRecorder.SessionState started = CensorLabRecorder.start(context);
        assertTrue(started.active);
        assertNotNull(started.id);
        CensorLabLog.i("ScreenshotA11y", "SCROLL_EVENT id=9 source=absolute dy=120");
        CensorLabLog.i("CensorMotion", "DRAW seq=3 inputToDrawMs=8 viewportLead=0,2");
        CensorLabLog.i("PersonInference", "PERSON_MODEL v=1 run=1 totalMs=87 prepMs=12 runtimeMs=73 postMs=2 cancelled=false success=true");
        CensorLabLog.i("ScreenshotA11y", "PERSON_PROVISIONAL v=1 source=100 captureAgeMs=121");
        CensorLabLog.i("ScreenshotA11y", "PERSON_PUBLISH v=1 run=1 source=100 captureAgeMs=207 applied=true submitted=1 dropped=0 preemptions=0 denied=0");
        CensorLabLog.i("ScreenshotA11y",
                "Recognition activated for foreground package com.example.private");
        CensorLabRecorder.mark("UI\nMARKER");

        CensorLabRecorder.CompletedSession completed = CensorLabRecorder.stop(context);
        assertNotNull(completed);
        assertTrue(completed.manifest.isFile());
        assertTrue(completed.trace.isFile());
        assertTrue(completed.eventCount >= 5);
        String trace = read(completed.trace);
        assertTrue(trace.contains("SCROLL_EVENT"));
        assertTrue(trace.contains("CensorMotion"));
        assertTrue(trace.contains("PERSON_MODEL v=1"));
        assertTrue(trace.contains("PERSON_PROVISIONAL v=1"));
        assertTrue(trace.contains("PERSON_PUBLISH v=1"));
        assertTrue(trace.contains("UI MARKER"));
        assertFalse(trace.contains("com.example.private"));

        JSONObject manifest = new JSONObject(read(completed.manifest));
        assertEquals(1, manifest.getInt("schemaVersion"));
        assertEquals(new com.subhub.app.settings.SettingsRepository(context).loadCensorCoverage().preferenceValue(),
                manifest.getJSONObject("capture").getString("censorCoverage"));
        assertFalse(manifest.getJSONObject("privacy").getBoolean("pixelCapture"));
        assertFalse(manifest.getJSONObject("privacy").getBoolean("ocrTextStored"));
        assertFalse(manifest.getJSONObject("privacy").getBoolean("foregroundPackageStored"));

        File bundle = CensorLabBundleExporter.export(context, completed, false);
        assertTrue(bundle.isFile());
        Set<String> entries = zipEntries(bundle);
        assertEquals(Set.of("manifest.json", "trace.ndjson", "README.txt"), entries);
        assertNotNull(FileProvider.getUriForFile(context,
                context.getPackageName() + ".updates", bundle));

        CensorLabRecorder.start(context);
        CensorLabRecorder.markVideoStarted(720, 1600, 60, 8_000_000);
        File video = CensorLabRecorder.activeVideoFile(context);
        try (FileOutputStream output = new FileOutputStream(video)) {
            output.write(new byte[]{0, 0, 0, 20, 'f', 't', 'y', 'p'});
        }
        CensorLabRecorder.markVideoStopped(true, video.length(), "test-stop");
        CensorLabRecorder.CompletedSession recorded = CensorLabRecorder.stop(context);
        assertNotNull(recorded.video);
        JSONObject recordedManifest = new JSONObject(read(recorded.manifest));
        assertTrue(recordedManifest.getBoolean("videoAttached"));
        assertTrue(recordedManifest.getJSONObject("privacy").getBoolean("pixelCapture"));
        assertEquals("mediaprojection-display",
                recordedManifest.getJSONObject("recording").getString("videoKind"));

        File videoBundle = CensorLabBundleExporter.export(context, recorded, true);
        assertTrue(zipEntries(videoBundle).contains("screen-recording.mp4"));

        File telemetryFromRecordedSession = CensorLabBundleExporter.export(
                context, recorded, false);
        assertFalse(zipEntries(telemetryFromRecordedSession).contains("screen-recording.mp4"));
        JSONObject telemetryManifest = new JSONObject(
                zipEntry(telemetryFromRecordedSession, "manifest.json"));
        assertFalse(telemetryManifest.getBoolean("videoAttached"));
        assertFalse(telemetryManifest.getJSONObject("privacy").getBoolean("pixelCapture"));
        JSONObject telemetryRecording = telemetryManifest.getJSONObject("recording");
        assertFalse(telemetryRecording.getBoolean("inAppScreenRecording"));
        assertTrue(telemetryRecording.getBoolean("sessionUsedScreenEncoder"));
        assertEquals(0L, telemetryRecording.getLong("bytes"));
        assertEquals(0, telemetryRecording.getInt("width"));
        assertTrue(telemetryRecording.getString("measurementOverhead")
                .contains("video omitted"));
    }

    @Test public void temporaryCensorSessionKeepsRuntimePathAndOwnershipClock() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        if (CensorLabRecorder.isActive()) CensorLabRecorder.stop(context);
        CensorLabRecorder.start(context);
        CensorLabRecorder.markCensorSession(true);
        CensorLabRecorder.markCensorSession(false);
        CensorLabRecorder.CompletedSession completed = CensorLabRecorder.stop(context);
        JSONObject manifest = new JSONObject(read(completed.manifest));
        JSONObject session = manifest.getJSONObject("recording").getJSONObject("censorSession");
        assertEquals("temporary-censor-only", session.getString("kind"));
        assertTrue(session.getLong("startedElapsedNanos") >= manifest.getLong("startedElapsedNanos"));
        assertTrue(session.getLong("stoppedElapsedNanos") >= session.getLong("startedElapsedNanos"));
        assertTrue(manifest.getLong("stoppedElapsedNanos") >= session.getLong("stoppedElapsedNanos"));
        assertEquals("Accessibility screenshot", manifest.getJSONObject("capture").getString("runtimeMode"));
        String trace = read(completed.trace);
        assertTrue(trace.contains("MARKER CENSOR_SESSION_STARTED"));
        assertTrue(trace.contains("MARKER CENSOR_SESSION_STOPPED"));
        assertFalse(manifest.getJSONObject("recording").getBoolean("inAppScreenRecording"));
        JSONObject exported = new JSONObject(zipEntry(
                CensorLabBundleExporter.export(context, completed, false), "manifest.json"));
        assertEquals("temporary-censor-only", exported.getJSONObject("recording")
                .getJSONObject("censorSession").getString("kind"));
    }

    private static String read(File file) throws Exception {
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) result.append(line).append('\n');
        }
        return result.toString();
    }

    private static Set<String> zipEntries(File file) throws Exception {
        Set<String> names = new HashSet<>();
        try (ZipInputStream zip = new ZipInputStream(new FileInputStream(file))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) names.add(entry.getName());
        }
        return names;
    }

    private static String zipEntry(File file, String wanted) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(new FileInputStream(file))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!wanted.equals(entry.getName())) continue;
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[4 * 1024];
                int read;
                while ((read = zip.read(buffer)) >= 0) {
                    if (read > 0) bytes.write(buffer, 0, read);
                }
                return bytes.toString(StandardCharsets.UTF_8.name());
            }
        }
        throw new AssertionError("Missing ZIP entry " + wanted);
    }
}
