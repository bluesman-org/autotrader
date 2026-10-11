package nl.jimkaplan.autotrader.strategy.rule;

import nl.jimkaplan.autotrader.strategy.model.document.Candle;

import java.util.List;
import java.util.Objects;

/**
 * The parallel series the indicators, rules and the backtester evaluate on, aligned with the
 * candles, oldest first.
 *
 * @param opens  Bar opens
 * @param highs  Bar highs
 * @param lows   Bar lows
 * @param closes Bar closes
 * @param times  Bar open times, epoch milliseconds
 * @throws IllegalArgumentException if the series differ in length
 */
public record CandleSeries(double[] opens, double[] highs, double[] lows, double[] closes, long[] times) {

    public CandleSeries {
        Objects.requireNonNull(opens, "opens must not be null");
        Objects.requireNonNull(highs, "highs must not be null");
        Objects.requireNonNull(lows, "lows must not be null");
        Objects.requireNonNull(closes, "closes must not be null");
        Objects.requireNonNull(times, "times must not be null");
        if (opens.length != highs.length || opens.length != lows.length
                || opens.length != closes.length || opens.length != times.length) {
            throw new IllegalArgumentException("all series must have the same length");
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
        int length = candles.size();
        double[] opens = new double[length];
        double[] highs = new double[length];
        double[] lows = new double[length];
        double[] closes = new double[length];
        long[] times = new long[length];
        long previousOpenTime = Long.MIN_VALUE;
        for (int i = 0; i < length; i++) {
            Candle candle = candles.get(i);
            if (candle.getOpenTime() <= previousOpenTime) {
                throw new IllegalArgumentException("candles must be ordered oldest first (index " + i + ")");
            }
            if (candle.getOpen() == null || candle.getHigh() == null
                    || candle.getLow() == null || candle.getClose() == null) {
                throw new IllegalArgumentException("candle at index " + i + " is missing OHLC values");
            }
            previousOpenTime = candle.getOpenTime();
            opens[i] = candle.getOpen().doubleValue();
            highs[i] = candle.getHigh().doubleValue();
            lows[i] = candle.getLow().doubleValue();
            closes[i] = candle.getClose().doubleValue();
            times[i] = candle.getOpenTime();
        }
        return new CandleSeries(opens, highs, lows, closes, times);
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
