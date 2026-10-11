package nl.jimkaplan.autotrader.strategy.rule;

import nl.jimkaplan.autotrader.strategy.model.document.Candle;

import java.util.List;
import java.util.Objects;

/**
 * The parallel series the indicators and rules evaluate on, aligned with the candles, oldest
 * first.
 *
 * @param highs  Bar highs
 * @param lows   Bar lows
 * @param closes Bar closes
 * @throws IllegalArgumentException if the arrays differ in length
 */
public record CandleSeries(double[] highs, double[] lows, double[] closes) {

    public CandleSeries {
        Objects.requireNonNull(highs, "highs must not be null");
        Objects.requireNonNull(lows, "lows must not be null");
        Objects.requireNonNull(closes, "closes must not be null");
        if (highs.length != lows.length || highs.length != closes.length) {
            throw new IllegalArgumentException("highs, lows and closes must have the same length");
        }
    }

    /**
     * Extract the series from stored candles, oldest first.
     *
     * <p>The candles must be strictly ordered by open time: an inverted or duplicated series would
     * silently invert every indicator window and signal, so it is rejected here.</p>
     *
     * @param candles The candles, oldest first, with no duplicate open times
     * @return The extracted series
     * @throws IllegalArgumentException if the candles are not strictly ordered by open time or a
     *                                  candle is missing its OHLC values
     */
    public static CandleSeries of(List<Candle> candles) {
        Objects.requireNonNull(candles, "candles must not be null");
        double[] highs = new double[candles.size()];
        double[] lows = new double[candles.size()];
        double[] closes = new double[candles.size()];
        long previousOpenTime = Long.MIN_VALUE;
        for (int i = 0; i < candles.size(); i++) {
            Candle candle = candles.get(i);
            if (candle.getOpenTime() <= previousOpenTime) {
                throw new IllegalArgumentException("candles must be ordered oldest first (index " + i + ")");
            }
            if (candle.getHigh() == null || candle.getLow() == null || candle.getClose() == null) {
                throw new IllegalArgumentException("candle at index " + i + " is missing OHLC values");
            }
            previousOpenTime = candle.getOpenTime();
            highs[i] = candle.getHigh().doubleValue();
            lows[i] = candle.getLow().doubleValue();
            closes[i] = candle.getClose().doubleValue();
        }
        return new CandleSeries(highs, lows, closes);
    }

    /**
     * The number of bars in the series.
     *
     * @return The number of bars
     */
    public int length() {
        return closes.length;
    }
}
