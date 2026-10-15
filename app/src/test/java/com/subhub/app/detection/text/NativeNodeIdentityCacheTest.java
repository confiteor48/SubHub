package com.subhub.app.detection.text;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import java.util.Arrays;
import com.subhub.app.censor.engine.NativeTextFrame;
import com.subhub.app.censor.engine.NativeTextResult;
import com.subhub.app.censor.engine.SceneEngine;
import com.subhub.app.censor.engine.ProtectionScope;
import org.junit.Test;

public final class NativeNodeIdentityCacheTest {
    private static final class Node {
        final int exactIdentity;
        int geometry;
        final boolean owned;
        boolean released;
        int attempts;
        Node(int identity, boolean owned) { exactIdentity = identity; this.owned = owned; }
    }
    private static final class Handles implements NativeNodeIdentityCache.Handles<Node> {
        final List<Node> copies = new ArrayList<>();
        int failReleaseIdentity = -1;
        boolean copyFails, alias, wrongIdentity;
        @Override public boolean same(Node a, Node b) { return a.exactIdentity == b.exactIdentity; }
        @Override public Node copy(Node node) {
            if (copyFails) throw new IllegalStateException("copy before transfer");
            if (alias) return node;
            Node copy = new Node(node.exactIdentity + (wrongIdentity ? 1 : 0), true);
            copies.add(copy); return copy;
        }
        @Override public void release(Node node) {
            assertTrue("Never release borrowed provider input", node.owned);
            assertFalse("No duplicate successful release", node.released);
            node.attempts++;
            if (node.exactIdentity == failReleaseIdentity) throw new IllegalStateException("uncertain release");
            node.released = true;
        }
    }
    private static Node node(int identity) { return new Node(identity, false); }
    private static void refused(Runnable action) {
        try { action.run(); fail("Expected refusal"); } catch (IllegalStateException expected) { }
    }
    private static NativeTextFrame frame(long sequence, long start, boolean partial, long... tokens) {
        List<NativeTextResult.Candidate> candidates = new ArrayList<>();
        for (long token : tokens) candidates.add(new NativeTextResult.Candidate("TEXT_SMUT", String.format("%064x", 99),
                String.format("%064x", token), .9f, true, Collections.singletonList(new SceneEngine.Region(1, 2, 10, 20)),
                NativeTextResult.TextSource.TEXT, 0, 4, 4, start + 60));
        NativeTextResult read = new NativeTextResult(1000, 2400, 7, start, start + 70, candidates, tokens.length,
                tokens.length, tokens.length, partial ? NativeTextResult.Coverage.POSITIVE_NODES_ONLY : NativeTextResult.Coverage.COMPLETE_TREE,
                1, 1, 1, start + 5, tokens.length == 0 ? -1 : start + 53);
        return new NativeTextFrame(SceneEngine.Source.nativeText(1, sequence, 2, 1000, 2400, 7,
                start, start + 70, start + 80, ProtectionScope.normal(9)), 9, read, 1000);
    }
    private static long[] admitPair(NativeNodeIdentityCache<Node> cache) {
        cache.begin("scope", 0, 1); long a = cache.resolve(node(1)), b = cache.resolve(node(2)), read = cache.readIdentity(); cache.end();
        assertTrue(cache.admit(read, frame(1, 0, false, a, b), Arrays.asList(a, b))); return new long[]{a, b};
    }

    @Test public void liveAcceptedAddressCannotBeEvictedByANewReadOrCapacityPressure() {
        Handles h = new Handles(); NativeNodeIdentityCache<Node> cache = new NativeNodeIdentityCache<>(2, h);
        long[] tokens = admitPair(cache); cache.begin("scope", 100, 1);
        refused(() -> cache.resolve(node(3))); assertEquals(2, h.copies.size());
        assertEquals(tokens[0], cache.resolve(node(1))); assertEquals(tokens[1], cache.resolve(node(2)));
        cache.end(); cache.close(); assertTrue(cache.drained());
    }

