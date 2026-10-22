package nl.jimkaplan.autotrader.strategy.service;

import nl.jimkaplan.autotrader.model.document.BitvavoCredentials;
import nl.jimkaplan.autotrader.repository.LiveOrderRepository;
import nl.jimkaplan.autotrader.repository.LivePositionRepository;
import nl.jimkaplan.autotrader.repository.LiveStrategyRepository;
import nl.jimkaplan.autotrader.service.BitvavoCredentialsService;
import nl.jimkaplan.autotrader.strategy.backtest.BacktestDefaults;
import nl.jimkaplan.autotrader.strategy.controller.LiveCloseResult;
import nl.jimkaplan.autotrader.strategy.controller.LiveCloseView;
import nl.jimkaplan.autotrader.strategy.controller.LiveStrategyRequest;
import nl.jimkaplan.autotrader.strategy.controller.MacdParametersRequest;
import nl.jimkaplan.autotrader.strategy.controller.ReconcileView;
import nl.jimkaplan.autotrader.strategy.controller.RuleSetRequest;
import nl.jimkaplan.autotrader.strategy.controller.SarParametersRequest;
import nl.jimkaplan.autotrader.strategy.live.BitvavoCandleStream;
import nl.jimkaplan.autotrader.strategy.live.CandleStreamFactory;
import nl.jimkaplan.autotrader.strategy.model.document.LiveOrder;
import nl.jimkaplan.autotrader.strategy.model.document.LivePosition;
import nl.jimkaplan.autotrader.strategy.model.document.LiveStrategy;
import nl.jimkaplan.autotrader.strategy.rule.LogicalOperator;
import nl.jimkaplan.autotrader.strategy.rule.RuleCondition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LiveStrategyServiceTest {

    @Mock
    private LiveStrategyRepository liveStrategyRepository;

    @Mock
    private LiveOrderRepository liveOrderRepository;

    @Mock
    private LivePositionRepository livePositionRepository;

    @Mock
    private CandleHistoryService candleHistoryService;

    @Mock
    private LiveTradingService liveTradingService;

    @Mock
    private LiveCloseService liveCloseService;

    @Mock
    private LiveAlertService alerts;

    @Mock
    private BitvavoCredentialsService credentialsService;

    @Mock
    private CandleStreamFactory streamFactory;

    @Mock
    private BitvavoCandleStream stream;

    @InjectMocks
    private LiveStrategyService liveStrategyService;

    @Test
    void create_appliesTheDocumentedDefaults() {
        LiveStrategyRequest request = new LiveStrategyRequest();
        request.setName("BTC 1h");
        request.setMarket("BTCEUR");
        request.setInterval("1h");
        when(liveStrategyRepository.save(any(LiveStrategy.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        LiveStrategy stored = liveStrategyService.create(request);

        assertNotNull(stored.getCreatedTime());
        assertEquals("BTC 1h", stored.getName());
        assertEquals("BTC-EUR", stored.getMarket());
        assertEquals("1h", stored.getInterval());
        assertEquals(100.0, stored.getAmountPerBuy(), 1e-9);
        assertEquals(BacktestDefaults.WARM_UP_CANDLES, stored.getWarmUpCandles());
        assertTrue(stored.isDryRun());
        assertFalse(stored.isEnabled());
        assertFalse(stored.isArchived());
        assertEquals(0, stored.getLastEvaluatedOpenTime());
        assertEquals(12, stored.getMacdFastPeriod());
        assertEquals(26, stored.getMacdSlowPeriod());
        assertEquals(9, stored.getMacdSignalPeriod());
        assertEquals(0.02, stored.getSarAcceleration(), 1e-9);
        assertEquals(0.2, stored.getSarMaximum(), 1e-9);
        assertEquals("ALL", stored.getEntryOperator());
        assertEquals(List.of("MACD_BULLISH_CROSSOVER"), stored.getEntryConditions());
        assertEquals(List.of("SAR_BEARISH_CROSSOVER"), stored.getExitConditions());
        verify(liveStrategyRepository).save(any(LiveStrategy.class));
        verifyNoInteractions(credentialsService);
    }

    @Test
    void create_whenLive_storesTheCustomConfiguration() {
        LiveStrategyRequest request = new LiveStrategyRequest();
        request.setName("ETH 4h");
        request.setMarket("ETH-EUR");
        request.setInterval("4h");
        request.setAmountPerBuy(50.0);
        request.setWarmUpCandles(150);
        request.setDryRun(false);
        request.setMacdParameters(new MacdParametersRequest(3, 5, 2));
        request.setSarParameters(new SarParametersRequest(0.05, 0.5));
        request.setEntryRules(new RuleSetRequest(LogicalOperator.ANY,
                List.of(RuleCondition.MACD_BULLISH_CROSSOVER, RuleCondition.PRICE_ABOVE_SAR)));
        request.setExitRules(new RuleSetRequest(LogicalOperator.ALL, List.of(RuleCondition.PRICE_BELOW_SAR)));
        when(credentialsService.configured()).thenReturn(true);
        when(liveStrategyRepository.save(any(LiveStrategy.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        LiveStrategy stored = liveStrategyService.create(request);

        assertEquals("ETH 4h", stored.getName());
        assertEquals("ETH-EUR", stored.getMarket());
        assertEquals("4h", stored.getInterval());
        assertEquals(50.0, stored.getAmountPerBuy(), 1e-9);
        assertEquals(150, stored.getWarmUpCandles());
        assertFalse(stored.isDryRun());
        assertEquals(3, stored.getMacdFastPeriod());
        assertEquals(5, stored.getMacdSlowPeriod());
        assertEquals(2, stored.getMacdSignalPeriod());
        assertEquals(0.05, stored.getSarAcceleration(), 1e-9);
        assertEquals(0.5, stored.getSarMaximum(), 1e-9);
        assertEquals("ANY", stored.getEntryOperator());
        assertEquals(List.of("MACD_BULLISH_CROSSOVER", "PRICE_ABOVE_SAR"), stored.getEntryConditions());
        assertEquals("ALL", stored.getExitOperator());
        assertEquals(List.of("PRICE_BELOW_SAR"), stored.getExitConditions());
    }

    @Test
    void create_rejectsInvalidRequests() {
        LiveStrategyRequest noName = request();
        noName.setName(" ");
        assertMessage("the name is required", noName);

        LiveStrategyRequest noMarket = request();
        noMarket.setMarket(" ");
        assertMessage("the market is required", noMarket);

        LiveStrategyRequest unknownInterval = request();
        unknownInterval.setInterval("1y");
        assertMessage("Unsupported interval: 1y", unknownInterval);

        LiveStrategyRequest nonEurMarket = request();
        nonEurMarket.setMarket("BTCUSDT");
        assertMessage("only EUR-quoted markets are supported: BTCUSDT", nonEurMarket);

        LiveStrategyRequest tinyAmount = request();
        tinyAmount.setAmountPerBuy(1.0);
        assertMessage("the amount per buy must be at least 5.0 EUR", tinyAmount);

        LiveStrategyRequest negativeWarmUp = request();
        negativeWarmUp.setWarmUpCandles(-1);
        assertMessage("the warm-up candles must not be negative", negativeWarmUp);

        LiveStrategyRequest noConditions = request();
        noConditions.setEntryRules(new RuleSetRequest(LogicalOperator.ALL, List.of()));
        assertMessage("select at least one entry condition", noConditions);

        LiveStrategyRequest liveWithoutKey = request();
        liveWithoutKey.setDryRun(false);
        assertMessage("no Bitvavo API key is configured: store it on the settings page", liveWithoutKey);
    }

    @Test
    void start_startsOneRunnerAndIsIdempotent() {
        LiveStrategy strategy = stored("strategy-1", "BTC-EUR");
        when(liveStrategyRepository.findById("strategy-1")).thenReturn(Optional.of(strategy));
        stubRunnerConstruction();

        liveStrategyService.start("strategy-1");
        liveStrategyService.start("strategy-1");

        assertTrue(strategy.isEnabled());
        assertTrue(liveStrategyService.isRunning("strategy-1"));
        verify(liveStrategyRepository).updateEnabled("strategy-1", true);
        verify(liveStrategyRepository).updateLastStartedTime(eq("strategy-1"), any());
        verify(streamFactory, times(1)).create(any(), any(), anyLong(), any());
        verify(stream).start();
        // Only targeted fields are written; a full save would clobber concurrent evaluation state.
        verify(liveStrategyRepository, never()).save(any());
    }

    @Test
    void start_reflectsTheStreamConnectionState() {
        LiveStrategy strategy = stored("strategy-1", "BTC-EUR");
        when(liveStrategyRepository.findById("strategy-1")).thenReturn(Optional.of(strategy));
        stubRunnerConstruction();
        when(stream.isConnected()).thenReturn(true);

        assertFalse(liveStrategyService.isConnected("strategy-1"));
        liveStrategyService.start("strategy-1");

        assertTrue(liveStrategyService.isConnected("strategy-1"));
    }

    @Test
    void stop_disablesTheStrategyAndStopsItsRunner() {
        LiveStrategy strategy = stored("strategy-1", "BTC-EUR");
        when(liveStrategyRepository.findById("strategy-1")).thenReturn(Optional.of(strategy));
        stubRunnerConstruction();
        liveStrategyService.start("strategy-1");

        LiveStrategy stopped = liveStrategyService.stop("strategy-1");

        assertFalse(stopped.isEnabled());
        assertFalse(liveStrategyService.isRunning("strategy-1"));
        verify(stream).stop();
        verify(liveStrategyRepository).updateEnabled("strategy-1", false);
    }

    @Test
    void unknownStrategiesAreRejected() {
        when(liveStrategyRepository.findById("missing")).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class, () -> liveStrategyService.start("missing"));
        assertThrows(NoSuchElementException.class, () -> liveStrategyService.stop("missing"));
        assertThrows(NoSuchElementException.class, () -> liveStrategyService.orders("missing"));
        assertThrows(NoSuchElementException.class,
                () -> liveStrategyService.update("missing", request()));
        assertThrows(NoSuchElementException.class, () -> liveStrategyService.delete("missing"));
    }

    @Test
    void orders_areReadForAnExistingStrategy() {
        when(liveStrategyRepository.findById("strategy-1")).thenReturn(Optional.of(stored("strategy-1", "BTC-EUR")));
        List<LiveOrder> orders = List.of(LiveOrder.builder().strategyId("strategy-1").build());
        when(liveOrderRepository.findByStrategyIdOrderByCreatedTimeDesc("strategy-1")).thenReturn(orders);

        assertEquals(orders, liveStrategyService.orders("strategy-1"));
    }

    @Test
    void all_returnsEveryStrategy() {
        when(liveStrategyRepository.findAll()).thenReturn(List.of(stored("strategy-1", "BTC-EUR")));

        assertEquals(1, liveStrategyService.all().size());
    }

    @Test
    void get_returnsTheStoredStrategy() {
        LiveStrategy strategy = stored("strategy-1", "BTC-EUR");
        when(liveStrategyRepository.findById("strategy-1")).thenReturn(Optional.of(strategy));

        assertEquals(Optional.of(strategy), liveStrategyService.get("strategy-1"));
    }

    @Test
    void update_changesTheEditableFields() {
        LiveStrategy strategy = stored("strategy-1", "BTC-EUR");
        when(liveStrategyRepository.findById("strategy-1")).thenReturn(Optional.of(strategy));
        when(liveStrategyRepository.save(any(LiveStrategy.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        LiveStrategyRequest request = request();
        request.setName("Renamed");
        request.setAmountPerBuy(250.0);
        request.setWarmUpCandles(10);
        request.setMacdParameters(new MacdParametersRequest(3, 5, 2));
        request.setSarParameters(new SarParametersRequest(0.01, 0.1));
        request.setEntryRules(new RuleSetRequest(LogicalOperator.ANY,
                List.of(RuleCondition.PRICE_ABOVE_SAR)));
        request.setExitRules(new RuleSetRequest(LogicalOperator.ALL,
                List.of(RuleCondition.PRICE_BELOW_SAR)));

        LiveStrategy updated = liveStrategyService.update("strategy-1", request);

        assertEquals("Renamed", updated.getName());
        assertEquals(250.0, updated.getAmountPerBuy(), 1e-9);
        assertEquals(10, updated.getWarmUpCandles());
        assertEquals(3, updated.getMacdFastPeriod());
        assertEquals(5, updated.getMacdSlowPeriod());
        assertEquals(2, updated.getMacdSignalPeriod());
        assertEquals(0.01, updated.getSarAcceleration(), 1e-9);
        assertEquals(0.1, updated.getSarMaximum(), 1e-9);
        assertEquals("ANY", updated.getEntryOperator());
        assertEquals(List.of("PRICE_ABOVE_SAR"), updated.getEntryConditions());
        assertEquals("ALL", updated.getExitOperator());
        assertEquals(List.of("PRICE_BELOW_SAR"), updated.getExitConditions());
        verify(liveStrategyRepository).save(strategy);
    }

    @Test
    void update_allowsThePairAndIntervalBeforeTheFirstStartButNotAfter() {
        LiveStrategy strategy = stored("strategy-1", "BTC-EUR");
        when(liveStrategyRepository.findById("strategy-1")).thenReturn(Optional.of(strategy));
        when(liveStrategyRepository.save(any(LiveStrategy.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        LiveStrategyRequest before = request();
        before.setMarket("ETH-EUR");
        before.setInterval("4h");
        liveStrategyService.update("strategy-1", before);

        assertEquals("ETH-EUR", strategy.getMarket());
        assertEquals("4h", strategy.getInterval());

        strategy.setLastStartedTime(Instant.now());
        LiveStrategyRequest market = request();
        market.setMarket("SOL-EUR");
        IllegalArgumentException marketError = assertThrows(IllegalArgumentException.class,
                () -> liveStrategyService.update("strategy-1", market));
        assertEquals("the market is fixed after the first start; duplicate the strategy to trade "
                + "another market", marketError.getMessage());

        LiveStrategyRequest interval = request();
        interval.setMarket(null);
        interval.setInterval("1d");
        IllegalArgumentException intervalError = assertThrows(IllegalArgumentException.class,
                () -> liveStrategyService.update("strategy-1", interval));
        assertEquals("the interval is fixed after the first start; duplicate the strategy to use "
                + "another interval", intervalError.getMessage());
    }

    @Test
    void update_keepsTheStoredNameWhenTheRequestOmitsIt() {
        LiveStrategy strategy = stored("strategy-1", "BTC-EUR");
        when(liveStrategyRepository.findById("strategy-1")).thenReturn(Optional.of(strategy));
        when(liveStrategyRepository.save(any(LiveStrategy.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        LiveStrategyRequest request = request();
        request.setName(null);

        LiveStrategy updated = liveStrategyService.update("strategy-1", request);

        assertEquals("Strategy strategy-1", updated.getName());
    }

    @Test
    void update_rejectsAChangedDryRunFlag() {
        LiveStrategy strategy = stored("strategy-1", "BTC-EUR");
        when(liveStrategyRepository.findById("strategy-1")).thenReturn(Optional.of(strategy));
        LiveStrategyRequest request = request();
        request.setDryRun(false);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> liveStrategyService.update("strategy-1", request));

        assertEquals("dry-run is chosen when the strategy is created; create a real copy instead",
                error.getMessage());
    }

    @Test
    void update_rejectsARunningStrategy() {
        LiveStrategy strategy = stored("strategy-1", "BTC-EUR");
        when(liveStrategyRepository.findById("strategy-1")).thenReturn(Optional.of(strategy));
        stubRunnerConstruction();
        liveStrategyService.start("strategy-1");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> liveStrategyService.update("strategy-1", request()));

        assertEquals("stop the strategy before editing it", error.getMessage());
    }

    @Test
    void delete_removesAStrategyThatNeverStarted() {
        LiveStrategy strategy = stored("strategy-1", "BTC-EUR");
        when(liveStrategyRepository.findById("strategy-1")).thenReturn(Optional.of(strategy));

        boolean archived = liveStrategyService.delete("strategy-1");

        assertFalse(archived);
        verify(liveStrategyRepository).delete(strategy);
        verify(liveStrategyRepository, never()).save(any());
    }

    @Test
    void delete_archivesAStrategyThatHasRun() {
        LiveStrategy strategy = stored("strategy-1", "BTC-EUR");
        strategy.setLastStartedTime(Instant.now());
        when(liveStrategyRepository.findById("strategy-1")).thenReturn(Optional.of(strategy));
        when(liveStrategyRepository.save(any(LiveStrategy.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        boolean archived = liveStrategyService.delete("strategy-1");

        assertTrue(archived);
        assertTrue(strategy.isArchived());
        verify(liveStrategyRepository).save(strategy);
        verify(liveStrategyRepository, never()).delete(any());
    }

    @Test
    void delete_refusesToArchiveAnOpenPosition() {
        LiveStrategy strategy = stored("strategy-1", "BTC-EUR");
        strategy.setLastStartedTime(Instant.now());
        when(liveStrategyRepository.findById("strategy-1")).thenReturn(Optional.of(strategy));
        when(livePositionRepository.findByStrategyIdAndStatus("strategy-1", "OPEN"))
                .thenReturn(Optional.of(LivePosition.builder()
                        .strategyId("strategy-1").market("BTC-EUR").status("OPEN").build()));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> liveStrategyService.delete("strategy-1"));

        assertEquals("close the open position before archiving", error.getMessage());
        assertFalse(strategy.isArchived());
        verify(liveStrategyRepository, never()).delete(any());
        verify(liveStrategyRepository, never()).save(any());
    }

    @Test
    void delete_rejectsARunningStrategy() {
        LiveStrategy strategy = stored("strategy-1", "BTC-EUR");
        when(liveStrategyRepository.findById("strategy-1")).thenReturn(Optional.of(strategy));
        stubRunnerConstruction();
        liveStrategyService.start("strategy-1");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> liveStrategyService.delete("strategy-1"));

        assertEquals("stop the strategy before deleting it", error.getMessage());
    }

    @Test
    void active_returnsTheNonArchivedStrategies() {
        when(liveStrategyRepository.findActive()).thenReturn(List.of(stored("strategy-1", "BTC-EUR")));

        assertEquals(1, liveStrategyService.active().size());
    }

    @Test
    void start_rejectsAnArchivedStrategy() {
        LiveStrategy strategy = stored("strategy-1", "BTC-EUR");
        strategy.setArchived(true);
        when(liveStrategyRepository.findById("strategy-1")).thenReturn(Optional.of(strategy));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> liveStrategyService.start("strategy-1"));

        assertEquals("the strategy is archived", error.getMessage());
        assertFalse(liveStrategyService.isRunning("strategy-1"));
    }

    @Test
    void lastError_reflectsTheRunningStream() {
        LiveStrategy strategy = stored("strategy-1", "BTC-EUR");
        when(liveStrategyRepository.findById("strategy-1")).thenReturn(Optional.of(strategy));
        stubRunnerConstruction();
        when(stream.getLastError()).thenReturn("errorCode 205: Invalid market");

        assertNull(liveStrategyService.lastError("strategy-1"));

        liveStrategyService.start("strategy-1");

        assertEquals("errorCode 205: Invalid market", liveStrategyService.lastError("strategy-1"));
    }

    @Test
    void startEnabled_startsWhatIsEnabledAndSurvivesFailures() {
        LiveStrategy good = stored("strategy-1", "BTC-EUR");
        LiveStrategy bad = stored("strategy-2", "ETH-EUR");
        when(liveStrategyRepository.findActiveEnabled()).thenReturn(List.of(good, bad));
        when(liveStrategyRepository.findById("strategy-1")).thenReturn(Optional.of(good));
        when(liveStrategyRepository.findById("strategy-2")).thenReturn(Optional.of(bad));
        when(candleHistoryService.getCandles(any(), any(), any(), any())).thenReturn(List.of());
        when(liveStrategyRepository.updateSeriesStartTime(any(), anyLong())).thenReturn(1L);
        when(streamFactory.create(eq("BTC-EUR"), any(), anyLong(), any())).thenReturn(stream);
        when(streamFactory.create(eq("ETH-EUR"), any(), anyLong(), any()))
                .thenThrow(new RuntimeException("no stream"));

        liveStrategyService.startEnabled(Set.of());

        assertTrue(liveStrategyService.isRunning("strategy-1"));
        assertFalse(liveStrategyService.isRunning("strategy-2"));
        verify(liveStrategyRepository).updateEnabled("strategy-1", true);
        verify(liveStrategyRepository, never()).updateEnabled("strategy-2", true);
    }

    @Test
    void closePreviewAndCloseDelegateForAStoppedOpenPosition() {
        LiveStrategy strategy = stored("strategy-1", "BTC-EUR");
        LivePosition position = openPosition();
        when(liveStrategyRepository.findById("strategy-1")).thenReturn(Optional.of(strategy));
        when(livePositionRepository.findByStrategyIdAndStatus("strategy-1", "OPEN"))
                .thenReturn(Optional.of(position));
        LiveCloseView view = closeView();
        LiveCloseResult result = new LiveCloseResult(true, "FILLED", "order-1", "closed");
        when(liveCloseService.preview(strategy, position)).thenReturn(view);
        when(liveCloseService.close(strategy)).thenReturn(result);

        assertEquals(view, liveStrategyService.closePreview("strategy-1"));
        assertEquals(result, liveStrategyService.close("strategy-1"));
    }

    @Test
    void closeRejectsARunningArchivedAwaitingOrFlatStrategy() {
        LiveStrategy strategy = stored("strategy-1", "BTC-EUR");
        when(liveStrategyRepository.findById("strategy-1")).thenReturn(Optional.of(strategy));
        stubRunnerConstruction();
        liveStrategyService.start("strategy-1");
        IllegalArgumentException running = assertThrows(IllegalArgumentException.class,
                () -> liveStrategyService.closePreview("strategy-1"));
        assertEquals("stop the strategy before closing its position", running.getMessage());
        liveStrategyService.stop("strategy-1");

        strategy.setArchived(true);
        IllegalArgumentException archived = assertThrows(IllegalArgumentException.class,
                () -> liveStrategyService.close("strategy-1"));
        assertEquals("the strategy is archived", archived.getMessage());
        strategy.setArchived(false);

        when(liveOrderRepository.existsByStrategyIdAndAwaitingReconciliationTrue("strategy-1"))
                .thenReturn(true);
        IllegalArgumentException awaiting = assertThrows(IllegalArgumentException.class,
                () -> liveStrategyService.close("strategy-1"));
        assertTrue(awaiting.getMessage().contains("awaiting reconciliation"));

        when(liveOrderRepository.existsByStrategyIdAndAwaitingReconciliationTrue("strategy-1"))
                .thenReturn(false);
        when(livePositionRepository.findByStrategyIdAndStatus("strategy-1", "OPEN"))
                .thenReturn(Optional.empty());
        IllegalArgumentException flat = assertThrows(IllegalArgumentException.class,
                () -> liveStrategyService.close("strategy-1"));
        assertEquals("there is no open position to close", flat.getMessage());
    }

    @Test
    void reconcileRejectsADryRunPositionAndDelegatesForARealOne() {
        LiveStrategy strategy = stored("strategy-1", "BTC-EUR");
        LivePosition position = openPosition();
        when(liveStrategyRepository.findById("strategy-1")).thenReturn(Optional.of(strategy));
        when(livePositionRepository.findByStrategyIdAndStatus("strategy-1", "OPEN"))
                .thenReturn(Optional.of(position));

        IllegalArgumentException dry = assertThrows(IllegalArgumentException.class,
                () -> liveStrategyService.reconcileView("strategy-1"));
        assertTrue(dry.getMessage().contains("dry-run position"));

        strategy.setDryRun(false);
        ReconcileView view = new ReconcileView("strategy-1", "Strategy strategy-1", "BTC-EUR", "1h",
                false, "LONG 0,001 BTC", "entry", "fee", List.of(), null);
        LiveOrder row = LiveOrder.builder().strategyId("strategy-1").external(true).build();
        when(liveCloseService.reconcileView(strategy, position)).thenReturn(view);
        when(liveCloseService.reconcile(strategy, position, "order-1")).thenReturn(row);

        assertEquals(view, liveStrategyService.reconcileView("strategy-1"));
        assertEquals(row, liveStrategyService.reconcileExternalClose("strategy-1", "order-1"));
    }

    @Test
    void startEnabled_holdsBackStrategiesWhoseReconciliationFailed() {
        LiveStrategy good = stored("strategy-1", "BTC-EUR");
        LiveStrategy held = stored("strategy-2", "ETH-EUR");
        when(liveStrategyRepository.findActiveEnabled()).thenReturn(List.of(good, held));
        when(liveStrategyRepository.findById("strategy-1")).thenReturn(Optional.of(good));
        stubRunnerConstruction();

        liveStrategyService.startEnabled(Set.of("strategy-2"));

        assertTrue(liveStrategyService.isRunning("strategy-1"));
        assertFalse(liveStrategyService.isRunning("strategy-2"));
        verify(liveStrategyRepository).updateEnabled("strategy-1", true);
        verify(liveStrategyRepository, never()).findById("strategy-2");
        verify(liveStrategyRepository, never()).updateEnabled("strategy-2", true);
        verify(alerts).reconciliationHeldBack(held);
    }

    @Test
    void closeRefusesWhenAFilledSellWasNotRecordedInThePosition() {
        LiveStrategy strategy = stored("strategy-1", "BTC-EUR");
        strategy.setDryRun(false);
        LivePosition position = openPosition();
        when(liveStrategyRepository.findById("strategy-1")).thenReturn(Optional.of(strategy));
        when(livePositionRepository.findByStrategyIdAndStatus("strategy-1", "OPEN"))
                .thenReturn(Optional.of(position));
        when(liveOrderRepository
                .existsByStrategyIdAndDryRunFalseAndSideAndStatusAndSignalOpenTimeGreaterThanEqual(
                        "strategy-1", "SELL", "FILLED", position.getEntryTime()))
                .thenReturn(true);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> liveStrategyService.closePreview("strategy-1"));

        assertTrue(error.getMessage().contains("position record is not updated"));
    }

    @Test
    void reconcileExplainsRunningAwaitingAndFlat() {
        LiveStrategy strategy = stored("strategy-1", "BTC-EUR");
        when(liveStrategyRepository.findById("strategy-1")).thenReturn(Optional.of(strategy));
        stubRunnerConstruction();
        liveStrategyService.start("strategy-1");
        IllegalArgumentException running = assertThrows(IllegalArgumentException.class,
                () -> liveStrategyService.reconcileView("strategy-1"));
        assertEquals("stop the strategy before reconciling a close", running.getMessage());
        liveStrategyService.stop("strategy-1");

        when(liveOrderRepository.existsByStrategyIdAndAwaitingReconciliationTrue("strategy-1"))
                .thenReturn(true);
        IllegalArgumentException awaiting = assertThrows(IllegalArgumentException.class,
                () -> liveStrategyService.reconcileView("strategy-1"));
        assertTrue(awaiting.getMessage().contains("before reconciling"));

        when(liveOrderRepository.existsByStrategyIdAndAwaitingReconciliationTrue("strategy-1"))
                .thenReturn(false);
        when(livePositionRepository.findByStrategyIdAndStatus("strategy-1", "OPEN"))
                .thenReturn(Optional.empty());
        IllegalArgumentException flat = assertThrows(IllegalArgumentException.class,
                () -> liveStrategyService.reconcileView("strategy-1"));
        assertEquals("there is no open position to reconcile", flat.getMessage());
    }

    @Test
    void closeAndReconcileRejectAnUnknownStrategy() {
        when(liveStrategyRepository.findById("missing")).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class, () -> liveStrategyService.closePreview("missing"));
        assertThrows(NoSuchElementException.class, () -> liveStrategyService.close("missing"));
        assertThrows(NoSuchElementException.class, () -> liveStrategyService.reconcileView("missing"));
        assertThrows(NoSuchElementException.class,
                () -> liveStrategyService.reconcileExternalClose("missing", "order-1"));
    }

    private static LivePosition openPosition() {
        return LivePosition.builder()
                .strategyId("strategy-1").market("BTC-EUR").status("OPEN")
                .quantity(0.001).entryPrice(73_000).entryFee(0.2)
                .entryTime(Instant.parse("2026-10-20T14:00:00Z").toEpochMilli())
                .dryRun(false)
                .build();
    }

    private static LiveCloseView closeView() {
        return new LiveCloseView("strategy-1", "Strategy strategy-1", "BTC-EUR", "1h", false, true,
                "LONG 0,001 BTC", "entry", "fee", "bid", "proceeds", "fee", "profit", "pos",
                "warning");
    }

    private void stubRunnerConstruction() {
        when(candleHistoryService.getCandles(any(), any(), any(), any())).thenReturn(List.of());
        when(streamFactory.create(any(), any(), anyLong(), any())).thenReturn(stream);
        when(liveStrategyRepository.updateSeriesStartTime(any(), anyLong())).thenReturn(1L);
    }

    private void assertMessage(String expected, LiveStrategyRequest request) {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> liveStrategyService.create(request));
        assertEquals(expected, exception.getMessage());
    }

    private static LiveStrategyRequest request() {
        LiveStrategyRequest request = new LiveStrategyRequest();
        request.setName("BTC 1h");
        request.setMarket("BTC-EUR");
        request.setInterval("1h");
        return request;
    }

    private static LiveStrategy stored(String id, String market) {
        LiveStrategy strategy = LiveStrategy.builder()
                .name("Strategy " + id)
                .market(market)
                .interval("1h")
                .macdFastPeriod(12)
                .macdSlowPeriod(26)
                .macdSignalPeriod(9)
                .sarAcceleration(0.02)
                .sarMaximum(0.2)
                .entryOperator("ALL")
                .entryConditions(List.of("MACD_BULLISH_CROSSOVER"))
                .exitOperator("ALL")
                .exitConditions(List.of("SAR_BEARISH_CROSSOVER"))
                .amountPerBuy(100)
                .warmUpCandles(0)
                .dryRun(true)
                .enabled(false)
                .build();
        strategy.setId(id);
        return strategy;
    }
}
