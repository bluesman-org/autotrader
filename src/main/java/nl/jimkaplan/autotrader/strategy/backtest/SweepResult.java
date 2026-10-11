package nl.jimkaplan.autotrader.strategy.backtest;

import java.util.List;

/**
 * The ranked outcome of a sweep.
 *
 * @param combinationsEvaluated How many combinations were simulated
 * @param ranked                The entries, best first per the ranking metric; combinations whose
 *                              train side never traded come last whatever the metric says
 */
public record SweepResult(int combinationsEvaluated, List<SweepEntry> ranked) {
}
