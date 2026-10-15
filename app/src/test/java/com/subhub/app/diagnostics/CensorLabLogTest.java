package com.subhub.app.diagnostics;

import org.junit.Test;
import static org.junit.Assert.*;

public final class CensorLabLogTest {
    @Test public void nativeCostStartAndTerminalAreSelectedOnlyForTheActualReplacementProducer() {
        for (String prefix : new String[]{"REPLACEMENT_NATIVE_COST ", "REPLACEMENT_NATIVE_COST_START "}) {
            assertTrue(CensorLabLog.allowed("ScreenshotA11y", prefix + "{}"));
            assertFalse(CensorLabLog.allowed("ScreenCaptureService", prefix + "{}"));
        }
    }
    @Test public void nativeTypedPublicationsAndFutureSchemasReachTheStrictReader() {
        assertTrue(CensorLabLog.allowed("ScreenshotA11y", "REPLACEMENT_NATIVE {\"schemaVersion\":1}"));
        assertTrue(CensorLabLog.allowed("ScreenshotA11y", "REPLACEMENT_NATIVE {\"schemaVersion\":2}"));
        assertFalse(CensorLabLog.allowed("ScreenCaptureService", "REPLACEMENT_NATIVE {}"));
    }

    @Test public void personTelemetryIsMirroredForBothCaptureModes() {
        assertTrue(CensorLabLog.allowed("PersonInference", "PERSON_MODEL v=1 run=1 totalMs=87"));
        for (String tag : new String[]{"ScreenshotA11y", "ScreenCaptureService"}) {
            assertTrue(CensorLabLog.allowed(tag, "PERSON_PROVISIONAL v=1 source=100 captureAgeMs=121"));
            assertTrue(CensorLabLog.allowed(tag, "PERSON_PUBLISH v=1 run=1 source=100 applied=true"));
        }
    }

    @Test public void unrelatedTagsAndMessagesRemainExcluded() {
        assertFalse(CensorLabLog.allowed("Other", "PERSON_MODEL v=1 run=1"));
        assertFalse(CensorLabLog.allowed("PersonInference", "private diagnostic text"));
        assertFalse(CensorLabLog.allowed("ScreenCaptureService", "unrelated text"));
        assertFalse(CensorLabLog.allowed("PersonInference", null));
        assertTrue(CensorLabLog.allowed("ScreenshotA11y", "OVERLAY_PUBLISH pass=fast"));
    }

    @Test public void futureSchemaMustReachParserInsteadOfSilentlyDisappearing() {
        assertTrue(CensorLabLog.allowed("PersonInference", "PERSON_MODEL v=2 future=1"));
        assertTrue(CensorLabLog.allowed("ScreenshotA11y", "PERSON_PUBLISH v=2 future=1"));
        assertTrue(CensorLabLog.allowed("ScreenshotA11y", "CAPTURE_ADMISSION v=1 action=dispatch"));
        assertTrue(CensorLabLog.allowed("ScreenshotA11y", "CAPTURE_ADMISSION v=2 future=1"));
        assertFalse(CensorLabLog.allowed("ScreenCaptureService", "CAPTURE_ADMISSION v=1"));
    }

    @Test public void scrollReceiptsReachTheSelectedReplacementReaderOnlyFromItsService() {
        assertTrue(CensorLabLog.allowed("ScreenshotA11y", "REPLACEMENT_SCROLL {\"schemaVersion\":1}"));
        assertTrue(CensorLabLog.allowed("ScreenshotA11y", "REPLACEMENT_SCROLL {\"schemaVersion\":2}"));
        assertFalse(CensorLabLog.allowed("ScreenCaptureService", "REPLACEMENT_SCROLL {}"));
        assertFalse(CensorLabLog.allowed("Other", "REPLACEMENT_SCROLL {}"));
    }
}
