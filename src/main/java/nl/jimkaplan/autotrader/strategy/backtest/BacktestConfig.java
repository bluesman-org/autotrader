package nl.jimkaplan.autotrader.strategy.backtest;

/**
 * Simulation settings shared by every backtest run.
 *
 * <p>The simulation uses double arithmetic deliberately: it reproduces order sizing and fills for
 * analysis, and the recorded decisions (a fixed EUR amount per buy, one position at a time, fees
 * per side) do not need ledger precision.</p>
 *
 * @param initialCapital Starting cash in EUR; must be positive
 * @param amountPerBuy   Fixed EUR amount spent per buy, before fees; at least the minimum order
 *                       value of 5 EUR
 * @param feeRate        Fee per side as a fraction (0.002 = 0.20%); in [0, 1)
 * @param warmUpCandles  Leading candles that are never traded
 * @throws IllegalArgumentException if a value is out of range
 */
public record BacktestConfig(double initialCapital, double amountPerBuy, double feeRate, int warmUpCandles) {

    /**
     * The smallest order value the exchange accepts, in EUR.
     */
    public static final double MINIMUM_ORDER_EUR = 5.0;

    public BacktestConfig {
        if (!Double.isFinite(initialCapital) || initialCapital <= 0) {
            throw new IllegalArgumentException("initialCapital must be positive");
        }
        if (!Double.isFinite(amountPerBuy) || amountPerBuy < MINIMUM_ORDER_EUR) {
            throw new IllegalArgumentException("amountPerBuy must be at least " + MINIMUM_ORDER_EUR);
        }
        if (!Double.isFinite(feeRate) || feeRate < 0 || feeRate >= 1) {
            throw new IllegalArgumentException("feeRate must be in [0, 1)");
        }
        if (warmUpCandles < 0) {
            throw new IllegalArgumentException("warmUpCandles must not be negative");
        }
    }

    /**
     * The defaults: 1000 EUR capital, 100 EUR per buy, the account's current taker fee (0.20% —
     * market orders take liquidity) and the conventional 200-candle warm-up.
     *
     * @return The default settings
     */
    public static BacktestConfig defaults() {
        return new BacktestConfig(1000.0, 100.0, 0.002, 200);
    }
}
