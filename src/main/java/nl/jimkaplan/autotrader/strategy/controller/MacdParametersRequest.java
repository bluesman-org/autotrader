package nl.jimkaplan.autotrader.strategy.controller;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A MACD parameter candidate.
 *
 * @param fastPeriod   Fast EMA period
 * @param slowPeriod   Slow EMA period
 * @param signalPeriod Signal EMA period
 */
public record MacdParametersRequest(
        @Schema(description = "Fast EMA period", example = "12") int fastPeriod,
        @Schema(description = "Slow EMA period", example = "26") int slowPeriod,
        @Schema(description = "Signal EMA period", example = "9") int signalPeriod) {
}
