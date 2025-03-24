package nl.jimkaplan.autotrader.service;

import nl.jimkaplan.autotrader.model.document.Position;
import nl.jimkaplan.autotrader.repository.PositionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PositionServiceTest {

    @Mock
    private PositionRepository positionRepository;

    @InjectMocks
    private PositionService positionService;

    private Position testPosition;
    private final String TEST_BOT_ID = "test-bot-id";
    private final String TEST_TICKER = "BTCEUR";
    private final String TEST_STATUS = "OPEN";
    private final String TEST_POSITION_ID = "test-position-id";

    @BeforeEach
    void setUp() {
        testPosition = Position.builder()
                .botId(TEST_BOT_ID)
                .ticker(TEST_TICKER)
                .status(TEST_STATUS)
                .build();
        testPosition.setId(TEST_POSITION_ID);
    }

    @Test
    void savePosition_shouldSaveAndReturnPosition() {
        // Arrange
        when(positionRepository.save(any(Position.class))).thenReturn(testPosition);

        // Act
        Position savedPosition = positionService.savePosition(testPosition);

        // Assert
        assertEquals(TEST_POSITION_ID, savedPosition.getId());
        assertEquals(TEST_BOT_ID, savedPosition.getBotId());
        assertEquals(TEST_TICKER, savedPosition.getTicker());
        assertEquals(TEST_STATUS, savedPosition.getStatus());
        verify(positionRepository).save(testPosition);
    }

    @Test
    void getPositionsByBotId_shouldReturnPositionsForBot() {
        // Arrange
        List<Position> positions = Arrays.asList(testPosition);
        when(positionRepository.findByBotId(TEST_BOT_ID)).thenReturn(positions);

        // Act
        List<Position> result = positionService.getPositionsByBotId(TEST_BOT_ID);

        // Assert
        assertEquals(1, result.size());
        assertEquals(TEST_POSITION_ID, result.get(0).getId());
        assertEquals(TEST_BOT_ID, result.get(0).getBotId());
        verify(positionRepository).findByBotId(TEST_BOT_ID);
    }

    @Test
    void getPositionsByBotIdAndTicker_shouldReturnPositionsForBotAndTicker() {
        // Arrange
        List<Position> positions = Arrays.asList(testPosition);
        when(positionRepository.findByBotIdAndTicker(TEST_BOT_ID, TEST_TICKER)).thenReturn(positions);

        // Act
        List<Position> result = positionService.getPositionsByBotIdAndTicker(TEST_BOT_ID, TEST_TICKER);

        // Assert
        assertEquals(1, result.size());
        assertEquals(TEST_POSITION_ID, result.get(0).getId());
        assertEquals(TEST_BOT_ID, result.get(0).getBotId());
        assertEquals(TEST_TICKER, result.get(0).getTicker());
        verify(positionRepository).findByBotIdAndTicker(TEST_BOT_ID, TEST_TICKER);
    }

    @Test
    void getPositionsByBotIdAndStatus_shouldReturnPositionsForBotAndStatus() {
        // Arrange
        List<Position> positions = Arrays.asList(testPosition);
        when(positionRepository.findByBotIdAndStatus(TEST_BOT_ID, TEST_STATUS)).thenReturn(positions);

        // Act
        List<Position> result = positionService.getPositionsByBotIdAndStatus(TEST_BOT_ID, TEST_STATUS);

        // Assert
        assertEquals(1, result.size());
        assertEquals(TEST_POSITION_ID, result.get(0).getId());
        assertEquals(TEST_BOT_ID, result.get(0).getBotId());
        assertEquals(TEST_STATUS, result.get(0).getStatus());
        verify(positionRepository).findByBotIdAndStatus(TEST_BOT_ID, TEST_STATUS);
    }

    @Test
    void getPositionByBotIdAndTickerAndStatus_shouldReturnPositionWhenFound() {
        // Arrange
        when(positionRepository.findByBotIdAndTickerAndStatus(TEST_BOT_ID, TEST_TICKER, TEST_STATUS))
                .thenReturn(Optional.of(testPosition));

        // Act
        Optional<Position> result = positionService.getPositionByBotIdAndTickerAndStatus(TEST_BOT_ID, TEST_TICKER, TEST_STATUS);

        // Assert
        assertTrue(result.isPresent());
        assertEquals(TEST_POSITION_ID, result.get().getId());
        assertEquals(TEST_BOT_ID, result.get().getBotId());
        assertEquals(TEST_TICKER, result.get().getTicker());
        assertEquals(TEST_STATUS, result.get().getStatus());
        verify(positionRepository).findByBotIdAndTickerAndStatus(TEST_BOT_ID, TEST_TICKER, TEST_STATUS);
    }

    @Test
    void getPositionByBotIdAndTickerAndStatus_shouldReturnEmptyOptionalWhenNotFound() {
        // Arrange
        when(positionRepository.findByBotIdAndTickerAndStatus(TEST_BOT_ID, TEST_TICKER, TEST_STATUS))
                .thenReturn(Optional.empty());

        // Act
        Optional<Position> result = positionService.getPositionByBotIdAndTickerAndStatus(TEST_BOT_ID, TEST_TICKER, TEST_STATUS);

        // Assert
        assertTrue(result.isEmpty());
        verify(positionRepository).findByBotIdAndTickerAndStatus(TEST_BOT_ID, TEST_TICKER, TEST_STATUS);
    }

    @Test
    void getPositionById_shouldReturnPositionWhenFound() {
        // Arrange
        when(positionRepository.findById(TEST_POSITION_ID)).thenReturn(Optional.of(testPosition));

        // Act
        Optional<Position> result = positionService.getPositionById(TEST_POSITION_ID);

        // Assert
        assertTrue(result.isPresent());
        assertEquals(TEST_POSITION_ID, result.get().getId());
        verify(positionRepository).findById(TEST_POSITION_ID);
    }

    @Test
    void getPositionById_shouldReturnEmptyOptionalWhenNotFound() {
        // Arrange
        when(positionRepository.findById(TEST_POSITION_ID)).thenReturn(Optional.empty());

        // Act
        Optional<Position> result = positionService.getPositionById(TEST_POSITION_ID);

        // Assert
        assertTrue(result.isEmpty());
        verify(positionRepository).findById(TEST_POSITION_ID);
    }

    @Test
    void updatePositionStatus_shouldUpdateStatusWhenPositionFound() {
        // Arrange
        String newStatus = "CLOSED";
        Position updatedPosition = Position.builder()
                .botId(TEST_BOT_ID)
                .ticker(TEST_TICKER)
                .status(newStatus)
                .build();
        updatedPosition.setId(TEST_POSITION_ID);

        when(positionRepository.findById(TEST_POSITION_ID)).thenReturn(Optional.of(testPosition));
        when(positionRepository.save(any(Position.class))).thenReturn(updatedPosition);

        // Act
        Optional<Position> result = positionService.updatePositionStatus(TEST_POSITION_ID, newStatus);

        // Assert
        assertTrue(result.isPresent());
        assertEquals(newStatus, result.get().getStatus());
        verify(positionRepository).findById(TEST_POSITION_ID);
        verify(positionRepository).save(any(Position.class));
    }

    @Test
    void updatePositionStatus_shouldReturnEmptyOptionalWhenPositionNotFound() {
        // Arrange
        String newStatus = "CLOSED";
        when(positionRepository.findById(TEST_POSITION_ID)).thenReturn(Optional.empty());

        // Act
        Optional<Position> result = positionService.updatePositionStatus(TEST_POSITION_ID, newStatus);

        // Assert
        assertTrue(result.isEmpty());
        verify(positionRepository).findById(TEST_POSITION_ID);
    }

    @Test
    void deletePosition_shouldCallRepositoryDeleteById() {
        // Act
        positionService.deletePosition(TEST_POSITION_ID);

        // Assert
        verify(positionRepository).deleteById(TEST_POSITION_ID);
    }
}
