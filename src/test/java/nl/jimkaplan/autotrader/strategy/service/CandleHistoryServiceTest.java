package nl.jimkaplan.autotrader.strategy.service;

import nl.jimkaplan.autotrader.bitvavo.client.BitvavoApiClient;
import nl.jimkaplan.autotrader.repository.CandleRepository;
import nl.jimkaplan.autotrader.strategy.model.CandleInterval;
import nl.jimkaplan.autotrader.strategy.model.document.Candle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CandleHistoryServiceTest {

    private static final long HOUR = 3_600_000L;

    /**
     * 2024-01-01T00:00:00Z, aligned to every supported interval.
     */
    private static final long BASE = 1_704_067_200_000L;

    private static final String MARKET = "BTC-EUR";
    private static final String INTERVAL_CODE = "1h";
    private static final CandleInterval INTERVAL = CandleInterval.ONE_HOUR;

    @Mock
    private CandleRepository candleRepository;

    @Mock
    private BitvavoApiClient bitvavoApiClient;

    private CandleHistoryService candleHistoryService;

    @BeforeEach
    void setUp() {
        candleHistoryService = new CandleHistoryService(candleRepository, bitvavoApiClient);
        ReflectionTestUtils.setField(candleHistoryService, "requestSpacingMs", 0L);
    }

    @Test
    void ensureRange_withEmptyStore_fetchesWholeRange() {
        when(candleRepository.findFirstByMarketAndIntervalOrderByOpenTimeDesc(MARKET, INTERVAL_CODE))
                .thenReturn(Optional.empty());
        when(bitvavoApiClient.getPublic(anyString(), eq(String[][].class)))
                .thenReturn(new String[][]{row(BASE + HOUR), row(BASE)});

        int fetched = candleHistoryService.ensureRange(MARKET, INTERVAL,
                Instant.ofEpochMilli(BASE), Instant.ofEpochMilli(BASE + 2 * HOUR));

        assertEquals(2, fetched);
        ArgumentCaptor<String> endpointCaptor = ArgumentCaptor.forClass(String.class);
        verify(bitvavoApiClient).getPublic(endpointCaptor.capture(), eq(String[][].class));
        String endpoint = endpointCaptor.getValue();
        assertTrue(endpoint.startsWith("/BTC-EUR/candles?"));
        assertTrue(endpoint.contains("interval=1h"));
        assertTrue(endpoint.contains("limit=1440"));
        assertTrue(endpoint.contains("start=" + BASE));
        assertTrue(endpoint.contains("end=" + (BASE + 2 * HOUR)));

        List<Candle> saved = captureSavedBatches(1).get(0);
        assertEquals(2, saved.size());
        assertEquals(BASE, saved.get(0).getOpenTime());
        assertEquals(BASE + HOUR, saved.get(1).getOpenTime());
        assertEquals(MARKET, saved.get(0).getMarket());
        assertEquals(INTERVAL_CODE, saved.get(0).getInterval());
        assertEquals(new BigDecimal("1.5"), saved.get(0).getClose());
        assertNotNull(saved.get(0).getIngestedAt());
    }

    @Test
    void ensureRange_withFullyCoveredRange_doesNotFetch() {
        when(candleRepository.findFirstByMarketAndIntervalOrderByOpenTimeDesc(MARKET, INTERVAL_CODE))
                .thenReturn(Optional.of(candle(BASE + HOUR)));
        when(candleRepository.findFirstByMarketAndIntervalOrderByOpenTimeAsc(MARKET, INTERVAL_CODE))
                .thenReturn(Optional.of(candle(BASE)));

        int fetched = candleHistoryService.ensureRange(MARKET, INTERVAL,
                Instant.ofEpochMilli(BASE), Instant.ofEpochMilli(BASE + 2 * HOUR));

        assertEquals(0, fetched);
        verifyNoInteractions(bitvavoApiClient);
        verify(candleRepository, never()).saveAll(any());
    }

    @Test
    void ensureRange_withHeadGap_fetchesOnlyMissingHead() {
        when(candleRepository.findFirstByMarketAndIntervalOrderByOpenTimeDesc(MARKET, INTERVAL_CODE))
                .thenReturn(Optional.of(candle(BASE + HOUR)));
        when(candleRepository.findFirstByMarketAndIntervalOrderByOpenTimeAsc(MARKET, INTERVAL_CODE))
                .thenReturn(Optional.of(candle(BASE)));
        when(bitvavoApiClient.getPublic(anyString(), eq(String[][].class)))
                .thenReturn(new String[][]{row(BASE + 2 * HOUR)});

        int fetched = candleHistoryService.ensureRange(MARKET, INTERVAL,
                Instant.ofEpochMilli(BASE), Instant.ofEpochMilli(BASE + 3 * HOUR));

        assertEquals(1, fetched);
        ArgumentCaptor<String> endpointCaptor = ArgumentCaptor.forClass(String.class);
        verify(bitvavoApiClient).getPublic(endpointCaptor.capture(), eq(String[][].class));
        assertTrue(endpointCaptor.getValue().contains("start=" + (BASE + 2 * HOUR)));
        assertTrue(endpointCaptor.getValue().contains("end=" + (BASE + 3 * HOUR)));
        List<Candle> saved = captureSavedBatches(1).get(0);
        assertEquals(List.of(BASE + 2 * HOUR), saved.stream().map(Candle::getOpenTime).toList());
    }

    @Test
    void ensureRange_withTailGap_fetchesOnlyMissingTail() {
        when(candleRepository.findFirstByMarketAndIntervalOrderByOpenTimeDesc(MARKET, INTERVAL_CODE))
                .thenReturn(Optional.of(candle(BASE + 2 * HOUR)));
        when(candleRepository.findFirstByMarketAndIntervalOrderByOpenTimeAsc(MARKET, INTERVAL_CODE))
                .thenReturn(Optional.of(candle(BASE + HOUR)));
        when(bitvavoApiClient.getPublic(anyString(), eq(String[][].class)))
                .thenReturn(new String[][]{row(BASE)});

        int fetched = candleHistoryService.ensureRange(MARKET, INTERVAL,
                Instant.ofEpochMilli(BASE), Instant.ofEpochMilli(BASE + 2 * HOUR));

        assertEquals(1, fetched);
        ArgumentCaptor<String> endpointCaptor = ArgumentCaptor.forClass(String.class);
        verify(bitvavoApiClient).getPublic(endpointCaptor.capture(), eq(String[][].class));
        assertTrue(endpointCaptor.getValue().contains("start=" + BASE));
        assertTrue(endpointCaptor.getValue().contains("end=" + (BASE + HOUR)));
        List<Candle> saved = captureSavedBatches(1).get(0);
        assertEquals(List.of(BASE), saved.stream().map(Candle::getOpenTime).toList());
    }

    @Test
    void resolveToOpen_isCappedAtLatestClosedCandle() {
        // 03:30: the candles up to and including the one opening at 02:00 have closed
        Instant now = Instant.ofEpochMilli(BASE + 3 * HOUR + 30 * 60_000L);

        assertEquals(BASE + 2 * HOUR, candleHistoryService.resolveToOpen(
                INTERVAL, Instant.ofEpochMilli(BASE + 10 * HOUR), now));
        assertEquals(BASE + HOUR, candleHistoryService.resolveToOpen(
                INTERVAL, Instant.ofEpochMilli(BASE + 2 * HOUR), now));
    }

    @Test
    void ensureRange_normalizesMarket() {
        when(candleRepository.findFirstByMarketAndIntervalOrderByOpenTimeDesc(MARKET, INTERVAL_CODE))
                .thenReturn(Optional.empty());
        when(bitvavoApiClient.getPublic(anyString(), eq(String[][].class)))
                .thenReturn(new String[][]{row(BASE)});

        int fetched = candleHistoryService.ensureRange("BTCEUR", INTERVAL,
                Instant.ofEpochMilli(BASE), Instant.ofEpochMilli(BASE + HOUR));

        assertEquals(1, fetched);
        verify(candleRepository).findFirstByMarketAndIntervalOrderByOpenTimeDesc(MARKET, INTERVAL_CODE);
        ArgumentCaptor<String> endpointCaptor = ArgumentCaptor.forClass(String.class);
        verify(bitvavoApiClient).getPublic(endpointCaptor.capture(), eq(String[][].class));
        assertTrue(endpointCaptor.getValue().startsWith("/BTC-EUR/candles?"));
        List<Candle> saved = captureSavedBatches(1).get(0);
        assertEquals(MARKET, saved.get(0).getMarket());
    }

    @Test
    void ensureRange_withRangeLargerThanPageSize_paginates() {
        when(candleRepository.findFirstByMarketAndIntervalOrderByOpenTimeDesc(MARKET, INTERVAL_CODE))
                .thenReturn(Optional.empty());
        when(bitvavoApiClient.getPublic(anyString(), eq(String[][].class)))
                .thenReturn(rowsDescending(BASE + 1999 * HOUR, 1440))
                .thenReturn(rowsDescending(BASE + 559 * HOUR, 560));

        int fetched = candleHistoryService.ensureRange(MARKET, INTERVAL,
                Instant.ofEpochMilli(BASE), Instant.ofEpochMilli(BASE + 2000 * HOUR));

        assertEquals(2000, fetched);
        ArgumentCaptor<String> endpointCaptor = ArgumentCaptor.forClass(String.class);
        verify(bitvavoApiClient, times(2)).getPublic(endpointCaptor.capture(), eq(String[][].class));
        assertTrue(endpointCaptor.getAllValues().get(0).contains("end=" + (BASE + 2000 * HOUR)));
        assertTrue(endpointCaptor.getAllValues().get(1).contains("end=" + (BASE + 560 * HOUR)));
        List<List<Candle>> batches = captureSavedBatches(2);
        assertEquals(1440, batches.get(0).size());
        assertEquals(560, batches.get(1).size());
        assertEquals(BASE, batches.get(1).get(0).getOpenTime());
    }

    @Test
    void ensureRange_ignoresRowsOutsideRequestedWindow() {
        when(candleRepository.findFirstByMarketAndIntervalOrderByOpenTimeDesc(MARKET, INTERVAL_CODE))
                .thenReturn(Optional.empty());
        when(bitvavoApiClient.getPublic(anyString(), eq(String[][].class)))
                .thenReturn(new String[][]{row(BASE + HOUR), row(BASE)});

        int fetched = candleHistoryService.ensureRange(MARKET, INTERVAL,
                Instant.ofEpochMilli(BASE), Instant.ofEpochMilli(BASE + HOUR));

        assertEquals(1, fetched);
        List<Candle> saved = captureSavedBatches(1).get(0);
        assertEquals(List.of(BASE), saved.stream().map(Candle::getOpenTime).toList());
    }

    @Test
    void ensureRange_withMalformedCandleRow_throws() {
        when(candleRepository.findFirstByMarketAndIntervalOrderByOpenTimeDesc(MARKET, INTERVAL_CODE))
                .thenReturn(Optional.empty());
        when(bitvavoApiClient.getPublic(anyString(), eq(String[][].class)))
                .thenReturn(new String[][]{{String.valueOf(BASE), "1.0", "2.0"}});

        assertThrows(IllegalStateException.class, () -> candleHistoryService.ensureRange(MARKET, INTERVAL,
                Instant.ofEpochMilli(BASE), Instant.ofEpochMilli(BASE + HOUR)));
    }

    @Test
    void getCandles_queriesAlignedBounds() {
        List<Candle> stored = List.of(candle(BASE + HOUR));
        when(candleRepository.findCandlesInRange(MARKET, INTERVAL_CODE, BASE, BASE + HOUR)).thenReturn(stored);

        List<Candle> result = candleHistoryService.getCandles("BTCEUR", INTERVAL,
                Instant.ofEpochMilli(BASE + 10 * 60_000L), Instant.ofEpochMilli(BASE + 2 * HOUR));

        assertEquals(stored, result);
        verify(candleRepository).findCandlesInRange(MARKET, INTERVAL_CODE, BASE, BASE + HOUR);
    }

    private static String[] row(long openTime) {
        return new String[]{String.valueOf(openTime), "1.0", "2.0", "0.5", "1.5", "10.5"};
    }

    private static String[][] rowsDescending(long newestOpenTime, int count) {
        String[][] rows = new String[count][];
        for (int i = 0; i < count; i++) {
            rows[i] = row(newestOpenTime - i * HOUR);
        }
        return rows;
    }

    private static Candle candle(long openTime) {
        return Candle.builder()
                .market(MARKET)
                .interval(INTERVAL_CODE)
                .openTime(openTime)
                .build();
    }

    @SuppressWarnings("unchecked")
    private List<List<Candle>> captureSavedBatches(int expectedBatches) {
        ArgumentCaptor<List<Candle>> captor = ArgumentCaptor.forClass(List.class);
        verify(candleRepository, times(expectedBatches)).saveAll(captor.capture());
        return captor.getAllValues();
    }
}
