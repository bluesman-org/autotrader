package nl.jimkaplan.autotrader.strategy.indicator;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParabolicSarTest {

    @Test
    void matchesTheFrozenReferenceFixtures() {
        List<String[]> rows = IndicatorFixtures.rows("sar_btc_eur_1h.csv");
        double[] highs = IndicatorFixtures.column(rows, 0);
        double[] lows = IndicatorFixtures.column(rows, 1);
        double[] expectedDefault = IndicatorFixtures.column(rows, 2);
        double[] expectedFast = IndicatorFixtures.column(rows, 3);

        assertArrayEquals(expectedDefault, ParabolicSar.calculate(highs, lows, 0.02, 0.2), 0.0);
        assertArrayEquals(expectedFast, ParabolicSar.calculate(highs, lows, 0.05, 0.5), 0.0);
    }

    @Test
    void matchesTheFrozenFlatFixture() {
        List<String[]> rows = IndicatorFixtures.rows("sar_flat.csv");

        assertArrayEquals(IndicatorFixtures.column(rows, 2),
                ParabolicSar.calculate(IndicatorFixtures.column(rows, 0),
                        IndicatorFixtures.column(rows, 1), 0.02, 0.2), 0.0);
    }

    @Test
    void matchesTheFrozenOscillatingFixture() {
        List<String[]> rows = IndicatorFixtures.rows("sar_oscillating.csv");

        assertArrayEquals(IndicatorFixtures.column(rows, 2),
                ParabolicSar.calculate(IndicatorFixtures.column(rows, 0),
                        IndicatorFixtures.column(rows, 1), 0.02, 0.2), 0.0);
    }

    @Test
    void withASingleBar_returnsAllNaN() {
        double[] result = ParabolicSar.calculate(new double[]{5.0}, new double[]{4.0}, 0.02, 0.2);

        assertTrue(Double.isNaN(result[0]));
    }

    @Test
    void withTwoBars_producesTheInitialValueAtTheSecondBar() {
        // Ascending: long, with the first bar's low as the initial SAR
        double[] result = ParabolicSar.calculate(new double[]{2.0, 3.0}, new double[]{1.0, 2.0}, 0.02, 0.2);

        assertTrue(Double.isNaN(result[0]));
        assertArrayEquals(new double[]{1.0}, new double[]{result[1]}, 0.0);
    }

    @Test
    void withAnAccelerationAboveTheMaximum_capsItToTheMaximum() {
        List<String[]> rows = IndicatorFixtures.rows("sar_btc_eur_1h.csv");
        double[] highs = IndicatorFixtures.column(rows, 0);
        double[] lows = IndicatorFixtures.column(rows, 1);

        assertArrayEquals(ParabolicSar.calculate(highs, lows, 0.2, 0.2),
                ParabolicSar.calculate(highs, lows, 0.5, 0.2), 0.0);
    }

    @Test
    void rejectsInvalidArguments() {
        double[] values = {1.0, 2.0};

        assertThrows(IllegalArgumentException.class, () -> ParabolicSar.calculate(null, values, 0.02, 0.2));
        assertThrows(IllegalArgumentException.class, () -> ParabolicSar.calculate(values, null, 0.02, 0.2));
        assertThrows(IllegalArgumentException.class,
                () -> ParabolicSar.calculate(values, new double[]{1.0}, 0.02, 0.2));
        assertThrows(IllegalArgumentException.class, () -> ParabolicSar.calculate(values, values, -0.01, 0.2));
        assertThrows(IllegalArgumentException.class, () -> ParabolicSar.calculate(values, values, 0.02, -0.01));
    }
}
