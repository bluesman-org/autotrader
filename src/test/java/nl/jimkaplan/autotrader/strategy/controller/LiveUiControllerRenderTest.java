package nl.jimkaplan.autotrader.strategy.controller;

import nl.jimkaplan.autotrader.strategy.service.LiveStrategyService;
import nl.jimkaplan.autotrader.strategy.service.LiveViewService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Renders the live page and its cards fragment, so a broken expression fails here instead of in
 * the browser. The property overrides stand in for the environment variables the application.yml
 * placeholders expect, which a test run does not have; the display zone is pinned so the rendered
 * times are deterministic.
 */
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

    @Test
    void pageRendersARunningCardWithItsPositionAndOrders() throws Exception {
        when(liveViewService.cards()).thenReturn(List.of(card(true)));
        when(liveViewService.zoneLabel()).thenReturn("UTC");

        mockMvc.perform(get("/ui/live"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Live engine")))
                .andExpect(content().string(containsString("Order times are local time (UTC)")))
                .andExpect(content().string(containsString("id=\"last-refresh\"")))
                .andExpect(content().string(containsString("BTC-EUR · 1m")))
                .andExpect(content().string(containsString("6ace5dbf")))
                .andExpect(content().string(containsString("● Running")))
                .andExpect(content().string(containsString("DRY RUN")))
                .andExpect(content().string(containsString("ui/live/cards")))
                .andExpect(content().string(containsString("If closed now (after fees)")))
                .andExpect(content().string(containsString("−€0.19")))
                .andExpect(content().string(containsString("Realised since start: 36 round trips · −€14.25")))
                .andExpect(content().string(containsString("opened position")))
                .andExpect(content().string(containsString("Skipped — candle unavailable")))
                .andExpect(content().string(containsString(">Stop<")));
    }

    @Test
    void pageRendersAStoppedCardWithoutAPosition() throws Exception {
        when(liveViewService.cards()).thenReturn(List.of(card(false)));
        when(liveViewService.zoneLabel()).thenReturn("UTC");

        mockMvc.perform(get("/ui/live"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Stopped")))
                .andExpect(content().string(containsString("No open position.")))
                .andExpect(content().string(containsString(">Start<")));
    }

    @Test
    void pageShowsTheEmptyStateWithoutStrategies() throws Exception {
        when(liveViewService.cards()).thenReturn(List.of());
        when(liveViewService.zoneLabel()).thenReturn("UTC");

        mockMvc.perform(get("/ui/live"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("No live strategies yet")));
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

    private static LiveCardView card(boolean running) {
        LiveCardView.PositionView position = running
                ? new LiveCardView.PositionView("LONG 0.00134403 BTC", "14 Oct 14:45 · €74,403.00",
                "€74,578.00", "€74,562.00", "€100.21", "−€0.19", "neg", "€0.20")
                : null;
        List<LiveCardView.OrderView> orders = running
                ? List.of(new LiveCardView.OrderView("14:46", "SELL", "0.00134403", "€74,412.00",
                        "DRY RUN", "dry", "+€0.12", "pos", false),
                new LiveCardView.OrderView("14:45", "BUY", "0.00134403", "€74,403.00",
                        "DRY RUN", "dry", "—", "", true),
                new LiveCardView.OrderView("14:48", "SKIP", "—", "—",
                        "Skipped — candle unavailable", "", "—", "", false))
                : List.of();
        return new LiveCardView("6ace5dbf7b4e0870883058cb", "BTC-EUR", "1m", running, running, true,
                "MACD 12/26/9 · SAR 0.02/0.2 · entry: MACD bullish crossover · exit: SAR bearish "
                        + "crossover · €100 per buy · warm-up 60 bars",
                running
                        ? "Running since 13 Oct 16:35 (1 d 22 h) · Last evaluated 14 Oct 14:48:00 "
                        + "(12 s ago) · Last error: —"
                        : "Created 14 Oct 09:20 · Last evaluated 14 Oct 12:20:00 · Last error: —",
                position,
                "Realised since start: 36 round trips · −€14.25 · 8 wins / 28 losses · avg win "
                        + "+€0.35 · avg loss −€0.61",
                orders);
    }
}
