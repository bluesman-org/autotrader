package nl.jimkaplan.autotrader.strategy.live;

import nl.jimkaplan.autotrader.strategy.service.LiveStrategyService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class LiveStrategyStarterTest {

    @Mock
    private LiveStrategyService liveStrategyService;

    @InjectMocks
    private LiveStrategyStarter starter;

    @Test
    void startingTheApplicationStartsTheEnabledStrategies() {
        starter.run(new DefaultApplicationArguments());

        verify(liveStrategyService).startEnabled();
    }
}
