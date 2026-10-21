package nl.jimkaplan.autotrader.strategy.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The display zone and its formats: one place shows every moment the pages render, so the formats
 * are pinned here.
 */
class DisplayZoneTest {

    private final DisplayZone display = new DisplayZone(ZoneId.of("Europe/Amsterdam"));

    @Test
    void formatsInTheZonesOwnTerms() {
        Instant when = Instant.parse("2026-10-14T20:31:04Z");

        assertEquals("Europe/Amsterdam", display.id());
        assertTrue(display.label().matches("CES?T"));
        assertEquals("14 Oct 22:31", display.minute(when));
        assertEquals("14 Oct 2026 22:31", display.minuteYear(when));
        assertEquals("14 Oct 2026", display.date(when));
        assertEquals("14 Oct 22:31:04", display.second(when));
        assertEquals("14 Oct 2026 22:31:04", display.secondYear(when));
        assertEquals("22:31", display.clock(when));
        assertEquals("22:31:04", display.clockSecond(when));
        assertEquals("14 Oct", display.day(LocalDate.of(2026, 10, 14)));
        assertEquals("14 Oct 2026", display.dayYear(LocalDate.of(2026, 10, 14)));
        assertEquals(ZoneId.of("Europe/Amsterdam"), display.zone());
    }

    @Test
    void clockTextKeepsTheDateOnlyForOtherDays() {
        assertTrue(display.clockText(Instant.now()).matches("\\d\\d:\\d\\d"));
        assertEquals("2 Jan 11:00", display.clockText(Instant.parse("2020-01-02T10:00:00Z")));
    }

    @Test
    void aBlankTimeZoneFallsBackToTheMachines() {
        assertEquals(ZoneId.systemDefault().getId(), new DisplayZone(" ").id());
        assertEquals("UTC", new DisplayZone("UTC").id());
    }
}
