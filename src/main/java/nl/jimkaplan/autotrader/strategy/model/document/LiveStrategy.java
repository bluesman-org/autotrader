package nl.jimkaplan.autotrader.strategy.model.document;

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
 * A live strategy: the same rules, parameters and sizing a backtest would use, tied to the
 * exchange credentials of a {@code BotConfiguration}, with dry-run on by default.
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
     * The bot configuration whose API key, secret and operator ID are used when not in dry-run.
     */
    @Field("bot_id")
    private String botId;

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
     * Open time of the last candle this strategy evaluated; 0 when it never has.
     */
    @Field("last_evaluated_open_time")
    private long lastEvaluatedOpenTime;

    /**
     * The sequence number of the next real order; feeds the deterministic client order ID so a
     * record lost to a restore can be re-derived from the exchange. 0 when nothing was sent yet.
     */
    @Field("next_order_sequence")
    private long nextOrderSequence;

    /**
     * When the strategy was created.
     */
    @Field("created_time")
    private Instant createdTime;
}
