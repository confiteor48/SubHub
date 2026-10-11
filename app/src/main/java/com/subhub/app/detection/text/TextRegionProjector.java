package com.subhub.app.detection.text;

import com.subhub.app.detection.BBox;
import java.util.ArrayList;
import java.util.List;

/** Projects a matched text span onto one or more bounded visual text lines. */
final class TextRegionProjector {
    private static final int HORIZONTAL_PADDING_PX = 4;
    private static final int VERTICAL_PADDING_PX = 2;
    private static final int MAX_PROJECTED_LINES = 12;

    private TextRegionProjector() {}

    static BBox project(
            String source,
            SmutTextClassifier.Match match,
            int sourceLeft,
            int sourceTop,
            int sourceRight,
            int sourceBottom,
            int screenWidth,
            int screenHeight) {
        if (match == null || !match.isMatched() || sourceRight <= sourceLeft
                || sourceBottom <= sourceTop || screenWidth <= 0 || screenHeight <= 0) return null;

        int left = clamp(sourceLeft, 0, screenWidth - 1);
        int top = clamp(sourceTop, 0, screenHeight - 1);
        int right = clamp(sourceRight, left + 1, screenWidth);
        int bottom = clamp(sourceBottom, top + 1, screenHeight);
        int width = right - left;
        int height = bottom - top;

        int targetLineHeight = clamp(Math.round(screenWidth * 0.045f), 32, 68);
        int normalizedLength = Math.max(1, match.getNormalizedLength());
        int explicitLines = explicitLineCount(source);
        int charactersPerLine = Math.max(12,
                Math.round(width / Math.max(8f, targetLineHeight * 0.48f)));
        int wrappedLines = Math.max(1,
                (normalizedLength + charactersPerLine - 1) / charactersPerLine);
        int linesFromHeight = Math.max(1, Math.round(height / (float) targetLineHeight));
        int lineCount = Math.max(explicitLines, wrappedLines);
        lineCount = Math.max(1, Math.min(MAX_PROJECTED_LINES,
                Math.min(lineCount, Math.max(explicitLines, linesFromHeight))));

        int naturalTextHeight = Math.min(height, targetLineHeight * lineCount);
        boolean tightTextNode = height <= Math.round(naturalTextHeight * 1.45f);
        int textHeight = tightTextNode ? height : naturalTextHeight;
        float lineHeight = textHeight / (float) lineCount;
        int charactersInLine = Math.max(1,
                (normalizedLength + lineCount - 1) / lineCount);
        int firstLine = clamp(match.getStartIndex() / charactersInLine, 0, lineCount - 1);
        int finalCharacter = Math.max(match.getStartIndex(), match.getEndIndex() - 1);
        int lastLine = clamp(finalCharacter / charactersInLine, firstLine, lineCount - 1);

        int projectedTop = top + Math.round(firstLine * lineHeight) - VERTICAL_PADDING_PX;
        int projectedBottom = top + Math.round((lastLine + 1) * lineHeight)
                + VERTICAL_PADDING_PX;
        int projectedLeft = left - HORIZONTAL_PADDING_PX;
        int projectedRight = right + HORIZONTAL_PADDING_PX;
        projectedLeft = clamp(projectedLeft, 0, screenWidth - 1);
        projectedTop = clamp(projectedTop, 0, screenHeight - 1);
        projectedRight = clamp(projectedRight, projectedLeft + 1, screenWidth);
        projectedBottom = clamp(projectedBottom, projectedTop + 1, screenHeight);
        return new BBox(projectedLeft, projectedTop,
                projectedRight - projectedLeft, projectedBottom - projectedTop);
    }

