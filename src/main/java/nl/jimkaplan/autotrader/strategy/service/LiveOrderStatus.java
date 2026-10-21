package nl.jimkaplan.autotrader.strategy.service;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * Exchange order-status classification shared by the live trader and the boot reconciliation, so an
 * immediate response and a later history entry agree on which statuses can still fill.
 */
final class LiveOrderStatus {

    private LiveOrderStatus() {
    }

    /**
     * @param status The exchange's status for the order
     * @return Whether the order is terminally not filled, so the row can be settled as FAILED
     */
    static boolean isTerminalNonFill(String status) {
        if (status == null) {
            return false;
        }
        String normalized = status.toLowerCase(Locale.ROOT);
        return normalized.startsWith("canceled") || normalized.equals("expired")
                || normalized.equals("rejected");
    }

    /**
     * @param filledAmountQuote The exchange's filled quote amount, or null
     * @return A note about a partial fill the terminal-status path cannot book, or an empty string
     */
    static String partialFillNote(BigDecimal filledAmountQuote) {
        if (filledAmountQuote == null || filledAmountQuote.signum() <= 0) {
            return "";
        }
        return " (partial fill of " + filledAmountQuote.toPlainString() + " quote not booked)";
    }
}
