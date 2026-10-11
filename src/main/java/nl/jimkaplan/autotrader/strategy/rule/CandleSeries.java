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
     * @param candles The candles
     * @return The extracted series
     */
    public static CandleSeries of(List<Candle> candles) {
        Objects.requireNonNull(candles, "candles must not be null");
        double[] highs = new double[candles.size()];
        double[] lows = new double[candles.size()];
        double[] closes = new double[candles.size()];
        for (int i = 0; i < candles.size(); i++) {
            Candle candle = candles.get(i);
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
