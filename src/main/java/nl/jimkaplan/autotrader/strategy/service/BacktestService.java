package nl.jimkaplan.autotrader.strategy.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.jimkaplan.autotrader.bitvavo.MarketFormat;
import nl.jimkaplan.autotrader.repository.BacktestRunRepository;
import nl.jimkaplan.autotrader.strategy.backtest.BacktestConfig;
import nl.jimkaplan.autotrader.strategy.backtest.BacktestDefaults;
import nl.jimkaplan.autotrader.strategy.backtest.BacktestEntryTrades;
import nl.jimkaplan.autotrader.strategy.backtest.BacktestEvaluation;
import nl.jimkaplan.autotrader.strategy.backtest.BacktestMetrics;
import nl.jimkaplan.autotrader.strategy.backtest.BacktestSplit;
import nl.jimkaplan.autotrader.strategy.backtest.BacktestSweep;
import nl.jimkaplan.autotrader.strategy.backtest.SweepDefinition;
import nl.jimkaplan.autotrader.strategy.backtest.SweepEntry;
import nl.jimkaplan.autotrader.strategy.backtest.SweepResult;
import nl.jimkaplan.autotrader.strategy.controller.BacktestRunRequest;
import nl.jimkaplan.autotrader.strategy.controller.MacdParametersRequest;
import nl.jimkaplan.autotrader.strategy.controller.RuleSetRequest;
import nl.jimkaplan.autotrader.strategy.controller.SarParametersRequest;
import nl.jimkaplan.autotrader.strategy.model.CandleInterval;
import nl.jimkaplan.autotrader.strategy.model.document.BacktestRun;
import nl.jimkaplan.autotrader.strategy.model.document.BacktestRunEntry;
import nl.jimkaplan.autotrader.strategy.model.document.BacktestRunMetrics;
import nl.jimkaplan.autotrader.strategy.model.document.Candle;
import nl.jimkaplan.autotrader.strategy.rule.CandleSeries;
import nl.jimkaplan.autotrader.strategy.rule.IndicatorSeries;
import nl.jimkaplan.autotrader.strategy.rule.LogicalOperator;
import nl.jimkaplan.autotrader.strategy.rule.MacdParameters;
import nl.jimkaplan.autotrader.strategy.rule.ParabolicSarParameters;
import nl.jimkaplan.autotrader.strategy.rule.RuleCondition;
import nl.jimkaplan.autotrader.strategy.rule.RuleGroup;
import nl.jimkaplan.autotrader.strategy.rule.StrategyEvaluator;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Runs backtest sweeps over stored candles and persists the ranked outcome, and reads stored runs
 * back. The sweep itself is {@link BacktestSweep}; this service resolves the request's optional
 * fields to their documented defaults, loads the candles and stores the result.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BacktestService {

    /**
     * Only the best entries are stored per run; the full ranked list can be thousands long.
     */
    static final int STORED_ENTRIES = 50;

    /**
     * Upper bound for the bars one request may cover: the sweep's cost scales with the range as
     * well as with the combination count, so a synchronous run needs a ceiling on both.
     */
    static final long MAX_CANDLES = 50_000;

    private final CandleHistoryService candleHistoryService;
    private final BacktestRunRepository backtestRunRepository;

    /**
     * Ensure the requested range is stored, sweep it and persist the ranked outcome.
     *
     * @param request The sweep request
     * @return The stored run
     * @throws IllegalArgumentException if the request is incomplete or the range has no candles
     */
    public BacktestRun run(BacktestRunRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        CandleInterval interval = CandleInterval.fromCode(requiredText("interval", request.getInterval()));
        String market = MarketFormat.toMarket(requiredText("market", request.getMarket()));
        if (!market.endsWith("-EUR")) {
            // The engine sizes every buy in euro and charges the euro fee the defaults name, so a
            // market quoted in anything else would make the run's figures meaningless.
            throw new IllegalArgumentException("Only EUR-quoted markets are supported: " + market);
        }
        long from = required("fromDate", request.getFromDate());
        long to = required("toDate", request.getToDate());
        if (from >= to) {
            throw new IllegalArgumentException("fromDate must be before toDate");
        }
        long span = to - from;
        if (span < 0) {
            // The timestamps span more than a long can measure: no run may cover that.
            throw new IllegalArgumentException("the range is longer than the maximum of " + MAX_CANDLES
                    + " candles");
        }
        long estimatedCandles = span / interval.getMillis() + 1;
        if (estimatedCandles > MAX_CANDLES) {
            throw new IllegalArgumentException("the range holds about " + estimatedCandles
                    + " candles; the maximum is " + MAX_CANDLES);
        }
        BacktestConfig config = config(request);
        SweepDefinition definition = definition(request);

        candleHistoryService.ensureRange(market, interval, Instant.ofEpochMilli(from), Instant.ofEpochMilli(to));
        List<Candle> candles = candleHistoryService.getCandles(market, interval,
                Instant.ofEpochMilli(from), Instant.ofEpochMilli(to));
        if (candles.isEmpty()) {
            throw new IllegalArgumentException("no candles for the requested range");
        }

        CandleSeries series = CandleSeries.of(candles);
        SweepResult result = BacktestSweep.run(series, definition, config);
        log.info("Evaluated {} combinations over {} {} bars of {}; best train return {}%",
                result.combinationsEvaluated(), series.length(), interval.getCode(), market,
                result.ranked().isEmpty() ? 0 : result.ranked().get(0).train().totalReturnPercent());

        BacktestRun run = BacktestRun.builder()
                .runAt(Instant.now())
                .market(market)
                .interval(interval.getCode())
                .fromTime(from)
                .toTime(to)
                .candleCount(series.length())
                .initialCapital(config.initialCapital())
                .amountPerBuy(config.amountPerBuy())
                .feeRate(config.feeRate())
                .warmUpCandles(config.warmUpCandles())
                .trainRatio(definition.trainRatio())
                .rankingMetric(definition.rankingMetric().name())
                .combinationsEvaluated(result.combinationsEvaluated())
                .entries(result.ranked().stream().limit(STORED_ENTRIES).map(BacktestService::toEntry).toList())
                .build();
        return backtestRunRepository.save(run);
    }

    /**
     * Read a stored run.
     *
     * @param id The run ID
     * @return The run if it exists
     */
    public Optional<BacktestRun> get(String id) {
        return backtestRunRepository.findById(id);
    }

    /**
     * The most recent runs, newest first, without their ranked entries.
     *
     * @param limit Maximum number of runs to return
     * @return The runs
     */
    public List<BacktestRun> recent(int limit) {
        return backtestRunRepository.findSummaries(
                PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "runAt")));
    }

    /**
     * Re-simulate one stored entry and return its trades and skipped signals.
     *
     * <p>A stored run keeps only metrics. Re-simulating a single combination over the same candles
     * with the same rules and split is deterministic and cheap, while stored trades for up to
     * fifty entries could outgrow MongoDB's document limit, so the trades are recomputed on
     * demand. The result reproduces the stored metrics as long as the candle history inside the
     * run's range has not changed since the run; the returned value carries the store's current
     * bar count and whether it differs, so callers can say so instead of only logging it. Runs are
     * read in full, so their entries are present.</p>
     *
     * @param id          The run ID
     * @param entryNumber The entry's number in the ranked table, starting at 1
     * @return The run, the entry's recomputed results and the history comparison, or empty when
     *         the run does not exist
     * @throws IllegalArgumentException if the entry number is out of range
     * @throws IllegalStateException    if the run's stored settings no longer resolve, or the
     *                                  candles for its range are no longer stored
     */
    public Optional<BacktestEntryTrades> trades(String id, int entryNumber) {
        Optional<BacktestRun> storedRun = backtestRunRepository.findById(id);
        if (storedRun.isEmpty()) {
            return Optional.empty();
        }
        BacktestRun run = storedRun.get();
        if (entryNumber < 1 || entryNumber > run.getEntries().size()) {
            throw new IllegalArgumentException("Entry " + entryNumber + " does not exist; valid entries are 1 to "
                    + run.getEntries().size());
        }
        BacktestRunEntry entry = run.getEntries().get(entryNumber - 1);
        CandleInterval interval;
        RuleGroup entryRules;
        RuleGroup exitRules;
        try {
            interval = CandleInterval.fromCode(run.getInterval());
            entryRules = storedRuleGroup(entry.getEntryOperator(), entry.getEntryConditions());
            exitRules = storedRuleGroup(entry.getExitOperator(), entry.getExitConditions());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("The stored run's interval or rule names no longer resolve", ex);
        }
        candleHistoryService.ensureRange(run.getMarket(), interval,
                Instant.ofEpochMilli(run.getFromTime()), Instant.ofEpochMilli(run.getToTime()));
        List<Candle> candles = candleHistoryService.getCandles(run.getMarket(), interval,
                Instant.ofEpochMilli(run.getFromTime()), Instant.ofEpochMilli(run.getToTime()));
        if (candles.isEmpty()) {
            throw new IllegalStateException("No candles are stored for the run's range");
        }
        boolean historyChanged = candles.size() != run.getCandleCount();
        if (historyChanged) {
            // The request-supplied run id stays out of the log line (CRLF-injection hygiene)
            log.warn("The stored {} {} history changed: {} bars now, {} recorded by the run; recomputing "
                            + "from the current candles",
                    run.getMarket(), interval.getCode(), candles.size(), run.getCandleCount());
        }

        CandleSeries series = CandleSeries.of(candles);
        MacdParameters macd = new MacdParameters(entry.getFastPeriod(), entry.getSlowPeriod(),
                entry.getSignalPeriod());
        ParabolicSarParameters sar = new ParabolicSarParameters(entry.getAcceleration(), entry.getMaximum());
        BacktestConfig config = new BacktestConfig(run.getInitialCapital(), run.getAmountPerBuy(),
                run.getFeeRate(), run.getWarmUpCandles());
        IndicatorSeries indicators = IndicatorSeries.of(series, macd, sar);
        boolean[] entrySignals = StrategyEvaluator.evaluate(entryRules, series, indicators);
        boolean[] exitSignals = StrategyEvaluator.evaluate(exitRules, series, indicators);
        BacktestEvaluation evaluation = BacktestSplit.of(series, config, run.getTrainRatio())
                .evaluate(entrySignals, exitSignals);
        return Optional.of(new BacktestEntryTrades(run, entryNumber, candles.size(), historyChanged, evaluation));
    }

    private static RuleGroup storedRuleGroup(String operator, List<String> conditions) {
        return new RuleGroup(LogicalOperator.valueOf(operator),
                conditions.stream().map(RuleCondition::valueOf).toList());
    }

    private static BacktestConfig config(BacktestRunRequest request) {
        return new BacktestConfig(
                orDefault(request.getInitialCapital(), BacktestDefaults.INITIAL_CAPITAL),
                orDefault(request.getAmountPerBuy(), BacktestDefaults.AMOUNT_PER_BUY),
                orDefault(request.getFeeRate(), BacktestDefaults.FEE_RATE),
                orDefault(request.getWarmUpCandles(), BacktestDefaults.WARM_UP_CANDLES));
    }

    private static SweepDefinition definition(BacktestRunRequest request) {
        return new SweepDefinition(
                macdParameters(request.getMacdParameters()),
                sarParameters(request.getSarParameters()),
                ruleGroups(request.getEntryRules(), BacktestDefaults.ENTRY_RULES),
                ruleGroups(request.getExitRules(), BacktestDefaults.EXIT_RULES),
                orDefault(request.getTrainRatio(), BacktestDefaults.TRAIN_RATIO),
                request.getRankingMetric() == null ? BacktestDefaults.RANKING_METRIC : request.getRankingMetric());
    }

    private static List<MacdParameters> macdParameters(List<MacdParametersRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return List.of(MacdParameters.defaults());
        }
        return requests.stream()
                .map(candidate -> new MacdParameters(candidate.fastPeriod(), candidate.slowPeriod(),
                        candidate.signalPeriod()))
                .toList();
    }

    private static List<ParabolicSarParameters> sarParameters(List<SarParametersRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return List.of(ParabolicSarParameters.defaults());
        }
        return requests.stream()
                .map(candidate -> new ParabolicSarParameters(candidate.acceleration(), candidate.maximum()))
                .toList();
    }

    private static List<RuleGroup> ruleGroups(List<RuleSetRequest> requests, RuleGroup defaults) {
        if (requests == null || requests.isEmpty()) {
            return List.of(defaults);
        }
        return requests.stream().map(BacktestService::ruleGroup).toList();
    }

    private static RuleGroup ruleGroup(RuleSetRequest request) {
        if (request.conditions() == null || request.conditions().isEmpty()) {
            throw new IllegalArgumentException("each rule set needs at least one condition");
        }
        LogicalOperator operator = request.operator() == null ? LogicalOperator.ALL : request.operator();
        return new RuleGroup(operator, request.conditions());
    }

    private static double orDefault(Double value, double fallback) {
        return value == null ? fallback : value;
    }

    private static int orDefault(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private static String requiredText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static long required(String name, Long value) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        return value;
    }

    private static BacktestRunEntry toEntry(SweepEntry entry) {
        return BacktestRunEntry.builder()
                .fastPeriod(entry.macd().fastPeriod())
                .slowPeriod(entry.macd().slowPeriod())
                .signalPeriod(entry.macd().signalPeriod())
                .acceleration(entry.sar().acceleration())
                .maximum(entry.sar().maximum())
                .entryOperator(entry.entryRules().operator().name())
                .entryConditions(entry.entryRules().conditions().stream().map(Enum::name).toList())
                .exitOperator(entry.exitRules().operator().name())
                .exitConditions(entry.exitRules().conditions().stream().map(Enum::name).toList())
                .train(toMetrics(entry.train()))
                .validate(toMetrics(entry.validate()))
                .build();
    }

    private static BacktestRunMetrics toMetrics(BacktestMetrics metrics) {
        return BacktestRunMetrics.builder()
                .finalEquity(metrics.finalEquity())
                .totalReturnPercent(metrics.totalReturnPercent())
                .maxDrawdownPercent(metrics.maxDrawdownPercent())
                .tradeCount(metrics.tradeCount())
                .winRatePercent(metrics.winRatePercent())
                .profitFactor(metrics.profitFactor())
                .skippedSignals(metrics.skippedSignals())
                .build();
    }
}
