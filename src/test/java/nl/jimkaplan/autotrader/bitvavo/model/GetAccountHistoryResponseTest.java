package nl.jimkaplan.autotrader.bitvavo.model;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Parses a live Bitvavo account history payload (captured 2026-10-09) to keep the models aligned
 * with what the exchange actually returns. The test mapper mirrors Spring Boot's defaults:
 * Java time support enabled and unknown properties ignored.
 */
class GetAccountHistoryResponseTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test
    void deserializeAccountHistoryPayload_parsesItemsAndPaging() throws Exception {
        String payload = "{\"items\":["
                + "{\"transactionId\":\"f4240be6-15b6-4b47-99c2-c59ef7267a12\","
                + "\"executedAt\":\"2026-10-09T12:16:21.262Z\",\"type\":\"buy\","
                + "\"priceCurrency\":\"EUR\",\"priceAmount\":\"74310\","
                + "\"sentCurrency\":\"EUR\",\"sentAmount\":\"2001.2322066000002\","
                + "\"receivedCurrency\":\"BTC\",\"receivedAmount\":\"0.02693086\","
                + "\"feesCurrency\":\"EUR\",\"feesAmount\":\"2.0077934\",\"address\":null},"
                + "{\"transactionId\":\"538cfc55-ba5b-46cb-8d77-8b715843782b\","
                + "\"executedAt\":\"2026-10-09T12:16:21.229Z\",\"type\":\"buy\","
                + "\"priceCurrency\":\"EUR\",\"priceAmount\":\"74310\","
                + "\"sentCurrency\":\"EUR\",\"sentAmount\":\"914.5264821000001\","
                + "\"receivedCurrency\":\"BTC\",\"receivedAmount\":\"0.01230691\","
                + "\"feesCurrency\":\"EUR\",\"feesAmount\":\"0.9135179\",\"address\":null}"
                + "],\"currentPage\":1,\"totalPages\":24,\"maxItems\":5}";

        GetAccountHistoryResponse response = objectMapper.readValue(payload, GetAccountHistoryResponse.class);

        assertEquals(2, response.getItems().size());
        assertEquals(1, response.getCurrentPage());
        assertEquals(24, response.getTotalPages());
        assertEquals(5, response.getMaxItems());

        Transaction first = response.getItems().get(0);
        assertEquals("f4240be6-15b6-4b47-99c2-c59ef7267a12", first.getTransactionId());
        assertEquals(Instant.parse("2026-10-09T12:16:21.262Z"), first.getExecutedAt());
        assertEquals("buy", first.getType());
        assertEquals("EUR", first.getPriceCurrency());
        assertEquals(new BigDecimal("74310"), first.getPriceAmount());
        assertEquals("EUR", first.getSentCurrency());
        assertEquals(new BigDecimal("2001.2322066000002"), first.getSentAmount());
        assertEquals("BTC", first.getReceivedCurrency());
        assertEquals(new BigDecimal("0.02693086"), first.getReceivedAmount());
        assertEquals("EUR", first.getFeesCurrency());
        assertEquals(new BigDecimal("2.0077934"), first.getFeesAmount());
        assertNull(first.getAddress());
    }
}
