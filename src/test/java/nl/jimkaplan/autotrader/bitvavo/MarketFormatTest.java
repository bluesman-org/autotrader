package nl.jimkaplan.autotrader.bitvavo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
