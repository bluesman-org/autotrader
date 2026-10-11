package nl.jimkaplan.autotrader.strategy.controller;

import nl.jimkaplan.autotrader.strategy.model.document.LiveStrategy;

/**
 * A live strategy with its runtime state.
 *
 * @param strategy  The stored strategy
 * @param running   Whether the engine has a runner for it
 * @param connected Whether its candle stream subscription is up
 */
public record LiveStrategyView(LiveStrategy strategy, boolean running, boolean connected) {
}
