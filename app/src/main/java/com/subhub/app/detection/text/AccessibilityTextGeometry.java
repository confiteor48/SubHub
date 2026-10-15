package com.subhub.app.detection.text;

import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Parcelable;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

/** Resolves exact rendered text lines when an Accessibility provider exposes character bounds. */
final class AccessibilityTextGeometry {
    private static final int EDGE_PADDING_PX = 2;

    private AccessibilityTextGeometry() {}

    static Rect resolve(
            AccessibilityNodeInfo node,
            String classifiedText,
            SmutTextClassifier.Match match,
            int screenWidth,
            int screenHeight) {
        try {
            List<Rect> lines = resolveLines(node, classifiedText, match, screenWidth, screenHeight);
            if (lines == null || lines.isEmpty()) return null;
            Rect hull = new Rect(lines.get(0));
            for (int i = 1; i < lines.size(); i++) hull.union(lines.get(i));
            return hull;
        } catch (IncompleteGeometry unavailable) { return null; }
    }

    /** Native readers retain these individual lines, never the compatibility method's gap hull. */
    static List<Rect> resolveLines(
            AccessibilityNodeInfo node, String classifiedText, SmutTextClassifier.Match match,
            int screenWidth, int screenHeight) {
        return resolveLines(node, classifiedText, match, screenWidth, screenHeight, (nanos, successful) -> { });
    }
    interface QueryObserver { void measured(long nanos, boolean successful); }
    static List<Rect> resolveLines(
            AccessibilityNodeInfo node, String classifiedText, SmutTextClassifier.Match match,
            int screenWidth, int screenHeight, QueryObserver observer) {
        if (node == null || match == null || !match.isMatched() || classifiedText == null
                || classifiedText.isEmpty()) return null;
        CharSequence nodeText = node.getText();
        if (nodeText == null || !nodeText.toString().trim().equals(classifiedText)) return null;
        List<String> available = node.getAvailableExtraData();
        if (available == null
                || !available.contains(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY)) {
            return null;
        }
        String raw = nodeText.toString();
        int length = raw.length();
        if (length <= 0) return null;
        if (length > 20_000 || screenWidth <= 0 || screenHeight <= 0) throw new IncompleteGeometry();
        TextOffsetMapping mapped;
        try { mapped = TextOffsetMapping.from(raw); }
        catch (IllegalArgumentException unavailable) { throw new IncompleteGeometry(); }
        if (mapped.text.length() != match.getNormalizedLength()
                || !mapped.text.equals(SmutTextClassifier.normalize(classifiedText))
                || match.getStartIndex() < 0 || match.getEndIndex() > mapped.text.length()
                || match.getEndIndex() <= match.getStartIndex()) throw new IncompleteGeometry();
        Bundle arguments = new Bundle();
        arguments.putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, 0);
        arguments.putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, length);
        long queryAt = System.nanoTime(); boolean refreshedQuery = false;
        try { refreshedQuery = node.refreshWithExtraData(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY, arguments); }
        finally { observer.measured(System.nanoTime() - queryAt, refreshedQuery); }
        if (!refreshedQuery) {
            throw new IncompleteGeometry();
        }
        CharSequence refreshed = node.getText();
        if (refreshed == null || refreshed.length() != raw.length() || !raw.contentEquals(refreshed)) {
            throw new IncompleteGeometry();
        }
        Parcelable[] values = node.getExtras().getParcelableArray(
                AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY);
        if (values == null || values.length != length) throw new IncompleteGeometry();
        RectF[] characters = new RectF[Math.min(values.length, length)];
        for (int index = 0; index < characters.length; index++) {
            if (values[index] instanceof RectF) characters[index] = (RectF) values[index];
        }
        boolean[] selected = new boolean[length];
        for (int i = match.getStartIndex(); i < match.getEndIndex(); i++) {
            if (mapped.text.charAt(i) == ' ') continue;
            for (int rawIndex = mapped.start(i); rawIndex < mapped.end(i); rawIndex++) {
                if (significant(raw.charAt(rawIndex))) selected[rawIndex] = true;
            }
        }
        // Missing glyph locations cannot become a successful partial set of matched lines.
        for (int i = 0; i < length; i++) {
            if (selected[i] && !useful(characters[i])) throw new IncompleteGeometry();
        }
        List<Rect> lines = lineBoundsParts(characters, selected, screenWidth, screenHeight);
        if (lines.isEmpty()) throw new IncompleteGeometry();
        return lines;
    }

    static final class IncompleteGeometry extends IllegalStateException {
        IncompleteGeometry() { super("Native text geometry is incomplete"); }
    }

    static List<Rect> lineBoundsParts(RectF[] characters, int rawStart, int rawEnd,
            int screenWidth, int screenHeight) {
        if (characters == null || characters.length == 0 || screenWidth <= 0 || screenHeight <= 0) {
            return Collections.emptyList();
        }
        if (rawStart < 0 || rawEnd <= rawStart || rawEnd > characters.length) return Collections.emptyList();
        boolean[] selected = new boolean[characters.length];
        for (int i = rawStart; i < rawEnd; i++) selected[i] = true;
        return lineBoundsParts(characters, selected, screenWidth, screenHeight);
    }

    private static List<Rect> lineBoundsParts(RectF[] characters, boolean[] selected,
            int screenWidth, int screenHeight) {
        List<RectF> matchedRows = new ArrayList<>();
        for (int i = 0; i < characters.length; i++) {
            if (!selected[i]) continue;
            RectF glyph = characters[i];
            if (!useful(glyph)) continue;
            RectF row = null;
            for (RectF existing : matchedRows) {
                if (sharesRenderedLine(glyph, existing)) { row = existing; break; }
            }
            if (row == null) {
                if (matchedRows.size() >= 12) throw new IncompleteGeometry();
                matchedRows.add(new RectF(glyph));
            } else row.union(glyph);
        }
        matchedRows.sort(Comparator.comparingDouble(value -> value.top));
        List<Rect> result = new ArrayList<>();
        for (RectF matched : matchedRows) {
            RectF line = new RectF(matched);
            for (RectF glyph : characters) {
                if (useful(glyph) && sharesRenderedLine(glyph, matched)) line.union(glyph);
            }
            Rect clipped = clip(line, screenWidth, screenHeight);
            if (clipped != null) result.add(clipped);
        }
        return result;
    }

    /** Expands matched characters to their complete rendered lines. */
    static Rect lineBounds(
            RectF[] characters,
            int rawStart,
            int rawEnd,
            int screenWidth,
            int screenHeight) {
        if (characters == null || characters.length == 0
                || screenWidth <= 0 || screenHeight <= 0) return null;
        int start = clamp(rawStart, 0, characters.length - 1);
        int end = clamp(Math.max(start + 1, rawEnd), start + 1, characters.length);
        RectF matched = null;
        for (int index = start; index < end; index++) {
            RectF box = characters[index];
            if (!useful(box)) continue;
            if (matched == null) matched = new RectF(box);
            else matched.union(box);
        }
        if (matched == null) return null;

        RectF completeLines = new RectF(matched);
        for (RectF box : characters) {
            if (!useful(box) || !sharesRenderedLine(box, matched)) continue;
            completeLines.union(box);
        }
        return clip(completeLines, screenWidth, screenHeight);
    }

    private static Rect clip(RectF bounds, int width, int height) {
        // Test the observed bounds before padding or clipping; never fabricate an edge pixel.
        if (bounds.right <= 0 || bounds.bottom <= 0 || bounds.left >= width || bounds.top >= height) return null;
        int left = (int) Math.max(0d, Math.floor(bounds.left) - EDGE_PADDING_PX);
        int top = (int) Math.max(0d, Math.floor(bounds.top) - EDGE_PADDING_PX);
        int right = (int) Math.min(width, Math.ceil(bounds.right) + EDGE_PADDING_PX);
        int bottom = (int) Math.min(height, Math.ceil(bounds.bottom) + EDGE_PADDING_PX);
        return right > left && bottom > top ? new Rect(left, top, right, bottom) : null;
    }

    private static boolean significant(char unit) {
        int type = Character.getType(unit);
        return !Character.isWhitespace(unit) && !Character.isSpaceChar(unit) && !Character.isLowSurrogate(unit)
                && type != Character.FORMAT && type != Character.NON_SPACING_MARK
                && type != Character.COMBINING_SPACING_MARK && type != Character.ENCLOSING_MARK;
    }

    private static boolean sharesRenderedLine(RectF box, RectF matched) {
        float overlap = Math.min(box.bottom, matched.bottom) - Math.max(box.top, matched.top);
        return overlap > 0f && overlap >= Math.min(box.height(), matched.height()) * 0.45f;
    }

    private static boolean useful(RectF box) {
        return box != null && !box.isEmpty()
                && Float.isFinite(box.left) && Float.isFinite(box.top)
                && Float.isFinite(box.right) && Float.isFinite(box.bottom);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
