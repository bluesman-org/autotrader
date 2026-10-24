package nl.jimkaplan.autotrader.strategy.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.jimkaplan.autotrader.bitvavo.MarketFormat;
import nl.jimkaplan.autotrader.repository.LiveOrderRepository;
import nl.jimkaplan.autotrader.repository.LivePositionRepository;
import nl.jimkaplan.autotrader.repository.LiveStrategyRepository;
import nl.jimkaplan.autotrader.service.BitvavoCredentialsService;
import nl.jimkaplan.autotrader.strategy.backtest.BacktestDefaults;
import nl.jimkaplan.autotrader.strategy.controller.LiveCloseResult;
import nl.jimkaplan.autotrader.strategy.controller.LiveCloseView;
import nl.jimkaplan.autotrader.strategy.controller.LiveStrategyRequest;
import nl.jimkaplan.autotrader.strategy.controller.ReconcileView;
import nl.jimkaplan.autotrader.strategy.controller.RuleSetRequest;
import nl.jimkaplan.autotrader.strategy.live.CandleStreamFactory;
import nl.jimkaplan.autotrader.strategy.live.LiveStrategyRunner;
import nl.jimkaplan.autotrader.strategy.model.CandleInterval;
import nl.jimkaplan.autotrader.strategy.model.document.LiveOrder;
import nl.jimkaplan.autotrader.strategy.model.document.LivePosition;
import nl.jimkaplan.autotrader.strategy.model.document.LiveStrategy;
import nl.jimkaplan.autotrader.strategy.rule.LogicalOperator;
import nl.jimkaplan.autotrader.strategy.rule.MacdParameters;
import nl.jimkaplan.autotrader.strategy.rule.ParabolicSarParameters;
import nl.jimkaplan.autotrader.strategy.rule.RuleCondition;
import nl.jimkaplan.autotrader.strategy.rule.RuleGroup;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Creates live strategies, starts and stops their runners, and reads their orders back.
 *
 * <p>A created strategy is not running: {@link #start} connects it, records {@code enabled}, and a
 * restart of the application starts whatever is enabled again. {@link #stop} disconnects it and
 * clears the flag.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LiveStrategyService {

    private final LiveStrategyRepository liveStrategyRepository;
    private final LiveOrderRepository liveOrderRepository;
    private final LivePositionRepository livePositionRepository;
    private final CandleHistoryService candleHistoryService;
    private final LiveTradingService liveTradingService;
    private final LiveCloseService liveCloseService;
    private final LiveAlertService alerts;
    private final BitvavoCredentialsService credentialsService;
    private final CandleStreamFactory candleStreamFactory;
    private final ConcurrentMap<String, LiveStrategyRunner> runners = new ConcurrentHashMap<>();

    /**
     * Create a strategy from a request, applying the documented defaults.
     *
     * @param request The request; market and interval are required
     * @return The stored strategy, not yet running
     * @throws IllegalArgumentException if the request is incomplete or invalid
     */
    public LiveStrategy create(LiveStrategyRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        String name = required("name", request.getName());
        String market = MarketFormat.toMarket(required("market", request.getMarket()));
        MarketFormat.requireEuroQuoted(market);
        CandleInterval interval = CandleInterval.fromCode(required("interval", request.getInterval()));
        double amountPerBuy = request.getAmountPerBuy() == null
                ? BacktestDefaults.AMOUNT_PER_BUY
                : request.getAmountPerBuy();
        requireMinimumAmount(amountPerBuy);
        boolean dryRun = request.getDryRun() == null || request.getDryRun();
        if (!dryRun && !credentialsService.configured()) {
            throw new IllegalArgumentException(
                    "no Bitvavo API key is configured: store it on the settings page");
        }
        MacdParameters macd = macdParameters(request);
        ParabolicSarParameters sar = sarParameters(request);
        RuleGroup entry = ruleGroup("entry", request.getEntryRules(), BacktestDefaults.ENTRY_RULES);
        RuleGroup exit = ruleGroup("exit", request.getExitRules(), BacktestDefaults.EXIT_RULES);
        int warmUpCandles = request.getWarmUpCandles() == null
                ? BacktestDefaults.WARM_UP_CANDLES
                : request.getWarmUpCandles();
        if (warmUpCandles < 0) {
            throw new IllegalArgumentException("the warm-up candles must not be negative");
        }
        LiveStrategy strategy = LiveStrategy.builder()
                .name(name)
                .market(market)
                .interval(interval.getCode())
                .macdFastPeriod(macd.fastPeriod())
                .macdSlowPeriod(macd.slowPeriod())
                .macdSignalPeriod(macd.signalPeriod())
                .sarAcceleration(sar.acceleration())
                .sarMaximum(sar.maximum())
                .entryOperator(entry.operator().name())
                .entryConditions(entry.conditions().stream().map(Enum::name).toList())
                .exitOperator(exit.operator().name())
                .exitConditions(exit.conditions().stream().map(Enum::name).toList())
                .amountPerBuy(amountPerBuy)
                .warmUpCandles(warmUpCandles)
                .dryRun(dryRun)
                .enabled(false)
                .archived(false)
                .lastEvaluatedOpenTime(0)
                .createdTime(Instant.now())
                .build();
        return liveStrategyRepository.save(strategy);
    }

    /**
     * Update a stopped strategy in place. The pair and interval stay changeable until the first
     * start; after that they are fixed because the candle series anchor belongs to them. Dry-run
     * is chosen at creation and never switched here: a real strategy is a separate copy.
     *
     * @param id      The strategy ID
     * @param request The new values; null fields keep the stored value
     * @return The stored strategy
     * @throws IllegalArgumentException if the strategy is running, archived, or a value is invalid
     * @throws NoSuchElementException   if the strategy does not exist
     */
    public synchronized LiveStrategy update(String id, LiveStrategyRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        LiveStrategy strategy = require(id);
        if (strategy.isArchived()) {
            throw new IllegalArgumentException("the strategy is archived");
        }
        if (runners.containsKey(id)) {
            throw new IllegalArgumentException("stop the strategy before editing it");
        }
        String name = required("name",
                request.getName() == null ? strategy.getName() : request.getName());
        boolean started = strategy.hasStarted();

        String market = strategy.getMarket();
        if (request.getMarket() != null && !request.getMarket().isBlank()) {
            String requested = MarketFormat.toMarket(request.getMarket());
            MarketFormat.requireEuroQuoted(requested);
            if (!requested.equals(strategy.getMarket())) {
                if (started) {
                    throw new IllegalArgumentException("the market is fixed after the first start; "
                            + "duplicate the strategy to trade another market");
                }
                market = requested;
            }
        }
        CandleInterval interval = CandleInterval.fromCode(strategy.getInterval());
        if (request.getInterval() != null && !request.getInterval().isBlank()) {
            CandleInterval requested = CandleInterval.fromCode(request.getInterval());
            if (!requested.equals(interval)) {
                if (started) {
                    throw new IllegalArgumentException("the interval is fixed after the first start; "
                            + "duplicate the strategy to use another interval");
                }
                interval = requested;
            }
        }
        if (request.getDryRun() != null && request.getDryRun() != strategy.isDryRun()) {
            throw new IllegalArgumentException(
                    "dry-run is chosen when the strategy is created; create a real copy instead");
        }

        double amountPerBuy = request.getAmountPerBuy() == null
                ? strategy.getAmountPerBuy() : request.getAmountPerBuy();
        requireMinimumAmount(amountPerBuy);
        int warmUpCandles = request.getWarmUpCandles() == null
                ? strategy.getWarmUpCandles() : request.getWarmUpCandles();
        if (warmUpCandles < 0) {
            throw new IllegalArgumentException("the warm-up candles must not be negative");
        }

        strategy.setName(name);
        strategy.setMarket(market);
        strategy.setInterval(interval.getCode());
        strategy.setAmountPerBuy(amountPerBuy);
        strategy.setWarmUpCandles(warmUpCandles);
        if (request.getMacdParameters() != null) {
            MacdParameters macd = macdParameters(request);
            strategy.setMacdFastPeriod(macd.fastPeriod());
            strategy.setMacdSlowPeriod(macd.slowPeriod());
            strategy.setMacdSignalPeriod(macd.signalPeriod());
        }
        if (request.getSarParameters() != null) {
            ParabolicSarParameters sar = sarParameters(request);
            strategy.setSarAcceleration(sar.acceleration());
            strategy.setSarMaximum(sar.maximum());
        }
        if (request.getEntryRules() != null) {
            RuleGroup entry = ruleGroup("entry", request.getEntryRules(), BacktestDefaults.ENTRY_RULES);
            strategy.setEntryOperator(entry.operator().name());
            strategy.setEntryConditions(entry.conditions().stream().map(Enum::name).toList());
        }
        if (request.getExitRules() != null) {
            RuleGroup exit = ruleGroup("exit", request.getExitRules(), BacktestDefaults.EXIT_RULES);
            strategy.setExitOperator(exit.operator().name());
            strategy.setExitConditions(exit.conditions().stream().map(Enum::name).toList());
        }
        return liveStrategyRepository.save(strategy);
    }

    /**
     * Delete a strategy that never started and has no orders or positions, or archive one that has
     * run or has either, so its recorded state survives for the history tab.
     *
     * @param id The strategy ID
     * @return {@code true} when the strategy was archived, {@code false} when it was deleted
     * @throws IllegalArgumentException if the strategy is running or still holds an open position
     * @throws NoSuchElementException   if the strategy does not exist
     */
    public synchronized boolean delete(String id) {
        LiveStrategy strategy = require(id);
        if (runners.containsKey(id)) {
            throw new IllegalArgumentException("stop the strategy before deleting it");
        }
        // Checked before the never-started branch: an old strategy can lack the start markers and
        // still own an open position. Deleting it would leave that position unreachable.
        if (livePositionRepository.findByStrategyIdAndStatus(id, "OPEN").isPresent()) {
            throw new IllegalArgumentException("close the open position before deleting or archiving it");
        }
        if (!strategy.hasStarted()
                && !liveOrderRepository.existsByStrategyId(id)
                && !livePositionRepository.existsByStrategyId(id)) {
            liveStrategyRepository.delete(strategy);
            log.info("Deleted live strategy {} (never started)", id);
            return false;
        }
        strategy.setArchived(true);
        liveStrategyRepository.save(strategy);
        log.info("Archived live strategy {} (it has history, so its records stay)", id);
        return true;
    }

    /**
     * @return All live strategies, including archived ones
     */
    public List<LiveStrategy> all() {
        return liveStrategyRepository.findAll();
    }

    /**
     * @return The strategies the live page and the forms offer: everything not archived
     */
    public List<LiveStrategy> active() {
        return liveStrategyRepository.findActive();
    }

    /**
     * @param id The strategy ID
     * @return The strategy if it exists
     */
    public Optional<LiveStrategy> get(String id) {
        return liveStrategyRepository.findById(id);
    }

    /**
     * A strategy's orders, newest first.
     *
     * @param id The strategy ID
     * @return The orders
     * @throws NoSuchElementException if the strategy does not exist
     */
    public List<LiveOrder> orders(String id) {
        require(id);
        return liveOrderRepository.findByStrategyIdOrderByCreatedTimeDesc(id);
    }

    /**
     * The confirmation view for closing a stopped strategy's open position.
     *
     * @param id The strategy ID
     * @return The close preview
     * @throws IllegalArgumentException when the strategy is running, archived, has no open position
     *                                  or has an order awaiting reconciliation
     * @throws NoSuchElementException   if the strategy does not exist
     */
    public synchronized LiveCloseView closePreview(String id) {
        LiveStrategy strategy = require(id);
        LivePosition position = requireCloseable(strategy, "close");
        return liveCloseService.preview(strategy, position);
    }

    /**
     * Close a stopped strategy's open position: a simulated sell for a dry-run strategy, a market
     * sell for a real one.
     *
     * @param id The strategy ID
     * @return What happened
     * @throws IllegalArgumentException when the strategy is running, archived, has no open position
     *                                  or has an order awaiting reconciliation
     * @throws NoSuchElementException   if the strategy does not exist
     */
    public synchronized LiveCloseResult close(String id) {
        LiveStrategy strategy = require(id);
        requireCloseable(strategy, "close");
        return liveCloseService.close(strategy);
    }

    /**
     * The exchange sells that may have manually closed a real strategy's open position.
     *
     * @param id The strategy ID
     * @return The position and its external-close candidates
     * @throws IllegalArgumentException when the strategy is dry-run, running, archived, has no open
     *                                  position or has an order awaiting reconciliation
     * @throws NoSuchElementException   if the strategy does not exist
     */
    public synchronized ReconcileView reconcileView(String id) {
        LiveStrategy strategy = require(id);
        LivePosition position = requireCloseable(strategy, "reconcile");
        requireRealPosition(strategy);
        return liveCloseService.reconcileView(strategy, position);
    }

    /**
     * Attach a manual exchange sell to a real strategy's open position and close the position at
     * the exchange's fill.
     *
     * @param id      The strategy ID
     * @param orderId The exchange order id
     * @return The recorded external row
     * @throws IllegalArgumentException when the strategy is dry-run, running, archived, has no open
     *                                  position or has an order awaiting reconciliation, or when
     *                                  the exchange sell does not fit
     * @throws NoSuchElementException   if the strategy does not exist
     */
    public synchronized LiveOrder reconcileExternalClose(String id, String orderId) {
        LiveStrategy strategy = require(id);
        LivePosition position = requireCloseable(strategy, "reconcile");
        requireRealPosition(strategy);
        return liveCloseService.reconcile(strategy, position, orderId);
    }

    private LivePosition requireCloseable(LiveStrategy strategy, String action) {
        if (strategy.isArchived()) {
            throw new IllegalArgumentException("the strategy is archived");
        }
        if (runners.containsKey(strategy.getId())) {
            throw new IllegalArgumentException("stop the strategy before "
                    + ("close".equals(action) ? "closing its position" : "reconciling a close"));
        }
        if (liveOrderRepository.existsByStrategyIdAndAwaitingReconciliationTrue(strategy.getId())) {
            throw new IllegalArgumentException("a real order is awaiting reconciliation; restart the "
                    + "application after it settles before "
                    + ("close".equals(action) ? "closing" : "reconciling"));
        }
        LivePosition position = livePositionRepository.findByStrategyIdAndStatus(strategy.getId(), "OPEN")
                .orElseThrow(() -> new IllegalArgumentException("there is no open position to " + action));
        boolean filledSellNotRecorded = liveOrderRepository
                .existsByStrategyIdAndDryRunFalseAndSideAndStatusAndSignalOpenTimeGreaterThanEqual(
                        strategy.getId(), "SELL", "FILLED", position.getEntryTime());
        if (filledSellNotRecorded) {
            throw new IllegalArgumentException("the exchange filled a sell for this open position but "
                    + "the position record is not updated; restart the application so reconciliation "
                    + "closes it before another close");
        }
        return position;
    }

    private static void requireRealPosition(LiveStrategy strategy) {
        if (strategy.isDryRun()) {
            throw new IllegalArgumentException(
                    "a dry-run position has no exchange order to reconcile; close it in the app instead");
        }
    }

    /**
     * Start (or keep) a strategy running. Lifecycle transitions are serialized: two concurrent
     * starts must never create two runners for one strategy, both of which would trade it. The
     * runner's start seeds the history with blocking I/O, so another strategy's start or stop waits
     * for that moment — acceptable for the handful of strategies this engine runs.
     *
     * @param id The strategy ID
     * @return The stored strategy with {@code enabled} set
     * @throws NoSuchElementException if the strategy does not exist
     */
    public synchronized LiveStrategy start(String id) {
        LiveStrategy strategy = require(id);
        if (strategy.isArchived()) {
            throw new IllegalArgumentException("the strategy is archived");
        }
        if (runners.containsKey(id)) {
            return strategy;
        }
        CandleInterval interval = CandleInterval.fromCode(strategy.getInterval());
        LiveStrategyRunner runner = new LiveStrategyRunner(strategy, interval, candleHistoryService,
                liveTradingService, liveStrategyRepository, candleStreamFactory);
        // Only touch enabled once the runner exists, and only this field, so a concurrent
        // last-evaluated update or stop can never be clobbered by a stale copy.
        liveStrategyRepository.updateEnabled(id, true);
        strategy.setEnabled(true);
        Instant started = Instant.now();
        liveStrategyRepository.updateLastStartedTime(id, started);
        strategy.setLastStartedTime(started);
        runners.put(id, runner);
        runner.start();
        log.info("Live strategy {} started ({} {}, dry-run={})", id, strategy.getMarket(),
                strategy.getInterval(), strategy.isDryRun());
        return strategy;
    }

    /**
     * Stop a strategy and clear its enabled flag.
     *
     * @param id The strategy ID
     * @return The stored strategy with {@code enabled} cleared
     * @throws NoSuchElementException if the strategy does not exist
     */
    public synchronized LiveStrategy stop(String id) {
        LiveStrategy strategy = require(id);
        LiveStrategyRunner runner = runners.remove(id);
        if (runner != null) {
            runner.stop();
        }
        liveStrategyRepository.updateEnabled(id, false);
        strategy.setEnabled(false);
        log.info("Live strategy {} stopped", id);
        return strategy;
    }

    /**
     * @param id The strategy ID
     * @return Whether the engine has a runner for the strategy
     */
    public boolean isRunning(String id) {
        return runners.containsKey(id);
    }

    /**
     * @param id The strategy ID
     * @return Whether the strategy's candle stream subscription is up
     */
    public boolean isConnected(String id) {
        LiveStrategyRunner runner = runners.get(id);
        return runner != null && runner.isConnected();
    }

    /**
     * @param id The strategy ID
     * @return The stream's last error message, or null when there is none
     */
    public String lastError(String id) {
        LiveStrategyRunner runner = runners.get(id);
        return runner == null ? null : runner.getLastError();
    }

    /**
     * Start every enabled strategy whose reconciliation did not fail. A strategy whose state could
     * not be reconciled is logged and held back — starting it on stale state is worse than not
     * starting it — while failures to start one strategy never stop the others.
     *
     * @param failedReconciliation The ids of strategies whose reconciliation failed
     */
    public void startEnabled(Collection<String> failedReconciliation) {
        for (LiveStrategy strategy : liveStrategyRepository.findActiveEnabled()) {
            if (failedReconciliation.contains(strategy.getId())) {
                log.error("Live strategy {} not started: reconciliation failed; restart after fixing",
                        strategy.getId());
                alerts.reconciliationHeldBack(strategy);
                continue;
            }
            try {
                start(strategy.getId());
            } catch (RuntimeException ex) {
                log.error("Could not start live strategy {}: {}", strategy.getId(), ex.getMessage(), ex);
            }
        }
    }

    private LiveStrategy require(String id) {
        return liveStrategyRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Live strategy not found: " + id));
    }

    private static MacdParameters macdParameters(LiveStrategyRequest request) {
        if (request.getMacdParameters() == null) {
            return MacdParameters.defaults();
        }
        return new MacdParameters(request.getMacdParameters().fastPeriod(),
                request.getMacdParameters().slowPeriod(), request.getMacdParameters().signalPeriod());
    }

    private static ParabolicSarParameters sarParameters(LiveStrategyRequest request) {
        if (request.getSarParameters() == null) {
            return ParabolicSarParameters.defaults();
        }
        return new ParabolicSarParameters(request.getSarParameters().acceleration(),
                request.getSarParameters().maximum());
    }

    private static RuleGroup ruleGroup(String name, RuleSetRequest request, RuleGroup defaults) {
        if (request == null) {
            return defaults;
        }
        if (request.conditions() == null || request.conditions().isEmpty()) {
            throw new IllegalArgumentException("select at least one " + name + " condition");
        }
        LogicalOperator operator = request.operator() == null ? LogicalOperator.ALL : request.operator();
        return new RuleGroup(operator, request.conditions());
    }

    private static void requireMinimumAmount(double amountPerBuy) {
        if (amountPerBuy < LiveTradingService.MINIMUM_ORDER_EUR) {
            throw new IllegalArgumentException("the amount per buy must be at least "
                    + LiveTradingService.MINIMUM_ORDER_EUR + " EUR");
        }
    }

    private static String required(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("the " + name + " is required");
        }
        return value.trim();
    }
}
