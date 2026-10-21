package nl.jimkaplan.autotrader.bitvavo.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Represents the best bid and ask of a market on Bitvavo: the sell price (bid) and the buy price
 * (ask) a market order would get right now.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GetBookResponse {
    /**
     * The market for which the book was requested.
     * Example: BTC-EUR
     */
    private String market;

    /**
     * The highest price a buyer is currently willing to pay — what a market sell gets.
     * Example: 34.243
     */
    private BigDecimal bid;

    /**
     * The amount of base currency available at the bid.
     */
    private BigDecimal bidSize;

    /**
     * The lowest price a seller is currently asking — what a market buy pays.
     * Example: 34.244
     */
    private BigDecimal ask;

    /**
     * The amount of base currency available at the ask.
     */
    private BigDecimal askSize;
}
