package nl.jimkaplan.autotrader.strategy.backtest;

import nl.jimkaplan.autotrader.strategy.rule.CandleSeries;
import nl.jimkaplan.autotrader.strategy.rule.IndicatorSeries;
import nl.jimkaplan.autotrader.strategy.rule.MacdParameters;
import nl.jimkaplan.autotrader.strategy.rule.ParabolicSarParameters;
import nl.jimkaplan.autotrader.strategy.rule.RuleGroup;
import nl.jimkaplan.autotrader.strategy.rule.StrategyEvaluator;
import nl.jimkaplan.autotrader.strategy.rule.StrategySignals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Sweeps entry×exit rule sets and indicator parameter grids, optimising on the earlier part of
 * the range and verifying on the held-out newer part ({@code docs/backtest.md}).
 *
 * <p>Indicators and signals are computed once per indicator parameter set and reused across the
 * rule combinations; the simulation is the same deterministic engine used by single runs.</p>
 */
public final class BacktestSweep {

    /**
     * The largest number of combinations one sweep may evaluate.
     */
    public static final int MAX_COMBINATIONS = 5000;

    private BacktestSweep() {
    }

    /**
     * Run the sweep.
     *
     * @param series     The candle series to evaluate over
     * @param definition The candidates, split and ranking metric
     * @param config     The simulation settings
     * @return The ranked result
     * @throws IllegalArgumentException if the sweep exceeds the combination cap or the range is
     *                                  too short for the split
     */
    public static SweepResult run(CandleSeries series, SweepDefinition definition, BacktestConfig config) {
        // Fold with a long accumulator, checking after each factor: the product cannot overflow
        // and cannot be wrapped past the guard
        long combinations = 1;
        for (long size : new long[]{definition.macdParameters().size(), definition.sarParameters().size(),
                definition.entryRules().size(), definition.exitRules().size()}) {
            combinations *= size;
            if (combinations > MAX_COMBINATIONS) {
                throw new IllegalArgumentException("a sweep may evaluate at most " + MAX_COMBINATIONS
                        + " combinations");
            }
        }
        int splitIndex = (int) Math.floor(series.length() * definition.trainRatio());
        if (splitIndex < 1 || splitIndex >= series.length()) {
            throw new IllegalArgumentException("trainRatio leaves no bars on one side of the split");
        }

        CandleSeries trainSeries = slice(series, 0, splitIndex);
        CandleSeries validateSeries = slice(series, splitIndex, series.length());
        // The validate side's indicators are warm from the earlier data, so it trades from its
        // first bar instead of repeating the warm-up
        BacktestConfig validateConfig = new BacktestConfig(config.initialCapital(), config.amountPerBuy(),
                config.feeRate(), 0);

        List<SweepEntry> entries = new ArrayList<>((int) combinations);
        for (MacdParameters macd : definition.macdParameters()) {
            for (ParabolicSarParameters sar : definition.sarParameters()) {
                IndicatorSeries indicators = IndicatorSeries.of(series, macd, sar);
                List<boolean[]> entrySignalSets = new ArrayList<>(definition.entryRules().size());
                for (RuleGroup entryRules : definition.entryRules()) {
                    entrySignalSets.add(StrategyEvaluator.evaluate(entryRules, series, indicators));
                }
                List<boolean[]> exitSignalSets = new ArrayList<>(definition.exitRules().size());
                for (RuleGroup exitRules : definition.exitRules()) {
                    exitSignalSets.add(StrategyEvaluator.evaluate(exitRules, series, indicators));
                }
                for (int entryIndex = 0; entryIndex < definition.entryRules().size(); entryIndex++) {
                    for (int exitIndex = 0; exitIndex < definition.exitRules().size(); exitIndex++) {
                        boolean[] entrySignals = entrySignalSets.get(entryIndex);
                        boolean[] exitSignals = exitSignalSets.get(exitIndex);
                        BacktestMetrics train = BacktestEngine.run(trainSeries,
                                new StrategySignals(Arrays.copyOfRange(entrySignals, 0, splitIndex),
                                        Arrays.copyOfRange(exitSignals, 0, splitIndex)),
                                config).metrics();
                        BacktestMetrics validate = BacktestEngine.run(validateSeries,
                                new StrategySignals(Arrays.copyOfRange(entrySignals, splitIndex, entrySignals.length),
                                        Arrays.copyOfRange(exitSignals, splitIndex, exitSignals.length)),
                                validateConfig).metrics();
                        entries.add(new SweepEntry(macd, sar, definition.entryRules().get(entryIndex),
                                definition.exitRules().get(exitIndex), train, validate));
                    }
                }
            }
        }

        Comparator<SweepEntry> byMetric = Comparator.comparingDouble(
                entry -> definition.rankingMetric().value(entry.train()));
        if (definition.rankingMetric().higherIsBetter()) {
            byMetric = byMetric.reversed();
        }
        // A combination whose train side never traded has no evidence; it ranks last whatever the
        // metric says (a flat curve would otherwise win "best drawdown")
        Comparator<SweepEntry> comparator = Comparator
                .comparingInt((SweepEntry entry) -> entry.train().tradeCount() == 0 ? 1 : 0)
                .thenComparing(byMetric);
        entries.sort(comparator);
        return new SweepResult(entries.size(), List.copyOf(entries));
    }

    private static CandleSeries slice(CandleSeries series, int from, int to) {
        return new CandleSeries(Arrays.copyOfRange(series.opens(), from, to),
                Arrays.copyOfRange(series.highs(), from, to),
                Arrays.copyOfRange(series.lows(), from, to),
                Arrays.copyOfRange(series.closes(), from, to),
                Arrays.copyOfRange(series.times(), from, to));
    }
}
