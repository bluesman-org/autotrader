package nl.jimkaplan.autotrader.strategy.backtest;

/**
 * The metric a sweep ranks by. Each extracts its value from the metrics and knows whether larger
 * values rank first.
 */
public enum RankingMetric {

    /**
     * Total return on the initial capital — higher is better.
     */
    TOTAL_RETURN(true) {
        @Override
        double value(BacktestMetrics metrics) {
            return metrics.totalReturnPercent();
        }
    },

    /**
     * Gross profit over gross loss — higher is better.
     */
    PROFIT_FACTOR(true) {
        @Override
        double value(BacktestMetrics metrics) {
            return metrics.profitFactor();
        }
    },

    /**
     * Largest peak-to-trough equity decline — smaller is better.
     */
    MAX_DRAWDOWN(false) {
        @Override
        double value(BacktestMetrics metrics) {
            return metrics.maxDrawdownPercent();
        }
    },

    /**
     * Share of profitable trades — higher is better.
     */
    WIN_RATE(true) {
        @Override
        double value(BacktestMetrics metrics) {
            return metrics.winRatePercent();
        }
    };

    private final boolean higherIsBetter;

    RankingMetric(boolean higherIsBetter) {
        this.higherIsBetter = higherIsBetter;
    }

    /**
     * The value of this metric in the given metrics.
     *
     * @param metrics The metrics
     * @return The value
     */
    abstract double value(BacktestMetrics metrics);

    /**
     * Whether larger values rank first.
     *
     * @return {@code true} when higher is better
     */
    public boolean higherIsBetter() {
        return higherIsBetter;
    }
}
