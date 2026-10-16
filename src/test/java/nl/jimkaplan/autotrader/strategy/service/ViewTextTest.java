package nl.jimkaplan.autotrader.strategy.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The number policy in one place: a point groups thousands, a comma is the decimal sign, and a
 * value that rounds to zero reads {@code 0} — never {@code -0}.
 */
class ViewTextTest {

    @Test
    void euroUsesTheReadersSeparators() {
        assertEquals("€74.403,00", ViewText.euro(74403));
        assertEquals("€0,1500", ViewText.euro(0.15, 4));
        assertEquals("€1.234.567,89", ViewText.euro(1234567.891));
    }

    @Test
    void signedKeepsTheTypographicMinus() {
        assertEquals("−€0,19", ViewText.signed(-0.19));
        assertEquals("+€0,12", ViewText.signed(0.12));
    }

    @Test
    void decimalKeepsTheGivenDigits() {
        assertEquals("0,00134403", ViewText.decimal(0.00134403, 8));
        assertEquals("1,5", ViewText.decimal(1.5, 1));
        assertEquals("1.234,5678", ViewText.decimal(1234.5678, 4));
    }

    @Test
    void trimmedDecimalDropsTrailingZerosAndNegativeZero() {
        assertEquals("0,0015", ViewText.trimmedDecimal(0.0015, 8));
        assertEquals("0", ViewText.trimmedDecimal(0, 8));
        assertEquals("0", ViewText.trimmedDecimal(-1e-9, 8));
        assertEquals("1.234,5", ViewText.trimmedDecimal(1234.5, 8));
    }
}
