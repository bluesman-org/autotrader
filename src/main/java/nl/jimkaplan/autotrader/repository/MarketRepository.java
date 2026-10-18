package nl.jimkaplan.autotrader.repository;

import nl.jimkaplan.autotrader.model.document.Market;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Repository for the trading-pair catalogue.
 */
@Repository
public interface MarketRepository extends MongoRepository<Market, String> {

    /**
     * Find a pair by its Bitvavo market symbol.
     *
     * @param market Market in Bitvavo format, e.g. "BTC-EUR"
     * @return The stored pair, when the catalogue has it
     */
    Optional<Market> findByMarket(String market);

    /**
     * @param market Market in Bitvavo format, e.g. "BTC-EUR"
     * @return Whether the catalogue already has the pair
     */
    boolean existsByMarket(String market);
}
