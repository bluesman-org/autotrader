package nl.jimkaplan.autotrader.repository;

import nl.jimkaplan.autotrader.tradingview.model.document.BotConfiguration;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository interface for BotConfiguration documents.
 * Provides methods for CRUD operations on BotConfiguration documents.
 */
@Repository
public interface BotConfigurationRepository extends MongoRepository<BotConfiguration, String> {

    /**
     * Find bot configuration by bot ID.
     *
     * @param botId The bot ID
     * @return Optional containing the bot configuration if found
     */
    Optional<BotConfiguration> findByBotId(String botId);

    /**
     * Find bot configurations by trading pair.
     *
     * @param tradingPair The trading pair
     * @return List of bot configurations for the specified trading pair
     */
    List<BotConfiguration> findByTradingPair(String tradingPair);

    /**
     * Check if a bot configuration exists with the given bot ID.
     *
     * @param botId The bot ID
     * @return true if a bot configuration exists with the given bot ID, false otherwise
     */
    boolean existsByBotId(String botId);

    /**
     * Delete bot configuration by bot ID.
     *
     * @param botId The bot ID
     */
    void deleteByBotId(String botId);

    /**
     * Find active bot configurations.
     *
     * @param active The active status
     * @return List of bot configurations with the specified active status
     */
    List<BotConfiguration> findByActive(Boolean active);

    /**
     * Find bot configuration by bot ID and active status.
     *
     * @param botId  The bot ID
     * @param active The active status
     * @return Optional containing the bot configuration if found
     */
    Optional<BotConfiguration> findByBotIdAndActive(String botId, Boolean active);
}
