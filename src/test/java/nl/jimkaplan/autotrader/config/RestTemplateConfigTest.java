package nl.jimkaplan.autotrader.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import nl.jimkaplan.autotrader.bitvavo.model.Transaction;
import nl.jimkaplan.autotrader.interceptor.CurlLoggingInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/**
 * Bitvavo writes every timestamp as Unix milliseconds and its responses grow fields over time, so
 * the clients' Jackson converters must read millisecond numbers and ignore unknown fields. Plain
 * Jackson reads a number on an {@code Instant} as seconds — a year in the far future.
 */
class RestTemplateConfigTest {

    private final RestTemplateConfig config = new RestTemplateConfig(mock(CurlLoggingInterceptor.class));

    @Test
    void theTradingClientReadsMillisecondTimestamps() throws Exception {
        assertEquals(Instant.parse("2024-01-24T12:50:50.751Z"), read(config.restTemplate()));
    }

    @Test
    void thePublicClientReadsMillisecondTimestamps() throws Exception {
        assertEquals(Instant.parse("2024-01-24T12:50:50.751Z"), read(config.publicRestTemplate()));
    }

    private static Instant read(RestTemplate restTemplate) throws Exception {
        ObjectMapper mapper = restTemplate.getMessageConverters().stream()
                .filter(MappingJackson2HttpMessageConverter.class::isInstance)
                .map(MappingJackson2HttpMessageConverter.class::cast)
                .map(MappingJackson2HttpMessageConverter::getObjectMapper)
                .findFirst()
                .orElseThrow();
        return mapper.readValue("{\"executedAt\":1706100650751,\"newField\":true}", Transaction.class)
                .getExecutedAt();
    }
}
