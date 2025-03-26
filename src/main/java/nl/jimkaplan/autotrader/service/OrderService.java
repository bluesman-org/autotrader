package nl.jimkaplan.autotrader.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.jimkaplan.autotrader.model.Order;
import nl.jimkaplan.autotrader.repository.OrderRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Service for managing orders.
 * Handles CRUD operations for orders.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;

    /**
     * Save an order.
     *
     * @param order The order to save
     * @return The saved order
     */
    public Order saveOrder(Order order) {
        log.info("Saving order for bot: {}, ticker: {}, status: {}",
                order.getBotId(), order.getTicker(), order.getStatus());
        return orderRepository.save(order);
    }

    /**
     * Get all orders for a bot.
     *
     * @param botId The bot ID
     * @return List of orders for the specified bot
     */
    public List<Order> getOrdersByBotId(String botId) {
        return orderRepository.findByBotId(botId);
    }

    /**
     * Get orders for a bot and ticker.
     *
     * @param botId  The bot ID
     * @param ticker The ticker symbol
     * @return List of orders for the specified bot and ticker
     */
    public List<Order> getOrdersByBotIdAndTicker(String botId, String ticker) {
        return orderRepository.findByBotIdAndTicker(botId, ticker);
    }

    /**
     * Get orders for a bot and status.
     *
     * @param botId  The bot ID
     * @param status The order status
     * @return List of orders for the specified bot and status
     */
    public List<Order> getOrdersByBotIdAndStatus(String botId, String status) {
        return orderRepository.findByBotIdAndStatus(botId, status);
    }

    /**
     * Get orders for a bot within a time range.
     *
     * @param botId     The bot ID
     * @param startTime The start time
     * @param endTime   The end time
     * @return List of orders for the specified bot and time range
     */
    public List<Order> getOrdersByBotIdAndTimeRange(String botId, Instant startTime, Instant endTime) {
        return orderRepository.findByBotIdAndTimestampBetween(botId, startTime, endTime);
    }

    /**
     * Get an order by ID.
     *
     * @param id The order ID
     * @return Optional containing the order if found
     */
    public Optional<Order> getOrderById(String id) {
        return orderRepository.findById(id);
    }

    /**
     * Get an order by Bitvavo order ID.
     *
     * @param orderId The Bitvavo order ID
     * @return Optional containing the order if found
     */
    public Optional<Order> getOrderByOrderId(String orderId) {
        return orderRepository.findByOrderId(orderId);
    }

    /**
     * Update the status of an order.
     *
     * @param id           The order ID
     * @param status       The new status
     * @param errorMessage The error message (if any)
     * @return Optional containing the updated order if found
     */
    public Optional<Order> updateOrderStatus(String id, String status, String errorMessage) {
        return orderRepository.findById(id).map(order -> {
            order.setStatus(status);
            order.setErrorMessage(errorMessage);
            return orderRepository.save(order);
        });
    }

    /**
     * Delete an order by ID.
     *
     * @param id The order ID
     */
    public void deleteOrder(String id) {
        orderRepository.deleteById(id);
    }
}