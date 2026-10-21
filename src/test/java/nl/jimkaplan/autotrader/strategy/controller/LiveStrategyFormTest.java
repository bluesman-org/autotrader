package nl.jimkaplan.autotrader.strategy.controller;

import nl.jimkaplan.autotrader.strategy.model.document.BacktestRun;
import nl.jimkaplan.autotrader.strategy.model.document.BacktestRunEntry;
import nl.jimkaplan.autotrader.strategy.model.document.LiveStrategy;
import nl.jimkaplan.autotrader.strategy.rule.LogicalOperator;
import nl.jimkaplan.autotrader.strategy.rule.RuleCondition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveStrategyFormTest {

    @Test
    void defaultsMatchTheEngineDefaults() {
        LiveStrategyForm form = LiveStrategyForm.defaults();

        assertEquals("", form.getName());
        assertEquals("BTC-EUR", form.getMarket());
        assertEquals("1h", form.getInterval());
        assertEquals("100", form.getAmountPerBuy());
        assertEquals("200", form.getWarmUpCandles());
        assertTrue(form.getDryRun());
        assertEquals("12", form.getMacdFastPeriod());
        assertEquals("26", form.getMacdSlowPeriod());
        assertEquals("9", form.getMacdSignalPeriod());
        assertEquals("0.02", form.getSarAcceleration());
        assertEquals("0.2", form.getSarMaximum());
        assertEquals("ALL", form.getEntryOperator());
        assertEquals(List.of("MACD_BULLISH_CROSSOVER"), form.getEntryConditions());
        assertEquals("ALL", form.getExitOperator());
        assertEquals(List.of("SAR_BEARISH_CROSSOVER"), form.getExitConditions());
    }

    @Test
    void fromStrategyCopiesEveryField() {
        LiveStrategy strategy = LiveStrategy.builder()
                .name("BTC 2h").market("BTC-EUR").interval("2h")
                .amountPerBuy(250.5).warmUpCandles(15).dryRun(false)
                .macdFastPeriod(3).macdSlowPeriod(5).macdSignalPeriod(2)
                .sarAcceleration(0.01).sarMaximum(0.1)
                .entryOperator("ANY").entryConditions(List.of("PRICE_ABOVE_SAR"))
                .exitOperator("ALL").exitConditions(List.of("PRICE_BELOW_SAR"))
                .build();

        LiveStrategyForm form = LiveStrategyForm.fromStrategy(strategy);

        assertEquals("BTC 2h", form.getName());
        assertEquals("BTC-EUR", form.getMarket());
        assertEquals("2h", form.getInterval());
        assertEquals("250.5", form.getAmountPerBuy());
        assertEquals("15", form.getWarmUpCandles());
        assertFalse(form.getDryRun());
        assertEquals("3", form.getMacdFastPeriod());
        assertEquals("5", form.getMacdSlowPeriod());
        assertEquals("2", form.getMacdSignalPeriod());
        assertEquals("0.01", form.getSarAcceleration());
        assertEquals("0.1", form.getSarMaximum());
        assertEquals("ANY", form.getEntryOperator());
        assertEquals(List.of("PRICE_ABOVE_SAR"), form.getEntryConditions());
        assertEquals("ALL", form.getExitOperator());
        assertEquals(List.of("PRICE_BELOW_SAR"), form.getExitConditions());
    }

    @Test
    void fromBacktestCopiesTheRunAndTheEntry() {
        BacktestRunEntry entry = BacktestRunEntry.builder()
                .fastPeriod(3).slowPeriod(5).signalPeriod(2)
                .acceleration(0.01).maximum(0.1)
                .entryOperator("ANY").entryConditions(List.of("PRICE_ABOVE_SAR"))
                .exitOperator("ALL").exitConditions(List.of("PRICE_BELOW_SAR"))
                .build();
        BacktestRun run = BacktestRun.builder()
                .market("ETH-EUR").interval("4h").amountPerBuy(50).warmUpCandles(150)
                .entries(List.of(entry)).build();

        LiveStrategyForm form = LiveStrategyForm.fromBacktest(run, entry);

        assertEquals("", form.getName());
        assertEquals("ETH-EUR", form.getMarket());
        assertEquals("4h", form.getInterval());
        assertEquals("50", form.getAmountPerBuy());
        assertEquals("150", form.getWarmUpCandles());
        assertTrue(form.getDryRun());
        assertEquals("3", form.getMacdFastPeriod());
        assertEquals("5", form.getMacdSlowPeriod());
        assertEquals("2", form.getMacdSignalPeriod());
        assertEquals("0.01", form.getSarAcceleration());
        assertEquals("0.1", form.getSarMaximum());
        assertEquals("ANY", form.getEntryOperator());
        assertEquals(List.of("PRICE_ABOVE_SAR"), form.getEntryConditions());
        assertEquals("ALL", form.getExitOperator());
        assertEquals(List.of("PRICE_BELOW_SAR"), form.getExitConditions());
    }

    @Test
    void toRequestBuildsTheApiRequest() {
        LiveStrategyForm form = LiveStrategyForm.defaults();
        form.setName(" BTC 1h ");
        form.setMarket(" BTC-EUR ");
        form.setInterval(" 1h ");
        form.setAmountPerBuy("150.5");
        form.setWarmUpCandles("10");
        form.setDryRun(false);
        form.setMacdFastPeriod("3");
        form.setMacdSlowPeriod("5");
        form.setMacdSignalPeriod("2");
        form.setSarAcceleration("0,01");
        form.setSarMaximum("0,1");
        form.setEntryOperator("ANY");
        form.setEntryConditions(List.of("PRICE_ABOVE_SAR"));
        form.setExitOperator("ALL");
        form.setExitConditions(List.of("PRICE_BELOW_SAR"));

        LiveStrategyRequest request = form.toRequest();

        assertEquals("BTC 1h", request.getName());
        assertEquals("BTC-EUR", request.getMarket());
        assertEquals("1h", request.getInterval());
        assertEquals(150.5, request.getAmountPerBuy(), 1e-9);
        assertEquals(10, request.getWarmUpCandles());
        assertFalse(request.getDryRun());
        assertEquals(3, request.getMacdParameters().fastPeriod());
        assertEquals(5, request.getMacdParameters().slowPeriod());
        assertEquals(2, request.getMacdParameters().signalPeriod());
        assertEquals(0.01, request.getSarParameters().acceleration(), 1e-9);
        assertEquals(0.1, request.getSarParameters().maximum(), 1e-9);
        assertEquals(LogicalOperator.ANY, request.getEntryRules().operator());
        assertEquals(List.of(RuleCondition.PRICE_ABOVE_SAR), request.getEntryRules().conditions());
        assertEquals(LogicalOperator.ALL, request.getExitRules().operator());
        assertEquals(List.of(RuleCondition.PRICE_BELOW_SAR), request.getExitRules().conditions());
    }

    @Test
    void toRequestLeavesBlankOptionalsOut() {
        LiveStrategyForm form = LiveStrategyForm.defaults();
        form.setName("BTC");
        form.setMarket(" ");
        form.setInterval(null);
        form.setAmountPerBuy(" ");
        form.setWarmUpCandles(null);
        form.setDryRun(null);
        form.setMacdFastPeriod("");
        form.setMacdSlowPeriod("");
        form.setMacdSignalPeriod("");
        form.setSarAcceleration(null);
        form.setSarMaximum(" ");

        LiveStrategyRequest request = form.toRequest();

        assertNull(request.getMarket());
        assertNull(request.getInterval());
        assertNull(request.getAmountPerBuy());
        assertNull(request.getWarmUpCandles());
        assertNull(request.getDryRun());
        assertNull(request.getMacdParameters());
        assertNull(request.getSarParameters());
    }

    @Test
    void toRequestRejectsMissingName() {
        LiveStrategyForm form = LiveStrategyForm.defaults();
        form.setName(" ");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, form::toRequest);

        assertEquals("the name is required", error.getMessage());
    }

    @Test
    void toRequestRejectsAPartialMacdOrSar() {
        LiveStrategyForm macd = LiveStrategyForm.defaults();
        macd.setName("BTC");
        macd.setMacdSlowPeriod(" ");
        assertEquals("the MACD slow period is required",
                assertThrows(IllegalArgumentException.class, macd::toRequest).getMessage());

        LiveStrategyForm sar = LiveStrategyForm.defaults();
        sar.setName("BTC");
        sar.setSarMaximum("");
        assertEquals("the SAR maximum is required",
                assertThrows(IllegalArgumentException.class, sar::toRequest).getMessage());
    }

    @Test
    void toRequestRejectsMalformedNumbers() {
        LiveStrategyForm integer = LiveStrategyForm.defaults();
        integer.setName("BTC");
        integer.setMacdFastPeriod("fast");
        assertEquals("the MACD fast period must be a whole number",
                assertThrows(IllegalArgumentException.class, integer::toRequest).getMessage());

        LiveStrategyForm decimal = LiveStrategyForm.defaults();
        decimal.setName("BTC");
        decimal.setSarAcceleration("high");
        assertEquals("the SAR acceleration must be a number",
                assertThrows(IllegalArgumentException.class, decimal::toRequest).getMessage());

        LiveStrategyForm warmUp = LiveStrategyForm.defaults();
        warmUp.setName("BTC");
        warmUp.setWarmUpCandles("many");
        assertEquals("the warm-up candles must be a whole number",
                assertThrows(IllegalArgumentException.class, warmUp::toRequest).getMessage());

        LiveStrategyForm amount = LiveStrategyForm.defaults();
        amount.setName("BTC");
        amount.setAmountPerBuy("lots");
        assertEquals("the amount per buy must be a number",
                assertThrows(IllegalArgumentException.class, amount::toRequest).getMessage());
    }

    @Test
    void toRequestRejectsMissingOrUnknownConditions() {
        LiveStrategyForm empty = LiveStrategyForm.defaults();
        empty.setName("BTC");
        empty.setEntryConditions(List.of());
        assertEquals("select at least one entry condition",
                assertThrows(IllegalArgumentException.class, empty::toRequest).getMessage());

        LiveStrategyForm unknown = LiveStrategyForm.defaults();
        unknown.setName("BTC");
        unknown.setExitConditions(List.of("NOT_A_CONDITION"));
        assertEquals("unknown rule condition: NOT_A_CONDITION",
                assertThrows(IllegalArgumentException.class, unknown::toRequest).getMessage());

        LiveStrategyForm operator = LiveStrategyForm.defaults();
        operator.setName("BTC");
        operator.setEntryOperator("SOME");
        assertEquals("unknown operator: SOME",
                assertThrows(IllegalArgumentException.class, operator::toRequest).getMessage());
    }
}
