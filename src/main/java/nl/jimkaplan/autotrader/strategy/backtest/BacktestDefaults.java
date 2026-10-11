package nl.jimkaplan.autotrader.strategy.backtest;

import nl.jimkaplan.autotrader.strategy.rule.LogicalOperator;
import nl.jimkaplan.autotrader.strategy.rule.RuleCondition;
import nl.jimkaplan.autotrader.strategy.rule.RuleGroup;

import java.util.List;

/**
 * The documented defaults of a backtest, shared by the service that applies them and the UI that
 * prefills them, so a form can never promise a value the engine would not use. Also listed in
 * {@code docs/backtest.md}.
 */
public final class BacktestDefaults {

    /**
     * Starting cash in EUR.
     */
    public static final double INITIAL_CAPITAL = 1000.0;

    /**
     * Fixed EUR amount per buy, before fees.
     */
    public static final double AMOUNT_PER_BUY = 100.0;

    /**
     * Fee per side as a fraction: the account's taker rate for market orders.
     */
    public static final double FEE_RATE = 0.002;

    /**
     * Leading candles that are never traded.
     */
    public static final int WARM_UP_CANDLES = 200;

    /**
     * Share of the range the sweep optimises on.
     */
    public static final double TRAIN_RATIO = 0.7;

    /**
     * Metric the sweep ranks by.
     */
    public static final RankingMetric RANKING_METRIC = RankingMetric.TOTAL_RETURN;

    /**
     * Entry rule set applied when none is given.
     */
    public static final RuleGroup ENTRY_RULES = new RuleGroup(LogicalOperator.ALL,
            List.of(RuleCondition.MACD_BULLISH_CROSSOVER));

    /**
     * Exit rule set applied when none is given.
     */
    public static final RuleGroup EXIT_RULES = new RuleGroup(LogicalOperator.ALL,
            List.of(RuleCondition.SAR_BEARISH_CROSSOVER));

    private BacktestDefaults() {
    }
}
