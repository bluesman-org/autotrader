package nl.jimkaplan.autotrader.strategy.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zerodeplibs.webpush.PushSubscription;
import com.zerodeplibs.webpush.VAPIDKeyPair;
import com.zerodeplibs.webpush.httpclient.StandardHttpClientRequestPreparer;
import lombok.extern.slf4j.Slf4j;
import nl.jimkaplan.autotrader.strategy.model.document.PushSubscriptionDocument;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * Sends live notifications through Web Push to every browser that subscribed on the settings page.
 *
 * <p>Notifications are sent off the trading thread, one subscription at a time. A push service
 * answer of 403, 404 or 410 means the browser's subscription cannot be used (it is gone, or the
 * VAPID key changed) and is deleted; any other failure is logged and the subscription is kept,
 * because a temporary outage should not silently unsubscribe the operator. Email and push are
 * independent channels: a missing SMTP server does not suppress a push and an unsubscribed browser
 * does not suppress the email.</p>
 */
@Slf4j
@Service
public class PushNotificationService {

    static final String TEST_TITLE = "Autotrader test notification";
    static final String TEST_BODY = "Push notifications are working on this device.";

    private static final int JWT_MINUTES = 15;
    private static final long TTL_HOURS = 1;
    private static final String DEFAULT_TAG = "autotrader";

    private final PushSettingsService pushSettingsService;
    private final PushSubscriptionService pushSubscriptionService;
    private final PushSender pushSender;
    private final ObjectMapper objectMapper;
    private final Executor executor;
    private final String publicUrl;

    public PushNotificationService(PushSettingsService pushSettingsService,
                                   PushSubscriptionService pushSubscriptionService,
                                   PushSender pushSender,
                                   ObjectMapper objectMapper,
                                   @Qualifier("livePushExecutor") Executor executor,
                                   @Value("${autotrader.security.public-url:http://localhost:8081/autotrader}")
                                   String publicUrl) {
        this.pushSettingsService = pushSettingsService;
        this.pushSubscriptionService = pushSubscriptionService;
        this.pushSender = pushSender;
        this.objectMapper = objectMapper;
        this.executor = executor;
        this.publicUrl = publicUrl;
    }

    /**
     * Send a notification to every subscribed browser, asynchronously.
     *
     * @param title The notification title
     * @param body  A short line under the title
     * @param tag   The browser-side grouping tag, so a later order update replaces the earlier one
     */
    public void send(String title, String body, String tag) {
        try {
            executor.execute(() -> {
                try {
                    List<PushSubscriptionDocument> recipients = pushSubscriptionService.all();
                    if (recipients.isEmpty()) {
                        log.debug("No Web Push subscription is registered; the push '{}' was not sent",
                                title);
                        return;
                    }
                    sendTo(recipients, title, body, tag);
                } catch (RuntimeException ex) {
                    log.warn("Could not send the push '{}': {}", title, ex.getMessage());
                }
            });
        } catch (RuntimeException ex) {
            // Scheduling is best effort, exactly like the email channel: it must never propagate
            // into an order decision.
            log.warn("Could not prepare or schedule the push '{}': {}", title, ex.getMessage());
        }
    }

    /**
     * Send a test notification synchronously, so the settings page can report a failure.
     *
     * @return How many subscriptions accepted the notification
     * @throws IllegalArgumentException when no browser is subscribed
     * @throws IllegalStateException    when no push service accepted the notification
     */
    public int sendTest() {
        List<PushSubscriptionDocument> recipients = pushSubscriptionService.all();
        if (recipients.isEmpty()) {
            throw new IllegalArgumentException(
                    "No push subscription is registered; enable notifications on this device first.");
        }
        int sent = sendTo(recipients, TEST_TITLE, TEST_BODY, "test");
        if (sent == 0) {
            throw new IllegalStateException(
                    "The push service accepted no notification; check the application log.");
        }
        return sent;
    }

    private int sendTo(List<PushSubscriptionDocument> recipients, String title, String body, String tag) {
        VAPIDKeyPair keyPair = pushSettingsService.keyPair();
        String subject = pushSettingsService.subject();
        String payload = payload(title, body, tag);
        int sent = 0;
        for (PushSubscriptionDocument recipient : recipients) {
            try {
                HttpRequest request = StandardHttpClientRequestPreparer.getBuilder()
                        .pushSubscription(subscription(recipient))
                        .vapidJWTExpiresAfter(JWT_MINUTES, TimeUnit.MINUTES)
                        .vapidJWTSubject(subject)
                        .pushMessage(payload)
                        .ttl(TTL_HOURS, TimeUnit.HOURS)
                        .urgencyHigh()
                        .build(keyPair)
                        .toRequest();
                int status = pushSender.send(request);
                if (status == 201 || status == 202) {
                    pushSubscriptionService.markSuccess(recipient.getId());
                    sent++;
                } else if (status == 403 || status == 404 || status == 410) {
                    pushSubscriptionService.delete(recipient.getId());
                    log.info("Removed a Web Push subscription the push service reported as unusable ({})",
                            status);
                } else {
                    pushSubscriptionService.markFailure(recipient.getId());
                    log.warn("The push service answered {} for a notification to {}",
                            status, host(recipient.getEndpoint()));
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                pushSubscriptionService.markFailure(recipient.getId());
                log.warn("Sending a Web Push notification was interrupted");
                break;
            } catch (IOException | RuntimeException ex) {
                pushSubscriptionService.markFailure(recipient.getId());
                log.warn("Could not send a Web Push notification to {}: {}",
                        host(recipient.getEndpoint()), ex.getMessage());
            }
        }
        return sent;
    }

    private String payload(String title, String body, String tag) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("title", title == null || title.isBlank() ? "Autotrader" : title);
        payload.put("body", body == null ? "" : body);
        payload.put("url", livePageUrl());
        payload.put("tag", tag == null || tag.isBlank() ? DEFAULT_TAG : tag);
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not encode the Web Push payload", ex);
        }
    }

    private String livePageUrl() {
        String base = publicUrl == null ? "" : publicUrl.replaceAll("/+$", "");
        return base + "/ui/live";
    }

    private static PushSubscription subscription(PushSubscriptionDocument document) {
        PushSubscription subscription = new PushSubscription();
        subscription.setEndpoint(document.getEndpoint());
        PushSubscription.Keys keys = new PushSubscription.Keys();
        keys.setP256dh(document.getP256dh());
        keys.setAuth(document.getAuth());
        subscription.setKeys(keys);
        return subscription;
    }

    private static String host(String endpoint) {
        try {
            String host = URI.create(endpoint).getHost();
            return host == null ? "the push service" : host;
        } catch (IllegalArgumentException ex) {
            return "the push service";
        }
    }
}
