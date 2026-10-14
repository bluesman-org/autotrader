package nl.jimkaplan.autotrader.repository;

import nl.jimkaplan.autotrader.strategy.model.document.LiveStrategy;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.data.mongodb.repository.Update;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repository for live strategies.
 */
@Repository
public interface LiveStrategyRepository extends MongoRepository<LiveStrategy, String> {

    /**
     * The strategies the engine should be running.
     *
     * @return Enabled strategies
     */
    List<LiveStrategy> findByEnabledTrue();

    /**
     * Set the enabled flag without touching any other field; the runner and the API can flip it
     * concurrently with the last-evaluated updates.
     *
     * @param id      The strategy ID
     * @param enabled The new value
     */
    @Query("{ '_id' : ?0 }")
    @Update("{ '$set' : { 'enabled' : ?1 } }")
    void updateEnabled(String id, boolean enabled);

    /**
     * Advance the last evaluated candle without touching any other field.
     *
     * @param id       The strategy ID
     * @param openTime The evaluated candle's open time
     */
    @Query("{ '_id' : ?0 }")
    @Update("{ '$set' : { 'last_evaluated_open_time' : ?1 } }")
    void updateLastEvaluatedOpenTime(String id, long openTime);

    /**
     * Advance the order sequence without touching any other field. The update is a {@code $max},
     * so it is monotonic: a concurrent caller whose value had lagged can never move the counter
     * backwards onto an ID the exchange might still hold open. (The two callers — the runner and
     * the boot reconciliation — are serialized by design; this makes the field safe regardless.)
     *
     * @param id       The strategy ID
     * @param sequence The desired next sequence number; kept only when it exceeds the stored one
     */
    @Query("{ '_id' : ?0 }")
    @Update("{ '$max' : { 'next_order_sequence' : ?1 } }")
    void updateNextOrderSequence(String id, long sequence);

    /**
     * Fix the runner's series anchor, written at most once: the condition matches only a document
     * whose anchor is still absent or 0, so a second writer — another process, an operator, an
     * older restored copy — cannot move the one value that must never move. The seed re-reads the
     * stored anchor when this reports no modification, so a lost race seeds from the winner's.
     *
     * @param id              The strategy ID
     * @param seriesStartTime The fixed series start, epoch millis
     * @return The number of modified documents; 0 means an anchor is already fixed
     */
    @Query("{ '_id' : ?0, 'series_start_time' : { $in : [ null, 0 ] } }")
    @Update("{ '$set' : { 'series_start_time' : ?1 } }")
    long updateSeriesStartTime(String id, long seriesStartTime);
}
