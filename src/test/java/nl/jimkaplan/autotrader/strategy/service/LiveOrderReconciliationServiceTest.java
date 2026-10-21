package nl.jimkaplan.autotrader.strategy.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import nl.jimkaplan.autotrader.bitvavo.client.BitvavoApiClient;
import nl.jimkaplan.autotrader.bitvavo.model.OrderResponse;
import nl.jimkaplan.autotrader.model.document.BitvavoCredentials;
import nl.jimkaplan.autotrader.repository.LiveOrderRepository;
import nl.jimkaplan.autotrader.repository.LivePositionRepository;
import nl.jimkaplan.autotrader.repository.LiveStrategyRepository;
import nl.jimkaplan.autotrader.service.BitvavoCredentialsService;
import nl.jimkaplan.autotrader.strategy.model.document.LiveOrder;
import nl.jimkaplan.autotrader.strategy.model.document.LivePosition;
import nl.jimkaplan.autotrader.strategy.model.document.LiveStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.ResourceAccessException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
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
    private static final String STRATEGY_ID = "strategy-1";
    private static final String MARKET = "BTC-EUR";
    private static final long START = 1_783_069_200_000L;
    private static final long MINUTE = 60_000L;
    private static final long HOUR = 3_600_000L;

    @Mock
    private LiveStrategyService liveStrategyService;

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

    @InjectMocks
    private LiveOrderReconciliationService service;

    @Test
    void dryRunStrategiesAreSkipped() {
        when(liveStrategyService.all()).thenReturn(List.of(strategy(true, MARKET)));

        assertTrue(service.reconcileAll().isEmpty());

        verifyNoInteractions(credentialsService, bitvavoApiClient, liveOrderRepository, livePositionRepository);
    }

    @Test
    void strategiesWithoutAnApiKeyAreHeldBack() {
        when(liveStrategyService.all()).thenReturn(List.of(strategy(false, MARKET)));
        when(credentialsService.get()).thenReturn(Optional.empty());

        // A real strategy cannot trade without the account key, so reconciliation fails and the
        // starter leaves it stopped.
        assertEquals(Set.of(STRATEGY_ID), service.reconcileAll());

        verifyNoInteractions(bitvavoApiClient, liveOrderRepository, livePositionRepository);
    }

    @Test
    void aPendingRowIsResolvedToFilledFromTheHistory() {
        stubStrategyAndAccount();
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
        stubStrategyAndAccount();
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
        stubStrategyAndAccount();
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
        stubStrategyAndAccount();
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
        stubStrategyAndAccount();
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
    void aFailedRowWithoutAClientOrderIdIsWarnedAndLeftAlone() {
        stubStrategyAndAccount();
        LiveOrder failed = failedRow("BUY", null, Instant.ofEpochMilli(START), "entry ALL [TEST]");
        when(liveOrderRepository.findByStrategyIdOrderByCreatedTimeDesc(STRATEGY_ID)).thenReturn(List.of(failed));
        when(bitvavoApiClient.get(ordersEndpoint(MARKET), OrderResponse[].class, KEY, SECRET))
                .thenReturn(new OrderResponse[0]);

        ListAppender<ILoggingEvent> warnings = attachWarnings();
        try {
            service.reconcileAll();
        } finally {
            detachWarnings(warnings);
        }

        assertEquals("FAILED", failed.getStatus());
        assertNull(failed.getClientOrderId());
        verify(liveOrderRepository, never()).save(any());
        assertTrue(warnedAboutMissingClientOrderId(warnings));
    }

    @Test
    void aFailedRowWithAnUnknownClientOrderIdIsLeftAlone() {
        stubStrategyAndAccount();
        LiveOrder failed = failedRow("BUY", UUID.randomUUID(), Instant.ofEpochMilli(START), "entry ALL [TEST]");
        when(liveOrderRepository.findByStrategyIdOrderByCreatedTimeDesc(STRATEGY_ID)).thenReturn(List.of(failed));
        when(bitvavoApiClient.get(ordersEndpoint(MARKET), OrderResponse[].class, KEY, SECRET))
                .thenReturn(new OrderResponse[0]);

        service.reconcileAll();

        assertEquals("FAILED", failed.getStatus());
        verify(liveOrderRepository, never()).save(any());
    }

    @Test
    void incompleteHistoryEntriesAreSkippedAndLeaveThePendingRowPending() {
        stubStrategyAndAccount();
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

        assertEquals("PENDING", pending.getStatus());
        verify(liveOrderRepository, never()).save(any());
    }

    @Test
    void anExchangeEntryWithoutAClientOrderIdDoesNotMakeThePageIncomplete() {
        stubStrategyAndAccount();
        LiveOrder pending = pendingRow("BUY", UUID.randomUUID(), Instant.ofEpochMilli(START + MINUTE));
        when(liveOrderRepository.findByStrategyIdOrderByCreatedTimeDesc(STRATEGY_ID)).thenReturn(List.of(pending));
        when(bitvavoApiClient.get(ordersEndpoint(MARKET), OrderResponse[].class, KEY, SECRET))
                .thenReturn(new OrderResponse[]{
                        order(UUID.randomUUID(), "buy", "filled", START, null, "0.001", "100.00", "0.2")});

        assertTrue(service.reconcileAll().isEmpty());

        assertEquals("FAILED", pending.getStatus());
        assertEquals(LiveOrderReconciliationService.NOT_ACCEPTED_MESSAGE, pending.getErrorMessage());
    }

    @Test
    void anExchangeEntryWithAClientOrderIdMissingAFieldMakesThePageIncomplete() {
        stubStrategyAndAccount();
        LiveOrder pending = pendingRow("BUY", UUID.randomUUID(), Instant.ofEpochMilli(START + MINUTE));
        when(liveOrderRepository.findByStrategyIdOrderByCreatedTimeDesc(STRATEGY_ID)).thenReturn(List.of(pending));
        when(bitvavoApiClient.get(ordersEndpoint(MARKET), OrderResponse[].class, KEY, SECRET))
                .thenReturn(new OrderResponse[]{
                        order(UUID.randomUUID(), "buy", "filled", null, UUID.randomUUID(),
                                "0.001", "100.00", "0.2")});

        assertTrue(service.reconcileAll().isEmpty());

        assertEquals("PENDING", pending.getStatus());
        verify(liveOrderRepository, never()).save(any());
    }

    @Test
    void aPendingRowMissingFromAFullPageIsLeftPending() {
        stubStrategyAndAccount();
        LiveOrder pending = pendingRow("BUY", UUID.randomUUID(), Instant.ofEpochMilli(START + MINUTE));
        when(liveOrderRepository.findByStrategyIdOrderByCreatedTimeDesc(STRATEGY_ID)).thenReturn(List.of(pending));
        when(bitvavoApiClient.get(ordersEndpoint(MARKET), OrderResponse[].class, KEY, SECRET))
                .thenReturn(fullHistory());

        assertTrue(service.reconcileAll().isEmpty());

        assertEquals("PENDING", pending.getStatus());
        verify(liveOrderRepository, never()).save(any());
    }

    @Test
    void aPendingRowWhoseExchangeOrderTurnedTerminalIsSettledAsFailed() {
        stubStrategyAndAccount();
        UUID clientOrderId = UUID.randomUUID();
        LiveOrder pending = pendingRow("BUY", clientOrderId, Instant.ofEpochMilli(START + MINUTE));
        when(liveOrderRepository.findByStrategyIdOrderByCreatedTimeDesc(STRATEGY_ID))
                .thenReturn(List.of(pending));
        when(bitvavoApiClient.get(ordersEndpoint(MARKET), OrderResponse[].class, KEY, SECRET))
                .thenReturn(new OrderResponse[]{order(UUID.randomUUID(), "buy",
                        "canceledMarketProtection", START + MINUTE, clientOrderId,
                        null, "50.00", null)});

        assertTrue(service.reconcileAll().isEmpty());

        assertEquals("FAILED", pending.getStatus());
        assertTrue(pending.getErrorMessage().contains("canceledMarketProtection"));
        assertTrue(pending.getErrorMessage().contains("partial fill"));
        verify(liveOrderRepository).save(pending);
    }

    @Test
    void aPendingRowWhoseExchangeOrderCanStillFillIsLeftPending() {
        stubStrategyAndAccount();
        UUID clientOrderId = UUID.randomUUID();
        LiveOrder pending = pendingRow("BUY", clientOrderId, Instant.ofEpochMilli(START + MINUTE));
        when(liveOrderRepository.findByStrategyIdOrderByCreatedTimeDesc(STRATEGY_ID))
                .thenReturn(List.of(pending));
        when(bitvavoApiClient.get(ordersEndpoint(MARKET), OrderResponse[].class, KEY, SECRET))
                .thenReturn(new OrderResponse[]{order(UUID.randomUUID(), "buy",
                        "partiallyFilled", START + MINUTE, clientOrderId,
                        "0.0005", "50.00", null)});

        assertTrue(service.reconcileAll().isEmpty());

        assertEquals("PENDING", pending.getStatus());
        verify(liveOrderRepository, never()).save(any());
    }

    @Test
    void aNullHistoryLeavesThePendingRowPending() {
        stubStrategyAndAccount();
        LiveOrder pending = pendingRow("BUY", UUID.randomUUID(), Instant.ofEpochMilli(START + MINUTE));
        when(liveOrderRepository.findByStrategyIdOrderByCreatedTimeDesc(STRATEGY_ID)).thenReturn(List.of(pending));
        when(bitvavoApiClient.get(ordersEndpoint(MARKET), OrderResponse[].class, KEY, SECRET)).thenReturn(null);

        service.reconcileAll();

        assertEquals("PENDING", pending.getStatus());
        verify(liveOrderRepository, never()).save(any());
    }

    @Test
    void aDryRunRowWithoutAClientOrderIdIsIgnored() {
        stubStrategyAndAccount();
        LiveOrder dryRun = LiveOrder.builder()
                .strategyId(STRATEGY_ID)
                .market(MARKET)
                .side("BUY")
                .status("DRY_RUN")
                .dryRun(true)
                .createdTime(Instant.ofEpochMilli(START))
                .build();
        dryRun.setId("dry-run-1");
        when(liveOrderRepository.findByStrategyIdOrderByCreatedTimeDesc(STRATEGY_ID)).thenReturn(List.of(dryRun));
        when(bitvavoApiClient.get(ordersEndpoint(MARKET), OrderResponse[].class, KEY, SECRET))
                .thenReturn(new OrderResponse[0]);

        ListAppender<ILoggingEvent> warnings = attachWarnings();
        try {
            service.reconcileAll();
        } finally {
            detachWarnings(warnings);
        }

        assertEquals("DRY_RUN", dryRun.getStatus());
        verify(liveOrderRepository, never()).save(any());
        assertFalse(warnedAboutMissingClientOrderId(warnings));
    }

    @Test
    void aRejectedRowWithoutAClientOrderIdIsIgnored() {
        stubStrategyAndAccount();
        LiveOrder rejected = LiveOrder.builder()
                .strategyId(STRATEGY_ID)
                .market(MARKET)
                .side("BUY")
                .status("REJECTED")
                .dryRun(false)
                .createdTime(Instant.ofEpochMilli(START))
                .build();
        rejected.setId("rejected-1");
        when(liveOrderRepository.findByStrategyIdOrderByCreatedTimeDesc(STRATEGY_ID)).thenReturn(List.of(rejected));
        when(bitvavoApiClient.get(ordersEndpoint(MARKET), OrderResponse[].class, KEY, SECRET))
                .thenReturn(new OrderResponse[0]);

        ListAppender<ILoggingEvent> warnings = attachWarnings();
        try {
            service.reconcileAll();
        } finally {
            detachWarnings(warnings);
        }

        assertEquals("REJECTED", rejected.getStatus());
        verify(liveOrderRepository, never()).save(any());
        assertFalse(warnedAboutMissingClientOrderId(warnings));
    }

    @Test
    void aFilledHistoryEntryWithoutFiguresKeepsThePreWriteEstimates() {
        stubStrategyAndAccount();
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
        when(credentialsService.get()).thenReturn(Optional.of(stored()));
        when(bitvavoApiClient.get(ordersEndpoint("BTC-EUR"), OrderResponse[].class, KEY, SECRET))
                .thenThrow(new ResourceAccessException("connect timed out"));
        when(bitvavoApiClient.get(ordersEndpoint("ETH-EUR"), OrderResponse[].class, KEY, SECRET))
                .thenReturn(new OrderResponse[0]);

        Set<String> failed = service.reconcileAll();

        assertEquals(Set.of(STRATEGY_ID), failed);
        verify(liveOrderRepository).findByStrategyIdOrderByCreatedTimeDesc("strategy-2");
    }

    @Test
    void aLostBuyIsRecoveredFromTheExchangeAndOpensThePosition() {
        LiveStrategy strategy = strategy(false, MARKET);
        strategy.setNextOrderSequence(3);
        when(liveStrategyService.all()).thenReturn(List.of(strategy));
        when(credentialsService.get()).thenReturn(Optional.of(stored()));
        UUID lostClientOrderId = ClientOrderIds.forSequence(STRATEGY_ID, 3);
        UUID orderId = UUID.randomUUID();
        when(bitvavoApiClient.get(ordersEndpoint(MARKET), OrderResponse[].class, KEY, SECRET))
                .thenReturn(new OrderResponse[]{order(orderId, "buy", "filled", START + HOUR,
                        lostClientOrderId, "0.01", "620.00", "1.24")});
        List<LiveOrder> stored = new ArrayList<>();
        when(liveOrderRepository.findByStrategyIdOrderByCreatedTimeDesc(STRATEGY_ID))
                .thenAnswer(invocation -> new ArrayList<>(stored));
        when(liveOrderRepository.save(any(LiveOrder.class))).thenAnswer(invocation -> {
            LiveOrder row = invocation.getArgument(0);
            stored.add(row);
            return row;
        });
        when(livePositionRepository.findByStrategyIdAndStatus(STRATEGY_ID, "OPEN"))
                .thenReturn(Optional.empty());

        service.reconcileAll();

        ArgumentCaptor<LiveOrder> saved = ArgumentCaptor.forClass(LiveOrder.class);
        verify(liveOrderRepository).save(saved.capture());
        LiveOrder recovered = saved.getValue();
        assertEquals("FILLED", recovered.getStatus());
        assertEquals("BUY", recovered.getSide());
        assertEquals(lostClientOrderId.toString(), recovered.getClientOrderId());
        assertEquals(orderId.toString(), recovered.getOrderId());
        assertEquals(0.01, recovered.getQuantity(), 1e-9);
        assertEquals(62000.0, recovered.getPrice(), 1e-9);
        assertEquals(1.24, recovered.getFee(), 1e-9);
        assertTrue(recovered.getReason().contains("recovered"));
        verify(liveStrategyRepository).updateNextOrderSequence(STRATEGY_ID, 4);
        ArgumentCaptor<LivePosition> opened = ArgumentCaptor.forClass(LivePosition.class);
        verify(livePositionRepository).save(opened.capture());
        assertEquals("OPEN", opened.getValue().getStatus());
        assertEquals(0.01, opened.getValue().getQuantity(), 1e-9);
        assertEquals(62000.0, opened.getValue().getEntryPrice(), 1e-9);
    }

    @Test
    void aLostSellIsRecoveredFromTheExchangeAndClosesThePosition() {
        LiveStrategy strategy = strategy(false, MARKET);
        strategy.setNextOrderSequence(1);
        when(liveStrategyService.all()).thenReturn(List.of(strategy));
        when(credentialsService.get()).thenReturn(Optional.of(stored()));
        UUID lostClientOrderId = ClientOrderIds.forSequence(STRATEGY_ID, 1);
        UUID orderId = UUID.randomUUID();
        when(bitvavoApiClient.get(ordersEndpoint(MARKET), OrderResponse[].class, KEY, SECRET))
                .thenReturn(new OrderResponse[]{order(orderId, "sell", "filled", START + 2 * HOUR,
                        lostClientOrderId, "0.01", "630.00", "1.26")});
        LiveOrder buy = LiveOrder.builder()
                .strategyId(STRATEGY_ID)
                .market(MARKET)
                .side("BUY")
                .status("FILLED")
                .dryRun(false)
                .signalOpenTime(START)
                .reason("buy ALL [TEST]")
                .amountQuote(620.0)
                .quantity(0.01)
                .price(62000.0)
                .fee(1.24)
                .clientOrderId(UUID.randomUUID().toString())
                .createdTime(Instant.ofEpochMilli(START))
                .build();
        buy.setId("buy-1");
        List<LiveOrder> stored = new ArrayList<>(List.of(buy));
        when(liveOrderRepository.findByStrategyIdOrderByCreatedTimeDesc(STRATEGY_ID))
                .thenAnswer(invocation -> new ArrayList<>(stored));
        when(liveOrderRepository.save(any(LiveOrder.class))).thenAnswer(invocation -> {
            LiveOrder row = invocation.getArgument(0);
            stored.add(row);
            return row;
        });
        LivePosition position = LivePosition.builder()
                .strategyId(STRATEGY_ID)
                .market(MARKET)
                .status("OPEN")
                .quantity(0.01)
                .entryPrice(62000.0)
                .entryFee(1.24)
                .entryTime(START)
                .dryRun(false)
                .createdTime(Instant.ofEpochMilli(START))
                .build();
        when(livePositionRepository.findByStrategyIdAndStatus(STRATEGY_ID, "OPEN"))
                .thenReturn(Optional.of(position));

        service.reconcileAll();

        verify(liveOrderRepository).save(any(LiveOrder.class));
        verify(liveStrategyRepository).updateNextOrderSequence(STRATEGY_ID, 2);
        verify(livePositionRepository).save(position);
        assertEquals("CLOSED", position.getStatus());
        assertEquals(63000.0, position.getExitPrice(), 1e-9);
        assertEquals(7.5, position.getProfit(), 1e-9);
    }

    @Test
    void aRecoveredOrderStillOpenIsPersistedAsPending() {
        LiveStrategy strategy = strategy(false, MARKET);
        strategy.setNextOrderSequence(3);
        when(liveStrategyService.all()).thenReturn(List.of(strategy));
        when(credentialsService.get()).thenReturn(Optional.of(stored()));
        UUID lostClientOrderId = ClientOrderIds.forSequence(STRATEGY_ID, 3);
        when(bitvavoApiClient.get(ordersEndpoint(MARKET), OrderResponse[].class, KEY, SECRET))
                .thenReturn(new OrderResponse[]{order(UUID.randomUUID(), "buy", "open", START + HOUR,
                        lostClientOrderId, null, null, null)});
        List<LiveOrder> stored = new ArrayList<>();
        when(liveOrderRepository.findByStrategyIdOrderByCreatedTimeDesc(STRATEGY_ID))
                .thenAnswer(invocation -> new ArrayList<>(stored));
        when(liveOrderRepository.save(any(LiveOrder.class))).thenAnswer(invocation -> {
            LiveOrder row = invocation.getArgument(0);
            stored.add(row);
            return row;
        });
        when(livePositionRepository.findByStrategyIdAndStatus(STRATEGY_ID, "OPEN"))
                .thenReturn(Optional.empty());

        service.reconcileAll();

        ArgumentCaptor<LiveOrder> saved = ArgumentCaptor.forClass(LiveOrder.class);
        verify(liveOrderRepository).save(saved.capture());
        assertEquals("PENDING", saved.getValue().getStatus());
        assertEquals(lostClientOrderId.toString(), saved.getValue().getClientOrderId());
        assertTrue(saved.getValue().getReason().contains("recovered"));
        verify(liveStrategyRepository).updateNextOrderSequence(STRATEGY_ID, 4);
    }

    @Test
    void aTerminalMatchIsWarnedAndNotPersisted() {
        LiveStrategy strategy = strategy(false, MARKET);
        strategy.setNextOrderSequence(3);
        when(liveStrategyService.all()).thenReturn(List.of(strategy));
        when(credentialsService.get()).thenReturn(Optional.of(stored()));
        when(bitvavoApiClient.get(ordersEndpoint(MARKET), OrderResponse[].class, KEY, SECRET))
                .thenReturn(new OrderResponse[]{order(UUID.randomUUID(), "buy", "canceled", START + HOUR,
                        ClientOrderIds.forSequence(STRATEGY_ID, 3), "0.005", "310.00", "0.62")});
        when(liveOrderRepository.findByStrategyIdOrderByCreatedTimeDesc(STRATEGY_ID))
                .thenReturn(List.of());
        when(livePositionRepository.findByStrategyIdAndStatus(STRATEGY_ID, "OPEN"))
                .thenReturn(Optional.empty());

        service.reconcileAll();

        verify(liveOrderRepository, never()).save(any());
        verify(liveStrategyRepository).updateNextOrderSequence(STRATEGY_ID, 4);
    }

    @Test
    void anOrderWithAnUnrelatedClientOrderIdIsNotAdopted() {
        LiveStrategy strategy = strategy(false, MARKET);
        when(liveStrategyService.all()).thenReturn(List.of(strategy));
        when(credentialsService.get()).thenReturn(Optional.of(stored()));
        when(bitvavoApiClient.get(ordersEndpoint(MARKET), OrderResponse[].class, KEY, SECRET))
                .thenReturn(new OrderResponse[]{order(UUID.randomUUID(), "buy", "filled", START + HOUR,
                        UUID.randomUUID(), "0.01", "620.00", "1.24")});
        when(liveOrderRepository.findByStrategyIdOrderByCreatedTimeDesc(STRATEGY_ID))
                .thenReturn(List.of());
        when(livePositionRepository.findByStrategyIdAndStatus(STRATEGY_ID, "OPEN"))
                .thenReturn(Optional.empty());

        service.reconcileAll();

        verify(liveOrderRepository, never()).save(any());
        verifyNoInteractions(liveStrategyRepository);
    }

    @Test
    void recoveryIsIdempotent() {
        LiveStrategy strategy = strategy(false, MARKET);
        strategy.setNextOrderSequence(3);
        when(liveStrategyService.all()).thenReturn(List.of(strategy));
        when(credentialsService.get()).thenReturn(Optional.of(stored()));
        when(bitvavoApiClient.get(ordersEndpoint(MARKET), OrderResponse[].class, KEY, SECRET))
                .thenReturn(new OrderResponse[]{order(UUID.randomUUID(), "buy", "filled", START + HOUR,
                        ClientOrderIds.forSequence(STRATEGY_ID, 3), "0.01", "620.00", "1.24")});
        List<LiveOrder> stored = new ArrayList<>();
        when(liveOrderRepository.findByStrategyIdOrderByCreatedTimeDesc(STRATEGY_ID))
                .thenAnswer(invocation -> new ArrayList<>(stored));
        when(liveOrderRepository.save(any(LiveOrder.class))).thenAnswer(invocation -> {
            LiveOrder row = invocation.getArgument(0);
            stored.add(row);
            return row;
        });
        when(livePositionRepository.findByStrategyIdAndStatus(STRATEGY_ID, "OPEN"))
                .thenReturn(Optional.empty());

        service.reconcileAll();
        service.reconcileAll();

        verify(liveOrderRepository).save(any(LiveOrder.class));
        verify(liveStrategyRepository).updateNextOrderSequence(STRATEGY_ID, 4);
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
        stubStrategyAndAccount();
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
        stubStrategyAndAccount();
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
        stubStrategyAndAccount();
        when(liveOrderRepository.findByStrategyIdOrderByCreatedTimeDesc(STRATEGY_ID)).thenReturn(List.of());
        when(bitvavoApiClient.get(ordersEndpoint(MARKET), OrderResponse[].class, KEY, SECRET))
                .thenReturn(new OrderResponse[0]);
        stubOpenPosition(0.001, 100000.0, 0.2);

        service.reconcileAll();

        verify(livePositionRepository, never()).save(any());
    }

    @Test
    void aLegacyFilledBuyWithoutAmountsDoesNotOpenAPosition() {
        stubStrategyAndAccount();
        LiveOrder legacy = filledRow("BUY", null, UUID.randomUUID(), Instant.ofEpochMilli(START),
                null, null, null, null);
        when(liveOrderRepository.findByStrategyIdOrderByCreatedTimeDesc(STRATEGY_ID)).thenReturn(List.of(legacy));
        when(bitvavoApiClient.get(ordersEndpoint(MARKET), OrderResponse[].class, KEY, SECRET))
                .thenReturn(new OrderResponse[0]);

        service.reconcileAll();

        verify(livePositionRepository, never()).save(any());
    }

    private LiveOrder aFilledBuyThatMatchesItsOpenPosition() {
        stubStrategyAndAccount();
        UUID clientOrderId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        when(bitvavoApiClient.get(ordersEndpoint(MARKET), OrderResponse[].class, KEY, SECRET))
                .thenReturn(new OrderResponse[]{order(orderId, "buy", "filled", START, clientOrderId,
                        "0.001", "100.00", "0.2")});
        stubOpenPosition(0.001, 100000.0, 0.2);
        return filledRow("BUY", clientOrderId, orderId, Instant.ofEpochMilli(START), 0.001, 100.0, 100000.0, 0.2);
    }

    private static ListAppender<ILoggingEvent> attachWarnings() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        reconciliationLogger().addAppender(appender);
        return appender;
    }

    private static void detachWarnings(ListAppender<ILoggingEvent> appender) {
        reconciliationLogger().detachAppender(appender);
    }

    private static Logger reconciliationLogger() {
        return (Logger) LoggerFactory.getLogger(LiveOrderReconciliationService.class);
    }

    private static boolean warnedAboutMissingClientOrderId(ListAppender<ILoggingEvent> warnings) {
        return warnings.list.stream()
                .anyMatch(event -> event.getLevel() == Level.WARN
                        && event.getFormattedMessage().contains("no client order ID"));
    }

    private void stubStrategyAndAccount() {
        when(liveStrategyService.all()).thenReturn(List.of(strategy(false, MARKET)));
        when(credentialsService.get()).thenReturn(Optional.of(stored()));
    }

    private void stubOpenPosition(double quantity, double entryPrice, Double entryFee) {
        when(livePositionRepository.findByStrategyIdAndStatus(STRATEGY_ID, "OPEN"))
                .thenReturn(Optional.of(openPosition(quantity, entryPrice, entryFee)));
    }

    private static String ordersEndpoint(String market) {
        return "/orders?market=" + market + "&start=" + START
                + "&limit=" + LiveOrderReconciliationService.ORDER_HISTORY_LIMIT;
    }

    private static OrderResponse[] fullHistory() {
        OrderResponse[] history = new OrderResponse[LiveOrderReconciliationService.ORDER_HISTORY_LIMIT];
        for (int i = 0; i < history.length; i++) {
            history[i] = order(UUID.randomUUID(), "buy", "filled", START, UUID.randomUUID(),
                    "0.001", "100.00", "0.2");
        }
        return history;
    }

    private static LiveStrategy strategy(boolean dryRun, String market) {
        LiveStrategy strategy = LiveStrategy.builder()
                .market(market)
                .dryRun(dryRun)
                .enabled(true)
                .createdTime(Instant.ofEpochMilli(START))
                .build();
        strategy.setId(STRATEGY_ID);
        return strategy;
    }

    private static BitvavoCredentials stored() {
        return BitvavoCredentials.builder()
                .id(BitvavoCredentials.ID)
                .apiKey(KEY)
                .apiSecret(SECRET)
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
