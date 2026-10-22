package nl.jimkaplan.autotrader.strategy.controller;

/**
 * One exchange sell that can be attached to an open position as a manual close.
 *
 * @param orderId       The exchange order id
 * @param timeText      When the exchange filled it
 * @param quantityText  The quantity sold
 * @param priceText     The average fill price
 * @param feeText       The fee the exchange charged
 * @param proceedsText  The filled quote amount
 * @param coversPosition Whether the sell covers the whole position
 * @param coverText     A sentence explaining whether it covers the position
 */
public record ExternalCloseView(String orderId, String timeText, String quantityText,
                                String priceText, String feeText, String proceedsText,
                                boolean coversPosition, String coverText) {
}
