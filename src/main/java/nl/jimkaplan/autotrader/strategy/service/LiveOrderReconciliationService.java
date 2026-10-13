package nl.jimkaplan.autotrader.strategy.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.jimkaplan.autotrader.bitvavo.client.BitvavoApiClient;
import nl.jimkaplan.autotrader.bitvavo.model.OrderResponse;
import nl.jimkaplan.autotrader.model.document.BotConfiguration;
import nl.jimkaplan.autotrader.repository.LiveOrderRepository;
import nl.jimkaplan.autotrader.repository.LivePositionRepository;
import nl.jimkaplan.autotrader.service.BotConfigurationService;
import nl.jimkaplan.autotrader.strategy.model.document.LiveOrder;
import nl.jimkaplan.autotrader.strategy.model.document.LivePosition;
import nl.jimkaplan.autotrader.strategy.model.document.LiveStrategy;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reconciles the live engine's recorded orders and position with the exchange after a crash between
 * an order submission and its response.
 *
 * <p>A real order is written as a {@code PENDING} row carrying the client order ID that was sent
 * with it, so at boot this service can look that ID up in the exchange's order history and settle
 * the row: a filled order becomes {@code FILLED} with the exchange's figures, and a {@code PENDING}
 * row the exchange does not know was never accepted and becomes {@code FAILED}. Only the rows of
 * this strategy are ever touched — nothing is adopted from the history that a row's client order ID
 * does not claim — so two strategies (or the legacy path) trading one account and market cannot
 * contaminate each other's orders or positions.</p>
 *
 * <p>The history is bounded to the strategy's lifetime ({@code start} = the strategy's creation
 * time), so fills that predate it are never considered, and only {@code filled} entries are acted
 * on: an order still {@code open}, {@code partiallyFilled}, {@code canceled} or {@code expired} is
 * warned and left for a later boot (v1 does not reconcile partial or abandoned orders). The service
 * is idempotent — once a row is settled it is no longer {@code PENDING} or {@code FAILED}, so a
 * second run writes nothing — and it never throws. It returns the ids of the strategies it could
 * not reconcile, so the caller can hold them back instead of letting them trade on stale state.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LiveOrderReconciliationService {

    static final String RECONCILED_NOTE =
            "(reconciled: the exchange had accepted the order despite the recorded failure)";
    static final String NOT_ACCEPTED_MESSAGE =
            "not found in the exchange's order history within the strategy's lifetime; "
                    + "treated as never accepted";

    private final LiveStrategyService liveStrategyService;
    private final BotConfigurationService botConfigurationService;
    private final BitvavoApiClient bitvavoApiClient;
    private final LiveOrderRepository liveOrderRepository;
    private final LivePositionRepository livePositionRepository;

    /**
     * Reconcile every non-dry-run strategy with a resolvable bot against the exchange.
     *
     * @return The ids of the strategies whose reconciliation threw; those must not be started
     */
    public Set<String> reconcileAll() {
        Set<String> failed = new LinkedHashSet<>();
        for (LiveStrategy strategy : liveStrategyService.all()) {
            try {
                reconcile(strategy);
            } catch (RuntimeException ex) {
                failed.add(strategy.getId());
                log.error("Reconciling live strategy {} failed; it will not be started: {}",
                        strategy.getId(), ex.getMessage(), ex);
            }
        }
        return failed;
    }

    private void reconcile(LiveStrategy strategy) {
        if (strategy.isDryRun()) {
            log.debug("Skipping reconciliation for dry-run strategy {}", strategy.getId());
            return;
        }
        BotConfiguration bot = activeBot(strategy);
        if (bot == null) {
            log.warn("Cannot reconcile live strategy {}: no active bot configuration for {}",
                    strategy.getId(), strategy.getBotId());
            return;
        }

        OrderResponse[] history = bitvavoApiClient.get(
                "/orders?market=" + strategy.getMarket()
                        + "&start=" + strategy.getCreatedTime().toEpochMilli() + "&limit=100",
                OrderResponse[].class, bot.getApiKey(), bot.getApiSecret());
        Map<String, OrderResponse> byClientOrderId = byClientOrderId(history, strategy.getId());

        List<LiveOrder> rows = liveOrderRepository.findByStrategyIdOrderByCreatedTimeDesc(strategy.getId());
        for (LiveOrder row : rows) {
            resolve(strategy, row, byClientOrderId);
        }
        syncPosition(strategy, rows);
    }

    private BotConfiguration activeBot(LiveStrategy strategy) {
        if (strategy.getBotId() == null) {
            return null;
        }
        return botConfigurationService.getBotConfiguration(strategy.getBotId()).orElse(null);
    }

    /**
     * Index the history by client order ID. An entry missing any of the fields reconciliation keys
     * on is warned and skipped rather than trusted.
     */
    private Map<String, OrderResponse> byClientOrderId(OrderResponse[] history, String strategyId) {
        Map<String, OrderResponse> byClientOrderId = new LinkedHashMap<>();
        if (history == null) {
            return byClientOrderId;
        }
        for (OrderResponse order : history) {
            if (order.getOrderId() == null || order.getCreated() == null
                    || order.getSide() == null || order.getClientOrderId() == null) {
                log.warn("Exchange order entry for strategy {} is incomplete "
                        + "(orderId/created/side/clientOrderId missing); skipped", strategyId);
                continue;
            }
            byClientOrderId.put(order.getClientOrderId().toString(), order);
        }
        return byClientOrderId;
    }

    /**
     * Settle one of this strategy's un-settled rows against the history entry that carries its
     * client order ID.
     */
    private void resolve(LiveStrategy strategy, LiveOrder row, Map<String, OrderResponse> byClientOrderId) {
        if (row.getClientOrderId() == null
                || (!"PENDING".equals(row.getStatus()) && !"FAILED".equals(row.getStatus()))) {
            return;
        }
        OrderResponse order = byClientOrderId.get(row.getClientOrderId());
        if (order == null) {
            if ("PENDING".equals(row.getStatus())) {
                notAccepted(row);
            }
            return;
        }
        if (!"filled".equalsIgnoreCase(order.getStatus())) {
            log.warn("Exchange order {} for strategy {} is {} (not filled); row {} is left alone",
                    order.getOrderId(), strategy.getId(), order.getStatus(), row.getId());
            return;
        }
        boolean corrected = "FAILED".equals(row.getStatus());
        apply(row, order);
        if (corrected) {
            row.setReason(appendReconciled(row.getReason()));
        }
        liveOrderRepository.save(row);
        log.info("Reconciled live order {} of strategy {} as {}", row.getId(), strategy.getId(),
                order.getOrderId());
    }

    private void notAccepted(LiveOrder row) {
        row.setStatus("FAILED");
        row.setErrorMessage(NOT_ACCEPTED_MESSAGE);
        liveOrderRepository.save(row);
        log.warn("Pending live order {} was not found in the exchange's order history; "
                + "treated as never accepted", row.getId());
    }

    /**
     * Adopt the exchange's figures into the row, keeping its pre-write estimates for anything the
     * response omits.
     */
    private static void apply(LiveOrder row, OrderResponse order) {
        row.setStatus("FILLED");
        row.setOrderId(String.valueOf(order.getOrderId()));
        Double amountQuote = TradeMath.amount(order.getFilledAmountQuote(), order.getAmountQuote());
        Double quantity = TradeMath.amount(order.getFilledAmount(), order.getAmount());
        Double price = TradeMath.price(order.getFilledAmount(), order.getFilledAmountQuote());
        row.setAmountQuote(amountQuote != null ? amountQuote : row.getAmountQuote());
        row.setQuantity(quantity != null ? quantity : row.getQuantity());
        row.setPrice(price != null ? price : row.getPrice());
        if (order.getFeePaid() != null) {
            row.setFee(order.getFeePaid().doubleValue());
        }
        row.setErrorMessage(null);
    }

    /**
     * Bring the strategy's open position in line with its filled, non-dry-run orders: a buy opens,
     * a sell closes. When the position already matches nothing is written.
     */
    private void syncPosition(LiveStrategy strategy, List<LiveOrder> rows) {
        List<LiveOrder> fills = rows.stream()
                .filter(fill -> !fill.isDryRun() && "FILLED".equals(fill.getStatus())
                        && ("BUY".equals(fill.getSide()) || "SELL".equals(fill.getSide())))
                .sorted(Comparator.comparing(LiveOrder::getCreatedTime))
                .toList();

        LiveOrder lastBuy = null;
        LiveOrder lastSell = null;
        boolean expectedOpen = false;
        for (LiveOrder fill : fills) {
            if ("BUY".equals(fill.getSide())) {
                lastBuy = fill;
                expectedOpen = true;
            } else {
                lastSell = fill;
                expectedOpen = false;
            }
        }

        Optional<LivePosition> stored = livePositionRepository.findByStrategyIdAndStatus(strategy.getId(), "OPEN");
        if (expectedOpen && stored.isEmpty()) {
            openPosition(strategy, lastBuy);
        } else if (!expectedOpen && stored.isPresent()) {
            closePosition(stored.get(), lastSell, strategy.getId());
        }
    }

    private void openPosition(LiveStrategy strategy, LiveOrder buy) {
        if (buy.getQuantity() == null || buy.getPrice() == null) {
            log.warn("Cannot open a reconciled position for strategy {} from order {}: "
                    + "quantity or price is missing", strategy.getId(), buy.getId());
            return;
        }
        long entryTime = buy.getSignalOpenTime() > 0 ? buy.getSignalOpenTime()
                : buy.getCreatedTime().toEpochMilli();
        livePositionRepository.save(LivePosition.builder()
                .strategyId(strategy.getId())
                .market(strategy.getMarket())
                .status("OPEN")
                .quantity(buy.getQuantity())
                .entryPrice(buy.getPrice())
                .entryFee(buy.getFee())
                .entryTime(entryTime)
                .dryRun(false)
                .createdTime(Instant.now())
                .build());
        log.info("Opened a reconciled position for strategy {} from live order {}",
                strategy.getId(), buy.getOrderId());
    }

    private void closePosition(LivePosition position, LiveOrder sell, String strategyId) {
        if (sell == null) {
            log.warn("Cannot close the open position of strategy {}: no sell order explains the close", strategyId);
            return;
        }
        double profit = TradeMath.closeProfit(TradeMath.zeroIfNull(sell.getAmountQuote()),
                position.getQuantity(), position.getEntryPrice(), position.getEntryFee(),
                TradeMath.zeroIfNull(sell.getFee()));
        position.setStatus("CLOSED");
        position.setExitPrice(sell.getPrice());
        position.setExitTime(sell.getSignalOpenTime() > 0 ? sell.getSignalOpenTime()
                : sell.getCreatedTime().toEpochMilli());
        position.setProfit(profit);
        livePositionRepository.save(position);
        log.info("Closed a reconciled position for strategy {} at {}: profit {} EUR",
                strategyId, sell.getPrice(), profit);
    }

    private static String appendReconciled(String reason) {
        return reason == null || reason.isBlank() ? RECONCILED_NOTE : reason + " " + RECONCILED_NOTE;
    }
}
