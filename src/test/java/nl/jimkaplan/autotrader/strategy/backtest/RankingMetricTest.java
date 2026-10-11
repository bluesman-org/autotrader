package nl.jimkaplan.autotrader.strategy.backtest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RankingMetricTest {

    private final BacktestMetrics metrics = new BacktestMetrics(1050.0, 5.0, 12.0, 4, 75.0, 2.5);

    @Test
    void extractsEachValue() {
        assertEquals(5.0, RankingMetric.TOTAL_RETURN.value(metrics), 1e-9);
        assertEquals(2.5, RankingMetric.PROFIT_FACTOR.value(metrics), 1e-9);
        assertEquals(12.0, RankingMetric.MAX_DRAWDOWN.value(metrics), 1e-9);
        assertEquals(75.0, RankingMetric.WIN_RATE.value(metrics), 1e-9);
    }

    @Test
    void knowsItsDirection() {
        assertTrue(RankingMetric.TOTAL_RETURN.higherIsBetter());
        assertTrue(RankingMetric.PROFIT_FACTOR.higherIsBetter());
        assertFalse(RankingMetric.MAX_DRAWDOWN.higherIsBetter());
        assertTrue(RankingMetric.WIN_RATE.higherIsBetter());
    }
}
