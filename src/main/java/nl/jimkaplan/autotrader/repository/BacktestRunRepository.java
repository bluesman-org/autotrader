package nl.jimkaplan.autotrader.repository;

import nl.jimkaplan.autotrader.strategy.model.document.BacktestRun;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repository for backtest runs.
 */
@Repository
public interface BacktestRunRepository extends MongoRepository<BacktestRun, String> {

    /**
     * Runs without their ranked entries, so list payloads stay small.
     *
     * @param pageable The page and sort
     * @return Runs without their entries
     */
    @Query(value = "{}", fields = "{ 'entries' : 0 }")
    List<BacktestRun> findSummaries(Pageable pageable);
}
