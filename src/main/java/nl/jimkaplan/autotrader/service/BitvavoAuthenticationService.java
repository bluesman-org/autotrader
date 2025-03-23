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
            throw new IllegalStateException("Bitvavo API key is not set. Please set the BITVAVO_API_KEY environment variable.");
        }
        if (bitvavoConfig.getApiSecret() == null || bitvavoConfig.getApiSecret().isEmpty()) {
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

            log.debug("Message for signature: {}", message);
            log.debug("Initializing {} algorithm", HMAC_SHA_256);

            Mac hmacSha256 = getMacInstance();
            SecretKeySpec secretKeySpec = createSecretKeySpec(bitvavoConfig.getApiSecret());
            initMac(hmacSha256, secretKeySpec);

            byte[] hashBytes = doFinal(hmacSha256, message);

            // Convert hash bytes to hexadecimal string
            return new String(Hex.encodeHex(hashBytes));

        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            // Exception handling for crypto operations
            throw new RuntimeException("Error creating signature for Bitvavo API", e);
        }
    }

    /**
     * Gets an instance of Mac for the HMAC-SHA256 algorithm.
     * Extracted for testability.
     *
     * @return Mac instance
     * @throws NoSuchAlgorithmException if the algorithm is not available
     */
    protected Mac getMacInstance() throws NoSuchAlgorithmException {
        return Mac.getInstance(HMAC_SHA_256);
    }

    /**
     * Creates a SecretKeySpec from the given secret.
     * Extracted for testability.
     *
     * @param secret The secret key
     * @return SecretKeySpec instance
     */
    protected SecretKeySpec createSecretKeySpec(String secret) {
        return new SecretKeySpec(
                secret.getBytes(StandardCharsets.UTF_8),
                HMAC_SHA_256
        );
    }

    /**
     * Initializes the Mac with the given key.
     * Extracted for testability.
     *
     * @param mac The Mac instance
     * @param key The SecretKeySpec
     * @throws InvalidKeyException if the key is invalid
     */
    protected void initMac(Mac mac, SecretKeySpec key) throws InvalidKeyException {
        mac.init(key);
    }

    /**
     * Computes the final hash.
     * Extracted for testability.
     *
     * @param mac     The Mac instance
     * @param message The message to hash
     * @return The hash bytes
     */
    protected byte[] doFinal(Mac mac, String message) {
        return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
    }
}
