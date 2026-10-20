package nl.jimkaplan.autotrader.strategy.model.document;

import com.fasterxml.jackson.annotation.JsonIgnore;
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
 * A live strategy: the same rules, parameters and sizing a backtest would use, signed with the
 * system-wide Bitvavo credential, with dry-run on by default.
 *
 * <p>Maps to the {@code live_strategies} collection.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Document(collection = "live_strategies")
public class LiveStrategy extends BaseDocument {

    /**
     * The operator-facing name. Required for new strategies; the startup initializer names the
     * documents that predate the field.
     */
    @Field("name")
    private String name;

    /**
     * Market in Bitvavo format, e.g. "BTC-EUR".
     */
    @Field("market")
    private String market;

    /**
     * Candle interval code, e.g. "1h".
     */
    @Field("interval")
    private String interval;

    @Field("macd_fast_period")
    private int macdFastPeriod;

    @Field("macd_slow_period")
    private int macdSlowPeriod;

    @Field("macd_signal_period")
    private int macdSignalPeriod;

    @Field("sar_acceleration")
    private double sarAcceleration;

    @Field("sar_maximum")
    private double sarMaximum;

    /**
     * The entry rule set's operator name, e.g. "ALL".
     */
    @Field("entry_operator")
    private String entryOperator;

    /**
     * The entry conditions' names, e.g. ["MACD_BULLISH_CROSSOVER"].
     */
    @Field("entry_conditions")
    private List<String> entryConditions;

    @Field("exit_operator")
    private String exitOperator;

    @Field("exit_conditions")
    private List<String> exitConditions;

    /**
     * Fixed EUR amount per buy, before fees.
     */
    @Field("amount_per_buy")
    private double amountPerBuy;

    /**
     * Leading candles the strategy never trades, matching the backtest's warm-up.
     */
    @Field("warm_up_candles")
    private int warmUpCandles;

    /**
     * When true (the default) orders are recorded but never sent to the exchange.
     */
    @Field("dry_run")
    private boolean dryRun;

    /**
     * Whether the engine should be running this strategy.
     */
    @Field("enabled")
    private boolean enabled;

    /**
     * Whether the strategy is archived: hidden from the live page and the forms, but kept with its
     * orders and positions so the history tab can still report what it did.
     */
    @Field("archived")
    private boolean archived;

    /**
     * Open time of the last candle this strategy evaluated; 0 when it never has.
     */
    @Field("last_evaluated_open_time")
    private long lastEvaluatedOpenTime;

    /**
     * The sequence number of the next real order; feeds the deterministic client order ID so a
     * record lost to a restore can be re-derived from the exchange. 0 when nothing was sent yet.
     * Engine bookkeeping only: kept out of the API so the next order's ID is not derivable from
     * the published strategy.
     */
    @JsonIgnore
    @Field("next_order_sequence")
    private long nextOrderSequence;

    /**
     * The fixed start of the runner's candle series: the strategy's first start minus the seed
     * window, persisted on the first seed and never moved, so restarts — and backtests started at
     * the same anchor with the warm-up set to the window length — replay exactly the same series.
     * This matters because the Parabolic SAR's flip timing is start-sensitive. 0 until the first
     * seed. Engine bookkeeping only: kept out of the API.
     */
    @JsonIgnore
    @Field("series_start_time")
    private long seriesStartTime;

    /**
     * When the current run started, written on every start so the live page can say how long the
     * strategy has been running. Engine bookkeeping only: kept out of the API.
     */
    @JsonIgnore
    @Field("last_started_time")
    private Instant lastStartedTime;

    /**
     * When the strategy was created.
     */
    @Field("created_time")
    private Instant createdTime;

    /**
     * @return Whether the strategy has ever run. The pair and interval lock on this, Delete turns
     *         into Archive, and the live page's edit form uses it to disable them; keeping the
     *         definition here stops those four callers from drifting apart.
     */
    @JsonIgnore
    public boolean hasStarted() {
        return lastStartedTime != null || seriesStartTime > 0;
    }
}
