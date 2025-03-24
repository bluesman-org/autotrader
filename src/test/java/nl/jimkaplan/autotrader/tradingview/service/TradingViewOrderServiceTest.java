package nl.jimkaplan.autotrader.tradingview.service;

import nl.jimkaplan.autotrader.repository.TradingViewOrderRepository;
import nl.jimkaplan.autotrader.tradingview.model.document.TradingViewOrder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
class TradingViewOrderServiceTest {

    @Mock
    private TradingViewOrderRepository tradingViewOrderRepository;

    @InjectMocks
    private TradingViewOrderService tradingViewOrderService;

    private TradingViewOrder testOrder;
    private final String TEST_BOT_ID = "test-bot-id";
    private final String TEST_TICKER = "BTCEUR";
    private final String TEST_STATUS = "COMPLETED";
    private final String TEST_ORDER_ID = "test-order-id";
    private final String TEST_BITVAVO_ORDER_ID = "bitvavo-order-123";
    private final Instant TEST_TIMESTAMP = Instant.parse("2023-01-01T12:00:00Z");

    @BeforeEach
    void setUp() {
        testOrder = TradingViewOrder.builder()
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
        when(tradingViewOrderRepository.save(any(TradingViewOrder.class))).thenReturn(testOrder);

        // Act
        TradingViewOrder savedOrder = tradingViewOrderService.saveOrder(testOrder);

        // Assert
        assertEquals(TEST_ORDER_ID, savedOrder.getId());
        assertEquals(TEST_BOT_ID, savedOrder.getBotId());
        assertEquals(TEST_TICKER, savedOrder.getTicker());
        assertEquals(TEST_STATUS, savedOrder.getStatus());
        assertEquals(TEST_BITVAVO_ORDER_ID, savedOrder.getOrderId());
        assertEquals(TEST_TIMESTAMP, savedOrder.getTimestamp());
        verify(tradingViewOrderRepository).save(testOrder);
    }

    @Test
    void getOrdersByBotId_shouldReturnOrdersForBot() {
        // Arrange
        List<TradingViewOrder> orders = Collections.singletonList(testOrder);
        when(tradingViewOrderRepository.findByBotId(TEST_BOT_ID)).thenReturn(orders);

        // Act
        List<TradingViewOrder> result = tradingViewOrderService.getOrdersByBotId(TEST_BOT_ID);

        // Assert
        assertEquals(1, result.size());
        assertEquals(TEST_ORDER_ID, result.getFirst().getId());
        assertEquals(TEST_BOT_ID, result.getFirst().getBotId());
        verify(tradingViewOrderRepository).findByBotId(TEST_BOT_ID);
    }

    @Test
    void getOrdersByBotIdAndTicker_shouldReturnOrdersForBotAndTicker() {
        // Arrange
        List<TradingViewOrder> orders = Collections.singletonList(testOrder);
        when(tradingViewOrderRepository.findByBotIdAndTicker(TEST_BOT_ID, TEST_TICKER)).thenReturn(orders);

        // Act
        List<TradingViewOrder> result = tradingViewOrderService.getOrdersByBotIdAndTicker(TEST_BOT_ID, TEST_TICKER);

        // Assert
        assertEquals(1, result.size());
        assertEquals(TEST_ORDER_ID, result.getFirst().getId());
        assertEquals(TEST_BOT_ID, result.getFirst().getBotId());
        assertEquals(TEST_TICKER, result.getFirst().getTicker());
        verify(tradingViewOrderRepository).findByBotIdAndTicker(TEST_BOT_ID, TEST_TICKER);
    }

    @Test
    void getOrdersByBotIdAndStatus_shouldReturnOrdersForBotAndStatus() {
        // Arrange
        List<TradingViewOrder> orders = Collections.singletonList(testOrder);
        when(tradingViewOrderRepository.findByBotIdAndStatus(TEST_BOT_ID, TEST_STATUS)).thenReturn(orders);

        // Act
        List<TradingViewOrder> result = tradingViewOrderService.getOrdersByBotIdAndStatus(TEST_BOT_ID, TEST_STATUS);

        // Assert
        assertEquals(1, result.size());
        assertEquals(TEST_ORDER_ID, result.getFirst().getId());
        assertEquals(TEST_BOT_ID, result.getFirst().getBotId());
        assertEquals(TEST_STATUS, result.getFirst().getStatus());
        verify(tradingViewOrderRepository).findByBotIdAndStatus(TEST_BOT_ID, TEST_STATUS);
    }

    @Test
    void getOrdersByBotIdAndTimeRange_shouldReturnOrdersForBotAndTimeRange() {
        // Arrange
        List<TradingViewOrder> orders = Collections.singletonList(testOrder);
        Instant startTime = Instant.parse("2023-01-01T00:00:00Z");
        Instant endTime = Instant.parse("2023-01-02T00:00:00Z");
        when(tradingViewOrderRepository.findByBotIdAndTimestampBetween(TEST_BOT_ID, startTime, endTime)).thenReturn(orders);

        // Act
        List<TradingViewOrder> result = tradingViewOrderService.getOrdersByBotIdAndTimeRange(TEST_BOT_ID, startTime, endTime);

        // Assert
        assertEquals(1, result.size());
        assertEquals(TEST_ORDER_ID, result.getFirst().getId());
        assertEquals(TEST_BOT_ID, result.getFirst().getBotId());
        assertEquals(TEST_TIMESTAMP, result.getFirst().getTimestamp());
        verify(tradingViewOrderRepository).findByBotIdAndTimestampBetween(TEST_BOT_ID, startTime, endTime);
    }

