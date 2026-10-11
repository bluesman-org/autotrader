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
        if (!Double.isFinite(acceleration) || acceleration < 0) {
            throw new IllegalArgumentException("acceleration must be finite and not negative");
        }
        if (!Double.isFinite(maximum) || maximum < 0) {
            throw new IllegalArgumentException("maximum must be finite and not negative");
        }

        double[] sarValues = new double[highs.length];
        Arrays.fill(sarValues, Double.NaN);
        if (highs.length < 2) {
            return sarValues;
        }

        double step = Math.min(acceleration, maximum);
        double af = step;

        // Initial direction from the directional movement between the first two bars: a
        // downward move that is positive and not exceeded by the upward move starts the series
        // short; ties default to long.
        double minusDm = lows[0] - lows[1];
        if (minusDm < 0) {
            minusDm = 0;
        }
        if (highs[1] - highs[0] > minusDm) {
            minusDm = 0;
        }
        boolean isLong = minusDm <= 0;

        double newHigh = highs[0];
        double newLow = lows[0];
        double ep;
        double sar;
        if (isLong) {
            ep = highs[1];
            sar = newLow;
        } else {
            ep = lows[1];
            sar = newHigh;
        }
        // The first iteration treats the second bar as both the previous and the current bar.
        newLow = lows[1];
        newHigh = highs[1];

        int today = 1;
        while (today < highs.length) {
            double prevLow = newLow;
            double prevHigh = newHigh;
            newLow = lows[today];
            newHigh = highs[today];
            today++;

            if (isLong) {
                if (newLow <= sar) {
                    // Reverse to short: the override SAR is wrapped outside the current range.
                    isLong = false;
                    sar = ep;
                    if (sar < prevHigh) {
                        sar = prevHigh;
                    }
                    if (sar < newHigh) {
                        sar = newHigh;
                    }
                    sarValues[today - 1] = sar;
                    af = step;
                    ep = newLow;
                    sar = Math.fma(af, ep - sar, sar);
                    if (sar < prevHigh) {
                        sar = prevHigh;
                    }
                    if (sar < newHigh) {
                        sar = newHigh;
                    }
                } else {
                    sarValues[today - 1] = sar;
                    if (newHigh > ep) {
                        ep = newHigh;
                        af = Math.min(af + step, maximum);
                    }
                    sar = Math.fma(af, ep - sar, sar);
                    if (sar > prevLow) {
                        sar = prevLow;
                    }
                    if (sar > newLow) {
                        sar = newLow;
                    }
                }
            } else if (newHigh >= sar) {
                // Reverse to long: mirror of the long-to-short case.
                isLong = true;
                sar = ep;
                if (sar > prevLow) {
                    sar = prevLow;
                }
                if (sar > newLow) {
                    sar = newLow;
                }
                sarValues[today - 1] = sar;
                af = step;
                ep = newHigh;
                sar = Math.fma(af, ep - sar, sar);
                if (sar > prevLow) {
                    sar = prevLow;
                }
                if (sar > newLow) {
                    sar = newLow;
                }
            } else {
                sarValues[today - 1] = sar;
                if (newLow < ep) {
                    ep = newLow;
                    af = Math.min(af + step, maximum);
                }
                sar = Math.fma(af, ep - sar, sar);
                if (sar < prevHigh) {
                    sar = prevHigh;
                }
                if (sar < newHigh) {
                    sar = newHigh;
                }
            }
        }
        return sarValues;
    }
}
