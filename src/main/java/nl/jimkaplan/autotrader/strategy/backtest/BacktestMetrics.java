package nl.jimkaplan.autotrader.strategy.backtest;

/**
 * The outcome metrics of one simulated run.
 *
 * @param finalEquity        Cash plus position value at the end, in EUR
 * @param totalReturnPercent Return on the initial capital, in percent
 * @param maxDrawdownPercent Largest peak-to-trough decline of the bar-close equity, in percent
 * @param tradeCount         Number of completed round trips
 * @param winRatePercent     Share of profitable trades, in percent (0 when there are no trades)
 * @param profitFactor       Gross profit divided by gross loss; 0 when there are no trades,
 *                           infinite when there are profits and no losses
 * @param skippedSignals     Entry signals that could not be filled, recorded rather than faked
 */
public record BacktestMetrics(double finalEquity, double totalReturnPercent, double maxDrawdownPercent,
                              int tradeCount, double winRatePercent, double profitFactor,
                              int skippedSignals) {
}
