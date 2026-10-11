package nl.jimkaplan.autotrader.strategy.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CandleIntervalTest {

    private static final long HOUR = 3_600_000L;

    /**
     * 2024-01-01T00:00:00Z, aligned to every supported interval.
     */
    private static final long BOUNDARY = 1_704_067_200_000L;

    @Test
    void fromCode_returnsMatchingInterval() {
        assertEquals(CandleInterval.ONE_MINUTE, CandleInterval.fromCode("1m"));
        assertEquals(CandleInterval.FIVE_MINUTES, CandleInterval.fromCode("5m"));
        assertEquals(CandleInterval.ONE_HOUR, CandleInterval.fromCode("1h"));
        assertEquals(CandleInterval.ONE_DAY, CandleInterval.fromCode("1d"));
    }

    @Test
    void fromCode_withUnsupportedCode_throws() {
        assertThrows(IllegalArgumentException.class, () -> CandleInterval.fromCode("1W"));
        assertThrows(IllegalArgumentException.class, () -> CandleInterval.fromCode("1M"));
        assertThrows(IllegalArgumentException.class, () -> CandleInterval.fromCode(null));
    }

    @Test
    void floorOpenTime_floorsToIntervalBoundary() {
        assertEquals(BOUNDARY, CandleInterval.ONE_HOUR.floorOpenTime(BOUNDARY));
        assertEquals(BOUNDARY, CandleInterval.ONE_HOUR.floorOpenTime(BOUNDARY + HOUR - 1));
        assertEquals(BOUNDARY - HOUR, CandleInterval.ONE_HOUR.floorOpenTime(BOUNDARY - 1));
    }

    @Test
    void lastClosedOpenTime_excludesFormingCandle() {
        // Exactly at a boundary the candle that has just closed is the one before it
        assertEquals(BOUNDARY - HOUR, CandleInterval.ONE_HOUR.lastClosedOpenTime(BOUNDARY));
        // Mid-interval the forming candle is excluded
        assertEquals(BOUNDARY - HOUR, CandleInterval.ONE_HOUR.lastClosedOpenTime(BOUNDARY + HOUR - 1));
        assertEquals(BOUNDARY, CandleInterval.ONE_HOUR.lastClosedOpenTime(BOUNDARY + HOUR));
    }

    @Test
    void nextAndPreviousOpenTime_shiftByOneInterval() {
        assertEquals(HOUR, CandleInterval.ONE_HOUR.nextOpenTime(0));
        assertEquals(-HOUR, CandleInterval.ONE_HOUR.previousOpenTime(0));
        assertEquals(2 * HOUR, CandleInterval.ONE_HOUR.nextOpenTime(HOUR));
    }

    @Test
    void intervalsHaveExpectedLengths() {
        assertEquals(60_000L, CandleInterval.ONE_MINUTE.getMillis());
        assertEquals(5 * 60_000L, CandleInterval.FIVE_MINUTES.getMillis());
        assertEquals(24 * HOUR, CandleInterval.ONE_DAY.getMillis());
    }
}
