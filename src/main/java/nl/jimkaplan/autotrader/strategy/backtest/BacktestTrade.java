package nl.jimkaplan.autotrader.strategy.backtest;

/**
 * One completed round trip.
 *
 * @param entryTime  Open time of the bar whose open the buy filled at, epoch milliseconds
 * @param entryPrice The fill price of the buy
 * @param exitTime   Open time of the bar whose open the sell filled at, epoch milliseconds
 * @param exitPrice  The fill price of the sell
 * @param amount     The EUR amount spent on the buy, before its fee
 * @param quantity   The quantity bought and sold
 * @param entryFee   The fee paid on the buy, in EUR
 * @param exitFee    The fee paid on the sell, in EUR
 * @param profit     Net profit of the round trip, in EUR, after both fees
 */
public record BacktestTrade(long entryTime, double entryPrice, long exitTime, double exitPrice,
                            double amount, double quantity, double entryFee, double exitFee,
                            double profit) {
}
