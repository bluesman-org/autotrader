package nl.jimkaplan.autotrader.bitvavo;

/**
 * Helpers for formatting Bitvavo market symbols.
 */
public final class MarketFormat {

    private MarketFormat() {
    }

    /**
     * Convert a ticker to Bitvavo's market format (e.g. "BTCEUR" or "BTC-EUR" to "BTC-EUR").
     * Bitvavo rejects market parameters without the dash (errorCode 205).
     *
     * @param ticker The ticker or trading pair
     * @return The market in Bitvavo's format
     */
    public static String toMarket(String ticker) {
        String market = ticker.replace("-", "");
        if (market.endsWith("EUR")) {
            market = market.substring(0, market.length() - "EUR".length()) + "-EUR";
        }
        return market;
    }
}
