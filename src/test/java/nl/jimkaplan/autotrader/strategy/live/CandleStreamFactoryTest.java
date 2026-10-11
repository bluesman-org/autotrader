package nl.jimkaplan.autotrader.strategy.live;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class CandleStreamFactoryTest {

    @Test
    void createsAStreamWithTheConfiguredPolicy() {
        CandleStreamFactory factory = new CandleStreamFactory(URI.create("wss://example.test/ws"),
                30, 5_000, unusedConnector());

        BitvavoCandleStream stream = factory.create("BTC-EUR", "1h", 3_600_000L, openTime -> {
        });

        assertNotNull(stream);
        stream.stop();
    }

    @Test
    void theDefaultConstructorTargetsBitvavo() {
        CandleStreamFactory factory = new CandleStreamFactory("wss://ws.bitvavo.com/v2/", 30, 5_000);

        BitvavoCandleStream stream = factory.create("BTC-EUR", "1h", 3_600_000L, openTime -> {
        });

        assertNotNull(stream);
        stream.stop();
    }

    private static WebSocketConnector unusedConnector() {
        return (uri, listener) -> {
            throw new UnsupportedOperationException("not connected in this test");
        };
    }
}
