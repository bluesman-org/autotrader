package nl.jimkaplan.autotrader.tradingview.controller;

import jakarta.servlet.http.HttpServletRequest;
import nl.jimkaplan.autotrader.service.BotConfigurationService;
import nl.jimkaplan.autotrader.service.TradingService;
import nl.jimkaplan.autotrader.tradingview.model.TradingViewAlertRequest;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TradingViewWebhookControllerTest {

    @Mock
    private BotConfigurationService botConfigurationService;

    @Mock
    private TradingService tradingService;

    @Mock
    private HttpServletRequest httpServletRequest;

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

        // Setup mock for HttpServletRequest - use lenient mode to avoid UnnecessaryStubbingException
        lenient().when(httpServletRequest.getRemoteAddr()).thenReturn("127.0.0.1"); // Use localhost for tests
    }

    @Test
    void handleWebhook_withValidRequest_returnsOk() {
        // Arrange
        when(botConfigurationService.validateWebhookApiKey(eq(botId), eq(validApiKey))).thenReturn(true);

        // Act
        ResponseEntity<?> response = controller.handleWebhook(validApiKey, validRequest, httpServletRequest);

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
        ResponseEntity<?> response = controller.handleWebhook(invalidApiKey, validRequest, httpServletRequest);

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
        ResponseEntity<?> response = controller.handleWebhook(validApiKey, validRequest, httpServletRequest);

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
        ResponseEntity<?> response = controller.handleWebhook(validApiKey, validRequest, httpServletRequest);

        // Assert
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals("Error processing alert: " + errorMessage, response.getBody());
        verify(botConfigurationService).validateWebhookApiKey(eq(botId), eq(validApiKey));
        verify(tradingService).processAlert(eq(validRequest));
    }

    @Test
    void handleWebhook_fromTradingViewAllowedIP_returnsOk() {
        // Arrange
        // Use one of the allowed IPs from TradingView
        when(httpServletRequest.getRemoteAddr()).thenReturn("52.89.214.238");

        // Act
        ResponseEntity<?> response = controller.handleWebhook(validApiKey, validRequest, httpServletRequest);

        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNull(response.getBody());
        // API key validation should be skipped for allowed IPs
        verify(botConfigurationService, never()).validateWebhookApiKey(any(), any());
        verify(tradingService).processAlert(eq(validRequest));
    }

    @Test
    void handleWebhook_fromUnauthorizedIP_returnsUnauthorized() {
        // Arrange
        // Use an IP that is neither localhost nor in the allowed list
        when(httpServletRequest.getRemoteAddr()).thenReturn("192.168.1.1");

        // Act
        ResponseEntity<?> response = controller.handleWebhook(validApiKey, validRequest, httpServletRequest);

        // Assert
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertEquals("Unauthorized access", response.getBody());
        verify(botConfigurationService, never()).validateWebhookApiKey(any(), any());
        verify(tradingService, never()).processAlert(any());
    }

    @Test
    void handleWebhook_withNullValidationResult_returnsInternalServerError() {
        // This test is to cover the null validation result case

        // Act - directly call the controller with a null validation result
        ResponseEntity<?> response = controller.processValidationResult(null, validRequest, httpServletRequest);

        // Assert
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals("Error processing alert for bot ID: " + botId, response.getBody());
        // Verify that the trading service was never called
        verify(tradingService, never()).processAlert(any());
    }

    // We can't easily test the default case in the switch statement because ValidationResult is an enum
    // and we can't create new enum values at runtime. The default case is essentially unreachable code
    // in normal execution, as validateRequest always returns one of the defined enum values.
    // 
    // We've already tested the null case, which is the most realistic edge case that could occur.
    // For 100% line coverage, we could use a custom class loader or reflection to create a new enum value,
    // but that would be overly complex for this test and not representative of real-world usage.
    //
    // Instead, we'll focus on ensuring that all the reachable code paths are tested.
}
