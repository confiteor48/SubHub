package com.subhub.app.detection.text;

import java.util.Arrays;

/** Bounded mapping of the existing classifier's normalized units to original UTF-16 ranges. */
final class TextOffsetMapping {
    private static final int MAXIMUM_SOURCE_UNITS = 20_000;
    private static final int MAXIMUM_NORMALIZED_UNITS = 120_000;
    final String text;
    private final int[] starts, ends;

    private TextOffsetMapping(String text, int[] starts, int[] ends) {
        this.text = text; this.starts = starts; this.ends = ends;
    }

    int start(int normalizedIndex) { return starts[normalizedIndex]; }
    int end(int normalizedIndex) { return ends[normalizedIndex]; }

    static TextOffsetMapping from(String source) {
        if (source == null || source.length() > MAXIMUM_SOURCE_UNITS) {
            throw new IllegalArgumentException("Text offset source exceeds its bound");
        }
        Builder words = new Builder(source.length());
        int separatorStart = -1, separatorEnd = -1;
        for (int raw = 0; raw < source.length();) {
            int next = raw + Character.charCount(source.codePointAt(raw));
            String folded = SmutTextClassifier.decomposeAndFold(source.substring(raw, next));
            for (int i = 0; i < folded.length(); i++) {
                char unit = folded.charAt(i);
                int type = Character.getType(unit);
                if (type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK
                        || type == Character.ENCLOSING_MARK) continue;
                boolean word = (unit >= 'a' && unit <= 'z') || (unit >= '0' && unit <= '9');
                if (!word) {
                    if (words.size > 0) {
                        if (separatorStart < 0) separatorStart = raw;
                        separatorEnd = next;
                    }
                    continue;
                }
                if (separatorStart >= 0) {
                    words.append(' ', separatorStart, separatorEnd);
                    separatorStart = separatorEnd = -1;
                }
                words.append(unit, raw, next);
            }
            raw = next;
        }
        // The canonical final pass only removes spaces. Preserve every surviving unit's origin.
        String result = SmutTextClassifier.collapseLetterSpacing(words.text.toString());
        int[] starts = new int[result.length()], ends = new int[result.length()];
        int cursor = 0;
        for (int i = 0; i < result.length(); i++) {
            char unit = result.charAt(i);
            while (cursor < words.size && words.text.charAt(cursor) == ' ' && unit != ' ') cursor++;
            if (cursor >= words.size || words.text.charAt(cursor) != unit) {
                throw new IllegalArgumentException("Text normalization mapping is unavailable");
            }
            starts[i] = words.starts[cursor]; ends[i] = words.ends[cursor]; cursor++;
        }
        // Contextual Unicode transformations must not silently change classifier behavior.
        if (!result.equals(SmutTextClassifier.normalize(source))) {
            throw new IllegalArgumentException("Text normalization mapping differs from classifier");
        }
        return new TextOffsetMapping(result, starts, ends);
    }

    private static final class Builder {
        final StringBuilder text;
        int[] starts, ends;
        int size;
        Builder(int sourceLength) {
            int capacity = Math.max(16, Math.min(MAXIMUM_NORMALIZED_UNITS, sourceLength * 4));
            text = new StringBuilder(capacity); starts = new int[capacity]; ends = new int[capacity];
        }
        void append(char unit, int start, int end) {
            if (size == MAXIMUM_NORMALIZED_UNITS) throw new IllegalArgumentException("Text normalization exceeds its bound");
            if (size == starts.length) {
                int capacity = Math.min(MAXIMUM_NORMALIZED_UNITS, starts.length * 2);
                starts = Arrays.copyOf(starts, capacity); ends = Arrays.copyOf(ends, capacity);
            }
            text.append(unit); starts[size] = start; ends[size] = end; size++;
        }
    }
}
