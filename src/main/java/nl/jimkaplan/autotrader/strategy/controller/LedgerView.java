package nl.jimkaplan.autotrader.strategy.controller;

import nl.jimkaplan.autotrader.strategy.controller.TradeHistoryView.Option;
import nl.jimkaplan.autotrader.strategy.controller.TradeHistoryView.PagerView;

import java.util.List;

/**
 * The Bitvavo-ledger tab's model: the account's orders for the selected market and period with
 * their fills, or the raw transactions when that view is selected, plus the current price block,
 * the filter bar, the period's totals and the caption.
 *
 * @param message  Why there is nothing to show, when the account could not be read; null otherwise
 * @param price    The market's current prices (buy is the exchange's ask, sell its bid), or null
 * @param filters  The filter bar's selected state and options
 * @param totals   The period's totals, or null when the account could not be read
 * @param rows     The current page of orders
 * @param pager    The orders' paging state and links
 * @param raw      Whether the raw-transactions view is selected
 * @param rawRows  The current page of raw transactions
 * @param rawPager The raw transactions' paging state and links
 */
public record LedgerView(String message, PriceView price, FiltersView filters, TotalsView totals,
                         List<OrderRowView> rows, PagerView pager, boolean raw,
                         List<RawRowView> rawRows, PagerView rawPager) {

    /** Copy the lists so the model cannot be mutated from outside. */
    public LedgerView {
        rows = List.copyOf(rows);
        rawRows = List.copyOf(rawRows);
    }

    /**
     * The market's current prices: the ask is what a buy costs now, the bid what a sell fetches.
     *
     * @param market    The market
     * @param buyText   The ask, as euro, or a dash
     * @param sellText  The bid, as euro, or a dash
     */
    public record PriceView(String market, String buyText, String sellText) {
    }

    /**
     * The filter bar's state and options.
     *
     * @param side          The selected side, or empty for all
     * @param market        The selected market
     * @param type          The selected order types; empty selects every type
     * @param status        The selected statuses; empty selects every status
     * @param view          The selected view ({@code orders}/{@code raw})
     * @param period        The selected time preset, empty when the custom range is in use
     * @param fromDate      The custom range's start date
     * @param fromTime      The custom range's start time
     * @param toDate        The custom range's end date
     * @param toTime        The custom range's end time
     * @param defaultPeriod The preset the app falls back to, so the page can restore it when a
     *                      custom range no longer carries a date
     * @param marketOptions The markets the engine knows, as values and labels
     * @param typeOptions   The order types the exchange uses
     * @param statusOptions The statuses an order can have, as values and labels
     * @param timeOptions   The time presets
     */
    public record FiltersView(String side, String market, List<String> type, List<String> status,
                              String view, String period, String fromDate, String fromTime,
                              String toDate, String toTime, String defaultPeriod,
                              List<Option> marketOptions, List<Option> typeOptions,
                              List<Option> statusOptions, List<Option> timeOptions) {

        /** Copy the lists so the model cannot be mutated from outside. */
        public FiltersView {
            type = List.copyOf(type);
            status = List.copyOf(status);
            marketOptions = List.copyOf(marketOptions);
            typeOptions = List.copyOf(typeOptions);
            statusOptions = List.copyOf(statusOptions);
            timeOptions = List.copyOf(timeOptions);
        }
    }

    /**
     * Totals over the whole selected period, not the page.
     *
     * @param periodText       The period, e.g. {@code Last 7 days} or an explicit range
     * @param orders           The orders in the period, after filtering
     * @param feesText         The fees the orders paid
     * @param realisedText     What the period's sells realised
     * @param realisedClass    {@code pos}/{@code neg}
     * @param unrealisedText   What the period's buys are worth against the current price
     * @param unrealisedClass  {@code pos}/{@code neg}
     * @param balanceText      The account's current balance of the market's base currency
     */
    public record TotalsView(String periodText, int orders, String feesText, String realisedText,
                             String realisedClass, String unrealisedText, String unrealisedClass,
                             String balanceText) {
    }

    /**
     * One order of the period.
     *
     * @param number           The row number in the current view (continues across pages)
     * @param dateText         When the order was created
     * @param side             {@code BUY} or {@code SELL}
     * @param sideClass        The side chip's colour class, or empty for a neutral side
     * @param typeText         The order type
     * @param marketText       The market
     * @param amountText       The order's amount in base currency
     * @param avgPriceText     The average of the fills, or a dash while nothing filled
     * @param statusLabel      The status, spelled out
     * @param statusClass      The status badge's colour class, or empty
     * @param triggerText      For a resting or trigger-waiting order: its trigger price and, when
     *                         the order type carries one, its limit price; empty once it traded
     * @param balanceAfterText The base-currency balance after the order's fills
     * @param feeText          The order's total fee, or a dash while nothing filled
     * @param netCashText      The filled cash flow, fees included
     * @param netCashClass     {@code pos}/{@code neg}
     * @param fillsCount       How many fills the order has
     * @param pnlText          What the order realised, or the buys' theoretical result
     * @param pnlClass         {@code pos}/{@code neg}
     * @param fills            The fills, for the expandable detail
     * @param filledText       What filled of the order and what remains
     */
    public record OrderRowView(int number, String dateText, String side, String sideClass,
                               String typeText, String marketText, String amountText,
                               String avgPriceText, String statusLabel, String statusClass,
                               String triggerText,
                               String balanceAfterText, String feeText, String netCashText,
                               String netCashClass, int fillsCount, String pnlText, String pnlClass,
                               List<FillRowView> fills, String filledText) {

        /** Copy the fills so the model cannot be mutated from outside. */
        public OrderRowView {
            fills = List.copyOf(fills);
        }
    }

    /**
     * One fill of an order.
     *
     * @param timeText       The fill's time of day
     * @param amountText     The filled amount in base currency
     * @param priceText      The fill's price
     * @param feeText        The fill's fee
     * @param makerTakerText {@code maker} or {@code taker}
     * @param netCashText    The fill's cash flow, fee included
     * @param netCashClass   {@code pos}/{@code neg}
     */
    public record FillRowView(String timeText, String amountText, String priceText, String feeText,
                              String makerTakerText, String netCashText, String netCashClass) {
    }

    /**
     * One raw transaction of the period — a fill, a deposit, staking, a rebate.
     *
     * @param number       The row number in the current view (continues across pages)
     * @param timeText     When the transaction executed
     * @param typeText     The transaction type
     * @param typeClass    The buy/sell chip's colour class, or empty for other types
     * @param sentText     What was sent, or a dash
     * @param receivedText What was received, or a dash
     * @param feeText      The fee, or a dash
     */
    public record RawRowView(int number, String timeText, String typeText, String typeClass,
                             String sentText, String receivedText, String feeText) {
    }
}
