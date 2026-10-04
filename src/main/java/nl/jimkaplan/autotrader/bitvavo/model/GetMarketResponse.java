package nl.jimkaplan.autotrader.bitvavo.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Response model for market information from the Bitvavo API.
 * Based on the <a href="https://docs.bitvavo.com/docs/rest-api/get-markets">Bitvavo API documentation</a>.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GetMarketResponse {
    /**
     * The market for which information was requested (e.g., "BTC-EUR").
     */
    private String market;

    /**
     * The asset traded on the market (e.g., "BTC").
     */
    private String base;

    /**
     * The currency used to trade the asset (e.g., "EUR").
     */
    private String quote;

    /**
     * The smallest unit in which you can trade the base asset (e.g., 0.00000001).
     */
    private BigDecimal minOrderInBaseAsset;

    /**
     * The smallest amount in quote currency for which you can trade (e.g., 5).
     */
    private BigDecimal minOrderInQuoteAsset;

    /**
     * The maximum number of decimals in the amount of an order.
     */
    private Integer quantityDecimals;

    /**
     * The maximum number of decimal places allowed for amountQuote (quote currency amount).
     */
    private Integer notionalDecimals;
}
