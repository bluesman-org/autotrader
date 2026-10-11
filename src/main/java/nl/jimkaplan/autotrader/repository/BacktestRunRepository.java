package nl.jimkaplan.autotrader.repository;

import nl.jimkaplan.autotrader.strategy.model.document.BacktestRun;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository for backtest runs.
 */
@Repository
public interface BacktestRunRepository extends MongoRepository<BacktestRun, String> {
}
