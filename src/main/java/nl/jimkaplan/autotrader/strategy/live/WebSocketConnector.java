package nl.jimkaplan.autotrader.strategy.live;

import java.net.URI;

/**
 * The transport behind {@link BitvavoCandleStream}: a thin seam so the keepalive and reconnect
 * policy can be tested without a socket.
 */
public interface WebSocketConnector {

    /**
     * Connect and hand back a session. The listener's {@code onOpen} is delivered with the session
     * already built, so the caller can send the subscription from it.
     *
     * @param uri      The WebSocket endpoint
     * @param listener The session callback
     * @return The open session
     * @throws RuntimeException if the connection cannot be established
     */
    WebSocketSession connect(URI uri, SessionListener listener);

    /**
     * What the stream hears from the socket.
     */
    interface SessionListener {

        /**
         * The session is open.
         *
         * @param session The open session, the same instance {@link #connect} returns
         */
        void onOpen(WebSocketSession session);

        /**
         * A complete text message arrived.
         *
         * @param message The message
         */
        void onText(String message);

        /**
         * The session closed, cleanly or not.
         *
         * @param reason A description for the log
         */
        void onClosed(String reason);

        /**
         * The session failed.
         *
         * @param error The failure
         */
        void onError(Throwable error);
    }

    /**
     * An open session.
     */
    interface WebSocketSession {

        /**
         * Send a text message.
         *
         * @param message The message
         * @throws RuntimeException if the send fails
         */
        void send(String message);

        /**
         * Close the session.
         */
        void close();
    }
}
