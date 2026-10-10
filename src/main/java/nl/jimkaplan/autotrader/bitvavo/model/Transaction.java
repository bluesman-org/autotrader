package nl.jimkaplan.autotrader.bitvavo.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A single transaction from the Bitvavo transaction history.
 * Based on the <a href="https://docs.bitvavo.com/docs/rest-api/get-transaction-history/">Bitvavo API documentation</a>.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Transaction {

    /**
     * The unique identifier of the transaction.
     */
    private String transactionId;

    /**
     * The moment the transaction was executed.
     */
    private Instant executedAt;

    /**
     * The type of transaction (e.g. buy, sell, deposit, withdrawal).
     */
    private String type;

    /**
     * The currency of the price.
     */
    private String priceCurrency;

    /**
     * The price per unit in the price currency.
     */
    private BigDecimal priceAmount;

    /**
     * The currency that was sent.
     */
    private String sentCurrency;

    /**
     * The amount that was sent.
     */
    private BigDecimal sentAmount;

    /**
     * The currency that was received.
     */
    private String receivedCurrency;

    /**
     * The amount that was received.
     */
    private BigDecimal receivedAmount;

    /**
     * The currency of the fees.
     */
    private String feesCurrency;

    /**
     * The fees paid for the transaction.
     */
    private BigDecimal feesAmount;

    /**
     * The address involved in the transaction, when applicable.
     */
    private String address;
}
