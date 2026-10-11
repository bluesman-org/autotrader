package nl.jimkaplan.autotrader.strategy.model;

import java.util.Arrays;

/**
 * Candlestick intervals supported by the strategy engine.
 * The codes are the values used by the Bitvavo candlestick API.
 */
public enum CandleInterval {

    ONE_MINUTE("1m", 60_000L),
    FIVE_MINUTES("5m", 5 * 60_000L),
    FIFTEEN_MINUTES("15m", 15 * 60_000L),
    THIRTY_MINUTES("30m", 30 * 60_000L),
    ONE_HOUR("1h", 60 * 60_000L),
    TWO_HOURS("2h", 2 * 60 * 60_000L),
    FOUR_HOURS("4h", 4 * 60 * 60_000L),
    SIX_HOURS("6h", 6 * 60 * 60_000L),
    EIGHT_HOURS("8h", 8 * 60 * 60_000L),
    TWELVE_HOURS("12h", 12 * 60 * 60_000L),
    ONE_DAY("1d", 24 * 60 * 60_000L);

    private final String code;
    private final long millis;

    CandleInterval(String code, long millis) {
        this.code = code;
        this.millis = millis;
    }

    /**
     * Resolve an interval from its Bitvavo code, e.g. "1h".
     *
     * @param code The interval code
     * @return The matching interval
     * @throws IllegalArgumentException if the code is not supported
     */
    public static CandleInterval fromCode(String code) {
        return Arrays.stream(values())
                .filter(interval -> interval.code.equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported interval: " + code));
    }

    public String getCode() {
        return code;
    }

    public long getMillis() {
        return millis;
    }

    /**
     * Open time of the candle that contains the given timestamp, floored to the interval boundary.
     *
     * @param timestamp Epoch milliseconds
     * @return The open time of the containing candle
     */
    public long floorOpenTime(long timestamp) {
        return timestamp - Math.floorMod(timestamp, millis);
    }

    /**
     * Open time of the latest candle that has fully closed at or before the given time,
     * i.e. the latest open time {@code t} with {@code t + interval <= time}.
     *
     * @param timestamp Epoch milliseconds
     * @return The open time of the latest closed candle
     */
    public long lastClosedOpenTime(long timestamp) {
        return floorOpenTime(timestamp - millis);
    }

    /**
     * Open time of the candle following the given open time.
     *
     * @param openTime The open time of a candle
     * @return The open time of the next candle
     */
    public long nextOpenTime(long openTime) {
        return openTime + millis;
    }

    /**
     * Open time of the candle preceding the given open time.
     *
     * @param openTime The open time of a candle
     * @return The open time of the previous candle
     */
    public long previousOpenTime(long openTime) {
        return openTime - millis;
    }
}