    @Test public void cohortPinsTwoDistinctBorrowedAddressesWithoutLendingNewEvidence() {
        Handles h = new Handles(); NativeNodeIdentityCache<Node> cache = new NativeNodeIdentityCache<>(2, h);
        long[] tokens = admitPair(cache); cache.begin("scope", 1100, 1);
        List<NativeNodeIdentityCache.Address<Node>> selected = cache.nextAddresses(2);
        assertEquals(2, selected.size()); assertEquals(tokens[0], selected.get(0).token); assertEquals(tokens[1], selected.get(1).token);
        assertSame(h.copies.get(0), selected.get(0).node); assertSame(h.copies.get(1), selected.get(1).node);
        for (NativeNodeIdentityCache.Address<Node> address : selected) {
            assertEquals(1060, address.basis.originalValidUntilMillis); assertEquals(1, address.basis.source.sequence);
            assertEquals(address.token, cache.known(node(address.node.exactIdentity))); assertFalse(address.node.released);
        }
        assertThrows(UnsupportedOperationException.class, () -> selected.clear());
        refused(() -> cache.resolve(node(3))); cache.end(); cache.close(); assertTrue(cache.drained());
    }

    @Test public void failedCohortMemberRetiresOnlyItsOwnRefreshAuthority() {
        Handles h = new Handles(); NativeNodeIdentityCache<Node> cache = new NativeNodeIdentityCache<>(2, h);
        long[] tokens = admitPair(cache); cache.begin("scope", 100, 1); cache.nextAddresses(2);
        cache.retireAddress(tokens[1]); cache.end(); cache.begin("scope", 200, 1);
        assertEquals(tokens[0], cache.nextAddress().token); assertEquals(1060, cache.nextAddress().basis.originalValidUntilMillis);
        refused(() -> cache.nextAddresses(2)); assertEquals(tokens[0], cache.nextAddress().token);
        cache.end(); cache.close(); assertTrue(cache.drained());
    }

    @Test public void insufficientOrHardFencedInventoryCannotWrapOneAddressIntoAPair() {
        Handles h = new Handles(); NativeNodeIdentityCache<Node> cache = new NativeNodeIdentityCache<>(2, h);
        long[] tokens = admitPair(cache); cache.begin("scope", 100, 1); cache.retireAddress(tokens[1]);
        refused(() -> cache.nextAddresses(2)); assertEquals(tokens[0], cache.nextAddress().token);
        for (int invalid : new int[]{0, -1, 3, 64}) assertThrows(IllegalArgumentException.class, () -> cache.nextAddresses(invalid));
        cache.end(); cache.begin("scope", 200, 2); refused(() -> cache.nextAddresses(2));
        cache.end(); cache.close(); assertTrue(cache.drained());
    }

    @Test public void acceptedPartialUpdatesOnlySelectedAddressAndDoesNotRenewTheUnseenSibling() {
        Handles h = new Handles(); NativeNodeIdentityCache<Node> cache = new NativeNodeIdentityCache<>(2, h);
        long[] tokens = admitPair(cache); cache.begin("scope", 300, 1); cache.resolve(node(1)); long read = cache.readIdentity(); cache.end();
        assertTrue(cache.admit(read, frame(2, 300, true, tokens[0]), Collections.singletonList(tokens[0])));
        cache.begin("scope", 400, 1); NativeNodeIdentityCache.Address<Node> first = cache.nextAddress(), second = cache.nextAddress();
        assertEquals(tokens[0], first.token); assertEquals(1360, first.basis.originalValidUntilMillis);
        assertEquals(tokens[1], second.token); assertEquals(1060, second.basis.originalValidUntilMillis);
        assertEquals(1, second.basis.source.sequence); cache.end(); cache.close();
    }

    @Test public void lruTouchesAndFailedAttemptsDoNotResetRoundRobinOrExtendOriginalDeadlines() {
        Handles h = new Handles(); NativeNodeIdentityCache<Node> cache = new NativeNodeIdentityCache<>(2, h);
        long[] tokens = admitPair(cache);
        for (int i = 0; i < 3; i++) {
            cache.begin("scope", 100 + i, 1); cache.resolve(node(2)); cache.resolve(node(1));
            NativeNodeIdentityCache.Address<Node> selected = cache.nextAddress();
            assertEquals(tokens[i % 2], selected.token); assertEquals(1060, selected.basis.originalValidUntilMillis);
            cache.end();
        }
        cache.begin("scope", 1060, 1); refused(() -> cache.resolve(node(3))); cache.end();
        cache.begin("scope", 1061, 1); assertTrue(cache.resolve(node(3)) > tokens[1]); cache.end(); cache.close();
    }

