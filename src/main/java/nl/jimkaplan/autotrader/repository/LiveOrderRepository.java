package nl.jimkaplan.autotrader.repository;

import nl.jimkaplan.autotrader.strategy.model.document.LiveOrder;
import org.springframework.data.mongodb.repository.MongoRepository;
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
}
