package nl.jimkaplan.autotrader.strategy.service;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ClientOrderIdsTest {

    @Test
    void forSequence_isDeterministicPerStrategyAndSequence() {
        UUID first = ClientOrderIds.forSequence("strategy-1", 0);

        assertEquals(first, ClientOrderIds.forSequence("strategy-1", 0));
        assertNotEquals(first, ClientOrderIds.forSequence("strategy-1", 1));
        assertNotEquals(first, ClientOrderIds.forSequence("strategy-2", 0));
    }

    @Test
    void forSequence_derivesANameBasedUuid() {
        assertEquals(3, ClientOrderIds.forSequence("strategy-1", 7).version());
    }
}
