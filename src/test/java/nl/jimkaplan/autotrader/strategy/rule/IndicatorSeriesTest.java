package nl.jimkaplan.autotrader.strategy.rule;

import nl.jimkaplan.autotrader.strategy.indicator.MacdResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IndicatorSeriesTest {

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