    @Test public void completeSettlementRetiresUnseenAddressButDoesNotReleaseClonesAfterSemanticTerminal() {
        Handles h = new Handles(); NativeNodeIdentityCache<Node> cache = new NativeNodeIdentityCache<>(2, h);
        long[] tokens = admitPair(cache); cache.begin("scope", 300, 1); cache.resolve(node(1)); long read = cache.readIdentity(); cache.end();
        assertTrue(cache.admit(read, frame(2, 300, false, tokens[0]), Collections.singletonList(tokens[0])));
        for (Node copy : h.copies) assertEquals(0, copy.attempts);
        cache.begin("scope", 400, 1); assertEquals(tokens[0], cache.nextAddress().token); assertEquals(tokens[0], cache.nextAddress().token);
        assertTrue(cache.resolve(node(3)) > tokens[1]); assertTrue(h.copies.get(1).released); cache.end(); cache.close();
    }

    @Test public void foreignLateDuplicateOrEmptyPartialCannotActivateOrOverwriteAcceptedAddresses() {
        Handles h = new Handles(); NativeNodeIdentityCache<Node> cache = new NativeNodeIdentityCache<>(2, h);
        long[] tokens = admitPair(cache); cache.begin("scope", 300, 1); cache.resolve(node(1)); long read = cache.readIdentity(); cache.end();
        assertFalse(cache.admit(read - 1, frame(2, 300, true, tokens[0]), Collections.singletonList(tokens[0])));
        assertFalse(cache.admit(read, frame(2, 300, true, tokens[0]), Collections.singletonList(999L)));
        assertFalse(cache.admit(read, frame(2, 300, true, tokens[0], tokens[1]), Arrays.asList(tokens[0], tokens[0])));
        assertFalse(cache.admit(read, frame(2, 300, true), Collections.emptyList()));
        cache.begin("scope", 400, 1); assertEquals(1060, cache.nextAddress().basis.originalValidUntilMillis); cache.end(); cache.close();
    }

    @Test public void hardFenceAndScopeChangeRetireAddressesWithoutLendingAnOldReadDecision() {
        Handles h = new Handles(); NativeNodeIdentityCache<Node> cache = new NativeNodeIdentityCache<>(2, h);
        long[] tokens = admitPair(cache); cache.begin("scope", 100, 2); assertNull(cache.nextAddress());
        long read = cache.readIdentity(); cache.resolve(node(1)); cache.end();
        assertFalse(cache.admit(read, frame(2, 100, true, tokens[0]), Collections.singletonList(tokens[0])));
        cache.begin("next", 200, 1); assertNull(cache.nextAddress()); assertTrue(h.copies.get(0).released);
        assertFalse(cache.admit(read, frame(2, 100, true, tokens[0]), Collections.singletonList(tokens[0])));
        cache.end(); cache.close();
    }

    @Test public void expiredAddressIsEvictableButAnActiveOperationStillPinsIt() {
        Handles h = new Handles(); NativeNodeIdentityCache<Node> cache = new NativeNodeIdentityCache<>(2, h);
        long[] tokens = admitPair(cache); cache.begin("scope", 1100, 1);
        NativeNodeIdentityCache.Address<Node> address = cache.nextAddress(); assertEquals(tokens[0], address.token);
        assertEquals(1060, address.basis.originalValidUntilMillis); assertEquals(tokens[0], cache.known(node(1)));
        assertTrue(cache.resolve(node(3)) > tokens[1]); assertFalse(h.copies.get(0).released); assertTrue(h.copies.get(1).released);
        cache.end(); cache.close();
    }

    @Test public void uncertainRetirementOfAnAcceptedAddressCannotClaimDrain() {
        Handles h = new Handles(); NativeNodeIdentityCache<Node> cache = new NativeNodeIdentityCache<>(2, h);
        admitPair(cache); h.failReleaseIdentity = 1; refused(cache::close); assertFalse(cache.drained());
        assertEquals(1, h.copies.get(0).attempts); assertTrue(h.copies.get(1).released); refused(cache::close);
        assertEquals(1, h.copies.get(0).attempts);
    }

