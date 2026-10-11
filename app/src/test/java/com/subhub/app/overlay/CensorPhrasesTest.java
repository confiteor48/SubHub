package com.subhub.app.overlay;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class CensorPhrasesTest {
    @Test
    public void customPhrasesAreNormalizedAndDeduplicated() {
        List<String> phrases = CensorPhrases.build(
                Collections.emptySet(),
                new LinkedHashSet<>(Arrays.asList("  obey   now ", "OBEY NOW")));
        assertEquals(1, phrases.size());
        assertEquals("OBEY NOW", phrases.get(0));
    }

    @Test
    public void emptySelectionFallsBackSafely() {
        assertTrue(CensorPhrases.build(Collections.emptySet(), Collections.emptySet())
                .contains("BLOCKED"));
    }

    @Test public void updatedCategoryPoolsKeepDistinctLabelsAndNeutralDefaults() {
        assertEquals(Arrays.asList("BLOCKED", "CENSORED", "DENIED", "LOCKED", "NO", "NOT A CHANCE", "NEVER"),
                CensorPhrases.build(Collections.singleton("short"), Collections.emptySet()));
        Map<String, Integer> counts = Map.of("short", 7, "denial", 14, "humiliation", 14,
                "edge", 12, "findom", 15, "ntr", 16, "gooner", 16);
        LinkedHashSet<String> all = new LinkedHashSet<>();
        for (String category : CensorPhrases.categoryNames()) {
            List<String> phrases = CensorPhrases.build(Collections.singleton(category),
                    Collections.emptySet());
            assertEquals(counts.get(category).intValue(), phrases.size());
            for (String phrase : phrases) {
                assertTrue(category + ": " + phrase, phrase.length() <= 80);
                assertTrue("Duplicate across categories: " + phrase, all.add(phrase));
            }
        }
        assertEquals(94, all.size());
        assertEquals(new LinkedHashSet<>(Arrays.asList("short", "denial")),
                CensorPhrases.DEFAULT_ENABLED);
    }
}
