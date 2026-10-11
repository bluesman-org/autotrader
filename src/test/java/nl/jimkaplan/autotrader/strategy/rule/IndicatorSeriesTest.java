package nl.jimkaplan.autotrader.strategy.rule;

import nl.jimkaplan.autotrader.strategy.indicator.MacdResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndicatorSeriesTest {

    @Test
    void of_buildsTheSeriesFromTheParameters() {
        CandleSeries series = new CandleSeries(
                new double[]{10, 11, 12, 13, 14, 15, 16},
                new double[]{10, 11, 12, 13, 14, 15, 16},
                new double[]{10, 11, 12, 13, 14, 15, 16},
                new double[]{10, 11, 12, 13, 14, 15, 16},
                new long[]{1, 2, 3, 4, 5, 6, 7});

        IndicatorSeries indicators = IndicatorSeries.of(series,
                new MacdParameters(2, 3, 1), ParabolicSarParameters.defaults());

        assertEquals(7, indicators.length());
        // MACD(2, 3, 1) is defined from index slow + signal - 2 = 2 on
        assertTrue(Double.isNaN(indicators.macd().macd()[1]));
        assertFalse(Double.isNaN(indicators.macd().macd()[2]));
        // The SAR is defined from the second bar on
        assertTrue(Double.isNaN(indicators.sar()[0]));
        assertFalse(Double.isNaN(indicators.sar()[1]));
    }

    @Test
    void length_isTheNumberOfBars() {
        IndicatorSeries series = new IndicatorSeries(
                new MacdResult(new double[4], new double[4], new double[4]), new double[4]);

        assertEquals(4, series.length());
    }

    @Test
    void rejectsNullComponents() {
        assertThrows(NullPointerException.class, () -> new IndicatorSeries(null, new double[1]));
        assertThrows(NullPointerException.class, () -> new IndicatorSeries(
                new MacdResult(new double[1], new double[1], new double[1]), null));
        assertThrows(NullPointerException.class, () -> new IndicatorSeries(
                new MacdResult(null, new double[1], new double[1]), new double[1]));
        assertThrows(NullPointerException.class, () -> new IndicatorSeries(
                new MacdResult(new double[1], null, new double[1]), new double[1]));
        assertThrows(NullPointerException.class, () -> new IndicatorSeries(
                new MacdResult(new double[1], new double[1], null), new double[1]));
    }

    @Test
    void rejectsMisalignedSeries() {
        assertThrows(IllegalArgumentException.class, () -> new IndicatorSeries(
                new MacdResult(new double[2], new double[2], new double[2]), new double[3]));
        assertThrows(IllegalArgumentException.class, () -> new IndicatorSeries(
                new MacdResult(new double[2], new double[3], new double[2]), new double[2]));
        assertThrows(IllegalArgumentException.class, () -> new IndicatorSeries(
                new MacdResult(new double[2], new double[2], new double[3]), new double[2]));
    }
}