    @Test
    void getOrderById_shouldReturnOrderWhenFound() {
        // Arrange
        when(tradingViewOrderRepository.findById(TEST_ORDER_ID)).thenReturn(Optional.of(testOrder));

        // Act
        Optional<TradingViewOrder> result = tradingViewOrderService.getOrderById(TEST_ORDER_ID);

        // Assert
        assertTrue(result.isPresent());
        assertEquals(TEST_ORDER_ID, result.get().getId());
        verify(tradingViewOrderRepository).findById(TEST_ORDER_ID);
    }

    @Test
    void getOrderById_shouldReturnEmptyOptionalWhenNotFound() {
        // Arrange
        when(tradingViewOrderRepository.findById(TEST_ORDER_ID)).thenReturn(Optional.empty());

        // Act
        Optional<TradingViewOrder> result = tradingViewOrderService.getOrderById(TEST_ORDER_ID);

        // Assert
        assertTrue(result.isEmpty());
        verify(tradingViewOrderRepository).findById(TEST_ORDER_ID);
    }

    @Test
    void getOrderByOrderId_shouldReturnOrderWhenFound() {
        // Arrange
        when(tradingViewOrderRepository.findByOrderId(TEST_BITVAVO_ORDER_ID)).thenReturn(Optional.of(testOrder));

        // Act
        Optional<TradingViewOrder> result = tradingViewOrderService.getOrderByOrderId(TEST_BITVAVO_ORDER_ID);

        // Assert
        assertTrue(result.isPresent());
        assertEquals(TEST_BITVAVO_ORDER_ID, result.get().getOrderId());
        verify(tradingViewOrderRepository).findByOrderId(TEST_BITVAVO_ORDER_ID);
    }

    @Test
    void getOrderByOrderId_shouldReturnEmptyOptionalWhenNotFound() {
        // Arrange
        when(tradingViewOrderRepository.findByOrderId(TEST_BITVAVO_ORDER_ID)).thenReturn(Optional.empty());

        // Act
        Optional<TradingViewOrder> result = tradingViewOrderService.getOrderByOrderId(TEST_BITVAVO_ORDER_ID);

        // Assert
        assertTrue(result.isEmpty());
        verify(tradingViewOrderRepository).findByOrderId(TEST_BITVAVO_ORDER_ID);
    }

    @Test
    void updateOrderStatus_shouldUpdateStatusWhenOrderFound() {
        // Arrange
        String newStatus = "FAILED";
        String errorMessage = "Test error message";
        TradingViewOrder updatedOrder = TradingViewOrder.builder()
                .botId(TEST_BOT_ID)
                .ticker(TEST_TICKER)
                .status(newStatus)
                .orderId(TEST_BITVAVO_ORDER_ID)
                .timestamp(TEST_TIMESTAMP)
                .errorMessage(errorMessage)
                .build();
        updatedOrder.setId(TEST_ORDER_ID);
        
        when(tradingViewOrderRepository.findById(TEST_ORDER_ID)).thenReturn(Optional.of(testOrder));
        when(tradingViewOrderRepository.save(any(TradingViewOrder.class))).thenReturn(updatedOrder);

        // Act
        Optional<TradingViewOrder> result = tradingViewOrderService.updateOrderStatus(TEST_ORDER_ID, newStatus, errorMessage);

        // Assert
        assertTrue(result.isPresent());
        assertEquals(newStatus, result.get().getStatus());
        assertEquals(errorMessage, result.get().getErrorMessage());
        verify(tradingViewOrderRepository).findById(TEST_ORDER_ID);
        verify(tradingViewOrderRepository).save(any(TradingViewOrder.class));
    }

    @Test
    void updateOrderStatus_shouldReturnEmptyOptionalWhenOrderNotFound() {
        // Arrange
        String newStatus = "FAILED";
        String errorMessage = "Test error message";
        when(tradingViewOrderRepository.findById(TEST_ORDER_ID)).thenReturn(Optional.empty());

        // Act
        Optional<TradingViewOrder> result = tradingViewOrderService.updateOrderStatus(TEST_ORDER_ID, newStatus, errorMessage);

        // Assert
        assertTrue(result.isEmpty());
        verify(tradingViewOrderRepository).findById(TEST_ORDER_ID);
    }

    @Test
    void deleteOrder_shouldCallRepositoryDeleteById() {
        // Act
        tradingViewOrderService.deleteOrder(TEST_ORDER_ID);

        // Assert
        verify(tradingViewOrderRepository).deleteById(TEST_ORDER_ID);
    }
}