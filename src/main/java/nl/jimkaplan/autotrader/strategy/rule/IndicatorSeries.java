package nl.jimkaplan.autotrader.strategy.rule;

import nl.jimkaplan.autotrader.strategy.indicator.MacdResult;

/**
 * The indicator series a strategy evaluates on, aligned with the candle series.
 *
 * @param macd The MACD series
 * @param sar  The Parabolic SAR series
 */
public record IndicatorSeries(MacdResult macd, double[] sar) {
}
