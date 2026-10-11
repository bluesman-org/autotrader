package nl.jimkaplan.autotrader.strategy.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.jimkaplan.autotrader.bitvavo.client.BitvavoApiClient;
import nl.jimkaplan.autotrader.bitvavo.model.CreateOrderRequest;
import nl.jimkaplan.autotrader.bitvavo.model.CreateOrderResponse;
import nl.jimkaplan.autotrader.bitvavo.model.GetAccountBalanceResponse;
import nl.jimkaplan.autotrader.bitvavo.model.GetMarketResponse;
import nl.jimkaplan.autotrader.model.document.BotConfiguration;
import nl.jimkaplan.autotrader.repository.LiveOrderRepository;
import nl.jimkaplan.autotrader.repository.LivePositionRepository;
import nl.jimkaplan.autotrader.service.BotConfigurationService;
import nl.jimkaplan.autotrader.strategy.backtest.BacktestDefaults;
import nl.jimkaplan.autotrader.strategy.model.document.Candle;
import nl.jimkaplan.autotrader.strategy.model.document.LiveOrder;
import nl.jimkaplan.autotrader.strategy.model.document.LivePosition;
import nl.jimkaplan.autotrader.strategy.model.document.LiveStrategy;
import nl.jimkaplan.autotrader.strategy.rule.CandleSeries;
import nl.jimkaplan.autotrader.strategy.rule.LogicalOperator;
import nl.jimkaplan.autotrader.strategy.rule.MacdParameters;
import nl.jimkaplan.autotrader.strategy.rule.ParabolicSarParameters;
import nl.jimkaplan.autotrader.strategy.rule.RuleCondition;
import nl.jimkaplan.autotrader.strategy.rule.RuleGroup;
import nl.jimkaplan.autotrader.strategy.rule.StrategyDefinition;
import nl.jimkaplan.autotrader.strategy.rule.StrategyEvaluator;
import nl.jimkaplan.autotrader.strategy.rule.StrategySignals;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;

