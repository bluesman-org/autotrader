package nl.jimkaplan.autotrader.strategy.controller;

import nl.jimkaplan.autotrader.service.MarketService;
import nl.jimkaplan.autotrader.strategy.model.document.BacktestRun;
import nl.jimkaplan.autotrader.strategy.model.document.BacktestRunEntry;
import nl.jimkaplan.autotrader.strategy.model.document.LiveOrder;
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
import org.springframework.web.client.RestClientException;
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

    @Test
    void closePreviewRendersThePageAndRedirectsOnAStateError() {
        LiveCloseView view = closeView();
        when(liveStrategyService.closePreview("strategy-1")).thenReturn(view);
        ExtendedModelMap model = new ExtendedModelMap();

        assertEquals("live/close", controller.closePreview("strategy-1", model,
                new RedirectAttributesModelMap()));
        assertEquals(view, model.getAttribute("preview"));

        when(liveStrategyService.closePreview("strategy-2"))
                .thenThrow(new IllegalArgumentException("stop the strategy before closing its position"));
        RedirectAttributesModelMap attributes = new RedirectAttributesModelMap();
        assertEquals("redirect:/ui/live", controller.closePreview("strategy-2",
                new ExtendedModelMap(), attributes));
        assertEquals("stop the strategy before closing its position",
                attributes.getFlashAttributes().get("error"));
    }

    @Test
    void closePostsTheResultAndRedirects() {
        when(liveStrategyService.close("strategy-1"))
                .thenReturn(new LiveCloseResult(true, "FILLED", "order-1", "The position was closed."));
        RedirectAttributesModelMap attributes = new RedirectAttributesModelMap();

        assertEquals("redirect:/ui/live", controller.close("strategy-1", attributes));
        assertEquals("The position was closed.", attributes.getFlashAttributes().get("notice"));
    }

    @Test
    void reconcileFormRendersTheCandidatesAndRedirectsOnAStateError() {
        ReconcileView view = reconcileView();
        when(liveStrategyService.reconcileView("strategy-1")).thenReturn(view);
        ExtendedModelMap model = new ExtendedModelMap();

        assertEquals("live/reconcile", controller.reconcileForm("strategy-1", model,
                new RedirectAttributesModelMap()));
        assertEquals(view, model.getAttribute("reconcile"));

        when(liveStrategyService.reconcileView("strategy-2"))
                .thenThrow(new IllegalArgumentException("a dry-run position has no exchange order"));
        RedirectAttributesModelMap attributes = new RedirectAttributesModelMap();
        assertEquals("redirect:/ui/live", controller.reconcileForm("strategy-2",
                new ExtendedModelMap(), attributes));
        assertEquals("a dry-run position has no exchange order",
                attributes.getFlashAttributes().get("error"));
    }

    @Test
    void reconcilePostsTheChosenOrderAndReportsIt() {
        when(liveStrategyService.reconcileExternalClose("strategy-1", "order-1"))
                .thenReturn(LiveOrder.builder().strategyId("strategy-1").external(true).build());
        RedirectAttributesModelMap attributes = new RedirectAttributesModelMap();

        assertEquals("redirect:/ui/live", controller.reconcile("strategy-1", "order-1", attributes));
        assertTrue(attributes.getFlashAttributes().get("notice").toString().contains("external sell"));
    }

    @Test
    void closeReportsAPendingResultAndTheErrorPaths() {
        when(liveStrategyService.close("strategy-1"))
                .thenReturn(new LiveCloseResult(false, "PENDING", "order-1", "The sell is not filled yet."));
        RedirectAttributesModelMap pending = new RedirectAttributesModelMap();
        assertEquals("redirect:/ui/live", controller.close("strategy-1", pending));
        assertEquals("The sell is not filled yet.", pending.getFlashAttributes().get("error"));

        when(liveStrategyService.close("strategy-2"))
                .thenThrow(new RestClientException("connect timed out"));
        RedirectAttributesModelMap unreachable = new RedirectAttributesModelMap();
        assertEquals("redirect:/ui/live", controller.close("strategy-2", unreachable));
        assertTrue(unreachable.getFlashAttributes().get("error").toString()
                .contains("Bitvavo could not be reached"));

        when(liveStrategyService.close("strategy-3")).thenThrow(new RuntimeException("boom"));
        RedirectAttributesModelMap failed = new RedirectAttributesModelMap();
        assertEquals("redirect:/ui/live", controller.close("strategy-3", failed));
        assertTrue(failed.getFlashAttributes().get("error").toString().contains("The close failed"));
    }

    @Test
    void reconcileReportsItsErrorPaths() {
        when(liveStrategyService.reconcileExternalClose("strategy-1", "order-1"))
                .thenThrow(new IllegalArgumentException("That sell covers only part"));
        RedirectAttributesModelMap invalid = new RedirectAttributesModelMap();
        assertEquals("redirect:/ui/live", controller.reconcile("strategy-1", "order-1", invalid));
        assertEquals("That sell covers only part", invalid.getFlashAttributes().get("error"));

        when(liveStrategyService.reconcileExternalClose("strategy-2", "order-2"))
                .thenThrow(new RestClientException("connect timed out"));
        RedirectAttributesModelMap unreachable = new RedirectAttributesModelMap();
        assertEquals("redirect:/ui/live", controller.reconcile("strategy-2", "order-2", unreachable));
        assertTrue(unreachable.getFlashAttributes().get("error").toString()
                .contains("Bitvavo could not be reached"));

        when(liveStrategyService.reconcileExternalClose("strategy-3", "order-3"))
                .thenThrow(new RuntimeException("boom"));
        RedirectAttributesModelMap failed = new RedirectAttributesModelMap();
        assertEquals("redirect:/ui/live", controller.reconcile("strategy-3", "order-3", failed));
        assertTrue(failed.getFlashAttributes().get("error").toString()
                .contains("The manual close could not be recorded"));
    }

    @Test
    void reconcileFormReportsABitvavoFailure() {
        when(liveStrategyService.reconcileView("strategy-1"))
                .thenThrow(new RestClientException("connect timed out"));
        RedirectAttributesModelMap attributes = new RedirectAttributesModelMap();

        assertEquals("redirect:/ui/live", controller.reconcileForm("strategy-1",
                new ExtendedModelMap(), attributes));
        assertTrue(attributes.getFlashAttributes().get("error").toString()
                .contains("Bitvavo could not be reached"));
    }

    private static LiveCloseView closeView() {
        return new LiveCloseView("strategy-1", "BTC 2h", "BTC-EUR", "2h", false, true,
                "LONG 0,001 BTC", "entry", "fee", "bid", "proceeds", "fee", "profit", "pos",
                "warning");
    }

    private static ReconcileView reconcileView() {
        return new ReconcileView("strategy-1", "BTC 2h", "BTC-EUR", "2h", false,
                "LONG 0,001 BTC", "entry", "fee", List.of(), null);
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
