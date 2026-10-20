package nl.jimkaplan.autotrader.strategy.controller;

/**
 * The outcome of deleting a live strategy.
 *
 * @param id       The strategy ID
 * @param archived Whether the strategy was archived instead of deleted because it has a history
 */
public record LiveStrategyDeletion(String id, boolean archived) {
}
