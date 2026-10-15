package com.subhub.app.diagnostics;

import android.util.Log;

/** Mirrors selected, sanitized performance log lines into an explicit Censor Lab session. */
public final class CensorLabLog {
    private static final String[] ALLOWED_PREFIXES = {
            "CALIBRATION_SCENE", "CALIBRATION_TOUCH", "CAPTURE_PHASE", "CAPTURE_ADMISSION", "FAST_DROP",
            "FRAME_MOTION", "INFERENCE_GATE",
            "OVERLAY_PUBLISH", "QUALITY_", "SCROLL_", "SETTLED_",
            "SCENE_", "SOURCE_FRAME_", "STARTUP", "TEXT_", "WORLD_CACHE_",
            "REPLACEMENT_TRACE ", "REPLACEMENT_POST ", "REPLACEMENT_SCROLL ", "REPLACEMENT_NATIVE ", "REPLACEMENT_NATIVE_COST ", "REPLACEMENT_NATIVE_COST_START "
    };

    private CensorLabLog() {}

    public static int i(String tag, String message) {
        int result = Log.i(tag, message);
        if (CensorLabRecorder.isActive() && allowed(tag, message)) {
            CensorLabRecorder.record(tag, message);
        }
        return result;
    }

    static boolean allowed(String tag, String message) {
        if (message == null) return false;
        if ("PersonInference".equals(tag)) return message.startsWith("PERSON_MODEL ");
        if ("ScreenshotA11y".equals(tag) || "ScreenCaptureService".equals(tag)) {
            if (message.startsWith("PERSON_PROVISIONAL ")
                    || message.startsWith("PERSON_PUBLISH ")) return true;
        }
        if ("CensorMotion".equals(tag)) return true;
        if (!"ScreenshotA11y".equals(tag)) return false;
        for (String prefix : ALLOWED_PREFIXES) {
            if (message.startsWith(prefix)) return true;
        }
        return false;
    }
}
