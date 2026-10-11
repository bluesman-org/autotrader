package nl.jimkaplan.autotrader.strategy.backtest;

/**
 * Why an entry signal did not become a trade.
 */
public enum SkipReason {

    /**
     * The remaining cash was not enough for the fixed amount plus its fee.
     */
    INSUFFICIENT_BALANCE
}
