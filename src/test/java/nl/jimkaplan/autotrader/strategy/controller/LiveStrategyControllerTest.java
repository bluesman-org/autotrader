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
    void all_returnsTheActiveStrategies() {
        when(liveStrategyService.active()).thenReturn(List.of(strategy()));

        ResponseEntity<List<LiveStrategy>> response = liveStrategyController.all(false);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().size());
    }

    @Test
    void all_includesArchivedStrategiesWhenAsked() {
        when(liveStrategyService.all()).thenReturn(List.of(strategy()));

        ResponseEntity<List<LiveStrategy>> response = liveStrategyController.all(true);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().size());
        verify(liveStrategyService).all();
    }

    @Test
    void update_delegatesAndReturnsTheStoredStrategy() {
        LiveStrategyRequest request = new LiveStrategyRequest();
        LiveStrategy strategy = strategy();
        when(liveStrategyService.update("strategy-1", request)).thenReturn(strategy);

        ResponseEntity<LiveStrategy> response = liveStrategyController.update("strategy-1", request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(strategy, response.getBody());
    }

    @Test
    void delete_reportsWhetherTheStrategyWasArchived() {
        when(liveStrategyService.delete("strategy-1")).thenReturn(true);

        ResponseEntity<LiveStrategyDeletion> response = liveStrategyController.delete("strategy-1");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("strategy-1", response.getBody().id());
        assertTrue(response.getBody().archived());
    }

    @Test
    void get_returnsTheRuntimeState() {
        LiveStrategy strategy = strategy();
        when(liveStrategyService.get("strategy-1")).thenReturn(Optional.of(strategy));
        when(liveStrategyService.isRunning("strategy-1")).thenReturn(true);
        when(liveStrategyService.isConnected("strategy-1")).thenReturn(true);
        when(liveStrategyService.lastError("strategy-1")).thenReturn("errorCode 205: Invalid market");

        ResponseEntity<LiveStrategyView> response = liveStrategyController.get("strategy-1");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(strategy, response.getBody().strategy());
        assertTrue(response.getBody().running());
        assertTrue(response.getBody().connected());
        assertEquals("errorCode 205: Invalid market", response.getBody().lastError());
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

    @Test
    void closePreviewAndCloseDelegate() {
        LiveCloseView view = closeView();
        LiveCloseResult result = new LiveCloseResult(true, "FILLED", "order-1", "closed");
        when(liveStrategyService.closePreview("strategy-1")).thenReturn(view);
        when(liveStrategyService.close("strategy-1")).thenReturn(result);

        assertEquals(HttpStatus.OK, liveStrategyController.closePreview("strategy-1").getStatusCode());
        assertEquals(view, liveStrategyController.closePreview("strategy-1").getBody());
        assertEquals(result, liveStrategyController.close("strategy-1").getBody());
    }

    @Test
    void externalClosesAndReconcileCloseDelegate() {
        ReconcileView view = reconcileView();
        LiveOrder row = LiveOrder.builder().strategyId("strategy-1").external(true).build();
        when(liveStrategyService.reconcileView("strategy-1")).thenReturn(view);
        when(liveStrategyService.reconcileExternalClose("strategy-1", "order-1")).thenReturn(row);

        assertEquals(view, liveStrategyController.externalCloses("strategy-1").getBody());
        assertEquals(row, liveStrategyController.reconcileClose("strategy-1",
                new ExternalCloseRequest("order-1")).getBody());
    }

    @Test
    void closeErrorsPropagateForTheGlobalHandler() {
        when(liveStrategyService.close("strategy-1"))
                .thenThrow(new IllegalArgumentException("stop the strategy before closing its position"));

        assertThrows(IllegalArgumentException.class, () -> liveStrategyController.close("strategy-1"));
    }

    private static LiveCloseView closeView() {
        return new LiveCloseView("strategy-1", "BTC 1h", "BTC-EUR", "1h", true, true,
                "LONG 0,001 BTC", "entry", "fee", "bid", "proceeds", "fee", "profit", "pos",
                "warning");
    }

    private static ReconcileView reconcileView() {
        return new ReconcileView("strategy-1", "BTC 1h", "BTC-EUR", "1h", false,
                "LONG 0,001 BTC", "entry", "fee", List.of(), null);
    }

    private static LiveStrategy strategy() {
        LiveStrategy strategy = LiveStrategy.builder()
                .name("BTC 1h")
                .market("BTC-EUR")
                .interval("1h")
                .dryRun(true)
                .enabled(true)
                .build();
        strategy.setId("strategy-1");
        return strategy;
    }
}