    /** Estimated individual members for native text, preserving known explicit blank rows. */
    static List<BBox> projectLines(String source, SmutTextClassifier.Match match,
            int sourceLeft, int sourceTop, int sourceRight, int sourceBottom,
            int screenWidth, int screenHeight) {
        if (source == null || match == null || !match.isMatched() || sourceRight <= sourceLeft
                || sourceBottom <= sourceTop || screenWidth <= 0 || screenHeight <= 0
                || sourceRight <= 0 || sourceBottom <= 0 || sourceLeft >= screenWidth || sourceTop >= screenHeight) return null;
        long rawWidth = (long) sourceRight - sourceLeft, rawHeight = (long) sourceBottom - sourceTop;
        if (rawWidth > Integer.MAX_VALUE || rawHeight > Integer.MAX_VALUE) return null;
        TextOffsetMapping mapped = TextOffsetMapping.from(source);
        if (mapped.text.length() != match.getNormalizedLength() || match.getStartIndex() < 0
                || match.getEndIndex() > mapped.text.length() || match.getEndIndex() <= match.getStartIndex()) return null;
        int explicit = explicitLineCount(source);
        if (explicit > MAX_PROJECTED_LINES) return null;
        int targetLineHeight = clamp(Math.round(screenWidth * 0.045f), 32, 68);
        int charactersPerLine = Math.max(12, Math.round(rawWidth / Math.max(8f, targetLineHeight * .48f)));
        int[] rawLine = new int[source.length()];
        int line = 0;
        for (int i = 0; i < source.length(); i++) {
            rawLine[i] = line;
            if (source.charAt(i) == '\n') line++;
        }
        int[] counts = new int[explicit], rows = new int[explicit], bases = new int[explicit];
        for (int i = 0; i < mapped.text.length(); i++) {
            int start = mapped.start(i), end = mapped.end(i);
            // Collapsed whitespace spanning a newline does not occupy a visual text row.
            if (source.charAt(start) == '\n' || rawLine[start] != rawLine[end - 1]) continue;
            counts[rawLine[start]]++;
        }
        int rowCount = 0;
        for (int i = 0; i < explicit; i++) {
            bases[i] = rowCount;
            rows[i] = Math.max(1, (counts[i] + charactersPerLine - 1) / charactersPerLine);
            rowCount += rows[i];
            if (rowCount > MAX_PROJECTED_LINES) return null;
        }
        if (explicit == 1) {
            int heightRows = Math.max(1, Math.round(rawHeight / (float) targetLineHeight));
            rowCount = Math.min(rowCount, heightRows); rows[0] = rowCount;
        }
        int naturalHeight = (int) Math.min(rawHeight, targetLineHeight * rowCount);
        int textHeight = rawHeight <= Math.round(naturalHeight * 1.45f) ? (int) rawHeight : naturalHeight;
        float rowHeight = textHeight / (float) rowCount;
        // As in the existing fallback policy, wrapped-line positions are estimates, not glyph facts.
        int[] offsets = new int[explicit];
        boolean[] selected = new boolean[rowCount];
        for (int i = 0; i < mapped.text.length(); i++) {
            int start = mapped.start(i), end = mapped.end(i);
            if (source.charAt(start) == '\n' || rawLine[start] != rawLine[end - 1]) continue;
            int sourceLine = rawLine[start];
            int perRow = Math.max(1, (counts[sourceLine] + rows[sourceLine] - 1) / rows[sourceLine]);
            int row = bases[sourceLine] + Math.min(rows[sourceLine] - 1, offsets[sourceLine]++ / perRow);
            if (i >= match.getStartIndex() && i < match.getEndIndex() && mapped.text.charAt(i) != ' ') selected[row] = true;
        }
        List<BBox> result = new ArrayList<>();
        for (int row = 0; row < rowCount; row++) {
            if (!selected[row]) continue;
            double top = (double) sourceTop + Math.round(row * rowHeight), bottom = (double) sourceTop + Math.round((row + 1) * rowHeight);
            if (bottom <= 0 || top >= screenHeight) continue;
            double left = Math.max(0d, (double) sourceLeft - HORIZONTAL_PADDING_PX);
            double right = Math.min(screenWidth, (double) sourceRight + HORIZONTAL_PADDING_PX);
            top = Math.max(0d, top - VERTICAL_PADDING_PX); bottom = Math.min(screenHeight, bottom + VERTICAL_PADDING_PX);
            if (right > left && bottom > top) result.add(new BBox((int) left, (int) top, (int) (right - left), (int) (bottom - top)));
        }
        return result.isEmpty() ? null : result;
    }

    private static int explicitLineCount(String source) {
        if (source == null || source.isEmpty()) return 1;
        int count = 1;
        for (int index = 0; index < source.length(); index++) {
            if (source.charAt(index) == '\n') count++;
        }
        return count;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
