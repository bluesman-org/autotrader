package nl.jimkaplan.autotrader.strategy.rule;

import nl.jimkaplan.autotrader.strategy.indicator.Macd;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MacdParametersTest {

    @Test
    void defaults_areTheConventionalPeriods() {
        MacdParameters defaults = MacdParameters.defaults();

        assertEquals(12, defaults.fastPeriod());
        assertEquals(26, defaults.slowPeriod());
        assertEquals(9, defaults.signalPeriod());
    }

    @Test
    void validatesThePeriodsEagerly() {
        assertThrows(IllegalArgumentException.class, () -> new MacdParameters(26, 12, 9));
        assertThrows(IllegalArgumentException.class, () -> new MacdParameters(0, 26, 9));
        assertThrows(IllegalArgumentException.class,
                () -> new MacdParameters(12, 26, Macd.MAX_PERIOD + 1));
    }
}
