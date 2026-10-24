package nl.jimkaplan.autotrader.strategy.service;

import java.io.IOException;
import java.net.http.HttpRequest;

/**
 * Sends one prepared Web Push request. Split out so the notification service can be tested without
 * a network and so the JDK transport stays replaceable.
 */
public interface PushSender {

    /**
     * @param request The signed, encrypted request the library prepared
     * @return The push service's HTTP status
     * @throws IOException          When the connection fails
     * @throws InterruptedException When the send is interrupted
     */
    int send(HttpRequest request) throws IOException, InterruptedException;
}
