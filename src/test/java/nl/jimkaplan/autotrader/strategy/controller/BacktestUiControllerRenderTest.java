package nl.jimkaplan.autotrader.strategy.controller;

import nl.jimkaplan.autotrader.strategy.backtest.BacktestEntryTrades;
import nl.jimkaplan.autotrader.strategy.backtest.BacktestEvaluation;
import nl.jimkaplan.autotrader.strategy.backtest.BacktestMetrics;
import nl.jimkaplan.autotrader.strategy.backtest.BacktestResult;
import nl.jimkaplan.autotrader.strategy.backtest.BacktestTrade;
import nl.jimkaplan.autotrader.strategy.backtest.SkipReason;
import nl.jimkaplan.autotrader.strategy.backtest.SkippedSignal;
import nl.jimkaplan.autotrader.strategy.model.document.BacktestRun;
import nl.jimkaplan.autotrader.strategy.model.document.BacktestRunEntry;
import nl.jimkaplan.autotrader.strategy.model.document.BacktestRunMetrics;
import nl.jimkaplan.autotrader.strategy.service.BacktestService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Renders the backtest templates, so a broken expression fails here instead of in the browser.
 * The property overrides stand in for the environment variables the application.yml placeholders
 * expect, which a test run does not have.
 */
@WebMvcTest(controllers = BacktestUiController.class, properties = {
        "spring.data.mongodb.uri=mongodb://localhost:27017/autotrader-test",
        "bitvavo.api.url=http://localhost:9999",
        "encryption.master-key=dGVzdC1rZXk="
})
class BacktestUiControllerRenderTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BacktestService backtestService;

    @Test
    void formPageRendersTheDefaultsAndTheOptions() throws Exception {
        when(backtestService.recent(BacktestUiController.RECENT_LIMIT)).thenReturn(List.of());

        mockMvc.perform(get("/ui/backtests"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Strategy backtests")))
                .andExpect(content().string(containsString("name=\"market\"")))
                .andExpect(content().string(containsString("value=\"BTC-EUR\"")))
                .andExpect(content().string(containsString("MACD BULLISH CROSSOVER")))
                .andExpect(content().string(containsString("SAR BEARISH CROSSOVER")))
                .andExpect(content().string(containsString("Run sweep")))
                // The fee-cover hint, its default data attributes and the ids its script looks up
                .andExpect(content().string(containsString("id=\"fee-hint\"")))
                .andExpect(content().string(containsString("id=\"initialCapital\"")))
                .andExpect(content().string(containsString("id=\"amountPerBuy\"")))
                .andExpect(content().string(containsString("id=\"feeRate\"")))
                .andExpect(content().string(containsString("data-default-fee-rate")))
                .andExpect(content().string(containsString("INSUFFICIENT_BALANCE")));
    }

    @Test
    void runPageRendersTheRankedTable() throws Exception {
        when(backtestService.get("run-1")).thenReturn(Optional.of(run()));

        mockMvc.perform(get("/ui/backtests/run-1"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Backtest BTC-EUR 1h")))
                .andExpect(content().string(containsString("12/26/9")))
                .andExpect(content().string(containsString("Train — earlier part (optimised on)")))
                .andExpect(content().string(containsString("Validate — held out")))
                .andExpect(content().string(containsString("MACD BULLISH CROSSOVER")))
                // A profit factor with no losses prints as the infinity symbol, not a broken cell
                .andExpect(content().string(containsString("∞")))
                .andExpect(content().string(containsString("0.64")));
    }

    @Test
    void tradesPageRendersTheTradesAndSkippedSignals() throws Exception {
        when(backtestService.trades("run-1", 1))
                .thenReturn(Optional.of(new BacktestEntryTrades(run(), 1, 2399, false, evaluation())));

        mockMvc.perform(get("/ui/backtests/run-1/entries/1"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Entry 1 · BTC-EUR 1h")))
                .andExpect(content().string(containsString("12/26/9")))
                .andExpect(content().string(containsString("Train — earlier part (optimised on)")))
                .andExpect(content().string(containsString("Validate — held out")))
                .andExpect(content().string(containsString("2026-09-01 10:00")))
                .andExpect(content().string(containsString("INSUFFICIENT BALANCE")))
                .andExpect(content().string(containsString("No trades on this side.")))
                .andExpect(content().string(containsString("so the trades reproduce the ranked table's metrics.")))
                .andExpect(content().string(not(containsString("The stored history changed"))))
                .andExpect(content().string(not(containsString("can differ from the ranked table"))));
    }

    @Test
    void tradesPageWarnsWhenTheStoredHistoryChanged() throws Exception {
        when(backtestService.trades("run-1", 1))
                .thenReturn(Optional.of(new BacktestEntryTrades(run(), 1, 2400, true, evaluation())));

        mockMvc.perform(get("/ui/backtests/run-1/entries/1"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("The stored history changed since the run")))
                .andExpect(content().string(containsString("now holds 2400 bars")))
                .andExpect(content().string(containsString("so the trades can differ from the ranked table's metrics.")))
                .andExpect(content().string(not(containsString("so the trades reproduce"))));
    }

    private static BacktestEvaluation evaluation() {
        BacktestTrade trade = new BacktestTrade(
                Instant.parse("2026-09-01T10:00:00Z").toEpochMilli(), 100.0,
                Instant.parse("2026-09-01T12:00:00Z").toEpochMilli(), 101.5,
                100.0, 1.0, 0.2, 0.203, 1.097);
        SkippedSignal skipped = new SkippedSignal(5,
                Instant.parse("2026-09-02T10:00:00Z").toEpochMilli(), SkipReason.INSUFFICIENT_BALANCE);
        return new BacktestEvaluation(
                new BacktestResult(new BacktestMetrics(1003.1, 0.31, 1.28, 1, 100.0, 5.0),
                        List.of(trade), List.of(skipped)),
                new BacktestResult(new BacktestMetrics(1000.0, 0.0, 0.0, 0, 0.0, 0.0),
                        List.of(), List.of()));
    }

    private static BacktestRun run() {
        BacktestRunEntry entry = BacktestRunEntry.builder()
                .fastPeriod(12).slowPeriod(26).signalPeriod(9)
                .acceleration(0.02).maximum(0.2)
                .entryOperator("ALL").entryConditions(List.of("MACD_BULLISH_CROSSOVER"))
                .exitOperator("ALL").exitConditions(List.of("SAR_BEARISH_CROSSOVER"))
                .train(BacktestRunMetrics.builder()
                        .finalEquity(1003.1).totalReturnPercent(0.31).maxDrawdownPercent(1.28)
                        .tradeCount(48).winRatePercent(52.1).profitFactor(Double.POSITIVE_INFINITY)
                        .build())
                .validate(BacktestRunMetrics.builder()
                        .finalEquity(1006.4).totalReturnPercent(0.64).maxDrawdownPercent(0.9)
                        .tradeCount(21).winRatePercent(57.1).profitFactor(1.4)
                        .build())
                .build();
        BacktestRun run = BacktestRun.builder()
                .runAt(Instant.parse("2026-10-11T17:40:00Z"))
                .market("BTC-EUR").interval("1h")
                .fromTime(Instant.parse("2026-07-03T00:00:00Z").toEpochMilli())
                .toTime(Instant.parse("2026-10-12T00:00:00Z").toEpochMilli())
                .candleCount(2399).initialCapital(1000.0).amountPerBuy(100.0).feeRate(0.002)
                .warmUpCandles(200).trainRatio(0.7).rankingMetric("TOTAL_RETURN")
                .combinationsEvaluated(1).entries(List.of(entry))
                .build();
        run.setId("run-1");
        return run;
    }
}
