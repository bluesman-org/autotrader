package nl.jimkaplan.autotrader.strategy.backtest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BacktestConfigTest {

    @Test
    void defaults_areCapitalFeeAndWarmUp() {
        BacktestConfig defaults = BacktestConfig.defaults();

        assertEquals(1000.0, defaults.initialCapital(), 1e-9);
        assertEquals(100.0, defaults.amountPerBuy(), 1e-9);
        assertEquals(0.002, defaults.feeRate(), 1e-9);
        assertEquals(200, defaults.warmUpCandles());
    }

    @Test
    void rejectsInvalidValues() {
        assertThrows(IllegalArgumentException.class, () -> new BacktestConfig(0, 100, 0.002, 200));
        assertThrows(IllegalArgumentException.class, () -> new BacktestConfig(-100, 100, 0.002, 200));
        assertThrows(IllegalArgumentException.class, () -> new BacktestConfig(Double.NaN, 100, 0.002, 200));
        assertThrows(IllegalArgumentException.class, () -> new BacktestConfig(1000, 4.99, 0.002, 200));
        assertThrows(IllegalArgumentException.class, () -> new BacktestConfig(1000, Double.POSITIVE_INFINITY, 0.002, 200));
        assertThrows(IllegalArgumentException.class, () -> new BacktestConfig(1000, 100, -0.001, 200));
        assertThrows(IllegalArgumentException.class, () -> new BacktestConfig(1000, 100, 1.0, 200));
        assertThrows(IllegalArgumentException.class, () -> new BacktestConfig(1000, 100, Double.NaN, 200));
        assertThrows(IllegalArgumentException.class, () -> new BacktestConfig(1000, 100, 0.002, -1));
    }
}
