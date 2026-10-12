package nl.jimkaplan.autotrader.strategy.live;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.CompletionStage;

/**
 * The JDK {@link HttpClient} WebSocket implementation behind {@link WebSocketConnector}: no extra
 * dependency, and the keepalive/reconnect policy stays with the caller.
 */
public class JdkWebSocketConnector implements WebSocketConnector {

    @Override
    public WebSocketSession connect(URI uri, SessionListener listener) {
        Adapter adapter = new Adapter(listener);
        WebSocket webSocket = HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .buildAsync(uri, adapter)
                .join();
        JdkSession session = new JdkSession(webSocket);
        // Delivered with the session already available, so onOpen can send the subscription.
        listener.onOpen(session);
        return session;
    }

    static final class Adapter implements WebSocket.Listener {

        private final SessionListener listener;
        private final StringBuilder buffer = new StringBuilder();

        Adapter(SessionListener listener) {
            this.listener = listener;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String message = buffer.toString();
                buffer.setLength(0);
                listener.onText(message);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            listener.onClosed(statusCode + " " + reason);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            listener.onError(error);
        }
    }

    record JdkSession(WebSocket webSocket) implements WebSocketSession {

        @Override
        public void send(String message) {
            webSocket.sendText(message, true).join();
        }

        @Override
        public void close() {
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "client closing").join();
        }
    }
}
