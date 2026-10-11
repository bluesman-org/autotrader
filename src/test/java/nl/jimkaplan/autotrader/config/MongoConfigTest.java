package nl.jimkaplan.autotrader.config;

import com.mongodb.ReadPreference;
import com.mongodb.WriteConcern;
import com.mongodb.client.MongoClient;
import com.mongodb.MongoClientSettings;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MongoConfigTest {

    @Test
    void getDatabaseName_returnsTheConfiguredName() {
        MongoConfig config = new MongoConfig();
        ReflectionTestUtils.setField(config, "databaseName", "autotrader");

        assertEquals("autotrader", config.getDatabaseName());
    }

    @Test
    void autoIndexCreation_reflectsTheConfiguredProperty() {
        MongoConfig config = new MongoConfig();
        assertFalse(config.autoIndexCreation());

        ReflectionTestUtils.setField(config, "autoIndexCreation", true);
        assertTrue(config.autoIndexCreation());
    }

    @Test
    void mongoClientSettings_wireTheConfiguredTimeoutsPoolAndConsistency() {
        MongoConfig config = configWithTls(false);

        MongoClientSettings settings = config.mongoClientSettings();

        assertFalse(settings.getSslSettings().isEnabled());
        assertEquals(10, settings.getClusterSettings().getServerSelectionTimeout(TimeUnit.SECONDS));
        assertEquals(10, settings.getSocketSettings().getConnectTimeout(TimeUnit.SECONDS));
        assertEquals(15, settings.getSocketSettings().getReadTimeout(TimeUnit.SECONDS));
        assertEquals(50, settings.getConnectionPoolSettings().getMaxSize());
        assertEquals(10, settings.getConnectionPoolSettings().getMinSize());
        assertEquals(15, settings.getConnectionPoolSettings().getMaxWaitTime(TimeUnit.SECONDS));
        assertEquals(ReadPreference.secondaryPreferred(), settings.getReadPreference());
        assertEquals(WriteConcern.MAJORITY, settings.getWriteConcern());
    }

    @Test
    void mongoClientSettings_reflectTheTlsFlag() {
        assertTrue(configWithTls(true).mongoClientSettings().getSslSettings().isEnabled());
    }

    @Test
    void mongoClient_buildsAClientFromTheSettings() {
        // Building a client does not connect; this is a smoke test for the wiring itself
        MongoClient client = configWithTls(false).mongoClient();
        try {
            assertNotNull(client);
        } finally {
            client.close();
        }
    }

    private MongoConfig configWithTls(boolean sslEnabled) {
        MongoConfig config = new MongoConfig();
        ReflectionTestUtils.setField(config, "mongoUri", "mongodb://localhost:27017/autotrader");
        ReflectionTestUtils.setField(config, "sslEnabled", sslEnabled);
        return config;
    }
}
