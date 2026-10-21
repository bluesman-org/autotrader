package nl.jimkaplan.autotrader.config;

import nl.jimkaplan.autotrader.repository.LiveStrategyRepository;
import nl.jimkaplan.autotrader.strategy.model.document.LiveStrategy;
import nl.jimkaplan.autotrader.strategy.service.DisplayZone;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LiveStrategyNameInitializerTest {

    @Mock
    private LiveStrategyRepository liveStrategyRepository;

    private LiveStrategyNameInitializer initializer;

    @BeforeEach
    void setUp() {
        initializer = new LiveStrategyNameInitializer(liveStrategyRepository,
                new DisplayZone(ZoneId.of("UTC")));
    }

    @Test
    void run_namesOnlyTheStrategiesWithoutAName() {
        LiveStrategy named = strategy("strategy-1", "BTC-EUR", "1h", "Already named");
        LiveStrategy unnamed = strategy("strategy-2", "ETH-EUR", "4h", null);
        when(liveStrategyRepository.findAll()).thenReturn(List.of(named, unnamed));

        initializer.run(null);

        verify(liveStrategyRepository).updateName("strategy-2", "ETH-EUR 4h · 12 Oct 21:04");
        verify(liveStrategyRepository, never()).updateName(eq("strategy-1"), anyString());
    }

    @Test
    void run_doesNothingWhenEveryStrategyHasAName() {
        when(liveStrategyRepository.findAll()).thenReturn(List.of(
                strategy("strategy-1", "BTC-EUR", "1h", "One"),
                strategy("strategy-2", "ETH-EUR", "4h", "Two")));

        initializer.run(null);

        verify(liveStrategyRepository, never()).updateName(anyString(), anyString());
    }

    @Test
    void run_survivesAFailureAndLeavesTheNamesToTheNextBoot() {
        when(liveStrategyRepository.findAll()).thenReturn(List.of(
                strategy("strategy-1", "BTC-EUR", "1h", null)));
        doThrow(new IllegalStateException("boom"))
                .when(liveStrategyRepository).updateName(eq("strategy-1"), anyString());

        assertDoesNotThrow(() -> initializer.run(null));
    }

    @Test
    void theAutowiredConstructorUsesTheConfiguredTimeZone() {
        LiveStrategyNameInitializer configured =
                new LiveStrategyNameInitializer(liveStrategyRepository, "UTC");

        assertEquals("BTC-EUR 1h · 12 Oct 21:04",
                configured.generatedName(strategy("strategy-1", "BTC-EUR", "1h", null)));
    }

    @Test
    void generatedName_usesThePairIntervalAndCreationMinute() {
        LiveStrategy strategy = strategy("strategy-1", "BTC-EUR", "1h", null);

        assertEquals("BTC-EUR 1h · 12 Oct 21:04", initializer.generatedName(strategy));
    }

    @Test
    void generatedName_fallsBackToThePairAndIntervalWithoutACreationTime() {
        LiveStrategy strategy = LiveStrategy.builder().market("BTC-EUR").interval("1h").build();

        assertEquals("BTC-EUR 1h", initializer.generatedName(strategy));
    }

    private static LiveStrategy strategy(String id, String market, String interval, String name) {
        LiveStrategy strategy = LiveStrategy.builder()
                .name(name)
                .market(market)
                .interval(interval)
                .createdTime(Instant.parse("2026-10-12T21:04:00Z"))
                .build();
        strategy.setId(id);
        return strategy;
    }
}
