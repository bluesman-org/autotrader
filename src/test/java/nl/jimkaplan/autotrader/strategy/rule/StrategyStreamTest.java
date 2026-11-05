package nl.jimkaplan.autotrader.strategy.rule;

import nl.jimkaplan.autotrader.strategy.rule.MacdParameters;
import nl.jimkaplan.autotrader.strategy.rule.ParabolicSarParameters;
import nl.jimkaplan.autotrader.strategy.model.document.Candle;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The live engine decides from a stream, not from a fresh evaluation of the whole series. The two
 * must agree on every bar: the stream feeds the same recursions the batch calculation drives, and
 * the rules are the same evaluator on a two-bar window.
 */
class StrategyStreamTest {

    private static final StrategyDefinition DEFINITION = new StrategyDefinition(
            new MacdParameters(3, 5, 2),
            new ParabolicSarParameters(0.02, 0.2),
            new RuleGroup(LogicalOperator.ALL, List.of(RuleCondition.MACD_BULLISH_CROSSOVER)),
            new RuleGroup(LogicalOperator.ALL, List.of(RuleCondition.SAR_BEARISH_CROSSOVER)));

    @Test
    void theStreamMatchesTheBatchEvaluatorOnEveryBar() {
        List<Candle> candles = candles(120);
        StrategySignals batch = StrategyEvaluator.evaluate(CandleSeries.of(candles), DEFINITION);
        StrategyStream stream = new StrategyStream(DEFINITION);

        int entries = 0;
        int exits = 0;
        for (int i = 0; i < candles.size(); i++) {
            stream.accept(candles.get(i));
            if (i == 0) {
                continue; // no predecessor: no crossover can fire
            }
            assertEquals(batch.entry()[i], stream.entryFired(), "entry signal at bar " + i);
            assertEquals(batch.exit()[i], stream.exitFired(), "exit signal at bar " + i);
            assertEquals(i + 1, stream.bars());
            if (batch.entry()[i]) {
                entries++;
            }
            if (batch.exit()[i]) {
                exits++;
            }
        }
        // Without this the comparison is vacuous when no rule fires.
        assertTrue(entries > 0, "the fixture must produce entry signals");
        assertTrue(exits > 0, "the fixture must produce exit signals");
    }

    /**
     * The two-bar window is only valid while every condition looks back at most one bar. This walks
     * every condition in the enum through both paths, so a future condition that needs more history
     * fails here instead of silently never firing in the live engine.
     */
    @Test
    void everyConditionAgreesBetweenTheStreamAndTheBatch() {
        List<Candle> candles = candles(120);
        CandleSeries series = CandleSeries.of(candles);
        for (RuleCondition condition : RuleCondition.values()) {
            StrategyDefinition definition = new StrategyDefinition(
                    DEFINITION.macd(), DEFINITION.sar(),
                    new RuleGroup(LogicalOperator.ALL, List.of(condition)),
                    new RuleGroup(LogicalOperator.ALL, List.of(condition)));
            StrategySignals batch = StrategyEvaluator.evaluate(series, definition);
            StrategyStream stream = new StrategyStream(definition);
            for (int i = 0; i < candles.size(); i++) {
                stream.accept(candles.get(i));
                if (i == 0) {
                    continue;
                }
                assertEquals(batch.entry()[i], stream.entryFired(),
                        condition + " entry at bar " + i);
                assertEquals(batch.exit()[i], stream.exitFired(),
                        condition + " exit at bar " + i);
            }
        }
    }

    /**
     * A wave with both trends and flat stretches, so crossovers and flips occur inside the series.
     */
    private static List<Candle> candles(int count) {
        List<Candle> candles = new ArrayList<>();
        double price = 100;
        for (int i = 0; i < count; i++) {
            price += Math.sin(i / 3.0) * 2 + Math.cos(i / 7.0) * 0.7;
            BigDecimal close = BigDecimal.valueOf(price);
            candles.add(Candle.builder()
                    .market("BTC-EUR")
                    .interval("1h")
                    .openTime(1_700_000_000_000L + i * 3_600_000L)
                    .open(close)
                    .high(close.add(BigDecimal.ONE))
                    .low(close.subtract(BigDecimal.ONE))
                    .close(close)
                    .volume(BigDecimal.ONE)
                    .build());
        }
        return candles;
    }
}
