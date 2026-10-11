package nl.jimkaplan.autotrader.strategy.rule;

/**
 * The conditions a rule can be built from.
 *
 * <p>Conditions are evaluated on closed candles only. A condition whose inputs are not yet
 * available — the indicator is still in its warm-up, or there is no previous bar for a
 * crossover — is {@code false}, so it never fires a signal.</p>
 *
 * <p>Crossovers compare the current bar with the previous one: a bullish crossover means the
 * left series was at or below the right series and is now above it.</p>
 */
public enum RuleCondition {

    /**
     * The MACD line crosses from at or below the signal line to above it.
     */
    MACD_BULLISH_CROSSOVER,

    /**
     * The MACD line crosses from at or above the signal line to below it.
     */
    MACD_BEARISH_CROSSOVER,

    /**
     * The MACD line is above the signal line.
     */
    MACD_ABOVE_SIGNAL,

    /**
     * The MACD line is below the signal line.
     */
    MACD_BELOW_SIGNAL,

    /**
     * The histogram (MACD line minus signal line) is positive.
     */
    HISTOGRAM_POSITIVE,

    /**
     * The histogram is negative.
     */
    HISTOGRAM_NEGATIVE,

    /**
     * The close is above the Parabolic SAR.
     */
    PRICE_ABOVE_SAR,

    /**
     * The close is below the Parabolic SAR.
     */
    PRICE_BELOW_SAR,

    /**
     * The close crosses from at or below the SAR to above it.
     */
    SAR_BULLISH_CROSS,

    /**
     * The close crosses from at or above the SAR to below it.
     */
    SAR_BEARISH_CROSS
}
