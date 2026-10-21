package nl.jimkaplan.autotrader.strategy.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveOrderStatusTest {

    @Test
    void isTerminalNonFill_classifiesTheExchangeStatuses() {
        assertTrue(LiveOrderStatus.isTerminalNonFill("canceledIOC"));
        assertTrue(LiveOrderStatus.isTerminalNonFill("CANCELED_MARKET_PROTECTION"));
        assertTrue(LiveOrderStatus.isTerminalNonFill("expired"));
        assertTrue(LiveOrderStatus.isTerminalNonFill("rejected"));
        assertFalse(LiveOrderStatus.isTerminalNonFill("partiallyFilled"));
        assertFalse(LiveOrderStatus.isTerminalNonFill("someFutureStatus"));
        assertFalse(LiveOrderStatus.isTerminalNonFill(null));
    }

    @Test
    void partialFillNote_describesOnlyAPositiveFill() {
        assertEquals("", LiveOrderStatus.partialFillNote(null));
        assertEquals("", LiveOrderStatus.partialFillNote(BigDecimal.ZERO));
        assertEquals(" (partial fill of 50.00 quote not booked)",
                LiveOrderStatus.partialFillNote(new BigDecimal("50.00")));
    }
}
