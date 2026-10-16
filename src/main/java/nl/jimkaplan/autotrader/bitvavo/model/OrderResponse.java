package nl.jimkaplan.autotrader.bitvavo.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * One entry of Bitvavo's order history, as returned by {@code GET /orders}.
 * Based on the <a href="https://docs.bitvavo.com/docs/rest-api/get-orders">Bitvavo API documentation</a>.
 *
 * <p>Only the fields the live engine reconciles against are modelled: the order's identity, when it
 * was created, its status and side, and the amounts and fee it filled with. The ledger tab also
 * reads the order type, price, remaining amount and the individual fills.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderResponse {
    /**
     * Bitvavo identifier of the order. An orderId is unique per market.
     * Example: 1be6d0df-d5dc-4b53-a250-3376f3b393e6
     */
    private UUID orderId;

    /**
     * Your identifier of the order, only returned when it was specified in the request.
     */
    private UUID clientOrderId;

    /**
     * The market the order was created for (e.g., "BTC-EUR").
     */
    private String market;

    /**
     * The Unix timestamp in milliseconds when the order was created.
     * Example: 1706100650751
     */
    private Long created;

    /**
     * The Unix timestamp in milliseconds when the order was last updated.
     */
    private Long updated;

    /**
     * The status of the order, e.g. "filled", "partiallyFilled", "canceled" or "expired".
     */
    private String status;

    /**
     * The side of the order: "buy" or "sell".
     */
    private String side;

    /**
     * The type of the order: "market", "limit", "stopLoss", "stopLossLimit", "takeProfit" or
     * "takeProfitLimit".
     */
    private String orderType;

    /**
     * The quantity of the base currency remaining after the fills are subtracted from
     * {@code amount}. Not returned when the order was placed with {@code amountQuote}.
     */
    private BigDecimal amountRemaining;

    /**
     * The limit price of the order, when it has one.
     */
    private BigDecimal price;

    /**
     * The quantity of the base currency to buy or sell. Not returned when the order was placed with
     * {@code amountQuote}.
     */
    private BigDecimal amount;

    /**
     * The quantity of the quote currency to buy or sell. Not returned when the order was placed with
     * {@code amount}.
     */
    private BigDecimal amountQuote;

    /**
     * The total amount bought or sold in base currency.
     */
    private BigDecimal filledAmount;

    /**
     * The total amount bought or sold in quote currency.
     */
    private BigDecimal filledAmountQuote;

    /**
     * The amount paid to Bitvavo to process the order. This value is negative for rebates.
     */
    private BigDecimal feePaid;

    /**
     * The currency the fee is paid in.
     */
    private String feeCurrency;

    /**
     * The individual fills the order was executed in.
     */
    private List<Fills> fills;
}
