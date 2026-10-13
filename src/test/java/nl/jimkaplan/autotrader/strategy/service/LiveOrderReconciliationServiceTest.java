package nl.jimkaplan.autotrader.strategy.service;

import nl.jimkaplan.autotrader.bitvavo.client.BitvavoApiClient;
import nl.jimkaplan.autotrader.bitvavo.model.OrderResponse;
import nl.jimkaplan.autotrader.model.document.BotConfiguration;
import nl.jimkaplan.autotrader.repository.LiveOrderRepository;
import nl.jimkaplan.autotrader.repository.LivePositionRepository;
import nl.jimkaplan.autotrader.service.BotConfigurationService;
import nl.jimkaplan.autotrader.strategy.model.document.LiveOrder;
import nl.jimkaplan.autotrader.strategy.model.document.LivePosition;
import nl.jimkaplan.autotrader.strategy.model.document.LiveStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.ResourceAccessException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LiveOrderReconciliationServiceTest {

    private static final String KEY = "test-key";
    private static final String SECRET = "test-secret";
    private static final String BOT_ID = "bot-1";
    private static final String STRATEGY_ID = "strategy-1";
    private static final String MARKET = "BTC-EUR";
    private static final long START = 1_783_069_200_000L;
    private static final long MINUTE = 60_000L;
    private static final long HOUR = 3_600_000L;

    @Mock
    private LiveStrategyService liveStrategyService;

    @Mock
    private BotConfigurationService botConfigurationService;

    @Mock
    private BitvavoApiClient bitvavoApiClient;

    @Mock
    private LiveOrderRepository liveOrderRepository;

    @Mock
    private LivePositionRepository livePositionRepository;

    @InjectMocks
    private LiveOrderReconciliationService service;

    @Test
    void dryRunStrategiesAreSkipped() {
        when(liveStrategyService.all()).thenReturn(List.of(strategy(true, MARKET)));

        assertTrue(service.reconcileAll().isEmpty());

        verifyNoInteractions(botConfigurationService, bitvavoApiClient, liveOrderRepository, livePositionRepository);
    }

    @Test
    void strategiesWithoutABotIdAreSkipped() {
        LiveStrategy strategy = strategy(false, MARKET);
        strategy.setBotId(null);
        when(liveStrategyService.all()).thenReturn(List.of(strategy));

        assertTrue(service.reconcileAll().isEmpty());

        verifyNoInteractions(botConfigurationService, bitvavoApiClient, liveOrderRepository, livePositionRepository);
    }

    @Test
    void strategiesWithAnUnknownBotAreSkipped() {
        when(liveStrategyService.all()).thenReturn(List.of(strategy(false, MARKET)));
        when(botConfigurationService.getBotConfiguration(BOT_ID)).thenReturn(Optional.empty());

        assertTrue(service.reconcileAll().isEmpty());

        verifyNoInteractions(bitvavoApiClient, liveOrderRepository, livePositionRepository);
    }

    @Test
    void aPendingRowIsResolvedToFilledFromTheHistory() {
        stubStrategyAndBot();
        UUID clientOrderId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        LiveOrder pending = pendingRow("BUY", clientOrderId, Instant.ofEpochMilli(START + MINUTE));
        when(liveOrderRepository.findByStrategyIdOrderByCreatedTimeDesc(STRATEGY_ID)).thenReturn(List.of(pending));
        when(bitvavoApiClient.get(ordersEndpoint(MARKET), OrderResponse[].class, KEY, SECRET))
                .thenReturn(new OrderResponse[]{order(orderId, "buy", "filled", START, clientOrderId,
                        "0.002", "110.00", "0.22")});
        stubOpenPosition(0.001, 100000.0, 0.2);

        assertTrue(service.reconcileAll().isEmpty());

        verify(bitvavoApiClient).get(ordersEndpoint(MARKET), OrderResponse[].class, KEY, SECRET);
        assertEquals("FILLED", pending.getStatus());
        assertEquals(orderId.toString(), pending.getOrderId());
        assertEquals(110.0, pending.getAmountQuote(), 1e-9);
        assertEquals(0.002, pending.getQuantity(), 1e-12);
        assertEquals(55000.0, pending.getPrice(), 1e-9);
        assertEquals(0.22, pending.getFee(), 1e-9);
        assertNull(pending.getErrorMessage());
        verify(liveOrderRepository).save(pending);
        verify(livePositionRepository, never()).save(any());
    }

    @Test
    void aPendingRowTheExchangeDoesNotKnowBecomesFailed() {
        stubStrategyAndBot();
        LiveOrder pending = pendingRow("BUY", UUID.randomUUID(), Instant.ofEpochMilli(START + MINUTE));
        when(liveOrderRepository.findByStrategyIdOrderByCreatedTimeDesc(STRATEGY_ID)).thenReturn(List.of(pending));
        when(bitvavoApiClient.get(ordersEndpoint(MARKET), OrderResponse[].class, KEY, SECRET))
                .thenReturn(new OrderResponse[0]);

        assertTrue(service.reconcileAll().isEmpty());

        assertEquals("FAILED", pending.getStatus());
        assertEquals(LiveOrderReconciliationService.NOT_ACCEPTED_MESSAGE, pending.getErrorMessage());
        assertNotNull(pending.getClientOrderId());
        verify(livePositionRepository, never()).save(any());
    }

    @Test
    void aPendingRowFoundButNotFilledIsLeftPending() {
        stubStrategyAndBot();
        UUID clientOrderId = UUID.randomUUID();
        LiveOrder pending = pendingRow("BUY", clientOrderId, Instant.ofEpochMilli(START + MINUTE));
        when(liveOrderRepository.findByStrategyIdOrderByCreatedTimeDesc(STRATEGY_ID)).thenReturn(List.of(pending));
        when(bitvavoApiClient.get(ordersEndpoint(MARKET), OrderResponse[].class, KEY, SECRET))
                .thenReturn(new OrderResponse[]{order(UUID.randomUUID(), "buy", "open", START, clientOrderId,
                        null, null, null)});

        assertTrue(service.reconcileAll().isEmpty());

        assertEquals("PENDING", pending.getStatus());
        verify(liveOrderRepository, never()).save(any());
    }

    @Test
    void aFailedRowWithItsClientOrderIdIsCorrected() {
        stubStrategyAndBot();
        UUID clientOrderId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        LiveOrder failed = failedRow("BUY", clientOrderId, Instant.ofEpochMilli(START), "entry ALL [TEST]");
        when(liveOrderRepository.findByStrategyIdOrderByCreatedTimeDesc(STRATEGY_ID)).thenReturn(List.of(failed));
        when(bitvavoApiClient.get(ordersEndpoint(MARKET), OrderResponse[].class, KEY, SECRET))
                .thenReturn(new OrderResponse[]{order(orderId, "buy", "filled", START, clientOrderId,
                        "0.001", "100.00", "0.2")});
        stubOpenPosition(0.001, 100000.0, 0.2);

        service.reconcileAll();

        assertEquals("FILLED", failed.getStatus());
        assertEquals(orderId.toString(), failed.getOrderId());
        assertEquals(100.0, failed.getAmountQuote(), 1e-9);
        assertEquals(0.001, failed.getQuantity(), 1e-12);
        assertEquals(100000.0, failed.getPrice(), 1e-9);
        assertEquals(0.2, failed.getFee(), 1e-9);
        assertNull(failed.getErrorMessage());
        assertEquals("entry ALL [TEST] " + LiveOrderReconciliationService.RECONCILED_NOTE, failed.getReason());
        verify(liveOrderRepository, times(1)).save(failed);
        verify(livePositionRepository, never()).save(any());
    }

    @Test
    void aCorrectedFailedRowWithoutAReasonGetsTheNote() {
        stubStrategyAndBot();
        UUID clientOrderId = UUID.randomUUID();
        LiveOrder failed = failedRow("BUY", clientOrderId, Instant.ofEpochMilli(START), null);
        when(liveOrderRepository.findByStrategyIdOrderByCreatedTimeDesc(STRATEGY_ID)).thenReturn(List.of(failed));
        when(bitvavoApiClient.get(ordersEndpoint(MARKET), OrderResponse[].class, KEY, SECRET))
                .thenReturn(new OrderResponse[]{order(UUID.randomUUID(), "buy", "filled", START, clientOrderId,
                        "0.001", "100.00", "0.2")});
        stubOpenPosition(0.001, 100000.0, 0.2);

        service.reconcileAll();

        assertEquals(LiveOrderReconciliationService.RECONCILED_NOTE, failed.getReason());
        assertEquals("FILLED", failed.getStatus());
    }

    @Test
    void aFailedRowWithoutAClientOrderIdIsLeftAlone() {
        stubStrategyAndBot();
        LiveOrder failed = failedRow("BUY", null, Instant.ofEpochMilli(START), "entry ALL [TEST]");
        when(liveOrderRepository.findByStrategyIdOrderByCreatedTimeDesc(STRATEGY_ID)).thenReturn(List.of(failed));
        when(bitvavoApiClient.get(ordersEndpoint(MARKET), OrderResponse[].class, KEY, SECRET))
                .thenReturn(new OrderResponse[0]);

        service.reconcileAll();

        assertEquals("FAILED", failed.getStatus());
        assertNull(failed.getClientOrderId());
        verify(liveOrderRepository, never()).save(any());
    }

    @Test
    void aFailedRowWithAnUnknownClientOrderIdIsLeftAlone() {
        stubStrategyAndBot();
        LiveOrder failed = failedRow("BUY", UUID.randomUUID(), Instant.ofEpochMilli(START), "entry ALL [TEST]");
        when(liveOrderRepository.findByStrategyIdOrderByCreatedTimeDesc(STRATEGY_ID)).thenReturn(List.of(failed));
        when(bitvavoApiClient.get(ordersEndpoint(MARKET), OrderResponse[].class, KEY, SECRET))
                .thenReturn(new OrderResponse[0]);

        service.reconcileAll();

        assertEquals("FAILED", failed.getStatus());
        verify(liveOrderRepository, never()).save(any());
    }

    @Test
    void incompleteHistoryEntriesAreSkipped() {
        stubStrategyAndBot();
        UUID clientOrderId = UUID.randomUUID();
        LiveOrder pending = pendingRow("BUY", clientOrderId, Instant.ofEpochMilli(START + MINUTE));
        when(liveOrderRepository.findByStrategyIdOrderByCreatedTimeDesc(STRATEGY_ID)).thenReturn(List.of(pending));
        when(bitvavoApiClient.get(ordersEndpoint(MARKET), OrderResponse[].class, KEY, SECRET))
                .thenReturn(new OrderResponse[]{
                        order(UUID.randomUUID(), "buy", "filled", null, clientOrderId, "0.001", "100.00", "0.2"),
                        order(null, "buy", "filled", START, UUID.randomUUID(), "0.001", "100.00", "0.2"),
                        order(UUID.randomUUID(), null, "filled", START, UUID.randomUUID(), "0.001", "100.00", "0.2"),
                        order(UUID.randomUUID(), "buy", "filled", START, null, "0.001", "100.00", "0.2")});

        assertTrue(service.reconcileAll().isEmpty());

        assertEquals("FAILED", pending.getStatus());
        assertEquals(LiveOrderReconciliationService.NOT_ACCEPTED_MESSAGE, pending.getErrorMessage());
    }

    @Test
    void aNullHistoryMarksAPendingRowAsNeverAccepted() {
        stubStrategyAndBot();
        LiveOrder pending = pendingRow("BUY", UUID.randomUUID(), Instant.ofEpochMilli(START + MINUTE));
        when(liveOrderRepository.findByStrategyIdOrderByCreatedTimeDesc(STRATEGY_ID)).thenReturn(List.of(pending));
        when(bitvavoApiClient.get(ordersEndpoint(MARKET), OrderResponse[].class, KEY, SECRET)).thenReturn(null);

        service.reconcileAll();

        assertEquals("FAILED", pending.getStatus());
        assertEquals(LiveOrderReconciliationService.NOT_ACCEPTED_MESSAGE, pending.getErrorMessage());
    }

    @Test
    void aFilledHistoryEntryWithoutFiguresKeepsThePreWriteEstimates() {
        stubStrategyAndBot();
        UUID clientOrderId = UUID.randomUUID();
        LiveOrder pending = pendingRow("BUY", clientOrderId, Instant.ofEpochMilli(START + MINUTE));
        when(liveOrderRepository.findByStrategyIdOrderByCreatedTimeDesc(STRATEGY_ID)).thenReturn(List.of(pending));
        when(bitvavoApiClient.get(ordersEndpoint(MARKET), OrderResponse[].class, KEY, SECRET))
                .thenReturn(new OrderResponse[]{order(UUID.randomUUID(), "buy", "filled", START, clientOrderId,
                        null, null, null)});
        stubOpenPosition(0.001, 100000.0, 0.2);

        service.reconcileAll();

        assertEquals("FILLED", pending.getStatus());
        assertEquals(100.0, pending.getAmountQuote(), 1e-9);
        assertEquals(1.0, pending.getQuantity(), 1e-12);
        assertEquals(100.0, pending.getPrice(), 1e-9);
        assertEquals(0.2, pending.getFee(), 1e-9);
    }

    @Test
    void reconcileAllReturnsTheIdsOfStrategiesItCouldNotReconcile() {
        LiveStrategy broken = strategy(false, "BTC-EUR");
        LiveStrategy healthy = strategy(false, "ETH-EUR");
        healthy.setId("strategy-2");
        when(liveStrategyService.all()).thenReturn(List.of(broken, healthy));
        when(botConfigurationService.getBotConfiguration(BOT_ID)).thenReturn(Optional.of(bot()));
        when(bitvavoApiClient.get(ordersEndpoint("BTC-EUR"), OrderResponse[].class, KEY, SECRET))
                .thenThrow(new ResourceAccessException("connect timed out"));
        when(bitvavoApiClient.get(ordersEndpoint("ETH-EUR"), OrderResponse[].class, KEY, SECRET))
                .thenReturn(new OrderResponse[0]);

        Set<String> failed = service.reconcileAll();

        assertEquals(Set.of(STRATEGY_ID), failed);
        verify(liveOrderRepository).findByStrategyIdOrderByCreatedTimeDesc("strategy-2");
    }

    @Test
    void rerunningIsANoOp() {
        LiveOrder filled = aFilledBuyThatMatchesItsOpenPosition();
        when(liveOrderRepository.findByStrategyIdOrderByCreatedTimeDesc(STRATEGY_ID)).thenReturn(List.of(filled));

        service.reconcileAll();
        service.reconcileAll();

        verify(liveOrderRepository, never()).save(any());
        verify(livePositionRepository, never()).save(any());
    }

    @Test
    void matchingStatesCauseNoWrites() {
        LiveOrder filled = aFilledBuyThatMatchesItsOpenPosition();
        when(liveOrderRepository.findByStrategyIdOrderByCreatedTimeDesc(STRATEGY_ID)).thenReturn(List.of(filled));

        service.reconcileAll();

        verify(liveOrderRepository, never()).save(any());
        verify(livePositionRepository, never()).save(any());
    }

    @Test
    void aResolvedBuyOpensThePosition() {
        stubStrategyAndBot();
        UUID clientOrderId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        LiveOrder pending = pendingRow("BUY", clientOrderId, Instant.ofEpochMilli(START + MINUTE));
        when(liveOrderRepository.findByStrategyIdOrderByCreatedTimeDesc(STRATEGY_ID)).thenReturn(List.of(pending));
        when(bitvavoApiClient.get(ordersEndpoint(MARKET), OrderResponse[].class, KEY, SECRET))
                .thenReturn(new OrderResponse[]{order(orderId, "buy", "filled", START, clientOrderId,
                        "0.002", "110.00", "0.22")});

        service.reconcileAll();

        ArgumentCaptor<LivePosition> captor = ArgumentCaptor.forClass(LivePosition.class);
        verify(livePositionRepository).save(captor.capture());
        LivePosition position = captor.getValue();
        assertEquals("OPEN", position.getStatus());
        assertEquals(0.002, position.getQuantity(), 1e-12);
        assertEquals(55000.0, position.getEntryPrice(), 1e-9);
        assertEquals(0.22, position.getEntryFee(), 1e-9);
        assertEquals(START, position.getEntryTime());
        assertFalse(position.isDryRun());
    }

    @Test
    void aSellClosesTheOpenPosition() {
        stubStrategyAndBot();
        LiveOrder buy = filledRow("BUY", UUID.randomUUID(), UUID.randomUUID(), Instant.ofEpochMilli(START),
                0.001, 100.0, 100000.0, 0.2);
        LiveOrder sell = filledRow("SELL", UUID.randomUUID(), UUID.randomUUID(), Instant.ofEpochMilli(START + HOUR),
                0.001, 110.0, 110000.0, 0.22);
        when(liveOrderRepository.findByStrategyIdOrderByCreatedTimeDesc(STRATEGY_ID)).thenReturn(List.of(sell, buy));
        when(bitvavoApiClient.get(ordersEndpoint(MARKET), OrderResponse[].class, KEY, SECRET))
                .thenReturn(new OrderResponse[0]);
        LivePosition position = openPosition(0.001, 100000.0, 0.2);
        when(livePositionRepository.findByStrategyIdAndStatus(STRATEGY_ID, "OPEN")).thenReturn(Optional.of(position));

        service.reconcileAll();

        ArgumentCaptor<LivePosition> captor = ArgumentCaptor.forClass(LivePosition.class);
        verify(livePositionRepository).save(captor.capture());
        LivePosition closed = captor.getValue();
        assertEquals("CLOSED", closed.getStatus());
        assertEquals(110000.0, closed.getExitPrice(), 1e-9);
        assertEquals(START + HOUR, closed.getExitTime());
        assertEquals(110.0 - 100.0 - 0.22 - 0.2, closed.getProfit(), 1e-9);
        verify(liveOrderRepository, never()).save(any());
    }

    @Test
    void anOpenPositionWithoutASellIsLeftAlone() {
        stubStrategyAndBot();
        when(liveOrderRepository.findByStrategyIdOrderByCreatedTimeDesc(STRATEGY_ID)).thenReturn(List.of());
        when(bitvavoApiClient.get(ordersEndpoint(MARKET), OrderResponse[].class, KEY, SECRET))
                .thenReturn(new OrderResponse[0]);
        stubOpenPosition(0.001, 100000.0, 0.2);

        service.reconcileAll();

        verify(livePositionRepository, never()).save(any());
    }

    @Test
    void aLegacyFilledBuyWithoutAmountsDoesNotOpenAPosition() {
        stubStrategyAndBot();
        LiveOrder legacy = filledRow("BUY", null, UUID.randomUUID(), Instant.ofEpochMilli(START),
                null, null, null, null);
        when(liveOrderRepository.findByStrategyIdOrderByCreatedTimeDesc(STRATEGY_ID)).thenReturn(List.of(legacy));
        when(bitvavoApiClient.get(ordersEndpoint(MARKET), OrderResponse[].class, KEY, SECRET))
                .thenReturn(new OrderResponse[0]);

        service.reconcileAll();

        verify(livePositionRepository, never()).save(any());
    }

    private LiveOrder aFilledBuyThatMatchesItsOpenPosition() {
        stubStrategyAndBot();
        UUID clientOrderId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        when(bitvavoApiClient.get(ordersEndpoint(MARKET), OrderResponse[].class, KEY, SECRET))
                .thenReturn(new OrderResponse[]{order(orderId, "buy", "filled", START, clientOrderId,
                        "0.001", "100.00", "0.2")});
        stubOpenPosition(0.001, 100000.0, 0.2);
        return filledRow("BUY", clientOrderId, orderId, Instant.ofEpochMilli(START), 0.001, 100.0, 100000.0, 0.2);
    }

    private void stubStrategyAndBot() {
        when(liveStrategyService.all()).thenReturn(List.of(strategy(false, MARKET)));
        when(botConfigurationService.getBotConfiguration(BOT_ID)).thenReturn(Optional.of(bot()));
    }

    private void stubOpenPosition(double quantity, double entryPrice, Double entryFee) {
        when(livePositionRepository.findByStrategyIdAndStatus(STRATEGY_ID, "OPEN"))
                .thenReturn(Optional.of(openPosition(quantity, entryPrice, entryFee)));
    }

    private static String ordersEndpoint(String market) {
        return "/orders?market=" + market + "&start=" + START + "&limit=100";
    }

    private static LiveStrategy strategy(boolean dryRun, String market) {
        LiveStrategy strategy = LiveStrategy.builder()
                .botId(BOT_ID)
                .market(market)
                .dryRun(dryRun)
                .enabled(true)
                .createdTime(Instant.ofEpochMilli(START))
                .build();
        strategy.setId(STRATEGY_ID);
        return strategy;
    }

    private static BotConfiguration bot() {
        return BotConfiguration.builder()
                .botId(BOT_ID)
                .apiKey(KEY)
                .apiSecret(SECRET)
                .active(true)
                .build();
    }

    private static OrderResponse order(UUID orderId, String side, String status, Long created, UUID clientOrderId,
                                       String filledAmount, String filledAmountQuote, String feePaid) {
        OrderResponse order = new OrderResponse();
        order.setOrderId(orderId);
        order.setMarket(MARKET);
        order.setSide(side);
        order.setStatus(status);
        order.setCreated(created);
        order.setClientOrderId(clientOrderId);
        order.setFilledAmount(decimal(filledAmount));
        order.setFilledAmountQuote(decimal(filledAmountQuote));
        order.setFeePaid(decimal(feePaid));
        return order;
    }

    private static BigDecimal decimal(String value) {
        return value == null ? null : new BigDecimal(value);
    }

    private static LiveOrder pendingRow(String side, UUID clientOrderId, Instant createdTime) {
        LiveOrder order = LiveOrder.builder()
                .strategyId(STRATEGY_ID)
                .market(MARKET)
                .side(side)
                .status("PENDING")
                .dryRun(false)
                .signalOpenTime(START)
                .reason(side.toLowerCase() + " ALL [TEST]")
                .amountQuote(100.0)
                .quantity(1.0)
                .price(100.0)
                .fee(0.2)
                .clientOrderId(clientOrderId.toString())
                .createdTime(createdTime)
                .build();
        order.setId("pending-" + side);
        return order;
    }

    private static LiveOrder failedRow(String side, UUID clientOrderId, Instant createdTime, String reason) {
        LiveOrder order = LiveOrder.builder()
                .strategyId(STRATEGY_ID)
                .market(MARKET)
                .side(side)
                .status("FAILED")
                .dryRun(false)
                .reason(reason)
                .clientOrderId(clientOrderId == null ? null : clientOrderId.toString())
                .createdTime(createdTime)
                .build();
        order.setId("failed-" + side);
        return order;
    }

    private static LiveOrder filledRow(String side, UUID clientOrderId, UUID orderId, Instant createdTime,
                                       Double quantity, Double amountQuote, Double price, Double fee) {
        LiveOrder order = LiveOrder.builder()
                .strategyId(STRATEGY_ID)
                .market(MARKET)
                .side(side)
                .status("FILLED")
                .dryRun(false)
                .orderId(orderId == null ? null : orderId.toString())
                .clientOrderId(clientOrderId == null ? null : clientOrderId.toString())
                .quantity(quantity)
                .amountQuote(amountQuote)
                .price(price)
                .fee(fee)
                .createdTime(createdTime)
                .build();
        order.setId("filled-" + side);
        return order;
    }

    private static LivePosition openPosition(double quantity, double entryPrice, Double entryFee) {
        LivePosition position = LivePosition.builder()
                .strategyId(STRATEGY_ID)
                .market(MARKET)
                .status("OPEN")
                .quantity(quantity)
                .entryPrice(entryPrice)
                .entryFee(entryFee)
                .entryTime(START)
                .dryRun(false)
                .build();
        position.setId("position-1");
        return position;
    }
}
