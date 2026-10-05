package nl.jimkaplan.autotrader.bitvavo.client;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import nl.jimkaplan.autotrader.bitvavo.model.GetMarketResponse;
import nl.jimkaplan.autotrader.bitvavo.model.GetPriceResponse;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Parses live Bitvavo API response payloads (captured 2026-10-05) to keep the models aligned
 * with what the exchange actually returns.
 *
 * <p>With {@code ?market=...} specified, both /ticker/price and /markets return a single object,
 * not an array (the array form is only returned when the parameter is omitted). The API returns
 * more fields than the models declare (e.g. {@code status}, {@code tickSize}); the test mapper
 * ignores unknown properties, matching Spring Boot's auto-configured ObjectMapper.</p>
 */
class BitvavoLivePayloadTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test
    void deserializeTickerPricePayload_withMarket_returnsSingleObject() throws Exception {
        // Captured from GET /v2/ticker/price?market=BTC-EUR
        String livePayload = "{\"market\":\"BTC-EUR\",\"price\":\"76328\"}";

        GetPriceResponse price = objectMapper.readValue(livePayload, GetPriceResponse.class);

        assertEquals("BTC-EUR", price.getMarket());
        assertEquals(new BigDecimal("76328"), price.getPrice());
    }

    @Test
    void deserializeMarketsPayload_withMarket_returnsSingleObjectWithDecimals() throws Exception {
        // Captured from GET /v2/markets?market=BTC-EUR
        String livePayload = "{\"market\":\"BTC-EUR\",\"status\":\"trading\",\"base\":\"BTC\",\"quote\":\"EUR\","
                + "\"pricePrecision\":null,\"minOrderInBaseAsset\":\"0.00006607\",\"minOrderInQuoteAsset\":\"5.00\","
                + "\"maxOrderInBaseAsset\":\"13213.19107219\",\"maxOrderInQuoteAsset\":\"1000000000.00\","
                + "\"quantityDecimals\":8,\"notionalDecimals\":2,\"tickSize\":\"1.00\",\"maxOpenOrders\":400,"
                + "\"feeCategory\":\"A\",\"orderTypes\":[\"market\",\"limit\",\"stopLoss\",\"stopLossLimit\","
                + "\"takeProfit\",\"takeProfitLimit\"]}";

        GetMarketResponse market = objectMapper.readValue(livePayload, GetMarketResponse.class);

        assertEquals("BTC-EUR", market.getMarket());
        assertEquals("BTC", market.getBase());
        assertEquals("EUR", market.getQuote());
        assertNotNull(market.getQuantityDecimals());
        assertNotNull(market.getNotionalDecimals());
        assertEquals(8, market.getQuantityDecimals());
        assertEquals(2, market.getNotionalDecimals());
    }
}
