package com.subhub.app.detection.text;

import com.subhub.app.censor.engine.SceneEngine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Bounded source-local OCR classification. No legacy Detection, fusion, tracker or cached text. */
public final class ImageTextLayout {
    public static final int MAXIMUM_BLOCKS = 64;
    public static final int MAXIMUM_LINES = 256;
    public static final int MAXIMUM_LINE_UNITS = 2048;
    public static final int MAXIMUM_TEXT_UNITS = 20_000;
    private static final int WINDOW_LINES = 3;

    public static final class Line {
        public final String text;
        public final SceneEngine.Region region;
        public Line(String text, SceneEngine.Region region) {
            this.text = Objects.requireNonNull(text); this.region = region;
            if (text.length() > MAXIMUM_LINE_UNITS || (!text.trim().isEmpty() && region == null)) {
                throw new IllegalArgumentException("incomplete OCR line");
            }
        }
    }

    private ImageTextLayout() { }

    public static List<SceneEngine.Observation> classify(List<? extends List<Line>> blocks,
            SmutTextClassifier classifier, TextSmutConfig config, boolean semanticEnabled, long partition) {
        Objects.requireNonNull(blocks); Objects.requireNonNull(classifier); Objects.requireNonNull(config);
        if (blocks.size() > MAXIMUM_BLOCKS) throw new IllegalArgumentException("OCR block budget");
        int lines = 0, units = 0;
        for (List<Line> block : blocks) {
            Objects.requireNonNull(block);
            if (block.size() > MAXIMUM_LINES - lines) throw new IllegalArgumentException("OCR line budget");
            lines += block.size();
            for (Line line : block) {
                Objects.requireNonNull(line);
                if (line.text.length() > MAXIMUM_TEXT_UNITS - units) throw new IllegalArgumentException("OCR text budget");
                units += line.text.length();
            }
        }
        if (!config.isEnabled()) return Collections.emptyList();
        List<SceneEngine.Observation> output = new ArrayList<>();
        long groupIdentity = 0;
        for (List<Line> block : blocks) {
            List<Group> groups = new ArrayList<>();
            for (int start = 0; start < block.size(); start++) {
                StringBuilder window = new StringBuilder();
                int[] offsets = new int[WINDOW_LINES], ends = new int[WINDOW_LINES];
                for (int end = start; end < block.size() && end < start + WINDOW_LINES; end++) {
                    if (end != start) window.append('\n');
                    int member = end - start;
                    offsets[member] = window.length(); window.append(block.get(end).text); ends[member] = window.length();
                    SmutTextClassifier.Match match = classifier.classify(window.toString(), config, semanticEnabled);
                    if (!match.isMatched()) continue;
                    TextOffsetMapping mapping = TextOffsetMapping.from(window.toString());
                    if (mapping.text.length() != match.getNormalizedLength() || match.getStartIndex() < 0
                            || match.getEndIndex() > mapping.text.length() || match.getEndIndex() <= match.getStartIndex()) {
                        throw new IllegalArgumentException("incomplete OCR span mapping");
                    }
                    Group selected = new Group(match.getCategory(), match.getConfidence(), block.size());
                    for (int i = match.getStartIndex(); i < match.getEndIndex(); i++) {
                        if (mapping.text.charAt(i) == ' ') continue;
                        int rawStart = mapping.start(i), rawEnd = mapping.end(i);
                        for (int row = 0; row <= member; row++) {
                            if (rawStart < ends[row] && rawEnd > offsets[row]) selected.rows[start + row] = true;
                        }
                    }
                    if (!selected.any()) throw new IllegalArgumentException("OCR match without line geometry");
                    // Window overlap is a source-local set union, not rectangle fusion. Preserve
                    // each actual line and blank gap, including transitively overlapping windows.
                    for (int index = 0; index < groups.size();) {
                        Group previous = groups.get(index);
                        if (previous.category.equals(selected.category) && previous.overlaps(selected)) {
                            selected.merge(previous); groups.remove(index); index = 0;
                        } else index++;
                    }
                    groups.add(selected);
                }
            }
            for (Group group : groups) {
                long identity = ++groupIdentity;
                for (int row = 0; row < group.rows.length; row++) {
                    if (!group.rows[row]) continue;
                    SceneEngine.Region region = block.get(row).region;
                    if (region == null) throw new IllegalArgumentException("OCR match geometry unavailable");
                    if (output.size() == MAXIMUM_LINES) throw new IllegalArgumentException("OCR observation budget");
                    output.add(new SceneEngine.Observation("TEXT_SMUT_OCR_" + group.category.toUpperCase(Locale.ROOT),
                            partition, identity, region, group.confidence));
                }
            }
        }
        return Collections.unmodifiableList(output);
    }

    private static final class Group {
        final String category;
        float confidence;
        final boolean[] rows;
        Group(String category, float confidence, int lines) { this.category = category; this.confidence = confidence; rows = new boolean[lines]; }
        boolean any() { for (boolean row : rows) if (row) return true; return false; }
        boolean overlaps(Group other) { for (int i = 0; i < rows.length; i++) if (rows[i] && other.rows[i]) return true; return false; }
        void merge(Group other) { for (int i = 0; i < rows.length; i++) rows[i] |= other.rows[i]; confidence = Math.max(confidence, other.confidence); }
    }
}
