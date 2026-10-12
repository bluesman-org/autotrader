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

/**
 * The live position of one strategy. One open position at a time, closed in place so the history
 * stays queryable.
 *
 * <p>Maps to the {@code live_positions} collection.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Document(collection = "live_positions")
public class LivePosition extends BaseDocument {

    @Field("strategy_id")
    private String strategyId;

    @Field("market")
    private String market;

    /**
     * "OPEN" or "CLOSED".
     */
    @Field("status")
    private String status;

    /**
     * Base asset quantity held.
     */
    @Field("quantity")
    private double quantity;

    /**
     * Entry price in EUR.
     */
    @Field("entry_price")
    private double entryPrice;

    /**
     * Fee paid on entry, in EUR (estimated for dry-run positions).
     */
    @Field("entry_fee")
    private Double entryFee;

    /**
     * Open time of the candle whose entry signal opened the position.
     */
    @Field("entry_time")
    private long entryTime;

    /**
     * Whether the position was opened in dry-run.
     */
    @Field("dry_run")
    private boolean dryRun;

    @Field("exit_price")
    private Double exitPrice;

    /**
     * Open time of the candle whose exit signal closed the position.
     */
    @Field("exit_time")
    private Long exitTime;

    /**
     * Realised profit in EUR, after the simulated or paid fees.
     */
    @Field("profit")
    private Double profit;

    @Field("created_time")
    private Instant createdTime;
}
