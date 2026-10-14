package com.subhub.app.censor.engine;

/** Explicit development/private test eligibility, separate from emulator instrumentation fixtures. */
public final class CensorCandidateGate {
    private CensorCandidateGate() { }

    /** Normal numbered development updates carry the opt-in selector without private flags. */
    public static boolean buildAllowed(boolean privateCandidateBuild, String versionName) {
        return privateCandidateBuild || (versionName != null && versionName.matches(
                "(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)-dev\\.[1-9][0-9]{0,4}"));
    }

    public static boolean supported(boolean allowedBuild, int sdk) {
        return allowedBuild && sdk >= 34;
    }

    /** Neither a stale preference nor coerced imported data can enable an ineligible build. */
    public static boolean selected(boolean allowedBuild, int sdk, Object storedValue) {
        return supported(allowedBuild, sdk) && Boolean.TRUE.equals(storedValue);
    }
}
