package nl.jimkaplan.autotrader.strategy.rule;

import nl.jimkaplan.autotrader.strategy.indicator.ParabolicSar;

/**
 * Parabolic SAR parameters shared by the backtester, the UI and the live engine.
 *
 * @param acceleration Acceleration factor: finite, at least 0
 * @param maximum      Maximum acceleration factor: finite, at least 0
 * @throws IllegalArgumentException if a parameter is not finite or negative
 */
public record ParabolicSarParameters(double acceleration, double maximum) {

    public ParabolicSarParameters {
        ParabolicSar.validateParameters(acceleration, maximum);
    }

    /**
     * The conventional 0.02/0.2 parameters.
     *
     * @return The default parameters
     */
    public static ParabolicSarParameters defaults() {
        return new ParabolicSarParameters(ParabolicSar.DEFAULT_ACCELERATION,
                ParabolicSar.DEFAULT_MAXIMUM);
    }
}
