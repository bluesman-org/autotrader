package nl.jimkaplan.autotrader.bitvavo.service;

import nl.jimkaplan.autotrader.bitvavo.client.BitvavoApiClient;
import nl.jimkaplan.autotrader.bitvavo.model.GetAccountHistoryResponse;
import nl.jimkaplan.autotrader.bitvavo.model.Transaction;
import nl.jimkaplan.autotrader.model.document.BotConfiguration;
import nl.jimkaplan.autotrader.service.BotConfigurationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionHistoryServiceTest {

    @Mock
    private BotConfigurationService botConfigurationService;

    @Mock
    private BitvavoApiClient bitvavoApiClient;

    @InjectMocks
    private TransactionHistoryService transactionHistoryService;

    private static final String BOT_ID = "abc123";
    private static final String API_KEY = "test-api-key";
    private static final String API_SECRET = "test-api-secret";

    private BotConfiguration botConfig;
    private GetAccountHistoryResponse historyResponse;

    @BeforeEach
    void setUp() {
        botConfig = BotConfiguration.builder()
                .botId(BOT_ID)
                .apiKey(API_KEY)
                .apiSecret(API_SECRET)
                .build();

        historyResponse = new GetAccountHistoryResponse(List.of(new Transaction()), 1, 24, 5);
    }

    @Test
    void getTransactions_withAllParameters_buildsEndpointAndReturnsResponse() {
        // Arrange
        when(botConfigurationService.getBotConfiguration(BOT_ID)).thenReturn(Optional.of(botConfig));
        when(bitvavoApiClient.get(
                eq("/account/history?fromDate=1706100650751&toDate=1706100650752&page=2&maxItems=5&type=buy"),
                eq(GetAccountHistoryResponse.class), eq(API_KEY), eq(API_SECRET)))
                .thenReturn(historyResponse);

        // Act
        GetAccountHistoryResponse result = transactionHistoryService
                .getTransactions(BOT_ID, 1706100650751L, 1706100650752L, 2, 5, "buy");

        // Assert
        assertEquals(historyResponse, result);
        verify(bitvavoApiClient).get(
                eq("/account/history?fromDate=1706100650751&toDate=1706100650752&page=2&maxItems=5&type=buy"),
                eq(GetAccountHistoryResponse.class), eq(API_KEY), eq(API_SECRET));
    }

    @Test
    void getTransactions_withoutOptionalParameters_usesBareEndpoint() {
        // Arrange
        when(botConfigurationService.getBotConfiguration(BOT_ID)).thenReturn(Optional.of(botConfig));
        when(bitvavoApiClient.get(
                eq("/account/history"), eq(GetAccountHistoryResponse.class), eq(API_KEY), eq(API_SECRET)))
                .thenReturn(historyResponse);

        // Act
        GetAccountHistoryResponse result = transactionHistoryService
                .getTransactions(BOT_ID, null, null, null, null, null);

        // Assert
        assertEquals(historyResponse, result);
        verify(bitvavoApiClient).get(
                eq("/account/history"), eq(GetAccountHistoryResponse.class), eq(API_KEY), eq(API_SECRET));
    }

    @Test
    void getTransactions_withEmptyType_omitsTypeParameter() {
        // Arrange
        when(botConfigurationService.getBotConfiguration(BOT_ID)).thenReturn(Optional.of(botConfig));
        when(bitvavoApiClient.get(
                eq("/account/history?maxItems=5"), eq(GetAccountHistoryResponse.class), eq(API_KEY), eq(API_SECRET)))
                .thenReturn(historyResponse);

        // Act
        GetAccountHistoryResponse result = transactionHistoryService
                .getTransactions(BOT_ID, null, null, null, 5, "");

        // Assert
        assertEquals(historyResponse, result);
        verify(bitvavoApiClient).get(
                eq("/account/history?maxItems=5"), eq(GetAccountHistoryResponse.class), eq(API_KEY), eq(API_SECRET));
    }

    @Test
    void getTransactions_withUnknownBot_throwsNoSuchElementException() {
        // Arrange
        when(botConfigurationService.getBotConfiguration(BOT_ID)).thenReturn(Optional.empty());

        // Act & Assert
        NoSuchElementException exception = assertThrows(NoSuchElementException.class,
                () -> transactionHistoryService.getTransactions(BOT_ID, null, null, null, null, null));

        assertEquals("Bot configuration not found: " + BOT_ID, exception.getMessage());
        verifyNoInteractions(bitvavoApiClient);
    }
}
