package nl.jimkaplan.autotrader.strategy.service;

import nl.jimkaplan.autotrader.strategy.controller.TradeHistoryView.Option;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The time presets both history tabs filter by: rolling windows, calendar periods and a custom
 * range. Presets and the custom range resolve in the display zone; anything unparseable falls back
 * to the default preset.
 */
final class HistoryPeriods {

    static final String DEFAULT_PRESET = "7d";

    static final List<Option> OPTIONS = List.of(
            new Option("1h", "Last hour"),
            new Option("2h", "Last 2 hours"),
            new Option("4h", "Last 4 hours"),
            new Option("8h", "Last 8 hours"),
            new Option("24h", "Last 24 hours"),
            new Option("3d", "Last 3 days"),
            new Option("7d", "Last 7 days"),
            new Option("14d", "Last 14 days"),
            new Option("30d", "Last 30 days"),
            new Option("last-week", "Last week (Mon–Sun)"),
            new Option("last-two-weeks", "Last two weeks"),
            new Option("last-month", "Last month"),
            new Option("ytd", "Year to date"),
            new Option("last-year", "Last year"));

    private static final Map<String, String> LABELS = OPTIONS.stream()
            .collect(Collectors.toMap(Option::value, Option::label));

    private final DisplayZone display;
    private final Clock clock;

    HistoryPeriods(DisplayZone display, Clock clock) {
        this.display = display;
        this.clock = clock;
    }

    /**
     * The bounds and label of the selected period.
     *
     * @param fromMillis The start of the period, inclusive
     * @param toMillis   The end of the period, inclusive
     * @param label      The period as shown, e.g. {@code Last 7 days} or an explicit range
     */
    record Range(long fromMillis, long toMillis, String label) {
    }

    /** The start time the custom range shows when its field is empty. */
    static final String SHOWN_FROM_TIME = "00:00";

    /** The end time the custom range shows when its field is empty. */
    static final String SHOWN_TO_TIME = "23:59";

    /**
     * @param period The requested preset
     * @return Whether the request names a preset the app knows
     */
    static boolean isPreset(String period) {
        return LABELS.containsKey(period == null ? "" : period.trim());
    }

    /**
     * The one rule for which range the request means: a chosen preset wins over dates that linger in
     * the form or the address bar, and the custom fields apply only while the Time select says
     * "Custom range". Every reader — the resolver, both filter bars and both link builders — asks
     * this.
     *
     * @param period   The requested preset
     * @param fromDate The custom range's start date
     * @param toDate   The custom range's end date
     * @return Whether the custom range is the one in effect
     */
    static boolean usesCustomRange(String period, String fromDate, String toDate) {
        return !isPreset(period) && hasCustomRange(fromDate, toDate);
    }

    /**
     * @return Whether the request carries a custom range instead of a preset
     */
    static boolean hasCustomRange(String fromDate, String toDate) {
        return notBlank(fromDate) || notBlank(toDate);
    }

    /**
     * @param value The submitted start time, possibly empty
     * @return The submitted time, or the whole day's default the form shows
     */
    static String shownFromTime(String value) {
        return notBlank(value) ? value : SHOWN_FROM_TIME;
    }

    /**
     * @param value The submitted end time, possibly empty
     * @return The submitted time, or the whole day's default the form shows
     */
    static String shownToTime(String value) {
        return notBlank(value) ? value : SHOWN_TO_TIME;
    }

    /**
     * @param period The requested preset
     * @return The preset when it is a known one, the default preset otherwise
     */
    static String preset(String period) {
        String value = period == null ? "" : period.trim();
        return LABELS.containsKey(value) ? value : DEFAULT_PRESET;
    }

