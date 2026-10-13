package nl.jimkaplan.autotrader.bitvavo.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One entry of Bitvavo's order history, as returned by {@code GET /orders}.
 * Based on the <a href="https://docs.bitvavo.com/docs/rest-api/get-orders">Bitvavo API documentation</a>.
 *
 * <p>Only the fields the live engine reconciles against are modelled: the order's identity, when it
 * was created, its status and side, and the amounts and fee it filled with.</p>
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
     * The status of the order, e.g. "filled", "partiallyFilled", "canceled" or "expired".
     */
    private String status;

    /**
     * The side of the order: "buy" or "sell".
     */
    private String side;

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
}
