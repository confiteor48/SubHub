package com.subhub.app.detection.text;

import static org.junit.Assert.*;
import java.util.Random;
import org.junit.Test;

public final class TextOffsetMappingTest {
    @Test public void removedSeparatorDoesNotStretchTheMatchedWord() {
        TextOffsetMapping mapped = TextOffsetMapping.from("porn\n\n----------------");
        assertEquals("porn", mapped.text);
        assertEquals(0, mapped.start(0)); assertEquals(4, mapped.end(3));
    }

    @Test public void compatibilityExpansionAccentsAndAstralLettersKeepTheirRawRanges() {
        String raw = "  \ufb03 P\u00d6RN \ud835\udd61";
        TextOffsetMapping mapped = TextOffsetMapping.from(raw);
        assertEquals("ffi porn p", mapped.text);
        for (int i = 0; i < 3; i++) { assertEquals(2, mapped.start(i)); assertEquals(3, mapped.end(i)); }
        assertEquals(4, mapped.start(4)); assertEquals(8, mapped.end(7));
        assertEquals(9, mapped.start(9)); assertEquals(11, mapped.end(9));
    }

    @Test public void letterSpacingLeetAndHomoglyphsRetainIndividualUnitOrigins() {
        String raw = "-- p . 0 . \u0440 . n --";
        TextOffsetMapping mapped = TextOffsetMapping.from(raw);
        assertEquals("popn", mapped.text);
        assertEquals(3, mapped.start(0)); assertEquals(7, mapped.start(1));
        assertEquals(11, mapped.start(2)); assertEquals(15, mapped.start(3));
    }

    @Test public void originalNormalizationParityIncludesWhitespaceMarksAndMalformedUtf16() {
        String[] fragments = {"porn", "safe", "a b c", "p.u.s.s.y", "\ufb03", "\u0130", "\u212a",
                "\u0440\u043e\u0440\u043d", "@57", "\n\n---", "\u0301\u0345", "\ud800", "\udc00",
                "\ud835\udd61", "\u00a0", "\u200d", "\u03a3", "\u2167", "\u00bd"};
        Random random = new Random(872301);
        for (int sample = 0; sample < 1000; sample++) {
            StringBuilder raw = new StringBuilder();
            for (int i = 0; i < 20; i++) raw.append(fragments[random.nextInt(fragments.length)]);
            TextOffsetMapping mapped = TextOffsetMapping.from(raw.toString());
            assertEquals(SmutTextClassifier.normalize(raw.toString()), mapped.text);
            for (int i = 0; i < mapped.text.length(); i++) {
                assertTrue(mapped.start(i) >= 0); assertTrue(mapped.end(i) > mapped.start(i));
                assertTrue(mapped.end(i) <= raw.length());
                if (i > 0) assertTrue(mapped.start(i) >= mapped.start(i - 1));
            }
        }
    }

    @Test public void oversizedRawInputIsRejectedBeforeMapping() {
        char[] raw = new char[20_001]; java.util.Arrays.fill(raw, 'a');
        try { TextOffsetMapping.from(new String(raw)); fail("Unbounded mapping admitted"); }
        catch (IllegalArgumentException expected) { }
    }
}
