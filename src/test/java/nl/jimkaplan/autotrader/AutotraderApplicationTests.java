package nl.jimkaplan.autotrader;

import nl.jimkaplan.autotrader.config.BitvavoConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class AutotraderApplicationTests {

    @Configuration
    static class TestConfig {

        @Bean
        @Primary
        public BitvavoConfig bitvavoConfig() {
            BitvavoConfig config = new BitvavoConfig();
            config.setApiKey("test-api-key");
            config.setApiSecret("test-api-secret");
            config.setApiUrl("https://api.bitvavo.com/v2");
            config.setWindow(10000);
            return config;
        }
    }
}
