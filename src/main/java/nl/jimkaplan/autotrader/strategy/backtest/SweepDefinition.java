package nl.jimkaplan.autotrader.strategy.backtest;

import nl.jimkaplan.autotrader.strategy.rule.MacdParameters;
import nl.jimkaplan.autotrader.strategy.rule.ParabolicSarParameters;
import nl.jimkaplan.autotrader.strategy.rule.RuleGroup;

import java.util.List;
import java.util.Objects;

/**
 * What a sweep explores: candidate indicator parameters, candidate rule sets, the train/validate
 * split and the metric to rank by.
 *
 * @param macdParameters The MACD parameter candidates
 * @param sarParameters  The Parabolic SAR parameter candidates
 * @param entryRules     The entry rule set candidates
 * @param exitRules      The exit rule set candidates
 * @param trainRatio     Share of the range the sweep optimises on, in (0, 1)
 * @param rankingMetric  The metric to rank by
 * @throws IllegalArgumentException if a candidate list is empty or the ratio is out of range
 */
public record SweepDefinition(List<MacdParameters> macdParameters, List<ParabolicSarParameters> sarParameters,
                              List<RuleGroup> entryRules, List<RuleGroup> exitRules,
                              double trainRatio, RankingMetric rankingMetric) {

    public SweepDefinition {
        macdParameters = requireNonEmpty("macdParameters", macdParameters);
        sarParameters = requireNonEmpty("sarParameters", sarParameters);
        entryRules = requireNonEmpty("entryRules", entryRules);
        exitRules = requireNonEmpty("exitRules", exitRules);
        if (!(trainRatio > 0 && trainRatio < 1)) {
            throw new IllegalArgumentException("trainRatio must be in (0, 1)");
        }
        Objects.requireNonNull(rankingMetric, "rankingMetric must not be null");
    }

    private static <T> List<T> requireNonEmpty(String name, List<T> values) {
        Objects.requireNonNull(values, name + " must not be null");
        if (values.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        return List.copyOf(values);
    }
}