    @Test public void failedRefreshOrHardFenceDisablesAddressButKeepsIdentityPinnedToOriginalExpiry() {
        for (boolean hard : new boolean[]{false, true}) {
            Handles h = new Handles(); NativeNodeIdentityCache<Node> cache = new NativeNodeIdentityCache<>(2, h);
            long[] tokens = admitPair(cache); cache.begin("scope", 100, hard ? 2 : 1);
            if (!hard) { cache.retireAddress(tokens[0]); cache.retireAddress(tokens[1]); }
            assertNull(cache.nextAddress()); refused(() -> cache.resolve(node(3)));
            assertEquals(tokens[0], cache.resolve(node(1))); assertFalse(h.copies.get(0).released); cache.end();
            cache.begin("scope", 1060, hard ? 2 : 1); refused(() -> cache.resolve(node(3))); cache.end();
            cache.begin("scope", 1061, hard ? 2 : 1); assertTrue(cache.resolve(node(3)) > tokens[1]);
            cache.end(); cache.close(); assertTrue(cache.drained());
        }
    }

    @Test public void continuingExactNodeKeepsItsTokenAcrossChangedGeometry() {
        Handles h = new Handles(); NativeNodeIdentityCache<Node> cache = new NativeNodeIdentityCache<>(2, h);
        Node first = node(7); cache.begin("scope"); long token = cache.resolve(first); cache.end();
        Node refreshed = node(7); refreshed.geometry = 100; cache.begin("scope");
        assertEquals(token, cache.resolve(refreshed)); assertEquals(token, cache.known(refreshed));
        assertEquals(1, h.copies.size()); cache.end(); cache.close(); assertTrue(cache.drained());
    }
    @Test public void identicalCaptionsAndBoundsAreNotIdentityInputs() {
        Handles h = new Handles(); NativeNodeIdentityCache<Node> cache = new NativeNodeIdentityCache<>(2, h);
        cache.begin("scope"); assertNotEquals(cache.resolve(node(1)), cache.resolve(node(2)));
        cache.end(); cache.close(); assertTrue(cache.drained());
    }
    @Test public void newViewWithSamePublicMetadataDoesNotInheritAnOldToken() {
        Handles h = new Handles(); NativeNodeIdentityCache<Node> cache = new NativeNodeIdentityCache<>(2, h);
        cache.begin("scope"); long first = cache.resolve(node(1)); cache.end();
        cache.begin("scope"); assertNotEquals(first, cache.resolve(node(2))); cache.end(); cache.close();
    }
    @Test public void scopeRetirementClosesHandlesAndNeverReusesTokens() {
        Handles h = new Handles(); NativeNodeIdentityCache<Node> cache = new NativeNodeIdentityCache<>(2, h);
        cache.begin("old"); long first = cache.resolve(node(1)); cache.end();
        cache.begin("new"); assertTrue(h.copies.get(0).released); assertEquals(0, cache.retained());
        assertTrue(cache.resolve(node(1)) > first); cache.end(); cache.close(); assertTrue(cache.drained());
    }
    @Test public void evictionCannotRemoveAnIdentityPinnedByThisRead() {
        Handles h = new Handles(); NativeNodeIdentityCache<Node> cache = new NativeNodeIdentityCache<>(2, h);
        cache.begin("scope"); long a = cache.resolve(node(1)); long b = cache.resolve(node(2)); cache.end();
        cache.begin("scope"); assertEquals(a, cache.resolve(node(1))); assertTrue(cache.resolve(node(3)) > b);
        assertEquals(a, cache.known(node(1))); assertEquals(0, cache.known(node(2)));
        assertFalse(h.copies.get(0).released); assertTrue(h.copies.get(1).released);
        cache.end(); cache.close(); assertTrue(cache.drained());
    }
    @Test public void readCapacityRefusalDoesNotEvictItsEarlierProbes() {
        Handles h = new Handles(); NativeNodeIdentityCache<Node> cache = new NativeNodeIdentityCache<>(2, h);
        cache.begin("scope"); long a = cache.resolve(node(1)), b = cache.resolve(node(2));
        refused(() -> cache.resolve(node(3))); assertEquals(2, cache.retained()); assertEquals(2, h.copies.size());
        assertEquals(a, cache.known(node(1))); assertEquals(b, cache.known(node(2)));
        cache.end(); cache.close(); assertTrue(cache.drained());
    }
    @Test public void confirmationNeverMintsANewIdentityOrBorrowsAPreviousReadsPin() {
        Handles h = new Handles(); NativeNodeIdentityCache<Node> cache = new NativeNodeIdentityCache<>(2, h);
        cache.begin("scope"); long a = cache.resolve(node(1)); cache.end(); cache.begin("scope");
        assertEquals(0, cache.known(node(1))); assertEquals(0, cache.known(node(2))); assertEquals(1, h.copies.size());
        assertEquals(a, cache.resolve(node(1))); assertEquals(a, cache.known(node(1)));
        cache.end(); cache.close();
    }
    @Test public void activeReadBlocksRetirementWithoutFreeingAnyHandle() {
        Handles h = new Handles(); NativeNodeIdentityCache<Node> cache = new NativeNodeIdentityCache<>(2, h);
        cache.begin("scope"); cache.resolve(node(1)); refused(cache::close); assertFalse(h.copies.get(0).released);
        refused(() -> cache.begin("different")); assertEquals(1, cache.retained());
        cache.end(); cache.close(); cache.close(); assertTrue(cache.drained());
    }
    @Test public void failedEvictionRetainsUncertaintyAndOtherHandlesStillClose() {
        Handles h = new Handles(); NativeNodeIdentityCache<Node> cache = new NativeNodeIdentityCache<>(2, h);
        cache.begin("scope"); cache.resolve(node(1)); cache.resolve(node(2)); cache.end();
        h.failReleaseIdentity = 1; cache.begin("scope"); cache.resolve(node(2));
        refused(() -> cache.resolve(node(3))); assertTrue(cache.resourceFailure()); assertEquals(2, cache.retained());
        cache.end(); refused(cache::close); assertFalse(cache.drained()); assertEquals(1, cache.retained());
        assertEquals(1, h.copies.get(0).attempts); assertTrue(h.copies.get(1).released);
        refused(cache::close); assertEquals(1, h.copies.get(0).attempts);
    }
    @Test public void failedCloseCannotSuppressOtherDisposalsOrClaimDrain() {
        Handles h = new Handles(); NativeNodeIdentityCache<Node> cache = new NativeNodeIdentityCache<>(2, h);
        cache.begin("scope"); cache.resolve(node(1)); cache.resolve(node(2)); cache.end(); h.failReleaseIdentity = 1;
        refused(cache::close); assertTrue(cache.resourceFailure()); assertFalse(cache.drained());
        assertTrue(h.copies.get(1).released); assertEquals(1, cache.retained());
        refused(() -> cache.begin("new")); refused(cache::close); assertEquals(1, h.copies.get(0).attempts);
    }
    @Test public void failedCopyHasNoTransferredHandleAndDoesNotInventAToken() {
        Handles h = new Handles(); NativeNodeIdentityCache<Node> cache = new NativeNodeIdentityCache<>(2, h);
        cache.begin("scope"); h.copyFails = true; refused(() -> cache.resolve(node(1)));
        assertEquals(0, cache.retained()); assertFalse(cache.resourceFailure()); h.copyFails = false;
        assertEquals(1, cache.resolve(node(1))); cache.end(); cache.close(); assertTrue(cache.drained());
    }
    @Test public void aliasedCopyNeverReleasesBorrowedInput() {
        Handles h = new Handles(); NativeNodeIdentityCache<Node> cache = new NativeNodeIdentityCache<>(2, h);
        cache.begin("scope"); h.alias = true; Node input = node(1); refused(() -> cache.resolve(input));
        assertEquals(0, input.attempts); assertEquals(0, cache.retained()); cache.end(); cache.close();
    }
    @Test public void mismatchedCloneIsDisposedAndItsTokenNeverReused() {
        Handles h = new Handles(); NativeNodeIdentityCache<Node> cache = new NativeNodeIdentityCache<>(2, h);
        cache.begin("scope"); h.wrongIdentity = true; refused(() -> cache.resolve(node(1)));
        assertEquals(0, cache.retained()); assertTrue(h.copies.get(0).released); h.wrongIdentity = false;
        assertEquals(2, cache.resolve(node(1))); cache.end(); cache.close();
    }
    @Test public void closedCacheRejectsNewReads() {
        Handles h = new Handles(); NativeNodeIdentityCache<Node> cache = new NativeNodeIdentityCache<>(2, h);
        cache.close(); assertTrue(cache.drained()); refused(() -> cache.begin("scope")); refused(() -> cache.resolve(node(1)));
    }
}
