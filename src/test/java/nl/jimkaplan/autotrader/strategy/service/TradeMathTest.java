package nl.jimkaplan.autotrader.strategy.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TradeMathTest {

    @Test
    void firstNonNull_prefersTheValueAndFallsBackToTheOther() {
        assertEquals(new BigDecimal("1"), TradeMath.firstNonNull(new BigDecimal("1"), new BigDecimal("2")));
        assertEquals(new BigDecimal("2"), TradeMath.firstNonNull(null, new BigDecimal("2")));
        assertNull(TradeMath.firstNonNull(null, null));
    }

    @Test
    void filled_usesTheValueOrTheFallback() {
        assertEquals(1.5, TradeMath.filled(new BigDecimal("1.5"), 9.0));
        assertEquals(9.0, TradeMath.filled(null, 9.0));
    }

    @Test
    void amount_usesTheValueTheFallbackOrNull() {
        assertEquals(1.5, TradeMath.amount(new BigDecimal("1.5"), new BigDecimal("2.5")));
        assertEquals(2.5, TradeMath.amount(null, new BigDecimal("2.5")));
        assertNull(TradeMath.amount(null, null));
    }

    @Test
    void ratio_dividesHalfUpToEightDecimalsOrReturnsNull() {
        assertEquals(new BigDecimal("3.00000000"), TradeMath.ratio(new BigDecimal("3"), 1.0));
        assertNull(TradeMath.ratio(null, 1.0));
        assertNull(TradeMath.ratio(new BigDecimal("3"), 0.0));
    }

    @Test
    void price_dividesTheFilledAmountsOrReturnsNull() {
        assertEquals(100000.0, TradeMath.price(new BigDecimal("0.001"), new BigDecimal("100")), 1e-9);
        assertNull(TradeMath.price(null, new BigDecimal("100")));
        assertNull(TradeMath.price(new BigDecimal("0.001"), null));
        assertNull(TradeMath.price(BigDecimal.ZERO, new BigDecimal("100")));
    }

    @Test
    void closeProfit_subtractsTheCostBasisAndTheFees() {
        assertEquals(9.58, TradeMath.closeProfit(110.0, 0.001, 100000.0, 0.2, 0.22), 1e-9);
        assertEquals(9.78, TradeMath.closeProfit(110.0, 0.001, 100000.0, null, 0.22), 1e-9);
    }

    @Test
    void zeroIfNull_replacesNullWithZero() {
        assertEquals(0.0, TradeMath.zeroIfNull(null));
        assertEquals(3.0, TradeMath.zeroIfNull(3.0));
    }
}
