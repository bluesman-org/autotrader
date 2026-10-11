package nl.jimkaplan.autotrader.strategy.rule;

import nl.jimkaplan.autotrader.strategy.indicator.MacdResult;

import java.util.Objects;

/**
 * The indicator series a strategy evaluates on, aligned with the candle series.
 *
 * <p>All four series (MACD line, signal, histogram and SAR) must have the same length — the
 * evaluator indexes them by bar, so misaligned series would produce wrong signals.</p>
 *
 * @param macd The MACD series
 * @param sar  The Parabolic SAR series
 * @throws IllegalArgumentException if the series are not aligned
 */
public record IndicatorSeries(MacdResult macd, double[] sar) {

    public IndicatorSeries {
        Objects.requireNonNull(macd, "macd must not be null");
        Objects.requireNonNull(sar, "sar must not be null");
        Objects.requireNonNull(macd.macd(), "macd.macd must not be null");
        Objects.requireNonNull(macd.signal(), "macd.signal must not be null");
        Objects.requireNonNull(macd.histogram(), "macd.histogram must not be null");
        if (macd.macd().length != macd.signal().length
                || macd.macd().length != macd.histogram().length
                || macd.macd().length != sar.length) {
            throw new IllegalArgumentException("the indicator series must be aligned");
        }
    }

    /**
     * The number of bars the series covers.
     *
     * @return The number of bars
     */
    public int length() {
        return sar.length;
    }
}
