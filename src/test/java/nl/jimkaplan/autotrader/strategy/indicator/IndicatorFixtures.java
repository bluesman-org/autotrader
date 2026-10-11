package nl.jimkaplan.autotrader.strategy.indicator;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Reads the frozen indicator fixtures from the test resources.
 */
final class IndicatorFixtures {

    private IndicatorFixtures() {
    }

    /**
     * Read a fixture file, skipping the header line.
     *
     * @param name File name under {@code indicators/}
     * @return The rows, split on commas
     */
    static List<String[]> rows(String name) {
        try (InputStream stream = IndicatorFixtures.class.getResourceAsStream("/indicators/" + name)) {
            if (stream == null) {
                throw new IllegalStateException("Fixture not found: " + name);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                return reader.lines()
                        .skip(1)
                        .filter(line -> !line.isBlank())
                        .map(line -> line.split(","))
                        .toList();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Read one column of a fixture as doubles.
     *
     * @param rows  The fixture rows
     * @param index The column index
     * @return The column values
     */
    static double[] column(List<String[]> rows, int index) {
        return rows.stream().mapToDouble(row -> Double.parseDouble(row[index])).toArray();
    }
}
