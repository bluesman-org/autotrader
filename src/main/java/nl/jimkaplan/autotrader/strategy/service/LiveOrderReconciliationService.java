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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Reconciles the live engine's recorded orders and position with the exchange after a crash between
 * an order submission and its record.
 *
 * <p>{@code live_orders} records what the engine sent; a crash after the exchange accepted an order
 * but before the row was written leaves the order missing locally (or recorded as {@code FAILED}
 * when the failure was an ambiguous read timeout). At boot the exchange's order history is the
 * source of truth: this service adopts its {@code filled} orders, corrects the ambiguous failure
 * rows and brings the strategy's open position in line with the fills.</p>
 *
 * <p>Only {@code filled} orders are considered. An order that is still {@code open},
 * {@code partiallyFilled}, {@code canceled} or {@code expired} is logged and left alone: v1 does
 * not reconcile partial or abandoned orders. The service is idempotent — a second run sees the
 * order IDs it adopted the first time and writes nothing — and it never throws out of
 * {@link #reconcileAll()}, so a failing exchange call for one strategy cannot stop the others or
 * the boot itself.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LiveOrderReconciliationService {

    static final long FAILED_WINDOW_BEFORE_MILLIS = 10L * 60L * 1000L;
    static final long FAILED_WINDOW_AFTER_MILLIS = 60L * 1000L;
    static final String MISSING_REASON =
            "reconciled from the exchange's order history: filled but missing locally "
                    + "(a crash between submission and record)";
    static final String RECONCILED_NOTE =
            "(reconciled: the exchange had accepted the order despite the recorded failure)";

    private final LiveStrategyService liveStrategyService;
    private final BotConfigurationService botConfigurationService;
    private final BitvavoApiClient bitvavoApiClient;
    private final LiveOrderRepository liveOrderRepository;
    private final LivePositionRepository livePositionRepository;

    /**
     * Reconcile every non-dry-run strategy with a resolvable bot against the exchange. A failure
     * for one strategy is logged and the next one still runs; this method never throws.
     */
    public void reconcileAll() {
        for (LiveStrategy strategy : liveStrategyService.all()) {
            try {
                reconcile(strategy);
            } catch (RuntimeException ex) {
                log.warn("Reconciling live strategy {} failed: {}", strategy.getId(), ex.getMessage(), ex);
            }
        }
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
                "/orders?market=" + strategy.getMarket() + "&limit=100",
                OrderResponse[].class, bot.getApiKey(), bot.getApiSecret());
        List<OrderResponse> eligible = eligible(history);

        List<LiveOrder> rows = liveOrderRepository.findByStrategyIdOrderByCreatedTimeDesc(strategy.getId());
        Set<String> claimed = new HashSet<>();
        claimRecorded(rows, eligible, claimed);
        correctFailedRows(strategy, rows, eligible, claimed);
        List<LiveOrder> created = createMissing(strategy, eligible, claimed);
        syncPosition(strategy, rows, created);
    }

    private BotConfiguration activeBot(LiveStrategy strategy) {
        if (strategy.getBotId() == null) {
            return null;
        }
        return botConfigurationService.getBotConfiguration(strategy.getBotId()).orElse(null);
    }

    /**
     * The exchange orders reconciliation may act on: only the ones that filled. Every other status
     * is logged and ignored, which is the documented v1 limitation.
     */
    private List<OrderResponse> eligible(OrderResponse[] history) {
        List<OrderResponse> eligible = new ArrayList<>();
        if (history == null) {
            return eligible;
        }
        for (OrderResponse order : history) {
            if ("filled".equalsIgnoreCase(order.getStatus())) {
                eligible.add(order);
            } else {
                log.warn("Exchange order {} of {} is {} (not filled); reconciliation leaves it alone",
                        order.getOrderId(), order.getMarket(), order.getStatus());
            }
        }
        return eligible;
    }

    /**
     * Claim every exchange order a local row already references by order ID, so a recorded order is
     * never adopted a second time.
     */
    private void claimRecorded(List<LiveOrder> rows, List<OrderResponse> eligible, Set<String> claimed) {
        for (LiveOrder row : rows) {
            if (row.getOrderId() == null) {
                continue;
            }
            for (OrderResponse order : eligible) {
                String orderId = String.valueOf(order.getOrderId());
                if (row.getOrderId().equals(orderId)) {
                    claimed.add(orderId);
                }
            }
        }
    }

    /**
     * Correct {@code FAILED} buy/sell rows without an order ID that an unclaimed filled order of the
     * same side and a nearby creation time explains: the failure was an ambiguous read timeout and
     * the exchange did accept the order. The row is updated in place; no second row is created.
     */
    private void correctFailedRows(LiveStrategy strategy, List<LiveOrder> rows,
                                   List<OrderResponse> eligible, Set<String> claimed) {
        for (LiveOrder row : rows) {
            if (!ambiguous(row)) {
                continue;
            }
            OrderResponse match = claimable(row, eligible, claimed);
            if (match == null) {
                continue;
            }
            apply(row, match);
            row.setReason(appendReconciled(row.getReason()));
            liveOrderRepository.save(row);
            log.info("Reconciled failed live order {} of strategy {}: the exchange had filled it as {}",
                    row.getId(), strategy.getId(), match.getOrderId());
        }
    }

    private static boolean ambiguous(LiveOrder row) {
        return !row.isDryRun()
                && ("BUY".equals(row.getSide()) || "SELL".equals(row.getSide()))
                && "FAILED".equals(row.getStatus())
                && row.getOrderId() == null;
    }

    /**
     * The unclaimed filled order that explains a failed row: same side, created between ten minutes
     * before and one minute after the row. The order is claimed on a match.
     */
    private OrderResponse claimable(LiveOrder row, List<OrderResponse> eligible, Set<String> claimed) {
        long rowMillis = row.getCreatedTime().toEpochMilli();
        for (OrderResponse order : eligible) {
            String orderId = String.valueOf(order.getOrderId());
            if (claimed.contains(orderId) || !row.getSide().equalsIgnoreCase(order.getSide())) {
                continue;
            }
            long created = order.getCreated();
            if (created >= rowMillis - FAILED_WINDOW_BEFORE_MILLIS
                    && created <= rowMillis + FAILED_WINDOW_AFTER_MILLIS) {
                claimed.add(orderId);
                return order;
            }
        }
        return null;
    }

    /**
     * Adopt the exchange's figures into a local row, mirroring {@code LiveTradingService.recordOrder}.
     */
    private void apply(LiveOrder row, OrderResponse order) {
        row.setStatus("FILLED");
        row.setOrderId(String.valueOf(order.getOrderId()));
        row.setAmountQuote(amount(order.getFilledAmountQuote(), order.getAmountQuote()));
        row.setQuantity(amount(order.getFilledAmount(), order.getAmount()));
        row.setPrice(derivedPrice(order));
        row.setFee(fee(order));
        row.setErrorMessage(null);
    }

    /**
     * Create a {@code FILLED} row for every eligible exchange order no local row accounts for.
     *
     * @return The rows created
     */
    private List<LiveOrder> createMissing(LiveStrategy strategy, List<OrderResponse> eligible, Set<String> claimed) {
        List<LiveOrder> created = new ArrayList<>();
        for (OrderResponse order : eligible) {
            String orderId = String.valueOf(order.getOrderId());
            if (claimed.contains(orderId)) {
                continue;
            }
            claimed.add(orderId);
            LiveOrder row = LiveOrder.builder()
                    .strategyId(strategy.getId())
                    .market(strategy.getMarket())
                    .side(order.getSide().toUpperCase())
                    .status("FILLED")
                    .dryRun(false)
                    .signalOpenTime(0)
                    .reason(MISSING_REASON)
                    .amountQuote(amount(order.getFilledAmountQuote(), order.getAmountQuote()))
                    .quantity(amount(order.getFilledAmount(), order.getAmount()))
                    .price(derivedPrice(order))
                    .fee(fee(order))
                    .orderId(orderId)
                    .createdTime(Instant.ofEpochMilli(order.getCreated()))
                    .build();
            liveOrderRepository.save(row);
            created.add(row);
            log.info("Created missing live order {} for strategy {} ({} {})",
                    orderId, strategy.getId(), order.getSide(), order.getMarket());
        }
        return created;
    }

    /**
     * Bring the strategy's open position in line with its filled, non-dry-run orders: a buy opens,
     * a sell closes. When the position already matches nothing is written.
     *
     * @param rows    The strategy's rows after the failed-row correction
     * @param created The missing rows adopted in this run
     */
    private void syncPosition(LiveStrategy strategy, List<LiveOrder> rows, List<LiveOrder> created) {
        List<LiveOrder> fills = new ArrayList<>(rows);
        fills.addAll(created);
        fills.removeIf(fill -> fill.isDryRun() || !"FILLED".equals(fill.getStatus())
                || (!"BUY".equals(fill.getSide()) && !"SELL".equals(fill.getSide())));
        fills.sort(Comparator.comparing(LiveOrder::getCreatedTime));

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
        log.info("Opened a reconciled position for strategy {} from exchange order {}",
                strategy.getId(), buy.getOrderId());
    }

    private void closePosition(LivePosition position, LiveOrder sell, String strategyId) {
        if (sell == null) {
            log.warn("Cannot close the open position of strategy {}: no sell order explains the close", strategyId);
            return;
        }
        double profit = zeroIfNull(sell.getAmountQuote())
                - position.getQuantity() * position.getEntryPrice()
                - zeroIfNull(sell.getFee())
                - zeroIfNull(position.getEntryFee());
        position.setStatus("CLOSED");
        position.setExitPrice(sell.getPrice());
        position.setExitTime(sell.getSignalOpenTime() > 0 ? sell.getSignalOpenTime()
                : sell.getCreatedTime().toEpochMilli());
        position.setProfit(profit);
        livePositionRepository.save(position);
        log.info("Closed a reconciled position for strategy {} at {}: profit {} EUR",
                strategyId, sell.getPrice(), profit);
    }

    private static Double amount(BigDecimal value, BigDecimal fallback) {
        BigDecimal resolved = value != null ? value : fallback;
        return resolved == null ? null : resolved.doubleValue();
    }

    private static Double derivedPrice(OrderResponse order) {
        BigDecimal base = order.getFilledAmount();
        BigDecimal quote = order.getFilledAmountQuote();
        if (base == null || quote == null || base.signum() <= 0) {
            return null;
        }
        return quote.divide(base, 8, RoundingMode.HALF_UP).doubleValue();
    }

    private static Double fee(OrderResponse order) {
        return order.getFeePaid() == null ? null : order.getFeePaid().doubleValue();
    }

    private static String appendReconciled(String reason) {
        return reason == null || reason.isBlank() ? RECONCILED_NOTE : reason + " " + RECONCILED_NOTE;
    }

    private static double zeroIfNull(Double value) {
        return value == null ? 0 : value;
    }
}
