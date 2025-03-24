package nl.jimkaplan.autotrader.tradingview.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.jimkaplan.autotrader.repository.TradingViewOrderRepository;
import nl.jimkaplan.autotrader.tradingview.model.document.TradingViewOrder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Service for managing TradingView orders.
 * Handles CRUD operations for TradingView orders.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TradingViewOrderService {

    private final TradingViewOrderRepository tradingViewOrderRepository;

    /**
     * Save a TradingView order.
     *
     * @param order The order to save
     * @return The saved order
     */
    public TradingViewOrder saveOrder(TradingViewOrder order) {
        log.info("Saving TradingView order for bot: {}, ticker: {}, status: {}",
                order.getBotId(), order.getTicker(), order.getStatus());
        return tradingViewOrderRepository.save(order);
    }

    /**
     * Get all orders for a bot.
     *
     * @param botId The bot ID
     * @return List of orders for the specified bot
     */
    public List<TradingViewOrder> getOrdersByBotId(String botId) {
        return tradingViewOrderRepository.findByBotId(botId);
    }

    /**
     * Get orders for a bot and ticker.
     *
     * @param botId  The bot ID
     * @param ticker The ticker symbol
     * @return List of orders for the specified bot and ticker
     */
    public List<TradingViewOrder> getOrdersByBotIdAndTicker(String botId, String ticker) {
        return tradingViewOrderRepository.findByBotIdAndTicker(botId, ticker);
    }

    /**
     * Get orders for a bot and status.
     *
     * @param botId  The bot ID
     * @param status The order status
     * @return List of orders for the specified bot and status
     */
    public List<TradingViewOrder> getOrdersByBotIdAndStatus(String botId, String status) {
        return tradingViewOrderRepository.findByBotIdAndStatus(botId, status);
    }

    /**
     * Get orders for a bot within a time range.
     *
     * @param botId     The bot ID
     * @param startTime The start time
     * @param endTime   The end time
     * @return List of orders for the specified bot and time range
     */
    public List<TradingViewOrder> getOrdersByBotIdAndTimeRange(String botId, Instant startTime, Instant endTime) {
        return tradingViewOrderRepository.findByBotIdAndTimestampBetween(botId, startTime, endTime);
    }

    /**
     * Get an order by ID.
     *
     * @param id The order ID
     * @return Optional containing the order if found
     */
    public Optional<TradingViewOrder> getOrderById(String id) {
        return tradingViewOrderRepository.findById(id);
    }

    /**
     * Get an order by Bitvavo order ID.
     *
     * @param orderId The Bitvavo order ID
     * @return Optional containing the order if found
     */
    public Optional<TradingViewOrder> getOrderByOrderId(String orderId) {
        return tradingViewOrderRepository.findByOrderId(orderId);
    }

    /**
     * Update the status of an order.
     *
     * @param id           The order ID
     * @param status       The new status
     * @param errorMessage The error message (if any)
     * @return Optional containing the updated order if found
     */
    public Optional<TradingViewOrder> updateOrderStatus(String id, String status, String errorMessage) {
        return tradingViewOrderRepository.findById(id).map(order -> {
            order.setStatus(status);
            order.setErrorMessage(errorMessage);
            return tradingViewOrderRepository.save(order);
        });
    }

    /**
     * Delete an order by ID.
     *
     * @param id The order ID
     */
    public void deleteOrder(String id) {
        tradingViewOrderRepository.deleteById(id);
    }
}