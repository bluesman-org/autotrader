package nl.jimkaplan.autotrader.strategy.indicator;

/**
 * The three MACD series, aligned with the input bars: entries before the first valid bar are
 * NaN.
 *
 * @param macd      The MACD line
 * @param signal    The signal line
 * @param histogram The difference between the MACD line and the signal line
 */
public record MacdResult(double[] macd, double[] signal, double[] histogram) {
}