/**
 * Evaluates a live strategy on every closed candle with the same rules, parameters and fill model
 * a backtest uses, and records (dry-run, the default) or places (live) the resulting orders.
 *
 * <p>The series it receives is the strategy's full history, which keeps the signals identical to a
 * backtest over that history: the indicators are causal, so evaluating bar by bar can never
 * disagree with evaluating the whole series at once (the replay gate).</p>
 *
 * <p>One position at a time: entry signals are only acted on while flat, exit signals only while
 * an entry is open. Live orders are priced and rounded from the exchange's market metadata; a
 * failed or skipped order is recorded, never thrown, so a running strategy survives it.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LiveTradingService {

    static final double MINIMUM_ORDER_EUR = 5.0;

    private final BotConfigurationService botConfigurationService;
    private final BitvavoApiClient bitvavoApiClient;
    private final LiveOrderRepository liveOrderRepository;
    private final LivePositionRepository livePositionRepository;

    /**
     * Evaluate the last candle of the series and trade its signals.
     *
     * @param strategy The live strategy
     * @param series   The strategy's full candle history, oldest first, ending in the closed candle
     */
    public void evaluateAndTrade(LiveStrategy strategy, List<Candle> series) {
        if (series.isEmpty()) {
            return;
        }
        CandleSeries candleSeries = CandleSeries.of(series);
        StrategyDefinition definition = definition(strategy);
        StrategySignals signals = StrategyEvaluator.evaluate(candleSeries, definition);
        int index = candleSeries.length() - 1;
        if (index < strategy.getWarmUpCandles()) {
            log.debug("{} has {} bars; still inside the {}-candle warm-up",
                    strategy.getId(), candleSeries.length(), strategy.getWarmUpCandles());
            return;
        }
        Candle candle = series.get(series.size() - 1);
        boolean open = livePositionRepository.findByStrategyIdAndStatus(strategy.getId(), "OPEN").isPresent();
        if (open) {
            if (signals.exit()[index]) {
                close(strategy, candle, definition.exitRules());
            }
        } else if (signals.entry()[index]) {
            buy(strategy, candle, definition.entryRules());
        }
    }

    private void buy(LiveStrategy strategy, Candle candle, RuleGroup rules) {
        double price = candle.getClose().doubleValue();
        String reason = reason("entry", rules);
        if (strategy.isDryRun()) {
            double quantity = strategy.getAmountPerBuy() / price;
            double fee = strategy.getAmountPerBuy() * BacktestDefaults.FEE_RATE;
            recordOrder(strategy, candle, "BUY", "DRY_RUN", reason,
                    strategy.getAmountPerBuy(), quantity, price, fee, null, null);
            livePositionRepository.save(LivePosition.builder()
                    .strategyId(strategy.getId())
                    .market(strategy.getMarket())
                    .status("OPEN")
                    .quantity(quantity)
                    .entryPrice(price)
                    .entryFee(fee)
                    .entryTime(candle.getOpenTime())
                    .dryRun(true)
                    .createdTime(Instant.now())
                    .build());
            log.info("[dry-run] {} bought {} at {} for {} EUR", strategy.getMarket(), quantity, price,
                    strategy.getAmountPerBuy());
            return;
        }

        BotConfiguration bot = activeBot(strategy);
        if (bot == null) {
            recordOrder(strategy, candle, "BUY", "FAILED", reason, null, null, null, null, null,
                    "No active bot configuration for " + strategy.getBotId());
            return;
        }
        try {
            double eur = eurBalance(bot);
            if (eur < strategy.getAmountPerBuy() || eur < MINIMUM_ORDER_EUR) {
                recordOrder(strategy, candle, "BUY", "FAILED", reason, null, null, null, null, null,
                        "Insufficient EUR balance: " + eur);
                return;
            }
            GetMarketResponse market = bitvavoApiClient.get("/markets?market=" + strategy.getMarket(),
                    GetMarketResponse.class, bot.getApiKey(), bot.getApiSecret());
            int notionalDecimals = market.getNotionalDecimals() == null ? 2 : market.getNotionalDecimals();
            BigDecimal amountQuote = BigDecimal.valueOf(strategy.getAmountPerBuy())
                    .setScale(notionalDecimals, RoundingMode.DOWN);
            if (market.getMinOrderInQuoteAsset() != null
                    && amountQuote.compareTo(market.getMinOrderInQuoteAsset()) < 0) {
                recordOrder(strategy, candle, "BUY", "FAILED", reason, null, null, null, null, null,
                        "Below the market minimum of " + market.getMinOrderInQuoteAsset() + " EUR");
                return;
            }
            CreateOrderRequest request = CreateOrderRequest.builder()
                    .market(strategy.getMarket())
                    .side("buy")
                    .orderType("market")
                    .amountQuote(amountQuote)
                    .operatorId(bot.getOperatorId())
                    .build();
            CreateOrderResponse response = bitvavoApiClient.post("/order", request,
                    CreateOrderResponse.class, bot.getApiKey(), bot.getApiSecret());
            double quantity = filled(baseValue(response.getFilledAmount(), response.getAmount()),
                    amountQuote.doubleValue() / price);
            double fillPrice = filled(ratio(baseValue(response.getFilledAmountQuote(), null), quantity), price);
            double fee = filled(baseValue(response.getFeePaid(), null), amountQuote.doubleValue()
                    * BacktestDefaults.FEE_RATE);
            recordOrder(strategy, candle, "BUY", "FILLED", reason, amountQuote.doubleValue(),
                    quantity, fillPrice, fee, String.valueOf(response.getOrderId()), null);
            livePositionRepository.save(LivePosition.builder()
                    .strategyId(strategy.getId())
                    .market(strategy.getMarket())
                    .status("OPEN")
                    .quantity(quantity)
                    .entryPrice(fillPrice)
                    .entryFee(fee)
                    .entryTime(candle.getOpenTime())
                    .dryRun(false)
                    .createdTime(Instant.now())
                    .build());
            log.info("{} bought {} at {} for {} EUR (order {})", strategy.getMarket(), quantity,
                    fillPrice, amountQuote, response.getOrderId());
        } catch (RuntimeException ex) {
            log.warn("Buying {} for strategy {} failed: {}", strategy.getMarket(), strategy.getId(),
                    ex.getMessage());
            recordOrder(strategy, candle, "BUY", "FAILED", reason, null, null, null, null, null,
                    ex.getMessage());
        }
    }

    private void close(LiveStrategy strategy, Candle candle, RuleGroup rules) {
        LivePosition position = livePositionRepository
                .findByStrategyIdAndStatus(strategy.getId(), "OPEN")
                .orElse(null);
        if (position == null) {
            return;
        }
        double price = candle.getClose().doubleValue();
        String reason = reason("exit", rules);
        if (strategy.isDryRun()) {
            double proceeds = position.getQuantity() * price;
            double fee = proceeds * BacktestDefaults.FEE_RATE;
            double profit = proceeds - position.getQuantity() * position.getEntryPrice() - fee
                    - zeroIfNull(position.getEntryFee());
            recordOrder(strategy, candle, "SELL", "DRY_RUN", reason, proceeds, position.getQuantity(),
                    price, fee, null, null);
            closePosition(position, candle, price, profit);
            log.info("[dry-run] {} sold {} at {}: profit {} EUR", strategy.getMarket(),
                    position.getQuantity(), price, profit);
            return;
        }

        BotConfiguration bot = activeBot(strategy);
        if (bot == null) {
            recordOrder(strategy, candle, "SELL", "FAILED", reason, null, null, null, null, null,
                    "No active bot configuration for " + strategy.getBotId());
            return;
        }
        try {
            GetMarketResponse market = bitvavoApiClient.get("/markets?market=" + strategy.getMarket(),
                    GetMarketResponse.class, bot.getApiKey(), bot.getApiSecret());
            int quantityDecimals = market.getQuantityDecimals() == null ? 8 : market.getQuantityDecimals();
            BigDecimal amount = BigDecimal.valueOf(position.getQuantity())
                    .setScale(quantityDecimals, RoundingMode.DOWN);
            if (amount.signum() <= 0) {
                recordOrder(strategy, candle, "SELL", "FAILED", reason, null, null, null, null, null,
                        "Position quantity rounds to zero at " + quantityDecimals + " decimals");
                return;
            }
            CreateOrderRequest request = CreateOrderRequest.builder()
                    .market(strategy.getMarket())
                    .side("sell")
                    .orderType("market")
                    .amount(amount)
                    .operatorId(bot.getOperatorId())
                    .build();
            CreateOrderResponse response = bitvavoApiClient.post("/order", request,
                    CreateOrderResponse.class, bot.getApiKey(), bot.getApiSecret());
            double proceeds = filled(baseValue(response.getFilledAmountQuote(), null),
                    amount.doubleValue() * price);
            double fee = filled(baseValue(response.getFeePaid(), null),
                    proceeds * BacktestDefaults.FEE_RATE);
            double profit = proceeds - position.getQuantity() * position.getEntryPrice() - fee
                    - zeroIfNull(position.getEntryFee());
            recordOrder(strategy, candle, "SELL", "FILLED", reason, proceeds, amount.doubleValue(),
                    filled(ratio(baseValue(response.getFilledAmountQuote(), null),
                            amount.doubleValue()), price), fee, String.valueOf(response.getOrderId()), null);
            closePosition(position, candle, price, profit);
            log.info("{} sold {} at {}: profit {} EUR (order {})", strategy.getMarket(),
                    amount.doubleValue(), price, profit, response.getOrderId());
        } catch (RuntimeException ex) {
            log.warn("Selling {} for strategy {} failed: {}", strategy.getMarket(), strategy.getId(),
                    ex.getMessage());
            recordOrder(strategy, candle, "SELL", "FAILED", reason, null, null, null, null, null,
                    ex.getMessage());
        }
    }

    private void closePosition(LivePosition position, Candle candle, double price, double profit) {
        position.setStatus("CLOSED");
        position.setExitPrice(price);
        position.setExitTime(candle.getOpenTime());
        position.setProfit(profit);
        livePositionRepository.save(position);
    }

    private BotConfiguration activeBot(LiveStrategy strategy) {
        if (strategy.getBotId() == null) {
            return null;
        }
        return botConfigurationService.getBotConfiguration(strategy.getBotId()).orElse(null);
    }

    private double eurBalance(BotConfiguration bot) {
        GetAccountBalanceResponse[] balances = bitvavoApiClient.get("/balance?symbol=EUR",
                GetAccountBalanceResponse[].class, bot.getApiKey(), bot.getApiSecret());
        if (balances == null) {
            return 0;
        }
        for (GetAccountBalanceResponse balance : balances) {
            if ("EUR".equals(balance.getSymbol())) {
                return balance.getAvailable().doubleValue();
            }
        }
        return 0;
    }

    private void recordOrder(LiveStrategy strategy, Candle candle, String side, String status,
                             String reason, Double amountQuote, Double quantity, Double price,
                             Double fee, String orderId, String errorMessage) {
        liveOrderRepository.save(LiveOrder.builder()
                .strategyId(strategy.getId())
                .market(strategy.getMarket())
                .side(side)
                .status(status)
                .dryRun(strategy.isDryRun())
                .signalOpenTime(candle.getOpenTime())
                .reason(reason)
                .amountQuote(amountQuote)
                .quantity(quantity)
                .price(price)
                .fee(fee)
                .orderId(orderId)
                .errorMessage(errorMessage)
                .createdTime(Instant.now())
                .build());
    }

    private static StrategyDefinition definition(LiveStrategy strategy) {
        return new StrategyDefinition(
                new MacdParameters(strategy.getMacdFastPeriod(), strategy.getMacdSlowPeriod(),
                        strategy.getMacdSignalPeriod()),
                new ParabolicSarParameters(strategy.getSarAcceleration(), strategy.getSarMaximum()),
                ruleGroup(strategy.getEntryOperator(), strategy.getEntryConditions()),
                ruleGroup(strategy.getExitOperator(), strategy.getExitConditions()));
    }

    private static RuleGroup ruleGroup(String operator, List<String> conditions) {
        return new RuleGroup(LogicalOperator.valueOf(operator),
                conditions.stream().map(RuleCondition::valueOf).toList());
    }

    private static String reason(String side, RuleGroup rules) {
        return side + " " + rules.operator() + " " + rules.conditions();
    }

    private static BigDecimal baseValue(BigDecimal value, BigDecimal fallback) {
        return value != null ? value : fallback;
    }

    private static double filled(BigDecimal value, double fallback) {
        return value != null ? value.doubleValue() : fallback;
    }

    private static BigDecimal ratio(BigDecimal amountQuote, double quantity) {
        return amountQuote != null && quantity > 0
                ? amountQuote.divide(BigDecimal.valueOf(quantity), 8, RoundingMode.HALF_UP)
                : null;
    }

    private static double zeroIfNull(Double value) {
        return value == null ? 0 : value;
    }
}
