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
     * Advance the order sequence without touching any other field; the runner and the boot
     * reconciliation can update it concurrently with the last-evaluated writes.
     *
     * @param id       The strategy ID
     * @param sequence The next sequence number to use
     */
    @Query("{ '_id' : ?0 }")
    @Update("{ '$set' : { 'next_order_sequence' : ?1 } }")
    void updateNextOrderSequence(String id, long sequence);
}
