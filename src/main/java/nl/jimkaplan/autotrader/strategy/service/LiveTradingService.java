package nl.jimkaplan.autotrader.strategy.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.jimkaplan.autotrader.bitvavo.client.BitvavoApiClient;
import nl.jimkaplan.autotrader.bitvavo.model.CreateOrderRequest;
import nl.jimkaplan.autotrader.bitvavo.model.CreateOrderResponse;
import nl.jimkaplan.autotrader.bitvavo.model.GetAccountBalanceResponse;
import nl.jimkaplan.autotrader.bitvavo.model.GetMarketResponse;
import nl.jimkaplan.autotrader.model.document.BitvavoCredentials;
import nl.jimkaplan.autotrader.repository.LiveOrderRepository;
import nl.jimkaplan.autotrader.repository.LivePositionRepository;
import nl.jimkaplan.autotrader.repository.LiveStrategyRepository;
import nl.jimkaplan.autotrader.service.BitvavoCredentialsService;
import nl.jimkaplan.autotrader.strategy.backtest.BacktestDefaults;
import nl.jimkaplan.autotrader.strategy.live.LiveDecision;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Evaluates a live strategy on closed candles with the same rules and parameters as the backtester,
 * and fills the resulting decisions — dry-run by default, recorded either way.
 *
 * <p>Decisions and fills are separate, mirroring {@code BacktestEngine}: a signal on the closed
 * candle is <em>decided</em> here and filled immediately at the arriving candle's open — the price
 * the exchange is trading at as the decision is made, which is the backtester's next-open fill
 * point. Live fills use what the exchange reports; dry-run fills use that open.</p>
 *
 * <p>A real order is written as a {@code PENDING} row with a generated client order ID before it is
 * sent, and that same row is updated in place to {@code FILLED}, or to {@code FAILED} when the
 * exchange reports a terminal non-fill. A response that can still fill — or one whose status this
 * version does not know — leaves the row {@code PENDING}, and no new decision is taken until the
 * next boot's reconciliation settles it. An ambiguous submission is marked awaiting reconciliation
 * as well: the timeout may have hidden an accepted order, so the engine must not send a second one.
 * The client order ID is sent to
 * the exchange, so a crash or an ambiguous timeout between the write and the response leaves a row
 * the next boot can reconcile against the exchange's order history (see
 * {@link LiveOrderReconciliationService}) instead of a lost or duplicated order.</p>
 *
 * <p>One position at a time: entry signals are only decided while flat, exit signals only while a
 * position is open. Live orders are priced and rounded from the exchange's market metadata; a
 * failed or skipped order is recorded, never thrown, so a running strategy survives it.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LiveTradingService {

    static final double MINIMUM_ORDER_EUR = 5.0;

    private final BitvavoCredentialsService credentialsService;
    private final BitvavoApiClient bitvavoApiClient;
    private final LiveOrderRepository liveOrderRepository;
    private final LivePositionRepository livePositionRepository;
    private final LiveStrategyRepository liveStrategyRepository;
    private final LiveAlertService alerts;

    /**
     * Strategies already warned about standing down, so a long wait logs once instead of on every
     * closed candle. An id is removed as soon as a decision is evaluated again without an
     * unsettled order.
     */
    private final Set<String> standDownWarned = ConcurrentHashMap.newKeySet();

    /**
     * One lock per strategy, held by the trading path and by an operator close. A strategy can be
     * stopped while a candle delivery that started just before the stop is still inside a fill;
     * serializing on the strategy id means a close either waits for that fill or sees the position
     * it closed, never placing a second sell on top of it.
     */
    private final ConcurrentMap<String, ReentrantLock> tradeLocks = new ConcurrentHashMap<>();

    /**
     * Decide what the last closed candle of the series asks for.
     *
     * @param strategy The live strategy
     * @param series   The strategy's candle window, oldest first, ending in the closed candle
     * @return The decision to fill at the next candle's open, or null when no signal fired or a
     *         real order is still awaiting reconciliation
     */
    public LiveDecision decide(LiveStrategy strategy, List<Candle> series) {
        if (series.isEmpty()) {
            return null;
        }
        CandleSeries candleSeries = CandleSeries.of(series);
        StrategyDefinition definition = definition(strategy);
        StrategySignals signals = StrategyEvaluator.evaluate(candleSeries, definition);
        int index = candleSeries.length() - 1;
        if (index < strategy.getWarmUpCandles()) {
            log.debug("{} has {} bars; still inside the {}-candle warm-up",
                    strategy.getId(), candleSeries.length(), strategy.getWarmUpCandles());
            return null;
        }
        if (liveOrderRepository.existsByStrategyIdAndAwaitingReconciliationTrue(strategy.getId())) {
            if (standDownWarned.add(strategy.getId())) {
                log.warn("{} has a real order awaiting reconciliation; no new decisions until it is "
                        + "settled", strategy.getId());
            }
            return null;
        }
        standDownWarned.remove(strategy.getId());
        Candle candle = series.get(series.size() - 1);
        boolean open = livePositionRepository.findByStrategyIdAndStatus(strategy.getId(), "OPEN").isPresent();
        if (open) {
            return signals.exit()[index]
                    ? new LiveDecision("SELL", reason("exit", definition.exitRules()), candle.getOpenTime())
                    : null;
        }
        return signals.entry()[index]
                ? new LiveDecision("BUY", reason("entry", definition.entryRules()), candle.getOpenTime())
                : null;
    }

    private ReentrantLock lockFor(String strategyId) {
        return tradeLocks.computeIfAbsent(strategyId, id -> new ReentrantLock());
    }

    /**
     * Fill a decision at the moment it is taken, at the arriving candle's open.
     *
     * @param strategy The live strategy
     * @param decision The decision taken on the candle that just closed
     * @param price    The arriving candle's open, the market price as the decision is made
     */
    public void fill(LiveStrategy strategy, LiveDecision decision, double price) {
        ReentrantLock lock = lockFor(strategy.getId());
        lock.lock();
        try {
            if ("SELL".equals(decision.side())) {
                sell(strategy, decision, price);
            } else {
                buy(strategy, decision, price);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Record that a closed candle could not be evaluated, so the gap is visible in the strategy's
     * orders instead of only in the log.
     *
     * @param strategy The live strategy
     * @param openTime The skipped candle's open time
     */
    public void recordSkippedCandle(LiveStrategy strategy, long openTime) {
        liveOrderRepository.save(LiveOrder.builder()
                .strategyId(strategy.getId())
                .market(strategy.getMarket())
                .side("SKIP")
                .status("SKIPPED")
                .dryRun(strategy.isDryRun())
                .signalOpenTime(openTime)
                .reason("candle unavailable within the retry window; its signal was not evaluated")
                .createdTime(Instant.now())
                .build());
    }

    private void buy(LiveStrategy strategy, LiveDecision decision, double price) {
        if (strategy.isDryRun()) {
            double quantity = strategy.getAmountPerBuy() / price;
            double fee = strategy.getAmountPerBuy() * BacktestDefaults.FEE_RATE;
            recordOrder(strategy, decision.signalOpenTime(), "BUY", "DRY_RUN", decision.reason(),
                    strategy.getAmountPerBuy(), quantity, price, fee, null, null);
            livePositionRepository.save(LivePosition.builder()
                    .strategyId(strategy.getId())
                    .market(strategy.getMarket())
                    .status("OPEN")
                    .quantity(quantity)
                    .entryPrice(price)
                    .entryFee(fee)
                    .entryTime(decision.signalOpenTime())
                    .dryRun(true)
                    .createdTime(Instant.now())
                    .build());
            log.info("[dry-run] {} bought {} at {} (the arriving candle's open) for {} EUR", strategy.getMarket(),
                    quantity, price, strategy.getAmountPerBuy());
            return;
        }

        BitvavoCredentials credentials = credentialsService.get().orElse(null);
        if (credentials == null) {
            recordOrder(strategy, decision.signalOpenTime(), "BUY", "REJECTED", decision.reason(), null, null,
                    null, null, null, "No Bitvavo API key is configured");
            return;
        }
        LiveOrder pending = null;
        try {
            double eur = eurBalance(credentials);
            if (eur < strategy.getAmountPerBuy() || eur < MINIMUM_ORDER_EUR) {
                recordOrder(strategy, decision.signalOpenTime(), "BUY", "REJECTED", decision.reason(), null, null,
                        null, null, null, "Insufficient EUR balance: " + eur);
                return;
            }
            GetMarketResponse market = bitvavoApiClient.get("/markets?market=" + strategy.getMarket(),
                    GetMarketResponse.class, credentials.getApiKey(), credentials.getApiSecret());
            int notionalDecimals = market.getNotionalDecimals() == null ? 2 : market.getNotionalDecimals();
            BigDecimal amountQuote = BigDecimal.valueOf(strategy.getAmountPerBuy())
                    .setScale(notionalDecimals, RoundingMode.DOWN);
            if (market.getMinOrderInQuoteAsset() != null
                    && amountQuote.compareTo(market.getMinOrderInQuoteAsset()) < 0) {
                recordOrder(strategy, decision.signalOpenTime(), "BUY", "REJECTED", decision.reason(), null, null,
                        null, null, null, "Below the market minimum of " + market.getMinOrderInQuoteAsset() + " EUR");
                return;
            }

            UUID clientOrderId = nextClientOrderId(strategy);
            double estimatedQuantity = amountQuote.doubleValue() / price;
            double estimatedFee = amountQuote.doubleValue() * BacktestDefaults.FEE_RATE;
            pending = preWrite(strategy, decision.signalOpenTime(), "BUY", decision.reason(), clientOrderId,
                    amountQuote.doubleValue(), estimatedQuantity, price, estimatedFee);

            CreateOrderRequest request = CreateOrderRequest.builder()
                    .market(strategy.getMarket())
                    .side("buy")
                    .orderType("market")
                    .amountQuote(amountQuote)
                    .operatorId(credentials.getOperatorId())
                    .clientOrderId(clientOrderId)
                    .build();
            CreateOrderResponse response = bitvavoApiClient.post("/order", request,
                    CreateOrderResponse.class, credentials.getApiKey(), credentials.getApiSecret());
            if (!applyFill(strategy, pending, response)) {
                return;
            }
            LivePosition position = LivePosition.builder()
                    .strategyId(strategy.getId())
                    .market(strategy.getMarket())
                    .status("OPEN")
                    .quantity(pending.getQuantity())
                    .entryPrice(pending.getPrice())
                    .entryFee(pending.getFee())
                    .entryTime(decision.signalOpenTime())
                    .dryRun(false)
                    .createdTime(Instant.now())
                    .build();
            livePositionRepository.save(position);
            alerts.realFill(strategy, pending, position);
            log.info("{} bought {} at {} for {} EUR (order {})", strategy.getMarket(), pending.getQuantity(),
                    pending.getPrice(), pending.getAmountQuote(), response.getOrderId());
        } catch (RuntimeException ex) {
            log.warn("Buying {} for strategy {} failed: {}", strategy.getMarket(), strategy.getId(),
                    ex.getMessage());
            if (pending == null) {
                recordOrder(strategy, decision.signalOpenTime(), "BUY", "REJECTED", decision.reason(), null, null,
                        null, null, null, ex.getMessage());
            } else {
                applyFailure(strategy, pending, ex.getMessage());
            }
        }
    }

    private void sell(LiveStrategy strategy, LiveDecision decision, double price) {
        LivePosition position = livePositionRepository
                .findByStrategyIdAndStatus(strategy.getId(), "OPEN")
                .orElse(null);
        if (position == null) {
            return;
        }
        sellAt(strategy, position, decision.signalOpenTime(), decision.reason(), price);
    }

    /**
     * Close an open position on the operator's instruction, reusing the signal sell path so the
     * pre-write, the client order id, the fill handling and the reconciliation invariants stay one
     * code path. The position is re-read under the same per-strategy lock the signal fills use, so
     * a fill already in flight when the strategy stopped either finishes first or sees the close.
     *
     * @param strategy The stopped strategy
     * @param exitTime The moment of the manual close, used as the order's signal open time and the
     *                 position's exit time
     * @param reason   Why the position is being closed
     * @param price    The current bid, used for the dry-run fill and the real pre-write estimate
     * @return The recorded order; the position is closed only when the fill is confirmed
     * @throws IllegalArgumentException when there is no open position
     */
    public LiveOrder closeManually(LiveStrategy strategy, long exitTime, String reason, double price) {
        ReentrantLock lock = lockFor(strategy.getId());
        lock.lock();
        try {
            LivePosition position = livePositionRepository
                    .findByStrategyIdAndStatus(strategy.getId(), "OPEN")
                    .orElseThrow(() -> new IllegalArgumentException(
                            "there is no open position to close"));
            if (liveOrderRepository.existsByStrategyIdAndAwaitingReconciliationTrue(strategy.getId())) {
                throw new IllegalArgumentException("a real order is awaiting reconciliation; restart "
                        + "the application after it settles before closing");
            }
            if (liveOrderRepository
                    .existsByStrategyIdAndDryRunFalseAndSideAndStatusAndSignalOpenTimeGreaterThanEqual(
                            strategy.getId(), "SELL", "FILLED", position.getEntryTime())) {
                throw new IllegalArgumentException("the exchange filled a sell for this open position "
                        + "but the position record is not updated; restart the application so "
                        + "reconciliation closes it before another close");
            }
            return sellAt(strategy, position, exitTime, reason, price);
        } finally {
            lock.unlock();
        }
    }

    private LiveOrder sellAt(LiveStrategy strategy, LivePosition position, long exitTime, String reason,
                             double price) {
        if (strategy.isDryRun()) {
            double proceeds = position.getQuantity() * price;
            double fee = proceeds * BacktestDefaults.FEE_RATE;
            double profit = TradeMath.closeProfit(proceeds, position.getQuantity(), position.getEntryPrice(),
                    position.getEntryFee(), fee);
            LiveOrder order = recordOrder(strategy, exitTime, "SELL", "DRY_RUN", reason, proceeds,
                    position.getQuantity(), price, fee, null, null);
            closePosition(position, exitTime, price, profit);
            log.info("[dry-run] {} sold {} at {}: profit {} EUR", strategy.getMarket(),
                    position.getQuantity(), price, profit);
            return order;
        }

        BitvavoCredentials credentials = credentialsService.get().orElse(null);
        if (credentials == null) {
            return recordOrder(strategy, exitTime, "SELL", "REJECTED", reason, null, null,
                    null, null, null, "No Bitvavo API key is configured");
        }
        LiveOrder pending = null;
        try {
            GetMarketResponse market = bitvavoApiClient.get("/markets?market=" + strategy.getMarket(),
                    GetMarketResponse.class, credentials.getApiKey(), credentials.getApiSecret());
            int quantityDecimals = market.getQuantityDecimals() == null ? 8 : market.getQuantityDecimals();
            BigDecimal amount = BigDecimal.valueOf(position.getQuantity())
                    .setScale(quantityDecimals, RoundingMode.DOWN);
            if (amount.signum() <= 0) {
                return recordOrder(strategy, exitTime, "SELL", "REJECTED", reason, null, null,
                        null, null, null,
                        "Position quantity rounds to zero at " + quantityDecimals + " decimals");
            }

            UUID clientOrderId = nextClientOrderId(strategy);
            double estimatedProceeds = position.getQuantity() * price;
            pending = preWrite(strategy, exitTime, "SELL", reason, clientOrderId,
                    estimatedProceeds, position.getQuantity(), price,
                    estimatedProceeds * BacktestDefaults.FEE_RATE);

            CreateOrderRequest request = CreateOrderRequest.builder()
                    .market(strategy.getMarket())
                    .side("sell")
                    .orderType("market")
                    .amount(amount)
                    .operatorId(credentials.getOperatorId())
                    .clientOrderId(clientOrderId)
                    .build();
            CreateOrderResponse response = bitvavoApiClient.post("/order", request,
                    CreateOrderResponse.class, credentials.getApiKey(), credentials.getApiSecret());
            if (!applyFill(strategy, pending, response)) {
                return pending;
            }
            // The exchange's fill price is what the position must record; the pre-write estimate
            // is only the fallback the response replaces.
            double exitPrice = pending.getPrice();
            double profit = TradeMath.closeProfit(TradeMath.zeroIfNull(pending.getAmountQuote()),
                    position.getQuantity(), position.getEntryPrice(), position.getEntryFee(),
                    TradeMath.zeroIfNull(pending.getFee()));
            closePosition(position, exitTime, exitPrice, profit);
            alerts.realFill(strategy, pending, position);
            log.info("{} sold {} at {}: profit {} EUR (order {})", strategy.getMarket(), pending.getQuantity(),
                    exitPrice, profit, response.getOrderId());
            return pending;
        } catch (RuntimeException ex) {
            log.warn("Selling {} for strategy {} failed: {}", strategy.getMarket(), strategy.getId(),
                    ex.getMessage());
            if (pending == null) {
                return recordOrder(strategy, exitTime, "SELL", "REJECTED", reason, null, null,
                        null, null, null, ex.getMessage());
            }
            applyFailure(strategy, pending, ex.getMessage());
            return pending;
        }
    }

    /**
     * The next client order ID for a real order, derived from the strategy's persisted sequence.
     * The sequence advances first (a monotonic update, so a crash can only skip a number), the
     * in-memory copy mirrors it, and the ID is derived from the consumed number — keeping the
     * three steps in one place so no order path can break the recovery invariant silently.
     */
    private UUID nextClientOrderId(LiveStrategy strategy) {
        long sequence = strategy.getNextOrderSequence();
        liveStrategyRepository.updateNextOrderSequence(strategy.getId(), sequence + 1);
        strategy.setNextOrderSequence(sequence + 1);
        return ClientOrderIds.forSequence(strategy.getId(), sequence);
    }

    /**
     * Write the row for a real order before it is sent, so the exchange's client order ID is on
     * record. The amounts are the dry-run style estimates, replaced by the response's figures once
     * the order is placed.
     */
    private LiveOrder preWrite(LiveStrategy strategy, long signalOpenTime, String side, String reason,
                               UUID clientOrderId, double amountQuote, double quantity, double price, double fee) {
        LiveOrder order = LiveOrder.builder()
                .strategyId(strategy.getId())
                .market(strategy.getMarket())
                .side(side)
                .status("PENDING")
                .dryRun(false)
                .signalOpenTime(signalOpenTime)
                .reason(reason)
                .amountQuote(amountQuote)
                .quantity(quantity)
                .price(price)
                .fee(fee)
                .clientOrderId(clientOrderId.toString())
                .awaitingReconciliation(true)
                .createdTime(Instant.now())
                .build();
        liveOrderRepository.save(order);
        return order;
    }

    /**
     * Update the pre-written row with what the exchange reports, falling back to its estimates for
     * anything the response omits. Only a {@code filled} response settles the row as FILLED; any
     * other status — partial, canceled, or one Bitvavo adds later — leaves it PENDING for the boot
     * reconciliation, and the caller must not open or close a position.
     *
     * @return Whether the order filled, so the caller may move the position
     */
    private boolean applyFill(LiveStrategy strategy, LiveOrder order, CreateOrderResponse response) {
        String status = response.getStatus();
        if (!"filled".equalsIgnoreCase(status)) {
            order.setOrderId(String.valueOf(response.getOrderId()));
            if (LiveOrderStatus.isTerminalNonFill(status)) {
                String note = LiveOrderStatus.partialFillNote(response.getFilledAmountQuote());
                order.setStatus("FAILED");
                order.setAwaitingReconciliation(false);
                order.setErrorMessage("exchange order status: " + status + note);
                liveOrderRepository.save(order);
                alerts.realFailure(strategy, order, "failed");
                log.warn("Order {} of strategy {} came back {}; recorded FAILED{}",
                        response.getOrderId(), order.getStrategyId(), status, note);
            } else {
                order.setAwaitingReconciliation(true);
                liveOrderRepository.save(order);
                alerts.realPending(strategy, order);
                log.warn("Order {} of strategy {} came back {}; left PENDING for reconciliation",
                        response.getOrderId(), order.getStrategyId(), status);
            }
            return false;
        }
        double quantity = TradeMath.filled(
                TradeMath.firstNonNull(response.getFilledAmount(), response.getAmount()),
                TradeMath.zeroIfNull(order.getQuantity()));
        order.setQuantity(quantity);
        order.setAmountQuote(TradeMath.filled(response.getFilledAmountQuote(),
                TradeMath.zeroIfNull(order.getAmountQuote())));
        order.setPrice(TradeMath.filled(TradeMath.ratio(response.getFilledAmountQuote(), quantity),
                TradeMath.zeroIfNull(order.getPrice())));
        order.setFee(TradeMath.filled(response.getFeePaid(), TradeMath.zeroIfNull(order.getFee())));
        order.setOrderId(String.valueOf(response.getOrderId()));
        order.setStatus("FILLED");
        order.setAwaitingReconciliation(false);
        liveOrderRepository.save(order);
        return true;
    }

    /**
     * Update the pre-written row to {@code FAILED}. It keeps its client order ID, so an ambiguous
     * timeout stays reconcilable against the exchange's history at the next boot. A row already
     * marked {@code FILLED} is never overwritten: the exchange holds the fill, and the failure here
     * happened after it.
     */
    private void applyFailure(LiveStrategy strategy, LiveOrder order, String message) {
        if ("FILLED".equals(order.getStatus())) {
            log.warn("Order {} of strategy {} filled at the exchange; not overwriting it as FAILED after {}",
                    order.getOrderId(), order.getStrategyId(), message);
            return;
        }
        order.setStatus("FAILED");
        order.setAwaitingReconciliation(true);
        order.setErrorMessage(message);
        liveOrderRepository.save(order);
        alerts.realFailure(strategy, order, "failed");
    }

    private void closePosition(LivePosition position, long exitOpenTime, double price, double profit) {
        position.setStatus("CLOSED");
        position.setExitPrice(price);
        position.setExitTime(exitOpenTime);
        position.setProfit(profit);
        livePositionRepository.save(position);
    }

    private double eurBalance(BitvavoCredentials credentials) {
        GetAccountBalanceResponse[] balances = bitvavoApiClient.get("/balance?symbol=EUR",
                GetAccountBalanceResponse[].class, credentials.getApiKey(), credentials.getApiSecret());
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

    private LiveOrder recordOrder(LiveStrategy strategy, long signalOpenTime, String side, String status,
                                  String reason, Double amountQuote, Double quantity, Double price,
                                  Double fee, String orderId, String errorMessage) {
        LiveOrder order = LiveOrder.builder()
                .strategyId(strategy.getId())
                .market(strategy.getMarket())
                .side(side)
                .status(status)
                .dryRun(strategy.isDryRun())
                .signalOpenTime(signalOpenTime)
                .reason(reason)
                .amountQuote(amountQuote)
                .quantity(quantity)
                .price(price)
                .fee(fee)
                .orderId(orderId)
                .errorMessage(errorMessage)
                .createdTime(Instant.now())
                .build();
        liveOrderRepository.save(order);
        if (!strategy.isDryRun() && "REJECTED".equals(status)) {
            alerts.realFailure(strategy, order, "rejected");
        }
        return order;
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
}
