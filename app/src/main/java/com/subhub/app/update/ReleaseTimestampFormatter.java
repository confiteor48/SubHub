package com.subhub.app.update;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;

/** Formats the existing release publication instant, never an inferred compiler timestamp. */
final class ReleaseTimestampFormatter {
    private ReleaseTimestampFormatter() { }

    static String preview(String publishedAt, Locale locale, ZoneId zone) {
        if (publishedAt == null || publishedAt.isEmpty()) return "";
        try {
            Instant instant = OffsetDateTime.parse(publishedAt).toInstant();
            String local = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.MEDIUM)
                    .withLocale(locale).withZone(zone).format(instant);
            String offset = DateTimeFormatter.ofPattern("'UTC'xxx", Locale.ROOT)
                    .withZone(zone).format(instant);
            return local + " " + offset;
        } catch (DateTimeException invalidTimestamp) {
            // Bundled historical entries may genuinely contain only a date. Do not invent a time.
            if (publishedAt.length() == 10) {
                try { return LocalDate.parse(publishedAt).toString(); }
                catch (DateTimeException invalidDate) { return ""; }
            }
            return "";
        }
    }
}
