package nl.jimkaplan.autotrader.bitvavo.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(
        properties = {
                "BITVAVO_API_KEY=test-api-key",
                "BITVAVO_API_SECRET=test-api-secret"
        }
)
@ActiveProfiles("test")
class BitvavoConfigTest {

    @Autowired
    private BitvavoConfig bitvavoConfig;

    @Test
    void contextLoads() {
        // Validate that the configuration is loaded with test values
        assertEquals("test-api-key", bitvavoConfig.getApiKey());
        assertEquals("test-api-secret", bitvavoConfig.getApiSecret());
    }

    @TestConfiguration
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