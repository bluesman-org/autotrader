package nl.jimkaplan.autotrader.strategy.indicator;

import java.util.Arrays;

/**
 * Parabolic SAR (stop and reverse) on bar highs and lows.
 *
 * <p>Follows Wilder's definition with the bootstrap and clamping conventions recorded in
 * {@code docs/indicators.md} (the conventions the reference implementation uses), so the output
 * matches it; the equivalence is pinned by the frozen fixtures under
 * {@code src/test/resources/indicators}. The result is aligned with the input bars: the first
 * bar has no value, and an input with fewer than two bars yields an all-NaN result.</p>
 */
public final class ParabolicSar {

    /**
     * The conventional acceleration factor.
     */
    public static final double DEFAULT_ACCELERATION = 0.02;

    /**
     * The conventional maximum acceleration factor.
     */
    public static final double DEFAULT_MAXIMUM = 0.2;

    private ParabolicSar() {
    }

    /**
     * Calculate the Parabolic SAR values.
     *
     * @param highs        Bar highs, oldest first
     * @param lows         Bar lows, oldest first
     * @param acceleration Acceleration factor: finite, at least 0; capped to {@code maximum} before use
     * @param maximum      Maximum acceleration factor: finite, at least 0
     * @return The SAR, aligned with the bars
     * @throws IllegalArgumentException if an argument is out of range or the arrays differ in length
     */
    public static double[] calculate(double[] highs, double[] lows, double acceleration, double maximum) {
        if (highs == null) {
            throw new IllegalArgumentException("highs must not be null");
        }
        if (lows == null) {
            throw new IllegalArgumentException("lows must not be null");
        }
        if (highs.length != lows.length) {
            throw new IllegalArgumentException("highs and lows must have the same length");
        }
        validateParameters(acceleration, maximum);

        double[] sarValues = new double[highs.length];
        Arrays.fill(sarValues, Double.NaN);

        // One definition of the recursion: the batch walk and the live engine's stream both drive
        // this state, so the two cannot drift apart.
        SarState state = new SarState(acceleration, maximum);
        for (int i = 0; i < highs.length; i++) {
            state.accept(highs[i], lows[i]);
            sarValues[i] = state.value();
        }
        return sarValues;
    }

    /**
     * Validate SAR parameters, so configuration can be checked without calculating.
     *
     * @param acceleration Acceleration factor: finite, at least 0
     * @param maximum      Maximum acceleration factor: finite, at least 0
     * @throws IllegalArgumentException if a parameter is not finite or negative
     */
    public static void validateParameters(double acceleration, double maximum) {
        if (!Double.isFinite(acceleration) || acceleration < 0) {
            throw new IllegalArgumentException("acceleration must be finite and not negative");
        }
        if (!Double.isFinite(maximum) || maximum < 0) {
            throw new IllegalArgumentException("maximum must be finite and not negative");
        }
    }
}
