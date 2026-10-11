package com.subhub.app.censor.engine;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class PixelPairMatcherTest {
    private static final int W = 64, H = 128;
    private static final PixelPairMatcher.Policy STRICT = new PixelPairMatcher.Policy(4, 8, 0, 32, 10_000_000);

    @Test public void independentlyQualifiesTexturedZeroAndMergesOnlyAdjacentSupport() {
        int[] a = texture(1);
        PixelPairMatcher.Result result = match(a, a);
        assertEquals(PixelPairMatcher.Status.COMPLETE, result.status);
        assertEquals(32, result.texturedTiles);
        assertEquals(1, result.evidence.size());
        PixelPairMatcher.Evidence piece = result.evidence.get(0);
        assertEquals(0, piece.sampledDx); assertEquals(0, piece.sampledDy);
        assertEquals(0, piece.sampledSupport.left, 0); assertEquals(W, piece.sampledSupport.right, 0);
        assertEquals(0, piece.sampledSupport.top, 0); assertEquals(H, piece.sampledSupport.bottom, 0);
        assertFalse(piece.physicalErrorKnown);
        assertEquals(-1, result.from.pixelAtMillis); assertEquals(-1, result.to.pixelAtMillis);
    }

    @Test public void fullTwoDimensionalSearchHasCorrectSigns() {
        for (int[] delta : new int[][] {{13, 17}, {-13, -17}, {0, -80}, {0, 80}, {40, 0}, {-40, 0}}) {
            int[] a = texture(2);
            PixelPairMatcher.Result result = match(a, shift(a, delta[0], delta[1]));
            assertEquals(PixelPairMatcher.Status.COMPLETE, result.status);
            assertFalse(Arrays.toString(delta), result.evidence.isEmpty());
            for (PixelPairMatcher.Evidence piece : result.evidence) {
                assertEquals(delta[0], piece.sampledDx); assertEquals(delta[1], piece.sampledDy);
                assertTrue(piece.sampledSupport.left + piece.sampledDx >= 0);
                assertTrue(piece.sampledSupport.right + piece.sampledDx <= W);
                assertTrue(piece.sampledSupport.top + piece.sampledDy >= 0);
                assertTrue(piece.sampledSupport.bottom + piece.sampledDy <= H);
            }
        }
    }

    @Test public void pairOrderingDoesNotAssumeVelocityOrPreventReversal() {
        int[] a = texture(3), b = shift(a, 0, -32);
        PixelPairMatcher.Result first = match(a, b), second = match(b, a);
        assertFalse(first.evidence.isEmpty()); assertFalse(second.evidence.isEmpty());
        for (PixelPairMatcher.Evidence piece : first.evidence) assertEquals(-32, piece.sampledDy);
        for (PixelPairMatcher.Evidence piece : second.evidence) assertEquals(32, piece.sampledDy);
    }

    @Test public void stationaryHeaderAndLargeBodyScrollCoexistWithoutInventedPartitions() {
        int[] a = texture(4), b = flat();
        System.arraycopy(a, 0, b, 0, W * 16);
        for (int y = 80; y < H; y++) System.arraycopy(a, y * W, b, (y - 64) * W, W);
        PixelPairMatcher.Result result = match(a, b);
        assertEquals(2, result.evidence.size());
        assertContains(result, new SceneEngine.Region(2, 2, 14, 14), 0, 0);
        assertContains(result, new SceneEngine.Region(18, 82, 30, 94), 0, -64);
        assertUnsupported(result, new SceneEngine.Region(2, 2, 14, 94));
    }

    @Test public void changingMediaAndUnknownInteriorNeverBecomeRectangleBridges() {
        int[] a = texture(5), b = a.clone();
        for (int y = 32; y < 64; y++) Arrays.fill(b, y * W, (y + 1) * W, 0xff000000);
        PixelPairMatcher.Result result = match(a, b);
        assertEquals(2, result.evidence.size());
        assertContains(result, new SceneEngine.Region(0, 0, W, 32), 0, 0);
        assertContains(result, new SceneEngine.Region(0, 64, W, H), 0, 0);
        assertUnsupported(result, new SceneEngine.Region(2, 2, 14, 100));
    }

    @Test public void aSingleUnqualifiedInteriorPixelRejectsItsWholeTile() {
        int[] a = texture(6), b = a.clone();
        b[8 * W + 8] ^= 0xff;
        PixelPairMatcher.Result result = match(a, b);
        assertUnsupported(result, new SceneEngine.Region(0, 0, 16, 16));
        assertContains(result, new SceneEngine.Region(16, 0, 32, 16), 0, 0);
    }

    @Test public void flatAndHorizontalVerticalAndDiagonalAperturesAreUnknown() {
        for (int pattern = 0; pattern < 4; pattern++) {
            int[] a = new int[W * H];
            for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
                int v = pattern == 0 ? 100 : pattern == 1 ? (x * 17) & 255
                        : pattern == 2 ? (y * 17) & 255 : ((x + y) * 17) & 255;
                a[y * W + x] = color(v);
            }
            assertTrue("pattern " + pattern, match(a, a).evidence.isEmpty());
        }
    }

    @Test public void twoDimensionalRepetitionIsAmbiguousNotZero() {
        int[] a = new int[W * H], patch = texture(7);
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) a[y * W + x] = patch[(y % 8) * W + x % 8];
        PixelPairMatcher.Result result = match(a, a);
        assertTrue(result.ambiguousTiles > 0); assertTrue(result.evidence.isEmpty());
    }

    @Test public void finiteWholeFrameSearchDoesNotTurnDiagonalApertureIntoZero() {
        int[] a = new int[W * H];
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) a[y * W + x] = color(((x + y) * 17) & 255);
        PixelPairMatcher.Result result = new PixelPairMatcher(new PixelPairMatcher.Policy(1, 1, 0, 32, 10_000_000))
                .match(image(a, 1), image(a, 2), () -> false);
        assertEquals(PixelPairMatcher.Status.COMPLETE, result.status);
        assertEquals(0, result.texturedTiles); assertTrue(result.evidence.isEmpty());
    }

    @Test public void offGridCloneIsFoundAndRejectsForwardUniqueness() {
        int[] a = texture(8), b = a.clone();
        copyTile(a, 0, 0, b, 21, 23, 16, 16);
        PixelPairMatcher.Result result = match(a, b);
        assertUnsupported(result, new SceneEngine.Region(0, 0, 16, 16));
        assertTrue(result.ambiguousTiles > 0);
    }

    @Test public void inverseUniquenessAlsoSearchesOffGridOrigins() {
        int[] a = texture(9), b = texture(10);
        copyTile(a, 0, 0, a, 21, 23, 16, 16);
        copyTile(a, 0, 0, b, 0, 0, 16, 16);
        PixelPairMatcher.Result result = match(a, b);
        assertUnsupported(result, new SceneEngine.Region(0, 0, 16, 16));
        assertTrue(result.ambiguousTiles > 0);
    }

    @Test public void equallyEligibleNoisyAliasIsNotHiddenByAnExactBest() {
        int[] a = texture(11), b = a.clone();
        copyTile(a, 0, 0, b, 21, 23, 16, 16);
        for (int y = 23; y < 39; y++) for (int x = 21; x < 37; x++) b[y * W + x] += 0x010101;
        PixelPairMatcher.Result result = new PixelPairMatcher(new PixelPairMatcher.Policy(4, 8, 1, 32, 10_000_000))
                .match(image(a, 1), image(b, 2), () -> false);
        assertUnsupported(result, new SceneEngine.Region(0, 0, 16, 16));
    }

    @Test public void declaresActualResidualWithoutClaimingPhysicalUncertainty() {
        int[] a = texture(12), b = a.clone();
        for (int i = 0; i < b.length; i++) b[i] += 0x010101;
        PixelPairMatcher.Result result = new PixelPairMatcher(new PixelPairMatcher.Policy(4, 8, 1, 32, 10_000_000))
                .match(image(a, 1), image(b, 2), () -> false);
        assertEquals(1, result.evidence.size());
        assertEquals(1, result.evidence.get(0).maximumObservedChannelError);
        assertFalse(result.evidence.get(0).physicalErrorKnown);
    }

    @Test public void independentVideoCanMoveDifferentlyFromScrollingBody() {
        int[] a = texture(13), b = flat();
        for (int y = 64; y < H; y++) System.arraycopy(a, y * W, b, (y - 32) * W, W);
        copyTile(a, 0, 0, b, 32, 0, 16, 16);
        PixelPairMatcher.Result result = match(a, b);
        assertContains(result, new SceneEngine.Region(0, 0, 16, 16), 32, 0);
        assertContains(result, new SceneEngine.Region(0, 64, W, H), 0, -32);
    }

    @Test public void destinationConvergenceMakesBothPiecesUnknown() {
        int[] b = texture(14), a = texture(15);
        copyTile(b, 0, 0, a, 0, 0, 16, 16);
        copyTile(b, 8, 0, a, 16, 0, 16, 16);
        PixelPairMatcher.Result result = match(a, b);
        assertEquals(2, result.destinationConflicts);
        assertUnsupported(result, new SceneEngine.Region(0, 0, 16, 16));
        assertUnsupported(result, new SceneEngine.Region(16, 0, 32, 16));
    }

    @Test public void unrelatedAndNoOverlapAndScaleReflowStayUnknown() {
        int[] a = texture(16), b = texture(17), scaled = flat();
        assertTrue(match(a, b).evidence.isEmpty());
        assertTrue(match(a, shift(a, 0, H)).evidence.isEmpty());
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) scaled[y * W + x] = a[(y / 2) * W + x / 2];
        assertTrue(match(a, scaled).evidence.isEmpty());
    }

    @Test public void anExhaustedSearchIsExplicitAndDiscardsAlreadyQualifiedPieces() {
        int[] a = texture(18);
        PixelPairMatcher.Result complete = match(a, a);
        PixelPairMatcher.Result result = new PixelPairMatcher(new PixelPairMatcher.Policy(4, 8, 0, 32,
                complete.pixelComparisons / 2)).match(image(a, 1), image(a, 2), () -> false);
        assertEquals(PixelPairMatcher.Status.WORK_BUDGET, result.status);
        assertTrue(result.evidence.isEmpty());
        assertEquals(complete.pixelComparisons / 2, result.pixelComparisons);
    }

    @Test public void cancellationHasABoundedPollingIntervalAndNoPartialResult() {
        int[] a = texture(19);
        AtomicInteger polls = new AtomicInteger();
        PixelPairMatcher.Result result = new PixelPairMatcher(STRICT).match(image(a, 1), image(a, 2),
                () -> polls.incrementAndGet() > 100);
        assertEquals(PixelPairMatcher.Status.CANCELLED, result.status);
        assertTrue(result.evidence.isEmpty()); assertTrue(result.pixelComparisons <= 100L * 256);
        result = new PixelPairMatcher(STRICT).match(image(a, 1), image(a, 2), () -> true);
        assertEquals(PixelPairMatcher.Status.CANCELLED, result.status); assertEquals(0, result.pixelComparisons);
    }

    @Test public void rasterCopyAndPublishedResultAreImmutable() {
        int[] a = texture(20), b = a.clone();
        PixelPairMatcher.Image image = image(a, 1);
        Arrays.fill(a, 0);
        PixelPairMatcher.Result result = new PixelPairMatcher(STRICT).match(image, image(b, 2), () -> false);
        assertEquals(1, result.evidence.size());
        try { result.evidence.clear(); fail(); } catch (UnsupportedOperationException expected) { }
    }

    @Test public void sourceScopeOrderingAndChangedCropAreNotGuessed() {
        int[] a = texture(21);
        PixelPairMatcher matcher = new PixelPairMatcher(STRICT);
        assertEquals(PixelPairMatcher.Status.INVALID_INPUT, matcher.match(image(a, 2), image(a, 1), () -> false).status);
        assertEquals(PixelPairMatcher.Status.INVALID_INPUT, matcher.match(image(a, 1), image(a, 1), () -> false).status);
        PixelPairMatcher.Image differentEpoch = PixelPairMatcher.Image.copyOf(new SceneEngine.Source(1, 2, 1, W, H, 0, -1),
                PixelGeometry.display(W, H, W, H), W, H, PixelPairMatcher.Provenance.UNCENSORED_ACCESSIBILITY_DISPLAY,
                PixelPairMatcher.Sampling.EXACT_RASTER, a);
        assertEquals(PixelPairMatcher.Status.INVALID_INPUT, matcher.match(image(a, 1), differentEpoch, () -> false).status);
        PixelPairMatcher.Image crop = PixelPairMatcher.Image.copyOf(new SceneEngine.Source(0, 2, 1, W + 1, H, 0, -1),
                PixelGeometry.window(W + 1, H, 1, 0, W, H, W, H), W, H,
                PixelPairMatcher.Provenance.UNCENSORED_ACCESSIBILITY_DISPLAY, PixelPairMatcher.Sampling.EXACT_RASTER, a);
        assertNotNull(crop);
        assertEquals(PixelPairMatcher.Status.INVALID_INPUT, matcher.match(image(a, 1), crop, () -> false).status);
    }

    @Test public void sampleFootprintIsExplicitButNotAnErrorBound() {
        int[] a = texture(22), b = shift(a, 0, -80);
        PixelGeometry geometry = PixelGeometry.window(W * 3 + 10, H * 3 + 20, 10, 20, W * 3, H * 3, W * 3, H * 3);
        PixelPairMatcher.Image first = PixelPairMatcher.Image.copyOf(new SceneEngine.Source(0, 1, 1, W * 3 + 10, H * 3 + 20, 0, -1),
                geometry, W, H, PixelPairMatcher.Provenance.UNCENSORED_ACCESSIBILITY_DISPLAY, PixelPairMatcher.Sampling.BOX_AVERAGE, a);
        PixelPairMatcher.Image second = PixelPairMatcher.Image.copyOf(new SceneEngine.Source(0, 2, 1, W * 3 + 10, H * 3 + 20, 334, -1),
                geometry, W, H, PixelPairMatcher.Provenance.UNCENSORED_ACCESSIBILITY_DISPLAY, PixelPairMatcher.Sampling.BOX_AVERAGE, b);
        PixelPairMatcher.Result result = new PixelPairMatcher(STRICT).match(first, second, () -> false);
        assertFalse(result.evidence.isEmpty());
        assertEquals(geometry, result.fromGeometry); assertEquals(geometry, result.toGeometry);
        assertEquals(W, result.sampledWidth); assertEquals(H, result.sampledHeight);
        for (PixelPairMatcher.Evidence piece : result.evidence) {
            assertEquals(-240, piece.nominalDy, 0); assertEquals(3, piece.cellHeight, 0);
            assertEquals(10 + piece.sampledSupport.left * 3, piece.sourceFootprint.left, 0);
            assertEquals(20 + piece.sampledSupport.top * 3, piece.sourceFootprint.top, 0);
            assertFalse(piece.physicalErrorKnown);
        }
    }

    @Test public void boundsAndOpaqueUncensoredInputAreRequiredBeforeSearch() {
        int[] a = texture(23);
        SceneEngine.Source source = new SceneEngine.Source(0, 1, 1, W, H, 0, -1);
        PixelGeometry geometry = PixelGeometry.display(W, H, W, H);
        assertNull(PixelPairMatcher.Image.copyOf(source, geometry, 257, H,
                PixelPairMatcher.Provenance.UNCENSORED_ACCESSIBILITY_DISPLAY, PixelPairMatcher.Sampling.EXACT_RASTER, a));
        assertNull(PixelPairMatcher.Image.copyOf(source, geometry, W, H,
                PixelPairMatcher.Provenance.CENSORED, PixelPairMatcher.Sampling.EXACT_RASTER, a));
        a[0] &= 0xffffff;
        assertNull(PixelPairMatcher.Image.copyOf(source, geometry, W, H,
                PixelPairMatcher.Provenance.UNCENSORED_ACCESSIBILITY_DISPLAY, PixelPairMatcher.Sampling.EXACT_RASTER, a));
        assertEquals(PixelPairMatcher.Status.INVALID_INPUT, new PixelPairMatcher(STRICT).match(null, null, () -> false).status);
        try { new PixelPairMatcher.Policy(9, 8, 0, 32, 100); fail(); } catch (IllegalArgumentException expected) { }
        try { new PixelPairMatcher.Policy(4, 8, 16, 32, 100); fail(); } catch (IllegalArgumentException expected) { }
    }

    private static PixelPairMatcher.Result match(int[] a, int[] b) {
        return new PixelPairMatcher(STRICT).match(image(a, 1), image(b, 2), () -> false);
    }
    private static PixelPairMatcher.Image image(int[] argb, long sequence) {
        return PixelPairMatcher.Image.copyOf(new SceneEngine.Source(0, sequence, 1, W, H, sequence * 334, -1),
                PixelGeometry.display(W, H, W, H), W, H, PixelPairMatcher.Provenance.UNCENSORED_ACCESSIBILITY_DISPLAY,
                PixelPairMatcher.Sampling.EXACT_RASTER, argb);
    }
    private static int[] texture(int seed) {
        Random random = new Random(seed);
        int[] pixels = new int[W * H];
        for (int i = 0; i < pixels.length; i++) pixels[i] = 0xff000000 | (32 + random.nextInt(192)) << 16
                | (32 + random.nextInt(192)) << 8 | (32 + random.nextInt(192));
        return pixels;
    }
    private static int[] flat() { int[] values = new int[W * H]; Arrays.fill(values, 0xff000000); return values; }
    private static int color(int value) { return 0xff000000 | value << 16 | value << 8 | value; }
    private static int[] shift(int[] original, int dx, int dy) {
        int[] result = flat();
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            if (x + dx >= 0 && x + dx < W && y + dy >= 0 && y + dy < H) result[(y + dy) * W + x + dx] = original[y * W + x];
        }
        return result;
    }
    private static void copyTile(int[] from, int x, int y, int[] to, int tx, int ty, int width, int height) {
        int[] copied = new int[width * height];
        for (int row = 0; row < height; row++) System.arraycopy(from, (y + row) * W + x, copied, row * width, width);
        for (int row = 0; row < height; row++) System.arraycopy(copied, row * width, to, (ty + row) * W + tx, width);
    }
    private static void assertContains(PixelPairMatcher.Result result, SceneEngine.Region region, int dx, int dy) {
        for (PixelPairMatcher.Evidence piece : result.evidence) {
            if (piece.sampledSupport.contains(region) && piece.sampledDx == dx && piece.sampledDy == dy) return;
        }
        fail("No entire supported region with the expected translation");
    }
    private static void assertUnsupported(PixelPairMatcher.Result result, SceneEngine.Region region) {
        for (PixelPairMatcher.Evidence piece : result.evidence) assertFalse(piece.sampledSupport.contains(region));
    }
}
