package nl.jimkaplan.autotrader.strategy.backtest;

import nl.jimkaplan.autotrader.strategy.rule.CandleSeries;
import nl.jimkaplan.autotrader.strategy.rule.StrategySignals;

import java.util.ArrayList;
import java.util.List;

/**
 * Simulates one strategy over one series, deterministically.
 *
 * <p>The contract, also recorded in {@code docs/backtest.md}: signals are read on closed candles
 * and fill at the <em>next</em> candle's open; one position at a time; a fixed EUR amount per buy
 * (fees charged in EUR on top of it, as the exchange does); a fee per side; entry signals that
 * cannot be paid for are skipped and recorded; an open position is closed at the last close; the
 * equity is marked at every bar's close and the drawdown is measured against its running peak.</p>
 */
public final class BacktestEngine {

    private BacktestEngine() {
    }

    /**
     * Simulate the signals over the series.
     *
     * @param series   The candle series
     * @param signals  The entry and exit signals, aligned with the series
     * @param config   The simulation settings
     * @return The result, including every completed trade and every skipped signal
     * @throws IllegalArgumentException if the signals are not aligned with the series
     */
    public static BacktestResult run(CandleSeries series, StrategySignals signals, BacktestConfig config) {
        int length = series.length();
        if (signals.entry().length != length || signals.exit().length != length) {
            throw new IllegalArgumentException("signals must be evaluated on the given series");
        }

        double cash = config.initialCapital();
        double quantity = 0;
        double entryPrice = 0;
        long entryTime = 0;
        double entryFee = 0;
        double amountSpent = 0;
        boolean pendingBuy = false;
        boolean pendingSell = false;

        List<BacktestTrade> trades = new ArrayList<>();
        List<SkippedSignal> skippedSignals = new ArrayList<>();
        double peakEquity = cash;
        double maxDrawdown = 0;

        for (int i = 0; i < length; i++) {
            // Fill what was decided on the previous closed candle, at this bar's open
            if (pendingBuy) {
                pendingBuy = false;
                double price = series.opens()[i];
                double fee = config.amountPerBuy() * config.feeRate();
                if (cash < config.amountPerBuy() + fee) {
                    // Recorded against the signal's own bar (the fill would have happened here)
                    skippedSignals.add(new SkippedSignal(i - 1, series.times()[i - 1], SkipReason.INSUFFICIENT_BALANCE));
                } else {
                    amountSpent = config.amountPerBuy();
                    entryFee = fee;
                    quantity = config.amountPerBuy() / price;
                    cash -= config.amountPerBuy() + fee;
                    entryPrice = price;
                    entryTime = series.times()[i];
                }
            } else if (pendingSell) {
                pendingSell = false;
                double price = series.opens()[i];
                double gross = quantity * price;
                double exitFee = gross * config.feeRate();
                cash += gross - exitFee;
                trades.add(new BacktestTrade(entryTime, entryPrice, series.times()[i], price,
                        amountSpent, quantity, entryFee, exitFee,
                        gross - exitFee - amountSpent - entryFee));
                quantity = 0;
            }

            // Decide the next fill from this bar's signals
            if (i >= config.warmUpCandles() && i + 1 < length) {
                if (quantity == 0 && signals.entry()[i]) {
                    pendingBuy = true;
                } else if (quantity > 0 && signals.exit()[i]) {
                    pendingSell = true;
                }
            }

            // Mark to market at the close, tracking the drawdown against the running peak
            double equity = cash + quantity * series.closes()[i];
            if (equity > peakEquity) {
                peakEquity = equity;
            }
            double drawdown = (peakEquity - equity) / peakEquity * 100;
            if (drawdown > maxDrawdown) {
                maxDrawdown = drawdown;
            }
        }

        // A position still open at the end is closed at the last close, paying the same fee
        if (quantity > 0) {
            double price = series.closes()[length - 1];
            double gross = quantity * price;
            double exitFee = gross * config.feeRate();
            cash += gross - exitFee;
            trades.add(new BacktestTrade(entryTime, entryPrice, series.times()[length - 1], price,
                    amountSpent, quantity, entryFee, exitFee,
                    gross - exitFee - amountSpent - entryFee));
            quantity = 0;
        }

        double finalEquity = cash;
        double grossProfit = 0;
        double grossLoss = 0;
        int wins = 0;
        for (BacktestTrade trade : trades) {
            if (trade.profit() > 0) {
                wins++;
                grossProfit += trade.profit();
            } else {
                grossLoss -= trade.profit();
            }
        }
        double winRate = trades.isEmpty() ? 0 : 100.0 * wins / trades.size();
        double profitFactor;
        if (grossLoss == 0) {
            profitFactor = grossProfit > 0 ? Double.POSITIVE_INFINITY : 0;
        } else {
            profitFactor = grossProfit / grossLoss;
        }
        BacktestMetrics metrics = new BacktestMetrics(finalEquity,
                (finalEquity - config.initialCapital()) / config.initialCapital() * 100,
                maxDrawdown, trades.size(), winRate, profitFactor);
        return new BacktestResult(metrics, List.copyOf(trades), List.copyOf(skippedSignals));
    }
}
