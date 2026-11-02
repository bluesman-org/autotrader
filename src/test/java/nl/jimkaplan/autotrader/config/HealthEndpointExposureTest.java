package nl.jimkaplan.autotrader.config;

import nl.jimkaplan.autotrader.strategy.live.LiveStrategyStarter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The health path is open so an external probe can read it, which also means anything in its body
 * is public. The component details — disk free and total space, the SSL chain, the component list —
 * stay inside the process; the probe needs the status.
 *
 * <p>Boot's own default is {@code never}, but the default is silent: a later
 * {@code show-details: always} would leak the host's disk figures without any test noticing. This
 * boots the app on a random port and reads the endpoint the way a probe does, anonymously.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.mongodb.uri=mongodb://localhost:27017/autotrader-health-test",
        "bitvavo.api.url=http://localhost:9999",
        "encryption.master-key=dGVzdC1rZXk=",
        "openapi.export-enabled=false",
        "openapi.export-dir=target/health-endpoint-check",
        "openapi.export-startup-delay-ms=0"
})
@AutoConfigureTestRestTemplate
class HealthEndpointExposureTest {

    @Autowired
    private TestRestTemplate rest;

    @MockitoBean
    private MongoIndexInitializer mongoIndexInitializer;

    @MockitoBean
    private LiveStrategyStarter liveStrategyStarter;

    @MockitoBean
    private BitvavoCredentialsInitializer bitvavoCredentialsInitializer;

    @MockitoBean
    private MarketInitializer marketInitializer;

    @MockitoBean
    private LiveStrategyNameInitializer liveStrategyNameInitializer;

    @Test
    void thePublicHealthEndpointReportsOnlyTheStatus() {
        ResponseEntity<String> response = rest.getForEntity("/actuator/health", String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        JsonNode body = JsonMapper.builder().build().readTree(response.getBody());
        assertEquals("UP", body.path("status").asText());
        assertFalse(body.has("components"), "the public health body must not carry component details");
    }
}
