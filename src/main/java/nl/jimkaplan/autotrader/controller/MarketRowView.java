package nl.jimkaplan.autotrader.controller;

/**
 * One row of the settings page's trading-pair table: the pair, how it got there, what the account
 * did on it and whether the forms offer it.
 *
 * @param market    The market symbol, e.g. {@code BTC-EUR}
 * @param source    {@code discovered} or {@code manual}
 * @param status    Bitvavo's status, or a dash when the exchange could not be asked
 * @param enabled   Whether the forms and the ledger offer the pair
 * @param trades    How many buys and sells the account made on it
 * @param firstText The account's first trade, or a dash
 * @param lastText  The account's last trade, or a dash
 */
public record MarketRowView(String market, String source, String status, boolean enabled,
                            int trades, String firstText, String lastText) {
}
