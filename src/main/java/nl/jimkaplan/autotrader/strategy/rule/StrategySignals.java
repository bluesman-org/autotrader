package nl.jimkaplan.autotrader.strategy.rule;

/**
 * The per-bar signals a strategy produced, aligned with the candle series.
 *
 * @param entry {@code true} where the entry rules fired
 * @param exit  {@code true} where the exit rules fired
 */
public record StrategySignals(boolean[] entry, boolean[] exit) {
}
