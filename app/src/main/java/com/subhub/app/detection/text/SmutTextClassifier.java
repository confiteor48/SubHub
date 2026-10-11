package com.subhub.app.detection.text;

import android.content.Context;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Small deterministic text-risk classifier. It deliberately requires explicit words or multiple
 * contextual signals; generic relationship language is not enough by itself.
 */
public final class SmutTextClassifier {
    private static final List<Rule> RULES = rules();
    private static final Pattern SAFE_CONTEXT = Pattern.compile(
            "\\b(?:sex education|sexual health|sexual assault|rape crisis|breast cancer|"
                    + "prostate cancer|medical|clinical|anatomy|gynecolog\\w*|urolog\\w*|"
                    + "healthcare|survivor support|support hotline|consent guide)\\b");
    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern NON_WORD = Pattern.compile("[^a-z0-9]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern LETTER_SPACED = Pattern.compile(
            "(?:(?<=^)|(?<=\\s))(?:[a-z]\\s+){2,}[a-z](?=\\s|$)");
    private final LocalSmutModel semanticModel;

    public SmutTextClassifier() {
        semanticModel = null;
    }

    public SmutTextClassifier(Context context) {
        semanticModel = context == null ? null : new LocalSmutModel(context);
    }

    /** Loads the compact table before the first protected frame needs contextual scoring. */
    public void warmSemanticModel() {
        if (semanticModel != null) semanticModel.score("local model warmup");
    }

    public Match classify(String source, TextSmutConfig config) {
        return classify(source, config, false);
    }

    public Match classify(String source, TextSmutConfig config, boolean semanticEnabled) {
        if (config == null || !config.isEnabled() || source == null) return Match.none();
        String text = normalize(source);
        if (text.length() < 3) return Match.none();

        int explicit = 0;
        int fetish = 0;
        int solicitation = 0;
        int matchStart = text.length();
        int matchEnd = 0;
        for (Rule rule : RULES) {
            if (!config.getEnabledCategories().contains(rule.category)) continue;
            Matcher matcher = rule.pattern.matcher(text);
            if (!matcher.find()) continue;
            if (TextSmutConfig.CATEGORY_EXPLICIT.equals(rule.category)) explicit += rule.points;
            else if (TextSmutConfig.CATEGORY_FETISH.equals(rule.category)) fetish += rule.points;
            else if (TextSmutConfig.CATEGORY_SOLICITATION.equals(rule.category)) {
                solicitation += rule.points;
            }
            do {
                matchStart = Math.min(matchStart, matcher.start());
                matchEnd = Math.max(matchEnd, matcher.end());
            } while (matcher.find());
        }

        int score = explicit + fetish + solicitation;
        if (SAFE_CONTEXT.matcher(text).find()) score = Math.max(0, score - 5);
        int threshold = config.getSensitivity() == TextSmutConfig.SENSITIVITY_STRICT
                ? 6 : config.getSensitivity() == TextSmutConfig.SENSITIVITY_BROAD ? 3 : 4;
        if (score < threshold) {
            return classifySemantically(text, config, semanticEnabled);
        }

        String category = TextSmutConfig.CATEGORY_EXPLICIT;
        int strongest = explicit;
        if (fetish > strongest) {
            category = TextSmutConfig.CATEGORY_FETISH;
            strongest = fetish;
        }
        if (solicitation > strongest) category = TextSmutConfig.CATEGORY_SOLICITATION;
        float confidence = Math.min(0.99f, 0.55f + score * 0.07f);
        return new Match(true, category, score, confidence,
                matchStart == text.length() ? 0 : matchStart,
                Math.max(matchStart + 1, matchEnd), text.length());
    }

    private Match classifySemantically(
            String normalizedText, TextSmutConfig config, boolean semanticEnabled) {
        if (!semanticEnabled || semanticModel == null
                || !config.getEnabledCategories().contains(TextSmutConfig.CATEGORY_EXPLICIT)
                || SAFE_CONTEXT.matcher(normalizedText).find()) return Match.none();
        float probability = semanticModel.score(normalizedText);
        float threshold = config.getSensitivity() == TextSmutConfig.SENSITIVITY_STRICT
                ? 0.75f : config.getSensitivity() == TextSmutConfig.SENSITIVITY_BROAD
                ? 0.40f : 0.52f;
        return probability < threshold ? Match.none()
                : Match.semantic(TextSmutConfig.CATEGORY_EXPLICIT,
                        probability, normalizedText.length());
    }

    static String normalize(String source) {
        String decomposed = decomposeAndFold(source);
        String plain = COMBINING_MARKS.matcher(decomposed).replaceAll("");
        String words = WHITESPACE.matcher(
                NON_WORD.matcher(plain).replaceAll(" ")).replaceAll(" ").trim();
        return collapseLetterSpacing(words);
    }

    /** Shared unchanged prefix for normalization and original UTF-16 position mapping. */
    static String decomposeAndFold(String source) {
        return foldHomoglyphs(Normalizer.normalize(source, Normalizer.Form.NFKD)
                .toLowerCase(Locale.ROOT))
                .replace('0', 'o')
                .replace('1', 'i')
                .replace('3', 'e')
                .replace('4', 'a')
                .replace('5', 's')
                .replace('7', 't')
                .replace('@', 'a')
                .replace('$', 's');
    }

    private static String foldHomoglyphs(String value) {
        return value
                .replace('а', 'a').replace('е', 'e').replace('і', 'i')
                .replace('о', 'o').replace('р', 'p').replace('с', 'c')
                .replace('х', 'x').replace('у', 'y');
    }

