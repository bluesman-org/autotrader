package nl.jimkaplan.autotrader.strategy.indicator;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MacdTest {

    @Test
    void matchesTheFrozenReferenceFixture() {
        List<String[]> rows = IndicatorFixtures.rows("macd_btc_eur_1h_12_26_9.csv");
        double[] closes = IndicatorFixtures.column(rows, 0);
        double[] expectedMacd = IndicatorFixtures.column(rows, 1);
        double[] expectedSignal = IndicatorFixtures.column(rows, 2);
        double[] expectedHistogram = IndicatorFixtures.column(rows, 3);

        MacdResult result = Macd.calculate(closes, 12, 26, 9);

        assertArrayEquals(expectedMacd, result.macd(), 0.0);
        assertArrayEquals(expectedSignal, result.signal(), 0.0);
        assertArrayEquals(expectedHistogram, result.histogram(), 0.0);
    }

    @Test
    void matchesTheFrozenFixtureOnTheLinearSeries() {
        List<String[]> rows = IndicatorFixtures.rows("macd_linear_3_5_2.csv");
        double[] closes = IndicatorFixtures.column(rows, 0);

        MacdResult result = Macd.calculate(closes, 3, 5, 2);

        assertArrayEquals(IndicatorFixtures.column(rows, 1), result.macd(), 0.0);
        assertArrayEquals(IndicatorFixtures.column(rows, 2), result.signal(), 0.0);
        assertArrayEquals(IndicatorFixtures.column(rows, 3), result.histogram(), 0.0);
    }

    @Test
    void withSignalPeriodOne_theSignalIsTheMacdLine() {
        double[] closes = IndicatorFixtures.column(
                IndicatorFixtures.rows("macd_linear_3_5_2.csv"), 0);

        MacdResult result = Macd.calculate(closes, 3, 5, 1);

        assertArrayEquals(result.macd(), result.signal(), 0.0);
        for (int i = 5 + 1 - 2; i < result.histogram().length; i++) {
            assertEquals(0.0, result.histogram()[i], 0.0);
        }
    }

    @Test
    void withFewerBarsThanTheMinimum_returnsAllNaN() {
        double[] closes = Arrays.copyOf(
                IndicatorFixtures.column(IndicatorFixtures.rows("macd_btc_eur_1h_12_26_9.csv"), 0), 33);

        MacdResult result = Macd.calculate(closes, 12, 26, 9);

        assertAllNaN(result.macd(), result.signal(), result.histogram());
    }

    @Test
    void withExactlyTheMinimumBars_producesTheFirstValueAtTheLastIndex() {
        // 12/26/9 needs slow + signal - 1 = 34 bars; the first value lands at index 33
        double[] closes = Arrays.copyOf(
                IndicatorFixtures.column(IndicatorFixtures.rows("macd_btc_eur_1h_12_26_9.csv"), 0), 34);

        MacdResult result = Macd.calculate(closes, 12, 26, 9);

        for (int i = 0; i < 33; i++) {
            assertTrue(Double.isNaN(result.macd()[i]), "index " + i + " must be NaN");
        }
        assertTrue(!Double.isNaN(result.macd()[33]));
        assertTrue(!Double.isNaN(result.signal()[33]));
        assertTrue(!Double.isNaN(result.histogram()[33]));
    }

    @Test
    void withEqualFastAndSlowPeriods_theMacdLineIsZero() {
        double[] closes = IndicatorFixtures.column(IndicatorFixtures.rows("macd_linear_3_5_2.csv"), 0);

        MacdResult result = Macd.calculate(closes, 5, 5, 3);

        for (int i = 5 + 3 - 2; i < closes.length; i++) {
            assertEquals(0.0, result.macd()[i], 0.0);
        }
    }

    @Test
    void rejectsInvalidArguments() {
        double[] closes = {1.0, 2.0, 3.0};

        assertThrows(IllegalArgumentException.class, () -> Macd.calculate(null, 12, 26, 9));
        assertThrows(IllegalArgumentException.class, () -> Macd.calculate(closes, 0, 26, 9));
        assertThrows(IllegalArgumentException.class, () -> Macd.calculate(closes, 12, 5, 9));
        assertThrows(IllegalArgumentException.class, () -> Macd.calculate(closes, 12, 26, 0));
    }

    @Test
    void rejectsPeriodsAboveTheMaximum() {
        double[] closes = {1.0, 2.0, 3.0};

        assertThrows(IllegalArgumentException.class,
                () -> Macd.calculate(closes, Macd.MAX_PERIOD + 1, Macd.MAX_PERIOD + 1, 9));
        assertThrows(IllegalArgumentException.class,
                () -> Macd.calculate(closes, 12, 26, Macd.MAX_PERIOD + 1));
        // Regression: a huge slow period used to wrap the minimum-length sum and read past the input
        assertThrows(IllegalArgumentException.class,
                () -> Macd.calculate(closes, 12, 2_147_483_640, 9));
    }

    @Test
    void withTheMaximumPeriodOnAShortInput_returnsAllNaN() {
        MacdResult result = Macd.calculate(new double[]{1.0, 2.0, 3.0}, 12, Macd.MAX_PERIOD, 9);

        assertAllNaN(result.macd(), result.signal(), result.histogram());
    }

    private static void assertAllNaN(double[]... series) {
        for (double[] values : series) {
            for (double value : values) {
                assertTrue(Double.isNaN(value), "expected NaN but was " + value);
            }
        }
    }
}
