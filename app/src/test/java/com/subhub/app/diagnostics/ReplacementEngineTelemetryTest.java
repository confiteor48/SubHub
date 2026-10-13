package com.subhub.app.diagnostics;

import static org.junit.Assert.*;
import com.subhub.app.censor.engine.CensorEngine;
import com.subhub.app.censor.engine.SceneEngine;
import java.lang.reflect.Constructor;
import org.junit.Test;

public final class ReplacementEngineTelemetryTest {
    private CensorEngine.Trace row(CensorEngine.Stage stage, SceneEngine.Lane lane,
            long age, CensorEngine.WorkTiming work) throws Exception {
        Constructor<CensorEngine.Trace> factory = CensorEngine.Trace.class.getDeclaredConstructor(
                CensorEngine.Stage.class, long.class, long.class, SceneEngine.Lane.class,
                long.class, long.class, long.class, CensorEngine.WorkTiming.class);
        factory.setAccessible(true);
        return factory.newInstance(stage, 2L, 17L, lane, 100L, 7L, age, work);
    }

    @Test public void unknownPixelAndWorkClocksRemainUnknown() throws Exception {
        String value = ReplacementEngineTelemetry.trace(5, row(CensorEngine.Stage.CAPTURE_REQUESTED, null, -1, null));
        assertTrue(value.startsWith("REPLACEMENT_TRACE {\"schemaVersion\":1,"));
        assertTrue(value.contains("\"pixelAgeMillis\":-1"));
        assertTrue(value.contains("\"preprocessMillis\":-1,\"runtimeMillis\":-1,\"postprocessMillis\":-1"));
        assertTrue(value.contains("\"lane\":\"NONE\""));
        assertFalse(value.contains("compositor"));
    }

    @Test public void explicitWorkAndRequestLineageArePreserved() throws Exception {
        String value = ReplacementEngineTelemetry.trace(5, row(CensorEngine.Stage.JOB_COMPLETED, SceneEngine.Lane.FAST,
                32, new CensorEngine.WorkTiming(3, 17, 2)));
        assertTrue(value.contains("\"clockDomain\":\"UPTIME_MILLIS\""));
        assertTrue(value.contains("\"epoch\":2,\"imageRequest\":17"));
        assertTrue(value.contains("\"atMillis\":100,\"queueMillis\":7,\"pixelAgeMillis\":32"));
        assertTrue(value.contains("\"preprocessMillis\":3,\"runtimeMillis\":17,\"postprocessMillis\":2"));
    }

    @Test public void everyStageIsAnExplicitLiteral() throws Exception {
        for (CensorEngine.Stage stage : CensorEngine.Stage.values()) {
            String value = ReplacementEngineTelemetry.trace(5, row(stage, SceneEngine.Lane.TEXT, -1, null));
            assertTrue(value.contains("\"stage\":\"" + stage.name() + "\""));
            assertFalse(value.contains("\n"));
            assertTrue(value.length() < 2048);
        }
    }
}
