package nl.jimkaplan.autotrader.strategy.model.document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import nl.jimkaplan.autotrader.model.document.BaseDocument;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Document class for storing historical candlesticks.
 * Maps to the 'candles' collection.
 *
 * <p>Candles are unique per market, interval and open time; a unique index
 * enforces this and serves the range queries used by the strategy engine.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Document(collection = "candles")
public class Candle extends BaseDocument {

    /**
     * Market in Bitvavo format, e.g. "BTC-EUR".
     */
    @Field("market")
    private String market;

    /**
     * Interval code as used by Bitvavo, e.g. "1h".
     */
    @Field("interval")
    private String interval;

    /**
     * Start of the candle interval, epoch milliseconds (UTC).
     */
    @Field("open_time")
    private long openTime;

    @Field("open")
    private BigDecimal open;

    @Field("high")
    private BigDecimal high;

    @Field("low")
    private BigDecimal low;

    @Field("close")
    private BigDecimal close;

    /**
     * Volume traded during the interval, in the base currency.
     */
    @Field("volume")
    private BigDecimal volume;

    /**
     * Time the candle was stored, for diagnostics.
     */
    @Field("ingested_at")
    private Instant ingestedAt;
}
