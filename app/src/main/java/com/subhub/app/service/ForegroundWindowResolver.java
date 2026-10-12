package com.subhub.app.service;

import com.subhub.app.appmode.AppModePolicy;

import java.util.Collections;
import java.util.List;

/** Selects the real foreground application window from Accessibility's interactive windows. */
public final class ForegroundWindowResolver {
    private ForegroundWindowResolver() {}

    public static Candidate select(List<Candidate> candidates, String inputMethodPackage) {
        return select(candidates, inputMethodPackage, "");
    }

    public static Candidate select(
            List<Candidate> candidates,
            String inputMethodPackage,
            String preferredPackage) {
        List<Candidate> source = candidates == null ? Collections.emptyList() : candidates;
        String preferred = preferredPackage == null ? "" : preferredPackage.trim();
        Candidate best = null;
        int bestScore = Integer.MIN_VALUE;
        for (Candidate candidate : source) {
            if (candidate == null || !AppModePolicy.shouldAcceptLiveForegroundPackage(
                    candidate.packageName, inputMethodPackage)) continue;
            // A small application-owned overlay can temporarily become Accessibility's focused
            // TYPE_APPLICATION window while the protected app remains plainly visible beneath
            // it. Keep the current protected window until it actually leaves the window list;
            // otherwise recognition tears down for one frame and every censor flashes off.
            int score = (candidate.packageName.equals(preferred) ? 4_000_000 : 0)
                    + (candidate.focused ? 2_000_000 : 0)
                    + (candidate.active ? 1_000_000 : 0)
                    + candidate.layer;
            if (best == null || score > bestScore) {
                best = candidate;
                bestScore = score;
            }
        }
        return best;
    }

    public static final class Candidate {
        public final String packageName;
        public final int windowId;
        public final boolean active;
        public final boolean focused;
        public final int layer;

        public Candidate(String packageName, int windowId, boolean active, boolean focused, int layer) {
            this.packageName = packageName == null ? "" : packageName;
            this.windowId = windowId;
            this.active = active;
            this.focused = focused;
            this.layer = layer;
        }
    }
}
