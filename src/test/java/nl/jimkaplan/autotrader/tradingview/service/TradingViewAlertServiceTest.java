package nl.jimkaplan.autotrader.tradingview.service;

import nl.jimkaplan.autotrader.repository.TradingViewAlertRepository;
import nl.jimkaplan.autotrader.tradingview.model.document.TradingViewAlert;
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
class TradingViewAlertServiceTest {

    @Mock
    private TradingViewAlertRepository tradingViewAlertRepository;

    @InjectMocks
    private TradingViewAlertService tradingViewAlertService;

    private TradingViewAlert testAlert;
    private final String TEST_BOT_ID = "test-bot-id";
    private final String TEST_TICKER = "BTCEUR";
    private final String TEST_ACTION = "buy";
    private final String TEST_ALERT_ID = "test-alert-id";
    private final Instant TEST_TIMESTAMP = Instant.parse("2023-01-01T12:00:00Z");

    @BeforeEach
    void setUp() {
        testAlert = TradingViewAlert.builder()
                .botId(TEST_BOT_ID)
                .ticker(TEST_TICKER)
                .action(TEST_ACTION)
                .timestamp(TEST_TIMESTAMP)
                .build();
        testAlert.setId(TEST_ALERT_ID);
    }

    @Test
    void saveAlert_shouldSaveAndReturnAlert() {
        // Arrange
        when(tradingViewAlertRepository.save(any(TradingViewAlert.class))).thenReturn(testAlert);

        // Act
        TradingViewAlert savedAlert = tradingViewAlertService.saveAlert(testAlert);

        // Assert
        assertEquals(TEST_ALERT_ID, savedAlert.getId());
        assertEquals(TEST_BOT_ID, savedAlert.getBotId());
        assertEquals(TEST_TICKER, savedAlert.getTicker());
        assertEquals(TEST_ACTION, savedAlert.getAction());
        assertEquals(TEST_TIMESTAMP, savedAlert.getTimestamp());
        verify(tradingViewAlertRepository).save(testAlert);
    }

    @Test
    void getAlertsByBotId_shouldReturnAlertsForBot() {
        // Arrange
        List<TradingViewAlert> alerts = Collections.singletonList(testAlert);
        when(tradingViewAlertRepository.findByBotId(TEST_BOT_ID)).thenReturn(alerts);

        // Act
        List<TradingViewAlert> result = tradingViewAlertService.getAlertsByBotId(TEST_BOT_ID);

        // Assert
        assertEquals(1, result.size());
        assertEquals(TEST_ALERT_ID, result.getFirst().getId());
        assertEquals(TEST_BOT_ID, result.getFirst().getBotId());
        verify(tradingViewAlertRepository).findByBotId(TEST_BOT_ID);
    }

    @Test
    void getAlertsByBotIdAndTicker_shouldReturnAlertsForBotAndTicker() {
        // Arrange
        List<TradingViewAlert> alerts = Collections.singletonList(testAlert);
        when(tradingViewAlertRepository.findByBotIdAndTicker(TEST_BOT_ID, TEST_TICKER)).thenReturn(alerts);

        // Act
        List<TradingViewAlert> result = tradingViewAlertService.getAlertsByBotIdAndTicker(TEST_BOT_ID, TEST_TICKER);

        // Assert
        assertEquals(1, result.size());
        assertEquals(TEST_ALERT_ID, result.getFirst().getId());
        assertEquals(TEST_BOT_ID, result.getFirst().getBotId());
        assertEquals(TEST_TICKER, result.getFirst().getTicker());
        verify(tradingViewAlertRepository).findByBotIdAndTicker(TEST_BOT_ID, TEST_TICKER);
    }

    @Test
    void getAlertsByBotIdAndAction_shouldReturnAlertsForBotAndAction() {
        // Arrange
        List<TradingViewAlert> alerts = Collections.singletonList(testAlert);
        when(tradingViewAlertRepository.findByBotIdAndAction(TEST_BOT_ID, TEST_ACTION)).thenReturn(alerts);

        // Act
        List<TradingViewAlert> result = tradingViewAlertService.getAlertsByBotIdAndAction(TEST_BOT_ID, TEST_ACTION);

        // Assert
        assertEquals(1, result.size());
        assertEquals(TEST_ALERT_ID, result.getFirst().getId());
        assertEquals(TEST_BOT_ID, result.getFirst().getBotId());
        assertEquals(TEST_ACTION, result.getFirst().getAction());
        verify(tradingViewAlertRepository).findByBotIdAndAction(TEST_BOT_ID, TEST_ACTION);
    }

    @Test
    void getAlertsByBotIdAndTimeRange_shouldReturnAlertsForBotAndTimeRange() {
        // Arrange
        List<TradingViewAlert> alerts = Collections.singletonList(testAlert);
        Instant startTime = Instant.parse("2023-01-01T00:00:00Z");
        Instant endTime = Instant.parse("2023-01-02T00:00:00Z");
        when(tradingViewAlertRepository.findByBotIdAndTimestampBetween(TEST_BOT_ID, startTime, endTime)).thenReturn(alerts);

        // Act
        List<TradingViewAlert> result = tradingViewAlertService.getAlertsByBotIdAndTimeRange(TEST_BOT_ID, startTime, endTime);

        // Assert
        assertEquals(1, result.size());
        assertEquals(TEST_ALERT_ID, result.getFirst().getId());
        assertEquals(TEST_BOT_ID, result.getFirst().getBotId());
        assertEquals(TEST_TIMESTAMP, result.getFirst().getTimestamp());
        verify(tradingViewAlertRepository).findByBotIdAndTimestampBetween(TEST_BOT_ID, startTime, endTime);
    }

    @Test
    void getAlertById_shouldReturnAlertWhenFound() {
        // Arrange
        when(tradingViewAlertRepository.findById(TEST_ALERT_ID)).thenReturn(Optional.of(testAlert));

        // Act
        Optional<TradingViewAlert> result = tradingViewAlertService.getAlertById(TEST_ALERT_ID);

        // Assert
        assertTrue(result.isPresent());
        assertEquals(TEST_ALERT_ID, result.get().getId());
        verify(tradingViewAlertRepository).findById(TEST_ALERT_ID);
    }

    @Test
    void getAlertById_shouldReturnEmptyOptionalWhenNotFound() {
        // Arrange
        when(tradingViewAlertRepository.findById(TEST_ALERT_ID)).thenReturn(Optional.empty());

        // Act
        Optional<TradingViewAlert> result = tradingViewAlertService.getAlertById(TEST_ALERT_ID);

        // Assert
        assertTrue(result.isEmpty());
        verify(tradingViewAlertRepository).findById(TEST_ALERT_ID);
    }

    @Test
    void deleteAlert_shouldCallRepositoryDeleteById() {
        // Act
        tradingViewAlertService.deleteAlert(TEST_ALERT_ID);

        // Assert
        verify(tradingViewAlertRepository).deleteById(TEST_ALERT_ID);
    }
}