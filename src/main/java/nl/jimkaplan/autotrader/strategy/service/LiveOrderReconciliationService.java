package nl.jimkaplan.autotrader.strategy.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.jimkaplan.autotrader.bitvavo.client.BitvavoApiClient;
import nl.jimkaplan.autotrader.bitvavo.model.OrderResponse;
import nl.jimkaplan.autotrader.model.document.BotConfiguration;
import nl.jimkaplan.autotrader.repository.LiveOrderRepository;
import nl.jimkaplan.autotrader.repository.LivePositionRepository;
import nl.jimkaplan.autotrader.repository.LiveStrategyRepository;
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
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Reconciles the live engine's recorded orders and position with the exchange after a crash between
 * an order submission and its response.
 *
 * <p>A real order is written as a {@code PENDING} row carrying the client order ID that was sent
 * with it, so at boot this service can look that ID up in the exchange's order history and settle
 * the row: a filled order becomes {@code FILLED} with the exchange's figures, and a {@code PENDING}
 * row the exchange does not know — in a page known to be complete — was never accepted and becomes
 * {@code FAILED}. An incomplete page (the limit was reached, or an entry could not be read) leaves
 * such a row {@code PENDING} for the next boot. Only the rows of this strategy are ever touched —
 * nothing is adopted from the history that a row's client order ID does not claim — so two
 * strategies (or the legacy path) trading one account and market cannot contaminate each other's
 * orders or positions.</p>
 *
 * <p>The history is bounded to the strategy's lifetime ({@code start} = the strategy's creation
 * time) and read with the exchange's maximum page size, so fills that predate it are never
 * considered, and only {@code filled} entries are acted on: an order still {@code open},
 * {@code partiallyFilled}, {@code canceled} or {@code expired} is warned and left for a later boot
 * (v1 does not reconcile partial or abandoned orders). A real {@code FAILED} row recorded before
 * the engine sent a client order ID cannot be attributed and is warned for a manual check. The
 * service is idempotent — once a row is settled it is no longer {@code PENDING} or {@code FAILED},
 * so a second run writes nothing — and it never throws. It returns the ids of the strategies it
 * could not reconcile, so the caller can hold them back instead of letting them trade on stale
 * state.</p>
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
    static final int ORDER_HISTORY_LIMIT = 1000;

    /**
     * How many sequence numbers the restore recovery derives beyond the strategy's counter. The
     * bound matches the history page: a lost order cannot be recovered from a page that does not
     * contain it, and an engine measured in trades per day never places more than this many
     * orders between a dump and a restore.
     */
    static final int ORDER_RECOVERY_RANGE = 1000;

    /**
     * The strategy's order history indexed by client order ID, with whether the page is known to be
     * complete. A complete page is non-null, shorter than the requested limit (so there are no more
     * pages) and had no entry that carries a client order ID skipped as incomplete. An entry without
     * a client order ID cannot be the row being searched, so it is skipped without affecting
     * completeness.
     */
    private record History(Map<String, OrderResponse> byClientOrderId, boolean complete) {
    }

    private final LiveStrategyService liveStrategyService;
    private final BotConfigurationService botConfigurationService;
    private final BitvavoApiClient bitvavoApiClient;
    private final LiveOrderRepository liveOrderRepository;
    private final LivePositionRepository livePositionRepository;
    private final LiveStrategyRepository liveStrategyRepository;

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
                        + "&start=" + strategy.getCreatedTime().toEpochMilli()
                        + "&limit=" + ORDER_HISTORY_LIMIT,
                OrderResponse[].class, bot.getApiKey(), bot.getApiSecret());
        History indexed = history(history, strategy.getId());

        List<LiveOrder> rows = liveOrderRepository.findByStrategyIdOrderByCreatedTimeDesc(strategy.getId());
        for (LiveOrder row : rows) {
            resolve(strategy, row, indexed);
        }
        if (recoverLostOrders(strategy, indexed, rows)) {
            rows = liveOrderRepository.findByStrategyIdOrderByCreatedTimeDesc(strategy.getId());
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
     * Index the history by client order ID. An entry that carries a client order ID but is missing
     * one of the other fields reconciliation keys on is warned and skipped, and marks the page
     * incomplete; an entry without a client order ID (the legacy or exchange-UI path) is skipped
     * without affecting completeness, since it can never be the row being searched.
     */
    private History history(OrderResponse[] history, String strategyId) {
        Map<String, OrderResponse> byClientOrderId = new LinkedHashMap<>();
        if (history == null) {
            return new History(byClientOrderId, false);
        }
        boolean complete = history.length < ORDER_HISTORY_LIMIT;
        for (OrderResponse order : history) {
            if (order.getClientOrderId() == null) {
                log.debug("Exchange order {} of strategy {} has no client order ID; skipped",
                        order.getOrderId(), strategyId);
                continue;
            }
            if (order.getOrderId() == null || order.getCreated() == null || order.getSide() == null) {
                log.warn("Exchange order entry for strategy {} is incomplete "
                        + "(orderId/created/side missing); skipped", strategyId);
                complete = false;
                continue;
            }
            byClientOrderId.put(order.getClientOrderId().toString(), order);
        }
        return new History(byClientOrderId, complete);
    }

    /**
     * Re-derive this strategy's own possible client order IDs for the sequence range starting at
     * its persisted counter. An exchange order that matches and that no local row claims can only
     * be one whose record was lost — the pre-write row of everything the engine sends survives on
     * disk, so after a restore the derivation is the only way back to those orders. Matching rows
     * are recreated from the exchange's figures and the sequence advances past every match.
     *
     * @return Whether a row was recovered (so the caller should re-read the rows)
     */
    private boolean recoverLostOrders(LiveStrategy strategy, History history, List<LiveOrder> rows) {
        Set<String> claimed = rows.stream()
                .map(LiveOrder::getClientOrderId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        long from = strategy.getNextOrderSequence();
        long highest = from - 1;
        boolean recovered = false;
        for (long sequence = from; sequence < from + ORDER_RECOVERY_RANGE; sequence++) {
            String candidate = ClientOrderIds.forSequence(strategy.getId(), sequence).toString();
            OrderResponse order = history.byClientOrderId().get(candidate);
            if (order == null || claimed.contains(candidate)) {
                continue;
            }
            highest = sequence;
            if (!"filled".equalsIgnoreCase(order.getStatus())) {
                log.warn("Recoverable exchange order {} of strategy {} is {} (not filled); "
                        + "left for a later boot", order.getOrderId(), strategy.getId(), order.getStatus());
                continue;
            }
            liveOrderRepository.save(recoveredRow(strategy, order, candidate));
            recovered = true;
            log.info("Recovered live order {} of strategy {} from the exchange's history",
                    order.getOrderId(), strategy.getId());
        }
        if (highest >= from) {
            long next = highest + 1;
            liveStrategyRepository.updateNextOrderSequence(strategy.getId(), next);
            strategy.setNextOrderSequence(next);
        }
        return recovered;
    }

    /**
     * One {@code FILLED} row rebuilt from the exchange's figures for an order whose record was
     * lost. The trigger candle is unknowable, so the row carries no signal open time and says so
     * in its reason; the position sync falls back to the exchange's creation instant.
     */
    private static LiveOrder recoveredRow(LiveStrategy strategy, OrderResponse order, String clientOrderId) {
        Double amountQuote = TradeMath.amount(order.getFilledAmountQuote(), order.getAmountQuote());
        Double quantity = TradeMath.amount(order.getFilledAmount(), order.getAmount());
        Double price = TradeMath.price(order.getFilledAmount(), order.getFilledAmountQuote());
        return LiveOrder.builder()
                .strategyId(strategy.getId())
                .market(strategy.getMarket())
                .side(order.getSide().toUpperCase(Locale.ROOT))
                .status("FILLED")
                .dryRun(false)
                .signalOpenTime(0)
                .reason("recovered after a restore: filled at the exchange but its record was lost")
                .amountQuote(amountQuote)
                .quantity(quantity)
                .price(price)
                .fee(order.getFeePaid() == null ? null : order.getFeePaid().doubleValue())
                .orderId(String.valueOf(order.getOrderId()))
                .clientOrderId(clientOrderId)
                .createdTime(Instant.ofEpochMilli(order.getCreated()))
                .build();
    }

    /**
     * Settle one of this strategy's un-settled rows against the history entry that carries its
     * client order ID.
     */
    private void resolve(LiveStrategy strategy, LiveOrder row, History history) {
        if (row.getClientOrderId() == null) {
            if (!row.isDryRun() && "FAILED".equals(row.getStatus())) {
                log.warn("Live order {} of strategy {} has no client order ID (recorded before orders "
                        + "carried one); it cannot be reconciled automatically and needs a manual check",
                        row.getId(), strategy.getId());
            }
            return;
        }
        if (!"PENDING".equals(row.getStatus()) && !"FAILED".equals(row.getStatus())) {
            return;
        }
        OrderResponse order = history.byClientOrderId().get(row.getClientOrderId());
        if (order == null) {
            if (!"PENDING".equals(row.getStatus())) {
                return;
            }
            if (history.complete()) {
                notAccepted(row);
            } else {
                log.info("Pending live order {} of strategy {} was not found in an incomplete page of "
                        + "the exchange's order history; it will be retried at the next boot",
                        row.getId(), strategy.getId());
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
