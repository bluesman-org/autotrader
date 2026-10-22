package nl.jimkaplan.autotrader.strategy.service;

import nl.jimkaplan.autotrader.bitvavo.client.BitvavoApiClient;
import nl.jimkaplan.autotrader.bitvavo.model.CreateOrderRequest;
import nl.jimkaplan.autotrader.bitvavo.model.CreateOrderResponse;
import nl.jimkaplan.autotrader.bitvavo.model.GetAccountBalanceResponse;
import nl.jimkaplan.autotrader.bitvavo.model.GetMarketResponse;
import nl.jimkaplan.autotrader.model.document.BitvavoCredentials;
import nl.jimkaplan.autotrader.repository.LiveOrderRepository;
import nl.jimkaplan.autotrader.repository.LivePositionRepository;
import nl.jimkaplan.autotrader.repository.LiveStrategyRepository;
import nl.jimkaplan.autotrader.service.BitvavoCredentialsService;
import nl.jimkaplan.autotrader.strategy.live.LiveDecision;
import nl.jimkaplan.autotrader.strategy.model.document.Candle;
import nl.jimkaplan.autotrader.strategy.model.document.LiveOrder;
import nl.jimkaplan.autotrader.strategy.model.document.LivePosition;
import nl.jimkaplan.autotrader.strategy.model.document.LiveStrategy;
import nl.jimkaplan.autotrader.strategy.rule.RuleCondition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
    private BitvavoCredentialsService credentialsService;

    @Mock
    private BitvavoApiClient bitvavoApiClient;

    @Mock
    private LiveOrderRepository liveOrderRepository;

    @Mock
    private LivePositionRepository livePositionRepository;

    @Mock
    private LiveStrategyRepository liveStrategyRepository;

    @Mock
    private LiveAlertService alerts;

    @InjectMocks
    private LiveTradingService liveTradingService;

    @Test
    void decide_returnsABuyWhenTheEntrySignalFires() {
        List<Candle> series = vUpToCrossover();
        LiveStrategy strategy = strategy(true, 0, RuleCondition.SAR_BEARISH_CROSSOVER);

        LiveDecision decision = liveTradingService.decide(strategy, series);

        assertNotNull(decision);
        assertEquals("BUY", decision.side());
        assertEquals(series.get(series.size() - 1).getOpenTime(), decision.signalOpenTime());
        assertTrue(decision.reason().contains("MACD_BULLISH_CROSSOVER"));
        verify(liveOrderRepository).existsByStrategyIdAndAwaitingReconciliationTrue(STRATEGY_ID);
        verify(liveOrderRepository, never()).save(any());
        verifyNoInteractions(bitvavoApiClient, credentialsService);
    }

    @Test
    void decide_returnsNullWithoutASignal() {
        LiveStrategy strategy = strategy(true, 0, RuleCondition.PRICE_BELOW_SAR);

        assertNull(liveTradingService.decide(strategy, rising(60)));

        verify(liveOrderRepository).existsByStrategyIdAndAwaitingReconciliationTrue(STRATEGY_ID);
        verify(liveOrderRepository, never()).save(any());
        verifyNoInteractions(bitvavoApiClient, credentialsService);
    }

    @Test
    void decide_returnsNullWhileAnOrderAwaitsReconciliation() {
        LiveStrategy strategy = strategy(false, 0, RuleCondition.SAR_BEARISH_CROSSOVER);
        when(liveOrderRepository.existsByStrategyIdAndAwaitingReconciliationTrue(STRATEGY_ID))
                .thenReturn(true);

        assertNull(liveTradingService.decide(strategy, vUpToCrossover()));
        assertNull(liveTradingService.decide(strategy, vUpToCrossover()));

        verify(livePositionRepository, never()).findByStrategyIdAndStatus(any(), any());
    }

    @Test
    void decide_respectsTheWarmUp() {
        LiveStrategy strategy = strategy(true, 200, RuleCondition.SAR_BEARISH_CROSSOVER);

        assertNull(liveTradingService.decide(strategy, vUpToCrossover()));
    }

    @Test
    void decide_returnsASellWhenAnExitSignalFiresWithAnOpenPosition() {
        LiveStrategy strategy = strategy(true, 0, RuleCondition.PRICE_ABOVE_SAR);
        when(livePositionRepository.findByStrategyIdAndStatus(STRATEGY_ID, "OPEN"))
                .thenReturn(Optional.of(openPosition(0.5, 200.0, 0.1)));

        List<Candle> series = rising(60);
        LiveDecision decision = liveTradingService.decide(strategy, series);

        assertNotNull(decision);
        assertEquals("SELL", decision.side());
        assertEquals(series.get(series.size() - 1).getOpenTime(), decision.signalOpenTime());
        assertTrue(decision.reason().contains("PRICE_ABOVE_SAR"));
        verify(liveOrderRepository).existsByStrategyIdAndAwaitingReconciliationTrue(STRATEGY_ID);
        verify(liveOrderRepository, never()).save(any());
    }

    @Test
    void decide_ignoresEntrySignalsWhileAPositionIsOpen() {
        LiveStrategy strategy = strategy(true, 0, RuleCondition.MACD_BEARISH_CROSSOVER);
        when(livePositionRepository.findByStrategyIdAndStatus(STRATEGY_ID, "OPEN"))
                .thenReturn(Optional.of(openPosition(0.5, 200.0, 0.1)));

        assertNull(liveTradingService.decide(strategy, vUpToCrossover()));
    }

    @Test
    void decide_ignoresExitSignalsWhileFlat() {
        LiveStrategy strategy = strategy(true, 0, RuleCondition.PRICE_ABOVE_SAR);

        assertNull(liveTradingService.decide(strategy, rising(60)));
    }

    @Test
    void fill_buysAtTheDecisionMomentsPriceInDryRun() {
        List<Candle> series = vUpToCrossover();
        LiveStrategy strategy = strategy(true, 0, RuleCondition.SAR_BEARISH_CROSSOVER);
        LiveDecision decision = liveTradingService.decide(strategy, series);

        liveTradingService.fill(strategy, decision, 62.0);

        ArgumentCaptor<LiveOrder> orderCaptor = ArgumentCaptor.forClass(LiveOrder.class);
        verify(liveOrderRepository).save(orderCaptor.capture());
        LiveOrder order = orderCaptor.getValue();
        assertEquals("BUY", order.getSide());
        assertEquals("DRY_RUN", order.getStatus());
        assertTrue(order.isDryRun());
        assertEquals(decision.signalOpenTime(), order.getSignalOpenTime());
        assertEquals(100.0, order.getAmountQuote(), 1e-9);
        assertEquals(62.0, order.getPrice(), 1e-9);
        assertEquals(100.0 / 62.0, order.getQuantity(), 1e-9);
        assertEquals(0.2, order.getFee(), 1e-9);

        ArgumentCaptor<LivePosition> positionCaptor = ArgumentCaptor.forClass(LivePosition.class);
        verify(livePositionRepository).save(positionCaptor.capture());
        LivePosition position = positionCaptor.getValue();
        assertEquals("OPEN", position.getStatus());
        assertEquals(62.0, position.getEntryPrice(), 1e-9);
        assertEquals(decision.signalOpenTime(), position.getEntryTime());
        verifyNoInteractions(bitvavoApiClient, credentialsService, alerts);
    }

    @Test
    void fill_sellsAtTheDecisionMomentsPriceInDryRun() {
        LiveStrategy strategy = strategy(true, 0, RuleCondition.PRICE_ABOVE_SAR);
        when(livePositionRepository.findByStrategyIdAndStatus(STRATEGY_ID, "OPEN"))
                .thenReturn(Optional.of(openPosition(0.5, 200.0, 0.1)));

        LiveDecision decision = liveTradingService.decide(strategy, rising(60));

        liveTradingService.fill(strategy, decision, 160.0);

        double proceeds = 0.5 * 160.0;
        double fee = proceeds * 0.002;
        double profit = proceeds - 0.5 * 200.0 - fee - 0.1;

        ArgumentCaptor<LiveOrder> orderCaptor = ArgumentCaptor.forClass(LiveOrder.class);
        verify(liveOrderRepository).save(orderCaptor.capture());
        LiveOrder order = orderCaptor.getValue();
        assertEquals("SELL", order.getSide());
        assertEquals("DRY_RUN", order.getStatus());
        assertEquals(160.0, order.getPrice(), 1e-9);
        assertEquals(decision.signalOpenTime(), order.getSignalOpenTime());

        ArgumentCaptor<LivePosition> positionCaptor = ArgumentCaptor.forClass(LivePosition.class);
        verify(livePositionRepository).save(positionCaptor.capture());
        LivePosition position = positionCaptor.getValue();
        assertEquals("CLOSED", position.getStatus());
        assertEquals(160.0, position.getExitPrice(), 1e-9);
        assertEquals(decision.signalOpenTime(), position.getExitTime());
        assertEquals(profit, position.getProfit(), 1e-9);
        verifyNoInteractions(alerts);
    }

    @Test
    void recordSkippedCandle_isVisibleInTheOrders() {
        LiveStrategy strategy = strategy(true, 0, RuleCondition.SAR_BEARISH_CROSSOVER);

        liveTradingService.recordSkippedCandle(strategy, START);

        ArgumentCaptor<LiveOrder> orderCaptor = ArgumentCaptor.forClass(LiveOrder.class);
        verify(liveOrderRepository).save(orderCaptor.capture());
        LiveOrder order = orderCaptor.getValue();
        assertEquals("SKIP", order.getSide());
        assertEquals("SKIPPED", order.getStatus());
        assertEquals(START, order.getSignalOpenTime());
        assertTrue(order.getReason().contains("not evaluated"));
    }

    @Test
    void fill_liveBuyWithoutAnApiKey_recordsARejection() {
        LiveStrategy strategy = strategy(false, 0, RuleCondition.SAR_BEARISH_CROSSOVER);
        when(credentialsService.get()).thenReturn(Optional.empty());

        liveTradingService.fill(strategy, decision("BUY"), 62.0);

        LiveOrder order = captureSingleOrder();
        assertEquals("BUY", order.getSide());
        assertEquals("REJECTED", order.getStatus());
        assertNull(order.getClientOrderId());
        assertTrue(order.getErrorMessage().contains("No Bitvavo API key is configured"));
        verify(livePositionRepository, never()).save(any());
        verifyNoInteractions(bitvavoApiClient);
        verify(alerts).realFailure(eq(strategy), any(LiveOrder.class), eq("rejected"));
    }

    @Test
    void fill_liveBuyWithInsufficientBalance_recordsARejection() {
        LiveStrategy strategy = strategy(false, 0, RuleCondition.SAR_BEARISH_CROSSOVER);
        when(credentialsService.get()).thenReturn(Optional.of(stored()));
        when(bitvavoApiClient.get("/balance?symbol=EUR", GetAccountBalanceResponse[].class, KEY, SECRET))
                .thenReturn(new GetAccountBalanceResponse[]{balance("EUR", 1.0)});

        liveTradingService.fill(strategy, decision("BUY"), 62.0);

        LiveOrder order = captureSingleOrder();
        assertEquals("REJECTED", order.getStatus());
        assertNull(order.getClientOrderId());
        assertTrue(order.getErrorMessage().contains("Insufficient EUR balance: 1.0"));
        verify(livePositionRepository, never()).save(any());
    }

    @Test
    void fill_liveBuyPlacesAMarketOrderAndTracksTheFill() {
        LiveStrategy strategy = strategy(false, 0, RuleCondition.SAR_BEARISH_CROSSOVER);
        when(credentialsService.get()).thenReturn(Optional.of(stored()));
        when(bitvavoApiClient.get("/balance?symbol=EUR", GetAccountBalanceResponse[].class, KEY, SECRET))
                .thenReturn(new GetAccountBalanceResponse[]{balance("EUR", 1000.0)});
        when(bitvavoApiClient.get("/markets?market=BTC-EUR", GetMarketResponse.class, KEY, SECRET))
                .thenReturn(market(5.0, 2, 8));
        UUID orderId = UUID.randomUUID();
        CreateOrderResponse response = new CreateOrderResponse();
        response.setOrderId(orderId);
        response.setStatus("filled");
        response.setFilledAmount(new BigDecimal("0.001"));
        response.setFilledAmountQuote(new BigDecimal("100.00"));
        response.setFeePaid(new BigDecimal("0.2"));
        when(bitvavoApiClient.post(eq("/order"), any(CreateOrderRequest.class),
                eq(CreateOrderResponse.class), eq(KEY), eq(SECRET))).thenReturn(response);

        List<LiveOrder> saves = recordSaves();
        LiveDecision decision = decision("BUY");
        liveTradingService.fill(strategy, decision, 62.0);

        ArgumentCaptor<CreateOrderRequest> requestCaptor = ArgumentCaptor.forClass(CreateOrderRequest.class);
        verify(bitvavoApiClient).post(eq("/order"), requestCaptor.capture(),
                eq(CreateOrderResponse.class), eq(KEY), eq(SECRET));
        CreateOrderRequest request = requestCaptor.getValue();
        assertEquals("BTC-EUR", request.getMarket());
        assertEquals("buy", request.getSide());
        assertEquals("market", request.getOrderType());
        assertEquals(0, new BigDecimal("100").compareTo(request.getAmountQuote()));
        assertEquals(1L, request.getOperatorId());

        assertEquals(2, saves.size());
        LiveOrder pending = saves.get(0);
        LiveOrder order = saves.get(1);
        assertEquals("PENDING", pending.getStatus());
        assertTrue(pending.isAwaitingReconciliation());
        assertNotNull(pending.getClientOrderId());
        assertEquals(100.0, pending.getAmountQuote(), 1e-9);
        assertEquals(100.0 / 62.0, pending.getQuantity(), 1e-9);
        assertEquals(62.0, pending.getPrice(), 1e-9);
        assertEquals(0.2, pending.getFee(), 1e-9);
        assertEquals(pending.getClientOrderId(), request.getClientOrderId().toString());
        assertEquals("FILLED", order.getStatus());
        assertFalse(order.isAwaitingReconciliation());
        assertEquals(orderId.toString(), order.getOrderId());
        assertEquals(0.001, order.getQuantity(), 1e-12);
        assertEquals(100000.0, order.getPrice(), 1e-9);
        assertEquals(0.2, order.getFee(), 1e-9);
        assertFalse(order.isDryRun());
        assertEquals(pending.getClientOrderId(), order.getClientOrderId());
        assertEquals(decision.signalOpenTime(), order.getSignalOpenTime());

        ArgumentCaptor<LivePosition> positionCaptor = ArgumentCaptor.forClass(LivePosition.class);
        verify(livePositionRepository).save(positionCaptor.capture());
        LivePosition position = positionCaptor.getValue();
        assertEquals("OPEN", position.getStatus());
        assertEquals(0.001, position.getQuantity(), 1e-12);
        assertEquals(100000.0, position.getEntryPrice(), 1e-9);
        assertEquals(0.2, position.getEntryFee(), 1e-9);
        assertEquals(decision.signalOpenTime(), position.getEntryTime());
        verify(alerts).realFill(eq(strategy), any(LiveOrder.class), eq(position));
    }

    @Test
    void fill_liveBuyWithANonFilledResponseLeavesTheRowPendingAndOpensNoPosition() {
        LiveStrategy strategy = strategy(false, 0, RuleCondition.SAR_BEARISH_CROSSOVER);
        when(credentialsService.get()).thenReturn(Optional.of(stored()));
        when(bitvavoApiClient.get("/balance?symbol=EUR", GetAccountBalanceResponse[].class, KEY, SECRET))
                .thenReturn(new GetAccountBalanceResponse[]{balance("EUR", 1000.0)});
        when(bitvavoApiClient.get("/markets?market=BTC-EUR", GetMarketResponse.class, KEY, SECRET))
                .thenReturn(market(5.0, 2, 8));
        UUID orderId = UUID.randomUUID();
        CreateOrderResponse response = new CreateOrderResponse();
        response.setOrderId(orderId);
        response.setStatus("partiallyFilled");
        response.setFilledAmount(new BigDecimal("0.0005"));
        response.setFilledAmountQuote(new BigDecimal("50.00"));
        when(bitvavoApiClient.post(eq("/order"), any(CreateOrderRequest.class),
                eq(CreateOrderResponse.class), eq(KEY), eq(SECRET))).thenReturn(response);

        List<LiveOrder> saves = recordSaves();
        liveTradingService.fill(strategy, decision("BUY"), 62.0);

        assertEquals(2, saves.size());
        assertEquals("PENDING", saves.get(1).getStatus());
        assertTrue(saves.get(1).isAwaitingReconciliation());
        assertEquals(orderId.toString(), saves.get(1).getOrderId());
        assertEquals(saves.get(0).getClientOrderId(), saves.get(1).getClientOrderId());
        verify(livePositionRepository, never()).save(any());
        verify(alerts).realPending(eq(strategy), any(LiveOrder.class));
    }

    @Test
    void fill_liveBuyWithATerminalResponseRecordsFailedAndOpensNoPosition() {
        LiveStrategy strategy = strategy(false, 0, RuleCondition.SAR_BEARISH_CROSSOVER);
        when(credentialsService.get()).thenReturn(Optional.of(stored()));
        when(bitvavoApiClient.get("/balance?symbol=EUR", GetAccountBalanceResponse[].class, KEY, SECRET))
                .thenReturn(new GetAccountBalanceResponse[]{balance("EUR", 1000.0)});
        when(bitvavoApiClient.get("/markets?market=BTC-EUR", GetMarketResponse.class, KEY, SECRET))
                .thenReturn(market(5.0, 2, 8));
        UUID orderId = UUID.randomUUID();
        CreateOrderResponse response = new CreateOrderResponse();
        response.setOrderId(orderId);
        response.setStatus("canceledMarketProtection");
        response.setFilledAmountQuote(new BigDecimal("50.00"));
        when(bitvavoApiClient.post(eq("/order"), any(CreateOrderRequest.class),
                eq(CreateOrderResponse.class), eq(KEY), eq(SECRET))).thenReturn(response);

        List<LiveOrder> saves = recordSaves();
        liveTradingService.fill(strategy, decision("BUY"), 62.0);

        assertEquals(2, saves.size());
        assertEquals("FAILED", saves.get(1).getStatus());
        assertFalse(saves.get(1).isAwaitingReconciliation());
        assertEquals(orderId.toString(), saves.get(1).getOrderId());
        assertEquals(saves.get(0).getClientOrderId(), saves.get(1).getClientOrderId());
        assertTrue(saves.get(1).getErrorMessage().contains("canceledMarketProtection"));
        assertTrue(saves.get(1).getErrorMessage().contains("partial fill"));
        verify(livePositionRepository, never()).save(any());
    }

    @Test
    void fill_liveBuyWithAnUnknownStatusLeavesTheRowPending() {
        LiveStrategy strategy = strategy(false, 0, RuleCondition.SAR_BEARISH_CROSSOVER);
        when(credentialsService.get()).thenReturn(Optional.of(stored()));
        when(bitvavoApiClient.get("/balance?symbol=EUR", GetAccountBalanceResponse[].class, KEY, SECRET))
                .thenReturn(new GetAccountBalanceResponse[]{balance("EUR", 1000.0)});
        when(bitvavoApiClient.get("/markets?market=BTC-EUR", GetMarketResponse.class, KEY, SECRET))
                .thenReturn(market(5.0, 2, 8));
        UUID orderId = UUID.randomUUID();
        CreateOrderResponse response = new CreateOrderResponse();
        response.setOrderId(orderId);
        response.setStatus("someFutureStatus");
        when(bitvavoApiClient.post(eq("/order"), any(CreateOrderRequest.class),
                eq(CreateOrderResponse.class), eq(KEY), eq(SECRET))).thenReturn(response);

        List<LiveOrder> saves = recordSaves();
        liveTradingService.fill(strategy, decision("BUY"), 62.0);

        assertEquals("PENDING", saves.get(1).getStatus());
        assertTrue(saves.get(1).isAwaitingReconciliation());
        assertEquals(saves.get(0).getClientOrderId(), saves.get(1).getClientOrderId());
        verify(livePositionRepository, never()).save(any());
    }

    @Test
    void fill_liveBuyBelowTheMarketMinimum_recordsARejection() {
        LiveStrategy strategy = strategy(false, 0, RuleCondition.SAR_BEARISH_CROSSOVER);
        when(credentialsService.get()).thenReturn(Optional.of(stored()));
        when(bitvavoApiClient.get("/balance?symbol=EUR", GetAccountBalanceResponse[].class, KEY, SECRET))
                .thenReturn(new GetAccountBalanceResponse[]{balance("EUR", 1000.0)});
        when(bitvavoApiClient.get("/markets?market=BTC-EUR", GetMarketResponse.class, KEY, SECRET))
                .thenReturn(market(1000.0, 2, 8));

        liveTradingService.fill(strategy, decision("BUY"), 62.0);

        LiveOrder order = captureSingleOrder();
        assertEquals("REJECTED", order.getStatus());
        assertTrue(order.getErrorMessage().contains("Below the market minimum"));
        verify(bitvavoApiClient, never()).post(any(), any(), any(), any(), any());
        verify(livePositionRepository, never()).save(any());
    }

    @Test
    void fill_liveBuyWhenTheBalanceCallFails_recordsARejection() {
        LiveStrategy strategy = strategy(false, 0, RuleCondition.SAR_BEARISH_CROSSOVER);
        when(credentialsService.get()).thenReturn(Optional.of(stored()));
        when(bitvavoApiClient.get("/balance?symbol=EUR", GetAccountBalanceResponse[].class, KEY, SECRET))
                .thenThrow(new ResourceAccessException("connect timed out"));

        liveTradingService.fill(strategy, decision("BUY"), 62.0);

        LiveOrder order = captureSingleOrder();
        assertEquals("REJECTED", order.getStatus());
        assertNull(order.getClientOrderId());
        assertEquals("connect timed out", order.getErrorMessage());
        verify(livePositionRepository, never()).save(any());
    }

    @Test
    void fill_liveBuyDerivesTheClientOrderIdFromTheSequenceAndAdvancesIt() {
        LiveStrategy strategy = strategy(false, 0, RuleCondition.SAR_BEARISH_CROSSOVER);
        when(credentialsService.get()).thenReturn(Optional.of(stored()));
        when(bitvavoApiClient.get("/balance?symbol=EUR", GetAccountBalanceResponse[].class, KEY, SECRET))
                .thenReturn(new GetAccountBalanceResponse[]{balance("EUR", 1000.0)});
        when(bitvavoApiClient.get("/markets?market=BTC-EUR", GetMarketResponse.class, KEY, SECRET))
                .thenReturn(market(5.0, 2, 8));
        CreateOrderResponse response = new CreateOrderResponse();
        response.setOrderId(UUID.randomUUID());
        response.setStatus("filled");
        response.setFilledAmount(new BigDecimal("0.001"));
        response.setFilledAmountQuote(new BigDecimal("100.00"));
        response.setFeePaid(new BigDecimal("0.2"));
        when(bitvavoApiClient.post(eq("/order"), any(CreateOrderRequest.class),
                eq(CreateOrderResponse.class), eq(KEY), eq(SECRET))).thenReturn(response);

        liveTradingService.fill(strategy, decision("BUY"), 62.0);

        ArgumentCaptor<CreateOrderRequest> requestCaptor = ArgumentCaptor.forClass(CreateOrderRequest.class);
        verify(bitvavoApiClient).post(eq("/order"), requestCaptor.capture(),
                eq(CreateOrderResponse.class), eq(KEY), eq(SECRET));
        assertEquals(ClientOrderIds.forSequence(STRATEGY_ID, 0).toString(),
                requestCaptor.getValue().getClientOrderId().toString());
        assertEquals(1L, strategy.getNextOrderSequence());
        verify(liveStrategyRepository).updateNextOrderSequence(STRATEGY_ID, 1);
    }

    @Test
    void fill_liveBuyAdvancesTheSequenceBeforeTheRowIsWritten() {
        LiveStrategy strategy = strategy(false, 0, RuleCondition.SAR_BEARISH_CROSSOVER);
        when(credentialsService.get()).thenReturn(Optional.of(stored()));
        when(bitvavoApiClient.get("/balance?symbol=EUR", GetAccountBalanceResponse[].class, KEY, SECRET))
                .thenReturn(new GetAccountBalanceResponse[]{balance("EUR", 1000.0)});
        when(bitvavoApiClient.get("/markets?market=BTC-EUR", GetMarketResponse.class, KEY, SECRET))
                .thenReturn(market(5.0, 2, 8));
        CreateOrderResponse response = new CreateOrderResponse();
        response.setOrderId(UUID.randomUUID());
        response.setStatus("filled");
        response.setFilledAmount(new BigDecimal("0.001"));
        response.setFilledAmountQuote(new BigDecimal("100.00"));
        response.setFeePaid(new BigDecimal("0.2"));
        when(bitvavoApiClient.post(eq("/order"), any(CreateOrderRequest.class),
                eq(CreateOrderResponse.class), eq(KEY), eq(SECRET))).thenReturn(response);

        liveTradingService.fill(strategy, decision("BUY"), 62.0);

        InOrder inOrder = inOrder(liveStrategyRepository, liveOrderRepository);
        inOrder.verify(liveStrategyRepository).updateNextOrderSequence(STRATEGY_ID, 1);
        inOrder.verify(liveOrderRepository, times(2)).save(any(LiveOrder.class));
    }

    @Test
    void fill_liveSellPlacesAMarketOrderAndRealisesTheProfit() {
        LiveStrategy strategy = strategy(false, 0, RuleCondition.PRICE_ABOVE_SAR);
        LivePosition position = openPosition(0.001, 100000.0, 0.2);
        when(livePositionRepository.findByStrategyIdAndStatus(STRATEGY_ID, "OPEN"))
                .thenReturn(Optional.of(position));
        when(credentialsService.get()).thenReturn(Optional.of(stored()));
        when(bitvavoApiClient.get("/markets?market=BTC-EUR", GetMarketResponse.class, KEY, SECRET))
                .thenReturn(market(5.0, 2, 8));
        UUID orderId = UUID.randomUUID();
        CreateOrderResponse response = new CreateOrderResponse();
        response.setOrderId(orderId);
        response.setStatus("filled");
        response.setFilledAmountQuote(new BigDecimal("110.00"));
        response.setFeePaid(new BigDecimal("0.22"));
        when(bitvavoApiClient.post(eq("/order"), any(CreateOrderRequest.class),
                eq(CreateOrderResponse.class), eq(KEY), eq(SECRET))).thenReturn(response);

        List<LiveOrder> saves = recordSaves();
        LiveDecision decision = decision("SELL");
        liveTradingService.fill(strategy, decision, 160.0);

        ArgumentCaptor<CreateOrderRequest> requestCaptor = ArgumentCaptor.forClass(CreateOrderRequest.class);
        verify(bitvavoApiClient).post(eq("/order"), requestCaptor.capture(),
                eq(CreateOrderResponse.class), eq(KEY), eq(SECRET));
        CreateOrderRequest request = requestCaptor.getValue();
        assertEquals("sell", request.getSide());
        assertEquals(0, new BigDecimal("0.001").compareTo(request.getAmount()));

        assertEquals(2, saves.size());
        LiveOrder pending = saves.get(0);
        LiveOrder order = saves.get(1);
        assertEquals("PENDING", pending.getStatus());
        assertTrue(pending.isAwaitingReconciliation());
        assertEquals("SELL", pending.getSide());
        assertEquals(0.001, pending.getQuantity(), 1e-12);
        assertEquals(160.0, pending.getPrice(), 1e-9);
        assertEquals(pending.getClientOrderId(), request.getClientOrderId().toString());
        assertEquals("SELL", order.getSide());
        assertEquals("FILLED", order.getStatus());
        assertFalse(order.isAwaitingReconciliation());
        assertEquals(110.0, order.getAmountQuote(), 1e-9);
        assertEquals(110000.0, order.getPrice(), 1e-9);
        assertEquals(pending.getClientOrderId(), order.getClientOrderId());
        assertEquals(decision.signalOpenTime(), order.getSignalOpenTime());

        ArgumentCaptor<LivePosition> positionCaptor = ArgumentCaptor.forClass(LivePosition.class);
        verify(livePositionRepository).save(positionCaptor.capture());
        LivePosition closed = positionCaptor.getValue();
        assertEquals("CLOSED", closed.getStatus());
        assertEquals(110000.0, closed.getExitPrice(), 1e-9);
        assertEquals(110.0 - 100.0 - 0.22 - 0.2, closed.getProfit(), 1e-9);
        assertEquals(decision.signalOpenTime(), closed.getExitTime());
        verify(alerts).realFill(eq(strategy), any(LiveOrder.class), eq(closed));
    }

    @Test
    void fill_liveSellWithATerminalResponseRecordsFailedAndKeepsThePositionOpen() {
        LiveStrategy strategy = strategy(false, 0, RuleCondition.PRICE_ABOVE_SAR);
        LivePosition position = openPosition(0.001, 100000.0, 0.2);
        when(livePositionRepository.findByStrategyIdAndStatus(STRATEGY_ID, "OPEN"))
                .thenReturn(Optional.of(position));
        when(credentialsService.get()).thenReturn(Optional.of(stored()));
        when(bitvavoApiClient.get("/markets?market=BTC-EUR", GetMarketResponse.class, KEY, SECRET))
                .thenReturn(market(5.0, 2, 8));
        UUID orderId = UUID.randomUUID();
        CreateOrderResponse response = new CreateOrderResponse();
        response.setOrderId(orderId);
        response.setStatus("canceledMarketProtection");
        when(bitvavoApiClient.post(eq("/order"), any(CreateOrderRequest.class),
                eq(CreateOrderResponse.class), eq(KEY), eq(SECRET))).thenReturn(response);

        List<LiveOrder> saves = recordSaves();
        liveTradingService.fill(strategy, decision("SELL"), 160.0);

        assertEquals(2, saves.size());
        assertEquals("FAILED", saves.get(1).getStatus());
        assertFalse(saves.get(1).isAwaitingReconciliation());
        assertEquals(orderId.toString(), saves.get(1).getOrderId());
        assertEquals(saves.get(0).getClientOrderId(), saves.get(1).getClientOrderId());
        assertTrue(saves.get(1).getErrorMessage().contains("canceledMarketProtection"));
        verify(livePositionRepository, never()).save(any());
    }

    @Test
    void fill_liveSellWithAPartiallyFilledResponseLeavesTheRowPendingAndKeepsThePositionOpen() {
        LiveStrategy strategy = strategy(false, 0, RuleCondition.PRICE_ABOVE_SAR);
        LivePosition position = openPosition(0.001, 100000.0, 0.2);
        when(livePositionRepository.findByStrategyIdAndStatus(STRATEGY_ID, "OPEN"))
                .thenReturn(Optional.of(position));
        when(credentialsService.get()).thenReturn(Optional.of(stored()));
        when(bitvavoApiClient.get("/markets?market=BTC-EUR", GetMarketResponse.class, KEY, SECRET))
                .thenReturn(market(5.0, 2, 8));
        UUID orderId = UUID.randomUUID();
        CreateOrderResponse response = new CreateOrderResponse();
        response.setOrderId(orderId);
        response.setStatus("partiallyFilled");
        response.setFilledAmountQuote(new BigDecimal("50.00"));
        when(bitvavoApiClient.post(eq("/order"), any(CreateOrderRequest.class),
                eq(CreateOrderResponse.class), eq(KEY), eq(SECRET))).thenReturn(response);

        List<LiveOrder> saves = recordSaves();
        liveTradingService.fill(strategy, decision("SELL"), 160.0);

        assertEquals(2, saves.size());
        assertEquals("PENDING", saves.get(1).getStatus());
        assertTrue(saves.get(1).isAwaitingReconciliation());
        assertEquals(orderId.toString(), saves.get(1).getOrderId());
        assertEquals(saves.get(0).getClientOrderId(), saves.get(1).getClientOrderId());
        verify(livePositionRepository, never()).save(any());
    }

    @Test
    void fill_liveSellWhenTheQuantityRoundsToZero_recordsARejection() {
        LiveStrategy strategy = strategy(false, 0, RuleCondition.PRICE_ABOVE_SAR);
        when(livePositionRepository.findByStrategyIdAndStatus(STRATEGY_ID, "OPEN"))
                .thenReturn(Optional.of(openPosition(0.000_000_001, 100000.0, 0.2)));
        when(credentialsService.get()).thenReturn(Optional.of(stored()));
        when(bitvavoApiClient.get("/markets?market=BTC-EUR", GetMarketResponse.class, KEY, SECRET))
                .thenReturn(market(5.0, 2, 4));

        liveTradingService.fill(strategy, decision("SELL"), 160.0);

        LiveOrder order = captureSingleOrder();
        assertEquals("REJECTED", order.getStatus());
        assertNull(order.getClientOrderId());
        assertTrue(order.getErrorMessage().contains("rounds to zero"));
        verify(bitvavoApiClient, never()).post(any(), any(), any(), any(), any());
        verify(livePositionRepository, never()).save(any());
    }

    @Test
    void fill_liveBuyWhenThePostFails_marksThePendingRowFailed() {
        LiveStrategy strategy = strategy(false, 0, RuleCondition.SAR_BEARISH_CROSSOVER);
        when(credentialsService.get()).thenReturn(Optional.of(stored()));
        when(bitvavoApiClient.get("/balance?symbol=EUR", GetAccountBalanceResponse[].class, KEY, SECRET))
                .thenReturn(new GetAccountBalanceResponse[]{balance("EUR", 1000.0)});
        when(bitvavoApiClient.get("/markets?market=BTC-EUR", GetMarketResponse.class, KEY, SECRET))
                .thenReturn(market(5.0, 2, 8));
        when(bitvavoApiClient.post(eq("/order"), any(CreateOrderRequest.class),
                eq(CreateOrderResponse.class), eq(KEY), eq(SECRET)))
                .thenThrow(new ResourceAccessException("read timed out"));

        List<LiveOrder> saves = recordSaves();
        liveTradingService.fill(strategy, decision("BUY"), 62.0);

        assertEquals(2, saves.size());
        LiveOrder pending = saves.get(0);
        LiveOrder failed = saves.get(1);
        assertEquals("PENDING", pending.getStatus());
        assertTrue(pending.isAwaitingReconciliation());
        assertNotNull(pending.getClientOrderId());
        assertEquals("FAILED", failed.getStatus());
        assertTrue(failed.isAwaitingReconciliation());
        assertEquals("read timed out", failed.getErrorMessage());
        assertEquals(pending.getClientOrderId(), failed.getClientOrderId());
        verify(livePositionRepository, never()).save(any());
        verify(alerts).realFailure(eq(strategy), any(LiveOrder.class), eq("failed"));
    }

    @Test
    void fill_liveSellWhenThePostFails_marksThePendingRowFailed() {
        LiveStrategy strategy = strategy(false, 0, RuleCondition.PRICE_ABOVE_SAR);
        LivePosition position = openPosition(0.001, 100000.0, 0.2);
        when(livePositionRepository.findByStrategyIdAndStatus(STRATEGY_ID, "OPEN"))
                .thenReturn(Optional.of(position));
        when(credentialsService.get()).thenReturn(Optional.of(stored()));
        when(bitvavoApiClient.get("/markets?market=BTC-EUR", GetMarketResponse.class, KEY, SECRET))
                .thenReturn(market(5.0, 2, 8));
        when(bitvavoApiClient.post(eq("/order"), any(CreateOrderRequest.class),
                eq(CreateOrderResponse.class), eq(KEY), eq(SECRET)))
                .thenThrow(new ResourceAccessException("read timed out"));

        List<LiveOrder> saves = recordSaves();
        liveTradingService.fill(strategy, decision("SELL"), 160.0);

        assertEquals(2, saves.size());
        LiveOrder pending = saves.get(0);
        LiveOrder failed = saves.get(1);
        assertEquals("PENDING", pending.getStatus());
        assertTrue(pending.isAwaitingReconciliation());
        assertEquals("SELL", pending.getSide());
        assertEquals("FAILED", failed.getStatus());
        assertTrue(failed.isAwaitingReconciliation());
        assertEquals("read timed out", failed.getErrorMessage());
        assertEquals(pending.getClientOrderId(), failed.getClientOrderId());
        assertEquals("OPEN", position.getStatus());
        verify(livePositionRepository, never()).save(any());
    }

    @Test
    void fill_liveBuyKeepsTheRowFilledWhenSavingThePositionFails() {
        LiveStrategy strategy = strategy(false, 0, RuleCondition.SAR_BEARISH_CROSSOVER);
        when(credentialsService.get()).thenReturn(Optional.of(stored()));
        when(bitvavoApiClient.get("/balance?symbol=EUR", GetAccountBalanceResponse[].class, KEY, SECRET))
                .thenReturn(new GetAccountBalanceResponse[]{balance("EUR", 1000.0)});
        when(bitvavoApiClient.get("/markets?market=BTC-EUR", GetMarketResponse.class, KEY, SECRET))
                .thenReturn(market(5.0, 2, 8));
        UUID orderId = UUID.randomUUID();
        CreateOrderResponse response = new CreateOrderResponse();
        response.setOrderId(orderId);
        response.setStatus("filled");
        response.setFilledAmount(new BigDecimal("0.001"));
        response.setFilledAmountQuote(new BigDecimal("100.00"));
        response.setFeePaid(new BigDecimal("0.2"));
        when(bitvavoApiClient.post(eq("/order"), any(CreateOrderRequest.class),
                eq(CreateOrderResponse.class), eq(KEY), eq(SECRET))).thenReturn(response);
        when(livePositionRepository.save(any(LivePosition.class)))
                .thenThrow(new ResourceAccessException("database down"));

        List<LiveOrder> saves = recordSaves();
        liveTradingService.fill(strategy, decision("BUY"), 62.0);

        assertEquals(2, saves.size());
        assertEquals("PENDING", saves.get(0).getStatus());
        assertEquals("FILLED", saves.get(1).getStatus());
        assertFalse(saves.get(1).isAwaitingReconciliation());
        assertEquals(orderId.toString(), saves.get(1).getOrderId());
        assertNull(saves.get(1).getErrorMessage());
    }

    @Test
    void fill_liveSellKeepsTheRowFilledWhenClosingThePositionFails() {
        LiveStrategy strategy = strategy(false, 0, RuleCondition.PRICE_ABOVE_SAR);
        when(livePositionRepository.findByStrategyIdAndStatus(STRATEGY_ID, "OPEN"))
                .thenReturn(Optional.of(openPosition(0.001, 100000.0, 0.2)));
        when(credentialsService.get()).thenReturn(Optional.of(stored()));
        when(bitvavoApiClient.get("/markets?market=BTC-EUR", GetMarketResponse.class, KEY, SECRET))
                .thenReturn(market(5.0, 2, 8));
        UUID orderId = UUID.randomUUID();
        CreateOrderResponse response = new CreateOrderResponse();
        response.setOrderId(orderId);
        response.setStatus("filled");
        response.setFilledAmountQuote(new BigDecimal("110.00"));
        response.setFeePaid(new BigDecimal("0.22"));
        when(bitvavoApiClient.post(eq("/order"), any(CreateOrderRequest.class),
                eq(CreateOrderResponse.class), eq(KEY), eq(SECRET))).thenReturn(response);
        when(livePositionRepository.save(any(LivePosition.class)))
                .thenThrow(new ResourceAccessException("database down"));

        List<LiveOrder> saves = recordSaves();
        liveTradingService.fill(strategy, decision("SELL"), 160.0);

        assertEquals(2, saves.size());
        assertEquals("PENDING", saves.get(0).getStatus());
        assertEquals("SELL", saves.get(1).getSide());
        assertEquals("FILLED", saves.get(1).getStatus());
        assertFalse(saves.get(1).isAwaitingReconciliation());
        assertEquals(orderId.toString(), saves.get(1).getOrderId());
        assertNull(saves.get(1).getErrorMessage());
    }

    @Test
    void fill_doesNothingWhenThePositionVanishedBeforeTheExitFill() {
        LiveStrategy strategy = strategy(false, 0, RuleCondition.PRICE_ABOVE_SAR);
        when(livePositionRepository.findByStrategyIdAndStatus(STRATEGY_ID, "OPEN"))
                .thenReturn(Optional.empty());

        liveTradingService.fill(strategy, decision("SELL"), 160.0);

        verify(liveOrderRepository, never()).save(any());
        verifyNoInteractions(bitvavoApiClient, credentialsService);
    }

    @Test
    void fill_liveSellWithoutAnApiKey_recordsARejection() {
        LiveStrategy strategy = strategy(false, 0, RuleCondition.PRICE_ABOVE_SAR);
        when(livePositionRepository.findByStrategyIdAndStatus(STRATEGY_ID, "OPEN"))
                .thenReturn(Optional.of(openPosition(0.001, 100000.0, 0.2)));
        when(credentialsService.get()).thenReturn(Optional.empty());

        liveTradingService.fill(strategy, decision("SELL"), 160.0);

        LiveOrder order = captureSingleOrder();
        assertEquals("SELL", order.getSide());
        assertEquals("REJECTED", order.getStatus());
        assertTrue(order.getErrorMessage().contains("No Bitvavo API key is configured"));
        verify(livePositionRepository, never()).save(any());
        verify(alerts).realFailure(eq(strategy), any(LiveOrder.class), eq("rejected"));
    }

    @Test
    void fill_liveSellWhenTheMarketCallFails_recordsARejection() {
        LiveStrategy strategy = strategy(false, 0, RuleCondition.PRICE_ABOVE_SAR);
        when(livePositionRepository.findByStrategyIdAndStatus(STRATEGY_ID, "OPEN"))
                .thenReturn(Optional.of(openPosition(0.001, 100000.0, 0.2)));
        when(credentialsService.get()).thenReturn(Optional.of(stored()));
        when(bitvavoApiClient.get("/markets?market=BTC-EUR", GetMarketResponse.class, KEY, SECRET))
                .thenThrow(new ResourceAccessException("market data down"));

        liveTradingService.fill(strategy, decision("SELL"), 160.0);

        LiveOrder order = captureSingleOrder();
        assertEquals("SELL", order.getSide());
        assertEquals("REJECTED", order.getStatus());
        assertEquals("market data down", order.getErrorMessage());
        verify(livePositionRepository, never()).save(any());
        verify(alerts).realFailure(eq(strategy), any(LiveOrder.class), eq("rejected"));
    }

    @Test
    void closeManually_withoutAnOpenPositionIsRejected() {
        LiveStrategy strategy = strategy(false, 0, RuleCondition.PRICE_ABOVE_SAR);
        when(livePositionRepository.findByStrategyIdAndStatus(STRATEGY_ID, "OPEN"))
                .thenReturn(Optional.empty());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> liveTradingService.closeManually(strategy, START + HOUR,
                        "manual close in the app", 160.0));

        assertEquals("there is no open position to close", error.getMessage());
        verifyNoInteractions(alerts, bitvavoApiClient, credentialsService);
    }

    @Test
    void closeManually_dryRunClosesAtTheBidAndRecordsTheReason() {
        LiveStrategy strategy = strategy(true, 0, RuleCondition.PRICE_ABOVE_SAR);
        LivePosition position = openPosition(0.5, 200.0, 0.1);
        when(livePositionRepository.findByStrategyIdAndStatus(STRATEGY_ID, "OPEN"))
                .thenReturn(Optional.of(position));
        long exit = START + HOUR;

        LiveOrder order = liveTradingService.closeManually(strategy, exit,
                "manual close in the app", 160.0);

        assertEquals("SELL", order.getSide());
        assertEquals("DRY_RUN", order.getStatus());
        assertEquals("manual close in the app", order.getReason());
        assertEquals(exit, order.getSignalOpenTime());
        assertEquals("CLOSED", position.getStatus());
        assertEquals(160.0, position.getExitPrice(), 1e-9);
        assertEquals(exit, position.getExitTime());
        verifyNoInteractions(alerts, bitvavoApiClient, credentialsService);
    }

    @Test
    void closeManually_realFillUsesTheExchangePriceAndAlerts() {
        LiveStrategy strategy = strategy(false, 0, RuleCondition.PRICE_ABOVE_SAR);
        LivePosition position = openPosition(0.001, 100000.0, 0.2);
        when(livePositionRepository.findByStrategyIdAndStatus(STRATEGY_ID, "OPEN"))
                .thenReturn(Optional.of(position));
        when(credentialsService.get()).thenReturn(Optional.of(stored()));
        when(bitvavoApiClient.get("/markets?market=BTC-EUR", GetMarketResponse.class, KEY, SECRET))
                .thenReturn(market(5.0, 2, 8));
        UUID orderId = UUID.randomUUID();
        CreateOrderResponse response = new CreateOrderResponse();
        response.setOrderId(orderId);
        response.setStatus("filled");
        response.setFilledAmount(new BigDecimal("0.001"));
        response.setFilledAmountQuote(new BigDecimal("110.00"));
        response.setFeePaid(new BigDecimal("0.22"));
        when(bitvavoApiClient.post(eq("/order"), any(CreateOrderRequest.class),
                eq(CreateOrderResponse.class), eq(KEY), eq(SECRET))).thenReturn(response);

        LiveOrder order = liveTradingService.closeManually(strategy, START + HOUR,
                "manual close in the app", 160.0);

        assertEquals("FILLED", order.getStatus());
        assertEquals(110000.0, order.getPrice(), 1e-9);
        assertEquals("CLOSED", position.getStatus());
        assertEquals(110000.0, position.getExitPrice(), 1e-9);
        verify(alerts).realFill(eq(strategy), eq(order), eq(position));
    }

    @Test
    void closeManually_realPendingLeavesThePositionOpenAndAlerts() {
        LiveStrategy strategy = strategy(false, 0, RuleCondition.PRICE_ABOVE_SAR);
        LivePosition position = openPosition(0.001, 100000.0, 0.2);
        when(livePositionRepository.findByStrategyIdAndStatus(STRATEGY_ID, "OPEN"))
                .thenReturn(Optional.of(position));
        when(credentialsService.get()).thenReturn(Optional.of(stored()));
        when(bitvavoApiClient.get("/markets?market=BTC-EUR", GetMarketResponse.class, KEY, SECRET))
                .thenReturn(market(5.0, 2, 8));
        CreateOrderResponse response = new CreateOrderResponse();
        response.setOrderId(UUID.randomUUID());
        response.setStatus("partiallyFilled");
        response.setFilledAmountQuote(new BigDecimal("50.00"));
        when(bitvavoApiClient.post(eq("/order"), any(CreateOrderRequest.class),
                eq(CreateOrderResponse.class), eq(KEY), eq(SECRET))).thenReturn(response);

        LiveOrder order = liveTradingService.closeManually(strategy, START + HOUR,
                "manual close in the app", 160.0);

        assertEquals("PENDING", order.getStatus());
        assertEquals("OPEN", position.getStatus());
        verify(alerts).realPending(eq(strategy), eq(order));
        verify(alerts, never()).realFill(any(), any(), any());
    }

    private static LiveDecision decision(String side) {
        return new LiveDecision(side, side.toLowerCase() + " ALL [TEST]", START);
    }

    private List<LiveOrder> recordSaves() {
        List<LiveOrder> saves = new ArrayList<>();
        when(liveOrderRepository.save(any(LiveOrder.class))).thenAnswer(invocation -> {
            saves.add(copy(invocation.getArgument(0)));
            return invocation.getArgument(0);
        });
        return saves;
    }

    private static LiveOrder copy(LiveOrder order) {
        return LiveOrder.builder()
                .strategyId(order.getStrategyId())
                .market(order.getMarket())
                .side(order.getSide())
                .status(order.getStatus())
                .dryRun(order.isDryRun())
                .signalOpenTime(order.getSignalOpenTime())
                .reason(order.getReason())
                .amountQuote(order.getAmountQuote())
                .quantity(order.getQuantity())
                .price(order.getPrice())
                .fee(order.getFee())
                .orderId(order.getOrderId())
                .clientOrderId(order.getClientOrderId())
                .awaitingReconciliation(order.isAwaitingReconciliation())
                .errorMessage(order.getErrorMessage())
                .createdTime(order.getCreatedTime())
                .build();
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

    private static BitvavoCredentials stored() {
        return BitvavoCredentials.builder()
                .id(BitvavoCredentials.ID)
                .operatorId(1L)
                .apiKey(KEY)
                .apiSecret(SECRET)
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
