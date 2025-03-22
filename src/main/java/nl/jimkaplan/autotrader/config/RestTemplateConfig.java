package nl.jimkaplan.autotrader.config;

import nl.jimkaplan.autotrader.interceptor.CurlLoggingInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;

/**
 * Configuration for RestTemplate.
 */
@Configuration
public class RestTemplateConfig {

    private final CurlLoggingInterceptor curlLoggingInterceptor;

    public RestTemplateConfig(CurlLoggingInterceptor curlLoggingInterceptor) {
        this.curlLoggingInterceptor = curlLoggingInterceptor;
    }

    /**
     * Creates a RestTemplate bean.
     *
     * @return RestTemplate instance
     */
    @Bean
    public RestTemplate restTemplate() {
        RestTemplate restTemplate = new RestTemplate();
        restTemplate.setInterceptors(Collections.singletonList(curlLoggingInterceptor));
        return restTemplate;
    }
}
