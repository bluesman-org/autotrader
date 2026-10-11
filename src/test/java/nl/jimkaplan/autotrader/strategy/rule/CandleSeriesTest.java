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
                candle(1_000L, "9.5", "10", "9", "9.5"),
                candle(2_000L, "10.5", "11", "9.5", "10.5"));

        CandleSeries series = CandleSeries.of(candles);

        assertEquals(2, series.length());
        assertArrayEquals(new double[]{9.5, 10.5}, series.opens(), 0.0);
        assertArrayEquals(new double[]{10.0, 11.0}, series.highs(), 0.0);
        assertArrayEquals(new double[]{9.0, 9.5}, series.lows(), 0.0);
        assertArrayEquals(new double[]{9.5, 10.5}, series.closes(), 0.0);
        assertArrayEquals(new long[]{1_000L, 2_000L}, series.times());
    }

    @Test
    void of_rejectsCandlesNotOrderedOldestFirst() {
        List<Candle> reversed = List.of(
                candle(2_000L, "10.5", "11", "9.5", "10.5"),
                candle(1_000L, "9.5", "10", "9", "9.5"));

        assertThrows(IllegalArgumentException.class, () -> CandleSeries.of(reversed));
    }

    @Test
    void of_rejectsDuplicateOpenTimes() {
        List<Candle> duplicated = List.of(
                candle(1_000L, "9.5", "10", "9", "9.5"),
                candle(1_000L, "10.5", "11", "9.5", "10.5"));

        assertThrows(IllegalArgumentException.class, () -> CandleSeries.of(duplicated));
    }

    @Test
    void of_rejectsCandlesMissingOhlcValues() {
        List<Candle> incomplete = List.of(Candle.builder().openTime(1_000L).build());

        assertThrows(IllegalArgumentException.class, () -> CandleSeries.of(incomplete));
    }

    @Test
    void rejectsMismatchedLengths() {
        double[] one = new double[1];
        double[] two = new double[2];

        assertThrows(IllegalArgumentException.class,
                () -> new CandleSeries(two, two, two, two, new long[1]));
        assertThrows(IllegalArgumentException.class,
                () -> new CandleSeries(one, two, one, one, new long[1]));
        assertThrows(IllegalArgumentException.class,
                () -> new CandleSeries(one, one, two, one, new long[1]));
        assertThrows(IllegalArgumentException.class,
                () -> new CandleSeries(one, one, one, two, new long[1]));
        assertThrows(IllegalArgumentException.class,
                () -> new CandleSeries(one, one, one, one, new long[2]));
    }

    @Test
    void rejectsNullArrays() {
        double[] one = new double[1];
        long[] times = new long[1];

        assertThrows(NullPointerException.class, () -> new CandleSeries(null, one, one, one, times));
        assertThrows(NullPointerException.class, () -> new CandleSeries(one, null, one, one, times));
        assertThrows(NullPointerException.class, () -> new CandleSeries(one, one, null, one, times));
        assertThrows(NullPointerException.class, () -> new CandleSeries(one, one, one, null, times));
        assertThrows(NullPointerException.class, () -> new CandleSeries(one, one, one, one, null));
    }

    @Test
    void of_rejectsNullCandles() {
        assertThrows(NullPointerException.class, () -> CandleSeries.of(null));
    }

    private static Candle candle(long openTime, String open, String high, String low, String close) {
        return Candle.builder()
                .openTime(openTime)
                .open(new BigDecimal(open))
                .high(new BigDecimal(high))
                .low(new BigDecimal(low))
                .close(new BigDecimal(close))
                .build();
    }
}
