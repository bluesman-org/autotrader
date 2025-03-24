package nl.jimkaplan.autotrader.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.jimkaplan.autotrader.model.document.TradingViewAlert;
import nl.jimkaplan.autotrader.repository.TradingViewAlertRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Service for managing TradingView alerts.
 * Handles CRUD operations for TradingView alerts.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TradingViewAlertService {

    private final TradingViewAlertRepository tradingViewAlertRepository;

    /**
     * Save a TradingView alert.
     *
     * @param alert The alert to save
     * @return The saved alert
     */
    public TradingViewAlert saveAlert(TradingViewAlert alert) {
        log.info("Saving TradingView alert for bot: {}, ticker: {}, action: {}",
                alert.getBotId(), alert.getTicker(), alert.getAction());
        return tradingViewAlertRepository.save(alert);
    }

    /**
     * Get all alerts for a bot.
     *
     * @param botId The bot ID
     * @return List of alerts for the specified bot
     */
    public List<TradingViewAlert> getAlertsByBotId(String botId) {
        return tradingViewAlertRepository.findByBotId(botId);
    }

    /**
     * Get alerts for a bot and ticker.
     *
     * @param botId  The bot ID
     * @param ticker The ticker symbol
     * @return List of alerts for the specified bot and ticker
     */
    public List<TradingViewAlert> getAlertsByBotIdAndTicker(String botId, String ticker) {
        return tradingViewAlertRepository.findByBotIdAndTicker(botId, ticker);
    }

    /**
     * Get alerts for a bot and action.
     *
     * @param botId  The bot ID
     * @param action The action (buy or sell)
     * @return List of alerts for the specified bot and action
     */
    public List<TradingViewAlert> getAlertsByBotIdAndAction(String botId, String action) {
        return tradingViewAlertRepository.findByBotIdAndAction(botId, action);
    }

    /**
     * Get alerts for a bot within a time range.
     *
     * @param botId     The bot ID
     * @param startTime The start time
     * @param endTime   The end time
     * @return List of alerts for the specified bot and time range
     */
    public List<TradingViewAlert> getAlertsByBotIdAndTimeRange(String botId, Instant startTime, Instant endTime) {
        return tradingViewAlertRepository.findByBotIdAndTimestampBetween(botId, startTime, endTime);
    }

    /**
     * Get an alert by ID.
     *
     * @param id The alert ID
     * @return Optional containing the alert if found
     */
    public Optional<TradingViewAlert> getAlertById(String id) {
        return tradingViewAlertRepository.findById(id);
    }

    /**
     * Delete an alert by ID.
     *
     * @param id The alert ID
     */
    public void deleteAlert(String id) {
        tradingViewAlertRepository.deleteById(id);
    }
}