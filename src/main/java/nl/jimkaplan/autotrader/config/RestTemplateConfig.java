package nl.jimkaplan.autotrader.config;

import nl.jimkaplan.autotrader.interceptor.CurlLoggingInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;

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
        return restTemplate;
    }
}
