package nl.jimkaplan.autotrader.bitvavo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketFormatTest {

    @Test
    void toMarket_withoutDash_insertsDashBeforeEur() {
        assertEquals("BTC-EUR", MarketFormat.toMarket("BTCEUR"));
    }

    @Test
    void toMarket_withDash_keepsMarket() {
        assertEquals("BTC-EUR", MarketFormat.toMarket("BTC-EUR"));
    }

    @Test
    void toMarket_withBaseEndingInEur_insertsDashAtCorrectPlace() {
        assertEquals("EURC-EUR", MarketFormat.toMarket("EURC-EUR"));
        assertEquals("EURC-EUR", MarketFormat.toMarket("EURCEUR"));
    }

    @Test
    void toMarket_withoutEurQuote_leavesMarketUnchanged() {
        assertEquals("BTCUSDT", MarketFormat.toMarket("BTCUSDT"));
    }

    @Test
    void toMarket_withIllegalCharacters_throws() {
        assertThrows(IllegalArgumentException.class, () -> MarketFormat.toMarket("../../account"));
        assertThrows(IllegalArgumentException.class, () -> MarketFormat.toMarket("btc-eur"));
        assertThrows(IllegalArgumentException.class, () -> MarketFormat.toMarket("BTC EUR"));
        assertThrows(IllegalArgumentException.class, () -> MarketFormat.toMarket("BTC-EUR#"));
    }

    @Test
    void theEuroQuoteHelpersFollowTheSymbol() {
        assertTrue(MarketFormat.isEuroQuoted("BTC-EUR"));
        assertFalse(MarketFormat.isEuroQuoted("BTCUSDT"));
        assertFalse(MarketFormat.isEuroQuoted(null));

        assertEquals("BTC-EUR", MarketFormat.requireEuroQuoted("BTC-EUR"));
        assertThrows(IllegalArgumentException.class,
                () -> MarketFormat.requireEuroQuoted("BTCUSDT"));
    }
}
