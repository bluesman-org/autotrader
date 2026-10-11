package nl.jimkaplan.autotrader.strategy.rule;

import nl.jimkaplan.autotrader.strategy.model.document.Candle;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CandleSeriesTest {

    @Test
    void of_extractsTheSeriesFromCandles() {
        List<Candle> candles = List.of(
                candle("10", "9", "9.5"),
                candle("11", "9.5", "10.5"));

        CandleSeries series = CandleSeries.of(candles);

        assertEquals(2, series.length());
        assertArrayEquals(new double[]{10.0, 11.0}, series.highs(), 0.0);
        assertArrayEquals(new double[]{9.0, 9.5}, series.lows(), 0.0);
        assertArrayEquals(new double[]{9.5, 10.5}, series.closes(), 0.0);
    }

    @Test
    void rejectsMismatchedLengths() {
        assertThrows(IllegalArgumentException.class,
                () -> new CandleSeries(new double[2], new double[2], new double[3]));
        assertThrows(IllegalArgumentException.class,
                () -> new CandleSeries(new double[2], new double[3], new double[2]));
    }

    @Test
    void rejectsNullArrays() {
        assertThrows(NullPointerException.class, () -> new CandleSeries(null, new double[1], new double[1]));
        assertThrows(NullPointerException.class, () -> new CandleSeries(new double[1], null, new double[1]));
        assertThrows(NullPointerException.class, () -> new CandleSeries(new double[1], new double[1], null));
    }

    @Test
    void of_rejectsNullCandles() {
        assertThrows(NullPointerException.class, () -> CandleSeries.of(null));
    }

    private static Candle candle(String high, String low, String close) {
        return Candle.builder()
                .high(new BigDecimal(high))
                .low(new BigDecimal(low))
                .close(new BigDecimal(close))
                .build();
    }
}
