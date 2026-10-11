package nl.jimkaplan.autotrader.strategy.backtest;

import nl.jimkaplan.autotrader.strategy.rule.LogicalOperator;
import nl.jimkaplan.autotrader.strategy.rule.MacdParameters;
import nl.jimkaplan.autotrader.strategy.rule.ParabolicSarParameters;
import nl.jimkaplan.autotrader.strategy.rule.RuleCondition;
import nl.jimkaplan.autotrader.strategy.rule.RuleGroup;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;

class SweepDefinitionTest {

    private static final List<MacdParameters> MACD = List.of(MacdParameters.defaults());
    private static final List<ParabolicSarParameters> SAR = List.of(ParabolicSarParameters.defaults());
    private static final RuleGroup RULES = new RuleGroup(LogicalOperator.ALL,
            List.of(RuleCondition.HISTOGRAM_POSITIVE));

    @Test
    void rejectsEmptyCandidateLists() {
        assertThrows(IllegalArgumentException.class, () -> new SweepDefinition(
                List.of(), SAR, List.of(RULES), List.of(RULES), 0.7, RankingMetric.TOTAL_RETURN));
        assertThrows(IllegalArgumentException.class, () -> new SweepDefinition(
                MACD, List.of(), List.of(RULES), List.of(RULES), 0.7, RankingMetric.TOTAL_RETURN));
        assertThrows(IllegalArgumentException.class, () -> new SweepDefinition(
                MACD, SAR, List.of(), List.of(RULES), 0.7, RankingMetric.TOTAL_RETURN));
        assertThrows(IllegalArgumentException.class, () -> new SweepDefinition(
                MACD, SAR, List.of(RULES), List.of(), 0.7, RankingMetric.TOTAL_RETURN));
    }

    @Test
    void rejectsNullCandidateLists() {
        assertThrows(NullPointerException.class, () -> new SweepDefinition(
                null, SAR, List.of(RULES), List.of(RULES), 0.7, RankingMetric.TOTAL_RETURN));
        assertThrows(NullPointerException.class, () -> new SweepDefinition(
                MACD, null, List.of(RULES), List.of(RULES), 0.7, RankingMetric.TOTAL_RETURN));
        assertThrows(NullPointerException.class, () -> new SweepDefinition(
                MACD, SAR, null, List.of(RULES), 0.7, RankingMetric.TOTAL_RETURN));
        assertThrows(NullPointerException.class, () -> new SweepDefinition(
                MACD, SAR, List.of(RULES), null, 0.7, RankingMetric.TOTAL_RETURN));
    }

    @Test
    void rejectsRatiosOutsideTheOpenUnitInterval() {
        assertThrows(IllegalArgumentException.class, () -> new SweepDefinition(
                MACD, SAR, List.of(RULES), List.of(RULES), 0.0, RankingMetric.TOTAL_RETURN));
        assertThrows(IllegalArgumentException.class, () -> new SweepDefinition(
                MACD, SAR, List.of(RULES), List.of(RULES), 1.0, RankingMetric.TOTAL_RETURN));
        assertThrows(IllegalArgumentException.class, () -> new SweepDefinition(
                MACD, SAR, List.of(RULES), List.of(RULES), Double.NaN, RankingMetric.TOTAL_RETURN));
    }

    @Test
    void rejectsANullRankingMetric() {
        assertThrows(NullPointerException.class, () -> new SweepDefinition(
                MACD, SAR, List.of(RULES), List.of(RULES), 0.7, null));
    }
}
