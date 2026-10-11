package nl.jimkaplan.autotrader.strategy.controller;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A Parabolic SAR parameter candidate.
 *
 * @param acceleration Acceleration factor
 * @param maximum      Maximum acceleration factor
 */
public record SarParametersRequest(
        @Schema(description = "Acceleration factor", example = "0.02") double acceleration,
        @Schema(description = "Maximum acceleration factor", example = "0.2") double maximum) {
}
