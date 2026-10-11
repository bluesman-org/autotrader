package nl.jimkaplan.autotrader.strategy.controller;

import nl.jimkaplan.autotrader.strategy.model.document.BacktestRun;

/**
 * A recent-run row for the backtest form page: the stored run with its display strings already
 * formatted, because the template engine has no date formatting helper on the classpath.
 *
 * @param run    The stored run, without entries
 * @param runAt  When the run was executed, UTC
 * @param range  The candle range it covered, UTC dates
 */
public record RunSummaryView(BacktestRun run, String runAt, String range) {
}
