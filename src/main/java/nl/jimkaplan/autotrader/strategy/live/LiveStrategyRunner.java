package nl.jimkaplan.autotrader.strategy.live;

import lombok.extern.slf4j.Slf4j;
import nl.jimkaplan.autotrader.repository.LiveStrategyRepository;
import nl.jimkaplan.autotrader.strategy.model.CandleInterval;
import nl.jimkaplan.autotrader.strategy.model.document.Candle;
import nl.jimkaplan.autotrader.strategy.model.document.LiveStrategy;
import nl.jimkaplan.autotrader.strategy.service.CandleHistoryService;
import nl.jimkaplan.autotrader.strategy.service.LiveTradingService;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The runtime of one live strategy.
 *
 * <p>At start it seeds a bounded candle window from the store — warm-up plus a convergence margin,
 * enough for the recursive indicators to have converged, so the decision matches what a whole
 * history produces at the same bar — and then listens to the stream's closed-candle boundaries.
 * Each closed candle is resolved from the store (the REST-filled candles are authoritative) and
 * appended; a boundary whose candle is missing (also after a long outage) is backfilled on the next
 * append so the in-memory series keeps matching the stored history.</p>
 *
 * <p>Decisions are carried one boundary forward, like the backtester's {@code pendingBuy} /
 * {@code pendingSell}: a signal on the closed candle is filled at the next candle's open. The
 * seed itself is never traded, so nothing is replayed after downtime.</p>
 */
@Slf4j
public class LiveStrategyRunner implements BitvavoCandleStream.CandleListener {

    /**
     * Candles kept beyond the warm-up so the MACD/PSAR recursions have converged; both converge
     * orders of magnitude faster than this margin.
     */
    static final int CONVERGENCE_MARGIN_CANDLES = 1_000;

    private final LiveStrategy strategy;
    private final CandleInterval interval;
    private final CandleHistoryService candleHistoryService;
    private final LiveTradingService liveTradingService;
    private final LiveStrategyRepository liveStrategyRepository;
    private final BitvavoCandleStream stream;
    private final List<Candle> series = new ArrayList<>();
    private long lastAppendedOpenTime = Long.MIN_VALUE;
    private LiveDecision pendingDecision;
    private long resolveRetryMillis = 1_000;
    private int convergenceMarginCandles = CONVERGENCE_MARGIN_CANDLES;

    /**
     * @param strategy               The strategy to run
     * @param interval               Its candle interval
     * @param candleHistoryService   The candle store
     * @param liveTradingService     The evaluator and executor
     * @param liveStrategyRepository Persists the last evaluated candle
     * @param streamFactory          Builds the candle stream
     */
    public LiveStrategyRunner(LiveStrategy strategy, CandleInterval interval,
                              CandleHistoryService candleHistoryService,
                              LiveTradingService liveTradingService,
                              LiveStrategyRepository liveStrategyRepository,
                              CandleStreamFactory streamFactory) {
        this.strategy = strategy;
        this.interval = interval;
        this.candleHistoryService = candleHistoryService;
        this.liveTradingService = liveTradingService;
        this.liveStrategyRepository = liveStrategyRepository;
        this.stream = streamFactory.create(strategy.getMarket(), interval.getCode(),
                interval.getMillis(), this::onCandleClosed);
    }

    /**
     * Seed the series and connect the stream.
     */
    public void start() {
        seed();
        stream.start();
    }

    /**
     * Disconnect for good; a stopped runner is replaced, not restarted.
     */
    public void stop() {
        stream.stop();
    }

    /**
     * @return Whether the stream subscription is currently up
     */
    public boolean isConnected() {
        return stream.isConnected();
    }

    /**
     * @return The stream's last error message, or null when there is none
     */
    public String getLastError() {
        return stream.getLastError();
    }

    /**
     * @return The strategy, whose last-evaluated candle the runner advances
     */
    public LiveStrategy getStrategy() {
        return strategy;
    }

    void seed() {
        Instant now = Instant.now();
        Instant from = Instant.ofEpochMilli(now.toEpochMilli() - windowLength() * interval.getMillis());
        candleHistoryService.ensureRange(strategy.getMarket(), interval, from, now);
        series.addAll(candleHistoryService.getCandles(strategy.getMarket(), interval, from, now));
        if (!series.isEmpty()) {
            lastAppendedOpenTime = series.get(series.size() - 1).getOpenTime();
        }
        log.info("Live strategy {} seeded with {} {} {} candles (window {})", strategy.getId(), series.size(),
                strategy.getMarket(), strategy.getInterval(), windowLength());
    }

