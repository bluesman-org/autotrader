package nl.jimkaplan.autotrader.repository;

import nl.jimkaplan.autotrader.strategy.model.document.LiveStrategy;
import org.springframework.data.mongodb.repository.MongoRepository;
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
}
