package nl.jimkaplan.autotrader.strategy.live;

import nl.jimkaplan.autotrader.strategy.service.LiveOrderReconciliationService;
import nl.jimkaplan.autotrader.strategy.service.LiveStrategyService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;

import java.util.Set;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LiveStrategyStarterTest {

    @Mock
    private LiveOrderReconciliationService liveOrderReconciliationService;

    @Mock
    private LiveStrategyService liveStrategyService;

    @InjectMocks
    private LiveStrategyStarter starter;

    @Test
    void startingReconcilesFirstAndPassesTheFailedStrategies() {
        when(liveOrderReconciliationService.reconcileAll()).thenReturn(Set.of("strategy-2"));

        starter.run(new DefaultApplicationArguments());

        InOrder inOrder = inOrder(liveOrderReconciliationService, liveStrategyService);
        inOrder.verify(liveOrderReconciliationService).reconcileAll();
        inOrder.verify(liveStrategyService).startEnabled(Set.of("strategy-2"));
    }
}
