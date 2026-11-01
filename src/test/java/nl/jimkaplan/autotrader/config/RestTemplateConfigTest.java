package nl.jimkaplan.autotrader.config;

import tools.jackson.databind.ObjectMapper;
import nl.jimkaplan.autotrader.bitvavo.model.GetMarketResponse;
import nl.jimkaplan.autotrader.bitvavo.model.Transaction;
import nl.jimkaplan.autotrader.interceptor.CurlLoggingInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.AbstractClientHttpRequestFactoryWrapper;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * The clients' wiring: the signed client stays unbounded and the public one bounded, and both read
 * Bitvavo's responses the way Bitvavo writes them — every timestamp is Unix milliseconds, and the
 * responses grow fields over time. Plain Jackson reads a number on an {@code Instant} as seconds,
 * a year in the far future.
 */
class RestTemplateConfigTest {

    private final RestTemplateConfig restTemplateConfig = new RestTemplateConfig(mock(CurlLoggingInterceptor.class));

    @Test
    void restTemplate_keepsTheUnboundedFactoryAndTheInterceptor() {
        RestTemplate restTemplate = restTemplateConfig.restTemplate();

        assertEquals(1, restTemplate.getInterceptors().size());
        assertNotSame(delegateOf(restTemplateConfig.publicRestTemplate()), delegateOf(restTemplate));
        SimpleClientHttpRequestFactory simpleFactory = assertInstanceOf(SimpleClientHttpRequestFactory.class,
                delegateOf(restTemplate));
        // No positive timeout is imposed on the signed client (Spring's own default applies)
        assertTrue((Integer) ReflectionTestUtils.getField(simpleFactory, "connectTimeout") <= 0);
        assertTrue((Integer) ReflectionTestUtils.getField(simpleFactory, "readTimeout") <= 0);
    }

    @Test
    void publicRestTemplate_isBounded() {
        RestTemplate publicRestTemplate = restTemplateConfig.publicRestTemplate();

        assertEquals(1, publicRestTemplate.getInterceptors().size());
        SimpleClientHttpRequestFactory factory = assertInstanceOf(SimpleClientHttpRequestFactory.class,
                delegateOf(publicRestTemplate));
        assertEquals(RestTemplateConfig.PUBLIC_CONNECT_TIMEOUT_MILLIS,
                ReflectionTestUtils.getField(factory, "connectTimeout"));
        assertEquals(RestTemplateConfig.PUBLIC_READ_TIMEOUT_MILLIS,
                ReflectionTestUtils.getField(factory, "readTimeout"));
    }

    @Test
    void theTradingClientReadsMillisecondTimestamps() throws Exception {
        assertEquals(Instant.parse("2024-01-24T12:50:50.751Z"), read(restTemplateConfig.restTemplate()));
    }

    @Test
    void thePublicClientReadsMillisecondTimestamps() throws Exception {
        assertEquals(Instant.parse("2024-01-24T12:50:50.751Z"), read(restTemplateConfig.publicRestTemplate()));
    }

    @Test
    void theTradingClientIgnoresFieldsTheExchangeAdds() throws Exception {
        GetMarketResponse market = jacksonMapperOf(restTemplateConfig.restTemplate()).readValue(
                "{\"market\":\"BTC-EUR\",\"price\":\"76328\",\"status\":\"trading\",\"unknownFutureField\":1}",
                GetMarketResponse.class);

        assertEquals("BTC-EUR", market.getMarket());
    }

    private static Instant read(RestTemplate restTemplate) throws Exception {
        return jacksonMapperOf(restTemplate)
                .readValue("{\"executedAt\":1706100650751,\"newField\":true}", Transaction.class)
                .getExecutedAt();
    }

    private static ObjectMapper jacksonMapperOf(RestTemplate restTemplate) {
        return restTemplate.getMessageConverters().stream()
                .filter(JacksonJsonHttpMessageConverter.class::isInstance)
                .map(JacksonJsonHttpMessageConverter.class::cast)
                .map(JacksonJsonHttpMessageConverter::getMapper)
                .findFirst()
                .orElseThrow();
    }

    /**
     * Setting interceptors wraps the configured factory; unwrap it to inspect the delegate.
     */
    private static ClientHttpRequestFactory delegateOf(RestTemplate restTemplate) {
        ClientHttpRequestFactory factory = restTemplate.getRequestFactory();
        if (factory instanceof AbstractClientHttpRequestFactoryWrapper wrapper) {
            return wrapper.getDelegate();
        }
        return factory;
    }
}
