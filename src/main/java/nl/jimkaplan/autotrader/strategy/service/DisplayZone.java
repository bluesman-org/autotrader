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
public final class DisplayZone {

    private final ZoneId zone;
    private final DateTimeFormatter minuteFormat;
    private final DateTimeFormatter minuteYearFormat;
    private final DateTimeFormatter secondFormat;
    private final DateTimeFormatter clockFormat;
    private final DateTimeFormatter clockSecondFormat;
    private final DateTimeFormatter dayFormat;
    private final DateTimeFormatter dateFormat;

    public DisplayZone(String timeZone) {
        this(timeZone == null || timeZone.isBlank() ? ZoneId.systemDefault() : ZoneId.of(timeZone));
    }

    public DisplayZone(ZoneId zone) {
        this.zone = zone;
        this.minuteFormat = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.ENGLISH).withZone(zone);
        this.minuteYearFormat = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm", Locale.ENGLISH).withZone(zone);
        this.secondFormat = DateTimeFormatter.ofPattern("d MMM HH:mm:ss", Locale.ENGLISH).withZone(zone);
        this.clockFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH).withZone(zone);
        this.clockSecondFormat = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ENGLISH).withZone(zone);
        this.dayFormat = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH).withZone(zone);
        this.dateFormat = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH).withZone(zone);
    }

    /**
     * @return The zone times are shown in, so date arithmetic uses the same one the labels do
     */
    public ZoneId zone() {
        return zone;
    }

    /**
     * @return The label of the zone times are shown in, e.g. {@code CEST} or {@code UTC}
     */
    public String label() {
        return zone.getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
    }

    /**
     * @return The zone's IANA id, e.g. {@code Europe/Amsterdam} — the pages pass it to their
     *         refresh scripts so their stamps render in the same zone and format
     */
    public String id() {
        return zone.getId();
    }

    /**
     * @param when The moment
     * @return The date with its year and the minute, e.g. {@code 14 Oct 2026 22:31} — history
     *         records can span years, so they always show which one
     */
    public String minuteYear(Instant when) {
        return minuteYearFormat.format(when);
    }

    /**
     * @param when The moment
     * @return The date and minute, e.g. {@code 14 Oct 22:31}
     */
    public String minute(Instant when) {
        return minuteFormat.format(when);
    }

    /**
     * @param when The moment
     * @return The date with its year, e.g. {@code 24 Sep 2021} — trade facts read across years
     */
    public String date(Instant when) {
        return dateFormat.format(when);
    }

    /**
     * @param when The moment
     * @return The date and second, e.g. {@code 14 Oct 22:31:04}
     */
    public String second(Instant when) {
        return secondFormat.format(when);
    }

    /**
     * @param when The moment
     * @return The time of day, e.g. {@code 22:31}
     */
    public String clock(Instant when) {
        return clockFormat.format(when);
    }

    /**
     * @param when The moment
     * @return The time of day with seconds, e.g. {@code 22:31:04} — fill lists stay compact
     */
    public String clockSecond(Instant when) {
        return clockSecondFormat.format(when);
    }

    /**
     * @param day The day
     * @return The day within the year, e.g. {@code 14 Oct}
     */
    public String day(LocalDate day) {
        return dayFormat.format(day);
    }

    /**
     * @param day The day
     * @return The day with its year, e.g. {@code 14 Oct 2026} — a daily table can span years
     */
    public String dayYear(LocalDate day) {
        return dateFormat.format(day);
    }

    /**
     * @param when The moment
     * @return The clock time when the moment is today, the date and minute otherwise — recent rows
     *         stay compact while older ones keep their date
     */
    public String clockText(Instant when) {
        return when.atZone(zone).toLocalDate().equals(LocalDate.now(zone)) ? clock(when) : minute(when);
    }
}
