package nl.jimkaplan.autotrader.strategy.controller;

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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void recent_rejectsLimitsOutOfRange() {
        assertThrows(IllegalArgumentException.class, () -> backtestController.recent(0));
        assertThrows(IllegalArgumentException.class, () -> backtestController.recent(101));
        verifyNoInteractions(backtestService);
    }
}
