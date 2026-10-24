package nl.jimkaplan.autotrader.strategy.controller;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The endpoint of a subscription to remove, sent when a browser unsubscribes on the settings page.
 *
 * @param endpoint The push service URL
 */
@Schema(description = "The endpoint of the subscription to remove")
public record PushEndpointRequest(
        @Schema(description = "The push service URL the browser returned") String endpoint) {
}
