package nl.jimkaplan.autotrader.bitvavo.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Model for a fill in an order from Bitvavo.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Fills {
    /**
     * The UUID of this fill.
     * Example: 371c6bd3-d06d-4573-9f15-18697cd210e5
     */
    private UUID id;

    /**
     * A Unix timestamp when Bitvavo processed the fill.
     * Example: 1542967486256
     */
    private Instant timestamp;

    /**
     * The amount of base currency exchanged in the fill.
     * Example: 0.005
     */
    private BigDecimal amount;

    /**
     * The price of the fill in quote currency.
     * Example: 5000.1
     */
    private BigDecimal price;

    /**
     * Returns true when you are the taker for the fill.
     * Example: true
     */
    private Boolean taker;

    /**
     * The amount you pay Bitvavo in feeCurrency to process the fill. For more information about your fees, see GET /account/fees.
     * This value is negative for rebates.
     * If settled is false, this parameter is not included in the return parameters.
     * Example: 0.03
     */
    private BigDecimal fee;

    /**
     * The currency that the fee is paid in.
     * If settled is false, this object is not returned.
     * Example: EUR
     */
    private String feeCurrency;

    /**
     * On Bitvavo, trade settlement happens after an order is filled.
     * When the fill is complete and the fee is not yet paid, settled is false.
     */
    private Boolean settled;
}