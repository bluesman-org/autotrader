package nl.jimkaplan.autotrader.repository;

import nl.jimkaplan.autotrader.strategy.model.document.LivePosition;
import org.springframework.data.mongodb.repository.Aggregation;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Collection;
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
     * The realised totals of a strategy's closed round trips, computed in the database: the live
     * page refreshes every ten seconds and used to load and walk the whole history for this line.
     * Zero counts as a win, matching the page's wording.
     *
     * @param strategyId The strategy ID
     * @return One row with count, total profit, win count and the wins' profit sum; empty when the
     *         strategy has no closed positions
     */
    @Aggregation(pipeline = {
            "{ $match: { 'strategy_id': ?0, 'status': 'CLOSED' } }",
            "{ $group: {"
                    + " _id: null,"
                    + " count: { $sum: 1 },"
                    + " total: { $sum: { $ifNull: ['$profit', 0] } },"
                    + " wins: { $sum: { $cond: [ { $gte: [ { $ifNull: ['$profit', 0] }, 0 ] }, 1, 0 ] } },"
                    + " winTotal: { $sum: { $cond: [ { $gte: [ { $ifNull: ['$profit', 0] }, 0 ] },"
                    + " { $ifNull: ['$profit', 0] }, 0 ] } }"
                    + " } }"
    })
    List<RoundTripStats> aggregateRoundTrips(String strategyId);

    /**
     * A strategy's closed positions whose exit signal is one of the given candle open times, for
     * the live page's order rows: only the newest eight orders are shown, so only their results
     * are needed rather than the whole history.
     *
     * @param strategyId The strategy ID
     * @param status     "CLOSED"
     * @param exitTimes  The exit signal open times to look up
     * @return The matching positions, in no particular order
     */
    List<LivePosition> findAllByStrategyIdAndStatusAndExitTimeIn(String strategyId, String status,
                                                                 Collection<Long> exitTimes);

    /**
     * Whether the strategy has any recorded positions, so a strategy with a history is archived
     * even when it predates the start markers.
     *
     * @param strategyId The strategy ID
     * @return Whether at least one position exists
     */
    boolean existsByStrategyId(String strategyId);

    /**
     * Closed round trips that opened within the given range, for the history page. The range bounds
     * the read; the page filters, sorts and pages the result in memory because its totals and its
     * daily breakdown cover the whole selected period, not one page.
     *
     * <p>Explicit {@code @Query}: two conditions on the same field cannot be written as a derived
     * method name — Spring Data builds one criteria document per property and Mongo rejects the
     * second bound at run time, which the mocked tests cannot see. This is the same shape as
     * {@code CandleRepository.findCandlesInRange}.</p>
     *
     * @param status The position status ("CLOSED")
     * @param from   The start of the range, inclusive, epoch millis
     * @param to     The end of the range, inclusive, epoch millis
     * @return The positions
     */
    @Query("{ 'status' : ?0, 'entry_time' : { $gte : ?1, $lte : ?2 } }")
    List<LivePosition> findByStatusAndEntryTimeRange(String status, long from, long to);
}
