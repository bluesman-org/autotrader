package nl.jimkaplan.autotrader.strategy.backtest;

import nl.jimkaplan.autotrader.strategy.rule.CandleSeries;
import nl.jimkaplan.autotrader.strategy.rule.StrategySignals;

import java.util.Arrays;

/**
 * One train/validate split of a candle series, with the configuration each side simulates under
 * ({@code docs/backtest.md}): the earlier side carries the configured warm-up, while the held-out
 * newer side trades from its first bar because its indicators are already warm from the earlier
 * data.
 *
 * <p>Signals are evaluated once over the whole series and sliced to the two sides, so a split can
 * evaluate any number of combinations without re-slicing the series. This is the single place the
 * split semantics live, shared by the sweep and the on-demand recomputation behind the trades
 * endpoint, so the two can never drift apart.</p>
 */
public record BacktestSplit(CandleSeries trainSeries, CandleSeries validateSeries,
                            BacktestConfig trainConfig, BacktestConfig validateConfig, int splitIndex) {

    /**
     * Split the series at {@code floor(length × trainRatio)}.
     *
     * @param series     The candle series to split
     * @param config     The simulation settings; the validate side repeats them with warm-up 0
     * @param trainRatio The share of the range optimised on
     * @return The split
     * @throws IllegalArgumentException if the ratio leaves no bars on one side
     */
    public static BacktestSplit of(CandleSeries series, BacktestConfig config, double trainRatio) {
        int splitIndex = (int) Math.floor(series.length() * trainRatio);
        if (splitIndex < 1 || splitIndex >= series.length()) {
            throw new IllegalArgumentException("trainRatio leaves no bars on one side of the split");
        }
        BacktestConfig validateConfig = new BacktestConfig(config.initialCapital(), config.amountPerBuy(),
                config.feeRate(), 0);
        return new BacktestSplit(slice(series, 0, splitIndex), slice(series, splitIndex, series.length()),
                config, validateConfig, splitIndex);
    }

    /**
     * Simulate one combination's signals on both sides.
     *
     * @param entrySignals The entry signals, evaluated on the unsplit series
     * @param exitSignals  The exit signals, evaluated on the unsplit series
     * @return Both sides' results, including every completed trade and skipped signal
     * @throws IllegalArgumentException if the signals are not aligned with the unsplit series
     */
    public BacktestEvaluation evaluate(boolean[] entrySignals, boolean[] exitSignals) {
        int expected = trainSeries.length() + validateSeries.length();
        if (entrySignals.length != expected || exitSignals.length != expected) {
            throw new IllegalArgumentException("signals must be evaluated on the split's unsplit series");
        }
        BacktestResult train = BacktestEngine.run(trainSeries,
                new StrategySignals(Arrays.copyOfRange(entrySignals, 0, splitIndex),
                        Arrays.copyOfRange(exitSignals, 0, splitIndex)),
                trainConfig);
        BacktestResult validate = BacktestEngine.run(validateSeries,
                new StrategySignals(Arrays.copyOfRange(entrySignals, splitIndex, entrySignals.length),
                        Arrays.copyOfRange(exitSignals, splitIndex, exitSignals.length)),
                validateConfig);
        return new BacktestEvaluation(train, validate);
    }

    private static CandleSeries slice(CandleSeries series, int from, int to) {
        return new CandleSeries(Arrays.copyOfRange(series.opens(), from, to),
                Arrays.copyOfRange(series.highs(), from, to),
                Arrays.copyOfRange(series.lows(), from, to),
                Arrays.copyOfRange(series.closes(), from, to),
                Arrays.copyOfRange(series.times(), from, to));
    }
}
