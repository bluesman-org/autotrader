package nl.jimkaplan.autotrader.strategy.rule;

import java.util.List;
import java.util.Objects;

/**
 * A set of conditions combined with a single logical operator.
 *
 * @param operator   How the conditions combine
 * @param conditions The conditions; at least one
 * @throws IllegalArgumentException if the conditions are empty
 */
public record RuleGroup(LogicalOperator operator, List<RuleCondition> conditions) {

    public RuleGroup {
        Objects.requireNonNull(operator, "operator must not be null");
        Objects.requireNonNull(conditions, "conditions must not be null");
        if (conditions.isEmpty()) {
            throw new IllegalArgumentException("conditions must not be empty");
        }
        conditions = List.copyOf(conditions);
    }
}
