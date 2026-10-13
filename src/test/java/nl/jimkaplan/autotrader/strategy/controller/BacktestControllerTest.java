package nl.jimkaplan.autotrader.strategy.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import nl.jimkaplan.autotrader.strategy.backtest.BacktestEntryTrades;
import nl.jimkaplan.autotrader.strategy.backtest.BacktestEvaluation;
import nl.jimkaplan.autotrader.strategy.backtest.BacktestMetrics;
import nl.jimkaplan.autotrader.strategy.backtest.BacktestResult;
import nl.jimkaplan.autotrader.strategy.model.document.BacktestRun;
import nl.jimkaplan.autotrader.strategy.service.BacktestService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BacktestControllerTest {

    @Mock
    private BacktestService backtestService;

    @InjectMocks
    private BacktestController backtestController;

    @Test
    void run_returnsCreatedWithTheStoredRun() {
        BacktestRunRequest request = new BacktestRunRequest();
        BacktestRun run = BacktestRun.builder().build();
        run.setId("run-1");
        when(backtestService.run(request)).thenReturn(run);

        ResponseEntity<BacktestRun> response = backtestController.run(request);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals(run, response.getBody());
    }

    @Test
    void get_returnsTheRunWhenFound() {
        BacktestRun run = BacktestRun.builder().build();
        run.setId("run-1");
        when(backtestService.get("run-1")).thenReturn(Optional.of(run));

        ResponseEntity<BacktestRun> response = backtestController.get("run-1");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(run, response.getBody());
    }

    @Test
    void get_throwsWhenTheRunIsMissing() {
        when(backtestService.get("missing")).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class, () -> backtestController.get("missing"));
    }

    @Test
    void recent_returnsTheRuns() {
        BacktestRun run = BacktestRun.builder().build();
        run.setId("run-1");
        when(backtestService.recent(5)).thenReturn(List.of(run));

        ResponseEntity<List<BacktestRun>> response = backtestController.recent(5);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().size());
    }

    @Test
    void recentResponse_omitsTheNullEntriesOfSummaries() throws JsonProcessingException {
        BacktestRun summary = BacktestRun.builder().build();
        summary.setId("run-1");
        when(backtestService.recent(5)).thenReturn(List.of(summary));

        ResponseEntity<List<BacktestRun>> response = backtestController.recent(5);
        String json = new ObjectMapper().writeValueAsString(response.getBody());

        assertFalse(json.contains("entries"));
        assertTrue(json.contains("\"id\":\"run-1\""));
    }

    @Test
    void recent_rejectsLimitsOutOfRange() {
        assertThrows(IllegalArgumentException.class, () -> backtestController.recent(0));
        assertThrows(IllegalArgumentException.class, () -> backtestController.recent(101));
        verifyNoInteractions(backtestService);
    }

    @Test
    void trades_returnsTheEvaluatedEntry() {
        BacktestEntryTrades trades = new BacktestEntryTrades(BacktestRun.builder().build(), 1, 0, false,
                new BacktestEvaluation(
                        new BacktestResult(metrics(), List.of(), List.of()),
                        new BacktestResult(metrics(), List.of(), List.of())));
        when(backtestService.trades("run-1", 1)).thenReturn(Optional.of(trades));

        ResponseEntity<BacktestEntryTrades> response = backtestController.trades("run-1", 1);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(trades, response.getBody());
    }

    @Test
    void trades_throwsWhenTheRunIsMissing() {
        when(backtestService.trades("missing", 1)).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class, () -> backtestController.trades("missing", 1));
    }

    private static BacktestMetrics metrics() {
        return new BacktestMetrics(1000.0, 0.0, 0.0, 0, 0.0, 0.0, 0);
    }
}
