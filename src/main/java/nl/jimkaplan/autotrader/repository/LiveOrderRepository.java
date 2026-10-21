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
     * Whether the strategy has an order in the given status. The engine uses this to stand down
     * while a real order is still `PENDING`: a slow or partial fill must not trigger a second real
     * order before the boot reconciliation settles it.
     *
     * @param strategyId The strategy ID
     * @param status     The order status, e.g. {@code PENDING}
     * @return Whether such an order exists
     */
    boolean existsByStrategyIdAndStatus(String strategyId, String status);

    /**
     * Whether the strategy has an order in the given status that never received an exchange order
     * ID. A {@code FAILED} row like this is an ambiguous submission: the request may have reached
     * the exchange before the timeout. The engine must stand down until reconciliation settles it,
     * just as it does for a {@code PENDING} row.
     *
     * @param strategyId The strategy ID
     * @param status     The order status, e.g. {@code FAILED}
     * @return Whether such an order exists
     */
    boolean existsByStrategyIdAndStatusAndOrderIdIsNull(String strategyId, String status);

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
