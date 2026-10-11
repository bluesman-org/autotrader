package nl.jimkaplan.autotrader.strategy.backtest;

import java.util.List;

/**
 * The outcome of one simulated run.
 *
 * @param metrics        The summary metrics
 * @param trades         The completed round trips, in fill order
 * @param skippedSignals Entry signals that could not be filled
 */
public record BacktestResult(BacktestMetrics metrics, List<BacktestTrade> trades,
                             List<SkippedSignal> skippedSignals) {
}
