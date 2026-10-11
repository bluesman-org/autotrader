package nl.jimkaplan.autotrader.strategy.service;

import nl.jimkaplan.autotrader.bitvavo.client.BitvavoApiClient;
import nl.jimkaplan.autotrader.bitvavo.model.CreateOrderRequest;
import nl.jimkaplan.autotrader.bitvavo.model.CreateOrderResponse;
import nl.jimkaplan.autotrader.bitvavo.model.GetAccountBalanceResponse;
import nl.jimkaplan.autotrader.bitvavo.model.GetMarketResponse;
import nl.jimkaplan.autotrader.model.document.BotConfiguration;
import nl.jimkaplan.autotrader.repository.LiveOrderRepository;
import nl.jimkaplan.autotrader.repository.LivePositionRepository;
import nl.jimkaplan.autotrader.service.BotConfigurationService;
import nl.jimkaplan.autotrader.strategy.model.document.Candle;
import nl.jimkaplan.autotrader.strategy.model.document.LiveOrder;
import nl.jimkaplan.autotrader.strategy.model.document.LivePosition;
import nl.jimkaplan.autotrader.strategy.model.document.LiveStrategy;
import nl.jimkaplan.autotrader.strategy.rule.RuleCondition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.ResourceAccessException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LiveTradingServiceTest {

    private static final String KEY = "test-key";
    private static final String SECRET = "test-secret";
    private static final String STRATEGY_ID = "strategy-1";
    private static final long START = 1_783_069_200_000L;
    private static final long HOUR = 3_600_000L;

    @Mock
    private BotConfigurationService botConfigurationService;

    @Mock
    private BitvavoApiClient bitvavoApiClient;

    @Mock
    private LiveOrderRepository liveOrderRepository;

    @Mock
    private LivePositionRepository livePositionRepository;

    @InjectMocks
    private LiveTradingService liveTradingService;

    @Test
    void dryRunBuy_recordsTheOrderAndOpensThePosition() {
        List<Candle> series = vUpToCrossover();
        LiveStrategy strategy = strategy(true, 0, RuleCondition.SAR_BEARISH_CROSSOVER);

        liveTradingService.evaluateAndTrade(strategy, series);

        double price = series.get(series.size() - 1).getClose().doubleValue();
        ArgumentCaptor<LiveOrder> orderCaptor = ArgumentCaptor.forClass(LiveOrder.class);
        verify(liveOrderRepository).save(orderCaptor.capture());
        LiveOrder order = orderCaptor.getValue();
        assertEquals(STRATEGY_ID, order.getStrategyId());
        assertEquals("BTC-EUR", order.getMarket());
        assertEquals("BUY", order.getSide());
        assertEquals("DRY_RUN", order.getStatus());
        assertTrue(order.isDryRun());
        assertEquals(series.get(series.size() - 1).getOpenTime(), order.getSignalOpenTime());
        assertTrue(order.getReason().contains("MACD_BULLISH_CROSSOVER"));
        assertEquals(100.0, order.getAmountQuote(), 1e-9);
        assertEquals(100.0 / price, order.getQuantity(), 1e-9);
        assertEquals(price, order.getPrice(), 1e-9);
        assertEquals(0.2, order.getFee(), 1e-9);

        ArgumentCaptor<LivePosition> positionCaptor = ArgumentCaptor.forClass(LivePosition.class);
        verify(livePositionRepository).save(positionCaptor.capture());
        LivePosition position = positionCaptor.getValue();
        assertEquals("OPEN", position.getStatus());
        assertEquals(100.0 / price, position.getQuantity(), 1e-9);
        assertEquals(price, position.getEntryPrice(), 1e-9);
        assertEquals(0.2, position.getEntryFee(), 1e-9);
        assertTrue(position.isDryRun());
        verifyNoInteractions(bitvavoApiClient, botConfigurationService);
    }

    @Test
    void withoutASignal_nothingIsRecorded() {
        LiveStrategy strategy = strategy(true, 0, RuleCondition.PRICE_BELOW_SAR);

        liveTradingService.evaluateAndTrade(strategy, rising(60));

        verify(livePositionRepository, never()).save(any());
        verifyNoInteractions(liveOrderRepository, bitvavoApiClient, botConfigurationService);
    }

    @Test
    void insideTheWarmUp_theSignalIsIgnored() {
        LiveStrategy strategy = strategy(true, 200, RuleCondition.SAR_BEARISH_CROSSOVER);

        liveTradingService.evaluateAndTrade(strategy, vUpToCrossover());

        verify(livePositionRepository, never()).save(any());
        verifyNoInteractions(liveOrderRepository, bitvavoApiClient, botConfigurationService);
    }

    @Test
    void dryRunExit_closesThePositionWithTheRealisedProfit() {
        LiveStrategy strategy = strategy(true, 0, RuleCondition.PRICE_ABOVE_SAR);
        when(livePositionRepository.findByStrategyIdAndStatus(STRATEGY_ID, "OPEN"))
                .thenReturn(Optional.of(openPosition(0.5, 200.0, 0.1)));

        List<Candle> series = rising(60);
        liveTradingService.evaluateAndTrade(strategy, series);

        double price = series.get(series.size() - 1).getClose().doubleValue();
        double proceeds = 0.5 * price;
        double fee = proceeds * 0.002;
        double profit = proceeds - 0.5 * 200.0 - fee - 0.1;

        ArgumentCaptor<LiveOrder> orderCaptor = ArgumentCaptor.forClass(LiveOrder.class);
        verify(liveOrderRepository).save(orderCaptor.capture());
        LiveOrder order = orderCaptor.getValue();
        assertEquals("SELL", order.getSide());
        assertEquals("DRY_RUN", order.getStatus());
        assertEquals(proceeds, order.getAmountQuote(), 1e-9);
        assertEquals(0.5, order.getQuantity(), 1e-9);
        assertEquals(price, order.getPrice(), 1e-9);
        assertEquals(fee, order.getFee(), 1e-9);

        ArgumentCaptor<LivePosition> positionCaptor = ArgumentCaptor.forClass(LivePosition.class);
        verify(livePositionRepository).save(positionCaptor.capture());
        LivePosition position = positionCaptor.getValue();
        assertEquals("CLOSED", position.getStatus());
        assertEquals(price, position.getExitPrice(), 1e-9);
        assertEquals(series.get(series.size() - 1).getOpenTime(), position.getExitTime());
        assertEquals(profit, position.getProfit(), 1e-9);
    }

    @Test
    void entrySignalsAreIgnoredWhileAPositionIsOpen() {
        LiveStrategy strategy = strategy(true, 0, RuleCondition.MACD_BEARISH_CROSSOVER);
        when(livePositionRepository.findByStrategyIdAndStatus(STRATEGY_ID, "OPEN"))
                .thenReturn(Optional.of(openPosition(0.5, 200.0, 0.1)));

        liveTradingService.evaluateAndTrade(strategy, vUpToCrossover());

        verify(liveOrderRepository, never()).save(any());
        verify(livePositionRepository, never()).save(any());
        verifyNoInteractions(bitvavoApiClient, botConfigurationService);
    }

    @Test
    void exitSignalsAreIgnoredWhileFlat() {
        LiveStrategy strategy = strategy(true, 0, RuleCondition.PRICE_ABOVE_SAR);

        liveTradingService.evaluateAndTrade(strategy, rising(60));

        verify(liveOrderRepository, never()).save(any());
        verify(livePositionRepository, never()).save(any());
    }

    @Test
    void liveBuy_withoutAnActiveBot_recordsAFailure() {
        LiveStrategy strategy = strategy(false, 0, RuleCondition.SAR_BEARISH_CROSSOVER);
        when(botConfigurationService.getBotConfiguration("bot-1")).thenReturn(Optional.empty());

        liveTradingService.evaluateAndTrade(strategy, vUpToCrossover());

        LiveOrder order = captureSingleOrder();
        assertEquals("BUY", order.getSide());
        assertEquals("FAILED", order.getStatus());
        assertTrue(order.getErrorMessage().contains("No active bot configuration"));
        verify(livePositionRepository, never()).save(any());
        verifyNoInteractions(bitvavoApiClient);
    }

    @Test
    void liveBuy_withInsufficientBalance_recordsAFailure() {
        LiveStrategy strategy = strategy(false, 0, RuleCondition.SAR_BEARISH_CROSSOVER);
        when(botConfigurationService.getBotConfiguration("bot-1")).thenReturn(Optional.of(bot()));
        when(bitvavoApiClient.get("/balance?symbol=EUR", GetAccountBalanceResponse[].class, KEY, SECRET))
                .thenReturn(new GetAccountBalanceResponse[]{balance("EUR", 1.0)});

        liveTradingService.evaluateAndTrade(strategy, vUpToCrossover());

        LiveOrder order = captureSingleOrder();
        assertEquals("FAILED", order.getStatus());
        assertTrue(order.getErrorMessage().contains("Insufficient EUR balance: 1.0"));
        verify(livePositionRepository, never()).save(any());
    }

    @Test
    void liveBuy_placesAMarketOrderAndTracksTheFill() {
        LiveStrategy strategy = strategy(false, 0, RuleCondition.SAR_BEARISH_CROSSOVER);
        when(botConfigurationService.getBotConfiguration("bot-1")).thenReturn(Optional.of(bot()));
        when(bitvavoApiClient.get("/balance?symbol=EUR", GetAccountBalanceResponse[].class, KEY, SECRET))
                .thenReturn(new GetAccountBalanceResponse[]{balance("EUR", 1000.0)});
        when(bitvavoApiClient.get("/markets?market=BTC-EUR", GetMarketResponse.class, KEY, SECRET))
                .thenReturn(market(5.0, 2, 8));
        UUID orderId = UUID.randomUUID();
        CreateOrderResponse response = new CreateOrderResponse();
        response.setOrderId(orderId);
        response.setStatus("COMPLETED");
        response.setFilledAmount(new BigDecimal("0.001"));
        response.setFilledAmountQuote(new BigDecimal("100.00"));
        response.setFeePaid(new BigDecimal("0.2"));
        when(bitvavoApiClient.post(eq("/order"), any(CreateOrderRequest.class),
                eq(CreateOrderResponse.class), eq(KEY), eq(SECRET))).thenReturn(response);

        liveTradingService.evaluateAndTrade(strategy, vUpToCrossover());

        ArgumentCaptor<CreateOrderRequest> requestCaptor = ArgumentCaptor.forClass(CreateOrderRequest.class);
        verify(bitvavoApiClient).post(eq("/order"), requestCaptor.capture(),
                eq(CreateOrderResponse.class), eq(KEY), eq(SECRET));
        CreateOrderRequest request = requestCaptor.getValue();
        assertEquals("BTC-EUR", request.getMarket());
        assertEquals("buy", request.getSide());
        assertEquals("market", request.getOrderType());
        assertEquals(0, new BigDecimal("100").compareTo(request.getAmountQuote()));
        assertEquals(1L, request.getOperatorId());

        LiveOrder order = captureSingleOrder();
        assertEquals("FILLED", order.getStatus());
        assertEquals(orderId.toString(), order.getOrderId());
        assertEquals(0.001, order.getQuantity(), 1e-12);
        assertEquals(100000.0, order.getPrice(), 1e-9);
        assertEquals(0.2, order.getFee(), 1e-9);
        assertTrue(!order.isDryRun());

        ArgumentCaptor<LivePosition> positionCaptor = ArgumentCaptor.forClass(LivePosition.class);
        verify(livePositionRepository).save(positionCaptor.capture());
        LivePosition position = positionCaptor.getValue();
        assertEquals("OPEN", position.getStatus());
        assertEquals(0.001, position.getQuantity(), 1e-12);
        assertEquals(100000.0, position.getEntryPrice(), 1e-9);
        assertEquals(0.2, position.getEntryFee(), 1e-9);
    }

    @Test
    void liveBuy_belowTheMarketMinimum_recordsAFailure() {
        LiveStrategy strategy = strategy(false, 0, RuleCondition.SAR_BEARISH_CROSSOVER);
        when(botConfigurationService.getBotConfiguration("bot-1")).thenReturn(Optional.of(bot()));
        when(bitvavoApiClient.get("/balance?symbol=EUR", GetAccountBalanceResponse[].class, KEY, SECRET))
                .thenReturn(new GetAccountBalanceResponse[]{balance("EUR", 1000.0)});
        when(bitvavoApiClient.get("/markets?market=BTC-EUR", GetMarketResponse.class, KEY, SECRET))
                .thenReturn(market(1000.0, 2, 8));

        liveTradingService.evaluateAndTrade(strategy, vUpToCrossover());

        LiveOrder order = captureSingleOrder();
        assertEquals("FAILED", order.getStatus());
        assertTrue(order.getErrorMessage().contains("Below the market minimum"));
        verify(bitvavoApiClient, never()).post(any(), any(), any(), any(), any());
        verify(livePositionRepository, never()).save(any());
    }

    @Test
    void liveBuy_whenTheExchangeFails_recordsAFailure() {
        LiveStrategy strategy = strategy(false, 0, RuleCondition.SAR_BEARISH_CROSSOVER);
        when(botConfigurationService.getBotConfiguration("bot-1")).thenReturn(Optional.of(bot()));
        when(bitvavoApiClient.get("/balance?symbol=EUR", GetAccountBalanceResponse[].class, KEY, SECRET))
                .thenThrow(new ResourceAccessException("connect timed out"));

        liveTradingService.evaluateAndTrade(strategy, vUpToCrossover());

        LiveOrder order = captureSingleOrder();
        assertEquals("FAILED", order.getStatus());
        assertEquals("connect timed out", order.getErrorMessage());
        verify(livePositionRepository, never()).save(any());
    }

    @Test
    void liveSell_placesAMarketOrderAndRealisesTheProfit() {
        LiveStrategy strategy = strategy(false, 0, RuleCondition.PRICE_ABOVE_SAR);
        LivePosition position = openPosition(0.001, 100000.0, 0.2);
        when(livePositionRepository.findByStrategyIdAndStatus(STRATEGY_ID, "OPEN"))
                .thenReturn(Optional.of(position));
        when(botConfigurationService.getBotConfiguration("bot-1")).thenReturn(Optional.of(bot()));
        when(bitvavoApiClient.get("/markets?market=BTC-EUR", GetMarketResponse.class, KEY, SECRET))
                .thenReturn(market(5.0, 2, 8));
        UUID orderId = UUID.randomUUID();
        CreateOrderResponse response = new CreateOrderResponse();
        response.setOrderId(orderId);
        response.setFilledAmountQuote(new BigDecimal("110.00"));
        response.setFeePaid(new BigDecimal("0.22"));
        when(bitvavoApiClient.post(eq("/order"), any(CreateOrderRequest.class),
                eq(CreateOrderResponse.class), eq(KEY), eq(SECRET))).thenReturn(response);

        liveTradingService.evaluateAndTrade(strategy, rising(60));

        ArgumentCaptor<CreateOrderRequest> requestCaptor = ArgumentCaptor.forClass(CreateOrderRequest.class);
        verify(bitvavoApiClient).post(eq("/order"), requestCaptor.capture(),
                eq(CreateOrderResponse.class), eq(KEY), eq(SECRET));
        CreateOrderRequest request = requestCaptor.getValue();
        assertEquals("sell", request.getSide());
        assertEquals(0, new BigDecimal("0.001").compareTo(request.getAmount()));

        LiveOrder order = captureSingleOrder();
        assertEquals("SELL", order.getSide());
        assertEquals("FILLED", order.getStatus());
        assertEquals(110.0, order.getAmountQuote(), 1e-9);
        assertEquals(110000.0, order.getPrice(), 1e-9);

        ArgumentCaptor<LivePosition> positionCaptor = ArgumentCaptor.forClass(LivePosition.class);
        verify(livePositionRepository).save(positionCaptor.capture());
        LivePosition closed = positionCaptor.getValue();
        assertEquals("CLOSED", closed.getStatus());
        assertEquals(110.0 - 100.0 - 0.22 - 0.2, closed.getProfit(), 1e-9);
    }

    @Test
    void liveSell_whenTheQuantityRoundsToZero_recordsAFailure() {
        LiveStrategy strategy = strategy(false, 0, RuleCondition.PRICE_ABOVE_SAR);
        when(livePositionRepository.findByStrategyIdAndStatus(STRATEGY_ID, "OPEN"))
                .thenReturn(Optional.of(openPosition(0.000_000_001, 100000.0, 0.2)));
        when(botConfigurationService.getBotConfiguration("bot-1")).thenReturn(Optional.of(bot()));
        when(bitvavoApiClient.get("/markets?market=BTC-EUR", GetMarketResponse.class, KEY, SECRET))
                .thenReturn(market(5.0, 2, 4));

        liveTradingService.evaluateAndTrade(strategy, rising(60));

        LiveOrder order = captureSingleOrder();
        assertEquals("FAILED", order.getStatus());
        assertTrue(order.getErrorMessage().contains("rounds to zero"));
        verify(bitvavoApiClient, never()).post(any(), any(), any(), any(), any());
        verify(livePositionRepository, never()).save(any());
    }

    private LiveOrder captureSingleOrder() {
        ArgumentCaptor<LiveOrder> orderCaptor = ArgumentCaptor.forClass(LiveOrder.class);
        verify(liveOrderRepository).save(orderCaptor.capture());
        LiveOrder order = orderCaptor.getValue();
        assertNotNull(order);
        return order;
    }

    private static LiveStrategy strategy(boolean dryRun, int warmUp, RuleCondition exitCondition) {
        LiveStrategy strategy = LiveStrategy.builder()
                .botId("bot-1")
                .market("BTC-EUR")
                .interval("1h")
                .macdFastPeriod(12)
                .macdSlowPeriod(26)
                .macdSignalPeriod(9)
                .sarAcceleration(0.02)
                .sarMaximum(0.2)
                .entryOperator("ALL")
                .entryConditions(List.of("MACD_BULLISH_CROSSOVER"))
                .exitOperator("ALL")
                .exitConditions(List.of(exitCondition.name()))
                .amountPerBuy(100)
                .warmUpCandles(warmUp)
                .dryRun(dryRun)
                .enabled(true)
                .build();
        strategy.setId(STRATEGY_ID);
        return strategy;
    }

    private static LivePosition openPosition(double quantity, double entryPrice, double entryFee) {
        LivePosition position = LivePosition.builder()
                .strategyId(STRATEGY_ID)
                .market("BTC-EUR")
                .status("OPEN")
                .quantity(quantity)
                .entryPrice(entryPrice)
                .entryFee(entryFee)
                .entryTime(START)
                .dryRun(true)
                .build();
        position.setId("position-1");
        return position;
    }

    private static BotConfiguration bot() {
        return BotConfiguration.builder()
                .botId("bot-1")
                .operatorId(1L)
                .apiKey(KEY)
                .apiSecret(SECRET)
                .tradingPair("BTC-EUR")
                .active(true)
                .build();
    }

    private static GetAccountBalanceResponse balance(String symbol, double available) {
        GetAccountBalanceResponse balance = new GetAccountBalanceResponse();
        balance.setSymbol(symbol);
        balance.setAvailable(BigDecimal.valueOf(available));
        return balance;
    }

    private static GetMarketResponse market(double minimum, int notionalDecimals, int quantityDecimals) {
        GetMarketResponse market = new GetMarketResponse();
        market.setMarket("BTC-EUR");
        market.setMinOrderInQuoteAsset(BigDecimal.valueOf(minimum));
        market.setNotionalDecimals(notionalDecimals);
        market.setQuantityDecimals(quantityDecimals);
        return market;
    }

    /**
     * The first 42 bars of a fall-then-rise fixture; its MACD bullish crossover sits exactly on the
     * last bar (verified against the indicator fixtures' conventions).
     */
    private static List<Candle> vUpToCrossover() {
        List<Candle> candles = new ArrayList<>(42);
        for (int i = 0; i < 42; i++) {
            double close = i <= 40 ? 100.0 - i : 60.0 + (i - 40) * 1.5;
            candles.add(candle(i, close));
        }
        return candles;
    }

    private static List<Candle> rising(int count) {
        List<Candle> candles = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            candles.add(candle(i, 100.0 + i));
        }
        return candles;
    }

    private static Candle candle(int index, double close) {
        return Candle.builder()
                .market("BTC-EUR")
                .interval("1h")
                .openTime(START + index * HOUR)
                .open(BigDecimal.valueOf(close))
                .high(BigDecimal.valueOf(close + 1))
                .low(BigDecimal.valueOf(close - 1))
                .close(BigDecimal.valueOf(close))
                .volume(BigDecimal.ONE)
                .build();
    }
}
