package nl.jimkaplan.autotrader.strategy.rule;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ParabolicSarParametersTest {

    @Test
    void defaults_areTheConventionalFactors() {
        ParabolicSarParameters defaults = ParabolicSarParameters.defaults();

        assertEquals(0.02, defaults.acceleration());
        assertEquals(0.2, defaults.maximum());
    }

    @Test
    void validatesTheFactorsEagerly() {
        assertThrows(IllegalArgumentException.class, () -> new ParabolicSarParameters(-0.01, 0.2));
        assertThrows(IllegalArgumentException.class, () -> new ParabolicSarParameters(0.02, -0.2));
        assertThrows(IllegalArgumentException.class, () -> new ParabolicSarParameters(Double.NaN, 0.2));
        assertThrows(IllegalArgumentException.class,
                () -> new ParabolicSarParameters(0.02, Double.POSITIVE_INFINITY));
    }
}
