package com.subhub.app.service;

import com.subhub.app.censor.engine.ProtectionScope;
import com.subhub.app.censor.engine.SceneEngine;
import com.subhub.app.censor.engine.ScrollMotionLedger;
import com.subhub.app.overlay.ViewportMotion;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

/** Provider-kind selection only; no image correspondence or physical/native-X performance claim. */
public final class ReplacementOwnerCurveSelectionTest {
    private static final ScrollMotionLedger.Scope SCOPE = new ScrollMotionLedger.Scope(1, 42, 7, ProtectionScope.normal(9));
    private static final SceneEngine.Region OWNER = new SceneEngine.Region(0, 200, 400, 2200);
    private static final SceneEngine.Region OTHER_OWNER = new SceneEngine.Region(500, 200, 1000, 2200);
    private static final ScrollMotionLedger.OwnerKind RECYCLER = ScrollMotionLedger.OwnerKind.RECYCLER_VIEW;
    private static final ScrollMotionLedger.OwnerKind OTHER = ScrollMotionLedger.OwnerKind.OTHER;
    private static final ScrollMotionLedger.OwnerKind UNKNOWN = ScrollMotionLedger.OwnerKind.UNKNOWN;

    private static final class Driver {
        final ScrollMotionLedger ledger = new ScrollMotionLedger(64, 16, 256);
        final ReplacementScrollPresentation presentation = new ReplacementScrollPresentation();
        final SceneEngine.Mask mask;
        long delivery;
        ScrollMotionLedger.Sample last;
        Driver(String packageName) {
            ledger.reset(SCOPE); presentation.reset(42, 7); presentation.configureNativeContext(packageName, 1, .015f);
            presentation.captured(1, 42, 1, 950, 960);
            SceneEngine scene = new SceneEngine(1, new SceneEngine.Policy(16, 100, 64, 256, 500, 5000, .5));
            SceneEngine.Source source = SceneEngine.Source.image(1, 1, 1, 42, 1000, 2400, 10, -1, SCOPE.protection);
            assertTrue(scene.register(source));
            assertTrue(scene.observe(new SceneEngine.Batch(source, SceneEngine.Lane.FAST,
                    Collections.singletonList(new SceneEngine.Observation("FACE", 1, 0,
                            new SceneEngine.Region(100, 600, 300, 800), .9f)), Collections.emptyList())));
            mask = scene.snapshot(1, 11, false).groups.get(0).members.get(0);
        }
        boolean event(long producer, long owner, long at, long position, SceneEngine.Region bounds, ScrollMotionLedger.OwnerKind kind) {
            last = new ScrollMotionLedger.Sample(SCOPE, ++delivery, owner, producer, at, at, at + 1, at + 2,
                    ScrollMotionLedger.Coordinate.absolute(0), ScrollMotionLedger.Coordinate.absolute(position), bounds, kind);
            return presentation.observe(ledger.observe(last, at + 3));
        }
        void seed(ScrollMotionLedger.OwnerKind kind) {
            for (int n = 0; n < 3; n++) assertTrue(event(1, 10, 1000 + n * 100, n * 300, OWNER, kind));
        }
        double top(long at) { return presentation.pose(mask, at).region.top; }
    }

    private static double reference(boolean nativeCurve, long at) {
        ViewportMotion motion = new ViewportMotion(); motion.reset(0, 0, 1003);
        motion.setNativeSpline(nativeCurve, 1, .015f, 1003);
        for (int n = 0; n < 3; n++) motion.addReportedDelta(0f, n == 0 ? 0f : -300f,
                1003 + n * 100, 400, 2000, 1000 + n * 100);
        return 600 + motion.position(at).y;
    }

