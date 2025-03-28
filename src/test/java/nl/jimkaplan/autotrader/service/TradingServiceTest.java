package nl.jimkaplan.autotrader.service;

import nl.jimkaplan.autotrader.bitvavo.client.BitvavoApiClient;
import nl.jimkaplan.autotrader.bitvavo.model.CreateOrderRequest;
import nl.jimkaplan.autotrader.bitvavo.model.CreateOrderResponse;
import nl.jimkaplan.autotrader.bitvavo.model.GetAccountBalanceResponse;
import nl.jimkaplan.autotrader.bitvavo.model.GetPriceResponse;
import nl.jimkaplan.autotrader.model.Order;
import nl.jimkaplan.autotrader.model.document.BotConfiguration;
import nl.jimkaplan.autotrader.model.document.Position;
import nl.jimkaplan.autotrader.tradingview.model.TradingViewAlertRequest;
import nl.jimkaplan.autotrader.tradingview.model.document.TradingViewAlert;
import nl.jimkaplan.autotrader.tradingview.service.TradingViewAlertService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TradingServiceTest {

    @Mock
    private BitvavoApiClient bitvavoApiClient;

    @Mock
    private BotConfigurationService botConfigurationService;

    @Mock
    private TradingViewAlertService tradingViewAlertService;

    @Mock
    private OrderService orderService;

    @Mock
    private PositionService positionService;

    @InjectMocks
    private TradingService tradingService;

    @Captor
    private ArgumentCaptor<TradingViewAlert> alertCaptor;

    @Captor
    private ArgumentCaptor<Order> orderCaptor;

    @Captor
    private ArgumentCaptor<Position> positionCaptor;

    @Captor
    private ArgumentCaptor<CreateOrderRequest> orderRequestCaptor;

    private TradingViewAlertRequest validBuyRequest;
    private TradingViewAlertRequest validSellRequest;
    private BotConfiguration botConfig;
    private TradingViewAlert savedAlert;
    private GetAccountBalanceResponse eurBalanceResponse;
    private GetAccountBalanceResponse btcBalanceResponse;
    private GetPriceResponse btcPriceResponse;
    private CreateOrderResponse orderResponse;
    private Position existingPosition;

    private final String TEST_BOT_ID = "test-bot-id";
    private final String TEST_TICKER = "BTCEUR";
    private final String TEST_TIMESTAMP = "2023-01-01T12:00:00Z";
    private final UUID TEST_ORDER_ID = UUID.randomUUID();
    private final double TEST_EUR_BALANCE = 100.0;
    private final double TEST_BTC_BALANCE = 0.01;
    private final String TEST_API_KEY = "test-api-key";
    private final String TEST_API_SECRET = "test-api-secret";

    @BeforeEach
    void setUp() {
        // Set up valid buy request
        validBuyRequest = new TradingViewAlertRequest();
        validBuyRequest.setBotId(TEST_BOT_ID);
        validBuyRequest.setTicker(TEST_TICKER);
        validBuyRequest.setAction("buy");
        validBuyRequest.setTimestamp(TEST_TIMESTAMP);

        // Set up valid sell request
        validSellRequest = new TradingViewAlertRequest();
        validSellRequest.setBotId(TEST_BOT_ID);
        validSellRequest.setTicker(TEST_TICKER);
        validSellRequest.setAction("sell");
        validSellRequest.setTimestamp(TEST_TIMESTAMP);

        // Set up bot configuration
        botConfig = BotConfiguration.builder()
                .botId(TEST_BOT_ID)
                .tradingPair(TEST_TICKER)
                .apiKey(TEST_API_KEY)
                .apiSecret(TEST_API_SECRET)
                .build();

        // Set up saved alert
        savedAlert = TradingViewAlert.builder()
                .botId(TEST_BOT_ID)
                .ticker(TEST_TICKER)
                .action("buy")
                .timestamp(Instant.parse(TEST_TIMESTAMP))
                .build();
        savedAlert.setId("test-alert-id");

        // Set up balance responses
        eurBalanceResponse = new GetAccountBalanceResponse();
        eurBalanceResponse.setAvailable(BigDecimal.valueOf(TEST_EUR_BALANCE));

        btcBalanceResponse = new GetAccountBalanceResponse();
        btcBalanceResponse.setAvailable(BigDecimal.valueOf(TEST_BTC_BALANCE));

        // Set up price response
        btcPriceResponse = new GetPriceResponse();
        double TEST_BTC_PRICE = 30000.0;
        btcPriceResponse.setPrice(BigDecimal.valueOf(TEST_BTC_PRICE));

        // Set up order response
        orderResponse = new CreateOrderResponse();
        orderResponse.setOrderId(TEST_ORDER_ID);

        // Set up existing position
        existingPosition = Position.builder()
                .botId(TEST_BOT_ID)
                .ticker(TEST_TICKER)
                .status("OPEN")
                .build();
        existingPosition.setId("test-position-id");
    }

    @Test
    void processAlert_withValidBuySignal_processesSuccessfully() {
        // Arrange
        when(tradingViewAlertService.saveAlert(any())).thenReturn(savedAlert);
        when(botConfigurationService.getBotConfiguration(TEST_BOT_ID)).thenReturn(Optional.of(botConfig));
        when(bitvavoApiClient.get(eq("/balance?symbol=EUR"), eq(GetAccountBalanceResponse.class), eq(TEST_API_KEY), eq(TEST_API_SECRET))).thenReturn(eurBalanceResponse);
        when(bitvavoApiClient.post(eq("/order"), any(CreateOrderRequest.class), eq(CreateOrderResponse.class), eq(TEST_API_KEY), eq(TEST_API_SECRET))).thenReturn(orderResponse);
        when(positionService.getPositionByBotIdAndTickerAndStatus(TEST_BOT_ID, TEST_TICKER, "OPEN")).thenReturn(Optional.empty());

        // Act
        tradingService.processAlert(validBuyRequest);

        // Assert
        verify(tradingViewAlertService).saveAlert(alertCaptor.capture());
        assertEquals(TEST_BOT_ID, alertCaptor.getValue().getBotId());
        assertEquals(TEST_TICKER, alertCaptor.getValue().getTicker());
        assertEquals("buy", alertCaptor.getValue().getAction());

        verify(bitvavoApiClient).get(eq("/balance?symbol=EUR"), eq(GetAccountBalanceResponse.class), eq(TEST_API_KEY), eq(TEST_API_SECRET));
        verify(bitvavoApiClient).post(eq("/order"), orderRequestCaptor.capture(), eq(CreateOrderResponse.class), eq(TEST_API_KEY), eq(TEST_API_SECRET));

        CreateOrderRequest capturedRequest = orderRequestCaptor.getValue();
        assertEquals(TEST_TICKER, capturedRequest.getMarket());
        assertEquals("buy", capturedRequest.getSide());
        assertEquals("market", capturedRequest.getOrderType());
        assertEquals(BigDecimal.valueOf(TEST_EUR_BALANCE), capturedRequest.getAmountQuote());
    }

    @Test
    void processAlert_withValidSellSignal_processesSuccessfully() {
        // Arrange
        when(tradingViewAlertService.saveAlert(any())).thenReturn(savedAlert);
        when(botConfigurationService.getBotConfiguration(TEST_BOT_ID)).thenReturn(Optional.of(botConfig));
        when(bitvavoApiClient.get(eq("/balance?symbol=BTC"), eq(GetAccountBalanceResponse.class), eq(TEST_API_KEY), eq(TEST_API_SECRET))).thenReturn(btcBalanceResponse);
        when(bitvavoApiClient.get(eq("/ticker/price?market=BTCEUR"), eq(GetPriceResponse.class), eq(TEST_API_KEY), eq(TEST_API_SECRET))).thenReturn(btcPriceResponse);
        when(bitvavoApiClient.post(eq("/order"), any(CreateOrderRequest.class), eq(CreateOrderResponse.class), eq(TEST_API_KEY), eq(TEST_API_SECRET))).thenReturn(orderResponse);
        when(positionService.getPositionByBotIdAndTickerAndStatus(TEST_BOT_ID, TEST_TICKER, "OPEN")).thenReturn(Optional.of(existingPosition));

        // Act
        tradingService.processAlert(validSellRequest);

        // Assert
        verify(tradingViewAlertService).saveAlert(alertCaptor.capture());
        assertEquals(TEST_BOT_ID, alertCaptor.getValue().getBotId());
        assertEquals(TEST_TICKER, alertCaptor.getValue().getTicker());
        assertEquals("sell", alertCaptor.getValue().getAction());

        verify(bitvavoApiClient).get(eq("/balance?symbol=BTC"), eq(GetAccountBalanceResponse.class), eq(TEST_API_KEY), eq(TEST_API_SECRET));
        verify(bitvavoApiClient).get(eq("/ticker/price?market=BTCEUR"), eq(GetPriceResponse.class), eq(TEST_API_KEY), eq(TEST_API_SECRET));
        verify(bitvavoApiClient).post(eq("/order"), orderRequestCaptor.capture(), eq(CreateOrderResponse.class), eq(TEST_API_KEY), eq(TEST_API_SECRET));

        CreateOrderRequest capturedRequest = orderRequestCaptor.getValue();
        assertEquals(TEST_TICKER, capturedRequest.getMarket());
        assertEquals("sell", capturedRequest.getSide());
        assertEquals("market", capturedRequest.getOrderType());
        assertEquals(BigDecimal.valueOf(TEST_BTC_BALANCE), capturedRequest.getAmount());
    }

    // Request validation tests

    @Test
    void processAlert_withMissingBotId_throwsException() {
        // Arrange
        TradingViewAlertRequest request = new TradingViewAlertRequest();
        request.setTicker(TEST_TICKER);
        request.setAction("buy");
        request.setTimestamp(TEST_TIMESTAMP);

        // Act & Assert
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, 
                () -> tradingService.processAlert(request));
        assertEquals("Bot ID is required", exception.getMessage());
    }

    @Test
    void processAlert_withMissingTicker_throwsException() {
        // Arrange
        TradingViewAlertRequest request = new TradingViewAlertRequest();
        request.setBotId(TEST_BOT_ID);
        request.setAction("buy");
        request.setTimestamp(TEST_TIMESTAMP);

        // Act & Assert
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, 
                () -> tradingService.processAlert(request));
        assertEquals("Ticker is required", exception.getMessage());
    }

    @Test
    void processAlert_withMissingAction_throwsException() {
        // Arrange
        TradingViewAlertRequest request = new TradingViewAlertRequest();
        request.setBotId(TEST_BOT_ID);
        request.setTicker(TEST_TICKER);
        request.setTimestamp(TEST_TIMESTAMP);

        // Act & Assert
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, 
                () -> tradingService.processAlert(request));
        assertEquals("Action is required", exception.getMessage());
    }

    @Test
    void processAlert_withMissingTimestamp_throwsException() {
        // Arrange
        TradingViewAlertRequest request = new TradingViewAlertRequest();
        request.setBotId(TEST_BOT_ID);
        request.setTicker(TEST_TICKER);
        request.setAction("buy");

        // Act & Assert
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, 
                () -> tradingService.processAlert(request));
        assertEquals("Timestamp is required", exception.getMessage());
    }

    @Test
    void processAlert_withInvalidTimestampFormat_throwsException() {
        // Arrange
        TradingViewAlertRequest request = new TradingViewAlertRequest();
        request.setBotId(TEST_BOT_ID);
        request.setTicker(TEST_TICKER);
        request.setAction("buy");
        request.setTimestamp("invalid-timestamp");

        // Act & Assert
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, 
                () -> tradingService.processAlert(request));
        assertEquals("Invalid timestamp format. Expected format: yyyy-MM-ddTHH:mm:ssZ", exception.getMessage());
    }

    // Ticker mismatch test

    @Test
    void processAlert_withTickerMismatch_throwsException() {
        // Arrange
        TradingViewAlertRequest request = new TradingViewAlertRequest();
        request.setBotId(TEST_BOT_ID);
        request.setTicker("ETHUSD"); // Different from bot's configured trading pair
        request.setAction("buy");
        request.setTimestamp(TEST_TIMESTAMP);

        when(botConfigurationService.getBotConfiguration(TEST_BOT_ID)).thenReturn(Optional.of(botConfig));
        when(tradingViewAlertService.saveAlert(any())).thenReturn(savedAlert);

        // Act & Assert
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, 
                () -> tradingService.processAlert(request));
        assertEquals("Ticker mismatch: ETHUSD does not match configured trading pair: BTCEUR", exception.getMessage());
    }

    // Non-EUR-based ticker test

    @Test
    void processAlert_withNonEurBasedTicker_throwsException() {
        // Arrange
        TradingViewAlertRequest request = new TradingViewAlertRequest();
        request.setBotId(TEST_BOT_ID);
        request.setTicker("BTCUSD"); // Non-EUR based ticker
        request.setAction("buy");
        request.setTimestamp(TEST_TIMESTAMP);

        // Update bot config to match the ticker
        BotConfiguration nonEurBotConfig = BotConfiguration.builder()
                .botId(TEST_BOT_ID)
                .tradingPair("BTCUSD")
                .apiKey(TEST_API_KEY)
                .apiSecret(TEST_API_SECRET)
                .build();

        when(botConfigurationService.getBotConfiguration(TEST_BOT_ID)).thenReturn(Optional.of(nonEurBotConfig));
        when(tradingViewAlertService.saveAlert(any())).thenReturn(savedAlert);

        // Act & Assert
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, 
                () -> tradingService.processAlert(request));
        assertEquals("Unsupported ticker: BTCUSD. Only EUR-based trading pairs are supported in v1.", exception.getMessage());
    }

    // Invalid action test

    @Test
    void processAlert_withInvalidAction_throwsException() {
        // Arrange
        TradingViewAlertRequest request = new TradingViewAlertRequest();
        request.setBotId(TEST_BOT_ID);
        request.setTicker(TEST_TICKER);
        request.setAction("hold"); // Invalid action
        request.setTimestamp(TEST_TIMESTAMP);

        when(botConfigurationService.getBotConfiguration(TEST_BOT_ID)).thenReturn(Optional.of(botConfig));
        when(tradingViewAlertService.saveAlert(any())).thenReturn(savedAlert);

        // Act & Assert
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, 
                () -> tradingService.processAlert(request));
        assertEquals("Invalid action: hold. Supported actions are 'buy' and 'sell'.", exception.getMessage());
    }

    // Insufficient balance tests

    @Test
    void processBuySignal_withInsufficientEurBalance_savesFailedOrder() {
        // Arrange
        GetAccountBalanceResponse lowBalanceResponse = new GetAccountBalanceResponse();
        lowBalanceResponse.setAvailable(BigDecimal.valueOf(4.0)); // Below MIN_EUR_AMOUNT (5.0)

        when(tradingViewAlertService.saveAlert(any())).thenReturn(savedAlert);
        when(botConfigurationService.getBotConfiguration(TEST_BOT_ID)).thenReturn(Optional.of(botConfig));
        when(bitvavoApiClient.get(eq("/balance?symbol=EUR"), eq(GetAccountBalanceResponse.class), eq(TEST_API_KEY), eq(TEST_API_SECRET)))
                .thenReturn(lowBalanceResponse);

        // Act
        tradingService.processAlert(validBuyRequest);

        // Assert
        verify(bitvavoApiClient).get(eq("/balance?symbol=EUR"), eq(GetAccountBalanceResponse.class), eq(TEST_API_KEY), eq(TEST_API_SECRET));
        verify(bitvavoApiClient, never()).post(anyString(), any(CreateOrderRequest.class), any(), anyString(), anyString());

        verify(orderService).saveOrder(orderCaptor.capture());
        Order capturedOrder = orderCaptor.getValue();
        assertEquals(TEST_BOT_ID, capturedOrder.getBotId());
        assertEquals(TEST_TICKER, capturedOrder.getTicker());
        assertEquals("FAILED", capturedOrder.getStatus());
        assertTrue(capturedOrder.getErrorMessage().contains("Insufficient EUR balance"));
    }

    @Test
    void processSellSignal_withInsufficientAssetBalance_savesFailedOrder() {
        // Arrange
        // Low BTC balance
        GetAccountBalanceResponse lowBtcBalanceResponse = new GetAccountBalanceResponse();
        lowBtcBalanceResponse.setAvailable(BigDecimal.valueOf(0.0001)); // Very small amount

        // BTC price that makes the worth below MIN_EUR_AMOUNT
        GetPriceResponse lowBtcPriceResponse = new GetPriceResponse();
        lowBtcPriceResponse.setPrice(BigDecimal.valueOf(30000.0)); // 0.0001 BTC * 30000 EUR = 3 EUR (below 5 EUR minimum)

        when(tradingViewAlertService.saveAlert(any())).thenReturn(savedAlert);
        when(botConfigurationService.getBotConfiguration(TEST_BOT_ID)).thenReturn(Optional.of(botConfig));
        when(bitvavoApiClient.get(eq("/balance?symbol=BTC"), eq(GetAccountBalanceResponse.class), eq(TEST_API_KEY), eq(TEST_API_SECRET)))
                .thenReturn(lowBtcBalanceResponse);
        when(bitvavoApiClient.get(eq("/ticker/price?market=BTCEUR"), eq(GetPriceResponse.class), eq(TEST_API_KEY), eq(TEST_API_SECRET)))
                .thenReturn(lowBtcPriceResponse);

        // Act
        tradingService.processAlert(validSellRequest);

        // Assert
        verify(bitvavoApiClient).get(eq("/balance?symbol=BTC"), eq(GetAccountBalanceResponse.class), eq(TEST_API_KEY), eq(TEST_API_SECRET));
        verify(bitvavoApiClient).get(eq("/ticker/price?market=BTCEUR"), eq(GetPriceResponse.class), eq(TEST_API_KEY), eq(TEST_API_SECRET));
        verify(bitvavoApiClient, never()).post(anyString(), any(CreateOrderRequest.class), any(), anyString(), anyString());

        verify(orderService).saveOrder(orderCaptor.capture());
        Order capturedOrder = orderCaptor.getValue();
        assertEquals(TEST_BOT_ID, capturedOrder.getBotId());
        assertEquals(TEST_TICKER, capturedOrder.getTicker());
        assertEquals("FAILED", capturedOrder.getStatus());
        assertTrue(capturedOrder.getErrorMessage().contains("Insufficient BTC balance worth"));
    }

    // Exception handling tests

    @Test
    void processBuySignal_withApiException_savesFailedOrderAndThrowsException() {
        // Arrange
        RuntimeException apiException = new RuntimeException("API error");

        when(tradingViewAlertService.saveAlert(any())).thenReturn(savedAlert);
        when(botConfigurationService.getBotConfiguration(TEST_BOT_ID)).thenReturn(Optional.of(botConfig));
        when(bitvavoApiClient.get(eq("/balance?symbol=EUR"), eq(GetAccountBalanceResponse.class), eq(TEST_API_KEY), eq(TEST_API_SECRET)))
                .thenThrow(apiException);

        // Act & Assert
        RuntimeException exception = assertThrows(RuntimeException.class, 
                () -> tradingService.processAlert(validBuyRequest));

        assertEquals("Error processing buy signal: API error", exception.getMessage());

        verify(orderService).saveOrder(orderCaptor.capture());
        Order capturedOrder = orderCaptor.getValue();
        assertEquals(TEST_BOT_ID, capturedOrder.getBotId());
        assertEquals(TEST_TICKER, capturedOrder.getTicker());
        assertEquals("FAILED", capturedOrder.getStatus());
        assertEquals("API error", capturedOrder.getErrorMessage());
    }

    @Test
    void processSellSignal_withApiException_savesFailedOrderAndThrowsException() {
        // Arrange
        RuntimeException apiException = new RuntimeException("API error");

        when(tradingViewAlertService.saveAlert(any())).thenReturn(savedAlert);
        when(botConfigurationService.getBotConfiguration(TEST_BOT_ID)).thenReturn(Optional.of(botConfig));
        when(bitvavoApiClient.get(eq("/balance?symbol=BTC"), eq(GetAccountBalanceResponse.class), eq(TEST_API_KEY), eq(TEST_API_SECRET)))
                .thenThrow(apiException);

        // Act & Assert
        RuntimeException exception = assertThrows(RuntimeException.class, 
                () -> tradingService.processAlert(validSellRequest));

        assertEquals("Error processing sell signal: API error", exception.getMessage());

        verify(orderService).saveOrder(orderCaptor.capture());
        Order capturedOrder = orderCaptor.getValue();
        assertEquals(TEST_BOT_ID, capturedOrder.getBotId());
        assertEquals(TEST_TICKER, capturedOrder.getTicker());
        assertEquals("FAILED", capturedOrder.getStatus());
        assertEquals("API error", capturedOrder.getErrorMessage());
    }

    // Position update logic tests

    @Test
    void processBuySignal_withNoExistingPosition_createsNewPosition() {
        // Arrange
        when(tradingViewAlertService.saveAlert(any())).thenReturn(savedAlert);
        when(botConfigurationService.getBotConfiguration(TEST_BOT_ID)).thenReturn(Optional.of(botConfig));
        when(bitvavoApiClient.get(eq("/balance?symbol=EUR"), eq(GetAccountBalanceResponse.class), eq(TEST_API_KEY), eq(TEST_API_SECRET))).thenReturn(eurBalanceResponse);
        when(bitvavoApiClient.post(eq("/order"), any(CreateOrderRequest.class), eq(CreateOrderResponse.class), eq(TEST_API_KEY), eq(TEST_API_SECRET))).thenReturn(orderResponse);
        when(positionService.getPositionByBotIdAndTickerAndStatus(TEST_BOT_ID, TEST_TICKER, "OPEN")).thenReturn(Optional.empty());

        // Act
        tradingService.processAlert(validBuyRequest);

        // Assert
        verify(positionService).getPositionByBotIdAndTickerAndStatus(TEST_BOT_ID, TEST_TICKER, "OPEN");
        verify(positionService).savePosition(positionCaptor.capture());

        Position capturedPosition = positionCaptor.getValue();
        assertEquals(TEST_BOT_ID, capturedPosition.getBotId());
        assertEquals(TEST_TICKER, capturedPosition.getTicker());
        assertEquals("OPEN", capturedPosition.getStatus());
    }

    @Test
    void processSellSignal_withExistingPosition_updatesPositionStatus() {
        // Arrange
        when(tradingViewAlertService.saveAlert(any())).thenReturn(savedAlert);
        when(botConfigurationService.getBotConfiguration(TEST_BOT_ID)).thenReturn(Optional.of(botConfig));
        when(bitvavoApiClient.get(eq("/balance?symbol=BTC"), eq(GetAccountBalanceResponse.class), eq(TEST_API_KEY), eq(TEST_API_SECRET))).thenReturn(btcBalanceResponse);
        when(bitvavoApiClient.get(eq("/ticker/price?market=BTCEUR"), eq(GetPriceResponse.class), eq(TEST_API_KEY), eq(TEST_API_SECRET))).thenReturn(btcPriceResponse);
        when(bitvavoApiClient.post(eq("/order"), any(CreateOrderRequest.class), eq(CreateOrderResponse.class), eq(TEST_API_KEY), eq(TEST_API_SECRET))).thenReturn(orderResponse);
        when(positionService.getPositionByBotIdAndTickerAndStatus(TEST_BOT_ID, TEST_TICKER, "OPEN")).thenReturn(Optional.of(existingPosition));

        // Act
        tradingService.processAlert(validSellRequest);

        // Assert
        verify(positionService).getPositionByBotIdAndTickerAndStatus(TEST_BOT_ID, TEST_TICKER, "OPEN");

        existingPosition.setStatus("CLOSED");
        verify(positionService).savePosition(existingPosition);
    }

    @Test
    void processAlert_withDryRunBuySignal_skipsOrderSubmission() {
        // Arrange
        TradingViewAlertRequest dryRunBuyRequest = new TradingViewAlertRequest();
        dryRunBuyRequest.setBotId(TEST_BOT_ID);
        dryRunBuyRequest.setTicker(TEST_TICKER);
        dryRunBuyRequest.setAction("buy");
        dryRunBuyRequest.setTimestamp(TEST_TIMESTAMP);
        dryRunBuyRequest.setDryRun(true);

        when(tradingViewAlertService.saveAlert(any())).thenReturn(savedAlert);
        when(botConfigurationService.getBotConfiguration(TEST_BOT_ID)).thenReturn(Optional.of(botConfig));
        when(bitvavoApiClient.get(eq("/balance?symbol=EUR"), eq(GetAccountBalanceResponse.class), eq(TEST_API_KEY), eq(TEST_API_SECRET))).thenReturn(eurBalanceResponse);
        when(positionService.getPositionByBotIdAndTickerAndStatus(TEST_BOT_ID, TEST_TICKER, "OPEN")).thenReturn(Optional.empty());

        // Act
        tradingService.processAlert(dryRunBuyRequest);

        // Assert
        // Verify alert is saved
        verify(tradingViewAlertService).saveAlert(alertCaptor.capture());
        assertEquals(TEST_BOT_ID, alertCaptor.getValue().getBotId());
        assertEquals(TEST_TICKER, alertCaptor.getValue().getTicker());
        assertEquals("buy", alertCaptor.getValue().getAction());

        // Verify balance is checked
        verify(bitvavoApiClient).get(eq("/balance?symbol=EUR"), eq(GetAccountBalanceResponse.class), eq(TEST_API_KEY), eq(TEST_API_SECRET));

        // Verify order is NOT sent to Bitvavo
        verify(bitvavoApiClient, never()).post(eq("/order"), any(CreateOrderRequest.class), eq(CreateOrderResponse.class), eq(TEST_API_KEY), eq(TEST_API_SECRET));

        // Verify order is saved to database
        verify(orderService).saveOrder(orderCaptor.capture());
        Order capturedOrder = orderCaptor.getValue();
        assertEquals(TEST_BOT_ID, capturedOrder.getBotId());
        assertEquals(TEST_TICKER, capturedOrder.getTicker());
        assertEquals("COMPLETED", capturedOrder.getStatus());
        assertTrue(capturedOrder.getOrderId().startsWith("dry-run-"));

        // Verify position is updated
        verify(positionService).getPositionByBotIdAndTickerAndStatus(TEST_BOT_ID, TEST_TICKER, "OPEN");
        verify(positionService).savePosition(positionCaptor.capture());
    }

    @Test
    void processAlert_withDryRunSellSignal_skipsOrderSubmission() {
        // Arrange
        TradingViewAlertRequest dryRunSellRequest = new TradingViewAlertRequest();
        dryRunSellRequest.setBotId(TEST_BOT_ID);
        dryRunSellRequest.setTicker(TEST_TICKER);
        dryRunSellRequest.setAction("sell");
        dryRunSellRequest.setTimestamp(TEST_TIMESTAMP);
        dryRunSellRequest.setDryRun(true);

        when(tradingViewAlertService.saveAlert(any())).thenReturn(savedAlert);
        when(botConfigurationService.getBotConfiguration(TEST_BOT_ID)).thenReturn(Optional.of(botConfig));
        when(bitvavoApiClient.get(eq("/balance?symbol=BTC"), eq(GetAccountBalanceResponse.class), eq(TEST_API_KEY), eq(TEST_API_SECRET))).thenReturn(btcBalanceResponse);
        when(bitvavoApiClient.get(eq("/ticker/price?market=BTCEUR"), eq(GetPriceResponse.class), eq(TEST_API_KEY), eq(TEST_API_SECRET))).thenReturn(btcPriceResponse);
        when(positionService.getPositionByBotIdAndTickerAndStatus(TEST_BOT_ID, TEST_TICKER, "OPEN")).thenReturn(Optional.of(existingPosition));

        // Act
        tradingService.processAlert(dryRunSellRequest);

        // Assert
        // Verify alert is saved
        verify(tradingViewAlertService).saveAlert(alertCaptor.capture());
        assertEquals(TEST_BOT_ID, alertCaptor.getValue().getBotId());
        assertEquals(TEST_TICKER, alertCaptor.getValue().getTicker());
        assertEquals("sell", alertCaptor.getValue().getAction());

        // Verify balances and price are checked
        verify(bitvavoApiClient).get(eq("/balance?symbol=BTC"), eq(GetAccountBalanceResponse.class), eq(TEST_API_KEY), eq(TEST_API_SECRET));
        verify(bitvavoApiClient).get(eq("/ticker/price?market=BTCEUR"), eq(GetPriceResponse.class), eq(TEST_API_KEY), eq(TEST_API_SECRET));

        // Verify order is NOT sent to Bitvavo
        verify(bitvavoApiClient, never()).post(eq("/order"), any(CreateOrderRequest.class), eq(CreateOrderResponse.class), eq(TEST_API_KEY), eq(TEST_API_SECRET));

        // Verify order is saved to database
        verify(orderService).saveOrder(orderCaptor.capture());
        Order capturedOrder = orderCaptor.getValue();
        assertEquals(TEST_BOT_ID, capturedOrder.getBotId());
        assertEquals(TEST_TICKER, capturedOrder.getTicker());
        assertEquals("COMPLETED", capturedOrder.getStatus());
        assertTrue(capturedOrder.getOrderId().startsWith("dry-run-"));

        // Verify position is updated
        verify(positionService).getPositionByBotIdAndTickerAndStatus(TEST_BOT_ID, TEST_TICKER, "OPEN");

        // For sell signals, we expect the position status to be updated to CLOSED
        existingPosition.setStatus("CLOSED");
        verify(positionService).savePosition(existingPosition);
    }
}
