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
