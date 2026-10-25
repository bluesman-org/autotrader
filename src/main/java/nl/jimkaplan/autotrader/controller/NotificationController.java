package nl.jimkaplan.autotrader.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import nl.jimkaplan.autotrader.strategy.service.LiveAlertService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The alert channel's test endpoint. The same action is a button on the settings page; the API
 * exposes it so a deployment check can prove the mail path without opening a browser.
 */
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
@Tag(name = "Notifications", description = "Test the live engine's email alerts")
public class NotificationController {

    private final LiveAlertService alerts;

    /**
     * Send one test alert to the account's email address.
     *
     * @return A confirmation message
     */
    @Operation(summary = "Send a test alert", operationId = "sendTestAlert",
            description = "Sends one mail to the account's email address through the stored SMTP "
                          + "server. Fails when no account email or no SMTP server is configured.")
    @ApiResponse(responseCode = "200", description = "The test alert was sent",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = NotificationTestResponse.class)))
    @PostMapping("/test")
    public ResponseEntity<NotificationTestResponse> test() {
        alerts.sendTest();
        return ResponseEntity.ok(new NotificationTestResponse(
                "The test alert was sent to the account's email address."));
    }

    /**
     * The test endpoint's response.
     *
     * @param message What happened
     */
    public record NotificationTestResponse(String message) {
    }
}
