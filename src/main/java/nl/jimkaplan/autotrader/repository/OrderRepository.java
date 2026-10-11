package nl.jimkaplan.autotrader.repository;

import nl.jimkaplan.autotrader.model.Order;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Repository interface for Order documents.
 * Provides methods for CRUD operations on Order documents.
 */
@Repository
public interface OrderRepository extends MongoRepository<Order, String> {

    /**
     * Find orders by bot ID.
     *
     * @param botId The bot ID
     * @return List of orders for the specified bot
     */
    List<Order> findByBotId(String botId);

    /**
     * Find orders by bot ID and ticker.
     *
     * @param botId  The bot ID
     * @param ticker The ticker symbol
     * @return List of orders for the specified bot and ticker
     */
    List<Order> findByBotIdAndTicker(String botId, String ticker);

    /**
     * Find orders by bot ID and status.
     *
     * @param botId  The bot ID
     * @param status The order status
     * @return List of orders for the specified bot and status
     */
    List<Order> findByBotIdAndStatus(String botId, String status);

    /**
     * Find orders by bot ID whose timestamp is in [startTime, endTime], both inclusive.
     *
     * <p>The predicate is explicit because a derived query cannot express it: Spring Data's
     * {@code Between} keyword is exclusive in MongoDB, and two inclusive keywords on the same
     * property fail to merge into one criteria. Keys are document field names.</p>
     *
     * @param botId     The bot ID
     * @param startTime The start time, inclusive
     * @param endTime   The end time, inclusive
     * @return List of orders for the specified bot and timestamp range
     */
    @Query(value = "{ 'botId': ?0, 'timestamp': { $gte: ?1, $lte: ?2 } }")
    List<Order> findByBotIdAndTimestampRange(String botId, Instant startTime, Instant endTime);

    /**
     * Find order by order ID.
     *
     * @param orderId The order ID
     * @return Optional containing the order if found
     */
    Optional<Order> findByOrderId(String orderId);
}