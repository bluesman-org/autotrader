package nl.jimkaplan.autotrader.strategy.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JdkPushSenderTest {

    @Test
    void theJdkSenderReturnsThePushServiceStatus() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/push", exchange -> {
            exchange.sendResponseHeaders(201, -1);
            exchange.close();
        });
        server.start();
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(
                            "http://127.0.0.1:" + server.getAddress().getPort() + "/push"))
                    .header("X-Test", "1")
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build();

            assertEquals(201, new JdkPushSender().send(request));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aStalledPushServiceHitsTheRequestTimeout() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/stall", exchange -> {
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        server.start();
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(
                            "http://127.0.0.1:" + server.getAddress().getPort() + "/stall"))
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build();

            JdkPushSender sender = new JdkPushSender(Duration.ofSeconds(1), Duration.ofMillis(200));

            assertThrows(HttpTimeoutException.class, () -> sender.send(request));
        } finally {
            release.countDown();
            server.stop(0);
        }
    }
}
