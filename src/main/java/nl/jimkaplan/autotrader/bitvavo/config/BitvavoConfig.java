package nl.jimkaplan.autotrader.bitvavo.config;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "bitvavo")
public class BitvavoConfig {
    @Value("${bitvavo.apiUrl}")
    private String apiUrl;
}