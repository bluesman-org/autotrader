package nl.jimkaplan.autotrader.repository;

import nl.jimkaplan.autotrader.strategy.model.document.LivePosition;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
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

    /**
     * A strategy's positions in the given state, for aggregating its round trips.
     *
     * @param strategyId The strategy ID
     * @param status     "OPEN" or "CLOSED"
     * @return The positions, in no particular order
     */
    List<LivePosition> findAllByStrategyIdAndStatus(String strategyId, String status);

    /**
     * Closed round trips that opened within the given range, for the history page. The range bounds
     * the read; the page filters, sorts and pages the result in memory because its totals and its
     * daily breakdown cover the whole selected period, not one page.
     *
     * @param status The position status ("CLOSED")
     * @param from   The start of the range, inclusive, epoch millis
     * @param to     The end of the range, inclusive, epoch millis
     * @return The positions
     */
    List<LivePosition> findByStatusAndEntryTimeGreaterThanEqualAndEntryTimeLessThanEqual(
            String status, long from, long to);
}
