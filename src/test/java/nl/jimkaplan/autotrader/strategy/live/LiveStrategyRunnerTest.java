package nl.jimkaplan.autotrader.strategy.live;

import nl.jimkaplan.autotrader.repository.LiveStrategyRepository;
import nl.jimkaplan.autotrader.strategy.model.CandleInterval;
import nl.jimkaplan.autotrader.strategy.model.document.Candle;
import nl.jimkaplan.autotrader.strategy.model.document.LiveStrategy;
import nl.jimkaplan.autotrader.strategy.service.CandleHistoryService;
import nl.jimkaplan.autotrader.strategy.service.LiveTradingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
class LiveStrategyRunnerTest {

    private static final String STRATEGY_ID = "strategy-1";
    private static final long START = 1_783_069_200_000L;
    private static final long HOUR = 3_600_000L;

    @Mock
    private CandleHistoryService candleHistoryService;

    @Mock
    private LiveTradingService liveTradingService;

    @Mock
    private LiveStrategyRepository liveStrategyRepository;

    @Mock
    private CandleStreamFactory streamFactory;

    @Mock
    private BitvavoCandleStream stream;

    private ArgumentCaptor<BitvavoCandleStream.CandleListener> listenerCaptor;
    private LiveStrategy strategy;
    private LiveStrategyRunner runner;

    @BeforeEach
    void setUp() {
        listenerCaptor = ArgumentCaptor.forClass(BitvavoCandleStream.CandleListener.class);
        strategy = strategy(0);
        when(streamFactory.create(eq("BTC-EUR"), eq("1h"), eq(HOUR), listenerCaptor.capture()))
                .thenReturn(stream);
        runner = new LiveStrategyRunner(strategy, CandleInterval.ONE_HOUR, candleHistoryService,
                liveTradingService, liveStrategyRepository, streamFactory);
    }

    @Test
    void start_seedsTheSeriesAndStartsTheStream() {
        when(candleHistoryService.getCandles(eq("BTC-EUR"), eq(CandleInterval.ONE_HOUR), any(), any()))
                .thenReturn(candles(300, 0));

        runner.start();

        verify(candleHistoryService).ensureRange(eq("BTC-EUR"), eq(CandleInterval.ONE_HOUR), any(), any());
        verify(stream).start();
        assertTrue(listenerCaptor.getValue() != null);
    }

    @Test
    void aClosedCandleIsResolvedAppendedAndEvaluatedAfterPersistingTheState() {
        when(candleHistoryService.getCandles(eq("BTC-EUR"), eq(CandleInterval.ONE_HOUR), any(), any()))
                .thenReturn(candles(300, 0));
        long closed = START + 300 * HOUR;
        Instant at = Instant.ofEpochMilli(closed);
        Instant next = Instant.ofEpochMilli(closed + HOUR);
        when(candleHistoryService.getCandles(eq("BTC-EUR"), eq(CandleInterval.ONE_HOUR), eq(at), eq(next)))
                .thenReturn(List.of(candle(300)));
        runner.start();

        listenerCaptor.getValue().onCandleClosed(closed);

        verify(candleHistoryService).ensureRange(eq("BTC-EUR"), eq(CandleInterval.ONE_HOUR), eq(at), eq(next));
        ArgumentCaptor<LiveStrategy> strategyCaptor = ArgumentCaptor.forClass(LiveStrategy.class);
        verify(liveStrategyRepository).save(strategyCaptor.capture());
        assertEquals(closed, strategyCaptor.getValue().getLastEvaluatedOpenTime());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Candle>> seriesCaptor = ArgumentCaptor.forClass(List.class);
        verify(liveTradingService).evaluateAndTrade(eq(strategy), seriesCaptor.capture());
        assertEquals(301, seriesCaptor.getValue().size());
        assertEquals(closed, seriesCaptor.getValue().get(300).getOpenTime());

        // The evaluated point is persisted before acting, so a crash costs a trade, not a duplicate.
        InOrder inOrder = inOrder(liveStrategyRepository, liveTradingService);
        inOrder.verify(liveStrategyRepository).save(any(LiveStrategy.class));
        inOrder.verify(liveTradingService).evaluateAndTrade(eq(strategy), any());
    }

    @Test
    void alreadyEvaluatedCandlesAreSkippedWithoutTouchingTheStore() {
        strategy.setLastEvaluatedOpenTime(START);

        listenerCaptor.getValue().onCandleClosed(START);

        verifyNoInteractions(candleHistoryService, liveTradingService);
    }

    @Test
    void candlesThatAreNotAvailableYetAreSkipped() {
        ReflectionTestUtils.setField(runner, "resolveRetryMillis", 0L);
        long closed = START + HOUR;
        Instant at = Instant.ofEpochMilli(closed);
        Instant next = Instant.ofEpochMilli(closed + HOUR);
        when(candleHistoryService.getCandles(eq("BTC-EUR"), eq(CandleInterval.ONE_HOUR), eq(at), eq(next)))
                .thenReturn(List.of());

        listenerCaptor.getValue().onCandleClosed(closed);

        verify(candleHistoryService, times(2))
                .ensureRange(eq("BTC-EUR"), eq(CandleInterval.ONE_HOUR), eq(at), eq(next));
        verify(liveStrategyRepository, never()).save(any());
        verify(liveTradingService, never()).evaluateAndTrade(any(), any());
    }

    @Test
    void stopAndStatusDelegateToTheStream() {
        when(stream.isConnected()).thenReturn(true);

        assertTrue(runner.isConnected());
        assertEquals(strategy, runner.getStrategy());

        runner.stop();

        verify(stream).stop();
    }

    private static LiveStrategy strategy(long lastEvaluated) {
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
                .exitConditions(List.of("SAR_BEARISH_CROSSOVER"))
                .amountPerBuy(100)
                .warmUpCandles(0)
                .dryRun(true)
                .enabled(true)
                .lastEvaluatedOpenTime(lastEvaluated)
                .build();
        strategy.setId(STRATEGY_ID);
        return strategy;
    }

    private static List<Candle> candles(int count, int fromIndex) {
        List<Candle> candles = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            candles.add(candle(fromIndex + i));
        }
        return candles;
    }

    private static Candle candle(int index) {
        double close = 100 + index;
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
