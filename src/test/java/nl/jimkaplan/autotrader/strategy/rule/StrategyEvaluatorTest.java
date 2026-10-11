package nl.jimkaplan.autotrader.strategy.rule;

import nl.jimkaplan.autotrader.strategy.indicator.MacdResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StrategyEvaluatorTest {

    @Test
    void macdConditions_holdWhereTheSeriesSaySo() {
        MacdResult macd = new MacdResult(
                new double[]{Double.NaN, 1.0, 2.0, 3.0, 1.5},
                new double[]{Double.NaN, 0.5, 2.0, 2.0, 2.0},
                new double[]{Double.NaN, 0.5, 0.0, 1.0, -0.5});
        CandleSeries series = new CandleSeries(new double[5], new double[5], new double[5]);
        IndicatorSeries indicators = new IndicatorSeries(macd, new double[5]);

        assertFalse(test(RuleCondition.MACD_BULLISH_CROSSOVER, 0, series, indicators));
        assertFalse(test(RuleCondition.MACD_BULLISH_CROSSOVER, 1, series, indicators));
        assertFalse(test(RuleCondition.MACD_BULLISH_CROSSOVER, 2, series, indicators));
        assertTrue(test(RuleCondition.MACD_BULLISH_CROSSOVER, 3, series, indicators));
        assertFalse(test(RuleCondition.MACD_BULLISH_CROSSOVER, 4, series, indicators));

        assertFalse(test(RuleCondition.MACD_BEARISH_CROSSOVER, 2, series, indicators));
        assertTrue(test(RuleCondition.MACD_BEARISH_CROSSOVER, 4, series, indicators));

        assertTrue(test(RuleCondition.MACD_ABOVE_SIGNAL, 1, series, indicators));
        assertFalse(test(RuleCondition.MACD_ABOVE_SIGNAL, 2, series, indicators));
        assertTrue(test(RuleCondition.MACD_ABOVE_SIGNAL, 3, series, indicators));
        assertFalse(test(RuleCondition.MACD_ABOVE_SIGNAL, 4, series, indicators));

        assertFalse(test(RuleCondition.MACD_BELOW_SIGNAL, 1, series, indicators));
        assertTrue(test(RuleCondition.MACD_BELOW_SIGNAL, 4, series, indicators));

        assertTrue(test(RuleCondition.HISTOGRAM_POSITIVE, 1, series, indicators));
        assertFalse(test(RuleCondition.HISTOGRAM_POSITIVE, 2, series, indicators));
        assertTrue(test(RuleCondition.HISTOGRAM_POSITIVE, 3, series, indicators));
        assertFalse(test(RuleCondition.HISTOGRAM_POSITIVE, 4, series, indicators));

        assertFalse(test(RuleCondition.HISTOGRAM_NEGATIVE, 1, series, indicators));
        assertFalse(test(RuleCondition.HISTOGRAM_NEGATIVE, 2, series, indicators));
        assertTrue(test(RuleCondition.HISTOGRAM_NEGATIVE, 4, series, indicators));
    }

    @Test
    void sarConditions_holdWhereTheSeriesSaySo() {
        MacdResult macd = new MacdResult(new double[5], new double[5], new double[5]);
        CandleSeries series = new CandleSeries(new double[5], new double[5],
                new double[]{10.0, 10.0, 10.0, 10.0, 10.0});
        IndicatorSeries indicators = new IndicatorSeries(macd,
                new double[]{Double.NaN, 9.0, 11.0, 9.0, 12.0});

        assertTrue(test(RuleCondition.PRICE_ABOVE_SAR, 1, series, indicators));
        assertFalse(test(RuleCondition.PRICE_ABOVE_SAR, 2, series, indicators));
        assertTrue(test(RuleCondition.PRICE_ABOVE_SAR, 3, series, indicators));
        assertFalse(test(RuleCondition.PRICE_ABOVE_SAR, 4, series, indicators));

        assertFalse(test(RuleCondition.PRICE_BELOW_SAR, 1, series, indicators));
        assertTrue(test(RuleCondition.PRICE_BELOW_SAR, 2, series, indicators));
        assertTrue(test(RuleCondition.PRICE_BELOW_SAR, 4, series, indicators));

        assertFalse(test(RuleCondition.SAR_BULLISH_CROSS, 0, series, indicators));
        assertFalse(test(RuleCondition.SAR_BULLISH_CROSS, 1, series, indicators));
        assertFalse(test(RuleCondition.SAR_BULLISH_CROSS, 2, series, indicators));
        assertTrue(test(RuleCondition.SAR_BULLISH_CROSS, 3, series, indicators));
        assertFalse(test(RuleCondition.SAR_BULLISH_CROSS, 4, series, indicators));

        assertTrue(test(RuleCondition.SAR_BEARISH_CROSS, 2, series, indicators));
        assertTrue(test(RuleCondition.SAR_BEARISH_CROSS, 4, series, indicators));
        assertFalse(test(RuleCondition.SAR_BEARISH_CROSS, 3, series, indicators));
    }

    @Test
    void allRequiresEveryCondition_andAnyRequiresOne() {
        MacdResult macd = new MacdResult(
                new double[]{1.0, 1.0, 1.0, 1.0},
                new double[]{0.0, 2.0, 2.0, 0.0},
                new double[]{1.0, -1.0, -1.0, 1.0});
        CandleSeries series = new CandleSeries(new double[4], new double[4], new double[4]);
        IndicatorSeries indicators = new IndicatorSeries(macd, new double[4]);

        // Mutually exclusive pair: ALL can never hold, ANY holds on every bar where either does
        boolean[] all = StrategyEvaluator.evaluate(
                new RuleGroup(LogicalOperator.ALL,
                        List.of(RuleCondition.MACD_ABOVE_SIGNAL, RuleCondition.MACD_BELOW_SIGNAL)),
                series, indicators);
        boolean[] any = StrategyEvaluator.evaluate(
                new RuleGroup(LogicalOperator.ANY,
                        List.of(RuleCondition.MACD_ABOVE_SIGNAL, RuleCondition.MACD_BELOW_SIGNAL)),
                series, indicators);
        for (int i = 0; i < 4; i++) {
            assertFalse(all[i]);
            assertTrue(any[i]);
        }

        // Overlapping pair: ALL holds where both hold (bars 0 and 3), ANY wherever either does
        boolean[] overlappingAll = StrategyEvaluator.evaluate(
                new RuleGroup(LogicalOperator.ALL,
                        List.of(RuleCondition.MACD_ABOVE_SIGNAL, RuleCondition.HISTOGRAM_POSITIVE)),
                series, indicators);
        assertTrue(overlappingAll[0]);
        assertFalse(overlappingAll[1]);
        assertFalse(overlappingAll[2]);
        assertTrue(overlappingAll[3]);
    }

    @Test
    void handWorkedScenario_macdCrossoverEntryFiresAtTheComputedBar() {
        // Closes fall 10 -> 5 and rise 6 -> 13; MACD(3,5,2) touches the signal at index 5 and
        // crosses above it at index 6 (docs/rules.md)
        CandleSeries series = series(new double[]{10, 9, 8, 7, 6, 5, 6, 7, 8, 9, 10, 11, 12, 13});
        StrategyDefinition strategy = new StrategyDefinition(new MacdParameters(3, 5, 2),
                ParabolicSarParameters.defaults(),
                new RuleGroup(LogicalOperator.ALL, List.of(RuleCondition.MACD_BULLISH_CROSSOVER)),
                new RuleGroup(LogicalOperator.ALL, List.of(RuleCondition.MACD_BEARISH_CROSSOVER)));

        StrategySignals signals = StrategyEvaluator.evaluate(series, strategy);

        for (int i = 0; i < 6; i++) {
            assertFalse(signals.entry()[i], "entry must not fire at index " + i);
        }
        assertTrue(signals.entry()[6]);
        for (int i = 7; i < series.length(); i++) {
            assertFalse(signals.entry()[i], "entry must not fire at index " + i);
        }
        for (int i = 0; i < series.length(); i++) {
            assertFalse(signals.exit()[i], "the rising tail has no bearish crossover");
        }
    }

    @Test
    void entryOnMacdAndExitOnSar_theTwoSetsAreIndependent() {
        double[] closes = {10, 9, 8, 7, 6, 5, 6, 7, 8, 9, 10, 11, 12, 13, 12, 11, 10, 9, 8};
        CandleSeries series = series(closes);
        StrategyDefinition strategy = new StrategyDefinition(new MacdParameters(3, 5, 2),
                ParabolicSarParameters.defaults(),
                new RuleGroup(LogicalOperator.ALL, List.of(RuleCondition.MACD_BULLISH_CROSSOVER)),
                new RuleGroup(LogicalOperator.ALL, List.of(RuleCondition.PRICE_BELOW_SAR)));

        StrategySignals signals = StrategyEvaluator.evaluate(series, strategy);

        int firstEntry = firstTrue(signals.entry());
        assertEquals(6, firstEntry);

        // Raw signals are position-free: the falling start is already below the SAR
        assertTrue(signals.exit()[1]);

        // After the entry, the exit fires exactly when the close first drops below the SAR
        double[] sar = nl.jimkaplan.autotrader.strategy.indicator.ParabolicSar.calculate(
                series.highs(), series.lows(), 0.02, 0.2);
        int expected = -1;
        for (int i = firstEntry + 1; i < closes.length; i++) {
            if (closes[i] < sar[i]) {
                expected = i;
                break;
            }
        }
        int actual = -1;
        for (int i = firstEntry + 1; i < closes.length; i++) {
            if (signals.exit()[i]) {
                actual = i;
                break;
            }
        }
        assertEquals(expected, actual);
        assertTrue(actual > firstEntry, "the exit must come after the entry");
    }

    @Test
    void duringMacdWarmUp_macdConditionsNeverFire() {
        // Twenty bars are enough for the SAR but far too few for MACD(12,26,9): its series is NaN
        CandleSeries series = series(new double[]{10, 9, 8, 7, 6, 5, 6, 7, 8, 9,
                10, 11, 12, 13, 14, 15, 16, 17, 18, 19});
        StrategyDefinition strategy = new StrategyDefinition(MacdParameters.defaults(),
                ParabolicSarParameters.defaults(),
                new RuleGroup(LogicalOperator.ANY, List.of(
                        RuleCondition.MACD_BULLISH_CROSSOVER, RuleCondition.HISTOGRAM_POSITIVE,
                        RuleCondition.MACD_ABOVE_SIGNAL)),
                new RuleGroup(LogicalOperator.ANY, List.of(
                        RuleCondition.MACD_BEARISH_CROSSOVER, RuleCondition.HISTOGRAM_NEGATIVE,
                        RuleCondition.MACD_BELOW_SIGNAL)));

        StrategySignals signals = StrategyEvaluator.evaluate(series, strategy);

        for (int i = 0; i < series.length(); i++) {
            assertFalse(signals.entry()[i], "no entry may fire while MACD is warming up");
            assertFalse(signals.exit()[i], "no exit may fire while MACD is warming up");
        }
    }

    @Test
    void withAnEmptySeries_producesEmptySignals() {
        StrategyDefinition strategy = new StrategyDefinition(MacdParameters.defaults(),
                ParabolicSarParameters.defaults(),
                new RuleGroup(LogicalOperator.ALL, List.of(RuleCondition.HISTOGRAM_POSITIVE)),
                new RuleGroup(LogicalOperator.ALL, List.of(RuleCondition.HISTOGRAM_NEGATIVE)));

        StrategySignals signals = StrategyEvaluator.evaluate(new CandleSeries(new double[0],
                new double[0], new double[0]), strategy);

        assertEquals(0, signals.entry().length);
        assertEquals(0, signals.exit().length);
    }

    private static boolean test(RuleCondition condition, int index, CandleSeries series,
                                IndicatorSeries indicators) {
        return StrategyEvaluator.test(condition, index, series, indicators);
    }

    private static CandleSeries series(double[] closes) {
        double[] highs = new double[closes.length];
        double[] lows = new double[closes.length];
        for (int i = 0; i < closes.length; i++) {
            highs[i] = closes[i] + 1;
            lows[i] = closes[i] - 1;
        }
        return new CandleSeries(highs, lows, closes);
    }

    private static int firstTrue(boolean[] values) {
        for (int i = 0; i < values.length; i++) {
            if (values[i]) {
                return i;
            }
        }
        return -1;
    }
}
