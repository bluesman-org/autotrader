package nl.jimkaplan.autotrader.strategy.indicator;

/**
 * The MACD recursion carried from bar to bar: the batch calculation and the live engine drive the
 * same state, so a streamed value is the batch value by construction rather than by a parallel
 * implementation.
 *
 * <p>The bootstrap follows the published conventions recorded in {@code docs/indicators.md},
 * mirroring the reference arithmetic operation for operation. Before the first valid bar every
 * value is {@link Double#NaN}.</p>
 */
public final class MacdState {

    private final int fastPeriod;
    private final int slowPeriod;
    private final int signalPeriod;
    private final double fastK;
    private final double slowK;
    private final double signalK;

    /**
     * The first {@code slowPeriod} closes, kept to seed the two price EMAs.
     */
    private final double[] seed;

    private int seedCount;
    private double prevFast;
    private double prevSlow;
    private double prevSignal;
    private int remainingSignalSeed;
    private boolean valid;
    private double macd = Double.NaN;
    private double signal = Double.NaN;
    private double histogram = Double.NaN;

    /**
     * @param fastPeriod   Fast EMA period, between 1 and {@link Macd#MAX_PERIOD} and at most {@code slowPeriod}
     * @param slowPeriod   Slow EMA period, between {@code fastPeriod} and {@link Macd#MAX_PERIOD}
     * @param signalPeriod Signal EMA period, between 1 and {@link Macd#MAX_PERIOD}
     * @throws IllegalArgumentException if a period is out of range
     */
    public MacdState(int fastPeriod, int slowPeriod, int signalPeriod) {
        Macd.validatePeriods(fastPeriod, slowPeriod, signalPeriod);
        this.fastPeriod = fastPeriod;
        this.slowPeriod = slowPeriod;
        this.signalPeriod = signalPeriod;
        this.fastK = 2.0 / (fastPeriod + 1);
        this.slowK = 2.0 / (slowPeriod + 1);
        this.signalK = 2.0 / (signalPeriod + 1);
        this.seed = new double[slowPeriod];
    }

    /**
     * Feed one close.
     *
     * @param close The bar's close
     */
    public void accept(double close) {
        if (valid) {
            step(close);
            prevSignal = signalPeriod == 1
                    ? macd
                    : Math.fma(macd - prevSignal, signalK, prevSignal);
            emit();
            return;
        }
        if (seedCount < slowPeriod) {
            seed[seedCount++] = close;
            if (seedCount == slowPeriod) {
                bootstrap();
            }
            return;
        }
        // The signal line's seed is the MACD line's simple average over its first signalPeriod
        // bars, taken while the price EMAs keep moving.
        step(close);
        prevSignal += macd;
        remainingSignalSeed--;
        if (remainingSignalSeed == 0) {
            prevSignal /= signalPeriod;
            valid = true;
            emit();
        }
    }

    /**
     * @return The MACD line at the last bar, or {@link Double#NaN} before the first valid bar
     */
    public double macd() {
        return valid ? macd : Double.NaN;
    }

    /**
     * @return The signal line at the last bar, or {@link Double#NaN} before the first valid bar
     */
    public double signal() {
        return valid ? signal : Double.NaN;
    }

    /**
     * @return The histogram at the last bar, or {@link Double#NaN} before the first valid bar
     */
    public double histogram() {
        return valid ? histogram : Double.NaN;
    }

    private void bootstrap() {
        // Each price EMA is seeded with the average of its own first window, placed so the
        // windows end on the slow EMA's first valid bar (this is the reference's bootstrap).
        double sumSlow = 0.0;
        for (int i = 0; i < slowPeriod; i++) {
            sumSlow += seed[i];
        }
        prevSlow = sumSlow / slowPeriod;
        double sumFast = 0.0;
        for (int i = slowPeriod - fastPeriod; i < slowPeriod; i++) {
            sumFast += seed[i];
        }
        prevFast = sumFast / fastPeriod;
        macd = prevFast - prevSlow;
        prevSignal = macd;
        remainingSignalSeed = signalPeriod - 1;
        if (remainingSignalSeed == 0) {
            prevSignal /= signalPeriod;
            valid = true;
            emit();
        }
    }

    private void step(double close) {
        prevFast = Math.fma(close - prevFast, fastK, prevFast);
        prevSlow = Math.fma(close - prevSlow, slowK, prevSlow);
        macd = prevFast - prevSlow;
    }

    private void emit() {
        signal = prevSignal;
        histogram = macd - prevSignal;
    }
}
