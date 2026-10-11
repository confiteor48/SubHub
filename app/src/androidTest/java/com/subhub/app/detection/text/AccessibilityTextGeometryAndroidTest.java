package com.subhub.app.detection.text;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.graphics.Rect;
import android.graphics.RectF;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.subhub.app.detection.BBox;
import com.subhub.app.detection.Detection;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class AccessibilityTextGeometryAndroidTest {
    @Test public void matchedCharactersExpandToOnlyTheirRenderedLine() {
        RectF[] characters = new RectF[12];
        for (int index = 0; index < 6; index++) {
            characters[index] = new RectF(50 + index * 20, 100, 68 + index * 20, 128);
        }
        for (int index = 6; index < 12; index++) {
            characters[index] = new RectF(50 + (index - 6) * 20,
                    140, 68 + (index - 6) * 20, 168);
        }

        Rect result = AccessibilityTextGeometry.lineBounds(
                characters, 8, 11, 500, 900);

        assertEquals(new Rect(48, 138, 170, 170), result);
    }

    @Test public void exactRenderedLineIsNotProjectedAgain() {
        String text = "ordinary first line that is safe touch yourself like a needy pet";
        SmutTextClassifier classifier = new SmutTextClassifier();
        SmutTextClassifier.Match match = classifier.classify(text, new TextSmutConfig(
                true, TextSmutConfig.SENSITIVITY_BALANCED,
                TextSmutConfig.DEFAULT_CATEGORIES));

        Detection result = new TextSmutDetectionFactory(classifier).createExact(
                new Rect(64, 420, 512, 458), match, 1080, 2400,
                "TEXT_SMUT_ACCESSIBILITY_");

        assertNotNull(result);
        assertEquals(new BBox(64, 420, 448, 38), result.getBox());
    }

    @Test public void whollyOffscreenRowsCannotBecomePositiveOnePixelEdgeRectangles() {
        RectF[] characters = new RectF[]{new RectF(50, 1000, 80, 1030), new RectF(80, 1000, 110, 1030)};
        org.junit.Assert.assertTrue("Known nonintersection is not visible edge coverage",
                AccessibilityTextGeometry.lineBoundsParts(characters, 0, 2, 500, 900).isEmpty());
    }

    @Test public void clippingKeepsOnlyKnownVisibleIntersectionsAndRejectsEveryOutsideEdge() {
        RectF[] characters = new RectF[]{new RectF(50, -100, 80, -70), new RectF(50, 890, 80, 930),
                new RectF(-80, 100, -50, 130), new RectF(510, 140, 540, 170)};
        java.util.List<Rect> rows = AccessibilityTextGeometry.lineBoundsParts(characters, 0, 4, 500, 900);
        assertEquals(1, rows.size()); assertEquals(new Rect(48, 888, 82, 900), rows.get(0));
        org.junit.Assert.assertNull(AccessibilityTextGeometry.lineBounds(new RectF[]{characters[0]}, 0, 1, 500, 900));
    }
}
