package nl.jimkaplan.autotrader.model;

import lombok.Builder;
import lombok.Data;

/**
 * Contains the authentication headers required for Bitvavo API requests.
 */
@Data
@Builder
public class BitvavoAuthHeaders {
    /**
     * The Bitvavo API key.
     */
    private final String bitvavoBitvAvoAccessKey;
    
    /**
     * The HMAC-SHA256 signature for the request.
     */
    private final String bitvavoBitvAvoAccessSignature;
    
    /**
     * The timestamp of the request in milliseconds.
     */
    private final String bitvavoBitvAvoAccessTimestamp;
    
    /**
     * The time window in milliseconds for which the request is valid.
     */
    private final String bitvavoBitvAvoAccessWindow;
}