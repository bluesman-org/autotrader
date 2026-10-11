package nl.jimkaplan.autotrader.strategy.live;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;

/**
 * Builds candle streams with the configured endpoint, keepalive and reconnect policy.
 */
@Component
public class CandleStreamFactory {

    private final URI webSocketUri;
    private final long keepaliveSeconds;
    private final long reconnectDelayMillis;
    private final WebSocketConnector connector;

    /**
     * @param webSocketUrl         Bitvavo's WebSocket endpoint
     * @param keepaliveSeconds     Idle keepalive period
     * @param reconnectDelayMillis Base delay for the reconnect backoff
     */
    @Autowired
    public CandleStreamFactory(
            @Value("${strategy.live.websocket-url:wss://ws.bitvavo.com/v2/}") String webSocketUrl,
            @Value("${strategy.live.keepalive-seconds:30}") long keepaliveSeconds,
            @Value("${strategy.live.reconnect-delay-millis:5000}") long reconnectDelayMillis) {
        this(URI.create(webSocketUrl), keepaliveSeconds, reconnectDelayMillis, new JdkWebSocketConnector());
    }

    CandleStreamFactory(URI webSocketUri, long keepaliveSeconds, long reconnectDelayMillis,
                        WebSocketConnector connector) {
        this.webSocketUri = webSocketUri;
        this.keepaliveSeconds = keepaliveSeconds;
        this.reconnectDelayMillis = reconnectDelayMillis;
        this.connector = connector;
    }

    /**
     * A stream for one market and interval.
     *
     * @param market        Market in Bitvavo format
     * @param interval      Candle interval code
     * @param intervalMillis The interval's length in milliseconds
     * @param listener      The closed-candle callback
     * @return A new, not yet started stream
     */
    public BitvavoCandleStream create(String market, String interval, long intervalMillis,
                                      BitvavoCandleStream.CandleListener listener) {
        return new BitvavoCandleStream(webSocketUri, market, interval, intervalMillis,
                keepaliveSeconds, reconnectDelayMillis, connector, new ObjectMapper(), listener);
    }
}
