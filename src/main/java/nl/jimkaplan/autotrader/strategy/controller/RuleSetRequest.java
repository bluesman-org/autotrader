package nl.jimkaplan.autotrader.strategy.controller;

import io.swagger.v3.oas.annotations.media.Schema;
import nl.jimkaplan.autotrader.strategy.rule.LogicalOperator;
import nl.jimkaplan.autotrader.strategy.rule.RuleCondition;

import java.util.List;

/**
 * A rule set candidate: conditions combined with one operator.
 *
 * @param operator   How the conditions combine; defaults to ALL
 * @param conditions The conditions; at least one
 */
public record RuleSetRequest(
        @Schema(description = "How the conditions combine", example = "ALL") LogicalOperator operator,
        @Schema(description = "The conditions", example = "[\"MACD_BULLISH_CROSSOVER\"]")
        List<RuleCondition> conditions) {
}
