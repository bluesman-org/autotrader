package nl.jimkaplan.autotrader.strategy.backtest;

import nl.jimkaplan.autotrader.strategy.model.document.BacktestRun;

/**
 * One stored run's entry, re-simulated on demand: the run itself, the entry's number in the ranked
 * table, how many bars the candle store holds for the run's range now, whether that differs from
 * what the run recorded, and the recomputed train and validate results.
 *
 * @param run            The stored run, read in full
 * @param entryNumber    The entry's number in the ranked table, starting at 1
 * @param candleCount    The bars the candle store holds for the run's range now
 * @param historyChanged Whether the store's bar count differs from the run's recorded one
 * @param evaluation     The entry's recomputed train and validate results
 */
public record BacktestEntryTrades(BacktestRun run, int entryNumber, int candleCount,
                                  boolean historyChanged, BacktestEvaluation evaluation) {
}
