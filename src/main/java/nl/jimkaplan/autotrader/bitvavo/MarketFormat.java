package nl.jimkaplan.autotrader.bitvavo;

import java.util.regex.Pattern;

/**
 * Helpers for formatting Bitvavo market symbols.
 */
public final class MarketFormat {

    /**
     * Base and quote in capitals with at most one dash, e.g. "BTC-EUR" or "BTCUSDT".
     * Markets end up in request paths and stored documents, so they must be well-formed.
     */
    private static final Pattern MARKET_PATTERN = Pattern.compile("[A-Z0-9]{1,20}(-[A-Z0-9]{1,20})?");

    private MarketFormat() {
    }

    /**
     * Convert a ticker to Bitvavo's market format (e.g. "BTCEUR" or "BTC-EUR" to "BTC-EUR").
     * Bitvavo rejects market parameters without the dash (errorCode 205).
     *
     * @param ticker The ticker or trading pair
     * @return The market in Bitvavo's format
     * @throws IllegalArgumentException if the result is not a well-formed market symbol
     */
    public static String toMarket(String ticker) {
        String market = ticker.replace("-", "");
        if (market.endsWith("EUR")) {
            market = market.substring(0, market.length() - "EUR".length()) + "-EUR";
        }
        if (!MARKET_PATTERN.matcher(market).matches()) {
            throw new IllegalArgumentException("Unsupported market: " + ticker);
        }
        return market;
    }

    /**
     * @param market Market in Bitvavo format
     * @return Whether the market is quoted in euro
     */
    public static boolean isEuroQuoted(String market) {
        return market != null && market.endsWith("-EUR");
    }

    /**
     * @param market Market in Bitvavo format
     * @return The market, unchanged
     * @throws IllegalArgumentException if the market is not quoted in euro
     */
    public static String requireEuroQuoted(String market) {
        if (!isEuroQuoted(market)) {
            throw new IllegalArgumentException("only EUR-quoted markets are supported: " + market);
        }
        return market;
    }
}
