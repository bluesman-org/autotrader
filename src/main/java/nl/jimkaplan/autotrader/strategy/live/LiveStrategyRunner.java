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
 * <p>At start it seeds the candle series from a fixed anchor — the strategy's first start minus the
 * seed window (warm-up plus the MACD periods plus a convergence margin), persisted as
 * {@code seriesStartTime} and never moved — and then listens to the stream's closed-candle
 * boundaries, appending every closed candle to the same series. The series is append-only and
 * identical across restarts. It is deliberately not truncated to a rolling window: the Parabolic
 * SAR's flip timing is start-sensitive, so a window that slides (or is re-seeded at a different
 * point) produces exits on slightly different candles for near-identical runs. A backtest started
 * at the same anchor with the warm-up set to the window length therefore reproduces the live
 * decisions on every bar the engine evaluated — for MACD and PSAR alike. It does not reproduce a
 * gap: the engine never replays the seed or downtime, so after one the backtest can hold a position
 * the engine never opened.</p>
 *
 * <p>Each closed candle is resolved from the store (the REST-filled candles are authoritative) and
 * appended; a boundary whose candle is missing (also after a long outage) is backfilled on the next
 * append so the in-memory series keeps matching the stored history. If the store cannot reach back
 * to the anchor (old data unavailable, e.g. after a restore), the series simply starts later and a
 * warning says so.</p>
 *
 * <p>Decisions are filled immediately at the arriving candle's open — the stream passes the open
 * price of the candle that just started, so a live order goes out at the moment its recorded price
 * is the market price, the same next-open fill point the backtester uses. The seed itself is never
 * traded, so nothing is replayed after downtime.</p>
 */
@Slf4j
public class LiveStrategyRunner implements BitvavoCandleStream.CandleListener {

    /**
     * Candles of history before the strategy's start that the fixed series anchor includes, so the
     * MACD recursion has converged and the Parabolic SAR has a stable trajectory to start from.
     * MACD converges well within this margin; the SAR's trajectory only coincides with a longer
     * history's when its flips are unambiguous, which is why the anchor is persisted and never
     * re-derived (see {@code docs/live-engine.md}).
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
        long seriesStart = strategy.getSeriesStartTime();
        if (seriesStart == 0) {
            seriesStart = now.toEpochMilli() - (long) windowLength() * interval.getMillis();
            long modified = liveStrategyRepository.updateSeriesStartTime(strategy.getId(), seriesStart);
            if (modified == 0) {
                // The race for the one value that must never move was lost (or the strategy is
                // gone): an anchor is already fixed, so the stored value wins over our derivation.
                seriesStart = liveStrategyRepository.findById(strategy.getId())
                        .map(LiveStrategy::getSeriesStartTime)
                        .filter(stored -> stored > 0)
                        .orElse(seriesStart);
            }
            strategy.setSeriesStartTime(seriesStart);
        }
        Instant from = Instant.ofEpochMilli(seriesStart);
        candleHistoryService.ensureRange(strategy.getMarket(), interval, from, now);
        series.addAll(candleHistoryService.getCandles(strategy.getMarket(), interval, from, now));
        if (!series.isEmpty()) {
            lastAppendedOpenTime = series.get(series.size() - 1).getOpenTime();
        }
        long expected = (now.toEpochMilli() - seriesStart) / interval.getMillis();
        if (series.size() < expected) {
            log.warn("Live strategy {} is anchored at {} but the store has only {} of about {} {} {} candles; "
                            + "the series starts later", strategy.getId(), from, series.size(), expected,
                    strategy.getMarket(), strategy.getInterval());
        }
        log.info("Live strategy {} seeded with {} {} {} candles (anchor {})", strategy.getId(), series.size(),
                strategy.getMarket(), strategy.getInterval(), from);
    }

    @Override
    public void onCandleClosed(long openTime, double nextOpen) {
        if (openTime <= strategy.getLastEvaluatedOpenTime()) {
            log.debug("Live strategy {} already evaluated candle {}", strategy.getId(), openTime);
            return;
        }
        Candle candle = resolve(openTime);
        if (candle == null) {
            liveTradingService.recordSkippedCandle(strategy, openTime);
            return;
        }
        append(candle);
        // Persist the evaluated point before acting: a crash in between then costs a trade instead
        // of duplicating one. Only this field is written, so the enabled flag is never clobbered.
        strategy.setLastEvaluatedOpenTime(openTime);
        liveStrategyRepository.updateLastEvaluatedOpenTime(strategy.getId(), openTime);

        LiveDecision decision = liveTradingService.decide(strategy, series);
        if (decision != null) {
            liveTradingService.fill(strategy, decision, fillPrice(openTime, nextOpen, candle));
        }
    }

    /**
     * The price a decision made on this candle is filled at: the open of the candle that followed
     * it. That candle has already closed for any boundary older than the frame that revealed it (a
     * reconnect gap), so it is fetched into the store and read from there — the backtester's price.
     * For the candle still forming the fetch is clamped away and the frame's open is the market
     * price; the closed candle's close is the last resort.
     */
    private double fillPrice(long openTime, double nextOpen, Candle closedCandle) {
        long nextOpenTime = openTime + interval.getMillis();
        Instant from = Instant.ofEpochMilli(nextOpenTime);
        Instant to = Instant.ofEpochMilli(nextOpenTime + interval.getMillis());
        // ensureRange clamps its end to the last closed candle, so this fetches the candle after an
        // older boundary and does nothing while the boundary's immediate successor is still forming.
        candleHistoryService.ensureRange(strategy.getMarket(), interval, from, to);
        List<Candle> next = candleHistoryService.getCandles(strategy.getMarket(), interval, from, to);
        if (!next.isEmpty()) {
            return next.get(0).getOpen().doubleValue();
        }
        if (nextOpen > 0) {
            return nextOpen;
        }
        return closedCandle.getClose().doubleValue();
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
        // The series is append-only: its anchored start must never move, or the Parabolic SAR's
        // flips — and with them the exits — shift for the very same candle history.
        series.add(candle);
        lastAppendedOpenTime = candle.getOpenTime();
    }

    /**
     * Re-read a skipped range from the store, fetching it first when it is not there yet, so the
     * in-memory series keeps matching the exchange's history instead of silently losing bars.
     */
    private void backfill(long fromOpenTime, long toOpenTime) {
        Instant from = Instant.ofEpochMilli(fromOpenTime);
        Instant to = Instant.ofEpochMilli(toOpenTime + interval.getMillis());
        candleHistoryService.ensureRange(strategy.getMarket(), interval, from, to);
        List<Candle> missed = candleHistoryService.getCandles(strategy.getMarket(), interval, from, to);
        int appended = 0;
        for (Candle missedCandle : missed) {
            if (missedCandle.getOpenTime() > lastAppendedOpenTime) {
                series.add(missedCandle);
                lastAppendedOpenTime = missedCandle.getOpenTime();
                appended++;
            }
        }
        if (appended > 0) {
            log.info("Backfilled {} skipped {} {} candles into the live series", appended, strategy.getMarket(),
                    strategy.getInterval());
        } else {
            log.warn("The store has no {} {} candles between {} and {} to backfill", strategy.getMarket(),
                    strategy.getInterval(), fromOpenTime, toOpenTime);
        }
    }

    int windowLength() {
        return windowLength(strategy, convergenceMarginCandles);
    }

    static int windowLength(LiveStrategy strategy, int convergenceMarginCandles) {
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
