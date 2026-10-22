package nl.jimkaplan.autotrader.strategy.controller;

/**
 * The outcome of a close or an external-close reconciliation.
 *
 * @param closed   Whether the position is closed now
 * @param status   The order's status after the action
 * @param orderId  The exchange order id, when there is one
 * @param message  What the operator should know
 */
public record LiveCloseResult(boolean closed, String status, String orderId, String message) {
}
