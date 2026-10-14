package com.subhub.app.censor.engine;

import static org.junit.Assert.*;
import org.junit.Test;

public final class CensorCandidateGateTest {
    @Test public void numberedDevelopmentUpdatesAllowExplicitTestingWithoutPrivateFlag() {
        for (String version : new String[]{"0.6.5-dev.1", "0.6.5-dev.129", "1.0.0-dev.99999"}) {
            boolean allowed = CensorCandidateGate.buildAllowed(false, version);
            assertTrue(allowed);
            assertTrue(CensorCandidateGate.supported(allowed, 34));
            assertFalse(CensorCandidateGate.selected(allowed, 34, null));
            assertFalse(CensorCandidateGate.selected(allowed, 34, false));
            assertTrue(CensorCandidateGate.selected(allowed, 34, true));
            assertFalse(CensorCandidateGate.selected(allowed, 33, true));
        }
    }

    @Test public void stableAndMalformedVersionsDoNotInheritCandidateSelection() {
        for (String version : new String[]{null, "", "0.6.5", "0.6.5-beta.129", "0.6.5-dev.0",
                "0.6.5-dev.0129", "0.6.5-dev.100000", "0.6.5-dev.129-extra", "0.6.5-dev.129\n",
                "00.6.5-dev.129", "0.06.5-dev.129", "0.6.05-dev.129", "0.6-dev.129"}) {
            boolean allowed = CensorCandidateGate.buildAllowed(false, version);
            assertFalse(allowed);
            assertFalse(CensorCandidateGate.selected(allowed, 35, true));
        }
    }

    @Test public void privateCandidatesRemainAvailableIndependentlyOfDevelopmentSuffix() {
        assertTrue(CensorCandidateGate.buildAllowed(true, "0.6.5"));
        assertTrue(CensorCandidateGate.buildAllowed(true, null));
        assertFalse(CensorCandidateGate.supported(CensorCandidateGate.buildAllowed(true, "0.6.5"), 33));
    }

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
            assertFalse(CensorCandidateGate.selected(CensorCandidateGate.buildAllowed(false, "0.6.5-dev.129"), 35, value));
        }
    }
}
