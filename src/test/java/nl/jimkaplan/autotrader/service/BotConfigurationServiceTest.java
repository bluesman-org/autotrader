package nl.jimkaplan.autotrader.service;

import nl.jimkaplan.autotrader.model.document.BotConfiguration;
import nl.jimkaplan.autotrader.repository.BotConfigurationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BotConfigurationServiceTest {

    @Mock
    private BotConfigurationRepository botConfigurationRepository;

    @Mock
    private EncryptionService encryptionService;

    @InjectMocks
    private BotConfigurationService botConfigurationService;

    private BotConfiguration testBotConfig;
    private final String TEST_BOT_ID = "test-bot-id";
    private final String TEST_API_KEY = "test-api-key";
    private final String TEST_API_SECRET = "test-api-secret";
    private final String TEST_TRADING_PAIR = "BTCEUR";
    private final String ENCRYPTED_API_KEY = "encrypted-api-key";
    private final String ENCRYPTED_API_SECRET = "encrypted-api-secret";

    @BeforeEach
    void setUp() {
        testBotConfig = BotConfiguration.builder()
                .botId(TEST_BOT_ID)
                .apiKey(TEST_API_KEY)
                .apiSecret(TEST_API_SECRET)
                .tradingPair(TEST_TRADING_PAIR)
                .build();
    }

    @Test
    void saveBotConfiguration_shouldEncryptSensitiveDataAndSave() {
        // Arrange
        when(encryptionService.encrypt(TEST_API_KEY)).thenReturn(ENCRYPTED_API_KEY);
        when(encryptionService.encrypt(TEST_API_SECRET)).thenReturn(ENCRYPTED_API_SECRET);
        when(botConfigurationRepository.save(any(BotConfiguration.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        BotConfiguration savedConfig = botConfigurationService.saveBotConfiguration(testBotConfig);

        // Assert
        assertNotNull(savedConfig);
        assertEquals(TEST_BOT_ID, savedConfig.getBotId());
        assertEquals(ENCRYPTED_API_KEY, savedConfig.getEncryptedApiKey());
        assertEquals(ENCRYPTED_API_SECRET, savedConfig.getEncryptedApiSecret());
        assertEquals(TEST_TRADING_PAIR, savedConfig.getTradingPair());
        assertNull(savedConfig.getApiKey()); // Transient fields should be cleared
        assertNull(savedConfig.getApiSecret()); // Transient fields should be cleared
        assertEquals(1, savedConfig.getKeyVersion()); // Initial key version

        verify(encryptionService).encrypt(TEST_API_KEY);
        verify(encryptionService).encrypt(TEST_API_SECRET);
        verify(botConfigurationRepository).save(any(BotConfiguration.class));
    }

    @Test
    void getBotConfiguration_shouldDecryptSensitiveData() {
        // Arrange
        BotConfiguration storedConfig = BotConfiguration.builder()
                .botId(TEST_BOT_ID)
                .encryptedApiKey(ENCRYPTED_API_KEY)
                .encryptedApiSecret(ENCRYPTED_API_SECRET)
                .tradingPair(TEST_TRADING_PAIR)
                .keyVersion(1)
                .active(true)
                .build();

        when(botConfigurationRepository.findByBotIdAndActive(TEST_BOT_ID, true)).thenReturn(Optional.of(storedConfig));
        when(encryptionService.decrypt(ENCRYPTED_API_KEY)).thenReturn(TEST_API_KEY);
        when(encryptionService.decrypt(ENCRYPTED_API_SECRET)).thenReturn(TEST_API_SECRET);

        // Act
        Optional<BotConfiguration> result = botConfigurationService.getBotConfiguration(TEST_BOT_ID);

        // Assert
        assertTrue(result.isPresent());
        BotConfiguration retrievedConfig = result.get();
        assertEquals(TEST_BOT_ID, retrievedConfig.getBotId());
        assertEquals(TEST_API_KEY, retrievedConfig.getApiKey());
        assertEquals(TEST_API_SECRET, retrievedConfig.getApiSecret());
        assertEquals(TEST_TRADING_PAIR, retrievedConfig.getTradingPair());
        assertTrue(retrievedConfig.getActive());

        verify(botConfigurationRepository).findByBotIdAndActive(TEST_BOT_ID, true);
        verify(encryptionService).decrypt(ENCRYPTED_API_KEY);
        verify(encryptionService).decrypt(ENCRYPTED_API_SECRET);
    }

    @Test
    void getBotConfigurationIncludingInactive_shouldDecryptSensitiveData() {
        // Arrange
        BotConfiguration storedConfig = BotConfiguration.builder()
                .botId(TEST_BOT_ID)
                .encryptedApiKey(ENCRYPTED_API_KEY)
                .encryptedApiSecret(ENCRYPTED_API_SECRET)
                .tradingPair(TEST_TRADING_PAIR)
                .keyVersion(1)
                .active(false)
                .build();

        when(botConfigurationRepository.findByBotId(TEST_BOT_ID)).thenReturn(Optional.of(storedConfig));
        when(encryptionService.decrypt(ENCRYPTED_API_KEY)).thenReturn(TEST_API_KEY);
        when(encryptionService.decrypt(ENCRYPTED_API_SECRET)).thenReturn(TEST_API_SECRET);

        // Act
        Optional<BotConfiguration> result = botConfigurationService.getBotConfigurationIncludingInactive(TEST_BOT_ID);

        // Assert
        assertTrue(result.isPresent());
        BotConfiguration retrievedConfig = result.get();
        assertEquals(TEST_BOT_ID, retrievedConfig.getBotId());
        assertEquals(TEST_API_KEY, retrievedConfig.getApiKey());
        assertEquals(TEST_API_SECRET, retrievedConfig.getApiSecret());
        assertEquals(TEST_TRADING_PAIR, retrievedConfig.getTradingPair());
        assertFalse(retrievedConfig.getActive());

        verify(botConfigurationRepository).findByBotId(TEST_BOT_ID);
        verify(encryptionService).decrypt(ENCRYPTED_API_KEY);
        verify(encryptionService).decrypt(ENCRYPTED_API_SECRET);
    }

    @Test
    void generateWebhookApiKey_shouldGenerateAndStoreHashedKey() {
        // Arrange
        BotConfiguration storedConfig = BotConfiguration.builder()
                .botId(TEST_BOT_ID)
                .encryptedApiKey(ENCRYPTED_API_KEY)
                .encryptedApiSecret(ENCRYPTED_API_SECRET)
                .tradingPair(TEST_TRADING_PAIR)
                .keyVersion(1)
                .build();

        when(botConfigurationRepository.findByBotId(TEST_BOT_ID)).thenReturn(Optional.of(storedConfig));
        when(botConfigurationRepository.save(any(BotConfiguration.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        String webhookApiKey = botConfigurationService.generateWebhookApiKey(TEST_BOT_ID);

        // Assert
        assertNotNull(webhookApiKey);
        assertFalse(webhookApiKey.isEmpty());
        assertNotNull(storedConfig.getWebhookKeyHash());
        assertFalse(storedConfig.getWebhookKeyHash().isEmpty());
        assertNotEquals(webhookApiKey, storedConfig.getWebhookKeyHash()); // Key should be hashed

        verify(botConfigurationRepository).findByBotId(TEST_BOT_ID);
        verify(botConfigurationRepository).save(any(BotConfiguration.class));
    }

    @Test
    void validateWebhookApiKey_shouldReturnTrueForValidKey() {
        // Arrange
        String webhookApiKey = "test-webhook-key";

        // Create a spy of BotConfigurationService to access the private hashApiKey method
        BotConfigurationService serviceSpy = spy(botConfigurationService);

        // Create a mock BotConfiguration
        BotConfiguration mockConfig = mock(BotConfiguration.class);

        // Set up the repository to return our mock config
        when(botConfigurationRepository.findByBotId(TEST_BOT_ID)).thenReturn(Optional.of(mockConfig));

        // Use doReturn to stub the private hashApiKey method
        try {
            java.lang.reflect.Method hashMethod = BotConfigurationService.class.getDeclaredMethod("hashApiKey", String.class);
            hashMethod.setAccessible(true);
            String hashedKey = (String) hashMethod.invoke(serviceSpy, webhookApiKey);

            // Set up the mock config to return our hashed key
            when(mockConfig.getWebhookKeyHash()).thenReturn(hashedKey);

            // Act
            boolean isValid = serviceSpy.validateWebhookApiKey(TEST_BOT_ID, webhookApiKey);

            // Assert
            assertTrue(isValid);
            verify(botConfigurationRepository).findByBotId(TEST_BOT_ID);
        } catch (Exception e) {
            fail("Test failed due to reflection error: " + e.getMessage());
        }
    }

    @Test
    void generateBotId_shouldGenerateSixCharacterString() {
        // Act
        String botId = botConfigurationService.generateBotId();

        // Assert
        assertNotNull(botId);
        assertEquals(6, botId.length(), "Bot ID should be exactly 6 characters long");

        // Verify that the bot ID contains only allowed characters
        assertTrue(botId.matches("^[A-Za-z0-9]{6}$"),
                "Bot ID should only contain capital letters, small case letters, and numbers");
    }

    @Test
    void deactivateBotConfiguration_shouldSetActiveToFalse() {
        // Arrange
        BotConfiguration storedConfig = BotConfiguration.builder()
                .botId(TEST_BOT_ID)
                .encryptedApiKey(ENCRYPTED_API_KEY)
                .encryptedApiSecret(ENCRYPTED_API_SECRET)
                .tradingPair(TEST_TRADING_PAIR)
                .keyVersion(1)
                .active(true)
                .build();

        when(botConfigurationRepository.findByBotId(TEST_BOT_ID)).thenReturn(Optional.of(storedConfig));
        when(botConfigurationRepository.save(any(BotConfiguration.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        boolean result = botConfigurationService.deactivateBotConfiguration(TEST_BOT_ID);

        // Assert
        assertTrue(result);
        assertFalse(storedConfig.getActive());
        verify(botConfigurationRepository).findByBotId(TEST_BOT_ID);
        verify(botConfigurationRepository).save(storedConfig);
    }

    @Test
    void deactivateBotConfiguration_shouldReturnFalseWhenBotNotFound() {
        // Arrange
        when(botConfigurationRepository.findByBotId(TEST_BOT_ID)).thenReturn(Optional.empty());

        // Act
        boolean result = botConfigurationService.deactivateBotConfiguration(TEST_BOT_ID);

        // Assert
        assertFalse(result);
        verify(botConfigurationRepository).findByBotId(TEST_BOT_ID);
        verify(botConfigurationRepository, never()).save(any(BotConfiguration.class));
    }

    @Test
    void activateBotConfiguration_shouldSetActiveToTrue() {
        // Arrange
        BotConfiguration storedConfig = BotConfiguration.builder()
                .botId(TEST_BOT_ID)
                .encryptedApiKey(ENCRYPTED_API_KEY)
                .encryptedApiSecret(ENCRYPTED_API_SECRET)
                .tradingPair(TEST_TRADING_PAIR)
                .keyVersion(1)
                .active(false)
                .build();

        when(botConfigurationRepository.findByBotId(TEST_BOT_ID)).thenReturn(Optional.of(storedConfig));
        when(botConfigurationRepository.save(any(BotConfiguration.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        boolean result = botConfigurationService.activateBotConfiguration(TEST_BOT_ID);

        // Assert
        assertTrue(result);
        assertTrue(storedConfig.getActive());
        verify(botConfigurationRepository).findByBotId(TEST_BOT_ID);
        verify(botConfigurationRepository).save(storedConfig);
    }

    @Test
    void activateBotConfiguration_shouldReturnFalseWhenBotNotFound() {
        // Arrange
        when(botConfigurationRepository.findByBotId(TEST_BOT_ID)).thenReturn(Optional.empty());

        // Act
        boolean result = botConfigurationService.activateBotConfiguration(TEST_BOT_ID);

        // Assert
        assertFalse(result);
        verify(botConfigurationRepository).findByBotId(TEST_BOT_ID);
        verify(botConfigurationRepository, never()).save(any(BotConfiguration.class));
    }

    @Test
    void getAllBotConfigurations_shouldReturnOnlyActiveConfigurations() {
        // Arrange
        BotConfiguration activeConfig1 = BotConfiguration.builder()
                .botId("active1")
                .encryptedApiKey(ENCRYPTED_API_KEY)
                .encryptedApiSecret(ENCRYPTED_API_SECRET)
                .active(true)
                .build();

        BotConfiguration activeConfig2 = BotConfiguration.builder()
                .botId("active2")
                .encryptedApiKey(ENCRYPTED_API_KEY)
                .encryptedApiSecret(ENCRYPTED_API_SECRET)
                .active(true)
                .build();

        when(botConfigurationRepository.findByActive(true)).thenReturn(java.util.List.of(activeConfig1, activeConfig2));
        when(encryptionService.decrypt(ENCRYPTED_API_KEY)).thenReturn(TEST_API_KEY);
        when(encryptionService.decrypt(ENCRYPTED_API_SECRET)).thenReturn(TEST_API_SECRET);

        // Act
        java.util.List<BotConfiguration> result = botConfigurationService.getAllBotConfigurations();

        // Assert
        assertEquals(2, result.size());
        assertTrue(result.stream().allMatch(BotConfiguration::getActive));
        verify(botConfigurationRepository).findByActive(true);
        verify(encryptionService, times(2)).decrypt(ENCRYPTED_API_KEY);
        verify(encryptionService, times(2)).decrypt(ENCRYPTED_API_SECRET);
    }

    @Test
    void getAllBotConfigurationsIncludingInactive_shouldReturnAllConfigurations() {
        // Arrange
        BotConfiguration activeConfig = BotConfiguration.builder()
                .botId("active")
                .encryptedApiKey(ENCRYPTED_API_KEY)
                .encryptedApiSecret(ENCRYPTED_API_SECRET)
                .active(true)
                .build();

        BotConfiguration inactiveConfig = BotConfiguration.builder()
                .botId("inactive")
                .encryptedApiKey(ENCRYPTED_API_KEY)
                .encryptedApiSecret(ENCRYPTED_API_SECRET)
                .active(false)
                .build();

        when(botConfigurationRepository.findAll()).thenReturn(java.util.List.of(activeConfig, inactiveConfig));
        when(encryptionService.decrypt(ENCRYPTED_API_KEY)).thenReturn(TEST_API_KEY);
        when(encryptionService.decrypt(ENCRYPTED_API_SECRET)).thenReturn(TEST_API_SECRET);

        // Act
        java.util.List<BotConfiguration> result = botConfigurationService.getAllBotConfigurationsIncludingInactive();

        // Assert
        assertEquals(2, result.size());
        assertEquals(1, result.stream().filter(BotConfiguration::getActive).count());
        assertEquals(1, result.stream().filter(config -> !config.getActive()).count());
        verify(botConfigurationRepository).findAll();
        verify(encryptionService, times(2)).decrypt(ENCRYPTED_API_KEY);
        verify(encryptionService, times(2)).decrypt(ENCRYPTED_API_SECRET);
    }

    @Test
    void hashApiKey_shouldThrowRuntimeException_whenNoSuchAlgorithmException() {
        try (MockedStatic<MessageDigest> mockedStatic = Mockito.mockStatic(MessageDigest.class)) {
            mockedStatic.when(() -> MessageDigest.getInstance("SHA-256"))
                    .thenThrow(new NoSuchAlgorithmException("Test exception"));

            try {
                // Use reflection to invoke the private hashApiKey method
                Method hashMethod = BotConfigurationService.class.getDeclaredMethod("hashApiKey", String.class);
                hashMethod.setAccessible(true);
                hashMethod.invoke(botConfigurationService, "dummyKey");
                fail("Expected RuntimeException was not thrown");
            } catch (java.lang.reflect.InvocationTargetException e) {
                // The actual exception is wrapped in InvocationTargetException when using reflection
                Throwable cause = e.getCause();
                assertTrue(cause instanceof RuntimeException, "Cause should be RuntimeException but was: " + cause.getClass().getName());
                assertTrue(cause.getMessage().contains("Hashing failed"), "Exception message should contain 'Hashing failed' but was: " + cause.getMessage());
            }
        } catch (Exception e) {
            fail("Test encountered unexpected exception: " + e.getMessage());
        }
    }
}
