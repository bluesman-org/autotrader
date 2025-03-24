package nl.jimkaplan.autotrader.repository;

import nl.jimkaplan.autotrader.tradingview.model.document.Position;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository interface for Position documents.
 * Provides methods for CRUD operations on Position documents.
 */
@Repository
public interface PositionRepository extends MongoRepository<Position, String> {

    /**
     * Find positions by bot ID.
     *
     * @param botId The bot ID
     * @return List of positions for the specified bot
     */
    List<Position> findByBotId(String botId);

    /**
     * Find positions by bot ID and ticker.
     *
     * @param botId  The bot ID
     * @param ticker The ticker symbol
     * @return List of positions for the specified bot and ticker
     */
    List<Position> findByBotIdAndTicker(String botId, String ticker);

    /**
     * Find positions by bot ID and status.
     *
     * @param botId  The bot ID
     * @param status The position status
     * @return List of positions for the specified bot and status
     */
    List<Position> findByBotIdAndStatus(String botId, String status);

    /**
     * Find position by bot ID, ticker, and status.
     *
     * @param botId  The bot ID
     * @param ticker The ticker symbol
     * @param status The position status
     * @return Optional containing the position if found
     */
    Optional<Position> findByBotIdAndTickerAndStatus(String botId, String ticker, String status);
}