package com.subhub.app.update;

import static org.junit.Assert.*;

import java.time.ZoneId;
import java.util.Locale;
import org.junit.Test;

public final class ReleaseTimestampFormatterTest {
    @Test public void previewUsesLocalDateTimeAndExplicitOffset() {
        assertEquals("18 Oct 2026, 10:31:42 UTC+02:00", format("2026-10-18T08:31:42Z", "Europe/Berlin"));
        assertEquals("18 Oct 2026, 08:31:42 UTC+00:00", format("2026-10-18T08:31:42Z", "UTC"));
    }

    @Test public void localeAndTimezoneComeFromTheCaller() {
        assertEquals("18.10.2026, 10:31:42 UTC+02:00", ReleaseTimestampFormatter.preview(
                "2026-10-18T08:31:42Z", Locale.GERMANY, ZoneId.of("Europe/Berlin")));
        assertEquals("17 Oct 2026, 17:30:00 UTC-07:00", format("2026-10-18T00:30:00Z", "America/Los_Angeles"));
    }

    @Test public void repeatedDstHourRemainsUnambiguous() {
        assertEquals("25 Oct 2026, 02:30:00 UTC+02:00", format("2026-10-25T00:30:00Z", "Europe/Berlin"));
        assertEquals("25 Oct 2026, 02:30:00 UTC+01:00", format("2026-10-25T01:30:00Z", "Europe/Berlin"));
    }

    @Test public void sourceOffsetsDescribeTheSameInstant() {
        assertEquals(format("2026-10-18T08:31:42Z", "UTC"),
                format("2026-10-18T14:01:42+05:30", "UTC"));
    }

    @Test public void legacyDatesNeverAcquireAnInventedTime() {
        assertEquals("2026-10-08", format("2026-10-08", "Europe/Berlin"));
        for (String invalid : new String[] {null, "", "invalid", "2026-02-30", "2026-10-18T08:31:42",
                "2026-10-18T08:31:42Zgarbage", "2026-02-30T08:31:42Z"}) {
            assertEquals("Invalid source: " + invalid, "", format(invalid, "UTC"));
        }
    }

    private static String format(String value, String zone) {
        return ReleaseTimestampFormatter.preview(value, Locale.UK, ZoneId.of(zone));
    }
}
