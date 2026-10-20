package nl.jimkaplan.autotrader.config;

import lombok.extern.slf4j.Slf4j;
import nl.jimkaplan.autotrader.repository.LiveStrategyRepository;
import nl.jimkaplan.autotrader.strategy.model.document.LiveStrategy;
import nl.jimkaplan.autotrader.strategy.service.DisplayZone;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Names the live strategies that predate the name field, so the live page and the forms always
 * have something readable to show. A document that already has a name is left untouched.
 *
 * <p>The generated name is the pair, the interval and the creation minute, e.g.
 * {@code BTC-EUR 1h · 12 Oct 21:04}. It runs before the live starter, so an enabled strategy is
 * named before its card is read; it needs no index because it matches on {@code _id}.</p>
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class LiveStrategyNameInitializer implements ApplicationRunner {

    private final LiveStrategyRepository liveStrategyRepository;
    private final DisplayZone display;

    @Autowired
    public LiveStrategyNameInitializer(
            LiveStrategyRepository liveStrategyRepository,
            @Value("${autotrader.ui.time-zone:}") String timeZone) {
        this(liveStrategyRepository, new DisplayZone(timeZone));
    }

    LiveStrategyNameInitializer(LiveStrategyRepository liveStrategyRepository, DisplayZone display) {
        this.liveStrategyRepository = liveStrategyRepository;
        this.display = display;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<LiveStrategy> unnamed = liveStrategyRepository.findAll().stream()
                .filter(strategy -> strategy.getName() == null || strategy.getName().isBlank())
                .toList();
        if (unnamed.isEmpty()) {
            return;
        }
        try {
            for (LiveStrategy strategy : unnamed) {
                liveStrategyRepository.updateName(strategy.getId(), generatedName(strategy));
            }
            log.info("Named {} live strategies that predated the name field", unnamed.size());
        } catch (RuntimeException e) {
            log.warn("Could not name the live strategies that predate the name field: {}",
                    e.getMessage());
        }
    }

    /**
     * @param strategy The strategy
     * @return The pair, the interval and the creation minute; just the pair and interval when the
     *         creation time is missing
     */
    String generatedName(LiveStrategy strategy) {
        String base = (strategy.getMarket() == null ? "Strategy" : strategy.getMarket())
                + (strategy.getInterval() == null ? "" : " " + strategy.getInterval());
        return strategy.getCreatedTime() == null ? base
                : base + " · " + display.minute(strategy.getCreatedTime());
    }
}
