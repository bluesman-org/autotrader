package nl.jimkaplan.autotrader.repository;

import nl.jimkaplan.autotrader.strategy.model.document.LivePosition;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Repository for live positions.
 */
@Repository
public interface LivePositionRepository extends MongoRepository<LivePosition, String> {

    /**
     * A strategy's position in the given state.
     *
     * @param strategyId The strategy ID
     * @param status     "OPEN" or "CLOSED"
     * @return The position if it exists
     */
    Optional<LivePosition> findByStrategyIdAndStatus(String strategyId, String status);
}
