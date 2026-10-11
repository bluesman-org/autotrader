package nl.jimkaplan.autotrader.strategy.backtest;

/**
 * An entry signal that could not be filled, recorded rather than dropped.
 *
 * @param index  The bar index of the signal
 * @param time   The bar's open time, epoch milliseconds
 * @param reason Why the trade was skipped
 */
public record SkippedSignal(int index, long time, SkipReason reason) {
}
