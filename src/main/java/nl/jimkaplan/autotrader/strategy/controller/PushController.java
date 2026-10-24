package nl.jimkaplan.autotrader.strategy.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.jimkaplan.autotrader.strategy.service.PushNotificationService;
import nl.jimkaplan.autotrader.strategy.service.PushSettingsService;
import nl.jimkaplan.autotrader.strategy.service.PushSubscriptionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Web Push subscription and test endpoints. The settings page uses them from the signed-in browser
 * session; the service worker and manifest themselves are the only public static assets.
 */
@Slf4j
@RestController
@RequestMapping("/api/push")
@RequiredArgsConstructor
@Tag(name = "Web Push", description = "Browser subscriptions for live notifications")
public class PushController {

    private final PushSettingsService pushSettingsService;
    private final PushSubscriptionService pushSubscriptionService;
    private final PushNotificationService pushNotificationService;

    /**
     * The VAPID public key in the uncompressed form {@code PushManager.subscribe} expects.
     *
     * @return The raw bytes, as {@code application/octet-stream}
     */
    @Operation(summary = "Get the VAPID public key",
            description = "Returns the installation's VAPID public key as raw bytes for " +
                          "`PushManager.subscribe({applicationServerKey})`. The key pair is " +
                          "generated on the first call and kept encrypted in the database.")
    @ApiResponse(responseCode = "200", description = "The public key",
            content = @Content(mediaType = MediaType.APPLICATION_OCTET_STREAM_VALUE))
    @GetMapping(value = "/public-key", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<byte[]> publicKey() {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(pushSettingsService.publicKey());
    }

    /**
     * Store the subscription a browser returned.
     *
     * @param request   The subscription
     * @param userAgent The browser's user agent, for the settings page
     * @return 201 when stored
     */
    @Operation(summary = "Subscribe a browser",
            description = "Stores the endpoint and encryption keys a browser returned from " +
                          "`PushManager.subscribe`. Re-subscribing the same endpoint replaces its " +
                          "keys.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Subscription stored"),
            @ApiResponse(responseCode = "400", description = "Incomplete subscription", content = @Content)
    })
    @PostMapping("/subscriptions")
    public ResponseEntity<Void> subscribe(
            @RequestBody PushSubscriptionRequest request,
            @RequestHeader(value = "User-Agent", required = false) String userAgent) {
        pushSubscriptionService.subscribe(request, userAgent);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    /**
     * Remove a browser's subscription.
     *
     * @param request The endpoint to remove
     * @return 204, also when the endpoint was not known
     */
    @Operation(summary = "Unsubscribe a browser",
            description = "Removes the subscription for an endpoint. Unknown endpoints are ignored.")
    @ApiResponse(responseCode = "204", description = "Subscription removed")
    @DeleteMapping("/subscriptions")
    public ResponseEntity<Void> unsubscribe(@RequestBody PushEndpointRequest request) {
        pushSubscriptionService.unsubscribe(request == null ? null : request.endpoint());
        return ResponseEntity.noContent().build();
    }

    /**
     * Send a test push to every subscribed browser.
     *
     * @return How many subscriptions accepted it
     */
    @Operation(summary = "Send a test push",
            description = "Sends a test notification to every subscribed browser and reports how " +
                          "many push services accepted it. Fails with 400 when no browser is " +
                          "subscribed.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Test sent",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = PushTestResponse.class))),
            @ApiResponse(responseCode = "400", description = "No subscription", content = @Content),
            @ApiResponse(responseCode = "500", description = "The push service rejected every send",
                    content = @Content)
    })
    @PostMapping("/test")
    public ResponseEntity<PushTestResponse> test() {
        int sent = pushNotificationService.sendTest();
        log.info("Sent a test Web Push notification to {} subscription(s)", sent);
        return ResponseEntity.ok(new PushTestResponse(sent));
    }
}
