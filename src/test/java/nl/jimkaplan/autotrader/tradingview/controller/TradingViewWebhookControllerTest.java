package nl.jimkaplan.autotrader.tradingview.controller;

import nl.jimkaplan.autotrader.service.TradingService;
import nl.jimkaplan.autotrader.tradingview.model.TradingViewAlertRequest;
import nl.jimkaplan.autotrader.tradingview.service.BotConfigurationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TradingViewWebhookControllerTest {

    @Mock
    private BotConfigurationService botConfigurationService;

    @Mock
    private TradingService tradingService;

    private TradingViewWebhookController controller;

    private TradingViewAlertRequest validRequest;
    private String validApiKey;
    private String botId;

    @BeforeEach
    void setUp() {
        controller = new TradingViewWebhookController(botConfigurationService, tradingService);
        
        // Setup test data
        botId = "test-bot-id";
        validApiKey = "valid-api-key";
        validRequest = TradingViewAlertRequest.builder()
                .botId(botId)
                .ticker("BTCEUR")
                .action("buy")
                .timestamp(Instant.now().toString())
                .build();
    }

    @Test
    void handleWebhook_withValidRequest_returnsOk() {
        // Arrange
        when(botConfigurationService.validateWebhookApiKey(eq(botId), eq(validApiKey))).thenReturn(true);
        
        // Act
        ResponseEntity<?> response = controller.handleWebhook(validApiKey, validRequest);
        
        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNull(response.getBody());
        verify(botConfigurationService).validateWebhookApiKey(eq(botId), eq(validApiKey));
        verify(tradingService).processAlert(eq(validRequest));
    }

    @Test
    void handleWebhook_withInvalidApiKey_returnsUnauthorized() {
        // Arrange
        String invalidApiKey = "invalid-api-key";
        when(botConfigurationService.validateWebhookApiKey(eq(botId), eq(invalidApiKey))).thenReturn(false);
        
        // Act
        ResponseEntity<?> response = controller.handleWebhook(invalidApiKey, validRequest);
        
        // Assert
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertEquals("Invalid API key", response.getBody());
        verify(botConfigurationService).validateWebhookApiKey(eq(botId), eq(invalidApiKey));
        verify(tradingService, never()).processAlert(any());
    }

    @Test
    void handleWebhook_whenTradingServiceThrowsIllegalArgumentException_returnsBadRequest() {
        // Arrange
        when(botConfigurationService.validateWebhookApiKey(eq(botId), eq(validApiKey))).thenReturn(true);
        String errorMessage = "Invalid request parameters";
        doThrow(new IllegalArgumentException(errorMessage)).when(tradingService).processAlert(eq(validRequest));
        
        // Act
        ResponseEntity<?> response = controller.handleWebhook(validApiKey, validRequest);
        
        // Assert
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(errorMessage, response.getBody());
        verify(botConfigurationService).validateWebhookApiKey(eq(botId), eq(validApiKey));
        verify(tradingService).processAlert(eq(validRequest));
    }

    @Test
    void handleWebhook_whenTradingServiceThrowsException_returnsInternalServerError() {
        // Arrange
        when(botConfigurationService.validateWebhookApiKey(eq(botId), eq(validApiKey))).thenReturn(true);
        String errorMessage = "Internal server error";
        doThrow(new RuntimeException(errorMessage)).when(tradingService).processAlert(eq(validRequest));
        
        // Act
        ResponseEntity<?> response = controller.handleWebhook(validApiKey, validRequest);
        
        // Assert
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals("Error processing alert: " + errorMessage, response.getBody());
        verify(botConfigurationService).validateWebhookApiKey(eq(botId), eq(validApiKey));
        verify(tradingService).processAlert(eq(validRequest));
    }
}