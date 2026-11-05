package nl.jimkaplan.autotrader.strategy.rule;

import nl.jimkaplan.autotrader.strategy.indicator.MacdResult;
import nl.jimkaplan.autotrader.strategy.indicator.MacdState;
import nl.jimkaplan.autotrader.strategy.indicator.SarState;
import nl.jimkaplan.autotrader.strategy.model.document.Candle;

/**
 * Evaluates a strategy bar by bar instead of recomputing the whole series on every decision.
 *
 * <p>The indicator state streams (see {@link MacdState} and {@link SarState}), and the rules are
 * the same ones the backtester uses: each readiness check builds the two-bar window the conditions
 * look at — the bar being decided and its predecessor — and runs the existing evaluator on it. The
 * live engine still decides on the anchored series, but at a constant cost per candle instead of a
 * walk over the whole history.</p>
 */
public final class StrategyStream {

    private final StrategyDefinition definition;
    private final MacdState macd;
    private final SarState sar;

    private boolean hasPrevious;
    private double previousOpen;
    private double previousHigh;
    private double previousLow;
    private double previousClose;
    private long previousTime;
    private double open;
    private double high;
    private double low;
    private double close;
    private long time;

    private double macdValue = Double.NaN;
    private double signalValue = Double.NaN;
    private double histogramValue = Double.NaN;
    private double sarValue = Double.NaN;
    private double previousMacd = Double.NaN;
    private double previousSignal = Double.NaN;
    private double previousHistogram = Double.NaN;
    private double previousSar = Double.NaN;

    private int bars;

    /**
     * @param definition The strategy's parameters and rules
     */
    public StrategyStream(StrategyDefinition definition) {
        this.definition = definition;
        this.macd = new MacdState(definition.macd().fastPeriod(), definition.macd().slowPeriod(),
                definition.macd().signalPeriod());
        this.sar = new SarState(definition.sar().acceleration(), definition.sar().maximum());
    }

    /**
     * Feed one closed candle.
     *
     * @param candle The candle
     */
    public void accept(Candle candle) {
        // The candle's values are copied in: the stream must not hold the caller's mutable
        // document, and the rules only need the last two bars' numbers.
        hasPrevious = bars > 0;
        previousOpen = open;
        previousHigh = high;
        previousLow = low;
        previousClose = close;
        previousTime = time;
        open = candle.getOpen().doubleValue();
        high = candle.getHigh().doubleValue();
        low = candle.getLow().doubleValue();
        close = candle.getClose().doubleValue();
        time = candle.getOpenTime();
        previousMacd = macdValue;
        previousSignal = signalValue;
        previousHistogram = histogramValue;
        previousSar = sarValue;
        macd.accept(close);
        sar.accept(high, low);
        macdValue = macd.macd();
        signalValue = macd.signal();
        histogramValue = macd.histogram();
        sarValue = sar.value();
        bars++;
    }

    /**
     * @return The number of candles fed
     */
    public int bars() {
        return bars;
    }

    /**
     * @return Whether the entry rules fired on the last candle
     */
    public boolean entryFired() {
        return fired(definition.entryRules());
    }

    /**
     * @return Whether the exit rules fired on the last candle
     */
    public boolean exitFired() {
        return fired(definition.exitRules());
    }

    private boolean fired(RuleGroup rules) {
        if (bars < 2 || !hasPrevious) {
            return false;
        }
        // The conditions only look at the decided bar and the one before it, so the two-bar window
        // runs through the same evaluator the backtester uses; there is no second copy of the rules.
        CandleSeries window = new CandleSeries(
                new double[]{previousOpen, open},
                new double[]{previousHigh, high},
                new double[]{previousLow, low},
                new double[]{previousClose, close},
                new long[]{previousTime, time});
        IndicatorSeries indicators = new IndicatorSeries(
                new MacdResult(
                        new double[]{previousMacd, macdValue},
                        new double[]{previousSignal, signalValue},
                        new double[]{previousHistogram, histogramValue}),
                new double[]{previousSar, sarValue});
        return StrategyEvaluator.evaluate(rules, window, indicators)[1];
    }
}
