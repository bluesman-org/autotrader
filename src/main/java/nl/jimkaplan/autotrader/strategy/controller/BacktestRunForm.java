package nl.jimkaplan.autotrader.strategy.controller;

import lombok.Data;
import nl.jimkaplan.autotrader.strategy.backtest.RankingMetric;
import nl.jimkaplan.autotrader.strategy.model.CandleInterval;
import nl.jimkaplan.autotrader.strategy.rule.LogicalOperator;
import nl.jimkaplan.autotrader.strategy.rule.RuleCondition;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * The backtest form's fields for the in-app UI. Every field is a string so that a typo surfaces
 * as an {@link IllegalArgumentException} message the page can show, rather than a binding error.
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
        form.setInitialCapital("1000");
        form.setAmountPerBuy("100");
        form.setFeeRate("0.002");
        form.setWarmUpCandles("200");
        form.setTrainRatio("0.7");
        form.setRankingMetric(RankingMetric.TOTAL_RETURN.name());
        form.setMacdCandidates("12/26/9");
        form.setSarCandidates("0.02/0.2");
        form.setEntryOperator(LogicalOperator.ALL.name());
        form.setExitOperator(LogicalOperator.ALL.name());
        form.setEntryConditions(new ArrayList<>(List.of(RuleCondition.MACD_BULLISH_CROSSOVER.name())));
        form.setExitConditions(new ArrayList<>(List.of(RuleCondition.SAR_BEARISH_CROSSOVER.name())));
        return form;
    }

    /**
     * Convert the form into an API request. Blank optional fields are left out, so the service
     * applies the same defaults as for the REST API.
     *
     * @return The request to run
     * @throws IllegalArgumentException if a field is missing or malformed
     */
    public BacktestRunRequest toRequest() {
        BacktestRunRequest request = new BacktestRunRequest();
        request.setMarket(required("market", market));
        request.setInterval(required("interval", interval));
        request.setFromDate(toEpochMillis("from date", fromDate));
        request.setToDate(toEpochMillis("to date", toDate));
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

    private static Long toEpochMillis(String name, String value) {
        try {
            return LocalDate.parse(required(name, value))
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
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("the " + name + " must be a number");
        }
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
        try {
            return Double.parseDouble(value.trim());
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
}
