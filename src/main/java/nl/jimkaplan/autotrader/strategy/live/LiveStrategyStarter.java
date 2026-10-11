package nl.jimkaplan.autotrader.strategy.live;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.jimkaplan.autotrader.strategy.service.LiveStrategyService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Starts the enabled live strategies when the application boots, so a restart resumes the engine
 * without manual intervention. Failures are logged and do not stop the application.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LiveStrategyStarter implements ApplicationRunner {

    private final LiveStrategyService liveStrategyService;

    @Override
    public void run(ApplicationArguments args) {
        liveStrategyService.startEnabled();
    }
}
