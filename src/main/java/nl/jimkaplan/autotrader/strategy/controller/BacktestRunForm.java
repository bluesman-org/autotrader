package nl.jimkaplan.autotrader.strategy.controller;

import lombok.Data;
import nl.jimkaplan.autotrader.strategy.backtest.BacktestDefaults;
import nl.jimkaplan.autotrader.strategy.backtest.RankingMetric;
import nl.jimkaplan.autotrader.strategy.model.CandleInterval;
import nl.jimkaplan.autotrader.strategy.rule.LogicalOperator;
import nl.jimkaplan.autotrader.strategy.rule.MacdParameters;
import nl.jimkaplan.autotrader.strategy.rule.ParabolicSarParameters;
import nl.jimkaplan.autotrader.strategy.rule.RuleCondition;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The backtest form's fields for the in-app UI. Every field is a string so that a typo surfaces
 * as an {@link IllegalArgumentException} message the page can show, rather than a binding error.
 *
 * <p>The prefill is derived from {@link BacktestDefaults} and the parameter records' own defaults,
 * so the form can never promise a value the engine would not use.</p>
 */
@Data
public class BacktestRunForm {

    private static final int DEFAULT_RANGE_DAYS = 100;

    private String market;
    private String interval;
    private String fromDate;
    private String toDate;
    private String initialCapital;
    private String amountPerBuy;
    private String feeRate;
    private String warmUpCandles;
    private String trainRatio;
    private String rankingMetric;
    private String macdCandidates;
    private String sarCandidates;
    private String entryOperator;
    private String exitOperator;
    private List<String> entryConditions = new ArrayList<>();
    private List<String> exitConditions = new ArrayList<>();

    /**
     * A form prefilled with the engine's documented defaults, over the last 100 days.
     *
     * @return The prefilled form
     */
    public static BacktestRunForm defaults() {
        BacktestRunForm form = new BacktestRunForm();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        form.setMarket("BTC-EUR");
        form.setInterval(CandleInterval.ONE_HOUR.getCode());
        form.setFromDate(today.minusDays(DEFAULT_RANGE_DAYS).toString());
        form.setToDate(today.toString());
        form.setInitialCapital(plain(BacktestDefaults.INITIAL_CAPITAL));
        form.setAmountPerBuy(plain(BacktestDefaults.AMOUNT_PER_BUY));
        form.setFeeRate(plain(BacktestDefaults.FEE_RATE));
        form.setWarmUpCandles(String.valueOf(BacktestDefaults.WARM_UP_CANDLES));
        form.setTrainRatio(plain(BacktestDefaults.TRAIN_RATIO));
        form.setRankingMetric(BacktestDefaults.RANKING_METRIC.name());
        form.setMacdCandidates(macdText(MacdParameters.defaults()));
        form.setSarCandidates(sarText(ParabolicSarParameters.defaults()));
        form.setEntryOperator(BacktestDefaults.ENTRY_RULES.operator().name());
        form.setExitOperator(BacktestDefaults.EXIT_RULES.operator().name());
        form.setEntryConditions(names(BacktestDefaults.ENTRY_RULES.conditions()));
        form.setExitConditions(names(BacktestDefaults.EXIT_RULES.conditions()));
        return form;
    }

    /**
     * Convert the form into an API request. Blank optional fields are left out, so the service
     * applies the same defaults as for the REST API. The picked end day is inclusive.
     *
     * @return The request to run
     * @throws IllegalArgumentException if a field is missing or malformed
     */
    public BacktestRunRequest toRequest() {
        BacktestRunRequest request = new BacktestRunRequest();
        request.setMarket(required("market", market));
        request.setInterval(required("interval", interval));
        request.setFromDate(startOfDay("from date", fromDate));
        request.setToDate(inclusiveEndOfDay("to date", toDate));
        request.setInitialCapital(optionalDouble("initial capital", initialCapital));
        request.setAmountPerBuy(optionalDouble("amount per buy", amountPerBuy));
        request.setFeeRate(optionalDouble("fee rate", feeRate));
        request.setWarmUpCandles(optionalInt("warm-up candles", warmUpCandles));
        request.setTrainRatio(optionalDouble("train ratio", trainRatio));
        if (rankingMetric != null && !rankingMetric.isBlank()) {
            request.setRankingMetric(parseEnum("ranking metric", RankingMetric.class, rankingMetric));
        }
        request.setMacdParameters(macdCandidates());
        request.setSarParameters(sarCandidates());
        request.setEntryRules(List.of(ruleSet("entry", entryOperator, entryConditions)));
        request.setExitRules(List.of(ruleSet("exit", exitOperator, exitConditions)));
        return request;
    }

