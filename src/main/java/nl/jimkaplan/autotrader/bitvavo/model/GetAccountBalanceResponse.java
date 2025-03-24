package nl.jimkaplan.autotrader.bitvavo.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Response object for account balance information.
 * Represents a single asset balance entry.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GetAccountBalanceResponse {
    /**
     * The asset for which the balance was returned.
     * Example: BTC
     */
    private String symbol;

    /**
     * The balance what is available for use.
     * Example: 1.57593193
     */
    private BigDecimal available;

    /**
     * The balance that is currently reserved for open orders.
     * Example: 0.74832374
     */
    private BigDecimal inOrder;
}
