package nl.jimkaplan.autotrader.strategy.live;

import nl.jimkaplan.autotrader.strategy.backtest.BacktestDefaults;
import nl.jimkaplan.autotrader.strategy.model.document.Candle;
import nl.jimkaplan.autotrader.strategy.model.document.LiveStrategy;
import nl.jimkaplan.autotrader.strategy.rule.CandleSeries;
import nl.jimkaplan.autotrader.strategy.rule.IndicatorSeries;
import nl.jimkaplan.autotrader.strategy.rule.LogicalOperator;
import nl.jimkaplan.autotrader.strategy.rule.MacdParameters;
import nl.jimkaplan.autotrader.strategy.rule.ParabolicSarParameters;
import nl.jimkaplan.autotrader.strategy.rule.RuleCondition;
import nl.jimkaplan.autotrader.strategy.rule.RuleGroup;
import nl.jimkaplan.autotrader.strategy.rule.StrategyDefinition;
import nl.jimkaplan.autotrader.strategy.rule.StrategyEvaluator;
import nl.jimkaplan.autotrader.strategy.rule.StrategySignals;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The replay gate: evaluating the history candle by candle — the way the live engine does it —
 * produces exactly the same signals and indicator values as evaluating the whole series at once,
 * which is what the backtester does. A dry-run replay of a history therefore reproduces a backtest
 * over that history.
 *
 * <p>Two cases pin the two claims the runner relies on: prefix equality (candle-by-candle
 * evaluation) and the bounded window the runner actually uses — warm-up plus both MACD periods
 * plus {@link LiveStrategyRunner#CONVERGENCE_MARGIN_CANDLES} — whose front-truncation the margin
 * has to make invisible.</p>
 */
class LiveSignalReplayTest {

    @Test
    void candleByCandleEvaluationMatchesTheWholeSeries() {
        List<Candle> candles = wavySeries(400);
        StrategyDefinition definition = definition();

        CandleSeries full = CandleSeries.of(candles);
        StrategySignals fullSignals = StrategyEvaluator.evaluate(full, definition);
        IndicatorSeries fullIndicators = StrategyEvaluator.indicators(full, definition);

        int entries = 0;
        int exits = 0;
        for (int i = 0; i < candles.size(); i++) {
            CandleSeries prefix = CandleSeries.of(candles.subList(0, i + 1));
            StrategySignals prefixSignals = StrategyEvaluator.evaluate(prefix, definition);
            IndicatorSeries prefixIndicators = StrategyEvaluator.indicators(prefix, definition);

            assertEquals(fullSignals.entry()[i], prefixSignals.entry()[i], "entry signal at bar " + i);
            assertEquals(fullSignals.exit()[i], prefixSignals.exit()[i], "exit signal at bar " + i);
            assertSameDouble("macd", fullIndicators.macd().macd()[i], prefixIndicators.macd().macd()[i], i);
            assertSameDouble("signal", fullIndicators.macd().signal()[i], prefixIndicators.macd().signal()[i], i);
            assertSameDouble("histogram", fullIndicators.macd().histogram()[i],
                    prefixIndicators.macd().histogram()[i], i);
            assertSameDouble("sar", fullIndicators.sar()[i], prefixIndicators.sar()[i], i);
            if (fullSignals.entry()[i]) {
                entries++;
            }
            if (fullSignals.exit()[i]) {
                exits++;
            }
        }
        assertTrue(entries > 0, "the fixture must contain entry signals");
        assertTrue(exits > 0, "the fixture must contain exit signals");
    }

    private static void assertSameDouble(String name, double expected, double actual, int index) {
        assertEquals(0, Double.compare(expected, actual), name + " differs at bar " + index);
    }

    @Test
    void aFrontTruncatedWindowMatchesTheWholeSeries() {
        List<Candle> candles = wavySeries(1_600);
        StrategyDefinition definition = definition();
        CandleSeries full = CandleSeries.of(candles);
        StrategySignals fullSignals = StrategyEvaluator.evaluate(full, definition);
        IndicatorSeries fullIndicators = StrategyEvaluator.indicators(full, definition);

        MacdParameters macd = MacdParameters.defaults();
        LiveStrategy strategy = LiveStrategy.builder()
                .warmUpCandles(BacktestDefaults.WARM_UP_CANDLES)
                .macdSlowPeriod(macd.slowPeriod())
                .macdSignalPeriod(macd.signalPeriod())
                .build();
        int window = LiveStrategyRunner.windowLength(strategy, LiveStrategyRunner.CONVERGENCE_MARGIN_CANDLES);

        int checked = 0;
        for (int i = window - 1; i < candles.size(); i++) {
            CandleSeries windowed = CandleSeries.of(candles.subList(i - window + 1, i + 1));
            StrategySignals windowedSignals = StrategyEvaluator.evaluate(windowed, definition);
            IndicatorSeries windowedIndicators = StrategyEvaluator.indicators(windowed, definition);
            int last = window - 1;

            assertEquals(fullSignals.entry()[i], windowedSignals.entry()[last],
                    "entry signal at bar " + i);
            assertEquals(fullSignals.exit()[i], windowedSignals.exit()[last], "exit signal at bar " + i);
            assertSameDouble("macd", fullIndicators.macd().macd()[i],
                    windowedIndicators.macd().macd()[last], i);
            assertSameDouble("signal", fullIndicators.macd().signal()[i],
                    windowedIndicators.macd().signal()[last], i);
            assertSameDouble("histogram", fullIndicators.macd().histogram()[i],
                    windowedIndicators.macd().histogram()[last], i);
            assertSameDouble("sar", fullIndicators.sar()[i], windowedIndicators.sar()[last], i);
            checked++;
        }
        assertTrue(checked > 0, "the fixture must be longer than the window");
    }

    private static StrategyDefinition definition() {
        return new StrategyDefinition(
                MacdParameters.defaults(),
                ParabolicSarParameters.defaults(),
                new RuleGroup(LogicalOperator.ALL, List.of(RuleCondition.MACD_BULLISH_CROSSOVER)),
                new RuleGroup(LogicalOperator.ALL, List.of(RuleCondition.SAR_BEARISH_CROSSOVER)));
    }

    private static List<Candle> wavySeries(int count) {
        List<Candle> candles = new ArrayList<>(count);
        long openTime = 1_783_069_200_000L;
        for (int i = 0; i < count; i++) {
            double close = 100 + 10 * Math.sin(i / 7.0) + i * 0.05;
            candles.add(Candle.builder()
                    .market("BTC-EUR")
                    .interval("1h")
                    .openTime(openTime + i * 3_600_000L)
                    .open(BigDecimal.valueOf(close))
                    .high(BigDecimal.valueOf(close + 1))
                    .low(BigDecimal.valueOf(close - 1))
                    .close(BigDecimal.valueOf(close))
                    .volume(BigDecimal.ONE)
                    .build());
        }
        return candles;
    }
}
