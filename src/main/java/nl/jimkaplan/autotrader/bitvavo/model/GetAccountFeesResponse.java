package nl.jimkaplan.autotrader.bitvavo.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Represents the response data for account fees.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GetAccountFeesResponse {

    /**
     * The trading fees and volume for your account.
     */
    private Fees fees;

    /**
     * Represents the trading fees and volume information.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Fees {

        /**
         * The fee for trades that take liquidity from the order book.
         * Example: 0.0025
         */
        private BigDecimal taker;

        /**
         * The fee for trades that add liquidity to the order book.
         * Example: 0.0015
         */
        private BigDecimal maker;

        /**
         * Your trading volume in the last 30 days measured in EUR.
         * Example: 10000.00
         */
        private BigDecimal volume;
    }
}