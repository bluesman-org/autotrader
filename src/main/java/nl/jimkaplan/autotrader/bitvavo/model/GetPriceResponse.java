package nl.jimkaplan.autotrader.bitvavo.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Represents the prices of the latest trades on Bitvavo for all markets or a single market.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GetPriceResponse {
    /**
     * The market for which you requested the latest trade price.
     * Example: BTC-EUR
     */
    private String market;

    /**
     * The latest trade price for 1 unit of base currency in the amount of quote currency for the specified market.
     * Example: 34.243
     */
    private BigDecimal price;
}