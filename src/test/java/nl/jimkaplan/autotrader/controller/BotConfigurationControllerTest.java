package nl.jimkaplan.autotrader.controller;

import nl.jimkaplan.autotrader.model.document.BotConfiguration;
import nl.jimkaplan.autotrader.model.dto.BotConfigurationRequest;
import nl.jimkaplan.autotrader.model.dto.BotConfigurationResponse;
import nl.jimkaplan.autotrader.model.dto.BotCreatedResponse;
import nl.jimkaplan.autotrader.model.dto.WebhookApiKeyResponse;
import nl.jimkaplan.autotrader.service.BotConfigurationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BotConfigurationControllerTest {

    public static final String WEBHOOK_API_KEY = "test-webhook-api-key";
    @Mock
    private BotConfigurationService botConfigurationService;

    private BotConfigurationController controller;

    private BotConfiguration testBotConfig;
    private BotConfigurationRequest testRequest;
    private String testBotId;

    @BeforeEach
    void setUp() {
        controller = new BotConfigurationController(botConfigurationService);

        // Setup test data
        testBotId = "abc123";
        testRequest = BotConfigurationRequest.builder()
                .apiKey("test-api-key")
                .apiSecret("test-api-secret")
                .tradingPair("BTC-EUR")
                .build();

        testBotConfig = BotConfiguration.builder()
                .botId(testBotId)
                .encryptedApiKey("encrypted-api-key")
                .encryptedApiSecret("encrypted-api-secret")
                .tradingPair("BTC-EUR")
                .active(true)
                .webhookKeyHash("webhook-key-hash")
                .build();
    }

    @Test
    void createBotConfiguration_withValidRequest_returnsCreatedResponse() {
        // Arrange
        when(botConfigurationService.generateBotId()).thenReturn(testBotId);
        when(botConfigurationService.saveBotConfiguration(any(BotConfiguration.class))).thenReturn(testBotConfig);
        when(botConfigurationService.generateAndSaveWebhookApiKey(eq(testBotId))).thenReturn(WEBHOOK_API_KEY);

        // Act
        ResponseEntity<BotCreatedResponse> response = controller.createBotConfiguration(testRequest);

        // Assert
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(testBotId, response.getBody().getBotId());
        assertEquals("BTC-EUR", response.getBody().getTradingPair());
        assertTrue(response.getBody().getActive());
        assertNotNull(response.getBody().getWebhookApiKey());

        verify(botConfigurationService).generateBotId();
        verify(botConfigurationService).saveBotConfiguration(any(BotConfiguration.class));
    }

    @Test
    void getBotConfiguration_withExistingBotId_returnsOkResponse() {
        // Arrange
        when(botConfigurationService.getBotConfiguration(eq(testBotId))).thenReturn(Optional.of(testBotConfig));

        // Act
        ResponseEntity<BotConfigurationResponse> response = controller.getBotConfiguration(testBotId);

        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(testBotId, response.getBody().getBotId());
        assertEquals("BTC-EUR", response.getBody().getTradingPair());
        assertTrue(response.getBody().getActive());

        verify(botConfigurationService).getBotConfiguration(eq(testBotId));
    }

    @Test
    void getBotConfiguration_withNonExistingBotId_returnsNotFound() {
        // Arrange
        String nonExistingBotId = "nonext";  // Valid format (6 chars) but doesn't exist
        when(botConfigurationService.getBotConfiguration(eq(nonExistingBotId))).thenReturn(Optional.empty());

        // Act
        ResponseEntity<BotConfigurationResponse> response = controller.getBotConfiguration(nonExistingBotId);

        // Assert
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertNull(response.getBody());

        verify(botConfigurationService).getBotConfiguration(eq(nonExistingBotId));
    }

    @Test
    void getAllBotConfigurations_withActiveOnly_returnsOkResponse() {
        // Arrange
        BotConfiguration config1 = BotConfiguration.builder()
                .botId("bot1")
                .tradingPair("BTC-EUR")
                .active(true)
                .build();

        BotConfiguration config2 = BotConfiguration.builder()
                .botId("bot2")
                .tradingPair("ETH-EUR")
                .active(true)
                .build();

        List<BotConfiguration> configs = Arrays.asList(config1, config2);

        when(botConfigurationService.getAllBotConfigurations()).thenReturn(configs);

        // Act
        ResponseEntity<List<BotConfigurationResponse>> response = controller.getAllBotConfigurations(false);

        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(2, response.getBody().size());
        assertEquals("bot1", response.getBody().get(0).getBotId());
        assertEquals("bot2", response.getBody().get(1).getBotId());

        verify(botConfigurationService).getAllBotConfigurations();
        verify(botConfigurationService, never()).getAllBotConfigurationsIncludingInactive();
    }

    @Test
    void getAllBotConfigurations_withIncludeInactive_returnsOkResponse() {
        // Arrange
        BotConfiguration config1 = BotConfiguration.builder()
                .botId("bot1")
                .tradingPair("BTC-EUR")
                .active(true)
                .build();

        BotConfiguration config2 = BotConfiguration.builder()
                .botId("bot2")
                .tradingPair("ETH-EUR")
                .active(false)
                .build();

        List<BotConfiguration> configs = Arrays.asList(config1, config2);

        when(botConfigurationService.getAllBotConfigurationsIncludingInactive()).thenReturn(configs);

        // Act
        ResponseEntity<List<BotConfigurationResponse>> response = controller.getAllBotConfigurations(true);

        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(2, response.getBody().size());
        assertEquals("bot1", response.getBody().get(0).getBotId());
        assertEquals("bot2", response.getBody().get(1).getBotId());

        verify(botConfigurationService, never()).getAllBotConfigurations();
        verify(botConfigurationService).getAllBotConfigurationsIncludingInactive();
    }

    @Test
    void getAllBotConfigurations_withEmptyList_returnsEmptyResponse() {
        // Arrange
        when(botConfigurationService.getAllBotConfigurations()).thenReturn(Collections.emptyList());

        // Act
        ResponseEntity<List<BotConfigurationResponse>> response = controller.getAllBotConfigurations(false);

        // Assert
        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        assertTrue(Objects.requireNonNull(response.getHeaders().get("X-Message")).contains("No bot configurations found"));
        assertNotNull(response.getBody());
        assertTrue(response.getBody().isEmpty());

        verify(botConfigurationService).getAllBotConfigurations();
    }

    @Test
    void deactivateBotConfiguration_withExistingBotId() {
        // Arrange
        when(botConfigurationService.deactivateBotConfiguration(eq(testBotId))).thenReturn(true);

        // Act
        ResponseEntity<String> response = controller.deactivateBotConfiguration(testBotId);

        // Assert
        assertTrue(Objects.requireNonNull(response.getBody()).contains("deactivated"));
        assertTrue(Objects.requireNonNull(response.getBody()).contains(testBotId));
        assertEquals(HttpStatus.OK, response.getStatusCode());

        verify(botConfigurationService).deactivateBotConfiguration(eq(testBotId));
    }

    @Test
    void deactivateBotConfiguration_withNonExistingBotId_returnsNotFound() {
        // Arrange
        String nonExistingBotId = "nonext";  // Valid format (6 chars) but doesn't exist
        when(botConfigurationService.deactivateBotConfiguration(eq(nonExistingBotId))).thenReturn(false);

        // Act
        ResponseEntity<String> response = controller.deactivateBotConfiguration(nonExistingBotId);

        // Assert
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertNull(response.getBody());

        verify(botConfigurationService).deactivateBotConfiguration(eq(nonExistingBotId));
    }

    @Test
    void activateBotConfiguration_withExistingBotId_returnsNoContent() {
        // Arrange
        when(botConfigurationService.activateBotConfiguration(eq(testBotId))).thenReturn(true);

        // Act
        ResponseEntity<String> response = controller.activateBotConfiguration(testBotId);

        // Assert
        assertTrue(Objects.requireNonNull(response.getBody()).contains("activated"));
        assertTrue(Objects.requireNonNull(response.getBody()).contains(testBotId));
        assertEquals(HttpStatus.OK, response.getStatusCode());

        verify(botConfigurationService).activateBotConfiguration(eq(testBotId));
    }

    @Test
    void activateBotConfiguration_withNonExistingBotId_returnsNotFound() {
        // Arrange
        String nonExistingBotId = "nonext";  // Valid format (6 chars) but doesn't exist
        when(botConfigurationService.activateBotConfiguration(eq(nonExistingBotId))).thenReturn(false);

        // Act
        ResponseEntity<String> response = controller.activateBotConfiguration(nonExistingBotId);

        // Assert
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertNull(response.getBody());

        verify(botConfigurationService).activateBotConfiguration(eq(nonExistingBotId));
    }

    @Test
    void generateWebhookApiKey_withExistingBotId_returnsOkResponse() {
        // Arrange
        when(botConfigurationService.getBotConfigurationIncludingInactive(eq(testBotId))).thenReturn(Optional.of(testBotConfig));
        when(botConfigurationService.generateAndSaveWebhookApiKey(eq(testBotId))).thenReturn(WEBHOOK_API_KEY);

        // Act
        ResponseEntity<WebhookApiKeyResponse> response = controller.generateWebhookApiKey(testBotId);

        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(WEBHOOK_API_KEY, response.getBody().getApiKey());

        verify(botConfigurationService).getBotConfigurationIncludingInactive(eq(testBotId));
        verify(botConfigurationService).generateAndSaveWebhookApiKey(eq(testBotId));
    }

    @Test
    void generateWebhookApiKey_withNonExistingBotId_returnsNotFound() {
        // Arrange
        String nonExistingBotId = "nonext";  // Valid format (6 chars) but doesn't exist
        when(botConfigurationService.getBotConfigurationIncludingInactive(eq(nonExistingBotId))).thenReturn(Optional.empty());

        // Act
        ResponseEntity<WebhookApiKeyResponse> response = controller.generateWebhookApiKey(nonExistingBotId);

        // Assert
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertNull(response.getBody());

        verify(botConfigurationService).getBotConfigurationIncludingInactive(eq(nonExistingBotId));
        verify(botConfigurationService, never()).generateAndSaveWebhookApiKey(anyString());
    }

    // Tests for validateBotId method

    @Test
    void getBotConfiguration_withNullBotId_throwsIllegalArgumentException() {
        // Act & Assert
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> controller.getBotConfiguration(null));

        assertEquals("Bot ID must be exactly 6 characters long and contain only letters and numbers",
                exception.getMessage());
    }

    @Test
    void getBotConfiguration_withShortBotId_throwsIllegalArgumentException() {
        // Arrange
        String shortBotId = "abc12";  // 5 characters instead of 6

        // Act & Assert
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> controller.getBotConfiguration(shortBotId));

        assertEquals("Bot ID must be exactly 6 characters long and contain only letters and numbers",
                exception.getMessage());
    }

    @Test
    void getBotConfiguration_withLongBotId_throwsIllegalArgumentException() {
        // Arrange
        String longBotId = "abc1234";  // 7 characters instead of 6

        // Act & Assert
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> controller.getBotConfiguration(longBotId));

        assertEquals("Bot ID must be exactly 6 characters long and contain only letters and numbers",
                exception.getMessage());
    }

    @Test
    void getBotConfiguration_withInvalidCharactersBotId_throwsIllegalArgumentException() {
        // Arrange
        String invalidBotId = "abc-12";  // Contains invalid character '-'

        // Act & Assert
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> controller.getBotConfiguration(invalidBotId));

        assertEquals("Bot ID must be exactly 6 characters long and contain only letters and numbers",
                exception.getMessage());
    }

    @Test
    void deactivateBotConfiguration_withInvalidBotId_throwsIllegalArgumentException() {
        // Arrange
        String invalidBotId = "abc-12";  // Contains invalid character '-'

        // Act & Assert
        assertThrows(IllegalArgumentException.class,
                () -> controller.deactivateBotConfiguration(invalidBotId));
    }

    @Test
    void activateBotConfiguration_withInvalidBotId_throwsIllegalArgumentException() {
        // Arrange
        String invalidBotId = "abc-12";  // Contains invalid character '-'

        // Act & Assert
        assertThrows(IllegalArgumentException.class,
                () -> controller.activateBotConfiguration(invalidBotId));
    }

    @Test
    void generateWebhookApiKey_withInvalidBotId_throwsIllegalArgumentException() {
        // Arrange
        String invalidBotId = "abc-12";  // Contains invalid character '-'

        // Act & Assert
        assertThrows(IllegalArgumentException.class,
                () -> controller.generateWebhookApiKey(invalidBotId));
    }

    // Tests for validateCreateRequest method

    @Test
    void createBotConfiguration_withNullApiKey_throwsIllegalArgumentException() {
        // Arrange
        BotConfigurationRequest invalidRequest = BotConfigurationRequest.builder()
                .apiKey(null)
                .apiSecret("test-api-secret")
                .tradingPair("BTC-EUR")
                .build();

        // Act & Assert
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> controller.createBotConfiguration(invalidRequest));

        assertEquals("API key cannot be empty", exception.getMessage());
    }

    @Test
    void createBotConfiguration_withEmptyApiKey_throwsIllegalArgumentException() {
        // Arrange
        BotConfigurationRequest invalidRequest = BotConfigurationRequest.builder()
                .apiKey("")
                .apiSecret("test-api-secret")
                .tradingPair("BTC-EUR")
                .build();

        // Act & Assert
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> controller.createBotConfiguration(invalidRequest));

        assertEquals("API key cannot be empty", exception.getMessage());
    }

    @Test
    void createBotConfiguration_withNullApiSecret_throwsIllegalArgumentException() {
        // Arrange
        BotConfigurationRequest invalidRequest = BotConfigurationRequest.builder()
                .apiKey("test-api-key")
                .apiSecret(null)
                .tradingPair("BTC-EUR")
                .build();

        // Act & Assert
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> controller.createBotConfiguration(invalidRequest));

        assertEquals("API secret cannot be empty", exception.getMessage());
    }

    @Test
    void createBotConfiguration_withEmptyApiSecret_throwsIllegalArgumentException() {
        // Arrange
        BotConfigurationRequest invalidRequest = BotConfigurationRequest.builder()
                .apiKey("test-api-key")
                .apiSecret("")
                .tradingPair("BTC-EUR")
                .build();

        // Act & Assert
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> controller.createBotConfiguration(invalidRequest));

        assertEquals("API secret cannot be empty", exception.getMessage());
    }

    @Test
    void createBotConfiguration_withNullTradingPair_throwsIllegalArgumentException() {
        // Arrange
        BotConfigurationRequest invalidRequest = BotConfigurationRequest.builder()
                .apiKey("test-api-key")
                .apiSecret("test-api-secret")
                .tradingPair(null)
                .build();

        // Act & Assert
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> controller.createBotConfiguration(invalidRequest));

        assertEquals("Trading pair cannot be empty", exception.getMessage());
    }

    @Test
    void createBotConfiguration_withEmptyTradingPair_throwsIllegalArgumentException() {
        // Arrange
        BotConfigurationRequest invalidRequest = BotConfigurationRequest.builder()
                .apiKey("test-api-key")
                .apiSecret("test-api-secret")
                .tradingPair("")
                .build();

        // Act & Assert
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> controller.createBotConfiguration(invalidRequest));

        assertEquals("Trading pair cannot be empty", exception.getMessage());
    }

    @Test
    void createBotConfiguration_withLowercaseTradingPair_throwsIllegalArgumentException() {
        // Arrange
        BotConfigurationRequest invalidRequest = BotConfigurationRequest.builder()
                .apiKey("test-api-key")
                .apiSecret("test-api-secret")
                .tradingPair("btc-eur")  // Lowercase instead of uppercase
                .build();

        // Act & Assert
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> controller.createBotConfiguration(invalidRequest));

        assertEquals("Trading pair must be in uppercase", exception.getMessage());
    }
}
