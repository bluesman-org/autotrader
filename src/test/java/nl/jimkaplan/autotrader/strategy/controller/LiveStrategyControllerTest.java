package nl.jimkaplan.autotrader.strategy.controller;

import nl.jimkaplan.autotrader.strategy.model.document.LiveOrder;
import nl.jimkaplan.autotrader.strategy.model.document.LiveStrategy;
import nl.jimkaplan.autotrader.strategy.service.LiveStrategyService;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LiveStrategyControllerTest {

    @Mock
    private LiveStrategyService liveStrategyService;

    @InjectMocks
    private LiveStrategyController liveStrategyController;

    @Test
    void create_returnsCreatedWithTheStoredStrategy() {
        LiveStrategyRequest request = new LiveStrategyRequest();
        LiveStrategy strategy = strategy();
        when(liveStrategyService.create(request)).thenReturn(strategy);

        ResponseEntity<LiveStrategy> response = liveStrategyController.create(request);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals(strategy, response.getBody());
    }

    @Test
    void all_returnsTheStrategies() {
        when(liveStrategyService.all()).thenReturn(List.of(strategy()));

        ResponseEntity<List<LiveStrategy>> response = liveStrategyController.all();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().size());
    }

    @Test
    void get_returnsTheRuntimeState() {
        LiveStrategy strategy = strategy();
        when(liveStrategyService.get("strategy-1")).thenReturn(Optional.of(strategy));
        when(liveStrategyService.isRunning("strategy-1")).thenReturn(true);
        when(liveStrategyService.isConnected("strategy-1")).thenReturn(true);

        ResponseEntity<LiveStrategyView> response = liveStrategyController.get("strategy-1");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(strategy, response.getBody().strategy());
        assertTrue(response.getBody().running());
        assertTrue(response.getBody().connected());
    }

    @Test
    void get_throwsWhenTheStrategyIsMissing() {
        when(liveStrategyService.get("missing")).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class, () -> liveStrategyController.get("missing"));
    }

    @Test
    void startAndStop_delegateAndReportTheState() {
        when(liveStrategyService.get("strategy-1")).thenReturn(Optional.of(strategy()));
        when(liveStrategyService.isConnected("strategy-1")).thenReturn(false);

        ResponseEntity<LiveStrategyView> started = liveStrategyController.start("strategy-1");
        ResponseEntity<LiveStrategyView> stopped = liveStrategyController.stop("strategy-1");

        verify(liveStrategyService).start("strategy-1");
        verify(liveStrategyService).stop("strategy-1");
        assertEquals(HttpStatus.OK, started.getStatusCode());
        assertEquals(HttpStatus.OK, stopped.getStatusCode());
        assertFalse(started.getBody().connected());
    }

    @Test
    void orders_returnsTheStrategyOrders() {
        when(liveStrategyService.orders("strategy-1"))
                .thenReturn(List.of(LiveOrder.builder().strategyId("strategy-1").build()));

        ResponseEntity<List<LiveOrder>> response = liveStrategyController.orders("strategy-1");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().size());
    }

    @Test
    void orders_throwWhenTheStrategyIsMissing() {
        when(liveStrategyService.orders("missing")).thenThrow(new NoSuchElementException("Live strategy not found: missing"));

        assertThrows(NoSuchElementException.class, () -> liveStrategyController.orders("missing"));
    }

    private static LiveStrategy strategy() {
        LiveStrategy strategy = LiveStrategy.builder()
                .market("BTC-EUR")
                .interval("1h")
                .dryRun(true)
                .enabled(true)
                .build();
        strategy.setId("strategy-1");
        return strategy;
    }
}
