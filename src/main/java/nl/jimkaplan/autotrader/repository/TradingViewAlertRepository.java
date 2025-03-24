package nl.jimkaplan.autotrader.repository;

import nl.jimkaplan.autotrader.model.document.TradingViewAlert;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * Repository interface for TradingViewAlert documents.
 * Provides methods for CRUD operations on TradingViewAlert documents.
 */
@Repository
public interface TradingViewAlertRepository extends MongoRepository<TradingViewAlert, String> {

    /**
     * Find alerts by bot ID.
     *
     * @param botId The bot ID
     * @return List of alerts for the specified bot
     */
    List<TradingViewAlert> findByBotId(String botId);

    /**
     * Find alerts by bot ID and ticker.
     *
     * @param botId  The bot ID
     * @param ticker The ticker symbol
     * @return List of alerts for the specified bot and ticker
     */
    List<TradingViewAlert> findByBotIdAndTicker(String botId, String ticker);

    /**
     * Find alerts by bot ID and action.
     *
     * @param botId  The bot ID
     * @param action The action (buy or sell)
     * @return List of alerts for the specified bot and action
     */
    List<TradingViewAlert> findByBotIdAndAction(String botId, String action);

    /**
     * Find alerts by bot ID and timestamp range.
     *
     * @param botId     The bot ID
     * @param startTime The start time
     * @param endTime   The end time
     * @return List of alerts for the specified bot and timestamp range
     */
    List<TradingViewAlert> findByBotIdAndTimestampBetween(String botId, Instant startTime, Instant endTime);
}