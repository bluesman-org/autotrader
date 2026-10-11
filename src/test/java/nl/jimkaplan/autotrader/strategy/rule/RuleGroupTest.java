package nl.jimkaplan.autotrader.strategy.rule;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RuleGroupTest {

    @Test
    void rejectsNullOperator() {
        assertThrows(NullPointerException.class,
                () -> new RuleGroup(null, List.of(RuleCondition.HISTOGRAM_POSITIVE)));
    }

    @Test
    void rejectsNullConditions() {
        assertThrows(NullPointerException.class,
                () -> new RuleGroup(LogicalOperator.ALL, null));
    }

    @Test
    void rejectsEmptyConditions() {
        assertThrows(IllegalArgumentException.class,
                () -> new RuleGroup(LogicalOperator.ALL, List.of()));
    }

    @Test
    void copiesTheConditions() {
        List<RuleCondition> conditions = new ArrayList<>(List.of(RuleCondition.HISTOGRAM_POSITIVE));
        RuleGroup group = new RuleGroup(LogicalOperator.ANY, conditions);

        conditions.add(RuleCondition.PRICE_BELOW_SAR);

        assertEquals(1, group.conditions().size());
        assertEquals(LogicalOperator.ANY, group.operator());
    }
}
