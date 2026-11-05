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

        // One definition of the recursion: the batch walk and the live engine's stream both drive
        // this state, so the two cannot drift apart.
        MacdState state = new MacdState(fastPeriod, slowPeriod, signalPeriod);
        for (int i = 0; i < length; i++) {
            state.accept(closes[i]);
            macd[i] = state.macd();
            signal[i] = state.signal();
            histogram[i] = state.histogram();
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

}
