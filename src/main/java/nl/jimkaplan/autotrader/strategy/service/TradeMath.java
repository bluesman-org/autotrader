package nl.jimkaplan.autotrader.strategy.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Shared fill arithmetic for the live engine, so the figures recorded when an order is placed and
 * the profit realised when its position closes cannot diverge between {@link LiveTradingService}
 * and {@link LiveOrderReconciliationService}.
 */
public final class TradeMath {

    private static final int PRICE_SCALE = 8;

    private TradeMath() {
    }

    /**
     * The first of {@code value} and {@code fallback} that is present, or null when both are.
     */
    public static BigDecimal firstNonNull(BigDecimal value, BigDecimal fallback) {
        return value != null ? value : fallback;
    }

    /**
     * {@code value} as a double, or {@code fallback} when it is null.
     */
    public static double filled(BigDecimal value, double fallback) {
        return value != null ? value.doubleValue() : fallback;
    }

    /**
     * {@code value} (or {@code fallback} when null) as a Double, or null when both are null.
     */
    public static Double amount(BigDecimal value, BigDecimal fallback) {
        BigDecimal resolved = firstNonNull(value, fallback);
        return resolved == null ? null : resolved.doubleValue();
    }

    /**
     * {@code amountQuote / quantity} half-up to eight decimals, or null when either is missing or
     * the quantity is not positive.
     */
    public static BigDecimal ratio(BigDecimal amountQuote, double quantity) {
        return amountQuote != null && quantity > 0
                ? amountQuote.divide(BigDecimal.valueOf(quantity), PRICE_SCALE, RoundingMode.HALF_UP)
                : null;
    }

    /**
     * {@code filledAmountQuote / filledAmount} half-up to eight decimals, or null when either is
     * missing or the base amount is not positive.
     */
    public static Double price(BigDecimal filledAmount, BigDecimal filledAmountQuote) {
        BigDecimal ratio = filledAmountQuote != null && filledAmount != null && filledAmount.signum() > 0
                ? filledAmountQuote.divide(filledAmount, PRICE_SCALE, RoundingMode.HALF_UP)
                : null;
        return ratio == null ? null : ratio.doubleValue();
    }

    /**
     * Realised profit of closing a position: proceeds minus the cost basis, the entry fee and the
     * exit fee.
     */
    public static double closeProfit(double proceeds, double quantity, double entryPrice,
                                     Double entryFee, double fee) {
        return proceeds - quantity * entryPrice - fee - zeroIfNull(entryFee);
    }

    /**
     * {@code value} as a double, or 0 when it is null.
     */
    public static double zeroIfNull(Double value) {
        return value == null ? 0 : value;
    }
}
