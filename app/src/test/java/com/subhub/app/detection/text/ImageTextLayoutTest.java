package com.subhub.app.detection.text;

import static org.junit.Assert.*;

import com.subhub.app.censor.engine.SceneEngine;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public final class ImageTextLayoutTest {
    private static ImageTextLayout.Line line(String text, int y) {
        return new ImageTextLayout.Line(text, new SceneEngine.Region(10, y, 200, y + 20));
    }
    private static List<SceneEngine.Observation> classify(ImageTextLayout.Line... rows) {
        return ImageTextLayout.classify(Collections.singletonList(Arrays.asList(rows)), new SmutTextClassifier(),
                new TextSmutConfig(true, TextSmutConfig.SENSITIVITY_BALANCED, null), false, 3);
    }

    @Test public void wholeWindowLetterSpacingMapsBothOriginalRowsWithoutFooterOrGap() {
        List<SceneEngine.Observation> result = classify(line("p o", 100), line("r n", 170), line("ordinary footer", 240));
        assertEquals(2, result.size());
        assertEquals(100, result.get(0).region.top, 0); assertEquals(170, result.get(1).region.top, 0);
        assertEquals(result.get(0).textGroup, result.get(1).textGroup);
        assertEquals(120, result.get(0).region.bottom, 0); assertEquals(170, result.get(1).region.top, 0);
        for (SceneEngine.Observation row : result) { assertEquals(3, row.partition); assertEquals("TEXT_SMUT_OCR_EXPLICIT_LANGUAGE", row.category); }
    }

    @Test public void unicodeExpansionAndAstralPrefixDoNotMoveTheSelectedLine() {
        List<SceneEngine.Observation> result = classify(line("ordinary \uD83D\uDC96", 100), line("\uFF50\uFF4F\uFF52\uFF4E", 170), line("footer", 240));
        assertEquals(1, result.size()); assertEquals(170, result.get(0).region.top, 0);
    }

    @Test public void overlappingWindowsDoNotRepeatTheSameLiteralLine() {
        List<SceneEngine.Observation> result = classify(line("first row", 100), line("porn", 170), line("footer", 240));
        assertEquals(1, result.size()); assertEquals(170, result.get(0).region.top, 0);
    }

    @Test public void blankRowsRemainGapsAndDistinctBlocksCannotBuildAPhrase() {
        List<SceneEngine.Observation> result = classify(line("porn", 100), new ImageTextLayout.Line("", null), line("nsfw", 240));
        assertEquals(2, result.size()); assertEquals(result.get(0).textGroup, result.get(1).textGroup);
        List<List<ImageTextLayout.Line>> blocks = Arrays.asList(Collections.singletonList(line("p o", 100)),
                Collections.singletonList(line("r n", 240)));
        assertTrue(ImageTextLayout.classify(blocks, new SmutTextClassifier(),
                new TextSmutConfig(true, 1, null), false, 3).isEmpty());
    }

    @Test public void safeContextAndDisabledCategoriesKeepUnchangedClassifierPolicy() {
        assertTrue(classify(line("porn medical sex education", 100)).isEmpty());
        assertTrue(ImageTextLayout.classify(Collections.singletonList(Collections.singletonList(line("porn", 100))),
                new SmutTextClassifier(), new TextSmutConfig(true, 1, Collections.singleton(TextSmutConfig.CATEGORY_FETISH)), false, 3).isEmpty());
    }

    @Test public void missingGeometryAndBudgetsAreFailuresNotCompleteEmpty() {
        assertThrows(IllegalArgumentException.class, () -> new ImageTextLayout.Line("porn", null));
        assertThrows(IllegalArgumentException.class, () -> new ImageTextLayout.Line(String.join("", Collections.nCopies(2049, "x")),
                new SceneEngine.Region(0, 0, 10, 10)));
        assertThrows(IllegalArgumentException.class, () -> ImageTextLayout.classify(
                Collections.singletonList(Collections.nCopies(257, line("ordinary", 0))), new SmutTextClassifier(), new TextSmutConfig(true, 1, null), false, 3));
        assertThrows(IllegalArgumentException.class, () -> ImageTextLayout.classify(
                Collections.nCopies(65, Collections.emptyList()), new SmutTextClassifier(), new TextSmutConfig(true, 1, null), false, 3));
        assertThrows(IllegalArgumentException.class, () -> ImageTextLayout.classify(
                Collections.singletonList(Collections.nCopies(21, line(String.join("", Collections.nCopies(1000, "x")), 0))),
                new SmutTextClassifier(), new TextSmutConfig(true, 1, null), false, 3));
    }
}
