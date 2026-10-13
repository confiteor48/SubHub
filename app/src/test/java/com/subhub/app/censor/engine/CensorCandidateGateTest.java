package com.subhub.app.censor.engine;

import static org.junit.Assert.*;
import org.junit.Test;

public final class CensorCandidateGateTest {
    @Test public void ordinaryBuildRejectsEvenPersistedTrue() {
        for (int sdk : new int[]{26, 30, 33, 34, 35, 36}) {
            assertFalse(CensorCandidateGate.supported(false, sdk));
            assertFalse(CensorCandidateGate.selected(false, sdk, true));
        }
    }

    @Test public void unsupportedSdkCannotSelectPrivateCandidate() {
        for (int sdk = 26; sdk <= 33; sdk++) {
            assertFalse(CensorCandidateGate.supported(true, sdk));
            assertFalse(CensorCandidateGate.selected(true, sdk, true));
        }
    }

    @Test public void supportedPrivateBuildStillDefaultsOff() {
        for (int sdk : new int[]{34, 35, 36}) {
            assertTrue(CensorCandidateGate.supported(true, sdk));
            assertFalse(CensorCandidateGate.selected(true, sdk, null));
            assertFalse(CensorCandidateGate.selected(true, sdk, false));
        }
    }

    @Test public void literalTrueSelectsSupportedPrivateBuild() {
        assertTrue(CensorCandidateGate.selected(true, 34, true));
        assertTrue(CensorCandidateGate.selected(true, 35, Boolean.TRUE));
    }

    @Test public void malformedDataNeverCoercesToAuthority() {
        for (Object value : new Object[]{"true", "TRUE", "1", 1, 1L, new Object()}) {
            assertFalse(CensorCandidateGate.selected(true, 35, value));
        }
    }
}
