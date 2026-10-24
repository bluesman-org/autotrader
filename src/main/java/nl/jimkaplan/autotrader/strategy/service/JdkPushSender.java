package nl.jimkaplan.autotrader.strategy.service;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Sends Web Push requests with the JDK's HTTP client. The timeout bounds one subscription so a
 * stalled push service cannot hold the notification thread indefinitely; it never runs on the
 * trading thread.
 */
@Component
public class JdkPushSender implements PushSender {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

    @Override
    public int send(HttpRequest request) throws IOException, InterruptedException {
        return httpClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
