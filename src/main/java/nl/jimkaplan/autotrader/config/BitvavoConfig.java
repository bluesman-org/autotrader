package nl.jimkaplan.autotrader.config;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
public class BitvavoConfig {
    @Value("${bitvavo.apiKey}")
    private String apiKey;
    @Value("${bitvavo.apiSecret}")
    private String apiSecret;
    @Value("${bitvavo.apiUrl}")
    private String apiUrl;
    @Value("${bitvavo.window}")
    private int window;
}