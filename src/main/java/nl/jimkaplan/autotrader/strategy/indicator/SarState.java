package nl.jimkaplan.autotrader.strategy.indicator;

/**
 * The Parabolic SAR recursion carried from bar to bar: the batch calculation and the live engine
 * drive the same state, so a streamed value is the batch value by construction.
 *
 * <p>Wilder's definition with the bootstrap and clamping conventions recorded in
 * {@code docs/indicators.md}. Before the second bar the value is {@link Double#NaN}.</p>
 */
public final class SarState {

    private final double step;
    private final double maximum;

    private double af;
    private boolean isLong;
    private double sar;
    private double ep;
    private double prevLow;
    private double prevHigh;
    private double newLow;
    private double newHigh;
    private int seen;
    private boolean valid;
    private double value = Double.NaN;

    /**
     * @param acceleration Acceleration factor: finite, at least 0; capped to {@code maximum} before use
     * @param maximum      Maximum acceleration factor: finite, at least 0
     * @throws IllegalArgumentException if a parameter is not finite or negative
     */
    public SarState(double acceleration, double maximum) {
        ParabolicSar.validateParameters(acceleration, maximum);
        this.step = Math.min(acceleration, maximum);
        this.maximum = maximum;
        this.af = step;
    }

    /**
     * Feed one bar.
     *
     * @param high The bar's high
     * @param low  The bar's low
     */
    public void accept(double high, double low) {
        seen++;
        if (seen == 1) {
            newHigh = high;
            newLow = low;
            return;
        }
        if (seen == 2) {
            bootstrap(high, low);
            prevHigh = high;
            prevLow = low;
            newHigh = high;
            newLow = low;
            advance();
            valid = true;
            return;
        }
        prevLow = newLow;
        prevHigh = newHigh;
        newLow = low;
        newHigh = high;
        advance();
    }

    /**
     * @return The SAR at the last bar fed, or {@link Double#NaN} before the second bar
     */
    public double value() {
        return valid ? value : Double.NaN;
    }

    private void bootstrap(double high, double low) {
        // Initial direction from the directional movement between the first two bars: a downward
        // move that is positive and not exceeded by the upward move starts the series short; ties
        // default to long.
        double minusDm = newLow - low;
        if (minusDm < 0) {
            minusDm = 0;
        }
        if (high - newHigh > minusDm) {
            minusDm = 0;
        }
        isLong = minusDm <= 0;
        if (isLong) {
            ep = high;
            sar = newLow;
        } else {
            ep = low;
            sar = newHigh;
        }
    }

    private void advance() {
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
                value = sar;
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
                value = sar;
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
            value = sar;
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
            value = sar;
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
}
