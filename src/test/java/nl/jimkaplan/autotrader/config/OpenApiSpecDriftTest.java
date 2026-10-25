package nl.jimkaplan.autotrader.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import nl.jimkaplan.autotrader.strategy.live.LiveStrategyStarter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Fails when the committed OpenAPI files drift from what the running application exports.
 *
 * <p>{@link OpenApiConfig} rewrites {@code src/main/resources/static/api-docs} on a normal startup,
 * but CI never runs a full server with the export enabled, so an annotation change could leave the
 * committed copies stale until someone repeats the manual 8081 procedure. This test boots the app
 * on a random port with the file export disabled, fetches {@code /v3/api-docs} over the loopback
 * (the security rule that lets the exporter read it), and compares the parsed specification with
 * the committed JSON. Only the runtime-specific {@code servers} block is ignored.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.data.mongodb.uri=mongodb://localhost:27017/autotrader-openapi-test",
        "bitvavo.api.url=http://localhost:9999",
        "encryption.master-key=dGVzdC1rZXk=",
        "openapi.export-enabled=false",
        "openapi.export-dir=target/openapi-drift-check",
        "openapi.export-startup-delay-ms=0"
})
class OpenApiSpecDriftTest {

    @LocalServerPort
    private int port;

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
    void theCommittedSpecMatchesTheRunningApi() throws Exception {
        String body = rest.getForObject(
                "http://localhost:" + port + "/autotrader/v3/api-docs", String.class);

        ObjectMapper mapper = new ObjectMapper();
        JsonNode expected = mapper.readTree(new ClassPathResource(
                "static/api-docs/autotrader-openapi.json").getInputStream());
        JsonNode actual = mapper.readTree(body);

        ((ObjectNode) expected).remove("servers");
        ((ObjectNode) actual).remove("servers");

        assertEquals(expected, actual,
                "The committed OpenAPI spec is stale. Regenerate it with the clean 8081 startup "
                        + "procedure and commit both files.");
    }
}
