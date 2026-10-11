package nl.jimkaplan.autotrader.strategy.rule;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StrategyDefinitionTest {

    private final RuleGroup entry = new RuleGroup(LogicalOperator.ALL,
            List.of(RuleCondition.MACD_BULLISH_CROSSOVER));
    private final RuleGroup exit = new RuleGroup(LogicalOperator.ANY,
            List.of(RuleCondition.PRICE_BELOW_SAR, RuleCondition.MACD_BEARISH_CROSSOVER));

    @Test
    void keepsItsComponents() {
        StrategyDefinition definition = new StrategyDefinition(MacdParameters.defaults(),
                ParabolicSarParameters.defaults(), entry, exit);

        assertEquals(MacdParameters.defaults(), definition.macd());
        assertEquals(ParabolicSarParameters.defaults(), definition.sar());
        assertEquals(entry, definition.entryRules());
        assertEquals(exit, definition.exitRules());
    }

    @Test
    void rejectsNullComponents() {
        assertThrows(NullPointerException.class, () -> new StrategyDefinition(
                null, ParabolicSarParameters.defaults(), entry, exit));
        assertThrows(NullPointerException.class, () -> new StrategyDefinition(
                MacdParameters.defaults(), null, entry, exit));
        assertThrows(NullPointerException.class, () -> new StrategyDefinition(
                MacdParameters.defaults(), ParabolicSarParameters.defaults(), null, exit));
        assertThrows(NullPointerException.class, () -> new StrategyDefinition(
                MacdParameters.defaults(), ParabolicSarParameters.defaults(), entry, null));
    }
}
