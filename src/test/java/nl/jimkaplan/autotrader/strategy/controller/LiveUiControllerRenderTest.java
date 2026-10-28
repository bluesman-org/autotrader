package nl.jimkaplan.autotrader.strategy.controller;

import nl.jimkaplan.autotrader.service.MarketService;
import nl.jimkaplan.autotrader.strategy.model.document.BacktestRun;
import nl.jimkaplan.autotrader.strategy.model.document.BacktestRunEntry;
import nl.jimkaplan.autotrader.strategy.model.document.LiveStrategy;
import nl.jimkaplan.autotrader.strategy.service.BacktestService;
import nl.jimkaplan.autotrader.strategy.service.LiveStrategyService;
import nl.jimkaplan.autotrader.strategy.service.LiveViewService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Renders the live page and its cards fragment, so a broken expression fails here instead of in
 * the browser. The property overrides stand in for the environment variables the application.yml
 * placeholders expect, which a test run does not have; the display zone is pinned so the rendered
 * times are deterministic.
 */
@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(controllers = LiveUiController.class, properties = {
        "spring.data.mongodb.uri=mongodb://localhost:27017/autotrader-test",
        "bitvavo.api.url=http://localhost:9999",
        "encryption.master-key=dGVzdC1rZXk=",
        "autotrader.ui.time-zone=UTC"
})
class LiveUiControllerRenderTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LiveViewService liveViewService;

    @MockitoBean
    private LiveStrategyService liveStrategyService;

    @MockitoBean
    private BacktestService backtestService;

    @MockitoBean
    private MarketService marketService;

    @Test
    void pageRendersARunningCardWithItsPositionAndOrders() throws Exception {
        when(liveViewService.cards()).thenReturn(List.of(card(true)));
        when(liveViewService.zoneLabel()).thenReturn("UTC");
        when(liveViewService.zoneId()).thenReturn("UTC");

        mockMvc.perform(get("/ui/live"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Live engine")))
                .andExpect(content().string(containsString("How the live engine works")))
                .andExpect(content().string(containsString("href=\"/ui/help#live\"")))
                .andExpect(content().string(containsString("Order times are local time (UTC)")))
                .andExpect(content().string(containsString("id=\"last-refresh\"")))
                .andExpect(content().string(containsString("data-time-zone=\"UTC\"")))
                .andExpect(content().string(containsString("BTC-EUR · 1m")))
                .andExpect(content().string(containsString("BTC 1m")))
                .andExpect(content().string(containsString("6ace5dbf")))
                .andExpect(content().string(containsString("● Running")))
                .andExpect(content().string(containsString("Create real copy")))
                .andExpect(content().string(containsString("Duplicate")))
                .andExpect(content().string(containsString("DRY RUN")))
                .andExpect(content().string(containsString("ui/live/cards")))
                .andExpect(content().string(containsString("If closed now (after fees)")))
                // The parameter line reads in the page's number style, like the fixture expects.
                .andExpect(content().string(containsString("SAR 0,02/0,2")))
                .andExpect(content().string(containsString("−€0,19")))
                .andExpect(content().string(containsString("Realised since start: 36 round trips · −€14,25")))
                .andExpect(content().string(containsString("opened position")))
                .andExpect(content().string(containsString("Skipped — candle unavailable")))
                .andExpect(content().string(containsString(">Stop<")));
    }

    @Test
    void pageRendersAStoppedCardWithoutAPosition() throws Exception {
        when(liveViewService.cards()).thenReturn(List.of(card(false)));
        when(liveViewService.zoneLabel()).thenReturn("UTC");
        when(liveViewService.zoneId()).thenReturn("UTC");

        mockMvc.perform(get("/ui/live"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Stopped")))
                .andExpect(content().string(containsString("No open position.")))
                .andExpect(content().string(containsString(">Edit<")))
                .andExpect(content().string(containsString(">Archive<")))
                .andExpect(content().string(containsString(">Start<")));
    }

    @Test
    void pageShowsTheEmptyStateWithoutStrategies() throws Exception {
        when(liveViewService.cards()).thenReturn(List.of());
        when(liveViewService.zoneLabel()).thenReturn("UTC");
        when(liveViewService.zoneId()).thenReturn("UTC");

        mockMvc.perform(get("/ui/live"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("No live strategies yet")));
    }

    @Test
    void pageOffersDeleteForANeverStartedStrategy() throws Exception {
        when(liveViewService.cards()).thenReturn(List.of(card(false, false)));
        when(liveViewService.zoneLabel()).thenReturn("UTC");
        when(liveViewService.zoneId()).thenReturn("UTC");

        mockMvc.perform(get("/ui/live"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(">Delete<")))
                .andExpect(content().string(not(containsString(">Archive<"))));
    }

    @Test
    void cardsEndpointRendersTheFragmentAlone() throws Exception {
        when(liveViewService.cards()).thenReturn(List.of(card(true)));

        mockMvc.perform(get("/ui/live/cards"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("BTC-EUR · 1m")))
                .andExpect(content().string(not(containsString("Live engine"))));
    }

    @Test
    void aStoppedPositionOffersCloseAndOnlyARealOneOffersReconcile() throws Exception {
        when(liveViewService.zoneLabel()).thenReturn("UTC");
        when(liveViewService.zoneId()).thenReturn("UTC");

        when(liveViewService.cards()).thenReturn(List.of(stoppedCard(true, true)));
        mockMvc.perform(get("/ui/live"))
                .andExpect(content().string(containsString("Close position")))
                .andExpect(content().string(containsString("Close the position before deleting or archiving")))
                .andExpect(content().string(not(containsString(">Delete<"))))
                .andExpect(content().string(not(containsString(">Archive<"))))
                .andExpect(content().string(not(containsString("Reconcile manual close"))));

        when(liveViewService.cards()).thenReturn(List.of(stoppedCard(false, true)));
        mockMvc.perform(get("/ui/live"))
                .andExpect(content().string(containsString("Close position")))
                .andExpect(content().string(containsString("Close the position before deleting or archiving")))
                .andExpect(content().string(not(containsString(">Delete<"))))
                .andExpect(content().string(not(containsString(">Archive<"))))
                .andExpect(content().string(containsString("Reconcile manual close")));

        when(liveViewService.cards()).thenReturn(List.of(stoppedCard(false, false)));
        mockMvc.perform(get("/ui/live"))
                .andExpect(content().string(not(containsString("Close position"))))
                .andExpect(content().string(not(containsString("Reconcile manual close"))))
                .andExpect(content().string(containsString(">Archive<")));
    }

    @Test
    void closePageRendersThePositionAndTheEstimate() throws Exception {
        when(liveStrategyService.closePreview("strategy-1")).thenReturn(new LiveCloseView(
                "strategy-1", "BTC 2h", "BTC-EUR", "2h", false, true,
                "LONG 0,00100000 BTC", "20 Oct 2026 16:00 · €73.000,00", "€0,20",
                "€73.400,00", "€73,40", "€0,15", "+€0,05", "pos",
                "This places a real market sell of the position at market."));

        mockMvc.perform(get("/ui/live/strategy-1/close"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Close position")))
                .andExpect(content().string(containsString("Estimated result after fees")))
                .andExpect(content().string(containsString("+€0,05")))
                .andExpect(content().string(containsString("Sell at market and close")))
                .andExpect(content().string(containsString("href=\"/ui/help#positions\"")));
    }

    @Test
    void reconcilePageRendersTheExchangeSells() throws Exception {
        when(liveStrategyService.reconcileView("strategy-1")).thenReturn(new ReconcileView(
                "strategy-1", "BTC 2h", "BTC-EUR", "2h", false,
                "LONG 0,00100000 BTC", "20 Oct 2026 16:00 · €73.000,00", "€0,20",
                List.of(new ExternalCloseView("order-1", "21 Oct 2026 14:00 (UTC)",
                        "0,00100000", "€73.400,00", "€0,15", "€73,40", true,
                        "covers the position")), null));

        mockMvc.perform(get("/ui/live/strategy-1/reconcile"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Reconcile a manual close")))
                .andExpect(content().string(containsString("Use this sell")))
                .andExpect(content().string(containsString("21 Oct 2026 14:00 (UTC)")))
                .andExpect(content().string(containsString("covers the position")))
                .andExpect(content().string(containsString("href=\"/ui/help#positions\"")));
    }

    @Test
    void closePageRedirectsWhenTheServiceRefuses() throws Exception {
        when(liveStrategyService.closePreview("strategy-1"))
                .thenThrow(new IllegalArgumentException("stop the strategy before closing its position"));

        mockMvc.perform(get("/ui/live/strategy-1/close"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/ui/live"))
                .andExpect(flash().attribute("error", "stop the strategy before closing its position"));
    }

    @Test
    void reconcilePageRedirectsWhenBitvavoCannotBeRead() throws Exception {
        when(liveStrategyService.reconcileView("strategy-1"))
                .thenThrow(new org.springframework.web.client.RestClientException("connect timed out"));

        mockMvc.perform(get("/ui/live/strategy-1/reconcile"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/ui/live"))
                .andExpect(flash().attribute("error", containsString("Bitvavo could not be reached")));
    }

    @Test
    void newFormRendersTheDefaults() throws Exception {
        when(marketService.selectable()).thenReturn(List.of("BTC-EUR", "ETH-EUR"));

        mockMvc.perform(get("/ui/live/new"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("New live strategy")))
                .andExpect(content().string(containsString("BTC-EUR")))
                .andExpect(content().string(containsString("Create strategy")))
                .andExpect(content().string(containsString("href=\"/ui/help#strategies\"")));
    }

    @Test
    void newFormPrefillsADryAndARealCopyFromAStrategy() throws Exception {
        when(liveStrategyService.get("strategy-1")).thenReturn(Optional.of(strategy()));
        when(marketService.selectable()).thenReturn(List.of("BTC-EUR"));

        mockMvc.perform(get("/ui/live/new").param("copy", "strategy-1"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("BTC 1m copy")));

        mockMvc.perform(get("/ui/live/new").param("copy", "strategy-1").param("real", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("BTC 1m-REAL")))
                .andExpect(content().string(containsString("send real orders")));
    }

    @Test
    void newFormPrefillsFromARankedBacktestEntry() throws Exception {
        BacktestRunEntry entry = BacktestRunEntry.builder()
                .fastPeriod(3).slowPeriod(5).signalPeriod(2)
                .acceleration(0.01).maximum(0.1)
                .entryOperator("ANY").entryConditions(List.of("PRICE_ABOVE_SAR"))
                .exitOperator("ALL").exitConditions(List.of("PRICE_BELOW_SAR"))
                .build();
        BacktestRun run = BacktestRun.builder()
                .market("ETH-EUR").interval("4h").amountPerBuy(50).warmUpCandles(150)
                .entries(List.of(entry)).build();
        run.setId("run-1");
        when(backtestService.get("run-1")).thenReturn(Optional.of(run));
        when(marketService.selectable()).thenReturn(List.of("ETH-EUR"));

        mockMvc.perform(get("/ui/live/new").param("run", "run-1").param("entry", "1"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("ETH-EUR")))
                .andExpect(content().string(containsString("PRICE ABOVE SAR")))
                .andExpect(content().string(containsString("150")));
    }

    @Test
    void createPostsTheFormAndRedirects() throws Exception {
        when(liveStrategyService.create(any())).thenReturn(strategy());

        mockMvc.perform(post("/ui/live")
                        .param("name", "BTC 1h")
                        .param("market", "BTC-EUR")
                        .param("interval", "1h")
                        .param("amountPerBuy", "100")
                        .param("warmUpCandles", "200")
                        .param("macdFastPeriod", "12")
                        .param("macdSlowPeriod", "26")
                        .param("macdSignalPeriod", "9")
                        .param("sarAcceleration", "0.02")
                        .param("sarMaximum", "0.2")
                        .param("dryRun", "true")
                        .param("entryOperator", "ALL")
                        .param("entryConditions", "MACD_BULLISH_CROSSOVER")
                        .param("exitOperator", "ALL")
                        .param("exitConditions", "SAR_BEARISH_CROSSOVER"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/ui/live"));

        verify(liveStrategyService).create(any());
    }

    @Test
    void createRendersTheValidationErrorOnTheForm() throws Exception {
        when(marketService.selectable()).thenReturn(List.of("BTC-EUR"));

        mockMvc.perform(post("/ui/live")
                        .param("name", " ")
                        .param("market", "BTC-EUR")
                        .param("interval", "1h")
                        .param("entryOperator", "ALL")
                        .param("entryConditions", "MACD_BULLISH_CROSSOVER")
                        .param("exitOperator", "ALL")
                        .param("exitConditions", "SAR_BEARISH_CROSSOVER"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("the name is required")));
    }

    @Test
    void editFormShowsTheStoredStrategyAndTheFixedPairHint() throws Exception {
        when(liveStrategyService.get("strategy-1")).thenReturn(Optional.of(strategy()));
        when(marketService.selectable()).thenReturn(List.of("BTC-EUR"));

        mockMvc.perform(get("/ui/live/strategy-1/edit"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Edit strategy")))
                .andExpect(content().string(containsString("BTC 1m")))
                .andExpect(content().string(containsString("fixed after the first start")))
                .andExpect(content().string(containsString("Save changes")));
    }

    @Test
    void editFormWarnsWhenTheStrategyIsRunning() throws Exception {
        when(liveStrategyService.get("strategy-1")).thenReturn(Optional.of(strategy()));
        when(liveStrategyService.isRunning("strategy-1")).thenReturn(true);
        when(marketService.selectable()).thenReturn(List.of("BTC-EUR"));

        mockMvc.perform(get("/ui/live/strategy-1/edit"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Stop the strategy before editing it.")));
    }

    @Test
    void updatePostsTheFormAndRedirects() throws Exception {
        when(liveStrategyService.get("strategy-1")).thenReturn(Optional.of(strategy()));

        mockMvc.perform(post("/ui/live/strategy-1")
                        .param("name", "Renamed")
                        .param("entryOperator", "ALL")
                        .param("entryConditions", "MACD_BULLISH_CROSSOVER")
                        .param("exitOperator", "ALL")
                        .param("exitConditions", "SAR_BEARISH_CROSSOVER"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/ui/live"));

        verify(liveStrategyService).update(eq("strategy-1"), any());
    }

    @Test
    void deletePostsAndRedirectsWithTheOutcome() throws Exception {
        when(liveStrategyService.delete("strategy-1")).thenReturn(true);

        mockMvc.perform(post("/ui/live/strategy-1/delete"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/ui/live"))
                .andExpect(flash().attribute("notice", containsString("archived")));

        verify(liveStrategyService).delete("strategy-1");
    }

    @Test
    void startAndStopPostThenRedirectBackToThePage() throws Exception {
        mockMvc.perform(post("/ui/live/6ace5dbf7b4e0870883058cb/start"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/ui/live"));
        verify(liveStrategyService).start("6ace5dbf7b4e0870883058cb");

        mockMvc.perform(post("/ui/live/6ace5dbf7b4e0870883058cb/stop"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/ui/live"));
        verify(liveStrategyService).stop("6ace5dbf7b4e0870883058cb");
    }

    private static LiveStrategy strategy() {
        LiveStrategy strategy = LiveStrategy.builder()
                .name("BTC 1m").market("BTC-EUR").interval("1m")
                .macdFastPeriod(12).macdSlowPeriod(26).macdSignalPeriod(9)
                .sarAcceleration(0.02).sarMaximum(0.2)
                .entryOperator("ALL").entryConditions(List.of("MACD_BULLISH_CROSSOVER"))
                .exitOperator("ALL").exitConditions(List.of("SAR_BEARISH_CROSSOVER"))
                .amountPerBuy(100).warmUpCandles(60).dryRun(true)
                .createdTime(Instant.parse("2026-10-13T14:35:00Z"))
                .lastStartedTime(Instant.parse("2026-10-13T14:35:00Z"))
                .build();
        strategy.setId("strategy-1");
        return strategy;
    }

    private static LiveCardView stoppedCard(boolean dryRun, boolean withPosition) {
        LiveCardView.PositionView position = withPosition
                ? new LiveCardView.PositionView("LONG 0,00136090 BTC", "20 Oct 18:00 · €73.481,00",
                "€73.500,00", "€73.400,00", "€99,84", "−€0,15", "neg", "€0,20")
                : null;
        return new LiveCardView("strategy-1", "BTC 2h", "BTC-EUR", "2h", true,
                false, false, dryRun,
                "MACD 12/26/9 · SAR 0,02/0,2 · entry: MACD bullish crossover · exit: SAR bearish "
                        + "crossover · €100 per buy · warm-up 60 bars",
                "Created 14 Oct 09:20 · Last evaluated 20 Oct 18:00:00 · Last error: —",
                position, "No completed round trips yet.", List.of());
    }

    private static LiveCardView card(boolean running) {
        return card(running, true);
    }

    private static LiveCardView card(boolean running, boolean started) {
        LiveCardView.PositionView position = running
                ? new LiveCardView.PositionView("LONG 0,00134403 BTC", "14 Oct 14:45 · €74.403,00",
                "€74.578,00", "€74.562,00", "€100,21", "−€0,19", "neg", "€0,20")
                : null;
        List<LiveCardView.OrderView> orders = running
                ? List.of(new LiveCardView.OrderView("14:46", "SELL", "0.00134403", "€74.412,00",
                        "DRY RUN", "dry", "+€0,12", "pos", false),
                new LiveCardView.OrderView("14:45", "BUY", "0.00134403", "€74.403,00",
                        "DRY RUN", "dry", "—", "", true),
                new LiveCardView.OrderView("14:48", "SKIP", "—", "—",
                        "Skipped — candle unavailable", "", "—", "", false))
                : List.of();
        return new LiveCardView("6ace5dbf7b4e0870883058cb", "BTC 1m", "BTC-EUR", "1m", started,
                running, running, true,
                "MACD 12/26/9 · SAR 0,02/0,2 · entry: MACD bullish crossover · exit: SAR bearish "
                        + "crossover · €100 per buy · warm-up 60 bars",
                running
                        ? "Running since 13 Oct 16:35 (1 d 22 h) · Last evaluated 14 Oct 14:48:00 "
                        + "(12 s ago) · Last error: —"
                        : "Created 14 Oct 09:20 · Last evaluated 14 Oct 12:20:00 · Last error: —",
                position,
                "Realised since start: 36 round trips · −€14,25 · 8 wins / 28 losses · avg win "
                        + "+€0.35 · avg loss −€0,61",
                orders);
    }
}
