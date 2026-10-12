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
}
