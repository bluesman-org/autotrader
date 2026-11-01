package nl.jimkaplan.autotrader.config;

import nl.jimkaplan.autotrader.interceptor.CurlLoggingInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.Collections;
import java.util.List;

/**
 * Configuration for the app's RestTemplate beans.
 *
 * <p>Signed trading calls and public market-data calls use separate templates on purpose.
 * The trading client keeps its previous, unbounded socket configuration: a read timeout on
 * an order submission is not a safe failure — {@code TradingService} records the order as
 * FAILED, and retrying the alert could duplicate an order the exchange actually accepted.
 * The public client is bounded, because candle backfills page through the API while holding
 * a per-market lock, where a stalled socket would block the loader indefinitely.</p>
 */
@Configuration
public class RestTemplateConfig {

    static final int PUBLIC_CONNECT_TIMEOUT_MILLIS = 10_000;
    static final int PUBLIC_READ_TIMEOUT_MILLIS = 15_000;

    private final CurlLoggingInterceptor curlLoggingInterceptor;

    public RestTemplateConfig(CurlLoggingInterceptor curlLoggingInterceptor) {
        this.curlLoggingInterceptor = curlLoggingInterceptor;
    }

    /**
     * Client for signed Bitvavo calls (orders, balances, account data).
     *
     * @return RestTemplate instance
     */
    @Bean
    public RestTemplate restTemplate() {
        RestTemplate restTemplate = new RestTemplate();
        restTemplate.setInterceptors(Collections.singletonList(curlLoggingInterceptor));
        configureJackson(restTemplate);
        return restTemplate;
    }

    /**
     * Client for public Bitvavo market data (candlesticks) with bounded connect and read
     * timeouts.
     *
     * @return RestTemplate instance
     */
    @Bean
    public RestTemplate publicRestTemplate() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(PUBLIC_CONNECT_TIMEOUT_MILLIS);
        requestFactory.setReadTimeout(PUBLIC_READ_TIMEOUT_MILLIS);

        RestTemplate restTemplate = new RestTemplate(requestFactory);
        restTemplate.setInterceptors(Collections.singletonList(curlLoggingInterceptor));
        configureJackson(restTemplate);
        return restTemplate;
    }

    /**
     * Bitvavo writes every timestamp as Unix milliseconds. Jackson reads a number on an
     * {@code Instant} as seconds with a nanosecond fraction by default, which turns a millisecond
     * value into a year in the far future, so the feature is switched off for both clients — here,
     * not on the application's own mapper, which serves the REST API and the OpenAPI export.
     *
     * @param restTemplate The client whose Jackson converter reads Bitvavo responses
     */
    private static void configureJackson(RestTemplate restTemplate) {
        JsonMapper mapper = JsonMapper.builder()
                .disable(DateTimeFeature.READ_DATE_TIMESTAMPS_AS_NANOSECONDS)
                .build();
        // The converter takes its mapper at construction in Spring Framework 7; there is no
        // setter, so the one RestTemplate registered is replaced in place.
        List<HttpMessageConverter<?>> converters = restTemplate.getMessageConverters();
        for (int i = 0; i < converters.size(); i++) {
            if (converters.get(i) instanceof JacksonJsonHttpMessageConverter) {
                converters.set(i, new JacksonJsonHttpMessageConverter(mapper));
            }
        }
    }
}
