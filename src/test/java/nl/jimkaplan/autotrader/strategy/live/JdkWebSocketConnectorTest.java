package nl.jimkaplan.autotrader.strategy.live;

import org.junit.jupiter.api.Test;

import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the parts of the JDK connector that make decisions: frame accumulation, forwarding and
 * the session's send/close calls.
 */
class JdkWebSocketConnectorTest {

    private final List<String> received = new ArrayList<>();
    private final StubWebSocket socket = new StubWebSocket();
    private final JdkWebSocketConnector.Adapter adapter =
            new JdkWebSocketConnector.Adapter(new WebSocketConnector.SessionListener() {
                @Override
                public void onOpen(WebSocketConnector.WebSocketSession session) {
                    received.add("opened");
                }

                @Override
                public void onText(String message) {
                    received.add(message);
                }

                @Override
                public void onClosed(String reason) {
                    received.add("closed:" + reason);
                }

                @Override
                public void onError(Throwable error) {
                    received.add("error:" + error.getMessage());
                }
            });

    @Test
    void openingTheSocketRequestsTheFirstFrame() {
        adapter.onOpen(socket);

        assertEquals(1, socket.requests);
    }

    @Test
    void accumulatesTextFragmentsUntilTheLastOne() {
        adapter.onText(socket, "{\"event\":\"cand", false);
        assertTrue(received.isEmpty());

        adapter.onText(socket, "les\"}", true);

        assertEquals(List.of("{\"event\":\"candles\"}"), received);
        assertEquals(2, socket.requests);
    }

    @Test
    void forwardsCloseAndError() {
        adapter.onClose(socket, 1000, "bye");
        adapter.onError(socket, new RuntimeException("boom"));

        assertEquals(List.of("closed:1000 bye", "error:boom"), received);
    }

    @Test
    void sessionsSendTextAndCloseThroughTheSocket() {
        JdkWebSocketConnector.JdkSession session = new JdkWebSocketConnector.JdkSession(socket);

        session.send("hello");
        session.close();

        assertEquals(List.of("hello"), socket.sent);
        assertEquals(WebSocket.NORMAL_CLOSURE, socket.closeCode);
    }

    private static final class StubWebSocket implements WebSocket {

        final List<String> sent = new ArrayList<>();
        long requests;
        int closeCode = -1;

        @Override
        public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) {
            sent.add(data.toString());
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendClose(int statusCode, String reason) {
            closeCode = statusCode;
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public void request(long n) {
            requests += n;
        }

        @Override
        public CompletableFuture<WebSocket> sendBinary(ByteBuffer data, boolean last) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public CompletableFuture<WebSocket> sendPing(ByteBuffer message) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public CompletableFuture<WebSocket> sendPong(ByteBuffer message) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public String getSubprotocol() {
            return "";
        }

        @Override
        public boolean isOutputClosed() {
            return false;
        }

        @Override
        public boolean isInputClosed() {
            return false;
        }

        @Override
        public void abort() {
        }
    }
}
