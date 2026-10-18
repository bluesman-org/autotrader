package nl.jimkaplan.autotrader.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.jimkaplan.autotrader.repository.MarketRepository;
import nl.jimkaplan.autotrader.service.BitvavoCredentialsService;
import nl.jimkaplan.autotrader.service.MarketService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Seeds the trading-pair catalogue from the account on the first boot, so an existing installation
 * offers the pairs it traded without an operator clicking anything.
 *
 * <p>It runs after {@link BitvavoCredentialsInitializer} (which holds the highest precedence), so
 * a key carried over from a bot configuration is already in place. It only runs while the
 * catalogue is empty: a later trade is picked up by the refresh on the settings page, not by a
 * network call on every boot. A failure is logged and leaves the page to offer the refresh; it
 * never stops the application.</p>
 */
@Slf4j
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
@RequiredArgsConstructor
public class MarketInitializer implements ApplicationRunner {

    private final MarketService marketService;
    private final MarketRepository marketRepository;
    private final BitvavoCredentialsService credentialsService;

    @Override
    public void run(ApplicationArguments args) {
        if (marketRepository.count() > 0 || !credentialsService.configured()) {
            return;
        }
        try {
            MarketService.Discovery discovery = marketService.discover();
            log.info("Seeded the trading-pair catalogue from the account: {} pairs ({} added)",
                    discovery.markets(), discovery.added());
        } catch (RuntimeException e) {
            log.warn("Could not seed the trading-pair catalogue from Bitvavo: {}", e.getMessage());
        }
    }
}