    static String collapseLetterSpacing(String value) {
        Matcher matcher = LETTER_SPACED.matcher(value);
        StringBuffer result = new StringBuffer(value.length());
        while (matcher.find()) {
            matcher.appendReplacement(result,
                    Matcher.quoteReplacement(matcher.group().replace(" ", "")));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static List<Rule> rules() {
        List<Rule> values = new ArrayList<>();
        values.add(rule(TextSmutConfig.CATEGORY_EXPLICIT, 4,
                "clit(?:oris)?|puss(?:y|ies)|cock(?:s)?|dick(?:s)?|penis(?:es)?|"
                        + "vagin(?:a|as|al)|cum(?:ming|s|med)?|orgasm(?:s|ic)?|ejaculat\\w*|"
                        + "masturbat\\w*|blowjobs?|handjobs?|hentai|porn(?:o|ography)?|nudes?|nsfw|"
                        + "smut|erotica?|rapeslop|rapeplay|rapey|horny|arous(?:ed|ing)|moan(?:ing|s)?"));
        values.add(rule(TextSmutConfig.CATEGORY_EXPLICIT, 2,
                "sex(?:ual|ually)?|fuck\\w*|suck\\w*|naked|breasts?|boobs?|nipples?|anal|genitals?|"
                        + "slut(?:s|ty)?|whores?|lewd|thirst trap"));
        values.add(rule(TextSmutConfig.CATEGORY_FETISH, 4,
                "bdsm|strap on|strapon|chastity(?: cage)?|cuck\\w*|goon\\w*|bondage|fetish|"
                        + "kink\\w*|noncon|non con|cnc|findom|paypig|siss(?:y|ies)|femdom|dominatrix|"
                        + "petplay|degrad(?:e|ed|ing|ation)"));
        values.add(rule(TextSmutConfig.CATEGORY_FETISH, 2,
                "strap|edg(?:e|ed|ing)|spank\\w*|whip\\w*|breed\\w*|domme|submissive|"
                        + "humiliat\\w*|keyholders?|deni(?:al|ed)|locked up|caged|owned"));
        values.add(rule(TextSmutConfig.CATEGORY_FETISH, 2,
                "daddy|mommy|master|mistress|slave|owner|good girls?|good boys?|"
                        + "bad girls?|bad boys?|little one|needy"));
        values.add(rule(TextSmutConfig.CATEGORY_FETISH, 2,
                "force\\w*|obey|behav(?:e|ing)|permission|reward|punish\\w*|eyes forward|"
                        + "on your knees|hands behind|beg(?:ging)?|locked up|use you|ruin you"));
        values.add(rule(TextSmutConfig.CATEGORY_FETISH, 4,
                "call(?:ed|ing)? (?:me |you |them |him |her )?(?:a )?loser|voice message.*loser"));
        values.add(rule(TextSmutConfig.CATEGORY_FETISH, 1,
                "feet|armpits?|sir|ma am|good pet|collar(?:ed)?"));
        values.add(phrase(TextSmutConfig.CATEGORY_SOLICITATION, 5,
                "send nudes", "suck my", "suck the", "ride me", "touch yourself",
                "make me cum", "make you cum", "open your legs", "open my legs",
                "not negotiable", "meet for sex", "fuck me", "fuck you until"));
        values.add(phrase(TextSmutConfig.CATEGORY_SOLICITATION, 3,
                "onlyfans", "fansly", "cam girl", "camgirl", "adult content", "link in bio"));
        return Collections.unmodifiableList(values);
    }

    private static Rule rule(String category, int points, String alternatives) {
        return new Rule(category, points, Pattern.compile("\\b(?:" + alternatives + ")\\b"));
    }

    private static Rule phrase(String category, int points, String... phrases) {
        StringBuilder pattern = new StringBuilder();
        for (String phrase : phrases) {
            if (pattern.length() > 0) pattern.append('|');
            pattern.append(Pattern.quote(phrase));
        }
        return new Rule(category, points, Pattern.compile("(?:" + pattern + ")"));
    }

    private static final class Rule {
        private final String category;
        private final int points;
        private final Pattern pattern;

        private Rule(String category, int points, Pattern pattern) {
            this.category = category;
            this.points = points;
            this.pattern = pattern;
        }
    }

    public static final class Match {
        private static final Match NONE = new Match(false, "", 0, 0f, 0, 0, 0);
        private final boolean matched;
        private final String category;
        private final int score;
        private final float confidence;
        private final int startIndex;
        private final int endIndex;
        private final int normalizedLength;

        private Match(boolean matched, String category, int score, float confidence,
                int startIndex, int endIndex, int normalizedLength) {
            this.matched = matched;
            this.category = category;
            this.score = score;
            this.confidence = confidence;
            this.startIndex = startIndex;
            this.endIndex = endIndex;
            this.normalizedLength = normalizedLength;
        }

        public static Match none() { return NONE; }
        static Match semantic(String category, float confidence, int normalizedLength) {
            return new Match(true, category, 0, confidence,
                    0, Math.max(1, normalizedLength), normalizedLength);
        }
        public boolean isMatched() { return matched; }
        public String getCategory() { return category; }
        public int getScore() { return score; }
        public float getConfidence() { return confidence; }
        public int getStartIndex() { return startIndex; }
        public int getEndIndex() { return endIndex; }
        public int getNormalizedLength() { return normalizedLength; }
    }
}
