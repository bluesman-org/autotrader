package nl.jimkaplan.autotrader.service;

import nl.jimkaplan.autotrader.bitvavo.client.BitvavoApiClient;
import nl.jimkaplan.autotrader.bitvavo.model.CreateOrderRequest;
import nl.jimkaplan.autotrader.bitvavo.model.CreateOrderResponse;
import nl.jimkaplan.autotrader.bitvavo.model.GetAccountBalanceResponse;
import nl.jimkaplan.autotrader.bitvavo.model.GetPriceResponse;
import nl.jimkaplan.autotrader.model.BotConfiguration;
import nl.jimkaplan.autotrader.model.Order;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
                .apiKey("test-api-key")
                .apiSecret("test-api-secret")
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
    void processAlert_withInvalidBotId_throwsException() {
        // Arrange
        TradingViewAlertRequest request = new TradingViewAlertRequest();
        request.setTicker(TEST_TICKER);
        request.setAction("buy");
        request.setTimestamp(TEST_TIMESTAMP);

        // Act & Assert
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> tradingService.processAlert(request));
        assertEquals("Bot ID is required", exception.getMessage());
        verify(tradingViewAlertService, never()).saveAlert(any());
    }

    @Test
    void processAlert_withInvalidTicker_throwsException() {
        // Arrange
        TradingViewAlertRequest request = new TradingViewAlertRequest();
        request.setBotId(TEST_BOT_ID);
        request.setAction("buy");
        request.setTimestamp(TEST_TIMESTAMP);

        // Act & Assert
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> tradingService.processAlert(request));
        assertEquals("Ticker is required", exception.getMessage());
        verify(tradingViewAlertService, never()).saveAlert(any());
    }

    @Test
    void processAlert_withInvalidAction_throwsException() {
        // Arrange
        TradingViewAlertRequest request = new TradingViewAlertRequest();
        request.setBotId(TEST_BOT_ID);
        request.setTicker(TEST_TICKER);
        request.setTimestamp(TEST_TIMESTAMP);

        // Act & Assert
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> tradingService.processAlert(request));
        assertEquals("Action is required", exception.getMessage());
        verify(tradingViewAlertService, never()).saveAlert(any());
    }

    @Test
    void processAlert_withInvalidTimestamp_throwsException() {
        // Arrange
        TradingViewAlertRequest request = new TradingViewAlertRequest();
        request.setBotId(TEST_BOT_ID);
        request.setTicker(TEST_TICKER);
        request.setAction("buy");

        // Act & Assert
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> tradingService.processAlert(request));
        assertEquals("Timestamp is required", exception.getMessage());
        verify(tradingViewAlertService, never()).saveAlert(any());
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
        verify(tradingViewAlertService, never()).saveAlert(any());
    }

    @Test
    void processAlert_withBotConfigNotFound_throwsException() {
        // Arrange
        when(botConfigurationService.getBotConfiguration(TEST_BOT_ID)).thenReturn(Optional.empty());

        // Act & Assert
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> tradingService.processAlert(validBuyRequest));
        assertEquals("Bot configuration not found: " + TEST_BOT_ID, exception.getMessage());
    }

    @Test
    void processAlert_withTickerMismatch_throwsException() {
        // Arrange
        BotConfiguration mismatchedConfig = BotConfiguration.builder()
                .botId(TEST_BOT_ID)
                .tradingPair("ETHEUR")
                .build();

        when(tradingViewAlertService.saveAlert(any())).thenReturn(savedAlert);
        when(botConfigurationService.getBotConfiguration(TEST_BOT_ID)).thenReturn(Optional.of(mismatchedConfig));

        // Act & Assert
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> tradingService.processAlert(validBuyRequest));
        assertEquals("Ticker mismatch: " + TEST_TICKER + " does not match bot's configured trading pair: ETHEUR", exception.getMessage());
        verify(tradingViewAlertService).saveAlert(any());
    }

    @Test
    void processAlert_withNonEurTicker_throwsException() {
        // Arrange
        TradingViewAlertRequest request = new TradingViewAlertRequest();
        request.setBotId(TEST_BOT_ID);
        request.setTicker("BTCUSD");
        request.setAction("buy");
        request.setTimestamp(TEST_TIMESTAMP);

        BotConfiguration usdConfig = BotConfiguration.builder()
                .botId(TEST_BOT_ID)
                .tradingPair("BTCUSD")
                .build();

        when(tradingViewAlertService.saveAlert(any())).thenReturn(savedAlert);
        when(botConfigurationService.getBotConfiguration(TEST_BOT_ID)).thenReturn(Optional.of(usdConfig));

        // Act & Assert
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> tradingService.processAlert(request));
        assertEquals("Unsupported ticker: BTCUSD. Only EUR-based trading pairs are supported in v1.", exception.getMessage());
        verify(tradingViewAlertService).saveAlert(any());
    }

    @Test
    void processAlert_withUnsupportedAction_throwsException() {
        // Arrange
        TradingViewAlertRequest request = new TradingViewAlertRequest();
        request.setBotId(TEST_BOT_ID);
        request.setTicker(TEST_TICKER);
        request.setAction("hold");
        request.setTimestamp(TEST_TIMESTAMP);

        when(tradingViewAlertService.saveAlert(any())).thenReturn(savedAlert);
        when(botConfigurationService.getBotConfiguration(TEST_BOT_ID)).thenReturn(Optional.of(botConfig));

        // Act & Assert
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> tradingService.processAlert(request));
        assertEquals("Invalid action: hold. Supported actions are 'buy' and 'sell'.", exception.getMessage());
        verify(tradingViewAlertService).saveAlert(any());
    }
    @Test
    void processAlert_withValidBuySignal_processesSuccessfully() {
        // Arrange
        when(tradingViewAlertService.saveAlert(any())).thenReturn(savedAlert);
        when(botConfigurationService.getBotConfiguration(TEST_BOT_ID)).thenReturn(Optional.of(botConfig));
        when(bitvavoApiClient.get("/balance?symbol=EUR", GetAccountBalanceResponse.class)).thenReturn(eurBalanceResponse);
        when(bitvavoApiClient.post(eq("/order"), any(CreateOrderRequest.class), eq(CreateOrderResponse.class))).thenReturn(orderResponse);
        when(positionService.getPositionByBotIdAndTickerAndStatus(TEST_BOT_ID, TEST_TICKER, "OPEN")).thenReturn(Optional.empty());

        // Act
        tradingService.processAlert(validBuyRequest);

        // Assert
        verify(tradingViewAlertService).saveAlert(alertCaptor.capture());
        assertEquals(TEST_BOT_ID, alertCaptor.getValue().getBotId());
        assertEquals(TEST_TICKER, alertCaptor.getValue().getTicker());
        assertEquals("buy", alertCaptor.getValue().getAction());

        verify(bitvavoApiClient).get("/balance?symbol=EUR", GetAccountBalanceResponse.class);
        verify(bitvavoApiClient).post(eq("/order"), orderRequestCaptor.capture(), eq(CreateOrderResponse.class));

        CreateOrderRequest capturedRequest = orderRequestCaptor.getValue();
        assertEquals(TEST_TICKER, capturedRequest.getMarket());
        assertEquals("buy", capturedRequest.getSide());
        assertEquals("market", capturedRequest.getOrderType());
        assertEquals(BigDecimal.valueOf(TEST_EUR_BALANCE), capturedRequest.getAmountQuote());

        verify(orderService).saveOrder(orderCaptor.capture());
        assertEquals(TEST_BOT_ID, orderCaptor.getValue().getBotId());
        assertEquals(TEST_ORDER_ID.toString(), orderCaptor.getValue().getOrderId());
        assertEquals(TEST_TICKER, orderCaptor.getValue().getTicker());
        assertEquals("COMPLETED", orderCaptor.getValue().getStatus());

        verify(positionService).getPositionByBotIdAndTickerAndStatus(TEST_BOT_ID, TEST_TICKER, "OPEN");
        verify(positionService).savePosition(positionCaptor.capture());
        assertEquals(TEST_BOT_ID, positionCaptor.getValue().getBotId());
        assertEquals(TEST_TICKER, positionCaptor.getValue().getTicker());
        assertEquals("OPEN", positionCaptor.getValue().getStatus());
    }

    @Test
    void processAlert_withInsufficientEurBalance_savesFailedOrder() {
        // Arrange
        GetAccountBalanceResponse lowBalanceResponse = new GetAccountBalanceResponse();
        lowBalanceResponse.setAvailable(BigDecimal.valueOf(4.0)); // Below MIN_EUR_AMOUNT (5.0)

        when(tradingViewAlertService.saveAlert(any())).thenReturn(savedAlert);
        when(botConfigurationService.getBotConfiguration(TEST_BOT_ID)).thenReturn(Optional.of(botConfig));
        when(bitvavoApiClient.get("/balance?symbol=EUR", GetAccountBalanceResponse.class)).thenReturn(lowBalanceResponse);

        // Act
        tradingService.processAlert(validBuyRequest);

        // Assert
        verify(tradingViewAlertService).saveAlert(any());
        verify(bitvavoApiClient).get("/balance?symbol=EUR", GetAccountBalanceResponse.class);
        verify(bitvavoApiClient, never()).post(anyString(), any(), any());

        verify(orderService).saveOrder(orderCaptor.capture());
        assertEquals(TEST_BOT_ID, orderCaptor.getValue().getBotId());
        assertEquals(TEST_TICKER, orderCaptor.getValue().getTicker());
        assertEquals("FAILED", orderCaptor.getValue().getStatus());
        assertEquals("Insufficient EUR balance: 4.0 EUR. Minimum required: 5.0 EUR.", orderCaptor.getValue().getErrorMessage());

        verify(positionService, never()).savePosition(any());
    }

    @Test
    void processAlert_withBuySignalException_savesFailedOrderAndRethrows() {
        // Arrange
        when(tradingViewAlertService.saveAlert(any())).thenReturn(savedAlert);
        when(botConfigurationService.getBotConfiguration(TEST_BOT_ID)).thenReturn(Optional.of(botConfig));
        when(bitvavoApiClient.get("/balance?symbol=EUR", GetAccountBalanceResponse.class)).thenReturn(eurBalanceResponse);
        when(bitvavoApiClient.post(eq("/order"), any(CreateOrderRequest.class), eq(CreateOrderResponse.class)))
                .thenThrow(new RuntimeException("API error"));

        // Act & Assert
        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> tradingService.processAlert(validBuyRequest));
        assertEquals("Error processing buy signal: API error", exception.getMessage());

        verify(tradingViewAlertService).saveAlert(any());
        verify(bitvavoApiClient).get("/balance?symbol=EUR", GetAccountBalanceResponse.class);

        verify(orderService).saveOrder(orderCaptor.capture());
        assertEquals(TEST_BOT_ID, orderCaptor.getValue().getBotId());
        assertEquals(TEST_TICKER, orderCaptor.getValue().getTicker());
        assertEquals("FAILED", orderCaptor.getValue().getStatus());
        assertEquals("API error", orderCaptor.getValue().getErrorMessage());
    }

    @Test
    void processAlert_withValidSellSignal_processesSuccessfully() {
        // Arrange
        when(tradingViewAlertService.saveAlert(any())).thenReturn(savedAlert);
        when(botConfigurationService.getBotConfiguration(TEST_BOT_ID)).thenReturn(Optional.of(botConfig));
        when(bitvavoApiClient.get("/balance?symbol=BTC", GetAccountBalanceResponse.class)).thenReturn(btcBalanceResponse);
        when(bitvavoApiClient.get("/ticker/price?market=BTCEUR", GetPriceResponse.class)).thenReturn(btcPriceResponse);
        when(bitvavoApiClient.post(eq("/order"), any(CreateOrderRequest.class), eq(CreateOrderResponse.class))).thenReturn(orderResponse);
        when(positionService.getPositionByBotIdAndTickerAndStatus(TEST_BOT_ID, TEST_TICKER, "OPEN")).thenReturn(Optional.of(existingPosition));

        // Act
        tradingService.processAlert(validSellRequest);

        // Assert
        verify(tradingViewAlertService).saveAlert(alertCaptor.capture());
        assertEquals(TEST_BOT_ID, alertCaptor.getValue().getBotId());
        assertEquals(TEST_TICKER, alertCaptor.getValue().getTicker());
        assertEquals("sell", alertCaptor.getValue().getAction());

        verify(bitvavoApiClient).get("/balance?symbol=BTC", GetAccountBalanceResponse.class);
        verify(bitvavoApiClient).get("/ticker/price?market=BTCEUR", GetPriceResponse.class);
        verify(bitvavoApiClient).post(eq("/order"), orderRequestCaptor.capture(), eq(CreateOrderResponse.class));

        CreateOrderRequest capturedRequest = orderRequestCaptor.getValue();
        assertEquals(TEST_TICKER, capturedRequest.getMarket());
        assertEquals("sell", capturedRequest.getSide());
        assertEquals("market", capturedRequest.getOrderType());
        assertEquals(BigDecimal.valueOf(TEST_BTC_BALANCE), capturedRequest.getAmount());

        verify(orderService).saveOrder(orderCaptor.capture());
        assertEquals(TEST_BOT_ID, orderCaptor.getValue().getBotId());
        assertEquals(TEST_ORDER_ID.toString(), orderCaptor.getValue().getOrderId());
        assertEquals(TEST_TICKER, orderCaptor.getValue().getTicker());
        assertEquals("COMPLETED", orderCaptor.getValue().getStatus());

        verify(positionService).getPositionByBotIdAndTickerAndStatus(TEST_BOT_ID, TEST_TICKER, "OPEN");
        verify(positionService).savePosition(positionCaptor.capture());
        assertEquals(TEST_BOT_ID, positionCaptor.getValue().getBotId());
        assertEquals(TEST_TICKER, positionCaptor.getValue().getTicker());
        assertEquals("CLOSED", positionCaptor.getValue().getStatus());
        assertEquals("test-position-id", positionCaptor.getValue().getId());
    }

    @Test
    void processAlert_withInsufficientAssetBalance_savesFailedOrder() {
        // Arrange
        GetAccountBalanceResponse lowBalanceResponse = new GetAccountBalanceResponse();
        lowBalanceResponse.setAvailable(BigDecimal.valueOf(0.0001)); // Very small BTC amount

        GetPriceResponse priceResponse = new GetPriceResponse();
        priceResponse.setPrice(BigDecimal.valueOf(30000.0)); // 0.0001 BTC * 30000 EUR = 3 EUR (below MIN_EUR_AMOUNT)

        when(tradingViewAlertService.saveAlert(any())).thenReturn(savedAlert);
        when(botConfigurationService.getBotConfiguration(TEST_BOT_ID)).thenReturn(Optional.of(botConfig));
        when(bitvavoApiClient.get("/balance?symbol=BTC", GetAccountBalanceResponse.class)).thenReturn(lowBalanceResponse);
        when(bitvavoApiClient.get("/ticker/price?market=BTCEUR", GetPriceResponse.class)).thenReturn(priceResponse);

        // Act
        tradingService.processAlert(validSellRequest);

        // Assert
        verify(tradingViewAlertService).saveAlert(any());
        verify(bitvavoApiClient).get("/balance?symbol=BTC", GetAccountBalanceResponse.class);
        verify(bitvavoApiClient).get("/ticker/price?market=BTCEUR", GetPriceResponse.class);
        verify(bitvavoApiClient, never()).post(anyString(), any(), any());

        verify(orderService).saveOrder(orderCaptor.capture());
        assertEquals(TEST_BOT_ID, orderCaptor.getValue().getBotId());
        assertEquals(TEST_TICKER, orderCaptor.getValue().getTicker());
        assertEquals("FAILED", orderCaptor.getValue().getStatus());
        assertEquals("Insufficient BTC balance worth: 3.0 EUR. Minimum required: 5.0 EUR.", orderCaptor.getValue().getErrorMessage());

        verify(positionService, never()).savePosition(any());
    }

    @Test
    void processAlert_withSellSignalException_savesFailedOrderAndRethrows() {
        // Arrange
        when(tradingViewAlertService.saveAlert(any())).thenReturn(savedAlert);
        when(botConfigurationService.getBotConfiguration(TEST_BOT_ID)).thenReturn(Optional.of(botConfig));
        when(bitvavoApiClient.get("/balance?symbol=BTC", GetAccountBalanceResponse.class)).thenReturn(btcBalanceResponse);
        when(bitvavoApiClient.get("/ticker/price?market=BTCEUR", GetPriceResponse.class)).thenReturn(btcPriceResponse);
        when(bitvavoApiClient.post(eq("/order"), any(CreateOrderRequest.class), eq(CreateOrderResponse.class)))
                .thenThrow(new RuntimeException("API error"));

        // Act & Assert
        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> tradingService.processAlert(validSellRequest));
        assertEquals("Error processing sell signal: API error", exception.getMessage());

        verify(tradingViewAlertService).saveAlert(any());
        verify(bitvavoApiClient).get("/balance?symbol=BTC", GetAccountBalanceResponse.class);
        verify(bitvavoApiClient).get("/ticker/price?market=BTCEUR", GetPriceResponse.class);

        verify(orderService).saveOrder(orderCaptor.capture());
        assertEquals(TEST_BOT_ID, orderCaptor.getValue().getBotId());
        assertEquals(TEST_TICKER, orderCaptor.getValue().getTicker());
        assertEquals("FAILED", orderCaptor.getValue().getStatus());
        assertEquals("API error", orderCaptor.getValue().getErrorMessage());
    }

    @Test
    void processAlert_withSellSignalAndNoExistingPosition_doesNotCreateNewPosition() {
        // Arrange
        when(tradingViewAlertService.saveAlert(any())).thenReturn(savedAlert);
        when(botConfigurationService.getBotConfiguration(TEST_BOT_ID)).thenReturn(Optional.of(botConfig));
        when(bitvavoApiClient.get("/balance?symbol=BTC", GetAccountBalanceResponse.class)).thenReturn(btcBalanceResponse);
        when(bitvavoApiClient.get("/ticker/price?market=BTCEUR", GetPriceResponse.class)).thenReturn(btcPriceResponse);
        when(bitvavoApiClient.post(eq("/order"), any(CreateOrderRequest.class), eq(CreateOrderResponse.class))).thenReturn(orderResponse);
        // Return empty to simulate no existing position
        when(positionService.getPositionByBotIdAndTickerAndStatus(TEST_BOT_ID, TEST_TICKER, "OPEN")).thenReturn(Optional.empty());

        // Act
        tradingService.processAlert(validSellRequest);

        // Assert
        verify(tradingViewAlertService).saveAlert(any());
        verify(bitvavoApiClient).get("/balance?symbol=BTC", GetAccountBalanceResponse.class);
        verify(bitvavoApiClient).get("/ticker/price?market=BTCEUR", GetPriceResponse.class);
        verify(bitvavoApiClient).post(eq("/order"), any(CreateOrderRequest.class), eq(CreateOrderResponse.class));

        verify(orderService).saveOrder(any());
        verify(positionService).getPositionByBotIdAndTickerAndStatus(TEST_BOT_ID, TEST_TICKER, "OPEN");
        // Verify that no position is saved when trying to close a non-existent position
        verify(positionService, never()).savePosition(any());
    }
}