    @Override
    public void onCandleClosed(long openTime) {
        if (openTime <= strategy.getLastEvaluatedOpenTime()) {
            log.debug("Live strategy {} already evaluated candle {}", strategy.getId(), openTime);
            return;
        }
        Candle candle = resolve(openTime);
        if (candle == null) {
            // The boundary is gone for good, so a decision waiting to be filled on it is dropped
            // rather than filled a bar late; the skip is recorded so it is visible.
            pendingDecision = null;
            liveTradingService.recordSkippedCandle(strategy, openTime);
            return;
        }
        append(candle);
        // Persist the evaluated point before acting: a crash in between then costs a trade instead
        // of duplicating one. Only this field is written, so the enabled flag is never clobbered.
        strategy.setLastEvaluatedOpenTime(openTime);
        liveStrategyRepository.updateLastEvaluatedOpenTime(strategy.getId(), openTime);

        LiveDecision decision = pendingDecision;
        pendingDecision = null;
        if (decision != null) {
            liveTradingService.fill(strategy, candle, decision);
        }
        pendingDecision = liveTradingService.decide(strategy, series);
    }

    private Candle resolve(long openTime) {
        // The store's end bound is exclusive: asking for [at, at] would cap back to the candle
        // before at, so the window runs to the start of the next candle instead.
        Instant at = Instant.ofEpochMilli(openTime);
        Instant next = Instant.ofEpochMilli(openTime + interval.getMillis());
        for (int attempt = 0; attempt < 2; attempt++) {
            candleHistoryService.ensureRange(strategy.getMarket(), interval, at, next);
            List<Candle> closed = candleHistoryService.getCandles(strategy.getMarket(), interval, at, next);
            if (!closed.isEmpty()) {
                return closed.get(0);
            }
            if (attempt == 0) {
                // The candle endpoint can lag the close by a moment; one short retry.
                sleep(resolveRetryMillis);
            }
        }
        log.warn("The closed {} {} candle at open time {} is not available yet; skipping it",
                strategy.getMarket(), strategy.getInterval(), openTime);
        return null;
    }

    private void append(Candle candle) {
        if (candle.getOpenTime() <= lastAppendedOpenTime) {
            return;
        }
        if (lastAppendedOpenTime != Long.MIN_VALUE
                && candle.getOpenTime() - lastAppendedOpenTime > interval.getMillis()) {
            backfill(lastAppendedOpenTime + interval.getMillis(), candle.getOpenTime() - interval.getMillis());
        }
        series.add(candle);
        lastAppendedOpenTime = candle.getOpenTime();
        trim();
    }

    /**
     * Re-read a skipped range from the store, so the in-memory series keeps matching the stored
     * history instead of silently losing bars.
     */
    private void backfill(long fromOpenTime, long toOpenTime) {
        List<Candle> missed = candleHistoryService.getCandles(strategy.getMarket(), interval,
                Instant.ofEpochMilli(fromOpenTime), Instant.ofEpochMilli(toOpenTime + interval.getMillis()));
        int appended = 0;
        for (Candle missedCandle : missed) {
            if (missedCandle.getOpenTime() > lastAppendedOpenTime) {
                series.add(missedCandle);
                lastAppendedOpenTime = missedCandle.getOpenTime();
                appended++;
            }
        }
        trim();
        if (appended > 0) {
            log.info("Backfilled {} skipped {} {} candles into the live series", appended, strategy.getMarket(),
                    strategy.getInterval());
        } else {
            log.warn("The store has no {} {} candles between {} and {} to backfill", strategy.getMarket(),
                    strategy.getInterval(), fromOpenTime, toOpenTime);
        }
    }

    private void trim() {
        int window = windowLength();
        while (series.size() > window) {
            series.remove(0);
        }
    }

    int windowLength() {
        return strategy.getWarmUpCandles() + strategy.getMacdSlowPeriod() + strategy.getMacdSignalPeriod()
                + convergenceMarginCandles;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
