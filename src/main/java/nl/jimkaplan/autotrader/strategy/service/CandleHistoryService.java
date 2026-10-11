package nl.jimkaplan.autotrader.strategy.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.jimkaplan.autotrader.bitvavo.MarketFormat;
import nl.jimkaplan.autotrader.bitvavo.client.BitvavoApiClient;
import nl.jimkaplan.autotrader.repository.CandleRepository;
import nl.jimkaplan.autotrader.strategy.model.CandleInterval;
import nl.jimkaplan.autotrader.strategy.model.document.Candle;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads and stores historical candlesticks from the Bitvavo API.
 *
 * <p>Candles are cached in MongoDB and fetched on demand. A request to Bitvavo either
 * succeeds completely or throws, so a stored block of candles is only ever missing
 * candles at its ends; filling the head and the tail is enough to guarantee coverage
 * of the requested range.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CandleHistoryService {

    /**
     * Bitvavo returns at most 1440 candles per request.
     */
    static final int MAX_CANDLES_PER_REQUEST = 1440;

    private static final int CANDLE_FIELD_COUNT = 6;

    private final CandleRepository candleRepository;
    private final BitvavoApiClient bitvavoApiClient;

    /**
     * Pause between paged requests to stay well within the API rate limit.
     */
    @Value("${strategy.candles.request-spacing-ms:150}")
    private long requestSpacingMs = 150;

    /**
     * Serializes range loads per market and interval, so parallel runs cannot fetch the same gap twice.
     */
    private final ConcurrentHashMap<String, Object> rangeLocks = new ConcurrentHashMap<>();

    /**
     * Ensure candles are stored for every candle that is closed between {@code from} and {@code to}.
     * Only missing candles at the head or the tail of the stored block are fetched.
     *
     * @param market   Market in Bitvavo format or as a ticker, e.g. "BTC-EUR" or "BTCEUR"
     * @param interval Candle interval
     * @param from     Start of the requested range
     * @param to       End of the requested range
     * @return Number of candles fetched and stored
     */
    public int ensureRange(String market, CandleInterval interval, Instant from, Instant to) {
        String normalizedMarket = MarketFormat.toMarket(market);
        long fromOpen = interval.floorOpenTime(from.toEpochMilli());
        long toOpen = resolveToOpen(interval, to, Instant.now());
        if (fromOpen > toOpen) {
            return 0;
        }

        Object lock = rangeLocks.computeIfAbsent(normalizedMarket + "|" + interval.getCode(), key -> new Object());
        synchronized (lock) {
            return fetchMissing(normalizedMarket, interval, fromOpen, toOpen);
        }
    }

    /**
     * Candles stored for the range, oldest first. Only candles already stored are returned;
     * call {@link #ensureRange} first to load the range from Bitvavo.
     *
     * @param market   Market in Bitvavo format or as a ticker, e.g. "BTC-EUR" or "BTCEUR"
     * @param interval Candle interval
     * @param from     Start of the requested range
     * @param to       End of the requested range
     * @return Stored candles whose open time is between the bounds
     */
    public List<Candle> getCandles(String market, CandleInterval interval, Instant from, Instant to) {
        return candleRepository.findCandlesInRange(
                MarketFormat.toMarket(market), interval.getCode(),
                interval.floorOpenTime(from.toEpochMilli()),
                interval.lastClosedOpenTime(to.toEpochMilli()));
    }

    /**
     * Open time of the last candle to load: the earlier of the requested end
     * and the latest candle that has fully closed by now.
     *
     * @param interval Candle interval
     * @param to       Requested end of the range
     * @param now      Current time
     * @return The open time of the last candle to load
     */
    long resolveToOpen(CandleInterval interval, Instant to, Instant now) {
        return Math.min(interval.lastClosedOpenTime(to.toEpochMilli()),
                interval.lastClosedOpenTime(now.toEpochMilli()));
    }

    private int fetchMissing(String market, CandleInterval interval, long fromOpen, long toOpen) {
        Optional<Candle> newest = candleRepository.findFirstByMarketAndIntervalOrderByOpenTimeDesc(market, interval.getCode());
        if (newest.isEmpty()) {
            return fetchPages(market, interval, fromOpen, toOpen);
        }

        Optional<Candle> oldest = candleRepository.findFirstByMarketAndIntervalOrderByOpenTimeAsc(market, interval.getCode());
        // The stored block is contiguous, so only its ends can be missing. Both ends are
        // computed from the stored state before either fetch; they cannot overlap.
        long headFrom = Math.max(fromOpen, interval.nextOpenTime(newest.get().getOpenTime()));
        int fetched = fetchPages(market, interval, headFrom, toOpen);
        long tailTo = Math.min(toOpen, interval.previousOpenTime(oldest.orElseThrow().getOpenTime()));
        fetched += fetchPages(market, interval, fromOpen, tailTo);
        return fetched;
    }

    /**
     * Fetch and store the candles in [fromOpen, toOpen], page by page, newest page first.
     * Each page is saved before the next is fetched, so an interruption only ever leaves
     * candles missing at the oldest end of the range.
     */
    private int fetchPages(String market, CandleInterval interval, long fromOpen, long toOpen) {
        if (fromOpen > toOpen) {
            return 0;
        }

        log.info("Fetching {} {} candles with open time from {} to {}", market, interval.getCode(), fromOpen, toOpen);
        int fetched = 0;
        // Bitvavo's 'end' parameter is exclusive, so the newest candle to include is the
        // open time just below the exclusive bound.
        long endExclusive = interval.nextOpenTime(toOpen);
        while (true) {
            String endpoint = UriComponentsBuilder.fromPath("/" + market + "/candles")
                    .queryParam("interval", interval.getCode())
                    .queryParam("limit", MAX_CANDLES_PER_REQUEST)
                    .queryParam("start", fromOpen)
                    .queryParam("end", endExclusive)
                    .build()
                    .encode()
                    .toUriString();

            String[][] rows = bitvavoApiClient.getPublic(endpoint, String[][].class);
            if (rows == null || rows.length == 0) {
                break;
            }

            List<Candle> page = toCandles(market, interval, rows, fromOpen, toOpen);
            if (page.isEmpty()) {
                break;
            }

            candleRepository.saveAll(page);
            fetched += page.size();

            long oldestOpen = page.get(0).getOpenTime();
            if (rows.length < MAX_CANDLES_PER_REQUEST || oldestOpen <= fromOpen) {
                break;
            }
            endExclusive = oldestOpen;
            pauseBetweenRequests();
            if (Thread.currentThread().isInterrupted()) {
                break;
            }
        }
        return fetched;
    }

    private List<Candle> toCandles(String market, CandleInterval interval, String[][] rows,
                                   long fromOpen, long toOpen) {
        Instant ingestedAt = Instant.now();
        List<Candle> candles = new ArrayList<>(rows.length);
        for (String[] row : rows) {
            if (row == null || row.length != CANDLE_FIELD_COUNT) {
                throw new IllegalStateException("Unexpected candle format from Bitvavo: " + Arrays.toString(row));
            }
            long openTime = Long.parseLong(row[0]);
            if (openTime < fromOpen || openTime > toOpen) {
                continue;
            }
            candles.add(Candle.builder()
                    .market(market)
                    .interval(interval.getCode())
                    .openTime(openTime)
                    .open(new BigDecimal(row[1]))
                    .high(new BigDecimal(row[2]))
                    .low(new BigDecimal(row[3]))
                    .close(new BigDecimal(row[4]))
                    .volume(new BigDecimal(row[5]))
                    .ingestedAt(ingestedAt)
                    .build());
        }
        candles.sort(Comparator.comparingLong(Candle::getOpenTime));
        return candles;
    }

    private void pauseBetweenRequests() {
        if (requestSpacingMs <= 0) {
            return;
        }
        try {
            Thread.sleep(requestSpacingMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
