package com.subhub.app.subliminal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class SubliminalSettingsRepositoryTest {
    @Test public void presetsStayWithinFaintSafeBounds() {
        for (SubliminalSettings.Preset preset : SubliminalSettings.Preset.values()) {
            SubliminalSettingsRepository.Values values =
                    SubliminalSettingsRepository.valuesFor(preset);
            assertTrue(values.opacity >= 1 && values.opacity <= 15);
            assertTrue(values.visible >= 800L && values.visible <= 4_000L);
            assertTrue(values.minimum >= 5_000L);
            assertTrue(values.maximum >= values.minimum);
            assertTrue(values.textSize >= 14 && values.textSize <= 28);
        }
    }

    @Test public void ultraIsFasterButStillFaint() {
        SubliminalSettingsRepository.Values normal =
                SubliminalSettingsRepository.valuesFor(SubliminalSettings.Preset.NORMAL);
        SubliminalSettingsRepository.Values ultra =
                SubliminalSettingsRepository.valuesFor(SubliminalSettings.Preset.ULTRA);
        assertTrue(ultra.minimum < normal.minimum);
        assertTrue(ultra.maximum < normal.maximum);
        assertTrue(ultra.opacity > normal.opacity);
        assertEquals(10, ultra.opacity);
    }

    @Test public void updatedBuiltInPacksKeepDistinctBriefMessages() {
        Set<String> all = new LinkedHashSet<>();
        for (String pack : Arrays.asList("obedience", "focus", "beta", "findom")) {
            List<String> phrases = SubliminalSettingsRepository.resolvePhrases(
                    settings(Collections.singleton(pack), ""));
            int expected = "obedience".equals(pack) ? 25 : "beta".equals(pack) ? 24
                    : "findom".equals(pack) ? 23 : 20;
            assertEquals(expected, phrases.size());
            for (String phrase : phrases) {
                assertTrue(phrase, phrase.length() <= 60);
                assertTrue("Duplicate message: " + phrase, all.add(phrase));
            }
        }
        assertEquals(92, all.size());
    }

    @Test public void displayDedupDoesNotModifySavedCustomTextOrCase() {
        String custom = "My message\nMy message\nmy message\nThere you are. Back in line.";
        SubliminalSettings settings = settings(
                new LinkedHashSet<>(Arrays.asList("obedience", "custom")), custom);
        List<String> phrases = SubliminalSettingsRepository.resolvePhrases(settings);
        assertEquals(27, phrases.size());
        assertEquals(custom, settings.getCustomPhrases());
        assertTrue(phrases.contains("My message"));
        assertTrue(phrases.contains("my message"));
    }

    @Test public void customMessagesRequireExplicitPackSelectionAndEmptySelectionStaysEmpty() {
        assertTrue(SubliminalSettingsRepository.resolvePhrases(
                settings(Collections.emptySet(), "Saved but not selected")).isEmpty());
        assertEquals(Collections.singletonList("Only mine"),
                SubliminalSettingsRepository.resolvePhrases(
                        settings(Collections.singleton("custom"), "Only mine\nOnly mine")));
    }

    private static SubliminalSettings settings(Set<String> packs, String custom) {
        return new SubliminalSettings(SubliminalSettings.Preset.NORMAL, false,
                5, 2_000L, 25_000L, 60_000L, 19, packs, custom);
    }
}
