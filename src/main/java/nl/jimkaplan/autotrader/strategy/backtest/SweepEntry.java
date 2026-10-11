package nl.jimkaplan.autotrader.strategy.backtest;

import nl.jimkaplan.autotrader.strategy.rule.MacdParameters;
import nl.jimkaplan.autotrader.strategy.rule.ParabolicSarParameters;
import nl.jimkaplan.autotrader.strategy.rule.RuleGroup;

/**
 * One evaluated combination: what it is and how it did on each side of the split.
 *
 * @param macd     The MACD parameters
 * @param sar      The Parabolic SAR parameters
 * @param entryRules The entry rule set
 * @param exitRules  The exit rule set
 * @param train    Metrics on the earlier (optimised) portion
 * @param validate Metrics on the held-out newer portion
 */
public record SweepEntry(MacdParameters macd, ParabolicSarParameters sar, RuleGroup entryRules,
                         RuleGroup exitRules, BacktestMetrics train, BacktestMetrics validate) {
}
