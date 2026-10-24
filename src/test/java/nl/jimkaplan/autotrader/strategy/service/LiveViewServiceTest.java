package nl.jimkaplan.autotrader.strategy.service;

import nl.jimkaplan.autotrader.bitvavo.client.BitvavoApiClient;
import nl.jimkaplan.autotrader.bitvavo.model.GetBookResponse;
import nl.jimkaplan.autotrader.repository.LiveOrderRepository;
import nl.jimkaplan.autotrader.repository.LivePositionRepository;
import nl.jimkaplan.autotrader.strategy.controller.LiveCardView;
import nl.jimkaplan.autotrader.strategy.model.document.LiveOrder;
import nl.jimkaplan.autotrader.strategy.model.document.LivePosition;
import nl.jimkaplan.autotrader.strategy.model.document.LiveStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LiveViewServiceTest {

    private static final String STRATEGY_ID = "strategy-1";
    private static final long SIGNAL_ENTRY = Instant.parse("2026-10-14T05:44:00Z").toEpochMilli();
    private static final long SIGNAL_EXIT = Instant.parse("2026-10-14T05:45:00Z").toEpochMilli();

    @Mock
    private LiveStrategyService liveStrategyService;

    @Mock
    private LivePositionRepository livePositionRepository;

    @Mock
    private LiveOrderRepository liveOrderRepository;

    @Mock
    private BitvavoApiClient bitvavoApiClient;

    private LiveViewService service;

    @BeforeEach
    void setUp() {
        service = new LiveViewService(liveStrategyService, livePositionRepository, liveOrderRepository,
                bitvavoApiClient, "UTC");
    }

    @Test
    void cards_valueTheOpenPositionAtTheSellPriceAndMarkItsOrders() {
        LiveStrategy strategy = strategy();
        LivePosition open = openPosition();
        LivePosition closed = LivePosition.builder()
                .strategyId(STRATEGY_ID).market("BTC-EUR").status("CLOSED")
                .quantity(0.00135153).entryPrice(73_990).entryFee(0.20).entryTime(SIGNAL_ENTRY)
                .exitPrice(74_380.0).exitTime(SIGNAL_EXIT).profit(0.12).dryRun(true).build();
        when(liveStrategyService.active()).thenReturn(List.of(strategy));
        when(liveStrategyService.isRunning(STRATEGY_ID)).thenReturn(true);
        when(liveStrategyService.isConnected(STRATEGY_ID)).thenReturn(true);
        when(liveStrategyService.lastError(STRATEGY_ID)).thenReturn(null);
        when(livePositionRepository.findByStrategyIdAndStatus(STRATEGY_ID, "OPEN"))
                .thenReturn(Optional.of(open));
        when(livePositionRepository.findAllByStrategyIdAndStatus(STRATEGY_ID, "CLOSED"))
                .thenReturn(List.of(closed));
        when(bitvavoApiClient.getPublic(eq("/ticker/book?market=BTC-EUR"), eq(GetBookResponse.class)))
                .thenReturn(new GetBookResponse("BTC-EUR", new BigDecimal("74562"),
                        BigDecimal.ONE, new BigDecimal("74578"), BigDecimal.ONE));
        when(liveOrderRepository.findTop8ByStrategyIdOrderByCreatedTimeDesc(STRATEGY_ID)).thenReturn(List.of(
                order("BUY", SIGNAL_ENTRY, Instant.parse("2020-01-02T03:04:00Z"), 0.00134403, 74_403, "DRY_RUN"),
                order("SELL", SIGNAL_EXIT, Instant.parse("2020-01-02T03:05:00Z"), 0.00134403, 74_412, "DRY_RUN"),
                skippedOrder()));

        List<LiveCardView> cards = service.cards();

        assertEquals(1, cards.size());
        LiveCardView card = cards.get(0);
        assertEquals("BTC 1m", card.name());
        assertTrue(card.started());
        assertTrue(card.running());
        assertTrue(card.connected());
        assertFalse(card.parameters().isEmpty());
        assertTrue(card.parameters().startsWith("MACD 12/26/9 · SAR 0,02/0,2 · entry: MACD bullish crossover"));
        assertTrue(card.statusLine().contains("Running since 13 Oct 14:35 ("));
        assertTrue(card.statusLine().contains("Last evaluated 14 Oct 05:48:00 ("));
        assertTrue(card.statusLine().endsWith("Last error: —"));

        LiveCardView.PositionView position = card.position();
        assertEquals("LONG 0,00134403 BTC", position.quantityText());
        assertEquals("14 Oct 05:45 · €74.403,00", position.entryText());
        assertEquals("€74.578,00", position.buyText());
        assertEquals("€74.562,00", position.sellText());
        assertEquals("€100,21", position.valueNowText());
        assertEquals("−€0,19", position.ifClosedNowText());
        assertEquals("neg", position.ifClosedNowClass());
        assertEquals("€0,20", position.entryFeeText());

        assertEquals("Realised since start: 1 round trips · +€0,12 · 1 wins / 0 losses · avg win +€0,12",
                card.statsLine());

        List<LiveCardView.OrderView> orders = card.orders();
        assertEquals(3, orders.size());
        assertEquals("2 Jan 03:04", orders.get(0).timeText());
        assertEquals("BUY", orders.get(0).side());
        assertTrue(orders.get(0).openedPosition());
        assertEquals("DRY RUN", orders.get(0).statusBadge());
        assertEquals("—", orders.get(0).resultText());
        assertEquals("2 Jan 03:05", orders.get(1).timeText());
        assertEquals("+€0,12", orders.get(1).resultText());
        assertEquals("pos", orders.get(1).resultClass());
        assertFalse(orders.get(1).openedPosition());
        assertEquals("Skipped — candle unavailable", orders.get(2).statusBadge());
        assertEquals("", orders.get(2).statusBadgeClass());
    }

    @Test
    void cards_degradeToDashesWhenTheBookIsUnavailable() {
        when(liveStrategyService.active()).thenReturn(List.of(strategy()));
        when(liveStrategyService.isRunning(STRATEGY_ID)).thenReturn(true);
        when(liveStrategyService.isConnected(STRATEGY_ID)).thenReturn(true);
        when(liveStrategyService.lastError(STRATEGY_ID)).thenReturn("errorCode 205: Invalid market");
        when(livePositionRepository.findByStrategyIdAndStatus(STRATEGY_ID, "OPEN"))
                .thenReturn(Optional.of(openPosition()));
        when(livePositionRepository.findAllByStrategyIdAndStatus(STRATEGY_ID, "CLOSED")).thenReturn(List.of());
        when(bitvavoApiClient.getPublic(any(), eq(GetBookResponse.class)))
                .thenThrow(new RestClientException("connect timed out"));
        when(liveOrderRepository.findTop8ByStrategyIdOrderByCreatedTimeDesc(STRATEGY_ID)).thenReturn(List.of());

        LiveCardView card = service.cards().get(0);

        assertTrue(card.statusLine().endsWith("Last error: errorCode 205: Invalid market"));
        assertEquals("—", card.position().buyText());
        assertEquals("—", card.position().sellText());
        assertEquals("—", card.position().valueNowText());
        assertEquals("—", card.position().ifClosedNowText());
        assertEquals("", card.position().ifClosedNowClass());
        assertEquals("No completed round trips yet.", card.statsLine());
    }

    @Test
    void cards_showAStoppedStrategyWithoutARunner() {
        when(liveStrategyService.active()).thenReturn(List.of(strategy()));
        when(livePositionRepository.findAllByStrategyIdAndStatus(STRATEGY_ID, "CLOSED")).thenReturn(List.of());
        when(livePositionRepository.findByStrategyIdAndStatus(STRATEGY_ID, "OPEN")).thenReturn(Optional.empty());
        when(liveOrderRepository.findTop8ByStrategyIdOrderByCreatedTimeDesc(STRATEGY_ID)).thenReturn(List.of());

        LiveCardView card = service.cards().get(0);

        assertFalse(card.running());
        assertFalse(card.connected());
        assertNull(card.position());
        assertEquals("No completed round trips yet.", card.statsLine());
        assertTrue(card.statusLine().startsWith("Created 13 Oct 14:35"));
    }

    @Test
    void cards_markAStoppedStrategyWithClosedHistoryAsStarted() {
        LiveStrategy strategy = strategy();
        strategy.setLastStartedTime(null);
        strategy.setSeriesStartTime(0);
        strategy.setLastEvaluatedOpenTime(0);
        when(liveStrategyService.active()).thenReturn(List.of(strategy));
        when(livePositionRepository.findAllByStrategyIdAndStatus(STRATEGY_ID, "CLOSED"))
                .thenReturn(List.of(closedPosition(0.12)));
        when(livePositionRepository.findByStrategyIdAndStatus(STRATEGY_ID, "OPEN")).thenReturn(Optional.empty());
        when(liveOrderRepository.findTop8ByStrategyIdOrderByCreatedTimeDesc(STRATEGY_ID)).thenReturn(List.of());

        LiveCardView card = service.cards().get(0);

        assertFalse(strategy.hasStarted());
        assertTrue(card.started());
        assertEquals("Realised since start: 1 round trips · +€0,12 · 1 wins / 0 losses · avg win +€0,12",
                card.statsLine());
    }

    @Test
    void statsLine_reportsWinsLossesAndTheirAverages() {
        when(liveStrategyService.active()).thenReturn(List.of(strategy()));
        when(livePositionRepository.findAllByStrategyIdAndStatus(STRATEGY_ID, "CLOSED"))
                .thenReturn(List.of(closedPosition(0.12), closedPosition(-0.11)));
        when(livePositionRepository.findByStrategyIdAndStatus(STRATEGY_ID, "OPEN")).thenReturn(Optional.empty());
        when(liveOrderRepository.findTop8ByStrategyIdOrderByCreatedTimeDesc(STRATEGY_ID)).thenReturn(List.of());

        LiveCardView card = service.cards().get(0);

        assertEquals("Realised since start: 2 round trips · +€0,01 · 1 wins / 1 losses · avg win +€0,12 "
                + "· avg loss −€0,11", card.statsLine());
    }

    @Test
    void cards_markAStoppedStrategyWithAnOpenPositionAsStarted() {
        LiveStrategy strategy = strategy();
        strategy.setLastStartedTime(null);
        strategy.setSeriesStartTime(0);
        strategy.setLastEvaluatedOpenTime(0);
        when(liveStrategyService.active()).thenReturn(List.of(strategy));
        when(livePositionRepository.findAllByStrategyIdAndStatus(STRATEGY_ID, "CLOSED")).thenReturn(List.of());
        when(livePositionRepository.findByStrategyIdAndStatus(STRATEGY_ID, "OPEN"))
                .thenReturn(Optional.of(openPosition()));
        when(liveOrderRepository.findTop8ByStrategyIdOrderByCreatedTimeDesc(STRATEGY_ID)).thenReturn(List.of());

        LiveCardView card = service.cards().get(0);

        assertFalse(strategy.hasStarted());
        assertTrue(card.started());
        assertNotNull(card.position());
    }

    @Test
    void cards_markAStoppedStrategyWithOrdersAsStarted() {
        LiveStrategy strategy = strategy();
        strategy.setLastStartedTime(null);
        strategy.setSeriesStartTime(0);
        strategy.setLastEvaluatedOpenTime(0);
        when(liveStrategyService.active()).thenReturn(List.of(strategy));
        when(livePositionRepository.findAllByStrategyIdAndStatus(STRATEGY_ID, "CLOSED")).thenReturn(List.of());
        when(livePositionRepository.findByStrategyIdAndStatus(STRATEGY_ID, "OPEN")).thenReturn(Optional.empty());
        when(liveOrderRepository.findTop8ByStrategyIdOrderByCreatedTimeDesc(STRATEGY_ID))
                .thenReturn(List.of(order("BUY", SIGNAL_ENTRY, Instant.parse("2026-10-11T20:20:02Z"),
                        0.001, 74_403, "DRY_RUN")));

        LiveCardView card = service.cards().get(0);

        assertFalse(strategy.hasStarted());
        assertTrue(card.started());
    }

    @Test
    void cards_listRunningStrategiesFirst() {
        LiveStrategy stopped = strategy();
        stopped.setId("strategy-2");
        stopped.setCreatedTime(Instant.parse("2026-10-14T09:20:00Z"));
        when(liveStrategyService.active()).thenReturn(List.of(stopped, strategy()));
        when(liveStrategyService.isRunning(STRATEGY_ID)).thenReturn(true);
        when(livePositionRepository.findAllByStrategyIdAndStatus(any(), eq("CLOSED"))).thenReturn(List.of());
        when(livePositionRepository.findByStrategyIdAndStatus(any(), eq("OPEN"))).thenReturn(Optional.empty());
        when(liveOrderRepository.findTop8ByStrategyIdOrderByCreatedTimeDesc(any())).thenReturn(List.of());
        when(bitvavoApiClient.getPublic(any(), eq(GetBookResponse.class)))
                .thenThrow(new RestClientException("connect timed out"));

        List<LiveCardView> cards = service.cards();

        assertEquals(2, cards.size());
        assertEquals(STRATEGY_ID, cards.get(0).id());
        assertTrue(cards.get(0).running());
        assertEquals("strategy-2", cards.get(1).id());
        assertFalse(cards.get(1).running());
    }

    @Test
    void cards_fallBackToThePairAndIntervalAndMarkANeverStartedStrategy() {
        LiveStrategy strategy = strategy();
        strategy.setName(null);
        strategy.setLastStartedTime(null);
        strategy.setSeriesStartTime(0);
        strategy.setLastEvaluatedOpenTime(0);
        when(liveStrategyService.active()).thenReturn(List.of(strategy));
        when(livePositionRepository.findAllByStrategyIdAndStatus(STRATEGY_ID, "CLOSED")).thenReturn(List.of());
        when(livePositionRepository.findByStrategyIdAndStatus(STRATEGY_ID, "OPEN")).thenReturn(Optional.empty());
        when(liveOrderRepository.findTop8ByStrategyIdOrderByCreatedTimeDesc(STRATEGY_ID)).thenReturn(List.of());
        when(bitvavoApiClient.getPublic(any(), eq(GetBookResponse.class)))
                .thenThrow(new RestClientException("connect timed out"));

        LiveCardView card = service.cards().get(0);

        assertEquals("BTC-EUR 1m", card.name());
        assertFalse(card.started());
    }

    @Test
    void zoneLabel_namesTheDisplayZone() {
        assertEquals("UTC", service.zoneLabel());
        assertEquals("UTC", service.zoneId());
    }

    private static LivePosition closedPosition(double profit) {
        return LivePosition.builder()
                .strategyId(STRATEGY_ID).market("BTC-EUR").status("CLOSED")
                .quantity(0.00135153).entryPrice(73_990).entryFee(0.20).entryTime(SIGNAL_ENTRY)
                .exitPrice(74_380.0).exitTime(SIGNAL_EXIT).profit(profit).dryRun(true).build();
    }

    private static LiveStrategy strategy() {
        LiveStrategy strategy = LiveStrategy.builder()
                .name("BTC 1m")
                .market("BTC-EUR")
                .interval("1m")
                .macdFastPeriod(12).macdSlowPeriod(26).macdSignalPeriod(9)
                .sarAcceleration(0.02).sarMaximum(0.2)
                .entryConditions(List.of("MACD_BULLISH_CROSSOVER"))
                .exitConditions(List.of("SAR_BEARISH_CROSSOVER"))
                .amountPerBuy(100).warmUpCandles(60)
                .dryRun(true).enabled(true)
                .lastEvaluatedOpenTime(Instant.parse("2026-10-14T05:48:00Z").toEpochMilli())
                .createdTime(Instant.parse("2026-10-13T14:35:00Z"))
                .lastStartedTime(Instant.parse("2026-10-13T14:35:00Z"))
                .build();
        strategy.setId(STRATEGY_ID);
        return strategy;
    }

    private static LivePosition openPosition() {
        return LivePosition.builder()
                .strategyId(STRATEGY_ID).market("BTC-EUR").status("OPEN")
                .quantity(0.00134403).entryPrice(74_403).entryFee(0.20).entryTime(SIGNAL_ENTRY)
                .dryRun(true)
                .build();
    }

    private static LiveOrder order(String side, long signalOpenTime, Instant created, double quantity,
                                   double price, String status) {
        return LiveOrder.builder()
                .strategyId(STRATEGY_ID).market("BTC-EUR").side(side).status(status).dryRun(true)
                .signalOpenTime(signalOpenTime).quantity(quantity).price(price).fee(0.20)
                .createdTime(created)
                .build();
    }

    private static LiveOrder skippedOrder() {
        return LiveOrder.builder()
                .strategyId(STRATEGY_ID).market("BTC-EUR").side("SKIP").status("SKIPPED").dryRun(true)
                .signalOpenTime(Instant.parse("2026-10-14T05:48:00Z").toEpochMilli())
                .createdTime(Instant.parse("2026-10-14T05:48:00Z"))
                .build();
    }
}
