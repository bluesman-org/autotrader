package nl.jimkaplan.autotrader.strategy.controller;

import nl.jimkaplan.autotrader.strategy.backtest.RankingMetric;
import nl.jimkaplan.autotrader.strategy.rule.LogicalOperator;
import nl.jimkaplan.autotrader.strategy.rule.RuleCondition;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BacktestRunFormTest {

    @Test
    void defaults_produceTheDocumentedRequest() {
        BacktestRunRequest request = BacktestRunForm.defaults().toRequest();

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        assertEquals("BTC-EUR", request.getMarket());
        assertEquals("1h", request.getInterval());
        assertEquals(epochMillis(today.minusDays(100)), request.getFromDate());
        assertEquals(epochMillis(today), request.getToDate());
        assertEquals(1000.0, request.getInitialCapital());
        assertEquals(100.0, request.getAmountPerBuy());
        assertEquals(0.002, request.getFeeRate());
        assertEquals(200, request.getWarmUpCandles());
        assertEquals(0.7, request.getTrainRatio());
        assertEquals(RankingMetric.TOTAL_RETURN, request.getRankingMetric());
        assertEquals(List.of(new MacdParametersRequest(12, 26, 9)), request.getMacdParameters());
        assertEquals(List.of(new SarParametersRequest(0.02, 0.2)), request.getSarParameters());
        assertEquals(List.of(new RuleSetRequest(LogicalOperator.ALL,
                List.of(RuleCondition.MACD_BULLISH_CROSSOVER))), request.getEntryRules());
        assertEquals(List.of(new RuleSetRequest(LogicalOperator.ALL,
                List.of(RuleCondition.SAR_BEARISH_CROSSOVER))), request.getExitRules());
    }

    @Test
    void toRequest_parsesCustomValuesAndCandidateGrids() {
        BacktestRunForm form = BacktestRunForm.defaults();
        form.setMarket("ETH-EUR");
        form.setInterval("4h");
        form.setFromDate("2026-01-01");
        form.setToDate("2026-03-01");
        form.setInitialCapital("500");
        form.setAmountPerBuy("50");
        form.setFeeRate("0.001");
        form.setWarmUpCandles("100");
        form.setTrainRatio("0.6");
        form.setRankingMetric("MAX_DRAWDOWN");
        form.setMacdCandidates("12/26/9, 3/5/2");
        form.setSarCandidates("0.02/0.2, 0.05/0.5");
        form.setEntryOperator("ANY");
        form.setEntryConditions(List.of("MACD_BULLISH_CROSSOVER", "PRICE_ABOVE_SAR"));
        form.setExitOperator("ANY");
        form.setExitConditions(List.of("SAR_BEARISH_CROSSOVER", "MACD_BEARISH_CROSSOVER"));

        BacktestRunRequest request = form.toRequest();

        assertEquals("ETH-EUR", request.getMarket());
        assertEquals("4h", request.getInterval());
        assertEquals(epochMillis(LocalDate.parse("2026-01-01")), request.getFromDate());
        assertEquals(epochMillis(LocalDate.parse("2026-03-01")), request.getToDate());
        assertEquals(500.0, request.getInitialCapital());
        assertEquals(50.0, request.getAmountPerBuy());
        assertEquals(0.001, request.getFeeRate());
        assertEquals(100, request.getWarmUpCandles());
        assertEquals(0.6, request.getTrainRatio());
        assertEquals(RankingMetric.MAX_DRAWDOWN, request.getRankingMetric());
        assertEquals(List.of(new MacdParametersRequest(12, 26, 9), new MacdParametersRequest(3, 5, 2)),
                request.getMacdParameters());
        assertEquals(List.of(new SarParametersRequest(0.02, 0.2), new SarParametersRequest(0.05, 0.5)),
                request.getSarParameters());
        assertEquals(List.of(new RuleSetRequest(LogicalOperator.ANY,
                List.of(RuleCondition.MACD_BULLISH_CROSSOVER, RuleCondition.PRICE_ABOVE_SAR))),
                request.getEntryRules());
        assertEquals(List.of(new RuleSetRequest(LogicalOperator.ANY,
                List.of(RuleCondition.SAR_BEARISH_CROSSOVER, RuleCondition.MACD_BEARISH_CROSSOVER))),
                request.getExitRules());
    }

    @Test
    void toRequest_leavesBlankOptionalsUnset() {
        BacktestRunForm form = BacktestRunForm.defaults();
        form.setInitialCapital(" ");
        form.setAmountPerBuy(null);
        form.setFeeRate("");
        form.setWarmUpCandles("");
        form.setTrainRatio(null);
        form.setRankingMetric(" ");
        form.setMacdCandidates("");
        form.setSarCandidates(null);
        form.setEntryOperator("");
        form.setExitOperator(null);

        BacktestRunRequest request = form.toRequest();

        assertNull(request.getInitialCapital());
        assertNull(request.getAmountPerBuy());
        assertNull(request.getFeeRate());
        assertNull(request.getWarmUpCandles());
        assertNull(request.getTrainRatio());
        assertNull(request.getRankingMetric());
        assertNull(request.getMacdParameters());
        assertNull(request.getSarParameters());
        // A blank operator combines with ALL
        assertEquals(LogicalOperator.ALL, request.getEntryRules().get(0).operator());
        assertEquals(LogicalOperator.ALL, request.getExitRules().get(0).operator());
    }

    @Test
    void toRequest_rejectsMalformedFields() {
        BacktestRunForm noMarket = BacktestRunForm.defaults();
        noMarket.setMarket(" ");
        assertMessage("the market is required", noMarket);

        BacktestRunForm badDate = BacktestRunForm.defaults();
        badDate.setFromDate("11-10-2026");
        assertMessage("the from date must be a date like 2026-10-11", badDate);

        BacktestRunForm badCapital = BacktestRunForm.defaults();
        badCapital.setInitialCapital("ten thousand");
        assertMessage("the initial capital must be a number", badCapital);

        BacktestRunForm badWarmUp = BacktestRunForm.defaults();
        badWarmUp.setWarmUpCandles("1.5");
        assertMessage("the warm-up candles must be a whole number", badWarmUp);

        BacktestRunForm badMetric = BacktestRunForm.defaults();
        badMetric.setRankingMetric("BEST");
        assertMessage("unknown ranking metric: BEST", badMetric);

        BacktestRunForm badMacdShape = BacktestRunForm.defaults();
        badMacdShape.setMacdCandidates("12/26");
        assertMessage("each MACD candidate reads fast/slow/signal, e.g. 12/26/9", badMacdShape);

        BacktestRunForm badMacdNumber = BacktestRunForm.defaults();
        badMacdNumber.setMacdCandidates("a/26/9");
        assertMessage("the MACD fast period must be a whole number", badMacdNumber);

        BacktestRunForm badSarShape = BacktestRunForm.defaults();
        badSarShape.setSarCandidates("0.02");
        assertMessage("each Parabolic SAR candidate reads acceleration/maximum, e.g. 0.02/0.2", badSarShape);

        BacktestRunForm badSarNumber = BacktestRunForm.defaults();
        badSarNumber.setSarCandidates("a/0.2");
        assertMessage("the SAR acceleration must be a number", badSarNumber);

        BacktestRunForm noEntryConditions = BacktestRunForm.defaults();
        noEntryConditions.setEntryConditions(List.of());
        assertMessage("select at least one entry condition", noEntryConditions);

        BacktestRunForm noExitConditions = BacktestRunForm.defaults();
        noExitConditions.setExitConditions(List.of());
        assertMessage("select at least one exit condition", noExitConditions);

        BacktestRunForm unknownCondition = BacktestRunForm.defaults();
        unknownCondition.setEntryConditions(List.of("NOPE"));
        assertMessage("unknown rule condition: NOPE", unknownCondition);

        BacktestRunForm unknownOperator = BacktestRunForm.defaults();
        unknownOperator.setExitOperator("BOTH");
        assertMessage("unknown operator: BOTH", unknownOperator);
    }

    private static void assertMessage(String expected, BacktestRunForm form) {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, form::toRequest);
        assertEquals(expected, exception.getMessage());
    }

    private static long epochMillis(LocalDate date) {
        return date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
    }
}
