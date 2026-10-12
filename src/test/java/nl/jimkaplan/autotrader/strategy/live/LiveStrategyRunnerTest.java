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
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
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
    void start_seedsTheWindowAndStartsTheStream() {
        when(candleHistoryService.getCandles(eq("BTC-EUR"), eq(CandleInterval.ONE_HOUR), any(), any()))
                .thenReturn(candles(300, 0));

        runner.start();

        verify(candleHistoryService).ensureRange(eq("BTC-EUR"), eq(CandleInterval.ONE_HOUR), any(), any());
        verify(stream).start();
        assertTrue(listenerCaptor.getValue() != null);
    }

    @Test
    void aClosedCandleIsResolvedAppendedAndPersistedBeforeDeciding() {
        seedWith(candles(300, 0));
        long closed = START + 300 * HOUR;
        stubResolved(closed, candle(300));
        runner.start();
        listenerCaptor.getValue().onCandleClosed(closed, 62.5);

        verify(candleHistoryService).ensureRange(eq("BTC-EUR"), eq(CandleInterval.ONE_HOUR),
                eq(at(closed)), eq(at(closed + HOUR)));
        verify(liveStrategyRepository).updateLastEvaluatedOpenTime(STRATEGY_ID, closed);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Candle>> seriesCaptor = ArgumentCaptor.forClass(List.class);
        verify(liveTradingService).decide(eq(strategy), seriesCaptor.capture());
        assertEquals(301, seriesCaptor.getValue().size());
        assertEquals(closed, seriesCaptor.getValue().get(300).getOpenTime());
        verify(liveTradingService, never()).fill(any(), any(), anyDouble());

        // The evaluated point is persisted before any action, so a crash costs a trade, not a duplicate.
        InOrder inOrder = inOrder(liveStrategyRepository, liveTradingService);
        inOrder.verify(liveStrategyRepository).updateLastEvaluatedOpenTime(STRATEGY_ID, closed);
        inOrder.verify(liveTradingService).decide(eq(strategy), any());
    }

    @Test
    void aDecisionIsFilledImmediatelyAtTheArrivingCandlesOpen() {
        seedWith(candles(300, 0));
        long first = START + 300 * HOUR;
        stubResolved(first, candle(300));
        stubNoCandleAt(first + HOUR);
        LiveDecision decision = new LiveDecision("BUY", "entry ALL [TEST]", first);
        when(liveTradingService.decide(any(), any())).thenReturn(decision);
        runner.start();

        listenerCaptor.getValue().onCandleClosed(first, 62.5);

        // The order goes out at the moment of the decision, so its price is the market price.
        verify(liveTradingService).fill(eq(strategy), eq(decision), eq(62.5));
    }

    @Test
    void aGapBatchFillsOlderBoundariesAtTheirStoredNextOpen() {
        seedWith(candles(300, 0));
        long older = START + 300 * HOUR;
        long newest = older + 2 * HOUR;
        stubResolved(older, candle(300));
        stubResolved(newest, candle(302));
        // The candle after the older boundary has closed and is in the store (it also has to be
        // backfilled into the series): its open is the exact next-open price, not the price the
        // newest frame arrived at.
        when(candleHistoryService.getCandles(eq("BTC-EUR"), eq(CandleInterval.ONE_HOUR),
                eq(at(older + HOUR)), eq(at(older + 2 * HOUR))))
                .thenReturn(List.of(openCandle(301, 61.8)));
        stubNoCandleAt(newest + HOUR);
        LiveDecision first = new LiveDecision("BUY", "entry ALL [TEST]", older);
        LiveDecision second = new LiveDecision("BUY", "entry ALL [TEST]", newest);
        when(liveTradingService.decide(any(), any())).thenReturn(first, second);
        runner.start();

        listenerCaptor.getValue().onCandleClosed(older, 62.5);
        listenerCaptor.getValue().onCandleClosed(newest, 62.5);

        // The older boundary's next candle is not in the store when the batch arrives; the fill
        // price fetches it (this is the step that makes the read succeed), like resolve() does.
        verify(candleHistoryService, atLeastOnce()).ensureRange(eq("BTC-EUR"), eq(CandleInterval.ONE_HOUR),
                eq(at(older + HOUR)), eq(at(older + 2 * HOUR)));
        verify(liveTradingService).fill(eq(strategy), eq(first), eq(61.8));
        verify(liveTradingService).fill(eq(strategy), eq(second), eq(62.5));
    }

    @Test
    void aFrameWithoutAnOpenFallsBackToTheClosedCandlesClose() {
        seedWith(candles(300, 0));
        long closed = START + 300 * HOUR;
        stubResolved(closed, candle(300));
        stubNoCandleAt(closed + HOUR);
        LiveDecision decision = new LiveDecision("BUY", "entry ALL [TEST]", closed);
        when(liveTradingService.decide(any(), any())).thenReturn(decision);
        runner.start();

        listenerCaptor.getValue().onCandleClosed(closed, 0);

        verify(liveTradingService).fill(eq(strategy), eq(decision), eq(400.5));
    }

    @Test
    void aMissedBoundaryIsRecordedAsSkipped() {
        seedWith(candles(300, 0));
        long second = START + 300 * HOUR + HOUR;
        when(candleHistoryService.getCandles(eq("BTC-EUR"), eq(CandleInterval.ONE_HOUR),
                eq(at(second)), eq(at(second + HOUR)))).thenReturn(List.of());
        ReflectionTestUtils.setField(runner, "resolveRetryMillis", 0L);
        runner.start();

        listenerCaptor.getValue().onCandleClosed(second, 0);

        verify(liveTradingService).recordSkippedCandle(strategy, second);
        verify(liveStrategyRepository, never()).updateLastEvaluatedOpenTime(STRATEGY_ID, second);
        verify(liveTradingService, never()).fill(any(), any(), anyDouble());
    }

    @Test
    void aMissedBoundaryIsBackfilledFromTheStore() {
        seedWith(candles(300, 0));
        long first = START + 300 * HOUR;
        long missed = first + HOUR;
        long second = missed + HOUR;
        stubResolved(first, candle(300));
        stubResolved(second, candle(302));
        when(candleHistoryService.getCandles(eq("BTC-EUR"), eq(CandleInterval.ONE_HOUR),
                eq(at(missed)), eq(at(second)))).thenReturn(List.of(candle(301)));
        runner.start();

        listenerCaptor.getValue().onCandleClosed(first, 62.5);
        listenerCaptor.getValue().onCandleClosed(second, 62.5);

        // The backfill fetches the missing range before reading it, so a candle that only appears
        // in the store after the failed resolve is still repaired into the series.
        verify(candleHistoryService).ensureRange(eq("BTC-EUR"), eq(CandleInterval.ONE_HOUR),
                eq(at(missed)), eq(at(second)));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Candle>> seriesCaptor = ArgumentCaptor.forClass(List.class);
        verify(liveTradingService, times(2)).decide(eq(strategy), seriesCaptor.capture());
        List<Candle> series = seriesCaptor.getValue();
        assertEquals(303, series.size());
        assertEquals(first, series.get(300).getOpenTime());
        assertEquals(missed, series.get(301).getOpenTime());
        assertEquals(second, series.get(302).getOpenTime());
    }

    @Test
    void theSeriesIsTrimmedToTheWindow() {
        ReflectionTestUtils.setField(runner, "convergenceMarginCandles", 2);
        seedWith(List.of());
        for (int i = 0; i < 11; i++) {
            stubResolved(START + i * HOUR, candle(i));
        }
        runner.start();

        for (int i = 0; i < 11; i++) {
            listenerCaptor.getValue().onCandleClosed(START + i * HOUR, 0);
        }

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Candle>> seriesCaptor = ArgumentCaptor.forClass(List.class);
        verify(liveTradingService, times(11)).decide(eq(strategy), seriesCaptor.capture());
        List<Candle> series = seriesCaptor.getValue();
        // warm-up 0 + slow 5 + signal 2 + margin 2 = 9
        assertEquals(9, series.size());
        assertEquals(START + 2 * HOUR, series.get(0).getOpenTime());
        assertEquals(START + 10 * HOUR, series.get(8).getOpenTime());
    }

    @Test
    void alreadyEvaluatedCandlesAreSkippedWithoutTouchingTheStore() {
        strategy.setLastEvaluatedOpenTime(START);

        listenerCaptor.getValue().onCandleClosed(START, 0);

        verifyNoInteractions(candleHistoryService, liveTradingService);
    }

    @Test
    void stopAndStatusDelegateToTheStream() {
        when(stream.isConnected()).thenReturn(true);
        when(stream.getLastError()).thenReturn("errorCode 205: Invalid market");

        assertTrue(runner.isConnected());
        assertEquals("errorCode 205: Invalid market", runner.getLastError());
        assertEquals(strategy, runner.getStrategy());

        runner.stop();

        verify(stream).stop();
    }

    private void seedWith(List<Candle> seeded) {
        when(candleHistoryService.getCandles(eq("BTC-EUR"), eq(CandleInterval.ONE_HOUR), any(), any()))
                .thenReturn(seeded);
    }

    private void stubResolved(long openTime, Candle candle) {
        when(candleHistoryService.getCandles(eq("BTC-EUR"), eq(CandleInterval.ONE_HOUR),
                eq(at(openTime)), eq(at(openTime + HOUR)))).thenReturn(List.of(candle));
    }

    /**
     * The candle after the given open time is not in the store yet (it is still forming), so a
     * fill there has to fall back to the frame's open.
     */
    private void stubNoCandleAt(long openTime) {
        when(candleHistoryService.getCandles(eq("BTC-EUR"), eq(CandleInterval.ONE_HOUR),
                eq(at(openTime)), eq(at(openTime + HOUR)))).thenReturn(List.of());
    }

    private static Instant at(long openTime) {
        return Instant.ofEpochMilli(openTime);
    }

    private static LiveStrategy strategy(long lastEvaluated) {
        LiveStrategy strategy = LiveStrategy.builder()
                .botId("bot-1")
                .market("BTC-EUR")
                .interval("1h")
                .macdFastPeriod(3)
                .macdSlowPeriod(5)
                .macdSignalPeriod(2)
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
        return openCandle(index, 100 + index);
    }

    private static Candle openCandle(int index, double open) {
        return Candle.builder()
                .market("BTC-EUR")
                .interval("1h")
                .openTime(START + index * HOUR)
                .open(BigDecimal.valueOf(open))
                .high(BigDecimal.valueOf(open + 1))
                .low(BigDecimal.valueOf(open - 1))
                .close(BigDecimal.valueOf(open + 0.5))
                .volume(BigDecimal.ONE)
                .build();
    }
}
