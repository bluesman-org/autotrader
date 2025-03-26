package nl.jimkaplan.autotrader.tradingview.service;

import nl.jimkaplan.autotrader.model.Order;
import nl.jimkaplan.autotrader.repository.OrderRepository;
import nl.jimkaplan.autotrader.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@ActiveProfiles("test")
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @InjectMocks
    private OrderService orderService;

    private Order testOrder;
    private final String TEST_BOT_ID = "test-bot-id";
    private final String TEST_TICKER = "BTCEUR";
    private final String TEST_STATUS = "COMPLETED";
    private final String TEST_ORDER_ID = "test-order-id";
    private final String TEST_BITVAVO_ORDER_ID = "bitvavo-order-123";
    private final Instant TEST_TIMESTAMP = Instant.parse("2023-01-01T12:00:00Z");

    @BeforeEach
    void setUp() {
        testOrder = Order.builder()
                .botId(TEST_BOT_ID)
                .ticker(TEST_TICKER)
                .status(TEST_STATUS)
                .orderId(TEST_BITVAVO_ORDER_ID)
                .timestamp(TEST_TIMESTAMP)
                .build();
        testOrder.setId(TEST_ORDER_ID);
    }

    @Test
    void saveOrder_shouldSaveAndReturnOrder() {
        // Arrange
        when(orderRepository.save(any(Order.class))).thenReturn(testOrder);

        // Act
        Order savedOrder = orderService.saveOrder(testOrder);

        // Assert
        assertEquals(TEST_ORDER_ID, savedOrder.getId());
        assertEquals(TEST_BOT_ID, savedOrder.getBotId());
        assertEquals(TEST_TICKER, savedOrder.getTicker());
        assertEquals(TEST_STATUS, savedOrder.getStatus());
        assertEquals(TEST_BITVAVO_ORDER_ID, savedOrder.getOrderId());
        assertEquals(TEST_TIMESTAMP, savedOrder.getTimestamp());
        verify(orderRepository).save(testOrder);
    }

    @Test
    void getOrdersByBotId_shouldReturnOrdersForBot() {
        // Arrange
        List<Order> orders = Collections.singletonList(testOrder);
        when(orderRepository.findByBotId(TEST_BOT_ID)).thenReturn(orders);

        // Act
        List<Order> result = orderService.getOrdersByBotId(TEST_BOT_ID);

        // Assert
        assertEquals(1, result.size());
        assertEquals(TEST_ORDER_ID, result.getFirst().getId());
        assertEquals(TEST_BOT_ID, result.getFirst().getBotId());
        verify(orderRepository).findByBotId(TEST_BOT_ID);
    }

    @Test
    void getOrdersByBotIdAndTicker_shouldReturnOrdersForBotAndTicker() {
        // Arrange
        List<Order> orders = Collections.singletonList(testOrder);
        when(orderRepository.findByBotIdAndTicker(TEST_BOT_ID, TEST_TICKER)).thenReturn(orders);

        // Act
        List<Order> result = orderService.getOrdersByBotIdAndTicker(TEST_BOT_ID, TEST_TICKER);

        // Assert
        assertEquals(1, result.size());
        assertEquals(TEST_ORDER_ID, result.getFirst().getId());
        assertEquals(TEST_BOT_ID, result.getFirst().getBotId());
        assertEquals(TEST_TICKER, result.getFirst().getTicker());
        verify(orderRepository).findByBotIdAndTicker(TEST_BOT_ID, TEST_TICKER);
    }

    @Test
    void getOrdersByBotIdAndStatus_shouldReturnOrdersForBotAndStatus() {
        // Arrange
        List<Order> orders = Collections.singletonList(testOrder);
        when(orderRepository.findByBotIdAndStatus(TEST_BOT_ID, TEST_STATUS)).thenReturn(orders);

        // Act
        List<Order> result = orderService.getOrdersByBotIdAndStatus(TEST_BOT_ID, TEST_STATUS);

        // Assert
        assertEquals(1, result.size());
        assertEquals(TEST_ORDER_ID, result.getFirst().getId());
        assertEquals(TEST_BOT_ID, result.getFirst().getBotId());
        assertEquals(TEST_STATUS, result.getFirst().getStatus());
        verify(orderRepository).findByBotIdAndStatus(TEST_BOT_ID, TEST_STATUS);
    }

    @Test
    void getOrdersByBotIdAndTimeRange_shouldReturnOrdersForBotAndTimeRange() {
        // Arrange
        List<Order> orders = Collections.singletonList(testOrder);
        Instant startTime = Instant.parse("2023-01-01T00:00:00Z");
        Instant endTime = Instant.parse("2023-01-02T00:00:00Z");
        when(orderRepository.findByBotIdAndTimestampBetween(TEST_BOT_ID, startTime, endTime)).thenReturn(orders);

        // Act
        List<Order> result = orderService.getOrdersByBotIdAndTimeRange(TEST_BOT_ID, startTime, endTime);

        // Assert
        assertEquals(1, result.size());
        assertEquals(TEST_ORDER_ID, result.getFirst().getId());
        assertEquals(TEST_BOT_ID, result.getFirst().getBotId());
        assertEquals(TEST_TIMESTAMP, result.getFirst().getTimestamp());
        verify(orderRepository).findByBotIdAndTimestampBetween(TEST_BOT_ID, startTime, endTime);
    }

    @Test
    void getOrderById_shouldReturnOrderWhenFound() {
        // Arrange
        when(orderRepository.findById(TEST_ORDER_ID)).thenReturn(Optional.of(testOrder));

        // Act
        Optional<Order> result = orderService.getOrderById(TEST_ORDER_ID);

        // Assert
        assertTrue(result.isPresent());
        assertEquals(TEST_ORDER_ID, result.get().getId());
        verify(orderRepository).findById(TEST_ORDER_ID);
    }

    @Test
    void getOrderById_shouldReturnEmptyOptionalWhenNotFound() {
        // Arrange
        when(orderRepository.findById(TEST_ORDER_ID)).thenReturn(Optional.empty());

        // Act
        Optional<Order> result = orderService.getOrderById(TEST_ORDER_ID);

        // Assert
        assertTrue(result.isEmpty());
        verify(orderRepository).findById(TEST_ORDER_ID);
    }

    @Test
    void getOrderByOrderId_shouldReturnOrderWhenFound() {
        // Arrange
        when(orderRepository.findByOrderId(TEST_BITVAVO_ORDER_ID)).thenReturn(Optional.of(testOrder));

        // Act
        Optional<Order> result = orderService.getOrderByOrderId(TEST_BITVAVO_ORDER_ID);

        // Assert
        assertTrue(result.isPresent());
        assertEquals(TEST_BITVAVO_ORDER_ID, result.get().getOrderId());
        verify(orderRepository).findByOrderId(TEST_BITVAVO_ORDER_ID);
    }

    @Test
    void getOrderByOrderId_shouldReturnEmptyOptionalWhenNotFound() {
        // Arrange
        when(orderRepository.findByOrderId(TEST_BITVAVO_ORDER_ID)).thenReturn(Optional.empty());

        // Act
        Optional<Order> result = orderService.getOrderByOrderId(TEST_BITVAVO_ORDER_ID);

        // Assert
        assertTrue(result.isEmpty());
        verify(orderRepository).findByOrderId(TEST_BITVAVO_ORDER_ID);
    }

    @Test
    void updateOrderStatus_shouldUpdateStatusWhenOrderFound() {
        // Arrange
        String newStatus = "FAILED";
        String errorMessage = "Test error message";
        Order updatedOrder = Order.builder()
                .botId(TEST_BOT_ID)
                .ticker(TEST_TICKER)
                .status(newStatus)
                .orderId(TEST_BITVAVO_ORDER_ID)
                .timestamp(TEST_TIMESTAMP)
                .errorMessage(errorMessage)
                .build();
        updatedOrder.setId(TEST_ORDER_ID);

        when(orderRepository.findById(TEST_ORDER_ID)).thenReturn(Optional.of(testOrder));
        when(orderRepository.save(any(Order.class))).thenReturn(updatedOrder);

        // Act
        Optional<Order> result = orderService.updateOrderStatus(TEST_ORDER_ID, newStatus, errorMessage);

        // Assert
        assertTrue(result.isPresent());
        assertEquals(newStatus, result.get().getStatus());
        assertEquals(errorMessage, result.get().getErrorMessage());
        verify(orderRepository).findById(TEST_ORDER_ID);
        verify(orderRepository).save(any(Order.class));
    }

    @Test
    void updateOrderStatus_shouldReturnEmptyOptionalWhenOrderNotFound() {
        // Arrange
        String newStatus = "FAILED";
        String errorMessage = "Test error message";
        when(orderRepository.findById(TEST_ORDER_ID)).thenReturn(Optional.empty());

        // Act
        Optional<Order> result = orderService.updateOrderStatus(TEST_ORDER_ID, newStatus, errorMessage);

        // Assert
        assertTrue(result.isEmpty());
        verify(orderRepository).findById(TEST_ORDER_ID);
    }

    @Test
    void deleteOrder_shouldCallRepositoryDeleteById() {
        // Act
        orderService.deleteOrder(TEST_ORDER_ID);

        // Assert
        verify(orderRepository).deleteById(TEST_ORDER_ID);
    }
}