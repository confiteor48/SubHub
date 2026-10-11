package com.subhub.app.overlay;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Phrase library compatible with the recovered category/custom-phrase preference model. */
public final class CensorPhrases {
    public static final Set<String> DEFAULT_ENABLED = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList("short", "denial")));

    private static final Map<String, List<String>> CATEGORIES = categories();

    private CensorPhrases() {}

    public static List<String> build(Set<String> enabled, Set<String> custom) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        Set<String> selected = enabled == null ? DEFAULT_ENABLED : enabled;
        for (String category : selected) {
            List<String> phrases = CATEGORIES.get(category);
            if (phrases != null) result.addAll(phrases);
        }
        if (custom != null) {
            for (String phrase : custom) {
                String normalized = normalize(phrase);
                if (!normalized.isEmpty()) result.add(normalized);
            }
        }
        if (result.isEmpty()) result.addAll(Arrays.asList("BLOCKED", "CENSORED", "DENIED"));
        return Collections.unmodifiableList(new ArrayList<>(result));
    }

    public static Set<String> categoryNames() {
        return Collections.unmodifiableSet(CATEGORIES.keySet());
    }

    private static Map<String, List<String>> categories() {
        Map<String, List<String>> value = new LinkedHashMap<>();
        value.put("short", Arrays.asList(
                "BLOCKED",
                "CENSORED",
                "DENIED",
                "LOCKED",
                "NO",
                "NOT A CHANCE",
                "NEVER"));
        value.put("denial", Arrays.asList(
                "DENIED, DARLING",
                "DREAM ON",
                "EYES OFF",
                "LOOK ELSEWHERE",
                "NICE TRY",
                "NO PEEKING",
                "NOT TODAY",
                "RULES ARE RULES",
                "STILL NO",
                "WISHFUL THINKING",
                "STOP TRYING",
                "ALL YOU GET IS CENSORED",
                "NOT FOR YOU",
                "NEVER FOR YOU"));
        value.put("humiliation", Arrays.asList(
                "ASK NICELY",
                "BACK IN LINE",
                "BEHAVE, CUCK",
                "CUTE TRY, BETA",
                "EYES DOWN, BETA",
                "GOOD LITTLE BETA",
                "KEEP DREAMING, CUCK",
                "KNOW YOUR ROLE",
                "LESS BRAVADO",
                "STILL WAITING?",
                "BETACHIP ACTIVATED",
                "BETAS DON'T GET TO SEE",
                "YOU LIKE THIS DON'T YOU?",
                "HURT YOURSELF FOR ME"));
        value.put("edge", Arrays.asList(
                "ANTICIPATION",
                "ENJOY THE WAIT",
                "HOLD THAT THOUGHT",
                "NOT YET",
                "PATIENCE, TROUBLE",
                "SLOW DOWN",
                "SO CLOSE",
                "STILL WAITING",
                "THE WAIT CONTINUES",
                "WAIT FOR IT",
                "NEVER FINISH",
                "IT FEELS BETTER THIS WAY"));
        value.put("findom", Arrays.asList(
                "EXCLUSIVE ACCESS",
                "LOOKS EXPENSIVE",
                "LUXURY DENIED",
                "NOT ON THE LIST",
                "PREMIUM TEMPTATION",
                "PRICELESS PATIENCE",
                "PRIVATE COLLECTION",
                "TRIBUTE THEATRE",
                "VELVET ROPE",
                "VIP VIEW",
                "YOU CAN'T AFFORD IT",
                "$END MORE",
                "PATHETIC PAYPIG",
                "RUIN YOUR LIFE FOR ME",
                "NEVER STOP $ENDING"));
        value.put("ntr", Arrays.asList(
                "GUEST LIST CLOSED",
                "JUST A SPECTATOR",
                "NOT YOUR VIEW",
                "OUTSIDE LOOKING IN",
                "PRIVATE SHOW",
                "RESERVED ELSEWHERE",
                "SIDE SEAT",
                "SPECTATOR ONLY",
                "WATCH THE RULES",
                "WRONG INVITATION",
                "YOU AREN'T NEEDED",
                "HAPPIER WITHOUT YOU",
                "STOP BREATHING",
                "LOOK AWAY",
                "YOU LOST",
                "THEY ARE BETTER"));
        value.put("gooner", Arrays.asList(
                "CAUGHT LOOKING",
                "CURIOUS LITTLE THING",
                "EYES UP, TROUBLE",
                "NICE EXCUSE",
                "OH, REALLY?",
                "ONE MORE PEEK?",
                "SCROLLING, HUH?",
                "THAT LOOK AGAIN",
                "THERE YOU GO AGAIN",
                "YOU KNOW THE RULE",
                "KEEP GOING",
                "NEVER STOP",
                "WORK IS OVERRATED",
                "YOU DON'T NEED SLEEP",
                "CENSORS ARE YOUR ONLY FRIENDS",
                "NEVER CUM"));
        return value;
    }

    private static String normalize(String value) {
        if (value == null) return "";
        String trimmed = value.trim().replaceAll("\\s+", " ");
        if (trimmed.length() > 80) trimmed = trimmed.substring(0, 80);
        return trimmed.toUpperCase(Locale.ROOT);
    }
}
