package nl.jimkaplan.autotrader.strategy.model.document;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import nl.jimkaplan.autotrader.model.document.BaseDocument;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;
import java.util.List;

/**
 * A stored backtest run: the effective settings, the sweep size and the best ranked entries.
 * Maps to the 'backtest_runs' collection.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Document(collection = "backtest_runs")
public class BacktestRun extends BaseDocument {

    /**
     * When the run was executed.
     */
    @Field("run_at")
    private Instant runAt;

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
     * Range start, epoch milliseconds.
     */
    @Field("from_time")
    private long fromTime;

    /**
     * Range end, epoch milliseconds.
     */
    @Field("to_time")
    private long toTime;

    /**
     * Number of bars the sweep evaluated over.
     */
    @Field("candle_count")
    private int candleCount;

    @Field("initial_capital")
    private double initialCapital;

    @Field("amount_per_buy")
    private double amountPerBuy;

    /**
     * Fee per side as a fraction.
     */
    @Field("fee_rate")
    private double feeRate;

    @Field("warm_up_candles")
    private int warmUpCandles;

    @Field("train_ratio")
    private double trainRatio;

    /**
     * The ranking metric's name, e.g. "TOTAL_RETURN".
     */
    @Field("ranking_metric")
    private String rankingMetric;

    @Field("combinations_evaluated")
    private int combinationsEvaluated;

    /**
     * The best entries, in rank order; the full ranked list is not stored. Null in the projected
     * list form, and left out of the JSON then.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Field("entries")
    private List<BacktestRunEntry> entries;
}
