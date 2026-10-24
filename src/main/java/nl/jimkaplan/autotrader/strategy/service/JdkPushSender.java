package nl.jimkaplan.autotrader.strategy.service;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Sends Web Push requests with the JDK's HTTP client. Both the connect and the response are
 * bounded, so a stalled push service cannot hold the notification thread indefinitely; it never
 * runs on the trading thread. The test constructor shrinks the limits.
 */
@Component
public class JdkPushSender implements PushSender {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient httpClient;
    private final Duration requestTimeout;

    public JdkPushSender() {
        this(CONNECT_TIMEOUT, REQUEST_TIMEOUT);
    }

    JdkPushSender(Duration connectTimeout, Duration requestTimeout) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .build();
        this.requestTimeout = requestTimeout;
    }

    @Override
    public int send(HttpRequest request) throws IOException, InterruptedException {
        HttpRequest timed = HttpRequest.newBuilder(request, (name, value) -> true)
                .timeout(requestTimeout)
                .build();
        return httpClient.send(timed, HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
