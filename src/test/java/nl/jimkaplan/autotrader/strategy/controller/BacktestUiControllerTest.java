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
import nl.jimkaplan.autotrader.strategy.service.BacktestService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BacktestUiControllerTest {

    @Mock
    private BacktestService backtestService;

    @InjectMocks
    private BacktestUiController backtestUiController;

    @Test
    void form_providesTheDefaultsAndOptions() {
        when(backtestService.recent(BacktestUiController.RECENT_LIMIT)).thenReturn(List.of());
        Model model = new ExtendedModelMap();

        String view = backtestUiController.form(model);

        assertEquals("backtests/form", view);
        assertInstanceOf(BacktestRunForm.class, model.getAttribute("form"));
        assertNotNull(model.getAttribute("intervals"));
        assertNotNull(model.getAttribute("rankingMetrics"));
        assertNotNull(model.getAttribute("conditions"));
        assertNotNull(model.getAttribute("operators"));
        assertNotNull(model.getAttribute("runs"));
    }

    @Test
    void form_keepsAFlashedForm() {
        when(backtestService.recent(BacktestUiController.RECENT_LIMIT)).thenReturn(List.of());
        Model model = new ExtendedModelMap();
        BacktestRunForm flashed = BacktestRunForm.defaults();
        flashed.setMarket("ETH-EUR");
        model.addAttribute("form", flashed);

        backtestUiController.form(model);

        assertSame(flashed, model.getAttribute("form"));
    }

    @Test
    void form_mapsRecentRunsWithDisplayStrings() {
        when(backtestService.recent(BacktestUiController.RECENT_LIMIT)).thenReturn(List.of(run("run-1")));
        Model model = new ExtendedModelMap();

        backtestUiController.form(model);

        @SuppressWarnings("unchecked")
        List<RunSummaryView> runs = (List<RunSummaryView>) model.getAttribute("runs");
        assertNotNull(runs);
        assertEquals(1, runs.size());
        assertEquals("run-1", runs.get(0).run().getId());
        assertEquals("2026-10-11 17:40", runs.get(0).runAt());
        assertEquals("2026-07-03 → 2026-10-11", runs.get(0).range());
    }

    @Test
    void run_redirectsToTheStoredRun() {
        when(backtestService.run(any())).thenReturn(run("run-1"));

        String view = backtestUiController.run(BacktestRunForm.defaults(), new RedirectAttributesModelMap());

        assertEquals("redirect:/ui/backtests/run-1", view);
    }

    @Test
    void run_redirectsBackWithTheValidationMessageAndTheSubmittedValues() {
        when(backtestService.run(any())).thenThrow(new IllegalArgumentException("the market is required"));
        BacktestRunForm form = BacktestRunForm.defaults();
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String view = backtestUiController.run(form, redirectAttributes);

        assertEquals("redirect:/ui/backtests", view);
        assertEquals("the market is required", redirectAttributes.getFlashAttributes().get("error"));
        assertSame(form, redirectAttributes.getFlashAttributes().get("form"));
    }

    @Test
    void run_redirectsBackWithAFixedMessageWhenTheCandleFetchFails() {
        when(backtestService.run(any())).thenThrow(
                new ResourceAccessException("I/O error on GET request for \"https://api.bitvavo.com\": connect timed out"));
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String view = backtestUiController.run(BacktestRunForm.defaults(), redirectAttributes);

        assertEquals("redirect:/ui/backtests", view);
        // The transport message is logged, not shown
        assertEquals(BacktestUiController.CANDLE_FETCH_ERROR,
                redirectAttributes.getFlashAttributes().get("error"));
    }

    @Test
    void runDetail_showsTheStoredRun() {
        BacktestRun run = run("run-1");
        when(backtestService.get("run-1")).thenReturn(Optional.of(run));
        Model model = new ExtendedModelMap();

        String view = backtestUiController.runDetail("run-1", model, new RedirectAttributesModelMap());

        assertEquals("backtests/run", view);
        assertSame(run, model.getAttribute("run"));
        assertEquals("2026-10-11 17:40", model.getAttribute("runAtText"));
        assertEquals("2026-07-03 → 2026-10-11", model.getAttribute("rangeText"));
    }

    @Test
    void runDetail_redirectsWhenTheRunIsUnknown() {
        when(backtestService.get("missing")).thenReturn(Optional.empty());
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String view = backtestUiController.runDetail("missing", new ExtendedModelMap(), redirectAttributes);

        assertEquals("redirect:/ui/backtests", view);
        assertEquals("No backtest run with ID missing", redirectAttributes.getFlashAttributes().get("error"));
    }

    @Test
    void trades_showsTheEntryAndItsRecomputedTrades() {
        BacktestEntryTrades trades = entryTrades("run-1", false, 2399);
        when(backtestService.trades("run-1", 1)).thenReturn(Optional.of(trades));
        Model model = new ExtendedModelMap();

        String view = backtestUiController.trades("run-1", 1, model, new RedirectAttributesModelMap());

        assertEquals("backtests/trades", view);
        assertSame(trades.run(), model.getAttribute("run"));
        assertEquals(1, model.getAttribute("entryNumber"));
        assertEquals(trades.run().getEntries().get(0), model.getAttribute("entry"));
        assertEquals("2026-07-03 → 2026-10-11", model.getAttribute("rangeText"));
        assertEquals(false, model.getAttribute("historyChanged"));
        assertEquals(2399, model.getAttribute("candleCount"));
        @SuppressWarnings("unchecked")
        List<BacktestTradeView> trainTrades = (List<BacktestTradeView>) model.getAttribute("trainTrades");
        assertNotNull(trainTrades);
        assertEquals(1, trainTrades.size());
        assertEquals("2026-09-01 10:00", trainTrades.get(0).entryTimeText());
        assertNotNull(model.getAttribute("trainMetrics"));
        @SuppressWarnings("unchecked")
        List<SkippedSignalView> trainSkipped = (List<SkippedSignalView>) model.getAttribute("trainSkipped");
        assertNotNull(trainSkipped);
        assertEquals(1, trainSkipped.size());
        assertEquals(SkipReason.INSUFFICIENT_BALANCE, trainSkipped.get(0).reason());
        @SuppressWarnings("unchecked")
        List<BacktestTradeView> validateTrades = (List<BacktestTradeView>) model.getAttribute("validateTrades");
        assertNotNull(validateTrades);
        assertEquals(0, validateTrades.size());
        assertNotNull(model.getAttribute("validateMetrics"));
        @SuppressWarnings("unchecked")
        List<SkippedSignalView> validateSkipped = (List<SkippedSignalView>) model.getAttribute("validateSkipped");
        assertNotNull(validateSkipped);
        assertEquals(0, validateSkipped.size());
    }

    @Test
    void trades_redirectsWhenTheRunIsUnknown() {
        when(backtestService.trades("missing", 1)).thenReturn(Optional.empty());
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String view = backtestUiController.trades("missing", 1, new ExtendedModelMap(), redirectAttributes);

        assertEquals("redirect:/ui/backtests", view);
        assertEquals("No backtest run with ID missing", redirectAttributes.getFlashAttributes().get("error"));
    }

    @Test
    void trades_redirectsWhenTheEntryNumberIsOutOfRange() {
        when(backtestService.trades("run-1", 2))
                .thenThrow(new IllegalArgumentException("Entry 2 does not exist; valid entries are 1 to 1"));
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String view = backtestUiController.trades("run-1", 2, new ExtendedModelMap(), redirectAttributes);

        assertEquals("redirect:/ui/backtests/run-1", view);
        assertEquals("Entry 2 does not exist; valid entries are 1 to 1",
                redirectAttributes.getFlashAttributes().get("error"));
    }

    @Test
    void trades_redirectsWhenTheStoredCandlesAreGone() {
        when(backtestService.trades("run-1", 1))
                .thenThrow(new IllegalStateException("No candles are stored for the run's range"));
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String view = backtestUiController.trades("run-1", 1, new ExtendedModelMap(), redirectAttributes);

        assertEquals("redirect:/ui/backtests/run-1", view);
        assertEquals("No candles are stored for the run's range",
                redirectAttributes.getFlashAttributes().get("error"));
    }

    @Test
    void trades_redirectsWithAFixedMessageWhenTheCandleFetchFails() {
        when(backtestService.trades("run-1", 1)).thenThrow(
                new ResourceAccessException("I/O error on GET request for \"https://api.bitvavo.com\": connect timed out"));
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String view = backtestUiController.trades("run-1", 1, new ExtendedModelMap(), redirectAttributes);

        assertEquals("redirect:/ui/backtests/run-1", view);
        assertEquals(BacktestUiController.CANDLE_FETCH_ERROR, redirectAttributes.getFlashAttributes().get("error"));
    }

    private static BacktestEntryTrades entryTrades(String id, boolean historyChanged, int candleCount) {
        return new BacktestEntryTrades(runWithEntry(id), 1, candleCount, historyChanged, evaluation());
    }

    private static BacktestRun runWithEntry(String id) {
        BacktestRun run = BacktestRun.builder()
                .runAt(Instant.parse("2026-10-11T17:40:00Z"))
                .market("BTC-EUR")
                .interval("1h")
                .fromTime(LocalDate.parse("2026-07-03").atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
                .toTime(LocalDate.parse("2026-10-12").atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
                .candleCount(2399)
                .combinationsEvaluated(1)
                .rankingMetric("TOTAL_RETURN")
                .entries(List.of(entry()))
                .build();
        run.setId(id);
        return run;
    }

    private static BacktestRunEntry entry() {
        return BacktestRunEntry.builder()
                .fastPeriod(12).slowPeriod(26).signalPeriod(9)
                .acceleration(0.02).maximum(0.2)
                .entryOperator("ALL").entryConditions(List.of("MACD_BULLISH_CROSSOVER"))
                .exitOperator("ALL").exitConditions(List.of("SAR_BEARISH_CROSSOVER"))
                .build();
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

    private static BacktestRun run(String id) {
        BacktestRun run = BacktestRun.builder()
                .runAt(Instant.parse("2026-10-11T17:40:00Z"))
                .market("BTC-EUR")
                .interval("1h")
                .fromTime(LocalDate.parse("2026-07-03").atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
                // The stored end bound is exclusive: the start of the day after the last included day
                .toTime(LocalDate.parse("2026-10-12").atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
                .candleCount(2399)
                .combinationsEvaluated(1)
                .rankingMetric("TOTAL_RETURN")
                .entries(List.of())
                .build();
        run.setId(id);
        return run;
    }
}
