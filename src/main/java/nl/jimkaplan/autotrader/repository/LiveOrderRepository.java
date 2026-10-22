package nl.jimkaplan.autotrader.repository;

import nl.jimkaplan.autotrader.strategy.model.document.LiveOrder;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repository for live orders.
 */
@Repository
public interface LiveOrderRepository extends MongoRepository<LiveOrder, String> {

    /**
     * A strategy's orders, newest first.
     *
     * @param strategyId The strategy ID
     * @return The orders
     */
    List<LiveOrder> findByStrategyIdOrderByCreatedTimeDesc(String strategyId);

    /**
     * A strategy's newest orders, for the live page: bounded in the query, so a card never loads
     * the strategy's whole order history on each refresh. The engine writes a row per evaluated
     * signal and nothing prunes them, so the unbounded read would grow with the strategy's age.
     *
     * @param strategyId The strategy ID
     * @return The newest orders, at most the eight the page shows
     */
    List<LiveOrder> findTop8ByStrategyIdOrderByCreatedTimeDesc(String strategyId);

    /**
     * Whether the strategy has a real order the engine must stand down for: a pre-write that has
     * not been settled, including an ambiguous submission recorded as {@code FAILED}. The marker
     * is set when the row is written and cleared by every reconciliation outcome, so a timed-out
     * request that Bitvavo accepted cannot trigger a second order.
     *
     * @param strategyId The strategy ID
     * @return Whether such an order exists
     */
    boolean existsByStrategyIdAndAwaitingReconciliationTrue(String strategyId);

    /**
     * Whether an exchange order is already attached to any live order row. This is global on
     * purpose: the reconcile page's candidate set is scoped to one strategy for rendering, but an
     * exchange sell must never be attached to two positions, including two strategies on the same
     * market.
     *
     * @param orderId The exchange order id
     * @return Whether a row carries it
     */
    boolean existsByOrderId(String orderId);

    /**
     * Whether a real sell filled after the position's entry, which means the exchange has already
     * sold the open position but the position record has not caught up. A derived exists query keeps
     * this constant-size and indexed instead of loading the strategy's whole order history.
     *
     * @param strategyId     The strategy ID
     * @param side           "SELL"
     * @param status         "FILLED"
     * @param signalOpenTime The position's entry signal time, inclusive
     * @return Whether such an order exists
     */
    boolean existsByStrategyIdAndDryRunFalseAndSideAndStatusAndSignalOpenTimeGreaterThanEqual(
            String strategyId, String side, String status, long signalOpenTime);

    /**
     * Orders whose decision candle falls in the given range, for the history page's order
     * references: a round trip's buy carries the position's entry signal time, its sell the exit
     * signal time. Explicit {@code @Query} for the same reason as
     * {@code LivePositionRepository.findByStatusAndEntryTimeRange}.
     *
     * @param from The start of the range, inclusive, epoch millis
     * @param to   The end of the range, inclusive, epoch millis
     * @return The orders
     */
    @Query("{ 'signal_open_time' : { $gte : ?0, $lte : ?1 } }")
    List<LiveOrder> findBySignalOpenTimeRange(long from, long to);
}
