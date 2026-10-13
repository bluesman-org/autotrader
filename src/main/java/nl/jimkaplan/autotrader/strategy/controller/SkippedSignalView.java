package nl.jimkaplan.autotrader.strategy.controller;

import nl.jimkaplan.autotrader.strategy.backtest.SkipReason;
import nl.jimkaplan.autotrader.strategy.backtest.SkippedSignal;

import java.time.Instant;
import java.time.format.DateTimeFormatter;

/**
 * A skipped entry signal row for the per-entry trades page, with its bar time already formatted.
 *
 * @param timeText The skipped signal's bar time, UTC
 * @param reason   Why the signal could not be filled
 */
public record SkippedSignalView(String timeText, SkipReason reason) {

    static SkippedSignalView of(SkippedSignal skipped, DateTimeFormatter timestamp) {
        return new SkippedSignalView(timestamp.format(Instant.ofEpochMilli(skipped.time())), skipped.reason());
    }
}
