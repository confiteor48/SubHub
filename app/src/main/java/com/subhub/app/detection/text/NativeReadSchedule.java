package com.subhub.app.detection.text;

import com.subhub.app.censor.engine.NativeTextResult;
import java.util.Objects;

/** Serial reader attempt selection only. A quota never grants evidence, geometry or discovery completeness. */
final class NativeReadSchedule {
    private int directRemaining;

    NativeTextResult.ReadMode next(boolean addressRefreshSupported, int admittedAddresses) {
        bounded(admittedAddresses);
        if (addressRefreshSupported && admittedAddresses > 0 && directRemaining > 0) {
            if (admittedAddresses >= NativeTextResult.COHORT_CANDIDATES && directRemaining >= NativeTextResult.COHORT_CANDIDATES) {
                directRemaining -= NativeTextResult.COHORT_CANDIDATES;
                return NativeTextResult.ReadMode.ADMITTED_NODE_COHORT_RECLASSIFY;
            }
            directRemaining--;
            return NativeTextResult.ReadMode.ADMITTED_NODE_RECLASSIFY;
        }
        return NativeTextResult.ReadMode.FULL_DISCOVERY;
    }

    void terminal(NativeTextResult.ReadMode mode, int admittedAddresses) {
        Objects.requireNonNull(mode); bounded(admittedAddresses);
        // Preserve the existing FULL-terminal refill, including a failed attempt with retained addresses.
        if (mode == NativeTextResult.ReadMode.FULL_DISCOVERY) directRemaining = admittedAddresses;
    }

    private static void bounded(int addresses) {
        if (addresses < 0 || addresses > NativeTextResult.MAXIMUM_CANDIDATES)
            throw new IllegalArgumentException("Native address inventory bound");
    }
}
