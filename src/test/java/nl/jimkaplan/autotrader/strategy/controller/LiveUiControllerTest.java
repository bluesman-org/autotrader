package nl.jimkaplan.autotrader.strategy.controller;

import nl.jimkaplan.autotrader.service.MarketService;
import nl.jimkaplan.autotrader.strategy.model.document.BacktestRun;
import nl.jimkaplan.autotrader.strategy.model.document.BacktestRunEntry;
import nl.jimkaplan.autotrader.strategy.model.document.LiveStrategy;
import nl.jimkaplan.autotrader.strategy.service.BacktestService;
import nl.jimkaplan.autotrader.strategy.service.LiveStrategyService;
import nl.jimkaplan.autotrader.strategy.service.LiveViewService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LiveUiControllerTest {

    @Mock
    private LiveViewService liveViewService;
    @Mock
    private LiveStrategyService liveStrategyService;
    @Mock
    private BacktestService backtestService;
    @Mock
    private MarketService marketService;

    @InjectMocks
    private LiveUiController controller;

    @Test
    void newFormCopiesAStrategyWithAndWithoutAName() {
        when(liveStrategyService.get("unnamed"))
                .thenReturn(Optional.of(strategy(null, "BTC-EUR", null, 0)));
        when(liveStrategyService.get("named"))
                .thenReturn(Optional.of(strategy("Breakout", "ETH-EUR", null, 0)));
        when(marketService.selectable()).thenReturn(List.of("BTC-EUR", "ETH-EUR"));

        ExtendedModelMap model = new ExtendedModelMap();
        assertEquals("live/form", controller.newForm(null, null, "unnamed", false, model));
        assertEquals("BTC-EUR 1h copy", form(model).getName());
        assertTrue(form(model).getDryRun());

        ExtendedModelMap real = new ExtendedModelMap();
        assertEquals("live/form", controller.newForm(null, null, "named", true, real));
        assertEquals("Breakout-REAL", form(real).getName());
        assertFalse(form(real).getDryRun());
    }

    @Test
    void newFormFallsBackToTheDefaultsForBlankCopyAndRun() {
        when(marketService.selectable()).thenReturn(List.of("BTC-EUR"));

        ExtendedModelMap model = new ExtendedModelMap();

        assertEquals("live/form", controller.newForm(" ", null, " ", false, model));
        assertEquals("", form(model).getName());
        assertEquals("BTC-EUR", form(model).getMarket());
    }

    @Test
    void newFormPrefillsFromABacktestAndDefaultsAMissingEntryNumber() {
        BacktestRun run = BacktestRun.builder()
                .market("ETH-EUR").interval("4h").amountPerBuy(50).warmUpCandles(150)
                .entries(List.of(entry())).build();
        run.setId("run-1");
        when(backtestService.get("run-1")).thenReturn(Optional.of(run));
        when(marketService.selectable()).thenReturn(List.of("ETH-EUR"));

        ExtendedModelMap model = new ExtendedModelMap();

        assertEquals("live/form", controller.newForm("run-1", null, null, false, model));
        assertEquals("ETH-EUR", form(model).getMarket());
        assertEquals("50", form(model).getAmountPerBuy());
    }

    @Test
    void newFormHandlesAMissingCopyRunOrEntry() {
        assertThrows(NoSuchElementException.class,
                () -> controller.newForm(null, null, "missing", false, new ExtendedModelMap()));

        when(backtestService.get("missing")).thenReturn(Optional.empty());
        assertThrows(NoSuchElementException.class,
                () -> controller.newForm("missing", 1, null, false, new ExtendedModelMap()));

        BacktestRun empty = BacktestRun.builder().market("ETH-EUR").interval("4h")
                .entries(List.of()).build();
        when(backtestService.get("empty")).thenReturn(Optional.of(empty));
        assertThrows(IllegalArgumentException.class,
                () -> controller.newForm("empty", 1, null, false, new ExtendedModelMap()));
    }

    @Test
    void newFormHandlesANullMarketAndAnEmptyCatalogue() {
        BacktestRun run = BacktestRun.builder()
                .market(null).interval("1h").entries(List.of(entry())).build();
        run.setId("run-null");
        when(backtestService.get("run-null")).thenReturn(Optional.of(run));
        when(marketService.selectable()).thenReturn(List.of());

        ExtendedModelMap empty = new ExtendedModelMap();
        assertEquals("live/form", controller.newForm("run-null", 1, null, false, empty));
        assertNull(form(empty).getMarket());

        when(marketService.selectable()).thenReturn(List.of("BTC-EUR"));
        ExtendedModelMap filled = new ExtendedModelMap();
        assertEquals("live/form", controller.newForm("run-null", 1, null, false, filled));
        assertEquals("BTC-EUR", form(filled).getMarket());
    }

    @Test
    void newFormDefaultsToTheFirstCataloguePairWhenTheDefaultIsMissing() {
        when(marketService.selectable()).thenReturn(List.of("ETH-EUR"));

        ExtendedModelMap model = new ExtendedModelMap();

        assertEquals("live/form", controller.newForm(null, null, null, false, model));
        assertEquals("ETH-EUR", form(model).getMarket());
    }

    @Test
    void newFormKeepsAPrefilledMarketThatIsNotInTheCatalogue() {
        when(liveStrategyService.get("sol")).thenReturn(Optional.of(strategy("SOL", "SOL-EUR", null, 0)));
        when(marketService.selectable()).thenReturn(List.of("BTC-EUR"));

        ExtendedModelMap model = new ExtendedModelMap();

        assertEquals("live/form", controller.newForm(null, null, "sol", false, model));
        assertEquals("SOL-EUR", form(model).getMarket());
        assertTrue(((List<?>) model.getAttribute("markets")).contains("SOL-EUR"));
    }

    @Test
    void newFormReplacesABlankCopiedMarketWithTheFirstCataloguePair() {
        when(liveStrategyService.get("blank")).thenReturn(Optional.of(strategy("X", " ", null, 0)));
        when(marketService.selectable()).thenReturn(List.of("BTC-EUR"));

        ExtendedModelMap model = new ExtendedModelMap();

        assertEquals("live/form", controller.newForm(null, null, "blank", false, model));
        assertEquals("BTC-EUR", form(model).getMarket());
    }

    @Test
    void editFormMarksAStrategyAsStartedThroughTheAnchorOrTheLastStart() {
        when(marketService.selectable()).thenReturn(List.of("BTC-EUR"));
        when(liveStrategyService.get("fresh")).thenReturn(Optional.of(strategy("Fresh", "BTC-EUR", null, 0)));
        when(liveStrategyService.get("anchored")).thenReturn(Optional.of(strategy("Old", "BTC-EUR", null, 1)));
        when(liveStrategyService.get("started"))
                .thenReturn(Optional.of(strategy("Started", "BTC-EUR", Instant.now(), 0)));

        ExtendedModelMap fresh = new ExtendedModelMap();
        assertEquals("live/form", controller.editForm("fresh", fresh));
        assertFalse((Boolean) fresh.getAttribute("started"));

        ExtendedModelMap anchored = new ExtendedModelMap();
        assertEquals("live/form", controller.editForm("anchored", anchored));
        assertTrue((Boolean) anchored.getAttribute("started"));

        ExtendedModelMap started = new ExtendedModelMap();
        assertEquals("live/form", controller.editForm("started", started));
        assertTrue((Boolean) started.getAttribute("started"));
    }

    @Test
    void updateRendersTheServiceErrorOnTheForm() {
        LiveStrategy strategy = strategy("BTC", "BTC-EUR", null, 0);
        when(liveStrategyService.get("strategy-1")).thenReturn(Optional.of(strategy));
        when(liveStrategyService.update(eq("strategy-1"), any()))
                .thenThrow(new IllegalArgumentException("stop the strategy before editing it"));
        when(marketService.selectable()).thenReturn(List.of("BTC-EUR"));
        LiveStrategyForm form = LiveStrategyForm.defaults();
        form.setName("BTC");
        ExtendedModelMap model = new ExtendedModelMap();

        assertEquals("live/form", controller.update("strategy-1", form, model));

        assertEquals("stop the strategy before editing it", model.getAttribute("error"));
    }

    @Test
    void deleteReportsADeletionAndAToleratedStartFailure() {
        when(liveStrategyService.delete("strategy-1")).thenReturn(false);
        RedirectAttributesModelMap attributes = new RedirectAttributesModelMap();

        assertEquals("redirect:/ui/live", controller.delete("strategy-1", attributes));
        assertTrue(attributes.getFlashAttributes().get("notice").toString().contains("deleted"));

        doThrow(new IllegalStateException("boom")).when(liveStrategyService).start("strategy-1");
        assertEquals("redirect:/ui/live", controller.start("strategy-1"));
    }

    private static LiveStrategyForm form(ExtendedModelMap model) {
        return (LiveStrategyForm) model.getAttribute("form");
    }

    private static LiveStrategy strategy(String name, String market, Instant started, long anchor) {
        LiveStrategy strategy = LiveStrategy.builder()
                .name(name).market(market).interval("1h")
                .macdFastPeriod(12).macdSlowPeriod(26).macdSignalPeriod(9)
                .sarAcceleration(0.02).sarMaximum(0.2)
                .entryOperator("ALL").entryConditions(List.of("MACD_BULLISH_CROSSOVER"))
                .exitOperator("ALL").exitConditions(List.of("SAR_BEARISH_CROSSOVER"))
                .amountPerBuy(100).warmUpCandles(200).dryRun(true)
                .lastStartedTime(started).seriesStartTime(anchor)
                .build();
        strategy.setId("strategy-1");
        return strategy;
    }

    private static BacktestRunEntry entry() {
        return BacktestRunEntry.builder()
                .fastPeriod(3).slowPeriod(5).signalPeriod(2)
                .acceleration(0.01).maximum(0.1)
                .entryOperator("ALL").entryConditions(List.of("MACD_BULLISH_CROSSOVER"))
                .exitOperator("ALL").exitConditions(List.of("SAR_BEARISH_CROSSOVER"))
                .build();
    }
}
