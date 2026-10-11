package nl.jimkaplan.autotrader.strategy.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.jimkaplan.autotrader.bitvavo.MarketFormat;
import nl.jimkaplan.autotrader.repository.LiveOrderRepository;
import nl.jimkaplan.autotrader.repository.LiveStrategyRepository;
import nl.jimkaplan.autotrader.service.BotConfigurationService;
import nl.jimkaplan.autotrader.strategy.backtest.BacktestDefaults;
import nl.jimkaplan.autotrader.strategy.controller.LiveStrategyRequest;
import nl.jimkaplan.autotrader.strategy.controller.RuleSetRequest;
import nl.jimkaplan.autotrader.strategy.live.CandleStreamFactory;
import nl.jimkaplan.autotrader.strategy.live.LiveStrategyRunner;
import nl.jimkaplan.autotrader.strategy.model.CandleInterval;
import nl.jimkaplan.autotrader.strategy.model.document.LiveOrder;
import nl.jimkaplan.autotrader.strategy.model.document.LiveStrategy;
import nl.jimkaplan.autotrader.strategy.rule.LogicalOperator;
import nl.jimkaplan.autotrader.strategy.rule.MacdParameters;
import nl.jimkaplan.autotrader.strategy.rule.ParabolicSarParameters;
import nl.jimkaplan.autotrader.strategy.rule.RuleCondition;
import nl.jimkaplan.autotrader.strategy.rule.RuleGroup;
import org.springframework.stereotype.Service;

import java.time.Instant;
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
    private final CandleHistoryService candleHistoryService;
    private final LiveTradingService liveTradingService;
    private final BotConfigurationService botConfigurationService;
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
        String market = MarketFormat.toMarket(required("market", request.getMarket()));
        CandleInterval interval = CandleInterval.fromCode(required("interval", request.getInterval()));
        double amountPerBuy = request.getAmountPerBuy() == null
                ? BacktestDefaults.AMOUNT_PER_BUY
                : request.getAmountPerBuy();
        if (amountPerBuy < LiveTradingService.MINIMUM_ORDER_EUR) {
            throw new IllegalArgumentException("the amount per buy must be at least "
                    + LiveTradingService.MINIMUM_ORDER_EUR + " EUR");
        }
        boolean dryRun = request.getDryRun() == null || request.getDryRun();
        if (!dryRun) {
            String botId = required("botId", request.getBotId());
            if (botConfigurationService.getBotConfiguration(botId).isEmpty()) {
                throw new IllegalArgumentException("no active bot configuration: " + botId);
            }
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
                .botId(request.getBotId())
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
                .lastEvaluatedOpenTime(0)
                .createdTime(Instant.now())
                .build();
        return liveStrategyRepository.save(strategy);
    }

    /**
     * @return All live strategies
     */
    public List<LiveStrategy> all() {
        return liveStrategyRepository.findAll();
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
     * Start (or keep) a strategy running.
     *
     * @param id The strategy ID
     * @return The stored strategy with {@code enabled} set
     * @throws NoSuchElementException if the strategy does not exist
     */
    public LiveStrategy start(String id) {
        LiveStrategy strategy = require(id);
        if (runners.containsKey(id)) {
            return strategy;
        }
        CandleInterval interval = CandleInterval.fromCode(strategy.getInterval());
        strategy.setEnabled(true);
        LiveStrategy stored = liveStrategyRepository.save(strategy);
        LiveStrategyRunner runner = new LiveStrategyRunner(stored, interval, candleHistoryService,
                liveTradingService, liveStrategyRepository, candleStreamFactory);
        runners.put(id, runner);
        runner.start();
        log.info("Live strategy {} started ({} {}, dry-run={})", id, stored.getMarket(),
                stored.getInterval(), stored.isDryRun());
        return stored;
    }

    /**
     * Stop a strategy and clear its enabled flag.
     *
     * @param id The strategy ID
     * @return The stored strategy with {@code enabled} cleared
     * @throws NoSuchElementException if the strategy does not exist
     */
    public LiveStrategy stop(String id) {
        LiveStrategy strategy = require(id);
        strategy.setEnabled(false);
        LiveStrategy stored = liveStrategyRepository.save(strategy);
        LiveStrategyRunner runner = runners.remove(id);
        if (runner != null) {
            runner.stop();
        }
        log.info("Live strategy {} stopped", id);
        return stored;
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
     * Start every enabled strategy; failures are logged, not thrown, so startup survives them.
     */
    public void startEnabled() {
        for (LiveStrategy strategy : liveStrategyRepository.findByEnabledTrue()) {
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

    private static String required(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("the " + name + " is required");
        }
        return value.trim();
    }
}
