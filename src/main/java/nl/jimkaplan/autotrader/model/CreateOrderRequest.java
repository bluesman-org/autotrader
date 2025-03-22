package nl.jimkaplan.autotrader.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Request model for creating an order on Bitvavo.
 * Based on the <a href="https://docs.bitvavo.com/docs/rest-api/create-order">Bitvavo API documentation</a>.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CreateOrderRequest {
    /**
     * The market to create the order for.
     * Example, "BTC-EUR"
     */
    private String market;
    
    /**
     * The side of the order: "buy" or "sell".
     */
    private String side;
    
    /**
     * The order type: "market", "limit", "stopLoss", "stopLossLimit", "takeProfit", or "takeProfitLimit".
     * For limit orders, you need to set amount and price.
     * For market orders, you need to set either amount or amountQuote.
     */
    private String orderType;

    /**
     * Your (UUID) of the order.
     */
    private UUID orderId;

    /**
     * The amount of the base currency to buy or sell.
     * Example: 1.567
     */
    private BigDecimal amount;

    /**
     * The amount of the quote currency to buy or sell for the market, stopLoss, or takeProfit order types.
     * Example: 5000
     */
    private BigDecimal amountQuote;
    
    /**
     * The amount in quote currency for which you want to buy or sell 1 unit of the base currency.
     * Example: 6000
     */
    private BigDecimal price;

    /**
     * The price at which you want to buy or sell 1 unit of base currency in the quote currency.
     * Example: 4000
     */
    private BigDecimal triggerAmount;
    
    /**
     * The type of trigger that fills the order when the condition is met.
     * Possible values: [price]
     */
    private String triggerType;
    
    /**
     * The price which triggers the order to be filled for stopLoss, stopLossLimit, takeProfit, or takeProfitLimit types of orders.
     * Possible values: [lastTrade, bestBid, bestAsk, midPrice]
     */
    private BigDecimal triggerReference;
    
    /**
     * How long an order stays active for.
     * Possible values: [GTC, IOC, FOK]
     * Default value: GTC
     * "GTC" (Good Till Canceled), "IOC" (Immediate Or Cancel), or "FOK" (Fill Or Kill).
     */
    private String timeInForce;
    
    /**
     * Whether the order is a post-only order.
     * Default value: false
     */
    private Boolean postOnly;
    
    /**
     * Prevents self-trading for conflicting orders.
     * Possible values: [cancelBoth, cancelNewest, cancelOldest, decrementAndCancel]
     * Default value: decrementAndCancel
     */
    private Boolean selfTradePrevention;

    /**
     * Indicates whether the response returns all parameters (true) or only the HTTP status code (false).
     * Default value: true
     */
    private Boolean responseRequired;
}