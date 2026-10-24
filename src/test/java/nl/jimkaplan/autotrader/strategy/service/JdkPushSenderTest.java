package nl.jimkaplan.autotrader.strategy.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build();

            assertEquals(201, new JdkPushSender().send(request));
        } finally {
            server.stop(0);
        }
    }
}
