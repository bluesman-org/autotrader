package nl.jimkaplan.autotrader.strategy.live;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * One Bitvavo WebSocket connection subscribed to the candle channel of one market and interval.
 *
 * <p>Owns the keepalive and reconnect policy: a {@code getTime} action every
 * {@code keepaliveSeconds} (Bitvavo closes connections that stay idle), and a reconnect with
 * exponential backoff capped at {@link #MAX_RECONNECT_DELAY_MILLIS} after any close or failure.</p>
 *
 * <p>The stream reports only <em>boundaries</em>: when a newer candle appears, the candle that just
 * completed is announced by its open time and the caller resolves it from the candle store. The
 * store's REST-filled candles are authoritative, so a trade-free interval cannot be missed and the
 * live evaluation always reads the same data a backtest would.</p>
 *
 * <p>One instance runs one connection and cannot be restarted after {@link #stop()}; start a new
 * instance instead.</p>
 */
@Slf4j
public class BitvavoCandleStream implements WebSocketConnector.SessionListener {

    /**
     * Receives the open times of candles that just completed.
     */
    public interface CandleListener {

        /**
         * A candle completed.
         *
         * @param openTime The closed candle's open time, epoch milliseconds
         */
        void onCandleClosed(long openTime);
    }

    static final long MAX_RECONNECT_DELAY_MILLIS = 60_000;
    private static final int MAX_GAP_BOUNDARIES = 1_000;

    private final URI uri;
    private final String market;
    private final String interval;
    private final long intervalMillis;
    private final long keepaliveSeconds;
    private final long reconnectDelayMillis;
    private final WebSocketConnector connector;
    private final ObjectMapper objectMapper;
    private final CandleListener listener;
    private final ScheduledExecutorService scheduler;

    private volatile WebSocketConnector.WebSocketSession session;
    private volatile boolean stopped;
    private volatile boolean reconnectScheduled;
    private int reconnectAttempt;
    private long requestId;
    private long lastSeenOpenTime = Long.MIN_VALUE;
    private long lastAnnouncedOpenTime = Long.MIN_VALUE;
    private ScheduledFuture<?> keepaliveTask;

    /**
     * @param uri                  The WebSocket endpoint
     * @param market               Market in Bitvavo format, e.g. "BTC-EUR"
     * @param interval             Candle interval code, e.g. "1h"
     * @param intervalMillis       The interval's length in milliseconds
     * @param keepaliveSeconds     Idle keepalive period
     * @param reconnectDelayMillis Base delay for the reconnect backoff
     * @param connector            The transport
     * @param objectMapper         JSON mapper for the protocol messages
     * @param listener             The closed-candle callback
     */
    public BitvavoCandleStream(URI uri, String market, String interval, long intervalMillis,
                               long keepaliveSeconds, long reconnectDelayMillis,
                               WebSocketConnector connector, ObjectMapper objectMapper,
                               CandleListener listener) {
        this.uri = uri;
        this.market = market;
        this.interval = interval;
        this.intervalMillis = intervalMillis;
        this.keepaliveSeconds = keepaliveSeconds;
        this.reconnectDelayMillis = reconnectDelayMillis;
        this.connector = connector;
        this.objectMapper = objectMapper;
        this.listener = listener;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "candle-stream-" + market + "-" + interval);
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Connect and subscribe. Returns immediately; the connection is established on the stream's own
     * thread.
     */
    public synchronized void start() {
        if (stopped) {
            throw new IllegalStateException("This stream was stopped; create a new one to restart");
        }
        if (session != null || reconnectScheduled) {
            return;
        }
        scheduler.execute(this::connect);
    }

    /**
     * Disconnect and shut the stream down for good.
     */
    public synchronized void stop() {
        stopped = true;
        if (keepaliveTask != null) {
            keepaliveTask.cancel(false);
            keepaliveTask = null;
        }
        WebSocketConnector.WebSocketSession current = session;
        session = null;
        if (current != null) {
            try {
                current.close();
            } catch (RuntimeException ex) {
                log.debug("Closing the {} {} candle stream reported {}", market, interval, ex.getMessage());
            }
        }
        scheduler.shutdownNow();
    }

    /**
     * Whether the subscription is currently up.
     *
     * @return true while connected
     */
    public boolean isConnected() {
        return session != null;
    }

    @Override
    public void onOpen(WebSocketConnector.WebSocketSession opened) {
        session = opened;
        reconnectAttempt = 0;
        reconnectScheduled = false;
        send(subscribeMessage());
        scheduleKeepalive();
        log.info("Subscribed to {} {} candles", market, interval);
    }

    @Override
    public void onText(String message) {
        try {
            JsonNode node = objectMapper.readTree(message);
            // The live API names the event "candle" (singular) while the AsyncAPI example shows
            // "candles"; both spellings are accepted.
            String event = node.path("event").asText();
            if (!"candle".equals(event) && !"candles".equals(event)) {
                log.debug("Ignoring {} event on the {} {} candle stream",
                        node.path("event").asText("unknown"), market, interval);
                return;
            }
            handleCandleEvent(readOpenTime(node));
        } catch (Exception ex) {
            // A malformed frame must not kill the subscription; the next one can still be handled.
            log.warn("Unreadable candle message on the {} {} stream: {}", market, interval, ex.getMessage());
        }
    }

    @Override
    public void onClosed(String reason) {
        log.warn("{} {} candle stream closed: {}", market, interval, reason);
        handleDisconnect();
    }

    @Override
    public void onError(Throwable error) {
        log.warn("{} {} candle stream failed: {}", market, interval, error.getMessage());
        handleDisconnect();
    }

    private synchronized void connect() {
        if (stopped) {
            return;
        }
        reconnectScheduled = false;
        try {
            session = connector.connect(uri, this);
        } catch (RuntimeException ex) {
            log.warn("Could not connect the {} {} candle stream: {}", market, interval, ex.getMessage());
            scheduleReconnect();
        }
    }

    private synchronized void handleDisconnect() {
        session = null;
        if (keepaliveTask != null) {
            keepaliveTask.cancel(false);
            keepaliveTask = null;
        }
        scheduleReconnect();
    }

    private synchronized void scheduleReconnect() {
        if (stopped || reconnectScheduled) {
            return;
        }
        reconnectScheduled = true;
        long delay = Math.min(reconnectDelayMillis * (1L << Math.min(reconnectAttempt, 5)),
                MAX_RECONNECT_DELAY_MILLIS);
        reconnectAttempt++;
        log.info("Reconnecting the {} {} candle stream in {} ms (attempt {})",
                market, interval, delay, reconnectAttempt);
        scheduler.schedule(this::connect, delay, TimeUnit.MILLISECONDS);
    }

    private void handleCandleEvent(long openTime) {
        synchronized (this) {
            if (openTime <= lastSeenOpenTime) {
                // The same candle again, or an out-of-order frame.
                return;
            }
            if (lastSeenOpenTime == Long.MIN_VALUE) {
                // The first frame is the candle in progress. The one before it closed before this
                // subscription existed, so it is marked as announced and never replayed.
                lastSeenOpenTime = openTime;
                lastAnnouncedOpenTime = openTime - intervalMillis;
                return;
            }
            long firstClosed = lastSeenOpenTime;
            long lastClosed = openTime - intervalMillis;
            lastSeenOpenTime = openTime;
            if (lastClosed < firstClosed) {
                return;
            }
            if ((lastClosed - firstClosed) / intervalMillis + 1 > MAX_GAP_BOUNDARIES) {
                log.warn("{} {} jumped from open time {} to {}; skipping stale boundaries",
                        market, interval, firstClosed, lastClosed);
                return;
            }
            for (long closed = firstClosed; closed <= lastClosed; closed += intervalMillis) {
                if (closed > lastAnnouncedOpenTime) {
                    lastAnnouncedOpenTime = closed;
                    announceClosed(closed);
                }
            }
        }
    }

    private void announceClosed(long openTime) {
        log.debug("{} {} candle closed at open time {}", market, interval, openTime);
        try {
            listener.onCandleClosed(openTime);
        } catch (RuntimeException ex) {
            // Keep the subscription alive even if the consumer failed on this candle.
            log.error("Handling the closed {} {} candle at {} failed: {}",
                    market, interval, openTime, ex.getMessage(), ex);
        }
    }

    private long readOpenTime(JsonNode node) {
        JsonNode rows = node.path("candle");
        if (!rows.isArray() || rows.isEmpty()) {
            throw new IllegalArgumentException("candle event without a candle row");
        }
        JsonNode row = rows.get(rows.size() - 1);
        if (!row.isArray() || row.isEmpty()) {
            throw new IllegalArgumentException("candle event with an empty candle row");
        }
        return Long.parseLong(row.get(0).asText());
    }

    private String subscribeMessage() {
        ObjectNode channel = objectMapper.createObjectNode();
        channel.put("name", "candles");
        channel.putArray("markets").add(market);
        channel.putArray("interval").add(interval);
        ObjectNode root = objectMapper.createObjectNode();
        root.put("action", "subscribe");
        root.putArray("channels").add(channel);
        return root.toString();
    }

    private void scheduleKeepalive() {
        if (keepaliveTask != null) {
            keepaliveTask.cancel(false);
        }
        keepaliveTask = scheduler.scheduleAtFixedRate(this::sendKeepalive,
                keepaliveSeconds, keepaliveSeconds, TimeUnit.SECONDS);
    }

    private void sendKeepalive() {
        // getTime resets Bitvavo's idle timer; it is the documented action for keeping a
        // connection alive without a subscription of its own.
        ObjectNode root = objectMapper.createObjectNode();
        root.put("action", "getTime");
        root.put("requestId", ++requestId);
        send(root.toString());
    }

    private void send(String message) {
        WebSocketConnector.WebSocketSession current = session;
        if (current == null) {
            log.debug("Dropping a send on the disconnected {} {} candle stream", market, interval);
            return;
        }
        try {
            current.send(message);
        } catch (RuntimeException ex) {
            // The resulting close/error callback drives the reconnect.
            log.warn("Sending on the {} {} candle stream failed: {}", market, interval, ex.getMessage());
        }
    }
}
