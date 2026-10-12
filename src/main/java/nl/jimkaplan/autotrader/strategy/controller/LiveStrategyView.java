package nl.jimkaplan.autotrader.strategy.controller;

import nl.jimkaplan.autotrader.strategy.model.document.LiveStrategy;

/**
 * A live strategy with its runtime state.
 *
 * @param strategy  The stored strategy
 * @param running   Whether the engine has a runner for it
 * @param connected Whether its candle stream subscription is up
 * @param lastError The stream's last error message, or null when there is none; a connected stream
 *                  with an error (a rejected subscription, for example) would otherwise look healthy
 */
public record LiveStrategyView(LiveStrategy strategy, boolean running, boolean connected, String lastError) {
}
