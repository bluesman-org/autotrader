package nl.jimkaplan.autotrader.strategy.controller;

import java.util.List;

/**
 * One strategy's card on the live page.
 *
 * @param id         The strategy ID (shown shortened)
 * @param market     The market
 * @param interval   The candle interval code
 * @param running    Whether the engine has a runner for it
 * @param connected  Whether its candle stream is up (never true when stopped)
 * @param dryRun     Whether its orders are simulated
 * @param parameters One-line summary of the decision parameters and sizing
 * @param statusLine Created/running-since/last-evaluated/freshness summary, ending in the last error
 * @param position   The open position, or null
 * @param statsLine  The realised round-trip statistics line
 * @param orders     The most recent orders, newest first
 */
public record LiveCardView(String id, String market, String interval, boolean running, boolean connected,
                           boolean dryRun, String parameters, String statusLine,
                           PositionView position, String statsLine, List<OrderView> orders) {

    /** Copy the orders so the card's list cannot be mutated from outside. */
    public LiveCardView {
        orders = List.copyOf(orders);
    }

    /**
     * The open position, valued at the current sell price when the exchange answered.
     *
     * @param quantityText      The position size, e.g. {@code LONG 0.00134403 BTC}
     * @param entryText         When and at what price it was bought
     * @param buyText           The current buy price (what a market buy pays), or a dash
     * @param sellText          The current sell price (what a market sell gets), or a dash
     * @param valueNowText      The position's value at the current sell price, or a dash
     * @param ifClosedNowText   The theoretical result of closing now, after both fees, or a dash
     * @param ifClosedNowClass  {@code pos}/{@code neg} for colour, empty when unpriced
     * @param entryFeeText      The fee already paid on the buy
     */
    public record PositionView(String quantityText, String entryText, String buyText, String sellText,
                               String valueNowText, String ifClosedNowText, String ifClosedNowClass,
                               String entryFeeText) {
    }

    /**
     * A recent order of the strategy.
     *
     * @param timeText         When the order was placed (same-day orders show the time only)
     * @param side             {@code BUY} or {@code SELL}
     * @param quantityText     The order quantity, or a dash
     * @param priceText        The fill or estimate price, or a dash
     * @param statusBadge      The status label of the badge
     * @param statusBadgeClass The badge's colour class ({@code dry}, {@code real}, {@code warn}, …)
     * @param resultText       The realised result for a sell, a dash otherwise
     * @param resultClass      {@code pos}/{@code neg} for colour, empty for a dash
     * @param openedPosition   Whether this buy opened the currently open position
     */
    public record OrderView(String timeText, String side, String quantityText, String priceText,
                            String statusBadge, String statusBadgeClass, String resultText,
                            String resultClass, boolean openedPosition) {
    }
}
