package nl.jimkaplan.autotrader.strategy.backtest;

/**
 * Both sides of one evaluated combination.
 *
 * @param train    The earlier side's result, including its trades and skipped signals
 * @param validate The held-out newer side's result
 */
public record BacktestEvaluation(BacktestResult train, BacktestResult validate) {
}
