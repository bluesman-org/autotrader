package nl.jimkaplan.autotrader.strategy.controller;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The result of a test push.
 *
 * @param sent How many subscriptions accepted the notification
 */
@Schema(description = "The result of a test push")
public record PushTestResponse(
        @Schema(description = "How many subscribed devices accepted the notification") int sent) {
}
