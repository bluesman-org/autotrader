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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

        service.reconcileAll();

        verifyNoInteractions(botConfigurationService, bitvavoApiClient, liveOrderRepository, livePositionRepository);
    }

    @Test
    void strategiesWithoutABotIdAreSkipped() {
        LiveStrategy strategy = strategy(false, MARKET);
        strategy.setBotId(null);
        when(liveStrategyService.all()).thenReturn(List.of(strategy));

        service.reconcileAll();

        verifyNoInteractions(botConfigurationService, bitvavoApiClient, liveOrderRepository, livePositionRepository);
    }

    @Test
    void strategiesWithAnUnknownBotAreSkipped() {
        when(liveStrategyService.all()).thenReturn(List.of(strategy(false, MARKET)));
        when(botConfigurationService.getBotConfiguration(BOT_ID)).thenReturn(Optional.empty());

        service.reconcileAll();

        verifyNoInteractions(bitvavoApiClient, liveOrderRepository, livePositionRepository);
    }

    @Test
    void anEmptyExchangeHistoryWritesNothing() {
        stubStrategyAndBot();
        when(bitvavoApiClient.get(ordersEndpoint(MARKET), OrderResponse[].class, KEY, SECRET))
                .thenReturn(new OrderResponse[0]);

        service.reconcileAll();

        verify(liveOrderRepository, never()).save(any());
        verify(livePositionRepository, never()).save(any());
    }

    @Test
    void aNullExchangeHistoryWritesNothing() {
        stubStrategyAndBot();
        when(bitvavoApiClient.get(ordersEndpoint(MARKET), OrderResponse[].class, KEY, SECRET)).thenReturn(null);

        service.reconcileAll();

        verify(liveOrderRepository, never()).save(any());
        verify(livePositionRepository, never()).save(any());
    }

    @Test
    void aMissingFilledOrderIsCreatedAndThePositionOpened() {
        stubStrategyAndBot();
        UUID orderId = UUID.randomUUID();
        OrderResponse filled = order(orderId, "buy", "filled", START, "0.001", "100.00", "0.2");
        OrderResponse open = order(UUID.randomUUID(), "buy", "open", START, null, null, null);
        when(bitvavoApiClient.get(ordersEndpoint(MARKET), OrderResponse[].class, KEY, SECRET))
                .thenReturn(new OrderResponse[]{filled, open});

        service.reconcileAll();

        ArgumentCaptor<LiveOrder> orderCaptor = ArgumentCaptor.forClass(LiveOrder.class);
        verify(liveOrderRepository).save(orderCaptor.capture());
        LiveOrder row = orderCaptor.getValue();
        assertEquals(STRATEGY_ID, row.getStrategyId());
        assertEquals(MARKET, row.getMarket());
        assertEquals("BUY", row.getSide());
        assertEquals("FILLED", row.getStatus());
        assertFalse(row.isDryRun());
        assertEquals(0, row.getSignalOpenTime());
        assertEquals(100.0, row.getAmountQuote(), 1e-9);
        assertEquals(0.001, row.getQuantity(), 1e-12);
        assertEquals(100000.0, row.getPrice(), 1e-9);
        assertEquals(0.2, row.getFee(), 1e-9);
        assertEquals(orderId.toString(), row.getOrderId());
        assertNull(row.getErrorMessage());
        assertEquals(LiveOrderReconciliationService.MISSING_REASON, row.getReason());
        assertEquals(Instant.ofEpochMilli(START), row.getCreatedTime());

        ArgumentCaptor<LivePosition> positionCaptor = ArgumentCaptor.forClass(LivePosition.class);
        verify(livePositionRepository).save(positionCaptor.capture());
        LivePosition position = positionCaptor.getValue();
        assertEquals("OPEN", position.getStatus());
        assertEquals(0.001, position.getQuantity(), 1e-12);
        assertEquals(100000.0, position.getEntryPrice(), 1e-9);
        assertEquals(0.2, position.getEntryFee(), 1e-9);
        assertEquals(START, position.getEntryTime());
        assertFalse(position.isDryRun());
    }

    @Test
    void aFailedRowWithinTheMatchingWindowIsCorrectedWithoutADuplicate() {
        stubStrategyAndBot();
        UUID orderId = UUID.randomUUID();
        when(bitvavoApiClient.get(ordersEndpoint(MARKET), OrderResponse[].class, KEY, SECRET))
                .thenReturn(new OrderResponse[]{order(orderId, "buy", "filled", START, "0.001", "100.00", "0.2")});
        LiveOrder failed = failedOrder("BUY", Instant.ofEpochMilli(START + 30_000L), "entry ALL [MACD_BULLISH_CROSSOVER]");
        when(liveOrderRepository.findByStrategyIdOrderByCreatedTimeDesc(STRATEGY_ID)).thenReturn(List.of(failed));
        stubOpenPosition(0.001, 100000.0, 0.2);

        service.reconcileAll();

        ArgumentCaptor<LiveOrder> captor = ArgumentCaptor.forClass(LiveOrder.class);
        verify(liveOrderRepository, times(1)).save(captor.capture());
        LiveOrder corrected = captor.getValue();
        assertEquals("FILLED", corrected.getStatus());
        assertEquals(orderId.toString(), corrected.getOrderId());
        assertEquals(100.0, corrected.getAmountQuote(), 1e-9);
        assertEquals(0.001, corrected.getQuantity(), 1e-12);
        assertEquals(100000.0, corrected.getPrice(), 1e-9);
        assertEquals(0.2, corrected.getFee(), 1e-9);
        assertNull(corrected.getErrorMessage());
        assertEquals("entry ALL [MACD_BULLISH_CROSSOVER] "
                + LiveOrderReconciliationService.RECONCILED_NOTE, corrected.getReason());
        verify(livePositionRepository, never()).save(any());
    }

    @Test
    void aFailedRowOutsideTheMatchingWindowIsLeftAlone() {
        stubStrategyAndBot();
        UUID orderId = UUID.randomUUID();
        when(bitvavoApiClient.get(ordersEndpoint(MARKET), OrderResponse[].class, KEY, SECRET))
                .thenReturn(new OrderResponse[]{order(orderId, "buy", "filled", START, "0.001", "100.00", "0.2")});
        LiveOrder failed = failedOrder("BUY", Instant.ofEpochMilli(START + 20 * MINUTE), "entry ALL [X]");
        when(liveOrderRepository.findByStrategyIdOrderByCreatedTimeDesc(STRATEGY_ID)).thenReturn(List.of(failed));
        stubOpenPosition(0.001, 100000.0, 0.2);

        service.reconcileAll();

        assertEquals("FAILED", failed.getStatus());
        assertNull(failed.getOrderId());
        ArgumentCaptor<LiveOrder> captor = ArgumentCaptor.forClass(LiveOrder.class);
        verify(liveOrderRepository).save(captor.capture());
        assertEquals(orderId.toString(), captor.getValue().getOrderId());
        verify(livePositionRepository, never()).save(any());
    }

    @Test
    void aFailedRowWithoutAReasonGetsTheReconciledNote() {
        stubStrategyAndBot();
        UUID orderId = UUID.randomUUID();
        when(bitvavoApiClient.get(ordersEndpoint(MARKET), OrderResponse[].class, KEY, SECRET))
                .thenReturn(new OrderResponse[]{order(orderId, "sell", "filled", START, "0.001", "110.00", "0.22")});
        LiveOrder failed = failedOrder("SELL", Instant.ofEpochMilli(START), null);
        when(liveOrderRepository.findByStrategyIdOrderByCreatedTimeDesc(STRATEGY_ID)).thenReturn(List.of(failed));
        stubOpenPosition(0.001, 100000.0, 0.2);

        service.reconcileAll();

        assertEquals(LiveOrderReconciliationService.RECONCILED_NOTE, failed.getReason());
        assertEquals(orderId.toString(), failed.getOrderId());
    }

    @Test
    void aClaimedOrderIsNotUsedTwiceWhenCorrectingAFailedRow() {
        stubStrategyAndBot();
        UUID recordedBuy = UUID.randomUUID();
        UUID recordedSell = UUID.randomUUID();
        UUID matchedBuy = UUID.randomUUID();
        long filledAt = START + 5 * MINUTE;
        when(bitvavoApiClient.get(ordersEndpoint(MARKET), OrderResponse[].class, KEY, SECRET)).thenReturn(new OrderResponse[]{
                order(recordedBuy, "buy", "filled", START, "0.001", "100.00", "0.2"),
                order(recordedSell, "sell", "filled", filledAt, "0.001", "110.00", "0.22"),
                order(matchedBuy, "buy", "filled", filledAt, "0.001", "101.00", "0.2")});
        LiveOrder buy = filledOrder("BUY", recordedBuy, Instant.ofEpochMilli(START), "0.001", "100.00", "0.2");
        LiveOrder sell = filledOrder("SELL", recordedSell, Instant.ofEpochMilli(filledAt), "0.001", "110.00", "0.22");
        LiveOrder failed = failedOrder("BUY", Instant.ofEpochMilli(filledAt), "entry ALL [X]");
        when(liveOrderRepository.findByStrategyIdOrderByCreatedTimeDesc(STRATEGY_ID))
                .thenReturn(List.of(buy, sell, failed));
        stubOpenPosition(0.001, 100000.0, 0.2);

        service.reconcileAll();

        assertEquals(matchedBuy.toString(), failed.getOrderId());
        assertEquals("FILLED", failed.getStatus());
        verify(liveOrderRepository, times(1)).save(any());
        verify(livePositionRepository, never()).save(any());
    }

    @Test
    void anAlreadyRecordedOrderIsNotDuplicated() {
        stubStrategyAndBot();
        UUID orderId = UUID.randomUUID();
        when(bitvavoApiClient.get(ordersEndpoint(MARKET), OrderResponse[].class, KEY, SECRET))
                .thenReturn(new OrderResponse[]{order(orderId, "buy", "filled", START, "0.001", "100.00", "0.2")});
        when(liveOrderRepository.findByStrategyIdOrderByCreatedTimeDesc(STRATEGY_ID))
                .thenReturn(List.of(filledOrder("BUY", orderId, Instant.ofEpochMilli(START), "0.001", "100.00", "0.2")));
        stubOpenPosition(0.001, 100000.0, 0.2);

        service.reconcileAll();

        verify(liveOrderRepository, never()).save(any());
        verify(livePositionRepository, never()).save(any());
    }

    @Test
    void aSellClosesTheOpenPosition() {
        stubStrategyAndBot();
        when(bitvavoApiClient.get(ordersEndpoint(MARKET), OrderResponse[].class, KEY, SECRET))
                .thenReturn(new OrderResponse[0]);
        LiveOrder buy = filledOrder("BUY", UUID.randomUUID(), Instant.ofEpochMilli(START), "0.001", "100.00", "0.2");
        LiveOrder sell = filledOrder("SELL", UUID.randomUUID(), Instant.ofEpochMilli(START + MINUTE),
                "0.001", "110.00", "0.22");
        sell.setPrice(110000.0);
        sell.setSignalOpenTime(0);
        when(liveOrderRepository.findByStrategyIdOrderByCreatedTimeDesc(STRATEGY_ID)).thenReturn(List.of(sell, buy));
        LivePosition position = openPosition(0.001, 100000.0, null);
        when(livePositionRepository.findByStrategyIdAndStatus(STRATEGY_ID, "OPEN")).thenReturn(Optional.of(position));

        service.reconcileAll();

        ArgumentCaptor<LivePosition> captor = ArgumentCaptor.forClass(LivePosition.class);
        verify(livePositionRepository).save(captor.capture());
        LivePosition closed = captor.getValue();
        assertEquals("CLOSED", closed.getStatus());
        assertEquals(110000.0, closed.getExitPrice(), 1e-9);
        assertEquals(START + MINUTE, closed.getExitTime());
        assertEquals(110.0 - 100.0 - 0.22, closed.getProfit(), 1e-9);
        verify(liveOrderRepository, never()).save(any());
    }

    @Test
    void matchingStatesCauseNoWrites() {
        stubStrategyAndBot();
        UUID orderId = UUID.randomUUID();
        when(bitvavoApiClient.get(ordersEndpoint(MARKET), OrderResponse[].class, KEY, SECRET))
                .thenReturn(new OrderResponse[]{order(orderId, "buy", "filled", START, "0.001", "100.00", "0.2")});
        when(liveOrderRepository.findByStrategyIdOrderByCreatedTimeDesc(STRATEGY_ID))
                .thenReturn(List.of(filledOrder("BUY", orderId, Instant.ofEpochMilli(START), "0.001", "100.00", "0.2")));
        stubOpenPosition(0.001, 100000.0, 0.2);

        service.reconcileAll();

        verify(liveOrderRepository, never()).save(any());
        verify(livePositionRepository, never()).save(any());
    }

    @Test
    void anOpenPositionIsCreatedUsingTheBuySignalTime() {
        stubStrategyAndBot();
        when(bitvavoApiClient.get(ordersEndpoint(MARKET), OrderResponse[].class, KEY, SECRET))
                .thenReturn(new OrderResponse[0]);
        LiveOrder buy = filledOrder("BUY", UUID.randomUUID(), Instant.ofEpochMilli(START + HOUR),
                "0.001", "100.00", "0.2");
        buy.setSignalOpenTime(START);
        when(liveOrderRepository.findByStrategyIdOrderByCreatedTimeDesc(STRATEGY_ID)).thenReturn(List.of(buy));

        service.reconcileAll();

        ArgumentCaptor<LivePosition> captor = ArgumentCaptor.forClass(LivePosition.class);
        verify(livePositionRepository).save(captor.capture());
        assertEquals(START, captor.getValue().getEntryTime());
        verify(liveOrderRepository, never()).save(any());
    }

    @Test
    void aSellSignalTimeIsUsedForTheExitTime() {
        stubStrategyAndBot();
        when(bitvavoApiClient.get(ordersEndpoint(MARKET), OrderResponse[].class, KEY, SECRET))
                .thenReturn(new OrderResponse[0]);
        LiveOrder buy = filledOrder("BUY", UUID.randomUUID(), Instant.ofEpochMilli(START), "0.001", "100.00", "0.2");
        LiveOrder sell = filledOrder("SELL", UUID.randomUUID(), Instant.ofEpochMilli(START + HOUR),
                "0.001", "110.00", "0.22");
        sell.setPrice(110000.0);
        sell.setSignalOpenTime(START + 2 * HOUR);
        when(liveOrderRepository.findByStrategyIdOrderByCreatedTimeDesc(STRATEGY_ID)).thenReturn(List.of(sell, buy));
        stubOpenPosition(0.001, 100000.0, 0.2);

        service.reconcileAll();

        ArgumentCaptor<LivePosition> captor = ArgumentCaptor.forClass(LivePosition.class);
        verify(livePositionRepository).save(captor.capture());
        assertEquals(START + 2 * HOUR, captor.getValue().getExitTime());
        assertEquals(110000.0, captor.getValue().getExitPrice(), 1e-9);
    }

    @Test
    void rerunningIsANoOp() {
        stubStrategyAndBot();
        UUID orderId = UUID.randomUUID();
        when(bitvavoApiClient.get(ordersEndpoint(MARKET), OrderResponse[].class, KEY, SECRET))
                .thenReturn(new OrderResponse[]{order(orderId, "buy", "filled", START, "0.001", "100.00", "0.2")});
        when(liveOrderRepository.findByStrategyIdOrderByCreatedTimeDesc(STRATEGY_ID))
                .thenReturn(List.of(filledOrder("BUY", orderId, Instant.ofEpochMilli(START), "0.001", "100.00", "0.2")));
        stubOpenPosition(0.001, 100000.0, 0.2);

        service.reconcileAll();
        service.reconcileAll();

        verify(liveOrderRepository, never()).save(any());
        verify(livePositionRepository, never()).save(any());
    }

    @Test
    void anExchangeFailureForOneStrategyStillReconcilesTheNext() {
        LiveStrategy broken = strategy(false, "BTC-EUR");
        LiveStrategy healthy = strategy(false, "ETH-EUR");
        healthy.setId("strategy-2");
        when(liveStrategyService.all()).thenReturn(List.of(broken, healthy));
        when(botConfigurationService.getBotConfiguration(BOT_ID)).thenReturn(Optional.of(bot()));
        when(bitvavoApiClient.get(ordersEndpoint("BTC-EUR"), OrderResponse[].class, KEY, SECRET))
                .thenThrow(new ResourceAccessException("connect timed out"));
        UUID orderId = UUID.randomUUID();
        when(bitvavoApiClient.get(ordersEndpoint("ETH-EUR"), OrderResponse[].class, KEY, SECRET))
                .thenReturn(new OrderResponse[]{order(orderId, "buy", "filled", START, "0.001", "100.00", "0.2")});

        service.reconcileAll();

        ArgumentCaptor<LiveOrder> captor = ArgumentCaptor.forClass(LiveOrder.class);
        verify(liveOrderRepository).save(captor.capture());
        assertEquals("strategy-2", captor.getValue().getStrategyId());
        ArgumentCaptor<LivePosition> positionCaptor = ArgumentCaptor.forClass(LivePosition.class);
        verify(livePositionRepository).save(positionCaptor.capture());
        assertEquals("strategy-2", positionCaptor.getValue().getStrategyId());
    }

    @Test
    void aMissingOrderWithoutAmountsLeavesThePositionClosed() {
        stubStrategyAndBot();
        UUID orderId = UUID.randomUUID();
        when(bitvavoApiClient.get(ordersEndpoint(MARKET), OrderResponse[].class, KEY, SECRET))
                .thenReturn(new OrderResponse[]{order(orderId, "buy", "filled", START, null, null, null)});

        service.reconcileAll();

        ArgumentCaptor<LiveOrder> captor = ArgumentCaptor.forClass(LiveOrder.class);
        verify(liveOrderRepository).save(captor.capture());
        LiveOrder row = captor.getValue();
        assertNull(row.getQuantity());
        assertNull(row.getAmountQuote());
        assertNull(row.getPrice());
        assertNull(row.getFee());
        verify(livePositionRepository, never()).save(any());
    }

    @Test
    void anOrderWithNoFilledBaseLeavesThePositionClosed() {
        stubStrategyAndBot();
        UUID orderId = UUID.randomUUID();
        when(bitvavoApiClient.get(ordersEndpoint(MARKET), OrderResponse[].class, KEY, SECRET))
                .thenReturn(new OrderResponse[]{order(orderId, "buy", "filled", START, "0", "0", "0")});

        service.reconcileAll();

        ArgumentCaptor<LiveOrder> captor = ArgumentCaptor.forClass(LiveOrder.class);
        verify(liveOrderRepository).save(captor.capture());
        assertEquals(0.0, captor.getValue().getQuantity(), 1e-12);
        assertNull(captor.getValue().getPrice());
        verify(livePositionRepository, never()).save(any());
    }

    @Test
    void anOpenPositionWithoutASellIsLeftAlone() {
        stubStrategyAndBot();
        when(bitvavoApiClient.get(ordersEndpoint(MARKET), OrderResponse[].class, KEY, SECRET))
                .thenReturn(new OrderResponse[0]);
        when(liveOrderRepository.findByStrategyIdOrderByCreatedTimeDesc(STRATEGY_ID)).thenReturn(List.of());
        stubOpenPosition(0.001, 100000.0, 0.2);

        service.reconcileAll();

        verify(livePositionRepository, never()).save(any());
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
        return "/orders?market=" + market + "&limit=100";
    }

    private static LiveStrategy strategy(boolean dryRun, String market) {
        LiveStrategy strategy = LiveStrategy.builder()
                .botId(BOT_ID)
                .market(market)
                .dryRun(dryRun)
                .enabled(true)
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

    private static OrderResponse order(UUID orderId, String side, String status, long created,
                                       String filledAmount, String filledAmountQuote, String feePaid) {
        OrderResponse order = new OrderResponse();
        order.setOrderId(orderId);
        order.setMarket(MARKET);
        order.setSide(side);
        order.setStatus(status);
        order.setCreated(created);
        order.setFilledAmount(decimal(filledAmount));
        order.setFilledAmountQuote(decimal(filledAmountQuote));
        order.setFeePaid(decimal(feePaid));
        return order;
    }

    private static BigDecimal decimal(String value) {
        return value == null ? null : new BigDecimal(value);
    }

    private static LiveOrder failedOrder(String side, Instant createdTime, String reason) {
        LiveOrder order = LiveOrder.builder()
                .strategyId(STRATEGY_ID)
                .market(MARKET)
                .side(side)
                .status("FAILED")
                .dryRun(false)
                .reason(reason)
                .createdTime(createdTime)
                .build();
        order.setId("failed-" + side);
        return order;
    }

    private static LiveOrder filledOrder(String side, UUID orderId, Instant createdTime,
                                         String quantity, String amountQuote, String fee) {
        LiveOrder order = LiveOrder.builder()
                .strategyId(STRATEGY_ID)
                .market(MARKET)
                .side(side)
                .status("FILLED")
                .dryRun(false)
                .orderId(orderId.toString())
                .amountQuote(Double.valueOf(amountQuote))
                .quantity(Double.valueOf(quantity))
                .price(100000.0)
                .fee(Double.valueOf(fee))
                .createdTime(createdTime)
                .build();
        order.setId(side.toLowerCase() + "-" + orderId);
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
