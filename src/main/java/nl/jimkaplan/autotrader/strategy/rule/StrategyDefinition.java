package nl.jimkaplan.autotrader.strategy.rule;

import java.util.Objects;

/**
 * A complete strategy: the indicator parameters and the independent entry and exit rule sets.
 *
 * @param macd       MACD parameters
 * @param sar        Parabolic SAR parameters
 * @param entryRules The conditions that signal an entry
 * @param exitRules  The conditions that signal an exit, defined independently of the entry
 */
public record StrategyDefinition(MacdParameters macd, ParabolicSarParameters sar,
                                 RuleGroup entryRules, RuleGroup exitRules) {

    public StrategyDefinition {
        Objects.requireNonNull(macd, "macd must not be null");
        Objects.requireNonNull(sar, "sar must not be null");
        Objects.requireNonNull(entryRules, "entryRules must not be null");
        Objects.requireNonNull(exitRules, "exitRules must not be null");
    }
}
