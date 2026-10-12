package nl.jimkaplan.autotrader.strategy.backtest;

import nl.jimkaplan.autotrader.strategy.rule.CandleSeries;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BacktestSplitTest {

    @Test
    void of_rejectsRatiosThatLeaveNoBarsOnOneSide() {
        CandleSeries series = series(10);

        assertThrows(IllegalArgumentException.class, () -> BacktestSplit.of(series, config(), 0.05));
        assertThrows(IllegalArgumentException.class, () -> BacktestSplit.of(series, config(), 1.0));
    }

    @Test
    void evaluate_runsBothSidesOnTheirSlices() {
        CandleSeries series = series(6);
        BacktestSplit split = BacktestSplit.of(series, config(), 0.5);
        boolean[] entrySignals = {true, false, false, false, false, false};
        boolean[] exitSignals = {false, true, false, false, false, false};

        BacktestEvaluation evaluation = split.evaluate(entrySignals, exitSignals);

        assertEquals(3, split.splitIndex());
        assertEquals(1, evaluation.train().trades().size());
        assertEquals(0, evaluation.validate().trades().size());
        BacktestTrade trade = evaluation.train().trades().get(0);
        // The signal on bar 0 fills at bar 1's open; the exit signal on bar 1 fills at bar 2's open
        assertEquals(series.opens()[1], trade.entryPrice());
        assertEquals(series.opens()[2], trade.exitPrice());
    }

    @Test
    void evaluate_rejectsSignalsThatAreNotAlignedWithTheSeries() {
        BacktestSplit split = BacktestSplit.of(series(10), config(), 0.7);

        assertThrows(IllegalArgumentException.class, () -> split.evaluate(new boolean[9], new boolean[10]));
        assertThrows(IllegalArgumentException.class, () -> split.evaluate(new boolean[10], new boolean[11]));
    }

    private static BacktestConfig config() {
        return new BacktestConfig(1000.0, 100.0, 0.002, 0);
    }

    private static CandleSeries series(int length) {
        double[] opens = new double[length];
        double[] highs = new double[length];
        double[] lows = new double[length];
        double[] closes = new double[length];
        long[] times = new long[length];
        for (int i = 0; i < length; i++) {
            opens[i] = 100.0 + i;
            highs[i] = opens[i] + 1;
            lows[i] = opens[i] - 1;
            closes[i] = opens[i];
            times[i] = 1_800_000_000_000L + i * 3_600_000L;
        }
        return new CandleSeries(opens, highs, lows, closes, times);
    }
}
