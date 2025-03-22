package nl.jimkaplan.autotrader.service;

import lombok.RequiredArgsConstructor;
import nl.jimkaplan.autotrader.config.BitvavoConfig;
import nl.jimkaplan.autotrader.model.BitvavoAuthHeaders;
import org.apache.commons.codec.binary.Hex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;

@Service
@RequiredArgsConstructor
public class BitvavoAuthenticationService {

    private static final Logger log = LoggerFactory.getLogger(BitvavoAuthenticationService.class);
    private static final String HMAC_SHA_256 = "HmacSHA256";

    private final BitvavoConfig bitvavoConfig;

    /**
     * Creates the authentication headers required for Bitvavo API requests.
     *
     * @param method HTTP method (GET, POST, PUT, DELETE)
     * @param path   API endpoint path
     * @param body   Request body (for POST/PUT requests)
     * @return Object containing all required authentication headers
     */
    public BitvavoAuthHeaders createAuthHeaders(String method, String path, String body) {
        log.debug("Creating authentication headers for {} request to {}", method, path);

        // Check if API key and secret are set
        if (bitvavoConfig.getApiKey() == null || bitvavoConfig.getApiKey().isEmpty()) {
            log.error("Bitvavo API key is not set");
            throw new IllegalStateException("Bitvavo API key is not set. Please set the BITVAVO_API_KEY environment variable.");
        }
        if (bitvavoConfig.getApiSecret() == null || bitvavoConfig.getApiSecret().isEmpty()) {
            log.error("Bitvavo API secret is not set");
            throw new IllegalStateException("Bitvavo API secret is not set. Please set the BITVAVO_API_SECRET environment variable.");
        }

        log.debug("API key and secret validation successful");
        long timestamp = Instant.now().toEpochMilli();
        String signature = createSignature(timestamp, method, path, body);

        log.debug("Building authentication headers with timestamp: {}", timestamp);
        return BitvavoAuthHeaders.builder()
                .bitvavoBitvAvoAccessKey(bitvavoConfig.getApiKey())
                .bitvavoBitvAvoAccessSignature(signature)
                .bitvavoBitvAvoAccessTimestamp(String.valueOf(timestamp))
                .bitvavoBitvAvoAccessWindow(String.valueOf(bitvavoConfig.getWindow()))
                .build();
    }

    /**
     * Creates the HMAC-SHA256 signature required for Bitvavo API authentication.
     *
     * @param timestamp Current timestamp in milliseconds
     * @param method    HTTP method (GET, POST, PUT, DELETE)
     * @param path      API endpoint path
     * @param body      Request body (for POST/PUT requests)
     * @return Base64 encoded HMAC-SHA256 signature
     */
    private String createSignature(long timestamp, String method, String path, String body) {
        log.debug("Creating signature for {} request to {} at timestamp {}", method, path, timestamp);
        try {
            String message = timestamp + method + "/v2" + path;
            if (body != null && !body.isEmpty()) {
                message += body;
                log.debug("Including body in signature message");
            }

            log.debug("Initializing HMAC-SHA256 algorithm");
            Mac hmacSha256 = Mac.getInstance(HMAC_SHA_256);
            SecretKeySpec secretKeySpec = new SecretKeySpec(
                    bitvavoConfig.getApiSecret().getBytes(StandardCharsets.UTF_8),
                    HMAC_SHA_256
            );
            hmacSha256.init(secretKeySpec);
            byte[] hashBytes = hmacSha256.doFinal(message.getBytes(StandardCharsets.UTF_8));

            // Convert hash bytes to hexadecimal string
            return new String(Hex.encodeHex(hashBytes));

        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            log.error("Error creating signature for Bitvavo API", e);
            throw new RuntimeException("Error creating signature for Bitvavo API", e);
        }
    }
}
