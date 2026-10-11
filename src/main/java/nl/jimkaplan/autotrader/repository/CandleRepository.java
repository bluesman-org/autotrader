package nl.jimkaplan.autotrader.repository;

import nl.jimkaplan.autotrader.strategy.model.document.Candle;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for historical candlesticks.
 */
@Repository
public interface CandleRepository extends MongoRepository<Candle, String> {

    /**
     * Newest candle stored for the market and interval, if any.
     *
     * @param market   Market in Bitvavo format
     * @param interval Interval code
     * @return The newest stored candle
     */
    Optional<Candle> findFirstByMarketAndIntervalOrderByOpenTimeDesc(String market, String interval);

    /**
     * Oldest candle stored for the market and interval, if any.
     *
     * @param market   Market in Bitvavo format
     * @param interval Interval code
     * @return The oldest stored candle
     */
    Optional<Candle> findFirstByMarketAndIntervalOrderByOpenTimeAsc(String market, String interval);

    /**
     * Candles for the market and interval with an open time in [fromOpenTime, toOpenTime], oldest first.
     *
     * <p>The predicate is explicit because a derived query cannot express it: Spring Data's
     * {@code Between} keyword is exclusive in MongoDB, and two inclusive keywords on the same
     * property fail to merge into one criteria. Keys are document field names.</p>
     *
     * @param market       Market in Bitvavo format
     * @param interval     Interval code
     * @param fromOpenTime Lower bound for the open time, inclusive
     * @param toOpenTime   Upper bound for the open time, inclusive
     * @return Stored candles within the range
     */
    @Query(value = "{ 'market': ?0, 'interval': ?1, 'open_time': { $gte: ?2, $lte: ?3 } }",
            sort = "{ 'open_time': 1 }")
    List<Candle> findCandlesInRange(String market, String interval, long fromOpenTime, long toOpenTime);
}
