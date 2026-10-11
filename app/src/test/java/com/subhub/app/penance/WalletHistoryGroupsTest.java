package com.subhub.app.penance;

import static org.junit.Assert.*;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.Test;

public final class WalletHistoryGroupsTest {
    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final long NOW = Instant.parse("2026-10-09T20:00:00Z").toEpochMilli();

    @Test public void combinesOnlyConsecutiveRunsAndKeepsTheNewestTimeAndLedger() {
        PenanceEvent first = event("first", NOW, 0, 100, 1, PenanceInfraction.NEW_DETECTION,
                PenanceEvent.Status.OPEN, "EUR");
        List<PenanceEvent> ledger = List.of(first,
                event("second", NOW - 1000, 0, 200, 2, PenanceInfraction.NEW_DETECTION, PenanceEvent.Status.OPEN, "EUR"),
                event("app", NOW - 2000, 0, 50, 1, PenanceInfraction.WATCHED_APP_OPEN, PenanceEvent.Status.OPEN, "EUR"),
                event("older", NOW - 3000, 0, 100, 1, PenanceInfraction.NEW_DETECTION, PenanceEvent.Status.OPEN, "EUR"));
        List<WalletHistoryGroups.Group> rows = WalletHistoryGroups.group(ledger, NOW, UTC);
        assertEquals(3, rows.size());
        assertSame(first, rows.get(0).latest);
        assertEquals(3, rows.get(0).count);
        assertEquals(300, rows.get(0).amountCents);
        assertEquals(1, rows.get(2).count);
        assertEquals(4, ledger.size());
        assertEquals(100, first.getAmountCents());
    }

    @Test public void currenciesAndPaymentStatesStaySeparate() {
        List<WalletHistoryGroups.Group> rows = WalletHistoryGroups.group(List.of(
                event("eur", NOW, 0, 100, 1, PenanceInfraction.NEW_DETECTION, PenanceEvent.Status.OPEN, "EUR"),
                event("usd", NOW, 0, 100, 1, PenanceInfraction.NEW_DETECTION, PenanceEvent.Status.OPEN, "USD"),
                event("pending", NOW, 0, 100, 1, PenanceInfraction.NEW_DETECTION, PenanceEvent.Status.CHECKOUT, "USD"),
                event("paid", NOW, 0, 100, 1, PenanceInfraction.NEW_DETECTION, PenanceEvent.Status.PAID, "USD"),
                event("forgiven", NOW, 0, 100, 1, PenanceInfraction.NEW_DETECTION, PenanceEvent.Status.FORGIVEN, "USD")), NOW, UTC);
        assertEquals(5, rows.size());
    }

    @Test public void correctionExpiryChangesTheRunsWithoutMixingDueAndCorrectableAmounts() {
        List<PenanceEvent> ledger = List.of(
                event("short", NOW, NOW + 100, 100, 1, PenanceInfraction.NEW_DETECTION, PenanceEvent.Status.OPEN, "EUR"),
                event("long", NOW - 1000, NOW + 200, 100, 1, PenanceInfraction.NEW_DETECTION, PenanceEvent.Status.OPEN, "EUR"));
        assertEquals(1, WalletHistoryGroups.group(ledger, NOW, UTC).size());
        assertEquals(2, WalletHistoryGroups.group(ledger, NOW + 100, UTC).size());
        assertEquals(1, WalletHistoryGroups.group(ledger, NOW + 200, UTC).size());
    }

    @Test public void calendarDayBoundariesUseTheDisplayTimeZone() {
        List<PenanceEvent> ledger = List.of(
                event("after", Instant.parse("2026-10-10T00:01:00Z").toEpochMilli(), 0, 100, 1,
                        PenanceInfraction.NEW_DETECTION, PenanceEvent.Status.OPEN, "EUR"),
                event("before", Instant.parse("2026-10-09T23:59:00Z").toEpochMilli(), 0, 100, 1,
                        PenanceInfraction.NEW_DETECTION, PenanceEvent.Status.OPEN, "EUR"));
        assertEquals(2, WalletHistoryGroups.group(ledger, NOW, UTC).size());
        assertEquals(1, WalletHistoryGroups.group(ledger, NOW, ZoneId.of("Europe/Berlin")).size());
    }

    @Test public void combinedAmountsAndCountsDoNotOverflowAnInteger() {
        PenanceEvent first = event("max", NOW, 0, Integer.MAX_VALUE, Integer.MAX_VALUE,
                PenanceInfraction.NEW_DETECTION, PenanceEvent.Status.OPEN, "EUR");
        WalletHistoryGroups.Group row = WalletHistoryGroups.group(List.of(first, first), NOW, UTC).get(0);
        assertEquals(2L * Integer.MAX_VALUE, row.count);
        assertEquals(2L * Integer.MAX_VALUE, row.amountCents);
        assertEquals("€42949672.94", WalletCurrency.format("EUR", row.amountCents));
        assertTrue(WalletHistoryGroups.group(List.of(), NOW, UTC).isEmpty());
    }

    private static PenanceEvent event(String id, long created, long correctionEnds, int cents,
            int count, PenanceInfraction kind, PenanceEvent.Status status, String currency) {
        return new PenanceEvent(id, created, correctionEnds, cents, count, kind, status, "", currency);
    }
}
