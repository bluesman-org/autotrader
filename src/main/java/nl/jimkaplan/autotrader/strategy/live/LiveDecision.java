package nl.jimkaplan.autotrader.strategy.live;

/**
 * A decision taken on a closed candle, carried to the next candle boundary and filled there at
 * that candle's open — the same pending-fill shape the backtester uses.
 *
 * @param side           "BUY" or "SELL"
 * @param reason         The rule set that fired, e.g. "entry ALL [MACD_BULLISH_CROSSOVER]"
 * @param signalOpenTime The open time of the candle whose signal produced the decision
 */
public record LiveDecision(String side, String reason, long signalOpenTime) {
}
