package com.subhub.app.detection.text;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public final class NativeTextContentKeyTest {
    @Test public void opaqueContentBindingCannotCollapseDistinctUnpairedUtf16Units() {
        String first = "porn \ud800", second = "porn \ud801";
        assertNotEquals(first, second);
        assertArrayEquals(first.getBytes(StandardCharsets.UTF_8), second.getBytes(StandardCharsets.UTF_8));
        assertNotEquals("Exact content binding must not use lossy UTF-8 replacement", NativeTextReader.digest(first), NativeTextReader.digest(second));
    }

    @Test public void oldJavaHashCollisionIsStillDistinctAndOutputStaysOpaque() {
        assertEquals("porn ap".hashCode(), "porn c2".hashCode());
        assertNotEquals(NativeTextReader.digest("porn ap"), NativeTextReader.digest("porn c2"));
        assertTrue(NativeTextReader.digest("bounded content").matches("[0-9a-f]{64}"));
    }
}
