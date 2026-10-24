package nl.jimkaplan.autotrader.strategy.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.jimkaplan.autotrader.repository.PushSubscriptionRepository;
import nl.jimkaplan.autotrader.strategy.controller.PushSubscriptionRequest;
import nl.jimkaplan.autotrader.strategy.model.document.PushSubscriptionDocument;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.List;

/**
 * Stores, removes and tracks the browsers that subscribed to live Web Push notifications.
 *
 * <p>The endpoint is a capability URL and is treated as sensitive: it is never rendered into a page
 * or returned by the API. A subscription is upserted by endpoint, because a browser that clears
 * its registration and subscribes again returns the same endpoint with new keys.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PushSubscriptionService {

    private static final int MAX_ENDPOINT_LENGTH = 2048;

    private final PushSubscriptionRepository pushSubscriptionRepository;

    /**
     * Store a browser's subscription, replacing the keys of an existing endpoint.
     *
     * @param request   The subscription the browser returned
     * @param userAgent The browser's user agent, for the settings page
     * @throws IllegalArgumentException when the subscription is incomplete or its endpoint is not
     *                                  an absolute https URL
     */
    public void subscribe(PushSubscriptionRequest request, String userAgent) {
        if (request == null || request.keys() == null) {
            throw new IllegalArgumentException("The push subscription is incomplete.");
        }
        String endpoint = requireEndpoint(request.endpoint());
        String p256dh = requireValue(request.keys().p256dh(), "The push subscription has no p256dh key.");
        String auth = requireValue(request.keys().auth(), "The push subscription has no auth key.");

        PushSubscriptionDocument document = pushSubscriptionRepository.findByEndpoint(endpoint)
                .orElseGet(() -> PushSubscriptionDocument.builder()
                        .endpoint(endpoint)
                        .createdTime(Instant.now())
                        .build());
        document.setP256dh(p256dh);
        document.setAuth(auth);
        document.setUserAgent(userAgent == null ? null : truncate(userAgent, 255));
        pushSubscriptionRepository.save(document);
        log.info("Stored a Web Push subscription");
    }

    /**
     * @param endpoint The endpoint to remove; an unknown endpoint is ignored
     */
    public void unsubscribe(String endpoint) {
        String value = requireEndpoint(endpoint);
        pushSubscriptionRepository.deleteByEndpoint(value);
        log.info("Removed a Web Push subscription");
    }

    /**
     * @return How many browsers are subscribed
     */
    public long count() {
        return pushSubscriptionRepository.count();
    }

    /**
     * @return Every subscription, for a notification run
     */
    public List<PushSubscriptionDocument> all() {
        return pushSubscriptionRepository.findAll();
    }

    /**
     * Record a notification the push service accepted.
     *
     * @param id The subscription document id
     */
    public void markSuccess(String id) {
        if (id == null) {
            return;
        }
        pushSubscriptionRepository.findById(id).ifPresent(document -> {
            document.setLastSuccessTime(Instant.now());
            pushSubscriptionRepository.save(document);
        });
    }

    /**
     * Record a notification the push service rejected.
     *
     * @param id The subscription document id
     */
    public void markFailure(String id) {
        if (id == null) {
            return;
        }
        pushSubscriptionRepository.findById(id).ifPresent(document -> {
            document.setLastFailureTime(Instant.now());
            pushSubscriptionRepository.save(document);
        });
    }

    /**
     * Remove a subscription the push service reported as gone.
     *
     * @param id The subscription document id
     */
    public void delete(String id) {
        if (id == null) {
            return;
        }
        pushSubscriptionRepository.deleteById(id);
    }

    private static String requireEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("The push subscription has no endpoint.");
        }
        String value = endpoint.trim();
        if (value.length() > MAX_ENDPOINT_LENGTH) {
            throw new IllegalArgumentException("The push subscription endpoint is too long.");
        }
        try {
            URI uri = new URI(value);
            if (!uri.isAbsolute() || uri.getHost() == null || !"https".equalsIgnoreCase(uri.getScheme())) {
                throw new IllegalArgumentException(
                        "The push subscription endpoint must be an absolute https URL.");
            }
        } catch (URISyntaxException ex) {
            throw new IllegalArgumentException(
                    "The push subscription endpoint must be an absolute https URL.", ex);
        }
        return value;
    }

    private static String requireValue(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
