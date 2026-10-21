package nl.jimkaplan.autotrader.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import nl.jimkaplan.autotrader.bitvavo.model.Transaction;
import nl.jimkaplan.autotrader.interceptor.CurlLoggingInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.AbstractClientHttpRequestFactoryWrapper;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
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
