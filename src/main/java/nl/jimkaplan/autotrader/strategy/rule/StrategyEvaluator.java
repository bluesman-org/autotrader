package nl.jimkaplan.autotrader.strategy.rule;

import nl.jimkaplan.autotrader.strategy.indicator.Macd;
import nl.jimkaplan.autotrader.strategy.indicator.MacdResult;
import nl.jimkaplan.autotrader.strategy.indicator.ParabolicSar;

/**
 * Evaluates strategies on closed candles.
 *
 * <p>The signals are raw: "the entry rules fired on this bar" and "the exit rules fired on this
 * bar". The position logic — a single position at a time, fills at the next candle's open, fees
 * and skipped signals — belongs to the backtester and the live engine, which consume these
 * signals.</p>
 */
public final class StrategyEvaluator {

    private StrategyEvaluator() {
    }

    /**
     * Compute the indicator series for the strategy's parameters.
     *
     * @param series   The candle series
     * @param strategy The strategy
     * @return The indicator series, aligned with the candles
     */
    public static IndicatorSeries indicators(CandleSeries series, StrategyDefinition strategy) {
        MacdParameters macd = strategy.macd();
        ParabolicSarParameters sar = strategy.sar();
        return new IndicatorSeries(
                Macd.calculate(series.closes(), macd.fastPeriod(), macd.slowPeriod(), macd.signalPeriod()),
                ParabolicSar.calculate(series.highs(), series.lows(), sar.acceleration(), sar.maximum()));
    }

    /**
     * Evaluate the strategy, bar by bar.
     *
     * @param series   The candle series
     * @param strategy The strategy
     * @return The entry and exit signals, aligned with the candles
     */
    public static StrategySignals evaluate(CandleSeries series, StrategyDefinition strategy) {
        IndicatorSeries indicators = indicators(series, strategy);
        return new StrategySignals(
                evaluate(strategy.entryRules(), series, indicators),
                evaluate(strategy.exitRules(), series, indicators));
    }

    /**
     * Evaluate one rule group, bar by bar.
     *
     * @param rules      The rule group
     * @param series     The candle series
     * @param indicators The indicator series
     * @return The signals, aligned with the candles
     */
    public static boolean[] evaluate(RuleGroup rules, CandleSeries series, IndicatorSeries indicators) {
        boolean[] signals = new boolean[series.length()];
        for (int i = 0; i < signals.length; i++) {
            boolean matches = rules.operator() == LogicalOperator.ALL;
            for (RuleCondition condition : rules.conditions()) {
                boolean holds = test(condition, i, series, indicators);
                matches = (rules.operator() == LogicalOperator.ALL) ? matches && holds : matches || holds;
            }
            signals[i] = matches;
        }
        return signals;
    }

    static boolean test(RuleCondition condition, int index, CandleSeries series, IndicatorSeries indicators) {
        MacdResult macd = indicators.macd();
        double[] sar = indicators.sar();
        double[] closes = series.closes();
        return switch (condition) {
            case MACD_BULLISH_CROSSOVER -> crossUp(macd.macd(), macd.signal(), index);
            case MACD_BEARISH_CROSSOVER -> crossDown(macd.macd(), macd.signal(), index);
            case MACD_ABOVE_SIGNAL -> greater(macd.macd(), macd.signal(), index);
            case MACD_BELOW_SIGNAL -> greater(macd.signal(), macd.macd(), index);
            case HISTOGRAM_POSITIVE -> positive(macd.histogram(), index);
            case HISTOGRAM_NEGATIVE -> negative(macd.histogram(), index);
            case PRICE_ABOVE_SAR -> greater(closes, sar, index);
            case PRICE_BELOW_SAR -> greater(sar, closes, index);
            case SAR_BULLISH_CROSS -> crossUp(closes, sar, index);
            case SAR_BEARISH_CROSS -> crossDown(closes, sar, index);
        };
    }

    private static boolean positive(double[] values, int index) {
        return !Double.isNaN(values[index]) && values[index] > 0;
    }

    private static boolean negative(double[] values, int index) {
        return !Double.isNaN(values[index]) && values[index] < 0;
    }

    private static boolean greater(double[] left, double[] right, int index) {
        return !Double.isNaN(left[index]) && !Double.isNaN(right[index]) && left[index] > right[index];
    }

    private static boolean crossUp(double[] left, double[] right, int index) {
        if (index < 1) {
            return false;
        }
        double leftPrevious = left[index - 1];
        double rightPrevious = right[index - 1];
        double leftCurrent = left[index];
        double rightCurrent = right[index];
        if (Double.isNaN(leftPrevious) || Double.isNaN(rightPrevious)
                || Double.isNaN(leftCurrent) || Double.isNaN(rightCurrent)) {
            return false;
        }
        return leftPrevious <= rightPrevious && leftCurrent > rightCurrent;
    }

    private static boolean crossDown(double[] left, double[] right, int index) {
        if (index < 1) {
            return false;
        }
        double leftPrevious = left[index - 1];
        double rightPrevious = right[index - 1];
        double leftCurrent = left[index];
        double rightCurrent = right[index];
        if (Double.isNaN(leftPrevious) || Double.isNaN(rightPrevious)
                || Double.isNaN(leftCurrent) || Double.isNaN(rightCurrent)) {
            return false;
        }
        return leftPrevious >= rightPrevious && leftCurrent < rightCurrent;
    }
}
