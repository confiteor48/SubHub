package com.subhub.app.penance;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Presentation-only runs; the original ledger and settlement entries remain individual. */
final class WalletHistoryGroups {
    private WalletHistoryGroups() {}

    static List<Group> group(List<PenanceEvent> events, long now, ZoneId zone) {
        List<Group> groups = new ArrayList<>();
        for (PenanceEvent event : events) {
            LocalDate day = Instant.ofEpochMilli(event.getCreatedAtMillis()).atZone(zone).toLocalDate();
            boolean correction = event.isInMercy(now);
            Group previous = groups.isEmpty() ? null : groups.get(groups.size() - 1);
            long count = Math.max(1, event.getStrikeCount());
            long cents = Math.max(0, event.getAmountCents());
            if (previous != null && previous.matches(event, correction, day)) {
                groups.set(groups.size() - 1, new Group(previous.latest, previous.count + count,
                        previous.amountCents + cents, correction, day));
            } else {
                groups.add(new Group(event, count, cents, correction, day));
            }
        }
        return Collections.unmodifiableList(groups);
    }

    static final class Group {
        final PenanceEvent latest;
        final long count;
        final long amountCents;
        private final boolean correction;
        private final LocalDate day;

        private Group(PenanceEvent latest, long count, long amountCents,
                boolean correction, LocalDate day) {
            this.latest = latest;
            this.count = count;
            this.amountCents = amountCents;
            this.correction = correction;
            this.day = day;
        }

        private boolean matches(PenanceEvent event, boolean nextCorrection, LocalDate nextDay) {
            return latest.getInfraction() == event.getInfraction()
                    && latest.getCurrency().equals(event.getCurrency())
                    && latest.getStatus() == event.getStatus()
                    && correction == nextCorrection && day.equals(nextDay);
        }
    }
}
