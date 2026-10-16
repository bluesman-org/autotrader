package nl.jimkaplan.autotrader.strategy.service;

import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * Small text helpers shared by the UI services, so money and quantity formatting exists once.
 * Numbers read the way the readers do — a point groups thousands, a comma is the decimal sign
 * ({@code €74.403,00}) — never the US convention.
 */
final class ViewText {

    /**
     * German and Dutch agree on the separators, so one locale carries both the grouping and the
     * decimal sign.
     */
    private static final Locale UI = Locale.GERMANY;

    private ViewText() {
    }

    /**
     * @param value The amount
     * @return The amount as euro without a sign, e.g. {@code €74.403,00}
     */
    static String euro(double value) {
        return euro(value, 2);
    }

    /**
     * @param value          The amount
     * @param fractionDigits The decimals to show
     * @return The amount as euro without a sign, e.g. {@code €0,1500} for four decimals
     */
    static String euro(double value, int fractionDigits) {
        return "€" + String.format(UI, "%,." + fractionDigits + "f", Math.abs(value));
    }

    /**
     * @param value The amount
     * @return The amount as a signed euro value with a typographic minus, e.g. {@code −€0,19}
     */
    static String signed(double value) {
        return (value < 0 ? "−" : "+") + euro(value);
    }

    /**
     * @param value          The quantity
     * @param fractionDigits The decimals to show
     * @return The quantity grouped the same way, e.g. {@code 0,00135153}
     */
    static String decimal(double value, int fractionDigits) {
        return String.format(UI, "%,." + fractionDigits + "f", value);
    }

    /**
     * @param value          The quantity
     * @param fractionDigits The decimals to keep at most
     * @return The quantity with trailing zeros dropped, e.g. {@code 0,0015}
     */
    static String trimmedDecimal(double value, int fractionDigits) {
        DecimalFormat format = new DecimalFormat("#,##0." + "#".repeat(fractionDigits),
                DecimalFormatSymbols.getInstance(UI));
        format.setRoundingMode(RoundingMode.HALF_UP);
        return format.format(value);
    }
}
