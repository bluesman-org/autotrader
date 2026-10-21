package nl.jimkaplan.autotrader;

import nl.jimkaplan.autotrader.config.LiveStrategyNameInitializer;
import nl.jimkaplan.autotrader.config.MarketInitializer;
import nl.jimkaplan.autotrader.config.MongoIndexInitializer;
import nl.jimkaplan.autotrader.strategy.live.LiveStrategyStarter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Boots the full application context. A per-class unit test cannot see a bean that fails to wire —
 * a component with two constructors, a missing qualifier — and that is a class of failure this
 * project has hit; one context start catches it.
 *
 * <p>No database is needed: the startup runners are replaced by mocks, nothing touches Mongo
 * before the first request, and the OpenAPI export is switched off
 * ({@code openapi.export-enabled=false}), so its runner returns without sleeping or calling the
 * API (it would otherwise, since Spring runs CommandLineRunners in this environment too). The
 * property overrides stand in for the environment variables the application.yml placeholders
 * expect.</p>
 */
@SpringBootTest(properties = {
        "spring.data.mongodb.uri=mongodb://localhost:27017/autotrader-context-test",
        "bitvavo.api.url=http://localhost:9999",
        "encryption.master-key=dGVzdC1rZXk=",
        "openapi.export-dir=target/context-test-openapi",
        "openapi.export-enabled=false",
        "openapi.export-startup-delay-ms=0"
})
class AutotraderApplicationTests {

    @MockitoBean
    private MongoIndexInitializer mongoIndexInitializer;

    @MockitoBean
    private LiveStrategyStarter liveStrategyStarter;

    @MockitoBean
    private nl.jimkaplan.autotrader.config.BitvavoCredentialsInitializer bitvavoCredentialsInitializer;

    @MockitoBean
    private MarketInitializer marketInitializer;

    @MockitoBean
    private LiveStrategyNameInitializer liveStrategyNameInitializer;

    @Test
    void contextLoads() {
        // The context starting is the assertion.
    }
}
