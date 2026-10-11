package nl.jimkaplan.autotrader.strategy.service;

import nl.jimkaplan.autotrader.repository.BacktestRunRepository;
import nl.jimkaplan.autotrader.strategy.backtest.RankingMetric;
import nl.jimkaplan.autotrader.strategy.controller.BacktestRunRequest;
import nl.jimkaplan.autotrader.strategy.controller.MacdParametersRequest;
import nl.jimkaplan.autotrader.strategy.controller.RuleSetRequest;
import nl.jimkaplan.autotrader.strategy.controller.SarParametersRequest;
import nl.jimkaplan.autotrader.strategy.model.CandleInterval;
import nl.jimkaplan.autotrader.strategy.model.document.BacktestRun;
import nl.jimkaplan.autotrader.strategy.model.document.BacktestRunEntry;
import nl.jimkaplan.autotrader.strategy.model.document.Candle;
import nl.jimkaplan.autotrader.strategy.rule.LogicalOperator;
import nl.jimkaplan.autotrader.strategy.rule.RuleCondition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BacktestServiceTest {

    private static final long HOUR = 3_600_000L;
    private static final long FROM = 1_783_069_200_000L;
    private static final long TO = FROM + 140 * HOUR;

    @Mock
    private CandleHistoryService candleHistoryService;

    @Mock
    private BacktestRunRepository backtestRunRepository;

    @InjectMocks
    private BacktestService backtestService;

    @Test
    void run_usesTheDefaultsAndStoresTheRankedOutcome() {
        when(candleHistoryService.getCandles(eq("BTC-EUR"), eq(CandleInterval.ONE_HOUR), any(), any()))
                .thenReturn(candles(140));
        when(backtestRunRepository.save(any(BacktestRun.class))).thenAnswer(invocation -> invocation.getArgument(0));

        BacktestRun run = backtestService.run(minimalRequest());

        assertEquals("BTC-EUR", run.getMarket());
        assertEquals("1h", run.getInterval());
        assertEquals(FROM, run.getFromTime());
        assertEquals(TO, run.getToTime());
        assertEquals(140, run.getCandleCount());
        assertEquals(1000.0, run.getInitialCapital(), 1e-9);
        assertEquals(100.0, run.getAmountPerBuy(), 1e-9);
        assertEquals(0.002, run.getFeeRate(), 1e-9);
        assertEquals(0, run.getWarmUpCandles());
        assertEquals(0.7, run.getTrainRatio(), 1e-9);
        assertEquals("TOTAL_RETURN", run.getRankingMetric());
        assertEquals(1, run.getCombinationsEvaluated());

        assertEquals(1, run.getEntries().size());
        BacktestRunEntry entry = run.getEntries().get(0);
        assertEquals(12, entry.getFastPeriod());
        assertEquals(26, entry.getSlowPeriod());
        assertEquals(9, entry.getSignalPeriod());
        assertEquals(0.02, entry.getAcceleration(), 1e-9);
        assertEquals(0.2, entry.getMaximum(), 1e-9);
        assertEquals("ALL", entry.getEntryOperator());
        assertEquals(List.of("MACD_BULLISH_CROSSOVER"), entry.getEntryConditions());
        assertEquals(List.of("SAR_BEARISH_CROSSOVER"), entry.getExitConditions());
        // The rising series crosses once, so the train side has a trade and the validate side none
        assertTrue(entry.getTrain().getTradeCount() > 0);
        assertEquals(0, entry.getValidate().getTradeCount());
        assertEquals(1000.0, entry.getValidate().getFinalEquity(), 1e-9);

        verify(candleHistoryService).ensureRange(eq("BTC-EUR"), eq(CandleInterval.ONE_HOUR),
                eq(Instant.ofEpochMilli(FROM)), eq(Instant.ofEpochMilli(TO)));
        verify(backtestRunRepository).save(any(BacktestRun.class));
    }

    @Test
    void run_normalizesTheMarketAndStoresAtMostFiftyEntries() {
        when(candleHistoryService.getCandles(eq("BTC-EUR"), eq(CandleInterval.ONE_HOUR), any(), any()))
                .thenReturn(candles(140));
        when(backtestRunRepository.save(any(BacktestRun.class))).thenAnswer(invocation -> invocation.getArgument(0));

        BacktestRunRequest request = minimalRequest();
        request.setMarket("BTCEUR");
        List<MacdParametersRequest> candidates = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            candidates.add(new MacdParametersRequest(3, 5, 2));
        }
        request.setMacdParameters(candidates);

        BacktestRun run = backtestService.run(request);

        assertEquals("BTC-EUR", run.getMarket());
        assertEquals(51, run.getCombinationsEvaluated());
        assertEquals(50, run.getEntries().size());
    }

    @Test
    void run_usesTheProvidedCandidatesAndSettings() {
        when(candleHistoryService.getCandles(eq("BTC-EUR"), eq(CandleInterval.ONE_HOUR), any(), any()))
                .thenReturn(candles(140));
        when(backtestRunRepository.save(any(BacktestRun.class))).thenAnswer(invocation -> invocation.getArgument(0));

        BacktestRunRequest request = minimalRequest();
        request.setMacdParameters(List.of(new MacdParametersRequest(3, 5, 2)));
        request.setSarParameters(List.of(new SarParametersRequest(0.05, 0.5)));
        request.setEntryRules(List.of(new RuleSetRequest(LogicalOperator.ANY,
                List.of(RuleCondition.MACD_BULLISH_CROSSOVER, RuleCondition.MACD_ABOVE_SIGNAL))));
        request.setExitRules(List.of(new RuleSetRequest(null, List.of(RuleCondition.SAR_BEARISH_CROSSOVER))));
        request.setRankingMetric(RankingMetric.WIN_RATE);
        request.setInitialCapital(500.0);
        request.setAmountPerBuy(50.0);
        request.setFeeRate(0.001);
        request.setTrainRatio(0.5);

        BacktestRun run = backtestService.run(request);

        assertEquals("WIN_RATE", run.getRankingMetric());
        assertEquals(0.5, run.getTrainRatio(), 1e-9);
        assertEquals(0.001, run.getFeeRate(), 1e-9);
        assertEquals(1, run.getCombinationsEvaluated());

        BacktestRunEntry entry = run.getEntries().get(0);
        assertEquals(3, entry.getFastPeriod());
        assertEquals(5, entry.getSlowPeriod());
        assertEquals(2, entry.getSignalPeriod());
        assertEquals(0.05, entry.getAcceleration(), 1e-9);
        assertEquals(0.5, entry.getMaximum(), 1e-9);
        assertEquals("ANY", entry.getEntryOperator());
        assertEquals(List.of("MACD_BULLISH_CROSSOVER", "MACD_ABOVE_SIGNAL"), entry.getEntryConditions());
        // A rule set without an operator combines with ALL
        assertEquals("ALL", entry.getExitOperator());
        assertEquals(List.of("SAR_BEARISH_CROSSOVER"), entry.getExitConditions());
    }

    @Test
    void run_rejectsIncompleteRequests() {
        BacktestRunRequest noMarket = minimalRequest();
        noMarket.setMarket(" ");
        assertThrows(IllegalArgumentException.class, () -> backtestService.run(noMarket));

        BacktestRunRequest noInterval = minimalRequest();
        noInterval.setInterval(null);
        assertThrows(IllegalArgumentException.class, () -> backtestService.run(noInterval));

        BacktestRunRequest unknownInterval = minimalRequest();
        unknownInterval.setInterval("1y");
        assertThrows(IllegalArgumentException.class, () -> backtestService.run(unknownInterval));

        BacktestRunRequest noFrom = minimalRequest();
        noFrom.setFromDate(null);
        assertThrows(IllegalArgumentException.class, () -> backtestService.run(noFrom));

        BacktestRunRequest noTo = minimalRequest();
        noTo.setToDate(null);
        assertThrows(IllegalArgumentException.class, () -> backtestService.run(noTo));

        BacktestRunRequest reversed = minimalRequest();
        reversed.setFromDate(TO);
        reversed.setToDate(FROM);
        assertThrows(IllegalArgumentException.class, () -> backtestService.run(reversed));
    }

    @Test
    void run_rejectsRangesWithoutCandles() {
        when(candleHistoryService.getCandles(eq("BTC-EUR"), eq(CandleInterval.ONE_HOUR), any(), any()))
                .thenReturn(List.of());

        BacktestRunRequest request = minimalRequest();
        assertThrows(IllegalArgumentException.class, () -> backtestService.run(request));
    }

    @Test
    void run_rejectsRuleSetsWithoutConditions() {
        BacktestRunRequest request = minimalRequest();
        request.setEntryRules(List.of(new RuleSetRequest(LogicalOperator.ALL, List.of())));

        // The rule sets are validated before any candle is loaded
        assertThrows(IllegalArgumentException.class, () -> backtestService.run(request));
    }

    @Test
    void get_returnsTheStoredRun() {
        BacktestRun run = BacktestRun.builder().build();
        run.setId("run-1");
        when(backtestRunRepository.findById("run-1")).thenReturn(Optional.of(run));

        assertEquals(Optional.of(run), backtestService.get("run-1"));
    }

    @Test
    void recent_returnsTheNewestFirstPage() {
        BacktestRun run = BacktestRun.builder().build();
        run.setId("run-1");
        when(backtestRunRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(run)));

        assertEquals(List.of(run), backtestService.recent(20));

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(backtestRunRepository).findAll(captor.capture());
        assertEquals(20, captor.getValue().getPageSize());
        assertEquals(Sort.by(Sort.Direction.DESC, "runAt"), captor.getValue().getSort());
    }

    private static BacktestRunRequest minimalRequest() {
        BacktestRunRequest request = new BacktestRunRequest();
        request.setMarket("BTC-EUR");
        request.setInterval("1h");
        request.setFromDate(FROM);
        request.setToDate(TO);
        request.setWarmUpCandles(0);
        return request;
    }

    private static List<Candle> candles(int count) {
        List<Candle> candles = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            // Falls to bar 40 and rises afterwards, so the default MACD crossover (bar 41) lands
            // well inside the train side of the split
            double close = i <= 40 ? 100.0 - i : 60.0 + (i - 40) * 1.5;
            candles.add(Candle.builder()
                    .market("BTC-EUR")
                    .interval("1h")
                    .openTime(FROM + i * HOUR)
                    .open(BigDecimal.valueOf(close))
                    .high(BigDecimal.valueOf(close + 1))
                    .low(BigDecimal.valueOf(close - 1))
                    .close(BigDecimal.valueOf(close))
                    .build());
        }
        return candles;
    }
}
