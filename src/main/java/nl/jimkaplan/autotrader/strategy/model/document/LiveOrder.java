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
     * "PENDING", "DRY_RUN", "REJECTED", "FILLED", "FAILED" or "SKIPPED". A real order is written as
     * {@code PENDING} before it is sent (so an ambiguous failure stays reconcilable), then updated
     * to {@code FILLED}; to {@code FAILED} for an error or a terminal non-filled exchange status;
     * or left {@code PENDING} when the exchange says it can still fill. {@code REJECTED} is a
     * local, pre-flight rejection that never reached the exchange.
     */
    @Field("status")
    private String status;

    /**
     * Whether this order was simulated instead of sent.
     */
    @Field("dry_run")
    private boolean dryRun;

    /**
     * The exchange's client order ID, set only for a real order before it is sent. It lets a boot
     * attribute the exchange's order history back to this row; null for dry-run and legacy rows.
     */
    @Field("client_order_id")
    private String clientOrderId;

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

    /**
     * Whether the engine must stand down for this strategy until reconciliation settles this row.
     * Set on a real pre-write and on an ambiguous failure; cleared when the row fills, is rejected
     * terminally by the exchange, or is proven never accepted. Engine bookkeeping only: kept out of
     * the API.
     */
    @JsonIgnore
    @Field("awaiting_reconciliation")
    private boolean awaitingReconciliation;

    /**
     * Whether this is a real fill the engine did not place: an operator-reconciled manual close
     * made on the exchange, attached to the strategy's open position. Such a row has an exchange
     * order id but no client order id, and is left alone by the boot reconciliation's client-order
     * attribution. Absent on older rows, so it reads as false.
     */
    @Field("external")
    private boolean external;

    @Field("error_message")
    private String errorMessage;

    @Field("created_time")
    private Instant createdTime;
}
