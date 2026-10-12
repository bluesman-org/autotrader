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
 * One intended or executed live order, kept for review: dry-run fills, real fills and failures.
 *
 * <p>Maps to the {@code live_orders} collection.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Document(collection = "live_orders")
public class LiveOrder extends BaseDocument {

    @Field("strategy_id")
    private String strategyId;

    @Field("market")
    private String market;

    /**
     * "BUY" or "SELL".
     */
    @Field("side")
    private String side;

    /**
     * "DRY_RUN", "FILLED" or "FAILED".
     */
    @Field("status")
    private String status;

    /**
     * Whether this order was simulated instead of sent.
     */
    @Field("dry_run")
    private boolean dryRun;

    /**
     * The open time of the closed candle whose signal triggered the order.
     */
    @Field("signal_open_time")
    private long signalOpenTime;

    /**
     * The rule set that fired, e.g. "entry ALL [MACD_BULLISH_CROSSOVER]".
     */
    @Field("reason")
    private String reason;

    /**
     * EUR spent or received, before fees.
     */
    @Field("amount_quote")
    private Double amountQuote;

    /**
     * Base asset quantity, filled or estimated.
     */
    @Field("quantity")
    private Double quantity;

    /**
     * Fill or estimated price in EUR.
     */
    @Field("price")
    private Double price;

    /**
     * Fee paid, in EUR.
     */
    @Field("fee")
    private Double fee;

    /**
     * The exchange's order ID; null for dry-run orders.
     */
    @Field("order_id")
    private String orderId;

    @Field("error_message")
    private String errorMessage;

    @Field("created_time")
    private Instant createdTime;
}
