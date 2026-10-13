package com.subhub.app.censor.engine;

/** Explicit private-candidate eligibility, separate from emulator instrumentation fixtures. */
public final class CensorCandidateGate {
    private CensorCandidateGate() { }

    public static boolean supported(boolean privateCandidateBuild, int sdk) {
        return privateCandidateBuild && sdk >= 34;
    }

    /** Neither a stale preference nor coerced imported data can enable an ordinary build. */
    public static boolean selected(boolean privateCandidateBuild, int sdk, Object storedValue) {
        return supported(privateCandidateBuild, sdk) && Boolean.TRUE.equals(storedValue);
    }
}
