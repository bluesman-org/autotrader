package nl.jimkaplan.autotrader.strategy.controller;

import lombok.Data;
import nl.jimkaplan.autotrader.strategy.backtest.BacktestDefaults;
import nl.jimkaplan.autotrader.strategy.model.CandleInterval;
import nl.jimkaplan.autotrader.strategy.model.document.BacktestRun;
import nl.jimkaplan.autotrader.strategy.model.document.BacktestRunEntry;
import nl.jimkaplan.autotrader.strategy.model.document.LiveStrategy;
import nl.jimkaplan.autotrader.strategy.rule.LogicalOperator;
import nl.jimkaplan.autotrader.strategy.rule.MacdParameters;
import nl.jimkaplan.autotrader.strategy.rule.ParabolicSarParameters;
import nl.jimkaplan.autotrader.strategy.rule.RuleCondition;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The live strategy form's fields for the in-app UI. Numeric fields are strings so a typo surfaces
 * as an {@link IllegalArgumentException} message the page can show, rather than a binding error.
 *
 * <p>The create defaults come from the same {@link BacktestDefaults} and parameter records the
 * engine uses, so the form cannot promise a value the strategy would not get.</p>
 */
@Data
public class LiveStrategyForm {

    private String name;
    private String market;
    private String interval;
    private String amountPerBuy;
    private String warmUpCandles;
    private Boolean dryRun;
    private String macdFastPeriod;
    private String macdSlowPeriod;
    private String macdSignalPeriod;
    private String sarAcceleration;
    private String sarMaximum;
    private String entryOperator;
    private List<String> entryConditions = new ArrayList<>();
    private String exitOperator;
    private List<String> exitConditions = new ArrayList<>();

    /**
     * @return A form prefilled with the engine's documented defaults, dry-run on
     */
    public static LiveStrategyForm defaults() {
        LiveStrategyForm form = new LiveStrategyForm();
        MacdParameters macd = MacdParameters.defaults();
        ParabolicSarParameters sar = ParabolicSarParameters.defaults();
        form.setName("");
        form.setMarket("BTC-EUR");
        form.setInterval(CandleInterval.ONE_HOUR.getCode());
        form.setAmountPerBuy(plain(BacktestDefaults.AMOUNT_PER_BUY));
        form.setWarmUpCandles(String.valueOf(BacktestDefaults.WARM_UP_CANDLES));
        form.setDryRun(true);
        form.setMacdFastPeriod(String.valueOf(macd.fastPeriod()));
        form.setMacdSlowPeriod(String.valueOf(macd.slowPeriod()));
        form.setMacdSignalPeriod(String.valueOf(macd.signalPeriod()));
        form.setSarAcceleration(plain(sar.acceleration()));
        form.setSarMaximum(plain(sar.maximum()));
        form.setEntryOperator(BacktestDefaults.ENTRY_RULES.operator().name());
        form.setExitOperator(BacktestDefaults.EXIT_RULES.operator().name());
        form.setEntryConditions(names(BacktestDefaults.ENTRY_RULES.conditions()));
        form.setExitConditions(names(BacktestDefaults.EXIT_RULES.conditions()));
        return form;
    }

    /**
     * @param strategy The stored strategy
     * @return A form carrying its current values
     */
    public static LiveStrategyForm fromStrategy(LiveStrategy strategy) {
        LiveStrategyForm form = new LiveStrategyForm();
        form.setName(strategy.getName());
        form.setMarket(strategy.getMarket());
        form.setInterval(strategy.getInterval());
        form.setAmountPerBuy(plain(strategy.getAmountPerBuy()));
        form.setWarmUpCandles(String.valueOf(strategy.getWarmUpCandles()));
        form.setDryRun(strategy.isDryRun());
        form.setMacdFastPeriod(String.valueOf(strategy.getMacdFastPeriod()));
        form.setMacdSlowPeriod(String.valueOf(strategy.getMacdSlowPeriod()));
        form.setMacdSignalPeriod(String.valueOf(strategy.getMacdSignalPeriod()));
        form.setSarAcceleration(plain(strategy.getSarAcceleration()));
        form.setSarMaximum(plain(strategy.getSarMaximum()));
        form.setEntryOperator(strategy.getEntryOperator());
        form.setEntryConditions(new ArrayList<>(strategy.getEntryConditions()));
        form.setExitOperator(strategy.getExitOperator());
        form.setExitConditions(new ArrayList<>(strategy.getExitConditions()));
        return form;
    }

