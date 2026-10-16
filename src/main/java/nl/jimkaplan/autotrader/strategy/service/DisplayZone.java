package nl.jimkaplan.autotrader.strategy.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Locale;

/**
 * The display zone and the timestamp formats the UI pages share — the live page and both history
 * tabs show moments the same way. The zone comes from {@code autotrader.ui.time-zone}, the machine's
 * zone by default (the container runs in the user's local time).
 */
final class DisplayZone {

    private final ZoneId zone;
    private final DateTimeFormatter minuteFormat;
    private final DateTimeFormatter secondFormat;
    private final DateTimeFormatter clockFormat;
    private final DateTimeFormatter clockSecondFormat;
    private final DateTimeFormatter dayFormat;

    DisplayZone(String timeZone) {
        this(timeZone == null || timeZone.isBlank() ? ZoneId.systemDefault() : ZoneId.of(timeZone));
    }

    DisplayZone(ZoneId zone) {
        this.zone = zone;
        this.minuteFormat = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.ENGLISH).withZone(zone);
        this.secondFormat = DateTimeFormatter.ofPattern("d MMM HH:mm:ss", Locale.ENGLISH).withZone(zone);
        this.clockFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH).withZone(zone);
        this.clockSecondFormat = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ENGLISH).withZone(zone);
        this.dayFormat = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH).withZone(zone);
    }

    /**
     * @return The zone times are shown in, so date arithmetic uses the same one the labels do
     */
    ZoneId zone() {
        return zone;
    }

    /**
     * @return The label of the zone times are shown in, e.g. {@code CEST} or {@code UTC}
     */
    String label() {
        return zone.getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
    }

    /**
     * @return The zone's IANA id, e.g. {@code Europe/Amsterdam} — the pages pass it to their
     *         refresh scripts so their stamps render in the same zone and format
     */
    String id() {
        return zone.getId();
    }

    /**
     * @param when The moment
     * @return The date and minute, e.g. {@code 14 Oct 22:31}
     */
    String minute(Instant when) {
        return minuteFormat.format(when);
    }

    /**
     * @param when The moment
     * @return The date and second, e.g. {@code 14 Oct 22:31:04}
     */
    String second(Instant when) {
        return secondFormat.format(when);
    }

    /**
     * @param when The moment
     * @return The time of day, e.g. {@code 22:31}
     */
    String clock(Instant when) {
        return clockFormat.format(when);
    }

    /**
     * @param when The moment
     * @return The time of day with seconds, e.g. {@code 22:31:04} — fill lists stay compact
     */
    String clockSecond(Instant when) {
        return clockSecondFormat.format(when);
    }

    /**
     * @param day The day
     * @return The day within the year, e.g. {@code 14 Oct}
     */
    String day(LocalDate day) {
        return dayFormat.format(day);
    }

    /**
     * @param when The moment
     * @return The clock time when the moment is today, the date and minute otherwise — recent rows
     *         stay compact while older ones keep their date
     */
    String clockText(Instant when) {
        return when.atZone(zone).toLocalDate().equals(LocalDate.now(zone)) ? clock(when) : minute(when);
    }
}
