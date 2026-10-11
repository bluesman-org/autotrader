package nl.jimkaplan.autotrader.strategy.live;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class BitvavoCandleStreamTest {

    private static final URI URI = java.net.URI.create("wss://ws.bitvavo.com/v2/");
    private static final long HOUR = 3_600_000L;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<Long> closed = new CopyOnWriteArrayList<>();
    private final FakeConnector connector = new FakeConnector();

    private BitvavoCandleStream stream() {
        return new BitvavoCandleStream(URI, "BTC-EUR", "1h", HOUR, 1, 20, connector, objectMapper,
                closed::add);
    }

    @Test
    void subscribesOnOpenAndKeepsTheConnectionAlive() {
        BitvavoCandleStream stream = stream();
        stream.start();

        FakeSession session = awaitSession(0);
        JsonNode subscribe = read(session.sent.get(0));
        assertEquals("subscribe", subscribe.path("action").asText());
        assertEquals("candles", subscribe.path("channels").get(0).path("name").asText());
        assertEquals("BTC-EUR", subscribe.path("channels").get(0).path("markets").get(0).asText());
        assertEquals("1h", subscribe.path("channels").get(0).path("interval").get(0).asText());
        assertTrue(stream.isConnected());

        await(() -> session.sent.stream().anyMatch(message -> message.contains("getTime")), 3_000);

        stream.stop();
        assertTrue(session.closed);
        assertFalse(stream.isConnected());
    }

    @Test
    void announcesEachClosedCandleOnceAndIgnoresNoise() {
        BitvavoCandleStream stream = stream();
        stream.start();
        awaitSession(0);
        WebSocketConnector.SessionListener listener = connector.listener;

        // The first frame is the candle in progress: nothing has closed yet.
        listener.onText(candleEvent(1_000 * HOUR));
        assertTrue(closed.isEmpty());

        listener.onText(candleEvent(1_001 * HOUR));
        assertEquals(List.of(1_000 * HOUR), closed);

        listener.onText(candleEvent(1_002 * HOUR));
        assertEquals(List.of(1_000 * HOUR, 1_001 * HOUR), closed);

        // Out-of-order, malformed and non-candle frames change nothing.
        listener.onText(candleEvent(1_001 * HOUR));
        listener.onText("not json at all");
        listener.onText("{\"event\":\"subscribed\"}");
        listener.onText("{\"event\":\"candles\",\"market\":\"BTC-EUR\",\"interval\":\"1h\",\"candle\":[]}");
        assertEquals(2, closed.size());
        stream.stop();
    }

    @Test
    void acceptsTheLiveEventShapeWithANumericOpenTime() {
        BitvavoCandleStream stream = stream();
        stream.start();
        awaitSession(0);
        WebSocketConnector.SessionListener listener = connector.listener;

        listener.onText(liveCandleEvent(1_000 * HOUR));
        listener.onText(liveCandleEvent(1_001 * HOUR));

        assertEquals(List.of(1_000 * HOUR), closed);
        stream.stop();
    }

    @Test
    void announcesEveryBoundaryAfterAQuietGap() {
        BitvavoCandleStream stream = stream();
        stream.start();
        awaitSession(0);
        WebSocketConnector.SessionListener listener = connector.listener;

        listener.onText(candleEvent(1_000 * HOUR));
        listener.onText(candleEvent(1_003 * HOUR));

        assertEquals(List.of(1_000 * HOUR, 1_001 * HOUR, 1_002 * HOUR), closed);
        stream.stop();
    }

    @Test
    void retriesFailedConnectsAndThenSubscribesAgain() {
        connector.failuresRemaining = 2;
        BitvavoCandleStream stream = stream();
        stream.start();

        await(() -> connector.sessions.size() > 0 && !connector.sessions.get(0).sent.isEmpty(), 3_000);
        assertTrue(connector.attempts >= 3);
        assertTrue(stream.isConnected());
        stream.stop();
    }

    @Test
    void reconnectsAfterTheSessionDrops() {
        BitvavoCandleStream stream = stream();
        stream.start();
        awaitSession(0);

        connector.listener.onError(new RuntimeException("socket died"));

        await(() -> connector.sessions.size() > 1 && !connector.sessions.get(1).sent.isEmpty(), 3_000);
        assertTrue(connector.sessions.get(1).sent.get(0).contains("subscribe"));
        stream.stop();
    }

    @Test
    void reconnectsAfterTheSessionCloses() {
        BitvavoCandleStream stream = stream();
        stream.start();
        awaitSession(0);

        connector.listener.onClosed("1000 bye");

        await(() -> connector.sessions.size() > 1, 3_000);
        assertTrue(stream.isConnected());
        stream.stop();
    }

    @Test
    void stopEndsReconnecting() {
        BitvavoCandleStream stream = stream();
        stream.start();
        awaitSession(0);

        stream.stop();
        connector.listener.onError(new RuntimeException("late failure"));
        sleep(120);

        assertEquals(1, connector.sessions.size());
    }

    @Test
    void startAfterStopIsRejected() {
        BitvavoCandleStream stream = stream();
        stream.stop();

        assertThrows(IllegalStateException.class, stream::start);
    }

    private FakeSession awaitSession(int index) {
        await(() -> connector.sessions.size() > index, 3_000);
        return connector.sessions.get(index);
    }

    private static void await(BooleanSupplier condition, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep(10);
        }
        fail("Condition not met within " + timeoutMillis + " ms");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    private JsonNode read(String message) {
        try {
            return objectMapper.readTree(message);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String candleEvent(long openTime) {
        return "{\"event\":\"candles\",\"market\":\"BTC-EUR\",\"interval\":\"1h\",\"candle\":[[\""
                + openTime + "\",\"1\",\"1\",\"1\",\"1\",\"1\"]]}";
    }

    /**
     * The shape the live API actually sends: singular event name, numeric open time, string prices.
     */
    private static String liveCandleEvent(long openTime) {
        return "{\"event\":\"candle\",\"market\":\"BTC-EUR\",\"interval\":\"1m\",\"candle\":[["
                + openTime + ",\"74630\",\"74630\",\"74630\",\"74630\",\"0.00319335\"]]}";
    }

    private static final class FakeConnector implements WebSocketConnector {

        final List<FakeSession> sessions = new CopyOnWriteArrayList<>();
        volatile WebSocketConnector.SessionListener listener;
        volatile int attempts;
        volatile int failuresRemaining;

        @Override
        public WebSocketConnector.WebSocketSession connect(URI uri,
                                                           WebSocketConnector.SessionListener listener) {
            attempts++;
            if (failuresRemaining > 0) {
                failuresRemaining--;
                throw new RuntimeException("connect failed");
            }
            FakeSession session = new FakeSession();
            sessions.add(session);
            this.listener = listener;
            listener.onOpen(session);
            return session;
        }
    }

    private static final class FakeSession implements WebSocketConnector.WebSocketSession {

        final List<String> sent = new CopyOnWriteArrayList<>();
        volatile boolean closed;

        @Override
        public void send(String message) {
            sent.add(message);
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