    @Test public void exactValidatedRecyclerOwnerUsesUnchangedNativeCurveInXOnly() {
        Driver nativeX = new Driver("com.twitter.android"); nativeX.seed(RECYCLER);
        Driver browser = new Driver("org.chromium.chrome.stable"); browser.seed(RECYCLER);
        assertEquals(reference(true, 1256), nativeX.top(1256), .000001);
        assertEquals(reference(false, 1256), browser.top(1256), .000001);
        assertNotEquals(nativeX.top(1256), browser.top(1256), .001);
    }
    @Test public void unknownAndOtherOwnersDoNotBorrowThePackageGate() {
        for (ScrollMotionLedger.OwnerKind kind : new ScrollMotionLedger.OwnerKind[]{UNKNOWN, OTHER}) {
            Driver driver = new Driver("com.twitter.android"); driver.seed(kind);
            assertEquals(reference(false, 1256), driver.top(1256), .000001);
        }
    }
    @Test public void anotherOwnersGenericClassCannotRephaseNativeOwner() {
        Driver driver = new Driver("com.twitter.android"); driver.seed(RECYCLER);
        double before = driver.top(1256);
        assertTrue(driver.event(2, 20, 1240, 0, OTHER_OWNER, OTHER));
        assertTrue(driver.event(2, 20, 1250, 200, OTHER_OWNER, OTHER));
        assertEquals(before, driver.top(1256), .000001);
        assertEquals(600, driver.mask.displayRegion.top, 0); assertEquals(600, driver.mask.evidenceRegion.top, 0);
        assertEquals(-1, driver.mask.geometrySource.pixelAtMillis);
    }
    @Test public void anotherOwnersNativeClassCannotEnableGenericOwner() {
        Driver driver = new Driver("com.twitter.android"); driver.seed(OTHER);
        double before = driver.top(1256);
        assertTrue(driver.event(2, 20, 1240, 0, OTHER_OWNER, RECYCLER));
        assertTrue(driver.event(2, 20, 1250, 200, OTHER_OWNER, RECYCLER));
        assertEquals(before, driver.top(1256), .000001);
    }
    @Test public void staleAndDuplicateReceiptsCannotChangeCurrentOwnerCurveOrRevision() {
        Driver driver = new Driver("com.twitter.android"); driver.seed(RECYCLER);
        double before = driver.top(1256); long revision = driver.presentation.revision();
        assertFalse(driver.presentation.observe(driver.ledger.observe(driver.last, 1243)));
        ScrollMotionLedger.Scope foreign = new ScrollMotionLedger.Scope(1, 43, 7, SCOPE.protection);
        ScrollMotionLedger.Sample stale = new ScrollMotionLedger.Sample(foreign, 100, 10, 1, 1240, 1240, 1241, 1242,
                ScrollMotionLedger.Coordinate.absolute(0), ScrollMotionLedger.Coordinate.absolute(600), OWNER, OTHER);
        assertFalse(driver.presentation.observe(driver.ledger.observe(stale, 1243)));
        assertEquals(revision, driver.presentation.revision()); assertEquals(before, driver.top(1256), .000001);
    }
    @Test public void companionDuplicateCannotSelectAnotherKindForSameOwner() {
        Driver driver = new Driver("com.twitter.android"); driver.seed(RECYCLER);
        double before = driver.top(1256); long revision = driver.presentation.revision();
        ScrollMotionLedger.Sample companion = new ScrollMotionLedger.Sample(SCOPE, ++driver.delivery, 10, 2,
                1201, 1201, 1202, 1203, ScrollMotionLedger.Coordinate.delta(0), ScrollMotionLedger.Coordinate.delta(300), OWNER, OTHER);
        assertFalse(driver.presentation.observe(driver.ledger.observe(companion, 1204)));
        assertEquals(revision, driver.presentation.revision()); assertEquals(before, driver.top(1256), .000001);
    }
    @Test public void rejectedOversizedDeltaCannotChangeCurveKind() {
        Driver driver = new Driver("com.twitter.android"); driver.seed(RECYCLER);
        double before = driver.top(1256); long revision = driver.presentation.revision();
        assertFalse(driver.event(1, 10, 1240, 10000, OWNER, OTHER));
        assertEquals(revision, driver.presentation.revision()); assertEquals(before, driver.top(1256), .000001);
    }
    @Test public void genuineValidatedKindChangePreservesCurrentPositionAndSourceBaseline() {
        Driver driver = new Driver("com.twitter.android"); driver.seed(RECYCLER);
        double before = driver.top(1243);
        assertTrue(driver.event(1, 10, 1240, 600, OWNER, OTHER));
        assertEquals(before, driver.top(1243), .000001);
        assertEquals(600, driver.mask.displayRegion.top, 0);
        assertTrue(Double.isFinite(driver.top(1400)));
    }
    @Test public void sameDeliveryWithChangedOwnerKindIsAConflictNotADuplicate() {
        ScrollMotionLedger ledger = new ScrollMotionLedger(16, 16, 32); ledger.reset(SCOPE);
        ScrollMotionLedger.Sample first = new ScrollMotionLedger.Sample(SCOPE, 1, 10, 1, 100, 100, 101, 102,
                ScrollMotionLedger.Coordinate.absolute(0), ScrollMotionLedger.Coordinate.absolute(0), OWNER, RECYCLER);
        assertEquals(ScrollMotionLedger.Admission.BASELINE, ledger.observe(first, 103).admission);
        ScrollMotionLedger.Sample changed = new ScrollMotionLedger.Sample(SCOPE, 1, 10, 1, 100, 100, 101, 102,
                first.x, first.y, OWNER, OTHER);
        ScrollMotionLedger.Receipt refused = ledger.observe(changed, 104);
        assertEquals(ScrollMotionLedger.Admission.GAP, refused.admission);
        assertEquals(ScrollMotionLedger.Refusal.CONFLICT, refused.refusal);
    }
    @Test public void providerClassMappingDoesNotBroadenTheExactAllowlist() {
        assertEquals(RECYCLER, ScrollMotionLedger.OwnerKind.fromProviderClass(new StringBuilder("androidx.recyclerview.widget.RecyclerView")));
        for (String name : new String[]{"android.support.v7.widget.RecyclerView", "androidx.recyclerview.widget.RecyclerView$Child",
                "android.widget.ScrollView", "android.webkit.WebView", "android.view.View"})
            assertEquals(OTHER, ScrollMotionLedger.OwnerKind.fromProviderClass(name));
        assertEquals(UNKNOWN, ScrollMotionLedger.OwnerKind.fromProviderClass(null));
        assertEquals(UNKNOWN, ScrollMotionLedger.OwnerKind.fromProviderClass(""));
    }
    @Test public void contextCannotBeRetargetedByCallbackWithoutResettingScope() {
        Driver driver = new Driver("com.twitter.android"); driver.seed(RECYCLER);
        double before = driver.top(1256); long revision = driver.presentation.revision();
        assertThrows(IllegalStateException.class, () -> driver.presentation.configureNativeContext("org.chromium.chrome.stable", 1, .015f));
        assertEquals(revision, driver.presentation.revision()); assertEquals(before, driver.top(1256), .000001);
    }
}
