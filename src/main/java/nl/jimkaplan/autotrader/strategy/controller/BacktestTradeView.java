package nl.jimkaplan.autotrader.strategy.controller;

import nl.jimkaplan.autotrader.strategy.backtest.BacktestTrade;

import java.time.Instant;
import java.time.format.DateTimeFormatter;

/**
 * A trade row for the per-entry trades page: the trade with its fill times already formatted,
 * because the template engine has no date formatting helper on the classpath.
 *
 * @param entryTimeText The buy fill time, UTC
 * @param entryPrice    The buy fill price
 * @param exitTimeText  The sell fill time, UTC
 * @param exitPrice     The sell fill price
 * @param quantity      The quantity bought and sold
 * @param amount        The EUR amount spent on the buy, before its fee
 * @param entryFee      The fee paid on the buy, in EUR
 * @param exitFee       The fee paid on the sell, in EUR
 * @param profit        The net profit of the round trip, in EUR, after both fees
 */
public record BacktestTradeView(String entryTimeText, double entryPrice, String exitTimeText,
                                double exitPrice, double quantity, double amount, double entryFee,
                                double exitFee, double profit) {

    static BacktestTradeView of(BacktestTrade trade, DateTimeFormatter timestamp) {
        return new BacktestTradeView(
                timestamp.format(Instant.ofEpochMilli(trade.entryTime())),
                trade.entryPrice(),
                timestamp.format(Instant.ofEpochMilli(trade.exitTime())),
                trade.exitPrice(),
                trade.quantity(),
                trade.amount(),
                trade.entryFee(),
                trade.exitFee(),
                trade.profit());
    }
}
