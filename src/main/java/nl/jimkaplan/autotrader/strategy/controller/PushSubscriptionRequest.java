package nl.jimkaplan.autotrader.strategy.controller;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The subscription a browser returns from {@code PushManager.subscribe}; the server stores what it
 * needs to encrypt and address a notification. The keys are the browser's, not the server's.
 *
 * @param endpoint The push service URL
 * @param keys     The browser's encryption keys
 */
@Schema(description = "A browser's Web Push subscription")
public record PushSubscriptionRequest(
        @Schema(description = "The push service URL the browser returned")
        String endpoint,
        @Schema(description = "The browser's encryption keys")
        Keys keys) {

    /**
     * @param p256dh The browser's P-256 public key, base64url
     * @param auth   The browser's authentication secret, base64url
     */
    @Schema(description = "The browser's encryption keys")
    public record Keys(
            @Schema(description = "The browser's P-256 public key") String p256dh,
            @Schema(description = "The browser's authentication secret") String auth) {
    }
}
