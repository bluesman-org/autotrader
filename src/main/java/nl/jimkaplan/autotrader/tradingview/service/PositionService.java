package nl.jimkaplan.autotrader.tradingview.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.jimkaplan.autotrader.repository.PositionRepository;
import nl.jimkaplan.autotrader.tradingview.model.document.Position;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Service for managing positions.
 * Handles CRUD operations for positions.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PositionService {

    private final PositionRepository positionRepository;

    /**
     * Save a position.
     *
     * @param position The position to save
     * @return The saved position
     */
    public Position savePosition(Position position) {
        log.info("Saving position for bot: {}, ticker: {}, status: {}",
                position.getBotId(), position.getTicker(), position.getStatus());
        return positionRepository.save(position);
    }

    /**
     * Get all positions for a bot.
     *
     * @param botId The bot ID
     * @return List of positions for the specified bot
     */
    public List<Position> getPositionsByBotId(String botId) {
        return positionRepository.findByBotId(botId);
    }

    /**
     * Get positions for a bot and ticker.
     *
     * @param botId  The bot ID
     * @param ticker The ticker symbol
     * @return List of positions for the specified bot and ticker
     */
    public List<Position> getPositionsByBotIdAndTicker(String botId, String ticker) {
        return positionRepository.findByBotIdAndTicker(botId, ticker);
    }

    /**
     * Get positions for a bot and status.
     *
     * @param botId  The bot ID
     * @param status The position status
     * @return List of positions for the specified bot and status
     */
    public List<Position> getPositionsByBotIdAndStatus(String botId, String status) {
        return positionRepository.findByBotIdAndStatus(botId, status);
    }

    /**
     * Get a position by bot ID, ticker, and status.
     *
     * @param botId  The bot ID
     * @param ticker The ticker symbol
     * @param status The position status
     * @return Optional containing the position if found
     */
    public Optional<Position> getPositionByBotIdAndTickerAndStatus(String botId, String ticker, String status) {
        return positionRepository.findByBotIdAndTickerAndStatus(botId, ticker, status);
    }

    /**
     * Get a position by ID.
     *
     * @param id The position ID
     * @return Optional containing the position if found
     */
    public Optional<Position> getPositionById(String id) {
        return positionRepository.findById(id);
    }

    /**
     * Update the status of a position.
     *
     * @param id     The position ID
     * @param status The new status
     * @return Optional containing the updated position if found
     */
    public Optional<Position> updatePositionStatus(String id, String status) {
        return positionRepository.findById(id).map(position -> {
            position.setStatus(status);
            return positionRepository.save(position);
        });
    }

    /**
     * Delete a position by ID.
     *
     * @param id The position ID
     */
    public void deletePosition(String id) {
        positionRepository.deleteById(id);
    }
}