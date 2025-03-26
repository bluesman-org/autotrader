package nl.jimkaplan.autotrader.tradingview.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.jimkaplan.autotrader.model.BotConfiguration;
import nl.jimkaplan.autotrader.repository.BotConfigurationRepository;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * Service for managing bot configurations.
 * Handles encryption of sensitive data and CRUD operations for bot configurations.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BotConfigurationService {

    private final BotConfigurationRepository botConfigurationRepository;
    private final EncryptionService encryptionService;

    /**
     * Save a bot configuration with encrypted API key and secret.
     *
     * @param config The bot configuration to save
     * @return The saved bot configuration
     */
    public BotConfiguration saveBotConfiguration(BotConfiguration config) {
        // Encrypt sensitive data
        String encryptedApiKey = encryptionService.encrypt(config.getApiKey());
        String encryptedApiSecret = encryptionService.encrypt(config.getApiSecret());

        // Set encrypted values
        config.setEncryptedApiKey(encryptedApiKey);
        config.setEncryptedApiSecret(encryptedApiSecret);
        config.setKeyVersion(1); // Initial key version

        // Clear transient fields
        config.setApiKey(null);
        config.setApiSecret(null);

        // Save to database
        return botConfigurationRepository.save(config);
    }

    /**
     * Get an active bot configuration by bot ID, with decrypted API key and secret.
     * Only returns configurations that are active.
     *
     * @param botId The bot ID
     * @return Optional containing the bot configuration if found and active
     */
    public Optional<BotConfiguration> getBotConfiguration(String botId) {
        return botConfigurationRepository.findByBotIdAndActive(botId, true)
                .map(this::decryptSensitiveData);
    }

    /**
     * Get a bot configuration by bot ID regardless of active status, with decrypted API key and secret.
     *
     * @param botId The bot ID
     * @return Optional containing the bot configuration if found
     */
    public Optional<BotConfiguration> getBotConfigurationIncludingInactive(String botId) {
        return botConfigurationRepository.findByBotId(botId)
                .map(this::decryptSensitiveData);
    }

    /**
     * Get all active bot configurations, with decrypted API keys and secrets.
     * Only returns configurations that are active.
     *
     * @return List of all active bot configurations
     */
    public List<BotConfiguration> getAllBotConfigurations() {
        return botConfigurationRepository.findByActive(true).stream()
                .map(this::decryptSensitiveData)
                .toList();
    }

    /**
     * Get all bot configurations including inactive ones, with decrypted API keys and secrets.
     *
     * @return List of all bot configurations
     */
    public List<BotConfiguration> getAllBotConfigurationsIncludingInactive() {
        return botConfigurationRepository.findAll().stream()
                .map(this::decryptSensitiveData)
                .toList();
    }

    /**
     * Deactivate a bot configuration by bot ID.
     * This sets the active field to false instead of deleting the configuration.
     *
     * @param botId The bot ID
     * @return true if the configuration was found and deactivated, false otherwise
     */
    public boolean deactivateBotConfiguration(String botId) {
        return botConfigurationRepository.findByBotId(botId)
                .map(config -> {
                    config.setActive(false);
                    botConfigurationRepository.save(config);
                    return true;
                })
                .orElse(false);
    }

    /**
     * Activate a previously deactivated bot configuration by bot ID.
     *
     * @param botId The bot ID
     * @return true if the configuration was found and activated, false otherwise
     */
    public boolean activateBotConfiguration(String botId) {
        return botConfigurationRepository.findByBotId(botId)
                .map(config -> {
                    config.setActive(true);
                    botConfigurationRepository.save(config);
                    return true;
                })
                .orElse(false);
    }

    /**
     * Generate a new webhook API key for a bot.
     *
     * @param botId The bot ID
     * @return The generated webhook API key
     */
    public String generateWebhookApiKey(String botId) {
        // Generate a secure random key
        String webhookApiKey = generateSecureRandomKey();

        // Hash the key for storage
        String webhookKeyHash = hashApiKey(webhookApiKey);

        // Update the bot configuration with the hashed key
        botConfigurationRepository.findByBotId(botId).ifPresent(config -> {
            config.setWebhookKeyHash(webhookKeyHash);
            botConfigurationRepository.save(config);
        });

        // Return the unhashed key (this is the only time it will be available)
        return webhookApiKey;
    }

    /**
     * Validate a webhook API key for a bot.
     *
     * @param botId  The bot ID
     * @param apiKey The webhook API key to validate
     * @return true if the key is valid, false otherwise
     */
    public boolean validateWebhookApiKey(String botId, String apiKey) {
        return botConfigurationRepository.findByBotId(botId)
                .map(config -> {
                    String storedHash = config.getWebhookKeyHash();
                    String providedHash = hashApiKey(apiKey);
                    return storedHash != null && storedHash.equals(providedHash);
                })
                .orElse(false);
    }

    /**
     * Generate a new bot ID.
     * Generates a random 6-character string with allowed characters (capital letters, small case letters, and numbers).
     *
     * @return The generated bot ID
     */
    String generateBotId() {
        String allowedChars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(6);

        for (int i = 0; i < 6; i++) {
            int randomIndex = random.nextInt(allowedChars.length());
            sb.append(allowedChars.charAt(randomIndex));
        }

        return sb.toString();
    }

    /**
     * Decrypt sensitive data in a bot configuration.
     *
     * @param config The bot configuration with encrypted data
     * @return The bot configuration with decrypted data in transient fields
     */
    private BotConfiguration decryptSensitiveData(BotConfiguration config) {
        if (config.getEncryptedApiKey() != null) {
            config.setApiKey(encryptionService.decrypt(config.getEncryptedApiKey()));
        }

        if (config.getEncryptedApiSecret() != null) {
            config.setApiSecret(encryptionService.decrypt(config.getEncryptedApiSecret()));
        }

        return config;
    }

    /**
     * Generate a secure random key for webhook authentication.
     *
     * @return The generated key
     */
    private String generateSecureRandomKey() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Hash an API key using SHA-256.
     *
     * @param apiKey The API key to hash
     * @return The hashed API key
     */
    private String hashApiKey(String apiKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(apiKey.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            //TODO cover this case with unit tests
            throw new RuntimeException("Hashing failed", e);
        }
    }
}
