package nl.jimkaplan.autotrader.strategy.rule;

import nl.jimkaplan.autotrader.strategy.indicator.Macd;

/**
 * MACD parameters shared by the backtester, the UI and the live engine.
 *
 * @param fastPeriod   Fast EMA period
 * @param slowPeriod   Slow EMA period
 * @param signalPeriod Signal EMA period
 * @throws IllegalArgumentException if a period is out of range
 */
public record MacdParameters(int fastPeriod, int slowPeriod, int signalPeriod) {

    public MacdParameters {
        Macd.validatePeriods(fastPeriod, slowPeriod, signalPeriod);
    }

    /**
     * The conventional 12/26/9 parameters.
     *
     * @return The default parameters
     */
    public static MacdParameters defaults() {
        return new MacdParameters(Macd.DEFAULT_FAST_PERIOD, Macd.DEFAULT_SLOW_PERIOD,
                Macd.DEFAULT_SIGNAL_PERIOD);
    }
}
