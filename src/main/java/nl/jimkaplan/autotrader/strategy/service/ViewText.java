package nl.jimkaplan.autotrader.strategy.service;

import java.util.Locale;

/**
 * Small text helpers shared by the UI services, so money formatting exists once.
 */
final class ViewText {

    private ViewText() {
    }

    /**
     * @param value The amount
     * @return The amount as euro without a sign, e.g. {@code €74,403.00}
     */
    static String euro(double value) {
        return "€" + String.format(Locale.US, "%,.2f", Math.abs(value));
    }

    /**
     * @param value The amount
     * @return The amount as a signed euro value with a typographic minus, e.g. {@code −€0.19}
     */
    static String signed(double value) {
        return (value < 0 ? "−" : "+") + euro(value);
    }
}
