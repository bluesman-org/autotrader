package nl.jimkaplan.autotrader.config;

import nl.jimkaplan.autotrader.interceptor.CurlLoggingInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.AbstractClientHttpRequestFactoryWrapper;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RestTemplateConfigTest {

    private final RestTemplateConfig restTemplateConfig = new RestTemplateConfig(new CurlLoggingInterceptor());

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
