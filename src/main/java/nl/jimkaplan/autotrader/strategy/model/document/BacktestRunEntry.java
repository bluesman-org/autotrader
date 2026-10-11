package nl.jimkaplan.autotrader.strategy.model.document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * One ranked combination inside a stored backtest run, flattened so the document maps without
 * custom converters.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BacktestRunEntry {

    private int fastPeriod;
    private int slowPeriod;
    private int signalPeriod;
    private double acceleration;
    private double maximum;

    /**
     * The entry rule set's operator name, e.g. "ALL".
     */
    private String entryOperator;

    /**
     * The entry conditions' names, e.g. ["MACD_BULLISH_CROSSOVER"].
     */
    private List<String> entryConditions;

    private String exitOperator;

    private List<String> exitConditions;

    /**
     * Metrics on the optimised (earlier) portion.
     */
    private BacktestRunMetrics train;

    /**
     * Metrics on the held-out (newer) portion.
     */
    private BacktestRunMetrics validate;
}
