package nl.jimkaplan.autotrader.strategy.controller;

import java.util.List;

/**
 * The engine-trades tab's model: the current page of closed round trips, the sortable headers, the
 * filter bar, the paging state, and the period's totals and daily realised P&L.
 *
 * @param rows    The current page of round trips
 * @param headers The sortable column headers, in order
 * @param filters The filter bar's selected state and options
 * @param pager   The paging state and links
 * @param totals  The totals of the whole selected period
 * @param daily   The realised P&L by day (real trades only)
 */
public record TradeHistoryView(List<TradeRowView> rows, List<HeaderView> headers, FiltersView filters,
                               PagerView pager, TotalsView totals, List<DailyView> daily) {

    /** Copy the lists so the model cannot be mutated from outside. */
    public TradeHistoryView {
        rows = List.copyOf(rows);
        headers = List.copyOf(headers);
        daily = List.copyOf(daily);
    }

    /**
     * One closed round trip.
     *
     * @param number       The row number in the current view (continues across pages)
     * @param strategyLabel The strategy's name, or its shortened ID when the name is missing
     * @param chipClass    The strategy chip's colour class
     * @param hueClass     The row accent's colour class
     * @param typeLabel    {@code DRY RUN} or {@code REAL}
     * @param typeClass    The badge's colour class
     * @param entryText    When it was bought
     * @param exitText     When it was sold
     * @param priceText    The entry → exit fill prices
     * @param quantityText The quantity
     * @param resultText   The net result after both fees
     * @param resultClass  {@code pos}/{@code neg}
     * @param details      The expandable detail
     */
    public record TradeRowView(int number, String strategyLabel, String chipClass, String hueClass,
                               String typeLabel, String typeClass, String entryText, String exitText,
                               String priceText, String quantityText, String resultText,
                               String resultClass, DetailsView details) {
    }

    /**
     * The row's expandable detail.
     *
     * @param boughtText    When, how much and at what price it was bought
     * @param boughtFeeText The buy's fee
     * @param soldText      When, how much and at what price it was sold
     * @param soldFeeText   The sell's fee
     * @param heldText      How long the position was held
     * @param orderRefsText The exchange order references, or why there are none
     */
    public record DetailsView(String boughtText, String boughtFeeText, String soldText, String soldFeeText,
                              String heldText, String orderRefsText) {
    }

    /**
     * A sortable column header.
     *
     * @param label      The column name
     * @param alignClass {@code left} for text columns, empty for numeric ones
     * @param query      The query string that sorts by this column (keeps the filters), appended to
     *                   the page's path by the template so the context path stays intact
     * @param active     Whether this column is the current sort
     * @param arrow      {@code ▼}/{@code ▲} when active, empty otherwise
     */
    public record HeaderView(String label, String alignClass, String query, boolean active, String arrow) {
    }

    /**
     * The filter bar's state and options.
     *
     * @param strategy        The selected strategy, or empty for all
     * @param market          The selected market, or empty for all
     * @param type            The selected type ({@code dry}/{@code real}), or empty for all
     * @param result          The selected result ({@code wins}/{@code losses}), or empty for all
     * @param period          The selected time preset, empty when the custom range is in use
     * @param fromDate        The custom range's start date
     * @param fromTime        The custom range's start time
     * @param toDate          The custom range's end date
     * @param toTime          The custom range's end time
     * @param defaultPeriod   The preset the app falls back to, so the page can restore it when a
     *                        custom range no longer carries a date
     * @param strategyOptions The strategies present in the period: full IDs as values, short IDs as
     *                        labels
     * @param marketOptions   The markets present in the period
     * @param timeOptions     The time presets
     */
    public record FiltersView(String strategy, String market, String type, String result, String period,
                              String fromDate, String fromTime, String toDate, String toTime,
                              String defaultPeriod, List<Option> strategyOptions,
                              List<Option> marketOptions, List<Option> timeOptions) {

        /** Copy the option lists so the model cannot be mutated from outside. */
        public FiltersView {
            strategyOptions = List.copyOf(strategyOptions);
            marketOptions = List.copyOf(marketOptions);
            timeOptions = List.copyOf(timeOptions);
        }
    }

    /**
     * A select option.
     *
     * @param value The option's value
     * @param label The option's label
     */
    public record Option(String value, String label) {
    }

    /**
     * Paging state.
     *
     * @param from      The first row on this page, 1-based
     * @param to        The last row on this page, 1-based
     * @param total     The rows in the whole filtered period
     * @param page      The current page, 1-based
     * @param pages     The page count
     * @param size      The rows per page
     * @param prevQuery The Newer link's query (page before this one), null on the first page
     * @param nextQuery The Older link's query (page after this one), null on the last page
     * @param sizes     The rows-per-page options, each carrying its own query
     */
    public record PagerView(int from, int to, int total, int page, int pages, int size,
                            String prevQuery, String nextQuery, List<SizeOption> sizes) {

        /** Copy the size options so the model cannot be mutated from outside. */
        public PagerView {
            sizes = List.copyOf(sizes);
        }
    }

    /**
     * A rows-per-page option.
     *
     * @param label    The option's label
     * @param query    The query that re-renders with this size, from the first page
     * @param selected Whether it is the current size
     */
    public record SizeOption(String label, String query, boolean selected) {
    }

    /**
     * Totals over the whole selected period, not the page.
     *
     * @param roundTrips    The round trips in the period
     * @param realisedText  The realised result
     * @param realisedClass {@code pos}/{@code neg}
     * @param winRateText   The share of round trips that ended at €0 or better
     * @param periodText    The period, e.g. {@code Last 7 days} or an explicit range
     */
    public record TotalsView(int roundTrips, String realisedText, String realisedClass,
                             String winRateText, String periodText) {
    }

    /**
     * One day's realised P&L, real trades only (dry-runs are excluded).
     *
     * @param dayText         The day
     * @param roundTrips      The round trips that closed that day
     * @param realisedText    The day's result
     * @param realisedClass   {@code pos}/{@code neg}
     * @param cumulativeText  The running total
     * @param cumulativeClass {@code pos}/{@code neg}
     */
    public record DailyView(String dayText, int roundTrips, String realisedText, String realisedClass,
                            String cumulativeText, String cumulativeClass) {
    }
}
