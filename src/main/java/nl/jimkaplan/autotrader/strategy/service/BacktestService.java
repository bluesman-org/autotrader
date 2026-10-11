package nl.jimkaplan.autotrader.strategy.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.jimkaplan.autotrader.bitvavo.MarketFormat;
import nl.jimkaplan.autotrader.repository.BacktestRunRepository;
import nl.jimkaplan.autotrader.strategy.backtest.BacktestConfig;
import nl.jimkaplan.autotrader.strategy.backtest.BacktestMetrics;
import nl.jimkaplan.autotrader.strategy.backtest.BacktestSweep;
import nl.jimkaplan.autotrader.strategy.backtest.RankingMetric;
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
import nl.jimkaplan.autotrader.strategy.rule.LogicalOperator;
import nl.jimkaplan.autotrader.strategy.rule.MacdParameters;
import nl.jimkaplan.autotrader.strategy.rule.ParabolicSarParameters;
import nl.jimkaplan.autotrader.strategy.rule.RuleCondition;
import nl.jimkaplan.autotrader.strategy.rule.RuleGroup;
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

    private static final double DEFAULT_INITIAL_CAPITAL = 1000.0;
    private static final double DEFAULT_AMOUNT_PER_BUY = 100.0;
    private static final double DEFAULT_FEE_RATE = 0.002;
    private static final int DEFAULT_WARM_UP_CANDLES = 200;
    private static final double DEFAULT_TRAIN_RATIO = 0.7;
    private static final RuleGroup DEFAULT_ENTRY_RULES = new RuleGroup(LogicalOperator.ALL,
            List.of(RuleCondition.MACD_BULLISH_CROSSOVER));
    private static final RuleGroup DEFAULT_EXIT_RULES = new RuleGroup(LogicalOperator.ALL,
            List.of(RuleCondition.SAR_BEARISH_CROSSOVER));

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

    private static BacktestConfig config(BacktestRunRequest request) {
        return new BacktestConfig(
                orDefault(request.getInitialCapital(), DEFAULT_INITIAL_CAPITAL),
                orDefault(request.getAmountPerBuy(), DEFAULT_AMOUNT_PER_BUY),
                orDefault(request.getFeeRate(), DEFAULT_FEE_RATE),
                orDefault(request.getWarmUpCandles(), DEFAULT_WARM_UP_CANDLES));
    }

    private static SweepDefinition definition(BacktestRunRequest request) {
        return new SweepDefinition(
                macdParameters(request.getMacdParameters()),
                sarParameters(request.getSarParameters()),
                ruleGroups(request.getEntryRules(), DEFAULT_ENTRY_RULES),
                ruleGroups(request.getExitRules(), DEFAULT_EXIT_RULES),
                orDefault(request.getTrainRatio(), DEFAULT_TRAIN_RATIO),
                request.getRankingMetric() == null ? RankingMetric.TOTAL_RETURN : request.getRankingMetric());
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
                .build();
    }
}