    /**
     * @param run   The stored backtest run
     * @param entry One of its ranked entries
     * @return A dry-run form prefilled with the run's data, parameters and rules
     */
    public static LiveStrategyForm fromBacktest(BacktestRun run, BacktestRunEntry entry) {
        LiveStrategyForm form = defaults();
        form.setMarket(run.getMarket());
        form.setInterval(run.getInterval());
        form.setAmountPerBuy(plain(run.getAmountPerBuy()));
        form.setWarmUpCandles(String.valueOf(run.getWarmUpCandles()));
        form.setMacdFastPeriod(String.valueOf(entry.getFastPeriod()));
        form.setMacdSlowPeriod(String.valueOf(entry.getSlowPeriod()));
        form.setMacdSignalPeriod(String.valueOf(entry.getSignalPeriod()));
        form.setSarAcceleration(plain(entry.getAcceleration()));
        form.setSarMaximum(plain(entry.getMaximum()));
        form.setEntryOperator(entry.getEntryOperator());
        form.setEntryConditions(new ArrayList<>(entry.getEntryConditions()));
        form.setExitOperator(entry.getExitOperator());
        form.setExitConditions(new ArrayList<>(entry.getExitConditions()));
        return form;
    }

    /**
     * Convert the form into an API request. Blank numeric fields are left out, so the service
     * applies the same defaults as for the REST API.
     *
     * @return The request to create or update
     * @throws IllegalArgumentException if a field is missing or malformed
     */
    public LiveStrategyRequest toRequest() {
        LiveStrategyRequest request = new LiveStrategyRequest();
        request.setName(required("name", name));
        request.setMarket(optionalText(market));
        request.setInterval(optionalText(interval));
        request.setAmountPerBuy(optionalDouble("amount per buy", amountPerBuy));
        request.setWarmUpCandles(optionalInt("warm-up candles", warmUpCandles));
        request.setDryRun(dryRun);
        request.setMacdParameters(macdRequest());
        request.setSarParameters(sarRequest());
        request.setEntryRules(ruleSet("entry", entryOperator, entryConditions));
        request.setExitRules(ruleSet("exit", exitOperator, exitConditions));
        return request;
    }

    private MacdParametersRequest macdRequest() {
        if (allBlank(macdFastPeriod, macdSlowPeriod, macdSignalPeriod)) {
            return null;
        }
        return new MacdParametersRequest(
                requiredInt("MACD fast period", macdFastPeriod),
                requiredInt("MACD slow period", macdSlowPeriod),
                requiredInt("MACD signal period", macdSignalPeriod));
    }

    private SarParametersRequest sarRequest() {
        if (allBlank(sarAcceleration, sarMaximum)) {
            return null;
        }
        return new SarParametersRequest(
                requiredDouble("SAR acceleration", sarAcceleration),
                requiredDouble("SAR maximum", sarMaximum));
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

    private static boolean allBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return false;
            }
        }
        return true;
    }

    private static String required(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("the " + name + " is required");
        }
        return value.trim();
    }

    private static String optionalText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static int requiredInt(String name, String value) {
        try {
            return Integer.parseInt(required(name, value));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("the " + name + " must be a whole number");
        }
    }

    private static double requiredDouble(String name, String value) {
        return parseDouble(name, required(name, value));
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

    private static List<String> names(List<RuleCondition> conditions) {
        return conditions.stream().map(Enum::name).collect(Collectors.toCollection(ArrayList::new));
    }
}
