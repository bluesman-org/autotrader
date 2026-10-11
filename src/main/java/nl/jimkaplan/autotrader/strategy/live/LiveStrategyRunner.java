package nl.jimkaplan.autotrader.strategy.live;

import lombok.extern.slf4j.Slf4j;
import nl.jimkaplan.autotrader.repository.LiveStrategyRepository;
import nl.jimkaplan.autotrader.strategy.backtest.BacktestDefaults;
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
 * <p>At start it seeds the candle series from the store — the strategy's full history for its
 * market and interval, so an evaluation can never disagree with a backtest over the same data —
 * and then listens to the stream's closed-candle boundaries. Each closed candle is resolved from
 * the store (the REST-filled candles are authoritative), appended to the series, and evaluated.
 * The seed itself is never traded: only candles that close while the strategy runs are acted on,
 * so nothing is replayed after downtime.</p>
 */
@Slf4j
public class LiveStrategyRunner implements BitvavoCandleStream.CandleListener {

    /**
     * Extra history beyond the warm-up, so the indicators are converged before the first evaluation.
     */
    static final int SEED_MARGIN_CANDLES = 60;

    private final LiveStrategy strategy;
    private final CandleInterval interval;
    private final CandleHistoryService candleHistoryService;
    private final LiveTradingService liveTradingService;
    private final LiveStrategyRepository liveStrategyRepository;
    private final BitvavoCandleStream stream;
    private final List<Candle> series = new ArrayList<>();
    private long lastAppendedOpenTime = Long.MIN_VALUE;
    private long resolveRetryMillis = 1_000;

    /**
     * @param strategy              The strategy to run
     * @param interval              Its candle interval
     * @param candleHistoryService  The candle store
     * @param liveTradingService    The evaluator and executor
     * @param liveStrategyRepository Persists the last evaluated candle
     * @param streamFactory         Builds the candle stream
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
     * @return The strategy, whose last-evaluated candle the runner advances
     */
    public LiveStrategy getStrategy() {
        return strategy;
    }

    void seed() {
        Instant now = Instant.now();
        Instant from = now.minusMillis(
                (BacktestDefaults.WARM_UP_CANDLES + SEED_MARGIN_CANDLES) * interval.getMillis());
        candleHistoryService.ensureRange(strategy.getMarket(), interval, from, now);
        series.addAll(candleHistoryService.getCandles(strategy.getMarket(), interval, Instant.EPOCH, now));
        if (!series.isEmpty()) {
            lastAppendedOpenTime = series.get(series.size() - 1).getOpenTime();
        }
        log.info("Live strategy {} seeded with {} {} {} candles", strategy.getId(), series.size(),
                strategy.getMarket(), strategy.getInterval());
    }

    @Override
    public void onCandleClosed(long openTime) {
        if (openTime <= strategy.getLastEvaluatedOpenTime()) {
            log.debug("Live strategy {} already evaluated candle {}", strategy.getId(), openTime);
            return;
        }
        Candle candle = resolve(openTime);
        if (candle == null) {
            return;
        }
        append(candle);
        // Persist the evaluated point before acting: a crash in between then costs a trade instead
        // of duplicating one.
        strategy.setLastEvaluatedOpenTime(openTime);
        liveStrategyRepository.save(strategy);
        liveTradingService.evaluateAndTrade(strategy, series);
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

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private void append(Candle candle) {
        if (candle.getOpenTime() > lastAppendedOpenTime) {
            series.add(candle);
            lastAppendedOpenTime = candle.getOpenTime();
        }
    }
}
