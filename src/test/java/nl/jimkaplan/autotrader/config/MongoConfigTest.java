package nl.jimkaplan.autotrader.config;

import com.mongodb.client.MongoClient;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

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
    void mongoClient_buildsAClientFromTheConfiguredUri() {
        // Building a client does not connect, so this covers the settings wiring without a server
        MongoConfig config = new MongoConfig();
        ReflectionTestUtils.setField(config, "mongoUri", "mongodb://localhost:27017/autotrader");
        ReflectionTestUtils.setField(config, "sslEnabled", false);

        MongoClient client = config.mongoClient();
        try {
            assertNotNull(client);
        } finally {
            client.close();
        }
    }
}
