package nl.jimkaplan.autotrader.strategy.service;

import nl.jimkaplan.autotrader.model.document.BotConfiguration;
import nl.jimkaplan.autotrader.repository.LiveOrderRepository;
import nl.jimkaplan.autotrader.repository.LiveStrategyRepository;
import nl.jimkaplan.autotrader.service.BotConfigurationService;
import nl.jimkaplan.autotrader.strategy.backtest.BacktestDefaults;
import nl.jimkaplan.autotrader.strategy.controller.LiveStrategyRequest;
import nl.jimkaplan.autotrader.strategy.controller.MacdParametersRequest;
import nl.jimkaplan.autotrader.strategy.controller.RuleSetRequest;
import nl.jimkaplan.autotrader.strategy.controller.SarParametersRequest;
import nl.jimkaplan.autotrader.strategy.live.BitvavoCandleStream;
import nl.jimkaplan.autotrader.strategy.live.CandleStreamFactory;
import nl.jimkaplan.autotrader.strategy.model.document.LiveOrder;
import nl.jimkaplan.autotrader.strategy.model.document.LiveStrategy;
import nl.jimkaplan.autotrader.strategy.rule.LogicalOperator;
import nl.jimkaplan.autotrader.strategy.rule.RuleCondition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

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
    private CandleHistoryService candleHistoryService;

    @Mock
    private LiveTradingService liveTradingService;

    @Mock
    private BotConfigurationService botConfigurationService;

    @Mock
    private CandleStreamFactory streamFactory;

    @Mock
    private BitvavoCandleStream stream;

    @InjectMocks
    private LiveStrategyService liveStrategyService;

    @Test
    void create_appliesTheDocumentedDefaults() {
        LiveStrategyRequest request = new LiveStrategyRequest();
        request.setMarket("BTCEUR");
        request.setInterval("1h");
        when(liveStrategyRepository.save(any(LiveStrategy.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        LiveStrategy stored = liveStrategyService.create(request);

        assertNotNull(stored.getCreatedTime());
        assertEquals("BTC-EUR", stored.getMarket());
        assertEquals("1h", stored.getInterval());
        assertEquals(100.0, stored.getAmountPerBuy(), 1e-9);
        assertEquals(BacktestDefaults.WARM_UP_CANDLES, stored.getWarmUpCandles());
        assertTrue(stored.isDryRun());
        assertFalse(stored.isEnabled());
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
        verifyNoInteractions(botConfigurationService);
    }

    @Test
    void create_whenLive_storesTheCustomConfiguration() {
        LiveStrategyRequest request = new LiveStrategyRequest();
        request.setBotId("bot-1");
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
        when(botConfigurationService.getBotConfiguration("bot-1"))
                .thenReturn(Optional.of(BotConfiguration.builder().botId("bot-1").build()));
        when(liveStrategyRepository.save(any(LiveStrategy.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        LiveStrategy stored = liveStrategyService.create(request);

        assertEquals("bot-1", stored.getBotId());
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

        LiveStrategyRequest liveWithoutBot = request();
        liveWithoutBot.setDryRun(false);
        assertMessage("the botId is required", liveWithoutBot);

        LiveStrategyRequest liveWithUnknownBot = request();
        liveWithUnknownBot.setDryRun(false);
        liveWithUnknownBot.setBotId("bot-9");
        assertMessage("no active bot configuration: bot-9", liveWithUnknownBot);
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
        verify(streamFactory, times(1)).create(any(), any(), anyLong(), any());
        verify(stream).start();
        // Only the enabled flag is written; a full save would clobber concurrent evaluation state.
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
        when(liveStrategyRepository.findByEnabledTrue()).thenReturn(List.of(good, bad));
        when(liveStrategyRepository.findById("strategy-1")).thenReturn(Optional.of(good));
        when(liveStrategyRepository.findById("strategy-2")).thenReturn(Optional.of(bad));
        when(candleHistoryService.getCandles(any(), any(), any(), any())).thenReturn(List.of());
        when(streamFactory.create(eq("BTC-EUR"), any(), anyLong(), any())).thenReturn(stream);
        when(streamFactory.create(eq("ETH-EUR"), any(), anyLong(), any()))
                .thenThrow(new RuntimeException("no stream"));

        liveStrategyService.startEnabled();

        assertTrue(liveStrategyService.isRunning("strategy-1"));
        assertFalse(liveStrategyService.isRunning("strategy-2"));
        verify(liveStrategyRepository).updateEnabled("strategy-1", true);
        verify(liveStrategyRepository, never()).updateEnabled("strategy-2", true);
    }

    private void stubRunnerConstruction() {
        when(candleHistoryService.getCandles(any(), any(), any(), any())).thenReturn(List.of());
        when(streamFactory.create(any(), any(), anyLong(), any())).thenReturn(stream);
    }

    private void assertMessage(String expected, LiveStrategyRequest request) {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> liveStrategyService.create(request));
        assertEquals(expected, exception.getMessage());
    }

    private static LiveStrategyRequest request() {
        LiveStrategyRequest request = new LiveStrategyRequest();
        request.setMarket("BTC-EUR");
        request.setInterval("1h");
        return request;
    }

    private static LiveStrategy stored(String id, String market) {
        LiveStrategy strategy = LiveStrategy.builder()
                .botId("bot-1")
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
