package nl.jimkaplan.autotrader.strategy.controller;

/**
 * The request body for attaching an exchange sell to an open position.
 *
 * @param orderId The exchange order id the operator picked
 */
public record ExternalCloseRequest(String orderId) {
}