    /**
     * @param period   The selected preset, when no custom range is given
     * @param fromDate The custom range's start date
     * @param fromTime The custom range's start time, {@code H:mm} or {@code HH}
     * @param toDate   The custom range's end date
     * @param toTime   The custom range's end time, {@code H:mm} or {@code HH}
     * @return The period's bounds and label
     */
    Range resolve(String period, String fromDate, String fromTime, String toDate, String toTime) {
        // A chosen preset wins over whatever dates linger in the form or the address bar: picking
        // "Year to date" while a start date is still filled in must show the year, not that range.
        // The custom fields apply only while the Time select says "Custom range".
        if (usesCustomRange(period, fromDate, toDate)) {
            LocalDate from = parseDate(fromDate);
            LocalDate to = parseDate(toDate);
            boolean unparseable = (notBlank(fromDate) && from == null) || (notBlank(toDate) && to == null)
                    || (notBlank(fromTime) && parseTime(fromTime) == null)
                    || (notBlank(toTime) && parseTime(toTime) == null);
            if (!unparseable) {
                // Each side stands on its own: an empty start date means "everything before the
                // end", an empty end date means "through today", and an empty time means the whole
                // day (an explicit minute runs through the end of that minute). Anything that is
                // present but unreadable — a date or a time — falls back to the preset below rather
                // than widening the range silently, and so does a pair that runs backwards.
                LocalDate end = to == null ? clock.instant().atZone(display.zone()).toLocalDate() : to;
                LocalTime fromClock = parseTime(fromTime);
                LocalTime toClock = parseTime(toTime);
                LocalDateTime start = from == null ? null : from.atTime(fromClock == null ? LocalTime.MIN : fromClock);
                long fromMillis = start == null ? 0 : start.atZone(display.zone()).toInstant().toEpochMilli();
                long toMillis = toClock == null ? endOfDay(end)
                        : end.atTime(toClock).plusMinutes(1).atZone(display.zone()).toInstant().toEpochMilli() - 1;
                // A range that runs backwards (a start in the future, swapped dates) falls back to
                // the preset rather than asking the database or the exchange for an impossible pair;
                // the REST layer rejects the same combination.
                if (fromMillis <= toMillis) {
                    LocalDateTime labelTo = end.atTime(toClock == null ? LocalTime.of(23, 59) : toClock);
                    String label = (start == null ? "the start"
                            : display.minuteYear(start.atZone(display.zone()).toInstant()))
                            + " → " + display.minuteYear(labelTo.atZone(display.zone()).toInstant());
                    return new Range(fromMillis, toMillis, label);
                }
            }
        }
        String resolved = preset(period);
        Instant now = clock.instant();
        LocalDate today = now.atZone(display.zone()).toLocalDate();
        long from = switch (resolved) {
            case "1h" -> now.minusSeconds(3600).toEpochMilli();
            case "2h" -> now.minusSeconds(2 * 3600).toEpochMilli();
            case "4h" -> now.minusSeconds(4 * 3600).toEpochMilli();
            case "8h" -> now.minusSeconds(8 * 3600).toEpochMilli();
            case "24h" -> now.minusSeconds(24 * 3600).toEpochMilli();
            case "3d" -> now.minusSeconds(3 * 86400).toEpochMilli();
            case "14d" -> now.minusSeconds(14 * 86400).toEpochMilli();
            case "30d" -> now.minusSeconds(30 * 86400).toEpochMilli();
            case "last-week" -> weekStart(today).minusWeeks(1).atStartOfDay(display.zone()).toInstant().toEpochMilli();
            case "last-two-weeks" -> weekStart(today).minusWeeks(2).atStartOfDay(display.zone()).toInstant().toEpochMilli();
            case "last-month" -> YearMonth.from(today).minusMonths(1).atDay(1)
                    .atStartOfDay(display.zone()).toInstant().toEpochMilli();
            case "ytd" -> today.withDayOfYear(1).atStartOfDay(display.zone()).toInstant().toEpochMilli();
            case "last-year" -> today.withDayOfYear(1).minusYears(1)
                    .atStartOfDay(display.zone()).toInstant().toEpochMilli();
            default -> now.minusSeconds(7 * 86400).toEpochMilli();
        };
        long to = switch (resolved) {
            case "last-week", "last-two-weeks" -> endOfDay(weekStart(today).minusDays(1));
            case "last-month" -> endOfDay(YearMonth.from(today).minusMonths(1).atEndOfMonth());
            case "last-year" -> endOfDay(YearMonth.of(today.getYear() - 1, 12).atEndOfMonth());
            default -> now.toEpochMilli();
        };
        return new Range(from, to, LABELS.get(resolved));
    }

    private LocalDate weekStart(LocalDate day) {
        return day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    private long endOfDay(LocalDate day) {
        return day.atTime(LocalTime.MAX).atZone(display.zone()).toInstant().toEpochMilli();
    }

    private static LocalDate parseDate(String value) {
        if (!notBlank(value)) {
            return null;
        }
        try {
            return LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static LocalTime parseTime(String value) {
        if (!notBlank(value)) {
            return null;
        }
        try {
            return LocalTime.parse(value.length() <= 2 ? value + ":00" : value,
                    DateTimeFormatter.ofPattern("H:mm"));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
