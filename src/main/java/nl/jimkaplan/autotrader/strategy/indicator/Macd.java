package nl.jimkaplan.autotrader.strategy.indicator;

import java.util.Arrays;

/**
 * MACD (moving average convergence/divergence) on closing prices.
 *
 * <p>Follows the published definition with the bootstrap conventions recorded in
 * {@code docs/indicators.md}, mirroring the reference arithmetic operation for operation so the
 * output matches it bit for bit; the equivalence is pinned by the frozen fixtures under
 * {@code src/test/resources/indicators}. Output arrays are aligned with the input bars: entries
 * before the first valid bar are NaN, and an input too short for a single value yields an
 * all-NaN result.</p>
 */
public final class Macd {

    /**
     * The conventional fast EMA period.
     */
    public static final int DEFAULT_FAST_PERIOD = 12;

    /**
     * The conventional slow EMA period.
     */
    public static final int DEFAULT_SLOW_PERIOD = 26;

    /**
     * The conventional signal EMA period.
     */
    public static final int DEFAULT_SIGNAL_PERIOD = 9;

    /**
     * Upper bound for all periods, mirroring the reference implementation's bound.
     */
    public static final int MAX_PERIOD = 100_000;

    private Macd() {
    }

    /**
     * Calculate the MACD line, signal line and histogram.
     *
     * @param closes       Closing prices, oldest first
     * @param fastPeriod   Fast EMA period, between 1 and {@link #MAX_PERIOD} and at most {@code slowPeriod}
     * @param slowPeriod   Slow EMA period, between {@code fastPeriod} and {@link #MAX_PERIOD}
     * @param signalPeriod Signal EMA period, between 1 and {@link #MAX_PERIOD}
     * @return The three series, aligned with the input
     * @throws IllegalArgumentException if an argument is out of range
     */
    public static MacdResult calculate(double[] closes, int fastPeriod, int slowPeriod, int signalPeriod) {
        if (closes == null) {
            throw new IllegalArgumentException("closes must not be null");
        }
        validatePeriods(fastPeriod, slowPeriod, signalPeriod);

        int length = closes.length;
        double[] macd = new double[length];
        double[] signal = new double[length];
        double[] histogram = new double[length];
        Arrays.fill(macd, Double.NaN);
        Arrays.fill(signal, Double.NaN);
        Arrays.fill(histogram, Double.NaN);

        // Widened so the sum cannot wrap even at the maximum periods
        long minimumLength = (long) slowPeriod + signalPeriod - 1;
        if (length < minimumLength) {
            return new MacdResult(macd, signal, histogram);
        }

        double fastK = 2.0 / (fastPeriod + 1);
        double slowK = 2.0 / (slowPeriod + 1);
        double signalK = 2.0 / (signalPeriod + 1);

        // Each price EMA is seeded with the average of its own first window, placed so the
        // windows end on the slow EMA's first valid bar (this is the reference's bootstrap).
        double prevSlow = average(closes, 0, slowPeriod);
        double prevFast = average(closes, slowPeriod - fastPeriod, fastPeriod);

        int today = slowPeriod;
        double macdValue = prevFast - prevSlow;
        double prevSignal = macdValue;
        for (int i = 0; i < signalPeriod - 1; i++) {
            double value = closes[today++];
            prevFast = Math.fma(value - prevFast, fastK, prevFast);
            prevSlow = Math.fma(value - prevSlow, slowK, prevSlow);
            macdValue = prevFast - prevSlow;
            prevSignal += macdValue;
        }
        prevSignal /= signalPeriod;

        int outputIndex = slowPeriod + signalPeriod - 2;
        macd[outputIndex] = macdValue;
        signal[outputIndex] = prevSignal;
        histogram[outputIndex] = macdValue - prevSignal;
        outputIndex++;

        while (today < length) {
            double value = closes[today++];
            prevFast = Math.fma(value - prevFast, fastK, prevFast);
            prevSlow = Math.fma(value - prevSlow, slowK, prevSlow);
            macdValue = prevFast - prevSlow;
            prevSignal = (signalPeriod == 1)
                    ? macdValue
                    : Math.fma(macdValue - prevSignal, signalK, prevSignal);
            macd[outputIndex] = macdValue;
            signal[outputIndex] = prevSignal;
            histogram[outputIndex] = macdValue - prevSignal;
            outputIndex++;
        }
        return new MacdResult(macd, signal, histogram);
    }

    /**
     * Validate MACD periods, so configuration can be checked without calculating.
     *
     * @param fastPeriod   Fast EMA period, between 1 and {@link #MAX_PERIOD}
     * @param slowPeriod   Slow EMA period, between {@code fastPeriod} and {@link #MAX_PERIOD}
     * @param signalPeriod Signal EMA period, between 1 and {@link #MAX_PERIOD}
     * @throws IllegalArgumentException if a period is out of range
     */
    public static void validatePeriods(int fastPeriod, int slowPeriod, int signalPeriod) {
        if (fastPeriod < 1 || fastPeriod > MAX_PERIOD) {
            throw new IllegalArgumentException("fastPeriod must be between 1 and " + MAX_PERIOD);
        }
        if (slowPeriod < fastPeriod || slowPeriod > MAX_PERIOD) {
            throw new IllegalArgumentException("slowPeriod must be between fastPeriod and " + MAX_PERIOD);
        }
        if (signalPeriod < 1 || signalPeriod > MAX_PERIOD) {
            throw new IllegalArgumentException("signalPeriod must be between 1 and " + MAX_PERIOD);
        }
    }

    private static double average(double[] values, int from, int count) {
        double sum = 0.0;
        for (int i = from; i < from + count; i++) {
            sum += values[i];
        }
        return sum / count;
    }
}
