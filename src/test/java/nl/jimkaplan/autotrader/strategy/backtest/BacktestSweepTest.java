package nl.jimkaplan.autotrader.strategy.backtest;

import nl.jimkaplan.autotrader.strategy.rule.CandleSeries;
import nl.jimkaplan.autotrader.strategy.rule.IndicatorSeries;
import nl.jimkaplan.autotrader.strategy.rule.LogicalOperator;
import nl.jimkaplan.autotrader.strategy.rule.MacdParameters;
import nl.jimkaplan.autotrader.strategy.rule.ParabolicSarParameters;
import nl.jimkaplan.autotrader.strategy.rule.RuleCondition;
import nl.jimkaplan.autotrader.strategy.rule.RuleGroup;
import nl.jimkaplan.autotrader.strategy.rule.StrategyEvaluator;
import nl.jimkaplan.autotrader.strategy.rule.StrategySignals;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BacktestSweepTest {

    private static final RuleGroup TRADES = new RuleGroup(LogicalOperator.ALL,
            List.of(RuleCondition.PRICE_ABOVE_SAR));
    private static final RuleGroup NEVER = new RuleGroup(LogicalOperator.ALL,
            List.of(RuleCondition.PRICE_BELOW_SAR));
    private static final BacktestConfig CONFIG = new BacktestConfig(1000.0, 100.0, 0.0, 0);

    @Test
    void ranksTheTradingRuleAboveTheNeverFiringOne() {
        // A strictly rising series: above-SAR trades (one position, closed at the end),
        // below-SAR can never fire because the SAR stays under the price
        CandleSeries series = risingSeries(40);
        SweepDefinition definition = definition(List.of(new MacdParameters(3, 5, 2)),
                List.of(TRADES, NEVER), RankingMetric.TOTAL_RETURN);

        SweepResult result = BacktestSweep.run(series, definition, CONFIG);

        assertEquals(2, result.combinationsEvaluated());
        SweepEntry best = result.ranked().get(0);
        assertEquals(TRADES, best.entryRules());
        assertTrue(best.train().tradeCount() > 0);
        assertTrue(best.train().totalReturnPercent() > 0);
        SweepEntry worst = result.ranked().get(1);
        assertEquals(NEVER, worst.entryRules());
        assertEquals(0, worst.train().tradeCount());
        assertEquals(0.0, worst.train().totalReturnPercent(), 1e-9);
    }

    @Test
    void ranksByTheChosenMetricsDirection() {
        CandleSeries series = zigZagSeries(80);
        RuleGroup entry = new RuleGroup(LogicalOperator.ALL, List.of(RuleCondition.MACD_BULLISH_CROSSOVER));
        RuleGroup exit = new RuleGroup(LogicalOperator.ALL, List.of(RuleCondition.MACD_BEARISH_CROSSOVER));

        for (RankingMetric metric : RankingMetric.values()) {
            SweepDefinition definition = new SweepDefinition(
                    List.of(new MacdParameters(3, 5, 2), new MacdParameters(5, 10, 3)),
                    List.of(ParabolicSarParameters.defaults()), List.of(entry), List.of(exit),
                    0.7, metric);

            SweepResult result = BacktestSweep.run(series, definition, new BacktestConfig(1000, 100, 0.002, 0));

            List<Double> values = result.ranked().stream().map(e -> metric.value(e.train())).toList();
            for (int i = 1; i < values.size(); i++) {
                if (metric.higherIsBetter()) {
                    assertTrue(values.get(i - 1) >= values.get(i), metric + " must rank higher first");
                } else {
                    assertTrue(values.get(i - 1) <= values.get(i), metric + " must rank lower first");
                }
            }
        }
    }

    @Test
    void reportsBothSidesOfTheSplit() {
        CandleSeries series = risingSeries(40);
        SweepDefinition definition = definition(List.of(new MacdParameters(3, 5, 2)),
                List.of(TRADES), RankingMetric.TOTAL_RETURN);

        SweepResult result = BacktestSweep.run(series, definition, CONFIG);

        SweepEntry entry = result.ranked().get(0);
        assertTrue(entry.validate().finalEquity() > 0);
        // Both sides start from the same capital; the rising series profits on both
        assertTrue(entry.validate().totalReturnPercent() > 0);
    }

    @Test
    void isDeterministic() {
        CandleSeries series = zigZagSeries(60);
        SweepDefinition definition = definition(List.of(new MacdParameters(3, 5, 2)),
                List.of(TRADES, NEVER), RankingMetric.MAX_DRAWDOWN);

        SweepResult first = BacktestSweep.run(series, definition, CONFIG);
        SweepResult second = BacktestSweep.run(series, definition, CONFIG);

        assertEquals(first, second);
    }

    @Test
    void rejectsSweepsAboveTheCombinationCap() {
        List<MacdParameters> macd = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            macd.add(new MacdParameters(1, 1, 1));
        }
        List<ParabolicSarParameters> sar = new ArrayList<>();
        for (int i = 0; i < 99; i++) {
            sar.add(ParabolicSarParameters.defaults());
        }
        SweepDefinition definition = new SweepDefinition(macd, sar, List.of(TRADES), List.of(NEVER),
                0.7, RankingMetric.TOTAL_RETURN);

        // 51 * 99 = 5049 combinations
        assertThrows(IllegalArgumentException.class,
                () -> BacktestSweep.run(risingSeries(10), definition, CONFIG));
    }

    @Test
    void ranksNeverTradingCombinationsLastEvenWhenTheirDrawdownIsZero() {
        // On a strictly rising series the below-SAR rule never fires: its flat curve has 0%
        // drawdown, while the trading rule dips below its peak by the entry fee — the flat result
        // must still rank last
        CandleSeries series = risingSeries(40);
        SweepDefinition definition = definition(List.of(new MacdParameters(3, 5, 2)),
                List.of(NEVER, TRADES), RankingMetric.MAX_DRAWDOWN);

        SweepResult result = BacktestSweep.run(series, definition,
                new BacktestConfig(1000.0, 100.0, 0.002, 0));

        SweepEntry best = result.ranked().get(0);
        assertEquals(TRADES, best.entryRules());
        assertTrue(best.train().tradeCount() > 0);
        assertTrue(best.train().maxDrawdownPercent() > 0);
        SweepEntry last = result.ranked().get(1);
        assertEquals(NEVER, last.entryRules());
        assertEquals(0, last.train().tradeCount());
        assertEquals(0.0, last.train().maxDrawdownPercent(), 1e-9);
    }

    @Test
    void theCombinationCapCannotBeWrappedByOverflow() {
        // Four lists of 300: the int product (8.1e9) used to wrap negative and slip past the guard
        List<MacdParameters> macd = new ArrayList<>();
        List<ParabolicSarParameters> sar = new ArrayList<>();
        List<RuleGroup> entries = new ArrayList<>();
        List<RuleGroup> exits = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            macd.add(new MacdParameters(1, 1, 1));
            sar.add(ParabolicSarParameters.defaults());
            entries.add(TRADES);
            exits.add(NEVER);
        }
        SweepDefinition definition = new SweepDefinition(macd, sar, entries, exits, 0.7,
                RankingMetric.TOTAL_RETURN);

        assertThrows(IllegalArgumentException.class,
                () -> BacktestSweep.run(zigZagSeries(10), definition, CONFIG));
    }

    @Test
    void theSplitBoundaryDecidesWhichBarsBelongToWhichSide() {
        // Rises for the first 70% and falls afterwards: the train side profits, the validate side
        // does not — and the exact boundary is pinned by rebuilding both slices independently
        CandleSeries series = riseThenFallSeries(50);
        MacdParameters macd = new MacdParameters(3, 5, 2);
        SweepDefinition definition = new SweepDefinition(List.of(macd),
                List.of(ParabolicSarParameters.defaults()), List.of(TRADES), List.of(NEVER),
                0.7, RankingMetric.TOTAL_RETURN);

        SweepResult result = BacktestSweep.run(series, definition, CONFIG);

        SweepEntry entry = result.ranked().get(0);
        assertTrue(entry.train().totalReturnPercent() > 0);
        assertTrue(entry.validate().totalReturnPercent() <= 0);

        int split = (int) Math.floor(series.length() * 0.7);
        IndicatorSeries indicators = IndicatorSeries.of(series, macd, ParabolicSarParameters.defaults());
        boolean[] entrySignals = StrategyEvaluator.evaluate(TRADES, series, indicators);
        boolean[] exitSignals = StrategyEvaluator.evaluate(NEVER, series, indicators);
        BacktestMetrics expectedTrain = BacktestEngine.run(slice(series, 0, split),
                new StrategySignals(Arrays.copyOfRange(entrySignals, 0, split),
                        Arrays.copyOfRange(exitSignals, 0, split)), CONFIG).metrics();
        BacktestMetrics expectedValidate = BacktestEngine.run(slice(series, split, series.length()),
                new StrategySignals(Arrays.copyOfRange(entrySignals, split, entrySignals.length),
                        Arrays.copyOfRange(exitSignals, split, exitSignals.length)),
                new BacktestConfig(1000, 100, 0.0, 0)).metrics();

        assertEquals(expectedTrain, entry.train());
        assertEquals(expectedValidate, entry.validate());
    }

    @Test
    void rejectsASplitThatLeavesNoBarsOnOneSide() {
        CandleSeries oneBar = risingSeries(1);
        SweepDefinition definition = definition(List.of(new MacdParameters(3, 5, 2)),
                List.of(TRADES), RankingMetric.TOTAL_RETURN);

        assertThrows(IllegalArgumentException.class, () -> BacktestSweep.run(oneBar, definition, CONFIG));
    }

    private static SweepDefinition definition(List<MacdParameters> macd, List<RuleGroup> entries,
                                              RankingMetric metric) {
        return new SweepDefinition(macd, List.of(ParabolicSarParameters.defaults()), entries,
                List.of(NEVER), 0.7, metric);
    }

    private static CandleSeries slice(CandleSeries series, int from, int to) {
        return new CandleSeries(Arrays.copyOfRange(series.opens(), from, to),
                Arrays.copyOfRange(series.highs(), from, to),
                Arrays.copyOfRange(series.lows(), from, to),
                Arrays.copyOfRange(series.closes(), from, to),
                Arrays.copyOfRange(series.times(), from, to));
    }

    private static CandleSeries riseThenFallSeries(int length) {
        double[] opens = new double[length];
        double[] highs = new double[length];
        double[] lows = new double[length];
        double[] closes = new double[length];
        long[] times = new long[length];
        int peak = (int) Math.floor(length * 0.7);
        for (int i = 0; i < length; i++) {
            closes[i] = i < peak ? 100.0 + i : 100.0 + peak - (i - peak) * 3.0;
            opens[i] = closes[i];
            highs[i] = closes[i] + 0.5;
            lows[i] = closes[i] - 0.5;
            times[i] = i * 3_600_000L;
        }
        return new CandleSeries(opens, highs, lows, closes, times);
    }

    private static CandleSeries risingSeries(int length) {
        double[] opens = new double[length];
        double[] highs = new double[length];
        double[] lows = new double[length];
        double[] closes = new double[length];
        long[] times = new long[length];
        for (int i = 0; i < length; i++) {
            closes[i] = 10.0 + i;
            opens[i] = closes[i];
            highs[i] = closes[i] + 0.5;
            lows[i] = closes[i] - 0.5;
            times[i] = i * 3_600_000L;
        }
        return new CandleSeries(opens, highs, lows, closes, times);
    }

    private static CandleSeries zigZagSeries(int length) {
        double[] opens = new double[length];
        double[] highs = new double[length];
        double[] lows = new double[length];
        double[] closes = new double[length];
        long[] times = new long[length];
        for (int i = 0; i < length; i++) {
            int phase = i % 10;
            closes[i] = 100.0 + (phase < 5 ? phase : 10 - phase);
            opens[i] = closes[i];
            highs[i] = closes[i] + 0.5;
            lows[i] = closes[i] - 0.5;
            times[i] = i * 3_600_000L;
        }
        return new CandleSeries(opens, highs, lows, closes, times);
    }
}