    private List<MacdParametersRequest> macdCandidates() {
        if (macdCandidates == null || macdCandidates.isBlank()) {
            return null;
        }
        List<MacdParametersRequest> candidates = new ArrayList<>();
        for (String candidate : macdCandidates.split(",")) {
            String[] parts = candidate.trim().split("/");
            if (parts.length != 3) {
                throw new IllegalArgumentException("each MACD candidate reads fast/slow/signal, e.g. 12/26/9");
            }
            candidates.add(new MacdParametersRequest(
                    candidateInt("MACD fast period", parts[0]),
                    candidateInt("MACD slow period", parts[1]),
                    candidateInt("MACD signal period", parts[2])));
        }
        return candidates;
    }

    private List<SarParametersRequest> sarCandidates() {
        if (sarCandidates == null || sarCandidates.isBlank()) {
            return null;
        }
        List<SarParametersRequest> candidates = new ArrayList<>();
        for (String candidate : sarCandidates.split(",")) {
            String[] parts = candidate.trim().split("/");
            if (parts.length != 2) {
                throw new IllegalArgumentException(
                        "each Parabolic SAR candidate reads acceleration/maximum, e.g. 0.02/0.2");
            }
            candidates.add(new SarParametersRequest(
                    candidateDouble("SAR acceleration", parts[0]),
                    candidateDouble("SAR maximum", parts[1])));
        }
        return candidates;
    }

    private static RuleSetRequest ruleSet(String name, String operator, List<String> conditions) {
        if (conditions == null || conditions.isEmpty()) {
            throw new IllegalArgumentException("select at least one " + name + " condition");
        }
        List<RuleCondition> parsed = conditions.stream()
                .map(condition -> parseEnum("rule condition", RuleCondition.class, condition))
                .toList();
        LogicalOperator parsedOperator = operator == null || operator.isBlank()
                ? LogicalOperator.ALL
                : parseEnum("operator", LogicalOperator.class, operator);
        return new RuleSetRequest(parsedOperator, parsed);
    }

    private static String required(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("the " + name + " is required");
        }
        return value.trim();
    }

    private static Long startOfDay(String name, String value) {
        try {
            return LocalDate.parse(required(name, value))
                    .atStartOfDay(ZoneOffset.UTC)
                    .toInstant()
                    .toEpochMilli();
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("the " + name + " must be a date like 2026-10-11");
        }
    }

    /**
     * The range's end bound is exclusive, so an inclusive picked day becomes the start of the next.
     */
    private static Long inclusiveEndOfDay(String name, String value) {
        try {
            return LocalDate.parse(required(name, value))
                    .plusDays(1)
                    .atStartOfDay(ZoneOffset.UTC)
                    .toInstant()
                    .toEpochMilli();
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("the " + name + " must be a date like 2026-10-11");
        }
    }

    private static Double optionalDouble(String name, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return parseDouble(name, value);
    }

    private static Integer optionalInt(String name, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("the " + name + " must be a whole number");
        }
    }

    private static int candidateInt(String name, String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("the " + name + " must be a whole number");
        }
    }

    private static double candidateDouble(String name, String value) {
        return parseDouble(name, value);
    }

    /**
     * Parse a decimal, accepting the comma separator as well, so a locale keyboard works against a
     * dot-decimal form. The candidate grids keep dots: their list separator is the comma.
     */
    private static double parseDouble(String name, String value) {
        try {
            return Double.parseDouble(value.trim().replace(',', '.'));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("the " + name + " must be a number");
        }
    }

    private static <E extends Enum<E>> E parseEnum(String name, Class<E> type, String value) {
        try {
            return Enum.valueOf(type, value.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("unknown " + name + ": " + value);
        }
    }

    private static String plain(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    private static String macdText(MacdParameters parameters) {
        return parameters.fastPeriod() + "/" + parameters.slowPeriod() + "/" + parameters.signalPeriod();
    }

    private static String sarText(ParabolicSarParameters parameters) {
        return plain(parameters.acceleration()) + "/" + plain(parameters.maximum());
    }

    private static List<String> names(List<RuleCondition> conditions) {
        return conditions.stream().map(Enum::name).collect(Collectors.toCollection(ArrayList::new));
    }
}
