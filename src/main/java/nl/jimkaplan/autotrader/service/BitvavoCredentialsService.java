package nl.jimkaplan.autotrader.service;

import lombok.extern.slf4j.Slf4j;
import nl.jimkaplan.autotrader.controller.BitvavoCredentialsResponse;
import nl.jimkaplan.autotrader.model.document.BitvavoCredentials;
import nl.jimkaplan.autotrader.repository.BitvavoCredentialsRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

/**
 * The system-wide Bitvavo API key: what the live engine signs its orders with and what the ledger
 * reads the account with. One key for the account, stored encrypted, not bound to a bot.
 *
 * <p>Only the callers that sign a request see the plaintext; the key itself is never logged, never
 * returned by an API response and never written anywhere but its encrypted form.</p>
 */
@Slf4j
@Service
public class BitvavoCredentialsService {

    private final BitvavoCredentialsRepository repository;
    private final EncryptionService encryptionService;

    public BitvavoCredentialsService(BitvavoCredentialsRepository repository,
                                     EncryptionService encryptionService) {
        this.repository = repository;
        this.encryptionService = encryptionService;
    }

    /**
     * @return The stored key with its plaintext filled in, or empty when none is configured
     */
    public Optional<BitvavoCredentials> get() {
        return repository.findById(BitvavoCredentials.ID).map(this::decrypt);
    }

    /**
     * @return Whether a key is configured
     */
    public boolean configured() {
        return repository.existsById(BitvavoCredentials.ID);
    }

    /**
     * Store the account's key, replacing whatever was stored before.
     *
     * @param apiKey     The API key
     * @param apiSecret  The API secret
     * @param operatorId The operator id Bitvavo expects on orders, when it is known
     * @return The stored key, with its plaintext filled in
     * @throws IllegalArgumentException if the key or the secret is missing
     */
    public BitvavoCredentials save(String apiKey, String apiSecret, Long operatorId) {
        if (apiKey == null || apiKey.isBlank() || apiSecret == null || apiSecret.isBlank()) {
            throw new IllegalArgumentException("The API key and its secret are both required");
        }
        BitvavoCredentials stored = repository.save(BitvavoCredentials.builder()
                .id(BitvavoCredentials.ID)
                .encryptedApiKey(encryptionService.encrypt(apiKey.trim()))
                .encryptedApiSecret(encryptionService.encrypt(apiSecret.trim()))
                .operatorId(operatorId)
                .keyVersion(1)
                .updatedTime(Instant.now())
                .build());
        log.info("Stored the Bitvavo API key (operator {})", operatorId);
        return decrypt(stored);
    }

    /**
     * @return A hint that identifies the stored key without revealing it, e.g. {@code …f3a1}, or
     *         empty when none is configured
     */
    public Optional<String> hint() {
        return get().map(BitvavoCredentialsService::hintOf);
    }

    /**
     * @return The masked view both the API and the settings page read: whether a key is configured,
     *         a hint that identifies it, the operator id and when it was stored — never the key and
     *         never the secret. It derives everything from the one entity it read.
     */
    public BitvavoCredentialsResponse view() {
        return get().map(credentials -> new BitvavoCredentialsResponse(true,
                        hintOf(credentials), credentials.getOperatorId(), credentials.getUpdatedTime()))
                .orElseGet(() -> new BitvavoCredentialsResponse(false, null, null, null));
    }

    private static String hintOf(BitvavoCredentials credentials) {
        return credentials.getApiKey() == null || credentials.getApiKey().length() <= 4
                ? "…"
                : "…" + credentials.getApiKey().substring(credentials.getApiKey().length() - 4);
    }

    private BitvavoCredentials decrypt(BitvavoCredentials stored) {
        stored.setApiKey(stored.getEncryptedApiKey() == null ? null
                : encryptionService.decrypt(stored.getEncryptedApiKey()));
        stored.setApiSecret(stored.getEncryptedApiSecret() == null ? null
                : encryptionService.decrypt(stored.getEncryptedApiSecret()));
        return stored;
    }
}
