package nl.jimkaplan.autotrader.strategy.backtest;

import nl.jimkaplan.autotrader.strategy.rule.CandleSeries;
import nl.jimkaplan.autotrader.strategy.rule.StrategySignals;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BacktestEngineTest {

    @Test
    void handWorkedRoundTrip_matchesTheComputedEquityAndTrade() {
        // Buy fills at bar 2's open (20) for 100 EUR, sell fills at bar 4's open (30); 1% fees
        CandleSeries series = series(
                new double[]{10, 10, 20, 20, 30},
                new double[]{10, 15, 20, 25, 30});
        StrategySignals signals = signals(
                new boolean[]{false, true, false, false, false},
                new boolean[]{false, false, false, true, false});
        BacktestConfig config = new BacktestConfig(1000.0, 100.0, 0.01, 0);

        BacktestResult result = BacktestEngine.run(series, signals, config);

        assertEquals(1047.5, result.metrics().finalEquity(), 1e-9);
        assertEquals(4.75, result.metrics().totalReturnPercent(), 1e-9);
        assertEquals(1, result.metrics().tradeCount());
        assertEquals(100.0, result.metrics().winRatePercent(), 1e-9);
        assertEquals(Double.POSITIVE_INFINITY, result.metrics().profitFactor());
        // Equity path 1000, 1000, 999, 1024, 1047.5: the 0.1% dip at bar 2 is the max drawdown
        assertEquals(0.1, result.metrics().maxDrawdownPercent(), 1e-9);

        BacktestTrade trade = result.trades().get(0);
        assertEquals(3_000L, trade.entryTime());
        assertEquals(20.0, trade.entryPrice(), 1e-9);
        assertEquals(5_000L, trade.exitTime());
        assertEquals(30.0, trade.exitPrice(), 1e-9);
        assertEquals(100.0, trade.amount(), 1e-9);
        assertEquals(5.0, trade.quantity(), 1e-9);
        assertEquals(1.0, trade.entryFee(), 1e-9);
        assertEquals(1.5, trade.exitFee(), 1e-9);
        assertEquals(47.5, trade.profit(), 1e-9);
        assertTrue(result.skippedSignals().isEmpty());
    }

    @Test
    void withoutFees_theRoundTripProfitsTheFullDifference() {
        CandleSeries series = series(
                new double[]{10, 10, 20, 20, 30},
                new double[]{10, 15, 20, 25, 30});
        StrategySignals signals = signals(
                new boolean[]{false, true, false, false, false},
                new boolean[]{false, false, false, true, false});

        BacktestResult result = BacktestEngine.run(series, signals, new BacktestConfig(1000.0, 100.0, 0.0, 0));

        assertEquals(1050.0, result.metrics().finalEquity(), 1e-9);
        assertEquals(50.0, result.trades().get(0).profit(), 1e-9);
    }

    @Test
    void withoutSignals_nothingTradesAndTheEquityIsFlat() {
        CandleSeries series = series(new double[]{10, 20, 30}, new double[]{10, 20, 30});
        StrategySignals signals = signals(new boolean[]{false, false, false}, new boolean[]{false, false, false});

        BacktestResult result = BacktestEngine.run(series, signals, BacktestConfig.defaults());

        assertEquals(1000.0, result.metrics().finalEquity(), 1e-9);
        assertEquals(0.0, result.metrics().totalReturnPercent(), 1e-9);
        assertEquals(0.0, result.metrics().maxDrawdownPercent(), 1e-9);
        assertEquals(0, result.metrics().tradeCount());
        assertEquals(0.0, result.metrics().winRatePercent(), 1e-9);
        assertEquals(0.0, result.metrics().profitFactor(), 1e-9);
        assertTrue(result.trades().isEmpty());
    }

    @Test
    void signalsInsideTheWarmUpAreNeverTraded() {
        CandleSeries series = series(new double[]{10, 10, 20, 30}, new double[]{10, 10, 20, 30});
        StrategySignals signals = signals(new boolean[]{true, false, false, false}, new boolean[]{false, false, false, false});
        BacktestConfig config = new BacktestConfig(1000.0, 100.0, 0.0, 5);

        BacktestResult result = BacktestEngine.run(series, signals, config);

        assertEquals(0, result.metrics().tradeCount());
        assertEquals(1000.0, result.metrics().finalEquity(), 1e-9);
    }

    @Test
    void entrySignalsThatCannotBePaidAreSkippedAndRecorded() {
        CandleSeries series = series(new double[]{10, 10, 10, 10}, new double[]{10, 10, 10, 10});
        StrategySignals signals = signals(new boolean[]{true, false, true, false}, new boolean[]{false, false, false, false});
        // 100 EUR cash, 100 EUR per buy plus its 1% fee: neither attempt can be paid
        BacktestConfig config = new BacktestConfig(100.0, 100.0, 0.01, 0);

        BacktestResult result = BacktestEngine.run(series, signals, config);

        assertEquals(0, result.metrics().tradeCount());
        assertEquals(100.0, result.metrics().finalEquity(), 1e-9);
        assertEquals(2, result.skippedSignals().size());
        assertEquals(SkipReason.INSUFFICIENT_BALANCE, result.skippedSignals().get(0).reason());
        assertEquals(1, result.skippedSignals().get(0).index());
        assertEquals(3, result.skippedSignals().get(1).index());
    }

    @Test
    void aPositionStillOpenIsClosedAtTheLastClose() {
        CandleSeries series = series(new double[]{10, 10, 10}, new double[]{10, 10, 25});
        StrategySignals signals = signals(new boolean[]{false, true, false}, new boolean[]{false, false, false});
        BacktestConfig config = new BacktestConfig(1000.0, 100.0, 0.0, 0);

        BacktestResult result = BacktestEngine.run(series, signals, config);

        assertEquals(1, result.metrics().tradeCount());
        BacktestTrade trade = result.trades().get(0);
        assertEquals(25.0, trade.exitPrice(), 1e-9);
        // Bought 10 units at the last bar's open (10) and closed at its close (25)
        assertEquals(1150.0, result.metrics().finalEquity(), 1e-9);
        assertEquals(3_000L, trade.exitTime());
    }

    @Test
    void aLosingRoundTrip_reportsZeroProfitFactor() {
        CandleSeries series = series(new double[]{10, 20, 10}, new double[]{10, 20, 10});
        StrategySignals signals = signals(new boolean[]{false, true, false}, new boolean[]{false, false, true});

        BacktestResult result = BacktestEngine.run(series, signals, new BacktestConfig(1000.0, 100.0, 0.0, 0));

        assertEquals(0, result.metrics().winRatePercent(), 1e-9);
        assertEquals(0.0, result.metrics().profitFactor(), 1e-9);
        assertEquals(1, result.metrics().tradeCount());
    }

    @Test
    void aSignalOnTheLastBarCannotFill() {
        CandleSeries series = series(new double[]{10, 10, 10}, new double[]{10, 10, 10});
        StrategySignals signals = signals(new boolean[]{false, false, true}, new boolean[]{false, false, false});

        BacktestResult result = BacktestEngine.run(series, signals, new BacktestConfig(1000.0, 100.0, 0.0, 0));

        assertEquals(0, result.metrics().tradeCount());
        assertFalse(result.metrics().finalEquity() < 1000.0);
    }

    @Test
    void rejectsSignalsThatDoNotMatchTheSeries() {
        CandleSeries series = series(new double[]{10, 10}, new double[]{10, 10});
        StrategySignals signals = signals(new boolean[]{true}, new boolean[]{false});

        assertThrows(IllegalArgumentException.class,
                () -> BacktestEngine.run(series, signals, BacktestConfig.defaults()));
    }

    private static CandleSeries series(double[] opens, double[] closes) {
        int length = opens.length;
        double[] highs = new double[length];
        double[] lows = new double[length];
        long[] times = new long[length];
        for (int i = 0; i < length; i++) {
            highs[i] = Math.max(opens[i], closes[i]);
            lows[i] = Math.min(opens[i], closes[i]);
            times[i] = (i + 1) * 1_000L;
        }
        return new CandleSeries(opens, highs, lows, closes, times);
    }

    private static StrategySignals signals(boolean[] entry, boolean[] exit) {
        return new StrategySignals(entry, exit);
    }
}
