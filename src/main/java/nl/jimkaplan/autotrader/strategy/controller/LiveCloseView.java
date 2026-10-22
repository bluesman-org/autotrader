package nl.jimkaplan.autotrader.strategy.controller;

/**
 * The confirmation view for closing a position from the live page.
 *
 * @param strategyId   The strategy ID
 * @param name         The strategy name
 * @param market       The market
 * @param interval     The interval
 * @param dryRun       Whether the close is simulated
 * @param priced       Whether a current bid was available; without it the close cannot run
 * @param quantityText The position size, e.g. {@code LONG 0,00136090 BTC}
 * @param entryText    When and at what price the position was opened
 * @param entryFeeText The fee paid on entry
 * @param bidText      The current bid, what a market sell gets
 * @param proceedsText The estimated proceeds before the sell fee
 * @param feeText      The estimated sell fee
 * @param profitText   The estimated realised result after both fees
 * @param profitClass  {@code pos}/{@code neg} for colour, empty when unpriced
 * @param warning      What the operator is about to do, in one sentence
 */
public record LiveCloseView(String strategyId, String name, String market, String interval,
                            boolean dryRun, boolean priced, String quantityText, String entryText,
                            String entryFeeText, String bidText, String proceedsText, String feeText,
                            String profitText, String profitClass, String warning) {
}
