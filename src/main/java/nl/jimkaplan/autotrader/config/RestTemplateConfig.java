package nl.jimkaplan.autotrader.config;

import nl.jimkaplan.autotrader.interceptor.CurlLoggingInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;

/**
 * Configuration for RestTemplate.
 */
@Configuration
public class RestTemplateConfig {

    private static final int CONNECT_TIMEOUT_MILLIS = 10_000;
    private static final int READ_TIMEOUT_MILLIS = 15_000;

    private final CurlLoggingInterceptor curlLoggingInterceptor;

    public RestTemplateConfig(CurlLoggingInterceptor curlLoggingInterceptor) {
        this.curlLoggingInterceptor = curlLoggingInterceptor;
    }

    /**
     * Creates a RestTemplate bean with bounded connect and read timeouts. A stalled socket
     * must not block the calling thread indefinitely: candle backfills page through the API
     * while holding a per-market lock.
     *
     * @return RestTemplate instance
     */
    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
        requestFactory.setReadTimeout(READ_TIMEOUT_MILLIS);

        RestTemplate restTemplate = new RestTemplate(requestFactory);
        restTemplate.setInterceptors(Collections.singletonList(curlLoggingInterceptor));
        return restTemplate;
    }
}
