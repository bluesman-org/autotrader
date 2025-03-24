package nl.jimkaplan.autotrader.bitvavo.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Response model for an order from Bitvavo.
 * Based on the <a href="https://docs.bitvavo.com/docs/rest-api/create-order">Bitvavo API documentation</a>.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateOrderResponse {
    /**
     * The (UUID) of an order. An orderId is unique per market. Different markets can use the same orderId values.
     * Example: 1be6d0df-d5dc-4b53-a250-3376f3b393e6
     */
    private UUID orderId;

    /**
     * A (UUID) used to track the status of an order.
     * If you do not set clientOrderId, the field is omitted from all responses and WebSocket subscriptions.
     * Example: 2be7d0df-d8dc-7b93-a550-8876f3b393e9
     */
    private UUID clientOrderId;

    /**
     * The market the order was created for (e.g., "BTC-EUR").
     */
    private String market;

    /**
     * The Unix timestamp when the order was created.
     * Example: 1706100650751
     */
    private Instant created;

    /**
     * The Unix timestamp when the order was last updated.
     * Example: 1706100650751
     */
    private Instant updated;

    /**
     * The status of an order. Possible values:
     * <ul>
     *   <li><b>new</b>: the orderId has been added to the order book and has not been filled.</li>
     *   <li><b>awaitingTrigger</b>: the conditions to fill an order haven't been met.</li>
     *   <li><b>canceled</b>: the orderId has been manually removed from the order book.</li>
     *   <li><b>canceledAuction</b>: Bitvavo cancelled the market order during an auction phase.</li>
     *   <li><b>canceledSelfTradePrevention</b>: Bitvavo cancelled the order because you are both the buyer and the seller.</li>
     *   <li><b>canceledIOC</b>: the filledAmount was filled against existing orders. The rest of the order has been cancelled and the amountRemaining returned to your balance.</li>
     *   <li><b>canceledFOK</b>: the amount could not be filled against existing orders. The order has been cancelled and the onHold currency is processed.</li>
     *   <li><b>canceledMarketProtection</b>: Bitvavo cancelled the order because the spread is too high.</li>
     *   <li><b>canceledPostOnly</b>: Bitvavo cancelled the order because it can't be filled in accordance with your postOnly requirements.</li>
     *   <li><b>filled</b>: all trades necessary to complete the order have been filled.</li>
     *   <li><b>partiallyFilled</b>: the order is still active. Bitvavo needs to receive more matching orders to completely fill the order.</li>
     *   <li><b>expired</b>: the order is no longer active because the timeInForce condition has been met.</li>
     *   <li><b>rejected</b>: Bitvavo could not accept the order.</li>
     * </ul>
     */
    private String status;

    /**
     * The side of the order: "buy" or "sell".
     */
    private String side;

    /**
     * The order type: "market", "limit", "stopLoss", "stopLossLimit", "takeProfit", or "takeProfitLimit".
     */
    private String orderType;

    /**
     * The amount of the base currency to buy or sell.
     * If you set amountQuote in your order, amount is not returned.
     */
    private BigDecimal amount;

    /**
     * The amount of the base currency remaining after the sum of fills is subtracted from the amount.
     * If you set amountQuote in your order, amountRemaining is not returned.
     */
    private BigDecimal amountRemaining;

    /**
     * The price you set for a limit, stopLossLimit, takeProfit, or a takeProfitLimit order.
     * For other types of orders, the price is not returned.
     */
    private BigDecimal price;

    /**
     * Whether the amount is specified in the quote currency.
     */
    private BigDecimal amountQuote;

    /**
     * The amount of quote currency remaining after the sum of all fills is subtracted from amountQuote.
     * If you set amount in the order, amountQuoteRemaining is not returned.
     */
    private BigDecimal amountQuoteRemaining;

    /**
     * The amount of onHoldCurrency locked while an order is not yet settled after an order is filled. All funds are released when order is filled.
     * Example: 9109.61
     */
    private BigDecimal onHold;


    /**
     * The currency that is onHold while the order is processed. This depends on the side you set for the orderId.
     * Example: BTC
     */
    private String onHoldCurrency;

    /**
     * The price calculated using triggerAmount and triggerType for the stopLoss, stopLossLimit, takeProfit, or takeProfitLimit types of orders.
     * For example, when you set triggerAmount to 4000 and triggerType to price,
     * your order is placed in a market when the triggerReference price in quote currency reaches 4000.
     */
    private BigDecimal triggerPrice;

    /**
     * The price at which you want to buy or sell 1 unit of base currency in the quote currency.
     * This value and triggerType are used to calculate triggerPrice.
     */
    private BigDecimal triggerAmount;

    /**
     * The type of trigger you set to fill the order when the condition is met. This value and triggerAmount are used to calculate triggerPrice.
     * Possible values: [price]
     */
    private String triggerType;

    /**
     * The price you set to trigger the order to be filled for the stopLoss, stopLossLimit, takeProfit, or takeProfitLimit types of orders.
     * Possible values: [lastTrade, bestBid, bestAsk, midPrice]
     */
    private BigDecimal triggerReference;

    /**
     * The total amount bought or sold in base currency.
     */
    private BigDecimal filledAmount;

    /**
     * The total amount bought or sold in quote currency.
     */
    private BigDecimal filledAmountQuote;

    /**
     * The amount you pay Bitvavo to process the order. This value is negative for rebates.
     * For more information, see GET /account/fees.
     */
    private BigDecimal feePaid;

    /**
     * The currency of the feePaid.
     * Example: EUR
     */
    private String feeCurrency;

    /**
     * A JSON object containing all fills for orderId.
     */
    private List<Fills> fills;

    /**
     * The value you set to prevent self-trading for conflicting orders of any type
     * Possible values: [decrementAndCancel, cancelOldest, cancelNewest, cancelBoth]
     */
    private String selfTradePrevention;

    /**
     * Returns true when the orderId is visible in the order book for a market.
     */
    private Boolean visible;

    /**
     * The value you set for how long an order stays active.
     * Possible values: [GTC, IOC, FOK]
     * * "GTC" (Good Till Canceled), "IOC" (Immediate Or Cancel), or "FOK" (Fill Or Kill).
     */
    private String timeInForce;

    /**
     * The value you set for how your fees are calculated for limit, stopLossLimit, or takeProfitLimit types of orders.
     */
    private Boolean postOnly;

    /**
     * Must be false because market protection can no longer be disabled.
     */
    private Boolean disableMarketProtection;

    // set disableMarketProtection to false always
    public Boolean getDisableMarketProtection() {
        return false;
    }

}