package nl.jimkaplan.autotrader.controller;

import lombok.Data;
import lombok.ToString;

/**
 * The request that stores or replaces the system-wide Bitvavo API key. The secret is write-only:
 * nothing ever returns it.
 */
@Data
@ToString(exclude = {"apiKey", "apiSecret"})
public class BitvavoCredentialsRequest {

    /**
     * The API key.
     */
    private String apiKey;

    /**
     * The API secret.
     */
    private String apiSecret;

    /**
     * The operator id Bitvavo expects on orders, when it is known.
     */
    private Long operatorId;
}
