package nl.jimkaplan.autotrader.strategy.live;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.jimkaplan.autotrader.strategy.service.LiveOrderReconciliationService;
import nl.jimkaplan.autotrader.strategy.service.LiveStrategyService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Starts the enabled live strategies when the application boots, so a restart resumes the engine
 * without manual intervention. Before any strategy runs, the recorded orders and open positions are
 * reconciled against the exchange's order history; a strategy whose reconciliation failed is held
 * back rather than allowed to trade on state that could not be trusted.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LiveStrategyStarter implements ApplicationRunner {

    private final LiveOrderReconciliationService liveOrderReconciliationService;
    private final LiveStrategyService liveStrategyService;

    @Override
    public void run(ApplicationArguments args) {
        Set<String> failedReconciliation = liveOrderReconciliationService.reconcileAll();
        liveStrategyService.startEnabled(failedReconciliation);
    }
}
